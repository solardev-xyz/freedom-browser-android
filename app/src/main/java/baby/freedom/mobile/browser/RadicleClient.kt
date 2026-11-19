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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement
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
     * node and wait up to [timeoutMs] for its answer, the wait for a slot
     * included. Never throws.
     *
     * [requester] is the tab and document asking ([requesterFor]); by
     * default whoever the calling coroutine runs for ([requesterContext]),
     * else no tab. Calls go through [admission] (#533): a read is refused
     * at once when its tab already has too many waiting ([REASON_BUSY]),
     * and is dropped — queued or running — once its document goes away
     * ([REASON_CANCELLED]).
     */
    internal fun call(
        method: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = READ_TIMEOUT_MS,
        requester: RadicleAdmission.Requester = currentRequester.get() ?: NO_REQUESTER,
    ): Answer {
        unavailableReason()?.let { return Answer.Failed(unavailableMessage(it), it) }
        val start = SystemClock.uptimeMillis()
        val slot = when (val admit = admission.admit(requester, write = method in WRITE_CALLS, waitMs = timeoutMs)) {
            is RadicleAdmission.Admit.Granted -> admit.slot
            RadicleAdmission.Admit.Busy -> return Answer.Failed("The Radicle node is busy; try again", REASON_BUSY)
            RadicleAdmission.Admit.Cancelled -> return Answer.Failed("request cancelled", REASON_CANCELLED)
        }
        try {
            val left = timeoutMs - (SystemClock.uptimeMillis() - start)
            if (left <= 0) return Answer.Failed("The Radicle node didn't answer in time", REASON_TIMEOUT)
            return callNow(method, args, left, slot)
        } finally {
            slot.release()
        }
    }

    /**
     * What every call waits on: desktop's limits for `rad:` reads (#533).
     * Its slots bound what this process waits for; `:node` bounds the
     * native calls themselves (`MAX_RADICLE_CALLS`), and skips one whose
     * reader gave up (a cancelled call's pipe is closed) before it starts.
     */
    private val admission = RadicleAdmission()

    private val currentRequester = ThreadLocal<RadicleAdmission.Requester?>()

    private val NO_REQUESTER = RadicleAdmission.Requester(RadicleAdmission.NO_TAB, 0)

    /** The requester [tab]'s document is now, captured when its request arrives. */
    internal fun requesterFor(tab: Long?): RadicleAdmission.Requester = admission.requester(tab)

    /** Who [call] runs for by default on this thread (tests). */
    internal fun currentRequester(): RadicleAdmission.Requester? = currentRequester.get()

    /** A coroutine context in which [call] runs for [requester]. */
    internal fun requesterContext(requester: RadicleAdmission.Requester): CoroutineContext =
        currentRequester.asContextElement(requester)

    /** [tab]'s main-frame request reached the interceptor (its thread). */
    fun navigationRequested(tab: Long) = admission.navigationRequested(tab)

    /** [tab] committed a document on [url]; null: it was torn down with none in its place. */
    fun documentStarted(tab: Long, url: String?) =
        admission.documentStarted(tab, gone = url == null, blank = url == ABOUT_BLANK)

    /** [tab] closed. */
    fun tabClosed(tab: Long) = admission.tabClosed(tab)

    private fun callNow(method: String, args: JSONObject, timeoutMs: Long, slot: RadicleAdmission.Slot): Answer {
        if (slot.cancelled) return Answer.Failed("request cancelled", REASON_CANCELLED)
        val binder = service ?: return Answer.Failed(unavailableMessage(REASON_STOPPED), REASON_STOPPED)
        val pipe = try {
            binder.radicleCall(method, args.toString())
        } catch (e: Exception) {
            Log.w(TAG, "radicle call $method failed: ${e.message}")
            null
        } ?: return Answer.Failed(unavailableMessage(REASON_STOPPED), REASON_STOPPED)
        val read = try {
            readAll(pipe, timeoutMs, MAX_ANSWER_BYTES) { slot.cancelled }
        } finally {
            runCatching { pipe.close() }
        }
        return when (read) {
            is Read.Done -> parseAnswer(read.text)
            Read.TimedOut -> Answer.Failed("The Radicle node didn't answer in time", REASON_TIMEOUT)
            Read.TooLarge -> Answer.Failed("repository data too large to serve here", REASON_TOO_LARGE)
            Read.Cancelled -> Answer.Failed("request cancelled", REASON_CANCELLED)
            Read.Failed -> Answer.Failed("unreadable answer from the Radicle node", "native-failed")
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

    /** How [readAll] ended. */
    internal sealed interface Read {
        data class Done(val text: String) : Read
        data object TimedOut : Read
        data object TooLarge : Read
        data object Cancelled : Read
        data object Failed : Read
    }

    /**
     * Everything written to [pipe] until it's closed — or why not: it took
     * longer than [timeoutMs], ran past [maxBytes], or [cancelled] turned
     * true (checked at least every [CANCEL_CHECK_MS]). Waits with `poll()`
     * rather than a blocking read, so the deadline holds however slowly
     * (or never) the other end writes.
     */
    internal fun readAll(
        pipe: ParcelFileDescriptor,
        timeoutMs: Long,
        maxBytes: Int,
        cancelled: () -> Boolean = { false },
    ): Read {
        val fd = pipe.fileDescriptor
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) return Read.Cancelled
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) return Read.TimedOut
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), left.coerceAtMost(CANCEL_CHECK_MS).toInt())
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return Read.Failed
            }
            if (ready == 0) continue
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                return Read.Failed
            } catch (e: java.io.InterruptedIOException) {
                continue
            }
            if (n <= 0) return Read.Done(out.toString(Charsets.UTF_8.name()))
            if (out.size() + n > maxBytes) return Read.TooLarge
            out.write(buf, 0, n)
        }
    }

    const val REASON_DISABLED = "integration-disabled"
    const val REASON_STOPPED = "node-stopped"
    const val REASON_NOT_READY = "node-not-ready"
    const val REASON_TIMEOUT = "timeout"
    /** Too many calls waiting, or none got a slot in time (#533). */
    const val REASON_BUSY = "busy"
    /** The document that asked went away (#533). */
    const val REASON_CANCELLED = "cancelled"
    /** The answer ran past [MAX_ANSWER_BYTES]. */
    const val REASON_TOO_LARGE = "too-large"
    /** `:node` refused a write made as an identity the node no longer runs as (#328). */
    const val REASON_IDENTITY_CHANGED = RadicleNode.IDENTITY_CHANGED

    /** A read's deadline: storage reads are local, so this is generous. */
    const val READ_TIMEOUT_MS = 30_000L

    /** A COB write's: it signs, stores and announces the new refs. */
    const val WRITE_TIMEOUT_MS = 60_000L

    /** The COB writes: never cancelled or refused for a full queue ([RadicleAdmission]). */
    internal val WRITE_CALLS = setOf("createIssue", "commentIssue", "editIssueState", "commentPatch")

    /**
     * The biggest answer taken (a big repository's issue list, a blob):
     * desktop's 16 MiB (#533). Counted in UTF-8 bytes, so never more
     * than desktop's 16 Mi characters.
     */
    internal const val MAX_ANSWER_BYTES = 16 * 1024 * 1024

    /** How often a running call checks whether its document went away. */
    private const val CANCEL_CHECK_MS = 200L

    private const val TAG = "RadicleClient"
}
