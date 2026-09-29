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
        val invisible = text.codePoints().anyMatch { c -> hides(c) && c != '\n'.code && c != '\t'.code && c != '\r'.code }
        return text.takeIf { !invisible && it.isNotBlank() }
    }

    /**
     * Whether code point [c] can hide or rearrange the text around it on a
     * sheet: a control character (line breaks included), a format character
     * (bidi overrides, zero-width joiners, and the supplementary-plane ones
     * too — tag characters U+E0001/U+E0020–E007F, U+1BCA0–3, U+1D173–A), a
     * line/paragraph separator, or a lone surrogate. Judged per code point,
     * never per UTF-16 `Char`: half of a surrogate pair is typed SURROGATE,
     * which would let every supplementary format character through.
     */
    fun hides(c: Int): Boolean =
        Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() ||
            Character.getType(c) == Character.SURROGATE.toInt() || c == 0x2028 || c == 0x2029

    /** Whether any code point of [s] [hides]. */
    fun anyHides(s: String): Boolean = s.codePoints().anyMatch(::hides)
}
