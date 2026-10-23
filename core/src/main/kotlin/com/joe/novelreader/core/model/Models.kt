package com.joe.novelreader.core.model

import kotlinx.serialization.Serializable

/** A novel as it appears in a list (search results, rankings, genre pages). */
@Serializable
data class NovelItem(
    val sourceId: String,
    /** Path relative to the source's site, always starting with "/". */
    val path: String,
    val title: String,
    val cover: String? = null,
    val subtitle: String? = null,
)

/** Full information shown on the book page. */
@Serializable
data class NovelDetails(
    val sourceId: String,
    val path: String,
    val title: String,
    val author: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val rating: Float? = null,
)

@Serializable
data class ChapterInfo(
    val path: String,
    val name: String,
    /** 0-based position in reading order. */
    val index: Int,
    val date: String? = null,
)

/** A chapter's readable text, split into paragraphs. */
data class ChapterText(
    val title: String?,
    val paragraphs: List<String>,
)

data class PageResult(
    val novels: List<NovelItem>,
    val hasNextPage: Boolean,
)

/** One way to browse a source, such as "Most popular" or "Latest updates". */
data class Listing(val id: String, val name: String)

/** A genre a source can filter by. [id] is whatever the source needs in its URL. */
data class Genre(val name: String, val id: String)
