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
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cardvault.app.MainActivity
import com.cardvault.app.R
import com.cardvault.app.data.CardDatabase
import com.cardvault.app.domain.CardValidation
import com.cardvault.app.security.DbKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class ExpiryNotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!canPostNotifications()) return Result.success()
        ExpiryNotificationScheduler.ensureChannel(applicationContext)

        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager.getOrCreatePassphrase(applicationContext)
        val db = Room.databaseBuilder(applicationContext, CardDatabase::class.java, "cardvault.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(CardDatabase.MIGRATION_1_2)
            .build()

        val cards = try {
            db.cardDao().getAll()
        } finally {
            db.close()
        }

        val expiring = cards.count {
            CardValidation.expiryStatus(it.expiryMonth, it.expiryYear) == CardValidation.ExpiryStatus.EXPIRING
        }
        val expired = cards.count {
            CardValidation.expiryStatus(it.expiryMonth, it.expiryYear) == CardValidation.ExpiryStatus.EXPIRED
        }
        if (expiring == 0 && expired == 0) return Result.success()

        val text = when {
            expiring > 0 && expired > 0 -> "$expiring 张卡 30 天内到期，$expired 张卡已过期"
            expiring > 0 -> "$expiring 张卡将在 30 天内到期"
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
        return Result.success()
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
