package baby.freedom.mobile.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/**
 * Routes service-worker-initiated fetches through the *same*
 * [interceptVirtualRequest] path the main interceptor uses — one shared
 * resolver/fetch code path, no fork. Without this, a SW registered on a
 * virtual origin would issue fetches that bypass
 * `WebViewClient.shouldInterceptRequest` entirely and die on DNS for
 * hostnames that don't exist.
 *
 * The SW client is process-global (androidx.webkit exposes exactly one),
 * which is fine: virtual-origin traffic is identified by host pattern,
 * not by tab, and requests for ordinary https origins return `null`
 * (pass-through) exactly like the per-WebView client does.
 *
 * Belonging to no tab, SW fetches carry no [EnsDocumentPins]: an ENS
 * subresource a SW fetches is served from the session registry (the
 * name's latest answer), not from the root the controlled page was
 * served from, and a navigation the SW answers from Cache Storage never
 * reaches the interceptor, so the #99 name re-check doesn't run for it.
 * See `docs/dapp-compatibility.md`.
 *
 * Degradation on WebViews without `SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`:
 * we install nothing, and service workers on virtual origins are
 * unsupported — documented in `docs/dapp-compatibility.md` so
 * offline-first dapps know to keep their no-SW fallback path. (We
 * deliberately don't script-inject a shim that hides
 * `navigator.serviceWorker`; a partial emulation is harder to reason
 * about than an honest feature gate.)
 */
object ServiceWorkerInterception {
    @Volatile
    private var installed = false

    /** Can this WebView route SW fetches through our interceptor? */
    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(
            WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST,
        )
    }.getOrDefault(false)

    /** Idempotent; call once the first WebView exists. */
    fun install() {
        if (installed || !isSupported()) return
        synchronized(this) {
            if (installed) return
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        // A frame document a worker fetches never reaches
                        // the tab's WebViewClient, so no tab's
                        // `documentOrigins` has it: note it for every tab
                        // (#125, [UnverifiedOrigins.noteWorkerDocument]).
                        if (isDocumentRequest(request.isForMainFrame, request.requestHeaders)) {
                            request.url?.toString()?.let(VirtualOrigin::parseHostOfUrl)
                                ?.let(VirtualOrigin::originFor)
                                ?.let(UnverifiedOrigins::noteWorkerDocument)
                        }
                        return interceptVirtualRequest(request)
                    }
                },
            )
            installed = true
        }
    }
}
