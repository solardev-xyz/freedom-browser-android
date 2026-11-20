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
 * three are website interactions the chat's mode decides; every other
 * kind asks the user in every mode, before any classifier is consulted.
 */
internal enum class ActionKind {
    /** browser_navigate, browser_create_tab. */
    Navigate,

    /** A click, typing, a key, a selection or a scroll that doesn't, by the page's markup, send a form. */
    PageInteraction,

    /** One that does: asks in "sensitive actions" without asking the model (desktop). */
    FormSubmission,

    /** Answering a page's alert, confirm or prompt (desktop asks these in every mode). */
    Dialog,

    /** A control that opens a file chooser. */
    Upload,

    /** A link that downloads. */
    Download,

    // Not yet tools on Android; listed so they can't arrive as interactions.
    Transfer,
    Publish,
    NodeWrite,
    DomainTransaction,
    PageTool,
    Mcp,
}

/** The policy's answer: run without a card, or ask the user. */
internal sealed interface PolicyDecision {
    data object Proceed : PolicyDecision

    /** [check]: the classifier's view, when one was asked, for the card to show. */
    data class Ask(val check: InteractionClassification? = null) : PolicyDecision
}

/**
 * Whether an action asks the user (#595). The single place the mode is
 * read: [BrowserToolset] asks it before every card.
 */
internal object ApprovalPolicy {
    /** The kinds that ask in every mode. */
    fun alwaysAsks(kind: ActionKind): Boolean = when (kind) {
        ActionKind.Navigate, ActionKind.PageInteraction, ActionKind.FormSubmission -> false
        ActionKind.Dialog, ActionKind.Upload, ActionKind.Download, ActionKind.Transfer, ActionKind.Publish,
        ActionKind.NodeWrite, ActionKind.DomainTransaction, ActionKind.PageTool, ActionKind.Mcp,
        -> true
    }

    /**
     * [mode] is read when asked and again after the classifier answers
     * (it can take seconds, and the user may have switched back to
     * asking every time meanwhile). [classify] is only called in
     * "sensitive actions", for a website interaction that isn't a form
     * submission; anything it throws but a cancellation reads as uncertain.
     */
    suspend fun decide(
        kind: ActionKind,
        mode: () -> ApprovalMode,
        classify: suspend () -> InteractionClassification,
        formSummary: () -> String = { "Submit this form." },
    ): PolicyDecision {
        if (alwaysAsks(kind)) return PolicyDecision.Ask()
        return when (mode()) {
            ApprovalMode.EveryInteraction -> PolicyDecision.Ask()
            ApprovalMode.AllowWebsiteInteractions -> PolicyDecision.Proceed
            ApprovalMode.SensitiveActions -> {
                if (kind == ActionKind.FormSubmission) {
                    return PolicyDecision.Ask(InteractionIntentClassifier.consequential(formSummary()))
                }
                val check = try {
                    classify()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InteractionIntentClassifier.uncertain(ClassifierReason.PROVIDER_ERROR)
                }
                when {
                    !InteractionIntentClassifier.mayProceed(check) -> PolicyDecision.Ask(check)
                    mode() == ApprovalMode.EveryInteraction -> PolicyDecision.Ask(check)
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
                    "the user; so does every form submission and every dialog answer. For every browser_click, " +
                    "browser_type, browser_select, browser_press, and browser_scroll call, include a brief literal " +
                    "intent describing what you expect that exact interaction to accomplish. Dialog answers, " +
                    "uploads, downloads, wallet requests, and other privileged capabilities keep their separate " +
                    "Freedom approval boundaries. Never claim that the user personally approved an automatic decision."
            ApprovalMode.AllowWebsiteInteractions ->
                "Freedom allows ordinary navigation and website interactions without asking each time. Dialog " +
                    "answers, uploads, downloads, wallet requests, and other privileged capabilities keep their " +
                    "separate Freedom approval boundaries."
        }
        return "Freedom approval policy (this chat's approval mode is ${mode.wire}; the user can change it at any " +
            "time, and this line always shows the current one): $policy Freedom enforces this policy; do not claim " +
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
