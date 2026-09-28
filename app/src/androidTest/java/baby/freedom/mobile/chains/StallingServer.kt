package baby.freedom.mobile.chains

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * One-shot loopback HTTP server that sends headers promising a big body,
 * then stalls. [responded] counts down once the headers are out, and
 * [closed] when the client hangs up.
 */
class StallingServer : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/rpcs.json"
    val responded = CountDownLatch(1)
    val closed = CountDownLatch(1)

    @Volatile
    private var client: Socket? = null

    init {
        thread(isDaemon = true, name = "stalling-server") {
            try {
                server.accept().use { s ->
                    client = s
                    val input = s.getInputStream()
                    val headers = input.bufferedReader(Charsets.ISO_8859_1)
                    while (!headers.readLine().isNullOrEmpty()) Unit
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\n\r\n[".toByteArray())
                        flush()
                    }
                    responded.countDown()
                    // Blocks until the client hangs up.
                    while (input.read() >= 0) Unit
                }
            } catch (_: Exception) {
            }
            closed.countDown()
        }
    }

    override fun close() {
        client?.close()
        server.close()
    }
}
