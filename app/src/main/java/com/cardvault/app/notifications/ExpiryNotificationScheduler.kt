package com.cardvault.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 到期提醒调度：自续期的一次性任务锚定「下一个早上 9 点」，
 * Worker 执行完毕后自行排下一天，天然免疫周期任务在 Doze 下的逐日漂移。
 * apply(true) 用 KEEP 策略——已有待执行任务时不重排，
 * 避免冷启动把当天尚未执行的提醒顶掉（旧实现的 bug）。
 */
class ExpiryNotificationScheduler(private val context: Context) {
    fun apply(enabled: Boolean) {
        ensureChannel(context)
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, nextRequest())
    }

    companion object {
        const val WORK_NAME = "card_expiry_notifications"
        const val CHANNEL_ID = "card_expiry"

        /** Worker 执行完后调用：排定次日 9 点的下一次检查 */
        fun scheduleNext(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                nextRequest(),
            )
        }

        private fun nextRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ExpiryNotificationWorker>()
                .setInitialDelay(delayUntilNextMorning())
                .addTag(WORK_NAME)
                .build()

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "卡片到期提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "本地检查银行卡有效期并提醒"
            }
            manager.createNotificationChannel(channel)
        }

        private fun delayUntilNextMorning(): Duration {
            val now = LocalDateTime.now()
            val today = now.toLocalDate().atTime(LocalTime.of(9, 0))
            val next = if (now.isBefore(today)) today else today.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
