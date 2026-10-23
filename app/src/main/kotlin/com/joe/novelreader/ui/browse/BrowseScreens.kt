package com.joe.novelreader.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joe.novelreader.core.model.Genre
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.source.NovelSource
import com.joe.novelreader.ui.components.Chip
import com.joe.novelreader.ui.components.EmptyBox
import com.joe.novelreader.ui.components.ErrorBox
import com.joe.novelreader.ui.components.LoadingBox
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.NovelGridCard
import com.joe.novelreader.ui.components.NovelRow
import com.joe.novelreader.ui.components.SectionHeader
import com.joe.novelreader.ui.discover.Load
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.abs

private val sourceColors = listOf(
    Color(0xFF3D63F2), Color(0xFFFF7A45), Color(0xFF00A88E), Color(0xFFE5486B),
    Color(0xFF6B4EFF), Color(0xFF2196F3), Color(0xFF8D6E63),
)

@Composable
fun SourceIcon(source: NovelSource, size: Int = 40) {
    val color = sourceColors[abs(source.id.hashCode()) % sourceColors.size]
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            source.name.filter { it.isUpperCase() }.take(2).ifBlank { source.name.take(2) },
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The "Browse" tab: all sources, plus a search box that searches every one of them. */
@Composable
fun BrowseScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val settings by c.settings.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Browse", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { nav.openSearch("") },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("Search novels on every site", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader("Sources")
        }
        items(c.sources.all, key = { it.id }) { s ->
            val disabled = s.id in settings.disabledSources
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.openSource(s.id, null, null) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(s)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        if (disabled) {
                            Spacer(Modifier.width(8.dp))
                            Text("hidden", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Text(s.baseUrl.removePrefix("https://"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// One source: listings + genres, endless grid

class PagedListViewModel(private val load: suspend (page: Int) -> com.joe.novelreader.core.model.PageResult) : ViewModel() {
    var items by mutableStateOf<List<NovelItem>>(emptyList())
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    private var page = 0
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val r = load(page + 1)
                page++
                val known = items.map { it.path }.toHashSet()
                val fresh = r.novels.filter { it.path !in known }
                items = items + fresh
                if (!r.hasNextPage || fresh.isEmpty()) endReached = true
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    fun retry() {
        error = null
        loadMore()
    }
}

@Composable
fun NovelGrid(vm: PagedListViewModel, webUrl: String?, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, vm.items.size) {
        if (nearEnd && vm.items.isNotEmpty()) vm.loadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(vm.items, key = { it.sourceId + it.path }) { n ->
            NovelGridCard(n.title, n.cover, n.sourceId, subtitle = n.subtitle) { nav.openNovel(n.sourceId, n.path, n.title, n.cover) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val err = vm.error
            when {
                err != null -> ErrorBox(err, webUrl = webUrl) { vm.retry() }
                vm.loading -> LoadingBox()
                vm.items.isEmpty() && vm.endReached -> EmptyBox("Nothing here.")
                else -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(sourceId: String, initialListing: String?, initialGenre: String?) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val source = c.sources.get(sourceId) ?: return
    var listingId by rememberSaveable { mutableStateOf(if (initialGenre != null) null else (initialListing ?: source.listings.first().id)) }
    var genreId by rememberSaveable { mutableStateOf(initialGenre) }
    var genreMenu by remember { mutableStateOf(false) }
    val listing: Listing? = source.listings.firstOrNull { it.id == listingId }
    val genre: Genre? = source.genres.firstOrNull { it.id == genreId }
    val key = "src:$sourceId:${listing?.id}:${genre?.id}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(source.name)
                        Text(genre?.name ?: listing?.name ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.openSearch("") }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = { nav.openWeb(source.baseUrl) }) { Icon(Icons.Filled.Public, "Open website") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val selectedTab = source.listings.indexOfFirst { it.id == listingId }.let { if (it < 0) source.listings.size else it }
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                source.listings.forEachIndexed { i, l ->
                    Tab(selected = i == selectedTab, onClick = { listingId = l.id; genreId = null }, text = { Text(l.name) })
                }
                if (source.genres.isNotEmpty()) {
                    Tab(selected = selectedTab == source.listings.size, onClick = { genreMenu = true }, text = {
                        Text(genre?.name ?: "Genres ▾")
                    })
                }
            }
            Box {
                DropdownMenu(expanded = genreMenu, onDismissRequest = { genreMenu = false }) {
                    source.genres.forEach { g ->
                        DropdownMenuItem(text = { Text(g.name) }, onClick = {
                            genreId = g.id
                            listingId = null
                            genreMenu = false
                        })
                    }
                }
            }
            if (genre != null) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(source.genres) { g ->
                        Chip(g.name, selected = g.id == genre.id, onClick = { genreId = g.id; listingId = null })
                    }
                }
            }
            val vm: PagedListViewModel = viewModel(key = key) {
                PagedListViewModel { page ->
                    when {
                        genre != null -> source.getGenre(genre, page)
                        else -> source.getListing(listing ?: source.listings.first(), page)
                    }
                }
            }
            NovelGrid(vm, source.baseUrl)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Global search

data class SourceResults(val source: NovelSource, val state: Load<List<NovelItem>>)

class SearchViewModel(private val sources: () -> List<NovelSource>) : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SourceResults>>(emptyList())
        private set
    var searched by mutableStateOf("")
        private set
    private var job: Job? = null

    fun search(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        searched = text
        job?.cancel()
        val list = sources()
        results = list.map { SourceResults(it, Load.Loading) }
        job = viewModelScope.launch {
            list.map { s ->
                async {
                    val r = try {
                        Load.Ok(s.search(text, 1).novels)
                    } catch (e: Exception) {
                        Load.Failed(e)
                    }
                    results = results.map { if (it.source.id == s.id) it.copy(state = r) else it }
                        .sortedBy { sr ->
                            when (val st = sr.state) {
                                is Load.Ok -> if (st.value.isEmpty()) 2 else 0
                                is Load.Loading -> 1
                                is Load.Failed -> 3
                            }
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initial: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val vm: SearchViewModel = viewModel { SearchViewModel { c.sources.enabled } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (initial.isNotBlank() && vm.searched.isEmpty()) {
            vm.query = initial
            vm.search()
        } else if (vm.searched.isEmpty()) {
            runCatching { focus.requestFocus() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        placeholder = { Text("Title or author") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = { IconButton(onClick = { keyboard?.hide(); vm.search() }) { Icon(Icons.Filled.Search, "Search") } },
            )
        },
    ) { pad ->
        if (vm.searched.isEmpty()) {
            EmptyBox("Search every source at once.\nTry a title like \"Shadow Slave\" or an author.", Modifier.padding(pad), Icons.Filled.Search)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.results, key = { it.source.id }) { r ->
                SectionHeader(r.source.name, action = "More", onAction = { nav.openSource(r.source.id, null, null) })
                when (val st = r.state) {
                    is Load.Loading -> LoadingBox(Modifier.height(120.dp))
                    is Load.Failed -> ErrorBox(st.error, webUrl = r.source.baseUrl) { vm.search(vm.searched) }
                    is Load.Ok -> if (st.value.isEmpty()) {
                        Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        NovelRow(st.value) { n -> nav.openNovel(n.sourceId, n.path, n.title, n.cover) }
                    }
                }
            }
        }
    }
}
