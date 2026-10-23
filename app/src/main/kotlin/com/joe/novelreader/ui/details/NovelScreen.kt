package com.joe.novelreader.ui.details

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.joe.novelreader.AppContainer
import com.joe.novelreader.data.ChapterEntity
import com.joe.novelreader.data.NovelEntity
import com.joe.novelreader.ui.components.Chip
import com.joe.novelreader.ui.components.ErrorBox
import com.joe.novelreader.ui.components.LoadingBox
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.NovelCover
import com.joe.novelreader.ui.components.RatingBadge
import com.joe.novelreader.ui.components.combinedClickableCompat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NovelViewModel(private val c: AppContainer, val novelId: String) : ViewModel() {
    val novel: StateFlow<NovelEntity?> = c.repository.observeNovel(novelId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val chapters: StateFlow<List<ChapterEntity>?> =
        c.repository.observeChapters(novelId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    init {
        refresh()
        viewModelScope.launch { c.repository.clearNew(novelId) }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        viewModelScope.launch {
            try {
                c.repository.refresh(novelId)
                c.repository.clearNew(novelId)
            } catch (e: Exception) {
                error = e
            } finally {
                refreshing = false
            }
        }
    }

    fun toggleLibrary() = viewModelScope.launch {
        val n = c.repository.getNovel(novelId) ?: return@launch
        c.repository.setInLibrary(novelId, !n.inLibrary)
    }

    fun download(list: List<ChapterEntity>) = viewModelScope.launch {
        val n = c.repository.getNovel(novelId) ?: return@launch
        if (!n.inLibrary) c.repository.setInLibrary(novelId, true)
        c.downloads.enqueue(n.title, list)
    }

    fun deleteDownloads() = viewModelScope.launch { c.repository.deleteAllDownloads(novelId) }
    fun deleteDownload(ch: ChapterEntity) = viewModelScope.launch { c.repository.deleteDownload(ch) }
    fun setRead(ch: List<ChapterEntity>, read: Boolean) = viewModelScope.launch { c.repository.setRead(ch.map { it.id }, read) }
    fun markReadBefore(ch: ChapterEntity) = viewModelScope.launch { c.repository.markReadBefore(novelId, ch.idx) }

    suspend fun resumeChapter(): ChapterEntity? = c.repository.resumeChapter(novelId)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovelScreen(novelId: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: NovelViewModel = viewModel(key = "novel:$novelId") { NovelViewModel(c, novelId) }
    val novel by vm.novel.collectAsState()
    val chapters by vm.chapters.collectAsState()
    var descending by rememberSaveable { mutableStateOf(false) }
    var downloadMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var descExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val n = novel
    val source = n?.let { c.sources.get(it.sourceId) }
    val list = chapters.orEmpty()
    val readCount = list.count { it.read }
    val lastRead = list.firstOrNull { it.id == n?.lastReadChapterId }

    fun startReading() {
        scope.launch {
            val ch = vm.resumeChapter()
            if (ch == null) Toast.makeText(ctx, "No chapters yet", Toast.LENGTH_SHORT).show()
            else nav.openReader(novelId, ch.id)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "header") {
                Box {
                    // Blurred cover behind the header
                    AsyncImage(
                        model = n?.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(330.dp)
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(330.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                        MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                    Column(Modifier.statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp)) {
                        Row {
                            NovelCover(n?.cover, n?.title.orEmpty(), n?.sourceId, Modifier.width(118.dp), corner = 10.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                n?.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2) }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    n?.rating?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
                                    n?.status?.let { st ->
                                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                                            Text(st, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    buildString {
                                        if (list.isNotEmpty()) append("${list.size} chapters")
                                        source?.let { if (isNotEmpty()) append(" · "); append(it.name) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "info") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (vm.refreshing && list.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    vm.error?.let { e ->
                        ErrorBox(e, webUrl = n?.let { source?.absoluteUrl(it.path) }) { vm.refresh() }
                    }
                    if (!n?.genreList.isNullOrEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            n!!.genreList.forEach { g ->
                                val sg = source?.genres?.firstOrNull { it.name.equals(g, true) }
                                Chip(g, onClick = if (sg != null) ({ nav.openSource(source!!.id, null, sg.id) }) else null)
                            }
                        }
                    }
                    n?.description?.let { d ->
                        Text("Synopsis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                        Text(
                            d,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .animateContentSize()
                                .clickable { descExpanded = !descExpanded },
                        )
                        Text(
                            if (descExpanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { descExpanded = !descExpanded }
                                .padding(vertical = 6.dp),
                        )
                    }
                    if (list.isNotEmpty()) {
                        val downloaded = list.count { it.downloaded }
                        Text(
                            "Read $readCount of ${list.size}" + if (downloaded > 0) " · $downloaded downloaded" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(
                            progress = { if (list.isEmpty()) 0f else readCount.toFloat() / list.size },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }

            item(key = "chapters-header") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (vm.refreshing) Text("Updating…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { descending = !descending }) { Icon(Icons.Filled.SwapVert, "Sort") }
                }
                HorizontalDivider()
            }

            if (chapters == null || (list.isEmpty() && vm.refreshing)) {
                item { LoadingBox() }
            }
            val shown = if (descending) list.asReversed() else list
            items(shown, key = { it.id }) { ch ->
                ChapterRow(
                    ch,
                    isLast = ch.id == lastRead?.id,
                    onClick = { nav.openReader(novelId, ch.id) },
                    onRead = { vm.setRead(listOf(ch), !ch.read) },
                    onReadBefore = { vm.markReadBefore(ch) },
                    onDownload = { if (ch.downloaded) vm.deleteDownload(ch) else vm.download(listOf(ch)) },
                    onDownloadFrom = { vm.download(list.filter { it.idx >= ch.idx }.take(50)) },
                )
            }
        }

        // Transparent top bar over the header
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            Box {
                IconButton(onClick = { downloadMenu = true }) { Icon(Icons.Filled.Download, "Download") }
                DropdownMenu(expanded = downloadMenu, onDismissRequest = { downloadMenu = false }) {
                    val start = lastRead?.idx ?: -1
                    val unread = list.filter { !it.read && !it.downloaded }
                    DropdownMenuItem(text = { Text("Next 10 chapters") }, onClick = { downloadMenu = false; vm.download(list.filter { it.idx > start }.take(10)) })
                    DropdownMenuItem(text = { Text("Next 50 chapters") }, onClick = { downloadMenu = false; vm.download(list.filter { it.idx > start }.take(50)) })
                    DropdownMenuItem(text = { Text("Next 100 chapters") }, onClick = { downloadMenu = false; vm.download(list.filter { it.idx > start }.take(100)) })
                    DropdownMenuItem(text = { Text("All unread (${unread.size})") }, onClick = { downloadMenu = false; vm.download(unread) })
                    DropdownMenuItem(text = { Text("Everything (${list.size})") }, onClick = { downloadMenu = false; vm.download(list) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete downloads") }, onClick = { downloadMenu = false; vm.deleteDownloads() })
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open website") },
                        leadingIcon = { Icon(Icons.Filled.Public, null) },
                        onClick = { moreMenu = false; n?.let { source?.let { s -> nav.openWeb(s.absoluteUrl(it.path)) } } },
                    )
                    DropdownMenuItem(text = { Text("Mark all as read") }, onClick = { moreMenu = false; vm.setRead(list, true) })
                    DropdownMenuItem(text = { Text("Mark all as unread") }, onClick = { moreMenu = false; vm.setRead(list, false) })
                    DropdownMenuItem(text = { Text("Search other sources") }, onClick = { moreMenu = false; n?.let { nav.openSearch(it.title) } })
                }
            }
        }

        // Bottom action bar like WebNovel: library toggle + big read button
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val inLib = n?.inLibrary == true
                OutlinedButton(onClick = { vm.toggleLibrary() }, modifier = Modifier.height(48.dp)) {
                    Icon(if (inLib) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (inLib) "In library" else "Add to library")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { startReading() },
                    enabled = list.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (lastRead != null) "Continue · ${shortName(lastRead.name)}" else "Start reading",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun shortName(name: String): String {
    val m = Regex("(?i)(?:chapter|ch\\.?)\\s*(\\d+)").find(name)
    return if (m != null) "Ch. ${m.groupValues[1]}" else name.take(18)
}

@Composable
private fun ChapterRow(
    ch: ChapterEntity,
    isLast: Boolean,
    onClick: () -> Unit,
    onRead: () -> Unit,
    onReadBefore: () -> Unit,
    onDownload: () -> Unit,
    onDownloadFrom: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickableCompat(onClick = onClick, onLongClick = { menu = true })
                .background(if (isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    color = if (ch.read && !isLast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLast && ch.progress > 0f && ch.progress < 0.98f) {
                    Text("${(ch.progress * 100).toInt()}% read", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (ch.read) Icon(Icons.Filled.CheckCircle, "Read", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(18.dp))
            if (ch.downloaded) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (ch.read) "Mark as unread" else "Mark as read") }, onClick = { menu = false; onRead() })
            DropdownMenuItem(text = { Text("Mark earlier chapters as read") }, onClick = { menu = false; onReadBefore() })
            DropdownMenuItem(text = { Text(if (ch.downloaded) "Delete download" else "Download") }, onClick = { menu = false; onDownload() })
            DropdownMenuItem(text = { Text("Download next 50 from here") }, onClick = { menu = false; onDownloadFrom() })
        }
    }
}
