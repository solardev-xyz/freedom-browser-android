package baby.freedom.mobile.wallet.ledger

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.NodeIdentity
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/** A connection to a Ledger that carries APDUs: Bluetooth ([LedgerBleLink]) or, in a debug build, an emulator. */
interface LedgerLink : AutoCloseable {
    /**
     * Sends one APDU and returns the answer: its data and the two-byte
     * status word. Throws [LedgerException] (DISCONNECTED, TIMEOUT…) if
     * no answer comes within [timeoutMs].
     */
    suspend fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray

    /** Drops the connection; an [exchange] waiting on it fails at once. Idempotent. */
    override fun close()
}

/** `v ‖ r ‖ s` as the Ethereum app returns a signature; [v]'s meaning depends on what was signed, so callers recover it. */
class LedgerSignature(val v: Int, val r: ByteArray, val s: ByteArray)

/**
 * The APDUs of Ledger's Ethereum app (#142, `app-ethereum`'s
 * `doc/ethapp.adoc`), built the way `@ledgerhq/hw-app-eth` builds them —
 * the same chunk sizes, and the same care not to split a legacy
 * transaction's EIP-155 tail across two chunks. Pure, so it's tested
 * byte for byte; [LedgerEthApp] sends them.
 */
internal object LedgerApdus {
    const val CLA = 0xe0
    const val INS_ADDRESS = 0x02
    const val INS_SIGN_TX = 0x04
    const val INS_SIGN_PERSONAL = 0x08
    const val INS_SIGN_EIP712 = 0x0c
    const val INS_EIP712_STRUCT_DEF = 0x1a
    const val INS_EIP712_STRUCT_IMPL = 0x1c

    /** What hw-app-eth puts in one signing chunk. */
    private const val CHUNK = 150

    /** The most one EIP-712 field value chunk carries. */
    private const val MAX_DATA = 255

    fun apdu(ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
        require(data.size <= 255) { "APDU data too long" }
        return byteArrayOf(CLA.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data
    }

    /** A BIP-32 path in the device's format (`44'/60'/0'/0/0`, no `m/`) as its component count and big-endian components. */
    fun path(path: String): ByteArray {
        val parts = path.removePrefix("m/").split('/')
        require(parts.size in 1..10) { "path length" }
        val out = ByteArrayOutputStream()
        out.write(parts.size)
        for (p in parts) {
            val hardened = p.endsWith("'")
            val n = p.removeSuffix("'").toLongOrNull()
            require(n != null && n in 0..0x7fffffffL && p.removeSuffix("'").all { it in '0'..'9' }) { "bad path component" }
            val v = if (hardened) n or 0x80000000L else n
            out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        }
        return out.toByteArray()
    }

    /** GET ETH PUBLIC ADDRESS, not shown on the device, no chain code. */
    fun address(path: String): ByteArray = apdu(INS_ADDRESS, 0x00, 0x00, path(path))

    /**
     * SIGN ETH TRANSACTION for [payload] (the bytes whose keccak256 is
     * signed: `0x02 ‖ rlp(…)`, or a legacy EIP-155 list): the path and the
     * payload cut into 255-byte chunks — hw-app-eth's
     * `safeChunkTransaction`, which for a legacy transaction picks a chunk
     * size that never leaves the `(chainId, 0, 0)` tail alone in the last
     * chunk, where the app would take it for the end of the transaction.
     */
    fun signTransaction(path: String, payload: ByteArray): List<ByteArray> {
        val all = path(path) + payload
        var size = MAX_DATA
        val vrs = legacyVrsOffset(payload).takeIf { it > 0 }?.let { payload.size - it }
        if (all.size > MAX_DATA && vrs != null) {
            size = (MAX_DATA downTo 1).first { n -> all.size % n == 0 || all.size % n > vrs }
        }
        return all.toList().chunked(size).mapIndexed { i, chunk ->
            apdu(INS_SIGN_TX, if (i == 0) 0x00 else 0x80, 0x00, chunk.toByteArray())
        }
    }

    /** Where a legacy EIP-155 payload's `(chainId, 0, 0)` items start; 0 for a typed transaction. */
    internal fun legacyVrsOffset(payload: ByteArray): Int {
        if (payload.isEmpty() || (payload[0].toInt() and 0xff) < 0xc0) return 0
        val items = Rlp.items(payload) ?: return 0
        if (items.size <= 6) return 0
        return items[items.size - 3].first
    }

    /** SIGN ETH PERSONAL MESSAGE: the device prefixes and hashes [message] itself (EIP-191). */
    fun signPersonal(path: String, message: ByteArray): List<ByteArray> {
        val p = path(path)
        val len = message.size
        val head = p + byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        val out = ArrayList<ByteArray>()
        var offset = 0
        do {
            val first = offset == 0
            val max = if (first) CHUNK - head.size else CHUNK
            val size = minOf(max, message.size - offset)
            val chunk = message.copyOfRange(offset, offset + size)
            out += apdu(INS_SIGN_PERSONAL, if (first) 0x00 else 0x80, 0x00, if (first) head + chunk else chunk)
            offset += size
        } while (offset < message.size)
        return out
    }

    /** SIGN ETH EIP 712 in its original form: the domain separator and the message's struct hash, shown as hashes. */
    fun signEip712Hashed(path: String, domainSeparator: ByteArray, structHash: ByteArray): ByteArray {
        require(domainSeparator.size == 32 && structHash.size == 32) { "hashes are 32 bytes" }
        return apdu(INS_SIGN_EIP712, 0x00, 0x00, path(path) + domainSeparator + structHash)
    }

    /**
     * EIP-712 the way the device shows it field by field: every struct's
     * definition (INS 0x1A), then the domain's and the message's values
     * (INS 0x1C), then the signature (INS 0x0C, P2 1) — hw-app-eth's
     * `signEIP712Message` with no filters, so nothing leaves the phone.
     * Throws [Unencodable] for data the device can't be given as it
     * would hash it (a missing nested struct, an array over 255).
     */
    fun signEip712Full(path: String, data: Eip712.TypedData): List<ByteArray> {
        if (data.primaryType == "EIP712Domain") throw Unencodable("a domain-only message")
        val out = ArrayList<ByteArray>()
        for ((name, fields) in data.types) {
            out += apdu(INS_EIP712_STRUCT_DEF, 0x00, 0x00, utf8(name))
            for (f in fields) out += apdu(INS_EIP712_STRUCT_DEF, 0x00, 0xff, fieldDefinition(data.types, f))
        }
        val impl = Eip712Values(data.types, out)
        impl.root("EIP712Domain", data.domain)
        impl.root(data.primaryType, data.message)
        out += apdu(INS_SIGN_EIP712, 0x00, 0x01, path(path))
        return out
    }

    class Unencodable(message: String) : Exception(message)

    private fun utf8(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size > 255) throw Unencodable("a name longer than 255 bytes")
        return b
    }

    /** A field type split into its base and its array levels, outermost last (as written: `T[2][]`). */
    private class FieldType(val base: String, val levels: List<Int?>)

    private fun fieldType(type: String): FieldType {
        var t = type
        val levels = ArrayList<Int?>()
        while (t.endsWith("]")) {
            val open = t.lastIndexOf('[')
            if (open < 0) throw Unencodable("bad type $type")
            val n = t.substring(open + 1, t.length - 1)
            levels.add(0, if (n.isEmpty()) null else n.toIntOrNull() ?: throw Unencodable("bad type $type"))
            t = t.substring(0, open)
        }
        return FieldType(t, levels)
    }

    /** An atomic type's key and size ([Eip712]'s names), or null for a struct. */
    private class Atom(val key: Int, val size: Int?)

    private fun atom(types: Map<String, List<Eip712.Field>>, base: String): Atom? {
        if (types.containsKey(base)) return null
        return when {
            base == "address" -> Atom(3, null)
            base == "bool" -> Atom(4, null)
            base == "string" -> Atom(5, null)
            base == "bytes" -> Atom(7, null)
            base.startsWith("bytes") -> Atom(6, base.removePrefix("bytes").toIntOrNull()?.takeIf { it in 1..32 } ?: throw Unencodable("unknown type $base"))
            base.startsWith("uint") -> Atom(2, bits(base.removePrefix("uint")) / 8)
            base.startsWith("int") -> Atom(1, bits(base.removePrefix("int")) / 8)
            else -> throw Unencodable("unknown type $base")
        }
    }

    private fun bits(s: String): Int =
        (if (s.isEmpty()) 256 else s.toIntOrNull())?.takeIf { it in 8..256 && it % 8 == 0 } ?: throw Unencodable("bad integer size")

    /** One field of a struct definition: `typeDesc [typeName] [typeSize] [arrayLevels] keyName`. */
    internal fun fieldDefinition(types: Map<String, List<Eip712.Field>>, field: Eip712.Field): ByteArray {
        val t = fieldType(field.type)
        val a = atom(types, t.base)
        val out = ByteArrayOutputStream()
        var desc = a?.key ?: 0
        if (t.levels.isNotEmpty()) desc = desc or 0x80
        if (a?.size != null) desc = desc or 0x40
        out.write(desc)
        if (a == null) {
            val name = utf8(t.base)
            out.write(name.size)
            out.write(name)
        }
        a?.size?.let { out.write(it) }
        if (t.levels.isNotEmpty()) {
            out.write(t.levels.size)
            for (n in t.levels) {
                if (n == null) {
                    out.write(0)
                } else {
                    if (n > 255) throw Unencodable("an array over 255")
                    out.write(1)
                    out.write(n)
                }
            }
        }
        val key = utf8(field.name)
        out.write(key.size)
        out.write(key)
        return out.toByteArray()
    }

    /** Streams a struct's values as STRUCT IMPL APDUs into [out]. */
    private class Eip712Values(val types: Map<String, List<Eip712.Field>>, val out: MutableList<ByteArray>) {
        fun root(type: String, value: JSONObject) {
            out += apdu(INS_EIP712_STRUCT_IMPL, 0x00, 0x00, utf8(type))
            struct(type, value)
        }

        private fun struct(type: String, value: JSONObject) {
            for (f in types.getValue(type)) {
                val v = value.opt(f.name).takeIf { it != JSONObject.NULL }
                val t = fieldType(f.type)
                field(t.base, t.levels.size, v, f.name)
            }
        }

        private fun field(base: String, depth: Int, value: Any?, name: String) {
            if (depth > 0) {
                val arr = value as? JSONArray ?: throw Unencodable("$name should be an array")
                if (arr.length() > 255) throw Unencodable("an array over 255")
                out += apdu(INS_EIP712_STRUCT_IMPL, 0x00, 0x0f, byteArrayOf(arr.length().toByte()))
                for (i in 0 until arr.length()) field(base, depth - 1, arr.opt(i).takeIf { it != JSONObject.NULL }, name)
                return
            }
            if (types.containsKey(base)) {
                // eth-sig-util hashes a missing struct as zero; the device has no way to say that.
                val obj = value as? JSONObject ?: throw Unencodable("a missing $base")
                struct(base, obj)
                return
            }
            val bytes = atomValue(base, value ?: throw Unencodable("missing value for $name"), name)
            val data = byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
            var offset = 0
            while (offset < data.size) {
                val size = minOf(MAX_DATA, data.size - offset)
                val last = offset + size == data.size
                // P1: 0x01 "more to come", 0x00 the last (or only) part.
                out += apdu(INS_EIP712_STRUCT_IMPL, if (last) 0x00 else 0x01, 0xff, data.copyOfRange(offset, offset + size))
                offset += size
            }
        }

        private fun atomValue(base: String, value: Any, name: String): ByteArray = try {
            when {
                base == "string" -> (value as? String ?: value.toString()).toByteArray(Charsets.UTF_8)
                base == "address" -> Eip712.hex(value as? String ?: "")?.takeIf { it.size == 20 } ?: throw Unencodable("$name should be an address")
                base == "bool" -> byteArrayOf(if (boolOf(value, name)) 1 else 0)
                base == "bytes" -> when (value) {
                    is String -> if (value.startsWith("0x") || value.startsWith("0X")) Eip712.hex(value) ?: throw Unencodable("$name isn't hex") else value.toByteArray(Charsets.UTF_8)
                    else -> unsigned(Eip712.integer(value, name))
                }
                base.startsWith("bytes") -> when (value) {
                    is String -> Eip712.hex(value) ?: throw Unencodable("$name should be hex")
                    else -> unsigned(Eip712.integer(value, name))
                }
                base.startsWith("uint") || base.startsWith("int") -> {
                    val v = Eip712.integer(value, name)
                    val size = atom(types, base)!!.size!!
                    if (v.signum() >= 0) unsigned(v) else twosComplement(v, size)
                }
                else -> throw Unencodable("unknown type $base")
            }
        } catch (e: Eip712.Invalid) {
            throw Unencodable(e.message ?: "invalid value")
        }

        private fun boolOf(v: Any, name: String): Boolean = when (v) {
            is Boolean -> v
            is Number -> Eip712.integer(v, name).signum() != 0
            "true" -> true
            "false" -> false
            else -> throw Unencodable("$name should be true or false")
        }
    }

    /** Big-endian with no leading zeros, at least one byte. */
    internal fun unsigned(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val trimmed = if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        return trimmed
    }

    /** A negative [v] as [size] bytes of two's complement. */
    internal fun twosComplement(v: BigInteger, size: Int): ByteArray {
        val raw = v.add(BigInteger.ONE.shiftLeft(size * 8)).toByteArray()
        val tail = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
        return ByteArray(size - tail.size) { 0xff.toByte() } + tail
    }

    /**
     * Whether [apdu] is one the Ethereum app answers "incorrect data"
     * (`0x6A80`) when Blind signing is off: a transaction (contract data it
     * can't show), typed data by its hashes (INS 0x0C), and unfiltered typed
     * data field by field — app-ethereum refuses that as it's about to show
     * the first value (`ui_712_redraw_generic_step`), so on a value APDU
     * (INS 0x1C; a value this app can't encode never gets there, it falls
     * back to the hashes). Struct definitions, addresses and messages get
     * that word only for data that's actually bad.
     */
    fun needsBlindSigning(apdu: ByteArray): Boolean {
        val ins = if (apdu.size > 1) apdu[1].toInt() and 0xff else return false
        return ins == INS_SIGN_TX || ins == INS_SIGN_EIP712 || ins == INS_EIP712_STRUCT_IMPL
    }

    /**
     * The data of an answer ([LedgerLink.exchange]'s bytes less the status
     * word), or the failure its status word means ([LedgerException.forStatus];
     * [blindSigning] for an APDU only Blind signing lets through).
     */
    fun ok(answer: ByteArray, blindSigning: Boolean = false): ByteArray {
        if (answer.size < 2) throw LedgerException(LedgerException.Kind.UNKNOWN)
        val sw = ((answer[answer.size - 2].toInt() and 0xff) shl 8) or (answer[answer.size - 1].toInt() and 0xff)
        if (sw != 0x9000) throw LedgerException.forStatus(sw, blindSigning)
        return answer.copyOfRange(0, answer.size - 2)
    }

    /** A GET ADDRESS answer's address, EIP-55 checksummed from the public key it came with (which must match its text). */
    fun parseAddress(data: ByteArray): String {
        try {
            val pkLen = data[0].toInt() and 0xff
            val pk = data.copyOfRange(1, 1 + pkLen)
            val addrLen = data[1 + pkLen].toInt() and 0xff
            val text = String(data, 2 + pkLen, addrLen, Charsets.US_ASCII).removePrefix("0x")
            if (pk.size != 65 || pk[0].toInt() != 4) throw LedgerException(LedgerException.Kind.UNKNOWN)
            val address = NodeIdentity.checksum(Keccak256.digest(pk.copyOfRange(1, 65)).copyOfRange(12, 32))
            if (!address.removePrefix("0x").equals(text, ignoreCase = true)) throw LedgerException(LedgerException.Kind.UNKNOWN)
            return address
        } catch (e: IndexOutOfBoundsException) {
            throw LedgerException(LedgerException.Kind.UNKNOWN)
        }
    }

    fun parseSignature(data: ByteArray): LedgerSignature {
        if (data.size < 65) throw LedgerException(LedgerException.Kind.UNKNOWN)
        return LedgerSignature(data[0].toInt() and 0xff, data.copyOfRange(1, 33), data.copyOfRange(33, 65))
    }

    /** Just enough RLP decoding to find where a list's items start. */
    private object Rlp {
        /** The (offset, length-with-header) of each item of the list [b] is, or null if it isn't one. */
        fun items(b: ByteArray): List<Pair<Int, Int>>? = runCatching {
            val (start, len) = header(b, 0).takeIf { (b[0].toInt() and 0xff) >= 0xc0 } ?: return null
            if (start + len != b.size) return null
            val out = ArrayList<Pair<Int, Int>>()
            var i = start
            while (i < start + len) {
                val (s, l) = header(b, i)
                out += i to (s - i + l)
                i = s + l
            }
            out
        }.getOrNull()

        /** Where item [at]'s payload starts and how long it is. */
        private fun header(b: ByteArray, at: Int): Pair<Int, Int> {
            val p = b[at].toInt() and 0xff
            return when {
                p < 0x80 -> at to 1
                p <= 0xb7 -> (at + 1) to (p - 0x80)
                p < 0xc0 -> long(b, at, p - 0xb7)
                p <= 0xf7 -> (at + 1) to (p - 0xc0)
                else -> long(b, at, p - 0xf7)
            }
        }

        private fun long(b: ByteArray, at: Int, n: Int): Pair<Int, Int> {
            var len = 0
            for (k in 1..n) len = (len shl 8) or (b[at + k].toInt() and 0xff)
            return (at + 1 + n) to len
        }
    }
}

/** The Ethereum app on a connected Ledger (#142): [LedgerApdus] over a [LedgerLink]. */
internal class LedgerEthApp(private val link: LedgerLink) {
    /** The address at [path], read without showing it on the device. */
    suspend fun address(path: String): String =
        LedgerApdus.parseAddress(LedgerApdus.ok(link.exchange(LedgerApdus.address(path), QUICK_MS)))

    suspend fun signTransaction(path: String, payload: ByteArray): LedgerSignature =
        LedgerApdus.parseSignature(sendAll(LedgerApdus.signTransaction(path, payload)))

    suspend fun signPersonal(path: String, message: ByteArray): LedgerSignature =
        LedgerApdus.parseSignature(sendAll(LedgerApdus.signPersonal(path, message)))

    /**
     * Typed data shown field by field; on an Ethereum app too old for that
     * (INS 0x1A unknown: `0x6D00`), or data it can't be streamed as, the
     * two hashes instead — as desktop falls back.
     */
    suspend fun signTypedData(path: String, data: Eip712.TypedData): LedgerSignature {
        val full = try {
            LedgerApdus.signEip712Full(path, data)
        } catch (e: LedgerApdus.Unencodable) {
            null
        }
        if (full != null) {
            try {
                return LedgerApdus.parseSignature(sendAll(full))
            } catch (e: LedgerException) {
                if ((e.cause as? LedgerException.StatusWord)?.sw != 0x6d00) throw e
            }
        }
        val domain = Eip712.hashStruct(data.types, "EIP712Domain", data.domain, 0)
        if (data.primaryType == "EIP712Domain") throw LedgerException(LedgerException.Kind.UNSUPPORTED)
        val struct = Eip712.hashStruct(data.types, data.primaryType, data.message, 0)
        return LedgerApdus.parseSignature(sendAll(listOf(LedgerApdus.signEip712Hashed(path, domain, struct))))
    }

    /** Sends [apdus] in order, each only after the one before answered `0x9000`; the last one's data. */
    private suspend fun sendAll(apdus: List<ByteArray>): ByteArray {
        var last = ByteArray(0)
        for (a in apdus) last = LedgerApdus.ok(link.exchange(a, CONFIRM_MS), LedgerApdus.needsBlindSigning(a))
        return last
    }

    companion object {
        /** An APDU the device answers without the user: an address. */
        const val QUICK_MS = 10_000L

        /** An APDU the user may be reading and confirming on the device first. */
        const val CONFIRM_MS = 180_000L
    }
}
