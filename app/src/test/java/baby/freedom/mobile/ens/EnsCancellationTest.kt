package baby.freedom.mobile.ens

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancelled ENS probe must come back as cancellation, never as an
 * [EnsResult].
 *
 * The caller acts on whatever [EnsResolver.resolveContenthash] returns:
 * `EnsResult.Error` sends the tab to the ENS error page, and that
 * `loadUrl` cancels whatever probe the tab is waiting on *now*. So a
 * resolver that answered a coroutine the user already superseded would
 * let a page looping `location.href='ens://…'` kill the user's own
 * navigation and pin them to an attacker-named error page — right past
 * the `submitSupersedesPendingProbe` gate (#51).
 */
class EnsCancellationTest {

    private val rpc = "https://rpc.test/"

    /** Blocks in `request` until the test releases it. */
    private class GatedHttp(private val reply: () -> EnsHttp.Reply) : EnsHttp {
        val entered = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)

        fun release() = release.countDown()

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "test never released the RPC" }
            return reply()
        }
    }

    /**
     * Run a resolve, cancel it while the RPC is in flight, then let the
     * RPC finish. Returns what the resolve handed back (`null` if it
     * threw) and what it threw.
     */
    private fun resolveAndCancel(name: String, http: GatedHttp): Pair<EnsResult?, Throwable?> {
        var returned: EnsResult? = null
        var thrown: Throwable? = null
        runBlocking {
            val probe = launch(Dispatchers.Default) {
                try {
                    returned = EnsResolver(listOf(rpc), http).resolveContenthash(name)
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            http.entered.await()
            probe.cancel()
            http.release()
            probe.join()
        }
        return returned to thrown
    }

    @Test
    fun `a resolve cancelled mid-RPC throws instead of answering`() {
        // ResolverNotFound(bytes) — a resolution that would otherwise
        // come back as a perfectly good NotFound.
        val http = GatedHttp {
            EnsHttp.Reply(
                200,
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"reverted","data":"0x77209fe8"}}""",
            )
        }

        val (returned, thrown) = resolveAndCancel("cancelled-answer.eth", http)

        assertNull("a cancelled probe must not be handed a result", returned)
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
    }

    @Test
    fun `cancellation surfacing as a transport error still throws`() {
        // Tearing the RPC down under a cancelled coroutine surfaces as
        // an ordinary IOException, which the retry loop would otherwise
        // map to a retryable PROVIDER_ERROR on every endpoint and
        // return like any other failure.
        val http = GatedHttp { throw IOException("socket closed") }

        val (returned, thrown) = resolveAndCancel("cancelled-transport.eth", http)

        assertNull("a cancelled probe must not be handed a result", returned)
        assertTrue("expected cancellation, got $thrown", thrown is CancellationException)
    }
}
