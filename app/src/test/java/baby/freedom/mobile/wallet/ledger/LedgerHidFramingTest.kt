package baby.freedom.mobile.wallet.ledger

import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ledger's APDU-over-USB-HID framing (#319): packets are the ones
 * `@ledgerhq/devices`' `hid-framing` (desktop's `hw-transport-node-hid`)
 * makes, byte for byte (`resources/ledger/hid-framing-vectors.json`, from
 * `gen-hid-vectors.js`), answers are put back together the way it reads
 * them, and a packet that can't belong to the answer fails it.
 */
class LedgerHidFramingTest {
    private val vectors = JSONObject(javaClass.getResourceAsStream("/ledger/hid-framing-vectors.json")!!.reader().readText())

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun hex(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else s.hexToBytes()

    @Test
    fun `APDUs are split into packets as hw-transport-node-hid splits them`() {
        val cases = vectors.getJSONArray("packets")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val apdu = hex(case.getString("apdu"))
            val packets = LedgerHidFraming.packets(apdu)
            assertEquals("APDU of ${apdu.size} bytes", case.getJSONArray("packets").strings(), packets.map { it.toHex() })
            assertTrue(packets.all { it.size == LedgerHidFraming.PACKET })
        }
    }

    @Test
    fun `answers are put back together from their packets, padding dropped`() {
        val cases = vectors.getJSONArray("answers")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val packets = case.getJSONArray("packets").strings().map { hex(it) }
            val reader = LedgerHidFraming.Reader()
            packets.dropLast(1).forEach { assertNull("only the last packet completes the answer", reader.add(it)) }
            assertEquals(case.getString("answer"), reader.add(packets.last())!!.toHex())
        }
    }

    @Test
    fun `an answer round-trips through its own packets`() {
        for (n in listOf(2, 57, 58, 59, 116, 117, 300, 0xffff)) {
            val answer = ByteArray(n) { (it * 7 + 3).toByte() }
            val reader = LedgerHidFraming.Reader()
            var got: ByteArray? = null
            for (p in LedgerHidFraming.packets(answer)) {
                assertNull("nothing after the answer is complete", got)
                got = reader.add(p)
            }
            assertArrayEquals(answer, got)
        }
    }

    @Test
    fun `a packet that can't belong to the answer fails it`() {
        val answer = ByteArray(150) { it.toByte() }
        val packets = LedgerHidFraming.packets(answer)
        assertEquals(3, packets.size)

        fun refused(why: String, vararg sent: ByteArray) {
            val reader = LedgerHidFraming.Reader()
            try {
                sent.forEach { reader.add(it) }
                fail("$why: accepted")
            } catch (_: LedgerHidFraming.BadPacket) {
            }
        }
        // Out of sequence: a skipped packet, a repeated one, an answer that doesn't start at 0.
        refused("skipped packet", packets[0], packets[2])
        refused("repeated packet", packets[0], packets[0])
        refused("not starting at 0", packets[1])
        // Another channel, another tag, too short to have a header.
        refused("other channel", packets[0].copyOf().also { it[1] = 0x02 })
        refused("other tag", packets[0].copyOf().also { it[2] = 0x08 })
        refused("short packet", packets[0].copyOf(4))
        refused("first packet with no length", packets[0].copyOf(6))
    }

    @Test
    fun `plugged-in Ledgers are named by model from their product id as Ledger's own library names them`() {
        val models = vectors.getJSONObject("models")
        for (id in models.keys()) {
            val expected = if (models.isNull(id)) null else models.getString(id).replace(' ', ' ').removePrefix("Ledger ")
            assertEquals("product id 0x$id", expected, LedgerHidFraming.model(id.toInt(16)))
        }
    }
}
