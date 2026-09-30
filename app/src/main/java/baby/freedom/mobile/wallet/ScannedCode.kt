package baby.freedom.mobile.wallet

import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.R
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder

/**
 * What a scanned (or pasted) QR code holds, as far as the wallet is
 * concerned (#106): a bare address, an EIP-681 payment request, a
 * pairing code for desktop Freedom (OpenLV, #113), a Safe owner's request
 * to co-sign (#141), or none of these.
 *
 * Addresses always come out EIP-55 checksummed. A mixed-case address
 * whose checksum doesn't hold is refused rather than "fixed": the case
 * is there to catch exactly the misread or mistyped character that
 * would send funds somewhere else.
 */
sealed class ScannedCode {
    data class Address(val address: String) : ScannedCode()

    /**
     * An EIP-681 `ethereum:` request to pay [recipient]. [token] is the
     * ERC-20 contract for a `…/transfer` request, null for the chain's
     * native currency. [amount] is in the asset's base units (wei for
     * the native currency), null when the request names none. [chainId]
     * is null when it names no chain.
     */
    data class Payment(
        val recipient: String,
        val chainId: Long?,
        val token: String?,
        val amount: BigInteger?,
    ) : ScannedCode()

    /** An OpenLV session URI (`openlv://…`), taken out of a bridge link if it came in one. */
    data class Pairing(val uri: String) : ScannedCode() {
        // The URI is a live session secret for as long as it's open: never in a log line.
        override fun toString() = "Pairing(…)"
    }

    /**
     * Another Safe owner's request to co-sign (#141): the typed data
     * [SafeProtocol.parseRequest] takes, checked to be one as it was read.
     */
    data class SafeRequest(val json: String) : ScannedCode()

    /** Nothing the wallet can use; [reason] says why, in a sentence for the user. */
    data class Unrecognized(val reason: String) : ScannedCode()

    companion object {
        private val HEX_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        private val NOT_A_WALLET_CODE: String get() = Strings.get(R.string.wallet_scan_not_a_wallet_code)

        /** Reads [raw], the text a QR code decoded to or the user pasted. */
        fun parse(raw: String): ScannedCode {
            val text = raw.trim()
            if (text.isEmpty()) return Unrecognized(NOT_A_WALLET_CODE)
            openLvUri(text)?.let { return Pairing(it) }
            if (text.startsWith("{")) {
                return try {
                    SafeProtocol.parseRequest(text)
                    SafeRequest(text)
                } catch (e: Eip712.Invalid) {
                    Unrecognized(e.message ?: NOT_A_WALLET_CODE)
                }
            }
            if (text.startsWith("ethereum:", ignoreCase = true)) return parseEip681(text.substring("ethereum:".length))
            if (HEX_ADDRESS.matches(text)) {
                return checkedAddress(text)?.let { Address(it) } ?: badChecksum()
            }
            return Unrecognized(NOT_A_WALLET_CODE)
        }

        /**
         * [address] (`0x` + 40 hex digits) EIP-55 checksummed, or null
         * when it's mixed case and the case doesn't match its checksum.
         * All-lower and all-upper hex carry no checksum and are taken as is.
         */
        internal fun checkedAddress(address: String): String? {
            if (!HEX_ADDRESS.matches(address)) return null
            val hex = address.substring(2)
            val bytes = ByteArray(20) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            val checksummed = NodeIdentity.checksum(bytes)
            val mixed = hex.any { it in 'a'..'f' } && hex.any { it in 'A'..'F' }
            return if (!mixed || checksummed.substring(2) == hex) checksummed else null
        }

        private fun badChecksum() = Unrecognized(Strings.get(R.string.wallet_scan_bad_checksum))

        /**
         * The `openlv://` URI in [text]: [text] itself, or the fragment
         * of a bridge link (`https://…/#openlv://…`), percent-decoded
         * once — iOS's `extractOpenLVURI`.
         */
        private fun openLvUri(text: String): String? {
            if (text.startsWith("openlv://", ignoreCase = true)) return text
            val hash = text.indexOf('#')
            if (hash < 0) return null
            val fragment = text.substring(hash + 1)
            val decoded = runCatching { URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(fragment)
            return decoded.takeIf { it.startsWith("openlv://", ignoreCase = true) }
        }

        /**
         * EIP-681: `[pay-]target[@chain_id][/function][?params]`, [rest]
         * being everything after `ethereum:`. Only what a wallet can pay
         * is taken: the native currency to an address, or an ERC-20
         * `transfer(address,uint256)`. Any other contract call, a name
         * in place of an address, or an ambiguous amount is refused.
         */
        private fun parseEip681(rest: String): ScannedCode {
            val body = if (rest.startsWith("pay-", ignoreCase = true)) rest.substring(4) else rest
            val query = body.substringAfter('?', "")
            val path = body.substringBefore('?')
            val function = path.substringAfter('/', "").takeIf { '/' in path }
            val targetAndChain = path.substringBefore('/')
            val target = targetAndChain.substringBefore('@')
            val chainText = targetAndChain.substringAfter('@', "").takeIf { '@' in targetAndChain }

            if (!HEX_ADDRESS.matches(target)) {
                return Unrecognized(
                    if (target.contains('.')) {
                        Strings.get(R.string.wallet_scan_name_not_address, shortened(target))
                    } else {
                        Strings.get(R.string.wallet_scan_no_valid_address)
                    },
                )
            }
            val targetAddress = checkedAddress(target) ?: return badChecksum()

            val chainId = if (chainText == null) {
                null
            } else {
                chainText.takeIf { it.isNotEmpty() && it.length <= 18 && it.all { c -> c in '0'..'9' } }
                    ?.toLong()?.takeIf { it > 0 }
                    ?: return Unrecognized(Strings.get(R.string.wallet_scan_bad_chain))
            }

            val params = mutableMapOf<String, String>()
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    // Two of the same key (value=1&value=1000) could be read
                    // either way by different wallets: refuse, don't pick one.
                    if (params.put(key, value) != null) {
                        return Unrecognized(Strings.get(R.string.wallet_scan_duplicate_param, shortened(key)))
                    }
                }
            }

            return when (function) {
                null -> {
                    val amount = params["value"]?.let {
                        eip681Number(it) ?: return badAmount()
                    }
                    Payment(targetAddress, chainId, token = null, amount = amount)
                }
                "transfer" -> {
                    // `value` would move the chain's native currency along with the token.
                    val value = params["value"]?.let { eip681Number(it) ?: return badAmount() }
                    if (value != null && value.signum() != 0) {
                        return Unrecognized(Strings.get(R.string.wallet_scan_token_with_native_value))
                    }
                    val to = params["address"]
                        ?: return Unrecognized(Strings.get(R.string.wallet_scan_token_no_recipient))
                    if (!HEX_ADDRESS.matches(to)) {
                        return Unrecognized(Strings.get(R.string.wallet_scan_token_bad_recipient))
                    }
                    val recipient = checkedAddress(to) ?: return badChecksum()
                    val amount = params["uint256"]?.let { eip681Number(it) ?: return badAmount() }
                    Payment(recipient, chainId, token = targetAddress, amount = amount)
                }
                else -> Unrecognized(Strings.get(R.string.wallet_scan_contract_call, shortened(function)))
            }
        }

        /**
         * Untrusted [text] made safe to quote inside a message: every
         * control, format (bidi overrides and isolates, zero-width
         * characters) or line/paragraph separator shown as U+FFFD, so the
         * scanned code can't reorder or hide part of the app's own
         * sentence; then cut to 40 characters without splitting a
         * surrogate pair.
         */
        internal fun shortened(text: String): String {
            val out = StringBuilder()
            var count = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (count == 39 && i < text.length) return out.append('…').toString()
                when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.SURROGATE,
                    -> out.append('�')
                    else -> out.appendCodePoint(cp)
                }
                count++
            }
            return out.toString()
        }

        private fun badAmount() = Unrecognized(Strings.get(R.string.wallet_scan_bad_amount))

        private val NUMBER = Regex("^([0-9]+)(?:\\.([0-9]+))?(?:[eE]\\+?([0-9]{1,3}))?$")
        private val UINT256_MAX: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

        /**
         * An EIP-681 number (`2014000000000000000`, `2.014e18`) as a
         * whole number of base units, or null when it isn't one — a
         * fraction of a base unit, negative, or beyond a `uint256`.
         */
        internal fun eip681Number(text: String): BigInteger? {
            val match = NUMBER.matchEntire(text) ?: return null
            val (whole, fraction, exponent) = match.destructured
            // Bounded before any arithmetic: a uint256 has at most 78 digits.
            val shift = exponent.ifEmpty { "0" }.toInt()
            if (shift > 78 || whole.trimStart('0').length + shift > 78) return null
            val digits = if (fraction.isEmpty()) whole else "$whole.$fraction"
            val value = BigDecimal(digits).movePointRight(shift).stripTrailingZeros()
            if (value.scale() > 0) return null
            val integer = value.toBigIntegerExact()
            return integer.takeIf { it <= UINT256_MAX }
        }
    }
}
