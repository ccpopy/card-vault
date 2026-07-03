package com.cardvault.app

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.cardvault.app.data.AppSettings
import com.cardvault.app.nfc.NfcImportEvent
import com.cardvault.app.ui.AppRoot
import com.cardvault.app.ui.effects.LocalDeviceTilt
import com.cardvault.app.ui.effects.rememberDeviceTilt
import com.cardvault.app.ui.theme.CardVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// BiometricPrompt 需要 FragmentActivity，因此继承 AppCompatActivity
class MainActivity : AppCompatActivity() {
    private lateinit var container: AppContainer
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as CardVaultApp).container
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())

            LaunchedEffect(settings.secureScreen) {
                if (settings.secureScreen) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(settings.expiryNotifications) {
                container.expiryNotificationScheduler.apply(settings.expiryNotifications)
            }

            CardVaultTheme(themeMode = settings.themeMode) {
                // 单例传感器监听：卡面高光跟随设备倾斜
                val tilt = rememberDeviceTilt()
                CompositionLocalProvider(LocalDeviceTilt provides tilt) {
                    AppRoot(container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(
            this,
            { tag ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val event = runCatching {
                        NfcImportEvent.CardRead(container.nfcCardReader.read(tag))
                    }.getOrElse { error ->
                        NfcImportEvent.Error(error.message ?: "NFC 读卡失败")
                    }
                    container.nfcImportController.emit(event)
                }
            },
            flags,
            null,
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }
}
