package com.cardvault.app.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object CardValidation {

    /** Luhn 校验（部分特殊卡种不遵循，仅作提示不强制）；仅接受 12-19 位 ASCII 数字 */
    fun luhnValid(digits: String): Boolean {
        if (digits.length !in 12..19) return false
        if (digits.any { it !in '0'..'9' }) return false
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

    /**
     * 打码显示：只保留末四位。圆点按 4 个一组，末四位固定独立成组，
     * 这样 19 位（国内储蓄卡）与 15 位（Amex）卡号的末四位不会被拆散到两组里。
     */
    fun maskNumber(digits: String): String {
        if (digits.length <= 4) return digits
        val dots = "•".repeat(digits.length - 4).chunked(4)
        return (dots + digits.takeLast(4)).joinToString(" ")
    }

    enum class ExpiryStatus { NONE, OK, EXPIRING, EXPIRED }

    /** 卡片有效期截止到到期月份的最后一天；noticeDays 天内到期视为“即将到期”（默认 30） */
    fun expiryStatus(
        month: Int?,
        year: Int?,
        noticeDays: Int = 30,
        today: LocalDate = LocalDate.now(),
    ): ExpiryStatus {
        if (month == null || year == null || month !in 1..12 || year !in 1900..9999) {
            return ExpiryStatus.NONE
        }
        val lastDay = YearMonth.of(year, month).atEndOfMonth()
        val days = ChronoUnit.DAYS.between(today, lastDay)
        return when {
            days < 0 -> ExpiryStatus.EXPIRED
            days <= noticeDays -> ExpiryStatus.EXPIRING
            else -> ExpiryStatus.OK
        }
    }

    fun daysUntilExpiry(month: Int?, year: Int?, today: LocalDate = LocalDate.now()): Long? {
        if (month == null || year == null || month !in 1..12 || year !in 1900..9999) return null
        return ChronoUnit.DAYS.between(today, YearMonth.of(year, month).atEndOfMonth())
    }

    fun formatExpiry(month: Int?, year: Int?): String {
        if (month == null || year == null || month !in 1..12 || year < 0) return "--/--"
        return "%02d/%02d".format(month, year % 100)
    }

    /**
     * 归一化有效期：月份限 1-12，两位年份补全为 20xx，超出合理范围返回 null。
     * 编辑页、NFC、备份导入共用，保证入库的年份恒为四位。
     */
    fun normalizeExpiry(month: Int?, year: Int?): Pair<Int, Int>? {
        if (month == null || year == null || month !in 1..12) return null
        val fullYear = when (year) {
            in 0..99 -> 2000 + year
            in 1900..2199 -> year
            else -> return null
        }
        return month to fullYear
    }

    /**
     * 模糊搜索：先做包含匹配，再做“子序列”匹配。
     * 纯数字查询只走包含匹配——子序列会让「1234」命中任何散布着 1…2…3…4 的卡号。
     */
    fun fuzzyMatch(haystack: String, query: String): Boolean {
        val h = haystack.lowercase()
        val q = query.lowercase().filterNot { it.isWhitespace() }
        if (q.isEmpty()) return true
        if (h.contains(q)) return true
        if (q.all { it in '0'..'9' }) return false
        var qi = 0
        for (c in h) {
            if (qi < q.length && c == q[qi]) qi++
            if (qi == q.length) return true
        }
        return false
    }
}
