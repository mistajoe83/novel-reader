package com.joe.novelreader.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joe.novelreader.data.AppSettings
import com.joe.novelreader.data.ReaderFont
import com.joe.novelreader.data.ReaderTheme
import com.joe.novelreader.ui.components.ErrorBox
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.discover.Load
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Lets the activity pass volume-key presses to the reader. */
object ReaderKeys {
    var handler: ((up: Boolean) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN) return h(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        return true
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SYSTEM -> FontFamily.Default
    ReaderFont.MONO -> FontFamily.Monospace
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(novelId: String, chapterId: String?, paragraph: Int?) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    val vm: ReaderViewModel = viewModel(key = "reader:$novelId") {
        ReaderViewModel(c, ctx.applicationContext, novelId, chapterId, paragraph)
    }
    val listState = rememberLazyListState()
    var controls by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val theme = settings.readerTheme
    val bg = Color(theme.background)
    val fg = Color(theme.text)
    val current = vm.currentChapter
    val bookmarks by remember(current?.id) { current?.let { vm.bookmarksForChapter(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList<com.joe.novelreader.data.BookmarkEntity>()) }
        .collectAsState(initial = emptyList())

    // Full-screen while reading, bars come back with the controls.
    DisposableEffect(controls) {
        val window = ctx.findActivity()?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.isAppearanceLightStatusBars = theme.background > 0xFFAAAAAA
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            ctx.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            ReaderKeys.handler = null
            vm.stopTts()
            vm.saveNow()
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(settings.volumeKeysScroll) {
        val handler: (Boolean) -> Boolean = { up ->
            scope.launch {
                val h = listState.layoutInfo.viewportSize.height * 0.85f
                listState.animateScrollBy(if (up) -h else h)
            }
            true
        }
        ReaderKeys.handler = if (settings.volumeKeysScroll) handler else null
        onDispose { ReaderKeys.handler = null }
    }

    // Jump requests from the view model (restore position, slider, text-to-speech follow).
    LaunchedEffect(vm.pendingScroll) {
        val target = vm.pendingScroll ?: return@LaunchedEffect
        if (target in vm.items.indices) {
            if (vm.ttsActive) {
                val visible = listState.layoutInfo.visibleItemsInfo
                val inView = visible.any { it.index == target } && visible.lastOrNull()?.index != target
                if (!inView) listState.animateScrollToItem(target)
            } else {
                listState.scrollToItem(target)
            }
        }
        vm.pendingScroll = null
    }
    LaunchedEffect(listState, settings.continuousScroll) {
        snapshotFlow { listState.firstVisibleItemIndex to (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .distinctUntilChanged()
            .collect { (first, last) -> vm.onScrolled(first, last, settings.continuousScroll) }
    }
    LaunchedEffect(vm.ttsError) {
        vm.ttsError?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.ttsError = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        val textStyle = TextStyle(
            color = fg,
            fontSize = settings.readerFontSize.sp,
            lineHeight = (settings.readerFontSize * settings.readerLineHeight).sp,
            fontFamily = settings.readerFont.family(),
            textAlign = if (settings.readerJustify) TextAlign.Justify else TextAlign.Start,
        )
        // On tablets keep lines at a readable length: the text column is centred and capped.
        val sidePadding = maxOf(settings.readerMargin.dp, (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 680.dp) / 2)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
            contentPadding = PaddingValues(
                start = sidePadding,
                end = sidePadding,
                top = 48.dp,
                bottom = 120.dp,
            ),
        ) {
            items(vm.items, key = { it.key }, contentType = { it::class.simpleName }) { item ->
                when (item) {
                    is ReaderItem.Header -> Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 20.dp)) {
                        Text(
                            item.title,
                            color = fg,
                            fontSize = (settings.readerFontSize * 1.3f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = settings.readerFont.family(),
                            lineHeight = (settings.readerFontSize * 1.7f).sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.25f)))
                    }
                    is ReaderItem.Para -> {
                        val speaking = vm.ttsSpeaking == item.key
                        val marked = bookmarks.any { it.paragraph == item.index }
                        Text(
                            item.text,
                            style = textStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = settings.readerParagraphSpacing.dp)
                                .then(
                                    if (speaking) Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                    else if (marked) Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f))
                                    else Modifier,
                                ),
                        )
                    }
                    is ReaderItem.Status -> when (val st = item.state) {
                        is Load.Loading -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                        }
                        is Load.Failed -> {
                            val src = vm.novel?.let { c.sources.get(it.sourceId) }
                            ErrorBox(st.error, webUrl = src?.absoluteUrl(item.chapter.path)) { vm.retry(item.chapter) }
                        }
                        is Load.Ok -> {}
                    }
                    is ReaderItem.Footer -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("End of ${item.chapter.name}", color = fg.copy(alpha = 0.55f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        if (!settings.continuousScroll || !item.hasNext) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { vm.goPrevious() }) { Text("Previous", color = fg) }
                                if (item.hasNext) {
                                    Button(onClick = { vm.goNext() }) { Text("Next chapter") }
                                } else {
                                    Button(onClick = { scope.launch { vm.refreshChapters() } }) { Text("Check for new chapters") }
                                }
                            }
                        } else {
                            Text("Keep scrolling for the next chapter", color = fg.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Thin progress line at the very top while reading
        if (!controls) {
            Text(
                "${current?.name.orEmpty()}  ·  ${(vm.progressInChapter * 100).toInt()}%",
                color = fg.copy(alpha = 0.45f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(bg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = controls,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(vm.novel?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val marked = bookmarks.isNotEmpty()
                    IconButton(onClick = {
                        if (marked) bookmarks.forEach { vm.deleteBookmark(it.id) } else vm.addBookmark()
                        Toast.makeText(ctx, if (marked) "Bookmark removed" else "Bookmarked", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark", tint = if (marked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Reload chapter") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; current?.let { vm.retry(it) } })
                            DropdownMenuItem(text = { Text("Download next 10") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; vm.download(10) })
                            DropdownMenuItem(text = { Text("Download next 50") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; vm.download(50) })
                            DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(Icons.Filled.Public, null) }, onClick = {
                                menu = false
                                val src = vm.novel?.let { c.sources.get(it.sourceId) }
                                if (src != null && current != null) nav.openWeb(src.absoluteUrl(current.path))
                            })
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controls,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.goPrevious() }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous chapter") }
                        var drag by remember(current?.id) { mutableStateOf<Float?>(null) }
                        Slider(
                            value = drag ?: vm.progressInChapter,
                            onValueChange = { drag = it },
                            onValueChangeFinished = { drag?.let { vm.jumpWithinChapter(it) }; drag = null },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.goNext() }, enabled = vm.hasNextChapter) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next chapter") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Chapters") { showChapters = true }
                        BarButton(if (vm.ttsActive) Icons.Filled.Stop else Icons.Filled.Headphones, if (vm.ttsActive) "Stop" else "Listen") { vm.toggleTts() }
                        BarButton(Icons.Filled.FormatSize, "Display") { showSettings = true }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ReaderSettingsSheet(settings) { change -> scope.launch { c.settings.update(change) } }
        }
    }
    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            val chState = rememberLazyListState(initialFirstVisibleItemIndex = ((current?.idx ?: 0) - 3).coerceAtLeast(0))
            Text("Chapters (${vm.chapters.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(state = chState, modifier = Modifier.fillMaxWidth().height(520.dp)) {
                itemsIndexed(vm.chapters, key = { _, ch -> ch.id }) { _, ch ->
                    val isCurrent = ch.id == current?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showChapters = false; vm.openChapter(ch, 0) }
                            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ch.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (ch.read && !isCurrent) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ch.downloaded) Icon(Icons.Filled.Download, "Downloaded", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ReaderSettingsSheet(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(ReaderTheme.entries) { t ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(t.background))
                            .border(if (t == s.readerTheme) 3.dp else 1.dp, if (t == s.readerTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { update { it.copy(readerTheme = t) } },
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = Color(t.text), fontWeight = FontWeight.Bold) }
                    Text(t.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { update { it.copy(readerFontSize = (it.readerFontSize - 1f).coerceAtLeast(12f)) } }) { Text("A−") }
            Text("${s.readerFontSize.toInt()}", modifier = Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { update { it.copy(readerFontSize = (it.readerFontSize + 1f).coerceAtMost(34f)) } }) { Text("A+") }
        }
        Text("Font", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { f ->
                val sel = f == s.readerFont
                Text(
                    f.label,
                    fontFamily = f.family(),
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { update { it.copy(readerFont = f) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        LabeledSlider("Line spacing", "%.1f".format(s.readerLineHeight), s.readerLineHeight, 1.2f..2.4f) { v -> update { it.copy(readerLineHeight = v) } }
        LabeledSlider("Paragraph spacing", "${s.readerParagraphSpacing}", s.readerParagraphSpacing.toFloat(), 0f..40f) { v -> update { it.copy(readerParagraphSpacing = v.toInt()) } }
        LabeledSlider("Side margins", "${s.readerMargin}", s.readerMargin.toFloat(), 4f..56f) { v -> update { it.copy(readerMargin = v.toInt()) } }
        LabeledSlider("Listen speed", "%.1fx".format(s.ttsRate), s.ttsRate, 0.5f..2.5f) { v -> update { it.copy(ttsRate = v) } }
        SwitchRow("Justify text", s.readerJustify) { v -> update { it.copy(readerJustify = v) } }
        SwitchRow("Endless scrolling into next chapter", s.continuousScroll) { v -> update { it.copy(continuousScroll = v) } }
        SwitchRow("Volume keys turn pages", s.volumeKeysScroll) { v -> update { it.copy(volumeKeysScroll = v) } }
        SwitchRow("Keep screen on", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
    }
}

@Composable
fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var v by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v) }, valueRange = range)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

