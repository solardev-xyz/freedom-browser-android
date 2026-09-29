package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Signing what a site asks the wallet to sign (#110): EIP-191
 * `personal_sign` and EIP-712 `eth_signTypedData_v4`, with the account's
 * key derived for the one signature and zeroed after — as the send flow
 * does ([WalletSender.vaultSigner]).
 */
object MessageSigning {
    /** EIP-191 version `0x45`: keccak256 of `"\x19Ethereum Signed Message:\n" + len + message`. */
    fun personalDigest(message: ByteArray): ByteArray {
        val prefix = "\u0019Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
        return Keccak256.digest(prefix + message)
    }

    /**
     * Signs the 32-byte [digest] with [account]'s key: `0x` + 65 bytes
     * `r ‖ s ‖ v`, v 27 or 28 — what ethers and MetaMask return. The
     * signer is recovered from the signature and must be [account], or
     * nothing is returned. Throws [VaultLockedException] if the wallet
     * isn't open.
     */
    fun sign(vault: Vault, account: WalletAccount, digest: ByteArray): String {
        val key = vault.withSeed { seed -> HdKeys.secp256k1(seed, account.path) }
        return try {
            sign(key, account.address, digest)
        } finally {
            key.fill(0)
        }
    }

    /** [sign] with the key itself (which the caller zeroes), checked against [address]. */
    internal fun sign(privateKey: ByteArray, address: String, digest: ByteArray): String {
        val sig = "0x" + EthSigning.sign(privateKey, digest).rsv().toHex()
        val recovered = Secp256k1.recover(digest, sig)
        check(recovered != null && recovered.equals(address, ignoreCase = true)) { "the signature doesn't match the account" }
        return sig
    }
}

/**
 * EIP-712 typed structured data, as `eth_signTypedData_v4` takes it —
 * MetaMask's `eth-sig-util` v4 rules where the spec leaves room (a
 * missing nested struct hashes as zero; `bytes` that aren't hex are
 * UTF-8; a missing `EIP712Domain` type is built from the domain's own
 * fields, as ethers and viem send it).
 *
 * Everything here comes from a web page: every shape is checked and a
 * bad one is an [Invalid], never a crash, and nesting is bounded.
 */
object Eip712 {
    class Invalid(message: String) : Exception(message)

    data class Field(val name: String, val type: String)

    class TypedData(
        val types: Map<String, List<Field>>,
        val primaryType: String,
        val domain: JSONObject,
        val message: JSONObject,
    ) {
        /** The domain's `chainId`, if it names one (and it's a number). */
        val chainId: Long?
            get() = domain.opt("chainId")?.takeIf { it != JSONObject.NULL }?.let {
                runCatching { integer(it, "chainId") }.getOrNull()?.takeIf { v -> v.bitLength() < 63 }?.toLong()
            }
    }

    /** The domain fields in the order EIP-712 lists them, with their types. */
    private val DOMAIN_FIELDS = listOf(
        Field("name", "string"),
        Field("version", "string"),
        Field("chainId", "uint256"),
        Field("verifyingContract", "address"),
        Field("salt", "bytes32"),
    )

    private const val MAX_DEPTH = 32

    /** Fields, array elements and type-walk steps one digest may take (#110 R1-F1). */
    internal const val MAX_WORK = 1_000_000L
    private val IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val ARRAY_SUFFIX = Regex("^(.*)\\[(\\d*)]$")

    /** [raw] — the JSON text, or the object itself — as typed data. */
    fun parse(raw: Any?): TypedData {
        val o = when (raw) {
            is JSONObject -> raw
            is String -> try {
                JSONTokener(raw).nextValue() as? JSONObject
            } catch (e: Exception) {
                null
            } catch (e: StackOverflowError) {
                null
            } ?: throw Invalid("typed data isn't a JSON object")
            else -> throw Invalid("typed data isn't a JSON object")
        }
        val typesJson = o.opt("types") as? JSONObject ?: throw Invalid("types is missing")
        val primaryType = o.opt("primaryType") as? String ?: throw Invalid("primaryType is missing")
        val domain = o.opt("domain") as? JSONObject ?: throw Invalid("domain is missing")
        val message = when (val m = o.opt("message")) {
            is JSONObject -> m
            null, JSONObject.NULL -> JSONObject()
            else -> throw Invalid("message isn't an object")
        }
        val types = LinkedHashMap<String, List<Field>>()
        for (name in typesJson.keys()) {
            if (!IDENT.matches(name)) throw Invalid("bad type name")
            val arr = typesJson.opt(name) as? JSONArray ?: throw Invalid("type $name isn't a list of fields")
            types[name] = (0 until arr.length()).map { i ->
                val f = arr.opt(i) as? JSONObject ?: throw Invalid("type $name has a field that isn't an object")
                val fname = f.opt("name") as? String ?: throw Invalid("type $name has a field with no name")
                val ftype = f.opt("type") as? String ?: throw Invalid("field $name.$fname has no type")
                Field(fname, ftype)
            }
        }
        if (!types.containsKey("EIP712Domain")) {
            types["EIP712Domain"] = DOMAIN_FIELDS.filter { domain.has(it.name) && !domain.isNull(it.name) }
        }
        if (!types.containsKey(primaryType)) throw Invalid("types has no entry for $primaryType")
        return TypedData(types, primaryType, domain, message)
    }

    /**
     * The 32 bytes `eth_signTypedData_v4` signs: keccak256(0x1901 ‖ domainSeparator ‖ hashStruct(message)).
     *
     * Each type's hash is worked out once per digest, and the whole digest
     * has a budget of [MAX_WORK] fields, array elements and type-walk steps:
     * a page can't make it take minutes with a payload that names hundreds
     * of types and repeats one struct thousands of times — past the budget
     * it's [Invalid] ("too large"). Still, call it off the main thread.
     */
    fun digest(data: TypedData): ByteArray {
        val encoder = Encoder(data.types)
        val domainSeparator = encoder.hashStruct("EIP712Domain", data.domain, 0)
        val body = if (data.primaryType == "EIP712Domain") ByteArray(0) else encoder.hashStruct(data.primaryType, data.message, 0)
        return Keccak256.digest(byteArrayOf(0x19, 0x01) + domainSeparator + body)
    }

    fun hashStruct(types: Map<String, List<Field>>, type: String, value: JSONObject, depth: Int): ByteArray =
        Encoder(types).hashStruct(type, value, depth)

    fun encodeType(types: Map<String, List<Field>>, primary: String): String = Encoder(types).encodeType(primary)

    /**
     * The message as the signature covers it, for showing: only the
     * fields its types declare, nested structs (and arrays of them) the
     * same way. A key the types don't name isn't hashed, so a site could
     * otherwise show the user a reassuring note that means nothing. Call
     * after [digest] succeeded (which checked the shape and depth).
     */
    fun signedMessage(data: TypedData): JSONObject {
        if (data.primaryType == "EIP712Domain") return JSONObject()
        return declaredOnly(data.types, data.primaryType, data.message, 0) as? JSONObject ?: JSONObject()
    }

    private fun declaredOnly(types: Map<String, List<Field>>, type: String, value: Any?, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw Invalid("typed data nests too deeply")
        ARRAY_SUFFIX.matchEntire(type)?.let { m ->
            val arr = value as? JSONArray ?: return value
            return JSONArray().apply {
                for (i in 0 until arr.length()) put(declaredOnly(types, m.groupValues[1], arr.opt(i), depth + 1) ?: JSONObject.NULL)
            }
        }
        val fields = types[type] ?: return value
        val obj = value as? JSONObject ?: return value
        return JSONObject().apply {
            for (f in fields) if (obj.has(f.name)) put(f.name, declaredOnly(types, f.type, obj.opt(f.name), depth + 1) ?: JSONObject.NULL)
        }
    }

    /** One digest's hashing: its type hashes, worked out once each, and its [MAX_WORK] budget. */
    private class Encoder(val types: Map<String, List<Field>>) {
        private val typeHashes = HashMap<String, ByteArray>()
        private var work = 0L

        private fun spend(n: Int = 1) {
            work += n
            if (work > MAX_WORK) throw Invalid("typed data is too large")
        }

        fun hashStruct(type: String, value: JSONObject, depth: Int): ByteArray {
            if (depth > MAX_DEPTH) throw Invalid("typed data nests too deeply")
            val fields = types[type] ?: throw Invalid("unknown type $type")
            val out = java.io.ByteArrayOutputStream()
            out.write(typeHash(type))
            for (f in fields) {
                spend()
                val v = value.opt(f.name)
                out.write(encodeField(f.type, if (v == JSONObject.NULL) null else v, f.name, depth))
            }
            return Keccak256.digest(out.toByteArray())
        }

        fun encodeType(primary: String): String {
            val deps = LinkedHashSet<String>()
            fun walk(t: String, depth: Int) {
                if (depth > MAX_DEPTH) throw Invalid("typed data nests too deeply")
                spend()
                val base = baseType(t)
                if (base in deps || !types.containsKey(base)) return
                deps += base
                types.getValue(base).forEach { walk(it.type, depth + 1) }
            }
            walk(primary, 0)
            deps.remove(primary)
            return (listOf(primary) + deps.sorted()).joinToString("") { t ->
                spend(types.getValue(t).size)
                t + "(" + types.getValue(t).joinToString(",") { "${it.type} ${it.name}" } + ")"
            }
        }

        private fun typeHash(type: String): ByteArray =
            typeHashes.getOrPut(type) { Keccak256.digest(encodeType(type).toByteArray(Charsets.UTF_8)) }

        private fun encodeField(type: String, value: Any?, name: String, depth: Int): ByteArray {
            if (depth > MAX_DEPTH) throw Invalid("typed data nests too deeply")
            ARRAY_SUFFIX.matchEntire(type)?.let { m ->
                val inner = m.groupValues[1]
                val arr = value as? JSONArray ?: throw Invalid("$name should be an array")
                val fixed = m.groupValues[2]
                if (fixed.isNotEmpty() && fixed.toIntOrNull() != arr.length()) throw Invalid("$name should have $fixed elements")
                val out = java.io.ByteArrayOutputStream()
                for (i in 0 until arr.length()) {
                    spend()
                    val e = arr.opt(i)
                    out.write(encodeField(inner, if (e == JSONObject.NULL) null else e, "$name[$i]", depth + 1))
                }
                return Keccak256.digest(out.toByteArray())
            }
            if (types.containsKey(type)) {
                if (value == null) return ByteArray(32)
                val obj = value as? JSONObject ?: throw Invalid("$name should be a $type object")
                return hashStruct(type, obj, depth + 1)
            }
            return encodeAtom(type, value, name)
        }
    }

    private fun baseType(t: String): String {
        var b = t
        while (true) {
            val m = ARRAY_SUFFIX.matchEntire(b) ?: return b
            b = m.groupValues[1]
        }
    }

    private fun encodeAtom(type: String, value: Any?, name: String): ByteArray {
        if (value == null) throw Invalid("missing value for $name")
        return when {
            type == "string" -> Keccak256.digest((value as? String ?: value.toString()).toByteArray(Charsets.UTF_8))
            type == "bytes" -> Keccak256.digest(dynamicBytes(value, name))
            type == "bool" -> word(if (bool(value, name)) BigInteger.ONE else BigInteger.ZERO)
            type == "address" -> {
                val s = value as? String ?: throw Invalid("$name should be an address")
                val b = hex(s) ?: throw Invalid("$name should be an address")
                if (b.size != 20) throw Invalid("$name should be an address")
                ByteArray(12) + b
            }
            type.startsWith("bytes") -> {
                val n = type.removePrefix("bytes").toIntOrNull()?.takeIf { it in 1..32 } ?: throw Invalid("unknown type $type")
                val b = when (value) {
                    is String -> hex(value) ?: throw Invalid("$name should be hex")
                    else -> BigIntegerBytes.of(integer(value, name))
                }
                if (b.size > n) throw Invalid("$name is longer than $n bytes")
                b + ByteArray(32 - b.size)
            }
            type.startsWith("uint") || type.startsWith("int") -> {
                val signed = type.startsWith("int")
                val bits = type.removePrefix(if (signed) "int" else "uint").ifEmpty { "256" }.toIntOrNull()
                    ?.takeIf { it in 8..256 && it % 8 == 0 } ?: throw Invalid("unknown type $type")
                val v = integer(value, name)
                val ok = if (signed) {
                    v >= BigInteger.ONE.shiftLeft(bits - 1).negate() && v < BigInteger.ONE.shiftLeft(bits - 1)
                } else {
                    v.signum() >= 0 && v.bitLength() <= bits
                }
                if (!ok) throw Invalid("$name is out of range for $type")
                word(if (v.signum() < 0) v.add(BigInteger.ONE.shiftLeft(256)) else v)
            }
            else -> throw Invalid("unknown type $type")
        }
    }

    private fun word(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    private fun bool(v: Any, name: String): Boolean = when (v) {
        is Boolean -> v
        is Number -> integer(v, name).signum() != 0
        "true" -> true
        "false" -> false
        else -> throw Invalid("$name should be true or false")
    }

    private fun dynamicBytes(v: Any, name: String): ByteArray = when (v) {
        is String -> if (v.startsWith("0x") || v.startsWith("0X")) hex(v) ?: throw Invalid("$name isn't hex") else v.toByteArray(Charsets.UTF_8)
        is Number -> BigIntegerBytes.of(integer(v, name))
        else -> throw Invalid("$name should be bytes")
    }

    /** A JSON number, a decimal string or a `0x` hex string as an integer. */
    internal fun integer(v: Any, name: String): BigInteger {
        val n = when (v) {
            is Int, is Long, is Short, is Byte -> BigInteger.valueOf((v as Number).toLong())
            is BigInteger -> v
            is BigDecimal -> runCatching { v.toBigIntegerExact() }.getOrNull()
            is Double, is Float -> (v as Number).toDouble().takeIf { it == Math.floor(it) && Math.abs(it) <= MAX_SAFE }
                ?.let { BigDecimal(it).toBigInteger() }
            is String -> {
                val t = v.trim()
                when {
                    t.startsWith("-0x") || t.startsWith("-0X") -> t.substring(3).takeIf(::isHexDigits)?.let { BigInteger(it, 16).negate() }
                    t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).takeIf(::isHexDigits)?.let { BigInteger(it, 16) }
                    t.matches(DECIMAL) -> BigInteger(t)
                    else -> null
                }
            }
            else -> null
        } ?: throw Invalid("$name should be an integer")
        if (n.bitLength() > 256) throw Invalid("$name is out of range")
        return n
    }

    private const val MAX_SAFE = 9007199254740991.0
    private val DECIMAL = Regex("^-?\\d{1,80}$")

    private fun isHexDigits(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** `0x`-hex (even length, possibly empty) as bytes, or null. */
    internal fun hex(s: String): ByteArray? {
        val t = s.trim()
        if (!t.startsWith("0x") && !t.startsWith("0X")) return null
        val h = t.substring(2)
        if (h.length % 2 != 0 || h.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }) return null
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private object BigIntegerBytes {
        fun of(v: BigInteger): ByteArray {
            if (v.signum() < 0) throw Invalid("negative bytes")
            val raw = v.toByteArray()
            return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        }
    }
}
