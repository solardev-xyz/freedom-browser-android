package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.browser.isOnionHost
import baby.freedom.mobile.chains.RpcUrls
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** The one network seam [ChainDataRouter] uses; tests script it. */
internal fun interface RpcTransport {
    /**
     * POST the JSON [body] to [url] and return a 2xx response's body.
     * Anything else — a non-2xx status, a refused address, a body over
     * the cap — is an [IOException]. The whole exchange is bounded by
     * [timeoutMs] ([RpcTimeoutException] past it), and a cancelled
     * caller is let go at once.
     */
    suspend fun post(url: String, body: String, timeoutMs: Long): String
}

/** An RPC exchange that ran past its deadline. */
internal class RpcTimeoutException(message: String) : IOException(message)

/**
 * The production [RpcTransport]: HTTP/1.1 over a socket this class opens
 * itself, to an address it has checked.
 *
 * [RpcUrls] refuses a URL that *names* the local network, but a public
 * name can still resolve to one — a chainlist entry pointing its DNS at
 * `192.168.1.1`, or at the device's own `127.0.0.1` services. So the host
 * is resolved here, every address is checked (all of them must be public;
 * for a loopback URL, the user's own node, all must be loopback), and the
 * socket connects to exactly the checked address — no second lookup a
 * rebinding DNS server could answer differently. TLS then runs over that
 * socket with SNI and hostname verification for the URL's host, through
 * the platform's default trust (network security config included).
 *
 * Nothing but the request goes out: no cookies, no referrer, the
 * platform's own `http.agent` string (what `HttpURLConnection` sends).
 * Redirects aren't followed (a JSON-RPC endpoint has no business sending
 * one, and following it would skip the address check). The blocking
 * work runs on a pool thread; a deadline or cancellation closes the
 * socket from another thread, since a read blocked on a stalled server
 * may not notice a close from its own until its timeout.
 */
internal class PinnedHttpTransport(
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val maxBytes: Long = MAX_RESPONSE_BYTES,
) : RpcTransport {

    override suspend fun post(url: String, body: String, timeoutMs: Long): String {
        val target = Target.of(url) ?: throw IOException("not an RPC URL")
        val exchange = Exchange()
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    pool.execute {
                        // Ignored if the caller has been let go already.
                        cont.resumeWith(runCatching { exchange.run(target, body, timeoutMs) })
                    }
                    cont.invokeOnCancellation { pool.execute { exchange.abort() } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw RpcTimeoutException("no answer from ${target.host} within ${timeoutMs}ms")
        }
    }

    private class Target(
        val https: Boolean,
        val host: String,
        val port: Int,
        val path: String,
        val hostHeader: String,
        val loopback: Boolean,
    ) {

        companion object {
            fun of(url: String): Target? {
                if (RpcUrls.normalize(url) != url) return null
                val uri = try {
                    URI(url)
                } catch (_: Exception) {
                    return null
                }
                val https = uri.scheme.equals("https", ignoreCase = true)
                val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
                val port = if (uri.port != -1) uri.port else if (https) 443 else 80
                val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = uri.rawAuthority ?: return null
                return Target(https, host, port, path, hostHeader, RpcUrls.isLoopbackUrl(url))
            }
        }
    }

    /** One request's socket, closable from any thread. */
    private inner class Exchange {
        private var socket: Socket? = null

        @Volatile
        private var aborted = false

        @Synchronized
        fun abort() {
            aborted = true
            socket?.let { runCatching { it.close() } }
        }

        @Synchronized
        private fun adopt(s: Socket) {
            if (aborted) {
                runCatching { s.close() }
                throw IOException("cancelled")
            }
            socket = s
        }

        fun run(target: Target, body: String, timeoutMs: Long): String {
            // Never resolved here, where the name would go to DNS (#143):
            // an onion RPC would need Tor, which this transport doesn't use.
            if (isOnionHost(target.host)) throw IOException("${target.host}: .onion RPCs aren't supported")
            val addresses = resolve(target.host)
            if (addresses.isEmpty()) throw IOException("${target.host} has no address")
            val bad = addresses.firstOrNull { !allowed(it, target.loopback) }
            if (bad != null) {
                throw IOException("${target.host} resolves to a local-network address; refused")
            }
            var last: IOException? = null
            for (address in addresses) {
                val plain = Socket()
                adopt(plain)
                try {
                    plain.connect(InetSocketAddress(address, target.port), timeoutMs.toInt())
                    plain.soTimeout = timeoutMs.toInt()
                    val s = if (target.https) tls(plain, target) else plain
                    try {
                        return exchange(s, target, body)
                    } finally {
                        runCatching { s.close() }
                    }
                } catch (e: IOException) {
                    runCatching { plain.close() }
                    // Only a failed connect tries the host's next address;
                    // a request that went out isn't sent twice.
                    if (plain.isConnected || aborted) throw e
                    last = e
                }
            }
            throw last ?: IOException("no address of ${target.host} answered")
        }

        private fun tls(plain: Socket, target: Target): Socket {
            val factory = HttpsURLConnection.getDefaultSSLSocketFactory()
            val ssl = factory.createSocket(plain, target.host, target.port, true) as SSLSocket
            adopt(ssl)
            ssl.sslParameters = ssl.sslParameters.apply {
                if (!isIpLiteral(target.host)) serverNames = listOf(SNIHostName(target.host))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, ssl.session)) {
                throw SSLPeerUnverifiedException("certificate doesn't match ${target.host}")
            }
            return ssl
        }
    }

    private fun exchange(s: Socket, target: Target, body: String): String {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("POST ").append(target.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(target.hostHeader).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Accept: application/json\r\n")
            System.getProperty("http.agent")?.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7e } }
                ?.let { append("User-Agent: ").append(it).append("\r\n") }
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        s.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(payload)
            flush()
        }
        val input = BufferedInputStream(s.getInputStream())
        var status: Int
        var headers: Map<String, String>
        do {
            val statusLine = readLine(input) ?: throw IOException("connection closed before a response")
            status = Regex("^HTTP/1\\.[01] (\\d{3})").find(statusLine)?.groupValues?.get(1)?.toInt()
                ?: throw IOException("not an HTTP response")
            headers = readHeaders(input)
        } while (status in 100..199)
        if (status !in 200..299) throw IOException("HTTP $status")
        val bytes = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
            headers["content-length"] != null -> {
                val n = headers["content-length"]!!.trim().toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IOException("bad content-length")
                if (n > maxBytes) throw IOException("response exceeds $maxBytes bytes")
                readExactly(input, n.toInt())
            }
            else -> readToEnd(input)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("connection closed in headers")
            if (line.isEmpty()) return headers
            total += line.length
            if (total > MAX_HEADER_BYTES) throw IOException("headers too large")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /** One CRLF- (or LF-) terminated line, `null` at end of stream. */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(b.toChar())
            if (out.length > MAX_HEADER_BYTES) throw IOException("header line too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArrayOutputStream {
        val out = ByteArrayOutputStream(minOf(n, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size, left))
            if (r < 0) throw IOException("response cut short")
            out.write(buf, 0, r)
            left -= r
        }
        return out
    }

    private fun readToEnd(input: InputStream): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) return out
            total += r
            if (total > maxBytes) throw IOException("response exceeds $maxBytes bytes")
            out.write(buf, 0, r)
        }
    }

    private fun readChunked(input: InputStream): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("response cut short")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?.takeIf { it >= 0 } ?: throw IOException("bad chunk size")
            if (size == 0L) {
                // Trailers, up to the blank line.
                while (!readLine(input).isNullOrEmpty()) Unit
                return out
            }
            if (out.size() + size > maxBytes) throw IOException("response exceeds $maxBytes bytes")
            readExactly(input, size.toInt()).writeTo(out)
            readLine(input) // the CRLF after the chunk
        }
    }

    companion object {
        /** `eth_getLogs` answers can be large; nothing a wallet reads comes near this. */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024

        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "chain-rpc").apply { isDaemon = true }
        }

        private fun isIpLiteral(host: String) =
            ':' in host || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

        /**
         * Whether a resolved [address] may be connected to: a loopback
         * URL (the user's node on this device) only to loopback, any
         * other URL only to a public address — [RpcUrls.isInternal]'s
         * ranges, loopback and the unspecified address included.
         */
        internal fun allowed(address: InetAddress, loopbackUrl: Boolean): Boolean {
            if (loopbackUrl) return address.isLoopbackAddress
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val literal = address.hostAddress?.substringBefore('%') ?: return false
            return !RpcUrls.isInternal(if (address is Inet6Address) "[$literal]" else literal)
        }
    }
}
