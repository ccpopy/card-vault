package com.cardvault.app.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cardvault.app.AppContainer
import com.cardvault.app.security.PinManager
import kotlinx.coroutines.delay

private const val PIN_LENGTH = 6

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

fun canUseBiometric(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS

/** 弹出生物识别验证；设置页开启生物识别开关时也用它先验证一次 */
fun verifyBiometric(
    context: Context,
    title: String,
    subtitle: String,
    negativeText: String = "取消",
    onSuccess: () -> Unit,
) {
    val activity = context.findFragmentActivity() ?: return
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(info)
}

/**
 * 解锁界面。PIN 与生物识别相互独立：
 * - 仅生物识别：全屏指纹入口，无数字键盘
 * - 仅 PIN：数字键盘
 * - 两者都开：自动弹生物识别（优先），键盘随时可用；每 7 天强制输一次 PIN 防遗忘，
 *   此时若确实忘记，可先通过生物识别验证再重设 PIN
 */
@Composable
fun LockScreen(container: AppContainer) {
    val pinManager = container.pinManager
    val context = LocalContext.current
    val pinSet = remember { pinManager.isPinSet() }
    val bioUsable = remember { pinManager.isBiometricEnabled() && canUseBiometric(context) }
    val pinRecheck = remember { pinManager.needsPinRecheck() }
    var showPinReset by remember { mutableStateOf(false) }

    fun tryBiometric() {
        verifyBiometric(
            context,
            "解锁 CardVault",
            "使用指纹 / 面容解锁",
            if (pinSet) "使用 PIN" else "取消",
        ) { container.lockController.unlock() }
    }

    // 只开了生物识别但设备生物识别已不可用（如指纹被删除）：没有其他凭据可验，直接放行
    LaunchedEffect(Unit) {
        if (!pinSet && !bioUsable) container.lockController.unlock()
    }

    when {
        // 忘记 PIN：先过生物识别，再走全屏重设流程
        showPinReset -> PinSetupScreen(
            pinManager = pinManager,
            onDone = { container.lockController.unlock() },
            onCancel = { showPinReset = false },
        )

        pinSet -> PinUnlockScreen(
            container = container,
            bioUsable = bioUsable,
            pinRecheck = pinRecheck,
            onBiometric = ::tryBiometric,
            onForgotPin = if (pinRecheck && bioUsable) {
                {
                    verifyBiometric(context, "重设 PIN", "验证通过后设置新 PIN") {
                        showPinReset = true
                    }
                }
            } else null,
        )

        bioUsable -> BiometricOnlyScreen(onBiometric = ::tryBiometric)
    }
}

@Composable
private fun PinUnlockScreen(
    container: AppContainer,
    bioUsable: Boolean,
    pinRecheck: Boolean,
    onBiometric: () -> Unit,
    onForgotPin: (() -> Unit)?,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 定期校验时不自动弹生物识别，引导输入 PIN
    LaunchedEffect(Unit) {
        if (bioUsable && !pinRecheck) onBiometric()
    }

    LaunchedEffect(input) {
        if (input.length == PIN_LENGTH) {
            val lockoutMs = container.pinManager.remainingLockoutMs()
            if (lockoutMs > 0) {
                error = "尝试次数过多，请 ${lockoutMs / 1000 + 1} 秒后再试"
                input = ""
            } else if (container.pinManager.verifyPin(input)) {
                container.lockController.unlock()
            } else {
                error = "PIN 不正确"
                delay(350)
                input = ""
            }
        }
    }

    PinScaffold(
        title = if (pinRecheck) "请输入 PIN 确认" else "输入 PIN 解锁",
        subtitle = error
            ?: if (pinRecheck) "已超过 7 天未使用 PIN，定期确认以防遗忘" else null,
        isError = error != null,
        filled = input.length,
        onDigit = { if (input.length < PIN_LENGTH) { error = null; input += it } },
        onBackspace = { input = input.dropLast(1) },
        biometricSlot = bioUsable && !pinRecheck,
        onBiometric = onBiometric,
        footer = onForgotPin?.let { forgot ->
            {
                TextButton(onClick = forgot) {
                    Text("忘记 PIN？通过生物识别重设", fontSize = 13.sp)
                }
            }
        },
    )
}

/** 仅开启生物识别时的解锁页 */
@Composable
private fun BiometricOnlyScreen(onBiometric: () -> Unit) {
    LaunchedEffect(Unit) { onBiometric() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("CardVault 已锁定", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "使用指纹 / 面容解锁",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onBiometric),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = "生物识别解锁",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "点击图标解锁",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 开启 PIN 时的全屏设置流程（从设置页或忘记 PIN 重设进入） */
@Composable
fun PinSetupScreen(
    pinManager: PinManager,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var stage by remember { mutableStateOf(0) } // 0 输入 1 确认
    var first by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(input) {
        if (input.length == PIN_LENGTH) {
            if (stage == 0) {
                first = input
                input = ""
                stage = 1
            } else {
                if (input == first) {
                    pinManager.setPin(input)
                    onDone()
                } else {
                    error = "两次输入不一致，请重新设置"
                    delay(400)
                    first = ""
                    input = ""
                    stage = 0
                }
            }
        }
    }

    PinScaffold(
        title = if (stage == 0) "设置 6 位 PIN" else "再次输入确认",
        subtitle = error ?: "开启后打开应用需要解锁",
        isError = error != null,
        filled = input.length,
        onDigit = { if (input.length < PIN_LENGTH) { error = null; input += it } },
        onBackspace = { input = input.dropLast(1) },
        biometricSlot = false,
        onBiometric = {},
        footer = onCancel?.let { cancel ->
            { TextButton(onClick = cancel) { Text("取消") } }
        },
    )
}

@Composable
private fun PinScaffold(
    title: String,
    subtitle: String?,
    isError: Boolean,
    filled: Int,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    biometricSlot: Boolean,
    onBiometric: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle ?: " ",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            NumPad(
                onDigit = onDigit,
                onBackspace = onBackspace,
                biometricSlot = biometricSlot,
                onBiometric = onBiometric,
            )
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                footer()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    biometricSlot: Boolean,
    onBiometric: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { c -> PadKey(c.toString()) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (biometricSlot) {
                PadIconKey(onClick = onBiometric) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = "生物识别",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(72.dp))
            }
            PadKey("0") { onDigit('0') }
            PadIconKey(onClick = onBackspace) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PadIconKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
