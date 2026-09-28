package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

/**
 * #143 / PR #203 R1-F1 against the platform's own `HttpURLConnection`
 * (OkHttp inside, which percent-decodes and IDNA-maps the host): a
 * clearnet redirect to an onion however spelled is refused before any
 * connection, and a plain redirect chain is still followed.
 */
@RunWith(AndroidJUnit4::class)
class TorRedirectDeviceTest {
    private val name = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid"
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aRedirectToAnEncodedOnionIsRefusedWithoutTor() {
        for (location in listOf("http://$name%2eonion/f.bin", "http://$name.on%C2%ADion/f.bin", "http://$name.onion/f.bin")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            try {
                TorRouting.openFollowingRedirects(URL(server.url("/dl").toString())) { }
                fail("followed $location")
            } catch (_: TorRouting.RefusedException) {
            }
        }
        // The stack itself reads the encoded host as an onion name.
        assertEquals("$name.onion", okhttp3.HttpUrl.Builder().scheme("http").host("$name%2eonion").build().host)
    }

    @Test
    fun aPlainRedirectChainIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val conn = TorRouting.openFollowingRedirects(URL(server.url("/first").toString())) {
            setRequestProperty("X-Hop", it.path)
        }
        assertEquals(200, conn.responseCode)
        assertEquals("ok", conn.inputStream.bufferedReader().readText())
        conn.disconnect()
        assertEquals("/first", server.takeRequest().getHeader("X-Hop"))
        assertEquals("/next", server.takeRequest().getHeader("X-Hop"))
    }
}
