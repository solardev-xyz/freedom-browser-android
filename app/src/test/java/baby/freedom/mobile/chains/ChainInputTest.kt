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

    /**
     * A site names the chain it adds (`wallet_addEthereumChain`) and the
     * sheets show the name as is: nothing that reorders the row (a bidi
     * override or isolate), splits it (U+2028/U+2029) or hides (a BOM, a
     * soft hyphen) gets in — emoji joiners and flag tags still do.
     */
    @Test
    fun namesAndSymbolsCantReorderOrSplitTheSheetsTheyAreShownOn() {
        fun build(name: String, symbol: String = "TST", currencyName: String? = null) = ChainInput.build(
            id = "1337", name = name, symbol = symbol, decimals = "18", explorer = "",
            rpcUrls = listOf("https://rpc.example.org"), currencyName = currencyName,
        )
        for (bad in listOf("Ethereum (chain 1)\u2028\u2028Testnet", "Eth\u2029ereum", "\u202Eeroc", "Gnosis\u2066x\u2069", "Base\uFEFF", "Ba\u00ADse")) {
            assertNull(bad, ChainInput.parseName(bad))
            assertNull(bad, build(bad))
            assertNull(bad, ChainInput.parseSymbol(bad.take(10)))
        }
        assertNull(build("Test", symbol = "ETH\u202E"))
        // A bad currency name falls back to the symbol, as an invalid one always has.
        assertEquals("TST", build("Test", currencyName = "Ether\u2028x")!!.currencyName)
        // Emoji built from joiners and tag characters are names like any other.
        val family = "Chain \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val scotland = "Chain \uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals(family, build(family)!!.name)
        assertEquals(scotland, build(scotland)!!.name)
        // A chain stored before this rule still reads back: it mustn't vanish on upgrade.
        val old = ChainInput.build(
            id = "1337", name = "Old\u202Ename", symbol = "TST", decimals = "18", explorer = "",
            rpcUrls = listOf("https://rpc.example.org"), stored = true,
        )
        assertEquals("Old\u202Ename", old!!.name)
    }
}

