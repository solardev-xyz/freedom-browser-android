package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WhatwgHost] on the device's own `android.icu` UTS-46 — the path the
 * JVM tests (icu4j) can't run.
 */
@RunWith(AndroidJUnit4::class)
class WhatwgHostParityTest {

    private val harness = WebViewHarness()

    @Before fun setUp() = harness.setUp()

    @After fun tearDown() = harness.tearDown()

    /** Desktop's reference: Node's WHATWG `new URL(...).hostname` (`null` = throws). */
    private val node = listOf(
        "LOCALHOST" to "localhost",
        "127.1" to "127.0.0.1",
        "[0:0:0:0:0:0:0:1]" to "[::1]",
        "bücher.de" to "xn--bcher-kva.de",
        "straße.example" to "xn--strae-oqa.example",
        "例。テスト" to "xn--fsq.xn--zckzah",
        "᠆localhost" to "xn--localhost-uf3c",
        "127.0.0.1%E1%A0%86" to "127.0.0.xn--1-f3j",
        "loca᠏lhost" to "localhost",
        "a⁡b.example" to "ab.example",
        "x𝅳y" to "xy",
        "aᅟb" to "ab",
        "h٠st" to null,
        "a⁦b" to null,
        "a🄀b" to null,
        "אב.example" to "xn--4dbc.example",
        "אa.example" to null,
        "local‍host" to null,
        "क्‍ष" to "xn--11b2ezcw70k",
        "xn--localhost" to null,
        "xn--a" to null,
    )

    @Test
    fun androidIcuMatchesNode() {
        node.forEach { (host, expected) ->
            assertEquals(host, expected, WhatwgHost.parse("https://$host/")?.hostname)
        }
    }

    /**
     * The WebView's Chromium may run an older UTS-46 than Node (it can
     * refuse a host Node accepts, or skip the `xn--`/joiner checks), but
     * a host that parses as loopback here must be loopback there too —
     * that is what lets an `http:` template through.
     */
    @Test
    fun loopbackHereIsLoopbackInChromium() {
        val hosts = node.map { it.first } + listOf("localhost", "127.0.0.1", "[::1]", "0x7f.1", "ｌｏｃａｌｈｏｓｔ")
        harness.load("about:blank")
        val script = "JSON.stringify(" + JSONArray(hosts) + ".map(h => { " +
            "try { return new URL('http://' + h + '/').hostname } catch (e) { return null } }))"
        // evaluateJavascript JSON-encodes the returned string once more.
        val chromium = JSONArray(JSONArray("[" + harness.js(script) + "]").getString(0))
        val loopback = setOf("localhost", "127.0.0.1", "[::1]")
        hosts.forEachIndexed { i, host ->
            val here = WhatwgHost.parse("http://$host/")?.hostname
            if (here in loopback) assertEquals(host, here, chromium.optString(i, null))
        }
    }
}
