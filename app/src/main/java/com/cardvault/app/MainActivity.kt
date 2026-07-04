package com.cardvault.app

import android.nfc.NfcAdapter
import android.nfc.TagLostException
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.cardvault.app.data.AppSettings
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.ui.AppRoot
import com.cardvault.app.ui.effects.LocalDeviceTilt
import com.cardvault.app.ui.effects.rememberDeviceTilt
import com.cardvault.app.ui.theme.CardVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

// BiometricPrompt 需要 FragmentActivity，因此继承 AppCompatActivity
class MainActivity : AppCompatActivity() {
    private lateinit var container: AppContainer
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as CardVaultApp).container
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // FLAG_SECURE 必须在首帧之前生效（DataStore 异步，读同步镜像），
        // 否则冷启动首帧到 Compose effect 执行之间存在可截屏窗口
        if (SettingsRepository.secureScreenCached(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

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
                    runCatching { container.nfcCardReader.read(tag) }
                        .onSuccess { draft ->
                            container.nfcImportController.post(draft)
                            if (container.lockController.locked.value) {
                                showToast("已读取卡片，解锁后继续导入")
                            }
                        }
                        .onFailure { error -> showToast(nfcErrorMessage(error)) }
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

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun nfcErrorMessage(error: Throwable): String = when (error) {
        is TagLostException -> "读取中断，请将卡片贴稳在手机背面后重试"
        is IOException -> "NFC 通信失败，请重新贴卡"
        else -> error.message ?: "NFC 读卡失败"
    }
}
