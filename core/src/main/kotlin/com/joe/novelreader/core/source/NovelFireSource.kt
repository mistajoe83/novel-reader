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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** NovelFire (the site that replaced LightNovelPub / LightNovelWorld). */
class NovelFireSource(env: HttpEnv) : NovelSource(env) {
    override val id = "novelfire"
    override val name = "NovelFire"
    override val defaultBaseUrl = "https://novelfire.net"
    override val description = "Formerly LightNovelPub. Big catalogue with rankings and ratings."

    override val listings = listOf(
        Listing("rank-top", "Top ranked"),
        Listing("monthly-view", "Popular this month"),
        Listing("date", "Latest updates"),
        Listing("rating-score-top", "Top rated"),
        Listing("completed", "Completed"),
    )

    override val genres = listOf(
        "Action" to "3", "Adult" to "28", "Adventure" to "4", "Comedy" to "5", "Drama" to "24",
        "Eastern" to "44", "Ecchi" to "26", "Fan-fiction" to "48", "Fantasy" to "6", "Game" to "19",
        "Gender Bender" to "25", "Harem" to "7", "Historical" to "12", "Horror" to "37", "Isekai" to "49",
        "Josei" to "2", "Magic" to "50", "Martial Arts" to "15", "Mature" to "8", "Mecha" to "34",
        "Military" to "53", "Modern life" to "54", "Mystery" to "16", "Psychological" to "9",
        "Reincarnation" to "43", "Romance" to "1", "School Life" to "21", "Sci-fi" to "20",
        "Seinen" to "10", "Shoujo" to "38", "Shounen" to "17", "Slice of Life" to "13", "Sports" to "42",
        "Supernatural" to "18", "System" to "58", "Tragedy" to "32", "Urban" to "63", "Video games" to "60",
        "War" to "61", "Wuxia" to "31", "Xianxia" to "23", "Xuanhuan" to "22",
    ).map { Genre(it.first, it.second) }

    private fun advUrl(sort: String, page: Int, genre: String? = null, status: String = "-1"): String {
        val p = buildList {
            add("ctgcon" to "and")
            if (genre != null) add("categories[]" to genre)
            add("totalchapter" to "0")
            add("ratcon" to "min")
            add("rating" to "0")
            add("status" to status)
            add("sort" to sort)
            add("tagcon" to "and")
            add("page" to page.toString())
        }
        return "$baseUrl/search-adv?" + p.joinToString("&") { (k, v) -> "${SourceCatalog.enc(k)}=${SourceCatalog.enc(v)}" }
    }

    override suspend fun getListing(listing: Listing, page: Int): PageResult {
        val url = if (listing.id == "completed") advUrl("rank-top", page, status = "1") else advUrl(listing.id, page)
        val doc = getDoc(url)
        return PageResult(parseItems(doc, ".novel-item"), hasNext(doc))
    }

    override suspend fun getGenre(genre: Genre, page: Int): PageResult {
        val doc = getDoc(advUrl("rank-top", page, genre.id))
        return PageResult(parseItems(doc, ".novel-item"), hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): PageResult {
        val doc = getDoc("$baseUrl/search?keyword=${SourceCatalog.enc(query)}&page=$page")
        var items = parseItems(doc, ".novel-list.chapters .novel-item")
        if (items.isEmpty()) items = parseItems(doc, ".novel-item")
        return PageResult(items, hasNext(doc))
    }

    private fun hasNext(doc: Document): Boolean =
        doc.selectFirst("a[rel=next], .pagination li.page-item:not(.disabled) a[rel=next], .PagedList-skipToNext a") != null

    private fun parseItems(doc: Document, selector: String): List<NovelItem> {
        val seen = HashSet<String>()
        return doc.select(selector).mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val path = toPath(href)
            if (!seen.add(path)) return@mapNotNull null
            val title = a.attr("title").ifBlank { el.selectFirst("h4, .novel-title")?.text().orEmpty() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val img = el.selectFirst(".novel-cover img, img")
            val cover = img?.let { pick(it) }
            val sub = el.selectFirst(".novel-stats, .chapter-title")?.text()?.trim()
            NovelItem(id, path, title, cover, sub?.ifBlank { null })
        }
    }

    private fun pick(img: Element): String? = absoluteImage(
        listOf("data-src", "data-original", "src").map { img.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") },
    )

    override suspend fun getDetails(path: String): NovelDetails {
        val doc = getDoc(absoluteUrl(path))
        val title = doc.selectFirst(".novel-title")?.text()?.trim()
            ?: doc.selectFirst(".cover > img")?.attr("alt").orEmpty()
        val cover = doc.selectFirst(".cover > img, .fixed-img img")?.let { pick(it) }
        val summary = doc.selectFirst(".summary .content")?.apply { select(".expand").remove() }
        val status = listOf(".header-stats .ongoing", ".header-stats .completed", ".header-stats .hiatus")
            .firstNotNullOfOrNull { doc.selectFirst(it)?.text()?.trim()?.ifBlank { null } }
        return NovelDetails(
            sourceId = id,
            path = path,
            title = title,
            author = doc.selectFirst(".author .property-item > span, .author a span, .author a")?.text()?.trim(),
            cover = cover,
            description = HtmlText.plain(summary),
            genres = doc.select(".categories .property-item").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status?.replaceFirstChar { it.uppercase() },
            rating = doc.selectFirst(".nub")?.text()?.trim()?.toFloatOrNull(),
        )
    }

    override suspend fun getChapters(path: String): List<ChapterInfo> {
        val doc = getDoc(absoluteUrl(path))
        val postId = doc.selectFirst("#novel-report")?.attr("report-post_id")?.ifBlank { null }
        val clean = path.trimEnd('/')
        if (postId != null) {
            try {
                val list = ajaxChapters(clean, postId)
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
                // fall back to the chapter pages
            }
        }
        val out = mutableListOf<ChapterInfo>()
        val seen = HashSet<String>()
        var page = 1
        while (page <= 200) {
            val d = getDoc("$baseUrl$clean/chapters?page=$page")
            val links = d.select(".chapter-list li a[href]")
            var added = 0
            for (a in links) {
                val p = toPath(a.attr("href"))
                if (!seen.add(p)) continue
                val nm = a.attr("title").ifBlank { a.selectFirst(".chapter-title")?.text() ?: a.text() }.trim()
                out += ChapterInfo(p, nm, out.size)
                added++
            }
            if (added == 0 || d.selectFirst("a[rel=next]") == null) break
            page++
        }
        return out
    }

    private suspend fun ajaxChapters(novelPath: String, postId: String): List<ChapterInfo> {
        val params = linkedMapOf(
            "draw" to "1",
            "columns[0][data]" to "n_sort",
            "columns[0][name]" to "cmm_posts_detail.n_sort",
            "columns[0][searchable]" to "true",
            "columns[0][orderable]" to "true",
            "columns[0][search][value]" to "",
            "columns[0][search][regex]" to "false",
            "order[0][column]" to "0",
            "order[0][dir]" to "asc",
            "order[0][name]" to "cmm_posts_detail.n_sort",
            "start" to "0",
            "length" to "-1",
            "search[value]" to "",
            "search[regex]" to "false",
            "post_id" to postId,
            "only_bookmark" to "false",
            "_" to System.currentTimeMillis().toString(),
        )
        val q = params.entries.joinToString("&") { "${SourceCatalog.enc(it.key)}=${SourceCatalog.enc(it.value)}" }
        val body = fetch("$baseUrl/ajax/listChapterDataAjax?$q", extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"))
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e.jsonObject
            val n = o["n_sort"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            val raw = o["title"]?.jsonPrimitive?.contentOrNull ?: o["slug"]?.jsonPrimitive?.contentOrNull ?: "Chapter $n"
            val nm = Jsoup.parse(raw).text().replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "").trim()
            n to nm
        }.sortedBy { it.first }.mapIndexed { i, (n, nm) ->
            ChapterInfo("$novelPath/chapter-$n", nm.ifBlank { "Chapter $n" }, i)
        }
    }

    override suspend fun getChapterText(path: String): ChapterText {
        val doc = getDoc(absoluteUrl(path))
        val content = doc.selectFirst("#content") ?: throw IllegalStateException("Couldn't find the chapter text on this page.")
        // The site sprinkles made-up tags (like <nfxyz>) with junk text into chapters.
        content.getAllElements().filter { it.normalName().startsWith("nf") && it.normalName().length > 5 }.forEach { it.remove() }
        content.select(".box-ads, .ad-container, [class*=ads]").remove()
        val paragraphs = HtmlText.dropWatermarks(HtmlText.paragraphs(content, HtmlText.COMMON_JUNK), listOf("novelfire", "lightnovelpub"))
        if (paragraphs.isEmpty()) throw IllegalStateException("This chapter came back empty.")
        val title = doc.selectFirst(".chapter-title, span.chapter-title, h2")?.text()?.trim()
        return ChapterText(title, paragraphs)
    }
}
