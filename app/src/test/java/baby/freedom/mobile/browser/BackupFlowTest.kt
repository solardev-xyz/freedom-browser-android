package baby.freedom.mobile.browser

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guided backup flow (#421): the three-word check, and that only a passed check records the backup. */
class BackupFlowTest {
    private val words = (1..24).map { "word$it" }

    /** Picks the chip holding [word], or any chip that doesn't if [wrong]. */
    private fun BackupFlowState.tap(word: String, wrong: Boolean = false): Boolean {
        val chips = check!!.chips
        val index = chips.indices.first { (chips[it] == word) != wrong && it !in picked }
        return pick(index)
    }

    private fun BackupFlowState.expected(slot: Int) = check!!.expected(slot)

    @Test
    fun `the check asks three ascending positions, from the asked words and six others of the phrase`() {
        repeat(50) { seed ->
            val check = BackupCheck(words, Random(seed))
            assertEquals(3, check.positions.size)
            assertEquals(check.positions.sorted(), check.positions)
            assertEquals(3, check.positions.distinct().size)
            assertEquals(9, check.chips.size)
            assertEquals(9, check.chips.distinct().size)
            assertTrue(check.chips.all { it in words })
            check.positions.forEach { assertTrue(words[it] in check.chips) }
        }
    }

    @Test
    fun `a short or repetitive phrase still gives every asked word a chip`() {
        val twelve = words.take(12)
        assertEquals(9, BackupCheck(twelve, Random(1)).chips.size)
        // Repeats are allowed in BIP-39: decoys are never another copy of an asked word.
        val repeats = List(12) { if (it % 2 == 0) "abandon" else "ability$it" }
        repeat(20) { seed ->
            val check = BackupCheck(repeats, Random(seed))
            val asked = check.positions.map { repeats[it] }
            asked.forEach { word -> assertTrue(word in check.chips) }
            val decoys = check.chips.toMutableList().apply { asked.forEach { remove(it) } }
            assertTrue(decoys.none { it in asked })
            // One chip per asked position: a word asked twice has two.
            asked.distinct().forEach { word ->
                assertEquals(asked.count { it == word }, check.chips.count { it == word })
            }
        }
    }

    @Test
    fun `a word asked at two positions can be picked for both`() {
        val repeats = List(12) { if (it % 2 == 0) "abandon" else "ability$it" }
        val seed = (0 until 1000).first { seed ->
            val c = BackupCheck(repeats, Random(seed))
            c.positions.count { repeats[it] == "abandon" } >= 2
        }
        val flow = BackupFlowState(startWithIntro = false, needsCheck = true, random = Random(seed))
        flow.revealed(repeats)
        assertTrue(flow.writtenDown())
        assertFalse(flow.tap(flow.expected(0)))
        assertFalse(flow.tap(flow.expected(1)))
        assertTrue(flow.tap(flow.expected(2)))
    }

    @Test
    fun `revealing the words records nothing, only a passed check does`() = runBlocking {
        var marked = 0
        val flow = BackupFlowState(startWithIntro = true, needsCheck = true, random = Random(7))
        assertEquals(BackupStep.INTRO, flow.step)
        assertFalse(flow.revealRequested)
        flow.showWords()
        // The intro's button reveals at once; the words page says when it has started.
        assertTrue(flow.revealRequested)
        flow.revealStarted()
        assertFalse(flow.revealRequested)
        flow.revealed(words)
        assertEquals(0, marked)
        assertTrue(flow.writtenDown())
        assertEquals(BackupStep.CHECK, flow.step)

        // A wrong word names the slot and fills nothing.
        assertFalse(flow.tap(flow.expected(0), wrong = true))
        assertEquals(flow.check!!.positions[0] + 1, flow.wrongWord)
        assertTrue(flow.picked.isEmpty())
        assertFalse(flow.passed)

        assertFalse(flow.tap(flow.expected(0)))
        assertNull(flow.wrongWord)
        assertFalse(flow.tap(flow.expected(1)))
        assertFalse(flow.passed)
        // Not passed yet: saving refuses, and markBackedUp isn't reached.
        assertTrue(runCatching { flow.save { marked++ } }.isFailure)
        assertEquals(0, marked)

        assertTrue(flow.tap(flow.expected(2)))
        assertTrue(flow.passed)
        flow.save { marked++ }
        assertEquals(1, marked)
        assertEquals(BackupStep.DONE, flow.step)
        assertNull(flow.words)
    }

    @Test
    fun `the words must be picked in the asked order`() {
        val flow = BackupFlowState(startWithIntro = false, needsCheck = true, random = Random(3))
        flow.revealed(words)
        flow.writtenDown()
        // Word for slot 2 tapped first: wrong for slot 1.
        assertFalse(flow.tap(flow.expected(2)))
        assertEquals(flow.check!!.positions[0] + 1, flow.wrongWord)
        assertTrue(flow.picked.isEmpty())
    }

    @Test
    fun `a failed save keeps the passed check to try again`() = runBlocking {
        val flow = BackupFlowState(startWithIntro = false, needsCheck = true, random = Random(5))
        flow.revealed(words)
        flow.writtenDown()
        (0 until 3).forEach { flow.tap(flow.expected(it)) }
        assertTrue(runCatching { flow.save { error("disk full") } }.isFailure)
        assertTrue(flow.passed)
        assertEquals(BackupStep.CHECK, flow.step)
        var marked = 0
        flow.save { marked++ }
        assertEquals(1, marked)
    }

    @Test
    fun `hiding or backgrounding drops the words and any check under way`() {
        val flow = BackupFlowState(startWithIntro = true, needsCheck = true, random = Random(9))
        flow.showWords()
        flow.revealed(words)
        flow.writtenDown()
        flow.tap(flow.expected(0))
        flow.hide()
        assertNull(flow.words)
        assertNull(flow.check)
        assertTrue(flow.picked.isEmpty())
        assertEquals(BackupStep.WORDS, flow.step)
        // Nothing to check against until they're shown again.
        assertFalse(flow.writtenDown())
    }

    @Test
    fun `see the words again starts the check over with the words still shown`() {
        val flow = BackupFlowState(startWithIntro = false, needsCheck = true, random = Random(11))
        flow.revealed(words)
        flow.writtenDown()
        flow.tap(flow.expected(0))
        flow.backToWords()
        assertEquals(BackupStep.WORDS, flow.step)
        assertEquals(words, flow.words)
        assertNull(flow.check)
        assertTrue(flow.writtenDown())
        assertTrue(flow.picked.isEmpty())
    }

    @Test
    fun `back steps check to words to intro, then leaves`() {
        val guided = BackupFlowState(startWithIntro = true, needsCheck = true, random = Random(1))
        guided.showWords()
        guided.revealed(words)
        guided.writtenDown()
        assertTrue(guided.back())
        assertEquals(BackupStep.WORDS, guided.step)
        assertTrue(guided.back())
        assertEquals(BackupStep.INTRO, guided.step)
        assertNull(guided.words)
        assertFalse(guided.back())

        val direct = BackupFlowState(startWithIntro = false, needsCheck = true, random = Random(1))
        assertFalse(direct.back())
    }

    @Test
    fun `an already backed-up phrase is only shown, with no check`() {
        val flow = BackupFlowState(startWithIntro = false, needsCheck = false, random = Random(1))
        assertEquals(BackupStep.WORDS, flow.step)
        flow.revealed(words)
        assertFalse(flow.writtenDown())
        assertEquals(BackupStep.WORDS, flow.step)
    }
}
