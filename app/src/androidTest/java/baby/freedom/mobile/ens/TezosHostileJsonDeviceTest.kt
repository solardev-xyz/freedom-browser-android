package baby.freedom.mobile.ens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deeply nested JSON in a `.tez` lookup, on the platform's own
 * `org.json` — which, unlike the `org.json:json` the JVM unit tests run
 * against, has no nesting limit: its parser recurses once per level and
 * a few thousand levels overflow the stack. A `StackOverflowError` isn't
 * an `Exception`, so it has to be caught by name, or it goes straight
 * through [TezosDomainsResolver] and the tab's lookup (which doesn't
 * catch) and takes the app down.
 */
@RunWith(AndroidJUnit4::class)
class TezosHostileJsonDeviceTest {

    private val deep = "[".repeat(5_000) + "]".repeat(5_000)

    @Before
    fun setUp() {
        Strings.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private val proxyScript =
        """{"code":[{"prim":"storage","args":[{"prim":"pair","args":[
            {"prim":"address","annots":["%contract"]},{"prim":"address","annots":["%owner"]}]}]}],
          "storage":{"prim":"Pair","args":[{"string":"KT1GBZmSxmnKJXGMdMLbugPfLyUPmuLSMwKS"},
            {"string":"KT1BzeXvLtPR83aj5FHemXmia6DmdXkeV3Uk"}]}}"""

    private val registryScript =
        """{"code":[{"prim":"storage","args":[{"prim":"pair","args":[
            {"prim":"big_map","args":[{"prim":"bytes"},{"prim":"pair","args":[
              {"prim":"map","annots":["%data"]},{"prim":"option","annots":["%expiry_key"]}]}],
             "annots":["%records"]},
            {"prim":"big_map","annots":["%expiry_map"]}]}]}],
          "storage":{"prim":"Pair","args":[{"int":"1264"},{"int":"1262"}]}}"""

    /** A record whose `web:content_url` bytes are [json] — the name owner's to write. */
    private fun record(json: String): String {
        val data = JSONArray().put(
            JSONObject().put("prim", "Elt").put(
                "args",
                JSONArray().put(JSONObject().put("string", "web:content_url"))
                    .put(JSONObject().put("bytes", json.toByteArray(Charsets.UTF_8).toHex())),
            ),
        )
        return JSONObject().put("prim", "Pair").put("args", JSONArray().put(data).put(JSONObject().put("prim", "None")))
            .toString()
    }

    /** Three agreeing providers; [override] answers instead where it returns non-null. */
    private class Rpc(val record: String, val override: (String) -> String? = { null }) : EnsHttp {
        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            override(url)?.let { return EnsHttp.Reply(200, it) }
            val json = when {
                url.endsWith("/chains/main/chain_id") -> "\"NetXdQprcVkpaWU\""
                url.endsWith("/blocks/head/header") -> """{"level":1000}"""
                Regex("/blocks/\\d+/hash$").containsMatchIn(url) -> "\"BLockHashSharedByProviders\""
                url.contains("KT1F7JKNqwaoLzRsMio1MQC7zv3jG9dHcDdJ/script/normalized") -> PROXY
                url.contains("KT1GBZmSxmnKJXGMdMLbugPfLyUPmuLSMwKS/script/normalized") -> REGISTRY
                url.contains("/big_maps/1264/") -> record
                else -> error("unexpected $url")
            }
            return EnsHttp.Reply(200, json)
        }

        companion object {
            lateinit var PROXY: String
            lateinit var REGISTRY: String
        }
    }

    private fun resolve(http: EnsHttp): EnsResult = runBlocking {
        Rpc.PROXY = proxyScript
        Rpc.REGISTRY = registryScript
        TezosDomainsResolver(listOf("https://a.test", "https://b.test", "https://c.test"), http).resolve("deep.tez")
    }

    @Test
    fun aDeeplyNestedWebsiteRecordIsUnsupportedNotACrash() {
        val result = resolve(Rpc(record(deep)))

        require(result is EnsResult.Unsupported) { "got $result" }
        // Every provider read the same bytes: the owner's record, agreed on.
        assertEquals(true, result.trust.verified)
    }

    @Test
    fun providersAnsweringDeeplyNestedJsonAreAnErrorNotACrash() {
        val result = resolve(Rpc(record("\"ipfs://bafy\"")) { url -> deep.takeIf { url.endsWith("/chain_id") } })

        require(result is EnsResult.Error) { "got $result" }
        assertEquals("PROVIDER_ERROR", result.reason)
    }
}
