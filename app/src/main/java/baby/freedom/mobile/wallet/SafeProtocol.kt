package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe smart accounts (#141), byte for byte what desktop Freedom's
 * `safe-executor.js` gets from `@safe-global/protocol-kit` 8 for Safe
 * v1.4.1, worked out here with no SDK and no hosted service:
 *
 *  - the counterfactual (CREATE2) address of a Safe from its frozen init
 *    params — owners, threshold and a 128-bit salt nonce — through the
 *    canonical `SafeProxyFactory`, with the `SafeL2` singleton and the
 *    `CompatibilityFallbackHandler` protocol-kit picks for Gnosis;
 *  - the factory's `createProxyWithNonce` call that deploys it;
 *  - a SafeTx and a SafeMessage as the EIP-712 typed data every owner
 *    signs — the same JSON `eth_signTypedData_v4` takes, so desktop
 *    Freedom, another phone or any wallet can co-sign it — and its hash;
 *  - the packed owner signatures and the `execTransaction` call.
 *
 * `SafeProtocolTest` pins every one of these to protocol-kit's own output.
 */
object SafeProtocol {
    /** The contract version every Safe here is created with (desktop's `SAFE_VERSION`). */
    const val VERSION = "1.4.1"

    /** v1 deploys on Gnosis only, as desktop does; the chain stays a parameter everywhere else. */
    const val CHAIN_ID = 100L

    /** The canonical v1.4.1 `SafeProxyFactory`: the same address on every chain, so a Safe's address is too. */
    const val FACTORY = "0x4e1DCf7AD4e460CfD30791CCC4F9c8a4f820ec67"

    /** The v1.4.1 `SafeL2` singleton every proxy delegates to. */
    const val SINGLETON = "0x29fcB43b46531BcA003ddC8FCB67FFE91900C762"

    /** The v1.4.1 `CompatibilityFallbackHandler`: it answers EIP-1271 `isValidSignature` for SafeMessages. */
    const val FALLBACK_HANDLER = "0xfd0732Dc9E303f09fCEf3a7388Ad10A83459Ec99"

    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

    /** The presets desktop offers: 1 of 2 and 2 of 3. 2 of 2 isn't one: losing either key would lock the funds for good. */
    fun validPreset(owners: Int, threshold: Int) = (owners == 2 && threshold == 1) || (owners == 3 && threshold == 2)

    /**
     * `SafeProxyFactory.proxyCreationCode()` for v1.4.1 — a constant of the
     * canonical factory, which protocol-kit reads from the chain for each
     * prediction. Kept here so an address can be worked out offline.
     */
    private const val PROXY_CREATION_CODE =
        "608060405234801561001057600080fd5b506040516101e63803806101e68339818101604052602081101561003357600080fd5b8101908080519060200190929190505050600073ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614156100ca576040517f08c379a00000000000000000000000000000000000000000000000000000000081526004018080602001828103825260228152602001806101c46022913960400191505060405180910390fd5b806000806101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff1602179055505060ab806101196000396000f3fe608060405273ffffffffffffffffffffffffffffffffffffffff600054167fa619486e0000000000000000000000000000000000000000000000000000000060003514156050578060005260206000f35b3660008037600080366000845af43d6000803e60008114156070573d6000fd5b3d6000f3fea264697066735822122003d1488ee65e08fa41e58e888a9865554c535f2c77126a82cb4c0f917f31441364736f6c63430007060033496e76616c69642073696e676c65746f6e20616464726573732070726f7669646564"

    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** A fresh salt nonce: 128 random bits in decimal, as desktop's `generateSaltNonce`. Frozen into the Safe for good. */
    fun newSaltNonce(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return BigInteger(1, bytes).toString()
    }

    /** `setup(owners, threshold, 0, 0x, fallbackHandler, 0, 0, 0)`: the proxy's initializer. */
    fun setupData(owners: List<String>, threshold: Int): ByteArray {
        checkInit(owners, threshold)
        return selector("setup(address[],uint256,address,bytes,address,address,uint256,address)") + Abi.encode(
            Abi.Addresses(owners),
            Abi.Word(Abi.uint(BigInteger.valueOf(threshold.toLong()))),
            Abi.Word(Abi.address(ZERO_ADDRESS)),
            Abi.Bytes(ByteArray(0)),
            Abi.Word(Abi.address(FALLBACK_HANDLER)),
            Abi.Word(Abi.address(ZERO_ADDRESS)),
            Abi.Word(Abi.uint(BigInteger.ZERO)),
            Abi.Word(Abi.address(ZERO_ADDRESS)),
        )
    }

    /** The factory call that deploys the Safe: `createProxyWithNonce(singleton, setupData, saltNonce)`, sent to [FACTORY]. */
    fun deploymentData(owners: List<String>, threshold: Int, saltNonce: String): ByteArray =
        selector("createProxyWithNonce(address,bytes,uint256)") + Abi.encode(
            Abi.Word(Abi.address(SINGLETON)),
            Abi.Bytes(setupData(owners, threshold)),
            Abi.Word(Abi.uint(salt(saltNonce))),
        )

    /**
     * The Safe's address (EIP-55), deployed or not, on any chain the
     * canonical factory is on: CREATE2 over keccak256(keccak256(setupData)
     * ‖ saltNonce) and the proxy's creation code with the singleton.
     */
    fun predictAddress(owners: List<String>, threshold: Int, saltNonce: String): String {
        val salt = Keccak256.digest(Keccak256.digest(setupData(owners, threshold)) + Abi.uint(salt(saltNonce)))
        val initCode = PROXY_CREATION_CODE.hexBytes() + Abi.address(SINGLETON)
        val hash = Keccak256.digest(byteArrayOf(0xff.toByte()) + FACTORY.substring(2).hexBytes() + salt + Keccak256.digest(initCode))
        return NodeIdentity.checksum(hash.copyOfRange(12, 32))
    }

    private fun salt(saltNonce: String): BigInteger {
        require(saltNonce.isNotEmpty() && saltNonce.length <= 78 && saltNonce.all { it in '0'..'9' }) { "a salt nonce is a decimal number" }
        return BigInteger(saltNonce).also { require(it.bitLength() <= 256) { "a salt nonce is a uint256" } }
    }

    private fun checkInit(owners: List<String>, threshold: Int) {
        require(owners.all { ADDRESS.matches(it) }) { "an owner isn't an address" }
        require(owners.map { it.lowercase() }.toSet().size == owners.size) { "an owner is listed twice" }
        require(owners.none { it.equals(ZERO_ADDRESS, ignoreCase = true) }) { "the zero address can't own a Safe" }
        require(threshold in 1..owners.size) { "the threshold must be between 1 and the number of owners" }
    }

    /**
     * A Safe transaction: a plain `CALL` from the Safe (never a
     * `DELEGATECALL`) with no gas refund — what desktop builds, and all
     * a co-signer here will sign. [nonce] is the Safe's own nonce.
     */
    data class SafeTx(val to: String, val value: BigInteger, val data: ByteArray, val nonce: BigInteger) {
        init {
            require(ADDRESS.matches(to)) { "not an address" }
            require(value.signum() >= 0 && value.bitLength() <= 256 && nonce.signum() >= 0 && nonce.bitLength() <= 53) { "out of range" }
        }

        override fun equals(other: Any?) = other is SafeTx && to.equals(other.to, ignoreCase = true) &&
            value == other.value && data.contentEquals(other.data) && nonce == other.nonce

        override fun hashCode() = listOf(to.lowercase(), value, data.contentHashCode(), nonce).hashCode()
    }

    private val SAFE_TX_FIELDS = listOf(
        "to" to "address", "value" to "uint256", "data" to "bytes", "operation" to "uint8", "safeTxGas" to "uint256",
        "baseGas" to "uint256", "gasPrice" to "uint256", "gasToken" to "address", "refundReceiver" to "address", "nonce" to "uint256",
    )
    private val SAFE_MESSAGE_FIELDS = listOf("message" to "bytes")
    private val DOMAIN_FIELDS = listOf("chainId" to "uint256", "verifyingContract" to "address")

    private fun fields(list: List<Pair<String, String>>) =
        JSONArray().apply { list.forEach { (name, type) -> put(JSONObject().put("type", type).put("name", name)) } }

    private fun types(primary: String, list: List<Pair<String, String>>) =
        JSONObject().put("EIP712Domain", fields(DOMAIN_FIELDS)).put(primary, fields(list))

    /**
     * [tx] from [safe] on [chainId] as the typed data its owners sign:
     * protocol-kit's `generateTypedData` after desktop's `toJsonSafe` —
     * amounts as decimal strings, `operation` and `nonce` as numbers.
     */
    fun safeTxTypedData(safe: String, chainId: Long, tx: SafeTx): JSONObject = JSONObject()
        .put("types", types("SafeTx", SAFE_TX_FIELDS))
        .put("domain", JSONObject().put("verifyingContract", eip55(safe)).put("chainId", chainId))
        .put("primaryType", "SafeTx")
        .put(
            "message",
            JSONObject()
                .put("to", eip55(tx.to))
                .put("value", tx.value.toString())
                .put("data", "0x" + tx.data.toHex())
                .put("operation", 0)
                .put("baseGas", "0")
                .put("gasPrice", "0")
                .put("gasToken", ZERO_ADDRESS)
                .put("refundReceiver", ZERO_ADDRESS)
                .put("nonce", tx.nonce.toLong())
                .put("safeTxGas", "0"),
        )

    /**
     * The SafeMessage envelope for a `personal_sign` [text], as desktop's
     * `safe-messages.js` builds it: the EIP-191 digest a verifier computes
     * is the envelope's `message` bytes. The Safe's fallback handler
     * checks the owners' signatures over this envelope when a verifier
     * calls `isValidSignature(digest, signature)` (EIP-1271).
     */
    fun messageTypedData(safe: String, chainId: Long, text: String): JSONObject {
        val digest = MessageSigning.personalDigest(text.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("types", types("SafeMessage", SAFE_MESSAGE_FIELDS))
            .put("domain", JSONObject().put("chainId", chainId).put("verifyingContract", eip55(safe)))
            .put("primaryType", "SafeMessage")
            .put("message", JSONObject().put("message", "0x" + digest.toHex()))
    }

    /** The 32-byte hash owners sign ([typedData]'s EIP-712 digest): the SafeTx hash, or the SafeMessage hash. */
    fun hash(typedData: JSONObject): ByteArray = Eip712.digest(Eip712.parseStrict(typedData.toString()))

    /** One owner's signature: `r ‖ s ‖ v` (65 bytes, v 27 or 28) over the hash, and who made it. */
    data class OwnerSignature(val signer: String, val data: String)

    /**
     * Who signed [hash] with [signature] (65 bytes hex, v 27/28 or 0/1),
     * EIP-55, or null if it isn't a signature at all. v 0/1 is how some
     * signers write it; the Safe only takes 27/28, so it's normalised in
     * [normalized].
     */
    fun recoverSigner(hash: ByteArray, signature: String): String? =
        normalized(signature)?.let { Secp256k1.recover(hash, it) }?.let { eip55(it) }

    /** [signature] as `0x` + 130 lower-case hex digits with v 27/28, or null if it isn't one. */
    fun normalized(signature: String): String? {
        val hex = signature.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 130 || hex.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }) return null
        val v = hex.substring(128).toInt(16)
        val fixed = when (v) {
            27, 28 -> v
            0, 1 -> v + 27
            else -> return null
        }
        return "0x" + hex.substring(0, 128).lowercase() + "%02x".format(fixed)
    }

    /**
     * The owners' signatures as the Safe checks them: sorted by signer
     * address, ascending, and concatenated (protocol-kit's
     * `buildSignatureBytes` for plain ECDSA signatures).
     */
    fun signatureBytes(signatures: List<OwnerSignature>): ByteArray =
        signatures.sortedBy { it.signer.lowercase() }.map { it.data.removePrefix("0x").hexBytes() }
            .fold(ByteArray(0)) { acc, b -> acc + b }

    /** `execTransaction(…)` for [tx] with [signatures], sent to the Safe by whichever account pays the gas. */
    fun execTransactionData(tx: SafeTx, signatures: List<OwnerSignature>): ByteArray =
        selector("execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)") + Abi.encode(
            Abi.Word(Abi.address(tx.to)),
            Abi.Word(Abi.uint(tx.value)),
            Abi.Bytes(tx.data),
            Abi.Word(Abi.uint(BigInteger.ZERO)),
            Abi.Word(Abi.uint(BigInteger.ZERO)),
            Abi.Word(Abi.uint(BigInteger.ZERO)),
            Abi.Word(Abi.uint(BigInteger.ZERO)),
            Abi.Word(Abi.address(ZERO_ADDRESS)),
            Abi.Word(Abi.address(ZERO_ADDRESS)),
            Abi.Bytes(signatureBytes(signatures)),
        )

    /**
     * A request to co-sign, as another owner's device shares it: the typed
     * data from [safeTxTypedData] or [messageTypedData] (for a message, with
     * the words themselves beside it as `text`, checked against the digest).
     */
    sealed class Request {
        abstract val safe: String
        abstract val chainId: Long

        /** The exact typed data to sign, re-serialised from what was checked. */
        abstract val typedData: JSONObject

        /** What the owners sign ([hash] of [typedData]). */
        abstract val hash: ByteArray

        class Tx(
            override val safe: String,
            override val chainId: Long,
            val tx: SafeTx,
            override val typedData: JSONObject,
            override val hash: ByteArray,
        ) : Request()

        /** [text] is always there, and [digest] is its EIP-191 `personal_sign` digest. */
        class Message(
            override val safe: String,
            override val chainId: Long,
            val digest: ByteArray,
            val text: String,
            override val typedData: JSONObject,
            override val hash: ByteArray,
        ) : Request()
    }

    /** A shared request larger than this is no SafeTx this wallet would sign. */
    const val MAX_REQUEST = 64 * 1024

    /** [request]'s share form: its typed data, plus the message's words as `text`. */
    fun shareText(typedData: JSONObject, text: String?): String =
        JSONObject(typedData.toString()).apply { if (text != null) put("text", text) }.toString()

    /**
     * [raw] as a Safe co-signing request, or [Eip712.Invalid] with what's
     * wrong, for the user. Only what this wallet builds itself is taken:
     * exactly the v1.4.1 SafeTx / SafeMessage types over a domain of
     * `chainId` and `verifyingContract`; a SafeTx that is a plain `CALL`
     * with no gas refund (a `DELEGATECALL` could hand the Safe to any
     * code, a refund pays someone from it).
     */
    fun parseRequest(raw: String): Request {
        if (raw.length > MAX_REQUEST) throw Eip712.Invalid("This request is too large to be a Safe transaction")
        val o = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: throw Eip712.Invalid("This isn’t a Safe signing request")
        val primary = o.opt("primaryType") as? String
        val expected = when (primary) {
            "SafeTx" -> SAFE_TX_FIELDS
            "SafeMessage" -> SAFE_MESSAGE_FIELDS
            else -> throw Eip712.Invalid("This isn’t a Safe signing request")
        }
        val types = o.optJSONObject("types") ?: throw Eip712.Invalid("This isn’t a Safe signing request")
        if (types.length() != 2 || !sameFields(types.optJSONArray("EIP712Domain"), DOMAIN_FIELDS) ||
            !sameFields(types.optJSONArray(primary), expected)
        ) {
            throw Eip712.Invalid("This request’s types aren’t those of a Safe v$VERSION ${if (primary == "SafeTx") "transaction" else "message"}")
        }
        val domain = o.optJSONObject("domain") ?: throw Eip712.Invalid("This request has no domain")
        if (domain.length() != 2) throw Eip712.Invalid("This request’s domain has fields a Safe doesn’t sign")
        val safe = (domain.opt("verifyingContract") as? String)?.takeIf { ADDRESS.matches(it) }
            ?: throw Eip712.Invalid("This request names no Safe")
        val chainId = domain.opt("chainId")?.let { runCatching { Eip712.integer(it, "chainId") }.getOrNull() }
            ?.takeIf { it.signum() > 0 && it.bitLength() < 63 }?.toLong()
            ?: throw Eip712.Invalid("This request names no chain")
        val message = o.optJSONObject("message") ?: throw Eip712.Invalid("This request has no message")
        // Checked by the strict encoder, which is also what the owners' signatures cover.
        val parsed = Eip712.parseStrict(JSONObject().put("types", types).put("domain", domain).put("primaryType", primary).put("message", message).toString())
        val hash = Eip712.digest(parsed)
        return if (primary == "SafeTx") {
            fun int(name: String) = Eip712.integer(message.get(name), name)
            if (int("operation").signum() != 0) throw Eip712.Invalid("This transaction is a delegate call, which can hand the Safe to other code. Freedom doesn’t sign those.")
            if (int("safeTxGas").signum() != 0 || int("baseGas").signum() != 0 || int("gasPrice").signum() != 0 ||
                !message.getString("gasToken").equals(ZERO_ADDRESS, ignoreCase = true) ||
                !message.getString("refundReceiver").equals(ZERO_ADDRESS, ignoreCase = true)
            ) {
                throw Eip712.Invalid("This transaction pays a gas refund out of the Safe. Freedom doesn’t sign those.")
            }
            val nonce = int("nonce").takeIf { it.bitLength() <= 53 } ?: throw Eip712.Invalid("This transaction’s nonce is out of range")
            val data = Eip712.hex(message.getString("data")) ?: throw Eip712.Invalid("This transaction’s data isn’t hex")
            val tx = SafeTx(eip55(message.getString("to")), int("value"), data, nonce)
            Request.Tx(eip55(safe), chainId, tx, safeTxTypedData(safe, chainId, tx), hash)
        } else {
            val digest = Eip712.hex(message.getString("message"))?.takeIf { it.size == 32 }
                ?: throw Eip712.Invalid("This message request isn’t for a signed text")
            // The words are required, and must be what the digest is of: a bare 32-byte
            // `message` could be any hash — a Permit2 permit's, an exchange order's — and the
            // Safe's EIP-1271 `isValidSignature(bytes32)` would then approve it for anyone.
            val text = (o.opt("text") as? String)
                ?: throw Eip712.Invalid("This message request doesn’t include the words to sign, only a hash. Freedom doesn’t sign those: the hash could approve anything.")
            if (!MessageSigning.personalDigest(text.toByteArray(Charsets.UTF_8)).contentEquals(digest)) {
                throw Eip712.Invalid("This message request’s text doesn’t match what would be signed")
            }
            val typedData = JSONObject()
                .put("types", types(primary, SAFE_MESSAGE_FIELDS))
                .put("domain", JSONObject().put("chainId", chainId).put("verifyingContract", eip55(safe)))
                .put("primaryType", primary)
                .put("message", JSONObject().put("message", "0x" + digest.toHex()))
            Request.Message(eip55(safe), chainId, digest, text, typedData, hash)
        }
    }

    private fun sameFields(arr: JSONArray?, expected: List<Pair<String, String>>): Boolean {
        if (arr == null || arr.length() != expected.size) return false
        return expected.indices.all { i ->
            val f = arr.optJSONObject(i) ?: return false
            f.length() == 2 && f.opt("name") == expected[i].first && f.opt("type") == expected[i].second
        }
    }

    /** `nonce()` call data. */
    val NONCE_CALL: String = "0x" + selector("nonce()").toHex()

    /** `getOwners()` call data. */
    val OWNERS_CALL: String = "0x" + selector("getOwners()").toHex()

    /** The most modules [MODULES_CALL] reads in one page; a Safe with more reads as "not known". */
    const val MAX_MODULES = 64

    /** `getModulesPaginated(0x…01, 64)` call data: the Safe's enabled modules from the start of their list. */
    val MODULES_CALL: String = "0x" + (
        selector("getModulesPaginated(address,uint256)") +
            ByteArray(31) + byteArrayOf(1) + ByteArray(31) + byteArrayOf(MAX_MODULES.toByte())
        ).toHex()

    /**
     * The `Guard` interface id a v1.4.1 Safe's `setGuard` asks a new guard
     * for (GS300): `checkTransaction(…) ^ checkAfterExecution(bytes32,bool)`.
     */
    val GUARD_INTERFACE_ID: ByteArray = selector(
        "checkTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes,address)",
    ).zip(selector("checkAfterExecution(bytes32,bool)")) { a, b -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()

    /** `supportsInterface(GUARD_INTERFACE_ID)` call data. */
    val SUPPORTS_GUARD_CALL: String = "0x" + (selector("supportsInterface(bytes4)") + GUARD_INTERFACE_ID + ByteArray(28)).toHex()

    /**
     * A `getModulesPaginated` return value `(address[] array, address next)`:
     * the whole module list (EIP-55, in the Safe's linked-list order), or null
     * if [hex] isn't one or the page doesn't reach the list's end (`next` isn't
     * the sentinel `0x…01`).
     */
    fun decodeModules(hex: String): List<String>? = runCatching {
        val b = hex.removePrefix("0x").hexBytes()
        if (b.size < 96) return null
        val next = b.copyOfRange(32, 64)
        if (next.copyOfRange(0, 31).any { it.toInt() != 0 } || next[31].toInt() != 1) return null
        // The array's own encoding is an `address[]` at its offset: reuse that decoder on a re-based copy.
        val offset = BigInteger(1, b.copyOfRange(0, 32))
        if (offset != BigInteger.valueOf(64)) return null
        decodeAddresses("0x" + (Abi.uint(BigInteger.valueOf(32)) + b.copyOfRange(64, b.size)).toHex())
    }.getOrNull()

    /** An ABI `bool` as a Safe compiled with abicoder v1 reads it: true for any non-zero first word, null if shorter than a word. */
    fun decodeBool(hex: String): Boolean? = runCatching {
        val b = hex.removePrefix("0x").hexBytes()
        if (b.size < 32) null else b.copyOfRange(0, 32).any { it.toInt() != 0 }
    }.getOrNull()

    /** `getThreshold()` call data. */
    val THRESHOLD_CALL: String = "0x" + selector("getThreshold()").toHex()

    /** A `uint256` return value, or null if [hex] isn't one word. */
    fun decodeUint(hex: String): BigInteger? = Erc20.decodeUint256(hex)

    /** An `address[]` return value (EIP-55), or null if [hex] isn't one. At most 64 entries: no Safe here has more. */
    fun decodeAddresses(hex: String): List<String>? = runCatching {
        val b = hex.removePrefix("0x").hexBytes()
        val offset = BigInteger(1, b.copyOfRange(0, 32))
        if (offset != BigInteger.valueOf(32)) return null
        val n = BigInteger(1, b.copyOfRange(32, 64))
        if (n > BigInteger.valueOf(64) || b.size != 64 + 32 * n.toInt()) return null
        (0 until n.toInt()).map { i ->
            val word = b.copyOfRange(64 + 32 * i, 96 + 32 * i)
            if (word.copyOfRange(0, 12).any { it.toInt() != 0 }) return null
            NodeIdentity.checksum(word.copyOfRange(12, 32))
        }
    }.getOrNull()

    /** [address] (`0x` + 40 hex digits, any case) EIP-55 checksummed. */
    fun eip55(address: String): String {
        require(ADDRESS.matches(address)) { "not an address" }
        return NodeIdentity.checksum(address.substring(2).hexBytes())
    }

    private fun selector(signature: String): ByteArray = Keccak256.digest(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Just enough of the Solidity ABI for the calls above: words, `bytes` and `address[]`. */
    private object Abi {
        sealed interface Arg
        class Word(val word: ByteArray) : Arg
        class Bytes(val bytes: ByteArray) : Arg
        class Addresses(val addresses: List<String>) : Arg

        fun uint(v: BigInteger): ByteArray {
            require(v.signum() >= 0 && v.bitLength() <= 256)
            val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - raw.size) + raw
        }

        fun address(a: String): ByteArray {
            require(ADDRESS.matches(a)) { "not an address" }
            return ByteArray(12) + a.substring(2).hexBytes()
        }

        fun encode(vararg args: Arg): ByteArray {
            var head = ByteArray(0)
            var tail = ByteArray(0)
            val headSize = 32L * args.size
            for (arg in args) {
                when (arg) {
                    is Word -> head += arg.word
                    is Bytes -> {
                        head += uint(BigInteger.valueOf(headSize + tail.size))
                        val pad = (32 - arg.bytes.size % 32) % 32
                        tail += uint(BigInteger.valueOf(arg.bytes.size.toLong())) + arg.bytes + ByteArray(pad)
                    }
                    is Addresses -> {
                        head += uint(BigInteger.valueOf(headSize + tail.size))
                        tail += uint(BigInteger.valueOf(arg.addresses.size.toLong()))
                        arg.addresses.forEach { tail += address(it) }
                    }
                }
            }
            return head + tail
        }
    }
}
