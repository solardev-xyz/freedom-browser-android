package baby.freedom.mobile.browser

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.InetAddress

/**
 * Loopback stand-in for the local content gateway, bound to the same
 * fixed address the ant gateway uses (`127.0.0.1:1633`) so the
 * production interceptor code path is exercised end-to-end with zero
 * test-only indirection.
 *
 * Serves the committed test dapp (`androidTest/assets/testdapp/`) under
 * two Swarm roots — [REF_A] and [REF_B] — with a version marker swapped
 * per root, mirroring a contenthash update. The suite is hermetic: no
 * p2p networking, no external network (uploading the same dapp to the
 * real embedded node is the manual AVD acceptance pass; see
 * docs/dapp-compatibility.md).
 */
class FixtureGateway {
    companion object {
        /** Two fixture roots — "the same site" before/after an update. */
        const val REF_A = "aaaa385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432aaaa"
        const val REF_B = "bbbb385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432bbbb"

        /** 64-hex root no fixture serves — the "misspelled hash" case. */
        const val REF_MISSING =
            "cccc385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432cccc"

        // 1×1 opaque PNG, so <img> loads can be asserted via naturalWidth.
        private const val PIXEL_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNi" +
                "YGD4DwABBAEAX+XLSQAAAABJRU5ErkJggg=="
    }

    private var server: MockWebServer? = null

    /** When true, every request 500s — simulates a dying gateway while
     *  the socket still accepts (for SW offline tests the server is
     *  [shutdown] instead, which refuses connections outright). */
    @Volatile
    var failAll: Boolean = false

    fun start() {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failAll) return MockResponse().setResponseCode(500)
                val path = request.path ?: return MockResponse().setResponseCode(400)
                val version = when {
                    path.startsWith("/bzz/$REF_A/") -> "VERSION_A"
                    path.startsWith("/bzz/$REF_B/") -> "VERSION_B"
                    else -> return MockResponse().setResponseCode(404)
                }
                val file = path
                    .substringAfter("/bzz/")
                    .substringAfter('/')
                    .substringBefore('?')
                    .ifEmpty { "index.html" }
                return serveFixture(file, version)
            }
        }
        s.start(InetAddress.getByName("127.0.0.1"), GATEWAY_PORT)
        server = s
    }

    fun shutdown() {
        runCatching { server?.shutdown() }
        server = null
    }

    private fun serveFixture(file: String, version: String): MockResponse {
        // Synthesized binaries first — committed assets stay text-only.
        when (file) {
            "sub/pixel.png" -> return binaryResponse(
                Base64.decode(PIXEL_PNG_B64, Base64.DEFAULT), "image/png",
            )
            "clip.wav" -> return binaryResponse(silenceWav(), "audio/wav")
        }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val body = runCatching {
            assets.open("testdapp/$file").use { it.readBytes() }
        }.getOrNull() ?: return MockResponse().setResponseCode(404)
        val mime = when (file.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            else -> "application/octet-stream"
        }
        val text = body.toString(Charsets.UTF_8).replace("VERSION_A", version)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "$mime; charset=utf-8")
            .setBody(text)
    }

    private fun binaryResponse(bytes: ByteArray, mime: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", mime)
            .setBody(Buffer().write(bytes))

    /** Minimal valid PCM WAV: 44-byte header + [samples] silence. */
    private fun silenceWav(samples: Int = 8000): ByteArray {
        val dataLen = samples * 2
        val total = 44 + dataLen
        val out = ByteArray(total)
        fun putStr(off: Int, s: String) = s.toByteArray().copyInto(out, off)
        fun putIntLE(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v shr 8) and 0xff).toByte()
            out[off + 2] = ((v shr 16) and 0xff).toByte()
            out[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShortLE(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF"); putIntLE(4, total - 8); putStr(8, "WAVE")
        putStr(12, "fmt "); putIntLE(16, 16); putShortLE(20, 1)
        putShortLE(22, 1); putIntLE(24, 8000); putIntLE(28, 16000)
        putShortLE(32, 2); putShortLE(34, 16)
        putStr(36, "data"); putIntLE(40, dataLen)
        return out
    }
}

/** Port half of [Gateways.SWARM_BASE] (`http://127.0.0.1:1633`). */
val GATEWAY_PORT: Int =
    Gateways.SWARM_BASE.substringAfterLast(':').toInt()
