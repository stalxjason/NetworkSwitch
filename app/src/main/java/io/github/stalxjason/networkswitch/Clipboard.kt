package io.github.stalxjason.networkswitch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * 写入剪贴板。
 *
 * 不写 `EXTRA_IS_SENSITIVE`：本应用的定位就是让密码随手可见/可复制，
 * 标了敏感反而会让 Android 13+ 的剪贴板预览遮住内容，与产品的预期相反。
 * Android 13+ 系统会自带剪贴板指示器，所以不再额外弹 Toast。
 */
fun Context.copyTextToClipboard(
    text: String,
    label: String,
    @StringRes toastRes: Int = R.string.toast_copied
) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
    }
}
