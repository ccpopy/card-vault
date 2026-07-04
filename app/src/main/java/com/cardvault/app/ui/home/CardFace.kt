package com.cardvault.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardvault.app.data.CardEntity
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardStylePreset
import com.cardvault.app.domain.CardValidation
import com.cardvault.app.ui.effects.LocalDeviceTilt
import kotlin.math.PI
import kotlin.math.sin

private const val TAU = (PI * 2.0).toFloat()

/**
 * 光效风格开关：
 * true  = 全息彩虹（货币变色油墨 / 闪卡）：倾斜时颜色相位滑动，同一点扫过整个光谱
 * false = 白色覆膜反光（回退版）
 */
private const val HOLO_EFFECT = true

/** 光带的法线方向（单位向量）：光带本体呈 "/" 斜角，接近实体卡覆膜的反光走向 */
private val SheenNormal = Offset(0.83f, 0.56f)

/** 交叉副层方向：与主层近似垂直，反向滑动制造干涉闪烁 */
private val HoloCrossNormal = Offset(0.56f, -0.83f)

/** 无缝彩虹色环（首尾同色），全息层沿对角平铺滑动 */
private val HoloColors = listOf(
    Color(0xFFFF3B6B), // 品红
    Color(0xFFFFB13B), // 橙金
    Color(0xFFF7FF52), // 黄
    Color(0xFF4BFF7E), // 绿
    Color(0xFF3BD8FF), // 青
    Color(0xFF5B6BFF), // 蓝
    Color(0xFFC44BFF), // 紫
    Color(0xFFFF3B6B), // 回到品红，保证平铺无缝
)

// 按透明度预先调好的色带（普通 / 浅色卡面减半），绘制阶段零分配
private val HoloMainColors = HoloColors.map { it.copy(alpha = 0.13f) }
private val HoloMainColorsLight = HoloColors.map { it.copy(alpha = 0.065f) }
private val HoloCrossColors = HoloColors.map { it.copy(alpha = 0.065f) }
private val HoloCrossColorsLight = HoloColors.map { it.copy(alpha = 0.0325f) }

/**
 * 卡面光效。两种风格共用同一套倾斜/待机漂移输入：
 *
 * 全息彩虹（HOLO_EFFECT = true）：
 * - 主层：彩虹光栅沿 "/" 对角平铺，相位随倾斜大增益滑动——这正是变色油墨的
 *   角度响应：倾斜十几度，卡面同一点的颜色就扫过一段光谱；
 * - 副层：第二道彩虹沿另一对角反向滑动，两层叠加处产生干涉般的闪烁；
 * - 顶层仍保留一条收敛的白色镜面高光，模拟闪卡表面的覆膜反光。
 *
 * 覆膜反光（HOLO_EFFECT = false）：仅白色光带 + 同心柔光。
 *
 * time != null（详情弹窗、编辑预览，单卡）：相位随时间缓慢爬行，并绘制完整四层；
 * time == null（列表堆叠，同屏十几张互相覆盖）：只画主彩虹层 + 白色高光带两层，
 * 交叉干涉层和柔光晕在 70dp 露出条上不可辨，省一半混合填充。
 *
 * 倾斜与时间都只在绘制阶段读取——传感器更新只触发重绘，不触发重组。
 * lightSurface = true（鎏金等浅色卡面）时彩虹透明度减半，避免发脏。
 */
fun Modifier.cardShimmer(
    enabled: Boolean,
    tilt: State<Offset>,
    time: State<Float>? = null,
    lightSurface: Boolean = false,
): Modifier = if (!enabled) this else drawWithContent {
    drawContent()
    val w = size.width
    val h = size.height
    val t = tilt.value
    val crawl = time?.value ?: 0f
    val rich = time != null

    if (HOLO_EFFECT) {
        // 主全息层：相位增益放大，小幅倾斜即可看到明显色变
        val cycleA = w * 1.25f
        val phaseA = (t.x * 1.35f - t.y * 0.85f + crawl * 0.055f) * cycleA
        drawRect(
            brush = Brush.linearGradient(
                colors = if (lightSurface) HoloMainColorsLight else HoloMainColors,
                start = Offset(SheenNormal.x * phaseA, SheenNormal.y * phaseA),
                end = Offset(
                    SheenNormal.x * (phaseA + cycleA),
                    SheenNormal.y * (phaseA + cycleA),
                ),
                tileMode = TileMode.Repeated,
            ),
            blendMode = BlendMode.Plus,
        )

        if (rich) {
            // 交叉副层：反向滑动，低透明度
            val cycleB = w * 0.90f
            val phaseB = (-t.x * 0.90f - t.y * 1.10f - crawl * 0.038f) * cycleB
            drawRect(
                brush = Brush.linearGradient(
                    colors = if (lightSurface) HoloCrossColorsLight else HoloCrossColors,
                    start = Offset(HoloCrossNormal.x * phaseB, HoloCrossNormal.y * phaseB),
                    end = Offset(
                        HoloCrossNormal.x * (phaseB + cycleB),
                        HoloCrossNormal.y * (phaseB + cycleB),
                    ),
                    tileMode = TileMode.Repeated,
                ),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // 白色镜面高光：全息版收敛一些，让彩虹当主角；回退版为主光效
    val coreAlpha = if (HOLO_EFFECT) 0.075f else 0.105f
    val softAlpha = if (HOLO_EFFECT) 0.025f else 0.035f

    val idle = if (rich) sin(crawl * TAU / 11f) * 0.09f else 0f
    val shift = (t.x * 0.60f - t.y * 0.38f + idle).coerceIn(-0.85f, 0.85f)
    val bandOffset = shift * w * 0.85f
    val center = Offset(
        w * 0.50f + SheenNormal.x * bandOffset,
        h * 0.45f + SheenNormal.y * bandOffset,
    )
    val halfSpan = w * 0.46f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = softAlpha),
                Color.White.copy(alpha = coreAlpha),
                Color.White.copy(alpha = softAlpha),
                Color.Transparent,
            ),
            start = Offset(center.x - SheenNormal.x * halfSpan, center.y - SheenNormal.y * halfSpan),
            end = Offset(center.x + SheenNormal.x * halfSpan, center.y + SheenNormal.y * halfSpan),
        ),
        blendMode = BlendMode.Plus,
    )
    if (rich) {
        val haloAlpha = if (HOLO_EFFECT) 0.035f else 0.055f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = haloAlpha), Color.Transparent),
                center = center,
                radius = w * 0.80f,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/** 详情/预览用的光效时钟：驱动待机漂移，列表卡不启动它 */
@Composable
private fun rememberShimmerClock(): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var startNanos = 0L
        while (true) {
            withFrameNanos { frame ->
                if (startNanos == 0L) startNanos = frame
                time.floatValue = ((frame - startNanos) / 1_000_000_000.0).toFloat()
            }
        }
    }
    return time
}

/** 可翻转银行卡：点击翻面；onToggleReveal 非空时在卡面内嵌显示/隐藏切换钮 */
@Composable
fun FlippableBankCard(
    card: CardEntity,
    preset: CardStylePreset,
    masked: Boolean,
    flipped: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleReveal: (() -> Unit)? = null,
    animated: Boolean = true,
) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 550),
        label = "flip",
    )
    Box(
        modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 18f * density
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFlip,
            )
    ) {
        if (rotation <= 90f) {
            BankCardFront(card, preset, masked, Modifier.fillMaxSize(), onToggleReveal, animated)
        } else {
            BankCardBack(
                card, preset, masked,
                Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                onToggleReveal,
                animated,
            )
        }
    }
}

@Composable
fun BankCardFront(
    card: CardEntity,
    preset: CardStylePreset,
    masked: Boolean,
    modifier: Modifier = Modifier,
    onToggleReveal: (() -> Unit)? = null,
    animated: Boolean = false,
) {
    val tint = if (preset.darkText) Color(0xDD1A1A1A) else Color.White
    val brand = CardBrand.fromName(card.brand)
    val tilt = LocalDeviceTilt.current
    val shimmerTime = if (animated && preset.shimmer) rememberShimmerClock() else null
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(preset.colors))
            .cardShimmer(preset.shimmer, tilt, shimmerTime, preset.darkText)
            .padding(20.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.bankName.ifBlank { "银行卡" },
                    color = tint,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BrandMark(brand, tint)
                if (onToggleReveal != null) {
                    Spacer(Modifier.width(10.dp))
                    RevealChip(masked, tint, onToggleReveal)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipGraphic()
                Spacer(Modifier.width(10.dp))
                ContactlessIcon(tint.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (masked) CardValidation.maskNumber(card.number)
                else CardValidation.formatNumber(card.number, brand),
                color = tint,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("持卡人", color = tint.copy(alpha = 0.65f), fontSize = 9.sp, letterSpacing = 1.sp)
                    Text(
                        card.cardholder.uppercase().ifBlank { "CARDHOLDER" },
                        color = tint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("VALID THRU", color = tint.copy(alpha = 0.65f), fontSize = 9.sp, letterSpacing = 1.sp)
                    Text(
                        if (masked) "••/••"
                        else CardValidation.formatExpiry(card.expiryMonth, card.expiryYear),
                        color = tint,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun BankCardBack(
    card: CardEntity,
    preset: CardStylePreset,
    masked: Boolean = false,
    modifier: Modifier = Modifier,
    onToggleReveal: (() -> Unit)? = null,
    animated: Boolean = false,
) {
    val tint = if (preset.darkText) Color(0xDD1A1A1A) else Color.White
    val brand = CardBrand.fromName(card.brand)
    val tilt = LocalDeviceTilt.current
    val shimmerTime = if (animated && preset.shimmer) rememberShimmerClock() else null
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(preset.colors.reversed()))
            .cardShimmer(preset.shimmer, tilt, shimmerTime, preset.darkText)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(22.dp))
            // 磁条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color.Black.copy(alpha = 0.88f))
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 签名条：CVV 像实体卡一样印在条带右端，天然对齐
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFEFEFEA)),
                ) {
                    Text(
                        card.cardholder,
                        color = Color(0xFF8A8A84),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp, end = 64.dp),
                    )
                    Text(
                        if (masked) "•••" else card.cvv?.ifBlank { null } ?: "•••",
                        color = Color(0xFF1A1A1A),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("CVV", color = tint.copy(alpha = 0.7f), fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "本卡仅限持卡人本人使用",
                    color = tint.copy(alpha = 0.55f),
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                )
                if (onToggleReveal != null) {
                    RevealChip(masked, tint, onToggleReveal)
                    Spacer(Modifier.width(10.dp))
                }
                BrandMark(brand, tint)
            }
        }
    }
}

@Composable
fun BrandMark(brand: CardBrand, tint: Color) {
    when (brand) {
        CardBrand.MASTERCARD -> Row {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFEB001B).copy(alpha = 0.92f))
            )
            Box(
                Modifier
                    .offset(x = (-10).dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFF79E1B).copy(alpha = 0.85f))
            )
        }

        CardBrand.VISA -> Text(
            "VISA",
            color = tint,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            fontStyle = FontStyle.Italic,
            letterSpacing = 1.sp,
        )

        CardBrand.UNIONPAY -> Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tint.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                "银联",
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }

        CardBrand.AMEX -> Text(
            "AMEX",
            color = tint,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        )

        CardBrand.JCB -> Text("JCB", color = tint, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        CardBrand.DISCOVER -> Text("DISCOVER", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        CardBrand.DINERS -> Text("Diners Club", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        CardBrand.UNKNOWN -> {}
    }
}

/** 卡面内嵌的显示/隐藏敏感信息切换钮（半透明玻璃圆钮，不占卡外空间） */
@Composable
private fun RevealChip(masked: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (masked) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = if (masked) "显示敏感信息" else "隐藏敏感信息",
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** IC 芯片图形：金色基底 + 田字触点纹路 */
@Composable
private fun ChipGraphic() {
    Box(
        Modifier
            .size(width = 42.dp, height = 30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFE8C878), Color(0xFFC9A24B), Color(0xFFE8C878))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color(0x59442E00)
            val sw = 1.4f
            val w = size.width
            val h = size.height
            // 横向纹路
            drawLine(line, Offset(0f, h * 0.35f), Offset(w, h * 0.35f), sw)
            drawLine(line, Offset(0f, h * 0.65f), Offset(w, h * 0.65f), sw)
            // 纵向纹路（避开中央触点区）
            drawLine(line, Offset(w * 0.34f, 0f), Offset(w * 0.34f, h * 0.35f), sw)
            drawLine(line, Offset(w * 0.34f, h * 0.65f), Offset(w * 0.34f, h), sw)
            drawLine(line, Offset(w * 0.66f, 0f), Offset(w * 0.66f, h * 0.35f), sw)
            drawLine(line, Offset(w * 0.66f, h * 0.65f), Offset(w * 0.66f, h), sw)
            // 中央触点
            drawRoundRect(
                color = line,
                topLeft = Offset(w * 0.34f, h * 0.35f),
                size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.30f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                style = Stroke(width = sw),
            )
        }
    }
}

/** 非接触支付（闪付）图标：三条同心弧，向右展开 */
@Composable
private fun ContactlessIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round)
        val cx = size.width * 0.16f
        val cy = size.height / 2f
        for (i in 1..3) {
            val r = size.width * 0.22f * i
            drawArc(
                color = color,
                startAngle = -48f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = stroke,
            )
        }
    }
}
