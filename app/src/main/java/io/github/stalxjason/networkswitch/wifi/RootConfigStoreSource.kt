package io.github.stalxjason.networkswitch.wifi

import android.util.Log
import io.github.stalxjason.networkswitch.isProbablyTruncated
import io.github.stalxjason.networkswitch.readAtMost
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Root 兜底通道：用 su 读出 WifiConfigStore.xml 并交给 [WifiConfigStoreParser]。
 *
 * 只在 Shizuku 通道失败、且用户显式开启「允许 Root 兜底」时才会走到这里。
 *
 * 关键点：`su -c cat 文件` 的输出必须**与等待并发读取**。
 * 该 XML 在保存了几十个网络后轻松超过管道缓冲区（约 64KB），
 * 若先 `waitFor` 再读，cat 会卡在写管道上永不退出，超时强杀后什么也拿不到。
 */
internal object RootConfigStoreSource {

    private const val TAG = "RootConfigStore"

    /** 按 Android 版本从新到旧排列；命中即用 */
    private val CANDIDATE_PATHS = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
        "/data/misc/apexconfig/com.android.wifi/WifiConfigStore.xml",
        "/data/misc/wifi/WifiConfigStore.xml"
    )

    private const val CAT_TIMEOUT_SECONDS = 10L

    /** 配置文件上限：几十条网络的真实文件通常 < 1MB，超过这个量级说明内容异常 */
    private const val MAX_CONFIG_CHARS = 4 * 1024 * 1024

    /** 依次尝试候选路径，返回第一份解析出网络的配置；全失败返回 null */
    fun read(): List<WifiConfigStoreParser.RawNetwork>? {
        // 本轮已经确认 su 用不了（无 su / 被拒 / 超时）就别再换路径重试，
        // 否则每个候选路径都会再弹一次授权框、再等一次超时
        if (suUnavailable) return null

        for (path in CANDIDATE_PATHS) {
            val xml = catAsRoot(path) ?: continue
            val parsed = runCatching { WifiConfigStoreParser.parse(xml) }
                .onFailure { Log.w(TAG, "解析失败: $path", it) }
                .getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return null
    }

    /**
     * `su` 有两套互不兼容的调用形态：
     *  - Magisk / SuperSU / KernelSU：`su -c "<cmd>"`
     *  - toybox su（AOSP 模拟器、部分精简 ROM）：只接受 `su <uid> [args]`，即 `su 0 sh -c "<cmd>"`
     * 只写死前者的话，后者会得到 "invalid uid/gid" 而永远失败。
     *
     * 关键：**只有 su 明确拒绝当前语法时才换形态**。超时（授权框没点）或启动失败（没 su / 没权限）
     * 直接放弃——否则同一个动作会在用户面前弹两次授权框、等两个超时。
     */
    private fun catAsRoot(path: String): String? {
        val forms = preferredForm?.let { listOf(it) + (SuForm.entries - it) } ?: SuForm.entries
        var sawWrongSyntax = false

        for (form in forms) {
            when (val result = runCat(form, path)) {
                is CatResult.Ok -> {
                    preferredForm = form
                    return result.text
                }

                CatResult.WrongSyntax -> sawWrongSyntax = true

                CatResult.Failed -> {
                    suUnavailable = true
                    return null
                }
            }
        }

        // 所有形态都被 su 拒绝：这台设备的 su 不属于任何一派，本次会话不再尝试
        if (sawWrongSyntax) suUnavailable = true
        return null
    }

    private fun runCat(form: SuForm, path: String): CatResult {
        val process = runCatching {
            ProcessBuilder(form.command(path))
                // su 的拒绝提示也走同一流，避免 stderr 写满反向阻塞
                .redirectErrorStream(true)
                .start()
        }.onFailure { Log.d(TAG, "启动 su 失败（${form.name}）: $path", it) }
            .getOrNull() ?: return CatResult.Failed

        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "root-config-cat").apply { isDaemon = true }
        }

        return try {
            val output = reader.submit(
                Callable { process.inputStream.bufferedReader().use { it.readAtMost(MAX_CONFIG_CHARS) } }
            )
            val text = output.get(CAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (text.isProbablyTruncated(MAX_CONFIG_CHARS)) {
                // 内容被截断：解析器只会保留完整的网络块，后面那些条会静默丢失，
                // 与其给出不完整的列表，不如当作本次读取失败
                Log.w(TAG, "配置文件超过 ${MAX_CONFIG_CHARS} 字符上限，放弃本次读取")
                return CatResult.Failed
            }

            if (text.contains(MARKER)) return CatResult.Ok(text)

            // 输出不是配置文件，多半是 su 自己的提示：据此判断是语法不对还是被拒
            Log.d(TAG, "su(${form.name}) 未返回配置: ${text.take(120)}")
            if (text.looksLikeSyntaxRejection()) CatResult.WrongSyntax else CatResult.Failed
        } catch (e: TimeoutException) {
            Log.d(TAG, "su cat 超时（${form.name}）: $path")
            CatResult.Failed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CatResult.Failed
        } catch (e: Exception) {
            Log.d(TAG, "su cat 失败: $path", e)
            CatResult.Failed
        } finally {
            // 先停读线程再关流：阻塞在 read 里的线程只有关流才会退出
            reader.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** su 的拒绝提示长这样：`su: invalid uid/gid '-c'` / `su: unknown option` / 用法说明 */
    private fun String.looksLikeSyntaxRejection(): Boolean {
        val lower = lowercase()
        return SYNTAX_REJECTION_MARKERS.any { lower.contains(it) }
    }

    /** 一次读取的结果，用来区分「该换形态」和「别重试了」 */
    private sealed interface CatResult {
        data class Ok(val text: String) : CatResult

        /** su 在，但不认识当前语法——值得换另一种形态再试一次 */
        data object WrongSyntax : CatResult

        /** 其它失败：没有 su、被拒、超时、内容不对——不重试 */
        data object Failed : CatResult
    }

    /** 两种 su 调用形态 */
    private enum class SuForm {
        /** Magisk / SuperSU / KernelSU */
        SHELL_FLAG {
            override fun command(path: String) = listOf("su", "-c", "cat \"$path\"")
        },

        /** toybox su：第一个参数是目标 uid */
        TARGET_UID {
            override fun command(path: String) = listOf("su", "0", "sh", "-c", "cat \"$path\"")
        };

        abstract fun command(path: String): List<String>
    }

    /** 记住已经成功过的形态，避免每个候选路径都白试一遍 */
    @Volatile
    private var preferredForm: SuForm? = null

    /** 本次进程内已确认 su 不可用，后续不再尝试（避免重复弹授权框） */
    @Volatile
    private var suUnavailable = false

    private val SYNTAX_REJECTION_MARKERS = listOf(
        "invalid uid/gid",
        "invalid uid",
        "unknown option",
        "unrecognized option",
        "invalid option",
        "usage:"
    )

    /** 用来确认读到的确实是配置文件而不是错误信息 */
    private const val MARKER = "WifiConfiguration"
}
