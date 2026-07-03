package com.cardvault.app.network

import com.cardvault.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val latestVersion: String,
    val tagName: String,
    val downloadUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult
}

class UpdateService {
    suspend fun check(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildClient()
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
                val json = JSONObject(response.body?.string().orEmpty())
                val tag = json.getString("tag_name")
                val latestVersion = tag.removePrefix("v")
                val assetUrl = findUniversalApkUrl(json, latestVersion)

                if (isNewerVersion(latestVersion, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.Available(
                        AppUpdateInfo(
                            latestVersion = latestVersion,
                            tagName = tag,
                            downloadUrl = assetUrl,
                        )
                    )
                } else {
                    UpdateCheckResult.UpToDate(
                        currentVersion = BuildConfig.VERSION_NAME,
                        latestVersion = latestVersion,
                    )
                }
            }
        }
    }

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

    private fun findUniversalApkUrl(json: JSONObject, latestVersion: String): String {
        val assets = json.getJSONArray("assets")
        var firstApk: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk") || url.isBlank()) continue
            if (firstApk == null) firstApk = url
            if (name == "CardVault_${latestVersion}_universal.apk" || name.contains("_universal.apk")) {
                return url
            }
        }
        return firstApk ?: error("最新版本未提供 APK 安装包")
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.versionParts()
        val currentParts = current.versionParts()
        val size = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until size) {
            val l = latestParts.getOrElse(index) { 0 }
            val c = currentParts.getOrElse(index) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun String.versionParts(): List<Int> =
        split('.', '-', '_').mapNotNull { part ->
            part.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt()
        }

    companion object {
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/ccpopy/card-vault/releases/latest"
    }
}
