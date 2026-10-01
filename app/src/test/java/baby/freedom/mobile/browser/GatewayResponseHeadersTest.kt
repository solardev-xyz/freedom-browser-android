package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** [gatewayResponseHeaders] drops what a gateway mustn't pass to a virtual origin (#355). */
class GatewayResponseHeadersTest {
    @Test
    fun `Service-Worker-Allowed never reaches the page`() {
        val headers = gatewayResponseHeaders(
            mapOf(
                "Content-Type" to listOf("text/javascript"),
                "Service-Worker-Allowed" to listOf("/"),
                "service-worker-allowed" to listOf("/"),
            ),
        )
        assertEquals(
            mapOf("Content-Type" to "text/javascript", "Access-Control-Allow-Origin" to "*"),
            headers,
        )
    }
}
