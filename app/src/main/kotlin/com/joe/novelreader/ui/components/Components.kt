package com.joe.novelreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.joe.novelreader.AppContainer
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.source.CloudflareException
import com.joe.novelreader.ui.theme.Brand
import com.joe.novelreader.ui.theme.SectionTitle
import java.net.UnknownHostException
import kotlin.math.abs

val LocalContainer = compositionLocalOf<AppContainer> { error("No container") }

/** Things screens can ask the app to do, so they don't need to know about navigation. */
class Nav(
    val openNovel: (sourceId: String, path: String, title: String, cover: String?) -> Unit,
    val openReader: (novelId: String, chapterId: String?) -> Unit,
    val openSource: (sourceId: String, listing: String?, genre: String?) -> Unit,
    val openSearch: (query: String) -> Unit,
    val openWeb: (url: String) -> Unit,
    val open: (route: String) -> Unit,
    val back: () -> Unit,
)

val LocalNav = compositionLocalOf<Nav> { error("No nav") }

private val coverPalette = listOf(
    listOf(Color(0xFF3D63F2), Color(0xFF7B5CFF)),
    listOf(Color(0xFFFF7A45), Color(0xFFFFB347)),
    listOf(Color(0xFF00A88E), Color(0xFF4FD8BE)),
    listOf(Color(0xFFE5486B), Color(0xFFFF8FA3)),
    listOf(Color(0xFF6B4EFF), Color(0xFFB79CFF)),
    listOf(Color(0xFF2B2F3A), Color(0xFF59606E)),
)

@Composable
fun NovelCover(
    url: String?,
    title: String,
    sourceId: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val referer = sourceId?.let { container.sources.get(it)?.baseUrl }
    val request = remember(url, referer) {
        ImageRequest.Builder(ctx)
            .data(url)
            .apply { if (referer != null) addHeader("Referer", "$referer/") }
            .addHeader("User-Agent", container.network.userAgent)
            .crossfade(true)
            .build()
    }
    Box(
        modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(corner)),
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { CoverPlaceholder(title) },
            error = { CoverPlaceholder(title) },
        )
    }
}

@Composable
fun CoverPlaceholder(title: String) {
    val colors = coverPalette[abs(title.hashCode()) % coverPalette.size]
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.ifBlank { "?" },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
fun NovelGridCard(
    title: String,
    cover: String?,
    sourceId: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickableCompat(onClick, onLongClick)
            .padding(4.dp),
    ) {
        Box {
            NovelCover(cover, title, sourceId, Modifier.fillMaxWidth())
            if (badge != null) {
                Surface(
                    color = Brand.Badge,
                    shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = SectionTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onAction != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action ?: "More", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun NovelRow(
    novels: List<NovelItem>,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 108.dp,
    onClick: (NovelItem) -> Unit,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(novels, key = { it.sourceId + it.path }) { n ->
            NovelGridCard(n.title, n.cover, n.sourceId, Modifier.width(itemWidth), subtitle = n.subtitle) { onClick(n) }
        }
    }
}

@Composable
fun RatingBadge(rating: Float, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = Brand.Gold, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(2.dp))
        Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is CloudflareException -> e.message ?: "The site is checking your browser."
    is UnknownHostException -> "Couldn't find the site. Check your internet, or try another DNS server in Settings."
    is java.net.SocketTimeoutException -> "The site took too long to answer. Try again."
    is java.net.ConnectException -> "Couldn't connect to the site. Check your internet connection."
    is javax.net.ssl.SSLException -> "Secure connection to the site failed. Try again or change DNS."
    else -> e.message ?: e.javaClass.simpleName
}

@Composable
fun ErrorBox(
    error: Throwable,
    modifier: Modifier = Modifier,
    webUrl: String? = null,
    onRetry: () -> Unit,
) {
    val nav = LocalNav.current
    val cf = error as? CloudflareException
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(friendlyError(error), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Try again") }
            val url = cf?.url ?: webUrl
            if (url != null) {
                OutlinedButton(onClick = { nav.openWeb(url) }) {
                    Icon(Icons.Filled.Public, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open in browser")
                }
            }
        }
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
