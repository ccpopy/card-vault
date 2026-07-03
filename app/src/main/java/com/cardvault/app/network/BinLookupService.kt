package com.cardvault.app.network

import com.cardvault.app.domain.CardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 发卡行在线查询（binlist.net 公共接口，免密钥，有频率限制）。
 * 支持通过用户配置的 socks5:// 或 http:// 代理访问。
 * 只发送卡号前 6-8 位（BIN），绝不上传完整卡号。
 */
class BinLookupService {

    data class BinInfo(
        val brand: CardBrand,
        val bankName: String?,
        val cardType: String?,   // debit / credit
        val country: String?,
    )

    suspend fun lookup(cardNumber: String, proxyUrl: String?): Result<BinInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bin = cardNumber.filter { it.isDigit() }.take(8)
                require(bin.length >= 6) { "卡号至少需要 6 位才能查询" }
                val client = buildClient(proxyUrl)
                val request = Request.Builder()
                    .url("https://lookup.binlist.net/$bin")
                    .header("Accept-Version", "3")
                    .build()
                client.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 404 -> error("未查询到该卡 BIN 信息")
                        resp.code == 429 -> error("查询过于频繁，请稍后再试")
                        !resp.isSuccessful -> error("查询失败：HTTP ${resp.code}")
                    }
                    val json = JSONObject(resp.body?.string().orEmpty())
                    BinInfo(
                        brand = CardBrand.fromScheme(json.optString("scheme")),
                        bankName = json.optJSONObject("bank")?.optString("name")
                            ?.takeIf { it.isNotBlank() },
                        cardType = json.optString("type").takeIf { it.isNotBlank() },
                        country = json.optJSONObject("country")?.optString("name")
                            ?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }

    /** 测试代理连通性，返回耗时毫秒 */
    suspend fun testConnection(proxyUrl: String?): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildClient(proxyUrl)
            val request = Request.Builder()
                .url("https://www.gstatic.com/generate_204")
                .build()
            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                check(resp.code in 200..299 || resp.code == 204) { "HTTP ${resp.code}" }
            }
            System.currentTimeMillis() - start
        }
    }

    private fun buildClient(proxyUrl: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
        parseProxy(proxyUrl)?.let { builder.proxy(it) }
        return builder.build()
    }

    companion object {
        /**
         * 解析代理地址：socks5://host:port、socks://host:port、http://host:port。
         * SOCKS 使用 createUnresolved，让 DNS 解析也走代理（防泄露）。
         */
        fun parseProxy(url: String?): Proxy? {
            val v = url?.trim().orEmpty()
            if (v.isEmpty()) return null
            val match = Regex("^(socks5|socks|http|https)://([^:/]+):(\\d{1,5})$", RegexOption.IGNORE_CASE)
                .find(v) ?: throw IllegalArgumentException("代理格式应为 socks5://host:port 或 http://host:port")
            val (scheme, host, portStr) = match.destructured
            val port = portStr.toInt()
            require(port in 1..65535) { "代理端口无效" }
            val type = if (scheme.startsWith("socks", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress.createUnresolved(host, port))
        }

        fun isValidProxyUrl(url: String): Boolean = try {
            parseProxy(url) != null
        } catch (_: Exception) {
            false
        }
    }
}
