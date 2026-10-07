package baby.freedom.mobile.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.R
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "BlobDownloads"

/**
 * The deadlines of a [BlobDownloads.prepare]. The frames of a blob's
 * origin are asked one at a time, the next [PREPARE_STEP_MS] after the
 * last unless it answered first; the ask fails [PREPARE_TIMEOUT_MS]
 * after the last frame was asked (#408 R4-M2) — at most
 * [FRAMES_PER_ORIGIN] are, so at most 7 s + 5 s = 12 s while no frame
 * says it's working. A frame that says it's taking hold of the file
 * ([BlobMessage.Working]; a big copy takes a while) gets
 * [PREPARE_WORKING_TIMEOUT_MS] counted from when *it* said so, not from
 * the start (#408 R5-M1). If that frame then fails, the next frame is
 * asked and the 5 s count starts again from that ask; so each frame adds
 * at most 1 s, or 30 s if it works on the file and then fails.
 */
private const val PREPARE_TIMEOUT_MS = 5_000L
private const val PREPARE_WORKING_TIMEOUT_MS = 30_000L

/**
 * How long one frame gets to answer before the next frame of the origin
 * is asked: frames are asked one at a time (#408 R3-M1), and one that
 * has gone (an iframe that navigated) never answers.
 */
private const val PREPARE_STEP_MS = 1_000L

/** How long one chunk may take before the page counts as gone. */
private const val CHUNK_TIMEOUT_MS = 30_000L

/** Frames remembered per origin, and origins per tab (most recent kept). */
private const val FRAMES_PER_ORIGIN = 8
private const val ORIGINS_PER_TAB = 32

/** Clicked `blob:` URLs remembered with the frame that took hold of them. */
private const val CAPTURES_PER_TAB = 16

/**
 * `blob:` downloads (#… "blob: downloads aren't supported"): a page that
 * builds a file in script (an export, a canvas image, a zip) and hands it
 * to an `<a download>` link gives `DownloadListener` a `blob:` URL —
 * one only documents of the origin that made it can read, and only while
 * the document that made it is alive. Native code can't fetch it, so it
 * is read inside the page.
 *
 * One per tab (WebView): a message channel and a document-start reader
 * script ([blobReaderJs]) in every frame, as the ad blocker's cosmetic
 * channel has (#126) — a random channel name, its object taken off
 * `window` before the page runs (#69), and every native the reader calls
 * later saved at document start (#146/#198). Each frame says
 * [BLOB_HELLO] once, so Kotlin can reach it.
 *
 * The reader takes hold of a `blob:` link's file the moment it's clicked
 * (by opening, not sending, a request for it), so a page that revokes the
 * URL right after its `a.click()` (most do) still gets its file saved,
 * and tells Kotlin which frame did ([BlobMessage.Captured]). When the tab
 * offers a `blob:` download ([prepare]), the frames of the blob's origin
 * are asked, **one at a time** — the frame that captured the click first,
 * then the main frame, then the rest — to hold the file under a fresh
 * one-time token ([newBlobToken]). Asking one at a time keeps the
 * captured link's `download` name from losing a race to another
 * same-origin frame that can read the URL too, and under a CSP that
 * refuses `blob:` reads makes one read, and one report, not one per frame
 * (#408 R3-M1). The frame that can read it answers with its size, type
 * and — when it saw the link clicked — its `download` attribute; one
 * that can't answers [BLOB_GONE] and the next is asked.
 *
 * How the page holds it depends on its size ([BLOB_COPY_MAX_BYTES],
 * #408 R3-F1): a file up to that size is copied in the page, so the
 * download survives the page revoking the URL while the prompt is up; a
 * bigger one isn't copied — WebView's limited blob memory can't make a
 * second copy of a 200 MB file the page still holds, and other tabs'
 * blobs use that same memory — and is read in
 * byte ranges from the page's own URL, which the page must then keep
 * until it's saved. Then the usual offer goes up, and once the user
 * accepts, [DownloadManager] reads the file through [BlobSource.open]:
 * chunk by chunk ([BLOB_CHUNK_BYTES]), each asked for by Kotlin and
 * checked against that transfer ([BlobTransfer]) — from the frame holding
 * it, under its token, at the offset and of the length asked for. Only one
 * chunk is ever in memory on either side.
 *
 * Can't be read, each with its own reason: a blob whose origin is opaque
 * (`blob:null/…`, from a sandboxed frame or a `data:` document — no frame
 * of it can be told apart) or in a frame whose reader isn't there
 * ("Couldn't reach…"); one under a CSP refusing `blob:` reads ("This
 * site's security policy…"); a link revoked right at its click whose
 * copy WebView couldn't make ([BLOB_TOO_BIG] — WebView's blob memory is
 * shared by every tab, so that depends on what else is open as much as
 * on the file's size, #408 R4-M3); and one the page revoked
 * before Freedom asked for it or, for a big file read from its URL,
 * before it was saved ("The page withdrew this file…") — the click of a
 * link never put in the document (FileSaver.js's detached `<a>`) isn't
 * seen, so revoking such a link at all before the prompt is answered
 * gives that.
 */
internal class BlobDownloads private constructor(private val binary: Boolean) {
    private val main = Handler(Looper.getMainLooper())

    /**
     * The current document's frames that said hello, by origin. Main
     * thread only. Emptied when a new top-level document says hello —
     * its own reader runs before any of its frames', so what's left is
     * the old document's — and never trimmed of [top] or its origin.
     */
    private val frames = LinkedHashMap<String, ArrayList<JavaScriptReplyProxy>>(16, 0.75f, true)

    /** The main frame of the current document, and its origin, once it said hello. */
    private var top: JavaScriptReplyProxy? = null
    private var topOrigin: String? = null

    /** The frame that took hold of each recently clicked `blob:` URL ([BlobMessage.Captured]). Main thread only. */
    private val captured = object : LinkedHashMap<String, JavaScriptReplyProxy>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JavaScriptReplyProxy>?) = size > CAPTURES_PER_TAB
    }

    /** Asks still waiting for the frames' answers, by token. Main thread only. */
    private val preparing = HashMap<String, Preparing>()

    /** Files a page holds for an offered or running download, by token. Main thread only. */
    private val held = HashMap<String, PageBlob>()

    /**
     * One [prepare]: the frames of the blob's origin, asked one at a
     * time ([askNext]); any frame asked may still answer, but the next
     * is asked only once the current one said it can't, or said nothing
     * within [PREPARE_STEP_MS].
     */
    private inner class Preparing(
        val token: String,
        val url: String,
        candidates: List<JavaScriptReplyProxy>,
        val answer: (BlobSource) -> Unit,
    ) {
        private val queue = ArrayDeque(candidates)
        val asked = HashSet<JavaScriptReplyProxy>()
        private val answered = HashSet<JavaScriptReplyProxy>()
        private val working = HashSet<JavaScriptReplyProxy>()
        private var current: JavaScriptReplyProxy? = null
        private var lastAskedAt = SystemClock.uptimeMillis()

        /** When a frame last said it's working on the file. */
        private var workingSince = 0L
        var resolved = false

        /** A frame said the page's CSP refuses reading `blob:` URLs ([BLOB_REFUSED]). */
        private var refused = false

        /** A frame said its copy of a file revoked at the click couldn't be made ([BLOB_TOO_BIG]). */
        private var tooBig = false

        /** Ask the next frame; false when every one has been. */
        fun askNext(): Boolean {
            val frame = queue.removeFirstOrNull() ?: return false
            current = frame
            asked += frame
            lastAskedAt = SystemClock.uptimeMillis()
            post(frame, blobPrepareMessage(token, binary, url))
            main.postDelayed({
                if (!resolved && current === frame && frame !in answered && frame !in working) askNext()
            }, PREPARE_STEP_MS)
            return true
        }

        fun working(frame: JavaScriptReplyProxy) {
            if (frame in asked && frame !in answered && working.add(frame)) workingSince = SystemClock.uptimeMillis()
        }

        /** [frame] can't hand the file over ([code]). */
        fun failed(frame: JavaScriptReplyProxy, code: String) {
            answered += frame
            working -= frame
            when (code) {
                BLOB_REFUSED -> {
                    // The site has had its one report: ask no other frame.
                    refused = true
                    finish(this, FailedBlob(whyNot()))
                    return
                }
                BLOB_TOO_BIG -> tooBig = true
            }
            if (frame === current && askNext()) return
            if (queue.isEmpty() && answered.containsAll(asked)) finish(this, FailedBlob(whyNot()))
        }

        fun ready(frame: JavaScriptReplyProxy) {
            answered += frame
            working -= frame
        }

        /**
         * The deadline ([PREPARE_TIMEOUT_MS]): [PREPARE_WORKING_TIMEOUT_MS]
         * after a frame last said it's working, while one is; otherwise
         * [PREPARE_TIMEOUT_MS] after the last frame was asked — not before
         * every frame has been (#408 R4-M2).
         */
        fun expire() {
            if (!resolved) {
                val now = SystemClock.uptimeMillis()
                val left = when {
                    working.isNotEmpty() -> workingSince + PREPARE_WORKING_TIMEOUT_MS - now
                    // Frames still to ask: the step timers ask them.
                    queue.isNotEmpty() -> PREPARE_STEP_MS
                    else -> lastAskedAt + PREPARE_TIMEOUT_MS - now
                }
                if (left > 0) {
                    main.postDelayed(::expire, left)
                    return
                }
            }
            // Gone from here on: a frame's "I have it" after this is told
            // to let go by [onReady] all the same.
            preparing.remove(token)
            if (!resolved) {
                finish(
                    this,
                    FailedBlob(
                        if (answered.isNotEmpty()) whyNot() else DownloadNote.of(R.string.library_download_blob_unreachable),
                    ),
                )
            }
        }

        /** Why no frame could hand the file over, once some answered. */
        fun whyNot(): String = DownloadNote.of(
            when {
                refused -> R.string.library_download_blob_refused
                tooBig -> R.string.library_download_blob_too_big
                else -> R.string.library_download_blob_gone
            },
        )
    }

    private fun onMessage(message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val parsed = when (message.type) {
            WebMessageCompat.TYPE_ARRAY_BUFFER -> runCatching { message.arrayBuffer }.getOrNull()?.let(::parseBlobChunk)
            else -> message.data?.let(::parseBlobMessage)
        } ?: return
        when (parsed) {
            BlobMessage.Hello -> hello(webOrigin(sourceOrigin.toString()) ?: return, isMainFrame, reply)
            is BlobMessage.Captured -> captured[parsed.url] = reply
            is BlobMessage.Working -> preparing[parsed.token]?.working(reply)
            is BlobMessage.Ready -> onReady(parsed, reply)
            is BlobMessage.Failed -> {
                // A frame's answer to a prepare ("I don't have it"), while
                // that ask is open. Anything else — a chunk the holding
                // frame couldn't read, even within the prepare window —
                // is the held file's.
                val ask = preparing[parsed.token]
                if (parsed.code != BLOB_READ_FAILED && ask != null && !ask.resolved && reply in ask.asked) {
                    ask.failed(reply, parsed.code)
                    return
                }
                held[parsed.token]?.takeIf { it.proxy === reply }?.pageFailed()
            }
            is BlobMessage.Chunk -> held[parsed.token]?.takeIf { it.proxy === reply }?.chunk(parsed)
        }
    }

    private fun hello(origin: String, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        if (isMainFrame) {
            // A new top-level document: the old one's frames are gone.
            frames.clear()
            captured.clear()
            top = reply
            topOrigin = origin
        }
        val list = frames.getOrPut(origin) { ArrayList() }
        if (reply !in list) list += reply
        while (list.size > FRAMES_PER_ORIGIN) list.remove(list.first { it !== top })
        while (frames.size > ORIGINS_PER_TAB) frames.remove(frames.keys.first { it != topOrigin })
    }

    private fun onReady(ready: BlobMessage.Ready, reply: JavaScriptReplyProxy) {
        val ask = preparing[ready.token]
        if (ask == null) {
            // Too late — the ask expired (a copy that took longer than
            // [PREPARE_WORKING_TIMEOUT_MS]) or its document went
            // ([documentChanged], #408 R5-M2) — or a token nobody asked
            // this frame about: either way, it lets go now rather than
            // holding the file for the page's whole hold time (#408
            // R4-M1). Only the file this frame holds under the token
            // goes: one held for a download is another frame's, or not
            // this token's.
            if (held[ready.token]?.proxy !== reply) post(reply, blobReleaseMessage(ready.token))
            return
        }
        if (reply !in ask.asked) return
        ask.ready(reply)
        if (ask.resolved) {
            // Another frame of the origin got there first: this one can let go.
            post(reply, blobReleaseMessage(ready.token))
            return
        }
        val blob = PageBlob(reply, ready.token, ready.size, ready.mimeType, ready.name)
        held[ready.token] = blob
        finish(ask, blob)
    }

    private fun finish(ask: Preparing, source: BlobSource) {
        if (ask.resolved) return
        ask.resolved = true
        ask.answer(source)
    }

    /**
     * The tab offers [url], a `blob:` download: find the frame that can
     * read it and have it hold the file. [answer] gets what it said (or
     * why nothing could), on the main thread, once — by the deadlines
     * of [PREPARE_TIMEOUT_MS] (12 s at most while no frame is working on
     * the file), or at once if the document changes first
     * ([documentChanged]). Main thread.
     */
    fun prepare(url: String, answer: (BlobSource) -> Unit) {
        val origin = blobUrlOrigin(url)
        val all = origin?.let { frames[it] }.orEmpty()
        if (all.isEmpty()) {
            Log.i(LOG_TAG, "no frame of ${origin ?: "an opaque origin"} to read a blob: download from")
            answer(FailedBlob(DownloadNote.of(R.string.library_download_blob_unreachable)))
            return
        }
        // The frame that saw the link clicked (it holds the file and its
        // name), then the main frame, then the most recent first.
        val candidates = LinkedHashSet<JavaScriptReplyProxy>()
        captured[url]?.takeIf { it in all }?.let(candidates::add)
        top?.takeIf { it in all }?.let(candidates::add)
        candidates.addAll(all.asReversed())
        val token = newBlobToken()
        val ask = Preparing(token, url, candidates.toList(), answer)
        preparing[token] = ask
        ask.askNext()
        main.postDelayed(ask::expire, PREPARE_TIMEOUT_MS)
    }

    /**
     * The tab's main frame committed a new document (`onPageStarted`):
     * every frame of the old one — and every file one of them held — is
     * gone. Downloads reading one fail at once ("The page that made this
     * file is closed") instead of after [CHUNK_TIMEOUT_MS], and so do
     * asks still waiting for an answer: an old frame's "I have it" that
     * crosses the commit is told to let go ([onReady]) instead of making
     * an offer whose Accept would wait out [CHUNK_TIMEOUT_MS] (#408
     * R5-M2). The frames
     * list stays: the new document's frames may already have said hello
     * (it's emptied by the new main frame's own hello, [hello]).
     * Main thread.
     */
    fun documentChanged() {
        val asks = preparing.values.toList()
        preparing.clear()
        for (ask in asks) finish(ask, FailedBlob(DownloadNote.of(R.string.library_download_blob_page_closed)))
        for (blob in held.values.toList()) blob.pageGone()
        held.clear()
    }

    private fun post(frame: JavaScriptReplyProxy, text: String) {
        // A frame that has gone (navigated, closed) just never answers.
        runCatching { frame.postMessage(text) }.onFailure { Log.i(LOG_TAG, "couldn't reach a frame", it) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** A file a frame holds, read through [open]. */
    private inner class PageBlob(
        val proxy: JavaScriptReplyProxy,
        val token: String,
        override val size: Long,
        override val mimeType: String?,
        override val name: String?,
    ) : BlobSource {
        override val failure: String? = null
        private val transfer = BlobTransfer(token, size)
        private val inbox = LinkedBlockingQueue<Any>()
        private val released = AtomicBoolean(false)
        private val opened = AtomicBoolean(false)

        /** Main thread: a chunk from [proxy]. Taken only if it's the one asked for. */
        fun chunk(chunk: BlobMessage.Chunk) {
            val taken = synchronized(transfer) { transfer.accept(chunk) }
            if (taken) inbox.offer(chunk.bytes) else Log.w(LOG_TAG, "refused a blob chunk nothing asked for")
        }

        /** Main thread: the document holding it is gone. */
        fun pageGone() {
            inbox.offer(PAGE_GONE)
        }

        /** Main thread: the page couldn't read a chunk (the file's gone). */
        fun pageFailed() {
            inbox.offer(PAGE_FAILED)
        }

        override fun open(): InputStream {
            check(opened.compareAndSet(false, true)) { "a blob download is read once" }
            return Stream()
        }

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            inbox.offer(CLOSED)
            onMain {
                held.remove(token)
                post(proxy, blobReleaseMessage(token))
            }
        }

        private inner class Stream : InputStream() {
            private var buf: ByteArray? = null
            private var at = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (buf.let { it == null || at >= it.size }) {
                    if (released.get()) throw IOException("closed")
                    val next = synchronized(transfer) {
                        if (transfer.done) return -1
                        transfer.next()
                    } ?: throw IOException("a chunk is already asked for")
                    onMain { post(proxy, blobChunkRequest(token, next.first, next.second)) }
                    when (val got = inbox.poll(CHUNK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        is ByteArray -> { buf = got; at = 0 }
                        PAGE_FAILED -> throw BlobReadException(DownloadNote.of(R.string.library_download_blob_gone))
                        PAGE_GONE -> throw BlobReadException(DownloadNote.of(R.string.library_download_blob_page_closed))
                        CLOSED -> throw IOException("closed")
                        else -> throw BlobReadException(DownloadNote.of(R.string.library_download_blob_page_closed))
                    }
                }
                val chunk = buf!!
                val n = minOf(len, chunk.size - at)
                chunk.copyInto(b, off, at, at + n)
                at += n
                return n
            }

            override fun close() = release()
        }
    }

    companion object {
        private val PAGE_FAILED = Any()
        private val PAGE_GONE = Any()
        private val CLOSED = Any()

        fun isSupported(): Boolean = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)

        /** Register [webView]'s channel and reader, before its first load; null where WebView can't. */
        fun install(webView: WebView, copyMaxBytes: Long = BLOB_COPY_MAX_BYTES): BlobDownloads? {
            if (!isSupported()) return null
            val binary = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) }
                .getOrDefault(false)
            val downloads = BlobDownloads(binary)
            val channel = newBottomUiChannelName()
            WebViewCompat.addWebMessageListener(webView, channel, BLOB_ORIGIN_RULES) { _, message, sourceOrigin, isMainFrame, reply ->
                downloads.onMessage(message, sourceOrigin, isMainFrame, reply)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, blobReaderJs(channel, copyMaxBytes), BLOB_ORIGIN_RULES)
            return downloads
        }
    }
}

/** A `blob:` download no page can hand over; [failure] says why. */
internal class FailedBlob(override val failure: String) : BlobSource {
    override val size: Long get() = 0
    override val mimeType: String? get() = null
    override val name: String? get() = null
    override fun open(): InputStream = throw BlobReadException(failure)
    override fun release() = Unit
}

/** Reading a `blob:` download failed; [note] says why ([DownloadNote]). */
internal class BlobReadException(val note: String) : IOException(DownloadNote.shown(note))

/** Every origin, as the cosmetic channel's ([COSMETIC_ORIGIN_RULES]); the script checks for http(s). */
private val BLOB_ORIGIN_RULES: Set<String> = setOf("*")

/**
 * The page side: see [BlobDownloads].
 *
 * It does nothing until the page clicks a `blob:` link or Kotlin asks:
 * a capture-phase `click` listener on `window` (registered first, before
 * any of the page's) takes hold of a clicked `<a>`/`<area>`'s `blob:`
 * file at once by *opening* (not sending) an `XMLHttpRequest` for it —
 * `open()` resolves a blob URL then, so a revoke after the click doesn't
 * matter — and keeps it, with the link's `download` attribute, for
 * [BLOB_CAPTURE_MS], and tells Kotlin it did (`c`). When Kotlin asks
 * for a file, its URL is first tried with a request that reads only the
 * headers (its size and type, then aborted); a file up to
 * [BLOB_COPY_MAX_BYTES] is then copied (through the request opened at the
 * click, when there is one), and a bigger one — or one whose copy fails —
 * is read in `Range` requests of one chunk from the URL itself, never
 * copied whole: WebView's limited blob memory can't make a second copy
 * of a big file the page still holds (#408 R3-F1). If the URL no longer
 * reads (revoked), the request opened at the click is the only way left
 * to the file, and is copied whatever its size ([BLOB_TOO_BIG] when that
 * fails). A held file is kept until Kotlin lets go, or [BLOB_HOLD_MS]
 * after its last chunk at most.
 *
 * **Nothing is fetched on a click** (#408 R2-F1): reading a `blob:` URL
 * is checked against the page's `connect-src`, which a plain
 * `default-src 'self'` refuses, and a refusal fires a
 * `securitypolicyviolation` event and sends the site a CSP report — no
 * other browser reads a clicked link's file, so that would tell any such
 * site it runs in Freedom. `open()` checks nothing; the request is only
 * sent once Kotlin asks for the file, which it does only for a download
 * the tab actually offered (`DownloadListener`). On a site whose CSP
 * refuses it, that one read is refused (the site's own report names a
 * download it has just started) and the download fails as
 * [BLOB_REFUSED]; the reader then tries no further read in that
 * document. Its own `securitypolicyviolation` listener only notes the
 * refusal; it doesn't hide it from the page.
 *
 * **Nothing the page wraps later sees it** (the cosmetic script's
 * technique, #368): every native it calls after document start was saved
 * then and is called through a `Function.prototype.call` bound then —
 * `XMLHttpRequest`'s `open` / `send` / `abort` / `setRequestHeader` /
 * `getResponseHeader` / `responseType` / `readyState` / `status` /
 * `response`, `SecurityPolicyViolationEvent`'s `blockedURI` /
 * `effectiveDirective`, `Blob.prototype.slice` and the `size` / `type` getters,
 * `FileReader`'s `readAsArrayBuffer` / `readAsDataURL` / `result`,
 * `ArrayBuffer`'s `byteLength`, `Event.prototype.composedPath`, the
 * `tagName`, `href` and `download` getters, `String.prototype.indexOf` /
 * `substring` / `charCodeAt`, `addEventListener`, the timers and the
 * `Uint8Array` / `ArrayBuffer` constructors. Its maps have no prototype.
 * And it makes, resolves and awaits **no promise**: every read finishes
 * in an event on an object only it holds (`loadend`), so a page hooking
 * `Promise.prototype` (`constructor`, `then`) or `Object.prototype.then`
 * sees nothing, not even when it clicks a `blob:` link of its own and
 * cancels the click (#408 R1-F1).
 */
internal fun blobReaderJs(channel: String, copyMaxBytes: Long = BLOB_COPY_MAX_BYTES): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    require(copyMaxBytes >= 0)
    return """
(function () {
  var w = window, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var proto = w.location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;
  var fcall = Function.prototype.call, fbind = Function.prototype.bind, gopd = Object.getOwnPropertyDescriptor,
      gpo = Object.getPrototypeOf, mk = Object.create;
  var un = function (f) { return typeof f === 'function' ? fbind.call(fcall, f) : null; };
  var method = function (P, n) {
    return (P && un(P[n])) || function (o, a, b) { return o[n](a, b); };
  };
  var prop = function (P, n) {
    var x = null;
    for (var p = P; p && !x; p = gpo(p)) x = gopd(p, n);
    return (x && un(x.get)) || function (o) { return o[n]; };
  };
  var setter = function (P, n) {
    var x = null;
    for (var p = P; p && !x; p = gpo(p)) x = gopd(p, n);
    return (x && un(x.set)) || function (o, v) { o[n] = v; };
  };
  var P = function (C) { return C && C.prototype; };
  var send = un(port.postMessage), setT = w.setTimeout, clearT = w.clearTimeout;
  var FR = w.FileReader, U8 = w.Uint8Array, AB = w.ArrayBuffer, TA = gpo(P(U8));
  var B = P(w.Blob), S = String.prototype, X = w.XMLHttpRequest, XP = P(X), RP = P(FR);
  var n = {
    slice: method(B, 'slice'), size: prop(B, 'size'),
    path: method(P(w.Event), 'composedPath'), tag: prop(P(w.Element), 'tagName'),
    aHref: prop(P(w.HTMLAnchorElement), 'href'), aName: prop(P(w.HTMLAnchorElement), 'download'),
    areaHref: prop(P(w.HTMLAreaElement), 'href'), areaName: prop(P(w.HTMLAreaElement), 'download'),
    on: method(P(w.EventTarget), 'addEventListener'), u8set: method(TA, 'set'), len: prop(TA, 'length'),
    data: prop(P(w.MessageEvent), 'data'),
    vUri: prop(P(w.SecurityPolicyViolationEvent), 'blockedURI'),
    vDir: prop(P(w.SecurityPolicyViolationEvent), 'effectiveDirective'),
    xOpen: method(XP, 'open'), xSend: method(XP, 'send'), xType: setter(XP, 'responseType'),
    xStatus: prop(XP, 'status'), xBody: prop(XP, 'response'), xState: prop(XP, 'readyState'),
    xAbort: method(XP, 'abort'), xHeader: method(XP, 'setRequestHeader'), xGet: method(XP, 'getResponseHeader'),
    abLen: prop(P(AB), 'byteLength'),
    asData: method(RP, 'readAsDataURL'), asBytes: method(RP, 'readAsArrayBuffer'), result: prop(RP, 'result'),
    at: un(S.indexOf), cut: un(S.substring), code: un(S.charCodeAt), call: un(fcall)
  };
  function post(m) { try { send(port, m); } catch (e) {} }
  function later(f, ms) { return n.call(setT, w, f, ms); }
  function cancel(t) { if (t) n.call(clearT, w, t); }
  function starts(s, p) { return typeof s === 'string' && n.cut(s, 0, p.length) === p; }
  // Clicked blob: links (url -> {name, x}) and files held for Kotlin (token -> {b, mode, timer}).
  var caps = mk(null), held = mk(null);
  // Set once this document's Content-Security-Policy is seen refusing a
  // blob: read (connect-src): no read is tried after that.
  var refused = false;
  n.on(w, 'securitypolicyviolation', function (e) {
    try {
      if (n.vUri(e) === 'blob' && starts(n.vDir(e), 'connect-src')) refused = true;
    } catch (x) {}
  }, true);
  // An XMLHttpRequest for url's file, opened but not sent: open()
  // resolves a blob: URL at once, so a revoke after it doesn't matter,
  // and it doesn't fetch anything — no Content-Security-Policy check
  // (connect-src, which refuses blob: under a plain default-src 'self'),
  // so no securitypolicyviolation event and no CSP report for a click
  // that never becomes a download (#408 R2-F1). Null if it can't be.
  // An XMLHttpRequest, not fetch(): nothing here makes or awaits a
  // promise, so a page watching Promise.prototype (its constructor,
  // then) sees nothing of it.
  function openUrl(url, type) {
    try {
      var x = new X();
      n.xOpen(x, 'GET', url);
      n.xType(x, type || 'blob');
      return x;
    } catch (e) { return null; }
  }
  // A whole non-negative number written in s, or -1. (No RegExp: a page
  // can hook its methods.)
  function digits(s) {
    if (typeof s !== 'string' || s.length === 0 || s.length > 15) return -1;
    var v = 0;
    for (var i = 0; i < s.length; i++) {
      var d = n.code(s, i) - 48;
      if (d < 0 || d > 9) return -1;
      v = v * 10 + d;
    }
    return v;
  }
  // What url's file is, read from the headers alone (the request is
  // aborted once they're in, so no copy of the file is made): to
  // done({size, type}) — size -1 if not given — done(null) if the URL no
  // longer reads (revoked), done(false) if the page's CSP refuses it.
  function probe(url, done) {
    var x = openUrl(url, 'arraybuffer'), over = false;
    function end(v) { if (!over) { over = true; done(v); } }
    if (!x) { end(null); return; }
    try {
      n.on(x, 'readystatechange', function () {
        try {
          if (over || n.xState(x) < 2 || n.xStatus(x) !== 200) return;
          var i = mk(null), t = n.xGet(x, 'Content-Type');
          i.size = digits(n.xGet(x, 'Content-Length'));
          i.type = typeof t === 'string' ? t : '';
          end(i);
          n.xAbort(x);
        } catch (e) {}
      });
      n.on(x, 'loadend', function () {
        if (!over) later(function () { end(refused ? false : null); }, 0);
      });
      n.xSend(x);
    } catch (e) { end(null); }
  }
  // Bytes off..off+len of url's file, as a Blob of just those bytes, to
  // done(blob) or done(null) — the page revoked the URL, most likely.
  function readRange(url, off, len, done) {
    var x = openUrl(url), over = false;
    function end(b) { if (!over) { over = true; done(b); } }
    if (!x) { end(null); return; }
    try {
      n.xHeader(x, 'Range', 'bytes=' + off + '-' + (off + len - 1));
      n.on(x, 'loadend', function () {
        var b = null;
        try {
          var st = n.xStatus(x);
          if (st === 206 || st === 200) { b = n.xBody(x); if (n.size(b) !== len) b = null; }
        } catch (e) { b = null; }
        end(b);
      });
      n.xSend(x);
    } catch (e) { end(null); }
  }
  // Send x (from openUrl): its file, to done(blob), or done(null) — and
  // on a refusal by the page's CSP, done(false), told apart by the
  // securitypolicyviolation event, which Chromium queues before the
  // request's own error.
  // done(b, type): the copy, and the file's own type — the response's
  // Content-Type, which is empty for an untyped blob, where the copy's
  // own type would say text/plain (#408 R4-F1).
  function readOpened(x, done) {
    var over = false;
    function end(b, t) { if (!over) { over = true; done(b, t); } }
    if (!x) { end(null); return; }
    try {
      n.on(x, 'loadend', function () {
        var b = null, t = '';
        try {
          if (n.xStatus(x) === 200) { b = n.xBody(x); n.size(b); t = n.xGet(x, 'Content-Type'); }
        } catch (e) { b = null; }
        if (b) { end(b, typeof t === 'string' ? t : ''); return; }
        // Let a violation event queued with the error be seen first.
        later(function () { end(refused ? false : null); }, 0);
      });
      n.xSend(x);
    } catch (e) { end(null); }
  }
  // Nothing is read on a click: the request is only opened, and sent
  // once Kotlin asks for the file — which it does only for a download
  // the tab actually offered (DownloadListener), never for a click the
  // page cancelled or one that leads nowhere.
  function capture(url, name) {
    var c = mk(null);
    c.name = typeof name === 'string' ? name : '';
    c.x = openUrl(url);
    caps[url] = c;
    // So Kotlin asks this frame first for a download of url (#408 R3-M1).
    post('c\n' + url);
    later(function () { if (caps[url] === c) delete caps[url]; }, $BLOB_CAPTURE_MS);
  }
  n.on(w, 'click', function (e) {
    try {
      var path = n.path(e);
      for (var i = 0; i < path.length; i++) {
        var el = path[i], tag;
        try { tag = n.tag(el); } catch (x) { continue; }
        var href = null, name = null;
        if (tag === 'A') { href = n.aHref(el); name = n.aName(el); }
        else if (tag === 'AREA') { href = n.areaHref(el); name = n.areaName(el); }
        else continue;
        if (starts(href, 'blob:')) capture(href, name);
        return;
      }
    } catch (x) {}
  }, true);
  function field(m, from) {
    var end = n.at(m, '\n', from);
    return end < 0 ? null : end;
  }
  function prepare(token, mode, url) {
    var c = caps[url];
    if (c) delete caps[url];
    var name = c ? c.name : '', x = c ? c.x : null;
    function fail(code) { post('e\n' + token + '\n' + code); }
    // Held: a copy (b), or the URL to read ranges from (u).
    function hold(b, u, size, type) {
      var h = mk(null);
      h.b = b; h.u = u; h.mode = mode; h.timer = 0;
      held[token] = h;
      keep(token, h);
      post('o\n' + token + '\n' + size + '\n' + type + '\n' + name);
    }
    function copied(b, type) { hold(b, null, n.size(b), type); }
    // Already refused once: don't try (and have the site sent another report).
    if (refused) { fail('$BLOB_REFUSED'); return; }
    // Copying can take a while: tell Kotlin not to ask another frame.
    if (x) post('a\n' + token);
    probe(url, function (info) {
      if (info === false) { fail('$BLOB_REFUSED'); return; }
      if (info === null) {
        // Revoked: only the request opened at the click still has the file.
        if (!x) { fail('$BLOB_GONE'); return; }
        readOpened(x, function (b, t) {
          if (b) copied(b, t); else fail(b === false ? '$BLOB_REFUSED' : '$BLOB_TOO_BIG');
        });
        return;
      }
      if (!x) post('a\n' + token);
      // Too big to copy: read from the URL, which the page still holds.
      if (info.size > $copyMaxBytes) { hold(null, url, info.size, info.type); return; }
      readOpened(x || openUrl(url), function (b, t) {
        if (b) copied(b, t);
        else if (b === false) fail('$BLOB_REFUSED');
        else if (info.size >= 0) hold(null, url, info.size, info.type);
        else fail('$BLOB_GONE');
      });
    });
  }
  function keep(token, h) {
    cancel(h.timer);
    h.timer = later(function () { if (held[token] === h) delete held[token]; }, $BLOB_HOLD_MS);
  }
  // part's bytes (or data: URL), to done(value) or done(null).
  function readPart(part, binary, done) {
    try {
      var r = new FR();
      n.on(r, 'loadend', function () {
        var v = null;
        try { v = n.result(r); } catch (e) {}
        if (binary) { try { n.abLen(v); } catch (e) { v = null; } } else if (typeof v !== 'string') v = null;
        done(v);
      });
      if (binary) n.asBytes(r, part); else n.asData(r, part);
    } catch (e) { done(null); }
  }
  function chunk(token, off, len) {
    var h = held[token];
    if (!h) { post('e\n' + token + '\n$BLOB_READ_FAILED'); return; }
    keep(token, h);
    var binary = h.mode === '$BLOB_MODE_BINARY', part;
    if (!h.b) {
      readRange(h.u, off, len, function (b) {
        if (b) send(b); else post('e\n' + token + '\n$BLOB_READ_FAILED');
      });
      return;
    }
    try { part = n.slice(h.b, off, off + len); } catch (e) { post('e\n' + token + '\n$BLOB_READ_FAILED'); return; }
    send(part);
    function send(part) { readPart(part, binary, function (v) {
      try {
        if (v === null) throw 0;
        if (binary) {
          var data = new U8(v), head = token + ':' + off + ':';
          var buf = new AB(head.length + n.len(data)), out = new U8(buf);
          for (var i = 0; i < head.length; i++) out[i] = n.code(head, i);
          n.u8set(out, data, head.length);
          post(buf);
        } else {
          post('d\n' + token + '\n' + off + '\n' + n.cut(v, n.at(v, ',') + 1));
        }
      } catch (e) { post('e\n' + token + '\n$BLOB_READ_FAILED'); }
    }); }
  }
  port.onmessage = function (e) {
    var m = null;
    try { var own = e ? gopd(e, 'data') : null; m = own ? own.value : e ? n.data(e) : null; } catch (x) {}
    if (typeof m !== 'string') return;
    var a = field(m, 0);
    if (a === null) return;
    var kind = n.cut(m, 0, a), b = field(m, a + 1);
    if (kind === 'x') { var t = n.cut(m, a + 1), h = held[t]; if (h) { cancel(h.timer); delete held[t]; } return; }
    if (b === null) return;
    var token = n.cut(m, a + 1, b), c = field(m, b + 1);
    if (c === null) return;
    if (kind === 'p') prepare(token, n.cut(m, b + 1, c), n.cut(m, c + 1));
    else if (kind === 'r') chunk(token, +n.cut(m, b + 1, c), +n.cut(m, c + 1));
  };
  post('$BLOB_HELLO');
})();
"""
}

/** How long a clicked `blob:` link's file is held for a download that may follow. */
internal const val BLOB_CAPTURE_MS = 60_000

/** How long a file held for Kotlin is kept with nothing asked of it. */
internal const val BLOB_HOLD_MS = 600_000
