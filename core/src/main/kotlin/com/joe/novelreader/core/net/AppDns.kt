package com.joe.novelreader.core.net

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** DNS settings that only apply to this app's own connections. */
data class DnsConfig(
    val enabled: Boolean = true,
    val servers: List<String> = DEFAULT_SERVERS,
    /** When every server fails, use the phone's normal DNS instead of failing. */
    val fallbackToSystem: Boolean = true,
) {
    companion object {
        val DEFAULT_SERVERS = listOf(
            "https://dns.adguard.com/dns-query",
            "https://dns.adguard-dns.com/dns-query",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/dns-query",
        )
    }
}

data class DnsPreset(val name: String, val url: String)

/**
 * Resolves host names with DNS-over-HTTPS, trying each configured server in turn.
 * This never touches the phone's DNS settings: it is only used by this app's HTTP client.
 */
class AppDns(
    private val config: () -> DnsConfig,
    bootstrapClient: OkHttpClient = OkHttpClient(),
) : Dns {

    private val bootstrap = bootstrapClient.newBuilder()
        .dns(Dns.SYSTEM)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val resolvers = ConcurrentHashMap<String, DnsOverHttps>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Server that answered last, tried first next time so a dead server doesn't slow every lookup. */
    @Volatile
    private var preferred: String? = null

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    override fun lookup(hostname: String): List<InetAddress> {
        val cfg = config()
        if (!cfg.enabled) return Dns.SYSTEM.lookup(hostname)
        val servers = cfg.servers.mapNotNull { normalizeServer(it) }.distinct()
        if (servers.isEmpty()) return Dns.SYSTEM.lookup(hostname)
        if (isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)

        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expiresAt > now) return it.addresses }

        val ordered = preferred?.takeIf { it in servers }?.let { listOf(it) + (servers - it) } ?: servers
        var lastError: Exception? = null
        for (server in ordered) {
            try {
                val result = resolver(server).lookup(hostname)
                if (result.isNotEmpty()) {
                    preferred = server
                    cache[hostname] = CacheEntry(result, now + CACHE_MS)
                    return result
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (cfg.fallbackToSystem) return Dns.SYSTEM.lookup(hostname)
        throw UnknownHostException("All DNS servers failed for $hostname").apply {
            if (lastError != null) initCause(lastError)
        }
    }

    /** Looks up [hostname] with one specific server, for the "Test" button in settings. */
    fun test(server: String, hostname: String): DnsTestResult {
        val url = normalizeServer(server) ?: return DnsTestResult(false, 0, "Not a valid address")
        val start = System.nanoTime()
        return try {
            val result = resolver(url).lookup(hostname)
            val ms = (System.nanoTime() - start) / 1_000_000
            DnsTestResult(true, ms, result.take(2).joinToString { it.hostAddress ?: "" })
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            DnsTestResult(false, ms, e.message ?: e.javaClass.simpleName)
        }
    }

    fun clearCache() {
        cache.clear()
        preferred = null
    }

    private fun resolver(server: String): DnsOverHttps = resolvers.getOrPut(server) {
        val url: HttpUrl = server.toHttpUrlOrNull() ?: throw UnknownHostException("Bad DNS server $server")
        val builder = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url)
            .includeIPv6(true)
            .resolvePrivateAddresses(false)
        BOOTSTRAP[url.host]?.let { ips ->
            builder.bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        }
        builder.build()
    }

    companion object {
        private const val CACHE_MS = 5 * 60 * 1000L

        /** Fixed IPs for well-known servers, so we never need the phone's DNS to find the DNS server. */
        private val BOOTSTRAP = mapOf(
            "dns.adguard.com" to listOf("94.140.14.14", "94.140.15.15"),
            "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
            "unfiltered.adguard-dns.com" to listOf("94.140.14.140", "94.140.14.141"),
            "family.adguard-dns.com" to listOf("94.140.14.15", "94.140.15.16"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
            "doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220"),
        )

        val PRESETS = listOf(
            DnsPreset("AdGuard", "https://dns.adguard.com/dns-query"),
            DnsPreset("AdGuard (new address)", "https://dns.adguard-dns.com/dns-query"),
            DnsPreset("AdGuard non-filtering", "https://unfiltered.adguard-dns.com/dns-query"),
            DnsPreset("AdGuard family", "https://family.adguard-dns.com/dns-query"),
            DnsPreset("Cloudflare", "https://cloudflare-dns.com/dns-query"),
            DnsPreset("Google", "https://dns.google/dns-query"),
            DnsPreset("Quad9", "https://dns.quad9.net/dns-query"),
            DnsPreset("OpenDNS", "https://doh.opendns.com/dns-query"),
        )

        /**
         * Accepts "dns.adguard.com", "dns.adguard.com/dns-query", "94.140.14.14" or a full https URL,
         * and turns it into a DNS-over-HTTPS URL. Returns null if it can't be used.
         */
        fun normalizeServer(input: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            val full = when {
                s.startsWith("https://", ignoreCase = true) -> s
                s.startsWith("http://", ignoreCase = true) -> "https://" + s.substring(7)
                s.contains('/') -> "https://$s"
                else -> "https://$s/dns-query"
            }
            val url = full.toHttpUrlOrNull() ?: return null
            return if (url.encodedPath == "/") url.newBuilder().encodedPath("/dns-query").build().toString()
            else url.toString()
        }

        private fun isIpLiteral(host: String): Boolean =
            host.matches(Regex("^[0-9.]+$")) || host.contains(':')
    }
}

data class DnsTestResult(val ok: Boolean, val millis: Long, val message: String)
