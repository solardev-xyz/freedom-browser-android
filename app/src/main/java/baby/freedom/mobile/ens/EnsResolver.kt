package baby.freedom.mobile.ens

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolve an ENS name to its content-addressed URI (`bzz://`, `ipfs://`,
 * `ipns://`) by calling the ENS Universal Resolver over public RPC.
 *
 * Ported from `freedom-browser/src/main/ens-resolver.js` — same algorithm,
 * same caching TTL, same reason codes. We avoid pulling in ethers / web3j:
 *
 *   - ABI encoding: two dynamic `bytes` args → hand-rolled (≈15 LoC)
 *   - namehash: bottom-up keccak(node || keccak(label)) (ENSIP-1)
 *   - DNS-encoded name: length-prefixed labels + `\x00`
 *   - Keccak-256: [Keccak256] (pure Kotlin, legacy padding)
 *   - JSON-RPC: [HttpURLConnection] + `org.json.JSONObject`
 *
 * Known limitations vs. the desktop resolver:
 *   - No CCIP-Read. The Universal Resolver reverts with `OffchainLookup`
 *     for offchain resolvers (notably `.box` via 3DNS); we surface that
 *     as `NotFound(NO_RESOLVER)` today. Implementing CCIP-Read is a
 *     separate follow-up (fetch from `urls[]`, call back with extraData).
 *   - ENSIP-15 normalization is lowercased-ASCII only. Pure-ASCII names
 *     round-trip correctly; emoji / non-ASCII labels may normalize
 *     differently than `@adraffy/ens-normalize`.
 */
class EnsResolver(
    private val rpcEndpoints: List<String> = DEFAULT_RPC_ENDPOINTS,
) {
    private data class Cached(val result: EnsResult, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var preferredRpcIndex: Int = 0

    /**
     * Resolve [rawName] (e.g. `swarm.eth`) to a content-addressed URI.
     * Thread-safe; cached for [CACHE_TTL_MS] per normalized name.
     */
    suspend fun resolveContenthash(rawName: String): EnsResult {
        val normalized = (rawName).trim().lowercase()
        if (normalized.isEmpty()) {
            return EnsResult.Error(name = "", reason = "INVALID_NAME", error = "empty name")
        }

        cache[normalized]?.let {
            if (System.currentTimeMillis() - it.timestamp < CACHE_TTL_MS) {
                return it.result
            }
        }

        val callData = buildResolveCallData(normalized)

        var lastError: EnsResult.Error? = null
        val total = rpcEndpoints.size
        // Try each endpoint up to MAX_RETRIES times, starting at the one
        // that last worked. Rotates on failure so persistent provider
        // outages fall through to the next one quickly.
        for (attempt in 0 until total) {
            val idx = (preferredRpcIndex + attempt) % total
            val rpc = rpcEndpoints[idx]
            val rpcResult = runCatching {
                withContext(Dispatchers.IO) { ethCall(rpc, UNIVERSAL_RESOLVER, callData) }
            }
            if (rpcResult.isFailure) {
                val err = rpcResult.exceptionOrNull()!!
                Log.w(TAG, "[$normalized] rpc=$rpc failed: ${err.message}")
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "PROVIDER_ERROR",
                    error = err.message.orEmpty(),
                    retryable = true,
                )
                continue
            }

            val call = rpcResult.getOrThrow()
            if (call.revertData != null) {
                val mapped = mapRevert(normalized, call.revertData)
                if (mapped != null) {
                    preferredRpcIndex = idx
                    return cacheAndReturn(normalized, mapped)
                }
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "RESOLUTION_ERROR",
                    error = "revert: ${call.revertData}",
                )
                break
            }

            val raw = call.data
            if (raw == null) {
                lastError = EnsResult.Error(
                    name = normalized,
                    reason = "RESOLUTION_ERROR",
                    error = "empty eth_call result",
                )
                continue
            }

            preferredRpcIndex = idx
            val decoded = decodeContenthashResponse(normalized, raw)
            return cacheAndReturn(normalized, decoded)
        }

        return lastError ?: EnsResult.Error(
            name = normalized,
            reason = "PROVIDER_ERROR",
            error = "all RPC providers failed",
            retryable = true,
        )
    }

    private fun cacheAndReturn(name: String, result: EnsResult): EnsResult {
        cache[name] = Cached(result, System.currentTimeMillis())
        Log.i(TAG, "[$name] → $result")
        return result
    }

    // ---- ABI / name encoding ----

    private fun buildResolveCallData(normalizedName: String): ByteArray {
        val dnsName = dnsEncode(normalizedName)
        val node = namehash(normalizedName)
        val innerCallData = CONTENTHASH_SELECTOR + node

        val selector = RESOLVE_SELECTOR
        val head = ByteArray(64)
        // offsets relative to start of args (after selector): 0x40 and
        // 0x40 + 32 + padded(dnsName).
        writeUint256(0x40L, head, 0)
        val namePaddedLen = padLen32(dnsName.size)
        writeUint256((0x40 + 32 + namePaddedLen).toLong(), head, 32)

        val nameBlock = ByteArray(32 + namePaddedLen).apply {
            writeUint256(dnsName.size.toLong(), this, 0)
            dnsName.copyInto(this, 32)
        }
        val dataPaddedLen = padLen32(innerCallData.size)
        val dataBlock = ByteArray(32 + dataPaddedLen).apply {
            writeUint256(innerCallData.size.toLong(), this, 0)
            innerCallData.copyInto(this, 32)
        }
        return selector + head + nameBlock + dataBlock
    }

    // ---- Response decoding ----

    private fun decodeContenthashResponse(name: String, rawHex: String): EnsResult {
        // Outer tuple: (bytes result, address resolver). The resolver address
        // is informational — we just extract `result`.
        val outer = decodeDynamicBytesAt(rawHex, pointerSlot = 0)
            ?: return EnsResult.Error(name, "RESOLUTION_ERROR", "malformed UR outer response")

        if (outer.isEmpty()) {
            return EnsResult.NotFound(name, "EMPTY_CONTENTHASH")
        }

        // Inner: the bytes returned by contenthash(bytes32) — themselves
        // an ABI-encoded dynamic bytes wrapper around the raw EIP-1577
        // contenthash.
        val innerHex = outer.toHex()
        val inner = decodeDynamicBytesAt("0x$innerHex", pointerSlot = 0)
            ?: return EnsResult.Error(
                name, "UNSUPPORTED_CONTENTHASH_FORMAT", "inner decode failed"
            )
        if (inner.isEmpty()) {
            return EnsResult.NotFound(name, "EMPTY_CONTENTHASH")
        }

        return parseContentHash(name, inner)
            ?: EnsResult.Unsupported(
                name = name,
                codec = inner.take(8).toByteArray().toHex(),
                rawContentHash = inner.toHex(),
            )
    }

    private fun parseContentHash(name: String, bytes: ByteArray): EnsResult? {
        // Swarm: 0xe40101fa011b20 + 32 bytes
        val swarmPrefix = byteArrayOf(
            0xe4.toByte(), 0x01, 0x01, 0xfa.toByte(), 0x01, 0x1b, 0x20,
        )
        if (bytes.size == swarmPrefix.size + 32 && bytes.startsWith(swarmPrefix)) {
            val hash = bytes.copyOfRange(swarmPrefix.size, bytes.size).toHex()
            return EnsResult.Ok(
                name = name,
                protocol = "bzz",
                uri = "bzz://$hash",
                decoded = hash,
            )
        }

        // IPFS (dag-pb): 0xe3 01 70 + multihash
        val ipfsPrefix = byteArrayOf(0xe3.toByte(), 0x01, 0x70)
        if (bytes.size > ipfsPrefix.size && bytes.startsWith(ipfsPrefix)) {
            val mh = bytes.copyOfRange(ipfsPrefix.size, bytes.size)
            val cid = Base58.encode(mh)
            return EnsResult.Ok(
                name = name,
                protocol = "ipfs",
                uri = "ipfs://$cid",
                decoded = cid,
            )
        }

        // IPNS: 0xe5 01 72 + multihash
        val ipnsPrefix = byteArrayOf(0xe5.toByte(), 0x01, 0x72)
        if (bytes.size > ipnsPrefix.size && bytes.startsWith(ipnsPrefix)) {
            val mh = bytes.copyOfRange(ipnsPrefix.size, bytes.size)
            val cid = Base58.encode(mh)
            return EnsResult.Ok(
                name = name,
                protocol = "ipns",
                uri = "ipns://$cid",
                decoded = cid,
            )
        }

        return null
    }

    private fun mapRevert(name: String, revertData: String): EnsResult? {
        val lower = revertData.lowercase()
        val selector = if (lower.length >= 10) lower.substring(0, 10) else return null
        return when (selector) {
            // ResolverNotFound(bytes), ResolverNotContract(bytes,address)
            "0x77209fe8", "0x1e9535f2" -> EnsResult.NotFound(name, "NO_RESOLVER")
            // OffchainLookup — CCIP-Read not supported yet
            "0x556f1830" -> EnsResult.NotFound(
                name = name,
                reason = "NO_RESOLVER",
                error = "CCIP-Read not supported (offchain resolver)",
            )
            else -> null
        }
    }

    // ---- JSON-RPC ----

    private data class CallOutcome(val data: String?, val revertData: String?)

    private fun ethCall(rpc: String, to: String, callData: ByteArray): CallOutcome {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_call")
            put(
                "params",
                org.json.JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("to", to)
                            put("data", "0x" + callData.toHex())
                        },
                    )
                    put("latest")
                },
            )
        }.toString()

        val conn = (URL(rpc).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: ${text.take(200)}")
            }
            val json = JSONObject(text)
            json.optJSONObject("error")?.let { err ->
                // Some providers pack the revert data inside error.data;
                // surface it so we can distinguish ResolverNotFound from
                // transport failures.
                val data = err.optString("data", "")
                if (data.startsWith("0x") && data.length >= 10) {
                    return CallOutcome(data = null, revertData = data)
                }
                throw RuntimeException("RPC error: ${err.optString("message", "unknown")}")
            }
            val result = json.optString("result", "")
            return CallOutcome(data = result, revertData = null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "EnsResolver"
        private const val CACHE_TTL_MS = 15L * 60 * 1000

        private const val UNIVERSAL_RESOLVER = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"

        // bytes4(keccak256("resolve(bytes,bytes)"))
        private val RESOLVE_SELECTOR = "9061b923".hexToBytes()

        // bytes4(keccak256("contenthash(bytes32)"))
        private val CONTENTHASH_SELECTOR = "bc1c58d1".hexToBytes()

        val DEFAULT_RPC_ENDPOINTS: List<String> = listOf(
            "https://ethereum.publicnode.com",
            "https://1rpc.io/eth",
            "https://eth.drpc.org",
            "https://eth-mainnet.public.blastapi.io",
            "https://eth.merkle.io",
        )

        // ---- helpers used by both the instance and tests ----

        internal fun dnsEncode(name: String): ByteArray {
            if (name.isEmpty()) return byteArrayOf(0x00)
            val labels = name.split('.')
            var size = 1
            for (l in labels) size += 1 + l.toByteArray(Charsets.UTF_8).size
            val out = ByteArray(size)
            var pos = 0
            for (l in labels) {
                val bytes = l.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..63) { "invalid DNS label: '$l'" }
                out[pos++] = bytes.size.toByte()
                bytes.copyInto(out, pos)
                pos += bytes.size
            }
            out[pos] = 0x00
            return out
        }

        /** ENSIP-1 namehash. Pure-ASCII normalization only; see class kdoc. */
        internal fun namehash(name: String): ByteArray {
            var node = ByteArray(32)
            if (name.isEmpty()) return node
            val labels = name.split('.')
            for (i in labels.indices.reversed()) {
                val labelHash = Keccak256.digest(labels[i])
                node = Keccak256.digest(node + labelHash)
            }
            return node
        }
    }
}

// ---- hex / byte utilities (file-level, internal) ----

internal fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        hex[i * 2] = HEX_CHARS[v ushr 4]
        hex[i * 2 + 1] = HEX_CHARS[v and 0x0f]
    }
    return String(hex)
}

internal fun String.hexToBytes(): ByteArray {
    val s = if (startsWith("0x") || startsWith("0X")) substring(2) else this
    require(s.length % 2 == 0) { "odd-length hex: $this" }
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun writeUint256(v: Long, buf: ByteArray, off: Int) {
    for (i in 0..7) {
        buf[off + 31 - i] = (v ushr (8 * i)).toByte()
    }
}

private fun padLen32(n: Int): Int {
    val rem = n % 32
    return if (rem == 0) n else n + (32 - rem)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

/**
 * Decode an ABI-encoded dynamic `bytes` whose pointer-slot lives at
 * `rawHex[pointerSlot * 32 : pointerSlot * 32 + 32]`. Returns null if
 * the layout doesn't look right.
 */
private fun decodeDynamicBytesAt(rawHex: String, pointerSlot: Int): ByteArray? {
    val body = if (rawHex.startsWith("0x") || rawHex.startsWith("0X")) rawHex.substring(2) else rawHex
    if (body.length % 2 != 0) return null
    val bytes = body.hexToBytes()
    val pointerOffset = pointerSlot * 32
    if (bytes.size < pointerOffset + 32) return null
    val offset = readUint256AsInt(bytes, pointerOffset) ?: return null
    if (bytes.size < offset + 32) return null
    val len = readUint256AsInt(bytes, offset) ?: return null
    if (bytes.size < offset + 32 + len) return null
    return bytes.copyOfRange(offset + 32, offset + 32 + len)
}

// uint256 → Int, returning null if the value doesn't fit. ABI offsets
// and lengths in realistic ENS responses are well under Int.MAX_VALUE.
private fun readUint256AsInt(bytes: ByteArray, off: Int): Int? {
    for (i in 0 until 28) {
        if (bytes[off + i].toInt() != 0) return null
    }
    var v = 0L
    for (i in 28 until 32) {
        v = (v shl 8) or (bytes[off + i].toLong() and 0xff)
    }
    return if (v < 0 || v > Int.MAX_VALUE) null else v.toInt()
}

/**
 * Bitcoin-style Base58 (no checksum). Used to render IPFS CIDv0 out of
 * multihash bytes, bit-for-bit matching what `ethers.encodeBase58` and
 * the Freedom desktop resolver emit.
 */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        // Count leading zero bytes — each becomes a leading '1' in Base58.
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        val encoded = CharArray(input.size * 2)
        val buf = input.copyOf()
        var outIdx = encoded.size

        var start = zeros
        while (start < buf.size) {
            encoded[--outIdx] = ALPHABET[divmod(buf, start, 256, 58)]
            if (buf[start].toInt() == 0) start++
        }
        while (outIdx < encoded.size && encoded[outIdx] == ALPHABET[0]) outIdx++
        repeat(zeros) { encoded[--outIdx] = ALPHABET[0] }
        return String(encoded, outIdx, encoded.size - outIdx)
    }

    // buf is treated as a big integer in base `base`; divide in place by
    // `divisor` and return the remainder. See Bitcoin Base58 reference.
    private fun divmod(buf: ByteArray, start: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in start until buf.size) {
            val num = (buf[i].toInt() and 0xff) + remainder * base
            buf[i] = (num / divisor).toByte()
            remainder = num % divisor
        }
        return remainder
    }
}
