package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #534: the cases desktop's `gateway-path.test.js` pins (#814), which
 * in turn are freedom-core's fc-dweb `validate.rs` cases, so the three
 * agree on which dweb paths may reach a gateway.
 */
class DwebPathTest {

    @Test
    fun `decodes only dots and separators, in one pass`() {
        mapOf(
            "/a%2Fb%2f%5C%5c%2E%2e" to "/a/b/\\\\..",
            "/%252F%2" to "/%252F%2",
            "/%" to "/%",
            "/%zz%2F" to "/%zz/",
            "/caf%C3%A9" to "/caf%C3%A9",
        ).forEach { (path, decoded) -> assertEquals(path, decoded, DwebPath.decodeDotsAndSeparators(path)) }
    }

    @Test
    fun `encoded separators`() {
        listOf("/a%2fb", "/a%2Fb", "/a%5cb", "/a%5Cb").forEach { assertTrue(it, DwebPath.hasEncodedSeparator(it)) }
        listOf("/a/b", "/a%252Fb", "/a%2Eb", "/a%20b").forEach { assertFalse(it, DwebPath.hasEncodedSeparator(it)) }
    }

    @Test
    fun `refuses paths that climb out of the content root`() {
        listOf(
            "/..%2f..%2fipns/example.com",
            "/..%2F..%2Fapi/v0/id",
            "/%2e%2e%2f%2e%2e%2fapi/v0/id",
            "/a%5c..%5c..",
            "/a/.%2E%2F.%2e/x",
            "/a/%2E%5Cx",
            "/a/..\\..\\api",
            "/a\\b",
            "/a/../b",
            "/./a",
        ).forEach { assertFalse(it, DwebPath.isContainedPath(it)) }
    }

    @Test
    fun `passes names that only look like dot segments`() {
        listOf(
            "/",
            "/index.html",
            "/a%2Fb",
            "/a%5Cb",
            "/my%20file%2Etxt",
            "/..foo/bar../%2e%2ebaz",
            "/.well-known/x",
            "/caf%C3%A9/100%25",
            "/%252F..%252F",
        ).forEach { assertTrue(it, DwebPath.isContainedPath(it)) }
    }

    @Test
    fun `bzz refuses any encoded separator`() {
        assertFalse(DwebPath.isContainedPath("/a%2Fb", encodedSeparators = false))
        assertFalse(DwebPath.isContainedPath("/a%5cb", encodedSeparators = false))
        assertTrue(DwebPath.isContainedPath("/my%20file%2Etxt", encodedSeparators = false))
    }

    @Test
    fun `only the path is checked, not the query`() {
        assertEquals("/a/b", DwebPath.pathnameOf("/a/b?q=..%2f#x"))
        assertTrue(DwebPath.isContainedPath(DwebPath.pathnameOf("/my%20file%2Etxt?q=..%2f")))
        assertTrue(DwebPath.isContainedTarget("/ipns/docs.ipfs.tech/x?q=..%2f"))
    }

    @Test
    fun `targets stay beneath their namespace and reference`() {
        listOf("/ipfs/c", "/ipfs/c/", "/ipfs/c/a%2Fb", "/ipns/docs.ipfs.tech/x?q=..%2f")
            .forEach { assertTrue(it, DwebPath.isContainedTarget(it)) }
        listOf(
            "/ipfs/c/a%2F..%2F..",
            "/ipfs/c/%2E%5Cx",
            "/ipfs/c/..%2f..%2fipns/x",
            "/ipfs/c/../../api/v0/id",
            "/ipfs/c/a\\..\\..\\..\\api",
            "/ipfs/c%2F..",
            "/ipfs/%2e%2e",
            "/ipfs/",
            "/ipfs",
            // java.net.URL sends this as written, unlike desktop's `new URL`:
            // refused, though the gateway would resolve it beneath the root.
            "/ipfs/c/a/../b",
        ).forEach { assertFalse(it, DwebPath.isContainedTarget(it)) }
        val ref = "a".repeat(64)
        assertTrue(DwebPath.isContainedTarget("/bzz/$ref/x", encodedSeparators = false))
        assertFalse(DwebPath.isContainedTarget("/bzz/$ref/a%2Fb", encodedSeparators = false))
        assertFalse(DwebPath.isContainedTarget("/bzz/$ref/a/..\\..\\..\\stamps"))
    }

    @Test
    fun `logs name the gateway or scheme, never the content`() {
        assertEquals(
            "http://127.0.0.1:1633/<redacted>",
            gatewayUrlForLog("http://127.0.0.1:1633/bzz/${"a".repeat(64)}/diary.txt?x=1"),
        )
        assertEquals("https://gw.example/<redacted>", gatewayUrlForLog("https://gw.example"))
        assertEquals("ipfs:<redacted>", dwebUrlForLog("ipfs://bafy/diary.txt"))
        assertEquals("https:<redacted>", dwebUrlForLog("https://bafy.ipfs.localhost/diary.txt"))
        assertEquals("<redacted>", dwebUrlForLog("nonsense"))
    }
}
