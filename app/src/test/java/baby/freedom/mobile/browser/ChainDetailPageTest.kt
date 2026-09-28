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
            "RPC quorum: 2 of the first 3 RPCs (each from a different provider, yours first) " +
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
            "RPC quorum: 2 of the first 2 RPCs (each from a different provider, yours first) " +
                "must give the same answer — verified",
            readSteps(withMine, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
        )
        val sameProvider = lonely.copy(userRpcUrls = listOf("https://a.example/?key=1"))
        assertEquals(
            "RPC quorum: skipped, needs RPCs from at least 2 providers (this chain has 1)",
            readSteps(sameProvider, ChainAccessPolicy.default(5), wiredRpcsOnly)[0],
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
    fun yourRpcsNoteSaysThePublicRpcsStillSeeReads() {
        val note = userRpcsNote(ChainAccessPolicy.default(1).sanitized(1))
        assert("not alone" in note && "up to 3 RPCs at once" in note && "public RPCs still see your reads" in note) { note }
        val directOnly = ChainAccessPolicy(listOf(ChainSource.DIRECT), listOf(ChainSource.DIRECT))
        assert("asked before the public RPCs" in userRpcsNote(directOnly)) { userRpcsNote(directOnly) }
    }

    @Test
    fun everyRefusedAddSaysWhy() {
        assertNull(userRpcAddError(ChainStore.RpcAddResult.ADDED))
        for (r in ChainStore.RpcAddResult.entries - ChainStore.RpcAddResult.ADDED) {
            assert(!userRpcAddError(r).isNullOrBlank()) { r }
        }
    }
}
