package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainAccessPolicy
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.data.ChainStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chain page's wording (#108): read steps, trust lines, add errors. */
class ChainDetailPageTest {
    private val wiredRpcsOnly: (ChainSource) -> Boolean = { it == ChainSource.QUORUM || it == ChainSource.DIRECT }

    @Test
    fun stepsListOnlyWiredTiersAndSayWhenAQuorumCantForm() {
        val eth = readSteps(BuiltInChains.ETHEREUM, ChainAccessPolicy.default(1), wiredRpcsOnly)
        assertEquals(2, eth.size)
        assertEquals(
            "RPC quorum: 2 of the first 3 RPCs (each from a different provider) " +
                "must give the same answer — verified",
            eth[0],
        )
        assertEquals(
            "Direct RPC: otherwise the first RPC that answers — unverified, or marked as yours if it's one of your RPCs",
            eth[1],
        )
        val lonely = Chain(id = 5, name = "X", symbol = "X", rpcUrls = listOf("https://a.example"))
        assertEquals(
            "RPC quorum: skipped, needs RPCs from at least 2 providers (this chain has 1)",
            readSteps(lonely, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
        )
        val withMine = lonely.copy(userRpcUrls = listOf("https://mine.example"))
        assertEquals(
            "RPC quorum: 2 of the first 2 RPCs (each from a different provider, yours among them) " +
                "must give the same answer — verified",
            readSteps(withMine, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
        )
        val sameProvider = lonely.copy(userRpcUrls = listOf("https://a.example/?key=1"))
        assertEquals(
            "RPC quorum: skipped, needs RPCs from at least 2 providers (this chain has 1)",
            readSteps(sameProvider, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
        )
        val allMine = lonely.copy(userRpcUrls = listOf("https://m1.example", "https://m2.example", "https://m3.example"))
        assertEquals(
            "RPC quorum: 2 of the first 3 RPCs (each from a different provider, all of them yours) " +
                "must give the same answer — verified",
            readSteps(allMine, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
        )
        assertEquals(
            "P2P light client: a proof checked on this device",
            readSteps(BuiltInChains.ETHEREUM, ChainAccessPolicy.default(1)) { true }[0],
        )
    }

    private fun trust(level: ChainTrust.Level, source: ChainSource, agreed: List<String>, dissented: List<String> = emptyList()) =
        ChainTrust(level, source, agreed, dissented, agreed + dissented, 3, 2, null)

    @Test
    fun trustLinesNameWhoAgreedAndWhoDidnt() {
        assertEquals(
            "Verified: 2 of 3 RPCs agreed (a.example, b.example) · c.example answered differently",
            trustSummary(trust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, listOf("a.example", "b.example"), listOf("c.example"))),
        )
        assertEquals(
            "Unverified: only a.example's word · b.example, c.example answered differently",
            trustSummary(trust(ChainTrust.Level.UNVERIFIED, ChainSource.DIRECT, listOf("a.example"), listOf("b.example", "c.example"))),
        )
        assertEquals(
            "From your RPC mine.example",
            trustSummary(trust(ChainTrust.Level.USER_CONFIGURED, ChainSource.DIRECT, listOf("mine.example"))),
        )
        assertEquals(
            "Verified by the P2P light client",
            trustSummary(trust(ChainTrust.Level.VERIFIED, ChainSource.MYOTIS, listOf("myotis-p2p"))),
        )
    }

    @Test
    fun yourRpcsNoteSaysWhoIsInTheQuorumForTheRpcsYouHave() {
        val policy = ChainAccessPolicy.default(1).sanitized(1)
        val eth = BuiltInChains.ETHEREUM
        val none = userRpcsNote(eth, policy)
        assert("up to 3 RPCs from different providers at the same time" in none && "all the chain's public RPCs" in none) { none }
        // No RPC of yours: nothing claims a first seat for one, and nothing reads as timing.
        assert("yours first" !in none && "first" !in none) { none }

        val one = userRpcsNote(eth.copy(userRpcUrls = listOf("https://mine.example")), policy)
        assert("in the quorum, but not alone" in one && "public RPCs fill the other 2 seats and see the same read" in one) { one }
        assert("isn't in the quorum" !in one) { one }
        assert("takes a seat ahead of public RPCs" in one && "first" !in one) { one }

        // Three providers of the user's own fill every seat: no public RPC is in the quorum.
        val three = eth.copy(userRpcUrls = listOf("https://m1.example", "https://m2.example", "https://m3.example"))
        val full = userRpcsNote(three, policy)
        assert("quorum is only yours" in full && "see the same read" !in full && "not alone" !in full) { full }

        // A fourth, and a second spelling of this device, take no seat — and the note says so.
        val extra = three.copy(userRpcUrls = three.userRpcUrls + "https://m4.example")
        assert("1 RPC of yours isn't in the quorum" in userRpcsNote(extra, policy)) { userRpcsNote(extra, policy) }
        val loopbackTwice = eth.copy(userRpcUrls = listOf("http://localhost:8545", "http://127.0.0.1:8545"))
        val lb = userRpcsNote(loopbackTwice, policy)
        assert("public RPCs fill the other 2 seats" in lb && "1 RPC of yours isn't in the quorum" in lb) { lb }
        val two = eth.copy(userRpcUrls = listOf("https://m1.example", "http://localhost:8545"))
        assert("public RPCs fill the other seat and see the same read" in userRpcsNote(two, policy)) { userRpcsNote(two, policy) }

        // No quorum can form on a chain with one provider: yours is simply asked first.
        val lonely = Chain(id = 5, name = "X", symbol = "X", rpcUrls = listOf("https://a.example"))
        val lonelyNote = userRpcsNote(lonely, ChainAccessPolicy.default(5))
        assert("One you add is asked before the public RPCs" in lonelyNote) { lonelyNote }

        val directOnly = ChainAccessPolicy(listOf(ChainSource.DIRECT), listOf(ChainSource.DIRECT))
        assert("One you add is asked before the public RPCs" in userRpcsNote(eth, directOnly)) { userRpcsNote(eth, directOnly) }
        val directMine = userRpcsNote(eth.copy(userRpcUrls = listOf("https://mine.example")), directOnly)
        assert("It's asked before the public RPCs" in directMine) { directMine }
        // Several of the user's own RPCs are "they", on both no-quorum paths.
        val directTwo = userRpcsNote(eth.copy(userRpcUrls = listOf("https://m1.example", "https://m2.example")), directOnly)
        assert("They're asked before the public RPCs" in directTwo && "It's asked" !in directTwo) { directTwo }
        // Two keyed URLs of the chain's only provider: still one provider, no quorum.
        val sameProvider = userRpcsNote(
            lonely.copy(userRpcUrls = listOf("https://a.example/key1", "https://a.example/key2")),
            ChainAccessPolicy.default(5),
        )
        assert("They're asked before the public RPCs" in sameProvider && "It's asked" !in sameProvider) { sameProvider }
    }

    @Test
    fun everyRefusedAddSaysWhy() {
        assertNull(userRpcAddError(ChainStore.RpcAddResult.ADDED))
        for (r in ChainStore.RpcAddResult.entries - ChainStore.RpcAddResult.ADDED) {
            assert(!userRpcAddError(r).isNullOrBlank()) { r }
        }
    }
}
