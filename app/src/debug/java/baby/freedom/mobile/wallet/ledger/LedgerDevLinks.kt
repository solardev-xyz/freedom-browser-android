package baby.freedom.mobile.wallet.ledger

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Debug builds only: a Ledger emulator (Speculos) the Connect Ledger page
 * lists next to Bluetooth Ledgers, so the whole flow — frames, APDUs, the
 * device's screens — can be exercised on an emulator with no Bluetooth
 * Ledger (#142). `scripts/ledger-speculos-bridge.py` stands in for the
 * Ledger's Bluetooth side: the same [LedgerBleFraming] frames go over
 * TCP, each with a two-byte length in front, as notifications would.
 *
 * Off unless `files/ledger-dev-links` exists (written with `adb shell
 * run-as`): one `name=host:port` per line.
 */
internal object LedgerDevLinks {
    private const val PREFIX = "dev:"

    fun devices(context: Context): List<LedgerDevice> = runCatching {
        File(context.filesDir, "ledger-dev-links").readLines().mapNotNull { line ->
            val (name, target) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            LedgerDevice(PREFIX + target.trim(), name.trim(), paired = true)
        }
    }.getOrDefault(emptyList())

    fun handles(id: String): Boolean = id.startsWith(PREFIX)

    suspend fun open(context: Context, id: String): LedgerLink? {
        if (!handles(id)) return null
        val target = id.removePrefix(PREFIX)
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toIntOrNull() ?: throw LedgerException(LedgerException.Kind.NOT_FOUND)
        return withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 250
                TcpFrameLink(socket).also { it.start() }
            } catch (e: java.io.IOException) {
                runCatching { socket.close() }
                throw LedgerException(LedgerException.Kind.NOT_FOUND, cause = e)
            }
        }
    }

    private class TcpFrameLink(private val socket: Socket) : LedgerLink {
        private var mtu = LedgerBleFraming.DEFAULT_MTU
        private val pending = ByteArrayOutputStream()

        suspend fun start() {
            send(LedgerBleFraming.MTU_QUERY)
            val deadline = System.currentTimeMillis() + 5_000
            while (true) {
                LedgerBleFraming.mtuFrom(frame(deadline))?.let {
                    mtu = it
                    return
                }
            }
        }

        private fun send(frame: ByteArray) {
            socket.getOutputStream().write(byteArrayOf((frame.size shr 8).toByte(), frame.size.toByte()) + frame)
            socket.getOutputStream().flush()
        }

        /** The next notified frame, read in 250 ms slices so a cancel or [deadline] ends the wait. */
        private suspend fun frame(deadline: Long): ByteArray = withContext(Dispatchers.IO) {
            val buf = ByteArray(1024)
            while (true) {
                val have = pending.toByteArray()
                if (have.size >= 2) {
                    val len = ((have[0].toInt() and 0xff) shl 8) or (have[1].toInt() and 0xff)
                    if (have.size >= 2 + len) {
                        pending.reset()
                        pending.write(have, 2 + len, have.size - 2 - len)
                        return@withContext have.copyOfRange(2, 2 + len)
                    }
                }
                coroutineContext.ensureActive()
                if (System.currentTimeMillis() > deadline) throw LedgerException(LedgerException.Kind.TIMEOUT)
                val n = try {
                    socket.getInputStream().read(buf)
                } catch (e: SocketTimeoutException) {
                    0
                } catch (e: java.io.IOException) {
                    throw LedgerException(LedgerException.Kind.DISCONNECTED, cause = e)
                }
                if (n < 0) throw LedgerException(LedgerException.Kind.DISCONNECTED)
                pending.write(buf, 0, n)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray {
            val deadline = System.currentTimeMillis() + timeoutMs
            withContext(Dispatchers.IO) {
                try {
                    LedgerBleFraming.frames(apdu, mtu).forEach { send(it) }
                } catch (e: java.io.IOException) {
                    throw LedgerException(LedgerException.Kind.DISCONNECTED, cause = e)
                }
            }
            val reader = LedgerBleFraming.Reader()
            while (true) {
                val f = frame(deadline)
                if ((f[0].toInt() and 0xff) == LedgerBleFraming.TAG_MTU) continue
                val answer = try {
                    reader.add(f)
                } catch (e: LedgerBleFraming.BadFrame) {
                    throw LedgerException(LedgerException.Kind.DISCONNECTED, cause = e)
                }
                if (answer != null) return answer
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
