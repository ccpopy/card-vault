package com.cardvault.app

import android.app.Application
import android.content.Context
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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class CardVaultApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** 手动依赖容器（应用规模小，不引入 Hilt） */
class AppContainer(private val app: Application) {

    val appContext: Context get() = app

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(app) }
    val pinManager by lazy { PinManager(app) }
    val lockController by lazy { LockController(pinManager) }
    val binLookupService by lazy { BinLookupService() }
    val updateService by lazy { UpdateService() }
    val clipboardHelper by lazy { ClipboardHelper(appScope) }
    val expiryNotificationScheduler by lazy { ExpiryNotificationScheduler(app) }
    val nfcImportController by lazy { NfcImportController() }
    val nfcCardReader by lazy { NfcCardReader() }

    private val database: CardDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager.getOrCreatePassphrase(app)
        Room.databaseBuilder(app, CardDatabase::class.java, "cardvault.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(CardDatabase.MIGRATION_1_2)
            .build()
    }

    val cardRepository by lazy { CardRepository(database.cardDao()) }
    val backupManager by lazy { BackupManager(app, cardRepository) }
}
