package baby.freedom.mobile.wallet.ledger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * A Ledger over Bluetooth LE (#142): bond (the user compares a code on
 * the Ledger and the phone, once), connect, ask for a bigger ATT MTU,
 * find the model's GATT service ([LedgerBleFraming.SPECS]), turn on its
 * notifications, learn the device's frame size, then carry one APDU at a
 * time in [LedgerBleFraming] frames.
 *
 * Every wait is bounded, and a disconnect fails whatever is waiting at
 * once ([LedgerException.Kind.DISCONNECTED]) — nothing here can hang.
 * Needs BLUETOOTH_CONNECT (API 31+), which the caller has checked.
 */
@SuppressLint("MissingPermission")
internal class LedgerBleLink private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
) : LedgerLink {
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var mtu = LedgerBleFraming.DEFAULT_MTU

    /** The GATT operation in flight: Android allows one at a time. */
    private var op: CompletableDeferred<Int>? = null
    private val ops = Mutex()
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val connected = CompletableDeferred<Unit>()

    @Volatile
    private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected (status $status)")
                fail(LedgerException(LedgerException.Kind.DISCONNECTED))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = done(status)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = done(status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done(status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = done(status)

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { notifications.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value.copyOf())
        }
    }

    private fun done(status: Int) {
        op?.complete(status)
    }

    private fun fail(e: LedgerException) {
        connected.completeExceptionally(e)
        op?.completeExceptionally(e)
        notifications.close(e)
    }

    /** Runs one GATT operation ([start] returns whether Android took it) and waits for its callback's status. */
    private suspend fun gattOp(what: String, timeoutMs: Long, start: (BluetoothGatt) -> Boolean): Int = ops.withLock {
        val g = gatt ?: throw LedgerException(LedgerException.Kind.DISCONNECTED)
        val d = CompletableDeferred<Int>()
        op = d
        try {
            if (!start(g)) throw LedgerException(LedgerException.Kind.DISCONNECTED, cause = IllegalStateException("$what refused"))
            timed(timeoutMs) { d.await() }
        } finally {
            op = null
        }
    }

    private suspend fun open() {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw LedgerException(LedgerException.Kind.NOT_FOUND)
        gatt = g
        timed(CONNECT_MS, LedgerException.Kind.NOT_FOUND) { connected.await() }
        // A bigger ATT MTU means fewer frames; the device says which frame size it then uses.
        runCatching { gattOp("MTU", OP_MS) { it.requestMtu(REQUESTED_MTU) } }
        if (gattOp("discovery", OP_MS) { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) {
            throw LedgerException(LedgerException.Kind.NOT_FOUND)
        }
        val (spec, service) = LedgerBleFraming.SPECS.firstNotNullOfOrNull { s -> g.getService(s.service)?.let { s to it } }
            ?: throw LedgerException(LedgerException.Kind.NOT_FOUND, "This device isn’t a Bluetooth Ledger (Nano X, Stax or Flex).")
        val notify = service.getCharacteristic(spec.notify) ?: throw LedgerException(LedgerException.Kind.NOT_FOUND)
        write = service.getCharacteristic(spec.write) ?: throw LedgerException(LedgerException.Kind.NOT_FOUND)
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(LedgerBleFraming.CCCD) ?: throw LedgerException(LedgerException.Kind.NOT_FOUND)
        val enabled = gattOp("notifications", PAIR_MS) { writeDescriptor(it, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        if (enabled != BluetoothGatt.GATT_SUCCESS) throw LedgerException(LedgerException.Kind.PAIRING_FAILED)
        writeFrame(LedgerBleFraming.MTU_QUERY)
        mtu = timed(OP_MS) {
            var m: Int? = null
            while (m == null) m = LedgerBleFraming.mtuFrom(notifications.receive())
            m
        }
        Log.i(TAG, "connected to a Ledger ${spec.model}, frames of $mtu bytes")
    }

    private suspend fun writeFrame(frame: ByteArray) {
        val c = write ?: throw LedgerException(LedgerException.Kind.DISCONNECTED)
        val status = gattOp("write", OP_MS) { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw LedgerException(LedgerException.Kind.DISCONNECTED)
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    override suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray {
        if (closed) throw LedgerException(LedgerException.Kind.DISCONNECTED)
        // Anything left over from an earlier answer isn't this one's.
        while (notifications.tryReceive().isSuccess) Unit
        for (f in LedgerBleFraming.frames(apdu, mtu)) writeFrame(f)
        val reader = LedgerBleFraming.Reader()
        return timed(timeoutMs) {
            var answer: ByteArray? = null
            while (answer == null) {
                val frame = notifications.receive()
                if ((frame[0].toInt() and 0xff) == LedgerBleFraming.TAG_MTU) continue
                answer = try {
                    reader.add(frame)
                } catch (e: LedgerBleFraming.BadFrame) {
                    throw LedgerException(LedgerException.Kind.DISCONNECTED, cause = e)
                }
            }
            answer
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        fail(LedgerException(LedgerException.Kind.DISCONNECTED))
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    companion object {
        private const val TAG = "LedgerBle"
        private const val CONNECT_MS = 20_000L
        private const val OP_MS = 10_000L

        /** Turning notifications on is what makes an unbonded link pair: the user reads and confirms a code. */
        private const val PAIR_MS = 60_000L
        private const val REQUESTED_MTU = 156

        /**
         * Bonds with [device] if it isn't yet (the user confirms the code
         * on both screens), then connects and gets it ready for APDUs.
         * [onPairing] is called while the pairing code is up.
         */
        suspend fun open(context: Context, device: BluetoothDevice, onPairing: () -> Unit): LedgerBleLink {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                onPairing()
                bond(context, device)
            }
            val link = LedgerBleLink(context.applicationContext, device)
            try {
                link.open()
            } catch (e: Throwable) {
                link.close()
                throw e
            }
            return link
        }

        private suspend fun bond(context: Context, device: BluetoothDevice) {
            val bonded = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                        BluetoothDevice.BOND_NONE -> bonded.complete(false)
                    }
                }
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) return
                if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                    throw LedgerException(LedgerException.Kind.PAIRING_FAILED)
                }
                if (!timed(PAIR_MS, LedgerException.Kind.PAIRING_FAILED) { bonded.await() }) {
                    throw LedgerException(LedgerException.Kind.PAIRING_FAILED)
                }
            } finally {
                runCatching { context.applicationContext.unregisterReceiver(receiver) }
            }
        }

        /** [block] within [ms], else [kind] — a [LedgerException], never a bare timeout. */
        private suspend fun <T> timed(ms: Long, kind: LedgerException.Kind = LedgerException.Kind.TIMEOUT, block: suspend () -> T): T = try {
            withTimeout(ms) { block() }
        } catch (e: TimeoutCancellationException) {
            throw LedgerException(kind)
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw LedgerException(LedgerException.Kind.DISCONNECTED)
        }
    }
}
