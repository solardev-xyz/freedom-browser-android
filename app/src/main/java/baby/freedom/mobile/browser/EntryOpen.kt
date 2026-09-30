package baby.freedom.mobile.browser

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import baby.freedom.mobile.R

/**
 * Where a saved page — a bookmark, a history entry, a Home tile — can be
 * opened other than over the tab on screen (#321): a new tab, or a
 * private one. Both open behind the current tab, with the "Opened in new
 * tab" snackbar and its Switch, as the page context menu's *Open in new
 * tab* does.
 */
internal enum class EntryOpenTarget { NewTab, PrivateTab }

/**
 * The targets a list opened from a tab offers, in menu order. From a
 * private tab a new tab is a private one too (the list mustn't be a way
 * out of private browsing), so *Open in private tab* would only repeat
 * *Open in new tab* and isn't offered.
 */
internal fun entryOpenTargets(fromPrivate: Boolean): List<EntryOpenTarget> =
    if (fromPrivate) listOf(EntryOpenTarget.NewTab) else EntryOpenTarget.entries

/** Whether [this] opens a private tab, from a list opened in a private tab or not. */
internal fun EntryOpenTarget.opensPrivate(fromPrivate: Boolean): Boolean = when (this) {
    EntryOpenTarget.NewTab -> fromPrivate
    EntryOpenTarget.PrivateTab -> true
}

/**
 * The open actions for one entry, labelled, each already resolved to
 * "private or not" — the one list every surface builds its menu items
 * and TalkBack actions from.
 */
@Composable
internal fun entryOpenActions(
    fromPrivate: Boolean,
    onOpenInNewTab: (private: Boolean) -> Unit,
): List<Pair<String, () -> Unit>> = entryOpenTargets(fromPrivate).map { target ->
    val label = stringResource(
        when (target) {
            EntryOpenTarget.NewTab -> R.string.browser_menu_open_in_new_tab
            EntryOpenTarget.PrivateTab -> R.string.browser_menu_open_in_private_tab
        },
    )
    label to { onOpenInNewTab(target.opensPrivate(fromPrivate)) }
}

/** [actions] as TalkBack custom actions. */
internal fun List<Pair<String, () -> Unit>>.asAccessibilityActions(): List<CustomAccessibilityAction> =
    map { (label, open) ->
        CustomAccessibilityAction(label) {
            open()
            true
        }
    }

/** [actions] as items of a dropdown menu that [onClose] closes first. */
@Composable
internal fun EntryOpenMenuItems(actions: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    actions.forEach { (label, open) ->
        DropdownMenuItem(
            text = { Text(label) },
            onClick = {
                onClose()
                open()
            },
        )
    }
}
