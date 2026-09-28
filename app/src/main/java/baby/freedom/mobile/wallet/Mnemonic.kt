package baby.freedom.mobile.wallet

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A BIP-39 recovery phrase (#75), ported from iOS's `Mnemonic.swift` and
 * desktop's `identity/derivation.js`: the same English wordlist, checksum
 * and PBKDF2 seed, so a phrase made on one platform opens the same
 * wallet on the others.
 *
 * Creating makes [CREATE_WORD_COUNT] (24) words; importing accepts any
 * standard length, [IMPORT_WORD_COUNTS], with the checksum checked. No
 * BIP-39 passphrase ("25th word") in v1: [seed] always uses the empty
 * one, as desktop and iOS do.
 *
 * The words are the secret. [toString] never prints them, so a stray log
 * line or exception message can't carry a phrase; [ParseException] names
 * a bad word by its position, not its text.
 */
class Mnemonic private constructor(val words: List<String>) {

    /** Why a phrase was refused on import. */
    sealed class Problem {
        /** Not 12, 15, 18, 21 or 24 words. */
        data class WordCount(val count: Int) : Problem()

        /** Word [position] (1-based) isn't on the BIP-39 English list. */
        data class UnknownWord(val position: Int) : Problem()

        /** Every word is valid, but the last one's checksum bits don't match. */
        data object Checksum : Problem()
    }

    class ParseException(val problem: Problem) : IllegalArgumentException(
        when (problem) {
            is Problem.WordCount -> "recovery phrase has ${problem.count} words"
            is Problem.UnknownWord -> "word ${problem.position} isn't a BIP-39 word"
            Problem.Checksum -> "recovery phrase checksum doesn't match"
        },
    )

    /** The words joined by single spaces, the form every platform stores and derives from. */
    fun phrase(): String = words.joinToString(" ")

    /**
     * The 64-byte BIP-39 seed: PBKDF2-HMAC-SHA512 over the NFKD phrase,
     * salt `"mnemonic"`, 2048 rounds. The caller owns the array and
     * should zero it once done ([java.util.Arrays.fill]).
     */
    fun seed(): ByteArray = seed(passphrase = "")

    /** Only the official test vectors use a passphrase ("TREZOR"); the app never does. */
    internal fun seed(passphrase: String): ByteArray {
        val password = nfkd(phrase()).toByteArray(Charsets.UTF_8)
        val salt = nfkd("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        return try {
            pbkdf2HmacSha512(password, salt, SEED_ROUNDS, SEED_BYTES)
        } finally {
            password.fill(0)
        }
    }

    override fun equals(other: Any?): Boolean = other is Mnemonic && other.words == words
    override fun hashCode(): Int = words.hashCode()
    override fun toString(): String = "Mnemonic(${words.size} words)"

    companion object {
        const val CREATE_WORD_COUNT = 24
        val IMPORT_WORD_COUNTS: Set<Int> = setOf(12, 15, 18, 21, 24)

        private const val SEED_ROUNDS = 2048
        private const val SEED_BYTES = 64

        private val index: Map<String, Int> by lazy {
            Bip39English.words.withIndex().associate { (i, w) -> w to i }
        }

        /** A fresh 24-word phrase from 256 bits of [random]. */
        fun generate(random: SecureRandom = SecureRandom()): Mnemonic {
            val entropy = ByteArray(CREATE_WORD_COUNT * 11 * 32 / 33 / 8)
            random.nextBytes(entropy)
            return try {
                fromEntropy(entropy)
            } finally {
                entropy.fill(0)
            }
        }

        /** The phrase for [entropy]: 16, 20, 24, 28 or 32 bytes. */
        fun fromEntropy(entropy: ByteArray): Mnemonic {
            val bits = entropy.size * 8
            require(bits in 128..256 && bits % 32 == 0) { "entropy must be 128-256 bits in steps of 32" }
            val checksum = sha256(entropy)
            val total = bits + bits / 32
            val words = List(total / 11) { i ->
                var idx = 0
                for (b in 0 until 11) {
                    val abs = i * 11 + b
                    val byte = if (abs < bits) entropy[abs / 8] else checksum[(abs - bits) / 8]
                    val bitPos = if (abs < bits) abs else abs - bits
                    idx = (idx shl 1) or ((byte.toInt() shr (7 - bitPos % 8)) and 1)
                }
                Bip39English.words[idx]
            }
            checksum.fill(0)
            return Mnemonic(words)
        }

        /**
         * Parses what the user typed or pasted: case and runs of any
         * whitespace (newlines from a paste, a double space) don't
         * matter. Throws [ParseException] for the wrong word count, a
         * word that isn't on the list, or a bad checksum.
         */
        fun parse(phrase: String): Mnemonic {
            val parsed = nfkd(phrase).lowercase().splitWhere { it.isWhitespace() }.filter { it.isNotEmpty() }
            if (parsed.size !in IMPORT_WORD_COUNTS) throw ParseException(Problem.WordCount(parsed.size))
            val indices = parsed.mapIndexed { i, w ->
                index[w] ?: throw ParseException(Problem.UnknownWord(i + 1))
            }
            val totalBits = parsed.size * 11
            val entropyBits = totalBits * 32 / 33
            val entropy = ByteArray(entropyBits / 8)
            fun bit(n: Int) = (indices[n / 11] shr (10 - n % 11)) and 1
            for (n in 0 until entropyBits) {
                if (bit(n) == 1) entropy[n / 8] = (entropy[n / 8].toInt() or (1 shl (7 - n % 8))).toByte()
            }
            val expected = sha256(entropy)
            entropy.fill(0)
            for (c in 0 until totalBits - entropyBits) {
                val want = (expected[c / 8].toInt() shr (7 - c % 8)) and 1
                if (bit(entropyBits + c) != want) throw ParseException(Problem.Checksum)
            }
            return Mnemonic(parsed)
        }

        /** [parse]'s verdict without the exception: null when the phrase is fine. */
        fun problemWith(phrase: String): Problem? = try {
            parse(phrase)
            null
        } catch (e: ParseException) {
            e.problem
        }

        /** Words typed so far, for the import field's live count. */
        fun wordCount(phrase: String): Int = phrase.splitWhere { it.isWhitespace() }.count { it.isNotEmpty() }

        private fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (ch in this) {
                if (isSeparator(ch)) {
                    out += sb.toString()
                    sb.setLength(0)
                } else sb.append(ch)
            }
            out += sb.toString()
            return out
        }

        private fun nfkd(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD)

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

        /**
         * RFC 8018 PBKDF2 with HMAC-SHA512, over raw bytes. Done by hand
         * rather than through `SecretKeyFactory("PBKDF2WithHmacSHA512")`,
         * whose `char[]` password is turned into bytes differently by
         * different providers — BIP-39 is defined on the UTF-8 bytes.
         */
        internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(password, "HmacSHA512"))
            val hLen = mac.macLength
            val out = ByteArray(length)
            var block = 1
            var offset = 0
            while (offset < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                repeat(rounds - 1) {
                    val next = mac.doFinal(u)
                    u.fill(0)
                    u = next
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                u.fill(0)
                val n = minOf(hLen, length - offset)
                System.arraycopy(t, 0, out, offset, n)
                t.fill(0)
                offset += n
                block++
            }
            return out
        }
    }
}
