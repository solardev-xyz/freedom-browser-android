package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.TxRecord
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Send form's validation, asset order and suggestions, the review's headline, Scan's Send/Pay and Receive's grouping (#422). */
class SendFormTest {
    private val eth = BuiltInChains.ETHEREUM
    private val gnosis = BuiltInChains.GNOSIS
    private val assets = listOf(eth, gnosis).flatMap { chain -> TokenRegistry.tokens(chain).map { chain to it } }
    private val ethNative = TokenRegistry.tokens(eth).first()
    private val usdc = TokenRegistry.tokens(eth).first { it.symbol == "USDC" }
    private val xbzz = TokenRegistry.tokens(gnosis).first { it.symbol == "xBZZ" }
    private val me = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val other = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val third = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val trust = ChainTrust(
        ChainTrust.Level.VERIFIED, ChainSource.QUORUM, listOf("a.example", "b.example"), emptyList(),
        listOf("a.example", "b.example"), 3, 2, null,
    )

    private fun known(raw: Long) = TokenBalance.Known(BigInteger.valueOf(raw), trust)

    // ---- asset order ----

    @Test
    fun `assets the account holds come first, each group in the wallet's own order`() {
        val balances = mapOf(
            xbzz.key to known(5),
            usdc.key to known(0),
            ethNative.key to known(0),
            "${gnosis.id}:native" to known(7),
        )
        val order = assetOrder(assets, balances).map { it.second.key }
        assertEquals(listOf("${gnosis.id}:native", xbzz.key), order.take(2))
        // The rest keep the registry's order: Ethereum's native currency before its tokens.
        assertEquals(assets.map { it.second.key }.filter { it !in order.take(2) }, order.drop(2))
    }

    @Test
    fun `with nothing read the order is the wallet's own, and a failed read keeps its last balance`() {
        assertEquals(assets, assetOrder(assets, emptyMap()))
        val failed = TokenBalance.Failed("timeout", previous = known(3))
        assertEquals(usdc.key, assetOrder(assets, mapOf(usdc.key to failed)).first().second.key)
    }

    // ---- amount checked while typing ----

    @Test
    fun `nothing typed says nothing, and a usable amount is its base units`() {
        assertEquals(AmountCheck.Empty, amountCheck("", ethNative, eth, held = null, nativeHeld = null))
        assertEquals(
            AmountCheck.Ok(BigInteger("1500000000000000000")),
            amountCheck("1.5", ethNative, eth, held = BigInteger.TEN.pow(19), nativeHeld = BigInteger.TEN.pow(19)),
        )
    }

    @Test
    fun `an unreadable amount says why, and isn't blamed on the balance`() {
        val ambiguous = amountCheck("1,234", ethNative, eth, held = null, nativeHeld = null) as AmountCheck.Problem
        assertEquals(ambiguousAmountNote("1,234"), ambiguous.note)
        assertEquals(false, ambiguous.balance)
        val tooPrecise = amountCheck("0.1234567", usdc, eth, held = null, nativeHeld = null) as AmountCheck.Problem
        assertTrue(tooPrecise.note, tooPrecise.note.contains("6"))
        assertEquals(false, tooPrecise.balance)
        assertTrue(amountCheck("0", ethNative, eth, null, null) is AmountCheck.Problem)
    }

    @Test
    fun `more than the balance is refused as it's typed, naming what the account has`() {
        val check = amountCheck("2", usdc, eth, held = BigInteger.valueOf(1_500_000), nativeHeld = BigInteger.ONE) as AmountCheck.Problem
        assertEquals("Not enough USDC: this account has 1.5 USDC.", check.note)
        assertTrue(check.balance)
        val empty = amountCheck("0.001", ethNative, eth, held = BigInteger.ZERO, nativeHeld = BigInteger.ZERO) as AmountCheck.Problem
        assertEquals("Not enough ETH: this account has 0 ETH.", empty.note)
        // Exactly the balance is the review's to price (Max takes the fee off a native send).
        assertEquals(
            AmountCheck.Ok(BigInteger.valueOf(1_500_000)),
            amountCheck("1.5", usdc, eth, held = BigInteger.valueOf(1_500_000), nativeHeld = BigInteger.ONE),
        )
    }

    @Test
    fun `a token with none of the network's currency for the fee is refused, an unread balance blocks nothing`() {
        val check = amountCheck("1", xbzz, gnosis, held = BigInteger.TEN.pow(17), nativeHeld = BigInteger.ZERO) as AmountCheck.Problem
        assertEquals("This account has no xDAI on Gnosis Chain to pay the network fee.", check.note)
        assertTrue(check.balance)
        assertTrue(amountCheck("1", xbzz, gnosis, held = null, nativeHeld = null) is AmountCheck.Ok)
    }

    // ---- suggestions ----

    private fun record(to: String, at: Long, from: String = me, name: String? = null, payee: Boolean = true) = TxRecord(
        hash = "0x" + at.toString().padStart(64, '0'), chainId = gnosis.id, chainName = gnosis.name, chainSymbol = gnosis.symbol,
        chainDecimals = 18, explorerUrl = gnosis.explorerUrl, from = from, to = to, toName = name, tokenAddress = null,
        tokenSymbol = "xDAI", tokenDecimals = 18, amount = BigInteger.ONE, nonce = BigInteger.valueOf(at), sentAt = at,
        status = TxRecord.Status.CONFIRMED, payee = payee,
    )

    @Test
    fun `suggestions are the other accounts, then recent recipients newest first, each once`() {
        val accounts = listOf(WalletAccount(0, "Account 1", me), WalletAccount(1, "Savings", other))
        val records = listOf(
            record(third, at = 10),
            record(third.lowercase(), at = 30, name = "alice.eth"),
            record(other, at = 40), // an own account: already listed as one
            record(me, at = 50), // a self-send
            record("0x0000000000000000000000000000000000000001", at = 20),
            record("0x0000000000000000000000000000000000000002", at = 60, from = other), // another account's send
        )
        assertEquals(
            listOf(
                RecipientSuggestion("Savings", other, mine = true),
                RecipientSuggestion("alice.eth", third.lowercase(), mine = false),
                RecipientSuggestion(null, "0x0000000000000000000000000000000000000001", mine = false),
            ),
            recipientSuggestions(accounts, me, records),
        )
        assertEquals(1, recipientSuggestions(emptyList(), me, records, recent = 1).size)
    }

    @Test
    fun `a composed call's contract, or a record not known to be a payment, is never suggested`() {
        val records = listOf(
            record("0x0000000000000000000000000000000000000003", at = 70, payee = false), // a DEX router, a Safe, the postage contract
            record(third, at = 10),
        )
        assertEquals(listOf(RecipientSuggestion(null, third, mine = false)), recipientSuggestions(emptyList(), me, records))
    }

    // ---- review headline ----

    @Test
    fun `the review's headline says what goes where, with the name and the short address`() {
        val to = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
        assertEquals(
            "Send 0.001 ETH to vitalik.eth (0xd8dA…6045) on Ethereum",
            sendHeadline("0.001", "ETH", to, "vitalik.eth", "Ethereum"),
        )
        assertEquals("Send 20 USDC to 0xd8dA…6045 on Ethereum", sendHeadline("20", "USDC", to, null, "Ethereum"))
    }

    @Test
    fun `explorer links name the address page, and a chain without an explorer has none`() {
        assertEquals("https://etherscan.io/address/$me", explorerAddressUrl(eth, me))
        assertEquals("https://x.example/address/$me", explorerAddressUrl("https://x.example/", me))
        assertNull(explorerAddressUrl(null, me))
        assertNull(explorerAddressUrl("", me))
        assertEquals(
            listOf("etherscan.io" to "https://etherscan.io/address/$me", "gnosisscan.io" to "https://gnosisscan.io/address/$me"),
            accountExplorerLinks(listOf(eth, gnosis, eth.copy(id = 999, explorerUrl = null)), me),
        )
    }

    // ---- scan: Send / Pay ----

    @Test
    fun `a scanned address opens Send on the page's default asset`() {
        val read = scannedRecipient(other) as ScannedRecipient.Fill
        assertEquals(other, read.recipient)
        val prefill = read.sendPrefill()
        assertEquals(ANY_ASSET, prefill.tokenKey)
        assertEquals(other, prefill.recipient)
        assertNull(prefill.amount)
        assertNull(prefill.origin)
    }

    @Test
    fun `a payment request opens Send as its link would, and one Send can't pay says why`() {
        val pay = scannedRecipient("ethereum:${xbzz.address}@100/transfer?address=$other&uint256=1.5e16") as ScannedRecipient.Fill
        val prefill = pay.sendPrefill()
        assertEquals(xbzz.key, prefill.tokenKey)
        assertEquals(other, prefill.recipient)
        assertEquals(BigInteger("15000000000000000"), prefill.amount)
        assertNull(prefill.chainGuess)

        val base = scannedRecipient("ethereum:$other@8453?value=1e15")
        assertTrue(base.toString(), base is ScannedRecipient.Refused && base.reason.contains("Base"))
        assertTrue(scannedRecipient("hello") is ScannedRecipient.Refused)
    }

    // ---- paste ----

    @Test
    fun `paste leaves out this wallet's phrase and key, and anything flagged sensitive, unread`() {
        assertEquals(PastedRecipient.Secret, pastedRecipient(PhraseClipboard.CLIP_LABEL, sensitive = false, text = "abandon ability able"))
        assertEquals(PastedRecipient.Secret, pastedRecipient(PhraseClipboard.KEY_CLIP_LABEL, sensitive = false, text = "0x" + "11".repeat(32)))
        assertEquals(PastedRecipient.Secret, pastedRecipient("Password", sensitive = true, text = other))
        assertEquals(PastedRecipient.Secret, pastedRecipient(PhraseClipboard.CLIP_LABEL, sensitive = false, text = null))
    }

    @Test
    fun `paste fills in a request as Scan does, refuses one Send can't pay, and takes anything else as typed`() {
        val pay = pastedRecipient("URL", false, " ethereum:${xbzz.address}@100/transfer?address=$other&uint256=1.5e16 ")
        assertEquals(PastedRecipient.Fill(scannedRecipient("ethereum:${xbzz.address}@100/transfer?address=$other&uint256=1.5e16") as ScannedRecipient.Fill), pay)
        assertEquals(xbzz.key, (pay as PastedRecipient.Fill).fill.prefill!!.tokenKey)
        assertEquals(PastedRecipient.Fill(ScannedRecipient.Fill(other)), pastedRecipient(null, false, other))
        val base = pastedRecipient(null, false, "ethereum:$other@8453?value=1e15")
        assertTrue(base.toString(), base is PastedRecipient.Refused && base.reason.contains("Base"))
        assertEquals(PastedRecipient.Text("vitalik.eth"), pastedRecipient(null, false, "  vitalik.eth\n"))
        assertNull(pastedRecipient(null, false, "  "))
    }

    @Test
    fun `a request naming no network says which was assumed, worded for a link or for a request`() {
        val guessed = (scannedRecipient("ethereum:$other?value=1e15") as ScannedRecipient.Fill).prefill!!
        assertEquals(ChainGuess.ETHEREUM_DEFAULT, guessed.chainGuess)
        assertTrue(chainGuessNote(ChainGuess.ETHEREUM_DEFAULT, FillSource.SCANNED, "Ethereum").startsWith("The request doesn’t name a network"))
        assertTrue(chainGuessNote(ChainGuess.ETHEREUM_DEFAULT, FillSource.PASTED, "Ethereum").startsWith("The request doesn’t name a network"))
        assertTrue(chainGuessNote(ChainGuess.ETHEREUM_DEFAULT, FillSource.LINK, "Ethereum").startsWith("The link doesn’t name a network"))
        assertTrue(chainGuessNote(ChainGuess.ONLY_CHAIN_WITH_TOKEN, FillSource.SCANNED, "Gnosis Chain").contains("Gnosis Chain is filled in"))
    }

    // ---- receive ----

    @Test
    fun `receive groups the address in fours, 0x on the first group`() {
        assertEquals(
            listOf("0x5aAe", "b605", "3F3E", "94C9", "b9A0", "9f33", "6694", "35E7", "Ef1B", "eAed"),
            addressGroups(me),
        )
        assertEquals(me, addressGroups(me).joinToString(""))
    }
}
