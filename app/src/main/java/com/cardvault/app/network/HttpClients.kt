package com.cardvault.app.network

import okhttp3.OkHttpClient
import org.json.JSONException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 进程级共享的 OkHttpClient：连接池与调度线程只建一份，
 * 按「用途 + 代理配置」派生并缓存，代理变化时用 newBuilder 复用底层资源。
 */
object HttpClients {

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    private val root: OkHttpClient by lazy { OkHttpClient() }

    /** API 请求（BIN 查询 / 更新检查 / 连通性测试）：短超时 */
    fun api(proxyUrl: String?): OkHttpClient =
        clients.getOrPut("api|${proxyUrl.orEmpty()}") {
            root.newBuilder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .apply { BinLookupService.parseProxy(proxyUrl)?.let { proxy(it) } }
                .build()
        }

    /** 大文件下载（更新 APK）：长读超时、无整体调用超时 */
    fun download(proxyUrl: String?): OkHttpClient =
        clients.getOrPut("dl|${proxyUrl.orEmpty()}") {
            root.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .apply { BinLookupService.parseProxy(proxyUrl)?.let { proxy(it) } }
                .build()
        }
}

/** 把底层网络异常映射为用户能看懂的中文提示 */
fun friendlyNetworkError(error: Throwable, fallback: String): String = when (error) {
    is UnknownHostException -> "域名解析失败，请检查网络或代理设置"
    is ConnectException -> "无法建立连接，请检查网络或代理设置"
    is SocketTimeoutException -> "连接超时，请稍后重试"
    is SSLException -> "安全连接失败，网络可能被劫持或代理异常"
    is JSONException -> "服务响应格式异常（镜像可能被限流），请稍后重试"
    else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
}
