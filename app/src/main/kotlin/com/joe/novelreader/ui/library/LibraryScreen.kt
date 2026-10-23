package com.joe.novelreader.ui.library

import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joe.novelreader.data.LibraryNovel
import com.joe.novelreader.data.LibrarySort
import com.joe.novelreader.ui.components.EmptyBox
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.NovelCover
import com.joe.novelreader.ui.components.NovelGridCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val tabs = listOf("Library", "History", "Bookmarks")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(t, fontWeight = if (pager.currentPage == i) FontWeight.Bold else FontWeight.Normal) },
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> LibraryPage()
                1 -> HistoryPage()
                else -> BookmarksPage()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryPage() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    val library by remember { c.repository.observeLibrary() }.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<LibraryNovel?>(null) }

    val sorted = remember(library, settings.librarySort) {
        val l = library.orEmpty()
        when (settings.librarySort) {
            LibrarySort.LAST_READ -> l.sortedByDescending { maxOf(it.novel.lastReadAt, it.novel.addedAt) }
            LibrarySort.TITLE -> l.sortedBy { it.novel.title.lowercase() }
            LibrarySort.ADDED -> l.sortedByDescending { it.novel.addedAt }
            LibrarySort.UNREAD -> l.sortedByDescending { it.unread }
        }
    }

    fun refresh() {
        refreshing = true
        scope.launch {
            val found = c.checkLibraryUpdates()
            refreshing = false
            val total = found.sumOf { it.second }
            Toast.makeText(ctx, if (total == 0) "No new chapters" else "$total new chapters", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} books", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.openSearch("") }) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Check for new chapters") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            leadingIcon = { RadioButton(selected = s == settings.librarySort, onClick = null) },
                            onClick = {
                                sortMenu = false
                                scope.launch { c.settings.update { it.copy(librarySort = s) } }
                            },
                        )
                    }
                }
            }
        }

        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize()) {
            if (library != null && sorted.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyBox("Your library is empty.\nFind something to read in Discover, then tap \"Add to library\".", icon = Icons.AutoMirrored.Filled.LibraryBooks)
                    Button(onClick = { nav.open("discover") }) { Text("Go to Discover") }
                }
                return@PullToRefreshBox
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val recent = sorted.filter { it.novel.lastReadAt > 0 }.maxByOrNull { it.novel.lastReadAt }
                if (recent != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueCard(recent) { nav.openReader(recent.novel.id, recent.novel.lastReadChapterId) }
                    }
                }
                items(sorted, key = { it.novel.id }) { ln ->
                    val n = ln.novel
                    val badge = when {
                        n.newChapters > 0 -> "+${n.newChapters}"
                        ln.unread > 0 && ln.total > 0 && ln.unread < ln.total -> "${ln.unread}"
                        else -> null
                    }
                    NovelGridCard(
                        title = n.title,
                        cover = n.cover,
                        sourceId = n.sourceId,
                        subtitle = n.lastReadChapterName?.let { "Read: $it" } ?: if (ln.total > 0) "${ln.total} chapters" else null,
                        badge = badge,
                        progress = if (ln.total > 0) (ln.total - ln.unread).toFloat() / ln.total else null,
                        onLongClick = { removeTarget = ln },
                    ) { nav.openNovel(n.sourceId, n.path, n.title, n.cover) }
                }
            }
        }
    }

    removeTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(t.novel.title) },
            text = { Text("Remove this book from your library? Downloaded chapters will be deleted too.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        c.repository.setInLibrary(t.novel.id, false)
                        c.repository.deleteAllDownloads(t.novel.id)
                    }
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = {
                    removeTarget = null
                    nav.openReader(t.novel.id, t.novel.lastReadChapterId)
                }) { Text("Continue reading") }
            },
        )
    }
}

@Composable
private fun ContinueCard(ln: LibraryNovel, onClick: () -> Unit) {
    val n = ln.novel
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NovelCover(n.cover, n.title, n.sourceId, Modifier.width(56.dp), corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(n.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.lastReadChapterName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun HistoryPage() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val history by remember { c.repository.observeHistory() }.collectAsState(initial = null)
    var confirmClear by remember { mutableStateOf(false) }
    val list = history.orEmpty()
    if (history != null && list.isEmpty()) {
        EmptyBox("Books you read show up here.", icon = Icons.Filled.History)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently read", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }
        items(list, key = { it.id }) { n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.openReader(n.id, n.lastReadChapterId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovelCover(n.cover, n.title, n.sourceId, Modifier.width(54.dp), corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clickable { nav.openNovel(n.sourceId, n.path, n.title, n.cover) }) {
                    Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(n.lastReadChapterName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        DateUtils.getRelativeTimeSpanString(n.lastReadAt).toString() + (c.sources.get(n.sourceId)?.let { " · ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { nav.openReader(n.id, n.lastReadChapterId) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Read") }
                IconButton(onClick = { scope.launch { c.repository.clearHistory(n.id) } }) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear reading history?") },
            text = { Text("Your library and reading progress stay. Only this list is cleared.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { c.repository.clearAllHistory() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookmarksPage() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val marks by remember { c.repository.observeBookmarks() }.collectAsState(initial = null)
    val list = marks.orEmpty()
    if (marks != null && list.isEmpty()) {
        EmptyBox("No bookmarks yet.\nWhile reading, tap the page and press the bookmark icon to save your place.", icon = Icons.Filled.Bookmarks)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(list, key = { it.id }) { b ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.open("reader/${android.net.Uri.encode(b.novelId)}?chapter=${android.net.Uri.encode(b.chapterId)}&para=${b.paragraph}") }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                NovelCover(b.cover, b.novelTitle, b.novelId.substringBefore(':'), Modifier.width(46.dp), corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.novelTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b.chapterName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.snippet.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text("“${b.snippet}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(DateUtils.getRelativeTimeSpanString(b.createdAt).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch { c.repository.deleteBookmark(b.id) } }) { Icon(Icons.Filled.Close, "Delete bookmark") }
            }
        }
    }
}
