package baby.freedom.mobile.ens

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one HTTP seam [EnsResolver] uses, for JSON-RPC and CCIP-Read
 * gateway fetches alike. Injectable so unit tests can script both
 * sides of a resolution without a network.
 */
internal interface EnsHttp {
    class Reply(val code: Int, val body: String)

    /**
     * Perform [method] against [url]. [maxBytes] caps the body: a
     * longer one (per `content-length` or as read) is an [IOException],
     * not a truncated success. [timeoutMs] bounds connect and read.
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int,
        maxBytes: Long,
        followRedirects: Boolean,
    ): Reply

    object Default : EnsHttp {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): Reply {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = minOf(timeoutMs, 8_000)
                readTimeout = timeoutMs
                instanceFollowRedirects = followRedirects
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) doOutput = true
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (conn.contentLengthLong > maxBytes) {
                    throw IOException("response exceeds $maxBytes bytes")
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { readBounded(it, maxBytes) }.orEmpty()
                return Reply(code, text)
            } finally {
                conn.disconnect()
            }
        }

        private fun readBounded(input: java.io.InputStream, maxBytes: Long): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("response exceeds $maxBytes bytes")
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
