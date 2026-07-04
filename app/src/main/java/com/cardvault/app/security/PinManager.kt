package com.cardvault.app.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 应用锁管理：PIN 与生物识别是两个独立的解锁方式，可单独开启。
 * PIN 用 PBKDF2-HmacSHA256 加盐哈希存储，绝不保存明文；旧哈希在下次验证成功时
 * 自动升级到当前迭代次数。
 * 防暴力：连续失败 5 次起锁定，失败计数不清零，锁定时长按 2 的幂指数退避
 * （30s → 1m → 2m → … 封顶 30 分钟）；计时使用 elapsedRealtime 单调时钟，
 * 改系统时间无法绕过，重启后按剩余时长保守恢复。
 * 两者同时开启时生物识别优先，但每 7 天强制输入一次 PIN，防止长期不用而遗忘。
 */
class PinManager(context: Context) {

    private val prefs = context.getSharedPreferences("cardvault_secure", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_SALT = "pin_salt"
        private const val PREF_HASH = "pin_hash"
        private const val PREF_ITERATIONS = "pin_iterations"
        private const val PREF_FAILS = "pin_fail_count"
        private const val PREF_LOCK_ANCHOR = "pin_lock_anchor_elapsed"
        private const val PREF_LOCK_DURATION = "pin_lock_duration_ms"
        private const val PREF_BIO = "biometric_unlock_enabled"
        private const val PREF_LAST_PIN_VERIFY = "last_pin_verify_at"

        private const val ITERATIONS = 300_000
        /** 1.1.4 及之前版本的迭代次数：无记录时按它验证，成功后升级 */
        private const val LEGACY_ITERATIONS = 120_000
        private const val FAIL_THRESHOLD = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 30 * 60_000L
        private const val PIN_RECHECK_INTERVAL_MS = 7 * 24 * 3600_000L
    }

    fun isPinSet(): Boolean = prefs.contains(PREF_HASH)

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(PREF_BIO, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_BIO, enabled).apply()
    }

    /** 任一解锁方式开启即视为应用锁开启 */
    fun isAppLockEnabled(): Boolean = isPinSet() || isBiometricEnabled()

    /** PIN 与生物识别同时开启、且距上次成功输入 PIN 超过 7 天时，强制用 PIN 解锁一次 */
    fun needsPinRecheck(): Boolean {
        if (!isPinSet() || !isBiometricEnabled()) return false
        val last = prefs.getLong(PREF_LAST_PIN_VERIFY, 0L)
        val now = System.currentTimeMillis()
        // 墙钟被回拨到上次验证之前：视为超期，防止改时间无限躲避复核
        return now < last || now - last > PIN_RECHECK_INTERVAL_MS
    }

    /** 关闭 PIN 解锁：清除 PIN 及失败计数（不影响生物识别开关） */
    fun clearPin() {
        prefs.edit()
            .remove(PREF_SALT)
            .remove(PREF_HASH)
            .remove(PREF_ITERATIONS)
            .remove(PREF_LAST_PIN_VERIFY)
            .putInt(PREF_FAILS, 0)
            .remove(PREF_LOCK_ANCHOR)
            .remove(PREF_LOCK_DURATION)
            .apply()
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = hash(pin, salt, ITERATIONS)
        prefs.edit()
            .putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PREF_HASH, Base64.encodeToString(digest, Base64.NO_WRAP))
            .putInt(PREF_ITERATIONS, ITERATIONS)
            .putInt(PREF_FAILS, 0)
            .remove(PREF_LOCK_ANCHOR)
            .remove(PREF_LOCK_DURATION)
            .putLong(PREF_LAST_PIN_VERIFY, System.currentTimeMillis())
            .apply()
        digest.fill(0)
    }

    /** 剩余锁定毫秒数，0 表示未锁定 */
    fun remainingLockoutMs(): Long {
        val duration = prefs.getLong(PREF_LOCK_DURATION, 0L)
        if (duration <= 0L) return 0L
        val anchor = prefs.getLong(PREF_LOCK_ANCHOR, 0L)
        val now = SystemClock.elapsedRealtime()
        if (now < anchor) {
            // elapsedRealtime 比锚点还小 → 发生过重启。保守处理：从现在起重新计满剩余时长
            prefs.edit().putLong(PREF_LOCK_ANCHOR, now).apply()
            return duration
        }
        return (anchor + duration - now).coerceAtLeast(0L)
    }

    /**
     * 校验 PIN。含 PBKDF2 计算，耗时数百毫秒，必须在后台线程调用。
     * 锁定期间直接返回 false 且不累计失败。
     */
    fun verifyPin(pin: String): Boolean {
        if (remainingLockoutMs() > 0) return false
        val salt = Base64.decode(prefs.getString(PREF_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(PREF_HASH, null) ?: return false, Base64.NO_WRAP)
        val iterations = prefs.getInt(PREF_ITERATIONS, LEGACY_ITERATIONS)
        val digest = hash(pin, salt, iterations)
        val ok = digest.contentEquals(expected)
        digest.fill(0)
        if (ok) {
            prefs.edit()
                .putInt(PREF_FAILS, 0)
                .remove(PREF_LOCK_ANCHOR)
                .remove(PREF_LOCK_DURATION)
                .putLong(PREF_LAST_PIN_VERIFY, System.currentTimeMillis())
                .apply()
            if (iterations != ITERATIONS) setPin(pin) // 迭代次数升级：重哈希落盘
        } else {
            val fails = prefs.getInt(PREF_FAILS, 0) + 1
            val editor = prefs.edit().putInt(PREF_FAILS, fails)
            if (fails >= FAIL_THRESHOLD) {
                val exponent = (fails - FAIL_THRESHOLD).coerceAtMost(20)
                val duration = (BASE_LOCKOUT_MS shl exponent).coerceAtMost(MAX_LOCKOUT_MS)
                editor.putLong(PREF_LOCK_ANCHOR, SystemClock.elapsedRealtime())
                editor.putLong(PREF_LOCK_DURATION, duration)
            }
            editor.apply()
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
