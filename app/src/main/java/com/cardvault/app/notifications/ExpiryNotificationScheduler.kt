package com.cardvault.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ExpiryNotificationScheduler(private val context: Context) {
    fun apply(enabled: Boolean) {
        ensureChannel(context)
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ExpiryNotificationWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilNextMorning().toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "card_expiry_notifications"
        const val CHANNEL_ID = "card_expiry"

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
