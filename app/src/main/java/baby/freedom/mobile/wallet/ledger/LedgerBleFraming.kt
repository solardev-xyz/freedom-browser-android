package baby.freedom.mobile.wallet.ledger

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Ledger's APDU-over-BLE framing (#142), as `@ledgerhq/hw-transport-ble`
 * speaks it: an APDU is cut into frames of at most [mtu] bytes, each
 * `0x05 ‖ index (u16) ‖ …`, the first also carrying the APDU's length
 * (u16) before its data. The answer comes back the same way, one
 * notification per frame.
 *
 * Pure: the GATT side is [LedgerBleLink]; this is what it writes and how
 * it reads back what the device notified.
 */
internal object LedgerBleFraming {
    const val TAG_APDU = 0x05
    const val TAG_MTU = 0x08

    /** The frame size a device that never answers the MTU query takes (a 23-byte ATT MTU less 3). */
    const val DEFAULT_MTU = 20

    /** The query whose answer's sixth byte is the frame size the device uses ([mtuFrom]). */
    val MTU_QUERY = byteArrayOf(TAG_MTU.toByte(), 0, 0, 0, 0)

    /** The frame size in an answer to [MTU_QUERY], or null if [frame] isn't one. */
    fun mtuFrom(frame: ByteArray): Int? {
        if (frame.size < 6 || frame[0].toInt() != TAG_MTU) return null
        val mtu = frame[5].toInt() and 0xff
        return mtu.takeIf { it > 5 }
    }

    /** [apdu] as the frames to write, in order, each at most [mtu] bytes. */
    fun frames(apdu: ByteArray, mtu: Int): List<ByteArray> {
        require(mtu > 5) { "frame size" }
        require(apdu.size <= 0xffff) { "APDU too long" }
        val out = ArrayList<ByteArray>()
        var offset = 0
        var index = 0
        do {
            val head = if (index == 0) 5 else 3
            val take = minOf(mtu - head, apdu.size - offset)
            val frame = ByteArray(head + take)
            frame[0] = TAG_APDU.toByte()
            frame[1] = (index shr 8).toByte()
            frame[2] = index.toByte()
            if (index == 0) {
                frame[3] = (apdu.size shr 8).toByte()
                frame[4] = apdu.size.toByte()
            }
            System.arraycopy(apdu, offset, frame, head, take)
            out += frame
            offset += take
            index++
        } while (offset < apdu.size)
        return out
    }

    /** Thrown for a notification that can't be part of the answer being read. */
    class BadFrame(message: String) : Exception(message)

    /**
     * Puts one answer back together from its frames. [add] each
     * notification as it comes; it returns the whole answer (data and
     * status word) once the last frame is in, else null.
     */
    class Reader {
        private val data = ByteArrayOutputStream()
        private var expected = -1
        private var next = 0

        fun add(frame: ByteArray): ByteArray? {
            if (frame.size < 3) throw BadFrame("short frame")
            val tag = frame[0].toInt() and 0xff
            if (tag != TAG_APDU) throw BadFrame("unexpected tag 0x%02x".format(tag))
            val index = ((frame[1].toInt() and 0xff) shl 8) or (frame[2].toInt() and 0xff)
            if (index != next) throw BadFrame("frame $index out of order (expected $next)")
            var start = 3
            if (index == 0) {
                if (frame.size < 5) throw BadFrame("short first frame")
                expected = ((frame[3].toInt() and 0xff) shl 8) or (frame[4].toInt() and 0xff)
                start = 5
            }
            data.write(frame, start, frame.size - start)
            next++
            if (data.size() > expected) throw BadFrame("more data than announced")
            return if (data.size() == expected) data.toByteArray() else null
        }
    }

    /** One Ledger model's GATT service and characteristics (`@ledgerhq/devices`' `bluetoothSpec`). */
    data class Spec(val model: String, val service: UUID, val notify: UUID, val write: UUID)

    private fun spec(model: String, id: String) = Spec(
        model,
        UUID.fromString("13d63400-2c97-$id-0000-4c6564676572"),
        UUID.fromString("13d63400-2c97-$id-0001-4c6564676572"),
        UUID.fromString("13d63400-2c97-$id-0002-4c6564676572"),
    )

    /** The Bluetooth Ledgers: Nano X, Stax and Flex. */
    val SPECS = listOf(spec("Nano X", "0004"), spec("Stax", "6004"), spec("Flex", "3004"))

    /** Client Characteristic Configuration: where notifications are turned on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
