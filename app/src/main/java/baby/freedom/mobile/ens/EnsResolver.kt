package baby.freedom.mobile.ens

import android.util.Log
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 * CCIP-Read (EIP-3668) is followed for offchain resolvers — subnames
 * under `base.eth`, `cb.id`, NameStone-managed names and the like. The
 * Universal Resolver reverts with `OffchainLookup`; we fetch from the
 * gateway it names, then call its callback with the gateway's answer
 * and the revert's `extraData`, repeating if the callback reverts with
 * another lookup. Gateway fetches are bounded (https only, 15 s, 4 MB)
 * because the URLs come from the contract, not from us; see
 * [ccipFetch].
 *
 * Known limitations vs. the desktop resolver:
 *   - ENSIP-15 normalization is lowercased-ASCII only. Pure-ASCII names
 *     round-trip correctly; emoji / non-ASCII labels may normalize
 *     differently than `@adraffy/ens-normalize`.
 */
class EnsResolver internal constructor(
    private val rpcEndpoints: List<String>,
    private val http: EnsHttp,
) {
    constructor(rpcEndpoints: List<String> = DEFAULT_RPC_ENDPOINTS) :
        this(rpcEndpoints, EnsHttp.Default)

    private data class Cached(val result: EnsResult, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var preferredRpcIndex: Int = 0

    /**
     * Resolve [rawName] (e.g. `swarm.eth`) to a content-addressed URI.
     * Thread-safe; cached for [CACHE_TTL_MS] per normalized name.
     *
     * Cancellation-honest: a caller whose coroutine was cancelled while
     * we were resolving gets a `CancellationException`, never an
     * [EnsResult]. Callers *act* on what comes back — navigate the tab,
     * show an error page, cancel whatever probe the tab is now waiting
     * on — and a probe the user has already superseded must do none of
     * that (#51).
     */
    suspend fun resolveContenthash(rawName: String): EnsResult {
        val result = resolve(rawName)
        // Not every cancellation arrives as a `CancellationException`:
        // tearing down the RPC in flight can surface as an ordinary
        // `IOException`, which the retry loop maps to a PROVIDER_ERROR
        // like any other transport failure. One check on the way out
        // covers every return path above.
        coroutineContext.ensureActive()
        return result
    }

    private suspend fun resolve(rawName: String): EnsResult {
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
            val rpcResult = runCatchingCancellable {
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

            var call = rpcResult.getOrThrow()
            if (call.revertData != null && isOffchainLookup(call.revertData)) {
                // Offchain resolver: run the CCIP-Read loop against the
                // same RPC. Gateway failures are retryable transport
                // errors, not "no such name", and aren't cached.
                val followed = runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        followOffchainLookup(rpc, call.revertData!!)
                    }
                }
                val err = followed.exceptionOrNull()
                if (err != null) {
                    Log.w(TAG, "[$normalized] CCIP-Read failed: ${err.message}")
                    return EnsResult.Error(
                        name = normalized,
                        reason = "CCIP_GATEWAY_FAILED",
                        error = err.message.orEmpty(),
                        retryable = true,
                    )
                }
                call = followed.getOrThrow()
            }
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
        return RESOLVE_SELECTOR + abiEncodeTwoBytes(dnsName, innerCallData)
    }

    // ---- CCIP-Read (EIP-3668) ----

    /**
     * Decoded `OffchainLookup(address sender, string[] urls, bytes
     * callData, bytes4 callbackFunction, bytes extraData)`.
     */
    internal class OffchainLookup(
        val sender: String,
        val urls: List<String>,
        val callData: ByteArray,
        val callback: ByteArray,
        val extraData: ByteArray,
    )

    /**
     * Drive the lookup to completion: fetch the gateway's answer, feed it
     * to the sender's callback, and repeat while that reverts with a
     * further `OffchainLookup`. Returns the final call outcome — a
     * result to decode as usual, or a non-CCIP revert for [mapRevert].
     * Throws on gateway failure, a sender other than the Universal
     * Resolver, malformed revert data, or too many rounds.
     */
    private fun followOffchainLookup(rpc: String, firstRevert: String): CallOutcome {
        var revert = firstRevert
        repeat(MAX_CCIP_ROUNDS) {
            val lookup = decodeOffchainLookup(revert)
                ?: throw IllegalStateException("malformed OffchainLookup revert")
            // Only follow lookups issued by the contract we called. A
            // resolver can't redirect us into calling back some other
            // contract with gateway-supplied bytes.
            if (!lookup.sender.equals(UNIVERSAL_RESOLVER, ignoreCase = true)) {
                throw IllegalStateException("OffchainLookup sender is not the Universal Resolver")
            }
            val response = ccipFetch(lookup.sender, lookup.urls, lookup.callData)
                ?: throw IllegalStateException("CCIP gateways unavailable or returned invalid data")
            val callbackData = lookup.callback + abiEncodeTwoBytes(response, lookup.extraData)
            val outcome = ethCall(rpc, lookup.sender, callbackData)
            val next = outcome.revertData
            if (next == null || !isOffchainLookup(next)) return outcome
            revert = next
        }
        throw IllegalStateException("CCIP-Read recursion limit exceeded")
    }

    /**
     * Ask the gateways, in order, for the answer to [callData]. Follows
     * EIP-3668 exactly: `{sender}` / `{data}` are substituted into the
     * URL template, a template containing `{data}` is fetched with GET,
     * anything else gets a JSON `{sender, data}` POST, and the reply is
     * JSON with a hex `data` field. A gateway that fails any check is
     * skipped and the next one tried; `null` once they're exhausted.
     *
     * Bounds are deliberate — the URLs are chosen by the resolver
     * contract, not by us: https only, no redirects, no credentials or
     * bare-IP / local hosts, [CCIP_TIMEOUT_MS] wall clock and
     * [CCIP_MAX_RESPONSE_BYTES] body per gateway. Non-URL entries such
     * as the Universal Resolver's `x-batch-gateway:true` hint are
     * skipped like any other non-https string.
     */
    internal fun ccipFetch(sender: String, urls: List<String>, callData: ByteArray): ByteArray? {
        val senderLower = sender.lowercase()
        val dataHex = "0x" + callData.toHex()
        for (template in urls) {
            val url = template.replace("{sender}", senderLower).replace("{data}", dataHex)
            val parsed = runCatching { URL(url) }.getOrNull() ?: continue
            val host = parsed.host.orEmpty().trim('[', ']').trimEnd('.').lowercase()
            if (parsed.protocol != "https" || parsed.userInfo != null || !isPublicHostname(host)) {
                continue
            }
            val get = template.contains("{data}")
            val reply = runCatching {
                http.request(
                    method = if (get) "GET" else "POST",
                    url = url,
                    headers = if (get) {
                        mapOf("accept" to "application/json")
                    } else {
                        mapOf("accept" to "application/json", "content-type" to "application/json")
                    },
                    body = if (get) {
                        null
                    } else {
                        JSONObject().put("sender", senderLower).put("data", dataHex).toString()
                    },
                    timeoutMs = CCIP_TIMEOUT_MS,
                    maxBytes = CCIP_MAX_RESPONSE_BYTES,
                    followRedirects = false,
                )
            }.getOrNull() ?: continue
            if (reply.code !in 200..299) continue
            val data = runCatching { JSONObject(reply.body).optString("data", "") }.getOrNull() ?: continue
            if (!isHexBytes(data)) continue
            return data.hexToBytes()
        }
        return null
    }

    private fun isPublicHostname(host: String): Boolean {
        if (host.isEmpty() || !host.contains('.')) return false
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        // Bare IPv4 / IPv6 literals: the point of a CCIP gateway is a
        // named, certificated service.
        if (host.all { it.isDigit() || it == '.' }) return false
        if (host.contains(':')) return false
        return true
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

        // IPFS: 0xe3 0x01 (varint for ipfs-ns multicodec 0xe3) + CID.
        // The CID that follows is either:
        //   • CIDv0: raw multihash, always dag-pb + sha2-256. Starts with
        //     0x12 0x20 (sha2-256, 32 bytes). Rendered as Base58BTC
        //     ("Qm…").
        //   • CIDv1: <0x01 version><codec><multihash>. Rendered as
        //     multibase Base32 with a 'b' prefix ("bafy…"). This is what
        //     modern ENS names (vitalik.eth and friends) actually use.
        val ipfsNs = byteArrayOf(0xe3.toByte(), 0x01)
        if (bytes.size > ipfsNs.size && bytes.startsWith(ipfsNs)) {
            val cidBytes = bytes.copyOfRange(ipfsNs.size, bytes.size)
            val cid = encodeCid(cidBytes) ?: return null
            return EnsResult.Ok(
                name = name,
                protocol = "ipfs",
                uri = "ipfs://$cid",
                decoded = cid,
            )
        }

        // IPNS: 0xe5 0x01 + CID. Same CIDv0 / CIDv1 split as above. For
        // CIDv0-style IPNS the CID is a raw libp2p-key multihash; for
        // CIDv1 the codec is typically 0x72 (libp2p-key).
        val ipnsNs = byteArrayOf(0xe5.toByte(), 0x01)
        if (bytes.size > ipnsNs.size && bytes.startsWith(ipnsNs)) {
            val cidBytes = bytes.copyOfRange(ipnsNs.size, bytes.size)
            val cid = encodeCid(cidBytes) ?: return null
            return EnsResult.Ok(
                name = name,
                protocol = "ipns",
                uri = "ipns://$cid",
                decoded = cid,
            )
        }

        return null
    }

    /**
     * Encode raw CID bytes (everything after the EIP-1577 protoCode
     * varint) to the string form the IPFS gateway accepts. Returns `null`
     * if the layout isn't recognised as either CIDv0 or CIDv1.
     */
    private fun encodeCid(cid: ByteArray): String? {
        if (cid.isEmpty()) return null
        // CIDv0: first byte is the multihash algorithm code (e.g. 0x12
        // for sha2-256). Only sha2-256 + 32 bytes is defined as CIDv0,
        // but in practice we pass the whole multihash through unchanged.
        if (cid[0] == 0x12.toByte() && cid.size >= 2) {
            val mhLen = cid[1].toInt() and 0xff
            if (cid.size == 2 + mhLen) return Base58.encode(cid)
        }
        // CIDv1: starts with 0x01 <codec> <multihash>. The whole thing
        // — version + codec + multihash — is what gets Base32-encoded
        // with the 'b' multibase prefix.
        if (cid[0] == 0x01.toByte() && cid.size >= 3) {
            return "b" + Base32.encodeLower(cid)
        }
        return null
    }

    private fun mapRevert(name: String, revertData: String): EnsResult? {
        val lower = revertData.lowercase()
        val selector = if (lower.length >= 10) lower.substring(0, 10) else return null
        return when (selector) {
            // ResolverNotFound(bytes), ResolverNotContract(bytes,address)
            "0x77209fe8", "0x1e9535f2" -> EnsResult.NotFound(name, "NO_RESOLVER")
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

        val reply = http.request(
            method = "POST",
            url = rpc,
            headers = mapOf("content-type" to "application/json", "accept" to "application/json"),
            body = body,
            timeoutMs = RPC_TIMEOUT_MS,
            maxBytes = RPC_MAX_RESPONSE_BYTES,
            followRedirects = true,
        )
        if (reply.code !in 200..299) {
            throw RuntimeException("HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val json = JSONObject(reply.body)
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
    }

    companion object {
        private const val TAG = "EnsResolver"
        private const val CACHE_TTL_MS = 15L * 60 * 1000

        private const val RPC_TIMEOUT_MS = 15_000
        private const val RPC_MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        // Per-gateway bounds for CCIP-Read fetches (same as the desktop
        // resolver's `ccip-fetch.js`).
        internal const val CCIP_TIMEOUT_MS = 15_000
        internal const val CCIP_MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_CCIP_ROUNDS = 10

        // bytes4(keccak256("OffchainLookup(address,string[],bytes,bytes4,bytes)"))
        private const val OFFCHAIN_LOOKUP_SELECTOR = "0x556f1830"

        internal fun isOffchainLookup(revertData: String): Boolean =
            revertData.length >= 10 &&
                revertData.substring(0, 10).equals(OFFCHAIN_LOOKUP_SELECTOR, ignoreCase = true)

        /**
         * Decode an `OffchainLookup` revert. `null` if any offset or
         * length points outside the data.
         */
        internal fun decodeOffchainLookup(revertData: String): OffchainLookup? {
            val bytes = runCatching { revertData.hexToBytes() }.getOrNull() ?: return null
            if (bytes.size < 4 + 5 * 32) return null
            val body = bytes.copyOfRange(4, bytes.size)
            val sender = "0x" + body.copyOfRange(12, 32).toHex()
            val urlsOffset = readUint256AsInt(body, 32) ?: return null
            val callData = decodeDynamicBytesAt(body, pointerSlot = 2) ?: return null
            val callback = body.copyOfRange(96, 100)
            val extraData = decodeDynamicBytesAt(body, pointerSlot = 4) ?: return null

            if (body.size < urlsOffset + 32) return null
            val count = readUint256AsInt(body, urlsOffset) ?: return null
            if (count < 0 || count > 64) return null
            val base = urlsOffset + 32
            val urls = ArrayList<String>(count)
            for (i in 0 until count) {
                if (body.size < base + (i + 1) * 32) return null
                val rel = readUint256AsInt(body, base + i * 32) ?: return null
                val str = decodeDynamicBytesAtOffset(body, base + rel) ?: return null
                urls.add(String(str, Charsets.UTF_8))
            }
            return OffchainLookup(sender, urls, callData, callback, extraData)
        }

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

/**
 * [runCatching], minus the hole it leaves open around coroutines: a
 * cancelled coroutine unwinds through a `CancellationException`, which
 * `runCatching` catches like any other `Throwable` and hands back as a
 * `Result.failure` — so the cancelled coroutine keeps running and
 * reports a resolution *error* for a navigation that no longer exists.
 * Cancellation isn't a failure to report; it goes to the caller.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
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

/** `abi.encode(bytes a, bytes b)` — two dynamic args, heads then tails. */
internal fun abiEncodeTwoBytes(a: ByteArray, b: ByteArray): ByteArray {
    val head = ByteArray(64)
    // Offsets relative to the start of the args: 0x40 and
    // 0x40 + 32 + padded(a).
    writeUint256(0x40L, head, 0)
    val aPaddedLen = padLen32(a.size)
    writeUint256((0x40 + 32 + aPaddedLen).toLong(), head, 32)
    val aBlock = ByteArray(32 + aPaddedLen).apply {
        writeUint256(a.size.toLong(), this, 0)
        a.copyInto(this, 32)
    }
    val bBlock = ByteArray(32 + padLen32(b.size)).apply {
        writeUint256(b.size.toLong(), this, 0)
        b.copyInto(this, 32)
    }
    return head + aBlock + bBlock
}

private fun isHexBytes(s: String): Boolean {
    if (!s.startsWith("0x") || s.length % 2 != 0) return false
    for (i in 2 until s.length) {
        val c = s[i]
        if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return false
    }
    return true
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
    return decodeDynamicBytesAt(body.hexToBytes(), pointerSlot)
}

private fun decodeDynamicBytesAt(bytes: ByteArray, pointerSlot: Int): ByteArray? {
    val pointerOffset = pointerSlot * 32
    if (bytes.size < pointerOffset + 32) return null
    val offset = readUint256AsInt(bytes, pointerOffset) ?: return null
    return decodeDynamicBytesAtOffset(bytes, offset)
}

/** Length-prefixed dynamic bytes / string whose length word sits at [offset]. */
private fun decodeDynamicBytesAtOffset(bytes: ByteArray, offset: Int): ByteArray? {
    if (offset < 0 || bytes.size < offset + 32) return null
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

/**
 * RFC 4648 Base32 using the lowercase multibase-'b' alphabet and no
 * padding. Used to render CIDv1 bytes to their canonical `bafy…`
 * string form (see [multibase](https://github.com/multiformats/multibase)).
 */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encodeLower(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val outLen = (input.size * 8 + 4) / 5
        val out = CharArray(outLen)
        var bits = 0
        var bitCount = 0
        var idx = 0
        for (b in input) {
            bits = (bits shl 8) or (b.toInt() and 0xff)
            bitCount += 8
            while (bitCount >= 5) {
                bitCount -= 5
                out[idx++] = ALPHABET[(bits ushr bitCount) and 0x1f]
            }
        }
        if (bitCount > 0) {
            out[idx++] = ALPHABET[(bits shl (5 - bitCount)) and 0x1f]
        }
        return String(out, 0, idx)
    }
}
