package com.joe.novelreader.core.source

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelDetails
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.model.PageResult
import com.joe.novelreader.core.util.HtmlText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Many novel sites (NovelBin, ReadNovelFull, NovelFull, FreeWebNovel...) run the same site software.
 * This class reads that layout; each site only fills in its own addresses.
 */
open class ReadNovelFullSource(
    env: HttpEnv,
    override val id: String,
    override val name: String,
    override val defaultBaseUrl: String,
    override val description: String,
    override val listings: List<Listing>,
    override val genres: List<Genre>,
    private val searchPath: String,
    private val searchKey: String = "keyword",
    /** Where the chapter list comes from: "ajax/chapter-archive", "ajax-chapter-option", or null to read it from the book page. */
    private val chapterAjax: String? = "ajax/chapter-archive",
    /** True when page 2 is ".../2" instead of "...?page=2". */
    private val pageAsPath: Boolean = false,
    /** Listings that only have one page. */
    private val singlePage: Set<String> = emptySet(),
    private val watermarkWords: List<String> = emptyList(),
) : NovelSource(env) {

    private fun pageUrl(path: String, page: Int): String =
        if (pageAsPath) {
            if (page > 1) "$baseUrl/$path/$page" else "$baseUrl/$path"
        } else {
            val sep = if (path.contains('?')) "&" else "?"
            "$baseUrl/$path${sep}page=$page"
        }

    override suspend fun getListing(listing: Listing, page: Int): PageResult {
        if (page > 1 && listing.id in singlePage) return PageResult(emptyList(), false)
        val doc = getDoc(pageUrl(listing.id, page))
        return PageResult(parseList(doc), hasNext(doc, page) && listing.id !in singlePage)
    }

    override suspend fun getGenre(genre: Genre, page: Int): PageResult {
        val doc = getDoc(pageUrl(genre.id, page))
        return PageResult(parseList(doc), hasNext(doc, page))
    }

    override suspend fun search(query: String, page: Int): PageResult {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = getDoc("$baseUrl/$searchPath?$searchKey=$q&page=$page")
        return PageResult(parseList(doc), hasNext(doc, page))
    }

    protected open fun hasNext(doc: Document, page: Int): Boolean {
        val pag = doc.select("ul.pagination li, .pagination a, .pages a, .page-nav a")
        if (pag.isEmpty()) return false
        if (doc.selectFirst("ul.pagination li.next:not(.disabled), li.next a, a[rel=next], .pagination a.next") != null) return true
        return pag.any { it.text().trim() == (page + 1).toString() }
    }

    protected open fun parseList(doc: Document): List<NovelItem> {
        val containers = doc.select("[class*=archive], .col-content, .list-novel, .ul-list1, .list-truyen")
        val scope: Element = if (containers.isNotEmpty()) containers.first()!!.parent() ?: doc else doc
        val links = (if (containers.isNotEmpty()) containers.select("h3 a[href]") else scope.select("h3 a[href]"))
        val seen = HashSet<String>()
        val out = mutableListOf<NovelItem>()
        for (a in links) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            val path = toPath(href)
            if (path == "/" || !seen.add(path)) continue
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) continue
            val row = a.parents().firstOrNull { p ->
                p.hasClass("row") || p.normalName() == "li" || p.hasClass("li-row") || p.hasClass("item")
            }
            val img = row?.selectFirst("img")
            val cover = img?.let { pickImage(it) }
            val sub = row?.selectFirst(".author, span.author, .chr-text, .text-info a, .chapter-text")?.text()?.trim()
            out += NovelItem(id, path, title, cover, sub?.ifBlank { null })
        }
        return out
    }

    protected fun pickImage(img: Element): String? {
        val src = listOf("data-src", "data-cfsrc", "data-original", "data-lazy-src", "src")
            .map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        return absoluteImage(src)?.let { upgradeCover(it) }
    }

    /** List pages often use tiny thumbnails; ask for the full-size picture instead when the site allows it. */
    protected open fun upgradeCover(url: String): String = url

    override suspend fun getDetails(path: String): NovelDetails {
        val doc = getDoc(absoluteUrl(path))
        return parseDetails(doc, path)
    }

    protected open fun parseDetails(doc: Document, path: String): NovelDetails {
        val img = doc.selectFirst(".book img, .books img, .m-imgtxt img, .pic img, .cover img")
        val title = listOf(".col-info-desc h3.title", ".desc h3.title", "h3.title[itemprop=name]", "h1.tit", ".books h3.title")
            .firstNotNullOfOrNull { doc.selectFirst(it)?.text()?.trim()?.ifBlank { null } }
            ?: img?.attr("title")?.trim()?.ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h3.title, h1")?.text()?.trim().orEmpty()
        val cover = img?.let { pickImage(it) } ?: absoluteImage(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val descEl = doc.selectFirst("div.desc-text, .desc-text, .m-desc .txt .inner, .m-desc .inner, #tab-description, .summary")
        val description = HtmlText.plain(descEl)

        var author: String? = null
        var status: String? = null
        var genres: List<String> = emptyList()

        // Layout 1: <ul class="info info-meta"><li><h3>Author:</h3><a>Name</a></li>...
        for (li in doc.select("ul.info-meta li, ul.info li, .info > div, .info-holder .info div")) {
            val text = li.text()
            val idx = text.indexOf(':')
            if (idx <= 0) continue
            val key = text.substring(0, idx).trim().lowercase()
            val links = li.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val value = text.substring(idx + 1).trim()
            when {
                key.startsWith("author") -> author = links.joinToString(", ").ifBlank { value }
                key.startsWith("genre") -> genres = links.ifEmpty { value.split(',').map { it.trim() } }
                key.startsWith("status") -> status = value
            }
        }
        // Layout 2 (FreeWebNovel): <span title="Author"></span><div class="right"><a>Name</a></div>
        for (item in doc.select(".m-imgtxt .txt .item, .m-book1 .item")) {
            val label = item.selectFirst("span[title]")?.attr("title")?.lowercase() ?: continue
            val right = item.selectFirst(".right") ?: continue
            val links = right.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            when (label) {
                "author" -> author = links.joinToString(", ").ifBlank { right.text().trim() }
                "genre" -> genres = links.ifEmpty { right.text().split(',').map { it.trim() } }
                "status" -> status = right.text().trim()
            }
        }
        val rating = doc.selectFirst("[itemprop=ratingValue], .small strong span, .score p")?.text()
            ?.trim()?.toFloatOrNull()
        return NovelDetails(
            sourceId = id,
            path = path,
            title = title,
            author = author?.ifBlank { null },
            cover = cover,
            description = description,
            genres = genres.filter { it.isNotBlank() }.distinct(),
            status = status?.lowercase()?.replaceFirstChar { it.uppercase() },
            rating = rating?.let { if (it > 5f) it / 2f else it },
        )
    }

    override suspend fun getChapters(path: String): List<ChapterInfo> {
        val doc = getDoc(absoluteUrl(path))
        return chaptersFrom(doc, path)
    }

    protected open suspend fun chaptersFrom(doc: Document, path: String): List<ChapterInfo> {
        val ajax = chapterAjax
        if (ajax != null) {
            val novelId = doc.selectFirst("#rating[data-novel-id]")?.attr("data-novel-id")
                ?: doc.selectFirst("[data-novel-id]")?.attr("data-novel-id")
                ?: path.trim('/').substringAfterLast('/').removeSuffix(".html")
            val html = fetch("$baseUrl/$ajax?novelId=${URLEncoder.encode(novelId, "UTF-8")}")
            val list = parseChapterFragment(unwrapHtml(html))
            if (list.isNotEmpty()) return list
        }
        return parseChapterFragment(doc.select("#list-chapter, ul.list-chapter, #idData, .m-newest2, .chapter-list").outerHtml())
    }

    protected fun unwrapHtml(body: String): String {
        val t = body.trimStart()
        if (!t.startsWith("{")) return body
        return try {
            Json.parseToJsonElement(t).jsonObject["html"]?.jsonPrimitive?.content ?: body
        } catch (_: Exception) {
            body
        }
    }

    protected fun parseChapterFragment(html: String): List<ChapterInfo> {
        if (html.isBlank()) return emptyList()
        val frag = Jsoup.parseBodyFragment(html, baseUrl)
        val seen = HashSet<String>()
        val out = mutableListOf<ChapterInfo>()
        val nodes = frag.select("a[href], option[value]")
        for (n in nodes) {
            val href = if (n.normalName() == "option") n.attr("value") else n.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) continue
            val path = toPath(if (href.startsWith("http") || href.startsWith("/")) href else "/$href")
            if (!seen.add(path)) continue
            val nm = n.attr("title").ifBlank { n.text() }.trim().ifBlank { "Chapter ${out.size + 1}" }
            out += ChapterInfo(path, nm, out.size)
        }
        return out
    }

    override suspend fun getChapterText(path: String): ChapterText {
        val doc = getDoc(absoluteUrl(path))
        doc.select("[class*=app-promo], [class*=unlock-buttons], div[class*=ads], div[id*=ads], .chr-nav, .chapter-nav").remove()
        val content = doc.selectFirst("#chr-content, #chapter-content, #article, .chapter-c, div.txt, .txt")
            ?: throw IllegalStateException("Couldn't find the chapter text on this page.")
        var paragraphs = HtmlText.paragraphs(content, HtmlText.COMMON_JUNK)
        paragraphs = HtmlText.dropWatermarks(paragraphs, watermarkWords)
        val title = doc.selectFirst(".chr-title, a.chr-title, .chapter-title, span.chapter, h1.tit, .top h1, .chr-text")
            ?.let { it.attr("title").ifBlank { it.text() } }?.trim()
        if (paragraphs.isEmpty()) throw IllegalStateException("This chapter came back empty.")
        return ChapterText(title, paragraphs)
    }
}
