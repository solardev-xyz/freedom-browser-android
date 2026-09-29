package baby.freedom.swarm

import java.math.BigInteger

/** Builds the signed legacy transactions ant sends, for [SpendGuard] tests (the signature is filler). */
internal object TestTx {
    const val OWNER = "1111111111111111111111111111111111111111"

    fun request(raw: ByteArray, id: Int = 7): String =
        """{"jsonrpc":"2.0","id":$id,"method":"eth_sendRawTransaction","params":["0x${hex(raw)}"]}"""

    fun tx(
        to: String?,
        data: ByteArray,
        value: BigInteger = BigInteger.ZERO,
        nonce: Long = 9,
        gasPrice: BigInteger = BigInteger.valueOf(2_000_000_000),
        gas: Long = 800_000,
        v: Long = 235,
    ): ByteArray = list(
        int(BigInteger.valueOf(nonce)), int(gasPrice), int(BigInteger.valueOf(gas)),
        str(to?.let(::bytes) ?: ByteArray(0)), int(value), str(data),
        int(BigInteger.valueOf(v)), str(ByteArray(32) { 0x11 }), str(ByteArray(32) { 0x22 }),
    )

    fun swap(value: BigInteger, recipient: String = OWNER) =
        tx(SpendPermit.SWAP_HELPER, call("3b88d7af", addr(recipient), word(BigInteger.ONE)), value = value)

    fun approve(
        amount: BigInteger,
        spender: String = SpendPermit.POSTAGE_STAMP,
        nonce: Long = 9,
        v: Long = 235,
        gasPrice: BigInteger = BigInteger.valueOf(2_000_000_000),
        gas: Long = 100_000,
    ) = tx(SpendPermit.BZZ_TOKEN, call("095ea7b3", addr(spender), word(amount)), nonce = nonce, v = v, gasPrice = gasPrice, gas = gas)

    fun createBatch(
        owner: String,
        amount: BigInteger,
        depth: Int,
        immutable: Boolean,
        bucketDepth: Int = 16,
        batchNonce: Byte = 0x33,
        value: BigInteger = BigInteger.ZERO,
        v: Long = 235,
    ) =
        tx(
            SpendPermit.POSTAGE_STAMP,
            call(
                "5239af71", addr(owner), word(amount), word(BigInteger.valueOf(depth.toLong())),
                word(BigInteger.valueOf(bucketDepth.toLong())), ByteArray(32) { batchNonce },
                word(if (immutable) BigInteger.ONE else BigInteger.ZERO),
            ),
            value = value,
            v = v,
        )

    fun topUp(batchId: String, amount: BigInteger, nonce: Long = 9) =
        tx(SpendPermit.POSTAGE_STAMP, call("b67644b9", bytes(batchId), word(amount)), nonce = nonce)

    fun deployChequebook(issuer: String = OWNER) =
        tx(SpendPermit.CHEQUEBOOK_FACTORY, call("15efd8a7", addr(issuer), word(BigInteger.valueOf(86400)), ByteArray(32) { 5 }))

    fun transfer(to: String, amount: BigInteger) =
        tx(SpendPermit.BZZ_TOKEN, call("a9059cbb", addr(to), word(amount)))

    fun call(selector: String, vararg words: ByteArray): ByteArray =
        words.fold(bytes(selector)) { acc, w -> acc + w }

    fun word(v: BigInteger): ByteArray {
        val b = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - b.size) + b
    }

    fun addr(a: String) = ByteArray(12) + bytes(a)

    fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // --- RLP ---
    private fun int(v: BigInteger): ByteArray =
        str(if (v.signum() == 0) ByteArray(0) else v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())

    fun str(b: ByteArray): ByteArray = when {
        b.size == 1 && (b[0].toInt() and 0xff) < 0x80 -> b
        else -> header(0x80, b.size) + b
    }

    fun list(vararg items: ByteArray): ByteArray {
        val payload = items.fold(ByteArray(0)) { acc, it -> acc + it }
        return header(0xc0, payload.size) + payload
    }

    private fun header(base: Int, len: Int): ByteArray = if (len < 56) {
        byteArrayOf((base + len).toByte())
    } else {
        val l = BigInteger.valueOf(len.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        byteArrayOf((base + 55 + l.size).toByte()) + l
    }
}
