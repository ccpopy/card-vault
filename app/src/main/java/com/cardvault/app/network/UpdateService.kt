package com.cardvault.app.network

import android.content.Context
import android.os.Build
import com.cardvault.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class AppUpdateInfo(
    val latestVersion: String,
    val tagName: String,
    /** GitHub 原始下载地址；镜像/直连在“下载时刻”按当时的代理状态解析，而不是 check 时固化 */
    val downloadUrl: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val assetSizeBytes: Long,
    val assetName: String,
    /** GitHub API 返回的 asset digest（sha256 hex，小写），可能为空 */
    val sha256: String?,
    /** Release 附带的 SHA256SUMS.txt 下载地址，digest 缺失时兜底 */
    val sumsUrl: String?,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult
}

class UpdateService(private val context: Context) {

    suspend fun check(proxyUrl: String?): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        // 更新元数据优先走 GitHub 原始地址，镜像只作为显式失败后的备用通道。
        // 这样不会在未配置代理时默认信任第三方镜像返回的版本与资产信息。
        val channels = listOf(GITHUB_LATEST_RELEASE_API, mirror(GITHUB_LATEST_RELEASE_API))
        var lastError: Exception? = null
        for (apiUrl in channels) {
            try {
                return@withContext Result.success(checkVia(apiUrl, proxyUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(IllegalStateException(friendlyNetworkError(lastError!!, "检查更新失败")))
    }

    private fun checkVia(apiUrl: String, proxyUrl: String?): UpdateCheckResult {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        HttpClients.api(proxyUrl).newCall(request).execute().use { response ->
            check(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.getString("tag_name")
            val latestVersion = tag.removePrefix("v")
            val asset = findBestApk(json, latestVersion)
            val sumsUrl = findSumsUrl(json)

            return if (isNewerVersion(latestVersion, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(
                    AppUpdateInfo(
                        latestVersion = latestVersion,
                        tagName = tag,
                        downloadUrl = asset.url,
                        releaseNotes = json.optString("body").trim(),
                        releasePageUrl = json.optString("html_url"),
                        assetSizeBytes = asset.sizeBytes,
                        assetName = asset.name,
                        sha256 = asset.sha256,
                        sumsUrl = sumsUrl,
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

    /**
     * 流式下载 APK：断点续传（.part 临时文件）、镜像/直连自动互备、
     * 下载完成后做大小 + ZIP 魔数 + SHA-256 三重校验，全部通过才交付。
     * 进度回调：已知总量按 1% 步进，未知总量按 256KB 步进（修复此前未知总量时进度恒 0 的问题）。
     */
    suspend fun downloadApk(
        info: AppUpdateInfo,
        destination: File,
        proxyUrl: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val channels = listOf(info.downloadUrl, mirror(info.downloadUrl))
        // 优先用 GitHub API 的 digest；缺失时下载 release 附带的 SHA256SUMS.txt。
        // 如果两者都没有拿到，直接失败，避免完整性校验静默降级。
        val expectedSha = info.sha256 ?: fetchExpectedSha(info, proxyUrl)
            ?: return@withContext Result.failure(
                IllegalStateException("未能获取安装包 SHA-256 校验值，已停止下载")
            )

        var lastError: Exception? = null
        for (url in channels) {
            try {
                return@withContext Result.success(
                    downloadVia(url, destination, proxyUrl, info, expectedSha, onProgress)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        Result.failure(IllegalStateException(friendlyNetworkError(lastError!!, "下载失败")))
    }

    private suspend fun downloadVia(
        url: String,
        destination: File,
        proxyUrl: String?,
        info: AppUpdateInfo,
        expectedSha: String?,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val part = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        var resumeFrom = if (part.exists()) part.length() else 0L
        if (info.assetSizeBytes in 1 until resumeFrom) {
            part.delete()
            resumeFrom = 0L
        }

        val builder = Request.Builder().url(url)
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

        HttpClients.download(proxyUrl).newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("下载内容为空")
            val resumed = response.code == 206 && resumeFrom > 0
            if (!resumed) {
                part.delete()
                resumeFrom = 0L
            }
            val contentLength = body.contentLength()
            val total = when {
                contentLength > 0 -> contentLength + resumeFrom
                info.assetSizeBytes > 0 -> info.assetSizeBytes
                else -> -1L
            }
            var downloaded = resumeFrom
            var lastStep = -1L
            body.byteStream().use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val step = if (total > 0) downloaded * 100 / total else downloaded / (256 * 1024)
                        if (step != lastStep) {
                            lastStep = step
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
        }

        verifyDownload(part, info, expectedSha)
        if (!part.renameTo(destination)) {
            part.copyTo(destination, overwrite = true)
            part.delete()
        }
        onProgress(destination.length(), destination.length())
        return destination
    }

    /** 校验失败即删除半成品并抛错（防止损坏文件成为断点续传的底座） */
    private fun verifyDownload(file: File, info: AppUpdateInfo, expectedSha: String?) {
        try {
            check(file.length() > 0) { "下载内容为空" }
            if (info.assetSizeBytes > 0) {
                check(file.length() == info.assetSizeBytes) { "安装包大小不符，可能被截断或替换" }
            }
            FileInputStream(file).use { input ->
                val head = ByteArray(2)
                check(input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                    "下载内容不是有效安装包（镜像可能返回了错误页）"
                }
            }
            if (expectedSha != null) {
                val actual = sha256Hex(file)
                check(actual.equals(expectedSha, ignoreCase = true)) {
                    "安装包 SHA-256 校验失败，已放弃安装"
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** 下载并解析 SHA256SUMS.txt，取出目标资产的哈希；原始地址优先，镜像仅作备用 */
    private fun fetchExpectedSha(info: AppUpdateInfo, proxyUrl: String?): String? {
        val sumsUrl = info.sumsUrl ?: return null
        val channels = listOf(sumsUrl, mirror(sumsUrl))
        for (url in channels) {
            try {
                HttpClients.api(proxyUrl).newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val text = resp.body?.string().orEmpty()
                    // 行格式：<hex>  <文件名>
                    text.lineSequence().forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2 && parts[1].trim('*') == info.assetName &&
                            parts[0].matches(Regex("[0-9a-fA-F]{64}"))
                        ) {
                            return parts[0].lowercase()
                        }
                    }
                }
            } catch (_: Exception) {
                // 尝试下一个通道
            }
        }
        return null
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ApkAsset(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val abi: String?,
        val sha256: String?,
    )

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
                sha256 = asset.optString("digest")
                    .removePrefix("sha256:")
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                    ?.lowercase(),
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

    private fun findSumsUrl(json: JSONObject): String? {
        val assets = json.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == "SHA256SUMS.txt") {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
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
            part.takeWhile { it in '0'..'9' }.takeIf { it.isNotEmpty() }?.toInt()
        }

    private fun abiFromAssetName(name: String): String? =
        APK_ABIS.firstOrNull { abi -> name.contains("_$abi.apk") }

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

        fun mirror(url: String): String =
            if (url.startsWith(MIRROR_PREFIX)) url else "$MIRROR_PREFIX$url"

        fun updateApkFile(context: Context, version: String): File =
            File(context.cacheDir, "updates/CardVault-$version.apk")

        /** 清理超过 24 小时的更新残包（历史版本 APK 会一直躺在 cache 里） */
        suspend fun cleanupUpdateCache(context: Context) = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates")
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }
}
