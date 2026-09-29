package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.PublisherKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Swarm's chunk formats (#120) against vectors captured from desktop's
 * bee-js — the same fixtures iOS pins in `SwarmSOCTests` and
 * `FeedTopicTests` — so a chunk, SOC or feed written here is the one
 * desktop and iOS read.
 */
class SwarmChunksTest {
    private fun hex(s: String) = s.hexToBytesOrNull()!!

    @Test
    fun `feed identifiers match bee-js`() {
        val cases = listOf(
            Triple("f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5", 0L, "ad1043721a8277e6f91f5ce59ee34dfb15ed1439db7f9ec2886731657ef9a74c"),
            Triple("f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5", 1L, "b3fc50019aae7a30f129abbcd35c4856fa13d8a3adc5345be2e583cddcfa494a"),
            Triple("f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5", 42L, "7ccde3b72c22a62e14cec01afd2ec75731f29b43fda9c0ca4b2523e87879306f"),
            Triple("f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5", 65535L, "cbca7ad1e237b0575b68371de92b32fdf6c6268fbe5b98eb9edd40d700039d47"),
            Triple("f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5", 4_294_967_295L, "853d443e3faa69248d0f0914b05dc569cfa8ed3fc3fe99e612edcaa45ef6f1a0"),
            Triple("aa".repeat(32), 0L, "5a233843ec4d7c91e238a5cd1304f014219085405d84f0e8080cc896b7f5b6dc"),
            Triple("aa".repeat(32), 42L, "1d32924566a1fd4edd493577d8578305d20345ba3d98a58d7e100fdffda1be45"),
            Triple("00".repeat(32), 1L, "1cf395c0cd58ef248dc39cbdb14948280ffdfcc9ac3aedbd8cb5d1d4bb9997be"),
            Triple("00".repeat(32), 4_294_967_295L, "cd6adfa685cf671cd4571f84b852a1e2e3877100da966960c8d3740f31cdb8a2"),
        )
        for ((topic, index, id) in cases) {
            assertEquals("index $index", id, SwarmChunks.feedIdentifier(hex(topic), index).swarmHex())
        }
    }

    @Test
    fun `CAC spans and addresses match bee-js at every payload-size edge`() {
        val cases = listOf(
            Triple("00", "0100000000000000", "fe60ba40b87599ddfb9e8947c1c872a4a1a5b56f7d1b80f0a646005b38db52a5"),
            Triple("aa", "0100000000000000", "8e420243e6112a2221fb4ae9a750c8083b16efeb014316bbbc1a6442728d749e"),
            Triple("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff", "2000000000000000", "815ee9eaca26f06a5d6f6eca88b017e99470e10afea2a60c3f254a53fca52034"),
            Triple("aa".repeat(33), "2100000000000000", "9d2e6c08c6f3cd675cac9fdf98db7b316b7331369ca2145440eae1c9f9a26964"),
            Triple("bb".repeat(64), "4000000000000000", "7ed3c89bb1a550ccde170343ef5ac1f687be419b53b5d5c6c018c7b13186e758"),
            Triple("cd".repeat(4095), "ff0f000000000000", "5fca91d10afa77a8d4bab9731dc36bbc920a3a0902c7946eb9b6b0d4bbf9b25b"),
            Triple("ef".repeat(4096), "0010000000000000", "f84b10fe9ad559cd73fe651a0521aa925d794a8affb8696bb02324bc78c5bcc6"),
        )
        for ((payload, span, address) in cases) {
            val cac = SwarmChunks.cac(hex(payload))
            assertEquals(span, cac.span.swarmHex())
            assertEquals("length ${payload.length / 2}", address, cac.address.swarmHex())
        }
        assertTrue(runCatching { SwarmChunks.cac(ByteArray(0)) }.isFailure)
        assertTrue(runCatching { SwarmChunks.cac(ByteArray(4097)) }.isFailure)
    }

    @Test
    fun `SOC addresses match bee-js`() {
        val owner = hex("19e7e376e7c213b7e7e7e46cc70a5dd086daff2a")
        val cases = listOf(
            "ad1043721a8277e6f91f5ce59ee34dfb15ed1439db7f9ec2886731657ef9a74c" to "752edabe6e3e38eeb8ce973cdb20649ea912494addc19a3c18e84c59f04203f2",
            "b3fc50019aae7a30f129abbcd35c4856fa13d8a3adc5345be2e583cddcfa494a" to "5960576fd6e06545b3723319a538d6529d4b8ce7cbc13bc3d266937d0ec226e0",
            "7ccde3b72c22a62e14cec01afd2ec75731f29b43fda9c0ca4b2523e87879306f" to "2b7432331e80b75f326cbc02e198879c0478000d1c8b38d39d1f70ab46f6f76a",
            "cbca7ad1e237b0575b68371de92b32fdf6c6268fbe5b98eb9edd40d700039d47" to "b5e1806046f8daf1650fe334570105174bb9a6dba187e8818ae30166dc780739",
        )
        for ((id, address) in cases) assertEquals(address, SwarmChunks.socAddress(hex(id), owner).swarmHex())
    }

    @Test
    fun `feed topics match bee-js Topic fromString over desktop's origin slash name`() {
        val cases = listOf(
            Triple("ens://foo.eth", "posts", "f757932a4cab2ba386df56c48cff6abd0515ed9e4ca464d44facb942bf1790b5"),
            Triple("https://app.example.com", "updates", "854786e1e3f3ea0b18380554fca413c2e2a350005bd788be3ac65e5462207d98"),
            Triple("bzz://1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", "log", "f11a3eee60dc5d82336eb78af5f39fd6aee1182186e65da402801c40363c6903"),
            Triple("ens://foo.eth", "x".repeat(64), "c152c9f94ff5d3a3b4123cefa946b9968861f713603cc7e295b1b57b81eaf17c"),
            Triple("ens://föö.eth", "posts", "2226bfbe0287a712265b6f708c5b177767db94352137dd31e104b5c39140afbb"),
            Triple("ens://foo.eth", "日記", "08f699fc5e141e7b9ed82843ef8a812a0ad505695b012baf7452f25012cd2c45"),
        )
        for ((origin, name, topic) in cases) assertEquals(topic, SwarmChunks.topic("$origin/$name").swarmHex())
    }

    @Test
    fun `a signed SOC parses back to its owner, and a tampered one doesn't`() {
        val key = hex("11".repeat(32))
        val owner = PublisherKeys.address(key)
        val identifier = hex("ab".repeat(32))
        val soc = SwarmChunks.sign(identifier, SwarmChunks.cac("hello swarm".toByteArray()), key)
        assertEquals(owner.lowercase(), "0x" + soc.owner.swarmHex())
        assertEquals(65, soc.signature.size)
        val wire = identifier + soc.signature + soc.cac.data()
        val parsed = SwarmChunks.parseSoc(soc.address, wire)
        assertNotNull(parsed)
        assertArrayEquals("hello swarm".toByteArray(), parsed!!.cac.payload)
        assertArrayEquals(soc.owner, parsed.owner)
        // Another payload recovers another signer: not the chunk at this address.
        val tampered = wire.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }
        assertNull(SwarmChunks.parseSoc(soc.address, tampered))
        // The right bytes at the wrong address aren't it either.
        assertNull(SwarmChunks.parseSoc(ByteArray(32), wire))
    }

    @Test
    fun `a CAC read back must hash to the reference asked for`() {
        val cac = SwarmChunks.cac("abc".toByteArray())
        assertNotNull(SwarmChunks.parseCac(cac.address, cac.data()))
        assertNull(SwarmChunks.parseCac(ByteArray(32), cac.data()))
        assertNull(SwarmChunks.parseCac(cac.address, cac.span))
    }

    @Test
    fun `spans are 8 bytes little-endian and can exceed a chunk`() {
        val big = SwarmChunks.cac("root".toByteArray(), span = 1_000_000UL)
        assertEquals("40420f0000000000", big.span.swarmHex())
        assertEquals(1_000_000UL, big.spanValue)
        assertEquals(ULong.MAX_VALUE, SwarmChunks.spanOf(SwarmChunks.spanBytes(ULong.MAX_VALUE)))
    }

    @Test
    fun `a reference update is a big-endian timestamp then the reference`() {
        val ref = hex("cd".repeat(32))
        val payload = SwarmChunks.referenceUpdate(ref, 0x0102030405L)
        assertEquals("0000000102030405" + "cd".repeat(32), payload.swarmHex())
    }
}
