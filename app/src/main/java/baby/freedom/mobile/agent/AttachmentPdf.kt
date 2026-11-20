package baby.freedom.mobile.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/*
 * PDFs for Freedom Agent's attachments (#601), desktop's
 * `pdf-processor.js` on Android: a page is rendered with Android's own
 * [PdfRenderer]; text is extracted with pdf.js in a WebView of its own
 * ([PdfJsText]), since `PdfRenderer.Page.getTextContents` needs API 35
 * and the app runs from API 30. Desktop parses both in a sandboxed
 * renderer; here the page rendering runs in the app's process (Android's
 * PDFium), and pdf.js in WebView's renderer, which the browser's tabs
 * share.
 */

/** Why a PDF couldn't be read: desktop's codes, mapped to fixed words for the model ([AttachmentTools]). */
internal enum class PdfFailure {
    PasswordRequired,
    PageLimit,
    PageOutOfRange,
    Invalid,
    TimedOut,
    Failed,
}

internal class PdfException(val failure: PdfFailure, val pageCount: Int = 0) : Exception(failure.name)

/** Text of some pages of a PDF: [pages] by 1-based page number, [pageCount] in the whole document. */
internal data class PdfText(val pageCount: Int, val pages: List<Pair<Int, String>>, val truncated: Boolean)

/** One page rendered as a PNG. */
internal data class PdfPage(val page: Int, val pageCount: Int, val width: Int, val height: Int, val png: ByteArray)

/** What the attachment tools need of a PDF; replaced by a fake in tests. */
internal interface PdfProcessor {
    suspend fun extractText(file: File, page: Int, pageCount: Int): PdfText

    suspend fun renderPage(file: File, page: Int): PdfPage
}

internal object PdfLimits {
    /** Desktop's bounds (`pdf-processor.js`). */
    const val MAX_PAGES = 500
    const val MAX_TEXT_PAGES = 4
    const val MAX_RENDER_DIMENSION = 2048
    const val MAX_RENDER_PIXELS = 4_000_000
    const val MAX_RENDER_BYTES = 8 * 1024 * 1024
    const val TEXT_TIMEOUT_MS = 15_000L

    /**
     * Characters of text one read returns: under the tool registry's cut
     * ([ToolRegistry.MAX_OUTPUT_CHARS]), so what a read says it covered
     * is what the model gets (desktop's is 128K, with no such cut).
     */
    const val MAX_TEXT_CHARS = 48 * 1024

    /**
     * Desktop's `renderScale`: at most twice the page's size in points,
     * within [MAX_RENDER_DIMENSION] on each side and [MAX_RENDER_PIXELS]
     * in all.
     */
    fun renderScale(width: Double, height: Double): Double {
        val w = max(1.0, width)
        val h = max(1.0, height)
        return min(2.0, min(min(MAX_RENDER_DIMENSION / w, MAX_RENDER_DIMENSION / h), sqrt(MAX_RENDER_PIXELS / (w * h))))
    }

    /**
     * Desktop's `validateResult` for a text reply: the page's answer is
     * checked again here, since the page parsed an untrusted file. Throws
     * [PdfException] for a refusal it reported, null-free [PdfText]
     * otherwise; anything not in the shape is [PdfFailure.Failed].
     */
    fun parseTextReply(json: String, page: Int, pageCount: Int): PdfText {
        val root = AgentWire.parseObject(json) ?: throw PdfException(PdfFailure.Failed)
        if (root.opt("ok") != true) {
            throw PdfException(
                when (root.opt("code")) {
                    "PDF_PASSWORD_REQUIRED" -> PdfFailure.PasswordRequired
                    "PDF_PAGE_LIMIT" -> PdfFailure.PageLimit
                    "PDF_PAGE_OUT_OF_RANGE" -> PdfFailure.PageOutOfRange
                    "PDF_INVALID" -> PdfFailure.Invalid
                    else -> PdfFailure.Failed
                },
            )
        }
        val total = root.opt("pageCount") as? Int ?: throw PdfException(PdfFailure.Failed)
        if (total < 1 || total > MAX_PAGES || page > total) throw PdfException(PdfFailure.Failed)
        val items = root.optJSONArray("pages") ?: throw PdfException(PdfFailure.Failed)
        if (items.length() > minOf(pageCount, MAX_TEXT_PAGES)) throw PdfException(PdfFailure.Failed)
        var chars = 0
        val pages = (0 until items.length()).map { i ->
            val item = items.optJSONObject(i) ?: throw PdfException(PdfFailure.Failed)
            val n = item.opt("page") as? Int ?: throw PdfException(PdfFailure.Failed)
            val text = item.opt("text") as? String ?: throw PdfException(PdfFailure.Failed)
            // In order, from the page asked for, within the document.
            if (n != page + i || n > total) throw PdfException(PdfFailure.Failed)
            chars += text.length
            n to text
        }
        if (chars > MAX_TEXT_CHARS) throw PdfException(PdfFailure.Failed)
        return PdfText(total, pages, truncated = root.opt("truncated") == true)
    }
}

/** [PdfProcessor] on the phone: [PdfRenderer] for pages, [PdfJsText] for text. */
internal class AndroidPdfProcessor(context: Context) : PdfProcessor {
    private val app = context.applicationContext
    private val text = PdfJsText(app)

    // PdfRenderer isn't safe to use from two threads.
    private val renderLock = Mutex()

    override suspend fun extractText(file: File, page: Int, pageCount: Int): PdfText = text.extract(file, page, pageCount)

    override suspend fun renderPage(file: File, page: Int): PdfPage = renderLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer -> render(renderer, page) }
                }
            } catch (e: PdfException) {
                throw e
            } catch (e: SecurityException) {
                // PdfRenderer's answer for a password-protected document.
                throw PdfException(PdfFailure.PasswordRequired)
            } catch (e: IOException) {
                throw PdfException(PdfFailure.Invalid)
            } catch (e: OutOfMemoryError) {
                throw PdfException(PdfFailure.Failed)
            } catch (e: RuntimeException) {
                throw PdfException(PdfFailure.Failed)
            }
        }
    }

    private fun render(renderer: PdfRenderer, page: Int): PdfPage {
        val count = renderer.pageCount
        if (count > PdfLimits.MAX_PAGES) throw PdfException(PdfFailure.PageLimit, count)
        if (page < 1 || page > count) throw PdfException(PdfFailure.PageOutOfRange, count)
        renderer.openPage(page - 1).use { p ->
            val scale = PdfLimits.renderScale(p.width.toDouble(), p.height.toDouble())
            val width = max(1, floor(p.width * scale).toInt())
            val height = max(1, floor(p.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // A page draws on transparency; a model reads that as black.
                bitmap.eraseColor(Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (out.size() > PdfLimits.MAX_RENDER_BYTES) throw PdfException(PdfFailure.Failed)
                return PdfPage(page, count, width, height, out.toByteArray())
            } finally {
                bitmap.recycle()
            }
        }
    }
}

/**
 * pdf.js in a WebView of its own, one job at a time, desktop's hidden
 * processor window: the page (`assets/agent/pdf/extract.html`) and pdf.js
 * come from a fixed list of the app's assets at
 * `https://appassets.androidplatform.net`, and the one PDF from a one-use
 * random path; everything else it asks for is refused, network loads are
 * off, it can't navigate, and it has no files, content or storage. The
 * answer comes back on its one message channel, from that origin's top
 * frame only, and is checked again ([PdfLimits.parseTextReply]). The
 * WebView is made for each job and destroyed after it, on time-out and
 * cancellation too; if pdf.js takes WebView's renderer down, this WebView
 * says it handled it (so the app isn't killed) and the job fails.
 */
internal class PdfJsText(private val app: Context) {
    private val lock = Mutex()
    private val random = SecureRandom()

    suspend fun extract(file: File, page: Int, pageCount: Int): PdfText = lock.withLock {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw PdfException(PdfFailure.Failed)
        }
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val reply = CompletableDeferred<String?>()
        var view: WebView? = null
        try {
            withContext(Dispatchers.Main) { view = start(file, token, page, pageCount, reply) }
            val json = withTimeoutOrNull(PdfLimits.TEXT_TIMEOUT_MS) { reply.await() }
                ?: throw PdfException(if (reply.isCompleted) PdfFailure.Failed else PdfFailure.TimedOut)
            PdfLimits.parseTextReply(json, page, pageCount)
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                view?.let {
                    WebViewCompat.removeWebMessageListener(it, CHANNEL)
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(file: File, token: String, page: Int, pageCount: Int, reply: CompletableDeferred<String?>): WebView {
        val view = WebView(app)
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            blockNetworkImage = true
            blockNetworkLoads = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(view.settings, false)
        }
        WebViewCompat.addWebMessageListener(view, CHANNEL, setOf(ORIGIN)) { _, message, origin, isMainFrame, _ ->
            if (isMainFrame && origin.toString().trimEnd('/') == ORIGIN) reply.complete(message.data)
        }
        var served = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.scheme != "https" || url.host != HOST || request.method != "GET") return refused()
                val path = url.path ?: return refused()
                if (path == "/job/$token.pdf") {
                    // Once: nothing else on the page gets the file.
                    synchronized(this) {
                        if (served) return refused()
                        served = true
                    }
                    return try {
                        WebResourceResponse("application/pdf", null, FileInputStream(file))
                    } catch (e: IOException) {
                        refused()
                    }
                }
                val type = ASSETS[path] ?: return refused()
                return try {
                    WebResourceResponse(type, "utf-8", app.assets.open(path.removePrefix("/")))
                } catch (e: IOException) {
                    refused()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView's renderer went while reading a PDF")
                reply.complete(null)
                return true
            }
        }
        val job = JSONObject().put("token", token).put("page", page).put("pageCount", pageCount).toString()
        view.loadUrl("$ORIGIN$PAGE#${Uri.encode(job)}")
        return view
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)

    companion object {
        private const val TAG = "PdfJsText"
        private const val HOST = "appassets.androidplatform.net"
        private const val ORIGIN = "https://$HOST"
        private const val PAGE = "/agent/pdf/extract.html"
        private const val CHANNEL = "pdfResult"

        /** Everything the page may load, with its type. */
        private val ASSETS = mapOf(
            PAGE to "text/html",
            "/agent/pdf/extract.mjs" to "text/javascript",
            "/agent/pdf/pdf.min.mjs" to "text/javascript",
            "/agent/pdf/pdf.worker.min.mjs" to "text/javascript",
        )
    }
}
