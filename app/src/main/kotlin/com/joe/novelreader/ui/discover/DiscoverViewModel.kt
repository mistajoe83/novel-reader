package com.joe.novelreader.ui.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joe.novelreader.AppContainer
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.source.NovelSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

data class DiscoverSection(
    val key: String,
    val title: String,
    val sourceId: String,
    val listingId: String,
    val state: Load<List<NovelItem>>,
)

data class Recommendations(val reason: String, val items: List<NovelItem>)

class DiscoverViewModel(private val c: AppContainer) : ViewModel() {
    var hero by mutableStateOf<Load<List<NovelItem>>>(Load.Loading)
        private set
    var ranking by mutableStateOf<Load<List<NovelItem>>>(Load.Loading)
        private set
    var rankingTitle by mutableStateOf("Top ranked")
        private set
    var recommended by mutableStateOf<Load<Recommendations>>(Load.Loading)
        private set
    var sections by mutableStateOf<List<DiscoverSection>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var lastLoad = 0L

    init {
        load()
    }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastLoad > 30 * 60 * 1000) load()
    }

    fun load(pull: Boolean = false) {
        lastLoad = System.currentTimeMillis()
        if (pull) refreshing = true
        val enabled = c.sources.enabled
        val main = enabled.firstOrNull { it.id == "novelbin" } ?: enabled.firstOrNull()
        val ranked = enabled.firstOrNull { it.id == "novelfire" } ?: main

        viewModelScope.launch {
            launch {
                hero = if (main == null) Load.Ok(emptyList()) else fetch(main, main.listings.firstOrNull { it.name.contains("Hot") } ?: main.listings.first()) { it.take(8) }
            }
            launch {
                if (ranked != null) {
                    val l = ranked.listings.first()
                    rankingTitle = "${l.name} on ${ranked.name}"
                    ranking = fetch(ranked, l) { it.take(15) }
                } else {
                    ranking = Load.Ok(emptyList())
                }
            }
            launch { recommended = loadRecommendations(enabled) }

            val wanted = buildList {
                enabled.forEach { s ->
                    add(s to s.listings.first())
                    if (s.id == "novelbin" || s.id == "royalroad") {
                        s.listings.firstOrNull { it.name.startsWith("Latest") }?.let { add(s to it) }
                    }
                    if (s.id == "novelfire") s.listings.firstOrNull { it.id == "completed" }?.let { add(s to it) }
                }
            }
            sections = wanted.map { (s, l) -> DiscoverSection(s.id + l.id, sectionTitle(s, l), s.id, l.id, Load.Loading) }
            wanted.forEach { (s, l) ->
                launch {
                    val result = fetch(s, l) { it }
                    sections = sections.map { if (it.key == s.id + l.id) it.copy(state = result) else it }
                }
            }
        }.invokeOnCompletion { refreshing = false }
    }

    private fun sectionTitle(s: NovelSource, l: Listing): String = when {
        l.name.startsWith("Latest") -> "Latest updates · ${s.name}"
        l.id == "completed" || l.name == "Completed" -> "Completed stories · ${s.name}"
        else -> "${l.name} · ${s.name}"
    }

    private suspend fun fetch(s: NovelSource, l: Listing, pick: (List<NovelItem>) -> List<NovelItem>): Load<List<NovelItem>> =
        try {
            Load.Ok(pick(s.getListing(l, 1).novels))
        } catch (e: Exception) {
            Load.Failed(e)
        }

    private suspend fun loadRecommendations(enabled: List<NovelSource>): Load<Recommendations> = try {
        val genres = c.repository.readGenres()
        val known = c.repository.knownPaths()
        val counts = genres.groupingBy { it.lowercase() }.eachCount()
        val top = counts.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val pool = enabled.filter { it.genres.isNotEmpty() }.take(4)
        if (top.isEmpty() || pool.isEmpty()) {
            val trending = enabled.firstOrNull { it.id == "novelfire" }?.let { s -> s to (s.listings.firstOrNull { it.id == "monthly-view" } ?: s.listings.first()) }
                ?: enabled.firstOrNull()?.let { it to it.listings.first() }
            val items = trending?.let { (s, l) -> s.getListing(l, 1).novels } ?: emptyList()
            Load.Ok(Recommendations("Trending now", items.take(15)))
        } else {
            val lists = coroutineScope {
                top.flatMap { g -> pool.map { s -> g to s } }.map { (g, s) ->
                    async {
                        val genre = s.genres.firstOrNull { it.name.equals(g, true) } ?: return@async emptyList()
                        runCatching { s.getGenre(genre, 1).novels }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            val seenTitles = HashSet<String>()
            val merged = mutableListOf<NovelItem>()
            val maxLen = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxLen) {
                for (l in lists) {
                    val n = l.getOrNull(i) ?: continue
                    val key = n.title.lowercase().filter { it.isLetterOrDigit() }
                    if (n.path in known || !seenTitles.add(key)) continue
                    merged += n
                }
            }
            val reason = "Because you like " + top.joinToString(", ") { it.replaceFirstChar { ch -> ch.uppercase() } }
            Load.Ok(Recommendations(reason, merged.take(18)))
        }
    } catch (e: Exception) {
        Load.Failed(e)
    }
}
