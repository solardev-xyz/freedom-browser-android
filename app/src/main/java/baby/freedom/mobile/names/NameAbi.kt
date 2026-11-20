package baby.freedom.mobile.names

import baby.freedom.mobile.ens.Keccak256
import java.math.BigInteger

/**
 * Just enough Solidity ABI encoding for the name contracts ([NameContracts]):
 * static words (`uint`, `address`, `bytes32`, `bool`) and the dynamic
 * `string`, `bytes` and `bytes[]`, inside a call or a tuple. Desktop uses
 * ethers' `Interface`; the unit tests pin this against its output.
 */
internal object NameAbi {
    /** One argument. */
    sealed interface Value

    /** A 32-byte static word. */
    class Word(val bytes: ByteArray) : Value {
        init {
            require(bytes.size == 32) { "a word is 32 bytes" }
        }
    }

    /** `bytes` or `string` (as UTF-8). */
    class Bytes(val bytes: ByteArray) : Value

    /** A dynamic array of dynamic `bytes`. */
    class BytesArray(val items: List<ByteArray>) : Value

    /** A tuple, encoded in place when all its members are static, else by offset. */
    class Tuple(val members: List<Value>) : Value

    fun uint(v: BigInteger): Word {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "out of range" }
        val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return Word(ByteArray(32 - b.size) + b)
    }

    fun uint(v: Long): Word = uint(BigInteger.valueOf(v))

    fun address(address: String): Word = Word(ByteArray(12) + addressBytes(address))

    fun bytes32(b: ByteArray): Word = Word(b.copyOf().also { require(it.size == 32) { "bytes32" } })

    fun string(s: String): Bytes = Bytes(s.toByteArray(Charsets.UTF_8))

    fun selector(signature: String): ByteArray =
        Keccak256.digest(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** `selector(signature) ‖ encode(args)`. */
    fun call(signature: String, vararg args: Value): ByteArray = selector(signature) + encode(args.toList())

    /** The head/tail encoding of [values] as one tuple. */
    fun encode(values: List<Value>): ByteArray {
        val heads = values.map { if (isDynamic(it)) 32 else headSize(it) }
        var tailOffset = heads.sum()
        val head = ArrayList<ByteArray>()
        val tail = ArrayList<ByteArray>()
        for (v in values) {
            if (isDynamic(v)) {
                head += uint(tailOffset.toLong()).bytes
                val t = encodeOne(v)
                tail += t
                tailOffset += t.size
            } else {
                head += encodeOne(v)
            }
        }
        return (head + tail).fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun isDynamic(v: Value): Boolean = when (v) {
        is Word -> false
        is Bytes, is BytesArray -> true
        is Tuple -> v.members.any(::isDynamic)
    }

    private fun headSize(v: Value): Int = when (v) {
        is Word -> 32
        is Tuple -> v.members.sumOf(::headSize)
        else -> 32
    }

    private fun encodeOne(v: Value): ByteArray = when (v) {
        is Word -> v.bytes
        is Bytes -> uint(v.bytes.size.toLong()).bytes + padded(v.bytes)
        is BytesArray -> uint(v.items.size.toLong()).bytes + encode(v.items.map { Bytes(it) })
        is Tuple -> encode(v.members)
    }

    private fun padded(b: ByteArray): ByteArray = b + ByteArray((32 - b.size % 32) % 32)

    fun addressBytes(address: String): ByteArray {
        val h = address.removePrefix("0x").removePrefix("0X")
        require(h.length == 40 && h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "not an address" }
        return ByteArray(20) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // Decoding: the few return shapes the name contracts give.

    /** The return data's words (`0x…`), or null when it isn't whole words. */
    fun words(hex: String): List<ByteArray>? {
        val h = hex.removePrefix("0x")
        if (h.length % 64 != 0 || !h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return (0 until h.length / 64).map { w -> ByteArray(32) { h.substring(w * 64 + it * 2, w * 64 + it * 2 + 2).toInt(16).toByte() } }
    }

    fun wordUint(w: ByteArray): BigInteger = BigInteger(1, w)

    /** A word as an address, or null when its top 12 bytes aren't zero. */
    fun wordAddress(w: ByteArray): String? =
        if (w.copyOfRange(0, 12).any { it != 0.toByte() }) null else "0x" + w.copyOfRange(12, 32).toHexString()

    /** A word as a bool, or null when it's neither 0 nor 1. */
    fun wordBool(w: ByteArray): Boolean? = when (wordUint(w)) {
        BigInteger.ZERO -> false
        BigInteger.ONE -> true
        else -> null
    }

    /** The dynamic `string` at the head word [index] of [words], or null when malformed. */
    fun dynamicString(words: List<ByteArray>, index: Int): String? {
        val offset = words.getOrNull(index)?.let(::wordUint) ?: return null
        if (offset.mod(BigInteger.valueOf(32)).signum() != 0 || offset > BigInteger.valueOf(32L * words.size)) return null
        val at = offset.toInt() / 32
        val length = words.getOrNull(at)?.let(::wordUint) ?: return null
        if (length > BigInteger.valueOf(32L * (words.size - at - 1))) return null
        val n = length.toInt()
        val data = (at + 1 until at + 1 + (n + 31) / 32).fold(ByteArray(0)) { acc, i -> acc + words[i] }.copyOf(n)
        return String(data, Charsets.UTF_8)
    }

    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
