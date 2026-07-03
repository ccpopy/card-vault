package com.cardvault.app.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object CardValidation {

    /** Luhn 校验（部分特殊卡种不遵循，仅作提示不强制） */
    fun luhnValid(digits: String): Boolean {
        if (digits.length < 12) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /** 按卡组织分组格式化卡号：Amex 4-6-5，其余每 4 位一组 */
    fun formatNumber(digits: String, brand: CardBrand = CardBrand.detect(digits)): String {
        if (digits.isEmpty()) return ""
        return if (brand == CardBrand.AMEX) {
            buildString {
                digits.forEachIndexed { i, c ->
                    if (i == 4 || i == 10) append(' ')
                    append(c)
                }
            }
        } else {
            digits.chunked(4).joinToString(" ")
        }
    }

    /** 打码显示：只保留末四位 */
    fun maskNumber(digits: String): String {
        if (digits.length <= 4) return digits
        val masked = "•".repeat(digits.length - 4) + digits.takeLast(4)
        return masked.chunked(4).joinToString(" ")
    }

    enum class ExpiryStatus { NONE, OK, EXPIRING, EXPIRED }

    /** 卡片有效期截止到到期月份的最后一天；30 天内到期视为“即将到期” */
    fun expiryStatus(month: Int?, year: Int?, today: LocalDate = LocalDate.now()): ExpiryStatus {
        if (month == null || year == null || month !in 1..12) return ExpiryStatus.NONE
        val lastDay = YearMonth.of(year, month).atEndOfMonth()
        val days = ChronoUnit.DAYS.between(today, lastDay)
        return when {
            days < 0 -> ExpiryStatus.EXPIRED
            days <= 30 -> ExpiryStatus.EXPIRING
            else -> ExpiryStatus.OK
        }
    }

    fun daysUntilExpiry(month: Int?, year: Int?, today: LocalDate = LocalDate.now()): Long? {
        if (month == null || year == null || month !in 1..12) return null
        return ChronoUnit.DAYS.between(today, YearMonth.of(year, month).atEndOfMonth())
    }

    fun formatExpiry(month: Int?, year: Int?): String {
        if (month == null || year == null) return "--/--"
        return "%02d/%02d".format(month, year % 100)
    }

    /** 模糊搜索：先做包含匹配，再做“子序列”匹配（如输入 gsh 命中 工商银行 gongshang 不做拼音，仅字符序） */
    fun fuzzyMatch(haystack: String, query: String): Boolean {
        val h = haystack.lowercase()
        val q = query.lowercase().filterNot { it.isWhitespace() }
        if (q.isEmpty()) return true
        if (h.contains(q)) return true
        var qi = 0
        for (c in h) {
            if (qi < q.length && c == q[qi]) qi++
            if (qi == q.length) return true
        }
        return false
    }
}
