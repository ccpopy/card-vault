package com.cardvault.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cardvault.app.CardVaultApp
import com.cardvault.app.MainActivity
import com.cardvault.app.R
import com.cardvault.app.domain.CardValidation
import kotlinx.coroutines.flow.first

/**
 * 每日到期检查。复用应用级单例数据库与设置仓库（不再自建第二个 Room 实例，
 * 也不再手工维护迁移列表）；瞬时异常走 Result.retry，最终无论成败都续排次日任务。
 */
class ExpiryNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CardVaultApp).container
        return try {
            val settings = container.settingsRepository.settings.first()
            // 开关已被关闭：不提醒也不续排（apply(false) 已取消，但保险起见再判一次）
            if (!settings.expiryNotifications) return Result.success()

            checkAndNotify(container, settings.expiryNoticeDays)
            ExpiryNotificationScheduler.scheduleNext(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                // 放弃本次，但保证链条不断——明天还会再查
                ExpiryNotificationScheduler.scheduleNext(applicationContext)
                Result.failure()
            }
        }
    }

    private suspend fun checkAndNotify(container: com.cardvault.app.AppContainer, noticeDays: Int) {
        if (!canPostNotifications()) return
        ExpiryNotificationScheduler.ensureChannel(applicationContext)

        val cards = container.cardRepository.getAll().filterNot { it.archived }
        val expiring = cards.count {
            CardValidation.expiryStatus(it.expiryMonth, it.expiryYear, noticeDays) ==
                CardValidation.ExpiryStatus.EXPIRING
        }
        val expired = cards.count {
            CardValidation.expiryStatus(it.expiryMonth, it.expiryYear, noticeDays) ==
                CardValidation.ExpiryStatus.EXPIRED
        }
        if (expiring == 0 && expired == 0) return

        val text = when {
            expiring > 0 && expired > 0 -> "$expiring 张卡 $noticeDays 天内到期，$expired 张卡已过期"
            expiring > 0 -> "$expiring 张卡将在 $noticeDays 天内到期"
            else -> "$expired 张卡已过期"
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            ExpiryNotificationScheduler.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("银行卡到期提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val NOTIFICATION_ID = 3101
    }
}
