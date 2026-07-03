package com.cardvault.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.cardvault.app.domain.CardBrand
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(
    private val context: Context,
    private val cardRepository: CardRepository,
) {
    suspend fun exportTo(uri: Uri, password: String): Int {
        requireValidPassword(password)
        val cards = cardRepository.getAll()
        val payload = JSONObject()
            .put("format", PAYLOAD_FORMAT)
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("cards", JSONArray(cards.map(::cardToJson)))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val envelope = encrypt(payload, password)

        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("无法打开备份文件用于写入")

        return cards.size
    }

    suspend fun importFrom(uri: Uri, password: String): ImportSummary {
        requireValidPassword(password)
        val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法打开备份文件用于读取")

        val payload = decrypt(JSONObject(encrypted), password)
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.optString("format") == PAYLOAD_FORMAT) { "备份文件格式不受支持" }
        require(root.optInt("version") == 1) { "备份文件版本不受支持" }

        val cardsJson = root.getJSONArray("cards")
        val cards = List(cardsJson.length()) { index ->
            jsonToCard(cardsJson.getJSONObject(index))
        }
        return cardRepository.importCards(cards)
    }

    private fun encrypt(payload: ByteArray, password: String): JSONObject {
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(password, salt)
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

    private fun decrypt(envelope: JSONObject, password: String): ByteArray {
        require(envelope.optString("format") == ENVELOPE_FORMAT) { "备份文件格式不受支持" }
        require(envelope.optInt("version") == 1) { "备份文件版本不受支持" }
        require(envelope.optString("kdf") == KDF) { "备份文件 KDF 不受支持" }
        require(envelope.optString("cipher") == CIPHER) { "备份文件加密算法不受支持" }
        require(envelope.optInt("iterations") == KDF_ITERATIONS) { "备份文件 KDF 迭代次数不匹配" }

        val salt = b64decode(envelope.getString("salt"))
        val iv = b64decode(envelope.getString("iv"))
        val ciphertext = b64decode(envelope.getString("payload"))
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("备份密码不正确或文件已损坏", e)
        }
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
            .put("alias", card.alias)
            .putNullable("styleId", card.styleId)
            .put("orderPosition", card.orderPosition)
            .put("createdAt", card.createdAt)
            .put("updatedAt", card.updatedAt)

    private fun jsonToCard(json: JSONObject): CardEntity {
        val number = json.getString("number").filter { it.isDigit() }
        require(number.length >= 8) { "备份文件包含无效卡号" }
        val brand = CardBrand.fromName(json.optString("brand")).name
        return CardEntity(
            cardholder = json.getString("cardholder"),
            number = number,
            expiryMonth = json.nullableInt("expiryMonth"),
            expiryYear = json.nullableInt("expiryYear"),
            cvv = json.nullableString("cvv"),
            brand = brand,
            bankName = json.optString("bankName"),
            bankCode = json.nullableString("bankCode"),
            alias = json.optString("alias"),
            styleId = json.nullableString("styleId"),
            orderPosition = json.optLong("orderPosition", 0L),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
        )
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun requireValidPassword(password: String) {
        require(password.length >= 8) { "备份密码至少需要 8 位" }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    companion object {
        private const val ENVELOPE_FORMAT = "cardvault.encrypted-backup"
        private const val PAYLOAD_FORMAT = "cardvault.backup"
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val KDF_ITERATIONS = 250_000
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
    }
}
