package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

/**
 * How far back Delete browsing data reaches (#400), as Chrome offers it.
 * Only browsing history honours it: WebView can delete cookies, site
 * storage and its HTTP cache only all at once ([allTimeOnlyNote]).
 */
enum class DeleteRange(@StringRes val labelRes: Int, private val millis: Long?) {
    LastHour(R.string.delete_data_range_hour, HOUR),
    Last24Hours(R.string.delete_data_range_day, 24 * HOUR),
    Last7Days(R.string.delete_data_range_week, 7 * 24 * HOUR),
    Last4Weeks(R.string.delete_data_range_four_weeks, 28 * 24 * HOUR),
    AllTime(R.string.delete_data_range_all, null),
    ;

    val label: String get() = Strings.get(labelRes)

    /**
     * The earliest visit time (epoch ms) this range deletes, given the
     * clock reads [now]; 0 for [AllTime], which deletes every visit.
     */
    fun since(now: Long): Long = millis?.let { (now - it).coerceAtLeast(1L) } ?: 0L
}

private const val HOUR = 60L * 60L * 1000L

/**
 * What Delete browsing data is about to delete: a [range] for history,
 * and the checkboxes. The defaults are Chrome's for Chrome's three: the
 * last hour, every box checked; [swarmCache], the Swarm node's chunk
 * cache, is off unless picked — it isn't something a browser's clear
 * usually reaches, and refilling it costs network.
 */
data class DeleteChoice(
    val range: DeleteRange = DeleteRange.LastHour,
    val history: Boolean = true,
    val siteData: Boolean = true,
    val cache: Boolean = true,
    val swarmCache: Boolean = false,
) {
    /** Delete data is enabled: at least one box is checked. */
    val canDelete: Boolean get() = history || siteData || cache || swarmCache

    /** The reopen stack keeps closed tabs' pages and back/forward lists — history by another name. */
    val forgetsClosedTabs: Boolean get() = history || siteData

    /**
     * Delete data asks first. Cookies and site data can't be taken back
     * and go for all time whatever the range says, so a tap meant for
     * the last hour's history must not sign the user out of every site
     * on its own. History in a range and the cache don't ask, as in Chrome.
     */
    val needsConfirm: Boolean get() = siteData
}

/**
 * The confirmation's message when [DeleteChoice.needsConfirm]: everyone
 * signed out, every site's storage gone, and — for a bounded range — that
 * the range doesn't hold them back.
 */
internal fun deleteConfirmMessage(choice: DeleteChoice): String =
    if (choice.range == DeleteRange.AllTime) {
        Strings.get(R.string.delete_data_confirm_all_time)
    } else {
        Strings.get(R.string.delete_data_confirm_ranged, choice.range.label.replaceFirstChar { it.lowercase() })
    }

/**
 * The line saying a picked range doesn't reach cookies, site data or the
 * cache — they go for all time, since neither `CookieManager`,
 * `WebStorage` nor `WebView.clearCache` take a date. Null when the range
 * is all time anyway, or neither of those boxes is checked. It says
 * history keeps to the range only when history is checked too.
 */
internal fun allTimeOnlyNote(choice: DeleteChoice): String? {
    if (choice.range == DeleteRange.AllTime) return null
    val note = when {
        choice.siteData && choice.cache -> Strings.get(R.string.delete_data_all_time_both)
        choice.siteData -> Strings.get(R.string.delete_data_all_time_cookies)
        choice.cache -> Strings.get(R.string.delete_data_all_time_cache)
        else -> return null
    }
    return if (choice.history) "$note ${Strings.get(R.string.delete_data_all_time_history_range)}" else note
}

/** The history box's sub-line: how many visits the range holds. */
internal fun historyCountLine(count: Int): String =
    if (count == 0) {
        Strings.get(R.string.delete_data_history_none)
    } else {
        Strings.plural(R.plurals.delete_data_history_count, count, count)
    }

/**
 * The toast after Delete data: "Browsing history deleted" when that was
 * all, else "Browsing data deleted". Kept short — Android cuts a toast
 * at two lines, and at a large font scale a list of what went wouldn't fit.
 */
internal fun deleteDoneMessage(choice: DeleteChoice): String =
    if (choice.history && !choice.siteData && !choice.cache && !choice.swarmCache) {
        Strings.get(R.string.delete_data_done_history)
    } else {
        Strings.get(R.string.delete_data_done)
    }

/**
 * Delete what [choice] names: history in its range from [repo] here, and
 * the Swarm node's cache, all of it whatever the range
 * ([SwarmCache.clearInBackground]: pinned content stays); the rest —
 * closed tabs, WebView state, node logs — through [onDelete], the
 * host's, which owns the tabs and WebViews.
 */
internal fun deleteBrowsingData(
    choice: DeleteChoice,
    repo: BrowsingRepository,
    now: Long,
    onDelete: (DeleteChoice) -> Unit,
) = deleteBrowsingData(choice, repo::deleteHistorySince, SwarmCache::clearInBackground, now, onDelete)

/** [deleteBrowsingData] over its two stores, for tests. */
internal fun deleteBrowsingData(
    choice: DeleteChoice,
    deleteHistorySince: (Long) -> Unit,
    clearSwarmCache: () -> Unit,
    now: Long,
    onDelete: (DeleteChoice) -> Unit,
) {
    if (!choice.canDelete) return
    if (choice.history) deleteHistorySince(choice.range.since(now))
    // Not a date in it: ant can only drop every unpinned chunk.
    if (choice.swarmCache) clearSwarmCache()
    onDelete(choice)
}

private val DeleteChoiceSaver = Saver<DeleteChoice, List<Any>>(
    save = { listOf(it.range.name, it.history, it.siteData, it.cache, it.swarmCache) },
    restore = {
        DeleteChoice(
            range = DeleteRange.entries.firstOrNull { r -> r.name == it[0] } ?: DeleteRange.LastHour,
            history = it[1] as Boolean,
            siteData = it[2] as Boolean,
            cache = it[3] as Boolean,
            swarmCache = it.getOrNull(4) as? Boolean ?: false,
        )
    },
)

/**
 * Settings → Privacy & security → Delete browsing data (#400), Chrome's
 * page of the same name: a time range, three boxes with what each
 * covers, and Delete data. [onDelete] gets the choice once history is
 * on its way out, to delete the rest (the host closes the page and says
 * what went).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun DeleteBrowsingDataPage(
    repo: BrowsingRepository,
    onDelete: (DeleteChoice) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var choice by rememberSaveable(stateSaver = DeleteChoiceSaver) { mutableStateOf(DeleteChoice()) }
    val swarmRunning = StampClient.node.collectAsState().value.status == NodeStatus.Running
    // What Delete data acts on: the Swarm box counts only while it can be acted on.
    val acted = if (swarmRunning) choice else choice.copy(swarmCache = false)
    var confirming by rememberSaveable { mutableStateOf(false) }
    // Fixed while the page is open, so the count doesn't creep as the clock does.
    val now = remember { System.currentTimeMillis() }
    // One flow for the page's life, switching query as the range does:
    // the last range's count stays on the sub-line until Room answers for
    // the new one, rather than the line blanking and the card jumping
    // (R3-M4). Only the very first answer starts from nothing.
    val historyCount by remember(repo, now) {
        snapshotFlow { choice.range.since(now) }.flatMapLatest { repo.historyCount(it) }
    }.collectAsState(initial = null)

    FullScreenScaffold(title = stringResource(R.string.delete_data_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("kinds") {
                SectionCard(title = stringResource(R.string.settings_section_browsing)) {
                    RangeRow(selected = choice.range, onSelect = { choice = choice.copy(range = it) })
                    CheckRow(
                        icon = Icons.Filled.History,
                        title = stringResource(R.string.delete_data_history),
                        subtitle = historyCount?.let(::historyCountLine) ?: "",
                        checked = choice.history,
                        onCheckedChange = { choice = choice.copy(history = it) },
                    )
                    CheckRow(
                        icon = Icons.Filled.Cookie,
                        title = stringResource(R.string.delete_data_cookies),
                        subtitle = stringResource(R.string.delete_data_cookies_subtitle),
                        detail = stringResource(R.string.delete_data_cookies_detail),
                        checked = choice.siteData,
                        onCheckedChange = { choice = choice.copy(siteData = it) },
                    )
                    CheckRow(
                        icon = Icons.Filled.Image,
                        title = stringResource(R.string.delete_data_cache),
                        subtitle = stringResource(R.string.delete_data_cache_subtitle),
                        detail = stringResource(R.string.delete_data_cache_detail),
                        checked = choice.cache,
                        onCheckedChange = { choice = choice.copy(cache = it) },
                    )
                    // ant clears only while the node runs; off (and unchecked) otherwise.
                    CheckRow(
                        icon = ImageVector.vectorResource(R.drawable.ic_swarm),
                        title = stringResource(R.string.delete_data_swarm_cache),
                        subtitle = stringResource(
                            if (swarmRunning) {
                                R.string.delete_data_swarm_cache_subtitle
                            } else {
                                R.string.delete_data_swarm_cache_off
                            },
                        ),
                        checked = choice.swarmCache && swarmRunning,
                        enabled = swarmRunning,
                        onCheckedChange = { choice = choice.copy(swarmCache = it) },
                    )
                }
            }
            val note = allTimeOnlyNote(acted)
                ?: if (!acted.canDelete) Strings.get(R.string.delete_data_nothing_selected) else null
            if (note != null) item("note") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("delete") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Button(
                        onClick = {
                            // The page's own clock reading: the range deletes at least
                            // every visit the count above showed.
                            if (acted.needsConfirm) confirming = true
                            else deleteBrowsingData(acted, repo, now, onDelete)
                        },
                        enabled = acted.canDelete,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.delete_data_button))
                    }
                }
            }
        }
    }
    if (confirming && acted.canDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_data_confirm_title),
            message = deleteConfirmMessage(acted),
            confirmLabel = stringResource(R.string.delete_data_confirm_button),
            onConfirm = {
                confirming = false
                deleteBrowsingData(acted, repo, now, onDelete)
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The time range: a row naming the range in use, opening a menu of all five. */
@Composable
private fun RangeRow(selected: DeleteRange, onSelect: (DeleteRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PageRow(
            title = stringResource(R.string.delete_data_range),
            subtitle = stringResource(selected.labelRes),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Schedule,
            onClick = { open = true },
            trailing = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (range in DeleteRange.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(range.labelRes)) },
                    // The one in use is ticked; the others keep the space, so the labels line up.
                    leadingIcon = {
                        if (range == selected) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    modifier = Modifier.semantics { this.selected = range == selected },
                    onClick = {
                        open = false
                        onSelect(range)
                    },
                )
            }
        }
    }
}

/**
 * A checkbox row: icon, title, a sub-line and an optional wrapped
 * detail, then the box. The whole row is the checkbox — one control to
 * TalkBack ("Cookies and site data, …, checkbox, checked") — and at
 * least 48 dp tall.
 */
@Composable
private fun CheckRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 24 dp whatever the vector's own size (the Swarm mark's is smaller), so the titles line up.
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle.isNotEmpty()) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            if (detail != null) Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
