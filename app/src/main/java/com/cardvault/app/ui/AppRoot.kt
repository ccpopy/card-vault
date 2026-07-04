package com.cardvault.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import com.cardvault.app.nfc.NfcImportEvent
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

    // 退后台计时，回前台判断是否需要重新锁定
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.autoLockSeconds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> container.lockController.onAppBackground()
                Lifecycle.Event.ON_START -> container.lockController.onAppForeground(settings.autoLockSeconds)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 未开启应用锁时直接进入主界面；locked 仅在开启了 PIN 或生物识别时为 true
    if (locked) {
        LockScreen(container)
    } else {
        MainNavHost(container, settings)
    }
}

@Composable
private fun MainNavHost(container: AppContainer, settings: AppSettings) {
    val navController = rememberNavController()
    val context = LocalContext.current

    LaunchedEffect(navController) {
        container.nfcImportController.events.collect { event ->
            when (event) {
                is NfcImportEvent.CardRead -> {
                    val draft = event.draft
                    Toast.makeText(context, "已读取 NFC 卡片", Toast.LENGTH_SHORT).show()
                    navController.navigate(
                        "edit?cardId=-1" +
                            "&nfcNumber=${Uri.encode(draft.number)}" +
                            "&nfcExpiryMonth=${draft.expiryMonth ?: -1}" +
                            "&nfcExpiryYear=${draft.expiryYear ?: -1}"
                    )
                }
                is NfcImportEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = viewModel { HomeViewModel(container.cardRepository) }
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
            route = "edit?cardId={cardId}&nfcNumber={nfcNumber}&nfcExpiryMonth={nfcExpiryMonth}&nfcExpiryYear={nfcExpiryYear}",
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
            ),
        ) { backStackEntry ->
            val cardId = backStackEntry.arguments?.getLong("cardId") ?: -1L
            val nfcNumber = backStackEntry.arguments?.getString("nfcNumber").orEmpty()
            val nfcExpiryMonth = backStackEntry.arguments?.getInt("nfcExpiryMonth") ?: -1
            val nfcExpiryYear = backStackEntry.arguments?.getInt("nfcExpiryYear") ?: -1
            val nfcDraft = nfcNumber.takeIf { it.isNotBlank() }?.let {
                NfcCardDraft(
                    number = it,
                    expiryMonth = nfcExpiryMonth.takeIf { value -> value in 1..12 },
                    expiryYear = nfcExpiryYear.takeIf { value -> value > 0 },
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}
