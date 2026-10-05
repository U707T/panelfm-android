package com.u707t.panelfm.core.vfs.webdav

import com.u707t.panelfm.core.model.ConnectionConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

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
 *  - 307/308 必须保留原方法与请求体（WebDAV 常见于反向代理）；
 *  - 301/302/303 对 **非 GET/HEAD 方法同样保留方法**（多数 WebDAV 服务器用 301 给集合补
 *    「/」尾斜杠，若降级为 GET 会直接破坏 PROPFIND 等请求）；仅 GET/HEAD 保持 GET；
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
            val next = redirectRequest(request, response) ?: break
            response.close()
            request = next
            response = chain.proceed(request)
        }
        return response
    }

    companion object {
        private const val MAX_HOPS = 10

        /** 计算一次重定向后的请求；返回 null = 不再跟随（无 Location / 无法解析） */
        internal fun redirectRequest(request: Request, response: Response): Request? {
            val location = response.header("Location") ?: return null
            val newUrl = response.request.url.resolve(location) ?: return null
            val sameHost = newUrl.host == request.url.host
            val builder = request.newBuilder().url(newUrl)
            if (!sameHost) builder.removeHeader("Authorization")
            return when {
                response.code == 307 || response.code == 308 -> builder.method(request.method, request.body) // 保方法保 body
                request.method == "GET" || request.method == "HEAD" -> builder.method(request.method, null)
                else -> builder.method(request.method, request.body)                                        // WebDAV：保方法保 body
            }.build()
        }
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
                // 只给本主机发凭据（跨主机跳转后不泄露）
                if (chain.request().url.host != cfg.host) {
                    chain.proceed(chain.request())
                } else {
                    val req = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(cfg.user, cfg.password ?: "", Charsets.UTF_8))
                        .build()
                    chain.proceed(req)
                }
            }
        }
        if (cfg.secure && cfg.trustSelfSigned) {
            builder.sslSocketFactory(
                com.u707t.panelfm.core.vfs.TlsTrust.socketFactory,
                com.u707t.panelfm.core.vfs.TlsTrust.trustManager,
            )
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /** 拼接请求 URL：basePath + 虚拟路径（逐段编码，正确处理空格/中文；虚拟根保留尾斜杠） */
    fun url(cfg: DavConfig, path: String): HttpUrl {
        val full = (cfg.basePath.trimEnd('/') + "/" + path.trimStart('/')).let { if (it.startsWith("/")) it else "/$it" }
        val builder = HttpUrl.Builder().scheme(cfg.scheme).host(cfg.host).port(cfg.port)
        full.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        val url = builder.build()
        // 根 / 空路径保留尾斜杠（部分服务器对集合根要求 "/" 结尾，缺省会 301 甚至 404）
        return if (full.endsWith("/") && url.encodedPath.length > 1 && !url.encodedPath.endsWith("/")) {
            url.newBuilder().encodedPath(url.encodedPath + "/").build()
        } else {
            url
        }
    }
}
