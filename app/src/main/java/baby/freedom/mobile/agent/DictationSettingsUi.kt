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
 * recognizer this phone has (the same answer the composer's microphone
 * uses), and the language, following the app language unless one is
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
    val kind = remember(context) { DictationRecognizers.pick(context) }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val appLocale = LocalConfiguration.current.locales[0]

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.agent_dictation), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("where") {
                SectionCard(title = stringResource(R.string.agent_dictation_where)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                when (kind) {
                                    RecognizerKind.OnDevice -> R.string.agent_dictation_where_on_device
                                    RecognizerKind.Service -> R.string.agent_dictation_where_service
                                    null -> R.string.agent_dictation_where_none
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
