package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a site's transaction or typed data does, in words (#423, audit
 * W21/W22): ERC-20 transfer and approve, EIP-2612 and DAI permits,
 * Permit2 — each decoded only in its exact standard shape — and the
 * three warning levels the sheets give them.
 */
class TxDecodeTest {
    private val usdc = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val unlisted = "0x1234567890AbcdEF1234567890aBcdef12345678"
    private val spender = "0x1111111254EEB25477B68fb85Ed929f73A960582"
    private val recipient = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private val owner = "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"
    private val now = 1_790_000_000L

    private fun word(hex: String) = hex.removePrefix("0x").lowercase().padStart(64, '0')
    private fun word(n: BigInteger) = n.toString(16).padStart(64, '0')
    private fun bytes(hex: String) = hex.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ---- Calls ----

    @Test
    fun `a transfer reads as the token amount to the recipient, never 0 ETH`() {
        val data = bytes("a9059cbb" + word(recipient) + word(BigInteger.valueOf(20_000_000)))
        val call = TxDecode.call(usdc, data, 1) as DecodedCall.Transfer
        assertEquals(recipient, call.to)
        assertEquals(BigInteger.valueOf(20_000_000), call.amount)
        assertEquals("Send 20 USDC to 0xd8dA…6045", callHeadline(call))
        assertNull(callWarning(call))
    }

    @Test
    fun `a transferFrom names whose tokens move`() {
        val data = bytes("23b872dd" + word(owner) + word(recipient) + word(BigInteger.valueOf(1_500_000)))
        val call = TxDecode.call(usdc, data, 1) as DecodedCall.Transfer
        assertEquals(owner, call.from)
        assertEquals("Move 1.5 USDC from 0xCD2a…D826 to 0xd8dA…6045", callHeadline(call))
    }

    @Test
    fun `transfer on an unlisted contract isn't read as an amount, since a pre-ERC-721 NFT's shares the selector`() {
        // CryptoKitties' transfer(to, kittyId=1234567): not "Send 1234567 units of token …".
        val data = bytes("a9059cbb" + word(recipient) + word(BigInteger.valueOf(1_234_567)))
        assertNull(TxDecode.call(unlisted, data, 1))
        // The generic sheet names the contract and the function, with no amount.
        assertEquals("Run token transfers on 0x1234…5678", sendTxHeadline(sendAsk(unlisted, data)))
    }

    @Test
    fun `an unlimited approve is UNLIMITED and a danger, a limited one a caution, a zero one a revoke`() {
        val max = bytes("095ea7b3" + word(spender) + word(TxDecode.MAX_UINT256))
        val unlimited = TxDecode.call(usdc, max, 1) as DecodedCall.Approve
        assertTrue(unlimited.unlimited)
        assertEquals("Allow 0x1111…0582 to spend UNLIMITED USDC", callHeadline(unlimited))
        assertEquals(WarningLevel.Danger, callWarning(unlimited)!!.level)

        val some = TxDecode.call(usdc, bytes("095ea7b3" + word(spender) + word(BigInteger.valueOf(5_000_000))), 1) as DecodedCall.Approve
        assertEquals("Allow 0x1111…0582 to spend up to 5 USDC", callHeadline(some))
        assertEquals(WarningLevel.Caution, callWarning(some)!!.level)

        val zero = TxDecode.call(usdc, bytes("095ea7b3" + word(spender) + word(BigInteger.ZERO)), 1) as DecodedCall.Approve
        assertEquals("Stop 0x1111…0582 from spending your USDC", callHeadline(zero))
        assertNull(callWarning(zero))
    }

    @Test
    fun `approve and transferFrom on an unlisted contract aren't read as ERC-20, since an NFT's share the selectors`() {
        // ERC-721 approve(op, tokenId=3): not "spend up to 3 units".
        assertNull(TxDecode.call(unlisted, bytes("095ea7b3" + word(spender) + word(BigInteger.valueOf(3))), 1))
        // An ENS-style huge token ID: not "UNLIMITED".
        assertNull(TxDecode.call(unlisted, bytes("095ea7b3" + word(spender) + word(TxDecode.MAX_UINT256)), 1))
        assertNull(TxDecode.call(unlisted, bytes("23b872dd" + word(owner) + word(recipient) + word(BigInteger.valueOf(7))), 1))
        // USDC's Ethereum address on Gnosis isn't a token the wallet lists there either.
        assertNull(TxDecode.call(usdc, bytes("095ea7b3" + word(spender) + word(BigInteger.TEN)), 100))
        // Nor is transfer, which pre-ERC-721 NFTs share (R5-M2).
        assertNull(TxDecode.call(unlisted, bytes("a9059cbb" + word(recipient) + word(BigInteger.TEN)), 1))
        // And the generic sheet names the contract.
        assertEquals(
            "Call contract 0x1234…5678 on Ethereum",
            sendTxHeadline(sendAsk(unlisted, bytes("095ea7b3" + word(spender) + word(BigInteger.valueOf(3))))),
        )
    }

    @Test
    fun `setApprovalForAll is a danger when it grants, nothing when it takes back`() {
        val on = TxDecode.call(unlisted, bytes("a22cb465" + word(spender) + word(BigInteger.ONE)), 1) as DecodedCall.ApproveAll
        assertEquals(WarningLevel.Danger, callWarning(on)!!.level)
        val off = TxDecode.call(unlisted, bytes("a22cb465" + word(spender) + word(BigInteger.ZERO)), 1) as DecodedCall.ApproveAll
        assertNull(callWarning(off))
        // A "bool" that isn't 0 or 1 isn't the standard encoding.
        assertNull(TxDecode.call(unlisted, bytes("a22cb465" + word(spender) + word(BigInteger.TWO)), 1))
    }

    @Test
    fun `only the exact standard encoding is decoded`() {
        val transfer = "a9059cbb" + word(recipient) + word(BigInteger.TEN)
        // Extra bytes the token would ignore, but the summary wouldn't describe.
        assertNull(TxDecode.call(usdc, bytes(transfer + "00".repeat(32)), 1))
        // Cut short.
        assertNull(TxDecode.call(usdc, bytes(transfer.dropLast(2)), 1))
        // An address word with dirty upper bytes.
        assertNull(TxDecode.call(usdc, bytes("a9059cbb" + "ff" + word(recipient).drop(2) + word(BigInteger.TEN)), 1))
        // Another function, and no function at all.
        assertNull(TxDecode.call(usdc, bytes("38ed1739" + word(recipient) + word(BigInteger.TEN)), 1))
        assertNull(TxDecode.call(usdc, ByteArray(0), 1))
    }

    @Test
    fun `a token is named only from the wallet's own list, on its own chain`() {
        val data = bytes("a9059cbb" + word(recipient) + word(BigInteger.valueOf(20_000_000)))
        // USDC's Ethereum address on Gnosis is no USDC the wallet knows.
        assertNull(TxDecode.call(usdc, data, 100))
        assertEquals("Send 20 USDC to 0xd8dA…6045", callHeadline(TxDecode.call(usdc, data, 1) as DecodedCall.Transfer))
    }

    // ---- Permits ----

    private fun field(name: String, type: String) = JSONObject().put("name", name).put("type", type)

    private fun domain(contract: String) = JSONArray()
        .put(field("name", "string")).put(field("chainId", "uint256")).put(field("verifyingContract", "address")) to
        JSONObject().put("name", "Token").put("chainId", 1).put("verifyingContract", contract)

    private fun typed(primary: String, types: JSONObject, message: JSONObject, contract: String = usdc): Eip712.TypedData {
        val (domainType, domainValue) = domain(contract)
        val json = JSONObject()
            .put("types", JSONObject(types.toString()).put("EIP712Domain", domainType))
            .put("primaryType", primary)
            .put("domain", domainValue)
            .put("message", message)
        return Eip712.parse(json.toString())
    }

    private val permitTypes = JSONObject().put(
        "Permit",
        JSONArray().put(field("owner", "address")).put(field("spender", "address")).put(field("value", "uint256"))
            .put(field("nonce", "uint256")).put(field("deadline", "uint256")),
    )

    private fun permit(value: BigInteger, deadline: Long) = typed(
        "Permit",
        permitTypes,
        JSONObject().put("owner", owner).put("spender", spender).put("value", value.toString()).put("nonce", 0).put("deadline", deadline),
    )

    @Test
    fun `an EIP-2612 permit for the uint256 maximum is UNLIMITED and a danger`() {
        val p = TxDecode.permit(permit(TxDecode.MAX_UINT256, now + 3600), 1)!!
        assertEquals(DecodedPermit.Kind.Allowance, p.kind)
        assertEquals(spender, p.spender)
        assertEquals("Allow 0x1111…0582 to spend UNLIMITED USDC", permitHeadline(p))
        assertEquals(WarningLevel.Danger, permitLevel(p, now))
        assertEquals(WarningLevel.Danger, permitWarning(p, now).level)
        assertTrue(permitWarning(p, now).text.startsWith("No limit"))
    }

    @Test
    fun `a limited, short-lived permit is a caution, a long-lived one a danger, one for 0 only info`() {
        val short = TxDecode.permit(permit(BigInteger.valueOf(20_000_000), now + 3600), 1)!!
        assertEquals("Allow 0x1111…0582 to spend up to 20 USDC", permitHeadline(short))
        assertEquals(WarningLevel.Caution, permitLevel(short, now))
        assertTrue(permitLines(short).first().startsWith("20 USDC, until it's used up or changed"))

        val long = TxDecode.permit(permit(BigInteger.valueOf(20_000_000), now + 365L * 24 * 3600), 1)!!
        assertEquals(WarningLevel.Danger, permitLevel(long, now))
        assertTrue(permitWarning(long, now).text.startsWith("Long-lived"))

        // A deadline of the uint256 maximum: never ends.
        val never = TxDecode.permit(permit(BigInteger.valueOf(20_000_000), 0).let {
            typed("Permit", permitTypes, JSONObject(it.message.toString()).put("deadline", TxDecode.MAX_UINT256.toString()))
        }, 1)!!
        assertNull(never.signatureDeadline)
        assertEquals(WarningLevel.Danger, permitLevel(never, now))
        assertTrue(permitLines(never).any { it == "This signature can be used until no end date" })

        val revoke = TxDecode.permit(permit(BigInteger.ZERO, now + 3600), 1)!!
        assertEquals(WarningLevel.Info, permitLevel(revoke, now))
    }

    @Test
    fun `a permit with another shape, a missing field or no signed contract isn't decoded`() {
        // A field's type changed: not EIP-2612's Permit.
        val wrongType = JSONObject(permitTypes.toString()).put(
            "Permit",
            JSONArray().put(field("owner", "address")).put(field("spender", "address")).put(field("value", "uint128"))
                .put(field("nonce", "uint256")).put(field("deadline", "uint256")),
        )
        assertNull(TxDecode.permit(typed("Permit", wrongType, permit(BigInteger.ONE, now).message), 1))
        // The value missing (the digest would hash it as 0).
        val missing = JSONObject(permit(BigInteger.ONE, now).message.toString()).apply { remove("value") }
        assertNull(TxDecode.permit(typed("Permit", permitTypes, missing), 1))
        // The domain declares no verifyingContract: the token isn't signed.
        val json = JSONObject()
            .put("types", JSONObject(permitTypes.toString()).put("EIP712Domain", JSONArray().put(field("name", "string"))))
            .put("primaryType", "Permit")
            .put("domain", JSONObject().put("name", "USD Coin").put("verifyingContract", usdc))
            .put("message", permit(BigInteger.ONE, now).message)
        assertNull(TxDecode.permit(Eip712.parse(json.toString()), 1))
    }

    @Test
    fun `DAI's permit is all or nothing, and an expiry of 0 never ends`() {
        val daiTypes = JSONObject().put(
            "Permit",
            JSONArray().put(field("holder", "address")).put(field("spender", "address")).put(field("nonce", "uint256"))
                .put(field("expiry", "uint256")).put(field("allowed", "bool")),
        )
        val dai = "0x6B175474E89094C44Da98b954EedeAC495271d0F"
        val p = TxDecode.permit(
            typed("Permit", daiTypes, JSONObject().put("holder", owner).put("spender", spender).put("nonce", 0).put("expiry", 0).put("allowed", true), dai),
            1,
        )!!
        assertEquals("Allow 0x1111…0582 to spend UNLIMITED DAI", permitHeadline(p))
        assertNull(p.signatureDeadline)
        assertEquals(WarningLevel.Danger, permitLevel(p, now))
    }

    private val permit2Types = JSONObject()
        .put(
            "PermitDetails",
            JSONArray().put(field("token", "address")).put(field("amount", "uint160")).put(field("expiration", "uint48")).put(field("nonce", "uint48")),
        )
        .put("PermitSingle", JSONArray().put(field("details", "PermitDetails")).put(field("spender", "address")).put(field("sigDeadline", "uint256")))

    private fun permit2(amount: BigInteger, expiration: Long, contract: String = TxDecode.PERMIT2) = typed(
        "PermitSingle",
        permit2Types,
        JSONObject()
            .put("details", JSONObject().put("token", usdc).put("amount", amount.toString()).put("expiration", expiration).put("nonce", 0))
            .put("spender", spender)
            .put("sigDeadline", now + 1800),
        contract,
    )

    @Test
    fun `a Permit2 single permit reads its token, amount and expiry, and the uint160 maximum is UNLIMITED`() {
        val p = TxDecode.permit(permit2(TxDecode.MAX_UINT160, now + 7 * 24 * 3600), 1)!!
        assertTrue(p.permit2)
        assertEquals("Allow 0x1111…0582 to spend UNLIMITED USDC", permitHeadline(p))
        assertEquals(WarningLevel.Danger, permitLevel(p, now))
        assertTrue(permitLines(p).contains("Through Permit2, an approval contract many apps share"))

        val limited = TxDecode.permit(permit2(BigInteger.valueOf(3_000_000), now + 7 * 24 * 3600), 1)!!
        assertEquals("Allow 0x1111…0582 to spend up to 3 USDC", permitHeadline(limited))
        assertEquals(WarningLevel.Caution, permitLevel(limited, now))
        // An allowance that lasts a year is long-lived even with a short signature deadline.
        val year = TxDecode.permit(permit2(BigInteger.valueOf(3_000_000), now + 365L * 24 * 3600), 1)!!
        assertEquals(WarningLevel.Danger, permitLevel(year, now))
    }

    @Test
    fun `Permit2's shapes count only at Permit2's own address`() {
        assertNull(TxDecode.permit(permit2(TxDecode.MAX_UINT160, now + 3600, contract = unlisted), 1))
    }

    @Test
    fun `a Permit2 batch and a one-time transfer are told apart`() {
        val batchTypes = JSONObject(permit2Types.toString()).apply { remove("PermitSingle") }
            .put("PermitBatch", JSONArray().put(field("details", "PermitDetails[]")).put(field("spender", "address")).put(field("sigDeadline", "uint256")))
        val details = JSONArray()
            .put(JSONObject().put("token", usdc).put("amount", "1000000").put("expiration", now + 3600).put("nonce", 0))
            .put(JSONObject().put("token", unlisted).put("amount", TxDecode.MAX_UINT160.toString()).put("expiration", now + 3600).put("nonce", 0))
        val batch = TxDecode.permit(
            typed("PermitBatch", batchTypes, JSONObject().put("details", details).put("spender", spender).put("sigDeadline", now + 600), TxDecode.PERMIT2),
            1,
        )!!
        assertEquals(2, batch.grants.size)
        assertEquals("Allow 0x1111…0582 to spend 2 tokens", permitHeadline(batch))
        assertEquals(WarningLevel.Danger, permitLevel(batch, now))

        val transferTypes = JSONObject()
            .put("TokenPermissions", JSONArray().put(field("token", "address")).put(field("amount", "uint256")))
            .put(
                "PermitTransferFrom",
                JSONArray().put(field("permitted", "TokenPermissions")).put(field("spender", "address")).put(field("nonce", "uint256"))
                    .put(field("deadline", "uint256")),
            )
        val once = TxDecode.permit(
            typed(
                "PermitTransferFrom",
                transferTypes,
                JSONObject().put("permitted", JSONObject().put("token", usdc).put("amount", "2500000")).put("spender", spender)
                    .put("nonce", 7).put("deadline", now + 600),
                TxDecode.PERMIT2,
            ),
            1,
        )!!
        assertEquals(DecodedPermit.Kind.OneTime, once.kind)
        assertEquals("Let 0x1111…0582 take up to 2.5 USDC from this account, once", permitHeadline(once))
        assertEquals(WarningLevel.Caution, permitLevel(once, now))
    }

    // ---- Warning levels on the sheets (W24) ----

    private val site = "https://app.example"
    private val account = WalletAccount(0, "Account 1", owner)

    private fun sendAsk(to: String, data: ByteArray, repriced: Boolean = false, replaces: String? = null): EthAsk.SendTransaction {
        val chain = BuiltInChains.ETHEREUM
        val request = SendRequest(chain, TokenRegistry.native(chain), account, to, BigInteger.ZERO, DappCall(site, data, null))
        val tx = EthTransaction(
            chainId = chain.id,
            nonce = BigInteger.valueOf(7),
            gasLimit = BigInteger.valueOf(50_000),
            to = to,
            value = BigInteger.ZERO,
            data = data,
            fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000_000_000), BigInteger.ONE),
        )
        val trust = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
        return EthAsk.SendTransaction(site, SendQuote(request, tx, BigInteger.TEN.pow(18), null, 0, trust, replaces), repriced)
    }

    @Test
    fun `the add-network and switch notes are info, never red`() {
        val add = sheetWarnings(EthAsk.AddChain(site, BuiltInChains.BASE), now)
        assertEquals(listOf(WarningLevel.Info), add.map { it.level })
        val switch = sheetWarnings(EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM), now)
        assertEquals(listOf(WarningLevel.Info), switch.map { it.level })
        // With Freedom's RPCs picked the note says so; with the site's, it says what the site's see.
        val checked = EthAsk.AddChain(site, BuiltInChains.BASE.copy(rpcUrls = listOf("https://rpc.site.example")), checked = BuiltInChains.BASE)
        assertTrue(sheetWarnings(checked, now).single().text.startsWith("Freedom's RPCs"))
        assertTrue(sheetWarnings(checked, now, siteRpcs = true).single().text.startsWith("The site chose"))
    }

    @Test
    fun `a send's sheet - a repriced fee and a replaced send are cautions, an unlimited approve a danger, a plain transfer nothing`() {
        val transfer = bytes("a9059cbb" + word(recipient) + word(BigInteger.valueOf(20_000_000)))
        assertTrue(sheetWarnings(sendAsk(usdc, transfer), now).isEmpty())
        assertEquals("Send 20 USDC to 0xd8dA…6045", sendTxHeadline(sendAsk(usdc, transfer)))
        val levels = sheetWarnings(sendAsk(usdc, transfer, repriced = true, replaces = "0x" + "ab".repeat(32)), now).map { it.tag to it.level }
        assertEquals(listOf("repriced" to WarningLevel.Caution, "replaces" to WarningLevel.Caution), levels)
        val approve = bytes("095ea7b3" + word(spender) + word(TxDecode.MAX_UINT256))
        assertEquals(listOf(WarningLevel.Danger), sheetWarnings(sendAsk(usdc, approve), now).map { it.level })
        // A call the wallet can't read is named by its contract, not "0 ETH".
        assertEquals("Call contract 0xA0b8…eB48 on Ethereum", sendTxHeadline(sendAsk(usdc, bytes("38ed1739" + word(BigInteger.ONE)))))
    }

    @Test
    fun `typed data the wallet can't read is a caution, so is a hex message, a readable message only info`() {
        val unknown = EthAsk.SignTypedData(site, account, BuiltInChains.ETHEREUM, true, "App", null, "Order", "{}")
        assertEquals(listOf(WarningLevel.Caution), sheetWarnings(unknown, now).map { it.level })
        val hex = EthAsk.SignMessage(site, account, null, "0x00ff")
        assertEquals(listOf(WarningLevel.Caution, WarningLevel.Info), sheetWarnings(hex, now).map { it.level })
        val text = EthAsk.SignMessage(site, account, "hello", "0x68656c6c6f")
        assertEquals(listOf(WarningLevel.Info), sheetWarnings(text, now).map { it.level })
    }

    @Test
    fun `an unlimited permit on the sheet is a danger card`() {
        val p = TxDecode.permit(permit(TxDecode.MAX_UINT256, now + 3600), 1)!!
        val ask = EthAsk.SignTypedData(site, account, BuiltInChains.ETHEREUM, true, "USD Coin", usdc, "Permit", "{}", permit = p)
        assertEquals(listOf(WarningLevel.Danger), sheetWarnings(ask, now).map { it.level })
    }
}
