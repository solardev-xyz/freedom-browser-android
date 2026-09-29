package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * EIP-712 typed data (`eth_signTypedData_v4`, #113): what the sheet shows
 * and the digest that's signed, for exactly the payload desktop Freedom
 * sends — ethers' `TypedDataEncoder.getPayload`: `{types, primaryType,
 * domain, message}` with integers as decimal (or `0x`) strings.
 *
 * Strict, like ethers: a field the type names but the value lacks, a
 * number out of its type's range, bytes of the wrong length or a type no
 * one defined is an error, never a guess — a signature over a digest the
 * user wasn't shown would be worse than none. Fields the value has but
 * the type doesn't name are ignored, as every encoder does (they aren't
 * signed, and the sheet doesn't show them either).
 */
object Eip712 {
    class InvalidTypedData(message: String) : IllegalArgumentException(message)

    class Field(val name: String, val type: String)

    class TypedData(
        val types: Map<String, List<Field>>,
        val primaryType: String,
        val domain: JSONObject,
        val message: JSONObject,
    )

    /** One line of what's signed, for the sheet: a field's [label] (or `[i]` for an array item), [depth] levels in. */
    data class Line(val label: String, val value: String, val depth: Int)

    private const val DOMAIN = "EIP712Domain"

    /** The domain fields in the order EIP-712 lists them, for a payload whose types leave `EIP712Domain` out. */
    private val DOMAIN_FIELDS = listOf(
        "name" to "string",
        "version" to "string",
        "chainId" to "uint256",
        "verifyingContract" to "address",
        "salt" to "bytes32",
    )

    /** A typed-data payload bigger than this isn't one any sheet could show. */
    const val MAX_JSON = 256 * 1024

    /**
     * More lines than this isn't something a person reads before signing
     * (a permit is ~10, a marketplace order a few dozen): refused rather
     * than laid out, so a payload built to fan out can't flood the sheet.
     */
    const val MAX_LINES = 1000

    private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private val ARRAY = Regex("^(.+)\\[(\\d*)]$")
    private val UINT = Regex("^uint(\\d{1,3})$")
    private val INT = Regex("^int(\\d{1,3})$")
    private val BYTES_N = Regex("^bytes(\\d{1,2})$")
    private val HEX = Regex("^0x([0-9a-fA-F]{2})*$")
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** [raw] — a JSON string, or the object itself — as typed data. */
    fun parse(raw: Any?): TypedData = try {
        val o = when (raw) {
            is JSONObject -> raw
            is String -> {
                if (raw.length > MAX_JSON) throw InvalidTypedData("The typed data is too large")
                JSONTokener(raw).nextValue() as? JSONObject ?: throw InvalidTypedData("The typed data isn’t a JSON object")
            }
            else -> throw InvalidTypedData("The typed data is missing")
        }
        val types = LinkedHashMap<String, List<Field>>()
        val t = o.optJSONObject("types") ?: throw InvalidTypedData("The typed data has no types")
        for (name in t.keys()) {
            if (!IDENTIFIER.matches(name)) throw InvalidTypedData("Not a type name: $name")
            val arr = t.optJSONArray(name) ?: throw InvalidTypedData("Type $name isn’t a list of fields")
            val fields = (0 until arr.length()).map { i ->
                val f = arr.optJSONObject(i) ?: throw InvalidTypedData("Type $name has a field that isn’t an object")
                val fieldName = f.optString("name")
                val fieldType = f.optString("type")
                if (!IDENTIFIER.matches(fieldName) || fieldType.isEmpty()) throw InvalidTypedData("Type $name has a malformed field")
                Field(fieldName, fieldType)
            }
            if (fields.map { it.name }.toSet().size != fields.size) throw InvalidTypedData("Type $name names a field twice")
            types[name] = fields
        }
        val domain = o.optJSONObject("domain") ?: JSONObject()
        if (DOMAIN !in types) {
            types[DOMAIN] = DOMAIN_FIELDS.filter { (n, _) -> domain.has(n) }.map { (n, ty) -> Field(n, ty) }
        }
        val primaryType = o.optString("primaryType")
        if (primaryType !in types) throw InvalidTypedData("The primary type isn’t defined")
        val message = o.optJSONObject("message") ?: if (primaryType == DOMAIN) JSONObject() else throw InvalidTypedData("The typed data has no message")
        for (fields in types.values) for (f in fields) checkType(f.type, types)
        TypedData(types, primaryType, domain, message)
    } catch (e: JSONException) {
        throw InvalidTypedData("The typed data isn’t valid JSON")
    } catch (e: StackOverflowError) {
        throw InvalidTypedData("The typed data is nested too deeply")
    }

    /** `keccak256(0x1901 ‖ domainSeparator ‖ hashStruct(message))`: what's signed. */
    fun digest(td: TypedData): ByteArray = guarded {
        val typeHashes = HashMap<String, ByteArray>()
        val domainSeparator = hashStruct(DOMAIN, td.domain, td.types, typeHashes)
        val body = if (td.primaryType == DOMAIN) ByteArray(0) else hashStruct(td.primaryType, td.message, td.types, typeHashes)
        Keccak256.digest(byteArrayOf(0x19, 0x01) + domainSeparator + body)
    }

    /** The domain's chain ID, if it names one. */
    fun chainId(td: TypedData): Long? = guarded {
        if (!td.domain.has("chainId")) null else integer(td.domain.get("chainId"), "chainId").takeIf { it.bitLength() < 63 }?.toLong()
    }

    /** `encodeType`: the primary type, then every type it uses, sorted by name. */
    fun encodeType(primary: String, types: Map<String, List<Field>>): String {
        val deps = LinkedHashSet<String>()
        collect(primary, types, deps)
        deps.remove(primary)
        return (listOf(primary) + deps.sorted()).joinToString("") { name ->
            name + "(" + types.getValue(name).joinToString(",") { "${it.type} ${it.name}" } + ")"
        }
    }

    /**
     * [typeHashes] memoizes each type's `keccak256(encodeType)` across one
     * digest: `encodeType` walks every type a struct reaches, so computing
     * it afresh per instance makes many instances of a type with a long
     * dependency chain quadratic — seconds of work from a 200 KB payload.
     */
    fun hashStruct(
        type: String,
        data: JSONObject,
        types: Map<String, List<Field>>,
        typeHashes: MutableMap<String, ByteArray> = HashMap(),
    ): ByteArray {
        val typeHash = typeHashes.getOrPut(type) { Keccak256.digest(encodeType(type, types).toByteArray(Charsets.UTF_8)) }
        val out = java.io.ByteArrayOutputStream()
        out.write(typeHash)
        for (f in types.getValue(type)) {
            if (!data.has(f.name) || data.isNull(f.name)) throw InvalidTypedData("$type.${f.name} is missing")
            out.write(encodeValue(f.type, data.get(f.name), types, "$type.${f.name}", typeHashes))
        }
        return Keccak256.digest(out.toByteArray())
    }

    /**
     * What's signed, for the sheet: the domain's fields, then the message's,
     * nested structs and arrays indented. More than [MAX_LINES] in either is
     * [InvalidTypedData]: too much to review is too much to sign.
     */
    fun lines(td: TypedData): Pair<List<Line>, List<Line>> = guarded {
        val domain = ArrayList<Line>()
        describe(DOMAIN, td.domain, td.types, "", 0, domain)
        val message = ArrayList<Line>()
        if (td.primaryType != DOMAIN) describe(td.primaryType, td.message, td.types, "", 0, message)
        domain to message
    }

    private fun describe(type: String, data: JSONObject, types: Map<String, List<Field>>, prefix: String, depth: Int, out: MutableList<Line>) {
        for (f in types.getValue(type)) {
            val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
            describeValue(f.type, data.opt(f.name), types, path, f.name, depth, out)
        }
    }

    private fun describeValue(type: String, value: Any?, types: Map<String, List<Field>>, path: String, label: String, depth: Int, out: MutableList<Line>) {
        if (out.size >= MAX_LINES) throw InvalidTypedData("The typed data has too many fields to show on the phone")
        val array = ARRAY.find(type)
        when {
            array != null -> {
                val items = value as JSONArray
                out += Line(label, "${items.length()} item" + if (items.length() == 1) "" else "s", depth)
                for (i in 0 until items.length()) {
                    describeValue(array.groupValues[1], items.get(i), types, "$path[$i]", "[$i]", depth + 1, out)
                }
            }
            type in types -> {
                out += Line(label, type, depth)
                val o = value as JSONObject
                for (f in types.getValue(type)) {
                    describeValue(f.type, o.opt(f.name), types, "$path.${f.name}", f.name, depth + 1, out)
                }
            }
            else -> out += Line(label, scalarText(type, value), depth)
        }
    }

    private fun scalarText(type: String, value: Any?): String = when {
        type == "string" -> visible(value as String)
        type == "bool" -> value.toString()
        type == "address" -> NodeIdentity.checksum(hex(value, type))
        type == "bytes" || BYTES_N.matches(type) -> "0x" + hex(value, type).toHex()
        else -> integer(value, type).toString()
    }

    /**
     * [s] with every character that could hide or rearrange what's around it
     * — line breaks and other controls, bidi overrides and other format
     * characters, line/paragraph separators — written as a visible `\n` or
     * `\u202E` escape, the way [MessageSigning.readableText] refuses them
     * for `personal_sign`. Everything else is shown as is.
     */
    internal fun visible(s: String): String {
        if (s.none(MessageSigning::hides)) return s
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                !MessageSigning.hides(c) -> b.append(c)
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                else -> b.append("\\u").append(c.code.toString(16).uppercase(java.util.Locale.ROOT).padStart(4, '0'))
            }
        }
        return b.toString()
    }

    private fun collect(type: String, types: Map<String, List<Field>>, into: MutableSet<String>) {
        val base = baseType(type)
        if (base !in types || !into.add(base)) return
        for (f in types.getValue(base)) collect(f.type, types, into)
    }

    private fun baseType(type: String): String {
        var t = type
        while (true) t = ARRAY.find(t)?.groupValues?.get(1) ?: return t
    }

    private fun checkType(type: String, types: Map<String, List<Field>>) {
        val base = baseType(type)
        val ok = base in types || base == "string" || base == "bytes" || base == "bool" || base == "address" ||
            UINT.find(base)?.let { bits(it.groupValues[1]) } != null ||
            INT.find(base)?.let { bits(it.groupValues[1]) } != null ||
            BYTES_N.find(base)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 1..32 } == true
        if (!ok) throw InvalidTypedData("Unknown type: $type")
    }

    private fun bits(digits: String): Int? = digits.toIntOrNull()?.takeIf { it in 8..256 && it % 8 == 0 }

    private fun encodeValue(
        type: String,
        value: Any?,
        types: Map<String, List<Field>>,
        where: String,
        typeHashes: MutableMap<String, ByteArray>,
    ): ByteArray {
        val array = ARRAY.find(type)
        if (array != null) {
            val items = value as? JSONArray ?: throw InvalidTypedData("$where isn’t a list")
            val fixed = array.groupValues[2]
            if (fixed.isNotEmpty() && fixed.toIntOrNull() != items.length()) throw InvalidTypedData("$where must have $fixed items")
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until items.length()) {
                if (items.isNull(i)) throw InvalidTypedData("$where[$i] is missing")
                out.write(encodeValue(array.groupValues[1], items.get(i), types, "$where[$i]", typeHashes))
            }
            return Keccak256.digest(out.toByteArray())
        }
        if (type in types) {
            val o = value as? JSONObject ?: throw InvalidTypedData("$where isn’t an object")
            return hashStruct(type, o, types, typeHashes)
        }
        return when {
            type == "string" -> Keccak256.digest((value as? String ?: throw InvalidTypedData("$where isn’t a string")).toByteArray(Charsets.UTF_8))
            type == "bytes" -> Keccak256.digest(hex(value, where))
            type == "bool" -> word(if (value as? Boolean ?: throw InvalidTypedData("$where isn’t true or false")) BigInteger.ONE else BigInteger.ZERO)
            type == "address" -> {
                val s = value as? String
                if (s == null || !ADDRESS.matches(s)) throw InvalidTypedData("$where isn’t an address")
                ByteArray(12) + hex(s, where)
            }
            BYTES_N.matches(type) -> {
                val n = BYTES_N.find(type)!!.groupValues[1].toInt()
                val b = hex(value, where)
                if (b.size != n) throw InvalidTypedData("$where must be $n bytes")
                b + ByteArray(32 - n)
            }
            UINT.matches(type) -> {
                val bits = UINT.find(type)!!.groupValues[1].toInt()
                val v = integer(value, where)
                if (v.signum() < 0 || v.bitLength() > bits) throw InvalidTypedData("$where is out of range for $type")
                word(v)
            }
            INT.matches(type) -> {
                val bits = INT.find(type)!!.groupValues[1].toInt()
                val v = integer(value, where)
                if (v.bitLength() > bits - 1) throw InvalidTypedData("$where is out of range for $type")
                word(if (v.signum() < 0) v.add(BigInteger.ONE.shiftLeft(256)) else v)
            }
            else -> throw InvalidTypedData("Unknown type: $type")
        }
    }

    /** A JSON integer, or one written as a decimal or `0x` string, exactly. */
    private fun integer(value: Any?, where: String): BigInteger = when (value) {
        is Int, is Long -> BigInteger.valueOf((value as Number).toLong())
        is BigInteger -> value
        // org.json reads a number past Long as a Double: only a whole one JS itself could hold exactly is taken.
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) <= 9.007199254740991E15) {
            BigInteger.valueOf(value.toLong())
        } else {
            throw InvalidTypedData("$where isn’t a whole number (large numbers must be strings)")
        }
        is String -> {
            val t = value.trim()
            val neg = t.startsWith("-")
            val digits = t.removePrefix("-")
            val v = when {
                digits.startsWith("0x") || digits.startsWith("0X") -> digits.substring(2).takeIf { it.isNotEmpty() && it.length <= 64 && it.all(::isHexChar) }?.let { BigInteger(it, 16) }
                digits.isNotEmpty() && digits.length <= 80 && digits.all { it in '0'..'9' } -> BigInteger(digits)
                else -> null
            } ?: throw InvalidTypedData("$where isn’t a number")
            if (neg) v.negate() else v
        }
        else -> throw InvalidTypedData("$where isn’t a number")
    }

    private fun isHexChar(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hex(value: Any?, where: String): ByteArray {
        val s = value as? String
        if (s == null || !HEX.matches(s)) throw InvalidTypedData("$where isn’t 0x hex bytes")
        return ByteArray((s.length - 2) / 2) { i -> s.substring(2 + 2 * i, 4 + 2 * i).toInt(16).toByte() }
    }

    private fun word(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    /** Anything a malformed value throws on the way (a wrong JSON type, a recursion too deep) is invalid typed data. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: InvalidTypedData) {
        throw e
    } catch (e: ClassCastException) {
        throw InvalidTypedData("The typed data doesn’t match its types")
    } catch (e: NullPointerException) {
        throw InvalidTypedData("The typed data doesn’t match its types")
    } catch (e: JSONException) {
        throw InvalidTypedData("The typed data doesn’t match its types")
    } catch (e: StackOverflowError) {
        throw InvalidTypedData("The typed data is nested too deeply")
    }
}
