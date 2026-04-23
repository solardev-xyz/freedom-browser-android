package baby.freedom.mobile.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BookmarkEntry
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.HistoryEntry

/**
 * Opaque home surface that overlays the WebView whenever the active
 * tab is on [HOME_URL] (i.e. [BrowserState.url] is empty). Renders:
 *
 *  - Hero: Freedom wordmark + tagline (mirrors the old home.html).
 *  - Bookmarks: horizontal row of tiles, up to what fits; tapping
 *    submits the bookmark URL through the browser's standard submit
 *    pipeline (so `bzz://` / `ens://` still take the probe-gated path).
 *  - Recent pages: vertical list of the 8 most-recent distinct URLs.
 *
 * Empty bookmarks / empty history each simply hide their section —
 * when both are empty the screen is just the hero, which matches what
 * users saw before we replaced the HTML home.
 */
@Composable
fun HomeScreen(
    repo: BrowsingRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookmarks by remember { repo.bookmarks }.collectAsState(initial = emptyList())
    val recent by remember { repo.recentDistinct(RECENT_LIMIT) }
        .collectAsState(initial = emptyList())

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        // Decorative swarm hero backdrop — same image the old
        // home.html painted with `background-size: cover; opacity: 0.6`
        // behind the logo. Sits under everything and never captures
        // input (no [clickable] / [pointerInput]).
        Image(
            painter = painterResource(id = R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.6f,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
        ) {
            HomeHero(modifier = Modifier.padding(horizontal = 24.dp))

            if (bookmarks.isNotEmpty()) {
                Spacer(Modifier.height(96.dp))
                SectionHeader(
                    "Bookmarks",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                BookmarkTiles(bookmarks = bookmarks, repo = repo, onOpen = onOpen)
            }

            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    "Recent",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                RecentList(entries = recent, onOpen = onOpen)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val RECENT_LIMIT = 8

@Composable
private fun HomeHero(modifier: Modifier = Modifier) {
    // Drive logo selection off the active Compose theme rather than
    // `isSystemInDarkTheme()` — the app forces a dark color scheme
    // regardless of the OS setting (see MainActivity), so reading the
    // system flag would pick the black wordmark on a light-mode
    // device and make the logo disappear on our dark background.
    val logo = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        R.drawable.ic_freedom_wordmark_dark
    } else {
        R.drawable.ic_freedom_wordmark_light
    }
    Column(modifier = modifier) {
        Image(
            painter = painterResource(id = logo),
            contentDescription = "Freedom",
            modifier = Modifier.height(32.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "The decentralized web is here. Powered by built-in nodes, " +
                "this browser connects you directly to peers, keeping the " +
                "network strong and user-controlled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun BookmarkTiles(
    bookmarks: List<BookmarkEntry>,
    repo: BrowsingRepository,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items = bookmarks, key = { it.id }) { entry ->
            BookmarkTile(entry = entry, repo = repo, onClick = { onOpen(entry.url) })
        }
    }
}

@Composable
private fun BookmarkTile(
    entry: BookmarkEntry,
    repo: BrowsingRepository,
    onClick: () -> Unit,
) {
    val label = entry.title.ifBlank { entry.url }
    val favicon = rememberFavicon(repo = repo, url = entry.url)

    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (favicon != null) {
            FaviconTile(favicon = favicon)
        } else {
            LetterTile(entry = entry)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FaviconTile(favicon: ImageBitmap) {
    // Pale surface under the icon so both light and dark favicons stay
    // legible — many sites ship a solid-dark mark that would disappear
    // against our dark app background if we let it float on nothing.
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = favicon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun LetterTile(entry: BookmarkEntry) {
    // Pick a deterministic accent from the URL so every tile is
    // distinguishable. Used when we haven't captured a favicon yet
    // (or for schemes / sites that don't advertise one).
    val accent = tileAccentFor(entry.url)
    val initial = initialChar(entry.title, entry.url).toString()
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * Load the cached favicon for [url], decoding PNG bytes into an
 * [ImageBitmap] the first time they show up (and again whenever the
 * cached row changes). Returns `null` until something is available,
 * or if decoding fails — callers should show a fallback in that case.
 */
@Composable
private fun rememberFavicon(repo: BrowsingRepository, url: String): ImageBitmap? {
    val bytes by remember(url) { repo.favicon(url) }.collectAsState(initial = null)
    return remember(bytes) {
        val data = bytes ?: return@remember null
        runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun RecentList(
    entries: List<HistoryEntry>,
    onOpen: (String) -> Unit,
) {
    // `RecentList` sits inside the home page's own vertical scroll, so a
    // nested LazyColumn would fight it for gestures. The list is capped
    // at [RECENT_LIMIT] so a plain Column is cheap enough.
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        for (entry in entries) {
            PageRow(
                title = entry.title.ifBlank { entry.url },
                subtitle = entry.url,
                onClick = { onOpen(entry.url) },
            )
        }
    }
}

/**
 * First printable letter we'll stamp on a bookmark tile. Prefers the
 * title (what the user sees in the bookmarks list) and falls back to
 * the scheme-stripped URL.
 */
private fun initialChar(title: String, url: String): Char {
    val source = title.ifBlank { url.substringAfter("://", url) }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•'
}

private val TILE_PALETTE: List<Color> = listOf(
    Color(0xFF3B82F6), // blue
    Color(0xFF8B5CF6), // violet
    Color(0xFFEC4899), // pink
    Color(0xFFEF4444), // red
    Color(0xFFF97316), // orange
    Color(0xFFEAB308), // amber
    Color(0xFF22C55E), // green
    Color(0xFF14B8A6), // teal
)

private fun tileAccentFor(url: String): Color {
    // `hashCode` is fine here — we only need a stable bucket per URL,
    // not cryptographic distribution. `absoluteValue` because
    // `hashCode` can legitimately return `Int.MIN_VALUE`, whose
    // `Math.abs` is still negative.
    val h = url.hashCode()
    val idx = (h and Int.MAX_VALUE) % TILE_PALETTE.size
    return TILE_PALETTE[idx]
}
