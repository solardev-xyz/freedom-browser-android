package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsTrust
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
        KnownEnsNames.record("bzz://ABCdef0123/some/path", "swarm.eth", EnsTrust.ASSUMED)
        assertEquals("swarm.eth", KnownEnsNames.nameFor("abcdef0123"))
        assertEquals("swarm.eth", KnownEnsNames.nameFor("ABCdef0123"))
        assertEquals("bzz", KnownEnsNames.protocolFor("swarm.eth"))
        assertEquals("bzz", KnownEnsNames.protocolFor("SWARM.eth"))
    }

    @Test
    fun `records ipfs cid exactly`() {
        KnownEnsNames.record("ipfs://bafybeigdy/some/path", "vitalik.eth", EnsTrust.ASSUMED)
        assertEquals("vitalik.eth", KnownEnsNames.nameFor("bafybeigdy"))
        assertEquals("ipfs", KnownEnsNames.protocolFor("vitalik.eth"))
    }

    @Test
    fun `records ipns name exactly`() {
        KnownEnsNames.record("ipns://k51qzi5uqu5dk/docs", "docs.eth", EnsTrust.ASSUMED)
        assertEquals("docs.eth", KnownEnsNames.nameFor("k51qzi5uqu5dk"))
        assertEquals("ipns", KnownEnsNames.protocolFor("docs.eth"))
    }

    @Test
    fun `record ignores unknown schemes`() {
        KnownEnsNames.record("https://example.com", "example.eth", EnsTrust.ASSUMED)
        assertNull(KnownEnsNames.nameFor("example.com"))
        assertNull(KnownEnsNames.protocolFor("example.eth"))
    }

    @Test
    fun `forget removes the mapping`() {
        KnownEnsNames.record("bzz://deadbeef", "drop.eth", EnsTrust.ASSUMED)
        assertEquals("drop.eth", KnownEnsNames.nameFor("deadbeef"))
        KnownEnsNames.forget("deadbeef")
        assertNull(KnownEnsNames.nameFor("deadbeef"))
    }

    @Test
    fun `clear wipes everything`() {
        KnownEnsNames.record("bzz://aaa", "a.eth", EnsTrust.ASSUMED)
        KnownEnsNames.record("ipfs://bafy", "b.eth", EnsTrust.ASSUMED)
        KnownEnsNames.clear()
        assertNull(KnownEnsNames.nameFor("aaa"))
        assertNull(KnownEnsNames.nameFor("bafy"))
        assertNull(KnownEnsNames.protocolFor("a.eth"))
        assertNull(KnownEnsNames.protocolFor("b.eth"))
    }

    @Test
    fun `forgetting a name keeps a shared root's mapping for the name still on it`() {
        KnownEnsNames.record("bzz://abcdef0123", "a.eth", EnsTrust.ASSUMED)
        KnownEnsNames.record("bzz://abcdef0123", "b.eth", EnsTrust.ASSUMED)
        assertEquals("b.eth", KnownEnsNames.nameFor("abcdef0123"))
        KnownEnsNames.forgetName("b.eth")
        assertEquals("a.eth", KnownEnsNames.nameFor("abcdef0123"))
        assertEquals("bzz", KnownEnsNames.protocolFor("a.eth"))
        KnownEnsNames.forgetName("a.eth")
        assertNull(KnownEnsNames.nameFor("abcdef0123"))
    }

    @Test
    fun `a name that moves stops naming its old root, unless another name holds it`() {
        KnownEnsNames.record("bzz://aaa", "a.eth", EnsTrust.ASSUMED)
        KnownEnsNames.record("bzz://aaa", "b.eth", EnsTrust.ASSUMED)
        KnownEnsNames.record("bzz://ccc", "c.eth", EnsTrust.ASSUMED)
        KnownEnsNames.record("ipfs://bafy", "c.eth", EnsTrust.ASSUMED)
        assertNull(KnownEnsNames.nameFor("ccc"))
        assertEquals("c.eth", KnownEnsNames.nameFor("bafy"))
        assertEquals("ipfs", KnownEnsNames.protocolFor("c.eth"))
        // b.eth held aaa last; moving b.eth hands aaa back to a.eth.
        KnownEnsNames.record("bzz://bbb", "b.eth", EnsTrust.ASSUMED)
        assertEquals("a.eth", KnownEnsNames.nameFor("aaa"))
        assertEquals("b.eth", KnownEnsNames.nameFor("bbb"))
    }

    @Test
    fun `a shared root names the name recorded there last, whatever the hash order`() {
        // R2-F1: record() used to hand the just-recorded root back to
        // whichever other name hash iteration met first. Try many pairs
        // so the old order-dependent pick can't pass by luck.
        for (i in 0 until 40) {
            KnownEnsNames.clear()
            val first = "n$i-first.eth"
            val last = "n$i-last.eth"
            KnownEnsNames.record("bzz://aaaa", first, EnsTrust.ASSUMED)
            KnownEnsNames.record("bzz://aaaa", last, EnsTrust.ASSUMED)
            assertEquals(last, KnownEnsNames.nameFor("aaaa"))
            // Re-recording the first name takes the root back.
            KnownEnsNames.record("bzz://aaaa", first, EnsTrust.ASSUMED)
            assertEquals(first, KnownEnsNames.nameFor("aaaa"))
        }
    }

    @Test
    fun `a root released by a moving name goes to the most recent other holder`() {
        for (i in 0 until 40) {
            KnownEnsNames.clear()
            val a = "a$i.eth"
            val b = "b$i.eth"
            val c = "c$i.eth"
            KnownEnsNames.record("bzz://aaaa", a, EnsTrust.ASSUMED)
            KnownEnsNames.record("bzz://aaaa", b, EnsTrust.ASSUMED)
            KnownEnsNames.record("bzz://aaaa", c, EnsTrust.ASSUMED)
            KnownEnsNames.record("bzz://bbbb", c, EnsTrust.ASSUMED)
            assertEquals(b, KnownEnsNames.nameFor("aaaa"))
            KnownEnsNames.forgetName(b)
            assertEquals(a, KnownEnsNames.nameFor("aaaa"))
        }
    }
}
