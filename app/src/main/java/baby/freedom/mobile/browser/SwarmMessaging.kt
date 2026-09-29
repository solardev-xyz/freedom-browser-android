package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.HdKeys
import baby.freedom.mobile.wallet.PublisherKeys
import baby.freedom.mobile.wallet.Secp256k1Keys
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * The Freedom profile v1 GSOC derivation (#121): how a messaging topic
 * becomes the single-owner-chunk address a room lives at, byte for byte
 * as desktop's `messaging-service.js` (bee-js `gsocMine`) and iOS's
 * `SwarmGsoc` derive it:
 *
 *     identifier    = keccak256(utf8(topic))
 *     targetOverlay = keccak256(utf8("freedom-gsoc-v1:" + topic))
 *     privateKey    = uint256BE(0xb33 + i), the first i in [0, 0xffff)
 *                     whose SOC address shares ≥ 12 leading bits with
 *                     targetOverlay
 *     address       = keccak256(identifier ‖ owner(privateKey))
 *
 * Every constant is frozen: any drift derives another address for the
 * same topic, and the room silently splits from desktop and iOS. The
 * mined key is no secret — it comes from a public constant and only
 * places the chunk in the right neighbourhood — so anyone with the topic
 * can write to the room, which is the point.
 */
internal object SwarmGsoc {
    const val TARGET_CONTEXT = "freedom-gsoc-v1:"
    const val PROXIMITY_BITS = 12
    private const val NONCE_BASE = 0xb33L
    private const val MAX_ITERATIONS = 0xffffL
    private const val CACHE_MAX = 128

    class Derivation(
        /** `keccak256(utf8(topic))`, the SOC identifier written to. */
        val identifier: ByteArray,
        /** The mined owner key: public by construction (see [SwarmGsoc]). */
        val privateKey: ByteArray,
        /** 64 lower-case hex: the subscribe key, and `sendGsoc`'s `address`. */
        val address: String,
    )

    /** topic → derivation: mining is a pure function of the topic, and pages pick topics, so it's bounded. */
    private val cache = object : LinkedHashMap<String, Derivation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Derivation>?) = size > CACHE_MAX
    }

    /** [topic]'s room, mined once and then cached. CPU work (a few thousand hashes): call it off the main thread. */
    fun derive(topic: String): Derivation {
        synchronized(cache) { cache[topic] }?.let { return it }
        val derivation = mine(topic)
        synchronized(cache) { cache[topic] = derivation }
        return derivation
    }

    /**
     * bee-js's search, with the owner keys walked by point addition: key
     * `k + 1`'s public point is key `k`'s plus G, so each step costs one
     * affine addition instead of a whole scalar multiplication.
     */
    private fun mine(topic: String): Derivation {
        val identifier = Keccak256.digest(topic.toByteArray(Charsets.UTF_8))
        val target = Keccak256.digest((TARGET_CONTEXT + topic).toByteArray(Charsets.UTF_8))
        var point = Secp256k1Keys.publicPoint(keyFor(0))
        for (i in 0 until MAX_ITERATIONS) {
            if (i > 0) point = addG(point)
            val owner = Keccak256.digest(HdKeys.to32(point.first) + HdKeys.to32(point.second)).copyOfRange(12, 32)
            val address = SwarmChunks.socAddress(identifier, owner)
            if (proximity(address, target) >= PROXIMITY_BITS) {
                val key = keyFor(i)
                // The walk must land where a plain derivation does.
                check(PublisherKeys.address(key).removePrefix("0x").equals(owner.swarmHex(), ignoreCase = true)) {
                    "GSOC mining drifted from the key's own address"
                }
                return Derivation(identifier, key, address.swarmHex())
            }
        }
        throw IllegalStateException("Could not mine a GSOC signer for this topic")
    }

    private fun keyFor(i: Long): ByteArray = HdKeys.to32(BigInteger.valueOf(NONCE_BASE + i))

    /** Leading bits two equal-length byte strings share (bee-js `Binary.proximity`). */
    fun proximity(a: ByteArray, b: ByteArray): Int {
        var bits = 0
        for (i in a.indices) {
            val diff = (a[i].toInt() xor b[i].toInt()) and 0xff
            if (diff == 0) {
                bits += 8
            } else {
                return bits + Integer.numberOfLeadingZeros(diff) - 24
            }
        }
        return bits
    }

    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

    /** Affine `p + G`, for a `p` that is neither G nor −G (a key between 2 and n − 2). */
    private fun addG(p: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        val (x, y) = p
        val lambda = GY.subtract(y).mod(P).multiply(GX.subtract(x).mod(P).modInverse(P)).mod(P)
        val x3 = lambda.multiply(lambda).subtract(x).subtract(GX).mod(P)
        val y3 = lambda.multiply(x.subtract(x3)).subtract(y).mod(P)
        return x3 to y3
    }
}

/**
 * The page's messaging subscriptions (#121), desktop's
 * `subscription-registry.js`: which document holds which subscription,
 * and the node sockets feeding them.
 *
 * - One socket per `(kind, key)`: the node has a small, node-wide pool
 *   of receive pipelines, so every subscription to one GSOC address or
 *   PSS topic shares it, and it closes with its last subscription.
 * - At most [maxPerOrigin] subscriptions per site; the node's own pool
 *   running out is told apart (`node_subscription_limit`).
 * - Subscribing waits at most [establishTimeoutMs] for the socket (it
 *   reconnects for as long as it's open) and gives back everything it
 *   took if it doesn't make it. A subscription gets messages only once
 *   the page has its id.
 * - Subscriptions go with the document that made them ([cancelWhere]:
 *   its tab starts another document or closes) and with the site's
 *   connection ([cancelByOrigin]).
 *
 * Delivery is at-least-once and payload-transparent, as on desktop: every
 * frame the node pushes goes to the page as it is, with no de-duplication
 * — a redelivery and a real repeat (an empty PSS ping, a second "ok") look
 * the same on the wire. Thread-safe.
 */
class SwarmSubscriptions(
    private val open: (kind: String, key: String, onMessage: (ByteArray) -> Unit) -> Socket,
    private val maxPerOrigin: Int = MAX_SUBSCRIPTIONS,
    private val establishTimeoutMs: Long = ESTABLISH_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One node receive pipeline. */
    interface Socket {
        /** Completes once the pipeline is up, or fails with a [Failure]. */
        val established: CompletableDeferred<Unit>
        fun cancel()
    }

    /** Why a subscription couldn't be made: a desktop `data.reason`. */
    class Failure(val reason: String, message: String) : Exception(message)

    /** Where a subscription's messages go: the document that asked ([SwarmProviders]). */
    interface Subscriber {
        /** The tab it's in. */
        val tab: Long

        /** Its document number in that tab. */
        val document: Int

        /** Whether it's still the tab's document. */
        fun live(): Boolean

        /** Hand it one `message` event's data. Called off the main thread. */
        fun deliver(message: JSONObject)
    }

    private class Sub(
        val id: String,
        val origin: String,
        val kind: String,
        val key: String,
        val subscriber: Subscriber,
        @Volatile var pending: Boolean = true,
    ) {
        val socketKey get() = "$kind:$key"
    }

    private class Pipe(val socket: Socket, val ids: LinkedHashSet<String> = LinkedHashSet())

    private val lock = Any()
    private val subs = HashMap<String, Sub>()
    private val pipes = HashMap<String, Pipe>()
    private val random = SecureRandom()

    fun count(origin: String): Int = synchronized(lock) { subs.values.count { it.origin == origin } }

    /** Sockets open now: for tests. */
    internal fun sockets(): Int = synchronized(lock) { pipes.size }

    /**
     * Open (or join) the `(kind, key)` pipeline for [origin]'s document
     * [subscriber] and return the subscription's id once it's up. Throws
     * [Failure]: `too_many_subscriptions`, `node_subscription_limit`,
     * `establish_timeout`, or `subscription_cancelled` if the document
     * went away (or the subscription was dropped) while it waited.
     */
    suspend fun subscribe(origin: String, kind: String, key: String, subscriber: Subscriber): String {
        val sub: Sub
        val socket: Socket
        synchronized(lock) {
            if (subs.values.count { it.origin == origin } >= maxPerOrigin) {
                throw Failure("too_many_subscriptions", "Origin has reached the maximum of $maxPerOrigin subscriptions")
            }
            val id = ByteArray(16).also(random::nextBytes).swarmHex()
            sub = Sub(id, origin, kind, key, subscriber)
            val pipe = pipes.getOrPut(sub.socketKey) {
                Pipe(open(kind, key) { payload -> fanOut(sub.socketKey, payload) })
            }
            subs[id] = sub
            pipe.ids += id
            socket = pipe.socket
        }
        try {
            val up = withTimeoutOrNull(establishTimeoutMs) { socket.established.await() }
            if (up == null) throw Failure("establish_timeout", "Subscription did not establish within $establishTimeoutMs ms")
        } catch (e: Throwable) {
            remove(sub)
            throw e
        }
        synchronized(lock) {
            // Dropped while it waited (its document went, the site was
            // disconnected): nothing to hand the page an id for.
            if (subs[sub.id] !== sub || !subscriber.live()) {
                removeLocked(sub)
                throw Failure("subscription_cancelled", "Subscription was cancelled before it was established")
            }
            sub.pending = false
        }
        return sub.id
    }

    /** Close [origin]'s subscription [id]; false if it has none by that id. */
    fun unsubscribe(origin: String, id: String): Boolean {
        val sub = synchronized(lock) { subs[id]?.takeIf { it.origin == origin } } ?: return false
        remove(sub)
        return true
    }

    /** Close every subscription whose document [drop] says is gone. */
    fun cancelWhere(drop: (Subscriber) -> Boolean) {
        val gone = synchronized(lock) { subs.values.filter { drop(it.subscriber) } }
        gone.forEach(::remove)
    }

    /** The site was disconnected: its subscriptions go with the grant. */
    fun cancelByOrigin(origin: String) {
        val gone = synchronized(lock) { subs.values.filter { it.origin == origin } }
        gone.forEach(::remove)
    }

    private fun remove(sub: Sub) {
        val socket = synchronized(lock) { removeLocked(sub) }
        socket?.cancel()
    }

    /** Drop [sub]; the socket to close if it was its pipeline's last. */
    private fun removeLocked(sub: Sub): Socket? {
        if (subs[sub.id] === sub) subs.remove(sub.id)
        val pipe = pipes[sub.socketKey] ?: return null
        pipe.ids -= sub.id
        if (pipe.ids.isNotEmpty()) return null
        pipes.remove(sub.socketKey)
        return pipe.socket
    }

    private fun fanOut(socketKey: String, payload: ByteArray) {
        val targets = synchronized(lock) {
            pipes[socketKey]?.ids?.mapNotNull { subs[it] }?.filter { !it.pending }.orEmpty()
        }
        if (targets.isEmpty()) return
        val data = Base64.getEncoder().encodeToString(payload)
        val receivedAt = clock()
        for (sub in targets) {
            sub.subscriber.deliver(
                JSONObject()
                    .put("type", "swarm_subscription")
                    .put("subscription", sub.id)
                    .put(
                        "result",
                        JSONObject()
                            .put("kind", sub.kind)
                            .put("key", sub.key)
                            .put("data", data)
                            .put("encoding", "base64")
                            .put("receivedAt", receivedAt),
                    ),
            )
        }
    }

    companion object {
        const val MAX_SUBSCRIPTIONS = 32
        const val ESTABLISH_TIMEOUT_MS = 30_000L
    }
}

/**
 * One node receive pipeline (#121): a WebSocket to the gateway's
 * `/gsoc/subscribe/{address}` or `/pss/subscribe/{topic}`, each binary
 * frame one message's payload — desktop's `openSubscriptionSocket`:
 *
 * - The node says nothing on success and refuses fast, so a socket open
 *   for [ESTABLISH_GRACE_MS] is up.
 * - A close with 1013 (try again later) before that is the node's pool
 *   of pipelines being full (`node_subscription_limit`).
 * - Any other close, before or after, reconnects with backoff (1 s,
 *   doubling to 30 s) until cancelled: messages sent meanwhile are missed
 *   (delivery is best-effort; the subscription's establish timeout
 *   bounds a node that never comes up).
 *
 * OkHttp answers the node's keep-alive pings.
 */
internal class NodeSubscriptionSocket(
    private val client: OkHttpClient,
    private val url: String,
    private val onMessage: (ByteArray) -> Unit,
    private val scheduler: ScheduledExecutorService = SCHEDULER,
    private val graceMs: Long = ESTABLISH_GRACE_MS,
    private val baseDelayMs: Long = RECONNECT_BASE_MS,
    private val maxDelayMs: Long = RECONNECT_MAX_MS,
) : SwarmSubscriptions.Socket {
    override val established = CompletableDeferred<Unit>()

    private val lock = Any()
    private var cancelled = false
    private var up = false
    private var current: WebSocket? = null
    private var grace: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var delayMs = baseDelayMs

    fun start(): NodeSubscriptionSocket = apply { connect() }

    private fun connect() {
        synchronized(lock) {
            if (cancelled) return
            current = client.newWebSocket(Request.Builder().url(url).build(), Listener())
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (cancelled || webSocket !== current) return
                grace?.cancel(false)
                grace = scheduler.schedule({ opened(webSocket) }, graceMs, TimeUnit.MILLISECONDS)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = deliver(webSocket, bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = deliver(webSocket, text.toByteArray(Charsets.UTF_8))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed(webSocket, code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, null, null)
    }

    private fun deliver(webSocket: WebSocket, payload: ByteArray) {
        synchronized(lock) { if (cancelled || webSocket !== current) return }
        try {
            onMessage(payload)
        } catch (e: RuntimeException) {
            // One bad delivery mustn't take the pipeline down.
        }
    }

    /** Open for the whole grace period: up. */
    private fun opened(webSocket: WebSocket) {
        synchronized(lock) {
            if (cancelled || webSocket !== current) return
            grace = null
            delayMs = baseDelayMs
            up = true
        }
        established.complete(Unit)
    }

    private fun closed(webSocket: WebSocket, code: Int?, reason: String?) {
        synchronized(lock) {
            if (cancelled || webSocket !== current) return
            current = null
            grace?.cancel(false)
            grace = null
            if (!up && code == CLOSE_TRY_AGAIN) {
                cancelled = true
                established.completeExceptionally(
                    SwarmSubscriptions.Failure("node_subscription_limit", reason?.takeIf { it.isNotEmpty() } ?: "Node subscription limit reached"),
                )
                return
            }
            val wait = delayMs
            delayMs = minOf(delayMs * 2, maxDelayMs)
            retry = scheduler.schedule({ connect() }, wait, TimeUnit.MILLISECONDS)
        }
    }

    override fun cancel() {
        val ws = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            grace?.cancel(false)
            retry?.cancel(false)
            current.also { current = null }
        }
        if (!up) established.completeExceptionally(SwarmSubscriptions.Failure("cancelled", "Subscription cancelled"))
        ws?.close(1000, null)
    }

    companion object {
        const val ESTABLISH_GRACE_MS = 500L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L

        /** The close code the node refuses a subscription with when its pipelines are all taken. */
        const val CLOSE_TRY_AGAIN = 1013

        private val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "swarm-subscriptions").apply { isDaemon = true }
        }

        /**
         * A client for the node on loopback: no proxy (a `.onion` setting
         * must never route the node's own socket), and no read timeout —
         * a quiet subscription is a healthy one; the node pings it.
         */
        val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** The pipeline for `(kind, key)` on the node at [base] (`http://127.0.0.1:1633`). */
        fun open(base: String, kind: String, key: String, onMessage: (ByteArray) -> Unit, client: OkHttpClient = CLIENT) =
            NodeSubscriptionSocket(client, "$base/$kind/subscribe/$key", onMessage).start()
    }
}
