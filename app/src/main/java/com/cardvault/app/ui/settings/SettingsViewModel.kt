package com.cardvault.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cardvault.app.data.BackupManager
import com.cardvault.app.data.SettingsRepository
import com.cardvault.app.network.BinLookupService
import com.cardvault.app.network.AppUpdateInfo
import com.cardvault.app.network.UpdateCheckResult
import com.cardvault.app.network.UpdateService
import com.cardvault.app.notifications.ExpiryNotificationScheduler
import kotlinx.coroutines.launch

sealed interface ProxyTestState {
    data object Idle : ProxyTestState
    data object Testing : ProxyTestState
    data class Ok(val latencyMs: Long) : ProxyTestState
    data class Failed(val message: String) : ProxyTestState
}

sealed interface BackupState {
    data object Idle : BackupState
    data object Working : BackupState
    data class Done(val message: String) : BackupState
    data class Error(val message: String) : BackupState
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class OpenDownload(val info: AppUpdateInfo) : AppUpdateState
    data class Done(val message: String) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val binService: BinLookupService,
    private val updateService: UpdateService,
    private val backupManager: BackupManager,
    private val notificationScheduler: ExpiryNotificationScheduler,
) : ViewModel() {

    var proxyTest by mutableStateOf<ProxyTestState>(ProxyTestState.Idle)
        private set
    var backupState by mutableStateOf<BackupState>(BackupState.Idle)
        private set
    var updateState by mutableStateOf<AppUpdateState>(AppUpdateState.Idle)
        private set

    fun setAutoLockSeconds(v: Int) = viewModelScope.launch { settingsRepo.setAutoLockSeconds(v) }
    fun setMaskNumbers(v: Boolean) = viewModelScope.launch { settingsRepo.setMaskNumbers(v) }
    fun setClipboardClearSeconds(v: Int) = viewModelScope.launch { settingsRepo.setClipboardClearSeconds(v) }
    fun setSecureScreen(v: Boolean) = viewModelScope.launch { settingsRepo.setSecureScreen(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settingsRepo.setThemeMode(v) }
    fun setExpiryNotifications(v: Boolean) = viewModelScope.launch {
        settingsRepo.setExpiryNotifications(v)
        notificationScheduler.apply(v)
    }

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

    fun checkUpdate() {
        viewModelScope.launch {
            updateState = AppUpdateState.Checking
            updateService.check()
                .onSuccess { result ->
                    updateState = when (result) {
                        is UpdateCheckResult.Available -> AppUpdateState.OpenDownload(result.info)
                        is UpdateCheckResult.UpToDate -> AppUpdateState.Done(
                            "已是最新版本 ${result.currentVersion}"
                        )
                    }
                }
                .onFailure {
                    updateState = AppUpdateState.Error(it.message ?: "检查更新失败")
                }
        }
    }

    fun markUpdateDownloadOpened(info: AppUpdateInfo) {
        updateState = AppUpdateState.Done("已打开 ${info.latestVersion} 下载页面")
    }

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            backupState = BackupState.Working
            runCatching { backupManager.exportTo(uri, password) }
                .onSuccess { count -> backupState = BackupState.Done("已导出 $count 张卡") }
                .onFailure { backupState = BackupState.Error(it.message ?: "导出失败") }
        }
    }

    fun importBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            backupState = BackupState.Working
            runCatching { backupManager.importFrom(uri, password) }
                .onSuccess { summary ->
                    backupState = BackupState.Done(
                        "已导入 ${summary.total} 张卡，新增 ${summary.inserted} 张，更新 ${summary.updated} 张"
                    )
                }
                .onFailure { backupState = BackupState.Error(it.message ?: "导入失败") }
        }
    }
}
