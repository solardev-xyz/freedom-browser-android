package baby.freedom.mobile.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
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

/** How long the frames of a blob's origin get to say whether they hold it. */
private const val PREPARE_TIMEOUT_MS = 5_000L

/** How long one chunk may take before the page counts as gone. */
private const val CHUNK_TIMEOUT_MS = 30_000L

/** Frames remembered per origin, and origins per tab (most recent kept). */
private const val FRAMES_PER_ORIGIN = 8
private const val ORIGINS_PER_TAB = 32

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
 * When the tab offers a `blob:` download ([prepare]), the frames of the
 * blob's origin are asked to hold the file under a fresh one-time token
 * ([newBlobToken]): the frame that can read it (an `XMLHttpRequest`
 * for a `Blob`) answers with its size, type and — when the reader saw the link
 * clicked — its `download` attribute; the others answer [BLOB_GONE]. The
 * reader also takes hold of a `blob:` link's file the moment it's
 * clicked, so a page that revokes the URL right after its `a.click()`
 * (most do) still gets its file saved. Then the usual offer goes up, and
 * once the user accepts, [DownloadManager] reads the file through
 * [BlobSource.open]: chunk by chunk ([BLOB_CHUNK_BYTES]), each asked for
 * by Kotlin and checked against that transfer ([BlobTransfer]) — from
 * the frame holding it, under its token, at the offset and of the
 * length asked for. Only one chunk is ever in memory on either side.
 *
 * Can't be read: a blob whose origin is opaque (`blob:null/…`, from a
 * sandboxed frame or a `data:` document — no frame of it can be told
 * apart), one in a frame whose reader isn't there, and one the page
 * revoked before Freedom asked for it — only possible for a link that was
 * never put in the document (FileSaver.js's detached `<a>`), whose click
 * the reader can't see. Each fails with its own reason, not "not
 * supported".
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

    /** Asks still waiting for the frames' answers, by token. Main thread only. */
    private val preparing = HashMap<String, Preparing>()

    /** Files a page holds for an offered or running download, by token. Main thread only. */
    private val held = HashMap<String, PageBlob>()

    private class Preparing(
        val token: String,
        val asked: Set<JavaScriptReplyProxy>,
        val answer: (BlobSource) -> Unit,
    ) {
        var answered = 0
        var resolved = false

        /** A frame said the page's CSP refuses reading `blob:` URLs ([BLOB_REFUSED]). */
        var refused = false

        /** Why no frame could hand the file over, once some answered. */
        fun whyNot(): String = DownloadNote.of(
            if (refused) R.string.library_download_blob_refused else R.string.library_download_blob_gone,
        )
    }

    private fun onMessage(message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val parsed = when (message.type) {
            WebMessageCompat.TYPE_ARRAY_BUFFER -> runCatching { message.arrayBuffer }.getOrNull()?.let(::parseBlobChunk)
            else -> message.data?.let(::parseBlobMessage)
        } ?: return
        when (parsed) {
            BlobMessage.Hello -> hello(webOrigin(sourceOrigin.toString()) ?: return, isMainFrame, reply)
            is BlobMessage.Ready -> onReady(parsed, reply)
            is BlobMessage.Failed -> {
                // A frame's answer to a prepare ("I don't have it"), while
                // that ask is open. Anything else — a chunk the holding
                // frame couldn't read, even within the prepare window —
                // is the held file's.
                val ask = preparing[parsed.token]
                if ((parsed.code == BLOB_GONE || parsed.code == BLOB_REFUSED) && ask != null && !ask.resolved && reply in ask.asked) {
                    ask.answered++
                    if (parsed.code == BLOB_REFUSED) ask.refused = true
                    if (ask.answered >= ask.asked.size) finish(ask, FailedBlob(ask.whyNot()))
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
            top = reply
            topOrigin = origin
        }
        val list = frames.getOrPut(origin) { ArrayList() }
        if (reply !in list) list += reply
        while (list.size > FRAMES_PER_ORIGIN) list.remove(list.first { it !== top })
        while (frames.size > ORIGINS_PER_TAB) frames.remove(frames.keys.first { it != topOrigin })
    }

    private fun onReady(ready: BlobMessage.Ready, reply: JavaScriptReplyProxy) {
        val ask = preparing[ready.token] ?: return
        if (reply !in ask.asked) return
        ask.answered++
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
        ask.resolved = true
        // Kept until the deadline, so a late "I have it" from a second
        // frame is told to let go rather than holding the file for nothing.
        ask.answer(source)
    }

    /**
     * The tab offers [url], a `blob:` download: find the frame that can
     * read it and have it hold the file. [answer] gets what it said (or
     * why nothing could), on the main thread, once — within
     * [PREPARE_TIMEOUT_MS]. Main thread.
     */
    fun prepare(url: String, answer: (BlobSource) -> Unit) {
        val origin = blobUrlOrigin(url)
        val asked = origin?.let { frames[it] }?.toSet().orEmpty()
        if (asked.isEmpty()) {
            Log.i(LOG_TAG, "no frame of ${origin ?: "an opaque origin"} to read a blob: download from")
            answer(FailedBlob(DownloadNote.of(R.string.library_download_blob_unreachable)))
            return
        }
        val token = newBlobToken()
        val ask = Preparing(token, asked, answer)
        preparing[token] = ask
        for (frame in asked) post(frame, blobPrepareMessage(token, binary, url))
        main.postDelayed({
            preparing.remove(token)
            if (!ask.resolved) {
                finish(
                    ask,
                    FailedBlob(
                        if (ask.answered > 0) ask.whyNot() else DownloadNote.of(R.string.library_download_blob_unreachable),
                    ),
                )
            }
        }, PREPARE_TIMEOUT_MS)
    }

    /**
     * The tab's main frame committed a new document (`onPageStarted`):
     * every frame of the old one — and every file one of them held — is
     * gone. Downloads reading one fail at once ("The page that made this
     * file is closed") instead of after [CHUNK_TIMEOUT_MS]. The frames
     * list stays: the new document's frames may already have said hello
     * (it's emptied by the new main frame's own hello, [hello]).
     * Main thread.
     */
    fun documentChanged() {
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
        fun install(webView: WebView): BlobDownloads? {
            if (!isSupported()) return null
            val binary = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) }
                .getOrDefault(false)
            val downloads = BlobDownloads(binary)
            val channel = newBottomUiChannelName()
            WebViewCompat.addWebMessageListener(webView, channel, BLOB_ORIGIN_RULES) { _, message, sourceOrigin, isMainFrame, reply ->
                downloads.onMessage(message, sourceOrigin, isMainFrame, reply)
            }
            WebViewCompat.addDocumentStartJavaScript(webView, blobReaderJs(channel), BLOB_ORIGIN_RULES)
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
 * [BLOB_CAPTURE_MS]; a file Kotlin asked it to hold is kept until Kotlin
 * lets go, or [BLOB_HOLD_MS] after its last chunk at most.
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
 * `XMLHttpRequest`'s `open` / `send` / `responseType` / `status` /
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
internal fun blobReaderJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
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
    slice: method(B, 'slice'), size: prop(B, 'size'), type: prop(B, 'type'),
    path: method(P(w.Event), 'composedPath'), tag: prop(P(w.Element), 'tagName'),
    aHref: prop(P(w.HTMLAnchorElement), 'href'), aName: prop(P(w.HTMLAnchorElement), 'download'),
    areaHref: prop(P(w.HTMLAreaElement), 'href'), areaName: prop(P(w.HTMLAreaElement), 'download'),
    on: method(P(w.EventTarget), 'addEventListener'), u8set: method(TA, 'set'), len: prop(TA, 'length'),
    data: prop(P(w.MessageEvent), 'data'),
    vUri: prop(P(w.SecurityPolicyViolationEvent), 'blockedURI'),
    vDir: prop(P(w.SecurityPolicyViolationEvent), 'effectiveDirective'),
    xOpen: method(XP, 'open'), xSend: method(XP, 'send'), xType: setter(XP, 'responseType'),
    xStatus: prop(XP, 'status'), xBody: prop(XP, 'response'),
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
  function openUrl(url) {
    try {
      var x = new X();
      n.xOpen(x, 'GET', url);
      n.xType(x, 'blob');
      return x;
    } catch (e) { return null; }
  }
  // Send x (from openUrl): its file, to done(blob), or done(null) — and
  // on a refusal by the page's CSP, done(false), told apart by the
  // securitypolicyviolation event, which Chromium queues before the
  // request's own error.
  function readOpened(x, done) {
    var over = false;
    function end(b) { if (!over) { over = true; done(b); } }
    if (!x) { end(null); return; }
    try {
      n.on(x, 'loadend', function () {
        var b = null;
        try { if (n.xStatus(x) === 200) { b = n.xBody(x); n.size(b); } } catch (e) { b = null; }
        if (b) { end(b); return; }
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
    function got(blob, name) {
      if (blob === false) { post('e\n' + token + '\n$BLOB_REFUSED'); return; }
      if (!blob) { post('e\n' + token + '\n$BLOB_GONE'); return; }
      var h = mk(null);
      h.b = blob; h.mode = mode; h.timer = 0;
      held[token] = h;
      keep(token, h);
      post('o\n' + token + '\n' + n.size(blob) + '\n' + n.type(blob) + '\n' + name);
    }
    // Already refused once: don't try (and have the site sent another report).
    if (refused) { got(false, ''); return; }
    function fresh(name) { readOpened(openUrl(url), function (b) { got(b, name); }); }
    if (!c || !c.x) { fresh(c ? c.name : ''); return; }
    readOpened(c.x, function (b) { if (b === null) fresh(c.name); else got(b, c.name); });
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
    try { part = n.slice(h.b, off, off + len); } catch (e) { post('e\n' + token + '\n$BLOB_READ_FAILED'); return; }
    readPart(part, binary, function (v) {
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
    });
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
