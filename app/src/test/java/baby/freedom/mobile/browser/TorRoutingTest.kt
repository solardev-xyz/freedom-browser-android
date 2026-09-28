package baby.freedom.mobile.browser

import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URL

class TorRoutingTest {
    private val onion = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"

    @Test
    fun `onion hosts are recognised the way the Tor side recognises them`() {
        assertTrue(isOnionHost(onion))
        assertTrue(isOnionHost("www.$onion"))
        assertTrue(isOnionHost("EXAMPLE.ONION"))
        assertTrue(isOnionHost("example.onion."))
        assertFalse(isOnionHost("onion"))
        assertFalse(isOnionHost(".onion"))
        assertFalse(isOnionHost("a..onion"))
        assertFalse(isOnionHost("example.onion.com"))
        assertFalse(isOnionHost("notonion"))
        assertFalse(isOnionHost("example.com"))
        assertFalse(isOnionHost("127.0.0.1"))
        assertFalse(isOnionHost(null))
    }

    @Test
    fun `onion is routed only with support, the setting on, and a listening client`() {
        val listening = TorInfo(status = TorStatus.Running, socksPort = 40123)
        assertEquals(40123, TorRouting.desiredPort(true, true, listening))
        // Bootstrapping already listens (a connect waits for the bootstrap).
        assertEquals(40123, TorRouting.desiredPort(true, true, listening.copy(status = TorStatus.Starting)))
        // Every other combination refuses.
        for (supported in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                for (status in TorStatus.entries) {
                    for (port in listOf(0, 40123, 70000)) {
                        val info = TorInfo(status = status, socksPort = port)
                        val routed = supported && enabled && port == 40123 &&
                            (status == TorStatus.Running || status == TorStatus.Starting)
                        assertEquals(
                            "supported=$supported enabled=$enabled $status port=$port",
                            if (routed) 40123 else 0,
                            TorRouting.desiredPort(supported, enabled, info),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the proxy applies only to onion hosts, over socks5`() {
        val config = TorRouting.proxyConfigFor(40123)
        assertTrue(config.isReverseBypassEnabled)
        assertEquals(listOf("*.onion", "*.onion."), config.bypassRules)
        assertEquals(listOf("socks5://127.0.0.1:40123"), config.proxyRules.map { it.url })
        assertEquals(
            listOf("socks5://127.0.0.1:${TorRouting.REFUSE_PORT}"),
            TorRouting.proxyConfigFor(TorRouting.REFUSE_PORT).proxyRules.map { it.url },
        )
    }

    @Test
    fun `the refusal says why`() {
        assertEquals(TorRouting.CODE_UNSUPPORTED, TorRouting.refusalCode(false, true))
        assertEquals(TorRouting.CODE_OFF, TorRouting.refusalCode(true, false))
        assertEquals(TorRouting.CODE_OFF, TorRouting.refusalCode(null, false))
        assertEquals(TorRouting.CODE_NOT_RUNNING, TorRouting.refusalCode(true, true))

        val off = TorRouting.refusalHtml(onion, TorRouting.CODE_OFF, TorInfo())
        assertTrue(off.contains("<h1>Tor is off</h1>"))
        assertTrue(off.contains(onion))
        val stopped = TorRouting.refusalHtml(onion, TorRouting.CODE_NOT_RUNNING, TorInfo())
        assertTrue(stopped.contains("<h1>Tor isn't running</h1>"))
        assertTrue(stopped.contains("Nodes page"))
        // A start failure is shown, escaped.
        val failed = TorRouting.refusalHtml(
            onion, TorRouting.CODE_NOT_RUNNING,
            TorInfo(status = TorStatus.Error, errorMessage = "bind <denied>"),
        )
        assertTrue(failed.contains("couldn't start"))
        assertTrue(failed.contains("bind &lt;denied&gt;"))
        assertTrue(TorRouting.refusalHtml(onion, TorRouting.CODE_UNSUPPORTED, TorInfo()).contains("newer WebView"))
        // Nothing to run or fetch.
        assertTrue(off.contains("default-src 'none'"))
        assertFalse(off.contains("<script"))
    }

    @Test
    fun `a native onion fetch without Tor is refused before anything resolves`() {
        // No Tor port is routed in a unit test.
        assertEquals(0, TorRouting.port)
        for (url in listOf("http://$onion/", "https://www.$onion/a.png", "http://example.onion.:8080/")) {
            try {
                TorRouting.openConnection(URL(url))
                fail("opened $url")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("Tor"))
            }
        }
        // Anything else is opened as before (openConnection doesn't connect).
        TorRouting.openConnection(URL("https://example.com/")).let {
            assertEquals("example.com", it.url.host)
        }
    }

    @Test
    fun `a typed onion address opens over http`() {
        val ddg = SearchEngines.DEFAULT.template
        assertEquals("http://$onion", UrlParser.toUrl(onion, ddg))
        assertEquals("http://$onion/path?q=1", UrlParser.toUrl("$onion/path?q=1", ddg))
        assertEquals("http://$onion:8080/x", UrlParser.toUrl("$onion:8080/x", ddg))
        assertEquals("https://$onion/", UrlParser.toUrl("https://$onion/", ddg))
        assertEquals("https://example.com", UrlParser.toUrl("example.com", ddg))
        assertEquals("https://onion.example.com", UrlParser.toUrl("onion.example.com", ddg))
    }
}
