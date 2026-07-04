package com.cardvault.app.domain

import androidx.compose.ui.graphics.Color

/**
 * 卡面视觉预设：渐变配色 + 是否开启流光。
 * styleId 为空时按发卡行/卡组织自动匹配默认样式，用户可在编辑页手动选择。
 */
data class CardStylePreset(
    val id: String,
    val label: String,
    val colors: List<Color>,
    val shimmer: Boolean = true,
    /** 浅色卡面（如鎏金）使用深色文字 */
    val darkText: Boolean = false,
)

object CardStyles {

    val presets: List<CardStylePreset> = listOf(
        // —— 银行主题色 ——
        CardStylePreset("icbc", "工行红", listOf(Color(0xFFD32F2F), Color(0xFF8E1414), Color(0xFF530A0A))),
        CardStylePreset("abc", "农行青", listOf(Color(0xFF26A69A), Color(0xFF00695C), Color(0xFF003D33))),
        CardStylePreset("boc", "中行绛红", listOf(Color(0xFFA31621), Color(0xFF6B0F16), Color(0xFF3D080C))),
        CardStylePreset("ccb", "建行蓝", listOf(Color(0xFF1E88E5), Color(0xFF0D47A1), Color(0xFF072A60))),
        CardStylePreset("bocom", "交行深蓝", listOf(Color(0xFF23538F), Color(0xFF102C52), Color(0xFF091A33))),
        CardStylePreset("cmb", "招行红金", listOf(Color(0xFFC62828), Color(0xFF7B1414), Color(0xFF4A0D0D))),
        CardStylePreset("psbc", "邮储绿", listOf(Color(0xFF2E7D32), Color(0xFF1B5E20), Color(0xFF0D3B12))),
        CardStylePreset("citic", "中信绯红", listOf(Color(0xFFB71C1C), Color(0xFF801313), Color(0xFF4C0B0B))),
        CardStylePreset("ceb", "光大紫金", listOf(Color(0xFF7B1FA2), Color(0xFF4A148C), Color(0xFF2A0B50))),
        CardStylePreset("cib", "兴业蓝", listOf(Color(0xFF0277BD), Color(0xFF01579B), Color(0xFF013057))),
        CardStylePreset("pab", "平安橙", listOf(Color(0xFFF4511E), Color(0xFFBF360C), Color(0xFF7A2208))),

        // —— 通用主题 ——
        CardStylePreset("midnight", "午夜黑", listOf(Color(0xFF3A3D4A), Color(0xFF23252E), Color(0xFF121318))),
        CardStylePreset("aurora", "极光", listOf(Color(0xFF12C2E9), Color(0xFFC471ED), Color(0xFFF64F59))),
        CardStylePreset("ocean", "深海蓝", listOf(Color(0xFF2193B0), Color(0xFF1B5C8C), Color(0xFF0E2A47))),
        CardStylePreset("sunset", "落日", listOf(Color(0xFFFF512F), Color(0xFFDD2476), Color(0xFF7A1350))),
        CardStylePreset("forest", "松林绿", listOf(Color(0xFF11998E), Color(0xFF0B6E56), Color(0xFF06402F))),
        CardStylePreset("violet", "星夜紫", listOf(Color(0xFF41295A), Color(0xFF2F0743), Color(0xFF190226))),
        CardStylePreset("gold", "鎏金", listOf(Color(0xFFF7E8AA), Color(0xFFE3C568), Color(0xFFC9A227)), darkText = true),
        CardStylePreset("rose", "玫瑰金", listOf(Color(0xFFF4C4BA), Color(0xFFE8A091), Color(0xFFC97B63)), darkText = true),
    )

    fun byId(id: String?): CardStylePreset? = presets.firstOrNull { it.id == id }

    private val bankDefaults = mapOf(
        "ICBC" to "icbc",
        "ABC" to "abc",
        "BOC" to "boc",
        "CCB" to "ccb",
        "BOCOM" to "bocom",
        "CMB" to "cmb",
        "PSBC" to "psbc",
        "CMBC" to "forest",
        "CITIC" to "citic",
        "CEB" to "ceb",
        "HXB" to "sunset",
        "SPDB" to "ocean",
        "CIB" to "cib",
        "PAB" to "pab",
        "CGB" to "sunset",
        "BOB" to "ocean",
        "BOS" to "aurora",
    )

    /** styleId 未指定时的自动样式：优先银行主题色，其次按卡组织 */
    fun resolve(styleId: String?, bankCode: String?, brand: CardBrand): CardStylePreset {
        byId(styleId)?.let { return it }
        bankDefaults[bankCode]?.let { id -> byId(id)?.let { return it } }
        val brandDefault = when (brand) {
            CardBrand.UNIONPAY -> "cmb"
            CardBrand.VISA -> "ocean"
            CardBrand.MASTERCARD -> "sunset"
            CardBrand.AMEX -> "forest"
            CardBrand.JCB -> "violet"
            CardBrand.MAESTRO -> "ocean"
            CardBrand.MIR -> "forest"
            else -> "midnight"
        }
        // 预设 id 与此映射是字符串耦合，byId 找不到时兜底第一项，避免改名后运行时崩溃
        return byId(brandDefault) ?: presets.first()
    }
}
