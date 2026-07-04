package com.cardvault.app.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 复制到剪贴板：标记为敏感内容（不出现在剪贴板预览），并支持定时自动清空 */
class ClipboardHelper(private val appScope: CoroutineScope) {

    private var clearJob: Job? = null

    fun copy(context: Context, label: String, text: String, clearAfterSeconds: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 24) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(sensitiveExtraKey(), true)
            }
        }
        cm.setPrimaryClip(clip)

        val hint = if (clearAfterSeconds > 0) "已复制$label（${clearAfterSeconds}秒后自动清除）" else "已复制$label"
        Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()

        clearJob?.cancel()
        if (clearAfterSeconds > 0) {
            val appContext = context.applicationContext
            clearJob = appScope.launch(Dispatchers.Main) {
                delay(clearAfterSeconds * 1000L)
                val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // Android 10+ 后台应用读不到剪贴板（primaryClip 为 null）。
                // 复制卡号后切到其他应用粘贴恰恰是最常见场景——读不到时必须照样清空，
                // 否则“定时清除”承诺在最需要它的时候失效。只有确认剪贴板已被
                // 其他内容覆盖（前台可读且不相等）时才跳过，避免误删用户后来复制的内容。
                val current = manager.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == null || current == text) {
                    if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private fun sensitiveExtraKey(): String =
        if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
        else "android.content.extra.IS_SENSITIVE" // 官方常量的字面值，低版本主流 ROM 也识别
}
