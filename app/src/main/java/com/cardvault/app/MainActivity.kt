package com.cardvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cardvault.app.data.AppSettings
import com.cardvault.app.ui.AppRoot
import com.cardvault.app.ui.effects.LocalDeviceTilt
import com.cardvault.app.ui.effects.rememberDeviceTilt
import com.cardvault.app.ui.theme.CardVaultTheme

// BiometricPrompt 需要 FragmentActivity，因此继承 AppCompatActivity
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CardVaultApp).container

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
}
