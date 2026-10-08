package baby.freedom.mobile.browser

import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BookmarkEntry
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.HistoryEntry
import baby.freedom.mobile.ui.isLight

/**
 * Opaque home surface that overlays the WebView whenever the active
 * tab is on [HOME_URL] (i.e. [BrowserState.url] is empty). Renders:
 *
 *  - Hero: Freedom wordmark + tagline (mirrors the old home.html).
 *  - Introduction (#278): on a first launch only, one card saying what
 *    Freedom opens, that its nodes run on the phone, and that no wallet
 *    is needed, with one tap to a curated dweb site. Got it dismisses it
 *    for good ([NodeSettings.introDismissed]).
 *  - Swarm warm-up (#278): a status line, until the Swarm node has
 *    peers ([swarmWarmUp]); tapping it opens the node page.
 *  - Bookmarks: horizontal row of tiles, up to what fits; tapping
 *    submits the bookmark URL through the browser's standard submit
 *    pipeline (so `bzz://` / `ens://` still take the probe-gated path).
 *  - Recent pages: vertical list of the 8 most-recent distinct URLs.
 *    A long-press on a tile or a recent page (or its TalkBack actions)
 *    opens it in a new or a private tab behind this one instead
 *    ([onOpenInNewTab], with whether the tab is private; #321).
 *  - Explore: the curated dweb sites ([EXPLORE_CURATED], iOS's list),
 *    always shown, so a first launch has something to tap.
 *
 * Empty bookmarks / empty history each simply hide their section.
 */
@Composable
internal fun HomeScreen(
    repo: BrowsingRepository,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
    nodeInfo: NodeInfo,
    runNodeEnabled: Boolean,
    onOpenNode: () -> Unit,
    /**
     * Footprint of the floating chrome capsule, which overlays the
     * bottom of this surface — added to the scrolling column's bottom
     * padding so the last "Recent" row can still be scrolled clear of
     * it.
     */
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    /** A newer Freedom release to announce (#272), or `null`. */
    update: LatestRelease? = null,
    /** Closes [update]'s notice; a later release shows again. */
    onDismissUpdate: (LatestRelease) -> Unit = {},
) {
    val bookmarks by remember { repo.bookmarks }.collectAsState(initial = emptyList())
    val recent by remember { repo.recentDistinct(RECENT_LIMIT) }
        .collectAsState(initial = emptyList())
    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    // `null` until read and decided: the card only appears once the store
    // has said `false`, so it never flashes up for everyone else.
    val introDismissed by remember(settings) { settings.introDismissed }
        .collectAsState(initial = null)
    // Hidden at once on Got it, whether or not the write then lands.
    var introClosed by remember { mutableStateOf(false) }
    val showIntro = introDismissed == false && !introClosed
    val scope = rememberCoroutineScope()
    val externalSwarm by Gateways.externalSwarmBaseFlow.collectAsState()
    // `null` until the store has been read: [runNodeEnabled] arrives with
    // an optimistic `true` before then, and Gateways' external endpoint as
    // `""`, so for someone who switched the node off (or points Swarm at
    // their own node) the line would flash up on a cold start.
    val nodeSettings by remember(settings) {
        settings.runNodeEnabled
            .combine(settings.externalSwarmEndpoint) { run, ext -> run to ext.isNotEmpty() }
            .catch { Log.w("HomeScreen", "reading the node settings failed (${it.javaClass.simpleName})") }
    }.collectAsState(initial = null)
    val warmUp = swarmWarmUp(
        nodeInfo,
        runNodeEnabled = nodeSettings?.let { it.first && runNodeEnabled },
        external = nodeSettings?.second == true || externalSwarm.isNotEmpty(),
    )

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        // Decorative swarm hero backdrop — same image the old
        // home.html painted with `background-size: cover; opacity: 0.6`
        // behind the logo. Sits under everything and never captures
        // input (no [clickable] / [pointerInput]).
        //
        // The image is mid-to-dark in tone, so the opacity that makes it
        // a subtle texture on a dark background makes it the *whole*
        // background on a light one — dark enough to swallow the black
        // wordmark and the body copy that are picked for a light
        // surface. On the light scheme it drops to a wash, which is the
        // same decoration doing the same job at the tone that scheme can
        // carry.
        Image(
            painter = painterResource(id = R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = if (MaterialTheme.colorScheme.isLight) 0.2f else 0.6f,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 24.dp + bottomContentPadding),
        ) {
            HomeHero(modifier = Modifier.padding(horizontal = 24.dp))

            if (update != null) {
                Spacer(Modifier.height(24.dp))
                UpdateNotice(
                    release = update,
                    onOpen = { onOpen(update.url) },
                    onDismiss = { onDismissUpdate(update) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (showIntro) {
                Spacer(Modifier.height(if (update != null) 12.dp else 24.dp))
                IntroCard(
                    onTry = { onOpen(EXPLORE_CURATED.first().address) },
                    onDismiss = {
                        introClosed = true
                        scope.launch {
                            try {
                                settings.dismissIntro()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Hidden for this session regardless; it
                                // comes back on a later launch only if the
                                // write never reached the disk.
                                Log.w("HomeScreen", "saving the introduction's dismissal failed (${e.javaClass.simpleName})")
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (warmUp != null) {
                Spacer(Modifier.height(if (showIntro || update != null) 12.dp else 24.dp))
                WarmUpRow(
                    warmUp = warmUp,
                    onClick = onOpenNode,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // The hero keeps its tall, empty backdrop above the first list
            // while nothing else sits under it.
            val firstGap = if (update != null || showIntro || warmUp != null) 32.dp else 96.dp

            if (bookmarks.isNotEmpty()) {
                Spacer(Modifier.height(firstGap))
                SectionHeader(
                    stringResource(R.string.browser_home_bookmarks),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                BookmarkTiles(bookmarks = bookmarks, repo = repo, onOpen = onOpen, onOpenInNewTab = onOpenInNewTab)
            }

            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(if (bookmarks.isEmpty()) firstGap else 16.dp))
                SectionHeader(
                    stringResource(R.string.browser_home_recent),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                RecentList(entries = recent, onOpen = onOpen, onOpenInNewTab = onOpenInNewTab)
            }

            Spacer(Modifier.height(if (bookmarks.isEmpty() && recent.isEmpty()) firstGap else 16.dp))
            SectionHeader(
                stringResource(R.string.browser_home_explore),
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            ExploreList(onOpen = onOpen)

            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val RECENT_LIMIT = 8

/** A curated dweb site on the home page's Explore row. */
internal data class ExploreEntry(
    val title: String,
    @StringRes private val subtitleRes: Int,
    /** Submitted through the address bar's pipeline, like a typed address. */
    val address: String,
) {
    val subtitle: String get() = Strings.get(subtitleRes)
}

/** iOS's `ExploreEntry.mainnetCurated`, the same list on both platforms. */
internal val EXPLORE_CURATED: List<ExploreEntry> = listOf(
    ExploreEntry(
        title = "Swarmit",
        subtitleRes = R.string.browser_explore_swarmit_subtitle,
        address = "app.swarmit.eth",
    ),
)

/** What the home page says about the Swarm node while it has no peers yet (#278). */
internal enum class SwarmWarmUp { Starting, Connecting, Failed }

/**
 * The Swarm warm-up line's state, or `null` once there is nothing to say:
 * the node has peers (the menu's "N peers"), the user switched it off, or
 * an external Swarm endpoint (#125) stands in for it. [runNodeEnabled] is
 * `null` while the setting hasn't been read yet, which says nothing either.
 */
internal fun swarmWarmUp(info: NodeInfo, runNodeEnabled: Boolean?, external: Boolean): SwarmWarmUp? {
    if (runNodeEnabled != true || external) return null
    return when (info.status) {
        // Enabled but not yet up: the service is on its way.
        NodeStatus.Stopped, NodeStatus.Starting -> SwarmWarmUp.Starting
        NodeStatus.Running -> if (info.connectedPeers > 0) null else SwarmWarmUp.Connecting
        NodeStatus.Error -> SwarmWarmUp.Failed
    }
}

@Composable
private fun IntroCard(
    onTry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body = MaterialTheme.typography.bodyMedium
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text(
                text = stringResource(R.string.browser_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_intro_body_web),
                style = body,
                color = bodyColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_intro_body_nodes),
                style = body,
                color = bodyColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_intro_body_wallet),
                style = body,
                color = bodyColor,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_intro_got_it)) }
                Button(onClick = onTry) { Text(stringResource(R.string.browser_intro_try, EXPLORE_CURATED.first().title)) }
            }
        }
    }
}

@Composable
private fun WarmUpRow(
    warmUp: SwarmWarmUp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, detail) = when (warmUp) {
        SwarmWarmUp.Starting -> stringResource(R.string.browser_warmup_starting) to
            stringResource(R.string.browser_warmup_starting_detail)
        SwarmWarmUp.Connecting -> stringResource(R.string.browser_warmup_connecting) to
            stringResource(R.string.browser_warmup_connecting_detail)
        // Any error, not only one at start: a node that was running can
        // fail later too, so this doesn't claim it never started.
        SwarmWarmUp.Failed -> stringResource(R.string.browser_warmup_failed) to
            stringResource(R.string.browser_warmup_failed_detail)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = stringResource(R.string.browser_warmup_open_node_details), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // Read out as it changes, so TalkBack hears when the node is up.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (warmUp == SwarmWarmUp.Failed) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        } else {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExploreList(onOpen: (String) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        for (entry in EXPLORE_CURATED) {
            PageRow(
                title = entry.title,
                subtitle = entry.address,
                thirdLine = entry.subtitle,
                onClick = { onOpen(entry.address) },
            )
        }
    }
}

/** "Freedom 0.6.11 is available", as the home notice (and Settings) says it. */
internal fun updateNoticeTitle(release: LatestRelease): String =
    Strings.get(R.string.browser_update_available, release.version.toString())

/**
 * The home screen's notice of a newer release (#272): its version, a
 * link to its release page, and × to close it. Every string wraps.
 */
@Composable
private fun UpdateNotice(
    release: LatestRelease,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.NewReleases,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                updateNoticeTitle(release),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.browser_update_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.browser_update_view_release))
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.browser_update_close, release.version.toString()),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeHero(modifier: Modifier = Modifier) {
    // Drive logo selection off the active Compose theme rather than
    // `isSystemInDarkTheme()`. The two agree (the theme follows the
    // configuration's light/dark, #269), but the scheme is still the honest
    // source: it is the thing this wordmark is actually being drawn on,
    // and an explicit `FreedomTheme(darkTheme = …)` — a preview, a
    // screenshot test — must not make the logo disappear.
    val logo = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        R.drawable.ic_freedom_wordmark_dark
    } else {
        R.drawable.ic_freedom_wordmark_light
    }
    Column(modifier = modifier) {
        Image(
            painter = painterResource(id = logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.height(32.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.browser_home_hero),
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
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun BookmarkTiles(
    bookmarks: List<BookmarkEntry>,
    repo: BrowsingRepository,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items = bookmarks, key = { it.id }) { entry ->
            BookmarkTile(
                entry = entry,
                repo = repo,
                onClick = { onOpen(entry.url) },
                // Home is a non-private tab's page: a private tab has its own.
                openActions = entryOpenActions(fromPrivate = false) { private -> onOpenInNewTab(entry.url, private) },
            )
        }
    }
}

@Composable
private fun BookmarkTile(
    entry: BookmarkEntry,
    repo: BrowsingRepository,
    onClick: () -> Unit,
    openActions: List<Pair<String, () -> Unit>>,
) {
    val label = entry.title.ifBlank { entry.url }
    val favicon = rememberFavicon(repo = repo, url = entry.url)
    var menuOpen by remember { mutableStateOf(false) }

    // The long-press menu drops from the tile.
    Box {
        Column(
            modifier = Modifier
                .width(84.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.library_entry_options),
                    onLongClick = { menuOpen = true },
                    onClick = onClick,
                )
                .semantics { customActions = openActions.asAccessibilityActions() }
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            EntryOpenMenuItems(openActions, onClose = { menuOpen = false })
        }
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
            .clip(MaterialTheme.shapes.large)
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
            // The initial is a picture of the name read just below it.
            .clearAndSetSemantics {}
            .size(64.dp)
            .clip(MaterialTheme.shapes.large)
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
 * [ImageBitmap] on [Dispatchers.Default] (#482), never during
 * composition, and through [FaviconImages] so rows showing the same icon,
 * or a row scrolled back into view, don't decode it again. Returns `null`
 * until something is available, or if decoding fails — callers should
 * show a fallback in that case.
 */
@Composable
internal fun rememberFavicon(repo: BrowsingRepository, url: String): ImageBitmap? {
    val image by remember(url) {
        repo.favicon(url)
            .map { bytes -> bytes?.let(FaviconImages::get) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = null)
    return image
}

/**
 * Decoded favicons, most recently shown kept (#482), budgeted by the
 * bitmaps' own size rather than their count (#516 R1-F1): Chromium
 * scales an icon to at most 192 px before handing it over, but nothing
 * here should depend on that, so the cache never holds more than
 * [MAX_BYTES] of pixels whatever the sites send, and lets go of all of
 * them when Android asks for memory back ([trimMemory]).
 */
internal object FaviconImages {
    /** 4 MiB: about thirty 192 px icons, or a thousand 32 px ones. */
    const val MAX_BYTES = 4 * 1024 * 1024

    private val cache = DecodeCache(
        maxSize = MAX_BYTES,
        sizeOf = { image: ImageBitmap -> image.width * image.height * 4 },
    ) { data ->
        runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }.getOrNull()
    }

    fun get(data: ByteArray): ImageBitmap? = cache.get(data)

    /**
     * Drop every decoded icon: Delete browsing data calls this once the
     * rows are gone (#516 R1-M2), so a deleted site's icon doesn't stay
     * in memory either. Rows still showing an icon decode it anew.
     */
    fun clear() = cache.clear()

    /**
     * Drop every decoded icon once the UI is hidden or memory runs low:
     * a row shown again decodes its icon anew, off the main thread.
     */
    fun trimMemory(level: Int) {
        if (clearsOn(level)) cache.clear()
    }

    @Suppress("DEPRECATION") // The RUNNING_* levels: still sent before API 34.
    internal fun clearsOn(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}

/**
 * A small LRU of decoded images keyed by their encoded bytes' content, so
 * the same icon arriving as a fresh array (Room hands out a new one per
 * query) still hits. It holds at most [maxSize] as measured by [sizeOf]
 * (by default, one per entry); an image bigger than that on its own is
 * returned but not kept. A failed decode isn't kept either.
 */
internal class DecodeCache<T : Any>(
    private val maxSize: Int,
    private val sizeOf: (T) -> Int = { 1 },
    private val decode: (ByteArray) -> T?,
) {
    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
    }

    private val entries = LinkedHashMap<Key, T>(16, 0.75f, true)
    private var size = 0

    /** Bumped by [clear], so a decode that began before it isn't kept after it. */
    private var cleared = 0L

    fun get(data: ByteArray): T? {
        val key = Key(data)
        val since = synchronized(entries) {
            entries[key]?.let { return it }
            cleared
        }
        // Decoded outside the lock: two rows racing on a new icon may both
        // decode it once, which beats one waiting on the other.
        val image = decode(data) ?: return null
        val cost = sizeOf(image)
        if (cost > maxSize) return image
        synchronized(entries) {
            if (cleared != since) return image
            entries.put(key, image)?.let { size -= sizeOf(it) }
            size += cost
            val eldest = entries.entries.iterator()
            while (size > maxSize && eldest.hasNext()) {
                size -= sizeOf(eldest.next().value)
                eldest.remove()
            }
        }
        return image
    }

    fun clear() = synchronized(entries) {
        entries.clear()
        size = 0
        cleared++
    }
}

@Composable
private fun RecentList(
    entries: List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
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
        for (entry in entries) key(entry.id) {
            // Home is a non-private tab's page: a private tab has its own.
            val openActions = entryOpenActions(fromPrivate = false) { private -> onOpenInNewTab(entry.url, private) }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                PageRow(
                    title = entry.title.ifBlank { entry.url },
                    subtitle = entry.url,
                    onClick = { onOpen(entry.url) },
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.library_entry_options),
                    modifier = Modifier.semantics { customActions = openActions.asAccessibilityActions() },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    EntryOpenMenuItems(openActions, onClose = { menuOpen = false })
                }
            }
        }
    }
}

/**
 * First printable letter we'll stamp on a bookmark tile. Prefers the
 * title (what the user sees in the bookmarks list) and falls back to
 * the scheme-stripped URL. Also the letter on a Home-screen shortcut
 * for a page with no favicon ([HomeScreenShortcuts]).
 */
internal fun initialChar(title: String, url: String): Char {
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

internal fun tileAccentFor(url: String): Color {
    // `hashCode` is fine here — we only need a stable bucket per URL,
    // not cryptographic distribution. `absoluteValue` because
    // `hashCode` can legitimately return `Int.MIN_VALUE`, whose
    // `Math.abs` is still negative.
    val h = url.hashCode()
    val idx = (h and Int.MAX_VALUE) % TILE_PALETTE.size
    return TILE_PALETTE[idx]
}

/**
 * The home surface of a private tab (#86), in place of [HomeScreen]:
 * no bookmarks or recent pages (the private tab isn't where the
 * browsing trail belongs), just what a private tab does and doesn't
 * do. Drawn in the private scheme by the caller ([PrivateTheme]).
 */
@Composable
fun PrivateHomeScreen(
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp, bottom = 24.dp + bottomContentPadding),
        ) {
            Icon(
                imageVector = PrivateTabIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.browser_private_home_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.browser_private_home_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.browser_private_home_still_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(R.string.browser_private_home_limits_header))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_private_home_limits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The mark of a private tab (#86): on its home page, its switcher card and its menu entry. */
internal val PrivateTabIcon: ImageVector
    get() = Icons.Filled.VisibilityOff
