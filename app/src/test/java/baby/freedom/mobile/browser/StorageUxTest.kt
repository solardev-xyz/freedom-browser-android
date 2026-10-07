package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage, chequebook and Swarm prompt surface (#425, wallet audit
 * W41–W47): defaults, presets, card titles, which actions show, and copy.
 */
class StorageUxTest {
    private val id = "ab".repeat(32)
    private fun xbzz(s: String) = BigDecimal(s).movePointRight(16).toBigIntegerExact()

    @Test
    fun `Fund opens on the same size and duration as Buy, never the smallest stamp (W41)`() {
        assertEquals(StorageChoice(DEFAULT_STAMP_DEPTH, DEFAULT_STAMP_DAYS), DEFAULT_STORAGE_CHOICE)
        assertEquals(20, DEFAULT_STORAGE_CHOICE.depth)
        assertEquals(30L, DEFAULT_STORAGE_CHOICE.days)
        assertTrue(DEFAULT_STORAGE_CHOICE.depth != STAMP_DEPTHS.first())
        assertTrue(DEFAULT_STORAGE_CHOICE.days != STAMP_BUY_DAYS.first())
        // It's one of the presets the surface shows, so it opens selected, and a duration on offer.
        assertTrue(STORAGE_PRESETS.any { it.depth == DEFAULT_STORAGE_CHOICE.depth })
        assertTrue(DEFAULT_STORAGE_CHOICE.days in STAMP_BUY_DAYS)
    }

    @Test
    fun `three presets of about 600 MB, 7 GB and 43 GB, all sizes the node takes (W42)`() {
        assertEquals(listOf(20, 22, 24), STORAGE_PRESETS.map { it.depth })
        assertTrue(STAMP_DEPTHS.containsAll(STORAGE_PRESETS.map { it.depth }))
        assertEquals(listOf("About 600 MB", "About 7 GB", "About 43 GB"), STORAGE_PRESETS.map { storagePresetLabel(it.depth) })
    }

    @Test
    fun `a storage card is titled by size and time left, the hash kept off it (W45)`() {
        val batch = PostageBatch(id, true, 20, 16, 0, true, 29L * 86_400 + 3 * 3_600)
        assertEquals("629 MB · 29 days 3 hours left", batchTitle(batch))
        assertFalse(batchTitle(batch).contains(shortBatchId(id)))
        assertEquals("629 MB · expired", batchTitle(batch.copy(ttlSeconds = 0)))
        // Unknown time left: the size alone, not a guess.
        assertEquals("629 MB", batchTitle(batch.copy(ttlSeconds = null)))
    }

    @Test
    fun `Extend shows only on the batch the node uploads with, on a node that can spend (W45)`() {
        assertEquals(ExtendAvailability.Offered, extendAvailability(null, id, id))
        assertEquals(ExtendAvailability.OtherActive, extendAvailability(null, "cd".repeat(32), id))
        assertEquals(ExtendAvailability.Checking, extendAvailability(null, null, id))
        assertEquals(ExtendAvailability.CantSpend, extendAvailability("node off", id, id))
        assertEquals(ExtendAvailability.CantSpend, extendAvailability("node off", null, id))
    }

    @Test
    fun `short of funds, the primary action becomes Add funds (W43)`() {
        assertEquals(BuyAction.AddFunds, buyAction(cantSpend = null, sufficientFunds = false))
        assertEquals(BuyAction.Buy, buyAction(cantSpend = null, sufficientFunds = true))
        // No quote yet: Buy, disabled until one comes.
        assertEquals(BuyAction.Buy, buyAction(cantSpend = null, sufficientFunds = null))
        // A node that can't spend at all says why instead; adding funds wouldn't help.
        assertEquals(BuyAction.Buy, buyAction(cantSpend = "set up a wallet", sufficientFunds = false))
    }

    @Test
    fun `dismissing a pending stamp asks first unless it surely bought nothing (W45)`() {
        assertTrue(dismissNeedsConfirm(superseded = false))
        assertFalse(dismissNeedsConfirm(superseded = true))
    }

    @Test
    fun `deposit presets are named by what they buy (W44)`() {
        assertEquals(
            listOf(
                "Less than a minute of HD video",
                "About 4 minutes of HD video",
                "About 38 minutes of HD video",
                "About 3 hours of HD video",
                "About 6 hours of HD video",
            ),
            DEPOSIT_PRESETS_PLUR.map(::depositPresetLabel),
        )
    }

    @Test
    fun `one deposit preset is picked to start, never more than the node holds when it can pay one (W44)`() {
        assertEquals(xbzz("0.1"), defaultDepositPreset(null))
        assertEquals(xbzz("0.1"), defaultDepositPreset(xbzz("3")))
        assertEquals(xbzz("0.01"), defaultDepositPreset(xbzz("0.05")))
        assertEquals(xbzz("0.001"), defaultDepositPreset(xbzz("0.0034")))
        // Can't pay any: still 0.1, and the page says what the node holds.
        assertEquals(xbzz("0.1"), defaultDepositPreset(BigInteger.ZERO))
        assertTrue(defaultDepositPreset(xbzz("0.05")) in DEPOSIT_PRESETS_PLUR)
    }

    @Test
    fun `the deposit pick waits for the balance, then stays put when the balance changes (W44)`() {
        // Balance not read yet: nothing highlighted.
        assertEquals(null, depositPick(null, null))
        // First read: the default for it, picked once.
        val first = depositPick(null, xbzz("3"))
        assertEquals(xbzz("0.1").toString(), first)
        // A later, smaller read doesn't move it; neither does the user's own pick.
        assertEquals(first, depositPick(first, xbzz("0.05")))
        assertEquals(xbzz("1").toString(), depositPick(xbzz("1").toString(), xbzz("0.05")))
    }

    @Test
    fun `the chequebook headline is the spendable credit, an upper bound said as such (W44)`() {
        val state = ChequebookState(address = "0x" + "37".repeat(20), balancePlur = xbzz("0.011"), availablePlur = xbzz("0.0082"))
        assertEquals("0.0082 xBZZ", creditHeadline(state))
        assertEquals("At most 0.011 xBZZ", creditHeadline(state.copy(availablePlur = xbzz("0.011"), availableUpperBound = true)))
        assertEquals("No chequebook yet", creditHeadline(ChequebookState(address = "")))
        assertEquals("Checking…", creditHeadline(ChequebookState()))
    }

    @Test
    fun `Swarm prompts carry no PSS, GSOC or SOC acronyms (W47)`() {
        val asks = listOf(
            SwarmAsk.Message("https://a.example", SwarmAsk.Message.Op.Subscribe, SwarmAsk.Message.Kind.Pss, "t", 0, grant = true),
            SwarmAsk.Message("https://a.example", SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Pss, "t", 3),
            SwarmAsk.Message("https://a.example", SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Gsoc, "t", 3),
        )
        val acronym = Regex("\\b(PSS|GSOC|SOC)\\b|Single Owner")
        for (ask in asks) {
            val copy = swarmPromptCopy(ask)
            for (text in listOf(copy.title, copy.request, copy.warning, copy.approve, copy.always.orEmpty())) {
                assertFalse("'$text' names an acronym", acronym.containsMatchIn(text))
            }
        }
        // The messaging grant leads with what it costs the user's privacy.
        assertTrue(swarmPromptCopy(asks[0]).warning.startsWith("This site will be able to recognise you"))
    }

    @Test
    fun `a public-room message is never called private, its sheet says anyone in the room reads it`() {
        val room = SwarmAsk.Message("https://a.example", SwarmAsk.Message.Op.Send, SwarmAsk.Message.Kind.Gsoc, "t", 3)
        val private = room.copy(kind = SwarmAsk.Message.Kind.Pss)
        // GSOC uploads the payload in the clear under a topic-derived key.
        val roomWarning = swarmPromptCopy(room).warning
        assertTrue(roomWarning, roomWarning.startsWith("Anyone who knows this room can read this message"))
        assertFalse(roomWarning, roomWarning.contains("see that"))
        // PSS is encrypted to the recipient's key: only that one was sent shows.
        val privateWarning = swarmPromptCopy(private).warning
        assertTrue(privateWarning, privateWarning.startsWith("Only the recipient can read this message"))
    }

    @Test
    fun `the buy page's spend reason still blocks Add funds' way out on a device-only node`() {
        val deviceOnly = NodeInfo(status = NodeStatus.Running, accountAddress = "0xabc", walletIdentity = false, lightMode = true)
        val reason = stampSpendBlockedReason(deviceOnly)
        assertEquals(BuyAction.Buy, buyAction(reason, sufficientFunds = false))
    }
}
