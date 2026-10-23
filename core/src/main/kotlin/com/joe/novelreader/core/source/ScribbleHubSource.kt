package com.joe.novelreader.core.source

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelDetails
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.model.PageResult
import com.joe.novelreader.core.util.HtmlText
import okhttp3.MultipartBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Scribble Hub: original English web novels, many in the Asian web-novel style. */
class ScribbleHubSource(env: HttpEnv) : NovelSource(env) {
    override val id = "scribblehub"
    override val name = "Scribble Hub"
    override val defaultBaseUrl = "https://www.scribblehub.com"
    override val description = "Original English web novels: isekai, fantasy, romance."

    override val listings = listOf(
        Listing("ratings", "Top rated"),
        Listing("readers", "Most readers"),
        Listing("pageviews", "Most viewed"),
        Listing("frequency", "Fast updates"),
        Listing("latest", "Latest updates"),
    )

    override val genres = listOf(
        "Action" to "9", "Adventure" to "8", "Comedy" to "7", "Drama" to "903", "Fantasy" to "19",
        "Gender Bender" to "905", "Harem" to "1015", "Historical" to "21", "Horror" to "22", "Isekai" to "37",
        "Josei" to "906", "LitRPG" to "1180", "Martial Arts" to "907", "Mature" to "20", "Mecha" to "908",
        "Mystery" to "909", "Psychological" to "910", "Romance" to "6", "School Life" to "911", "Sci-fi" to "912",
        "Seinen" to "913", "Slice of Life" to "914", "Sports" to "916", "Supernatural" to "5", "Tragedy" to "901",
        "Fanfiction" to "38",
    ).map { Genre(it.first, it.second) }

    override suspend fun builtInListing(listing: Listing, page: Int): PageResult {
        val url = if (listing.id == "latest") "$baseUrl/latest-series/?pg=$page"
        else "$baseUrl/series-finder/?sf=1&sort=${listing.id}&order=desc&pg=$page"
        val doc = getDoc(url)
        val items = parseList(doc)
        return PageResult(items, items.isNotEmpty() && hasNext(doc, page))
    }

    override suspend fun builtInGenre(genre: Genre, page: Int): PageResult {
        val doc = getDoc("$baseUrl/series-finder/?sf=1&gi=${genre.id}&mgi=and&sort=ratings&order=desc&pg=$page")
        val items = parseList(doc)
        return PageResult(items, items.isNotEmpty() && hasNext(doc, page))
    }

    override suspend fun builtInSearch(query: String, page: Int): PageResult {
        if (page > 1) return PageResult(emptyList(), false)
        val doc = getDoc("$baseUrl/?s=${SourceCatalog.enc(query)}&post_type=fictionposts")
        return PageResult(parseList(doc), false)
    }

    private fun hasNext(doc: Document, page: Int): Boolean =
        doc.select("#pagination-mesh a, .simple-pagination a, a.page-link").any { it.text().trim().toIntOrNull()?.let { n -> n > page } == true } ||
            doc.selectFirst("a.next, .next.page-link") != null

    private fun parseList(doc: Document): List<NovelItem> {
        val seen = HashSet<String>()
        return doc.select(".search_main_box").mapNotNull { el ->
            val a = el.selectFirst(".search_title > a, .search_title a") ?: return@mapNotNull null
            val path = toPath(a.attr("href"))
            if (!seen.add(path)) return@mapNotNull null
            val cover = absoluteImage(el.selectFirst(".search_img img")?.attr("src"))
            val sub = el.select(".search_genre a, .fic_genre").take(3).joinToString(" · ") { it.text().trim() }
            NovelItem(id, path, a.text().trim(), cover, sub.ifBlank { null })
        }
    }

    override suspend fun builtInDetails(path: String): NovelDetails {
        val doc = getDoc(absoluteUrl(path))
        val status = doc.select(".rnd_stats").firstOrNull()?.nextElementSibling()?.text().orEmpty()
        val widget = doc.select(".widget_fic_similar li, span.rnd_stats + span").text()
        val statusText = when {
            status.contains("Ongoing", true) || widget.contains("Ongoing", true) -> "Ongoing"
            status.contains("Completed", true) || widget.contains("Completed", true) -> "Completed"
            status.contains("Hiatus", true) -> "Hiatus"
            else -> null
        }
        return NovelDetails(
            sourceId = id,
            path = path,
            title = doc.selectFirst(".fic_title")?.text()?.trim().orEmpty(),
            author = doc.selectFirst(".auth_name_fic")?.text()?.trim(),
            cover = absoluteImage(doc.selectFirst(".fic_image img")?.attr("src")),
            description = HtmlText.plain(doc.selectFirst(".wi_fic_desc")),
            genres = doc.select(".fic_genre").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = statusText,
            rating = doc.selectFirst("#ratefic_user > span, .fic_rate span")?.text()?.trim()?.toFloatOrNull(),
        )
    }

    override suspend fun builtInChapters(path: String): List<ChapterInfo> {
        val postId = path.trim('/').split('/').getOrNull(1)
            ?: throw IllegalStateException("Couldn't read this novel's id")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "wi_getreleases_pagination")
            .addFormDataPart("pagenum", "-1")
            .addFormDataPart("mypostid", postId)
            .build()
        val html = fetch("$baseUrl/wp-admin/admin-ajax.php", body, mapOf("X-Requested-With" to "XMLHttpRequest"))
        val frag = Jsoup.parseBodyFragment(html, baseUrl)
        val list = frag.select(".toc_w").mapNotNull { el ->
            val a = el.selectFirst("a.toc_a, a[href]") ?: return@mapNotNull null
            Triple(toPath(a.attr("href")), a.text().trim(), el.selectFirst(".fic_date_pub")?.attr("title"))
        }.reversed()
        return list.mapIndexed { i, (p, n, d) -> ChapterInfo(p, n.ifBlank { "Chapter ${i + 1}" }, i, d) }
    }

    override suspend fun builtInChapterText(path: String): ChapterText {
        val doc = getDoc(absoluteUrl(path))
        val content = doc.selectFirst("div.chp_raw, #chp_raw") ?: throw IllegalStateException("Couldn't find the chapter text on this page.")
        content.select(".wi_authornotes, .modern-footnotes-footnote__note").remove()
        val paragraphs = HtmlText.paragraphs(content, HtmlText.COMMON_JUNK)
        if (paragraphs.isEmpty()) throw IllegalStateException("This chapter came back empty.")
        return ChapterText(doc.selectFirst(".chapter-title")?.text()?.trim(), paragraphs)
    }
}
