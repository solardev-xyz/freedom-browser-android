package baby.freedom.mobile.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
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
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.HistoryEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.time.Duration
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
 */
@Composable
fun HistoryScreen(
    repo: BrowsingRepository,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Registered after the dismiss handler so it wins while there's a
    // query: Back clears the search first, as on the Settings page.
    var query by rememberSaveable(saver = HistoryQuerySaver) { mutableStateOf("") }
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
    val days = remember(results, calendar) {
        historyDays(results?.second.orEmpty(), calendar.date, calendar.zone)
    }
    // DateFormat captures the default time zone when it's built, so a
    // zone change needs a fresh one for the rows' times to follow.
    val timeFormat = remember(calendar.zone) { DateFormat.getTimeInstance(DateFormat.SHORT) }

    FullScreenScaffold(
        title = "History",
        onDismiss = onDismiss,
    ) {
        when {
            // Nothing read yet: draw nothing rather than a wrong state.
            hasHistory == null -> Unit
            hasHistory == false && query.isBlank() -> EmptyState(
                icon = Icons.Outlined.History,
                title = "No history yet",
                hint = "Pages you visit will show up here.",
            )
            else -> Column(modifier = Modifier.fillMaxSize()) {
                HistorySearchField(query = query, onQueryChange = { query = it })
                if (showsNoMatches(results, query)) {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "No matches",
                        hint = "Nothing in your history matches that. " +
                            "Search looks at page titles and addresses.",
                    )
                } else {
                    HistoryList(
                        days = days,
                        timeFormat = timeFormat,
                        onOpen = onOpen,
                        onRemove = { repo.deleteHistory(it) },
                    )
                }
            }
        }
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
    onOpen: (String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
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
                        .padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            items(items = day.entries, key = { it.id }) { entry ->
                EntryRow(
                    title = entry.title.ifBlank { entry.url },
                    subtitle = entry.url,
                    timestamp = timeFormat.format(Date(entry.visitedAt)),
                    onClick = { onOpen(entry.url) },
                    onRemove = { onRemove(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun HistorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search history") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
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
private val HistoryQuerySaver = Saver<MutableState<String>, String>(
    save = { it.value.takeIf { q -> q.length <= 1024 } },
    restore = { mutableStateOf(it) },
)

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    timestamp: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    PageRow(
        title = title,
        subtitle = subtitle,
        thirdLine = timestamp,
        onClick = onClick,
        trailing = {
            IconButton(
                onClick = onRemove,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
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
