package baby.freedom.mobile.browser

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BookmarkEditResult
import baby.freedom.mobile.data.BookmarkEntry
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit bookmark [id]'s name and address (#264): from the Bookmarks
 * list's row menu, and from the "Bookmark added" snackbar's Edit.
 * [private] is whether it was opened from a private tab, whose fields
 * keep the keyboard from learning what's typed ([TabTextInput]).
 *
 * Closes by itself if the bookmark isn't there (removed before the
 * dialog came up).
 */
@Composable
internal fun BookmarkEditDialog(
    repo: BrowsingRepository,
    id: Long,
    private: Boolean,
    onDismiss: () -> Unit,
) {
    // Loading is null; a bookmark that isn't there is an empty list.
    val loaded by produceState<List<BookmarkEntry>?>(null, repo, id) {
        value = listOfNotNull(
            try {
                repo.bookmarkById(id)
            } catch (e: SQLiteException) {
                null
            },
        )
    }
    val entry = loaded?.firstOrNull()
    when {
        loaded == null -> Unit
        entry == null -> LaunchedEffect(Unit) { onDismiss() }
        else -> BookmarkEditor(repo, entry, private, onDismiss)
    }
}

@Composable
private fun BookmarkEditor(
    repo: BrowsingRepository,
    entry: BookmarkEntry,
    private: Boolean,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(entry.id, saver = BookmarkFieldSaver) { mutableStateOf(entry.title) }
    var address by rememberSaveable(entry.id, saver = BookmarkFieldSaver) { mutableStateOf(entry.url) }
    // What the last Save came back with, until the fields change.
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Parsed off the main thread (#296 R3-M2): a dweb name goes through
    // ENSIP-15 normalisation, on every keystroke. Until the typed text's
    // own result is in, the last one is shown; Save parses what's typed
    // itself, so it never saves an older result.
    val parsed by produceState<BookmarkAddress?>(null, address) {
        value = withContext(Dispatchers.Default) { bookmarkAddress(address) }
    }

    // The address as it was opened: saved exactly as it's stored, not
    // re-spelled, and with no "Saves as" note — a rename touches only
    // the name (#296 R6-M1).
    val unchanged = address.trim() == entry.url
    val canSave = (unchanged || parsed is BookmarkAddress.Ok) && !saving

    val save: () -> Unit = save@{
        if (!canSave) return@save
        val typed = address
        saving = true
        scope.launch {
            val url = if (typed.trim() == entry.url) {
                entry.url
            } else {
                val ok = withContext(Dispatchers.Default) { bookmarkAddress(typed) } as? BookmarkAddress.Ok
                if (ok == null) {
                    saving = false
                    return@launch
                }
                ok.url
            }
            // The write runs in the repository's scope: leaving the
            // dialog while it runs doesn't undo it.
            val result = repo.editBookmark(entry.id, bookmarkTitle(title), url).await()
            saving = false
            when (result) {
                BookmarkEditResult.Saved -> onDismiss()
                is BookmarkEditResult.Duplicate ->
                    saveError = result.title.takeIf { it.isNotBlank() }
                        ?.let { Strings.get(R.string.library_bookmark_already_bookmarked_as, it) }
                        ?: Strings.get(R.string.library_bookmark_already_bookmarked)
                BookmarkEditResult.Gone -> saveError = Strings.get(R.string.library_bookmark_removed)
                BookmarkEditResult.Failed -> saveError = Strings.get(R.string.library_bookmark_save_failed)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_bookmark_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TabTextInput(private) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            saveError = null
                        },
                        label = { Text(stringResource(R.string.library_bookmark_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TabTextInput(private) {
                    NoSuggestionsTextInput {
                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                saveError = null
                            },
                            label = { Text(stringResource(R.string.library_bookmark_address)) },
                            singleLine = true,
                            isError = saveError != null || (!unchanged && parsed is BookmarkAddress.Invalid),
                            // The address it saves as, when that's not
                            // what's typed — `https://` added, say — or
                            // why it can't be saved.
                            supportingText = {
                                val note = saveError ?: if (unchanged) null else when (val p = parsed) {
                                    null -> null
                                    is BookmarkAddress.Invalid -> p.reason
                                    is BookmarkAddress.Ok ->
                                        if (p.url != address.trim()) stringResource(R.string.library_bookmark_saves_as, p.url) else null
                                }
                                if (note != null) Text(note)
                            },
                            keyboardOptions = urlKeyboardOptions(ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { save() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = save,
                enabled = canSave,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Keeps a field's text across a recreation only up to
 * [MAX_SAVED_BOOKMARK_FIELD_CHARS]: a huge paste isn't put in the
 * saved-state bundle, where it could crash the app past the binder limit
 * as it goes to the background (the edit restarts from the bookmark then).
 */
private val BookmarkFieldSaver = Saver<MutableState<String>, String>(
    save = { it.value.takeIf { text -> text.length <= MAX_SAVED_BOOKMARK_FIELD_CHARS } },
    restore = { mutableStateOf(it) },
)

private const val MAX_SAVED_BOOKMARK_FIELD_CHARS = 8 * 1024
