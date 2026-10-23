package com.joe.novelreader.ui.discover

import android.os.Build
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
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.ui.components.Chip
import com.joe.novelreader.ui.components.ErrorBox
import com.joe.novelreader.ui.components.LoadingBox
import com.joe.novelreader.ui.components.LocalContainer
import com.joe.novelreader.ui.components.LocalNav
import com.joe.novelreader.ui.components.NovelCover
import com.joe.novelreader.ui.components.NovelRow
import com.joe.novelreader.ui.components.SectionHeader
import com.joe.novelreader.ui.theme.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val vm: DiscoverViewModel = viewModel { DiscoverViewModel(c) }
    LaunchedEffect(Unit) { vm.refreshIfStale() }
    val open: (NovelItem) -> Unit = { nav.openNovel(it.sourceId, it.path, it.title, it.cover) }
    val genreSource = c.sources.enabled.firstOrNull { it.id == "novelbin" } ?: c.sources.enabled.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        // Search bar header, like the big reading apps.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Discover", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { nav.openSearch("") },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search all sources", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero") {
                    when (val h = vm.hero) {
                        is Load.Loading -> LoadingBox(Modifier.height(220.dp))
                        is Load.Failed -> ErrorBox(h.error, webUrl = genreSource?.baseUrl) { vm.load() }
                        is Load.Ok -> if (h.value.isNotEmpty()) HeroCarousel(h.value, open)
                    }
                }

                if (genreSource != null && genreSource.genres.isNotEmpty()) {
                    item(key = "genres") {
                        SectionHeader("Browse by genre", action = "All", onAction = { nav.openSource(genreSource.id, null, null) })
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(genreSource.genres) { g ->
                                Chip(g.name, onClick = { nav.openSource(genreSource.id, null, g.id) })
                            }
                        }
                    }
                }

                item(key = "recommended") {
                    when (val r = vm.recommended) {
                        is Load.Loading -> {
                            SectionHeader("Recommended for you")
                            LoadingBox()
                        }
                        is Load.Failed -> {
                            SectionHeader("Recommended for you")
                            ErrorBox(r.error) { vm.load() }
                        }
                        is Load.Ok -> if (r.value.items.isNotEmpty()) {
                            SectionHeader("Recommended for you")
                            Text(
                                r.value.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 0.dp),
                            )
                            NovelRow(r.value.items, onClick = open)
                        }
                    }
                }

                item(key = "ranking") {
                    when (val r = vm.ranking) {
                        is Load.Loading -> {
                            SectionHeader(vm.rankingTitle)
                            LoadingBox()
                        }
                        is Load.Failed -> {
                            SectionHeader(vm.rankingTitle)
                            ErrorBox(r.error) { vm.load() }
                        }
                        is Load.Ok -> if (r.value.isNotEmpty()) {
                            val src = r.value.first().sourceId
                            SectionHeader(vm.rankingTitle, action = "Full list", onAction = { nav.openSource(src, null, null) })
                            RankingGrid(r.value, open)
                        }
                    }
                }

                vm.sections.forEach { s ->
                    item(key = s.key) {
                        SectionHeader(s.title, onAction = { nav.openSource(s.sourceId, s.listingId, null) })
                        when (val st = s.state) {
                            is Load.Loading -> LoadingBox(Modifier.height(200.dp))
                            is Load.Failed -> ErrorBox(st.error, webUrl = c.sources.get(s.sourceId)?.baseUrl) { vm.load() }
                            is Load.Ok -> NovelRow(st.value, onClick = open)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCarousel(items: List<NovelItem>, open: (NovelItem) -> Unit) {
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            if (!pager.isScrollInProgress && items.isNotEmpty()) {
                pager.animateScrollToPage((pager.currentPage + 1) % items.size)
            }
        }
    }
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val n = items[page]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { open(n) },
            ) {
                AsyncImage(
                    model = n.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.35f)))),
                )
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Surface(color = Brand.Amber, shape = RoundedCornerShape(6.dp)) {
                            Text("HOT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(n.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        n.subtitle?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Spacer(Modifier.height(10.dp))
                        Surface(color = Color.White, shape = RoundedCornerShape(50)) {
                            Text("Read now", color = Color.Black, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    NovelCover(n.cover, n.title, n.sourceId, Modifier.width(110.dp), corner = 10.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}

@Composable
private fun RankingGrid(items: List<NovelItem>, open: (NovelItem) -> Unit) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { _, n -> n.sourceId + n.path }) { i, n ->
            Row(
                Modifier
                    .width(270.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open(n) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovelCover(n.cover, n.title, n.sourceId, Modifier.width(58.dp), corner = 6.dp)
                Spacer(Modifier.width(10.dp))
                val rankColor = when (i) {
                    0 -> Brand.Rank1
                    1 -> Brand.Rank2
                    2 -> Brand.Rank3
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text("${i + 1}", color = rankColor, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    n.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
