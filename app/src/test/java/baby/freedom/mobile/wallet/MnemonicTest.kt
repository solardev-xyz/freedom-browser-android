package baby.freedom.mobile.wallet

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The official BIP-39 English test vectors (trezor/python-mnemonic's
 * `vectors.json`, which the BIP links to: all 24, passphrase "TREZOR"),
 * plus the checks import relies on.
 */
class MnemonicTest {
    private class Vector(val entropy: String, val mnemonic: String, val seed: String)

    private val vectors: List<Vector> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("bip39/vectors.json")!!.bufferedReader().readText()
        val english = JSONObject(text).getJSONArray("english")
        List(english.length()) { i ->
            val v = english.getJSONArray(i)
            Vector(v.getString(0), v.getString(1), v.getString(2))
        }
    }

    @Test
    fun `wordlist is the canonical BIP-39 English list`() {
        val words = Bip39English.words
        assertEquals(2048, words.size)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(words.joinToString("\n", postfix = "\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", digest)
    }

    @Test
    fun `all 24 official vectors - entropy to words`() {
        assertEquals(24, vectors.size)
        for (v in vectors) {
            assertEquals(v.entropy, v.mnemonic, Mnemonic.fromEntropy(v.entropy.hex()).phrase())
        }
    }

    @Test
    fun `all 24 official vectors - words parse, checksum passes, seed matches`() {
        for (v in vectors) {
            val m = Mnemonic.parse(v.mnemonic)
            assertEquals(v.mnemonic, m.phrase())
            assertEquals(v.entropy, v.seed, m.seed("TREZOR").toHex())
        }
    }

    @Test
    fun `app seed uses the empty passphrase`() {
        // Well-known seed for "abandon ×11 about" with no passphrase.
        val m = Mnemonic.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            m.seed().toHex(),
        )
    }

    @Test
    fun `create makes 24 fresh words that import accepts`() {
        val a = Mnemonic.generate()
        val b = Mnemonic.generate(SecureRandom())
        assertEquals(24, a.words.size)
        assertNotEquals(a, b)
        assertEquals(a, Mnemonic.parse(a.phrase()))
    }

    @Test
    fun `import accepts every standard length`() {
        for (bytes in listOf(16, 20, 24, 28, 32)) {
            val m = Mnemonic.fromEntropy(ByteArray(bytes) { 0x5a })
            assertEquals(bytes * 8 * 33 / 32 / 11, m.words.size)
            assertEquals(m, Mnemonic.parse(m.phrase()))
        }
    }

    @Test
    fun `parse ignores case and extra whitespace`() {
        val m = Mnemonic.parse("  Abandon abandon\tabandon abandon abandon abandon\nabandon abandon abandon abandon  abandon ABOUT \n")
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", m.phrase())
    }

    @Test
    fun `bad checksum is refused`() {
        assertEquals(
            Mnemonic.Problem.Checksum,
            Mnemonic.problemWith("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"),
        )
        // Two valid words swapped in a valid 24-word phrase.
        val v = vectors.last().mnemonic.split(" ").toMutableList()
        val t = v[0]; v[0] = v[1]; v[1] = t
        assertEquals(Mnemonic.Problem.Checksum, Mnemonic.problemWith(v.joinToString(" ")))
    }

    @Test
    fun `wrong word count is refused`() {
        for (n in listOf(0, 1, 11, 13, 23, 25)) {
            assertEquals(Mnemonic.Problem.WordCount(n), Mnemonic.problemWith(List(n) { "abandon" }.joinToString(" ")))
        }
    }

    @Test
    fun `unknown word is named by position, never by text`() {
        val phrase = "abandon abandon abandon abandon notaword abandon abandon abandon abandon abandon abandon about"
        try {
            Mnemonic.parse(phrase)
            fail("parsed")
        } catch (e: Mnemonic.ParseException) {
            assertEquals(Mnemonic.Problem.UnknownWord(5), e.problem)
            assertFalse(e.message!!.contains("notaword"))
        }
    }

    @Test
    fun `toString never prints the words`() {
        val m = Mnemonic.parse(vectors[0].mnemonic)
        assertEquals("Mnemonic(12 words)", m.toString())
        assertFalse(m.toString().contains("abandon"))
    }

    @Test
    fun `pbkdf2 matches RFC 6070-style known answer for HMAC-SHA512`() {
        // PBKDF2-HMAC-SHA512("password", "salt", 1, 64), a widely published vector.
        val out = Mnemonic.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            out.toHex(),
        )
        val two = Mnemonic.pbkdf2HmacSha512("password".toByteArray(), "salt".toByteArray(), 2, 64)
        assertEquals(
            "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53cf76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
            two.toHex(),
        )
    }

    @Test
    fun `invisible format characters from a paste are dropped, a zero-width space splits`() {
        val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        val pasted = "\uFEFF" + phrase.replaceFirst(" ", "\u200B").replace("wave ", "wave\u200C ").replace("thank yellow", "thank\u2060 yellow")
        assertEquals(phrase.split(" "), Mnemonic.words(pasted))
        assertEquals(Mnemonic.parse(phrase).phrase(), Mnemonic.parse(pasted).phrase())
    }

    @Test
    fun `a soft hyphen or joiner inside a word doesn't split it`() {
        val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        // Hyphenated web text: soft hyphens, a ZWJ/ZWNJ, a direction mark mid-word.
        val pasted = phrase.replace("sausage", "sau\u00ADsage").replace("useful", "use\u200Dful")
            .replace("winner", "win\u200Cner").replace("yellow", "yel\u200Elow")
        assertEquals(phrase.split(" "), Mnemonic.words(pasted))
        assertEquals(12, Mnemonic.wordCount(pasted))
        assertEquals(Mnemonic.parse(phrase).phrase(), Mnemonic.parse(pasted).phrase())
        assertEquals(Mnemonic.parse(phrase).phrase(), Mnemonic.parse(pasted).phrase())
    }

    @Test
    fun `wordCount counts words as typed`() {
        assertEquals(0, Mnemonic.wordCount("   "))
        assertEquals(3, Mnemonic.wordCount(" a  b\nc "))
        assertTrue(Mnemonic.IMPORT_WORD_COUNTS.contains(12))
        assertNull(Mnemonic.problemWith(vectors[0].mnemonic))
    }
}

internal fun String.hex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
