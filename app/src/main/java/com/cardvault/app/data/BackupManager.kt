package com.cardvault.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import com.cardvault.app.domain.CardBrand
import com.cardvault.app.domain.CardValidation

/**
 * 加密备份的编解码（纯 JVM 逻辑，可单元测试）。
 * 信封字段 kdf/iterations/cipher 是解密的**实际输入**而非装饰：
 * 未来提高迭代次数或换算法时，旧备份仍按信封内记录的参数解密。
 */
object BackupCodec {

    const val ENVELOPE_FORMAT = "cardvault.encrypted-backup"
    const val PAYLOAD_FORMAT = "cardvault.backup"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"

    /** 新导出使用的迭代次数；解密按信封记录值，在安全区间内均接受 */
    const val KDF_ITERATIONS = 600_000
    private val ACCEPTED_ITERATIONS = 100_000..5_000_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    fun encrypt(payload: ByteArray, password: String): JSONObject {
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(password, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload)

        return JSONObject()
            .put("format", ENVELOPE_FORMAT)
            .put("version", 1)
            .put("kdf", KDF)
            .put("iterations", KDF_ITERATIONS)
            .put("cipher", CIPHER)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("payload", b64(ciphertext))
    }

    fun decrypt(envelope: JSONObject, password: String): ByteArray {
        require(envelope.optString("format") == ENVELOPE_FORMAT) { "备份文件格式不受支持" }
        require(envelope.optInt("version") == 1) { "备份文件版本不受支持" }
        require(envelope.optString("kdf") == KDF) { "备份文件 KDF 不受支持" }
        require(envelope.optString("cipher") == CIPHER) { "备份文件加密算法不受支持" }
        val iterations = envelope.optInt("iterations")
        require(iterations in ACCEPTED_ITERATIONS) { "备份文件 KDF 迭代次数超出安全范围" }

        val salt = b64decode(envelope.getString("salt"))
        val iv = b64decode(envelope.getString("iv"))
        val ciphertext = b64decode(envelope.getString("payload"))
        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("备份密码不正确或文件已损坏", e)
        }
    }

    fun cardsToPayload(cards: List<CardEntity>, exportedAt: Long): ByteArray =
        JSONObject()
            .put("format", PAYLOAD_FORMAT)
            .put("version", 1)
            .put("exportedAt", exportedAt)
            .put("cards", JSONArray(cards.map(::cardToJson)))
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun payloadToCards(payload: ByteArray): List<CardEntity> {
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.optString("format") == PAYLOAD_FORMAT) { "备份文件格式不受支持" }
        require(root.optInt("version") == 1) { "备份文件版本不受支持" }
        val cardsJson = root.getJSONArray("cards")
        return List(cardsJson.length()) { index -> jsonToCard(cardsJson.getJSONObject(index)) }
    }

    fun requireValidPassword(password: String) {
        require(password.length >= 8) { "备份密码至少需要 8 位" }
    }

    private fun cardToJson(card: CardEntity): JSONObject =
        JSONObject()
            .put("cardholder", card.cardholder)
            .put("number", card.number)
            .putNullable("expiryMonth", card.expiryMonth)
            .putNullable("expiryYear", card.expiryYear)
            .putNullable("cvv", card.cvv)
            .put("brand", card.brand)
            .put("bankName", card.bankName)
            .putNullable("bankCode", card.bankCode)
            .putNullable("cardType", card.cardType)
            .put("archived", card.archived)
            .put("alias", card.alias)
            .putNullable("styleId", card.styleId)
            .put("orderPosition", card.orderPosition)
            .put("createdAt", card.createdAt)
            .put("updatedAt", card.updatedAt)

    private fun jsonToCard(json: JSONObject): CardEntity {
        // 只认 ASCII 数字：Char.isDigit 会放行全角数字等 Unicode 数字，
        // 污染卡号唯一键并让 Luhn/BIN 匹配静默失效
        val number = json.getString("number").filter { it in '0'..'9' }
        require(number.length >= 8) { "备份文件包含无效卡号" }
        val brand = CardBrand.fromName(json.optString("brand")).name
        val expiry = CardValidation.normalizeExpiry(
            json.nullableInt("expiryMonth"),
            json.nullableInt("expiryYear"),
        )
        return CardEntity(
            cardholder = json.getString("cardholder"),
            number = number,
            expiryMonth = expiry?.first,
            expiryYear = expiry?.second,
            cvv = json.nullableString("cvv")?.filter { it in '0'..'9' }?.take(4)
                ?.takeIf { it.isNotEmpty() },
            brand = brand,
            bankName = json.optString("bankName"),
            bankCode = json.nullableString("bankCode"),
            cardType = CardKind.fromKey(json.nullableString("cardType"))?.key,
            archived = json.optBoolean("archived", false),
            alias = json.optString("alias"),
            styleId = json.nullableString("styleId"),
            orderPosition = json.optLong("orderPosition", 0L),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            val key = SecretKeySpec(bytes, "AES")
            bytes.fill(0)
            return key
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun b64decode(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)
}

class BackupManager(
    private val context: Context,
    private val cardRepository: CardRepository,
) {
    suspend fun exportTo(uri: Uri, password: String): Int {
        BackupCodec.requireValidPassword(password)
        val cards = cardRepository.getAll()
        val payload = BackupCodec.cardsToPayload(cards, System.currentTimeMillis())
        val envelope = BackupCodec.encrypt(payload, password)

        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("无法打开备份文件用于写入")

        return cards.size
    }

    suspend fun importFrom(uri: Uri, password: String): ImportSummary {
        BackupCodec.requireValidPassword(password)
        val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法打开备份文件用于读取")

        val payload = BackupCodec.decrypt(JSONObject(encrypted), password)
        val cards = BackupCodec.payloadToCards(payload)
        return cardRepository.importCards(cards)
    }
}
