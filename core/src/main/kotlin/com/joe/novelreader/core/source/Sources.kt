package com.joe.novelreader.core.source

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import org.jsoup.nodes.Document
import java.net.URLEncoder

private fun genres(prefix: String, vararg names: String, encode: (String) -> String): List<Genre> =
    names.map { Genre(it, prefix + encode(it)) }

private val COMMON_GENRES = arrayOf(
    "Action", "Adventure", "Comedy", "Drama", "Eastern", "Fantasy", "Game", "Harem", "Historical",
    "Horror", "Isekai", "Josei", "Martial Arts", "Mature", "Mecha", "Mystery", "Psychological",
    "Reincarnation", "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo", "Shounen",
    "Slice of Life", "Sports", "Supernatural", "System", "Tragedy", "Urban", "Wuxia", "Xianxia", "Xuanhuan",
)

class NovelBinSource(env: HttpEnv) : ReadNovelFullSource(
    env = env,
    id = "novelbin",
    name = "NovelBin",
    defaultBaseUrl = "https://novelbin.com",
    description = "Huge library of translated Chinese, Korean and Japanese web novels.",
    listings = listOf(
        Listing("sort/top-view-novel", "Most popular"),
        Listing("sort/top-hot-novel", "Hot"),
        Listing("sort/latest", "Latest updates"),
        Listing("sort/completed", "Completed"),
    ),
    genres = genres("genre/", *COMMON_GENRES) { it.lowercase().replace(' ', '-') },
    searchPath = "search",
    chapterAjax = "ajax/chapter-archive",
    watermarkWords = listOf("novelbin"),
) {
    // NovelBin's own image host (images.novelbin.com) does not resolve any more. NovelFire is a
    // sister site that uses the same novel slugs and serves the same covers in full size.
    override fun upgradeCover(url: String): String {
        val m = Regex("images\\.novelbin\\.[a-z]+/novel(?:_\\d+_\\d+)?/([^/?#]+)\\.(?:jpg|jpeg|png|webp)").find(url) ?: return url
        return "https://novelfire.net/server-1/${m.groupValues[1]}.jpg"
    }
}

class ReadNovelFullComSource(env: HttpEnv) : ReadNovelFullSource(
    env = env,
    id = "readnovelfull",
    name = "ReadNovelFull",
    defaultBaseUrl = "https://readnovelfull.com",
    description = "Popular translated novels, long-running series.",
    listings = listOf(
        Listing("novel-list/most-popular-novel", "Most popular"),
        Listing("novel-list/hot-novel", "Hot"),
        Listing("novel-list/latest-release-novel", "Latest updates"),
        Listing("novel-list/completed-novel", "Completed"),
    ),
    genres = genres("genres/", *COMMON_GENRES) { it.lowercase().replace(' ', '+') },
    searchPath = "novel-list/search",
    chapterAjax = "ajax/chapter-archive",
    watermarkWords = listOf("readnovelfull"),
) {
    override fun upgradeCover(url: String): String = url.replace(Regex("/thumb/t-\\d+x\\d+/"), "/thumb/t-300x439/")
}

class NovelFullSource(env: HttpEnv) : ReadNovelFullSource(
    env = env,
    id = "novelfull",
    name = "NovelFull",
    defaultBaseUrl = "https://novelfull.com",
    description = "Classic site with many completed translations.",
    listings = listOf(
        Listing("most-popular", "Most popular"),
        Listing("hot-novel", "Hot"),
        Listing("latest-release-novel", "Latest updates"),
        Listing("completed-novel", "Completed"),
    ),
    genres = genres("genre/", *COMMON_GENRES) { it.replace(' ', '+') },
    searchPath = "search",
    chapterAjax = "ajax-chapter-option",
    watermarkWords = listOf("novelfull"),
)

class FreeWebNovelSource(env: HttpEnv) : ReadNovelFullSource(
    env = env,
    id = "freewebnovel",
    name = "FreeWebNovel",
    defaultBaseUrl = "https://freewebnovel.com",
    description = "Fast updates for popular Chinese and Korean novels.",
    listings = listOf(
        Listing("sort/most-popular", "Most popular"),
        Listing("sort/latest-release", "Latest updates"),
        Listing("sort/latest-release/chinese-novel", "Chinese"),
        Listing("sort/latest-release/korean-novel", "Korean"),
        Listing("sort/latest-release/japanese-novel", "Japanese"),
        Listing("sort/completed-novel", "Completed"),
    ),
    genres = genres("genre/", *COMMON_GENRES) { it.replace(' ', '+') },
    searchPath = "search",
    chapterAjax = null,
    pageAsPath = true,
    singlePage = setOf("sort/most-popular"),
    watermarkWords = listOf("freewebnovel", "freewebnovelcom", "libread"),
) {
    override suspend fun chaptersFrom(doc: Document, path: String): List<ChapterInfo> {
        val onPage = parseChapterFragment(doc.select("#idData, ul.ul-list5, .m-newest2 ul").outerHtml())
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val total = doc.selectFirst("#indexListPage[data-total-chapters]")?.attr("data-total-chapters")?.toIntOrNull()
            ?: Regex("totalChapters:\\s*(\\d+)").find(scripts)?.groupValues?.get(1)?.toIntOrNull()
        if (total == null || total <= onPage.size) return onPage

        val novelId = doc.selectFirst("a.set-case[data-articleid]")?.attr("data-articleid")
            ?: doc.selectFirst("#indexListPage[data-novel-id]")?.attr("data-novel-id")
            ?: Regex("sourceid=(\\d+)").find(scripts)?.groupValues?.get(1)
        val slug = path.trim('/').substringAfterLast('/').removeSuffix(".html")
        if (novelId != null) {
            try {
                val body = postForm(
                    "$baseUrl/api/chapterlist.php",
                    mapOf("aid" to novelId, "acode" to slug, "cid" to ((0 until total).random()).toString()),
                    mapOf("X-Requested-With" to "XMLHttpRequest"),
                )
                val list = parseChapterFragment(unwrapHtml(body))
                if (list.size >= onPage.size && list.isNotEmpty()) return list
            } catch (_: Exception) {
                // fall through to building the list ourselves
            }
        }
        // Chapter addresses follow a simple pattern, so build the whole list.
        val base = path.removeSuffix(".html").trimEnd('/')
        val ext = if (path.endsWith(".html")) ".html" else ""
        return (1..total).map { n ->
            val known = onPage.getOrNull(n - 1)
            ChapterInfo(known?.path ?: "$base/chapter-$n$ext", known?.name ?: "Chapter $n", n - 1)
        }
    }
}

/** Every source the app knows about, in the order shown. */
object SourceCatalog {
    fun create(env: HttpEnv): List<NovelSource> = listOf(
        NovelBinSource(env),
        FreeWebNovelSource(env),
        NovelFireSource(env),
        RoyalRoadSource(env),
        ScribbleHubSource(env),
        ReadNovelFullComSource(env),
        NovelFullSource(env),
    )

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
