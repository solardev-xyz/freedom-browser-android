package baby.freedom.mobile.agent

import baby.freedom.mobile.browser.NodeApiGuard
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * `node_request`'s arguments, as desktop's `operations.js` checks them
 * (`NODE_REQUEST`), with the path already canonical ([NodePaths]): the
 * one string the card shows, the classifier judges, the journal keeps and
 * the node is sent.
 */
internal data class NodeRequestArgs(
    val service: String,
    val transport: String,
    val method: String,
    val path: String,
    /** Lowercased names, in the order given. */
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    val write: Boolean get() = method != "GET" && method != "HEAD"

    companion object {
        val SERVICES = listOf("ant", "radicle", "ipfs")
        val METHODS = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        const val MAX_BODY_BYTES = 65_536
        private const val MAX_HEADERS = 32
        private const val MAX_HEADER_VALUE = 4_096
        private val HEADER_NAME = Regex("^[a-z0-9][a-z0-9-]*$")

        /** Desktop's blocked names. */
        private val BLOCKED = setOf("authorization", "cookie", "host", "origin", "proxy-authorization", "referer")

        /**
         * Android's own: what frames the request, which the platform's HTTP
         * stack sets itself (desktop's fetch refuses these too, as forbidden
         * request headers), and anything that would keep a connection open
         * to be used again (every request here gets a fresh one).
         */
        private val FRAMING = setOf(
            "connection", "content-length", "expect", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
        )
        private val KEYS = setOf("service", "transport", "request")
        private val REQUEST_KEYS = setOf("method", "path", "headers", "body")

        fun parse(args: JSONObject): NodeRequestArgs {
            args.keys().forEach { if (it !in KEYS) throw invalid("$it is not a node_request argument") }
            val service = string(args, "service").trim()
            if (service !in SERVICES) throw invalid("service must be one of: ${SERVICES.joinToString(", ")}")
            val transport = string(args, "transport").trim()
            val expected = if (service == "ipfs") "gateway" else "http"
            if (transport != expected) throw invalid("transport must be $expected for $service")
            val request = args.opt("request") as? JSONObject ?: throw invalid("request must be an object")
            request.keys().forEach { if (it !in REQUEST_KEYS) throw invalid("request.$it is not a node_request field") }
            val method = string(request, "request.method", request.opt("method")).trim().uppercase(java.util.Locale.ROOT)
            if (method !in METHODS) throw invalid("request.method must be one of: ${METHODS.joinToString(", ")}")
            if (service == "ipfs" && method != "GET" && method != "HEAD") {
                throw invalid("IPFS native gateway requests support only GET and HEAD")
            }
            val path = NodePaths.canonical(string(request, "request.path", request.opt("path")))
            val headers = LinkedHashMap<String, String>()
            request.opt("headers")?.takeIf { it != JSONObject.NULL }?.let { raw ->
                val h = raw as? JSONObject ?: throw invalid("request.headers must be an object")
                if (h.length() > MAX_HEADERS) throw invalid("request.headers cannot contain more than $MAX_HEADERS fields")
                for (rawName in h.keys()) {
                    val name = rawName.trim().lowercase(java.util.Locale.ROOT)
                    if (!HEADER_NAME.matches(name) || name in BLOCKED || name in FRAMING ||
                        name.startsWith("sec-") || name.startsWith("proxy-")
                    ) {
                        throw invalid("request header is not allowed: $rawName")
                    }
                    val value = h.opt(rawName) as? String ?: throw invalid("request.headers.$rawName must be a string")
                    if (value.length > MAX_HEADER_VALUE || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                        throw invalid("request header value is invalid: $rawName")
                    }
                    headers[name] = value
                }
            }
            val body = request.opt("body")?.takeIf { it != JSONObject.NULL }?.let {
                val b = it as? String ?: throw invalid("request.body must be a string")
                if (b.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
                    throw invalid("request.body cannot exceed $MAX_BODY_BYTES UTF-8 bytes")
                }
                if (method == "GET" || method == "HEAD") throw invalid("$method node requests cannot include a body")
                b
            }
            return NodeRequestArgs(service, transport, method, path, headers, body)
        }

        private fun string(o: JSONObject, field: String, value: Any? = o.opt(field)): String {
            val s = value as? String
            if (s == null || s.isBlank()) throw invalid("$field must be a non-empty string")
            return s
        }
    }
}

private fun invalid(message: String) = ToolError(ToolError.INVALID_ARGUMENT, message)

/**
 * Desktop's `canonicalNodeRequestPath`: one unambiguous route for
 * approval, classification, journaling and dispatch. A direct absolute
 * path — no `//` start, backslash, fragment, control character, stray
 * `%`, dot segment, or encoded dot, slash, backslash or percent in the
 * route (`%2e`, `%2f`, `%5c`, `%25`) — whose encoded letters, digits and
 * `_~-` are decoded and other escapes upper-cased, then serialized as
 * WHATWG's URL parser does (`new URL(…).pathname + search`): what the
 * route's and query's percent-encode sets name is encoded as UTF-8, an
 * empty `?` dropped. Anything a JavaScript string could hold that a
 * Kotlin one renders differently (a lone surrogate) is refused rather
 * than guessed at.
 */
internal object NodePaths {
    private const val MAX = 2048

    /** WHATWG's path percent-encode set, past the controls (refused before): space " # < > ? ` { } ^. */
    private const val PATH_SET = " \"#<>?`{}^"

    /** The special-query percent-encode set: space " # < > '. */
    private const val QUERY_SET = " \"#<>'"

    /** What JavaScript's `String.prototype.trim` takes off. */
    private fun jsSpace(c: Char) = c in "\t\n\u000B\u000C\r       　﻿" ||
        c in ' '..' '

    private val ENCODED_SEPARATOR = Regex("%(?:2e|2f|5c|25)", RegexOption.IGNORE_CASE)
    private val STRAY_PERCENT = Regex("%(?![a-fA-F0-9]{2})")
    private val ESCAPE = Regex("%([a-fA-F0-9]{2})")

    fun canonical(value: String): String {
        val path = value.trim(::jsSpace)
        val route = path.substringBefore('?')
        if (path.isEmpty() || path.length > MAX || !path.startsWith("/") || path.startsWith("//") ||
            '\\' in path || '#' in path || path.any { it.code <= 31 || it.code == 127 } ||
            STRAY_PERCENT.containsMatchIn(path) || ENCODED_SEPARATOR.containsMatchIn(route) ||
            route.split('/').any { it == "." || it == ".." } || hasLoneSurrogate(path)
        ) {
            throw invalid("Use a direct absolute API path without fragments, encoded separators or dot segments.")
        }
        val decoded = ESCAPE.replace(route) { m ->
            val c = m.groupValues[1].toInt(16).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '~' || c == '-') c.toString() else m.value.uppercase()
        }
        val query = if ('?' in path) path.substringAfter('?') else ""
        return encode(decoded, PATH_SET) + if (query.isEmpty()) "" else "?" + encode(query, QUERY_SET)
    }

    private fun encode(s: String, set: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp < 128 && s[i] !in set) {
                out.append(s[i])
            } else {
                for (b in s.substring(i, i + n).toByteArray(Charsets.UTF_8)) out.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            i += n
        }
        return out.toString()
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }
}

/**
 * The money floor on the Swarm node's spending routes (#598): desktop's
 * (`/stamps`, `/stake`, `/chequebook`, `/wallet`, `/transactions`, any
 * case) and every other route [NodeApiGuard] knows signs a transaction
 * (ant's `/v0/storage/…` and `/v0/settlement/…`). A write there is
 * [Effect.FINANCIAL] at least, whatever the classifier says; a GET or
 * HEAD is a read like any other.
 */
internal object NodeSpending {
    private val DESKTOP = Regex("^/(?:stamps|stake|chequebook|wallet|transactions)(?:/|$)", RegexOption.IGNORE_CASE)

    /** [path] is canonical ([NodePaths]). */
    fun isSpendingWrite(service: String, method: String, path: String): Boolean {
        if (service != "ant" || method == "GET" || method == "HEAD") return false
        val route = path.substringBefore('?')
        return DESKTOP.containsMatchIn(route) || NodeApiGuard.isChainWrite(method, SwarmNode.GATEWAY_URL + route)
    }

    /** Desktop's floor: a spending write is financial, a DELETE destructive, any other write a reversible change. */
    fun minimumEffect(service: String, method: String, path: String): Effect = when {
        isSpendingWrite(service, method, path) -> Effect.FINANCIAL
        method == "GET" || method == "HEAD" -> Effect.READ
        method == "DELETE" -> Effect.DESTRUCTIVE
        else -> Effect.REVERSIBLE_ADMIN
    }
}

/** A node's answer, as the model gets it (desktop's `normalizedResponse`). */
internal data class NodeResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String,
    val bytes: Int,
) {
    fun json(): JSONObject = JSONObject()
        .put("status", status)
        .put("statusText", statusText)
        .put("headers", JSONObject(headers as Map<*, *>))
        .put("body", body)
        .put("bytes", bytes)

    companion object {
        /** Desktop's `RESPONSE_HEADER_ALLOWLIST`. */
        val HEADERS = setOf(
            "content-length", "content-type", "etag", "last-modified", "location", "swarm-blocklist", "swarm-feed-index", "swarm-tag",
        )
        const val MAX_BYTES = 65_536

        fun of(o: JSONObject): NodeResponse = NodeResponse(
            o.optInt("status"),
            o.optString("statusText"),
            o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty(),
            o.optString("body"),
            o.optInt("bytes"),
        )
    }
}

/** The response was over [NodeResponse.MAX_BYTES]. */
internal class NodeResponseTooLarge : Exception("Node response exceeds the ${NodeResponse.MAX_BYTES}-byte limit")

/**
 * Sends one request to a node on this phone and reads its answer: no
 * proxy, no redirect followed, a fresh connection that's closed after
 * (never a pooled one the platform might send a write on twice), the
 * body sent with its exact length, and at most [NodeResponse.MAX_BYTES]
 * read back whatever `Content-Length` says. [connection] is handed the
 * open connection first, so whoever runs this can close it from another
 * thread to end a blocked read.
 */
internal object NodeHttp {
    fun send(base: String, args: NodeRequestArgs, connection: (HttpURLConnection) -> Unit = {}): NodeResponse {
        val url = URL(base.trimEnd('/') + args.path)
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection(conn)
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 5_000
            conn.readTimeout = 0
            conn.requestMethod = args.method
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Accept-Encoding", "identity")
            args.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val body = args.body?.toByteArray(Charsets.UTF_8)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val headers = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (k, v) ->
                val name = k?.lowercase(java.util.Locale.ROOT) ?: return@forEach
                if (name in NodeResponse.HEADERS && !v.isNullOrEmpty()) headers[name] = v.joinToString(", ")
            }
            headers["content-length"]?.toLongOrNull()?.let { if (it > NodeResponse.MAX_BYTES) throw NodeResponseTooLarge() }
            val text = if (args.method == "HEAD") "" else read(conn)
            return NodeResponse(status, conn.responseMessage.orEmpty(), headers, text, text.toByteArray(Charsets.UTF_8).size)
        } finally {
            conn.disconnect()
        }
    }

    private fun read(conn: HttpURLConnection): String {
        val stream = (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream) ?: return ""
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > NodeResponse.MAX_BYTES) throw NodeResponseTooLarge()
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    /** The embedded Swarm node's gateway: the only Bee API the agent may reach. */
    val ANT_BASE: String get() = SwarmNode.GATEWAY_URL
}

/** Where a node request may go, from the live node state; a [ToolError] when it can't go anywhere. */
internal object NodeEndpoints {
    fun baseFor(service: String, s: NodeSnapshot?): String {
        if (service == "radicle") {
            throw ToolError(
                ToolError.CAPABILITY_UNAVAILABLE,
                "Raw Radicle requests are unavailable for the embedded node; use node status or lifecycle controls",
            )
        }
        s ?: throw ToolError(ToolError.CAPABILITY_UNAVAILABLE, "Freedom's node state isn't available right now.")
        return when (service) {
            "ant" -> when {
                s.externalSwarm -> throw ToolError(
                    ToolError.CAPABILITY_UNAVAILABLE,
                    "Swarm is served by the user's own node set in Freedom's settings; Freedom sends agent requests " +
                        "only to the node it runs itself.",
                )
                !s.runNode || s.swarm.status != NodeStatus.Running ->
                    throw ToolError(ToolError.CAPABILITY_UNAVAILABLE, "The ant node API is not currently available")
                else -> NodeHttp.ANT_BASE
            }
            "ipfs" -> when {
                s.externalIpfs -> throw ToolError(
                    ToolError.CAPABILITY_UNAVAILABLE,
                    "IPFS is served by the user's own gateway set in Freedom's settings; Freedom sends agent requests " +
                        "only to the node it runs itself.",
                )
                !s.runNode || s.ipfs.status != IpfsStatus.Running || !isLoopback(s.ipfs.gatewayUrl) ->
                    throw ToolError(ToolError.CAPABILITY_UNAVAILABLE, "The ipfs node API is not currently available")
                else -> s.ipfs.gatewayUrl
            }
            else -> throw ToolError(ToolError.CAPABILITY_UNAVAILABLE, "This node request transport is unavailable")
        }
    }

    private fun isLoopback(url: String): Boolean = Regex("^http://127\\.0\\.0\\.1:\\d{1,5}/?$").matches(url)
}

/** The SHA-256 of [text]'s UTF-8 bytes, in hex: how the journal keeps a request body. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
