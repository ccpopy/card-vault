package com.cardvault.app.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用锁状态：启动即锁定（若开启了任一解锁方式），退后台超时后再次锁定 */
class LockController(private val pinManager: PinManager) {

    private val _locked = MutableStateFlow(pinManager.isAppLockEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt: Long = -1L

    fun unlock() {
        _locked.value = false
    }

    fun lockNow() {
        if (pinManager.isAppLockEnabled()) _locked.value = true
    }

    fun onAppBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onAppForeground(autoLockSeconds: Int) {
        if (!pinManager.isAppLockEnabled() || _locked.value) return
        if (backgroundAt < 0) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        if (away >= autoLockSeconds * 1000L) _locked.value = true
    }
}
