package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.Mnemonic
import baby.freedom.mobile.wallet.VaultProtection
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.launch

/**
 * The three-word check at the end of the backup flow (#421): [CHECKED]
 * positions of the phrase, in ascending order, to be picked from shuffled
 * [chips] — the right words among others from the same phrase, so the
 * chips alone don't tell which are asked for; only the paper does.
 *
 * Words are compared as text, so a phrase that repeats a word accepts
 * either copy.
 */
internal class BackupCheck(private val words: List<String>, random: Random) {
    /** The asked-for positions, 0-based and ascending. */
    val positions: List<Int>

    /** What to pick from: each asked-for word once, plus up to [DECOYS] other words of the phrase. */
    val chips: List<String>

    init {
        require(words.size >= CHECKED) { "a phrase has at least $CHECKED words" }
        positions = words.indices.shuffled(random).take(CHECKED).sorted()
        val asked = positions.map { words[it] }
        val decoys = words.distinct().filter { it !in asked }.shuffled(random).take(DECOYS)
        chips = (asked + decoys).shuffled(random)
    }

    /** The word asked for in [slot] (0 until [CHECKED]). */
    fun expected(slot: Int): String = words[positions[slot]]

    companion object {
        const val CHECKED = 3
        const val DECOYS = 6
    }
}

/** The steps of [BackupFlow]. */
internal enum class BackupStep { INTRO, WORDS, CHECK, DONE }

/**
 * Where the backup flow (#421) stands, kept out of the composables so the
 * order it enforces is unit-tested: the words are only ever held here in
 * memory (never saved state), and the phrase counts as backed up only by
 * [save] — reachable only once the check has [passed]. Revealing the
 * words ([revealed]) records nothing.
 *
 * [needsCheck] false: the phrase is already backed up, so the words page
 * is just for looking, with no "I've written them down".
 */
internal class BackupFlowState(
    startWithIntro: Boolean,
    val needsCheck: Boolean,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    val startedWithIntro = startWithIntro

    var step by mutableStateOf(if (startWithIntro) BackupStep.INTRO else BackupStep.WORDS)
        private set

    /** The revealed words, while they may be on screen; null once hidden. */
    var words by mutableStateOf<List<String>?>(null)
        private set

    var check by mutableStateOf<BackupCheck?>(null)
        private set

    /** The chips picked so far (indices into [BackupCheck.chips]), one per filled slot. */
    var picked by mutableStateOf<List<Int>>(emptyList())
        private set

    /** The 1-based number of the word a wrong chip was picked for, while that's the last tap. */
    var wrongWord by mutableStateOf<Int?>(null)
        private set

    val passed: Boolean get() = check != null && picked.size == BackupCheck.CHECKED

    /** The intro's "Show the words" was tapped: the words page reveals at once, no second tap. */
    var revealRequested by mutableStateOf(false)
        private set

    fun showWords() {
        step = BackupStep.WORDS
        revealRequested = true
    }

    /** The words page has started the reveal [revealRequested] asked for. */
    fun revealStarted() {
        revealRequested = false
    }

    fun revealed(shown: List<String>) {
        words = shown
    }

    /**
     * Drop the words (Hide, Back, or the app going to the background): a
     * check under way goes with them, back to the words page, which asks
     * for them again.
     */
    fun hide() {
        words = null
        resetCheck()
        if (step == BackupStep.CHECK) step = BackupStep.WORDS
    }

    /** "I've written them down": on to the check. False when there's nothing to check against. */
    fun writtenDown(): Boolean {
        val shown = words ?: return false
        if (!needsCheck) return false
        check = BackupCheck(shown, random)
        picked = emptyList()
        wrongWord = null
        step = BackupStep.CHECK
        return true
    }

    /** "See the words again": back to them, still revealed; the check starts over afterwards. */
    fun backToWords() {
        resetCheck()
        step = BackupStep.WORDS
    }

    /** One back step: check → words, words → intro (if it began there). False: leave the flow. */
    fun back(): Boolean = when (step) {
        BackupStep.CHECK -> {
            backToWords()
            true
        }
        BackupStep.WORDS -> if (startedWithIntro) {
            hide()
            step = BackupStep.INTRO
            true
        } else {
            false
        }
        BackupStep.INTRO, BackupStep.DONE -> false
    }

    /**
     * A chip tapped: fills the next slot if it's that word, else names the
     * word it isn't and leaves the slot open. True once all three are right.
     */
    fun pick(chip: Int): Boolean {
        val c = check ?: return false
        if (passed || chip in picked || chip !in c.chips.indices) return passed
        val slot = picked.size
        if (c.chips[chip] == c.expected(slot)) {
            picked = picked + chip
            wrongWord = null
        } else {
            wrongWord = c.positions[slot] + 1
        }
        return passed
    }

    /**
     * Record the backup ([markBackedUp]) — only once the check has passed —
     * then drop the words and show Done. If recording fails, it throws and
     * the passed check stays, to try again.
     */
    suspend fun save(markBackedUp: suspend () -> Unit) {
        check(passed) { "the check hasn't passed" }
        markBackedUp()
        words = null
        resetCheck()
        step = BackupStep.DONE
    }

    private fun resetCheck() {
        check = null
        picked = emptyList()
        wrongWord = null
    }
}

/**
 * Back up the recovery phrase (#421), or just look at it: an intro ("Write
 * these 24 words on paper", when [BackupFlowState.startedWithIntro]), the
 * words behind a fresh authentication ([RecoveryPhrasePage]), "I've
 * written them down", then a check of three named words from shuffled
 * chips. Only a passed check records the backup ([markBackedUp]); "Later"
 * leaves at any point with the reminder still up.
 *
 * Every step is `FLAG_SECURE` ([SecureWindow]); the words are held in
 * plain memory ([BackupFlowState]) and dropped on Hide, on leaving, and as
 * soon as the app goes to the background.
 */
@Composable
internal fun BackupFlow(
    state: BackupFlowState,
    protection: VaultProtection,
    /** For the intro's title; null when the phrase's length isn't known before it's shown. */
    wordCount: Int?,
    /** Why the backup matters for this wallet ([backupReminderText]). */
    reminder: String,
    reveal: suspend () -> Mnemonic,
    markBackedUp: suspend () -> Unit,
    errorMessage: (Throwable) -> String?,
    onClose: () -> Unit,
) {
    SecureWindow()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.hide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val close = {
        state.hide()
        onClose()
    }
    val back = { if (!state.back()) close() }
    when (state.step) {
        BackupStep.INTRO -> BackupIntroPage(wordCount, reminder, onShow = state::showWords, onLater = close)
        BackupStep.WORDS -> RecoveryPhrasePage(
            title = stringResource(if (state.needsCheck) R.string.wallet_backup_flow_title else R.string.wallet_phrase_section),
            protection = protection,
            words = state.words,
            onRevealed = state::revealed,
            onHide = state::hide,
            reveal = reveal,
            errorMessage = errorMessage,
            onWrittenDown = if (state.needsCheck) ({ state.writtenDown() }) else null,
            onBack = back,
            revealNow = state.revealRequested,
            onRevealStarted = state::revealStarted,
        )
        BackupStep.CHECK -> BackupCheckPage(state, markBackedUp, errorMessage, onBack = back, onLater = close)
        BackupStep.DONE -> BackupDonePage(onDone = close)
    }
}

@Composable
private fun BackupIntroPage(wordCount: Int?, reminder: String, onShow: () -> Unit, onLater: () -> Unit) {
    BackHandler(onBack = onLater)
    FullScreenScaffold(title = stringResource(R.string.wallet_backup_flow_title), onDismiss = onLater) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("head") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    BigIcon(Icons.Filled.EditNote)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (wordCount != null) {
                            pluralText(R.plurals.wallet_backup_intro_title, wordCount, wordCount)
                        } else {
                            stringResource(R.string.wallet_backup_intro_title_any)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        reminder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item("points") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntroPoint(Icons.Outlined.NoPhotography, stringResource(R.string.wallet_backup_intro_paper))
                    IntroPoint(Icons.Outlined.Visibility, stringResource(R.string.wallet_backup_intro_secret))
                    IntroPoint(Icons.Outlined.FactCheck, stringResource(R.string.wallet_backup_intro_check))
                }
            }
            item("actions") {
                Column(Modifier.fillMaxWidth()) {
                    Button(onClick = onShow, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wallet_backup_show_words))
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wallet_backup_later))
                    }
                }
            }
        }
    }
}

@Composable
private fun BigIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun IntroPoint(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BackupCheckPage(
    state: BackupFlowState,
    markBackedUp: suspend () -> Unit,
    errorMessage: (Throwable) -> String?,
    onBack: () -> Unit,
    onLater: () -> Unit,
) {
    val check = state.check ?: return
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (saving || !state.passed) return
        saving = true
        error = null
        scope.launch {
            try {
                state.save(markBackedUp)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                error = errorMessage(e)
            } finally {
                saving = false
            }
        }
    }
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.wallet_check_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("text") {
                Text(stringResource(R.string.wallet_check_text), style = MaterialTheme.typography.bodyLarge)
            }
            item("slots") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    check.positions.forEachIndexed { slot, position ->
                        CheckSlot(
                            number = position + 1,
                            word = state.picked.getOrNull(slot)?.let { check.chips[it] },
                            current = slot == state.picked.size,
                        )
                    }
                }
            }
            item("wrong") {
                // Always composed, so TalkBack reads a new wrong pick as it lands.
                Text(
                    state.wrongWord?.let { stringResource(R.string.wallet_check_wrong, it) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            item("chips") {
                SectionCard(title = stringResource(R.string.wallet_check_words_heading)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        check.chips.forEachIndexed { index, word ->
                            val used = index in state.picked
                            val enabled = !used && !state.passed
                            FilterChip(
                                selected = used,
                                enabled = enabled,
                                // On surface with the outline colour: on surfaceVariant the
                                // default border all but disappears in the dark theme.
                                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = enabled,
                                    selected = used,
                                    borderColor = MaterialTheme.colorScheme.outline,
                                ),
                                onClick = { if (state.pick(index)) save() },
                                label = { Text(word, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
            error?.let { message ->
                item("error") {
                    Column {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.common_try_again))
                        }
                    }
                }
            }
            if (saving) item("saving") {
                Text(stringResource(R.string.wallet_check_saving), style = MaterialTheme.typography.bodyMedium)
            }
            item("actions") {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = state::backToWords, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wallet_check_see_words))
                    }
                    TextButton(onClick = onLater, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wallet_backup_later))
                    }
                }
            }
        }
    }
}

/** One asked-for word: its number, and the word once picked; the next to fill is outlined. */
@Composable
private fun CheckSlot(number: Int, word: String?, current: Boolean) {
    val description = if (word != null) {
        stringResource(R.string.wallet_check_slot_filled, number, word)
    } else {
        stringResource(R.string.wallet_check_slot_empty, number)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (current) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.wallet_check_slot, number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            word ?: "—",
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            fontWeight = if (word != null) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (word != null) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BackupDonePage(onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    FullScreenScaffold(title = stringResource(R.string.wallet_backup_flow_title), onDismiss = onDone) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            item("icon") { BigIcon(Icons.Filled.CheckCircle) }
            item("title") {
                Text(
                    stringResource(R.string.wallet_check_done_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            item("text") {
                Text(
                    stringResource(R.string.wallet_check_done_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            item("done") {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wallet_check_done))
                }
            }
        }
    }
}

/**
 * The wallet home's slim "Back up your wallet" banner (#421), shown until
 * the phrase is backed up; the whole row opens the backup flow. Kept as
 * one self-contained item so the home's layout can move it freely.
 */
@Composable
internal fun BackupBanner(enabled: Boolean, onBackUp: () -> Unit) {
    // Amber, as the wallet page's other warnings.
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, amber.copy(alpha = 0.5f), MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onBackUp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = amber)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.wallet_banner_title), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.wallet_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // The row is the button; this label only shows what a tap does. Under the
            // text rather than beside it, so a large font never squeezes the text.
            Text(
                stringResource(R.string.wallet_banner_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
