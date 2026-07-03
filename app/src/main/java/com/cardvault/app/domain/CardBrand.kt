package com.cardvault.app.domain

/**
 * 卡组织（卡品牌），基于卡号前缀（IIN/BIN）本地识别。
 * 注意：银联 62 开头优先于 Discover 的 6 系列判断。
 */
enum class CardBrand(val displayName: String) {
    UNIONPAY("银联 UnionPay"),
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    JCB("JCB"),
    DISCOVER("Discover"),
    DINERS("Diners Club"),
    UNKNOWN("未知卡组织");

    companion object {
        fun detect(digits: String): CardBrand {
            if (digits.isBlank()) return UNKNOWN

            fun prefixIn(len: Int, from: Int, to: Int): Boolean {
                if (digits.length < len) return false
                val p = digits.take(len).toIntOrNull() ?: return false
                return p in from..to
            }

            return when {
                digits.startsWith("62") -> UNIONPAY
                digits.startsWith("4") -> VISA
                prefixIn(2, 51, 55) || prefixIn(4, 2221, 2720) -> MASTERCARD
                digits.startsWith("34") || digits.startsWith("37") -> AMEX
                prefixIn(4, 3528, 3589) -> JCB
                digits.startsWith("6011") || prefixIn(3, 644, 649) || digits.startsWith("65") -> DISCOVER
                prefixIn(3, 300, 305) || digits.startsWith("36") || digits.startsWith("38") -> DINERS
                else -> UNKNOWN
            }
        }

        fun fromName(name: String?): CardBrand =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN

        /** 将 binlist 等接口返回的 scheme 字符串映射为本地枚举 */
        fun fromScheme(scheme: String?): CardBrand = when (scheme?.lowercase()?.trim()) {
            "visa" -> VISA
            "mastercard" -> MASTERCARD
            "unionpay", "china union pay" -> UNIONPAY
            "amex", "american express" -> AMEX
            "jcb" -> JCB
            "discover" -> DISCOVER
            "diners", "diners club" -> DINERS
            else -> UNKNOWN
        }
    }
}
