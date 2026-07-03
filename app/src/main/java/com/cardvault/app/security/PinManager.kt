package com.cardvault.app.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 应用锁管理：PIN 与生物识别是两个独立的解锁方式，可单独开启。
 * PIN 用 PBKDF2-HmacSHA256（120,000 次迭代）加盐哈希存储，绝不保存明文。
 * 连续失败 5 次锁定 30 秒，防止暴力尝试。
 * 两者同时开启时生物识别优先，但每 7 天强制输入一次 PIN，防止长期不用而遗忘。
 */
class PinManager(context: Context) {

    private val prefs = context.getSharedPreferences("cardvault_secure", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_SALT = "pin_salt"
        private const val PREF_HASH = "pin_hash"
        private const val PREF_FAILS = "pin_fail_count"
        private const val PREF_LOCK_UNTIL = "pin_lock_until"
        private const val PREF_BIO = "biometric_unlock_enabled"
        private const val PREF_LAST_PIN_VERIFY = "last_pin_verify_at"
        private const val ITERATIONS = 120_000
        private const val MAX_FAILS = 5
        private const val LOCKOUT_MS = 30_000L
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
    fun needsPinRecheck(): Boolean =
        isPinSet() && isBiometricEnabled() &&
            System.currentTimeMillis() - prefs.getLong(PREF_LAST_PIN_VERIFY, 0L) > PIN_RECHECK_INTERVAL_MS

    /** 关闭 PIN 解锁：清除 PIN 及失败计数（不影响生物识别开关） */
    fun clearPin() {
        prefs.edit()
            .remove(PREF_SALT)
            .remove(PREF_HASH)
            .remove(PREF_LAST_PIN_VERIFY)
            .putInt(PREF_FAILS, 0)
            .putLong(PREF_LOCK_UNTIL, 0L)
            .apply()
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PREF_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(PREF_FAILS, 0)
            .putLong(PREF_LOCK_UNTIL, 0L)
            .putLong(PREF_LAST_PIN_VERIFY, System.currentTimeMillis())
            .apply()
    }

    /** 剩余锁定毫秒数，0 表示未锁定 */
    fun remainingLockoutMs(): Long =
        (prefs.getLong(PREF_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun verifyPin(pin: String): Boolean {
        if (remainingLockoutMs() > 0) return false
        val salt = Base64.decode(prefs.getString(PREF_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(PREF_HASH, null) ?: return false, Base64.NO_WRAP)
        val ok = hash(pin, salt).contentEquals(expected)
        if (ok) {
            prefs.edit()
                .putInt(PREF_FAILS, 0)
                .putLong(PREF_LOCK_UNTIL, 0L)
                .putLong(PREF_LAST_PIN_VERIFY, System.currentTimeMillis())
                .apply()
        } else {
            val fails = prefs.getInt(PREF_FAILS, 0) + 1
            val editor = prefs.edit().putInt(PREF_FAILS, fails)
            if (fails >= MAX_FAILS) {
                editor.putLong(PREF_LOCK_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                editor.putInt(PREF_FAILS, 0)
            }
            editor.apply()
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }
}
