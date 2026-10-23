package com.joe.novelreader.core.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fixes for a source that can be changed without a new app version.
 *
 * The app downloads these from `sources/rules.json` in the GitHub repo (and the user can also
 * type their own on the phone). Everything is optional: anything left out uses the built-in code.
 * See `sources/README.md` for how to write one.
 */
@Serializable
data class SourceRules(
    /** New site address when a site moves (the user's own address in settings still wins). */
    val baseUrl: String? = null,
    /** Turn the source off for everyone, for example when a site shuts down. */
    val disabled: Boolean = false,
    /** A short message shown on the source, for example "Site is down, use NovelFire". */
    val notice: String? = null,
    /** Extra request headers, for example a different Referer. */
    val headers: Map<String, String> = emptyMap(),
    /** Web address patterns. {base}, {page}, {query}, {listing}, {genre}, {path}, {slug} are filled in. */
    val urls: UrlRules = UrlRules(),
    /** Overrides for reading lists of novels (popular, search results, genres). */
    val list: ListRules? = null,
    /** Overrides for the book page. */
    val details: DetailsRules? = null,
    /** Overrides for the chapter list. */
    val chapters: ChapterListRules? = null,
    /** Overrides for chapter text. */
    val chapter: ChapterRules? = null,
    /** Rewrites cover image links: each "from" is a regular expression, "to" its replacement. */
    val coverRewrite: List<Rewrite> = emptyList(),
) {
    val isEmpty: Boolean get() = this == SourceRules()

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = false; prettyPrint = true }
    }
}

@Serializable
data class UrlRules(
    val listing: String? = null,
    val genre: String? = null,
    val search: String? = null,
    val details: String? = null,
    val chapters: String? = null,
)

@Serializable
data class ListRules(
    /** One element per novel. */
    val item: String,
    /** Link to the book inside the item. Defaults to the first link. */
    val link: String? = null,
    /** Defaults to the link's title or text. */
    val title: String? = null,
    /** Defaults to the first image. */
    val cover: String? = null,
    val subtitle: String? = null,
    /** Present when there is a next page. */
    val next: String? = null,
)

@Serializable
data class DetailsRules(
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    val genres: String? = null,
    val status: String? = null,
)

@Serializable
data class ChapterListRules(
    /** One element per chapter, usually a link. */
    val item: String,
    val name: String? = null,
    /** True when the page lists the newest chapter first. */
    val reversed: Boolean = false,
)

@Serializable
data class ChapterRules(
    /** The element holding the chapter text. */
    val content: String? = null,
    val title: String? = null,
    /** Elements to delete before reading the text (ads, buttons...). */
    val remove: List<String> = emptyList(),
    /** Paragraphs matching any of these regular expressions are dropped. */
    val dropParagraphs: List<String> = emptyList(),
)

@Serializable
data class Rewrite(val from: String, val to: String)

/** The whole rules file. */
@Serializable
data class RulesFile(
    val version: Int = 1,
    val updated: String? = null,
    val sources: Map<String, SourceRules> = emptyMap(),
) {
    companion object {
        fun parse(text: String): RulesFile = SourceRules.json.decodeFromString(serializer(), text)
    }
}
