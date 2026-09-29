package baby.freedom.mobile.wallet.ledger

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.EthSigning
import baby.freedom.mobile.wallet.HdKeys
import baby.freedom.mobile.wallet.Mnemonic
import baby.freedom.mobile.wallet.NodeIdentity
import baby.freedom.mobile.wallet.Secp256k1Keys
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ledger's Ethereum app over BLE (#142): the APDUs and frames are the
 * ones `@ledgerhq/hw-app-eth` and `hw-transport-ble` build, byte for byte
 * (`resources/ledger/hw-app-eth-vectors.json`, from `gen-vectors.js`),
 * status words become the errors the wallet shows, and a signature is
 * only used if it recovers to the account over the digest this app made.
 */
class LedgerProtocolTest {
    private val path = "44'/60'/0'/0/0"
    private val vectors = JSONObject(javaClass.getResourceAsStream("/ledger/hw-app-eth-vectors.json")!!.reader().readText())

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun List<ByteArray>.hex() = map { it.toHex() }

    @Test
    fun `transactions are chunked as hw-app-eth chunks them, EIP-155 tail never split`() {
        val keys = vectors.keys().asSequence().filter { it.startsWith("tx_") }.toList()
        assertTrue(keys.size > 60)
        for (k in keys) {
            val v = vectors.getJSONObject(k)
            val payload = v.getString("payload").hexToBytes()
            assertEquals(k, v.getJSONArray("apdus").strings(), LedgerApdus.signTransaction(path, payload).hex())
        }
    }

    @Test
    fun `the EIP-155 tail is found in a legacy payload and not in a typed one`() {
        val legacy = vectors.getJSONObject("tx_legacy").getString("payload").hexToBytes()
        // …8080 6480 80: chainId 100 then two empty strings, the last three bytes.
        assertEquals(legacy.size - 3, LedgerApdus.legacyVrsOffset(legacy))
        assertEquals(0, LedgerApdus.legacyVrsOffset(vectors.getJSONObject("tx_eip1559").getString("payload").hexToBytes()))
    }

    @Test
    fun `personal messages are chunked as hw-app-eth chunks them`() {
        for (k in listOf("personal_short", "personal_long")) {
            val v = vectors.getJSONObject(k)
            val message = v.getString("message").hexToBytes()
            assertEquals(k, v.getJSONArray("apdus").strings(), LedgerApdus.signPersonal(path, message).hex())
        }
        // hw-app-eth sends nothing for an empty message; the app takes a zero length.
        assertEquals(listOf("e008000019" + "058000002c8000003c800000000000000000000000" + "00000000"), LedgerApdus.signPersonal(path, ByteArray(0)).hex())
    }

    @Test
    fun `typed data is streamed as hw-app-eth streams it with no filters`() {
        val v = vectors.getJSONObject("eip712")
        val data = Eip712.parse(v.getJSONObject("typed").toString())
        val mine = LedgerApdus.signEip712Full(path, data).hex()
        // hw-app-eth first asks the app's version (B0 01): not needed to sign.
        val theirs = v.getJSONArray("apdus").strings().drop(1)
        // Struct definitions come in the types' key order, which JSON leaves open: compare them per struct.
        fun defs(apdus: List<String>) = apdus.takeWhile { it.startsWith("e01a") }
            .fold(mutableListOf<MutableList<String>>()) { acc, a -> if (a.startsWith("e01a0000")) acc.add(mutableListOf(a)) else acc.last().add(a); acc }
            .toSet()
        assertEquals(defs(theirs), defs(mine))
        assertEquals(theirs.dropWhile { it.startsWith("e01a") }, mine.dropWhile { it.startsWith("e01a") })
    }

    @Test
    fun `typed data the device can't be given as it would hash it is refused, not guessed`() {
        val missing = Eip712.parse(
            """{"types":{"EIP712Domain":[{"name":"name","type":"string"}],"A":[{"name":"b","type":"B"}],"B":[{"name":"x","type":"uint8"}]},
               "primaryType":"A","domain":{"name":"n"},"message":{}}""",
        )
        try {
            LedgerApdus.signEip712Full(path, missing)
            fail("a missing struct hashes as zero, which the device can't be told")
        } catch (e: LedgerApdus.Unencodable) {
            // expected: the hashed form is used instead
        }
    }

    @Test
    fun `negative and wide integers are encoded as the device takes them`() {
        assertEquals("fffb", LedgerApdus.twosComplement(BigInteger.valueOf(-5), 2).toHex())
        assertEquals("ff".repeat(32), LedgerApdus.twosComplement(BigInteger.ONE.negate(), 32).toHex())
        assertEquals("00", LedgerApdus.unsigned(BigInteger.ZERO).toHex())
        assertEquals("80", LedgerApdus.unsigned(BigInteger.valueOf(128)).toHex())
    }

    @Test
    fun `APDUs are framed for BLE as hw-transport-ble frames them`() {
        for (mtu in listOf(20, 23, 153)) {
            val v = vectors.getJSONObject("ble_$mtu")
            val apdu = v.getString("apdu").hexToBytes()
            val frames = LedgerBleFraming.frames(apdu, mtu)
            assertEquals("mtu $mtu", v.getJSONArray("frames").strings(), frames.hex())
            assertTrue(frames.all { it.size <= mtu })
            // …and read back whole from the same frames.
            val reader = LedgerBleFraming.Reader()
            val back = frames.map { reader.add(it) }
            assertTrue(back.dropLast(1).all { it == null })
            assertArrayEquals(apdu, back.last())
        }
    }

    @Test
    fun `a notification that can't belong to the answer fails it`() {
        val frames = LedgerBleFraming.frames(ByteArray(40) { it.toByte() }, 20)
        fun rejects(vararg fs: ByteArray) {
            val r = LedgerBleFraming.Reader()
            try {
                fs.forEach { r.add(it) }
                fail("accepted")
            } catch (e: LedgerBleFraming.BadFrame) {
                // expected
            }
        }
        rejects(frames[1])
        rejects(frames[0], frames[2])
        rejects(byteArrayOf(0x07, 0, 0, 0, 1, 9))
        rejects(byteArrayOf(0x05, 0, 0, 0, 1, 9, 9))
    }

    @Test
    fun `the device's frame size comes from its answer to the MTU query`() {
        assertEquals(153, LedgerBleFraming.mtuFrom(byteArrayOf(0x08, 0, 0, 0, 0, 153.toByte())))
        assertNull(LedgerBleFraming.mtuFrom(byteArrayOf(0x05, 0, 0, 0, 0, 153.toByte())))
        assertNull(LedgerBleFraming.mtuFrom(byteArrayOf(0x08, 0, 0)))
        assertEquals(listOf("0800000000"), listOf(LedgerBleFraming.MTU_QUERY.toHex()))
    }

    @Test
    fun `status words become the errors desktop names`() {
        val cases = mapOf(
            0x5515 to LedgerException.Kind.LOCKED,
            0x6982 to LedgerException.Kind.LOCKED,
            0x6985 to LedgerException.Kind.REJECTED,
            0x5501 to LedgerException.Kind.REJECTED,
            0x6a80 to LedgerException.Kind.INVALID_DATA,
            0x6511 to LedgerException.Kind.APP_NOT_OPEN,
            0x6d00 to LedgerException.Kind.APP_NOT_OPEN,
            0x6e00 to LedgerException.Kind.APP_NOT_OPEN,
            0x6e01 to LedgerException.Kind.APP_NOT_OPEN,
            0x6f00 to LedgerException.Kind.UNKNOWN,
        )
        for ((sw, kind) in cases) {
            try {
                LedgerApdus.ok(byteArrayOf((sw shr 8).toByte(), sw.toByte()))
                fail("0x%04x accepted".format(sw))
            } catch (e: LedgerException) {
                assertEquals("0x%04x".format(sw), kind, e.kind)
                assertEquals(sw, (e.cause as LedgerException.StatusWord).sw)
            }
        }
        // "Incorrect data" means Blind signing is off only for an APDU that needs it.
        try {
            LedgerApdus.ok(byteArrayOf(0x6a, 0x80.toByte()), blindSigning = true)
            fail("0x6a80 accepted")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.BLIND_SIGNING, e.kind)
        }
        assertEquals(LedgerException.Kind.LOCKED, LedgerException.kindForStatus(0x5515, blindSigning = true))
        assertArrayEquals(byteArrayOf(1, 2), LedgerApdus.ok(byteArrayOf(1, 2, 0x90.toByte(), 0)))
    }

    @Test
    fun `paths are the device's BIP-32 encoding`() {
        assertEquals("058000002c8000003c800000000000000000000000", LedgerApdus.path(path).toHex())
        assertEquals("048000002c8000003c8000000000000007", LedgerApdus.path("44'/60'/0'/7").toHex())
        for (bad in listOf("44'/x/0", "44'/60'/-1", "44'/60'/4294967296", "")) {
            try {
                LedgerApdus.path(bad)
                fail(bad)
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    private val seed = Mnemonic.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about").seed()
    private val key = HdKeys.secp256k1(seed, "m/$path")
    private val address = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

    @Test
    fun `an address answer is checked against its public key`() {
        val pub = byteArrayOf(4) + Secp256k1Keys.publicKeyUncompressed(key)
        val text = address.removePrefix("0x").lowercase().toByteArray()
        val answer = byteArrayOf(65) + pub + byteArrayOf(text.size.toByte()) + text
        assertEquals(address, LedgerApdus.parseAddress(answer))
        val lying = answer.copyOf().also { it[it.size - 1] = '0'.code.toByte() }
        try {
            LedgerApdus.parseAddress(lying)
            fail("an address its key doesn't make")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.UNKNOWN, e.kind)
        }
        assertEquals(address, NodeIdentity.checksum(Keccak256.digest(pub.copyOfRange(1, 65)).copyOfRange(12, 32)))
    }

    @Test
    fun `a signature is used only if it recovers to the account over this app's digest`() {
        val digest = Keccak256.digest("what the phone showed".toByteArray())
        val sig = EthSigning.sign(key, digest)
        val r = HdKeys.to32(sig.r)
        val s = HdKeys.to32(sig.s)
        // The Ledger's v isn't read: any value comes out as the right recovery id.
        val got = Ledger.recover(LedgerSignature(0xee, r, s), digest, address)
        assertEquals(sig.recoveryId, got.recoveryId)
        // A high-s twin is brought back to low-s, as a node requires.
        val highS = HdKeys.to32(Secp256k1Keys.N.subtract(sig.s))
        assertEquals(sig.s, Ledger.recover(LedgerSignature(0, r, highS), digest, address).s)
        for ((d, a) in listOf(Keccak256.digest("something else".toByteArray()) to address, digest to "0x" + "11".repeat(20))) {
            try {
                Ledger.recover(LedgerSignature(0, r, s), d, a)
                fail("used a signature that isn't the account's over the digest")
            } catch (e: LedgerException) {
                assertEquals(LedgerException.Kind.MISMATCH, e.kind)
            }
        }
    }

    /** A Ledger that answers from a script: each APDU in turn, with its answer. */
    private class Scripted(val answers: List<Pair<String?, String>>) : LedgerLink {
        var n = 0
        override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray {
            val (want, answer) = answers[n++]
            if (want != null) assertEquals(want, apdu.toHex().take(want.length))
            return answer.hexToBytes()
        }

        override fun close() = Unit
    }

    @Test
    fun `an Ethereum app too old for field-by-field typed data signs the hashes instead`() = runBlocking {
        val data = Eip712.parse(vectors.getJSONObject("eip712").getJSONObject("typed").toString())
        val sig = "01".repeat(65)
        val link = Scripted(listOf("e01a" to "6d00", "e00c0000" to sig + "9000"))
        val app = LedgerEthApp(link)
        assertEquals("01".repeat(32), app.signTypedData(path, data).r.toHex())
        assertEquals(2, link.n)
        // Any other refusal is the answer: nothing is retried behind the user's back.
        val rejected = LedgerEthApp(Scripted(listOf("e01a" to "6985")))
        try {
            rejected.signTypedData(path, data)
            fail("retried a rejection")
        } catch (e: LedgerException) {
            assertEquals(LedgerException.Kind.REJECTED, e.kind)
        }
    }

    @Test
    fun `incorrect data asks for Blind signing only where Blind signing is what lets it through`() = runBlocking {
        suspend fun kind(block: suspend () -> Unit): LedgerException.Kind = try {
            block()
            error("signed")
        } catch (e: LedgerException) {
            e.kind
        }
        val data = Eip712.parse(vectors.getJSONObject("eip712").getJSONObject("typed").toString())
        val tx = vectors.keys().asSequence().first { it.startsWith("tx_") }
        val payload = vectors.getJSONObject(tx).getString("payload")
        val chunks = LedgerApdus.signTransaction(path, payload.hexToBytes()).size
        assertEquals(
            LedgerException.Kind.BLIND_SIGNING,
            kind { LedgerEthApp(Scripted(List(chunks - 1) { "e004" to "9000" } + ("e004" to "6a80"))).signTransaction(path, payload.hexToBytes()) },
        )
        assertEquals(
            LedgerException.Kind.BLIND_SIGNING,
            kind { LedgerEthApp(Scripted(listOf("e01a" to "6d00", "e00c0000" to "6a80"))).signTypedData(path, data) },
        )
        // Field by field: a struct definition refused is bad data; the first value refused is
        // Blind signing (app-ethereum refuses unfiltered data as it's about to show it), as is the sign step.
        assertEquals(LedgerException.Kind.INVALID_DATA, kind { LedgerEthApp(Scripted(listOf("e01a" to "6a80"))).signTypedData(path, data) })
        val full = LedgerApdus.signEip712Full(path, data)
        assertEquals("e00c0001", full.last().toHex().take(8))
        val valueAt = full.indexOfFirst { it.toHex().startsWith("e01c00ff") }
        assertEquals(
            LedgerException.Kind.BLIND_SIGNING,
            kind { LedgerEthApp(Scripted(List(valueAt) { null to "9000" } + ("e01c00ff" to "6a80"))).signTypedData(path, data) },
        )
        assertEquals(
            LedgerException.Kind.BLIND_SIGNING,
            kind { LedgerEthApp(Scripted(List(full.size - 1) { null to "9000" } + ("e00c0001" to "6a80"))).signTypedData(path, data) },
        )
        assertEquals(LedgerException.Kind.INVALID_DATA, kind { LedgerEthApp(Scripted(listOf("e008" to "6a80"))).signPersonal(path, "hi".toByteArray()) })
        assertEquals(LedgerException.Kind.INVALID_DATA, kind { LedgerEthApp(Scripted(listOf("e002" to "6a80"))).address(path) })
    }

    @Test
    fun `stored Ledger paths are the ones the account picker makes`() {
        for (s in LedgerScheme.entries) for (i in listOf(0, 1, 42)) {
            assertTrue(s.path(i), baby.freedom.mobile.wallet.WalletAccountStore.LEDGER_PATH.matches(s.path(i)))
        }
        assertTrue(!baby.freedom.mobile.wallet.WalletAccountStore.LEDGER_PATH.matches("44'/0'/0'/0/0"))
    }

    @Test
    fun `paired devices are recognised as Ledgers by name`() {
        assertTrue(Ledger.isLedgerName("Nano X 1A2B"))
        assertTrue(Ledger.isLedgerName("Ledger Stax 9F"))
        assertTrue(!Ledger.isLedgerName("Pixel Buds"))
        assertTrue(!Ledger.isLedgerName(null))
    }
}
