package com.joe.novelreader.core.source

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelDetails
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.model.PageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** Shared things every source needs, provided by the app. */
class HttpEnv(
    val client: () -> OkHttpClient,
    val userAgent: () -> String = { DEFAULT_UA },
) {
    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

/** The site is showing a Cloudflare "checking your browser" page. Opening it in the in-app browser fixes it. */
class CloudflareException(val url: String) :
    IOException("This site is checking your browser. Open it in the in-app browser once, then try again.")

class SourceDisabledException(name: String, notice: String?) :
    IOException(notice?.ifBlank { null } ?: "$name is switched off for now.")

class HttpStatusException(val code: Int, val url: String) :
    IOException(
        when (code) {
            404 -> "Not found on the site (404). It may have been removed."
            429 -> "The site is limiting requests (429). Wait a moment and try again."
            in 500..599 -> "The site is having problems ($code). Try again later."
            else -> "The site answered with an error ($code)."
        },
    )

abstract class NovelSource(protected val env: HttpEnv) {
    abstract val id: String
    abstract val name: String
    abstract val defaultBaseUrl: String
    open val language: String = "English"
    open val description: String = ""

    /** Address typed by the user in settings. Wins over everything else. */
    @Volatile
    var userBaseUrl: String = ""
        set(value) {
            field = value.trim().trimEnd('/')
        }

    /** Fixes downloaded from GitHub or typed on the phone. */
    @Volatile
    var rules: SourceRules = SourceRules()

    /** Kept for older callers: setting it sets the user's own address. */
    var baseUrl: String
        get() = userBaseUrl.ifBlank { rules.baseUrl?.trim()?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl }
        set(value) {
            userBaseUrl = value
        }

    abstract val listings: List<Listing>
    open val genres: List<Genre> = emptyList()

    protected abstract suspend fun builtInListing(listing: Listing, page: Int): PageResult
    protected open suspend fun builtInGenre(genre: Genre, page: Int): PageResult = PageResult(emptyList(), false)
    protected abstract suspend fun builtInSearch(query: String, page: Int): PageResult
    protected abstract suspend fun builtInDetails(path: String): NovelDetails
    protected abstract suspend fun builtInChapters(path: String): List<ChapterInfo>
    protected abstract suspend fun builtInChapterText(path: String): ChapterText

    // ---- Public entry points: built-in code, with the rules applied on top. ----

    private fun checkEnabled() {
        if (rules.disabled) throw SourceDisabledException(name, rules.notice)
    }

    suspend fun getListing(listing: Listing, page: Int): PageResult {
        checkEnabled()
        val url = rules.urls.listing
        val r = if (url != null && rules.list != null) {
            ruleList(fill(url, page = page, listing = listing.id))
        } else {
            builtInListing(listing, page)
        }
        return fixList(r)
    }

    suspend fun getGenre(genre: Genre, page: Int): PageResult {
        checkEnabled()
        val url = rules.urls.genre
        val r = if (url != null && rules.list != null) ruleList(fill(url, page = page, genre = genre.id)) else builtInGenre(genre, page)
        return fixList(r)
    }

    suspend fun search(query: String, page: Int): PageResult {
        checkEnabled()
        val url = rules.urls.search
        val r = if (url != null && rules.list != null) {
            ruleList(fill(url, page = page, query = java.net.URLEncoder.encode(query, "UTF-8")))
        } else {
            builtInSearch(query, page)
        }
        return fixList(r)
    }

    suspend fun getDetails(path: String): NovelDetails {
        checkEnabled()
        val dr = rules.details
        if (dr == null) return builtInDetails(path).let { it.copy(cover = it.cover?.let(::rewriteCover)) }
        val builtIn = try { builtInDetails(path) } catch (e: CloudflareException) { throw e } catch (_: Exception) { null }
        val doc = getDoc(rules.urls.details?.let { fill(it, path = path) } ?: absoluteUrl(path))
        fun text(sel: String?) = sel?.let { doc.selectFirst(it) }?.let { it.attr("content").ifBlank { it.text() } }?.trim()?.ifBlank { null }
        val d = NovelDetails(
            sourceId = id,
            path = path,
            title = text(dr.title) ?: builtIn?.title ?: "",
            author = text(dr.author) ?: builtIn?.author,
            cover = dr.cover?.let { doc.selectFirst(it) }?.let { imageOf(it) } ?: builtIn?.cover,
            description = dr.description?.let { com.joe.novelreader.core.util.HtmlText.plain(doc.selectFirst(it)) } ?: builtIn?.description,
            genres = dr.genres?.let { s -> doc.select(s).map { it.text().trim() }.filter { it.isNotEmpty() }.distinct() }?.ifEmpty { null }
                ?: builtIn?.genres.orEmpty(),
            status = text(dr.status) ?: builtIn?.status,
            rating = builtIn?.rating,
        )
        return d.copy(cover = d.cover?.let(::rewriteCover))
    }

    suspend fun getChapters(path: String): List<ChapterInfo> {
        checkEnabled()
        val cr = rules.chapters ?: return builtInChapters(path)
        val doc = getDoc(rules.urls.chapters?.let { fill(it, path = path) } ?: absoluteUrl(path))
        var nodes = doc.select(cr.item).toList()
        if (cr.reversed) nodes = nodes.reversed()
        val seen = HashSet<String>()
        val out = mutableListOf<ChapterInfo>()
        for (n in nodes) {
            val a = if (n.hasAttr("href")) n else n.selectFirst("a[href]") ?: continue
            val p = toPath(a.attr("abs:href").ifBlank { a.attr("href") })
            if (!seen.add(p)) continue
            val nm = (cr.name?.let { n.selectFirst(it)?.text() } ?: a.attr("title").ifBlank { a.text() }).trim()
            out += ChapterInfo(p, nm.ifBlank { "Chapter ${out.size + 1}" }, out.size)
        }
        if (out.isEmpty()) throw IllegalStateException("The chapter list rule found no chapters.")
        return out
    }

    suspend fun getChapterText(path: String): ChapterText {
        checkEnabled()
        val cr = rules.chapter
        val text = if (cr?.content != null) {
            val doc = getDoc(absoluteUrl(path))
            cr.remove.forEach { runCatching { doc.select(it).remove() } }
            val content = doc.selectFirst(cr.content)
                ?: throw IllegalStateException("Couldn't find the chapter text (rule: ${cr.content}).")
            val title = cr.title?.let { doc.selectFirst(it)?.text()?.trim() }
            ChapterText(title, com.joe.novelreader.core.util.HtmlText.paragraphs(content, com.joe.novelreader.core.util.HtmlText.COMMON_JUNK))
        } else {
            builtInChapterText(path)
        }
        val drops = cr?.dropParagraphs.orEmpty().mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val paragraphs = if (drops.isEmpty()) text.paragraphs else text.paragraphs.filter { p -> drops.none { it.containsMatchIn(p) } }
        if (paragraphs.isEmpty()) throw IllegalStateException("This chapter came back empty.")
        return text.copy(paragraphs = paragraphs)
    }

    private fun fill(template: String, page: Int = 1, query: String = "", listing: String = "", genre: String = "", path: String = ""): String =
        template.replace("{base}", baseUrl)
            .replace("{page}", page.toString())
            .replace("{query}", query)
            .replace("{listing}", listing)
            .replace("{genre}", genre)
            .replace("{path}", path)
            .replace("{slug}", path.trim('/').substringAfterLast('/').removeSuffix(".html"))
            .let { if (it.startsWith("/")) baseUrl + it else it }

    private suspend fun ruleList(url: String): PageResult {
        val lr = rules.list!!
        val doc = getDoc(url)
        val seen = HashSet<String>()
        val out = mutableListOf<NovelItem>()
        for (item in doc.select(lr.item)) {
            val a = (lr.link?.let { item.selectFirst(it) } ?: if (item.hasAttr("href")) item else item.selectFirst("a[href]")) ?: continue
            val p = toPath(a.attr("abs:href").ifBlank { a.attr("href") })
            if (p == "/" || !seen.add(p)) continue
            val title = (lr.title?.let { item.selectFirst(it)?.text() } ?: a.attr("title").ifBlank { a.text() }).trim()
            if (title.isBlank()) continue
            val img = (lr.cover?.let { item.selectFirst(it) } ?: item.selectFirst("img"))
            out += NovelItem(id, p, title, img?.let { imageOf(it) }, lr.subtitle?.let { item.selectFirst(it)?.text()?.trim() })
        }
        val next = lr.next?.let { doc.selectFirst(it) != null } ?: out.isNotEmpty()
        return PageResult(out, next)
    }

    private fun imageOf(el: org.jsoup.nodes.Element): String? {
        val src = listOf("data-src", "data-original", "data-lazy-src", "src", "content", "href")
            .map { el.attr("abs:$it").ifBlank { el.attr(it) } }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return absoluteImage(src)
    }

    private fun fixList(r: PageResult): PageResult =
        if (rules.coverRewrite.isEmpty()) r else r.copy(novels = r.novels.map { it.copy(cover = it.cover?.let(::rewriteCover)) })

    fun rewriteCover(url: String): String {
        var u = url
        for (rw in rules.coverRewrite) {
            u = runCatching { Regex(rw.from).replace(u, rw.to) }.getOrDefault(u)
        }
        return u
    }

    /** Turns a stored path back into a full web address. */
    fun absoluteUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/$path"
    }

    /** Strips the domain from a link so it keeps working if the site's domain changes. */
    fun toPath(url: String): String {
        val u = url.trim()
        val m = Regex("^(?:https?:)?//[^/]+(/.*)?$").find(u)
        if (m != null) return m.groupValues[1].ifEmpty { "/" }
        return if (u.startsWith("/")) u else "/$u"
    }

    /** Makes an image link absolute. */
    fun absoluteImage(src: String?): String? {
        val s = src?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("data:")) return null
        return absoluteUrl(s)
    }

    protected fun headers(extra: Map<String, String> = emptyMap()): Headers {
        val b = Headers.Builder()
            .add("User-Agent", env.userAgent())
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Referer", "$baseUrl/")
        rules.headers.forEach { (k, v) -> b.set(k, v) }
        extra.forEach { (k, v) -> b.set(k, v) }
        return b.build()
    }

    protected suspend fun fetch(
        url: String,
        body: RequestBody? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .headers(headers(extraHeaders))
            .apply { if (body != null) post(body) }
            .build()
        env.client().newCall(req).execute().use { resp -> readBody(resp, url) }
    }

    protected suspend fun getDoc(url: String, extraHeaders: Map<String, String> = emptyMap()): Document {
        val html = fetch(url, null, extraHeaders)
        return Jsoup.parse(html, url)
    }

    protected suspend fun postForm(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        return fetch(url, fb.build(), extraHeaders)
    }

    private fun readBody(resp: Response, url: String): String {
        val text = resp.body?.string().orEmpty()
        val server = resp.header("Server").orEmpty()
        if ((resp.code == 403 || resp.code == 503 || resp.code == 429) &&
            (server.contains("cloudflare", true) || looksLikeCloudflare(text))
        ) {
            throw CloudflareException(url)
        }
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
        if (looksLikeCloudflare(text) && text.length < 40_000) throw CloudflareException(url)
        return text
    }

    private fun looksLikeCloudflare(html: String): Boolean =
        html.contains("cf-chl-", ignoreCase = true) ||
            html.contains("challenge-platform", ignoreCase = true) && html.contains("<title>Just a moment", true) ||
            html.contains("<title>Attention Required! | Cloudflare", ignoreCase = true)
}
