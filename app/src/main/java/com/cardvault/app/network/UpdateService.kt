package com.cardvault.app.network

import android.os.Build
import com.cardvault.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val latestVersion: String,
    val tagName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val assetSizeBytes: Long,
    val assetName: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult
}

class UpdateService {
    suspend fun check(proxyUrl: String?): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val hasProxy = !proxyUrl.isNullOrBlank()
            val client = buildClient(proxyUrl.takeIf { hasProxy })
            val apiUrl = if (hasProxy) GITHUB_LATEST_RELEASE_API else mirror(GITHUB_LATEST_RELEASE_API)
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
                val json = JSONObject(response.body?.string().orEmpty())
                val tag = json.getString("tag_name")
                val latestVersion = tag.removePrefix("v")
                val asset = findBestApk(json, latestVersion)
                val downloadUrl = if (hasProxy) asset.url else mirror(asset.url)
                val releasePageUrl = json.optString("html_url").let { url ->
                    if (hasProxy || url.isBlank()) url else mirror(url)
                }

                if (isNewerVersion(latestVersion, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.Available(
                        AppUpdateInfo(
                            latestVersion = latestVersion,
                            tagName = tag,
                            downloadUrl = downloadUrl,
                            releaseNotes = json.optString("body").trim(),
                            releasePageUrl = releasePageUrl,
                            assetSizeBytes = asset.sizeBytes,
                            assetName = asset.name,
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

    /** 流式下载 APK 到本地文件，按进度回调（downloaded / total 字节，total 未知时为 -1） */
    suspend fun downloadApk(
        url: String,
        destination: File,
        proxyUrl: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildDownloadClient(proxyUrl)
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                val body = response.body ?: error("下载内容为空")
                val total = body.contentLength()
                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.delete()

                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPercent = -1L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) downloaded * 100 / total else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(destination.length(), total)
                destination
            }
        }
    }

    private fun buildClient(proxyUrl: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
        BinLookupService.parseProxy(proxyUrl)?.let { builder.proxy(it) }
        return builder.build()
    }

    private fun buildDownloadClient(proxyUrl: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
        BinLookupService.parseProxy(proxyUrl)?.let { builder.proxy(it) }
        return builder.build()
    }

    private data class ApkAsset(val name: String, val url: String, val sizeBytes: Long, val abi: String?)

    private fun findBestApk(json: JSONObject, latestVersion: String): ApkAsset {
        val assets = json.getJSONArray("assets")
        val apkAssets = mutableListOf<ApkAsset>()
        var firstApk: ApkAsset? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk") || url.isBlank()) continue
            val candidate = ApkAsset(
                name = name,
                url = url,
                sizeBytes = asset.optLong("size", -1L),
                abi = abiFromAssetName(name),
            )
            if (firstApk == null) firstApk = candidate
            apkAssets += candidate
        }
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        supportedAbis.forEach { abi ->
            apkAssets.firstOrNull { it.abi == abi }?.let { return it }
        }
        apkAssets.firstOrNull {
            it.name == "CardVault_${latestVersion}_universal.apk" || it.abi == ABI_UNIVERSAL
        }?.let { return it }
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

    private fun abiFromAssetName(name: String): String? =
        APK_ABIS.firstOrNull { abi -> name.contains("_$abi.apk") }

    private fun mirror(url: String): String =
        if (url.startsWith(MIRROR_PREFIX)) url else "$MIRROR_PREFIX$url"

    companion object {
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/ccpopy/card-vault/releases/latest"
        private const val MIRROR_PREFIX = "https://gh.lessdo.top/"
        private const val ABI_UNIVERSAL = "universal"
        private val APK_ABIS = listOf(
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
            ABI_UNIVERSAL,
        )
    }
}
