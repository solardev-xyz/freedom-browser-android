package baby.freedom.mobile.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import baby.freedom.mobile.data.PageVisits
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.HistoryEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/**
 * Full-screen list of recent history entries, grouped under day headers
 * (Today, Yesterday, then dates) and filtered by a search field pinned
 * under the title (#263). The search runs in Room
 * ([BrowsingRepository.searchHistory]) against the title and the stored
 * display URL (`bzz://…`, `name.eth/…`, `https://…`). Tapping a row
 * calls [onOpen] with the canonical URL; the host closes the screen and
 * submits the URL into the active tab.
 *
 * A long-press on a row (or its TalkBack actions) opens it in a new or
 * a private tab behind this one instead (#321): [onOpenInNewTab], with
 * whether the new tab is private — always, when [private] (the page was
 * opened from a private tab; see [entryOpenTargets]).
 *
 * Rows are flat (#418): favicon, title and site; the full address is in
 * the long-press menu (and read by TalkBack). Back-to-back visits of one
 * page on one day that differ only in their `#section` are one row
 * ([PageVisits.mergeRuns]), whose × removes all of them; a search's
 * results aren't merged, as the visits between them may not be shown. The first row opens Delete browsing data
 * ([DeleteBrowsingDataPage]) over this page, which hands the choice to
 * [onDeleteBrowsingData] as Settings' does.
 */
@Composable
fun HistoryScreen(
    repo: BrowsingRepository,
    private: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
    onDeleteBrowsingData: (DeleteChoice) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var deleteDataOpen by rememberSaveable { mutableStateOf(false) }
    // Registered after the dismiss handler so it wins while there's a
    // query: Back clears the search first, as on the Settings page.
    var query by rememberSaveable(saver = LibraryQuerySaver) { mutableStateOf("") }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    val hasHistory by remember { repo.hasHistory }.collectAsState(initial = null)
    // Results stay tagged with the query they answer, and the previous
    // answer stays up until the next one arrives, so typing never
    // flashes the no-results state between keystrokes.
    val results by produceState<Pair<String, List<HistoryEntry>>?>(null, query.trim()) {
        val q = query.trim()
        repo.searchHistory(q).collect { value = q to it }
    }
    val calendar = rememberCalendarDay()
    // Merged only in the whole, unfiltered list and within a day: a
    // search's results leave out the visits between two of its rows, so
    // there two visits of one page needn't be back to back, and a row's
    // × must remove only the visits it stands for.
    val merged = remember(results, calendar.zone) {
        val searching = results?.first.orEmpty().isNotEmpty()
        PageVisits.mergeRuns(results?.second.orEmpty()) { newer, older ->
            searching || !sameDay(newer.visitedAt, older.visitedAt, calendar.zone)
        }
    }
    val days = remember(merged, calendar) {
        historyDays(merged.rows, calendar.date, calendar.zone)
    }
    // DateFormat captures the default time zone when it's built, so a
    // zone change needs a fresh one for the rows' times to follow.
    val timeFormat = remember(calendar.zone) { DateFormat.getTimeInstance(DateFormat.SHORT) }

    FullScreenScaffold(
        title = stringResource(R.string.library_history_title),
        onDismiss = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Cookies, cache and closed tabs are there to delete even with
            // no history, so this row is always there.
            DeleteDataRow(onClick = { deleteDataOpen = true })
            when {
                // Nothing read yet: draw nothing rather than a wrong state.
                hasHistory == null -> Unit
                hasHistory == false && query.isBlank() -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.library_history_empty_title),
                    hint = stringResource(R.string.library_history_empty_hint),
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    LibrarySearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.library_history_search_placeholder),
                    )
                    if (showsNoMatches(results, query)) {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(R.string.library_history_no_matches_title),
                            hint = stringResource(R.string.library_history_no_matches_hint),
                        )
                    } else {
                        HistoryList(
                            days = days,
                            timeFormat = timeFormat,
                            fromPrivate = private,
                            repo = repo,
                            onOpen = onOpen,
                            onOpenInNewTab = onOpenInNewTab,
                            onRemove = { repo.deleteHistory(merged.idsOf(it)) },
                        )
                    }
                }
            }
        }
    }
    // Over History, which Back from it returns to.
    if (deleteDataOpen) {
        val context = LocalContext.current
        DeleteBrowsingDataPage(
            repo = repo,
            onDelete = { choice ->
                afterDeleteBrowsingData(context, choice, onDeleteBrowsingData) { deleteDataOpen = false }
            },
            onBack = { deleteDataOpen = false },
        )
    }
}

/** [a] and [b] (epoch millis) fall on the same calendar day in [zone]. */
internal fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

/** History's first row (#418): opens Delete browsing data. */
@Composable
private fun DeleteDataRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.DeleteSweep,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.library_history_delete_data),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The local calendar day and the zone it was read in. */
internal data class CalendarDay(val date: LocalDate, val zone: ZoneId) {
    companion object {
        fun now(): CalendarDay {
            val zone = ZoneId.systemDefault()
            return CalendarDay(LocalDate.now(zone), zone)
        }
    }
}

/**
 * Today's date and zone, kept current while the page is open so the
 * Today/Yesterday headers never go stale. A midnight timer alone isn't
 * enough: `delay` on the main thread counts awake time only, so it
 * runs late after the phone sleeps, and it knows nothing of a clock or
 * zone change. So the day is also re-read every time the page resumes
 * and on the system's date, time and time-zone change broadcasts.
 */
@Composable
private fun rememberCalendarDay(): CalendarDay {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val day by produceState(CalendarDay.now(), context, lifecycle) {
        val recheck = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                recheck.trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Protected system broadcasts sent by system_server, which
        // reaches a not-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck.trySend(Unit)
        }
        lifecycle.addObserver(observer)
        try {
            while (true) {
                val now = ZonedDateTime.now(ZoneId.systemDefault())
                value = CalendarDay(now.toLocalDate(), now.zone)
                val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                val wait = Duration.between(now, next).toMillis().coerceAtLeast(1_000)
                withTimeoutOrNull(wait) { recheck.receive() }
            }
        } finally {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return day
}

@Composable
internal fun HistoryList(
    days: List<HistoryDay>,
    timeFormat: DateFormat,
    fromPrivate: Boolean,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
    onRemove: (Long) -> Unit,
    // Where the rows' favicons come from; without it, letter tiles.
    repo: BrowsingRepository? = null,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (day in days) {
            stickyHeader(key = "day-${day.date.toEpochDay()}") {
                Text(
                    day.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            items(items = day.entries, key = { it.id }) { entry ->
                EntryRow(
                    url = entry.url,
                    title = entry.title.ifBlank { entry.url },
                    timestamp = timeFormat.format(Date(entry.visitedAt)),
                    repo = repo,
                    onClick = { onOpen(entry.url) },
                    openActions = entryOpenActions(fromPrivate) { private -> onOpenInNewTab(entry.url, private) },
                    onRemove = { onRemove(entry.id) },
                )
            }
        }
    }
}

/** The search field pinned under History's and Downloads' titles (#263, #322). */
@Composable
internal fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_history_clear_search))
                }
            }
        } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The search text rides in saved instance state only up to 1024 chars;
 * a longer paste is dropped on process death rather than risking a
 * bundle past the binder limit.
 */
internal val LibraryQuerySaver = Saver<MutableState<String>, String>(
    save = { it.value.takeIf { q -> q.length <= 1024 } },
    restore = { mutableStateOf(it) },
)

/**
 * The site a History row names (#418): the address's host, without
 * `www.` — `https://www.example.org/a?b` reads "example.org", a dweb
 * address its name or content hash. The whole address is in the row's
 * long-press menu.
 */
internal fun historyHost(url: String): String {
    val rest = url.substringAfter("://", url)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (end < 0) rest else rest.substring(0, end)
    val host = authority.substringAfterLast('@')
    return host.removePrefix("www.").ifBlank { url }
}

/**
 * One History row (#418): favicon, title and site on a flat row, the
 * visit's time at the end, and its ×. A long-press opens a menu that
 * starts with the full address, then the open-in-new-tab items (#321).
 */
@Composable
private fun EntryRow(
    url: String,
    title: String,
    timestamp: String,
    repo: BrowsingRepository?,
    onClick: () -> Unit,
    openActions: List<Pair<String, () -> Unit>>,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.common_remove)
    val host = historyHost(url)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            // Long-press for the menu (#321); TalkBack gets its items as
            // actions on the row itself.
            .combinedClickable(
                onLongClickLabel = stringResource(R.string.library_entry_options),
                onLongClick = { menuOpen = true },
                onClick = onClick,
            )
            .semantics { customActions = openActions.asAccessibilityActions() }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryFavicon(repo = repo, url = url, title = title)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The site, and when: the time is a fact of its own, so it
            // keeps its place when the site is long or the font large.
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        // The row shows the site; TalkBack reads the full
                        // address, which a sighted user has in the
                        // long-press menu — not among the row's actions —
                        // so two visits of one site can be told apart.
                        .semantics { contentDescription = url }
                        .padding(end = 8.dp),
                )
                Text(
                    timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box {
            // The long-press menu, dropped from the row's end: the full
            // address first, then where to open it.
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                EntryOpenMenuItems(openActions, onClose = { menuOpen = false })
            }
        }
        // Material's own size: a full 48 dp target (#279).
        IconButton(
            onClick = onRemove,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = removeLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A History row's site icon: its cached favicon, or a letter tile until there is one. */
@Composable
private fun EntryFavicon(repo: BrowsingRepository?, url: String, title: String) {
    val favicon = repo?.let { rememberFavicon(repo = it, url = url) }
    Box(
        modifier = Modifier
            // A picture of the site the row's text already names.
            .clearAndSetSemantics {}
            .size(32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (favicon != null) Color.White else tileAccentFor(url)),
        contentAlignment = Alignment.Center,
    ) {
        if (favicon != null) {
            Image(
                bitmap = favicon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                initialChar(title, url).toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = BiasAlignment(0f, -0.25f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
