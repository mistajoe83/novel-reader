package com.joe.novelreader.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.joe.novelreader.core.net.AppDns
import com.joe.novelreader.core.net.DnsConfig
import com.joe.novelreader.core.source.HttpEnv
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Shares cookies with the in-app browser, so passing a site's "checking your browser" page once also works for the app. */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance().apply { setAcceptCookie(true) } }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager ?: return
        val u = url.toString()
        cookies.forEach { m.setCookie(u, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val m = manager ?: return emptyList()
        val raw = m.getCookie(url.toString()) ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

class Network(context: Context, private val dnsConfig: () -> DnsConfig) {

    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?.replace("; wv", "")
            ?: HttpEnv.DEFAULT_UA
    }

    val dns = AppDns(dnsConfig)

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .cookieJar(WebViewCookieJar())
        .cache(Cache(File(context.cacheDir, "http"), 30L * 1024 * 1024))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Images go through the same client, so they also use the app's DNS. */
    val imageClient: OkHttpClient = client.newBuilder()
        .cache(Cache(File(context.cacheDir, "images-http"), 10L * 1024 * 1024))
        .build()

    val env = HttpEnv(client = { client }, userAgent = { userAgent })

    /** Call after DNS settings change so new lookups use the new servers right away. */
    fun onDnsChanged() {
        dns.clearCache()
        client.connectionPool.evictAll()
        imageClient.connectionPool.evictAll()
    }
}
