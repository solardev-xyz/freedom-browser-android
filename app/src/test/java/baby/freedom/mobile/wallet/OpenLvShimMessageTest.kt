package baby.freedom.mobile.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the app takes from the signing page's shim (#113): its three messages, and nothing malformed. */
class OpenLvShimMessageTest {
    @Test
    fun `ready, status and request are read`() {
        assertEquals(OpenLvShimMessage.Ready, OpenLvShimMessage.parse("""{"type":"ready"}"""))
        assertEquals(OpenLvShimMessage.Link(3, OpenLvLink.Connected), OpenLvShimMessage.parse("""{"type":"status","sid":3,"status":"connected"}"""))
        assertEquals(
            OpenLvShimMessage.Link(3, OpenLvLink.Failed("relay down")),
            OpenLvShimMessage.parse("""{"type":"status","sid":3,"status":"failed","message":"relay down"}"""),
        )
        val r = OpenLvShimMessage.parse("""{"type":"request","sid":2,"id":9,"method":"personal_sign","params":["0x68","0xab"]}""") as OpenLvShimMessage.Request
        assertEquals(2, r.sid)
        assertEquals(9, r.id)
        assertEquals("personal_sign", r.method)
        assertEquals("0xab", r.params.getString(1))
    }

    @Test
    fun `anything malformed is dropped`() {
        for (bad in listOf(
            "",
            "nope",
            "[]",
            """{"type":"what"}""",
            """{"type":"status","status":"connected"}""",
            """{"type":"status","sid":"1","status":"connected"}""",
            """{"type":"status","sid":1,"status":"sideways"}""",
            """{"type":"request","sid":1,"method":"eth_chainId"}""",
            """{"type":"request","sid":1,"id":1.5,"method":"eth_chainId"}""",
            "[".repeat(50_000),
            "x".repeat(OpenLvShimMessage.MAX_MESSAGE + 1),
        )) {
            assertNull(bad.take(40), OpenLvShimMessage.parse(bad))
        }
    }

    @Test
    fun `a request with a readable session and ID is refused, not dropped`() {
        // R4-F3: dropped, the shim's promise and the peer waiting on it would hang until the session ends.
        for (bad in listOf(
            """{"type":"request","sid":1,"id":7,"method":""}""",
            """{"type":"request","sid":1,"id":7,"method":"${"m".repeat(65)}"}""",
            """{"type":"request","sid":1,"id":7,"method":"personal_sign","params":{"a":1}}""",
        )) {
            assertEquals(bad.take(60), OpenLvShimMessage.Refused(1, 7), OpenLvShimMessage.parse(bad))
        }
    }
}
