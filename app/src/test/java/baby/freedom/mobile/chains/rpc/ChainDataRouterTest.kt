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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * On the test's virtual clock, quorum legs included (they run in its
     * `backgroundScope`): every leg that can run has run before any time
     * passes, so which answers are in when the quorum decides, and every
     * deadline, come out the same on however slow a runner.
     */
    private fun TestScope.router(
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
        legScope = backgroundScope,
    )

    // ---- quorum ----

    @Test
    fun quorumVerifiesTwoMatchingAnswers() = runTest {
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
    fun quorumSettlesWithoutWaitingForTheStraggler() = runTest {
        val net = Net()
        // a and b agree once c has been asked, so there is a straggler to cut off.
        val cAsked = CompletableDeferred<Unit>()
        net.handlers[a] = { cAsked.await(); ok("0x1") }
        net.handlers[b] = { cAsked.await(); ok("0x1") }
        net.handlers[c] = { cAsked.complete(Unit); awaitCancellation() }
        val r = router(net, listOf(chain())).request(137, "eth_blockNumber")
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals("no (virtual) time spent waiting on c", 0L, currentTime)
        runCurrent()
        assertEquals("the straggler is cut off, not left running", listOf(c), net.cancelled.toList())
    }

    @Test
    fun conflictFallsToDirectReusingTheFirstMembersAnswer() = runTest {
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
    fun failedQuorumLetsDirectAskOnlyTheRestOfThePool() = runTest {
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
    fun matchingRevertsAreAVerifiedAnswerThatEndsTheWalk() = runTest {
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
    fun aPoolSmallerThanMSkipsTheQuorum() = runTest {
        val net = Net()
        net.handlers[a] = { ok("0x5") }
        val r = router(net, listOf(chain(rpcs = listOf(a)))).request(137, "eth_blockNumber")
        assertEquals(ChainTrust.Level.UNVERIFIED, r.trust.level)
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(1, net.count(a))
    }

    // ---- direct ----

    @Test
    fun directMovesPastNodeErrorsAndReportsTheLastOne() = runTest {
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
    fun directStopsAtADeterministicError() = runTest {
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
    fun theUsersOwnRpcIsTriedFirstAndLabelledAsTheirs() = runTest {
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
    fun aFailedUserRpcStaysAheadOfThePublicOnes() = runTest {
        val net = Net()
        val now = AtomicLong(1_000_000L)
        var down = true
        net.handlers[mine] = { if (down) throw IOException("restarting") else ok("0x5") }
        listOf(a, b, c).forEach { url -> net.handlers[url] = { ok("0x5") } }
        val r = router(net, listOf(chain(rpcs = listOf(a, b, c), user = listOf(mine))), clock = { now.get() })
        assertEquals(ChainTrust.Level.VERIFIED, r.request(137, "eth_blockNumber").trust.level)
        down = false
        now.addAndGet(1_000)
        val next = r.request(137, "eth_blockNumber")
        assertEquals(
            "one blip doesn't drop the user's own RPC out of the quorum for ten minutes",
            listOf("my-node.example", "a.example", "b.example"),
            next.trust.queried,
        )
        assertEquals(2, net.count(mine))
    }

    @Test
    fun oneProviderIsOneVoteInTheQuorum() = runTest {
        val net = Net()
        val sameAsA = "https://a.example/?key=1"
        val sibling = "https://eu.b.example"
        // The first two agree only once c has been asked: otherwise their agreement could
        // cut the quorum short before c's leg ever ran, and c would read as never asked.
        val cAsked = CompletableDeferred<Unit>()
        listOf(sameAsA, a, sibling, b).forEach { url -> net.handlers[url] = { cAsked.await(); ok("0x1") } }
        net.handlers[c] = { cAsked.complete(Unit); ok("0x1") }
        val r = router(net, listOf(chain(rpcs = listOf(a, sibling, b, c), user = listOf(sameAsA))))
            .request(137, "eth_blockNumber")
        assertEquals(listOf("a.example", "eu.b.example", "c.example"), r.trust.queried)
        assertEquals(listOf(1, 0, 1, 0, 1), listOf(sameAsA, a, sibling, b, c).map(net::count))

        val oneOperator = router(net, listOf(chain(rpcs = listOf(a, sameAsA))))
        val lone = oneOperator.request(137, "eth_blockNumber")
        assertEquals("two URLs of one provider never verify each other", ChainTrust.Level.UNVERIFIED, lone.trust.level)
    }

    @Test
    fun theUsersNodeUnderTwoLoopbackSpellingsIsOneVote() = runTest {
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
    fun aDirectTierNotRightAfterTheQuorumDoesntWaitOnItsCancelledLegs() = runTest {
        val net = Net()
        // a and b refuse as soon as c has been asked, so the quorum can't form while
        // c is still in flight; c hangs when the quorum asks, answers when asked again.
        // (Refusing before c's leg even started would leave nothing in flight to cancel.)
        val cAsked = CompletableDeferred<Unit>()
        net.handlers[a] = { cAsked.await(); err(-32005, "rate limited") }
        net.handlers[b] = { cAsked.await(); err(-32005, "rate limited") }
        net.handlers[c] = { if (net.count(c) == 1) { cAsked.complete(Unit); awaitCancellation() } else ok("0x9") }
        val eth = chain(id = 1, rpcs = listOf(a, b, c))
        val policy = ChainAccessPolicy(
            readOrder = listOf(ChainSource.QUORUM, ChainSource.COLIBRI, ChainSource.DIRECT),
            broadcastOrder = listOf(ChainSource.DIRECT),
        )
        val r = router(net, listOf(eth), { policy }).request(1, "eth_blockNumber")
        assertEquals("0x9", r.result)
        assertEquals("no (virtual) time spent waiting out c's cancelled leg", 0L, currentTime)
        assertEquals("c's cancelled leg never answered, so direct asks it again", 2, net.count(c))
        assertEquals("a and b did answer, so aren't asked twice", listOf(1, 1), listOf(a, b).map(net::count))
    }

    @Test
    fun filtersSkipTheVerifiedTiers() = runTest {
        val net = Net()
        listOf(a, b, c, d).forEach { url -> net.handlers[url] = { ok("0x1") } }
        val r = router(net, listOf(chain())).request(137, "eth_newBlockFilter")
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(listOf(1, 0, 0, 0), listOf(a, b, c, d).map(net::count))
    }

    // ---- interactive deadline ----

    @Test
    fun aPageReadStopsWaitingForTheQuorumButKeepsItsAnswers() = runTest {
        val net = Net()
        listOf(a, b, c).forEach { url -> net.handlers[url] = { delay(2_600); ok("0x3") } }
        net.handlers[d] = { ok("0x4") }
        val policy: (Chain) -> ChainAccessPolicy = { ChainAccessPolicy(listOf(ChainSource.QUORUM, ChainSource.DIRECT), listOf(ChainSource.DIRECT), timeoutMs = 4_000) }

        val page = router(net, listOf(chain()), policy)
            .request(137, "eth_blockNumber", context = RoutingContext.forPage("https://app.example"))
        assertEquals("the late quorum member serves the direct tier", "0x3", page.result)
        assertEquals("answered as the member did, past the page's deadline", 2_600L, currentTime)
        assertEquals(ChainTrust.Level.UNVERIFIED, page.trust.level)
        assertEquals("no second request to a member, none to the rest", listOf(1, 1, 1, 0), listOf(a, b, c, d).map(net::count))

        val wallet = router(net, listOf(chain()), policy).request(137, "eth_blockNumber")
        assertEquals("the wallet's own read waits the full timeout", ChainTrust.Level.VERIFIED, wallet.trust.level)
    }

    @Test
    fun aPageReadsShortWaitDoesntQuarantineASlowButHealthyRpc() = runTest {
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
    fun aLightClientAnswersFirstWhenWired() = runTest {
        val net = Net()
        val myotis = FakeSource { ChainDataResult("0x77", proof) }
        val r = router(net, listOf(BuiltInChains.ETHEREUM), sources = mapOf(ChainSource.MYOTIS to myotis))
            .request(1, "eth_blockNumber")
        assertEquals(ChainSource.MYOTIS, r.trust.source)
        assertEquals(123L, r.trust.block)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun unwiredOrFailingVerifiedSourcesAreSkipped() = runTest {
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
    fun aSlowVerifiedSourceGetsTwoSecondsOnAPageRead() = runTest {
        val net = Net()
        BuiltInChains.ETHEREUM.rpcUrls.forEach { url -> net.handlers[url] = { ok("0x5") } }
        val slow = FakeSource { delay(10_000); ChainDataResult("0x0", proof) }
        val r = router(net, listOf(BuiltInChains.ETHEREUM), sources = mapOf(ChainSource.MYOTIS to slow))
            .request(1, "eth_blockNumber", context = RoutingContext.forPage("web3://app.eth"))
        assertEquals(ChainSource.QUORUM, r.trust.source)
        assertEquals(ChainDataRouter.INTERACTIVE_DEADLINE_MS, currentTime)
    }

    @Test
    fun aRevertFromAVerifiedSourceEndsTheWalk() = runTest {
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
    fun unknownChainsAndNonReadMethodsAreRefused() = runTest {
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
    fun everySourceFailingSaysWhyPerTier() = runTest {
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
    fun anRpcThatJustFailedMovesToTheBackOfThePool() = runTest {
        val net = Net()
        val now = AtomicLong(1_000_000L)
        val aFailed = kotlinx.coroutines.CompletableDeferred<Unit>()
        net.handlers[a] = { aFailed.complete(Unit); throw IOException("down") }
        // The others answer only after a has failed (and a virtual moment more,
        // by which a's leg has run on and recorded it): agreeing first would
        // cancel a's leg, and a cancelled leg's failure is — rightly — never counted.
        listOf(b, c, d).forEach { url -> net.handlers[url] = { aFailed.await(); kotlinx.coroutines.delay(100); ok("0x1") } }
        val r = router(net, listOf(chain()), clock = { now.get() })
        r.request(137, "eth_blockNumber")
        assertEquals(1, net.count(a))
        val second = r.request(137, "eth_blockNumber")
        assertEquals(listOf("b.example", "c.example", "d.example"), second.trust.queried)
        assertEquals(1, net.count(a))
        now.addAndGet(ChainDataRouter.QUARANTINE_MS)
        assertEquals("a.example", r.request(137, "eth_blockNumber").trust.queried.first())
    }

    @Test
    fun everyEndpointGetsTheSameNormalizedBody() = runTest {
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
    fun broadcastWalksThePoolAndTakesAlreadyKnownAsSent() = runTest {
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
    fun broadcastTakesEveryClientsAlreadyKnownWordingAsSent() = runTest {
        for (wording in listOf("already known", "AlreadyKnown", "known transaction: abc", "Transaction already imported")) {
            val net = Net()
            net.handlers[a] = { err(-32010, wording) }
            val r = router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            assertEquals(wording, txHash, r.result)
        }
    }

    @Test
    fun broadcastAnswersTheTransactionsOwnHashWhateverTheNodeSays() = runTest {
        for (reply in listOf(ok(true), ok("0xdeadbeef"), ok(null))) {
            val net = Net()
            net.handlers[a] = { reply }
            val r = router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            assertEquals(reply, txHash, r.result)
        }
    }

    @Test
    fun broadcastSurfacesTheNodesRejection() = runTest {
        val net = Net()
        net.handlers[a] = { err(-32000, "nonce too low") }
        try {
            router(net, listOf(chain(rpcs = listOf(a)))).broadcast(137, rawTx)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals("nonce too low", e.nodeError?.rpcMessage)
            assertFalse(e.unanswered)
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
    fun aBroadcastWithAnRpcThatNeverAnsweredSaysSoWhateverTheOthersRefused() = runTest {
        for (refusal in listOf(err(-32005, "rate limit exceeded"), err(-32000, "insufficient funds for gas * price + value"))) {
            val net = Net()
            net.handlers[a] = { throw IOException("timed out") }
            net.handlers[b] = { refusal }
            net.handlers[c] = { refusal }
            try {
                router(net, listOf(chain(rpcs = listOf(a, b, c)))).broadcast(137, rawTx)
                fail()
            } catch (e: ChainRpcException.AllSourcesFailed) {
                assertTrue(refusal.toString(), e.unanswered)
                assertNotNull(e.nodeError)
            }
        }
    }

    @Test
    fun anUncertainLightClientBroadcastIsNeverResent() = runTest {
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
    fun broadcastRefusesSomethingThatIsntATransaction() = runTest {
        try {
            router(Net(), listOf(chain())).broadcast(137, "hello")
            fail()
        } catch (_: ChainRpcException.InvalidResponse) {
        }
    }

    // ---- ranked failures (rankError, #273) ----

    /** A walk whose quorum fails with a's query-size refusal and two node errors, d left for direct. */
    private fun Net.rangeCapped() {
        handlers[a] = { err(-32005, "query exceeds max block range 50000") }
        handlers[b] = { err(-32603, "internal error") }
        handlers[c] = { err(-32603, "internal error") }
        handlers[d] = { ok(JSONArray()) }
    }

    private val requestRank: (ChainFailure) -> Int = { f ->
        when {
            f.timeout -> ChainDataRouter.ErrorRank.TIMEOUT
            "max block range" in f.message -> ChainDataRouter.ErrorRank.REQUEST
            "limit" in f.message -> ChainDataRouter.ErrorRank.HINT
            else -> ChainDataRouter.ErrorRank.ENDPOINT
        }
    }

    @Test
    fun aRequestRankedFailureEndsTheWalkAndIsKept() = runTest {
        val net = Net().apply { rangeCapped() }
        try {
            router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals(ChainFailure(-32005, "query exceeds max block range 50000", null, timeout = false), e.kept)
        }
        assertEquals("the direct tier never asks d", 0, net.count(d))
    }

    @Test
    fun aMembersAnswerBeatsARequestRankedFailure() = runTest {
        val net = Net().apply { rangeCapped() }
        net.handlers[c] = { ok(JSONArray()) }
        val r = router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
        assertEquals("[]", r.result.toString())
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(ChainTrust.Level.UNVERIFIED, r.trust.level)
        assertEquals("reused, not asked again, and d never asked", listOf(1, 0), listOf(net.count(c), net.count(d)))
    }

    @Test
    fun aMemberStillInFlightIsWaitedForAfterARequestRankedFailure() = runTest {
        val net = Net().apply { rangeCapped() }
        net.handlers[c] = { delay(1_000); ok(JSONArray()) }
        val r = router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
        assertEquals("[]", r.result.toString())
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(0, net.count(d))
    }

    @Test
    fun aMemberThatFailsAfterTheWaitLeavesTheRequestRankedFailure() = runTest {
        val net = Net().apply { rangeCapped() }
        net.handlers[c] = { delay(1_000); throw IOException("reset") }
        try {
            router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals("query exceeds max block range 50000", e.kept?.message)
        }
        assertEquals("the direct tier still asks no one new", 0, net.count(d))
    }

    @Test
    fun withoutRankErrorNothingIsKeptOrEndsEarly() = runTest {
        val net = Net().apply { rangeCapped() }
        val r = router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()))
        assertEquals("[]", r.result.toString())
        assertEquals(ChainSource.DIRECT, r.trust.source)
        assertEquals(1, net.count(d))
    }

    @Test
    fun aHintIsKeptButLaterSourcesAreStillAsked() = runTest {
        val net = Net()
        net.handlers[a] = { err(-32005, "limit exceeded") }
        net.handlers[b] = { err(-32005, "internal error") }
        net.handlers[c] = { throw IOException("refused") }
        net.handlers[d] = { err(-32601, "method not found") }
        try {
            router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertEquals("a later endpoint-dependent failure never displaces it", "limit exceeded", e.kept?.message)
        }
        assertEquals(1, net.count(d))
    }

    @Test
    fun onlyEndpointFailuresKeepNothing() = runTest {
        val net = Net()
        for (u in listOf(a, b, c, d)) net.handlers[u] = { err(-32603, "internal error") }
        try {
            router(net, listOf(chain())).request(137, "eth_getLogs", JSONArray().put(JSONObject()), rankError = requestRank)
            fail()
        } catch (e: ChainRpcException.AllSourcesFailed) {
            assertNull(e.kept)
        }
    }

    @Test
    fun theKeeperKeepsTheHighestRankAndTheLatestTimeout() {
        val keeper = ChainDataRouter.ErrorKeeper(requestRank)
        val t1 = ChainFailure(null, "first", null, timeout = true)
        val t2 = ChainFailure(null, "second", null, timeout = true)
        keeper.note(ChainFailure(-32603, "internal error", null, timeout = false))
        assertNull("endpoint-dependent is never reported", keeper.error)
        keeper.note(ChainFailure(-32005, "limit exceeded", null, timeout = false))
        keeper.note(t1)
        assertEquals(t1, keeper.error)
        keeper.note(ChainFailure(-32005, "limit exceeded", null, timeout = false))
        assertEquals("a lower rank never replaces it", t1, keeper.error)
        keeper.note(t2)
        assertEquals("timeout by timeout, the later one ended the request", t2, keeper.error)
        assertFalse(keeper.final)
        val cap = ChainFailure(-32005, "query exceeds max block range 10", null, timeout = false)
        keeper.note(cap)
        assertEquals(cap, keeper.error)
        assertTrue(keeper.final)
        val throwing = ChainDataRouter.ErrorKeeper { error("boom") }
        throwing.note(t1)
        assertNull("a ranking that throws ranks the failure endpoint-dependent", throwing.error)
    }
}
