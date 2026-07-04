package com.cardvault.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 守护 BIN 表数据质量：重复条目、脏前缀在这里被拦截，而不是上线后误判发卡行 */
class BankDirectoryTest {

    @Test
    fun `同一 BIN 不允许挂在多家银行名下`() {
        assertEquals(
            "重复 BIN（同一前缀出现在多家银行）：${BankDirectory.duplicateBins}",
            emptyList<String>(),
            BankDirectory.duplicateBins,
        )
    }

    @Test
    fun `BIN 必须是 4-8 位纯数字前缀`() {
        val bad = BankDirectory.banks
            .flatMap { bank -> bank.bins.map { bank.code to it } }
            .filterNot { (_, bin) -> bin.length in 4..8 && bin.all { it in '0'..'9' } }
        assertTrue("非法 BIN 条目：$bad", bad.isEmpty())
    }

    @Test
    fun `同一家银行内不允许重复 BIN`() {
        val dupes = BankDirectory.banks
            .flatMap { bank ->
                bank.bins.groupingBy { it }.eachCount()
                    .filterValues { it > 1 }
                    .keys.map { bank.code to it }
            }
        assertTrue("银行内部重复 BIN：$dupes", dupes.isEmpty())
    }

    @Test
    fun `最长前缀优先匹配`() {
        // 622700 建行；6227 不应命中任何 4 位表项而误判
        assertEquals("CCB", BankDirectory.findBank("6227001234567890")?.code)
        // 未知前缀不命中
        assertEquals(null, BankDirectory.findBank("9999991234567890"))
    }
}
