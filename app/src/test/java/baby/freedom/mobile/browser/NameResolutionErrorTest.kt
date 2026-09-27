package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interceptor's refusal of an ENS document (#99): how
 * `onReceivedHttpError` recognises it, and which requests count as the
 * documents the name re-check runs for.
 */
class NameResolutionErrorTest {

    @Test
    fun `the interceptor's header names the refusal`() {
        for (code in listOf("ens_not_found", "ens_unsupported_codec", "ens_lookup_failed")) {
            assertEquals(code, nameResolutionErrorIn(mapOf(NAME_RESOLUTION_ERROR_HEADER to code)))
        }
    }

    @Test
    fun `the header is matched case-insensitively`() {
        assertEquals(
            "ens_not_found",
            nameResolutionErrorIn(mapOf("x-name-resolution-error" to "ens_not_found")),
        )
    }

    @Test
    fun `without the header it is an ordinary HTTP error`() {
        assertNull(nameResolutionErrorIn(emptyMap()))
        assertNull(nameResolutionErrorIn(null))
        assertNull(nameResolutionErrorIn(mapOf("Content-Type" to "text/html")))
    }

    @Test
    fun `a lookup failure is a 502 and a missing name a 404`() {
        assertEquals(502, statusForNameResolutionError("ens_lookup_failed"))
        assertEquals(404, statusForNameResolutionError("ens_not_found"))
        assertEquals(404, statusForNameResolutionError("ens_unsupported_codec"))
    }

    @Test
    fun `every refusal code gets its own copy, not the RPC-unreachable fallback`() {
        val fallback = nameResolutionRefusalCopy("ens_lookup_failed")
        for (code in listOf("ens_not_found", "ens_unsupported_codec", "ens_invalid_name", "ens_name_too_long")) {
            assertTrue(code, nameResolutionRefusalCopy(code) != fallback)
            assertFalse(code, nameResolutionRefusalCopy(code).second.contains("RPC"))
        }
        assertEquals("Not a valid ENS name", nameResolutionRefusalCopy("ens_invalid_name").first)
        assertEquals("ENS name too long", nameResolutionRefusalCopy("ens_name_too_long").first)
    }

    @Test
    fun `name refusals map to their own pages and lookup failures don't`() {
        assertEquals("ens_invalid_name", refusedNameErrorCode("INVALID_NAME"))
        assertEquals("ens_name_too_long", refusedNameErrorCode("NAME_TOO_LONG"))
        assertNull(refusedNameErrorCode("PROVIDER_ERROR"))
        assertEquals(404, statusForNameResolutionError("ens_invalid_name"))
        assertEquals(404, statusForNameResolutionError("ens_name_too_long"))
    }

    private val navAccept =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    @Test
    fun `the main frame is always a document`() {
        assertTrue(isDocumentRequest(true, null))
        assertTrue(isDocumentRequest(true, mapOf("Accept" to "*/*")))
    }

    @Test
    fun `an iframe or a service-worker-forwarded navigation is a document`() {
        assertTrue(isDocumentRequest(false, mapOf("Sec-Fetch-Dest" to "iframe")))
        assertTrue(isDocumentRequest(false, mapOf("sec-fetch-dest" to "document")))
        assertTrue(isDocumentRequest(false, mapOf("Accept" to navAccept)))
    }

    @Test
    fun `subresources are not documents`() {
        assertFalse(isDocumentRequest(false, null))
        assertFalse(isDocumentRequest(false, mapOf("Accept" to "*/*")))
        assertFalse(isDocumentRequest(false, mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertFalse(isDocumentRequest(false, mapOf("Accept" to "image/avif,image/webp,*/*")))
        // Sec-Fetch-Dest, when present, wins over an HTML-ish Accept.
        assertFalse(
            isDocumentRequest(false, mapOf("Sec-Fetch-Dest" to "empty", "Accept" to navAccept)),
        )
    }

    @Test
    fun `a refused main-frame document is recognised until the next one`() {
        val slot = NameRefusalSlot()
        val url = "https://name-eth.ens.freedom.baby/"
        assertFalse(slot.isRefused(url))
        slot.onMainFrameResponse(url, "ens_not_found")
        assertTrue(slot.isRefused(url))
        assertTrue(slot.isRefused("$url#section"))
        assertFalse(slot.isRefused("https://other-eth.ens.freedom.baby/"))
        assertFalse(slot.isRefused(null))
        // The next main-frame document was served: no longer refused.
        slot.onMainFrameResponse(url, null)
        assertFalse(slot.isRefused(url))
    }

    @Test
    fun `only a response WebView renders in place marks a navigation delivered`() {
        assertTrue(rendersInPlace(200, "text/html", emptyMap()))
        assertTrue(rendersInPlace(200, "image/svg+xml", emptyMap()))
        assertTrue(rendersInPlace(200, "application/xml", emptyMap()))
        assertTrue(rendersInPlace(200, "text/xml; charset=utf-8", emptyMap()))
        assertTrue(rendersInPlace(200, "application/json", emptyMap()))
        assertTrue(rendersInPlace(200, "text/plain", emptyMap()))
        assertTrue(rendersInPlace(200, "image/png", emptyMap()))
        assertTrue(rendersInPlace(200, "video/mp4", emptyMap()))
        assertFalse(rendersInPlace(200, "text/csv", emptyMap()))
        assertFalse(rendersInPlace(200, "application/zip", emptyMap()))
        assertTrue(rendersInPlace(404, "text/html", mapOf("X-Name-Resolution-Error" to "ens_not_found")))
        assertTrue(rendersInPlace(200, "application/xhtml+xml", null))
        assertFalse(rendersInPlace(200, "application/octet-stream", emptyMap()))
        assertFalse(rendersInPlace(204, "text/html", emptyMap()))
        assertFalse(rendersInPlace(302, "text/html", emptyMap()))
        assertFalse(rendersInPlace(200, null, emptyMap()))
        assertFalse(
            rendersInPlace(200, "text/html", mapOf("content-disposition" to "attachment; filename=a.html")),
        )
    }
}
