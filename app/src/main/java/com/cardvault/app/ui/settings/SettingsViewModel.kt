package com.cardvault.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.network.BinLookupService
import kotlinx.coroutines.launch

sealed interface ProxyTestState {
    data object Idle : ProxyTestState
    data object Testing : ProxyTestState
    data class Ok(val latencyMs: Long) : ProxyTestState
    data class Failed(val message: String) : ProxyTestState
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val binService: BinLookupService,
) : ViewModel() {

    var proxyTest by mutableStateOf<ProxyTestState>(ProxyTestState.Idle)
        private set

    fun setAutoLockSeconds(v: Int) = viewModelScope.launch { settingsRepo.setAutoLockSeconds(v) }
    fun setMaskNumbers(v: Boolean) = viewModelScope.launch { settingsRepo.setMaskNumbers(v) }
    fun setClipboardClearSeconds(v: Int) = viewModelScope.launch { settingsRepo.setClipboardClearSeconds(v) }
    fun setSecureScreen(v: Boolean) = viewModelScope.launch { settingsRepo.setSecureScreen(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settingsRepo.setThemeMode(v) }

    fun saveProxy(url: String) = viewModelScope.launch {
        settingsRepo.setProxyUrl(url)
        proxyTest = ProxyTestState.Idle
    }

    fun testProxy(url: String) {
        viewModelScope.launch {
            proxyTest = ProxyTestState.Testing
            binService.testConnection(url.ifBlank { null })
                .onSuccess { proxyTest = ProxyTestState.Ok(it) }
                .onFailure { proxyTest = ProxyTestState.Failed(it.message ?: "连接失败") }
        }
    }
}
