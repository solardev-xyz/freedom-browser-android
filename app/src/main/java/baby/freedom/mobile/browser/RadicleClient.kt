package baby.freedom.mobile.browser

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import baby.freedom.mobile.node.INodeService
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleNode
import baby.freedom.swarm.RadicleStatus
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The UI process's handle on the embedded Radicle node (#124), which runs
 * in `:node` (#73): what the `rad://` repository browser ([RadApi]) and
 * the `window.radicle` provider ([RadicleProvider]) read and write
 * through. [MainActivity] keeps [service], [state] and [enabled] current.
 *
 * [call] is blocking — the interceptor's IO thread or a provider
 * coroutine on `Dispatchers.IO`, never the main thread.
 */
object RadicleClient {
    /** The bound `:node` service, or null while it isn't bound. */
    @Volatile
    var service: INodeService? = null

    /** The node's last published state (the Radicle page shows the same). */
    val state = MutableStateFlow(RadicleInfo())

    /** Settings → Radicle node is on. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Why nothing can be read or written now, or null when the node is up:
     * `integration-disabled` (Radicle is off), `node-not-ready` (it is
     * starting), `node-stopped` (anything else — the node service isn't
     * bound, the node is stopped or failed to start).
     */
    fun unavailableReason(): String? = unavailableReason(enabled, service != null, state.value.status)

    internal fun unavailableReason(enabled: Boolean, bound: Boolean, status: RadicleStatus): String? = when {
        !enabled -> REASON_DISABLED
        !bound -> REASON_STOPPED
        status == RadicleStatus.Running -> null
        status == RadicleStatus.Starting -> REASON_NOT_READY
        else -> REASON_STOPPED
    }

    /** One of the node's answers: parsed JSON, or why there's none. */
    sealed interface Answer {
        /** A [JSONObject] or [JSONArray]. */
        data class Ok(val value: Any) : Answer

        data class Failed(val message: String, val reason: String?) : Answer
    }

    /**
     * Run [method] (one of `RadicleNode.BROWSER_CALLS`) with [args] on the
     * node and wait up to [timeoutMs] for its answer. Never throws.
     */
    fun call(method: String, args: JSONObject = JSONObject(), timeoutMs: Long = READ_TIMEOUT_MS): Answer {
        unavailableReason()?.let { return Answer.Failed(unavailableMessage(it), it) }
        // At most [MAX_CALLS] at once, each holding up to [MAX_ANSWER_BYTES]
        // (#201 R1-F4). The wait counts against the call's deadline.
        val start = SystemClock.uptimeMillis()
        val permitted = try {
            calls.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!permitted) return Answer.Failed("The Radicle node is busy", REASON_TIMEOUT)
        try {
            val left = timeoutMs - (SystemClock.uptimeMillis() - start)
            if (left <= 0) return Answer.Failed("The Radicle node didn't answer in time", REASON_TIMEOUT)
            return callNow(method, args, left)
        } finally {
            calls.release()
        }
    }

    private val calls = Semaphore(MAX_CALLS, true)

    private fun callNow(method: String, args: JSONObject, timeoutMs: Long): Answer {
        val binder = service ?: return Answer.Failed(unavailableMessage(REASON_STOPPED), REASON_STOPPED)
        val pipe = try {
            binder.radicleCall(method, args.toString())
        } catch (e: Exception) {
            Log.w(TAG, "radicle call $method failed: ${e.message}")
            null
        } ?: return Answer.Failed(unavailableMessage(REASON_STOPPED), REASON_STOPPED)
        val raw = try {
            readAll(pipe, timeoutMs, MAX_ANSWER_BYTES)
        } finally {
            runCatching { pipe.close() }
        }
        return when (raw) {
            null -> Answer.Failed("The Radicle node didn't answer in time", REASON_TIMEOUT)
            else -> parseAnswer(raw)
        }
    }

    /** Seed and fetch [rid] (the node page's own path; progress lands in [state]). */
    fun seed(rid: String): Boolean = runCatching { service?.seedRadicleRepo(rid) != null }.getOrDefault(false)

    /** Stop seeding [rid]. */
    fun unseed(rid: String): Boolean = runCatching { service?.unseedRadicleRepo(rid) != null }.getOrDefault(false)

    /**
     * Parse the node's answer. A library error (`{"error": …}`) becomes
     * [Answer.Failed] with its `reason` if it gave one. The JSON is built
     * from repository data other people wrote, so a parse failure of any
     * kind — `StackOverflowError` included — is an answer, not a crash.
     */
    internal fun parseAnswer(raw: String): Answer {
        val value = try {
            JSONTokener(raw).nextValue()
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
        return when (value) {
            is JSONObject -> {
                val error = value.opt("error")
                if (error is String) {
                    Answer.Failed(error, value.optString("reason").ifEmpty { null })
                } else {
                    Answer.Ok(value)
                }
            }
            is JSONArray -> Answer.Ok(value)
            else -> Answer.Failed("unreadable answer from the Radicle node", "native-failed")
        }
    }

    internal fun unavailableMessage(reason: String): String = when (reason) {
        REASON_DISABLED -> "Radicle is turned off"
        REASON_NOT_READY -> "The Radicle node is starting"
        else -> "The Radicle node is not running"
    }

    /**
     * Everything written to [pipe] until it's closed, or null if that
     * takes longer than [timeoutMs] or runs past [maxBytes]. Waits with
     * `poll()` rather than a blocking read, so the deadline holds however
     * slowly (or never) the other end writes.
     */
    internal fun readAll(pipe: ParcelFileDescriptor, timeoutMs: Long, maxBytes: Int): String? {
        val fd = pipe.fileDescriptor
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) return null
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return null
            }
            if (ready == 0) return null
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                return null
            } catch (e: java.io.InterruptedIOException) {
                continue
            }
            if (n <= 0) return out.toString(Charsets.UTF_8.name())
            if (out.size() + n > maxBytes) return null
            out.write(buf, 0, n)
        }
    }

    const val REASON_DISABLED = "integration-disabled"
    const val REASON_STOPPED = "node-stopped"
    const val REASON_NOT_READY = "node-not-ready"
    const val REASON_TIMEOUT = "timeout"
    /** `:node` refused a write made as an identity the node no longer runs as (#328). */
    const val REASON_IDENTITY_CHANGED = RadicleNode.IDENTITY_CHANGED

    /** A read's deadline: storage reads are local, so this is generous. */
    const val READ_TIMEOUT_MS = 30_000L

    /** A COB write's: it signs, stores and announces the new refs. */
    const val WRITE_TIMEOUT_MS = 60_000L

    /** Calls waiting on the node at once (its own side runs as many). */
    private const val MAX_CALLS = 4

    /** The biggest answer taken (a big repository's issue list, a blob). */
    private const val MAX_ANSWER_BYTES = 32 * 1024 * 1024

    private const val TAG = "RadicleClient"
}
