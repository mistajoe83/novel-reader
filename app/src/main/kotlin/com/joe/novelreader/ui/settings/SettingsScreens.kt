package com.joe.novelreader.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joe.novelreader.BuildConfig
import com.joe.novelreader.core.net.AppDns
import com.joe.novelreader.core.net.DnsConfig
import com.joe.novelreader.core.net.DnsTestResult
import com.joe.novelreader.data.BackupManager
import com.joe.novelreader.data.ThemeMode
import com.joe.novelreader.ui.browse.SourceIcon
import com.joe.novelreader.ui.components.Chip
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.reader.ReaderSettingsSheet
import com.joe.novelreader.ui.reader.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, content: @Composable (PaddingValues) -> Unit) {
    val nav = LocalNav.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        content = content,
    )
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "More" tab. */
@Composable
fun MoreScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s by c.settings.state.collectAsState()
    val downloadedCount by remember { c.db.chapters().observeDownloadedCount() }.collectAsState(initial = 0)
    val queue by remember { c.db.downloads().observeQueue() }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("More", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
        MenuRow(Icons.Filled.Download, "Downloads", if (queue.isNotEmpty()) "${queue.size} in queue · $downloadedCount saved" else "$downloadedCount chapters saved for offline") { nav.open("downloads") }
        MenuRow(Icons.Filled.Dns, "Network & DNS", if (s.dnsEnabled) "Private DNS on · ${s.dnsServers.firstOrNull()?.let { Uri.parse(it).host } ?: "none"}" else "Using phone DNS") { nav.open("settings/dns") }
        MenuRow(Icons.Filled.FormatSize, "Reading", "Theme, font, spacing, listen speed") { nav.open("settings/reader") }
        MenuRow(Icons.Filled.Palette, "Appearance", when (s.themeMode) { ThemeMode.SYSTEM -> "Follow phone"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }) { nav.open("settings/appearance") }
        MenuRow(Icons.Filled.HealthAndSafety, "Source health & fixes", "Test sources, get fixes, pass site checks") { nav.open("settings/health") }
        MenuRow(Icons.Filled.Extension, "Sources", "${c.sources.all.size - s.disabledSources.size} of ${c.sources.all.size} on · change site addresses") { nav.open("settings/sources") }
        MenuRow(Icons.Filled.Backup, "Backup & restore", "Save your library to a file") { nav.open("settings/backup") }
        MenuRow(Icons.Filled.Info, "About", "NovelNest ${BuildConfig.VERSION_NAME}") { nav.open("settings/about") }
    }
}

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsSettingsScreen() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    var newServer by remember { mutableStateOf("") }
    val results = remember { mutableStateMapOf<String, DnsTestResult?>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }

    fun setServers(list: List<String>) = scope.launch { c.settings.update { it.copy(dnsServers = list) } }
    fun test(server: String) {
        testing[server] = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { c.network.dns.test(server, "novelbin.com") }
            results[server] = r
            testing[server] = false
        }
    }

    SubScreen("Network & DNS") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Card(Modifier.padding(16.dp)) {
                    Text(
                        "These DNS servers are used only by this app, through encrypted DNS (DNS-over-HTTPS). " +
                            "Your phone's own settings are not changed. If the first server fails, the next one is tried.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SwitchRow("Use private DNS in this app", s.dnsEnabled, "Off = use the phone's normal DNS") { v -> scope.launch { c.settings.update { it.copy(dnsEnabled = v) } } }
                    SwitchRow("Fall back to phone DNS", s.dnsFallbackToSystem, "If every server above fails, still try the phone's DNS") { v -> scope.launch { c.settings.update { it.copy(dnsFallbackToSystem = v) } } }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Servers, in order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { s.dnsServers.forEach { test(it) } }) {
                        Icon(Icons.Filled.Speed, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test all")
                    }
                }
            }
            itemsIndexed(s.dnsServers, key = { _, it -> it }) { i, server ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Uri.parse(server).host ?: server, style = MaterialTheme.typography.bodyLarge)
                        Text(server, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val r = results[server]
                        when {
                            testing[server] == true -> LinearProgressIndicator(Modifier.width(120.dp).padding(top = 4.dp))
                            r != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (r.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (r.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (r.ok) "Works · ${r.millis} ms" else "Failed: ${r.message}", style = MaterialTheme.typography.labelSmall, color = if (r.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, maxLines = 2)
                            }
                        }
                    }
                    IconButton(onClick = { test(server) }) { Icon(Icons.Filled.Speed, "Test") }
                    IconButton(enabled = i > 0, onClick = { setServers(s.dnsServers.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                    IconButton(enabled = i < s.dnsServers.size - 1, onClick = { setServers(s.dnsServers.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    IconButton(onClick = { setServers(s.dnsServers - server) }) { Icon(Icons.Filled.Delete, "Remove") }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = newServer,
                        onValueChange = { newServer = it },
                        label = { Text("Add server") },
                        placeholder = { Text("dns.adguard.com or https://…/dns-query") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = {
                        val url = AppDns.normalizeServer(newServer)
                        if (url == null) {
                            Toast.makeText(ctx, "That doesn't look like a DNS address", Toast.LENGTH_SHORT).show()
                        } else if (url !in s.dnsServers) {
                            setServers(s.dnsServers + url)
                            newServer = ""
                            test(url)
                        }
                    }) { Text("Add") }
                    Spacer(Modifier.size(16.dp))
                    Text("Quick add", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        AppDns.PRESETS.forEach { p ->
                            val added = p.url in s.dnsServers
                            Chip(p.name, selected = added, onClick = {
                                if (!added) {
                                    setServers(s.dnsServers + p.url)
                                    test(p.url)
                                }
                            })
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    OutlinedButton(onClick = { setServers(DnsConfig.DEFAULT_SERVERS) }) { Text("Reset to AdGuard defaults") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun ReaderSettingsScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    SubScreen("Reading") { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            ReaderSettingsSheet(s) { change -> scope.launch { c.settings.update(change) } }
        }
    }
}

@Composable
fun AppearanceScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    SubScreen("Appearance") { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("App theme", style = MaterialTheme.typography.titleMedium)
            listOf(ThemeMode.SYSTEM to "Follow phone setting", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { c.settings.update { it.copy(themeMode = m) } } }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.themeMode == m, onClick = { scope.launch { c.settings.update { it.copy(themeMode = m) } } })
                    Text(label)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Use wallpaper colours", s.dynamicColor, "Material You colours from your phone") { v -> scope.launch { c.settings.update { it.copy(dynamicColor = v) } } }
            }
            SwitchRow("Check for new chapters automatically", s.autoUpdate, "About every 12 hours, for books in your library") { v -> scope.launch { c.settings.update { it.copy(autoUpdate = v) } } }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun SourcesSettingsScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    SubScreen("Sources") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Turn sites on or off. If a site moves to a new address, tap it to change the address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(c.sources.all, key = { it.id }) { src ->
                val on = src.id !in s.disabledSources
                Row(
                    Modifier.fillMaxWidth().clickable { editing = src.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceIcon(src, 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(src.name, style = MaterialTheme.typography.titleMedium)
                        Text(src.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = { v ->
                        scope.launch {
                            c.settings.update { it.copy(disabledSources = if (v) it.disabledSources - src.id else it.disabledSources + src.id) }
                        }
                    })
                }
            }
        }
    }
    editing?.let { id ->
        val src = c.sources.get(id) ?: return@let
        var text by remember(id) { mutableStateOf(src.baseUrl) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${src.name} address") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Default: ${src.defaultBaseUrl}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                    scope.launch {
                        c.settings.update { st -> st.copy(baseUrls = if (v == src.defaultBaseUrl) st.baseUrls - id else st.baseUrls + (id to v)) }
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { c.settings.update { st -> st.copy(baseUrls = st.baseUrls - id) } }
                    editing = null
                }) { Text("Reset") }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val queue by remember { c.db.downloads().observeQueue() }.collectAsState(initial = emptyList())
    val count by remember { c.db.chapters().observeDownloadedCount() }.collectAsState(initial = 0)
    val ctx = LocalContext.current
    var sizes by remember { mutableStateOf("…") }
    LaunchedEffect(count) {
        sizes = withContext(Dispatchers.IO) {
            "Saved chapters: " + Formatter.formatShortFileSize(ctx, c.store.downloadedBytes()) +
                " · Cache: " + Formatter.formatShortFileSize(ctx, c.store.cacheBytes())
        }
    }
    SubScreen("Downloads") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("$count chapters saved for offline reading", style = MaterialTheme.typography.titleMedium)
                    Text(sizes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { c.store.clearCache() }
                                sizes = "Cache cleared"
                            }
                        }) { Text("Clear cache") }
                        if (queue.any { it.error != null }) OutlinedButton(onClick = { scope.launch { c.downloads.retryFailed() } }) { Text("Retry failed") }
                        if (queue.isNotEmpty()) OutlinedButton(onClick = { scope.launch { c.downloads.clearQueue() } }) { Text("Cancel all") }
                    }
                    Text(
                        "To download, open a book and tap the download button at the top. Downloads keep going in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                HorizontalDivider()
                if (queue.isNotEmpty()) {
                    Text("Queue (${queue.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                }
            }
            items(queue, key = { it.chapterId }) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.novelTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(d.chapterName, style = MaterialTheme.typography.bodyMedium)
                        d.error?.let { Text("Failed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    }
                    if (d.error == null && d == queue.firstOrNull { it.error == null }) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun BackupScreen() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { BackupManager(c.db) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = backup.export()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "Backup saved" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } ?: error("Empty file")
                backup.import(text)
            }
            Toast.makeText(ctx, result.fold({ "Restored $it books" }, { "Couldn't read that file" }), Toast.LENGTH_SHORT).show()
        }
    }
    SubScreen("Backup & restore") { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("Save your library, reading progress and bookmarks to a file, for example before changing phones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(16.dp))
            Button(onClick = { exportLauncher.launch("novelnest-backup-${System.currentTimeMillis() / 1000}.json") }, modifier = Modifier.fillMaxWidth()) { Text("Create backup") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
        }
    }
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    SubScreen("About") { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("NovelNest", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            Text(
                "A personal web novel reader. It reads free chapters straight from the sites listed under Sources, " +
                    "with no accounts, coins or ads. Downloads are kept on this phone only.",
            )
            Spacer(Modifier.size(16.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mistajoe83/novel-reader/releases/latest")))
            }) { Text("Check for a newer version") }
        }
    }
}
