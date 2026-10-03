package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.model.ConnectionConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** WebDAV 连接参数（从 ConnectionConfig 派生）。 */
data class DavConfig(
    val host: String,
    val port: Int,
    val secure: Boolean,
    val basePath: String,
    val user: String,
    val password: String?,
    val userAgent: String,
    val trustSelfSigned: Boolean,
    val timeoutMs: Long,
) {
    val scheme: String get() = if (secure) "https" else "http"

    companion object {
        fun from(config: ConnectionConfig, secret: String?, defaultUserAgent: String, trustSelfSignedDefault: Boolean) =
            DavConfig(
                host = config.host,
                port = if (config.port > 0) config.port else if (config.option("secure") == "true") 443 else 80,
                secure = config.option("secure")?.toBoolean() ?: (config.port == 443),
                basePath = config.basePath.ifBlank { "/" },
                user = config.user,
                password = secret,
                userAgent = config.option(ConnectionConfig.OPT_USER_AGENT) ?: defaultUserAgent,
                trustSelfSigned = config.option(ConnectionConfig.OPT_TRUST_SELF_SIGNED)?.toBoolean() ?: trustSelfSignedDefault,
                timeoutMs = 30_000L,
            )
    }
}

/**
 * 自研重定向拦截器：
 *  - 301/302/303 才降级为 GET；307/308 必须保留原方法与请求体（WebDAV 常见于反向代理）；
 *  - 跨主机跳转剥离 Authorization，避免凭据泄露；
 *  - 最多 10 跳。
 */
class DavRedirectInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0
        while (response.isRedirect && hops < MAX_HOPS) {
            hops++
            val location = response.header("Location") ?: break
            val newUrl = response.request.url.resolve(location) ?: break
            val sameHost = newUrl.host == request.url.host
            val code = response.code
            val builder = request.newBuilder().url(newUrl)
            if (!sameHost) builder.removeHeader("Authorization")
            when (code) {
                307, 308 -> builder.method(request.method, request.body) // 保方法保 body
                else -> builder.method("GET", null)                       // 301/302/303 降级
            }
            response.close()
            request = builder.build()
            response = chain.proceed(request)
        }
        return response
    }

    companion object {
        private const val MAX_HOPS = 10
    }
}

/** 信任自签证书（仅对该连接生效，不动全局）。 */
object InsecureTls {

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        }.socketFactory
    }
}

object DavHttp {

    fun client(cfg: DavConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(cfg.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)      // 大文件下载不设读超时，靠连接与写超时
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)                     // 交给 DavRedirectInterceptor 精确控制
            .followSslRedirects(false)
            .addInterceptor(DavRedirectInterceptor())
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", cfg.userAgent)
                    .header("Accept", "*/*")
                    .build()
                chain.proceed(req)
            }
        if (cfg.user.isNotEmpty()) {
            builder.addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", Credentials.basic(cfg.user, cfg.password ?: "", Charsets.UTF_8))
                    .build()
                chain.proceed(req)
            }
        }
        if (cfg.secure && cfg.trustSelfSigned) {
            builder.sslSocketFactory(InsecureTls.socketFactory, InsecureTls.trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /** 拼接请求 URL：basePath + 虚拟路径（逐段编码，正确处理空格/中文） */
    fun url(cfg: DavConfig, path: String): HttpUrl {
        val full = (cfg.basePath.trimEnd('/') + "/" + path.trimStart('/')).let { if (it.startsWith("/")) it else "/$it" }
        val builder = HttpUrl.Builder().scheme(cfg.scheme).host(cfg.host).port(cfg.port)
        full.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder.build()
    }
}
