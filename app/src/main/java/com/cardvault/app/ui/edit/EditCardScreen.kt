package com.cardvault.app.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardStyles
import com.cardvault.app.domain.CardValidation
import com.cardvault.app.ui.home.BankCardFront

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardScreen(
    vm: EditCardViewModel,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    var validationError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加卡片" else "编辑卡片") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // 实时预览
            val preview = vm.buildPreviewCard()
            val preset = CardStyles.resolve(vm.styleId, vm.bankCode, vm.brand)
            BankCardFront(
                card = preview,
                preset = preset,
                masked = false,
                animated = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.586f),
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = vm.cardholder,
                onValueChange = { vm.cardholder = it },
                label = { Text("持卡人姓名 Cardholder *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            val numberSupportingText = buildList {
                if (vm.brand != CardBrand.UNKNOWN) {
                    add("识别为：${vm.brand.displayName}")
                }
                if (vm.number.length >= 12 && !CardValidation.luhnValid(vm.number)) {
                    add("Luhn 校验未通过，请核对卡号")
                }
            }.joinToString("；")
            OutlinedTextField(
                value = vm.number,
                onValueChange = vm::onNumberChange,
                label = { Text("卡号 *") },
                singleLine = true,
                visualTransformation = remember { CardNumberTransformation() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = numberSupportingText.takeIf { it.isNotBlank() }?.let { text ->
                    { Text(text) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))

            // 卡组织手动选择
            if (vm.brand != CardBrand.UNKNOWN || vm.number.isNotBlank()) {
                BrandSelector(
                    current = vm.brand,
                    onSelect = { vm.brandOverride = it.takeIf { b -> b != vm.detectedBrand } },
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.expiryRaw,
                    onValueChange = { vm.expiryRaw = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("MM/YY") },
                    placeholder = { Text("08/${EditCardViewModel.currentYearHint()}") },
                    singleLine = true,
                    visualTransformation = remember { ExpiryTransformation() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.cvv,
                    onValueChange = { vm.cvv = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("CVV") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = vm.bankName,
                onValueChange = vm::onBankNameChange,
                label = { Text("发卡行") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = vm::verifyOnline,
                        enabled = vm.number.length >= 6 && vm.lookupState != LookupState.Loading,
                    ) {
                        when (vm.lookupState) {
                            LookupState.Loading -> CircularProgressIndicator(
                                Modifier.size(20.dp), strokeWidth = 2.dp
                            )
                            is LookupState.Done -> Icon(
                                Icons.Filled.CloudDone, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            is LookupState.Error -> Icon(
                                Icons.Filled.CloudOff, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            else -> Icon(Icons.Filled.TravelExplore, contentDescription = "在线识别发卡行")
                        }
                    }
                },
                supportingText = {
                    when (val s = vm.lookupState) {
                        is LookupState.Done -> Text(s.message)
                        is LookupState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        else -> Text("点击右侧图标可在线查询 BIN（仅上传卡号前 8 位）")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = vm.alias,
                onValueChange = { vm.alias = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            Text("卡面样式", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            StylePicker(
                selectedId = vm.styleId,
                onSelect = { vm.styleId = it },
            )
            Spacer(Modifier.height(24.dp))

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        val err = vm.validate()
                        validationError = err
                        if (err == null) vm.save(onBack)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BrandSelector(
    current: CardBrand,
    onSelect: (CardBrand) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("卡组织：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            FilterChip(
                selected = current != CardBrand.UNKNOWN,
                onClick = { expanded = true },
                label = {
                    Text(
                        if (current == CardBrand.UNKNOWN) "选择卡组织"
                        else current.displayName
                    )
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                CardBrand.entries.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (b == CardBrand.UNKNOWN) "未指定"
                                else b.displayName
                            )
                        },
                        onClick = {
                            onSelect(b)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StylePicker(
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            StyleSwatch(
                label = "自动",
                brush = Brush.linearGradient(
                    listOf(Color(0xFF9E9E9E), Color(0xFF616161))
                ),
                selected = selectedId == null,
                onClick = { onSelect(null) },
            )
        }
        items(CardStyles.presets, key = { it.id }) { preset ->
            StyleSwatch(
                label = preset.label,
                brush = Brush.linearGradient(preset.colors),
                selected = selectedId == preset.id,
                onClick = { onSelect(preset.id) },
            )
        }
    }
}

@Composable
private fun StyleSwatch(
    label: String,
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(brush)
                .then(
                    if (selected) Modifier.border(
                        2.5.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp),
                    ) else Modifier
                )
                .clickable(onClick = onClick)
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(0.dp))
    }
}
