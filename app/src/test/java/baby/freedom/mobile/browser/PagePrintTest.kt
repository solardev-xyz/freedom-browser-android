package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PagePrintTest {

    @Test
    fun `job is named after the page title`() {
        assertEquals(
            "Vitalik's docs",
            printJobName(" Vitalik's docs ", "vitalik.eth/docs", "http://127.0.0.1:1633/bzz/abc/"),
        )
    }

    @Test
    fun `untitled page falls back to the address the capsule shows, not the gateway url`() {
        assertEquals(
            "vitalik.eth/docs",
            printJobName("", "vitalik.eth/docs", "http://127.0.0.1:1633/bzz/abc/"),
        )
    }

    @Test
    fun `no address bar text falls back to the url`() {
        assertEquals("https://example.com/", printJobName("", "", "https://example.com/"))
    }

    @Test
    fun `nothing at all still names the job`() {
        assertEquals("Page", printJobName("  ", "", ""))
    }
}
