package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a main-frame HTTP error on a dweb page picks its [ErrorPage] code:
 * the interceptor's own refusal of an ENS document (#99) names its code
 * in [NAME_RESOLUTION_ERROR_HEADER]; everything else keeps the
 * status-based reading.
 */
class NameResolutionErrorTest {

    @Test
    fun `the interceptor's header names the error page`() {
        for (code in listOf("ens_not_found", "ens_unsupported_codec", "ens_lookup_failed")) {
            assertEquals(
                code,
                errorCodeForMainFrameHttpError(
                    statusForNameResolutionError(code),
                    mapOf(NAME_RESOLUTION_ERROR_HEADER to code),
                ),
            )
        }
    }

    @Test
    fun `the header is matched case-insensitively`() {
        assertEquals(
            "ens_not_found",
            errorCodeForMainFrameHttpError(404, mapOf("x-name-resolution-error" to "ens_not_found")),
        )
    }

    @Test
    fun `without the header the status decides as before`() {
        assertEquals("ERR_CONNECTION_REFUSED", errorCodeForMainFrameHttpError(502, emptyMap()))
        assertEquals("swarm_content_not_found", errorCodeForMainFrameHttpError(404, null))
        assertEquals("swarm_content_not_found", errorCodeForMainFrameHttpError(500, emptyMap()))
    }

    @Test
    fun `a lookup failure is a 502 and a missing name a 404`() {
        assertEquals(502, statusForNameResolutionError("ens_lookup_failed"))
        assertEquals(404, statusForNameResolutionError("ens_not_found"))
        assertEquals(404, statusForNameResolutionError("ens_unsupported_codec"))
    }
}
