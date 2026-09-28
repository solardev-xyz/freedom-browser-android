package baby.freedom.mobile.chains

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChainInputTest {
    @Test
    fun chainIds() {
        assertEquals(137L, ChainInput.parseId(" 137 "))
        assertEquals(8453L, ChainInput.parseId("0x2105"))
        assertEquals(Chain.MAX_ID, ChainInput.parseId(Chain.MAX_ID.toString()))
        for (bad in listOf("", "0", "-1", "1.5", "0x", "12a", "0xZZ", (Chain.MAX_ID + 1).toString(), "99999999999999999999")) {
            assertNull(bad, ChainInput.parseId(bad))
        }
    }

    @Test
    fun explorer() {
        assertEquals("https://polygonscan.com", ChainInput.normalizeExplorer(" https://polygonscan.com/ "))
        assertNull(ChainInput.normalizeExplorer("http://polygonscan.com"))
        assertNull(ChainInput.normalizeExplorer("https://u:p@polygonscan.com"))
        assertNull(ChainInput.normalizeExplorer("javascript:alert(1)"))
        assertNull(ChainInput.normalizeExplorer(""))
    }

    @Test
    fun buildNeedsEveryRequiredField() {
        val ok = ChainInput.build(
            id = "137", name = " Polygon ", symbol = "POL", decimals = "18",
            explorer = "", rpcUrls = listOf("https://polygon-rpc.com", "https://polygon-rpc.com"),
        )
        assertNotNull(ok)
        assertEquals("Polygon", ok!!.name)
        assertEquals("POL", ok.currencyName)
        assertNull(ok.explorerUrl)
        assertEquals(listOf("https://polygon-rpc.com"), ok.rpcUrls)
        assertEquals(false, ok.builtIn)

        fun without(
            id: String = "137", name: String = "Polygon", symbol: String = "POL",
            decimals: String = "18", explorer: String = "", rpcs: List<String> = listOf("https://polygon-rpc.com"),
        ) = ChainInput.build(id, name, symbol, decimals, explorer, rpcs)
        assertNull(without(id = "x"))
        assertNull(without(name = "  "))
        assertNull(without(symbol = "P OL"))
        assertNull(without(decimals = "37"))
        assertNull(without(explorer = "ftp://x.example"))
        assertNull(without(rpcs = emptyList()))
        assertNull(without(rpcs = listOf("http://polygon-rpc.com")))
        assertNull(without(rpcs = List(Chain.MAX_RPC_URLS + 1) { "https://r$it.example" }))
    }
}
