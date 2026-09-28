package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * What `shouldInterceptRequest` answers on [RadUrl.HOST] (#124): the
 * repository browser and the read API it runs on.
 *
 *  - `/_/api/<rid>/<endpoint>` — public repository data as JSON, straight
 *    out of the embedded node's storage: the endpoints (and JSON shapes)
 *    desktop serves for `rad:<rid>/…` and iOS for `rad:` scheme fetches
 *    (`radicle-api-protocol.js` / `RadSchemeHandler.swift`):
 *
 *    ```
 *    (root)              repo metadata (radicle-httpd `payloads` shape)
 *    /tree/SHA[/path]    tree entries at the commit
 *    /blob/SHA/path      blob content at the commit
 *    /readme/SHA         the root readme blob, 404 when there is none
 *    /commits?parent=SHA paginated commit history
 *    /commits/SHA        commit metadata + structured diff
 *    /stats/tree/SHA     commit / branch / contributor counts
 *    /remotes            signed remote branch heads
 *    /issues[/ID]        issues (list paginated, `status` filter)
 *    /patches[/ID]       patches (same)
 *    ```
 *
 *    GET / HEAD only; writes go
 *    through the consented `window.radicle` provider, never here. Only the
 *    per-repository surface exists — the node's seeded list, identity and
 *    peers are the user's, not public — and a private repository is
 *    refused (403). Only the repository browser itself reads it (see
 *    [isFromViewer]): no CORS headers, and a request any other page makes
 *    is refused with one fixed answer before the node is asked anything,
 *    so an unconsented site can't learn which repositories this device
 *    holds (the data is public, but *that the user has it* isn't — the
 *    provider's `listSeededRepos` is behind a connection grant for the
 *    same reason) (#201 R1-F1). Revisions are
 *    full 40-hex commit ids only. Path segments are checked so an encoded
 *    `/`, `.` / `..` or a control character can't climb out of the
 *    repository.
 *
 *    WebView has no custom schemes, so a page can't `fetch('rad:…')` the
 *    way it can on desktop. Unlike desktop's `rad:` URLs this surface is
 *    not open to other origins.
 *
 *  - `/_/viewer.js`, `/_/viewer.css` — the browser page's own script and
 *    style, from the app's assets.
 *
 *  - anything else — the browser page (`assets/rad/viewer.html`), which
 *    reads the address it was loaded at and fetches what it shows from
 *    the API. Served with a CSP that only runs its own script: repository
 *    content (file names, issue text, a README) is other people's and is
 *    only ever inserted as text.
 */
object RadApi {
    /** A response before it becomes a [WebResourceResponse] (unit-testable). */
    data class Reply(
        val status: Int,
        val mime: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    /** How the API reaches the node; [RadicleClient.call] in the app, a fake in tests. */
    fun interface Backend {
        fun call(method: String, args: JSONObject): RadicleClient.Answer
    }

    @Volatile
    private var assets: Map<String, ByteArray> = emptyMap()

    /** Load the viewer's files from the app's assets, once. */
    fun init(context: Context) {
        if (assets.isNotEmpty()) return
        assets = VIEWER_FILES.associate { (path, file) ->
            path to runCatching { context.assets.open("rad/$file").use { it.readBytes() } }
                .onFailure { Log.w(TAG, "missing viewer asset $file", it) }
                .getOrDefault(ByteArray(0))
        }
    }

    /**
     * The interceptor's answer for [url], or null if it isn't on
     * [RadUrl.HOST]. Never falls through to the network: the host doesn't
     * exist in DNS.
     */
    internal fun intercept(request: WebResourceRequest, url: String): WebResourceResponse? {
        if (!RadUrl.isVirtualUrl(url) && !isUnderHost(url)) return null
        val reply = if (RadUrl.isVirtualUrl(url)) {
            serve(
                request.method ?: "GET",
                url,
                RadicleClient::call,
                fromViewer = isFromViewer(request.requestHeaders.orEmpty(), request.isForMainFrame),
            )
        } else {
            // `http://rad.freedom.baby/`, a port, userinfo: another origin
            // on a host nobody else answers. Nothing is served there.
            json(404, JSONObject().put("error", "not found"))
        }
        return WebResourceResponse(
            reply.mime.substringBefore(';').trim(),
            if (reply.mime.contains("charset")) "utf-8" else null,
            reply.status,
            reasonFor(reply.status),
            reply.headers,
            ByteArrayInputStream(reply.body),
        )
    }

    /** Any http(s) URL whose host is [RadUrl.HOST], whatever its port or userinfo. */
    private fun isUnderHost(url: String): Boolean {
        val m = Regex("^https?://(?:[^/?#@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE).find(url) ?: return false
        return m.groupValues[1].trimEnd('.').equals(RadUrl.HOST, ignoreCase = true)
    }

    /**
     * Did the repository browser itself make this request? Its pages send
     * a same-origin `Referer` ([PAGE_HEADERS]' `Referrer-Policy:
     * same-origin`), which no other origin's request can carry. Any
     * `Origin` or `Sec-Fetch-Site` WebView passes along must agree. A
     * top-level load with no referrer at all (the user typing an API URL)
     * is let through too: it's a document of its own origin, which no
     * other page can read. Everything else — another site's `fetch`,
     * `<img>`, `<script>`, iframe, a `no-referrer` page's `window.open` —
     * is not the viewer.
     */
    internal fun isFromViewer(headers: Map<String, String>, isForMainFrame: Boolean): Boolean {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val origin = header("Origin")
        if (origin != null && origin != RadUrl.ORIGIN) return false
        val site = header("Sec-Fetch-Site")
        if (site != null && site != "same-origin" && site != "none") return false
        val referer = header("Referer")
        if (referer.isNullOrEmpty()) return isForMainFrame && origin == null
        return referer == RadUrl.ORIGIN || referer.startsWith("${RadUrl.ORIGIN}/")
    }

    /**
     * The whole routing, off Android: [url] is on [RadUrl.HOST].
     * [fromViewer] is [isFromViewer]'s verdict; the API refuses anything else.
     */
    fun serve(method: String, url: String, backend: Backend, fromViewer: Boolean): Reply {
        val path = RadUrl.pathOf(url) ?: return json(404, error("not found"))
        val m = method.uppercase()
        val isApi = path.startsWith(RadUrl.API_PREFIX)
        // Nothing but the viewer (same-origin, so it never preflights)
        // reads the API: another page's request gets this one answer,
        // whatever the repository, before the node is asked (R1-F1).
        if (isApi && !fromViewer) return json(403, error("not available to other sites"))
        if (m != "GET" && m != "HEAD") return reply405()
        val head = m == "HEAD"
        val reply = when {
            isApi -> serveApi(path.removePrefix(RadUrl.API_PREFIX), backend)
            path.startsWith(RadUrl.INTERNAL_PREFIX) -> serveFile(path.substringBefore('?').substringBefore('#'))
            else -> viewer()
        }
        return if (head) reply.copy(body = ByteArray(0)) else reply
    }

    private fun serveFile(path: String): Reply {
        if (path == RadUrl.INVALID_PATH) return viewer()
        val file = VIEWER_FILES.firstOrNull { it.first == path && it.first != VIEWER_HTML }
            ?: return json(404, error("not found"))
        return Reply(200, mimeFor(file.second), assets[path] ?: ByteArray(0), PAGE_HEADERS)
    }

    private fun viewer(): Reply =
        Reply(200, "text/html; charset=utf-8", assets[VIEWER_HTML] ?: FALLBACK_HTML, PAGE_HEADERS)

    private fun mimeFor(file: String): String = when {
        file.endsWith(".js") -> "text/javascript; charset=utf-8"
        file.endsWith(".css") -> "text/css; charset=utf-8"
        else -> "text/html; charset=utf-8"
    }

    // ---------------------------------------------------------------
    // The read API
    // ---------------------------------------------------------------

    /** A parsed `/_/api/` request: the full `rad:z…` RID, decoded path segments, query. */
    data class ApiRequest(val rid: String, val segments: List<String>, val query: Map<String, String>)

    /**
     * Parse `<rid>[/<segments>][?query]` (the part after `/_/api/`), or
     * null if the RID or a segment isn't acceptable: desktop's
     * `decodeRepoApiPath` — no backslash, no empty segment but a trailing
     * one, no `.` / `..`, nothing that decodes to `/`, `\` or a control
     * character. Each segment is decoded on its own, after splitting, so
     * an encoded `/` can't make a new one.
     */
    fun parseApiPath(raw: String): ApiRequest? {
        val noFragment = raw.substringBefore('#')
        val pathPart = noFragment.substringBefore('?')
        val queryPart = if (noFragment.contains('?')) noFragment.substringAfter('?') else ""
        val rid = pathPart.substringBefore('/')
        if (!RadUrl.RID.matches(rid)) return null
        val rest = if (pathPart.contains('/')) pathPart.substringAfter('/') else ""
        if (rest.contains('\\')) return null
        val segments = mutableListOf<String>()
        if (rest.isNotEmpty()) {
            val raw = rest.split('/')
            for ((i, segment) in raw.withIndex()) {
                if (segment.isEmpty()) {
                    if (i == raw.lastIndex) continue
                    return null
                }
                val decoded = decode(segment) ?: return null
                if (decoded == "." || decoded == "..") return null
                if (decoded.any { it.code < 0x20 || it.code == 0x7f || it == '/' || it == '\\' }) return null
                segments += decoded
            }
        }
        val query = LinkedHashMap<String, String>()
        if (queryPart.isNotEmpty()) {
            for (pair in queryPart.split('&')) {
                if (pair.isEmpty()) continue
                val name = decode(pair.substringBefore('=')) ?: continue
                val value = if (pair.contains('=')) decode(pair.substringAfter('=')) ?: "" else ""
                query.putIfAbsent(name, value)
            }
        }
        return ApiRequest("rad:$rid", segments, query)
    }

    /** Percent-decoding that leaves `+` alone (it's a path, not a form) and fails on a bad escape. */
    private fun decode(s: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    private val REVISION = Regex("^[0-9a-f]{40}$")
    private val COB_ID = Regex("^[0-9a-f]{6,40}$")

    private fun isRevision(s: String?) = s != null && REVISION.matches(s)

    internal fun serveApi(raw: String, backend: Backend): Reply {
        RadicleClient.unavailableReason()?.let { return unavailable(it) }
        return serveApiWith(raw, backend)
    }

    /** [serveApi] once the node is known to be up (tests start here). */
    fun serveApiWith(raw: String, backend: Backend): Reply {
        val req = parseApiPath(raw) ?: return apiError(400, "invalid rad reference")
        val rid = req.rid
        fun args() = JSONObject().put("rid", rid)

        // Public-repo gate: this surface is unconsented, so a private
        // repository is invisible through it (desktop and iOS parity).
        val info = when (val a = backend.call("repoInfo", args())) {
            is RadicleClient.Answer.Failed -> return failed(a)
            is RadicleClient.Answer.Ok -> a.value as? JSONObject ?: return apiError(500, "unexpected repository info")
        }
        if (info.optJSONObject("visibility")?.optString("type") != "public") {
            return apiError(403, "repository is not public")
        }

        val segs = req.segments
        val section = segs.getOrNull(0)
        val second = segs.getOrNull(1)
        return when (section) {
            null -> ok(repoMeta(rid, info, backend))
            "tree" -> {
                if (!isRevision(second)) return apiError(400, "missing revision")
                unwrap(backend.call("treeAt", args().put("revision", second).put("path", segs.drop(2).joinToString("/"))))
            }
            "blob" -> {
                val path = segs.drop(2).joinToString("/")
                if (!isRevision(second) || path.isEmpty()) return apiError(400, "missing path")
                unwrap(backend.call("blobAt", args().put("revision", second).put("path", path)))
            }
            "readme" -> {
                if (!isRevision(second) || segs.size != 2) return apiError(400, "missing revision")
                readme(rid, second!!, backend)
            }
            "commits" -> when {
                segs.size > 2 -> apiError(400, "invalid commit path")
                second != null -> if (!isRevision(second)) {
                    apiError(400, "invalid revision")
                } else {
                    unwrap(backend.call("commit", args().put("revision", second)))
                }
                else -> {
                    val parent = req.query["parent"]
                    if (!isRevision(parent)) return apiError(400, "missing parent revision")
                    val (page, perPage) = pageParams(req.query)
                    unwrap(
                        backend.call(
                            "commits",
                            args().put("parent", parent).put("page", page).put("perPage", perPage),
                        ),
                    )
                }
            }
            "stats" -> {
                if (segs.size != 3 || second != "tree" || !isRevision(segs[2])) return apiError(400, "invalid stats path")
                unwrap(backend.call("repoStats", args().put("revision", segs[2])))
            }
            "remotes" -> if (segs.size != 1) apiError(404, "not found") else unwrap(backend.call("remotes", args()))
            "issues" -> cobs(segs, req.query, backend, args(), list = "issues", one = "issue", idKey = "issueId")
            "patches" -> cobs(segs, req.query, backend, args(), list = "patches", one = "patch", idKey = "patchId")
            else -> apiError(404, "unsupported endpoint: $section")
        }
    }

    private fun cobs(
        segs: List<String>,
        query: Map<String, String>,
        backend: Backend,
        args: JSONObject,
        list: String,
        one: String,
        idKey: String,
    ): Reply {
        if (segs.size > 2) return apiError(400, "invalid $one path")
        val id = segs.getOrNull(1)
        if (id != null) {
            if (!COB_ID.matches(id)) return apiError(400, "invalid $one id")
            return unwrap(backend.call(one, args.put(idKey, id)))
        }
        val all = when (val a = backend.call(list, args)) {
            is RadicleClient.Answer.Failed -> return failed(a)
            is RadicleClient.Answer.Ok -> a.value as? JSONArray ?: return ok(a.value)
        }
        return ok(paginate(all, query))
    }

    /**
     * Desktop's `paginate`: an optional `status` filter on
     * `item.state.status`, then `page` / `perPage` (defaults 0 / 30,
     * `perPage` at most 100).
     */
    internal fun paginate(items: JSONArray, query: Map<String, String>): JSONArray {
        val status = query["status"].orEmpty()
        val filtered = (0 until items.length()).mapNotNull { items.opt(it) }.filter { item ->
            status.isEmpty() || ((item as? JSONObject)?.optJSONObject("state")?.optString("status") == status)
        }
        val (page, perPage) = pageParams(query)
        val start = (page.toLong() * perPage).coerceAtMost(filtered.size.toLong()).toInt()
        val end = (start + perPage).coerceAtMost(filtered.size)
        return JSONArray(filtered.subList(start, end))
    }

    internal fun pageParams(query: Map<String, String>): Pair<Int, Int> {
        val page = (query["page"]?.toIntOrNull() ?: 0).coerceIn(0, 1_000_000)
        val perPage = (query["perPage"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
        return page to perPage
    }

    /**
     * Repository metadata in radicle-httpd's `payloads` shape, built from
     * the flat `repoInfo` (desktop's `buildRepoMeta`, iOS's likewise).
     */
    private fun repoMeta(rid: String, info: JSONObject, backend: Backend): JSONObject {
        val seeding = (backend.call("seeders", JSONObject().put("rid", rid)) as? RadicleClient.Answer.Ok)
            ?.let { (it.value as? JSONObject)?.optInt("seeding", 0) } ?: 0
        fun v(key: String): Any = info.opt(key) ?: JSONObject.NULL
        return JSONObject()
            .put("rid", info.optString("rid").ifEmpty { rid })
            .put(
                "payloads",
                JSONObject().put(
                    "xyz.radicle.project",
                    JSONObject()
                        .put(
                            "data",
                            JSONObject()
                                .put("name", v("name"))
                                .put("description", v("description"))
                                .put("defaultBranch", v("defaultBranch")),
                        )
                        .put(
                            "meta",
                            JSONObject()
                                .put("head", v("head"))
                                .put("issues", JSONObject().put("open", v("issuesOpen")))
                                .put("patches", JSONObject().put("open", v("patchesOpen"))),
                        ),
                ),
            )
            .put("delegates", info.optJSONArray("delegates") ?: JSONArray())
            .put("threshold", info.opt("threshold") ?: 1)
            .put("visibility", info.opt("visibility") ?: JSONObject().put("type", "public"))
            .put("seeding", seeding)
    }

    private val README_CANDIDATES = listOf("README.md", "README.markdown", "README.txt", "README", "readme.md")

    /** The first readme blob at the root for [revision], plus its `path`; 404 when there's none. */
    private fun readme(rid: String, revision: String, backend: Backend): Reply {
        val tree = when (val a = backend.call("treeAt", JSONObject().put("rid", rid).put("revision", revision).put("path", ""))) {
            is RadicleClient.Answer.Failed -> return failed(a)
            is RadicleClient.Answer.Ok -> a.value as? JSONObject ?: JSONObject()
        }
        val entries = tree.optJSONArray("entries") ?: JSONArray()
        val blobs = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .filter { it.optString("kind") == "blob" }
            .map { it.optString("name") }
            .toSet()
        val name = README_CANDIDATES.firstOrNull { it in blobs } ?: return apiError(404, "no readme")
        return when (val a = backend.call("blobAt", JSONObject().put("rid", rid).put("revision", revision).put("path", name))) {
            is RadicleClient.Answer.Failed -> failed(a)
            is RadicleClient.Answer.Ok -> ok((a.value as? JSONObject ?: JSONObject()).put("path", name))
        }
    }

    // ---------------------------------------------------------------
    // Replies
    // ---------------------------------------------------------------

    private fun unwrap(a: RadicleClient.Answer): Reply = when (a) {
        is RadicleClient.Answer.Ok -> ok(a.value)
        is RadicleClient.Answer.Failed -> failed(a)
    }

    /**
     * A node error as desktop's catch maps it: "not found" phrasing → 404,
     * the node being off or starting → 403 / 503 with its reason, anything
     * else → 500.
     */
    private fun failed(a: RadicleClient.Answer.Failed): Reply = when {
        a.reason == RadicleClient.REASON_DISABLED || a.reason == RadicleClient.REASON_STOPPED ||
            a.reason == RadicleClient.REASON_NOT_READY -> unavailable(a.reason)
        NOT_FOUND.containsMatchIn(a.message) -> apiError(404, a.message)
        else -> apiError(500, a.message)
    }

    private val NOT_FOUND = Regex("not found|does not exist|NotFound", RegexOption.IGNORE_CASE)

    private fun unavailable(reason: String): Reply = json(
        if (reason == RadicleClient.REASON_DISABLED) 403 else 503,
        error(RadicleClient.unavailableMessage(reason)).put("reason", reason),
    )

    private fun ok(value: Any): Reply = json(200, value)

    private fun apiError(status: Int, message: String): Reply = json(status, error(message))

    private fun error(message: String) = JSONObject().put("error", message)

    private fun reply405() = json(405, error("method not allowed"))

    /**
     * No CORS headers: a cross-origin reader can't see the status or the
     * body, and no page may frame or sniff it either.
     */
    private fun json(status: Int, value: Any): Reply = Reply(
        status,
        "application/json; charset=utf-8",
        value.toString().toByteArray(),
        API_HEADERS,
    )

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    private val NO_STORE = mapOf("Cache-Control" to "no-store")

    private val API_HEADERS = NO_STORE + mapOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'",
        "Cross-Origin-Resource-Policy" to "same-origin",
    )

    /**
     * The viewer's own files: only its own script and style run, nothing
     * loads from anywhere else, and no other page may frame it.
     */
    internal val PAGE_HEADERS = NO_STORE + mapOf(
        "Content-Security-Policy" to
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "same-origin",
        "X-Content-Type-Options" to "nosniff",
    )

    private const val VIEWER_HTML = "${RadUrl.INTERNAL_PREFIX}viewer.html"

    private val VIEWER_FILES = listOf(
        VIEWER_HTML to "viewer.html",
        "${RadUrl.INTERNAL_PREFIX}viewer.js" to "viewer.js",
        "${RadUrl.INTERNAL_PREFIX}viewer.css" to "viewer.css",
    )

    private val FALLBACK_HTML = "<!doctype html><title>Radicle</title><p>The repository browser is missing.".toByteArray()

    private const val TAG = "RadApi"
}
