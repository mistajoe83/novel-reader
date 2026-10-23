package com.joe.novelreader.core

import com.joe.novelreader.core.net.AppDns
import com.joe.novelreader.core.net.DnsConfig
import com.joe.novelreader.core.source.HttpEnv
import com.joe.novelreader.core.source.NovelSource
import com.joe.novelreader.core.source.SourceCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to the real websites and writes a report. Only runs on CI (-DliveTests=true),
 * and never fails the build: sites go down or change, and the report shows what broke.
 */
class LiveSourcesTest {

    private val live = System.getProperty("liveTests") == "true"

    private val dns = AppDns({ DnsConfig() })
    private val client = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val env = HttpEnv({ client })

    private val report = StringBuilder()

    private fun log(s: String) {
        println(s)
        report.appendLine(s)
    }

    @Test
    fun checkDnsAndAllSources() {
        assumeTrue(live)
        log("# Live check")
        for (server in DnsConfig.DEFAULT_SERVERS) {
            val r = dns.test(server, "novelbin.com")
            log("- DNS $server -> ok=${r.ok} ${r.millis}ms ${r.message}")
        }
        for (source in SourceCatalog.create(env)) {
            runBlocking { checkSource(source) }
        }
        val out = File(System.getProperty("user.dir"), "build/live-report.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
    }

    private suspend fun checkSource(s: NovelSource) {
        log("\n## ${s.name} (${s.baseUrl})")
        val listing = s.listings.first()
        val list = step("listing '${listing.name}'") {
            val r = s.getListing(listing, 1)
            "${r.novels.size} novels, next=${r.hasNextPage}, first=${r.novels.take(3).map { it.title + " | " + it.path + " | " + it.cover }}" to r.novels
        } ?: return
        step("page 2") {
            val r = s.getListing(listing, 2)
            "${r.novels.size} novels, first=${r.novels.firstOrNull()?.title}" to Unit
        }
        if (s.listings.size > 2) {
            val l2 = s.listings[2]
            step("listing '${l2.name}'") { val r = s.getListing(l2, 1); "${r.novels.size} novels" to Unit }
        }
        s.genres.firstOrNull()?.let { g ->
            step("genre '${g.name}'") { val r = s.getGenre(g, 1); "${r.novels.size} novels, next=${r.hasNextPage}" to Unit }
        }
        step("search 'martial'") { val r = s.search("martial", 1); "${r.novels.size} results: ${r.novels.take(3).map { it.title }}" to Unit }
        val novel = list.firstOrNull() ?: return
        step("details ${novel.path}") {
            val d = s.getDetails(novel.path)
            "title='${d.title}' author='${d.author}' status='${d.status}' genres=${d.genres.take(5)} rating=${d.rating} cover=${d.cover} desc=${d.description?.take(120)?.replace('\n', ' ')}" to Unit
        }
        val chapters = step("chapters") {
            val c = s.getChapters(novel.path)
            "${c.size} chapters, first=${c.take(2)}, last=${c.lastOrNull()}" to c
        } ?: return
        val ch = chapters.firstOrNull() ?: return
        step("chapter text ${ch.path}") {
            val t = s.getChapterText(ch.path)
            "title='${t.title}' paragraphs=${t.paragraphs.size} chars=${t.paragraphs.sumOf { it.length }} start='${t.paragraphs.take(3).joinToString(" / ").take(300)}' end='${t.paragraphs.takeLast(2).joinToString(" / ").take(200)}'" to Unit
        }
    }

    private suspend fun <T> step(name: String, block: suspend () -> Pair<String, T>): T? = try {
        val start = System.currentTimeMillis()
        val (msg, value) = block()
        log("- OK $name (${System.currentTimeMillis() - start}ms): $msg")
        value
    } catch (e: Throwable) {
        log("- FAIL $name: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
