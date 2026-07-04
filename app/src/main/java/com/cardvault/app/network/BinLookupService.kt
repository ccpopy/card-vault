package com.cardvault.app.network

import android.content.Context
import com.cardvault.app.domain.CardBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 发卡行在线查询（binlist.net 公共接口，免密钥，有频率限制）。
 * 支持通过用户配置的 socks5:// 或 http:// 代理访问。
 * 只发送卡号前 6-8 位（BIN），绝不上传完整卡号。
 * 查询成功的结果按 BIN 缓存在本地——binlist 免费档约 5 次/小时，查过一次终身可用。
 */
class BinLookupService(context: Context) {

    private val cachePrefs = context.getSharedPreferences("bin_cache", Context.MODE_PRIVATE)

    data class BinInfo(
        val brand: CardBrand,
        val bankName: String?,
        val cardType: String?,   // debit / credit / prepaid
        val country: String?,
        val fromCache: Boolean = false,
    )

    suspend fun lookup(cardNumber: String, proxyUrl: String?, offlineMode: Boolean = false): Result<BinInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bin = cardNumber.filter { it in '0'..'9' }.take(8)
                require(bin.length >= 6) { "卡号至少需要 6 位才能查询" }
                cacheGet(bin)?.let { return@runCatching it }
                if (offlineMode) error("完全离线模式已开启，且本地缓存中没有该 BIN 的记录")

                val request = Request.Builder()
                    .url("https://lookup.binlist.net/$bin")
                    .header("Accept-Version", "3")
                    .build()
                try {
                    HttpClients.api(proxyUrl).newCall(request).execute().use { resp ->
                        when {
                            resp.code == 404 -> error("未查询到该卡 BIN 信息")
                            resp.code == 429 -> error("查询过于频繁，请稍后再试")
                            !resp.isSuccessful -> error("查询失败：HTTP ${resp.code}")
                        }
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val info = BinInfo(
                            brand = CardBrand.fromScheme(json.optString("scheme")),
                            bankName = json.optJSONObject("bank")?.optString("name")
                                ?.takeIf { it.isNotBlank() },
                            cardType = json.optString("type").takeIf { it.isNotBlank() },
                            country = json.optJSONObject("country")?.optString("name")
                                ?.takeIf { it.isNotBlank() },
                        )
                        cachePut(bin, info)
                        info
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    error(friendlyNetworkError(e, "查询失败"))
                }
            }
        }

    /**
     * 连通性测试：直接探测应用真实依赖的两个业务域（binlist 与 GitHub API），
     * 而不是 gstatic 之类的第三方 204 服务——后者直连不可达并不代表业务域不可达。
     */
    suspend fun testConnection(proxyUrl: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClients.api(proxyUrl)
            val targets = listOf(
                "BIN 查询" to "https://lookup.binlist.net/",
                "GitHub" to "https://api.github.com/",
            )
            val results = targets.map { (label, url) ->
                val start = System.currentTimeMillis()
                try {
                    client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                        // 能拿到任何 HTTP 状态（含 404/405）即证明链路通
                        check(resp.code < 500) { "HTTP ${resp.code}" }
                    }
                    "$label ✓ ${System.currentTimeMillis() - start}ms"
                } catch (e: Exception) {
                    "$label ✗ ${friendlyNetworkError(e, "失败")}"
                }
            }
            if (results.none { "✓" in it }) {
                error(results.joinToString("；"))
            }
            results.joinToString(" · ")
        }
    }

    private fun cacheGet(bin: String): BinInfo? {
        val raw = cachePrefs.getString(bin, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            BinInfo(
                brand = CardBrand.fromName(json.optString("brand")),
                bankName = json.optString("bank").takeIf { it.isNotBlank() },
                cardType = json.optString("type").takeIf { it.isNotBlank() },
                country = json.optString("country").takeIf { it.isNotBlank() },
                fromCache = true,
            )
        }.getOrNull()
    }

    private fun cachePut(bin: String, info: BinInfo) {
        val entry = JSONObject()
            .put("brand", info.brand.name)
            .put("bank", info.bankName.orEmpty())
            .put("type", info.cardType.orEmpty())
            .put("country", info.country.orEmpty())
            .put("at", System.currentTimeMillis())
        val editor = cachePrefs.edit().putString(bin, entry.toString())
        // 简单容量控制：超过 200 条时按写入时间淘汰最旧的一半
        val all = cachePrefs.all
        if (all.size >= 200) {
            all.entries
                .sortedBy { (_, v) -> runCatching { JSONObject(v.toString()).optLong("at") }.getOrDefault(0L) }
                .take(all.size / 2)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
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
