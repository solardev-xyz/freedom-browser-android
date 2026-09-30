package baby.freedom.mobile.browser

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.OpenSourceLicences.Component
import baby.freedom.mobile.browser.OpenSourceLicences.Section
import kotlinx.coroutines.CancellationException

/**
 * Settings → About → Open-source licences (#325): every component the APK
 * ships, grouped as [Section], searchable; a tap opens its licence text.
 * Everything comes from the bundled asset, so it works offline.
 */
@Composable
internal fun OpenSourceLicencesPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var licences by remember { mutableStateOf<OpenSourceLicences?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            licences = OpenSourceLicences.load(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A build always carries the asset; a broken one says so
            // instead of spinning forever.
            Log.w("OpenSourceLicences", "couldn't read ${OpenSourceLicences.ASSET}", e)
            failed = true
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // The open component, by a key that survives recreation.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val loaded = licences
    val open = openKey?.let { key -> loaded?.components?.firstOrNull { it.key == key } }
    if (loaded != null && open != null) {
        LicenceDetailPage(component = open, licences = loaded, onBack = { openKey = null })
        return
    }

    val focusManager = LocalFocusManager.current
    FullScreenScaffold(title = stringResource(R.string.licences_title), onDismiss = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.licences_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.licences_search_clear))
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
            when {
                loaded != null -> {
                    val matches = remember(loaded, query) { OpenSourceLicences.search(loaded.components, query) }
                    LicenceList(matches = matches, query = query, onOpen = { openKey = it.key })
                }
                failed -> Text(
                    stringResource(R.string.licences_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun LicenceList(matches: List<Component>, query: String, onOpen: (Component) -> Unit) {
    val bySection = remember(matches) { matches.groupBy { it.section } }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (query.isBlank()) item("intro") {
            Text(
                stringResource(R.string.licences_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        for (section in Section.entries) {
            val list = bySection[section] ?: continue
            item("header-${section.key}") {
                Text(
                    stringResource(R.string.licences_section_count, stringResource(section.title), list.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
                        .semantics { heading() },
                )
            }
            items(list, key = { it.key }) { component ->
                LicenceRow(component, onClick = { onOpen(component) })
            }
        }
        if (matches.isEmpty()) item("no-match") {
            Text(
                stringResource(R.string.licences_no_match, query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
            )
        }
    }
}

/**
 * One component: its name, then version and licence. Both wrap rather
 * than being cut short, so a long name or licence expression stays whole
 * at a large font scale.
 */
@Composable
private fun LicenceRow(component: Component, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(component.name, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            listOf(component.version, component.licence).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One component's page: what it is and where it comes from, any notice
 * that goes with it, then each licence text in full, reflowed
 * ([OpenSourceLicences.paragraphs]) and one list item per paragraph, so a
 * long one (the GPL is ~35 KB) lays out a screenful at a time.
 */
@Composable
private fun LicenceDetailPage(component: Component, licences: OpenSourceLicences, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val texts = remember(component) { licences.textsOf(component) }
    val paragraphs = remember(texts) { texts.map { OpenSourceLicences.paragraphs(it.text) } }
    FullScreenScaffold(title = component.name, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("facts") {
                SectionCard(title = stringResource(component.section.title)) {
                    if (component.id != component.name) Fact(stringResource(R.string.licences_detail_id), component.id)
                    if (component.version.isNotBlank()) Fact(stringResource(R.string.settings_version), component.version)
                    Fact(stringResource(R.string.licences_detail_licence), component.licence)
                    if (component.url.isNotBlank()) Fact(stringResource(R.string.licences_detail_source), component.url)
                }
            }
            component.notice?.let { notice ->
                item("notice") {
                    SelectionContainer {
                        Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            texts.forEachIndexed { t, text ->
                item("title-$t") {
                    Text(
                        text.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 16.dp)
                            .semantics { heading() },
                    )
                }
                itemsIndexed(paragraphs[t], key = { p, _ -> "text-$t-$p" }) { _, paragraph ->
                    SelectionContainer {
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A labelled value on a line of its own below the label, so neither is cut short. */
@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private val Component.key: String get() = "${section.key}:$id:$version"

private val Section.title: Int
    get() = when (this) {
        Section.Android -> R.string.licences_section_android
        Section.Native -> R.string.licences_section_native
        Section.Data -> R.string.licences_section_data
    }
