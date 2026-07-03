package com.cardvault.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardvault.app.AppContainer
import com.cardvault.app.data.AppSettings
import com.cardvault.app.data.CardEntity
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardStyles
import com.cardvault.app.domain.CardValidation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    container: AppContainer,
    settings: AppSettings,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val cards by vm.cards.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val expiringCount by vm.expiringCount.collectAsState()

    var detailCard by remember { mutableStateOf<CardEntity?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    // 详情中的卡被编辑/删除后同步刷新
    val liveDetail = detailCard?.let { d -> cards.firstOrNull { it.id == d.id } ?: d }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加卡片")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 顶栏：搜索 + 设置
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索持卡人 / 银行 / 卡号…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            }

            // 到期分类
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.filter.value = f },
                        label = {
                            Text(
                                if (f == CardFilter.EXPIRING && expiringCount > 0)
                                    "${f.label} $expiringCount" else f.label
                            )
                        },
                    )
                }
            }

            if (cards.isEmpty()) {
                EmptyHint(filter, query)
            } else {
                // 钱包式堆叠：负间距让卡片相互覆盖，只露出每张的顶部
                val listState = rememberLazyListState()
                // 搜索词/筛选变化后列表数据集变了，旧的滚动锚点会把内容顶出屏幕，
                // 统一回到顶部（修复：删除搜索词后卡片被顶上去，收起键盘才恢复）
                LaunchedEffect(query, filter) { listState.scrollToItem(0) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy((-136).dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        val preset = CardStyles.resolve(
                            card.styleId, card.bankCode, CardBrand.fromName(card.brand)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .shadow(8.dp, RoundedCornerShape(20.dp))
                        ) {
                            BankCardFront(
                                card = card,
                                preset = preset,
                                masked = settings.maskNumbers,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.586f)
                                    .clickable {
                                        detailCard = card
                                        showDetail = true
                                    },
                            )
                            ExpiryBadge(card)
                        }
                    }
                }
            }
        }
    }

    // 弹出详情：缩放 + 淡入动画，卡片可点击翻转，字段点击复制
    AnimatedVisibility(
        visible = showDetail && liveDetail != null,
        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.85f, animationSpec = tween(260)),
        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.9f, animationSpec = tween(180)),
    ) {
        liveDetail?.let { card ->
            CardDetailOverlay(
                card = card,
                container = container,
                settings = settings,
                onDismiss = { showDetail = false },
                onEdit = {
                    showDetail = false
                    onEdit(card.id)
                },
                onDelete = {
                    vm.delete(card)
                    showDetail = false
                },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ExpiryBadge(card: CardEntity) {
    val status = CardValidation.expiryStatus(card.expiryMonth, card.expiryYear)
    val (text, color) = when (status) {
        CardValidation.ExpiryStatus.EXPIRED -> "已过期" to Color(0xFFB3261E)
        CardValidation.ExpiryStatus.EXPIRING -> {
            val days = CardValidation.daysUntilExpiry(card.expiryMonth, card.expiryYear) ?: 0
            "${days}天后到期" to Color(0xFFE65100)
        }
        else -> return
    }
    Surface(
        color = color,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 14.dp, end = 56.dp),
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyHint(filter: CardFilter, query: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CreditCard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                query.isNotBlank() -> "没有匹配「$query」的卡片"
                filter == CardFilter.EXPIRING -> "没有 30 天内到期的卡片"
                filter == CardFilter.EXPIRED -> "没有已过期的卡片"
                else -> "还没有卡片，点右下角 + 添加"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardDetailOverlay(
    card: CardEntity,
    container: AppContainer,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var flipped by remember(card.id) { mutableStateOf(false) }
    // 详情默认打码，防止旁人窥屏；点眼睛切换。复制始终复制真实值。
    var revealed by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val brand = CardBrand.fromName(card.brand)
    val preset = CardStyles.resolve(card.styleId, card.bankCode, brand)

    fun copy(label: String, value: String) {
        container.clipboardHelper.copy(context, label, value, settings.clipboardClearSeconds)
    }

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
                // 拦截点击，避免点内容误关闭
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            FlippableBankCard(
                card = card,
                preset = preset,
                masked = !revealed,
                flipped = flipped,
                onFlip = { flipped = !flipped },
                onToggleReveal = { revealed = !revealed },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.586f)
                    .shadow(16.dp, RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "点击卡片翻面查看 CVV",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    CopyRow("持卡人", card.cardholder) { copy("持卡人", card.cardholder) }
                    HorizontalDivider()
                    CopyRow(
                        "卡号",
                        if (revealed) CardValidation.formatNumber(card.number, brand)
                        else CardValidation.maskNumber(card.number),
                        mono = true,
                    ) { copy("卡号", card.number) }
                    HorizontalDivider()
                    CopyRow(
                        "有效期",
                        if (revealed) CardValidation.formatExpiry(card.expiryMonth, card.expiryYear) else "••/••",
                    ) {
                        copy("有效期", CardValidation.formatExpiry(card.expiryMonth, card.expiryYear))
                    }
                    if (!card.cvv.isNullOrBlank()) {
                        HorizontalDivider()
                        CopyRow("CVV", if (revealed) card.cvv else "•••") { copy("CVV", card.cvv) }
                    }
                    HorizontalDivider()
                    CopyRow("发卡行", card.bankName.ifBlank { "未填写" }, copyable = card.bankName.isNotBlank()) {
                        copy("发卡行", card.bankName)
                    }
                    HorizontalDivider()
                    CopyRow("卡组织", brand.displayName, copyable = false) {}
                    if (card.alias.isNotBlank()) {
                        HorizontalDivider()
                        CopyRow("备注", card.alias) { copy("备注", card.alias) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑")
                }
                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.75f)),
                ) {
                    Text("关闭")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张卡？") },
            text = { Text("将从本机永久删除「${card.bankName.ifBlank { "银行卡" }} •••• ${card.number.takeLast(4)}」，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CopyRow(
    label: String,
    value: String,
    mono: Boolean = false,
    copyable: Boolean = true,
    onCopy: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = copyable, onClick = onCopy)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (copyable) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制$label",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
