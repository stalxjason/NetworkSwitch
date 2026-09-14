package io.github.stalxjason.networkswitch

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * 写入剪贴板。
 *
 * sensitive=true 时写入公开的 EXTRA_IS_SENSITIVE 键（API 34+ 生效，
 * setIsSensitive() 属 @UnsupportedAppUsage 不能直调）；
 * Android 13+ 系统自带剪贴板指示器，不再重复弹 Toast。
 */
fun Context.copyTextToClipboard(
    text: String,
    label: String,
    sensitive: Boolean = false,
    @StringRes toastRes: Int = R.string.toast_copied
) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, getString(toastRes), Toast.LENGTH_SHORT).show()
    }
}
