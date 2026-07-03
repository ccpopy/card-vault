package com.cardvault.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** 代理地址，如 socks5://127.0.0.1:10808 或 http://127.0.0.1:8080；空 = 直连 */
    val proxyUrl: String = "",
    /** 退到后台多少秒后需要重新解锁；0 = 立即 */
    val autoLockSeconds: Int = 60,
    /** 列表卡面打码显示卡号 */
    val maskNumbers: Boolean = true,
    /** 复制敏感字段后多少秒自动清空剪贴板；0 = 不清空 */
    val clipboardClearSeconds: Int = 30,
    /** 禁止截屏/最近任务预览（FLAG_SECURE） */
    val secureScreen: Boolean = true,
    /** system / light / dark */
    val themeMode: String = "system",
    /** 是否启用本地到期通知 */
    val expiryNotifications: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "settings")
        private val KEY_PROXY = stringPreferencesKey("proxy_url")
        private val KEY_AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        private val KEY_MASK = booleanPreferencesKey("mask_numbers")
        private val KEY_CLIP_CLEAR = intPreferencesKey("clipboard_clear_seconds")
        private val KEY_SECURE = booleanPreferencesKey("secure_screen")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_EXPIRY_NOTIFICATIONS = booleanPreferencesKey("expiry_notifications")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            proxyUrl = p[KEY_PROXY] ?: "",
            autoLockSeconds = p[KEY_AUTO_LOCK] ?: 60,
            maskNumbers = p[KEY_MASK] ?: true,
            clipboardClearSeconds = p[KEY_CLIP_CLEAR] ?: 30,
            secureScreen = p[KEY_SECURE] ?: true,
            themeMode = p[KEY_THEME] ?: "system",
            expiryNotifications = p[KEY_EXPIRY_NOTIFICATIONS] ?: false,
        )
    }

    suspend fun setProxyUrl(v: String) = context.dataStore.edit { it[KEY_PROXY] = v.trim() }
    suspend fun setAutoLockSeconds(v: Int) = context.dataStore.edit { it[KEY_AUTO_LOCK] = v }
    suspend fun setMaskNumbers(v: Boolean) = context.dataStore.edit { it[KEY_MASK] = v }
    suspend fun setClipboardClearSeconds(v: Int) = context.dataStore.edit { it[KEY_CLIP_CLEAR] = v }
    suspend fun setSecureScreen(v: Boolean) = context.dataStore.edit { it[KEY_SECURE] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[KEY_THEME] = v }
    suspend fun setExpiryNotifications(v: Boolean) =
        context.dataStore.edit { it[KEY_EXPIRY_NOTIFICATIONS] = v }
}
