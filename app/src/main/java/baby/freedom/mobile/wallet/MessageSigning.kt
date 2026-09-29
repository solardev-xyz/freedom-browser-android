package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex

/**
 * Signing something other than a transaction (#113): an EIP-191
 * `personal_sign` message or an EIP-712 digest ([Eip712.digest]). The
 * signature is desktop's and every wallet's wire form — `0x` ‖ r ‖ s ‖ v
 * with v 27 or 28 — and is checked to recover to the account before it
 * leaves, as a transaction's is ([EthTransaction.sign]).
 */
internal object MessageSigning {
    /** `keccak256("\x19Ethereum Signed Message:\n" ‖ len ‖ message)`. */
    fun personalDigest(message: ByteArray): ByteArray =
        Keccak256.digest("\u0019Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8) + message)

    /** [digest] signed with [privateKey], which must be [address]'s (the caller zeroes it). */
    fun sign(privateKey: ByteArray, digest: ByteArray, address: String): String {
        val signature = "0x" + EthSigning.sign(privateKey, digest).rsv().toHex()
        check(Secp256k1.recover(digest, signature).equals(address, ignoreCase = true)) { "the key isn’t this account’s" }
        return signature
    }

    /**
     * How a `personal_sign` message reads on the sheet: its text when it's
     * UTF-8 with nothing invisible in it but line breaks and tabs (what a
     * sign-in message is), otherwise null — then the sheet shows the bytes
     * in hex, since text that doesn't round-trip can't be what's signed.
     */
    fun readableText(message: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(message)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        var prev = -1
        val invisible = text.codePoints().anyMatch { c ->
            (hides(c, prev) && c != '\n'.code && c != '\t'.code && c != '\r'.code).also { prev = c }
        }
        return text.takeIf { !invisible && it.isNotBlank() }
    }

    /**
     * Whether code point [c], following code point [prev] (-1 at the start),
     * can hide or rearrange the text around it on a sheet: a control
     * character (line breaks included), a format character (bidi overrides,
     * zero-width joiners, and the supplementary-plane ones too — tag
     * characters U+E0001/U+E0020–E007F, U+1BCA0–3, U+1D173–A), a
     * line/paragraph separator, a lone surrogate, or a character that draws
     * nothing although it isn't typed as format: the variation selectors
     * U+FE00–FE0F and U+E0100–E01EF (256 of them, enough to smuggle a byte
     * each after one visible character), the Hangul fillers U+115F, U+1160,
     * U+3164 and U+FFA0, the blank Braille pattern U+2800, the combining
     * grapheme joiner U+034F, the Mongolian variation selectors
     * U+180B–180F and the Khmer inherent vowels U+17B4/U+17B5. The one
     * exception is an emoji's own presentation selector — U+FE0E or U+FE0F
     * straight after a visible character that isn't a selector itself — so
     * "❤️" still reads as text; a run of selectors never does.
     * Judged per code point, never per UTF-16 `Char`: half of a surrogate
     * pair is typed SURROGATE, which would let every supplementary format
     * character through.
     */
    fun hides(c: Int, prev: Int = -1): Boolean = when {
        c == 0xFE0E || c == 0xFE0F -> prev < 0 || hides(prev) || Character.isWhitespace(prev)
        else -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() ||
            Character.getType(c) == Character.SURROGATE.toInt() || c == 0x2028 || c == 0x2029 ||
            c in 0xFE00..0xFE0F || c in 0xE0100..0xE01EF || c in 0x180B..0x180F ||
            c == 0x115F || c == 0x1160 || c == 0x3164 || c == 0xFFA0 || c == 0x2800 ||
            c == 0x034F || c == 0x17B4 || c == 0x17B5
    }

    /** Whether any code point of [s] [hides], each judged after the one before it. */
    fun anyHides(s: String): Boolean {
        var prev = -1
        return s.codePoints().anyMatch { c -> hides(c, prev).also { prev = c } }
    }
}
