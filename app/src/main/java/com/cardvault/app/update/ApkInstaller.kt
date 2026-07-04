package com.cardvault.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** 调用系统包安装器安装下载好的 APK，并处理「未知来源」授权跳转 */
object ApkInstaller {

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * 拉起安装前做最后一道闸：
     * 1. 文件仍存在（cache 目录可能被系统清理）；
     * 2. 包名与本应用一致（防止被替换成任意其他应用）；
     * 3. 签名证书与当前安装的应用一致（能读到签名时强校验；读不到时由系统
     *    覆盖安装的同签名检查兜底）。
     * 返回 null 表示已成功拉起安装器，否则返回用户可读的错误信息。
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() == 0L) {
            return "安装包已被系统清理，请重新下载"
        }
        val pm = context.packageManager
        val archiveInfo = getArchiveInfo(pm, apk.absolutePath)
            ?: return "安装包已损坏，请重新下载"
        if (archiveInfo.packageName != context.packageName) {
            return "安装包身份异常（包名不符），已阻止安装"
        }
        val archiveSigs = signatureDigests(archiveInfo)
        val ownSigs = ownSignatureDigests(context)
        if (archiveSigs.isNotEmpty() && ownSigs.isNotEmpty() &&
            archiveSigs.intersect(ownSigs).isEmpty()
        ) {
            return "安装包签名与当前应用不一致，已阻止安装"
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (_: Exception) {
            "无法启动系统安装器"
        }
    }

    private fun getArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val withSigning = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
        // 个别 ROM 对归档包不回签名信息，至少拿到包名做身份校验
        return withSigning ?: pm.getPackageArchiveInfo(path, 0)
    }

    private fun ownSignatureDigests(context: Context): Set<String> {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            return emptySet()
        }
        return signatureDigests(info)
    }

    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig ->
            runCatching {
                digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    /** 跳转到本应用的「允许安装未知应用」系统设置页 */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
}
