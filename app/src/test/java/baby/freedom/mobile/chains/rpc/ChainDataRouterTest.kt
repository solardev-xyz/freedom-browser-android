package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ChainDataRouter], tier by tier, over a scripted [RpcTransport]. */
class ChainDataRouterTest {
    private val a = "https://a.example"
    private val b = "https://b.example"
    private val c = "https://c.example"
    private val d = "https://d.example"
    private val mine = "https://my-node.example/key123"

    private fun chain(id: Long = 137, rpcs: List<String> = listOf(a, b, c, d), user: List<String> = emptyList()) =
        Chain(id = id, name = "Test", symbol = "T", rpcUrls = rpcs, userRpcUrls = user)

    private fun ok(result: Any?) = JSONObject().put("jsonrpc", "2.0").put("id", 1)
        .put("result", result ?: JSONObject.NULL).toString()

    private fun err(code: Int, message: String, data: String? = null) = JSONObject().put("jsonrpc", "2.0").put("id", 1)
        .put("error", JSONObject().put("code", code).put("message", message).apply { data?.let { put("data", it) } })
        .toString()

    /** Scripted endpoints: url → handler of the request body. Counts and records every call. */
    private class Net {
        val handlers = ConcurrentHashMap<String, suspend (String) -> String>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val bodies = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()

        val transport = RpcTransport { url, body, timeoutMs ->
            calls.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            bodies += body
            val handler = handlers[url] ?: throw IOException("no route to $url")
            try {
                kotlinx.coroutines.withTimeout(timeoutMs) { handler(body) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw RpcTimeoutException("timeout")
            } catch (e: CancellationException) {
                cancelled += url
                throw e
            }
        }

        fun count(url: String) = calls[url]?.get() ?: 0
    }

    private fun router(
        net: Net,
        chains: List<Chain>,
        policy: ((Chain) -> ChainAccessPolicy)? = null,
        sources: Map<ChainSource, VerifiedChainSource> = emptyMap(),
        clock: () -> Long = System::currentTimeMillis,
    ) = ChainDataRouter(
        chains = { chains },
        transport = net.transport,
        verifiedSources = sources,
        policyFor = policy ?: { ChainAccessPolicy.default(it.id) },
        clock = clock,
    )

    // ---- quorum ----

    @Test
    fun quorumVerifiesTwoMatchingAnswers() = runBlocking {
        val net = Net()
        net.handlers[a] = { ok("0x10") }
        net.handlers[b] = { delay(100); ok("0x10") }
        net.handlers[c] = { ok("0x11") }
        val r = router(net, listOf(chain())).request(137, "eth_blockNumber")
        assertEquals("0x10", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(ChainSource.QUORUM, r.trust.source)
        assertEquals(listOf("a.example", "b.example"), r.trust.agreed)
        assertEquals(listOf("c.example"), r.trust.dissented)
        assertEquals(listOf("a.example", "b.example", "c.example"), r.trust.queried)
        assertEquals(3 to 2, r.trust.k to r.trust.m)
        assertEquals("only the first K RPCs are asked", 0, net.count(d))
    }

    @Test
    fun quorumSettlesWithoutWaitingForTheStraggler() = runBlocking {
        val net = Net()
        net.handlers[a] = { ok("0x1") }
        net.handlers[b] = { ok("0x1") }
        net.handlers[c] = { awaitCancellation() }
        val started = System.currentTimeMillis()
        val r = router(net, listOf(chain())).request(137, "eth_blockNumber")
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertTrue(System.currentTimeMillis() - started < 1_000)
        delay(100)
        assertEquals("the straggler is cut off, not left running", listOf(c), net.cancelled.toList())
    }

    @Test
    fun conflictFallsToDirectReusingTheFirstMembersAnswer() = runBlocking {
        val net = Net()
        net.handlers[a] = { delay(50); ok("0x1") }
        net.handlers[b] = { ok("0x2") }
        net.handlers[c] = { ok("0x3") }
        net.handlers[d] = { ok("0x4") }
        val r = router(net, listOf(chain())).request(137, "eth_getBalance", JSONArray().put("0xabc").put("latest"))
        assertEquals("the highest-priority member's answer, not the first to arrive", "0x1", r.result)
        assertEquals(ChainTrust.Level.UNVERIFIED, r.trust.level)
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(listOf("a.example"), r.trust.agreed)
        assertEquals(listOf("b.example", "c.example"), r.trust.dissented)
        assertEquals(listOf(1, 1, 1, 0), listOf(a, b, c, d).map(net::count))
    }

    @Test
    fun failedQuorumLetsDirectAskOnlyTheRestOfThePool() = runBlocking {
        val net = Net()
        net.handlers[a] = { throw IOException("down") }
        net.handlers[b] = { throw IOException("down") }
        net.handlers[c] = { throw IOException("down") }
        net.handlers[d] = { ok("0x7") }
        val r = router(net, listOf(chain())).request(137, "eth_blockNumber")
        assertEquals("0x7", r.result)
        assertEquals(ChainTrust.Level.UNVERIFIED, r.trust.level)
        assertEquals(listOf("d.example"), r.trust.agreed)
        assertEquals(listOf(1, 1, 1, 1), listOf(a, b, c, d).map(net::count))
    }

    @Test
    fun matchingRevertsAreAVerifiedAnswerThatEndsTheWalk() = runBlocking {
        val net = Net()
        net.handlers[a] = { err(3, "execution reverted: nope", "0xdeadbeef") }
        net.handlers[b] = { err(3, "execution reverted", "0xDEADBEEF") }
        net.handlers[c] = { delay(2_000); ok("0x") }
        try {
            router(net, listOf(chain())).request(137, "eth_call", JSONArray().put(JSONObject().put("to", "0x1")))
            fail("expected a revert")
        } catch (e: ChainRpcException.Rpc) {
            assertEquals("0xdeadbeef", e.data?.lowercase())
            assertTrue(e.deterministic)
        }
        assertEquals("direct isn't asked after a deterministic answer", 0, net.count(d))
    }

    @Test
    fun aPoolSmallerThanMSkipsTheQuorum() = runBlocking {
        val net = Net()
        net.handlers[a] = { ok("0x5") }
        val r = router(net, listOf(chain(rpcs = listOf(a)))).request(137, "eth_blockNumber")
        assertEquals(ChainTrust.Level.UNVERIFIED, r.trust.level)
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(1, net.count(a))
    }

    // ---- direct ----

    @Test
    fun directMovesPastNodeErrorsAndReportsTheLastOne() = runBlocking {
        val net = Net()
        net.handlers[a] = { err(-32005, "rate limited") }
        net.handlers[b] = { ok("0x9") }
        val directOnly: (Chain) -> ChainAccessPolicy = { ChainAccessPolicy(listOf(ChainSource.DIRECT), listOf(ChainSource.DIRECT)) }
        val r = router(net, listOf(chain(rpcs = listOf(a, b))), directOnly).request(137, "eth_blockNumber")
        assertEquals("0x9", r.result)

        net.handlers[b] = { err(-32005, "rate limited too") }
        try {
            router(net, listOf(chain(rpcs = listOf(a, b))), directOnly).request(137, "eth_blockNumber")
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals("rate limited too", e.nodeError?.rpcMessage)
            assertTrue(e.failures.single().startsWith("direct: b.example"))
        }
    }

    @Test
    fun directStopsAtADeterministicError() = runBlocking {
        val net = Net()
        net.handlers[a] = { err(-32602, "invalid argument 0") }
        net.handlers[b] = { ok("0x1") }
        try {
            router(net, listOf(chain(rpcs = listOf(a))), null).request(137, "eth_getBalance", JSONArray().put("zz"))
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertEquals(-32602, e.code)
        }
        net.handlers[a] = { err(-32000, "insufficient funds for gas * price + value") }
        try {
            router(net, listOf(chain(rpcs = listOf(a, b))), { ChainAccessPolicy(listOf(ChainSource.DIRECT), listOf(ChainSource.DIRECT)) })
                .request(137, "eth_estimateGas", JSONArray().put(JSONObject()))
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.insufficientFunds)
        }
        assertEquals(0, net.count(b))
    }

    @Test
    fun theUsersOwnRpcIsTriedFirstAndLabelledAsTheirs() = runBlocking {
        val net = Net()
        net.handlers[mine] = { ok("0x42") }
        net.handlers[a] = { ok("0x42") }
        val c = chain(rpcs = listOf(a), user = listOf(mine))
        val quorum = router(net, listOf(c)).request(137, "eth_blockNumber")
        assertEquals("their RPC counts toward the quorum", ChainTrust.Level.VERIFIED, quorum.trust.level)
        assertEquals(listOf("my-node.example", "a.example"), quorum.trust.queried)

        val direct = router(net, listOf(c), { ChainAccessPolicy(listOf(ChainSource.DIRECT), listOf(ChainSource.DIRECT)) })
            .request(137, "eth_blockNumber")
        assertEquals(ChainTrust.Level.USER_CONFIGURED, direct.trust.level)
        assertEquals("host only, never the path (it can hold a key)", listOf("my-node.example"), direct.trust.agreed)
    }

    @Test
    fun aFailedUserRpcStaysAheadOfThePublicOnes() = runBlocking {
        val net = Net()
        var now = 1_000_000L
        var down = true
        net.handlers[mine] = { if (down) throw IOException("restarting") else ok("0x5") }
        listOf(a, b, c).forEach { url -> net.handlers[url] = { ok("0x5") } }
        val r = router(net, listOf(chain(rpcs = listOf(a, b, c), user = listOf(mine))), clock = { now })
        assertEquals(ChainTrust.Level.VERIFIED, r.request(137, "eth_blockNumber").trust.level)
        down = false
        now += 1_000
        val next = r.request(137, "eth_blockNumber")
        assertEquals(
            "one blip doesn't drop the user's own RPC out of the quorum for ten minutes",
            listOf("my-node.example", "a.example", "b.example"),
            next.trust.queried,
        )
        assertEquals(2, net.count(mine))
    }

    @Test
    fun oneProviderIsOneVoteInTheQuorum() = runBlocking {
        val net = Net()
        val sameAsA = "https://a.example/?key=1"
        val sibling = "https://eu.b.example"
        listOf(sameAsA, a, sibling, b, c).forEach { url -> net.handlers[url] = { ok("0x1") } }
        val r = router(net, listOf(chain(rpcs = listOf(a, sibling, b, c), user = listOf(sameAsA))))
            .request(137, "eth_blockNumber")
        assertEquals(listOf("a.example", "eu.b.example", "c.example"), r.trust.queried)
        assertEquals(listOf(1, 0, 1, 0, 1), listOf(sameAsA, a, sibling, b, c).map(net::count))

        val oneOperator = router(net, listOf(chain(rpcs = listOf(a, sameAsA))))
        val lone = oneOperator.request(137, "eth_blockNumber")
        assertEquals("two URLs of one provider never verify each other", ChainTrust.Level.UNVERIFIED, lone.trust.level)
    }

    @Test
    fun theUsersNodeUnderTwoLoopbackSpellingsIsOneVote() = runBlocking {
        val net = Net()
        val local1 = "http://localhost:8545"
        val local2 = "http://127.0.0.1:8545"
        net.handlers[local1] = { ok("0x1") }
        net.handlers[local2] = { ok("0x1") }
        net.handlers[a] = { ok("0x2") }
        net.handlers[b] = { ok("0x2") }
        val eth = chain(rpcs = listOf(a, b), user = listOf(local1, local2))
        assertEquals(listOf(local1, a, b), ChainDataRouter.quorumMembers(listOf(local1, local2, a, b), 3))
        val r = router(net, listOf(eth)).request(137, "eth_blockNumber")
        assertEquals("the public RPCs outvote the one local node", "0x2", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(listOf("localhost:8545", "a.example", "b.example"), r.trust.queried)
        assertEquals(0, net.count(local2))

        val onlyLocal = router(net, listOf(chain(rpcs = listOf(a), user = listOf(local1, local2))))
            .request(137, "eth_blockNumber")
        assertTrue("two loopback spellings never verify each other", onlyLocal.trust.level != ChainTrust.Level.VERIFIED)
    }

    @Test
    fun providersAreTellApartByRegistrableDomainOrAddress() {
        assertEquals("publicnode.com", ChainDataRouter.providerOf("https://ethereum.publicnode.com/?x"))
        assertEquals("publicnode.com", ChainDataRouter.providerOf("https://Ethereum-Rpc.PublicNode.com"))
        for (loopback in listOf(
            "http://localhost:8545", "http://127.0.0.1:8545", "http://127.1.2.3:9000/",
            "http://[::1]:8545", "http://node.localhost:8545", "http://LOCALHOST.:8545",
        )) {
            assertEquals(loopback, ChainDataRouter.LOOPBACK_PROVIDER, ChainDataRouter.providerOf(loopback))
        }
        assertEquals("tracker.example", ChainDataRouter.providerOf("https://127.tracker.example"))
        assertEquals(
            listOf(a, b),
            ChainDataRouter.quorumMembers(listOf(a, "https://x.a.example", b, "https://b.example/2"), 3),
        )
    }

    @Test
    fun aDirectTierNotRightAfterTheQuorumDoesntWaitOnItsCancelledLegs() = runBlocking {
        val net = Net()
        // a and b refuse at once, so the quorum can't form while c is
        // still in flight; c hangs when the quorum asks, answers when asked again.
        net.handlers[a] = { err(-32005, "rate limited") }
        net.handlers[b] = { err(-32005, "rate limited") }
        net.handlers[c] = { if (net.count(c) == 1) awaitCancellation() else ok("0x9") }
        val eth = chain(id = 1, rpcs = listOf(a, b, c))
        val policy = ChainAccessPolicy(
            readOrder = listOf(ChainSource.QUORUM, ChainSource.COLIBRI, ChainSource.DIRECT),
            broadcastOrder = listOf(ChainSource.DIRECT),
        )
        val started = System.currentTimeMillis()
        val r = kotlinx.coroutines.withTimeout(10_000) {
            router(net, listOf(eth), { policy }).request(1, "eth_blockNumber")
        }
        assertEquals("0x9", r.result)
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals("c's cancelled leg never answered, so direct asks it again", 2, net.count(c))
        assertEquals("a and b did answer, so aren't asked twice", listOf(1, 1), listOf(a, b).map(net::count))
    }

    @Test
    fun filtersSkipTheVerifiedTiers() = runBlocking {
        val net = Net()
        listOf(a, b, c, d).forEach { url -> net.handlers[url] = { ok("0x1") } }
        val r = router(net, listOf(chain())).request(137, "eth_newBlockFilter")
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(listOf(1, 0, 0, 0), listOf(a, b, c, d).map(net::count))
    }

    // ---- interactive deadline ----

    @Test
    fun aPageReadStopsWaitingForTheQuorumButKeepsItsAnswers() = runBlocking {
        val net = Net()
        listOf(a, b, c).forEach { url -> net.handlers[url] = { delay(2_600); ok("0x3") } }
        net.handlers[d] = { ok("0x4") }
        val policy: (Chain) -> ChainAccessPolicy = { ChainAccessPolicy(listOf(ChainSource.QUORUM, ChainSource.DIRECT), listOf(ChainSource.DIRECT), timeoutMs = 4_000) }

        val page = router(net, listOf(chain()), policy)
            .request(137, "eth_blockNumber", context = RoutingContext.forPage("https://app.example"))
        assertEquals("the late quorum member serves the direct tier", "0x3", page.result)
        assertEquals(ChainTrust.Level.UNVERIFIED, page.trust.level)
        assertEquals("no second request to a member, none to the rest", listOf(1, 1, 1, 0), listOf(a, b, c, d).map(net::count))

        val wallet = router(net, listOf(chain()), policy).request(137, "eth_blockNumber")
        assertEquals("the wallet's own read waits the full timeout", ChainTrust.Level.VERIFIED, wallet.trust.level)
    }

    @Test
    fun aPageReadsShortWaitDoesntQuarantineASlowButHealthyRpc() = runBlocking {
        val net = Net()
        listOf(a, b, c).forEach { url -> net.handlers[url] = { delay(2_600); ok("0x3") } }
        net.handlers[d] = { ok("0x4") }
        // The quorum isn't directly followed by direct, so its legs aren't kept.
        val policy: (Chain) -> ChainAccessPolicy = {
            ChainAccessPolicy(
                listOf(ChainSource.QUORUM, ChainSource.COLIBRI, ChainSource.DIRECT),
                listOf(ChainSource.DIRECT),
                timeoutMs = 4_000,
            )
        }
        // Colibri is in the order (Ethereum) but not wired: no answer.
        val r = router(net, listOf(chain(id = 1)), policy)
        val page = r.request(1, "eth_blockNumber", context = RoutingContext.forPage("https://app.example"))
        assertEquals("direct asks the first RPC again, with the full timeout", "0x3", page.result)
        assertEquals(listOf(2, 1, 1, 0), listOf(a, b, c, d).map(net::count))

        val wallet = r.request(1, "eth_blockNumber")
        assertEquals(
            "RPCs the page read stopped waiting for aren't quarantined",
            listOf("a.example", "b.example", "c.example"),
            wallet.trust.queried,
        )
        assertEquals(ChainTrust.Level.VERIFIED, wallet.trust.level)
    }

    @Test
    fun routingContextNormalizesOrigins() {
        assertEquals(RoutingContext.WALLET, RoutingContext.forPage(null))
        assertEquals(RoutingContext.WALLET, RoutingContext.forPage("  "))
        assertEquals(RoutingContext.WALLET, RoutingContext.forPage("https://a\u0000b"))
        assertEquals(RoutingContext.WALLET, RoutingContext.forPage("x".repeat(2049)))
        assertEquals("https://app.example", RoutingContext.forPage(" https://app.example ").origin)
    }

    // ---- verified sources ----

    private class FakeSource(
        val available: Boolean = true,
        val answer: suspend () -> ChainDataResult,
    ) : VerifiedChainSource {
        var calls = 0
        override fun isAvailable(chainId: Long) = available
        override suspend fun request(chainId: Long, method: String, params: JSONArray): ChainDataResult {
            calls++
            return answer()
        }
    }

    private val proof = ChainTrust(
        ChainTrust.Level.VERIFIED, ChainSource.MYOTIS, listOf("myotis-p2p"), emptyList(), listOf("myotis-p2p"), 1, 1, 123,
    )

    @Test
    fun aLightClientAnswersFirstWhenWired() = runBlocking {
        val net = Net()
        val myotis = FakeSource { ChainDataResult("0x77", proof) }
        val r = router(net, listOf(BuiltInChains.ETHEREUM), sources = mapOf(ChainSource.MYOTIS to myotis))
            .request(1, "eth_blockNumber")
        assertEquals(ChainSource.MYOTIS, r.trust.source)
        assertEquals(123L, r.trust.block)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun unwiredOrFailingVerifiedSourcesAreSkipped() = runBlocking {
        val net = Net()
        BuiltInChains.ETHEREUM.rpcUrls.forEach { url -> net.handlers[url] = { ok("0x5") } }
        val failing = FakeSource { throw IOException("prover down") }
        val unsynced = FakeSource(available = false) { ChainDataResult("0x0", proof) }
        val r = router(
            net, listOf(BuiltInChains.ETHEREUM),
            sources = mapOf(ChainSource.MYOTIS to unsynced, ChainSource.COLIBRI to failing),
        ).request(1, "eth_blockNumber")
        assertEquals(ChainSource.QUORUM, r.trust.source)
        assertEquals(0, unsynced.calls)
        assertEquals(1, failing.calls)
    }

    @Test
    fun aSlowVerifiedSourceGetsTwoSecondsOnAPageRead() = runBlocking {
        val net = Net()
        BuiltInChains.ETHEREUM.rpcUrls.forEach { url -> net.handlers[url] = { ok("0x5") } }
        val slow = FakeSource { delay(10_000); ChainDataResult("0x0", proof) }
        val started = System.currentTimeMillis()
        val r = router(net, listOf(BuiltInChains.ETHEREUM), sources = mapOf(ChainSource.MYOTIS to slow))
            .request(1, "eth_blockNumber", context = RoutingContext.forPage("web3://app.eth"))
        val took = System.currentTimeMillis() - started
        assertEquals(ChainSource.QUORUM, r.trust.source)
        assertTrue("took $took", took in 1_900..3_500)
    }

    @Test
    fun aRevertFromAVerifiedSourceEndsTheWalk() = runBlocking {
        val net = Net()
        val reverting = FakeSource { throw ChainRpcException.Rpc(3, "execution reverted", "0x08c379a0") }
        try {
            router(net, listOf(BuiltInChains.GNOSIS), sources = mapOf(ChainSource.COLIBRI to reverting))
                .request(100, "eth_call", JSONArray().put(JSONObject()))
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertEquals("0x08c379a0", e.data)
        }
        assertTrue(net.calls.isEmpty())
    }

    // ---- walk-wide ----

    @Test
    fun unknownChainsAndNonReadMethodsAreRefused() = runBlocking {
        val r = router(Net(), listOf(chain()))
        try {
            r.request(999, "eth_blockNumber"); fail()
        } catch (_: ChainRpcException.UnknownChain) {
        }
        try {
            r.request(137, "eth_sendRawTransaction"); fail()
        } catch (_: ChainRpcException.UnsupportedMethod) {
        }
        try {
            r.request(137, "eth_accounts"); fail()
        } catch (_: ChainRpcException.UnsupportedMethod) {
        }
    }

    @Test
    fun everySourceFailingSaysWhyPerTier() = runBlocking {
        val net = Net()
        try {
            router(net, listOf(chain())).request(137, "eth_blockNumber")
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals(2, e.failures.size)
            assertTrue(e.failures[0], e.failures[0].startsWith("quorum:"))
            assertTrue(e.failures[1], e.failures[1].startsWith("direct: d.example"))
            assertNull(e.nodeError)
        }
    }

    @Test
    fun anRpcThatJustFailedMovesToTheBackOfThePool() = runBlocking {
        val net = Net()
        var now = 1_000_000L
        net.handlers[a] = { throw IOException("down") }
        listOf(b, c, d).forEach { url -> net.handlers[url] = { ok("0x1") } }
        val r = router(net, listOf(chain()), clock = { now })
        r.request(137, "eth_blockNumber")
        assertEquals(1, net.count(a))
        val second = r.request(137, "eth_blockNumber")
        assertEquals(listOf("b.example", "c.example", "d.example"), second.trust.queried)
        assertEquals(1, net.count(a))
        now += ChainDataRouter.QUARANTINE_MS
        assertEquals("a.example", r.request(137, "eth_blockNumber").trust.queried.first())
    }

    @Test
    fun everyEndpointGetsTheSameNormalizedBody() = runBlocking {
        val net = Net()
        listOf(a, b, c).forEach { url -> net.handlers[url] = { ok("0x") } }
        val call = JSONObject().put("to", "0x1").put("input", "0xabcd").put("value", "1000").put("gas", 21000)
        router(net, listOf(chain())).request(137, "eth_call", JSONArray().put(call).put("latest"))
        val sent = net.bodies.toSet().single()
        val params = JSONObject(sent).getJSONArray("params").getJSONObject(0)
        assertEquals("0xabcd", params.getString("data"))
        assertEquals("0x3e8", params.getString("value"))
        assertEquals("0x5208", params.getString("gas"))
    }

    // ---- broadcast ----

    private val rawTx = "0x02f86b0180843b9aca00850df8475800825208940000000000000000000000000000000000000001808080c0"
    private val txHash = "0x" + Keccak256.digest(rawTx.hexToBytes()).toHex()

    @Test
    fun broadcastWalksThePoolAndTakesAlreadyKnownAsSent() = runBlocking {
        val net = Net()
        net.handlers[a] = { throw IOException("reset") }
        net.handlers[b] = { err(-32000, "already known") }
        net.handlers[c] = { ok("0xshouldnotbeasked") }
        val r = router(net, listOf(chain(rpcs = listOf(a, b, c)))).broadcast(137, rawTx)
        assertEquals(txHash, r.result)
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(0, net.count(c))
    }

    @Test
    fun broadcastAnswersTheTransactionsOwnHashWhateverTheNodeSays() = runBlocking {
        for (reply in listOf(ok(true), ok("0xdeadbeef"), ok(null))) {
            val net = Net()
            net.handlers[a] = { reply }
            val r = router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            assertEquals(reply, txHash, r.result)
        }
    }

    @Test
    fun broadcastSurfacesTheNodesRejection() = runBlocking {
        val net = Net()
        net.handlers[a] = { err(-32000, "nonce too low") }
        try {
            router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals("nonce too low", e.nodeError?.rpcMessage)
        }
        net.handlers[a] = { err(-32000, "insufficient funds for gas * price + value") }
        try {
            router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.insufficientFunds)
        }
    }

    @Test
    fun anUncertainLightClientBroadcastIsNeverResent() = runBlocking {
        val net = Net()
        BuiltInChains.ETHEREUM.rpcUrls.forEach { url -> net.handlers[url] = { ok(txHash) } }
        val myotis = object : VerifiedChainSource {
            override fun isAvailable(chainId: Long) = true
            override suspend fun request(chainId: Long, method: String, params: JSONArray): ChainDataResult =
                throw IOException()
            override suspend fun broadcast(chainId: Long, rawTransaction: String): String =
                throw ChainRpcException.BroadcastUncertain("devp2p")
        }
        try {
            router(net, listOf(BuiltInChains.ETHEREUM), sources = mapOf(ChainSource.MYOTIS to myotis)).broadcast(1, rawTx)
            fail()
        } catch (_: ChainRpcException.BroadcastUncertain) {
        }
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun broadcastRefusesSomethingThatIsntATransaction() = runBlocking {
        try {
            router(Net(), listOf(chain())).broadcast(137, "hello")
            fail()
        } catch (_: ChainRpcException.InvalidResponse) {
        }
    }
}
