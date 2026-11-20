package baby.freedom.mobile.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tab custody, approval binding and the approval card's text (#575). */
class AgentApprovalsTest {
    @Before
    fun setUp() = AgentTabs.clear()

    @After
    fun tearDown() {
        AgentApprovals.withdrawAll()
        AgentTabs.clear()
    }

    private val button = PageTarget("button", "button", "Search")

    private fun approval(id: Long = AgentApprovals.nextId(), session: String = "s", tab: Long = 1, action: AgentAction = AgentAction.Click(button)) =
        AgentApproval(id, session, "call", tab, "example.com", 3, action, intent = null)

    // ---- custody ----

    @Test
    fun `a private tab is never held, and another chat's tab can't be taken`() {
        assertFalse(AgentTabs.hold(1, "a", private = true))
        assertNull(AgentTabs.holder(1))
        assertTrue(AgentTabs.hold(2, "a", private = false))
        assertFalse(AgentTabs.hold(2, "b", private = false))
        assertEquals("a", AgentTabs.holder(2))
        // The same chat taking it again is fine.
        assertTrue(AgentTabs.hold(2, "a", private = false))
    }

    @Test
    fun `a popup from a held tab is held by the same chat, from a user's tab by none`() {
        AgentTabs.hold(1, "a", private = false)
        AgentTabs.popupOpened(openerId = 1, popupId = 5, private = false)
        assertEquals("a", AgentTabs.holder(5))
        AgentTabs.popupOpened(openerId = 9, popupId = 6, private = false)
        assertNull(AgentTabs.holder(6))
    }

    @Test
    fun `taking a tab back, closing it or deleting the chat releases it`() {
        AgentTabs.hold(1, "a", private = false)
        AgentTabs.hold(2, "a", private = false)
        AgentTabs.hold(3, "b", private = false)
        AgentTabs.release(1)
        assertFalse(AgentTabs.isHeld(1))
        AgentTabs.closed(3)
        assertFalse(AgentTabs.isHeld(3))
        AgentTabs.hold(4, "a", private = false)
        AgentTabs.releaseChat("a")
        assertEquals(emptyList<Long>(), AgentTabs.heldBy("a"))
        assertFalse(AgentTabs.isHeld(2))
    }

    // ---- approvals ----

    @Test
    fun `an answer settles only the approval it was shown for`() = runBlocking {
        val first = approval()
        val second = approval()
        val a = async { AgentApprovals.ask(first) }
        val b = async { AgentApprovals.ask(second) }
        yield()
        assertEquals(first.id, AgentApprovals.shown.value?.id)
        AgentApprovals.answer(second.id, approved = false)
        assertEquals(ApprovalOutcome.Declined, b.await())
        assertTrue(a.isActive)
        // A second answer to a settled approval is nothing.
        AgentApprovals.answer(second.id, approved = true)
        AgentApprovals.answer(first.id, approved = true)
        assertEquals(ApprovalOutcome.Approved, a.await())
        assertNull(AgentApprovals.shown.value)
    }

    @Test
    fun `taking the tab back or stopping withdraws an approval, which isn't a decline`() = runBlocking {
        AgentTabs.hold(1, "s", private = false)
        val onTab = async { AgentApprovals.ask(approval(tab = 1)) }
        val other = async { AgentApprovals.ask(approval(tab = 2)) }
        yield()
        AgentTabs.release(1)
        assertEquals(ApprovalOutcome.Withdrawn, onTab.await())
        assertTrue(other.isActive)
        // Stop: the asking turn is cancelled, and the card goes with it.
        other.cancelAndJoin()
        assertNull(AgentApprovals.shown.value)
    }

    @Test
    fun `deleting a chat withdraws its approvals only`() = runBlocking {
        val mine = async { AgentApprovals.ask(approval(session = "a")) }
        val theirs = async { AgentApprovals.ask(approval(session = "b")) }
        yield()
        AgentApprovals.withdrawSession("a")
        assertEquals(ApprovalOutcome.Withdrawn, mine.await())
        assertTrue(theirs.isActive)
        theirs.cancelAndJoin()
    }

    @Test
    fun `an approved action runs only on the tab, document, site and control it was shown for`() {
        val a = approval()
        val now = ApprovalBinding.TabNow(heldBy = "s", private = false, site = "example.com", document = 3)
        assertNull(ApprovalBinding.check(a, now, button))
        assertEquals(ApprovalBinding.Refusal.TabGone, ApprovalBinding.check(a, null, button))
        assertEquals(ApprovalBinding.Refusal.NotHeld, ApprovalBinding.check(a, now.copy(heldBy = null), button))
        assertEquals(ApprovalBinding.Refusal.NotHeld, ApprovalBinding.check(a, now.copy(heldBy = "other"), button))
        assertEquals(ApprovalBinding.Refusal.NotHeld, ApprovalBinding.check(a, now.copy(private = true), button))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now.copy(document = 4), button))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now.copy(site = "evil.example"), button))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now, button.copy(name = "Pay now")))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now, button.copy(target = "https://evil.example/")))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now, button.copy(tag = "a")))
        assertEquals(ApprovalBinding.Refusal.Changed, ApprovalBinding.check(a, now, button.copy(effect = "form_submission")))
        // A page setting the ARIA role from script once shown is the same control.
        assertNull(ApprovalBinding.check(a, now, button.copy(role = "combobox")))
    }

    // ---- the card's text ----

    @Test
    fun `line breaks and controls flatten to one line`() {
        assertEquals("Pay now and confirm", ApprovalText.flatten("Pay\nnow\r\n\tand confirm "))
        assertEquals("a b", ApprovalText.flatten("a\u0000\u0007b"))
        assertEquals("a b", ApprovalText.flatten("  a    b  "))
    }

    @Test
    fun `bidi overrides and invisible characters show as a replacement character`() {
        // "Cancel" written so that it would read as something else.
        assertEquals("�lecnaC", ApprovalText.flatten("‮lecnaC"))
        assertEquals("a�b�c", ApprovalText.flatten("a⁦b​c"))
        assertEquals("x�", ApprovalText.flatten("x﻿"))
        // A supplementary-plane tag character, judged by code point.
        assertEquals("ok�", ApprovalText.flatten("ok󠁁"))
        // A lone surrogate.
        assertEquals("a�", ApprovalText.flatten("a\uD800"))
    }

    @Test
    fun `an emoji's own selector and a few combining marks are kept, a stack of them isn't`() {
        assertEquals("❤️", ApprovalText.flatten("❤️"))
        assertEquals("Tiếng Việt", ApprovalText.flatten("Tiếng Việt"))
        val zalgo = "a" + "́".repeat(6)
        assertEquals("á́́���", ApprovalText.flatten(zalgo))
    }

    @Test
    fun `long text is cut at a code point with an ellipsis`() {
        assertEquals("abc…", ApprovalText.flatten("abcdef", max = 3))
        val emoji = "😀".repeat(5)
        assertEquals("😀😀…", ApprovalText.flatten(emoji, max = 2))
        assertEquals("abc", ApprovalText.flatten("abc", max = 3))
    }
}
