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
 * Signing what a site (#110) or desktop Freedom over OpenLV (#113) asks
 * the wallet to sign: EIP-191 `personal_sign` and EIP-712
 * `eth_signTypedData_v4` ([Eip712.digest]), with the account's key
 * derived for the one signature and zeroed after — as the send flow does
 * ([WalletSender.vaultSigner]). Every signature is checked to recover to
 * the account before it leaves.
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
        val scan = Scan()
        val invisible = text.codePoints().anyMatch { c ->
            scan.hides(c) && c != '\n'.code && c != '\t'.code && c != '\r'.code
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
     * U+180B–180F and the Khmer inherent vowels U+17B4/U+17B5 — and every
     * Default_Ignorable_Code_Point block whose unassigned slots draw nothing
     * either: U+2060–206F, U+FFF0–FFF8 and the whole of U+E0000–E0FFF
     * (tags and supplementary selectors included). An unassigned code point
     * there isn't typed as format (it's Cn), but text shaping treats it as
     * ignorable and draws no glyph, so one between two combining-mark runs
     * would split the count while the marks still stack on one letter. The one
     * exception is an emoji's own presentation selector — U+FE0E or U+FE0F
     * straight after a visible character that isn't a selector itself — so
     * "❤️" still reads as text; a run of selectors never does.
     * Judged per code point, never per UTF-16 `Char`: half of a surrogate
     * pair is typed SURROGATE, which would let every supplementary format
     * character through.
     *
     * [marksBefore] is how many combining marks (non-spacing or enclosing)
     * stand straight before [c]. A mark past the [MAX_STACKED_MARKS]th in a
     * row hides: marks draw over their base without advancing, and a long
     * enough stack ("Zalgo" text) paints ink far above and below its line —
     * Compose doesn't clip glyphs to the line box — over the rows around it
     * on the sheet, where it can blot out or overprint a spender or amount.
     * Real text stacks two or three (Vietnamese, Hebrew points, Arabic
     * harakat, a keycap emoji's selector and enclosing square).
     */
    fun hides(c: Int, prev: Int = -1, marksBefore: Int = 0): Boolean = when {
        isMark(c) && marksBefore >= MAX_STACKED_MARKS -> true
        c == 0xFE0E || c == 0xFE0F -> prev < 0 || hides(prev) || Character.isWhitespace(prev)
        else -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt() ||
            Character.getType(c) == Character.SURROGATE.toInt() || c == 0x2028 || c == 0x2029 ||
            c in 0xFE00..0xFE0F || c in 0x180B..0x180F ||
            c in 0x2060..0x206F || c in 0xFFF0..0xFFF8 || c in 0xE0000..0xE0FFF ||
            c == 0x115F || c == 0x1160 || c == 0x3164 || c == 0xFFA0 || c == 0x2800 ||
            c == 0x034F || c == 0x17B4 || c == 0x17B5
    }

    /** Combining marks allowed in a row on one base before the next one [hides]. */
    const val MAX_STACKED_MARKS = 3

    /** A non-spacing or enclosing combining mark: drawn over the character before it, not beside it. */
    fun isMark(c: Int): Boolean = Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * [hides] over a string, a code point at a time in order: carries the
     * previous code point and the length of the combining-mark run it ends.
     */
    class Scan {
        private var prev = -1
        private var marks = 0

        fun hides(c: Int): Boolean {
            val hidden = hides(c, prev, marks)
            marks = if (isMark(c)) marks + 1 else 0
            prev = c
            return hidden
        }
    }

    /** Whether any code point of [s] [hides], each judged after the ones before it. */
    fun anyHides(s: String): Boolean {
        val scan = Scan()
        return s.codePoints().anyMatch { scan.hides(it) }
    }
}
