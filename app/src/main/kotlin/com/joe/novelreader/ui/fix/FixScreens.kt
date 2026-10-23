package com.joe.novelreader.ui.fix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.source.CloudflareException
import com.joe.novelreader.core.source.NovelSource
import com.joe.novelreader.data.NovelEntity
import com.joe.novelreader.ui.browse.SourceIcon
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.NovelCover
import com.joe.novelreader.ui.components.friendlyError
import com.joe.novelreader.ui.settings.SubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Source health: tests each site step by step and offers the fixes.

data class StepResult(val name: String, val ok: Boolean, val millis: Long, val detail: String)

data class Health(val running: Boolean, val steps: List<StepResult>, val cloudflareUrl: String? = null) {
    val ok: Boolean get() = !running && steps.isNotEmpty() && steps.all { it.ok }
}

/** Runs the same steps a reader goes through: list, search, book page, chapter list, chapter text. */
suspend fun checkSource(s: NovelSource, onStep: (List<StepResult>, String?) -> Unit) = withContext(Dispatchers.IO) {
    val steps = mutableListOf<StepResult>()
    var cf: String? = null
    suspend fun <T> step(name: String, block: suspend () -> Pair<T, String>): T? {
        val start = System.currentTimeMillis()
        return try {
            val (v, detail) = block()
            steps += StepResult(name, true, System.currentTimeMillis() - start, detail)
            v
        } catch (e: Throwable) {
            if (e is CloudflareException) cf = e.url
            steps += StepResult(name, false, System.currentTimeMillis() - start, friendlyError(e))
            null
        }.also { onStep(steps.toList(), cf) }
    }
    val list = step("Book lists") {
        val r = s.getListing(s.listings.first(), 1)
        if (r.novels.isEmpty()) error("The list came back empty.")
        r.novels to "${r.novels.size} books"
    }
    step("Search") {
        val r = s.search("the", 1)
        if (r.novels.isEmpty()) error("No results for a common word.")
        Unit to "${r.novels.size} results"
    }
    val novel = list?.firstOrNull() ?: return@withContext
    step("Book page") {
        val d = s.getDetails(novel.path)
        if (d.title.isBlank()) error("Couldn't read the title.")
        Unit to d.title
    }
    val chapters = step("Chapter list") {
        val c = s.getChapters(novel.path)
        if (c.isEmpty()) error("No chapters found.")
        c to "${c.size} chapters"
    } ?: return@withContext
    step("Chapter text") {
        val t = s.getChapterText(chapters.first().path)
        Unit to "${t.paragraphs.size} paragraphs"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceHealthScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val rules by c.rules.state.collectAsState()
    val settings by c.settings.state.collectAsState()
    val results = remember { mutableStateMapOf<String, Health>() }
    var editing by remember { mutableStateOf<String?>(null) }
    var addressFor by remember { mutableStateOf<String?>(null) }

    fun test(s: NovelSource) {
        results[s.id] = Health(true, emptyList())
        scope.launch {
            checkSource(s) { steps, cf -> results[s.id] = Health(true, steps, cf) }
            results[s.id] = results[s.id]!!.copy(running = false)
        }
    }
    fun testAll() = c.sources.all.filter { it.id !in settings.disabledSources }.forEach { test(it) }

    SubScreen("Source health") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Card(Modifier.padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Source fixes", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sites sometimes change. Fixes are published on GitHub and the app downloads them on its own, " +
                                "so most broken sources are fixed without installing a new version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val last = if (rules.lastChecked > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(rules.lastChecked)) else "never"
                        Text("Last checked: $last", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        rules.lastMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { c.rules.refresh() } }, enabled = !rules.checking) {
                                if (rules.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Check for fixes")
                            }
                            OutlinedButton(onClick = { testAll() }) { Text("Test all sources") }
                        }
                    }
                }
            }
            items(c.sources.all, key = { it.id }) { s ->
                val h = results[s.id]
                val (_, ruleFrom) = remember(rules) { c.rules.activeFor(s.id) }
                val off = s.id in settings.disabledSources
                Card(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceIcon(s, 36)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium)
                                Text(s.baseUrl.removePrefix("https://"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                off -> StatusLabel("Off", MaterialTheme.colorScheme.onSurfaceVariant)
                                h == null -> StatusLabel("Not tested", MaterialTheme.colorScheme.onSurfaceVariant)
                                h.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                h.ok -> StatusLabel("Working", Color(0xFF2E7D32))
                                else -> StatusLabel("Problem", MaterialTheme.colorScheme.error)
                            }
                        }
                        s.rules.notice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (ruleFrom != null) {
                            Text("Using $ruleFrom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                        h?.steps?.forEach { st ->
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                                Icon(
                                    if (st.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    null,
                                    tint = if (st.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("${st.name} · ${st.millis} ms", style = MaterialTheme.typography.labelLarge)
                                    Text(st.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { test(s) }, enabled = h?.running != true && !off) { Text("Test") }
                            TextButton(onClick = { nav.openWeb(h?.cloudflareUrl ?: s.baseUrl) }) {
                                Text(if (h?.cloudflareUrl != null) "Pass site check" else "Open site")
                            }
                            TextButton(onClick = { addressFor = s.id }) { Text("Change address") }
                            TextButton(onClick = { editing = s.id }) { Text("Edit fix") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Still broken? Open the book and use Switch source to keep reading it on another site. " +
                        "Your progress and bookmarks move with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    addressFor?.let { id ->
        val src = c.sources.get(id) ?: return@let
        var text by remember(id) { mutableStateOf(src.baseUrl) }
        AlertDialog(
            onDismissRequest = { addressFor = null },
            title = { Text("${src.name} address") },
            text = {
                Column {
                    Text("If the site moved to a new domain, type the new address.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Default: ${src.defaultBaseUrl}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                    scope.launch { c.settings.update { st -> st.copy(baseUrls = st.baseUrls + (id to v)) } }
                    addressFor = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { c.settings.update { st -> st.copy(baseUrls = st.baseUrls - id) } }
                    addressFor = null
                }) { Text("Reset") }
            },
        )
    }

    editing?.let { id ->
        val src = c.sources.get(id) ?: return@let
        var text by remember(id) { mutableStateOf(rules.local[id] ?: c.rules.template(id)) }
        var err by remember(id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Fix for ${src.name}") },
            text = {
                Column {
                    Text(
                        "Advanced. A fix written here only applies on this phone and wins over the one from GitHub. " +
                            "See sources/README.md in the GitHub repo for what each field does.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; err = null },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp).padding(top = 8.dp),
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    err = c.rules.setLocal(id, text)
                    if (err == null) {
                        editing = null
                        test(src)
                    }
                }) { Text("Save & test") }
            },
            dismissButton = {
                TextButton(onClick = {
                    c.rules.setLocal(id, "")
                    editing = null
                }) { Text("Remove my fix") }
            },
        )
    }
}

@Composable
private fun StatusLabel(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

// ---------------------------------------------------------------------------------------------
// Switch source: find the same book on another site and move it there.

private data class Found(val source: NovelSource, val items: List<NovelItem>?, val error: Throwable?)

@Composable
fun MigrateScreen(novelId: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var novel by remember { mutableStateOf<NovelEntity?>(null) }
    var query by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<Found>() }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<NovelItem?>(null) }
    var moving by remember { mutableStateOf(false) }
    var moveError by remember { mutableStateOf<String?>(null) }

    fun search() {
        val n = novel ?: return
        val q = query.trim().ifBlank { return }
        found.clear()
        searching = true
        scope.launch {
            val targets = c.sources.enabled.filter { it.id != n.sourceId }
            coroutineScope {
                targets.map { s ->
                    async(Dispatchers.IO) {
                        val f = try { Found(s, s.search(q, 1).novels.take(8), null) } catch (e: Throwable) { Found(s, null, e) }
                        withContext(Dispatchers.Main) { found += f }
                    }
                }.awaitAll()
            }
            searching = false
        }
    }

    LaunchedEffect(novelId) {
        novel = c.repository.getNovel(novelId)
        query = novel?.title.orEmpty()
        search()
    }

    SubScreen("Switch source") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                val n = novel
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Pick the same book on another site. Your library place, read chapters, last position and bookmarks move over.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (n != null) {
                        Text(
                            "Now on ${c.sources.get(n.sourceId)?.name ?: n.sourceId}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Book title") },
                        trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            }
            items(found, key = { it.source.id }) { f ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceIcon(f.source, 24)
                        Spacer(Modifier.width(8.dp))
                        Text(f.source.name, style = MaterialTheme.typography.titleSmall)
                    }
                    when {
                        f.error != null -> Text(friendlyError(f.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        f.items.isNullOrEmpty() -> Text("Not found here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        else -> f.items.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { picked = item }.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NovelCover(item.cover, item.title, item.sourceId, Modifier.width(44.dp).heightIn(min = 62.dp, max = 62.dp), corner = 4.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                }
                                Icon(Icons.Filled.SwapHoriz, "Switch", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (searching) item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            if (!searching && found.isEmpty() && novel != null) item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search for the book title to see it on other sites.")
                }
            }
        }
    }

    picked?.let { item ->
        val target = c.sources.get(item.sourceId)
        AlertDialog(
            onDismissRequest = { if (!moving) picked = null },
            title = { Text("Switch to ${target?.name}?") },
            text = {
                Column {
                    Text("\"${item.title}\" on ${target?.name} will replace the current one in your library, with your progress and bookmarks.")
                    if (moving) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Moving…")
                    }
                    moveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !moving, onClick = {
                    moving = true
                    moveError = null
                    scope.launch {
                        try {
                            c.repository.migrate(novelId, item.sourceId, item.path, item.title, item.cover)
                            picked = null
                            nav.back()
                            nav.back()
                            nav.openNovel(item.sourceId, item.path, item.title, item.cover)
                        } catch (e: Throwable) {
                            moveError = friendlyError(e)
                        } finally {
                            moving = false
                        }
                    }
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(enabled = !moving, onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}
