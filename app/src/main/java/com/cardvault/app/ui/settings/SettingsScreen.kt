package com.cardvault.app.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cardvault.app.BuildConfig
import com.cardvault.app.data.AppSettings
import com.cardvault.app.network.BinLookupService
import com.cardvault.app.security.PinManager
import com.cardvault.app.update.ApkInstaller
import com.cardvault.app.ui.lock.PinSetupScreen
import com.cardvault.app.ui.lock.canUseBiometric
import com.cardvault.app.ui.lock.verifyBiometric
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    settings: AppSettings,
    pinManager: PinManager,
    onBack: () -> Unit,
) {
    var proxyInput by rememberSaveable(settings.proxyUrl) { mutableStateOf(settings.proxyUrl) }
    val proxyValid = proxyInput.isBlank() || BinLookupService.isValidProxyUrl(proxyInput)
    var showChangePin by remember { mutableStateOf(false) }
    var showExportBackup by remember { mutableStateOf(false) }
    var showImportBackup by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pinSet by remember { mutableStateOf(pinManager.isPinSet()) }
    var bioOn by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    var showPinSetup by remember { mutableStateOf(false) }
    var showDisablePin by remember { mutableStateOf(false) }
    val biometricSupported = remember { canUseBiometric(context) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri != null && password != null) vm.exportBackup(uri, password)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportBackup = true
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            notificationPermissionDenied = false
            vm.setExpiryNotifications(true)
        } else {
            notificationPermissionDenied = true
        }
    }

    fun setExpiryNotificationPreference(want: Boolean) {
        if (
            want &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionDenied = false
            vm.setExpiryNotifications(want)
        }
    }

    // 从「未知来源」授权页返回后，若已下载完成则继续安装
    val installLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val s = vm.updateState
        if (s is AppUpdateState.ReadyToInstall && ApkInstaller.canInstallPackages(context)) {
            ApkInstaller.install(context, File(s.apkPath))
        }
    }

    fun launchInstall(path: String) {
        if (ApkInstaller.canInstallPackages(context)) {
            ApkInstaller.install(context, File(path))
        } else {
            installLauncher.launch(ApkInstaller.unknownSourcesSettingsIntent(context))
        }
    }

    val updateState = vm.updateState
    LaunchedEffect(updateState) {
        if (updateState is AppUpdateState.ReadyToInstall) {
            launchInstall(updateState.apkPath)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionLabel("安全")
            SectionCard {
                SecurityStatusHeader(pinSet = pinSet, bioOn = bioOn && biometricSupported)
                SettingRow(
                    icon = Icons.Filled.Fingerprint,
                    title = "生物识别解锁",
                    subtitle = if (!biometricSupported) "设备不支持或未录入指纹 / 面容"
                    else "验证一次后开启，可独立使用，无需 PIN",
                    enabled = biometricSupported,
                ) {
                    Switch(
                        checked = bioOn && biometricSupported,
                        enabled = biometricSupported,
                        onCheckedChange = { want ->
                            if (want) {
                                verifyBiometric(context, "启用生物识别解锁", "验证通过后生效") {
                                    pinManager.setBiometricEnabled(true)
                                    bioOn = true
                                }
                            } else {
                                pinManager.setBiometricEnabled(false)
                                bioOn = false
                            }
                        },
                    )
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.Lock,
                    title = "PIN 解锁",
                    subtitle = if (bioOn) "作为生物识别的备用方式，推荐开启"
                    else "打开应用时需输入 6 位 PIN",
                ) {
                    Switch(
                        checked = pinSet,
                        onCheckedChange = { want ->
                            if (want) showPinSetup = true else showDisablePin = true
                        },
                    )
                }
                RowDivider()
                DropdownSettingRow(
                    icon = Icons.Filled.Timer,
                    title = "自动锁定",
                    subtitle = "退到后台超过所选时间后重新锁定",
                    options = listOf(0 to "立即", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟", 900 to "15 分钟"),
                    current = settings.autoLockSeconds,
                    enabled = pinSet || bioOn,
                    onSelect = vm::setAutoLockSeconds,
                )
                if (pinSet) {
                    RowDivider()
                    SettingRow(
                        icon = Icons.Filled.Password,
                        title = "修改 PIN",
                        subtitle = null,
                        onClick = { showChangePin = true },
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pinSet && bioOn) {
                    Text(
                        "两者同时开启时优先使用生物识别；每 7 天需输入一次 PIN，以防遗忘。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 2.dp),
                    )
                }
            }

            SectionLabel("隐私")
            SectionCard {
                SettingRow(
                    icon = Icons.Filled.CreditCard,
                    title = "打码显示卡号",
                    subtitle = "列表卡面仅显示末四位",
                ) {
                    Switch(checked = settings.maskNumbers, onCheckedChange = vm::setMaskNumbers)
                }
                RowDivider()
                DropdownSettingRow(
                    icon = Icons.Filled.ContentPaste,
                    title = "自动清空剪贴板",
                    subtitle = "复制的卡片信息在所选时间后清除",
                    options = listOf(0 to "不清除", 15 to "15 秒", 30 to "30 秒", 60 to "60 秒"),
                    current = settings.clipboardClearSeconds,
                    onSelect = vm::setClipboardClearSeconds,
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.Screenshot,
                    title = "阻止截屏",
                    subtitle = "同时隐藏最近任务中的应用预览",
                ) {
                    Switch(checked = settings.secureScreen, onCheckedChange = vm::setSecureScreen)
                }
            }

            SectionLabel("提醒")
            SectionCard {
                SettingRow(
                    icon = Icons.Filled.NotificationsActive,
                    title = "到期本地通知",
                    subtitle = "每天在本机检查即将到期和已过期卡片",
                ) {
                    Switch(
                        checked = settings.expiryNotifications,
                        onCheckedChange = ::setExpiryNotificationPreference,
                    )
                }
                if (notificationPermissionDenied) {
                    Text(
                        "系统通知权限未授权，无法开启到期提醒。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            SectionLabel("网络")
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        label = { Text("代理地址") },
                        placeholder = { Text("socks5://127.0.0.1:10808") },
                        singleLine = true,
                        isError = !proxyValid,
                        supportingText = {
                            Text(
                                if (!proxyValid) "格式：socks5://host:port 或 http://host:port"
                                else "留空时直连。仅 BIN 查询与连通性测试会访问网络。"
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { vm.saveProxy(proxyInput) },
                            enabled = proxyValid && proxyInput != settings.proxyUrl,
                        ) { Text("保存") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { vm.testProxy(proxyInput) },
                            enabled = proxyValid && vm.proxyTest != ProxyTestState.Testing,
                        ) {
                            if (vm.proxyTest == ProxyTestState.Testing) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("测试连接")
                        }
                    }
                    when (val t = vm.proxyTest) {
                        is ProxyTestState.Ok -> StatusLine(
                            "连接正常 · ${t.latencyMs} ms",
                            MaterialTheme.colorScheme.primary,
                        )
                        is ProxyTestState.Failed -> StatusLine(
                            "连接失败：${t.message}",
                            MaterialTheme.colorScheme.error,
                        )
                        else -> {}
                    }
                }
            }

            SectionLabel("外观")
            SectionCard {
                DropdownSettingRow(
                    icon = Icons.Filled.Palette,
                    title = "主题",
                    subtitle = null,
                    options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                    current = settings.themeMode,
                    onSelect = vm::setThemeMode,
                )
            }

            SectionLabel("数据")
            SectionCard {
                SettingRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "检查更新",
                    subtitle = "在应用内下载并安装新版本",
                    enabled = vm.updateState != AppUpdateState.Checking,
                    onClick = { vm.checkUpdate() },
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (val state = vm.updateState) {
                    AppUpdateState.Checking -> {
                        Row(
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在检查更新…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    is AppUpdateState.UpToDate -> StatusLine(
                        state.message,
                        MaterialTheme.colorScheme.primary,
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    is AppUpdateState.Error -> StatusLine(
                        state.message,
                        MaterialTheme.colorScheme.error,
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    else -> {}
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.FileDownload,
                    title = "导出加密备份",
                    subtitle = "用备份密码加密保存卡片数据",
                    enabled = vm.backupState != BackupState.Working,
                    onClick = { showExportBackup = true },
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Filled.FileUpload,
                    title = "导入加密备份",
                    subtitle = "按卡号合并，同卡号更新",
                    enabled = vm.backupState != BackupState.Working,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (val state = vm.backupState) {
                    BackupState.Working -> {
                        Row(
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在处理备份…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    is BackupState.Done -> StatusLine(
                        state.message,
                        MaterialTheme.colorScheme.primary,
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    is BackupState.Error -> StatusLine(
                        state.message,
                        MaterialTheme.colorScheme.error,
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    else -> {}
                }
                RowDivider()
                Text(
                    "· 卡片数据仅存储于本机，采用 SQLCipher（AES-256）加密，密钥由系统 Keystore 保护。\n" +
                        "· 应用内不包含统计、广告或后台上报组件。\n" +
                        "· 网络请求仅发生在手动触发的 BIN 查询、代理连通性测试与版本检查；BIN 查询仅发送卡号前 8 位。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "CardVault ${BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showChangePin) {
        ChangePinDialog(pinManager = pinManager, onDismiss = { showChangePin = false })
    }

    if (showDisablePin) {
        VerifyPinDialog(
            pinManager = pinManager,
            title = "关闭 PIN 解锁",
            message = if (bioOn) "关闭后仍可使用生物识别解锁。输入当前 PIN 确认。"
            else "关闭后打开应用不再需要解锁。输入当前 PIN 确认。",
            onVerified = {
                pinManager.clearPin()
                pinSet = false
            },
            onDismiss = { showDisablePin = false },
        )
    }

    if (showExportBackup) {
        BackupPasswordDialog(
            title = "导出加密备份",
            confirmText = "选择位置",
            requireConfirm = true,
            onConfirm = { password ->
                pendingExportPassword = password
                showExportBackup = false
                exportLauncher.launch("CardVault-backup-${System.currentTimeMillis()}.cvbk")
            },
            onDismiss = { showExportBackup = false },
        )
    }

    if (showImportBackup && pendingImportUri != null) {
        BackupPasswordDialog(
            title = "导入加密备份",
            message = "导入会按卡号合并：同卡号更新，新卡新增，本机独有卡片保留。",
            confirmText = "导入",
            requireConfirm = false,
            onConfirm = { password ->
                val uri = pendingImportUri
                pendingImportUri = null
                showImportBackup = false
                if (uri != null) vm.importBackup(uri, password)
            },
            onDismiss = {
                pendingImportUri = null
                showImportBackup = false
            },
        )
    }

    // 全屏 PIN 设置流程（覆盖设置页）
    if (showPinSetup) {
        PinSetupScreen(
            pinManager = pinManager,
            onDone = {
                pinSet = true
                showPinSetup = false
            },
            onCancel = { showPinSetup = false },
        )
    }

    if (
        updateState is AppUpdateState.Available ||
        updateState is AppUpdateState.Downloading ||
        updateState is AppUpdateState.ReadyToInstall
    ) {
        UpdateAvailableDialog(
            state = updateState,
            currentVersion = BuildConfig.VERSION_NAME,
            onDownload = { info -> vm.downloadUpdate(info) },
            onInstall = { path -> launchInstall(path) },
            onOpenInBrowser = { info -> uriHandler.openUri(info.releasePageUrl) },
            onDismiss = { vm.dismissUpdate() },
        )
    }
}

/** 安全分组内的状态头：颜色与文字随当前解锁配置变化 */
@Composable
private fun SecurityStatusHeader(pinSet: Boolean, bioOn: Boolean) {
    val cs = MaterialTheme.colorScheme
    val lockOn = pinSet || bioOn
    val container = if (lockOn) cs.primaryContainer.copy(alpha = 0.55f)
    else cs.errorContainer.copy(alpha = 0.55f)
    val content = if (lockOn) cs.onPrimaryContainer else cs.onErrorContainer
    val icon = if (lockOn) Icons.Filled.GppGood else Icons.Filled.GppMaybe
    val title = when {
        pinSet && bioOn -> "生物识别 + PIN 已开启"
        bioOn -> "生物识别解锁已开启"
        pinSet -> "PIN 解锁已开启"
        else -> "应用锁未开启"
    }
    val desc = when {
        pinSet && bioOn -> "解锁优先使用生物识别，PIN 作为备用。"
        bioOn -> "未设置 PIN。若设备生物识别失效将无法验证，建议同时开启 PIN 备用。"
        pinSet -> "可再开启生物识别，解锁更快捷。"
        else -> "数据已加密存储；开启下方任一方式后，打开应用需先解锁。"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(container)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = content)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = content.copy(alpha = 0.85f), lineHeight = 16.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** 图标对齐的行内分隔线 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun <T> DropdownSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    options: List<Pair<T, String>>,
    current: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { if (enabled) expanded = true },
    ) {
        Box {
            Text(
                options.firstOrNull { it.first == current }?.second ?: "$current",
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 14.sp,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    confirmText: String,
    requireConfirm: Boolean,
    message: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) {
                    Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("备份密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirm) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("确认备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    password.length < 8 -> "备份密码至少需要 8 位"
                    requireConfirm && password != confirm -> "两次输入的备份密码不一致"
                    else -> {
                        onConfirm(password)
                        null
                    }
                }
            }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun VerifyPinDialog(
    pinManager: PinManager,
    title: String,
    message: String,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PinField(pin, { pin = it }, "当前 PIN")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pinManager.remainingLockoutMs() > 0 -> "尝试次数过多，请稍后再试"
                    !pinManager.verifyPin(pin) -> "PIN 不正确"
                    else -> {
                        onVerified()
                        onDismiss()
                        null
                    }
                }
            }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChangePinDialog(pinManager: PinManager, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改 PIN") },
        text = {
            Column {
                PinField(current, { current = it }, "当前 PIN")
                Spacer(Modifier.height(8.dp))
                PinField(newPin, { newPin = it }, "新 PIN（6 位数字）")
                Spacer(Modifier.height(8.dp))
                PinField(confirm, { confirm = it }, "确认新 PIN")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pinManager.remainingLockoutMs() > 0 -> "尝试次数过多，请稍后再试"
                    !pinManager.verifyPin(current) -> "当前 PIN 不正确"
                    newPin.length != 6 -> "新 PIN 必须是 6 位数字"
                    newPin != confirm -> "两次输入的新 PIN 不一致"
                    else -> {
                        pinManager.setPin(newPin)
                        onDismiss()
                        null
                    }
                }
            }) { Text("确认修改") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
