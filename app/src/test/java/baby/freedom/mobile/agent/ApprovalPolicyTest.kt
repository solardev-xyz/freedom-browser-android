package baby.freedom.mobile.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval modes (#595): what each mode lets run without a card, and
 * that the always-ask kinds ask in every mode, before any classifier is
 * consulted, even one that would call them ordinary.
 */
class ApprovalPolicyTest {
    private val ordinary = InteractionClassification(InteractionKind.ORDINARY, 1.0, "Open the result.", emptyList())

    private class Counting(var answer: InteractionClassification) {
        var calls = 0
        suspend fun classify(): InteractionClassification {
            calls++
            return answer
        }
    }

    private val alwaysAsk = listOf(
        ActionKind.Dialog, ActionKind.Upload, ActionKind.Download, ActionKind.Transfer, ActionKind.Publish,
        ActionKind.NodeWrite, ActionKind.DomainTransaction, ActionKind.PageTool, ActionKind.Mcp,
    )

    @Test
    fun `the always-ask list asks in every mode without consulting the classifier`() = runBlocking {
        assertEquals(alwaysAsk.toSet(), ActionKind.entries.filter { ApprovalPolicy.alwaysAsks(it) }.toSet())
        for (mode in ApprovalMode.entries) for (kind in alwaysAsk) {
            val check = Counting(ordinary)
            val d = ApprovalPolicy.decide(kind, { mode }, check::classify)
            assertEquals("$mode $kind", PolicyDecision.Ask(), d)
            assertEquals("$mode $kind", 0, check.calls)
        }
    }

    @Test
    fun `every interaction asks for everything, and asks no model`() = runBlocking {
        for (kind in ActionKind.entries) {
            val check = Counting(ordinary)
            assertEquals(PolicyDecision.Ask(), ApprovalPolicy.decide(kind, { ApprovalMode.EveryInteraction }, check::classify))
            assertEquals(0, check.calls)
        }
    }

    @Test
    fun `allow website interactions runs navigation, interactions and form submissions without a card`() = runBlocking {
        for (kind in listOf(ActionKind.Navigate, ActionKind.PageInteraction, ActionKind.FormSubmission, ActionKind.Credential)) {
            val check = Counting(InteractionIntentClassifier.uncertain())
            assertEquals(PolicyDecision.Proceed, ApprovalPolicy.decide(kind, { ApprovalMode.AllowWebsiteInteractions }, check::classify))
            assertEquals(0, check.calls)
        }
    }

    @Test
    fun `sensitive actions runs only what the classifier confidently calls ordinary`() = runBlocking {
        val mode = { ApprovalMode.SensitiveActions }
        for (kind in listOf(ActionKind.Navigate, ActionKind.PageInteraction)) {
            assertEquals(PolicyDecision.Proceed, ApprovalPolicy.decide(kind, mode, Counting(ordinary)::classify))
            val low = ordinary.copy(confidence = 0.84)
            assertEquals(PolicyDecision.Ask(ApprovalCheck.Judged(low.summary)), ApprovalPolicy.decide(kind, mode, Counting(low)::classify))
            val unsure = ordinary.copy(uncertainties = listOf("which account?"))
            assertEquals(PolicyDecision.Ask(ApprovalCheck.Judged(unsure.summary)), ApprovalPolicy.decide(kind, mode, Counting(unsure)::classify))
            val sends = InteractionClassification(InteractionKind.CONSEQUENTIAL, 0.99, "Sends the message.", emptyList())
            assertEquals(PolicyDecision.Ask(ApprovalCheck.Judged(sends.summary)), ApprovalPolicy.decide(kind, mode, Counting(sends)::classify))
            // Malformed output reaches the policy as the parser's fallback: no answer, never the model's word.
            val malformed = InteractionIntentClassifier.parse("""{"kind":"ordinary","confidence":"1"}""")
            assertEquals(PolicyDecision.Ask(ApprovalCheck.NoAnswer), ApprovalPolicy.decide(kind, mode, Counting(malformed)::classify))
        }
    }

    @Test
    fun `a form submission asks in sensitive actions without asking the model`() = runBlocking {
        val check = Counting(ordinary)
        val d = ApprovalPolicy.decide(ActionKind.FormSubmission, { ApprovalMode.SensitiveActions }, check::classify)
        assertEquals(0, check.calls)
        assertEquals(PolicyDecision.Ask(ApprovalCheck.SendsForm), d)
    }

    @Test
    fun `typing a credential asks in sensitive actions without asking the model`() = runBlocking {
        val check = Counting(ordinary)
        val d = ApprovalPolicy.decide(ActionKind.Credential, { ApprovalMode.SensitiveActions }, check::classify)
        assertEquals(0, check.calls)
        assertEquals(PolicyDecision.Ask(ApprovalCheck.Credential), d)
    }

    @Test
    fun `a classifier that throws asks`() = runBlocking {
        val d = ApprovalPolicy.decide(ActionKind.PageInteraction, { ApprovalMode.SensitiveActions }, { throw IllegalStateException("boom") })
        assertEquals(PolicyDecision.Ask(ApprovalCheck.NoAnswer), d)
    }

    @Test
    fun `switching to every interaction while the check runs asks`() = runBlocking {
        var mode = ApprovalMode.SensitiveActions
        val d = ApprovalPolicy.decide(ActionKind.PageInteraction, { mode }, classify = {
            mode = ApprovalMode.EveryInteraction
            ordinary
        })
        assertEquals(PolicyDecision.Ask(ApprovalCheck.Judged(ordinary.summary)), d)
    }

    @Test
    fun `a stored mode that isn't known reads as every interaction`() {
        assertEquals(ApprovalMode.EveryInteraction, ApprovalMode.DEFAULT)
        for (m in ApprovalMode.entries) assertEquals(m, ApprovalMode.of(m.wire))
        assertEquals(ApprovalMode.EveryInteraction, ApprovalMode.of(null))
        assertEquals(ApprovalMode.EveryInteraction, ApprovalMode.of("yolo"))
        assertEquals(ApprovalMode.EveryInteraction, ApprovalMode.of("ALLOW_WEBSITE_INTERACTIONS"))
    }

    @Test
    fun `the model is told the mode, and that what's privileged still asks`() {
        for (m in ApprovalMode.entries) {
            val p = ApprovalPolicy.prompt(m)
            assertTrue(p.contains(m.wire))
            assertTrue(p.contains("do not claim broader authority"))
        }
        assertTrue(ApprovalPolicy.prompt(ApprovalMode.SensitiveActions).contains("include a brief literal intent"))
        for (m in listOf(ApprovalMode.SensitiveActions, ApprovalMode.AllowWebsiteInteractions)) {
            assertTrue(ApprovalPolicy.prompt(m).contains("Dialog answers, uploads, downloads, wallet requests"))
        }
    }
}
