package com.cardvault.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cardvault.app.AppContainer
import com.cardvault.app.data.AppSettings
import com.cardvault.app.nfc.NfcCardDraft
import com.cardvault.app.ui.edit.EditCardScreen
import com.cardvault.app.ui.edit.EditCardViewModel
import com.cardvault.app.ui.home.HomeScreen
import com.cardvault.app.ui.home.HomeViewModel
import com.cardvault.app.ui.lock.LockScreen
import com.cardvault.app.ui.settings.SettingsScreen
import com.cardvault.app.ui.settings.SettingsViewModel

@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val locked by container.lockController.locked.collectAsState()

    // 退后台计时，回前台判断是否需要重新锁定。
    // observer 只随 lifecycleOwner 注册一次；autoLockSeconds 经 rememberUpdatedState
    // 读最新值——避免设置变化触发 remove/add observer 时补发 ON_START 的脆弱耦合
    val lifecycleOwner = LocalLifecycleOwner.current
    val autoLockSeconds by rememberUpdatedState(settings.autoLockSeconds)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> container.lockController.onAppBackground()
                Lifecycle.Event.ON_START -> container.lockController.onAppForeground(autoLockSeconds)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 覆盖式锁屏：NavHost 常驻，锁定时叠加 LockScreen。
    // 旧实现是分支替换，锁一次就把返回栈和编辑到一半的表单全部销毁
    val focusManager = LocalFocusManager.current
    LaunchedEffect(locked) {
        if (locked) focusManager.clearFocus(force = true)
    }
    Box(Modifier.fillMaxSize()) {
        MainNavHost(container, settings, locked)
        if (locked) {
            LockScreen(container)
        }
    }
}

@Composable
private fun MainNavHost(container: AppContainer, settings: AppSettings, locked: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // NFC 读卡结果：锁屏时缓冲在 StateFlow 里，解锁后在这里消费；过期(>2分钟)丢弃
    val pendingNfc by container.nfcImportController.pending.collectAsState()
    LaunchedEffect(pendingNfc, locked) {
        val pending = pendingNfc ?: return@LaunchedEffect
        if (locked) return@LaunchedEffect
        container.nfcImportController.consume(pending)
        if (!pending.isFresh()) return@LaunchedEffect
        val draft = pending.draft
        Toast.makeText(context, "已读取 NFC 卡片", Toast.LENGTH_SHORT).show()
        navController.navigate(
            "edit?cardId=-1" +
                "&nfcNumber=${Uri.encode(draft.number)}" +
                "&nfcExpiryMonth=${draft.expiryMonth ?: -1}" +
                "&nfcExpiryYear=${draft.expiryYear ?: -1}" +
                "&nfcHolder=${Uri.encode(draft.cardholder.orEmpty())}"
        ) {
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = viewModel {
                HomeViewModel(container.cardRepository, container.settingsRepository)
            }
            HomeScreen(
                vm = vm,
                container = container,
                settings = settings,
                onAdd = { navController.navigate("edit?cardId=-1") },
                onEdit = { id -> navController.navigate("edit?cardId=$id") },
                onSettings = { navController.navigate("settings") },
            )
        }

        composable(
            route = "edit?cardId={cardId}&nfcNumber={nfcNumber}&nfcExpiryMonth={nfcExpiryMonth}&nfcExpiryYear={nfcExpiryYear}&nfcHolder={nfcHolder}",
            arguments = listOf(
                navArgument("cardId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("nfcNumber") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("nfcExpiryMonth") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("nfcExpiryYear") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("nfcHolder") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val cardId = backStackEntry.arguments?.getLong("cardId") ?: -1L
            val nfcNumber = backStackEntry.arguments?.getString("nfcNumber").orEmpty()
            val nfcExpiryMonth = backStackEntry.arguments?.getInt("nfcExpiryMonth") ?: -1
            val nfcExpiryYear = backStackEntry.arguments?.getInt("nfcExpiryYear") ?: -1
            val nfcHolder = backStackEntry.arguments?.getString("nfcHolder").orEmpty()
            val nfcDraft = nfcNumber.takeIf { it.isNotBlank() }?.let {
                NfcCardDraft(
                    number = it,
                    expiryMonth = nfcExpiryMonth.takeIf { value -> value in 1..12 },
                    expiryYear = nfcExpiryYear.takeIf { value -> value > 0 },
                    cardholder = nfcHolder.takeIf { value -> value.isNotBlank() },
                )
            }
            val vm: EditCardViewModel = viewModel(key = "edit_${cardId}_${nfcNumber}") {
                EditCardViewModel(
                    repo = container.cardRepository,
                    binService = container.binLookupService,
                    settingsRepo = container.settingsRepository,
                    cardId = cardId,
                    initialDraft = nfcDraft,
                )
            }
            EditCardScreen(
                vm = vm,
                isNew = cardId <= 0,
                onBack = { navController.popBackStack() },
            )
        }

        composable("settings") {
            val vm: SettingsViewModel = viewModel {
                SettingsViewModel(
                    appContext = container.appContext,
                    settingsRepo = container.settingsRepository,
                    binService = container.binLookupService,
                    updateService = container.updateService,
                    backupManager = container.backupManager,
                    notificationScheduler = container.expiryNotificationScheduler,
                )
            }
            SettingsScreen(
                vm = vm,
                settings = settings,
                pinManager = container.pinManager,
                lockController = container.lockController,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
