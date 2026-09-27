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
}
