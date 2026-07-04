package com.cardvault.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cardvault.app.AppContainer
import com.cardvault.app.data.AppSettings
import com.cardvault.app.data.CardEntity
import com.cardvault.app.data.CardSortMode
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardStyles
import com.cardvault.app.domain.CardValidation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val uiState by vm.uiState.collectAsState()
    val filter by vm.filter.collectAsState()
    val cards = uiState.cards

    // 搜索词本地持有、同步写回 ViewModel：文本若经 StateFlow 异步回环再驱动输入框，
    // 中文输入法的组合窗口会丢字/跳光标
    var searchText by rememberSaveable { mutableStateOf(vm.query.value) }

    var detailCard by remember { mutableStateOf<CardEntity?>(null) }
    var showDetail by remember { mutableStateOf(false) }
    val canReorder = filter == CardFilter.ALL && searchText.isBlank() &&
        uiState.sortMode == CardSortMode.MANUAL

    val snackbarHostState = remember { SnackbarHostState() }

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 钱包式堆叠的重叠高度；相邻两张卡顶部的间距 = 卡片实测高度 - stackOverlap
    val stackOverlap = 136.dp
    val stackOverlapPx = with(density) { stackOverlap.toPx() }
    val edgeZonePx = with(density) { 92.dp.toPx() }
    val maxAutoScrollPxPerSec = with(density) { 540.dp.toPx() }

    // 拖拽排序（仿 Apple 钱包）：liftedId 表示手指仍按住的卡，
    // draggingId 表示位移仍归手动控制的卡——松手后回弹期间依然持有，动画结束才交还
    var liftedId by remember { mutableStateOf<Long?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var workingOrder by remember { mutableStateOf<List<CardEntity>?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    var autoScrollVelocity by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // 详情中的卡被编辑/删除后同步刷新
    val liveDetail = detailCard?.let { d -> cards.firstOrNull { it.id == d.id } ?: d }
    // 拖拽期间渲染本地顺序；落库回流后再切回数据库顺序，两者一致所以不会闪跳
    val displayCards = workingOrder ?: cards

    // 越过半格即交换位置（邻卡由 animateItem 弹簧让位），并同步修正位移，
    // 保证被拖的卡始终吸在手指下而不是跳格
    fun applyDragSwaps() {
        val id = draggingId ?: return
        var order = workingOrder ?: return
        val step = itemHeightPx - stackOverlapPx
        if (step <= 0f) return
        var index = order.indexOfFirst { it.id == id }
        if (index < 0) return
        var offset = dragOffsetPx
        var moved = false
        while (offset > step / 2f && index < order.lastIndex) {
            order = order.move(index, index + 1); index++; offset -= step; moved = true
        }
        while (offset < -step / 2f && index > 0) {
            order = order.move(index, index - 1); index--; offset += step; moved = true
        }
        if (moved) {
            workingOrder = order
            dragOffsetPx = offset
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // 拖近列表上下边缘时自动滚动，越深入越快
    fun updateAutoScroll() {
        val id = draggingId ?: run { autoScrollVelocity = 0f; return }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == id }
            ?: run { autoScrollVelocity = 0f; return }
        val top = item.offset + dragOffsetPx
        val bottom = top + item.size
        autoScrollVelocity = when {
            top < info.viewportStartOffset + edgeZonePx ->
                -maxAutoScrollPxPerSec *
                    ((info.viewportStartOffset + edgeZonePx - top) / edgeZonePx).coerceAtMost(1f)
            bottom > info.viewportEndOffset - edgeZonePx ->
                maxAutoScrollPxPerSec *
                    ((bottom - (info.viewportEndOffset - edgeZonePx)) / edgeZonePx).coerceAtMost(1f)
            else -> 0f
        }
    }

    // 松手：立即落库（防中途退出丢顺序），再用弹簧把残余位移收敛进槽位；
    // zIndex 随手指抬起已还原，回弹过程视觉上是“插回卡包”
    fun finishDrag() {
        liftedId = null
        autoScrollVelocity = 0f
        val order = workingOrder
        if (order != null && order.map { it.id } != cards.map { it.id }) {
            vm.reorder(order.map { it.id })
        }
        settleJob = scope.launch {
            animate(
                initialValue = dragOffsetPx,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 430f),
            ) { value, _ -> dragOffsetPx = value }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            draggingId = null
            if (workingOrder?.map { it.id } == cards.map { it.id }) workingOrder = null
        }
    }

    // 数据库顺序回流且不在拖拽中时丢弃本地顺序（回弹中到达的排队到 finishDrag 末尾处理）
    LaunchedEffect(cards) {
        if (draggingId == null) workingOrder = null
    }

    // 边缘自动滚动：手指停住也持续滚；滚动量补偿进位移，让卡钉在手指下
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now
            val velocity = autoScrollVelocity
            if (velocity != 0f && dt > 0f) {
                val consumed = listState.scrollBy(velocity * dt)
                if (consumed != 0f) {
                    dragOffsetPx += consumed
                    applyDragSwaps()
                }
                updateAutoScroll()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // 顶栏：搜索 + 排序 + 设置
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        vm.query.value = it
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索持卡人 / 银行 / 卡号…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                SortMenuButton(current = uiState.sortMode, onSelect = vm::setSortMode)
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
                                when {
                                    f == CardFilter.EXPIRING && uiState.expiringCount > 0 ->
                                        "${f.label} ${uiState.expiringCount}"
                                    f == CardFilter.ARCHIVED && uiState.archivedCount > 0 ->
                                        "${f.label} ${uiState.archivedCount}"
                                    else -> f.label
                                }
                            )
                        },
                    )
                }
            }

            if (uiState.loading) {
                // 数据库首个快照未到：留白，避免冷启动闪现「还没有卡片」空态
            } else if (displayCards.isEmpty()) {
                EmptyHint(filter, searchText, uiState.noticeDays, onAdd)
            } else {
                // 搜索词/筛选变化后列表数据集变了，旧的滚动锚点会把内容顶出屏幕，
                // 统一回到顶部（修复：删除搜索词后卡片被顶上去，收起键盘才恢复）
                LaunchedEffect(searchText, filter) { listState.scrollToItem(0) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                    // 钱包式堆叠：负间距让卡片相互覆盖，只露出每张的顶部
                    verticalArrangement = Arrangement.spacedBy(-stackOverlap),
                ) {
                    itemsIndexed(displayCards, key = { _, c -> c.id }) { index, card ->
                        val preset = remember(card.styleId, card.bankCode, card.brand) {
                            CardStyles.resolve(
                                card.styleId, card.bankCode, CardBrand.fromName(card.brand)
                            )
                        }
                        val lifted = liftedId == card.id
                        // 拎起：轻微放大 + 阴影加深；抬手立即回落，与位移回弹并行
                        val liftScale by animateFloatAsState(
                            targetValue = if (lifted) 1.045f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 720f),
                            label = "liftScale",
                        )
                        val liftElevation by animateDpAsState(
                            targetValue = if (lifted) 26.dp else 8.dp,
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = 480f),
                            label = "liftElevation",
                        )
                        val dragModifier = if (canReorder) {
                            Modifier.pointerInput(card.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        settleJob?.cancel()
                                        // 回弹途中再次拎起同一张卡：保留残余位移接着拖，不跳变
                                        if (draggingId != card.id) dragOffsetPx = 0f
                                        liftedId = card.id
                                        draggingId = card.id
                                        workingOrder = workingOrder ?: cards
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (draggingId == card.id) {
                                            dragOffsetPx += dragAmount.y
                                            applyDragSwaps()
                                            updateAutoScroll()
                                        }
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                )
                            }
                        } else {
                            Modifier
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // 按住期间浮在最上层；抬手即还原，回弹时卡片滑入下方卡片底下，
                                // 视觉上像插回卡包（zIndex 若持续到动画结束，收尾会有一次遮挡跳变）
                                .zIndex(if (lifted) 1f else 0f)
                                .then(
                                    if (draggingId == card.id) Modifier
                                    else Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = 380f,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                    )
                                )
                                .onSizeChanged { itemHeightPx = it.height }
                                .then(dragModifier)
                                .graphicsLayer {
                                    var manualOffset = 0f
                                    if (draggingId == card.id) {
                                        val raw = dragOffsetPx
                                        // 拖出首尾之外时加阻尼，仿 iOS 橡皮筋
                                        manualOffset = if (
                                            (index == 0 && raw < 0f) ||
                                            (index == displayCards.lastIndex && raw > 0f)
                                        ) raw * 0.35f else raw
                                        translationY = manualOffset
                                    }
                                    // 顶部收纳：卡片顶边越过视口上缘后轻微缩小、变暗，
                                    // 像被塞回卡包一样滑进上方卡片底下再离场。
                                    // 纯粹是滚动位置的函数——滚动帧只更新图层矩阵，
                                    // 不触发重组也不重录制绘制指令
                                    val step = itemHeightPx - stackOverlapPx
                                    var tuck = 0f
                                    if (step > 0f) {
                                        val info = listState.layoutInfo
                                        val item = info.visibleItemsInfo
                                            .firstOrNull { it.key == card.id }
                                        if (item != null) {
                                            tuck = ((info.viewportStartOffset -
                                                (item.offset + manualOffset)) / (step * 0.6f))
                                                .coerceIn(0f, 1f)
                                        }
                                    }
                                    // 顶边为轴：收纳时卡片底部先退后，顶边保持贴着滑出轨迹
                                    transformOrigin = TransformOrigin(0.5f, 0f)
                                    val scale = liftScale * (1f - 0.05f * tuck)
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = 1f - 0.16f * tuck * tuck
                                }
                                .shadow(liftElevation, RoundedCornerShape(20.dp))
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
                onArchive = {
                    val target = !card.archived
                    vm.setArchived(card, target)
                    showDetail = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (target) "已归档，可在「已归档」分类中找到" else "已取消归档"
                        )
                    }
                },
                onDelete = {
                    vm.delete(card)
                    showDetail = false
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除「${card.bankName.ifBlank { "银行卡" }} •••• ${card.number.takeLast(4)}」",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.restore(card)
                    }
                },
            )
        }
    }
}

private fun List<CardEntity>.move(from: Int, to: Int): List<CardEntity> {
    if (from == to) return this
    val copy = toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
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
            .padding(top = 14.dp, end = 14.dp),
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
private fun SortMenuButton(current: CardSortMode, onSelect: (CardSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CardSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = {
                        if (mode == current) {
                            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(filter: CardFilter, query: String, noticeDays: Int, onAdd: () -> Unit) {
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
                filter == CardFilter.EXPIRING -> "没有 $noticeDays 天内到期的卡片"
                filter == CardFilter.EXPIRED -> "没有已过期的卡片"
                filter == CardFilter.ARCHIVED -> "没有已归档的卡片"
                else -> "还没有卡片"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (query.isBlank() && filter == CardFilter.ALL) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加第一张卡")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "也可以把实体卡贴在手机背面，通过 NFC 自动读取卡号",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CardDetailOverlay(
    card: CardEntity,
    container: AppContainer,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var flipped by remember(card.id) { mutableStateOf(false) }
    // 详情默认打码，防止旁人窥屏；点眼睛切换。复制始终复制真实值。
    var revealed by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val brand = CardBrand.fromName(card.brand)
    val preset = CardStyles.resolve(card.styleId, card.bankCode, brand)

    // 返回键关闭详情，而不是退出应用
    BackHandler(onBack = onDismiss)

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
        // 点空白关闭：滚动列本身承担关闭点击；卡片、信息面板、按钮各自消费自己的点击。
        // （旧实现在滚动列上放了占满全屏的空拦截层，导致只有边缝能点中关闭）
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
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
                modifier = Modifier
                    .fillMaxWidth()
                    // 面板内非复制行/分隔线的点击不应透传成「关闭」
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
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
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onArchive,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.75f)),
                ) {
                    Icon(
                        if (card.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = null,
                        Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (card.archived) "取消归档" else "归档")
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
