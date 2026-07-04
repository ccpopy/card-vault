package com.cardvault.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardValidationTest {

    @Test
    fun `打码后末四位固定独立成组`() {
        // 19 位银联储蓄卡：末四位不能被拆散
        assertEquals("•••• •••• •••• ••• 1234", CardValidation.maskNumber("6217001234567891234"))
        // 16 位
        assertEquals("•••• •••• •••• 3456", CardValidation.maskNumber("6222021234563456"))
        // 15 位 Amex
        assertEquals("•••• •••• ••• 0005", CardValidation.maskNumber("378282246310005"))
    }

    @Test
    fun `有效期归一化`() {
        assertEquals(8 to 2027, CardValidation.normalizeExpiry(8, 27))
        assertEquals(8 to 2027, CardValidation.normalizeExpiry(8, 2027))
        assertNull(CardValidation.normalizeExpiry(13, 27))
        assertNull(CardValidation.normalizeExpiry(8, 12345))
        assertNull(CardValidation.normalizeExpiry(null, 27))
    }

    @Test
    fun `Luhn 校验只认 ASCII 数字`() {
        assertTrue(CardValidation.luhnValid("4111111111111111"))
        assertFalse(CardValidation.luhnValid("4111111111111112"))
        assertFalse(CardValidation.luhnValid("４１１１１１１１１１１１１１１１")) // 全角
    }
}
