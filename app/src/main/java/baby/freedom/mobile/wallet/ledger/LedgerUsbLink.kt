package baby.freedom.mobile.wallet.ledger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Ledger over USB (#319), for the Nano S and Nano S Plus, which have no
 * Bluetooth, and any Ledger on a cable: Android's USB host API, the
 * Ledger's generic HID interface (interface 0, whatever else the device
 * offers next to it — U2F, WebUSB), one APDU at a time in
 * [LedgerHidFraming] packets.
 *
 * Every wait is bounded and cut into [SLICE_MS] reads, so a cancel, a
 * timeout or an unplug ends it within a slice: an unplugged Ledger —
 * gone from [UsbManager.getDeviceList] — fails what's waiting with
 * [LedgerException.Kind.DISCONNECTED]. [close] releases the interface
 * and the connection; plugged back in, the next conversation opens a new
 * link (Android asks for access again: a grant lasts until unplugged).
 */
internal class LedgerUsbLink(private val pipe: Pipe) : LedgerLink {
    /**
     * The USB side of a link: one interrupt transfer each way, whether
     * the device is still plugged in, and letting it go. [UsbPipe] on a
     * phone; a fake in a test.
     */
    interface Pipe {
        /** Bytes written from [buf] within [timeoutMs], or negative. */
        fun write(buf: ByteArray, timeoutMs: Int): Int

        /** Bytes read into [buf] within [timeoutMs]; zero or negative when none came or the read failed. */
        fun read(buf: ByteArray, timeoutMs: Int): Int

        fun attached(): Boolean

        /** Releases the interface and closes the connection. Called once, never during a transfer. */
        fun release()
    }

    /** Held around every transfer and around [close], so the pipe is never released under one. */
    private val io = ReentrantLock()

    @Volatile
    private var closed = false

    override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        if (closed) throw unplugged()
        val deadline = now() + timeoutMs
        val buf = ByteArray(LedgerHidFraming.PACKET)
        // Anything left over from an earlier answer isn't this one's.
        for (i in 0 until DRAIN_MAX) if (transfer { pipe.read(buf, 1) } <= 0) break
        for (p in LedgerHidFraming.packets(apdu)) {
            if (transfer { pipe.write(p, WRITE_MS) } != p.size) {
                throw if (closed || !pipe.attached()) unplugged() else LedgerException(LedgerException.Kind.DISCONNECTED, cannotTalk())
            }
        }
        val reader = LedgerHidFraming.Reader()
        var quickFailures = 0
        var answer: ByteArray? = null
        while (answer == null) {
            coroutineContext.ensureActive()
            if (now() >= deadline) throw LedgerException(LedgerException.Kind.TIMEOUT)
            val started = now()
            val n = transfer { pipe.read(buf, SLICE_MS) }
            if (n <= 0) {
                if (closed || !pipe.attached()) throw unplugged()
                // A read that failed well before its slice ran out is an error, not a quiet
                // device: don't spin on one that keeps failing while Android still lists it.
                if (now() - started < SLICE_MS / 2) {
                    if (++quickFailures > QUICK_FAILURES_MAX) throw LedgerException(LedgerException.Kind.DISCONNECTED, cannotTalk())
                    Thread.sleep(QUICK_FAILURE_PAUSE_MS)
                }
                continue
            }
            quickFailures = 0
            answer = try {
                reader.add(buf.copyOf(n))
            } catch (e: LedgerHidFraming.BadPacket) {
                throw LedgerException(LedgerException.Kind.DISCONNECTED, cannotTalk(), e)
            }
        }
        checkNotNull(answer)
    }

    /** One transfer, or -1 once [close]d: never runs on a released pipe. */
    private inline fun transfer(block: () -> Int): Int = io.withLock { if (closed) -1 else block() }

    override fun close() {
        if (closed) return
        closed = true
        io.withLock { runCatching { pipe.release() } }
    }

    /** A plugged-in Ledger's claimed HID interface, as a [Pipe]. */
    private class UsbPipe(
        private val manager: UsbManager,
        private val device: UsbDevice,
        private val connection: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val input: UsbEndpoint,
        private val output: UsbEndpoint,
    ) : Pipe {
        override fun write(buf: ByteArray, timeoutMs: Int) = connection.bulkTransfer(output, buf, buf.size, timeoutMs)
        override fun read(buf: ByteArray, timeoutMs: Int) = connection.bulkTransfer(input, buf, buf.size, timeoutMs)
        override fun attached() = attached(manager, device)
        override fun release() {
            runCatching { connection.releaseInterface(intf) }
            runCatching { connection.close() }
        }
    }

    companion object {
        private const val TAG = "LedgerUsb"

        /** Monotonic milliseconds: a wall-clock change can't stretch or cut a wait. */
        private fun now(): Long = System.nanoTime() / 1_000_000

        /** How long one read waits before the link checks for a cancel, a timeout or an unplug. */
        private const val SLICE_MS = 250
        private const val WRITE_MS = 2_000
        private const val DRAIN_MAX = 8
        private const val QUICK_FAILURES_MAX = 20
        private const val QUICK_FAILURE_PAUSE_MS = 50L

        /** How long Android's "Allow Freedom to access …?" is waited for. */
        private const val PERMISSION_MS = 60_000L

        /** The Ledgers plugged in now: Ledger's vendor id, nothing else. */
        fun devices(manager: UsbManager?): List<UsbDevice> =
            manager?.deviceList?.values.orEmpty().filter { it.vendorId == LedgerHidFraming.VENDOR_ID }.sortedBy { it.deviceName }

        /** The name a plugged-in Ledger shows: its model, from its product id. */
        fun name(device: UsbDevice): String =
            LedgerHidFraming.model(device.productId)?.let { "Ledger $it" }
                ?: runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Ledger"

        fun attached(manager: UsbManager, device: UsbDevice): Boolean =
            manager.deviceList?.values.orEmpty().any { it.deviceName == device.deviceName }

        /**
         * Asks for access to [device] if the app hasn't got it
         * ([onPermission] is called while Android's prompt is up), then
         * opens it and claims its HID interface.
         */
        suspend fun open(context: Context, manager: UsbManager, device: UsbDevice, onPermission: () -> Unit): LedgerUsbLink {
            if (!manager.hasPermission(device)) {
                onPermission()
                requestPermission(context.applicationContext, manager, device)
            }
            return withContext(Dispatchers.IO) {
                val (intf, input, output) = hidInterface(device)
                    ?: throw LedgerException(LedgerException.Kind.NOT_FOUND, cannotTalk())
                val connection = manager.openDevice(device)
                    ?: throw if (!attached(manager, device)) unplugged() else LedgerException(LedgerException.Kind.NOT_FOUND, cannotTalk())
                if (!connection.claimInterface(intf, true)) {
                    runCatching { connection.close() }
                    throw LedgerException(LedgerException.Kind.NOT_FOUND, cannotTalk())
                }
                Log.i(TAG, "connected to a ${name(device)} over USB (interface ${intf.id})")
                LedgerUsbLink(UsbPipe(manager, device, connection, intf, input, output))
            }
        }

        /**
         * Android's prompt for [device] alone, waited for up to
         * [PERMISSION_MS]. The answer is taken from [UsbManager.hasPermission],
         * not from the broadcast (the pending intent is immutable, so it
         * carries nothing, and it's ours: only its arrival counts).
         * Refused, it's a [LedgerException.Kind.PERMISSION]; unplugged
         * meanwhile, DISCONNECTED.
         */
        private suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice) {
            val action = context.packageName + ACTION_PERMISSION_SUFFIX
            val answered = CompletableDeferred<Unit>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    answered.complete(Unit)
                }
            }
            // The pending intent is ours, so its broadcast comes as this app: a non-exported receiver gets it.
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val pending = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                manager.requestPermission(device, pending)
                withTimeoutOrNull(PERMISSION_MS) { answered.await() }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
            if (manager.hasPermission(device)) return
            throw if (!attached(manager, device)) {
                unplugged()
            } else {
                LedgerException(LedgerException.Kind.PERMISSION, Strings.said(R.string.signing_ledger_usb_permission_refused))
            }
        }

        /**
         * [device]'s generic HID interface and its interrupt endpoints:
         * interface 0 on every Ledger (the one `hw-transport-node-hid` and
         * Ledger Live's Android HID module talk to), else the first HID
         * interface that has both — never the U2F one, which comes later.
         */
        private fun hidInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                .sortedBy { it.id }
            for (intf in candidates) {
                val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: continue
                val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
                return Triple(intf, input, output)
            }
            return null
        }

        private const val ACTION_PERMISSION_SUFFIX = ".LEDGER_USB_PERMISSION"

        fun unplugged() = LedgerException(LedgerException.Kind.DISCONNECTED, Strings.said(R.string.signing_ledger_usb_unplugged))

        private fun cannotTalk() = Strings.said(R.string.signing_ledger_usb_cannot_talk)
    }
}
