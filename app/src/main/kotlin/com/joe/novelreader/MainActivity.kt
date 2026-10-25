package com.joe.novelreader

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.joe.novelreader.ui.browse.BrowseScreen
import com.joe.novelreader.ui.browse.SearchScreen
import com.joe.novelreader.ui.browse.SourceScreen
import com.joe.novelreader.ui.components.ContentWidth
import com.joe.novelreader.ui.components.LoadingBox
import com.joe.novelreader.ui.components.isWideScreen
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.Nav
import com.joe.novelreader.ui.details.NovelScreen
import com.joe.novelreader.ui.discover.DiscoverScreen
import com.joe.novelreader.ui.library.LibraryScreen
import com.joe.novelreader.ui.reader.ReaderKeys
import com.joe.novelreader.ui.reader.ReaderScreen
import com.joe.novelreader.ui.settings.AboutScreen
import com.joe.novelreader.ui.settings.AppearanceScreen
import com.joe.novelreader.ui.settings.BackupScreen
import com.joe.novelreader.ui.settings.DnsSettingsScreen
import com.joe.novelreader.ui.settings.DownloadsScreen
import com.joe.novelreader.ui.settings.MoreScreen
import com.joe.novelreader.ui.settings.ReaderSettingsScreen
import com.joe.novelreader.ui.settings.SourcesSettingsScreen
import com.joe.novelreader.ui.theme.NovelTheme
import com.joe.novelreader.ui.web.WebScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as NovelApp).container
        setContent {
            val settings by container.settings.state.collectAsState()
            NovelTheme(settings.themeMode, settings.dynamicColor) {
                CompositionLocalProvider(LocalContainer provides container) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ReaderKeys.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("discover", "Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
    Tab("browse", "Browse", Icons.Outlined.TravelExplore, Icons.Filled.TravelExplore),
    Tab("more", "More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

private fun enc(s: String?) = Uri.encode(s.orEmpty())

@Composable
private fun AppRoot() {
    val container = LocalContainer.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // First time with an empty library: start on Discover instead.
        if (container.db.novels().libraryNovels().isEmpty()) {
            navController.navigate("discover") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    val nav = remember(navController) { makeNav(navController) }

    val wide = isWideScreen()
    val goTab: (Tab) -> Unit = { t ->
        navController.navigate(t.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalNav provides nav) {
      Row(Modifier.fillMaxSize()) {
        // Tablets and landscape: navigation rail on the side instead of the bottom bar.
        if (showBar && wide) {
            NavigationRail {
                Spacer(Modifier.weight(1f))
                tabs.forEach { t ->
                    val selected = route == t.route
                    NavigationRailItem(
                        selected = selected,
                        onClick = { goTab(t) },
                        icon = { Icon(if (selected) t.selectedIcon else t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBar && !wide) {
                    NavigationBar {
                        tabs.forEach { t ->
                            val selected = route == t.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { goTab(t) },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            NavHost(navController, startDestination = "library", modifier = Modifier.padding(pad)) {
                composable("library") { Box(Modifier.statusBarsPadding()) { LibraryScreen() } }
                composable("discover") { Box(Modifier.statusBarsPadding()) { DiscoverScreen() } }
                composable("browse") { Box(Modifier.statusBarsPadding()) { BrowseScreen() } }
                composable("more") { Box(Modifier.statusBarsPadding()) { MoreScreen() } }

                composable(
                    "novel/{sourceId}?path={path}&title={title}&cover={cover}",
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("path") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { e ->
                    val a = e.arguments!!
                    val sourceId = a.getString("sourceId").orEmpty()
                    val path = a.getString("path").orEmpty()
                    val title = a.getString("title").orEmpty()
                    val cover = a.getString("cover")?.ifBlank { null }
                    val id by produceState<String?>(null, sourceId, path) {
                        value = container.repository.ensureNovel(sourceId, path, title, cover)
                    }
                    val novelId = id
                    if (novelId == null) LoadingBox() else ContentWidth(840.dp) { NovelScreen(novelId) }
                }

                composable(
                    "reader/{novelId}?chapter={chapter}&para={para}",
                    arguments = listOf(
                        navArgument("novelId") { type = NavType.StringType },
                        navArgument("chapter") { type = NavType.StringType; defaultValue = "" },
                        navArgument("para") { type = NavType.IntType; defaultValue = -1 },
                    ),
                ) { e ->
                    val a = e.arguments!!
                    ReaderScreen(
                        novelId = a.getString("novelId").orEmpty(),
                        chapterId = a.getString("chapter")?.ifBlank { null },
                        paragraph = a.getInt("para").takeIf { it >= 0 },
                    )
                }

                composable(
                    "source/{sourceId}?listing={listing}&genre={genre}",
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("listing") { type = NavType.StringType; defaultValue = "" },
                        navArgument("genre") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { e ->
                    val a = e.arguments!!
                    SourceScreen(
                        a.getString("sourceId").orEmpty(),
                        a.getString("listing")?.ifBlank { null },
                        a.getString("genre")?.ifBlank { null },
                    )
                }

                composable("search?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { e ->
                    SearchScreen(e.arguments?.getString("q").orEmpty())
                }
                composable("web?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })) { e ->
                    WebScreen(e.arguments?.getString("url").orEmpty())
                }
                composable("downloads") { DownloadsScreen() }
                composable("settings/dns") { DnsSettingsScreen() }
                composable("settings/reader") { ReaderSettingsScreen() }
                composable("settings/appearance") { AppearanceScreen() }
                composable("settings/sources") { SourcesSettingsScreen() }
                composable("settings/backup") { BackupScreen() }
                composable("settings/about") { AboutScreen() }
                composable("settings/health") { com.joe.novelreader.ui.fix.SourceHealthScreen() }
                composable("migrate/{novelId}", arguments = listOf(navArgument("novelId") { type = NavType.StringType })) { e ->
                    com.joe.novelreader.ui.fix.MigrateScreen(e.arguments?.getString("novelId").orEmpty())
                }
            }
        }
      }
    }
}

private fun makeNav(nc: NavHostController) = Nav(
    openNovel = { sourceId, path, title, cover ->
        nc.navigate("novel/${enc(sourceId)}?path=${enc(path)}&title=${enc(title)}&cover=${enc(cover)}")
    },
    openReader = { novelId, chapterId ->
        nc.navigate("reader/${enc(novelId)}?chapter=${enc(chapterId)}")
    },
    openSource = { sourceId, listing, genre ->
        nc.navigate("source/${enc(sourceId)}?listing=${enc(listing)}&genre=${enc(genre)}")
    },
    openSearch = { q -> nc.navigate("search?q=${enc(q)}") },
    openWeb = { url -> nc.navigate("web?url=${enc(url)}") },
    open = { route ->
        if (tabs.any { it.route == route }) {
            nc.navigate(route) {
                popUpTo(nc.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            nc.navigate(route)
        }
    },
    back = { if (!nc.popBackStack()) Unit },
)
