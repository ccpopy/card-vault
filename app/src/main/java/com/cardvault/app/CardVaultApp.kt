package com.cardvault.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.cardvault.app.data.BackupManager
import com.cardvault.app.data.CardDatabase
import com.cardvault.app.data.CardRepository
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.network.BinLookupService
import com.cardvault.app.network.UpdateService
import com.cardvault.app.nfc.NfcCardReader
import com.cardvault.app.nfc.NfcImportController
import com.cardvault.app.notifications.ExpiryNotificationScheduler
import com.cardvault.app.security.ClipboardHelper
import com.cardvault.app.security.DbKeyManager
import com.cardvault.app.security.LockController
import com.cardvault.app.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class CardVaultApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (container.lockOnScreenOffEnabled) container.lockController.lockNow()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 到期提醒调度挂在应用作用域并只消费 DataStore 的真实值：
        // 以前放在 Activity 组合里，collectAsState 的初值 false 会在每次冷启动
        // 先 cancel 再重排队，把当天尚未执行的提醒吞掉
        container.appScope.launch {
            container.settingsRepository.settings
                .map { it.expiryNotifications }
                .distinctUntilChanged()
                .collect { enabled -> container.expiryNotificationScheduler.apply(enabled) }
        }
        container.appScope.launch {
            container.settingsRepository.settings
                .map { it.lockOnScreenOff }
                .distinctUntilChanged()
                .collect { container.lockOnScreenOffEnabled = it }
        }

        // 息屏即锁（受设置开关控制）；ACTION_SCREEN_OFF 只能动态注册
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // 清理历史版本残留的更新安装包
        container.appScope.launch { UpdateService.cleanupUpdateCache(this@CardVaultApp) }
    }
}

/** 手动依赖容器（应用规模小，不引入 Hilt） */
class AppContainer(private val app: Application) {

    val appContext: Context get() = app

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 息屏即锁开关的内存镜像（广播回调里无法挂起读 DataStore） */
    @Volatile
    var lockOnScreenOffEnabled: Boolean = false

    val settingsRepository by lazy { SettingsRepository(app) }
    val pinManager by lazy { PinManager(app) }
    val lockController by lazy { LockController(pinManager) }
    val binLookupService by lazy { BinLookupService(app) }
    val updateService by lazy { UpdateService(app) }
    val clipboardHelper by lazy { ClipboardHelper(appScope) }
    val expiryNotificationScheduler by lazy { ExpiryNotificationScheduler(app) }
    val nfcImportController by lazy { NfcImportController() }
    val nfcCardReader by lazy { NfcCardReader() }

    private val database: CardDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager.getOrCreatePassphrase(app)
        Room.databaseBuilder(app, CardDatabase::class.java, "cardvault.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*CardDatabase.ALL_MIGRATIONS)
            .build()
    }

    val cardRepository by lazy { CardRepository(database) }
    val backupManager by lazy { BackupManager(app, cardRepository) }
}
