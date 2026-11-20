package baby.freedom.mobile.names

import baby.freedom.mobile.names.NameAbi.toHexString
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Encoding pinned to desktop's `src/main/domains/contracts.js`: every
 * expected value here is what ethers' `Interface`/`AbiCoder` and desktop's
 * `parseName`/`commitmentFor` produce for the same input (generated from
 * desktop `origin/main` under node).
 */
class NameContractsTest {
    private val owner = "0x000000000000000000000000000000000000dEaD"
    private val secret = ByteArray(32) { 0x42 }

    private fun hex(b: ByteArray) = "0x" + b.toHexString()

    @Test
    fun `names parse as desktop parses them`() {
        val eth = NameContracts.parse("FrEeDoM.eth")
        assertEquals("freedom.eth", eth.name)
        assertEquals("freedom", eth.label)
        assertTrue(eth.topLevel)
        assertEquals("0xd196ba559a07bc638779ae5b4566eefbb5f1ce7b676e4b643decfd180571aeca", hex(eth.node))
        assertEquals(BigInteger("115192299574218722406209610361552701062715483622231055178814505609612380518805"), eth.tokenId)

        val wei = NameContracts.parse("freedom.wei")
        assertEquals(NameSpace.WEI, wei.space)
        assertEquals("0x9122a03dd1f3b65e06e0c840edd64da564892c1d931fe9e2fc7cb1ac14f85b50", hex(wei.node))
        assertEquals(BigInteger("65646541790858850981258835361005195612627286058551645495873857523619596884816"), wei.tokenId)

        val gwei = NameContracts.parse("FREEDOM.gwei")
        assertEquals("freedom.gwei", gwei.name)
        assertEquals(BigInteger("46296330056431471898442550703703978132990377069582789260693605164683423946514"), gwei.tokenId)

        val sub = NameContracts.parse("sub.freedom.gwei")
        assertFalse(sub.topLevel)
        assertEquals("sub", sub.label)
        assertEquals(BigInteger("99551010877393393837327777631706854081124928502036761027604817054372962340457"), sub.tokenId)

        // ENSIP-15 for .eth: a Unicode name normalizes.
        assertEquals("café.eth", NameContracts.parse("caFé.eth").name)
    }

    @Test
    fun `names desktop refuses are refused with its codes`() {
        val invalid = listOf("https://alice.eth", "alice.eth/path", " alice.eth", "alice..wei", "alice.com", "alice‮.eth", "alice", ".eth", "alice.eth\n")
        for (v in invalid) {
            try {
                NameContracts.parse(v)
                fail("accepted $v")
            } catch (e: NameException) {
                assertEquals(v, NameException.Code.INVALID, e.code)
            }
        }
        // A NameNFT keeps non-ASCII bytes as given: normalizing would pick another token.
        try {
            NameContracts.parse("CAFÉ.wei")
            fail("accepted a legacy Unicode .wei")
        } catch (e: NameException) {
            assertEquals(NameException.Code.UNSUPPORTED, e.code)
        }
    }

    @Test
    fun `commitments match desktop's commitmentFor`() {
        assertEquals(
            "0x039c254d1bdaed963a37f55e1ead8392b8c6a9e494ec16433d0c778c4796e49e",
            hex(NameContracts.commitment(NameSpace.ETH, "freedom", owner, NameContracts.YEAR, secret)),
        )
        val nft = "0x44d06bb855164937e0b90254bc8233279a8380b9af008bd8a347c7af042606a5"
        assertEquals(nft, hex(NameContracts.commitment(NameSpace.WEI, "freedom", owner, NameContracts.YEAR, secret)))
        assertEquals(nft, hex(NameContracts.commitment(NameSpace.GWEI, "freedom", owner, NameContracts.YEAR, secret)))
        // The research vector desktop's own test pins (a NameNFT commitment binds no duration).
        assertEquals(
            "0x7c3f938bd6886591be52e3e4f4167390556ed29af19fe83c582502ed5e0ca99a",
            hex(NameContracts.commitment(NameSpace.WEI, "freedom-skill-research-20261020", owner, NameContracts.YEAR, secret)),
        )
        assertEquals(
            "0xcd8e582b8cc877d9d9fe8ed7f5e810bd97bef4f3a7c4b8bc903f7b9660f25500",
            hex(NameContracts.commitment(NameSpace.ETH, "freedom-skill-research-20261020", owner, NameContracts.YEAR, secret)),
        )
        // An ENS commitment binds the term; a NameNFT's doesn't.
        assertFalse(
            NameContracts.commitment(NameSpace.ETH, "freedom", owner, 2 * NameContracts.YEAR, secret)
                .contentEquals(NameContracts.commitment(NameSpace.ETH, "freedom", owner, NameContracts.YEAR, secret)),
        )
    }

    @Test
    fun `write call data matches ethers' encodeFunctionData`() {
        val c = NameContracts.commitment(NameSpace.ETH, "freedom", owner, NameContracts.YEAR, secret)
        assertEquals("0xf14fcbc8039c254d1bdaed963a37f55e1ead8392b8c6a9e494ec16433d0c778c4796e49e", hex(NameContracts.commitData(c)))
        assertEquals(
            "0xef9c8805" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000100" +
                "000000000000000000000000000000000000000000000000000000000000dead" +
                "0000000000000000000000000000000000000000000000000000000001e13380" +
                "4242424242424242424242424242424242424242424242424242424242424242" +
                "000000000000000000000000f29100983e058b709f3d539b0c765937b804ac15" +
                "0000000000000000000000000000000000000000000000000000000000000140" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000007" +
                "66726565646f6d00000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000",
            hex(NameContracts.registerData("freedom", owner, NameContracts.YEAR, secret)),
        )
        assertEquals(
            "0xea9384fa" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "4242424242424242424242424242424242424242424242424242424242424242" +
                "0000000000000000000000000000000000000000000000000000000000000007" +
                "66726565646f6d00000000000000000000000000000000000000000000000000",
            hex(NameContracts.revealData("freedom", secret)),
        )
        assertEquals(
            "0x18026ad1" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "0000000000000000000000000000000000000000000000000000000001e13380" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000007" +
                "66726565646f6d00000000000000000000000000000000000000000000000000",
            hex(NameContracts.renewData("freedom", NameContracts.YEAR)),
        )
        assertEquals(
            "0xacf1a841" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000001e13380" +
                "0000000000000000000000000000000000000000000000000000000000000007" +
                "66726565646f6d00000000000000000000000000000000000000000000000000",
            hex(NameContracts.wrappedRenewData("freedom", NameContracts.YEAR)),
        )
        assertEquals(
            "0x5baa75099122a03dd1f3b65e06e0c840edd64da564892c1d931fe9e2fc7cb1ac14f85b50",
            hex(NameContracts.nftRenewData(NameContracts.parse("freedom.wei").tokenId)),
        )
    }

    @Test
    fun `read call data matches ethers' encodeFunctionData`() {
        val y = "0000000000000000000000000000000000000000000000000000000001e13380"
        val label = "0000000000000000000000000000000000000000000000000000000000000007" +
            "66726565646f6d00000000000000000000000000000000000000000000000000"
        assertEquals("0x83e7f6ff" + "0000000000000000000000000000000000000000000000000000000000000040" + y + label, hex(NameContracts.rentPriceData("freedom", NameContracts.YEAR)))
        assertEquals("0xaeb8ce9b" + "0000000000000000000000000000000000000000000000000000000000000020" + label, hex(NameContracts.availableData("freedom")))
        assertEquals(
            "0x8f8dc386" + "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000000" + label,
            hex(NameContracts.nftIsAvailableData("freedom", BigInteger.ZERO)),
        )
        assertEquals("0xfcee45f40000000000000000000000000000000000000000000000000000000000000007", hex(NameContracts.nftGetFeeData(7)))
        assertEquals("0x344610670000000000000000000000000000000000000000000000000000000000000001", hex(NameContracts.nftRecordsData(BigInteger.ONE)))
    }

    @Test
    fun `the price buffer is two percent, rounded down like desktop's BigInt division`() {
        assertEquals(BigInteger.valueOf(102), NameContracts.withBuffer(BigInteger.valueOf(100)))
        assertEquals(BigInteger.valueOf(1), NameContracts.withBuffer(BigInteger.ONE))
        assertEquals(BigInteger("3162557440314959999"), NameContracts.withBuffer(BigInteger("3100546510112705882")))
    }

    @Test
    fun `terms are a year at a time for wei and gwei, up to ten for eth`() {
        NameRegistrar.checkDuration(NameSpace.ETH, NameContracts.YEAR)
        NameRegistrar.checkDuration(NameSpace.ETH, 10 * NameContracts.YEAR)
        NameRegistrar.checkDuration(NameSpace.WEI, NameContracts.YEAR)
        for ((space, d) in listOf(NameSpace.ETH to 0L, NameSpace.ETH to 11 * NameContracts.YEAR, NameSpace.WEI to 2 * NameContracts.YEAR, NameSpace.GWEI to 1L)) {
            try {
                NameRegistrar.checkDuration(space, d)
                fail("accepted $space $d")
            } catch (e: NameException) {
                assertEquals(NameException.Code.INVALID, e.code)
            }
        }
    }

    @Test
    fun `return data decodes words, addresses and strings`() {
        val w = NameAbi.words(
            "0x" + "00000000000000000000000000000000000000000000000000000000000000a0" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000077359400" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000007" +
                "66726565646f6d00000000000000000000000000000000000000000000000000",
        )!!
        assertEquals("freedom", NameAbi.dynamicString(w, 0))
        assertEquals(BigInteger.valueOf(2_000_000_000), NameAbi.wordUint(w[2]))
        assertEquals(null, NameAbi.wordAddress(ByteArray(32) { 1 }))
        assertEquals(null, NameAbi.words("0x1234"))
        // An offset past the data is malformed, not read out of bounds.
        assertEquals(null, NameAbi.dynamicString(listOf(NameAbi.uint(4096).bytes), 0))
    }
}
