package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EIP-712 and personal_sign (#113) against ethers v6 — what desktop
 * Freedom verifies the phone's signatures with. Every expected value
 * below was computed by ethers (`TypedDataEncoder.hash`,
 * `Wallet.signTypedData`, `hashMessage`, `Wallet.signMessage`) for the
 * published Hardhat account 0 key, over the exact payloads
 * `TypedDataEncoder.getPayload` produces — what desktop sends.
 */
class Eip712Test {
    private val key = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80".hexToBytes()
    private val address = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

    /** The EIP's own example, as ethers' getPayload writes it. */
    private val mail = """{"types":{"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}],"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}]},"domain":{"name":"Ether Mail","version":"1","chainId":"0x1","verifyingContract":"0xcccccccccccccccccccccccccccccccccccccccc"},"primaryType":"Mail","message":{"from":{"name":"Cow","wallet":"0xcd2a3d9f938e13cd947ec05abc7fe734df8dd826"},"to":{"name":"Bob","wallet":"0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"contents":"Hello, Bob!"}}"""

    /** Arrays of structs, a fixed-size array, strings with non-ASCII, uint256 max, negative and max int64, bytesN, empty bytes, a salt. */
    private val complex = """{"types":{"Item":[{"name":"id","type":"uint256"},{"name":"delta","type":"int64"},{"name":"tag","type":"bytes4"},{"name":"blob","type":"bytes"}],"Order":[{"name":"owner","type":"address"},{"name":"items","type":"Item[]"},{"name":"flags","type":"bool[2]"},{"name":"notes","type":"string[]"},{"name":"nonce","type":"uint8"}],"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"},{"name":"salt","type":"bytes32"}]},"domain":{"name":"Freedom test","version":"2","chainId":"0x64","verifyingContract":"0x9a676e781a523b5d0c0e43731313a708cb607508","salt":"0x1111111111111111111111111111111111111111111111111111111111111111"},"primaryType":"Order","message":{"owner":"0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266","items":[{"id":"115792089237316195423570985008687907853269984665640564039457584007913129639935","delta":"-5","tag":"0xdeadbeef","blob":"0x"},{"id":"7","delta":"9223372036854775807","tag":"0x00000001","blob":"0x0102030405"}],"flags":[true,false],"notes":["a","ünïcødé ✓"],"nonce":"255"}}"""

    private fun digest(json: String) = "0x" + Eip712.digest(Eip712.parse(json)).toHex()

    @Test
    fun `digests and signatures match ethers, desktop's verifier`() {
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", digest(mail))
        assertEquals(
            "0x6ea8bb309a3401225701f3565e32519f94a0ea91a5910ce9229fe488e773584c0390416a2190d9560219dab757ecca2029e63fa9d1c2aebf676cc25b9f03126a1b",
            MessageSigning.sign(key.copyOf(), Eip712.digest(Eip712.parse(mail)), address),
        )
        assertEquals("0x9219795b39538994abb08c4e68cbfcd6fa5b3f49105b33cd6284ec968c66e609", digest(complex))
        assertEquals(
            "0x71efebb21effba274e4353a9f9a9b4e5b7abe3ed860cd781533ba71071f8c4c31e72c900488b681fb3eb9a0ee09094ff326e08487e37f2c149c279b6308b87b11b",
            MessageSigning.sign(key.copyOf(), Eip712.digest(Eip712.parse(complex)), address),
        )
    }

    @Test
    fun `encodeType puts the primary type first and the rest by name`() {
        val td = Eip712.parse(complex)
        assertEquals("Order(address owner,Item[] items,bool[2] flags,string[] notes,uint8 nonce)Item(uint256 id,int64 delta,bytes4 tag,bytes blob)", Eip712.encodeType("Order", td.types))
        assertEquals("Mail(Person from,Person to,string contents)Person(string name,address wallet)", Eip712.encodeType("Mail", Eip712.parse(mail).types))
    }

    @Test
    fun `a payload without EIP712Domain in its types gets the domain's fields in the EIP's order`() {
        val o = JSONObject(mail)
        o.getJSONObject("types").remove("EIP712Domain")
        // Keys in another order, numbers as a JSON number and a decimal string: the same digest.
        o.put("domain", JSONObject().put("verifyingContract", "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC").put("chainId", 1).put("version", "1").put("name", "Ether Mail"))
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", digest(o.toString()))
        o.getJSONObject("domain").put("chainId", "1")
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", digest(o.toString()))
        assertEquals(1L, Eip712.chainId(Eip712.parse(o.toString())))
        // The object itself (not its JSON string) works too.
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", "0x" + Eip712.digest(Eip712.parse(o)).toHex())
    }

    @Test
    fun `anything not exactly what the types say is refused, never guessed`() {
        fun refused(edit: (JSONObject) -> Unit) {
            val o = JSONObject(complex)
            edit(o)
            assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.digest(Eip712.parse(o.toString())) }
        }
        val item = { o: JSONObject -> o.getJSONObject("message").getJSONArray("items").getJSONObject(0) }
        refused { it.getJSONObject("message").remove("nonce") } // a missing field
        refused { it.getJSONObject("message").put("nonce", "256") } // uint8 overflow
        refused { it.getJSONObject("message").put("nonce", "-1") }
        refused { item(it).put("delta", "9223372036854775808") } // int64 overflow
        refused { item(it).put("tag", "0xdeadbe") } // bytes4 of 3 bytes
        refused { item(it).put("blob", "0x0") } // odd hex
        refused { it.getJSONObject("message").put("owner", "0x1234") }
        refused { it.getJSONObject("message").put("flags", JSONArray().put(true)) } // bool[2] of one
        refused { it.getJSONObject("message").put("flags", JSONArray().put(1).put(0)) } // bools aren't numbers
        refused { it.getJSONObject("message").put("notes", "a") } // not a list
        refused { it.getJSONObject("message").put("nonce", 1.5) }
        refused { it.getJSONObject("message").put("nonce", 1e300) } // a JSON number past what JS holds exactly
        refused { it.getJSONObject("types").getJSONArray("Order").put(JSONObject().put("name", "x").put("type", "Missing")) }
        refused { it.getJSONObject("types").getJSONArray("Order").put(JSONObject().put("name", "y").put("type", "uint7")) }
        refused { it.put("primaryType", "Nope") }
        assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.parse("not json") }
        assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.parse("[" .repeat(100_000)) }
        assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.parse(null) }
    }

    @Test
    fun `the sheet's lines show every signed field, nested ones indented, and nothing else`() {
        val o = JSONObject(complex)
        o.getJSONObject("message").put("unsigned", "not shown")
        val (domain, message) = Eip712.lines(Eip712.parse(o.toString()))
        assertEquals(
            listOf(
                Eip712.Line("name", "Freedom test", 0),
                Eip712.Line("version", "2", 0),
                Eip712.Line("chainId", "100", 0),
                Eip712.Line("verifyingContract", "0x9A676e781A523b5d0C0e43731313A708CB607508", 0),
                Eip712.Line("salt", "0x" + "11".repeat(32), 0),
            ),
            domain,
        )
        assertEquals(
            listOf(
                Eip712.Line("owner", address, 0),
                Eip712.Line("items", "2 items", 0),
                Eip712.Line("[0]", "Item", 1),
                Eip712.Line("id", "115792089237316195423570985008687907853269984665640564039457584007913129639935", 2),
                Eip712.Line("delta", "-5", 2),
                Eip712.Line("tag", "0xdeadbeef", 2),
                Eip712.Line("blob", "0x", 2),
                Eip712.Line("[1]", "Item", 1),
                Eip712.Line("id", "7", 2),
                Eip712.Line("delta", "9223372036854775807", 2),
                Eip712.Line("tag", "0x00000001", 2),
                Eip712.Line("blob", "0x0102030405", 2),
                Eip712.Line("flags", "2 items", 0),
                Eip712.Line("[0]", "true", 1),
                Eip712.Line("[1]", "false", 1),
                Eip712.Line("notes", "2 items", 0),
                Eip712.Line("[0]", "a", 1),
                Eip712.Line("[1]", "ünïcødé ✓", 1),
                Eip712.Line("nonce", "255", 0),
            ),
            message,
        )
    }

    @Test
    fun `personal_sign matches ethers for text and for bytes`() {
        val text = "freedom openlv android harness".toByteArray()
        assertEquals("0x740df87a68c5f40631aad3bbab1756bb854137557c0c052eed84b741bd1c5b8e", "0x" + MessageSigning.personalDigest(text).toHex())
        val sig = MessageSigning.sign(key.copyOf(), MessageSigning.personalDigest(text), address)
        assertEquals(
            "0x09036ce97346b4f7fe9bb7970503cf8329571b6e638c95f716536c594931ebd34543e67d878ab12cc9e3651608c9e625b741c0dc26134a39725148a836f6f6331b",
            sig,
        )
        assertEquals(address.lowercase(), Secp256k1.recoverPersonalSign(text, sig)?.lowercase())
        val bin = byteArrayOf(0xff.toByte(), 0x00, 0x10)
        assertEquals(
            "0x5fc88852a6252648db7b5c51012d1cc4cba09b334074181848652b6a48e742a978aa3b0a50976aaef7b0f21d31c31e66e37ded7aa646da26323c7f7f31e09ef21c",
            MessageSigning.sign(key.copyOf(), MessageSigning.personalDigest(bin), address),
        )
    }

    @Test
    fun `a key that isn't the account's signs nothing`() {
        assertThrows(IllegalStateException::class.java) {
            MessageSigning.sign(key.copyOf(), MessageSigning.personalDigest(byteArrayOf(1)), "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
        }
    }

    @Test
    fun `a message reads as text only when it's clean UTF-8`() {
        assertEquals("Sign in to example.com\nNonce: 1", MessageSigning.readableText("Sign in to example.com\nNonce: 1".toByteArray()))
        assertNull(MessageSigning.readableText(byteArrayOf(0xff.toByte(), 0x00, 0x10))) // not UTF-8
        assertNull(MessageSigning.readableText("a\u0000b".toByteArray())) // a NUL
        assertNull(MessageSigning.readableText("pay‮gnp.exe".toByteArray())) // a bidi override hides what's there
        assertNull(MessageSigning.readableText("   ".toByteArray()))
    }

    /**
     * R1-F1's payload: 1000 chained types `T0(T1[] f)`, `T1(T2[] f)`…`T999(uint8 f)` and
     * 20000 `T1` instances. With `encodeType` redone per instance this took
     * seconds; memoized it's one hash per type. Its 40000-odd lines are
     * refused rather than laid out.
     */
    private fun fanOut(): String {
        val types = JSONObject()
        for (i in 0 until 999) types.put("T$i", JSONArray().put(JSONObject().put("name", "f").put("type", "T${i + 1}[]")))
        types.put("T999", JSONArray().put(JSONObject().put("name", "f").put("type", "uint8")))
        // Each T1 is `{"f":[]}`: cheap in JSON, but its encodeType still walks T2…T999.
        val items = JSONArray()
        repeat(20_000) { items.put(JSONObject().put("f", JSONArray())) }
        return JSONObject()
            .put("types", types)
            .put("domain", JSONObject().put("name", "x"))
            .put("primaryType", "T0")
            .put("message", JSONObject().put("f", items))
            .toString()
    }

    @Test
    fun `a payload built to fan out digests quickly and is refused for the sheet`() {
        val json = fanOut()
        assertTrue(json.length <= Eip712.MAX_JSON)
        val td = Eip712.parse(json)
        val started = System.nanoTime()
        Eip712.digest(td)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("digest took $ms ms", ms < 2_000)
        assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.lines(td) }
    }

    @Test
    fun `memoized type hashes don't change a digest`() {
        // Same type (Person) hashed twice, nested; and the EIP's own digest.
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", digest(mail))
        assertEquals("0x9219795b39538994abb08c4e68cbfcd6fa5b3f49105b33cd6284ec968c66e609", digest(complex))
    }

    @Test
    fun `a string field can't hide or reorder what's around it on the sheet`() {
        val payload = JSONObject(mail)
        payload.getJSONObject("message").put("contents", "pay‮gnp.exe\n\n\nend​ ")
        val (_, message) = Eip712.lines(Eip712.parse(payload.toString()))
        assertEquals("pay\\u202Egnp.exe\\n\\n\\nend\\u200B\\u2028", message.single { it.label == "contents" }.value)
        // Plain text, non-ASCII included, is shown as is.
        assertEquals("ünïcødé ✓", Eip712.visible("ünïcødé ✓"))
    }

    @Test
    fun `supplementary-plane format characters are caught per code point`() {
        // Tag characters (U+E0001, U+E0041), a shorthand format control (U+1BCA0),
        // a musical-notation format control (U+1D173): each two UTF-16 Chars.
        val tags = "ok" + String(Character.toChars(0xE0001)) + String(Character.toChars(0xE0041)) + "!"
        assertEquals("ok\\u{E0001}\\u{E0041}!", Eip712.visible(tags))
        assertEquals("a\\u{1BCA0}b\\u{1D173}", Eip712.visible("a" + String(Character.toChars(0x1BCA0)) + "b" + String(Character.toChars(0x1D173))))
        assertNull(MessageSigning.readableText(tags.toByteArray()))
        // A lone surrogate (JSON "\ud800") is escaped too; a real emoji is not.
        assertEquals("x\\uD800y", Eip712.visible("x\uD800y"))
        assertEquals("gm 👋", Eip712.visible("gm 👋"))
        assertEquals("gm 👋", MessageSigning.readableText("gm 👋".toByteArray()))
    }

    @Test
    fun `one huge string field is refused rather than laid out on the sheet`() {
        val payload = JSONObject(mail)
        payload.getJSONObject("message").put("contents", "x".repeat(Eip712.MAX_SHOWN + 1))
        val td = Eip712.parse(payload.toString())
        assertThrows(Eip712.InvalidTypedData::class.java) { Eip712.lines(td) }
        payload.getJSONObject("message").put("contents", "x".repeat(1000))
        Eip712.lines(Eip712.parse(payload.toString()))
    }

    @Test
    fun `invisible characters that aren't typed as format are caught too`() {
        val vs = { cp: Int -> String(Character.toChars(cp)) }
        // Bytes smuggled as variation selectors after one visible character (0xFE00-0xFE0F, 0xE0100-0xE01EF).
        val smuggled = "hi" + vs(0xFE01) + vs(0xE0100) + vs(0xE01EF)
        assertNull(MessageSigning.readableText(smuggled.toByteArray()))
        assertEquals("hi\\uFE01\\u{E0100}\\u{E01EF}", Eip712.visible(smuggled))
        // Hangul fillers, the blank Braille pattern, the grapheme joiner, Mongolian FVS, Khmer inherent vowels.
        for (cp in listOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x034F, 0x180B, 0x17B4, 0x17B5)) {
            assertNull("U+%04X".format(cp), MessageSigning.readableText("a${vs(cp)}b".toByteArray()))
            assertTrue("U+%04X".format(cp), MessageSigning.anyHides("a${vs(cp)}b"))
        }
        // An emoji's own presentation selector is still text; a run of them, or one on its own, isn't.
        assertEquals("I ❤️ it", MessageSigning.readableText("I ❤️ it".toByteArray()))
        assertEquals("I ❤️ it", Eip712.visible("I ❤️ it"))
        assertNull(MessageSigning.readableText("I ❤\uFE0F\uFE0F\uFE0E it".toByteArray()))
        assertNull(MessageSigning.readableText("\uFE0Fstart".toByteArray()))
        assertNull(MessageSigning.readableText("a \uFE0F b".toByteArray()))
    }

    @Test
    fun `deeply nested typed data keeps every level's line`() {
        // Order{string note; N0 n}, N0{N1 n} … N29{address spender; uint256 amount}: the R3-F1 payload.
        val types = JSONObject()
        types.put("EIP712Domain", JSONArray().put(JSONObject().put("name", "name").put("type", "string")))
        types.put("Order", JSONArray().put(JSONObject().put("name", "note").put("type", "string")).put(JSONObject().put("name", "n").put("type", "N0")))
        for (i in 0 until 29) types.put("N$i", JSONArray().put(JSONObject().put("name", "n").put("type", "N${i + 1}")))
        types.put("N29", JSONArray().put(JSONObject().put("name", "spender").put("type", "address")).put(JSONObject().put("name", "amount").put("type", "uint256")))
        var inner = JSONObject().put("spender", "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("amount", "1000")
        for (i in 29 downTo 1) inner = JSONObject().put("n", inner)
        val payload = JSONObject().put("types", types).put("domain", JSONObject().put("name", "x")).put("primaryType", "Order")
            .put("message", JSONObject().put("note", "hello").put("n", inner))
        val (_, message) = Eip712.lines(Eip712.parse(payload.toString()))
        val spender = message.single { it.label == "spender" }
        assertEquals(30, spender.depth)
        assertEquals("0x" + "b".repeat(40), spender.value.lowercase())
        assertEquals("1000", message.single { it.label == "amount" }.value)
    }
}
