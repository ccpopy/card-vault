package com.cardvault.app

import android.app.Application
import androidx.room.Room
import com.cardvault.app.data.CardDatabase
import com.cardvault.app.data.CardRepository
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.network.BinLookupService
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(app) }
    val pinManager by lazy { PinManager(app) }
    val lockController by lazy { LockController(pinManager) }
    val binLookupService by lazy { BinLookupService() }
    val clipboardHelper by lazy { ClipboardHelper(appScope) }

    private val database: CardDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager.getOrCreatePassphrase(app)
        Room.databaseBuilder(app, CardDatabase::class.java, "cardvault.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    val cardRepository by lazy { CardRepository(database.cardDao()) }
}
