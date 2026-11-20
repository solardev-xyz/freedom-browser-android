package baby.freedom.mobile.agent

import baby.freedom.mobile.agent.ProviderUrls.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which LAN server URLs Settings → AI accepts (#573, desktop audit L7). */
class ProviderUrlsTest {
    private fun ok(input: String) = ProviderUrls.validate(input).url?.let(ProviderUrls::display)
    private fun why(input: String) = ProviderUrls.validate(input).rejection

    @Test
    fun `plain http is accepted for loopback and private IPv4 literals`() {
        assertEquals("http://127.0.0.1:11434", ok("http://127.0.0.1:11434"))
        assertEquals("http://localhost:11434", ok("http://localhost:11434"))
        assertEquals("http://10.0.2.2:8700/v1", ok("http://10.0.2.2:8700/v1/"))
        assertEquals("http://192.168.1.20:11434", ok("  http://192.168.1.20:11434/  "))
        assertEquals("http://172.16.0.5", ok("http://172.16.0.5"))
        assertEquals("http://172.31.255.255", ok("http://172.31.255.255"))
        assertEquals("http://[::1]:8080", ok("http://[::1]:8080"))
        assertEquals("http://[fd12:3456::1]:8080", ok("http://[fd12:3456::1]:8080"))
        assertEquals("http://[fc00::1]", ok("http://[fc00::1]"))
        // OkHttp dials an IPv4-mapped address as the IPv4 one, and judges it so.
        assertEquals("http://127.0.0.1", ok("http://[::ffff:127.0.0.1]"))
    }

    @Test
    fun `plain http to anything public, or a name, is refused`() {
        for (url in listOf(
            "http://example.com",
            "http://8.8.8.8",
            "http://172.15.0.1",
            "http://172.32.0.1",
            "http://192.169.0.1",
            "http://169.254.1.1",
            // Short, hex, octal and integer forms are names to OkHttp, never local literals.
            "http://127.1",
            "http://0x7f.0.0.1",
            "http://0177.0.0.1",
            "http://2130706433",
            "http://127.0.0.1.evil.example",
            "http://127.tracker.example",
            "http://nas.local",
            "http://my.localhost",
            "http://[2001:db8::1]",
            "http://[fe80::1]",
            "http://[::ffff:8.8.8.8]",
        )) {
            assertEquals(url, Rejection.PublicHttp, why(url))
        }
    }

    @Test
    fun `full-width and percent-encoded digits are judged as OkHttp parses them`() {
        // OkHttp maps full-width digits to ASCII, so this is the private literal it dials…
        assertEquals("http://192.168.1.2", ok("http://１９２.１６８.１.２"))
        // …and decodes a percent-encoded host before judging it the same way.
        assertEquals("http://192.168.1.2", ok("http://192%2e168.1.2"))
        assertEquals(Rejection.PublicHttp, why("http://8%2e8.8.8"))
    }

    @Test
    fun `https goes anywhere a name resolves, LAN names included`() {
        assertEquals("https://api.example.com/v1", ok("https://api.example.com/v1"))
        assertEquals("https://nas.local:8443", ok("https://nas.local:8443/"))
    }

    @Test
    fun `credentials, queries, fragments, onions and other schemes are refused`() {
        assertEquals(Rejection.Credentials, why("http://user:pw@192.168.1.2"))
        assertEquals(Rejection.Credentials, why("https://key@api.example.com"))
        assertEquals(Rejection.QueryOrFragment, why("http://192.168.1.2/v1?key=x"))
        assertEquals(Rejection.QueryOrFragment, why("http://192.168.1.2/v1#x"))
        assertEquals(Rejection.Onion, why("https://abcdefghijklmnop.onion"))
        assertEquals(Rejection.Onion, why("https://ABCDEF.ONION."))
        assertEquals(Rejection.Scheme, why("ftp://192.168.1.2"))
        assertEquals(Rejection.Scheme, why("192.168.1.2:11434"))
        assertEquals(Rejection.Scheme, why("javascript:alert(1)"))
        assertEquals(Rejection.Invalid, why(""))
        assertEquals(Rejection.Invalid, why("http://"))
        assertEquals(Rejection.Invalid, why("https://example.com/" + "a".repeat(2100)))
    }

    @Test
    fun `strict IPv4 accepts only four plain decimal parts`() {
        assertEquals(listOf(10, 0, 0, 1), ProviderUrls.strictIpv4("10.0.0.1"))
        for (bad in listOf("10.0.0", "10.0.0.256", "10.0.0.01", "10.0.0.+1", "10..0.1", "a.b.c.d", "10.0.0.1.")) {
            assertNull(bad, ProviderUrls.strictIpv4(bad))
        }
    }
}
