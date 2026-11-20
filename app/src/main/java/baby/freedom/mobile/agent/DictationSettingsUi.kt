package baby.freedom.mobile.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.FullScreenScaffold
import baby.freedom.mobile.browser.SectionCard
import kotlinx.coroutines.launch
import java.util.Locale

/** [tag]'s name in its own language ("Deutsch"), capitalized as a list item. */
internal fun nativeLanguageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/**
 * The phone's recognizers and the one dictation uses ([kind], null: none).
 * [ready] false: the user's pick isn't read yet, so [kind] isn't known
 * either; nothing is built or named from it until it is.
 */
internal data class DictationRecognizer(val available: Set<RecognizerKind>, val kind: RecognizerKind?, val ready: Boolean = true)

/**
 * The recognizer dictation uses, with the user's pick from Settings:
 * the composer, the Settings row and the Settings page all read this,
 * so each names the destination the microphone actually sends to.
 * The phone's recognizers are read again each time the app comes back
 * on screen, so one turned on or installed meanwhile counts at once;
 * and nothing is picked until the stored choice is read (the last one
 * this process read stands in), so a user who picked the service never
 * gets an on-device recognizer built for a frame first.
 */
@Composable
internal fun rememberDictationRecognizer(): DictationRecognizer {
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(DictationRecognizers.available(context)) }
    LifecycleResumeEffect(context) {
        available = DictationRecognizers.available(context)
        onPauseOrDispose { }
    }
    val settings = remember(context) { DictationSettings.get(context) }
    val preferService by remember(settings) { settings.preferServiceOrUnread }.collectAsState(initial = settings.lastPreferService)
    return dictationRecognizer(available, preferService)
}

/** [available]'s recognizer for the user's pick ([preferService] null: not read yet, so none and not [DictationRecognizer.ready]). */
internal fun dictationRecognizer(available: Set<RecognizerKind>, preferService: Boolean?): DictationRecognizer =
    if (preferService == null) {
        DictationRecognizer(available, kind = null, ready = false)
    } else {
        DictationRecognizer(available, DictationRecognizers.pick(available, preferService))
    }

/** The Voice dictation row's line: where audio goes and the language, or that there's no recognizer. */
@Composable
internal fun dictationSummary(kind: RecognizerKind?, pick: String?): String {
    val language = pick?.let(::nativeLanguageName) ?: stringResource(R.string.agent_dictation_app_language)
    return when (kind) {
        null -> stringResource(R.string.agent_dictation_none)
        RecognizerKind.OnDevice -> stringResource(R.string.agent_dictation_summary_on_device, language)
        RecognizerKind.Service -> stringResource(R.string.agent_dictation_summary_service, language)
    }
}

/**
 * Settings → AI → Voice dictation (#578): where the audio goes with the
 * recognizer dictation uses (the same answer the composer's microphone
 * uses), a pick between the on-device recognizer and the speech service
 * when the phone has both, and the language, following the app language unless one is
 * picked. Closes by itself if AI is turned off meanwhile.
 */
@Composable
internal fun AiDictationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val providers = remember(context) { AgentProviders.get(context) }
    val state by providers.state.collectAsState(initial = null)
    if (state?.enabled == false) LaunchedEffect(Unit) { onBack() }
    val settings = remember(context) { DictationSettings.get(context) }
    val pick by settings.language.collectAsState(initial = null)
    val recognizer = rememberDictationRecognizer()
    val kind = recognizer.kind
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var recognizerFailed by remember { mutableStateOf(false) }
    val appLocale = LocalConfiguration.current.locales[0]

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.agent_dictation), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Not before the user's recognizer pick is read: never the wrong destination.
            if (recognizer.ready) item("where") {
                SectionCard(title = stringResource(R.string.agent_dictation_where)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                when (kind) {
                                    RecognizerKind.OnDevice -> R.string.agent_dictation_where_on_device
                                    RecognizerKind.Service if RecognizerKind.OnDevice in recognizer.available ->
                                        R.string.agent_dictation_where_service_picked
                                    RecognizerKind.Service -> R.string.agent_dictation_where_service
                                    null -> R.string.agent_dictation_where_none
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Both on the phone: which one is the user's to pick.
                        // Android can report an on-device recognizer that never
                        // works, and dictation never switches by itself.
                        if (recognizer.available.size > 1) {
                            Column {
                                if (recognizerFailed) {
                                    Text(
                                        stringResource(R.string.agent_dictation_save_failed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                                LanguageRow(
                                    title = stringResource(R.string.agent_dictation_use_on_device),
                                    subtitle = stringResource(R.string.agent_dictation_use_on_device_hint),
                                    selected = kind == RecognizerKind.OnDevice,
                                    onClick = { scope.launch { recognizerFailed = !settings.setPreferService(false) } },
                                )
                                LanguageRow(
                                    title = stringResource(R.string.agent_dictation_use_service),
                                    subtitle = stringResource(R.string.agent_dictation_use_service_hint),
                                    selected = kind == RecognizerKind.Service,
                                    onClick = { scope.launch { recognizerFailed = !settings.setPreferService(true) } },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.agent_dictation_where_common),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item("language") {
                SectionCard(title = stringResource(R.string.agent_dictation_language_title)) {
                    Column {
                        if (failed) {
                            Text(
                                stringResource(R.string.agent_dictation_save_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        fun choose(tag: String?) {
                            scope.launch { failed = !settings.setLanguage(tag) }
                        }
                        LanguageRow(
                            title = stringResource(R.string.agent_dictation_app_language),
                            subtitle = appLocale.getDisplayName(appLocale),
                            selected = pick == null,
                            onClick = { choose(null) },
                        )
                        for (tag in DICTATION_LANGUAGES) {
                            LanguageRow(
                                title = nativeLanguageName(tag),
                                subtitle = Locale.forLanguageTag(tag).getDisplayName(appLocale),
                                selected = pick == tag,
                                onClick = { choose(tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            // "Deutsch / German", but not "English / English".
            if (!subtitle.equals(title, ignoreCase = true)) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
