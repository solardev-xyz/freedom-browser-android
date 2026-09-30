package baby.freedom.mobile.wallet.ledger

import java.io.ByteArrayOutputStream

/**
 * Ledger's APDU-over-USB-HID framing (#319), as `@ledgerhq/devices`'
 * `hid-framing` speaks it (desktop's `hw-transport-node-hid`): the APDU,
 * with its length (u16) in front, is cut into packets of exactly
 * [PACKET] bytes, each `channel (u16) ‖ 0x05 ‖ index (u16) ‖ …`, the
 * last one padded with zeros. The answer comes back the same way, and
 * its announced length says where the padding starts.
 *
 * Pure: the USB side is [LedgerUsbLink]; this is what it writes and how
 * it reads back what the device sent.
 */
internal object LedgerHidFraming {
    /** A Ledger's HID report size: every packet, both ways, is this long. */
    const val PACKET = 64

    /** The channel both sides use; an answer on another isn't ours. */
    const val CHANNEL = 0x0101
    const val TAG_APDU = 0x05
    private const val HEADER = 5

    /** [apdu] as the packets to write, in order, each [PACKET] bytes. */
    fun packets(apdu: ByteArray): List<ByteArray> {
        require(apdu.size <= 0xffff) { "APDU too long" }
        val data = ByteArray(2 + apdu.size)
        data[0] = (apdu.size shr 8).toByte()
        data[1] = apdu.size.toByte()
        System.arraycopy(apdu, 0, data, 2, apdu.size)
        val block = PACKET - HEADER
        val count = (data.size + block - 1) / block
        return List(count) { index ->
            val packet = ByteArray(PACKET)
            packet[0] = (CHANNEL shr 8).toByte()
            packet[1] = CHANNEL.toByte()
            packet[2] = TAG_APDU.toByte()
            packet[3] = (index shr 8).toByte()
            packet[4] = index.toByte()
            val offset = index * block
            System.arraycopy(data, offset, packet, HEADER, minOf(block, data.size - offset))
            packet
        }
    }

    /** Thrown for a packet that can't be part of the answer being read. */
    class BadPacket(message: String) : Exception(message)

    /**
     * Puts one answer back together from its packets. [add] each packet
     * as it's read; it returns the whole answer (data and status word)
     * once the last one is in, else null. The first packet's length
     * decides where the answer ends: the zero padding after it is dropped.
     * A length under two (no room for the status word) is a [BadPacket].
     */
    class Reader {
        private val data = ByteArrayOutputStream()
        private var expected = -1
        private var next = 0

        fun add(packet: ByteArray): ByteArray? {
            if (packet.size < HEADER) throw BadPacket("short packet")
            val channel = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
            if (channel != CHANNEL) throw BadPacket("unexpected channel 0x%04x".format(channel))
            val tag = packet[2].toInt() and 0xff
            if (tag != TAG_APDU) throw BadPacket("unexpected tag 0x%02x".format(tag))
            val index = ((packet[3].toInt() and 0xff) shl 8) or (packet[4].toInt() and 0xff)
            if (index != next) throw BadPacket("packet $index out of order (expected $next)")
            var start = HEADER
            if (index == 0) {
                if (packet.size < HEADER + 2) throw BadPacket("short first packet")
                expected = ((packet[5].toInt() and 0xff) shl 8) or (packet[6].toInt() and 0xff)
                // Every answer ends in a two-byte status word: anything shorter isn't one.
                if (expected < 2) throw BadPacket("answer length $expected")
                start = HEADER + 2
            }
            data.write(packet, start, minOf(packet.size - start, expected - data.size()))
            next++
            return if (data.size() == expected) data.toByteArray() else null
        }
    }

    /**
     * The model a Ledger's USB product id names (`@ledgerhq/devices`'
     * `identifyUSBProductId`): a whole-id legacy product (older firmware,
     * the bootloader), else the high byte — the low byte says which
     * interfaces it has, so a Nano S Plus shows as `0x5011` or `0x5000`.
     * Null for an id that's none of these.
     */
    fun model(productId: Int): String? = when (productId) {
        0x0000 -> "Blue"
        0x0001 -> "Nano S"
        0x0004 -> "Nano X"
        0x0005 -> "Nano S Plus"
        0x0006 -> "Stax"
        0x0007 -> "Flex"
        else -> when ((productId shr 8) and 0xff) {
            0x00 -> "Blue"
            0x10 -> "Nano S"
            0x40 -> "Nano X"
            0x50 -> "Nano S Plus"
            0x60 -> "Stax"
            0x70 -> "Flex"
            else -> null
        }
    }

    /** Ledger's USB vendor id: the only devices the app asks access to. */
    const val VENDOR_ID = 0x2c97
}
