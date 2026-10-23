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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/** Royal Road: free original English web fiction (LitRPG, progression fantasy and more). */
class RoyalRoadSource(env: HttpEnv) : NovelSource(env) {
    override val id = "royalroad"
    override val name = "Royal Road"
    override val defaultBaseUrl = "https://www.royalroad.com"
    override val description = "Free original English web fiction: LitRPG, progression fantasy, sci-fi."

    override val listings = listOf(
        Listing("fictions/best-rated", "Best rated"),
        Listing("fictions/trending", "Trending"),
        Listing("fictions/weekly-popular", "Popular this week"),
        Listing("fictions/active-popular", "Ongoing popular"),
        Listing("fictions/latest-updates", "Latest updates"),
        Listing("fictions/complete", "Completed"),
        Listing("fictions/rising-stars", "Rising stars"),
    )

    override val genres = listOf(
        "Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Contemporary" to "contemporary",
        "Drama" to "drama", "Fantasy" to "fantasy", "Historical" to "historical", "Horror" to "horror",
        "Mystery" to "mystery", "Psychological" to "psychological", "Romance" to "romance", "Satire" to "satire",
        "Sci-fi" to "sci_fi", "Tragedy" to "tragedy", "LitRPG" to "litrpg", "Progression" to "progression",
        "Portal Fantasy / Isekai" to "summoned_hero", "Reincarnation" to "reincarnation", "Magic" to "magic",
        "Cultivation" to "wuxia", "Xianxia" to "xianxia", "Dungeon" to "dungeon", "System Invasion" to "system_invasion",
        "Slice of Life" to "slice_of_life", "Space Opera" to "space_opera", "Villainous Lead" to "villainous_lead",
        "Female Lead" to "female_lead", "Male Lead" to "male_lead", "Grimdark" to "grimdark", "Harem" to "harem",
    ).map { Genre(it.first, it.second) }

    override suspend fun builtInListing(listing: Listing, page: Int): PageResult {
        val doc = getDoc("$baseUrl/${listing.id}?page=$page")
        return PageResult(parseList(doc), hasNext(doc, page))
    }

    override suspend fun builtInGenre(genre: Genre, page: Int): PageResult {
        val doc = getDoc("$baseUrl/fictions/search?tagsAdd=${genre.id}&orderBy=popularity&page=$page")
        return PageResult(parseList(doc), hasNext(doc, page))
    }

    override suspend fun builtInSearch(query: String, page: Int): PageResult {
        val doc = getDoc("$baseUrl/fictions/search?title=${SourceCatalog.enc(query)}&page=$page")
        return PageResult(parseList(doc), hasNext(doc, page))
    }

    private fun hasNext(doc: Document, page: Int): Boolean =
        doc.select("ul.pagination a[data-page]").any { (it.attr("data-page").toIntOrNull() ?: 0) > page }

    private fun parseList(doc: Document): List<NovelItem> {
        val seen = HashSet<String>()
        return doc.select(".fiction-list-item").mapNotNull { el ->
            val a = el.selectFirst("h2.fiction-title a, .fiction-title a") ?: el.selectFirst("a[href^=/fiction/]") ?: return@mapNotNull null
            val parts = a.attr("href").trim('/').split('/')
            if (parts.size < 2) return@mapNotNull null
            val path = "/" + parts.take(3).joinToString("/")
            if (!seen.add(path)) return@mapNotNull null
            val img = el.selectFirst("img")
            val title = a.text().trim().ifBlank { img?.attr("alt").orEmpty() }
            val cover = absoluteImage(img?.attr("src"))?.takeUnless { it.contains("nocover", true) }
            val sub = el.select(".tags a, span.tags a").take(3).joinToString(" · ") { it.text().trim() }
            NovelItem(id, path, title, cover, sub.ifBlank { null })
        }
    }

    override suspend fun builtInDetails(path: String): NovelDetails {
        val doc = getDoc(absoluteUrl(path))
        val labels = doc.select(".fiction-info span.label, .fiction-info .label-sm").map { it.text().trim().uppercase() }
        val status = labels.firstOrNull { it in setOf("ONGOING", "COMPLETED", "HIATUS", "STUB", "DROPPED", "INACTIVE") }
        val rating = doc.selectFirst("meta[property=books:rating:value]")?.attr("content")?.toFloatOrNull()
            ?: doc.selectFirst("[data-original-title=Overall Score]")?.attr("data-content")?.substringBefore('/')?.trim()?.toFloatOrNull()
        return NovelDetails(
            sourceId = id,
            path = path,
            title = doc.selectFirst(".fic-title h1, h1")?.text()?.trim().orEmpty(),
            author = doc.selectFirst(".fic-title h4 a, h4 a[href^=/profile/]")?.text()?.trim(),
            cover = absoluteImage(doc.selectFirst(".cover-art-container img, img.thumbnail")?.attr("src")),
            description = HtmlText.plain(doc.selectFirst(".description .hidden-content, .description")),
            genres = doc.select(".tags a, span.tags a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = status?.lowercase()?.replaceFirstChar { it.uppercase() },
            rating = rating,
        )
    }

    override suspend fun builtInChapters(path: String): List<ChapterInfo> {
        val doc = getDoc(absoluteUrl(path))
        val script = doc.select("script").map { it.data() }.firstOrNull { it.contains("window.chapters") }
        if (script != null) {
            val json = Regex("window\\.chapters\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
            if (json != null) {
                val arr = Json.parseToJsonElement(json).jsonArray
                val list = arr.mapNotNull { e ->
                    val o = e.jsonObject
                    val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val visible = o["visible"]?.jsonPrimitive?.intOrNull ?: 1
                    if (visible == 0) return@mapNotNull null
                    Triple(o["order"]?.jsonPrimitive?.intOrNull ?: 0, url, o)
                }.sortedBy { it.first }
                if (list.isNotEmpty()) {
                    return list.mapIndexed { i, (_, url, o) ->
                        ChapterInfo(
                            path = toPath(url),
                            name = o["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "Chapter ${i + 1}" },
                            index = i,
                            date = o["date"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
            }
        }
        return doc.select("#chapters tbody tr a[href*=/chapter/]").distinctBy { it.attr("href") }.mapIndexed { i, a ->
            ChapterInfo(toPath(a.attr("href")), a.text().trim(), i)
        }
    }

    override suspend fun builtInChapterText(path: String): ChapterText {
        val doc = getDoc(absoluteUrl(path))
        // Royal Road hides "stolen from Royal Road" lines with CSS classes that make them invisible.
        val css = doc.select("style").joinToString("\n") { it.data() }
        val hidden = Regex("\\.([A-Za-z0-9_-]+)\\s*\\{[^}]*display:\\s*none", RegexOption.IGNORE_CASE)
            .findAll(css).map { it.groupValues[1] }.toSet()
        val content = doc.selectFirst(".chapter-content") ?: throw IllegalStateException("Couldn't find the chapter text on this page.")
        hidden.forEach { cls -> content.select(".$cls").remove() }
        val paragraphs = HtmlText.paragraphs(content, HtmlText.COMMON_JUNK).filterNot {
            it.contains("Royal Road", true) && it.length < 200 &&
                (it.contains("stolen", true) || it.contains("without the author", true) || it.contains("report", true) || it.contains("unauthorized", true))
        }
        if (paragraphs.isEmpty()) throw IllegalStateException("This chapter came back empty.")
        val title = doc.selectFirst(".fic-header h1, h1")?.text()?.trim()
        return ChapterText(title, paragraphs)
    }
}
