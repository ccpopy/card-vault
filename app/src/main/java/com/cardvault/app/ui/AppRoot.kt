package com.cardvault.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            route = "edit?cardId={cardId}",
            arguments = listOf(navArgument("cardId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { backStackEntry ->
            val cardId = backStackEntry.arguments?.getLong("cardId") ?: -1L
            val vm: EditCardViewModel = viewModel(key = "edit_$cardId") {
                EditCardViewModel(
                    repo = container.cardRepository,
                    binService = container.binLookupService,
                    settingsRepo = container.settingsRepository,
                    cardId = cardId,
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
                SettingsViewModel(container.settingsRepository, container.binLookupService)
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
