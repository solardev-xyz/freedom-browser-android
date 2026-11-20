package baby.freedom.mobile.agent

import baby.freedom.mobile.wallet.MessageSigning
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the agent asks to do, as the approval card shows it. */
internal sealed interface AgentAction {
    /** Load [url] in the tab (browser_navigate), or open it in a new one (browser_create_tab). */
    data class Navigate(val url: String, val newTab: Boolean) : AgentAction
    data class Click(val target: PageTarget) : AgentAction
    data class Type(val target: PageTarget, val text: String, val replace: Boolean) : AgentAction
    data class Select(val target: PageTarget, val values: List<String>) : AgentAction
    data class Press(val target: PageTarget, val key: String) : AgentAction
    data class Scroll(val target: PageTarget?, val direction: String, val pages: Double) : AgentAction

    /** Tap a file input (browser_upload, #600): the user picks the file in Android's picker (or the camera, for `capture`). */
    data class Upload(val target: PageTarget) : AgentAction

    /**
     * Tap a download link (browser_download, #600): only a download of the
     * link's exact address (fragment aside, [DownloadIntentMatch]) is saved
     * without another prompt, once; any other download the tap starts, even
     * where the link redirects to on its own site, waits for the user.
     * [target]'s address is the link's in full, as matched.
     */
    data class Download(val target: PageTarget) : AgentAction

    /**
     * Answer the page's [kind] dialog: OK ([accept]) or Cancel, with a
     * prompt's [promptText]. [from]: the site of the frame that opened it,
     * which the card names (an embedded page's can differ from the tab's).
     */
    data class Dialog(
        val kind: String,
        val message: String,
        val accept: Boolean,
        val promptText: String?,
        val from: String = "",
    ) : AgentAction
}

/**
 * A control on the page, as read just before the approval was asked
 * (page-controlled: the page names its own controls). [target] is where
 * a link or form goes, by the page's own markup.
 */
internal data class PageTarget(
    val role: String,
    val tag: String,
    val name: String,
    val effect: String? = null,
    val target: String? = null,
    val sensitive: Boolean = false,
)

/**
 * One approval: what the card shows, and everything the action is bound
 * to. [site] and [document] are the app's own record of the tab (its
 * committed page and how many documents it has committed), never the
 * page's word; the action is refused unless they still hold when it runs.
 */
internal data class AgentApproval(
    val id: Long,
    val sessionId: String,
    val callId: String,
    val tabId: Long,
    val site: String,
    val document: Int,
    val action: AgentAction,
    /** The agent's own claim of what this does: untrusted, shown as its words. */
    val intent: String?,
    /**
     * Why it's asked (#595): in "sensitive actions", the intent
     * classifier's summary (the chat's model judging page and agent
     * text, so shown as its words), that it sends a form, that it types
     * into a password or one-time-code field, or that the check gave no answer. In either relaxed mode, that the tab
     * wasn't on screen ([ApprovalCheck.NeedsScreen]). Null otherwise.
     */
    val check: ApprovalCheck? = null,
    /**
     * False when the mode let it run without a card: then it may only
     * run on a tab that is already on screen, never take the screen.
     * No default: a new kind of approval says which it is.
     */
    val byUser: Boolean,
)

/** How an approval ended. Only [Declined] is the user saying no. */
internal sealed interface ApprovalOutcome {
    data object Approved : ApprovalOutcome
    data object Declined : ApprovalOutcome

    /** Taken down without the user's answer: Stop, the tab taken back or closed, the chat deleted. */
    data object Withdrawn : ApprovalOutcome
}

/**
 * The approvals Freedom Agent is waiting on (#575). Each navigate,
 * click, type, select, press, scroll and dialog answer the chat's
 * approval mode ([ApprovalPolicy], #595) says to ask about waits here
 * for the user's own tap on its card ([AgentApprovalHost]); one card at a time,
 * in the order asked. An answer settles exactly the request it was
 * shown for ([answer] by id), once; a request that goes away (its turn
 * stopped, its tab taken back or closed) is withdrawn, which is never
 * read as the user's no.
 */
internal object AgentApprovals {
    private class Pending(val approval: AgentApproval, val result: CompletableDeferred<ApprovalOutcome>)

    private val ids = AtomicLong()
    private val pending = MutableStateFlow<List<Pending>>(emptyList())
    private val _shown = MutableStateFlow<AgentApproval?>(null)

    /** The approval on screen: the oldest waiting. */
    val shown: StateFlow<AgentApproval?> = _shown.asStateFlow()

    fun nextId(): Long = ids.incrementAndGet()

    /** Waits for the user's answer to [approval]; cancelling the caller withdraws it. */
    suspend fun ask(approval: AgentApproval): ApprovalOutcome {
        val entry = Pending(approval, CompletableDeferred())
        update { it + entry }
        try {
            return entry.result.await()
        } catch (e: CancellationException) {
            entry.result.complete(ApprovalOutcome.Withdrawn)
            throw e
        } finally {
            update { list -> list.filter { it !== entry } }
        }
    }

    /** The user's answer to the approval [id]; nothing for one already settled or gone. */
    fun answer(id: Long, approved: Boolean) {
        val entry = synchronized(lock) { pending.value.firstOrNull { it.approval.id == id } } ?: return
        entry.result.complete(if (approved) ApprovalOutcome.Approved else ApprovalOutcome.Declined)
        update { list -> list.filter { it !== entry } }
    }

    fun withdrawTab(tabId: Long) = withdraw { it.tabId == tabId }

    fun withdrawSession(sessionId: String) = withdraw { it.sessionId == sessionId }

    /** Every approval: the browser's host went away. */
    fun withdrawAll() = withdraw { true }

    private fun withdraw(which: (AgentApproval) -> Boolean) {
        val gone = synchronized(lock) { pending.value.filter { which(it.approval) } }
        if (gone.isEmpty()) return
        for (entry in gone) entry.result.complete(ApprovalOutcome.Withdrawn)
        update { list -> list.filter { it !in gone } }
    }

    /** One lock for the list and the card it shows, so two chats can't leave a card for a gone approval. */
    private val lock = Any()

    private fun update(change: (List<Pending>) -> List<Pending>) = synchronized(lock) {
        pending.update(change)
        _shown.value = pending.value.firstOrNull()?.approval
    }
}

/**
 * Text from a page or the model, made fit for an approval card (desktop's
 * L5): line breaks, tabs and other controls and the line and paragraph
 * separators become spaces; anything that draws nothing or reorders the
 * text around it (bidi overrides and isolates, zero-width and other
 * format characters, variation-selector runs, lone surrogates, a fourth
 * combining mark in a row: [MessageSigning.hides]) shows as U+FFFD, so
 * the card says something is there instead of hiding it; runs of spaces
 * collapse; and it's cut at [max] code points with an ellipsis.
 */
internal object ApprovalText {
    fun flatten(text: String, max: Int = 300): String {
        val scan = MessageSigning.Scan()
        val out = StringBuilder()
        var n = 0
        var cut = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val hidden = scan.hides(cp)
            val blank = Character.isWhitespace(cp) || Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 ||
                Character.isSpaceChar(cp)
            if (blank) {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                continue
            }
            if (n >= max) {
                cut = true
                break
            }
            if (hidden) out.append('�') else out.appendCodePoint(cp)
            n++
        }
        val s = out.toString().trim()
        return if (cut) "$s…" else s
    }

    private fun isLineBreak(c: Char) = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

    fun hasLineBreak(text: String): Boolean = text.any(::isLineBreak)

    /** Nothing but line breaks: what Chromium's input connection would send as the Enter key. */
    fun onlyLineBreaks(text: String): Boolean = text.isNotEmpty() && text.all(::isLineBreak)

    /** Typed text as a Type card shows it ([typed]). */
    sealed interface Typed {
        /** No text at all. */
        data object Empty : Typed

        /** Only spaces (no tab or line break): [count] of them, counted rather than drawn as ␣ after ␣. */
        data class Blank(val count: Int) : Typed

        data class Shown(val text: String) : Typed
    }

    /**
     * What a Type card shows for [text]: every character the field will
     * get, not [flatten]ed. Each line break is marked ↵ and starts a new
     * line on the card; a tab shows as ⇥; spaces stay as typed (a run of
     * them too), and those that would draw nothing — at the start or end
     * of the text or of a line — show as ␣. Controls and anything that
     * hides ([MessageSigning.hides]) show as U+FFFD. Text that's only
     * spaces is counted, not shown blank. Cut at [max] code points.
     */
    fun typed(text: String, max: Int): Typed {
        if (text.isEmpty()) return Typed.Empty
        val t = text.replace("\r\n", "\n")
        val cps = t.codePoints().toArray()
        if (cps.all { isTypedSpace(it) }) return Typed.Blank(cps.size)
        val scan = MessageSigning.Scan()
        val out = StringBuilder()
        var cut = false
        var i = 0
        while (i < cps.size) {
            if (i >= max) {
                cut = true
                break
            }
            val cp = cps[i]
            val hidden = scan.hides(cp)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == 0x2028 || cp == 0x2029 -> out.append("↵\n")
                cp == '\t'.code -> out.append('⇥')
                isTypedSpace(cp) -> {
                    // The whole run: as typed between two characters on a line, ␣ at an edge.
                    var j = i
                    while (j < cps.size && isTypedSpace(cps[j])) j++
                    val edge = i == 0 || j == cps.size || isLineBreakCp(cps[i - 1]) || isLineBreakCp(cps[j])
                    val end = minOf(j, max)
                    for (k in i until end) {
                        if (k > i) scan.hides(cps[k])
                        if (edge) out.append('␣') else out.appendCodePoint(cps[k])
                    }
                    i = end
                    continue
                }
                hidden || Character.isISOControl(cp) -> out.append('\uFFFD')
                else -> out.appendCodePoint(cp)
            }
            i++
        }
        val shown = out.toString().removeSuffix("\n")
        return Typed.Shown(if (cut) "$shown…" else shown)
    }

    private fun isLineBreakCp(cp: Int) = cp < 0x10000 && isLineBreak(cp.toChar())

    /** A space the field takes as is: U+0020 and the other space separators (no-break space, say). */
    private fun isTypedSpace(cp: Int) = cp == ' '.code || (Character.isSpaceChar(cp) && cp != 0x2028 && cp != 0x2029)
}

/**
 * Whether an approved action may still run (desktop re-checks at
 * dispatch, `describeReferencedElement`): the chat still holds the tab,
 * it's the same document on the same site the card named, and the
 * control is still the one shown. Anything else refuses it; the agent
 * is told to look again.
 */
internal object ApprovalBinding {
    data class TabNow(val heldBy: String?, val private: Boolean, val site: String, val document: Int)

    enum class Refusal { TabGone, NotHeld, Changed }

    fun check(approval: AgentApproval, now: TabNow?, targetNow: PageTarget? = null): Refusal? = when {
        now == null -> Refusal.TabGone
        now.private || now.heldBy != approval.sessionId -> Refusal.NotHeld
        now.document != approval.document || now.site != approval.site -> Refusal.Changed
        targetNow != null && !sameControl(targetNow, targetOf(approval.action)) -> Refusal.Changed
        else -> null
    }

    /**
     * The control the card named, as it is now: its tag, name, where it
     * goes and what it does. Not its ARIA role, which a page sets from
     * script once it's shown (a search box becoming a combobox) — and a
     * held tab's page is first shown by the approved action itself.
     */
    private fun sameControl(now: PageTarget, shown: PageTarget?): Boolean =
        shown != null && now.copy(role = shown.role) == shown

    fun targetOf(action: AgentAction): PageTarget? = when (action) {
        is AgentAction.Click -> action.target
        is AgentAction.Type -> action.target
        is AgentAction.Select -> action.target
        is AgentAction.Press -> action.target
        is AgentAction.Scroll -> action.target
        is AgentAction.Upload -> action.target
        is AgentAction.Download -> action.target
        else -> null
    }
}
