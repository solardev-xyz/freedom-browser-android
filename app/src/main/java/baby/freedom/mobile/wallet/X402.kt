package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The x402 protocol (#140, https://www.x402.org/) as the browser speaks it —
 * desktop's `src/main/x402/` with `@x402/core` and `@x402/evm`'s `exact`
 * scheme: a server answers `402 Payment Required` with its terms in a
 * header, the browser signs an EIP-3009 `transferWithAuthorization` for one
 * of the offers, and sends the same request again with the signed payment
 * in a header of its own.
 *
 *  - v2: terms in `PAYMENT-REQUIRED`, the payment in `PAYMENT-SIGNATURE`,
 *    networks as CAIP-2 `eip155:<chainId>`;
 *  - v1: terms in `X-PAYMENT-REQUIRED`, the payment in `X-PAYMENT`,
 *    networks by name (only `base` and `ethereum`, as desktop).
 *
 * Both are base64 of JSON. Terms in a response *body* (some v1 servers) are
 * out of reach: WebView shows a page's response headers, never its body.
 *
 * Only the `exact` scheme's EIP-3009 flavour is signed: it moves exactly the
 * amount asked, to exactly the address named, and nothing else — no
 * approval, no Permit2 allowance a site could draw on later. Everything
 * here comes from a web server, so every field is checked; an offer that
 * fails a check is listed with why ([Required.unusable]), never signed.
 */
object X402 {
    const val REQUIRED_V2 = "PAYMENT-REQUIRED"
    const val REQUIRED_V1 = "X-PAYMENT-REQUIRED"
    const val SIGNATURE_V2 = "PAYMENT-SIGNATURE"
    const val SIGNATURE_V1 = "X-PAYMENT"

    /** A terms header bigger than this isn't read: real ones are a few hundred bytes to a few KB. */
    internal const val MAX_HEADER_CHARS = 64 * 1024

    /** Offers past this many aren't looked at. */
    internal const val MAX_OFFERS = 16

    /**
     * The longest an authorization is valid for, whatever the server's
     * `maxTimeoutSeconds`: the server can settle it until then. The
     * `accepted` terms are echoed back untouched — only `validBefore`,
     * which is the payer's to choose, is shortened.
     */
    internal const val MAX_VALIDITY_SECONDS = 600L

    /**
     * An authorization must have at least this long left when it's sent
     * (desktop's `MIN_AUTHORIZATION_RUNWAY_SECONDS`): `@x402/evm`'s
     * facilitator refuses one with less than 6 s, on its own clock, after
     * the retry's round trip.
     */
    internal const val MIN_RUNWAY_SECONDS = 20L

    /**
     * The shortest `maxTimeoutSeconds` an offer can have and still be
     * paid: [MIN_RUNWAY_SECONDS] plus time to sign. A shorter one would
     * always run out before it's sent, so it's listed as unpayable on the
     * sheet rather than failing after the user taps Pay (#218 R1-M1).
     */
    internal const val MIN_TIMEOUT_SECONDS = MIN_RUNWAY_SECONDS + 10

    /**
     * Below this many seconds to confirm in ([confirmSeconds]), the sheet
     * of a Ledger account warns that the payment may run out while it's
     * reviewed on the device: the Ledger shows the authorization field by
     * field, which takes longer than a tap on the phone (#218 R1-F1).
     */
    internal const val LEDGER_CONFIRM_SECONDS = 60L

    /**
     * How long, from when [offer]'s authorization is made, it can take to
     * sign it and still be sent: its validity less [MIN_RUNWAY_SECONDS].
     */
    fun confirmSeconds(offer: Offer): Long = minOf(offer.maxTimeoutSeconds, MAX_VALIDITY_SECONDS) - MIN_RUNWAY_SECONDS

    /** v1 network names this browser pays on (desktop's `V1_NETWORKS`). */
    private val V1_NETWORKS = mapOf("base" to 8453L, "ethereum" to 1L)

    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val DIGITS = Regex("^[0-9]{1,78}$")
    private val CAIP2_EIP155 = Regex("^eip155:([1-9][0-9]{0,15})$")
    private val UINT256_MAX = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

    /** One offer the browser can pay: on [chainId], [amount] base units of [asset] to [payTo]. */
    class Offer internal constructor(
        /** Its position in the server's `accepts`, in the server's order. */
        val index: Int,
        /** The entry exactly as the server sent it: v2 echoes it back as `accepted`. */
        internal val raw: JSONObject,
        val network: String,
        val chainId: Long,
        /** The token contract, EIP-55. */
        val asset: String,
        val amount: BigInteger,
        /** Who gets paid, EIP-55. */
        val payTo: String,
        val maxTimeoutSeconds: Long,
        /** The token's EIP-712 domain `name` and `version`, from the offer's `extra`. */
        val domainName: String,
        val domainVersion: String,
    ) {
        /** `chainId:0x…` in lower case, the wallet's token key ([Token.key]). */
        val assetKey: String get() = "$chainId:${asset.lowercase()}"
    }

    /** A server's terms: the [offers] the browser can pay, and why each other one can't be. */
    class Required internal constructor(
        val version: Int,
        val offers: List<Offer>,
        /** One line per offer that isn't payable, e.g. "Offer 2: network solana isn't supported". */
        val unusable: List<String>,
        /** The server's words about what's paid for (`resource.description` / `description`), if any. */
        val description: String?,
        /** v2's `resource` object, echoed back in the payment. */
        internal val resource: JSONObject?,
        /** v2's `extensions`, echoed back in the payment. */
        internal val extensions: JSONObject?,
    )

    /**
     * The terms in [headers] (a response's, names in any case), or null if
     * it carries none this browser reads. v2's header wins over v1's.
     */
    fun requiredFrom(headers: Map<String, String>?): Required? {
        if (headers == null) return null
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        header(REQUIRED_V2)?.let { v -> parseRequired(v)?.takeIf { it.version == 2 }?.let { return it } }
        header(REQUIRED_V1)?.let { v -> parseRequired(v)?.takeIf { it.version == 1 }?.let { return it } }
        return null
    }

    /** A terms header's value (base64 of JSON), or null if it isn't one. */
    fun parseRequired(value: String): Required? {
        if (value.length > MAX_HEADER_CHARS) return null
        val json = decodeJson(value.trim()) ?: return null
        val version = (json.opt("x402Version") as? Number)?.toInt() ?: return null
        if (version != 1 && version != 2) return null
        val accepts = json.opt("accepts") as? JSONArray ?: return null
        val offers = mutableListOf<Offer>()
        val unusable = mutableListOf<String>()
        for (i in 0 until minOf(accepts.length(), MAX_OFFERS)) {
            val entry = accepts.opt(i) as? JSONObject
            if (entry == null) {
                unusable += "Offer ${i + 1}: not an offer"
                continue
            }
            when (val r = offer(i, entry, version)) {
                is OfferResult.Ok -> offers += r.offer
                is OfferResult.No -> unusable += "Offer ${i + 1}: ${r.why}"
            }
        }
        if (offers.isEmpty() && unusable.isEmpty()) return null
        val resource = if (version == 2) json.opt("resource") as? JSONObject else null
        val description = when (version) {
            2 -> resource?.opt("description") as? String
            else -> (accepts.opt(0) as? JSONObject)?.opt("description") as? String
        }?.let(::displayText)
        return Required(
            version = version,
            offers = offers,
            unusable = unusable,
            description = description,
            resource = resource,
            extensions = if (version == 2) json.opt("extensions") as? JSONObject else null,
        )
    }

    private const val MAX_DESCRIPTION_CHARS = 500

    /**
     * A site's free text (the terms' description) as one plain line for
     * the payment sheet, or null if nothing is left (#218 R4-M4): control
     * characters and line breaks (which could fake a row of the sheet,
     * "Amount 0.01 USDC", under "The site says"), format characters
     * (bidi overrides and isolates, which reorder what's shown, and
     * zero-width ones) become spaces; runs of whitespace fold into one;
     * capped at [MAX_DESCRIPTION_CHARS] without splitting a surrogate pair.
     */
    internal fun displayText(raw: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val hidden = when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(),
                -> true
                else -> false
            }
            if (hidden || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        var s = sb.toString().trim()
        if (s.length > MAX_DESCRIPTION_CHARS) {
            var end = MAX_DESCRIPTION_CHARS
            if (Character.isHighSurrogate(s[end - 1])) end--
            s = s.substring(0, end).trimEnd()
        }
        return s.ifEmpty { null }
    }

    private sealed interface OfferResult {
        class Ok(val offer: Offer) : OfferResult
        class No(val why: String) : OfferResult
    }

    private fun offer(index: Int, o: JSONObject, version: Int): OfferResult {
        val scheme = o.opt("scheme") as? String
        if (scheme != "exact") return OfferResult.No("scheme ${printable(scheme)} isn't supported")
        val network = o.opt("network") as? String ?: return OfferResult.No("no network")
        val chainId = when (version) {
            2 -> CAIP2_EIP155.matchEntire(network)?.groupValues?.get(1)?.toLongOrNull()
            else -> V1_NETWORKS[network]
        }?.takeIf { it in 1..MAX_CHAIN_ID } ?: return OfferResult.No("network ${printable(network)} isn't supported")
        val extra = o.opt("extra") as? JSONObject
        val method = extra?.opt("assetTransferMethod")
        if (method != null && method != JSONObject.NULL && method != "eip3009") {
            return OfferResult.No("only EIP-3009 transfers are supported, not ${printable(method as? String)}")
        }
        val amountText = o.opt(if (version == 2) "amount" else "maxAmountRequired") as? String
        val amount = amountText?.takeIf { DIGITS.matches(it) }?.let(::BigInteger)
            ?.takeIf { it.signum() > 0 && it <= UINT256_MAX } ?: return OfferResult.No("no valid amount")
        val asset = (o.opt("asset") as? String)?.let(::checksummedOrNull) ?: return OfferResult.No("no valid token address")
        val payTo = (o.opt("payTo") as? String)?.let(::checksummedOrNull) ?: return OfferResult.No("no valid address to pay")
        if (payTo.drop(2).all { it == '0' }) return OfferResult.No("it pays the zero address")
        if (payTo.equals(asset, ignoreCase = true)) return OfferResult.No("it pays the token contract itself")
        val timeout = (o.opt("maxTimeoutSeconds") as? Number)?.toLong()?.takeIf { it > 0 }
            ?: return OfferResult.No("no valid time limit")
        if (timeout < MIN_TIMEOUT_SECONDS) {
            return OfferResult.No("its time limit ($timeout s) is too short to pay in; at least $MIN_TIMEOUT_SECONDS s is needed")
        }
        val name = (extra?.opt("name") as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 }
        val domainVersion = (extra?.opt("version") as? String)?.takeIf { it.isNotEmpty() && it.length <= 32 }
        if (name == null || domainVersion == null) return OfferResult.No("the token's signing domain isn't named")
        return OfferResult.Ok(Offer(index, o, network, chainId, asset, amount, payTo, timeout, name, domainVersion))
    }

    private const val MAX_CHAIN_ID = (1L shl 53) - 1

    private fun printable(s: String?): String =
        s?.filter { it.code in 0x20..0x7e }?.take(40)?.ifEmpty { null }?.let { "“$it”" } ?: "(none)"

    /** EIP-55 form of [address], or null if it isn't one (or is mixed case with a wrong checksum). */
    internal fun checksummedOrNull(address: String): String? {
        if (!ADDRESS.matches(address)) return null
        val digits = address.substring(2)
        val sum = NodeIdentity.checksum(ByteArray(20) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        val mixed = digits.any { it in 'a'..'f' } && digits.any { it in 'A'..'F' }
        return if (mixed && sum != address) null else sum
    }

    private fun decodeJson(value: String): JSONObject? {
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(value)
            } catch (e2: IllegalArgumentException) {
                return null
            }
        }
        return try {
            JSONTokener(String(bytes, Charsets.UTF_8)).nextValue() as? JSONObject
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /** The EIP-3009 `TransferWithAuthorization` fields, as the payment carries them (all text). */
    class Authorization internal constructor(
        val from: String,
        val to: String,
        val value: String,
        val validAfter: String,
        val validBefore: String,
        /** 32 random bytes, `0x` hex: the token refuses a nonce it has seen from [from]. */
        val nonce: String,
    ) {
        internal fun toJson(): JSONObject = JSONObject()
            .put("from", from).put("to", to).put("value", value)
            .put("validAfter", validAfter).put("validBefore", validBefore).put("nonce", nonce)
    }

    /**
     * [from]'s authorization to pay [offer], valid from [nowSeconds] for the
     * server's time limit (at most [MAX_VALIDITY_SECONDS]). [nonce] is 32
     * random bytes. v2 leaves `validAfter` at 0 and v1 backdates it ten
     * minutes, as `@x402/evm`'s clients do.
     */
    fun authorize(version: Int, offer: Offer, from: String, nowSeconds: Long, nonce: ByteArray): Authorization {
        require(nonce.size == 32) { "nonce must be 32 bytes" }
        val from55 = checksummedOrNull(from) ?: throw IllegalArgumentException("from isn't an address")
        val validity = minOf(offer.maxTimeoutSeconds, MAX_VALIDITY_SECONDS)
        return Authorization(
            from = from55,
            to = offer.payTo,
            value = offer.amount.toString(),
            validAfter = if (version == 1) (nowSeconds - 600).coerceAtLeast(0).toString() else "0",
            validBefore = (nowSeconds + validity).toString(),
            nonce = "0x" + nonce.toHex(),
        )
    }

    /** The EIP-712 typed data [authorization] is signed as: the token's own domain on [Offer.chainId]. */
    fun typedData(offer: Offer, authorization: Authorization): JSONObject {
        fun field(name: String, type: String) = JSONObject().put("name", name).put("type", type)
        val types = JSONObject()
            .put(
                "EIP712Domain",
                JSONArray()
                    .put(field("name", "string")).put(field("version", "string"))
                    .put(field("chainId", "uint256")).put(field("verifyingContract", "address")),
            )
            .put(
                "TransferWithAuthorization",
                JSONArray()
                    .put(field("from", "address")).put(field("to", "address")).put(field("value", "uint256"))
                    .put(field("validAfter", "uint256")).put(field("validBefore", "uint256"))
                    .put(field("nonce", "bytes32")),
            )
        val domain = JSONObject()
            .put("name", offer.domainName).put("version", offer.domainVersion)
            .put("chainId", offer.chainId).put("verifyingContract", offer.asset)
        return JSONObject()
            .put("types", types)
            .put("primaryType", "TransferWithAuthorization")
            .put("domain", domain)
            .put("message", authorization.toJson())
    }

    /** The 32 bytes the wallet signs for [authorization]. */
    fun digest(offer: Offer, authorization: Authorization): ByteArray = Eip712.digest(Eip712.parse(typedData(offer, authorization)))

    /** Seconds [authorization] still has at [nowSeconds]. */
    fun runway(authorization: Authorization, nowSeconds: Long): Long = authorization.validBefore.toLong() - nowSeconds

    /**
     * The request header carrying the payment: its name and value (base64
     * of JSON). v2 echoes the server's `resource`, `extensions` and the
     * offer itself (`accepted`) untouched — the server compares them with
     * what it sent.
     */
    fun paymentHeader(required: Required, offer: Offer, authorization: Authorization, signature: String): Pair<String, String> {
        val payload = JSONObject().put("authorization", authorization.toJson()).put("signature", signature)
        val body = JSONObject().put("x402Version", required.version)
        val name = if (required.version == 1) {
            body.put("scheme", "exact").put("network", offer.network).put("payload", payload)
            SIGNATURE_V1
        } else {
            required.resource?.let { body.put("resource", it) }
            body.put("accepted", offer.raw).put("payload", payload)
            required.extensions?.let { body.put("extensions", it) }
            SIGNATURE_V2
        }
        return name to Base64.getEncoder().encodeToString(body.toString().toByteArray(Charsets.UTF_8))
    }
}
