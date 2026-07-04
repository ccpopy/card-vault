package com.cardvault.app.ui.settings

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ProxyTestState {
    data object Idle : ProxyTestState
    data object Testing : ProxyTestState
    data class Ok(val message: String) : ProxyTestState
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
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(
        val info: AppUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateState
    data class ReadyToInstall(val info: AppUpdateInfo, val apkPath: String) : AppUpdateState
    data class UpToDate(val message: String) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class SettingsViewModel(
    private val appContext: Context,
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

    private var downloadJob: Job? = null

    fun setAutoLockSeconds(v: Int) = viewModelScope.launch { settingsRepo.setAutoLockSeconds(v) }
    fun setMaskNumbers(v: Boolean) = viewModelScope.launch { settingsRepo.setMaskNumbers(v) }
    fun setClipboardClearSeconds(v: Int) = viewModelScope.launch { settingsRepo.setClipboardClearSeconds(v) }
    fun setSecureScreen(v: Boolean) = viewModelScope.launch { settingsRepo.setSecureScreen(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settingsRepo.setThemeMode(v) }
    fun setOfflineMode(v: Boolean) = viewModelScope.launch { settingsRepo.setOfflineMode(v) }
    fun setLockOnScreenOff(v: Boolean) = viewModelScope.launch { settingsRepo.setLockOnScreenOff(v) }
    fun setExpiryNoticeDays(v: Int) = viewModelScope.launch { settingsRepo.setExpiryNoticeDays(v) }
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
            if (settingsRepo.settings.first().offlineMode) {
                proxyTest = ProxyTestState.Failed("完全离线模式已开启")
                return@launch
            }
            proxyTest = ProxyTestState.Testing
            binService.testConnection(url.ifBlank { null })
                .onSuccess { proxyTest = ProxyTestState.Ok(it) }
                .onFailure { proxyTest = ProxyTestState.Failed(it.message ?: "连接失败") }
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            if (settings.offlineMode) {
                updateState = AppUpdateState.Error("完全离线模式已开启，检查更新被禁用")
                return@launch
            }
            updateState = AppUpdateState.Checking
            updateService.check(settings.proxyUrl.ifBlank { null })
                .onSuccess { result ->
                    updateState = when (result) {
                        is UpdateCheckResult.Available -> AppUpdateState.Available(result.info)
                        is UpdateCheckResult.UpToDate -> AppUpdateState.UpToDate(
                            "已是最新版本 ${result.currentVersion}"
                        )
                    }
                }
                .onFailure {
                    updateState = AppUpdateState.Error(it.message ?: "检查更新失败")
                }
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        if (updateState is AppUpdateState.Downloading) return
        downloadJob = viewModelScope.launch {
            updateState = AppUpdateState.Downloading(info, 0L, info.assetSizeBytes)
            val target = UpdateService.updateApkFile(appContext, info.latestVersion)
            val proxyUrl = settingsRepo.settings.first().proxyUrl.ifBlank { null }
            try {
                updateService.downloadApk(info, target, proxyUrl) { downloaded, total ->
                    val current = updateState
                    if (current is AppUpdateState.Downloading) {
                        updateState = current.copy(
                            downloadedBytes = downloaded,
                            totalBytes = if (total > 0) total else current.totalBytes,
                        )
                    }
                }
                    .onSuccess { file ->
                        if (updateState is AppUpdateState.Downloading) {
                            updateState = AppUpdateState.ReadyToInstall(info, file.absolutePath)
                        }
                    }
                    .onFailure {
                        if (updateState is AppUpdateState.Downloading) {
                            updateState = AppUpdateState.Error(it.message ?: "下载失败")
                        }
                    }
            } catch (e: CancellationException) {
                // 用户主动取消：已下载的 .part 保留，下次下载从断点续传
                throw e
            }
        }
    }

    /** 取消下载：中止网络读取，回到「可下载」状态；断点文件保留用于续传 */
    fun cancelDownload() {
        val current = updateState
        downloadJob?.cancel()
        downloadJob = null
        if (current is AppUpdateState.Downloading) {
            updateState = AppUpdateState.Available(current.info)
        }
    }

    fun reportUpdateError(message: String) {
        updateState = AppUpdateState.Error(message)
    }

    fun dismissUpdate() {
        if (updateState is AppUpdateState.Downloading) cancelDownload()
        updateState = AppUpdateState.Idle
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
