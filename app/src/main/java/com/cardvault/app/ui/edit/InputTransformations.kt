package com.cardvault.app.ui.edit

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 卡号每 4 位插入空格显示（输入内容仍为纯数字） */
class CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(19)
        val out = buildString {
            digits.forEachIndexed { i, c ->
                append(c)
                if (i % 4 == 3 && i != digits.lastIndex) append(' ')
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var spaces = 0
                for (i in 0 until offset.coerceAtMost(digits.length)) {
                    if (i % 4 == 3 && i != digits.lastIndex) spaces++
                }
                return (offset + spaces).coerceAtMost(out.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                var spaces = 0
                for (i in digits.indices) {
                    if (i % 4 == 3 && i != digits.lastIndex) {
                        val spacePos = i + 1 + spaces
                        if (spacePos < offset) spaces++
                    }
                }
                return (offset - spaces).coerceIn(0, digits.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

/** 输入 MMYY 四位数字，显示为 MM/YY */
class ExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text.take(4)
        val out = if (t.length > 2) t.substring(0, 2) + "/" + t.substring(2) else t
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (t.length > 2 && offset > 2) (offset + 1).coerceAtMost(out.length)
                else offset.coerceAtMost(out.length)

            override fun transformedToOriginal(offset: Int): Int =
                if (t.length > 2 && offset > 2) (offset - 1).coerceIn(0, t.length)
                else offset.coerceIn(0, t.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
