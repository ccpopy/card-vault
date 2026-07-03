package com.cardvault.app.security

import android.content.ClipData
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
                // API 33+ 的官方常量，低版本上同名 key 也被主流 ROM 识别
                putBoolean("android.content.extra.IS_SENSITIVE", true)
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
                // 仅当剪贴板内容仍是我们写入的才清空，避免误删用户后来复制的内容
                val current = manager.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == text) {
                    if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }
}
