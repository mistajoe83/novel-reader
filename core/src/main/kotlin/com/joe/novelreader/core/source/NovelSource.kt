package com.joe.novelreader.core.source

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelDetails
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

    /** Can be changed in settings when a site moves to a new domain. */
    @Volatile
    var baseUrl: String = ""
        get() = field.ifBlank { defaultBaseUrl }
        set(value) {
            field = value.trim().trimEnd('/')
        }

    abstract val listings: List<Listing>
    open val genres: List<Genre> = emptyList()

    abstract suspend fun getListing(listing: Listing, page: Int): PageResult
    open suspend fun getGenre(genre: Genre, page: Int): PageResult = PageResult(emptyList(), false)
    abstract suspend fun search(query: String, page: Int): PageResult
    abstract suspend fun getDetails(path: String): NovelDetails
    abstract suspend fun getChapters(path: String): List<ChapterInfo>
    abstract suspend fun getChapterText(path: String): ChapterText

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
