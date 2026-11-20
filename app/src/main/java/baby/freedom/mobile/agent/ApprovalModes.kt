package baby.freedom.mobile.agent

import kotlinx.coroutines.CancellationException

/**
 * A chat's approval mode (#595), desktop's `agent-approval-modes.js`,
 * chosen per chat in its composer and kept with it ([ChatSession]).
 */
internal enum class ApprovalMode(val wire: String) {
    /** Every navigation, page interaction and dialog answer asks (phase 1's only mode). */
    EveryInteraction("every_interaction"),

    /** The interaction-intent classifier lets ordinary browsing run; anything else asks. */
    SensitiveActions("sensitive_actions"),

    /** Ordinary navigation and page interactions run without asking. */
    AllowWebsiteInteractions("allow_website_interactions");

    companion object {
        val DEFAULT = EveryInteraction

        /** A stored mode; anything unknown or missing is the strictest. */
        fun of(wire: String?): ApprovalMode = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * What an action is, as the approval policy sorts it. Only the first
 * four are website interactions the chat's mode decides; every other
 * kind asks the user in every mode, before any classifier is consulted.
 */
internal enum class ActionKind {
    /** browser_navigate, browser_create_tab. */
    Navigate,

    /**
     * A click, typing, a key, a selection or a scroll that doesn't, by the
     * page's markup, send a form, nor type into a password or one-time-code field.
     */
    PageInteraction,

    /** One that does: asks in "sensitive actions" without asking the model (desktop). */
    FormSubmission,

    /**
     * Typing or a key into a password or one-time-code field: asks in
     * "sensitive actions" without asking the model, which is only given
     * a character count and the page's label for the field (phase 1's
     * card always flagged these). Such a field is one the page marks
     * (type password, autocomplete password or one-time-code), masks with
     * CSS, or a one-character box grouped with a one-time-code one (a
     * split code's later boxes); see `isCredential` in page.js. A field
     * the page gives no such sign of goes to the model's check.
     */
    Credential,

    /** Answering a page's alert, confirm or prompt (desktop asks these in every mode). */
    Dialog,

    /** A control that opens a file chooser. */
    Upload,

    /** A link that downloads. */
    Download,

    /**
     * A wallet transfer (`wallet_transfer`). It asks on its own sheet
     * ([AgentTransfers]) in every mode and never comes through
     * [ApprovalPolicy]; listed so it reads as always asking.
     */
    Transfer,

    /**
     * A Swarm publication (`swarm_publish`, #597). It asks on its own
     * card ([SwarmPublishTools]) in every mode and never comes through
     * [ApprovalPolicy]; [BrowserToolset.kindOf] maps it here only so it
     * reads as always asking.
     */
    Publish,

    // Not yet tools on Android; listed so they can't arrive as interactions.
    NodeWrite,
    DomainTransaction,
    PageTool,
    Mcp,
}

/** Why a relaxed mode put an action to the user, as its card says. */
internal sealed interface ApprovalCheck {
    /** The model judged it consequential or uncertain: its own [summary]. */
    data class Judged(val summary: String) : ApprovalCheck

    /** It sends a form, which isn't left to the model. */
    data object SendsForm : ApprovalCheck

    /** It types into a password or one-time-code field, which isn't left to the model. */
    data object Credential : ApprovalCheck

    /** The check gave no usable answer (an error, a timeout, a reply in the wrong shape). */
    data object NoAnswer : ApprovalCheck

    /**
     * The mode would let it run, but its tab isn't on screen: taking the
     * screen (switching tabs, closing the chat or a panel) needs the
     * user's tap, so it asks instead. In either relaxed mode.
     */
    data object NeedsScreen : ApprovalCheck
}

/** The policy's answer: run without a card, or ask the user. */
internal sealed interface PolicyDecision {
    data object Proceed : PolicyDecision

    /** [check]: why "sensitive actions" asks, for the card; null in the other modes ([ApprovalCheck.NeedsScreen] is added later, by the toolset). */
    data class Ask(val check: ApprovalCheck? = null) : PolicyDecision
}

/**
 * Whether an action asks the user (#595). The single place the mode is
 * read: [BrowserToolset] asks it before every card.
 */
internal object ApprovalPolicy {
    /** The kinds that ask in every mode. */
    fun alwaysAsks(kind: ActionKind): Boolean = when (kind) {
        ActionKind.Navigate, ActionKind.PageInteraction, ActionKind.FormSubmission, ActionKind.Credential -> false
        ActionKind.Dialog, ActionKind.Upload, ActionKind.Download, ActionKind.Transfer, ActionKind.Publish,
        ActionKind.NodeWrite, ActionKind.DomainTransaction, ActionKind.PageTool, ActionKind.Mcp,
        -> true
    }

    /**
     * [mode] is read when asked and again after the classifier answers
     * (it can take seconds, and the user may have switched back to
     * asking every time meanwhile). [classify] is only called in
     * "sensitive actions", for a website interaction that isn't a form
     * submission or typing into a password or one-time-code field; anything it throws but a cancellation reads as uncertain.
     */
    suspend fun decide(
        kind: ActionKind,
        mode: () -> ApprovalMode,
        classify: suspend () -> InteractionClassification,
    ): PolicyDecision {
        if (alwaysAsks(kind)) return PolicyDecision.Ask()
        return when (mode()) {
            ApprovalMode.EveryInteraction -> PolicyDecision.Ask()
            ApprovalMode.AllowWebsiteInteractions -> PolicyDecision.Proceed
            ApprovalMode.SensitiveActions -> {
                if (kind == ActionKind.FormSubmission) return PolicyDecision.Ask(ApprovalCheck.SendsForm)
                if (kind == ActionKind.Credential) return PolicyDecision.Ask(ApprovalCheck.Credential)
                val check = try {
                    classify()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InteractionIntentClassifier.uncertain(ClassifierReason.PROVIDER_ERROR)
                }
                val why = if (InteractionIntentClassifier.isFallback(check)) ApprovalCheck.NoAnswer else ApprovalCheck.Judged(check.summary)
                when {
                    !InteractionIntentClassifier.mayProceed(check) -> PolicyDecision.Ask(why)
                    mode() == ApprovalMode.EveryInteraction -> PolicyDecision.Ask(why)
                    else -> PolicyDecision.Proceed
                }
            }
        }
    }

    /**
     * What the model is told about the chat's mode, with every request
     * (desktop's `approvalPolicyPrompt`; navigation asks here in
     * "every interaction", which desktop leaves free).
     */
    fun prompt(mode: ApprovalMode): String {
        val policy = when (mode) {
            ApprovalMode.EveryInteraction ->
                "Freedom asks the user before every navigation, page interaction and dialog answer. Reading pages " +
                    "and managing this task's tabs need no approval."
            ApprovalMode.SensitiveActions ->
                "Freedom independently classifies the intended consequence of each navigation and website " +
                    "interaction. Ordinary browsing may proceed, while consequential or uncertain interactions ask " +
                    "the user; so does every form submission, all typing into a password or one-time-code field, and every dialog answer. For every browser_click, " +
                    "browser_type, browser_select, browser_press, and browser_scroll call, include a brief literal " +
                    "intent describing what you expect that exact interaction to accomplish. Dialog answers, " +
                    "uploads, downloads, wallet requests, Swarm and Radicle publishing, signing, messages and writes, " +
                    "and other privileged capabilities keep their separate " +
                    "Freedom approval boundaries. Never claim that the user personally approved an automatic decision."
            ApprovalMode.AllowWebsiteInteractions ->
                "Freedom allows ordinary navigation and website interactions without asking each time. Dialog " +
                    "answers, uploads, downloads, wallet requests, Swarm and Radicle publishing, signing, messages and " +
                    "writes, and other privileged capabilities keep their " +
                    "separate Freedom approval boundaries."
        }
        val screen = if (mode == ApprovalMode.EveryInteraction) "" else " A page interaction (click, type, select, " +
            "press, scroll) allowed without asking runs only in a tab already on screen; otherwise the user is asked " +
            "to switch to it. A navigation allowed without asking runs in its tab whether or not it is on screen."
        return "Freedom approval policy (this chat's approval mode is ${mode.wire}; the user can change it at any " +
            "time, and this line always shows the current one): $policy$screen Freedom enforces this policy; do not claim " +
            "broader authority."
    }
}

/** A chat's approval mode and classifier, as its browser tools see them. */
internal interface ChatApprovals {
    val mode: ApprovalMode

    /** The intent classifier on the chat's running turn's model; uncertain when there's none. */
    suspend fun classifyInteraction(input: InteractionInput): InteractionClassification

    /** Phase 1's behaviour: every interaction asks, nothing is classified. */
    object AskEveryTime : ChatApprovals {
        override val mode = ApprovalMode.EveryInteraction

        override suspend fun classifyInteraction(input: InteractionInput) =
            InteractionIntentClassifier.uncertain(ClassifierReason.UNAVAILABLE)
    }
}
