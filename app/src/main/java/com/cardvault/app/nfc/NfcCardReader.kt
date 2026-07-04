package com.cardvault.app.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep

class NfcCardReader {
    fun read(tag: Tag): NfcCardDraft {
        val isoDep = IsoDep.get(tag) ?: error("当前 NFC 卡片不支持 ISO-DEP")
        isoDep.timeout = 5000
        isoDep.connect()
        try {
            val aid = selectPaymentApplication(isoDep)
            val selected = transceiveOk(isoDep, selectAid(aid))
            val gpo = getProcessingOptions(isoDep, aid, selected)
            val payloads = readCardPayloads(isoDep, gpo)
            val tlvs = payloads.flatMap { parseTlvs(it) }
            val pan = tlvs.firstValue(0x5A)?.toHexDigits()
                ?: tlvs.firstValue(0x57)?.track2Pan()
                ?: error("未从 NFC 卡片读取到卡号。该卡未在 AFL 记录或 GPO 响应中提供 5A/57 卡号字段。")
            val expiry = tlvs.firstValue(0x5F24)?.expiryFromDate()
                ?: tlvs.firstValue(0x57)?.track2Expiry()
            return NfcCardDraft(
                number = pan.filter { it.isDigit() },
                expiryMonth = expiry?.first,
                expiryYear = expiry?.second,
            )
        } finally {
            isoDep.close()
        }
    }

    private fun selectPaymentApplication(isoDep: IsoDep): ByteArray {
        val ppse = runCatching {
            transceiveOk(isoDep, selectByName("2PAY.SYS.DDF01"))
        }.getOrNull()
        val aidFromDirectory = ppse
            ?.let { parseTlvs(it).flatMap { tlv -> tlv.findAll(0x4F) } }
            ?.firstOrNull()
        if (aidFromDirectory != null) return aidFromDirectory

        KNOWN_AIDS.forEach { aid ->
            if (runCatching { transceiveOk(isoDep, selectAid(aid)) }.isSuccess) return aid
        }
        error("未找到可读取的银行卡应用")
    }

    private fun getProcessingOptions(
        isoDep: IsoDep,
        aid: ByteArray,
        selectedAidResponse: ByteArray,
    ): ByteArray {
        val pdol = parseTlvs(selectedAidResponse).firstValue(0x9F38)

        val first = tryGpo(isoDep, buildGpoField(pdol, TTQ_PRIMARY))
        if (first.data != null) return first.data

        // 部分卡片（尤其 Visa payWave）在首组终端参数下拒绝，重新选中应用复位状态后换一组 TTQ 再试一次
        val second = if (pdol != null && runCatching { transceiveOk(isoDep, selectAid(aid)) }.isSuccess) {
            tryGpo(isoDep, buildGpoField(pdol, TTQ_ALTERNATE))
        } else {
            null
        }
        if (second?.data != null) return second.data

        val status = second?.status ?: first.status
        if (status == "6985") {
            error("该银行卡未开放 NFC 读取（状态码 6985）。部分银行卡默认关闭非接触读取，请改用手动录入。")
        }
        error("NFC 卡片拒绝读取，状态码 $status")
    }

    private fun tryGpo(isoDep: IsoDep, field: ByteArray): GpoResult {
        val apdu = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, field.size.toByte()) +
            field + byteArrayOf(0x00)
        val response = isoDep.transceive(apdu)
        if (response.size < 2) return GpoResult(null, "空响应")
        val sw1 = response[response.lastIndex - 1].toInt() and 0xFF
        val sw2 = response[response.lastIndex].toInt() and 0xFF
        val status = "%02X%02X".format(sw1, sw2)
        return if (sw1 == 0x90 && sw2 == 0x00) {
            GpoResult(response.copyOfRange(0, response.size - 2), status)
        } else {
            GpoResult(null, status)
        }
    }

    /** 组装 GPO 的命令模板（tag 83）。无 PDOL 时为 83 00，有则按需填充终端数据 */
    private fun buildGpoField(pdol: ByteArray?, ttq: ByteArray): ByteArray {
        val pdolData = if (pdol == null) ByteArray(0) else buildPdolData(pdol, ttq)
        return byteArrayOf(0x83.toByte(), pdolData.size.toByte()) + pdolData
    }

    /**
     * 按卡片 PDOL 请求逐项填充真实终端数据。全零会导致很多卡在 GPO 阶段返回 6985，
     * 关键在于给出有效的 TTQ（9F66）、国家/货币代码、交易日期与不可预知数。
     */
    private fun buildPdolData(pdol: ByteArray, ttq: ByteArray): ByteArray {
        val out = ArrayList<Byte>(pdol.size)
        var index = 0
        while (index < pdol.size) {
            val tagStart = index
            index = skipTag(pdol, index)
            if (index >= pdol.size) break
            val tag = pdol.copyOfRange(tagStart, index).toTagInt()
            val length = pdol[index].toInt() and 0xFF
            index++
            fitToLength(defaultForTag(tag, length, ttq), length).forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun defaultForTag(tag: Int, length: Int, ttq: ByteArray): ByteArray = when (tag) {
        0x9F66 -> ttq                                              // TTQ 终端交易属性
        0x9F1A -> byteArrayOf(0x01, 0x56)                          // 终端国家代码 156（中国）
        0x5F2A -> byteArrayOf(0x01, 0x56)                          // 交易货币代码 156（CNY）
        0x9F02 -> byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0x00)  // 授权金额 1.00（避免零金额被拒）
        0x9A -> currentDateBcd()                                   // 交易日期 YYMMDD
        0x9C -> byteArrayOf(0x00)                                  // 交易类型：消费
        0x9F37 -> randomBytes(if (length > 0) length else 4)       // 不可预知数
        0x9F35 -> byteArrayOf(0x22)                                // 终端类型
        0x9F33 -> byteArrayOf(0x60, 0x08, 0x08)                    // 终端能力
        0x9F40 -> byteArrayOf(0x60, 0x00, 0x00, 0x00, 0x00)        // 附加终端能力
        else -> ByteArray(length)                                  // 其余项按请求长度置零
    }

    private fun fitToLength(value: ByteArray, length: Int): ByteArray = when {
        length <= 0 -> ByteArray(0)
        value.size == length -> value
        value.size > length -> value.copyOfRange(0, length)
        else -> value + ByteArray(length - value.size)
    }

    private fun currentDateBcd(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return byteArrayOf(
            toBcd(cal.get(java.util.Calendar.YEAR) % 100),
            toBcd(cal.get(java.util.Calendar.MONTH) + 1),
            toBcd(cal.get(java.util.Calendar.DAY_OF_MONTH)),
        )
    }

    private fun toBcd(value: Int): Byte = (((value / 10) shl 4) or (value % 10)).toByte()

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { kotlin.random.Random.nextBytes(it) }

    private fun readCardPayloads(isoDep: IsoDep, gpo: ByteArray): List<ByteArray> {
        val records = readRecords(isoDep, gpo)
        return if (records.isEmpty()) {
            listOf(gpo)
        } else {
            records + gpo
        }
    }

    private fun readRecords(isoDep: IsoDep, gpo: ByteArray): List<ByteArray> {
        val afl = extractAfl(gpo) ?: return emptyList()
        require(afl.size % 4 == 0) { "NFC AFL 记录索引格式无效" }
        val records = mutableListOf<ByteArray>()
        afl.asIterable().chunked(4).forEach { entry ->
            val sfi = (entry[0].toInt() and 0xF8) shr 3
            val first = entry[1].toInt() and 0xFF
            val last = entry[2].toInt() and 0xFF
            for (record in first..last) {
                val p2 = ((sfi shl 3) or 0x04).toByte()
                val response = runCatching {
                    transceiveOk(isoDep, byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), p2, 0x00))
                }.getOrNull()
                if (response != null) records += response
            }
        }
        return records
    }

    private fun extractAfl(gpo: ByteArray): ByteArray? {
        if (gpo.isEmpty()) return null
        if ((gpo[0].toInt() and 0xFF) == 0x80 && gpo.size >= 4) {
            val len = gpo[1].toInt() and 0xFF
            val value = gpo.copyOfRange(2, 2 + len.coerceAtMost(gpo.size - 2))
            return value.drop(2).toByteArray().takeIf { it.isNotEmpty() }
        }
        return parseTlvs(gpo).firstValue(0x94)
    }

    private fun transceiveOk(isoDep: IsoDep, apdu: ByteArray): ByteArray {
        val response = isoDep.transceive(apdu)
        require(response.size >= 2) { "NFC 卡片响应为空" }
        val sw1 = response[response.lastIndex - 1].toInt() and 0xFF
        val sw2 = response[response.lastIndex].toInt() and 0xFF
        require(sw1 == 0x90 && sw2 == 0x00) {
            "NFC 卡片拒绝读取，状态码 %02X%02X".format(sw1, sw2)
        }
        return response.copyOfRange(0, response.size - 2)
    }

    private fun selectByName(name: String): ByteArray =
        selectAid(name.toByteArray(Charsets.US_ASCII))

    private fun selectAid(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    private fun parseTlvs(bytes: ByteArray): List<Tlv> {
        val result = mutableListOf<Tlv>()
        var index = 0
        while (index < bytes.size) {
            val tagStart = index
            index = skipTag(bytes, index)
            if (index >= bytes.size) break
            val lengthInfo = readLength(bytes, index)
            index = lengthInfo.nextIndex
            if (index + lengthInfo.length > bytes.size) break
            val tag = bytes.copyOfRange(tagStart, lengthInfo.tagEnd).toTagInt()
            val value = bytes.copyOfRange(index, index + lengthInfo.length)
            val constructed = (bytes[tagStart].toInt() and 0x20) == 0x20
            result += Tlv(tag, value, if (constructed) parseTlvs(value) else emptyList())
            index += lengthInfo.length
        }
        return result
    }

    private fun skipTag(bytes: ByteArray, start: Int): Int {
        var index = start + 1
        if ((bytes[start].toInt() and 0x1F) == 0x1F) {
            while (index < bytes.size && (bytes[index].toInt() and 0x80) == 0x80) index++
            index++
        }
        return index
    }

    private fun readLength(bytes: ByteArray, start: Int): LengthInfo {
        val first = bytes[start].toInt() and 0xFF
        if ((first and 0x80) == 0) return LengthInfo(first, start + 1, start)
        val count = first and 0x7F
        require(count in 1..3) { "NFC TLV 长度格式无效" }
        var length = 0
        repeat(count) { offset ->
            length = (length shl 8) or (bytes[start + 1 + offset].toInt() and 0xFF)
        }
        return LengthInfo(length, start + 1 + count, start)
    }

    private fun List<Tlv>.firstValue(tag: Int): ByteArray? =
        asSequence().flatMap { it.flatten().asSequence() }.firstOrNull { it.tag == tag }?.value

    private fun Tlv.findAll(tag: Int): List<ByteArray> =
        flatten().filter { it.tag == tag }.map { it.value }

    private fun Tlv.flatten(): List<Tlv> = listOf(this) + children.flatMap { it.flatten() }

    private fun ByteArray.toTagInt(): Int =
        fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }

    private fun ByteArray.toHexDigits(): String =
        joinToString("") { "%02X".format(it) }.trimEnd('F')

    private fun ByteArray.track2Pan(): String? {
        val data = toHexDigits()
        val separator = data.indexOfFirst { it == 'D' || it == '=' }
        return separator.takeIf { it > 0 }?.let { data.take(it) }
    }

    private fun ByteArray.track2Expiry(): Pair<Int, Int>? {
        val data = toHexDigits()
        val separator = data.indexOfFirst { it == 'D' || it == '=' }
        if (separator < 0 || data.length < separator + 5) return null
        val yy = data.substring(separator + 1, separator + 3).toIntOrNull() ?: return null
        val month = data.substring(separator + 3, separator + 5).toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return month to (2000 + yy)
    }

    private fun ByteArray.expiryFromDate(): Pair<Int, Int>? {
        val data = toHexDigits()
        if (data.length < 4) return null
        val yy = data.substring(0, 2).toIntOrNull() ?: return null
        val month = data.substring(2, 4).toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return month to (2000 + yy)
    }

    private data class Tlv(
        val tag: Int,
        val value: ByteArray,
        val children: List<Tlv>,
    )

    private data class LengthInfo(
        val length: Int,
        val nextIndex: Int,
        val tagEnd: Int,
    )

    private data class GpoResult(val data: ByteArray?, val status: String)

    companion object {
        // TTQ（9F66）：告诉卡片终端支持的读取模式，全零会触发 6985
        private val TTQ_PRIMARY = byteArrayOf(0x36, 0x00, 0x00, 0x00)
        private val TTQ_ALTERNATE = byteArrayOf(0x27, 0x00, 0x00, 0x00)

        private val KNOWN_AIDS = listOf(
            "A000000333010101",
            "A0000000031010",
            "A0000000041010",
            "A00000002501",
            "A0000000651010",
            "A0000001523010",
        ).map { hex ->
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
