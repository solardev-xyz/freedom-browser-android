package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class AdblockManifestTest {
    /** The production feed's payload at version 142, as `GET /feeds/<owner>/<topic>` returned it. */
    private val production: ByteArray =
        javaClass.getResource("/adblock/feed-manifest-v142.json")!!.readBytes()

    private val signer = TestManifestSigner(BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16))

    private fun manifest(version: Long = 5, lists: List<Map<String, Any?>> = listOf(entry())): Map<String, Any?> = mapOf(
        "schema" to 1L,
        "version" to version,
        "generated_at" to "2026-09-18T20:41:06.821Z",
        "engines" to mapOf("adblock_rs" to "0.12.3"),
        "platforms" to mapOf(
            "desktop" to mapOf("lists" to lists),
            "ios" to mapOf("lists" to emptyList<Any>()),
        ),
    )

    private fun entry(
        category: String = "ads",
        listId: String = "easylist",
        sha256: String = "a".repeat(64),
        bytes: Long = 10,
    ): Map<String, Any?> = mapOf(
        "category" to category, "list_id" to listId, "title" to "EasyList",
        "source_url" to "https://easylist.to/easylist/easylist.txt", "license" to "GPLv3+",
        "ref" to "b".repeat(64), "sha256" to sha256, "bytes" to bytes, "rule_count" to 3L,
    )

    private fun payload(m: Map<String, Any?>) = CanonicalJson.stringify(m).toByteArray()

    private fun verify(bytes: ByteArray, applied: Long = 0, republish: Boolean = false, by: String = signer.address) =
        verifyAdblockManifest(bytes, by, applied, republish)

    @Test
    fun `the topic is keccak256 of the topic string`() {
        val hex = Keccak256.digest(AdblockFeed.TOPIC).joinToString("") { "%02x".format(it) }
        assertEquals(AdblockFeed.TOPIC_HEX, hex)
    }

    @Test
    fun `the production manifest verifies against the pinned signer`() {
        val verdict = verify(production, by = AdblockFeed.PRODUCTION_PUBLISHER) as AdblockManifestVerdict.Ok
        assertEquals(142L, verdict.manifest.version)
        assertEquals(listOf("ads", "privacy", "cookies", "annoyances"), verdict.manifest.lists.map { it.category })
        val ads = verdict.manifest.lists[0]
        assertEquals("easylist", ads.listId)
        assertEquals("9df47280eee59e3ddb1fa96507973511e0b68af9471a60c1dcaad2f555a64d71", ads.sha256)
        assertEquals(2158317L, ads.bytes)
    }

    @Test
    fun `a tampered production manifest is rejected`() {
        val text = production.toString(Charsets.UTF_8)
        // Point the ads list at other bytes: the hash the app would check
        // a download against is now the attacker's.
        val tampered = text.replace(
            "9df47280eee59e3ddb1fa96507973511e0b68af9471a60c1dcaad2f555a64d71",
            "0000000000000000000000000000000000000000000000000000000000000000",
        )
        assertTrue(tampered != text)
        assertEquals(
            AdblockManifestVerdict.Rejected("wrong_signer"),
            verify(tampered.toByteArray(), by = AdblockFeed.PRODUCTION_PUBLISHER),
        )
        // Bumping the version to jump ahead of a real one fails the same way.
        assertEquals(
            AdblockManifestVerdict.Rejected("wrong_signer"),
            verify(text.replace("\"version\": 142", "\"version\": 999").toByteArray(), by = AdblockFeed.PRODUCTION_PUBLISHER),
        )
    }

    @Test
    fun `only the pinned signer is trusted`() {
        assertEquals(AdblockManifestVerdict.Rejected("wrong_signer"), verify(production, by = signer.address))
        val ours = payload(signer.sign(manifest()))
        assertEquals(
            AdblockManifestVerdict.Rejected("wrong_signer"),
            verify(ours, by = AdblockFeed.PRODUCTION_PUBLISHER),
        )
        assertTrue(verify(ours) is AdblockManifestVerdict.Ok)
    }

    @Test
    fun `whitespace and key order in the payload don't matter, as the signature is over canonical bytes`() {
        val signed = signer.sign(manifest())
        val pretty = "{\n  \"sig\": \"${signed["sig"]}\",\n" + payload(signed - "sig").toString(Charsets.UTF_8).drop(1)
        assertTrue(verify(pretty.toByteArray()) is AdblockManifestVerdict.Ok)
    }

    @Test
    fun `missing or malformed signatures are rejected`() {
        assertEquals(AdblockManifestVerdict.Rejected("missing_sig"), verify(payload(manifest())))
        assertEquals(
            AdblockManifestVerdict.Rejected("bad_sig"),
            verify(payload(manifest() + ("sig" to "0x1234"))),
        )
    }

    @Test
    fun `versions only move forward`() {
        val v5 = payload(signer.sign(manifest(version = 5)))
        assertTrue(verify(v5, applied = 4) is AdblockManifestVerdict.Ok)
        assertEquals(AdblockManifestVerdict.Rejected("not_newer", 5), verify(v5, applied = 5))
        assertEquals(AdblockManifestVerdict.Rejected("not_newer", 5), verify(v5, applied = 6))
        // A backfill takes the applied version again, never an older one.
        assertTrue(verify(v5, applied = 5, republish = true) is AdblockManifestVerdict.Ok)
        assertEquals(AdblockManifestVerdict.Rejected("not_newer", 5), verify(v5, applied = 6, republish = true))
    }

    @Test
    fun `signed but malformed manifests are rejected`() {
        fun reason(m: Map<String, Any?>) = (verify(payload(signer.sign(m))) as AdblockManifestVerdict.Rejected).reason
        assertEquals("schema_mismatch", reason(manifest() + ("schema" to 2L)))
        assertEquals("bad_version", reason(manifest(version = 0)))
        assertEquals("bad_generated_at", reason(manifest() + ("generated_at" to 1L)))
        assertEquals("bad_desktop_section", reason(manifest() + ("platforms" to mapOf<String, Any?>())))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(listId = "../../evil")))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(listId = "easylist.txt")))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(category = "Ads")))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(sha256 = "A".repeat(64))))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(bytes = 0)))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry(bytes = MAX_ADBLOCK_LIST_BYTES + 1)))))
        assertEquals("bad_list_entry", reason(manifest(lists = listOf(entry() + ("title" to 3L)))))
        assertEquals(
            "duplicate_list_id",
            reason(manifest(lists = listOf(entry(), entry(category = "privacy")))),
        )
        assertEquals(
            "duplicate_category",
            reason(manifest(lists = listOf(entry(), entry(listId = "other")))),
        )
    }

    @Test
    fun `payloads that aren't one unambiguous JSON object are rejected`() {
        for (bad in listOf(
            "", "[]", "null", "{", "{\"a\":1}x", "{\"a\":1,\"a\":2}", "{\"__proto__\":{}}",
            "{\"a\":1.5}", "{\"a\":9007199254740993}", "{\"a\":01}", "{\"a\":\"\u0001\"}", "{'a':1}",
        )) {
            assertEquals(bad, AdblockManifestVerdict.Rejected("not_an_object"), verify(bad.toByteArray()))
        }
        // Not UTF-8.
        assertEquals(
            AdblockManifestVerdict.Rejected("not_an_object"),
            verify(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xff.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte())),
        )
        assertEquals(AdblockManifestVerdict.Rejected("too_large"), verify(ByteArray(MAX_ADBLOCK_MANIFEST_BYTES + 1)))
    }

    @Test
    fun `canonical form matches JavaScript's JSON stringify of the deep-sorted object`() {
        // The expected string is node's `JSON.stringify(sortDeep(JSON.parse(input)))`,
        // with the publisher's own sortDeep.
        val input = """{"b":1,"a":[3,{"z":"\u0000\u001f\b\f\n\r\t\"\\/","y":"\ud800x\udc00 🚀 é"}],"10":true,"2":null,"B":-0,"_":1.0,"é":1e2,"Z":false,"01":"s","4294967295":1,"4294967294":2}"""
        val expected = """{"2":null,"10":true,"4294967294":2,"01":"s","4294967295":1,"B":0,"Z":false,"_":1,"a":[3,{"y":"\ud800x\udc00 🚀 é","z":"\u0000\u001f\b\f\n\r\t\"\\/"}],"b":1,"é":100}"""
        assertEquals(expected, CanonicalJson.stringify(CanonicalJson.parse(input.toByteArray())))
    }

    @Test
    fun `the production manifest's canonical bytes are what the publisher signed`() {
        // sha256 of node's canonicalManifestForSigning over the same payload.
        val root = CanonicalJson.parse(production) as Map<*, *>
        assertEquals(
            "db4c433e7f8de8bc180a8c4b0dc59db118aa248aa84dd94404248d7a356ce719",
            sha256Hex(canonicalManifestBytes(root)),
        )
    }
}
