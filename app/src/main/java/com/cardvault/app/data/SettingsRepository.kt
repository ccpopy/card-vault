package com.cardvault.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 卡片墙排序方式 */
enum class CardSortMode(val key: String, val label: String) {
    MANUAL("manual", "手动排序"),
    CREATED("created", "按添加时间"),
    BANK("bank", "按银行"),
    EXPIRY("expiry", "按到期日");

    companion object {
        fun fromKey(key: String?): CardSortMode =
            entries.firstOrNull { it.key == key } ?: MANUAL
    }
}

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
    /** 「即将到期」阈值天数 */
    val expiryNoticeDays: Int = 30,
    /** 卡片墙排序方式 */
    val sortMode: CardSortMode = CardSortMode.MANUAL,
    /** 完全离线：禁用 BIN 在线查询、连通性测试与检查更新 */
    val offlineMode: Boolean = false,
    /** 息屏立即锁定（需已开启应用锁） */
    val lockOnScreenOff: Boolean = false,
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
        private val KEY_EXPIRY_NOTICE_DAYS = intPreferencesKey("expiry_notice_days")
        private val KEY_SORT_MODE = stringPreferencesKey("sort_mode")
        private val KEY_OFFLINE = booleanPreferencesKey("offline_mode")
        private val KEY_LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")

        private val THEME_MODES = setOf("system", "light", "dark")
        val EXPIRY_NOTICE_OPTIONS = listOf(7, 14, 30, 60, 90)

        // FLAG_SECURE 需要在 Activity 首帧前同步生效，DataStore 是异步的，
        // 因此镜像一份到 SharedPreferences 供 onCreate 直接读取
        private const val MIRROR_PREFS = "settings_mirror"
        private const val MIRROR_SECURE = "secure_screen"

        fun secureScreenCached(context: Context): Boolean =
            context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getBoolean(MIRROR_SECURE, true)
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            proxyUrl = p[KEY_PROXY] ?: "",
            autoLockSeconds = p[KEY_AUTO_LOCK] ?: 60,
            maskNumbers = p[KEY_MASK] ?: true,
            clipboardClearSeconds = p[KEY_CLIP_CLEAR] ?: 30,
            secureScreen = p[KEY_SECURE] ?: true,
            themeMode = (p[KEY_THEME] ?: "system").takeIf { it in THEME_MODES } ?: "system",
            expiryNotifications = p[KEY_EXPIRY_NOTIFICATIONS] ?: false,
            expiryNoticeDays = (p[KEY_EXPIRY_NOTICE_DAYS] ?: 30).coerceIn(1, 365),
            sortMode = CardSortMode.fromKey(p[KEY_SORT_MODE]),
            offlineMode = p[KEY_OFFLINE] ?: false,
            lockOnScreenOff = p[KEY_LOCK_ON_SCREEN_OFF] ?: false,
        )
    }

    suspend fun setProxyUrl(v: String) = context.dataStore.edit { it[KEY_PROXY] = v.trim() }

    suspend fun setAutoLockSeconds(v: Int) =
        context.dataStore.edit { it[KEY_AUTO_LOCK] = v.coerceIn(0, 3600) }

    suspend fun setMaskNumbers(v: Boolean) = context.dataStore.edit { it[KEY_MASK] = v }

    suspend fun setClipboardClearSeconds(v: Int) =
        context.dataStore.edit { it[KEY_CLIP_CLEAR] = v.coerceIn(0, 600) }

    suspend fun setSecureScreen(v: Boolean) {
        context.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MIRROR_SECURE, v).apply()
        context.dataStore.edit { it[KEY_SECURE] = v }
    }

    suspend fun setThemeMode(v: String) =
        context.dataStore.edit { it[KEY_THEME] = v.takeIf { m -> m in THEME_MODES } ?: "system" }

    suspend fun setExpiryNotifications(v: Boolean) =
        context.dataStore.edit { it[KEY_EXPIRY_NOTIFICATIONS] = v }

    suspend fun setExpiryNoticeDays(v: Int) =
        context.dataStore.edit { it[KEY_EXPIRY_NOTICE_DAYS] = v.coerceIn(1, 365) }

    suspend fun setSortMode(v: CardSortMode) =
        context.dataStore.edit { it[KEY_SORT_MODE] = v.key }

    suspend fun setOfflineMode(v: Boolean) = context.dataStore.edit { it[KEY_OFFLINE] = v }

    suspend fun setLockOnScreenOff(v: Boolean) =
        context.dataStore.edit { it[KEY_LOCK_ON_SCREEN_OFF] = v }
}
