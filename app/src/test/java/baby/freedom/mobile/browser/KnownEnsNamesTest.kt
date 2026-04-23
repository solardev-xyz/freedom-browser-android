package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownEnsNamesTest {

    @After
    fun tearDown() {
        KnownEnsNames.clear()
    }

    @Test
    fun `records bzz hash case-insensitively`() {
        KnownEnsNames.record("bzz://ABCdef0123/some/path", "swarm.eth")
        assertEquals("swarm.eth", KnownEnsNames.nameFor("abcdef0123"))
        assertEquals("swarm.eth", KnownEnsNames.nameFor("ABCdef0123"))
        assertEquals("bzz", KnownEnsNames.protocolFor("swarm.eth"))
        assertEquals("bzz", KnownEnsNames.protocolFor("SWARM.eth"))
    }

    @Test
    fun `records ipfs cid exactly`() {
        KnownEnsNames.record("ipfs://bafybeigdy/some/path", "vitalik.eth")
        assertEquals("vitalik.eth", KnownEnsNames.nameFor("bafybeigdy"))
        assertEquals("ipfs", KnownEnsNames.protocolFor("vitalik.eth"))
    }

    @Test
    fun `records ipns name exactly`() {
        KnownEnsNames.record("ipns://k51qzi5uqu5dk/docs", "docs.eth")
        assertEquals("docs.eth", KnownEnsNames.nameFor("k51qzi5uqu5dk"))
        assertEquals("ipns", KnownEnsNames.protocolFor("docs.eth"))
    }

    @Test
    fun `record ignores unknown schemes`() {
        KnownEnsNames.record("https://example.com", "example.eth")
        assertNull(KnownEnsNames.nameFor("example.com"))
        assertNull(KnownEnsNames.protocolFor("example.eth"))
    }

    @Test
    fun `forget removes the mapping`() {
        KnownEnsNames.record("bzz://deadbeef", "drop.eth")
        assertEquals("drop.eth", KnownEnsNames.nameFor("deadbeef"))
        KnownEnsNames.forget("deadbeef")
        assertNull(KnownEnsNames.nameFor("deadbeef"))
    }

    @Test
    fun `clear wipes everything`() {
        KnownEnsNames.record("bzz://aaa", "a.eth")
        KnownEnsNames.record("ipfs://bafy", "b.eth")
        KnownEnsNames.clear()
        assertNull(KnownEnsNames.nameFor("aaa"))
        assertNull(KnownEnsNames.nameFor("bafy"))
        assertNull(KnownEnsNames.protocolFor("a.eth"))
        assertNull(KnownEnsNames.protocolFor("b.eth"))
    }
}
