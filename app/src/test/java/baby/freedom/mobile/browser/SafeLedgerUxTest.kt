package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.Erc20
import baby.freedom.mobile.wallet.SafeAccount
import baby.freedom.mobile.wallet.SafePending
import baby.freedom.mobile.wallet.SafeProtocol
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Safe, Ledger and remote-signing pages' row models and rules (#424: W33–W39). */
class SafeLedgerUxTest {
    private val mine1 = WalletAccount(0, "Account 1", "0x" + "11".repeat(20))
    private val mine2 = WalletAccount(1, "Account 2", "0x" + "22".repeat(20))
    private val other = "0x" + "33".repeat(20)
    private val safe = SafeAccount("0x" + "aa".repeat(20), "Team", listOf(mine1.address, mine2.address, other), 2, "1", 100, true, 0)
    private val recipient = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"

    private fun pending(
        id: String,
        signers: List<String> = emptyList(),
        kind: SafePending.Kind = SafePending.Kind.TX,
        createdAt: Long = 0,
        superseded: Boolean = false,
        execHash: String? = null,
        payment: SafePending.Payment? = null,
        text: String? = null,
    ) = SafePending(
        id = id,
        safe = safe.address,
        kind = kind,
        chainId = 100,
        typedData = "{}",
        threshold = 2,
        signatures = signers.map { SafeProtocol.OwnerSignature(it, "0x") },
        createdAt = createdAt,
        payment = payment,
        text = text,
        execHash = execHash,
        superseded = superseded,
    )

    // W33

    @Test fun `an item an owner here hasn't signed needs you, one only others can finish doesn't`() {
        assertTrue(safeNeedsYou(pending("a"), safe, listOf(mine1, mine2)))
        // Account 1 signed, Account 2 hasn't: still needs this wallet.
        assertTrue(safeNeedsYou(pending("a", listOf(mine1.address)), safe, listOf(mine1, mine2)))
        // Only Account 1 is in this wallet and it signed: waiting for the other device.
        assertFalse(safeNeedsYou(pending("a", listOf(mine1.address)), safe, listOf(mine1)))
        // No owner here at all.
        assertFalse(safeNeedsYou(pending("a"), safe, emptyList()))
    }

    @Test fun `a ready transaction needs you to execute it, unless it's going out or can't`() {
        val ready = listOf(mine1.address, other)
        assertTrue(safeNeedsYou(pending("a", ready), safe, listOf(mine1)))
        assertFalse(safeNeedsYou(pending("a", ready, execHash = "0x01"), safe, listOf(mine1)))
        assertFalse(safeNeedsYou(pending("a", superseded = true), safe, listOf(mine1)))
        // A signed message has nothing left to do here.
        assertFalse(safeNeedsYou(pending("a", ready, kind = SafePending.Kind.MESSAGE, text = "hi"), safe, listOf(mine1)))
    }

    @Test fun `pending items split into needs-you and the rest, oldest first`() {
        val waiting = pending("w", listOf(mine1.address), createdAt = 1)
        val late = pending("late", createdAt = 3)
        val early = pending("early", createdAt = 2)
        val groups = safePendingGroups(listOf(late, waiting, early), safe, listOf(mine1))
        assertEquals(listOf("early", "late"), groups.needsYou.map { it.id })
        assertEquals(listOf("w"), groups.others.map { it.id })
    }

    @Test fun `Send says why it can't start`() {
        assertNull(safeSendBlocked(safe, chainKnown = true, hasTx = false))
        assertEquals("Send works once the Safe is active.", safeSendBlocked(safe.copy(deployed = false), chainKnown = true, hasTx = false))
        assertEquals("One at a time: execute or discard the one waiting first", safeSendBlocked(safe, chainKnown = true, hasTx = true))
        assertEquals("Gnosis isn’t set up in Settings → Wallet & chains.", safeSendBlocked(safe, chainKnown = false, hasTx = false))
    }

    // W34

    @Test fun `a request leads with what it does, not its hash`() {
        val pay = SafePending.Payment(recipient, BigInteger("1500000000000000000"), "xDAI", 18, null)
        assertEquals("Send 1.5 xDAI to 0xd8dA…6045 from Team", safeRequestHeadline(pending("a", payment = pay), "Team"))
        assertEquals("A transaction from Team", safeRequestHeadline(pending("a"), "Team"))
        assertEquals("Team signs “gm”", safeRequestHeadline(pending("a", kind = SafePending.Kind.MESSAGE, text = "gm"), "Team"))
    }

    // W35

    @Test fun `one Sign button signs as the account picked, else the first`() {
        assertEquals(mine1, safeCoSigner(listOf(mine1, mine2), null))
        assertEquals(mine2, safeCoSigner(listOf(mine1, mine2), mine2.address.uppercase().replace("0X", "0x")))
        // A pick that isn't an owner any more falls back to the first.
        assertEquals(mine1, safeCoSigner(listOf(mine1, mine2), other))
        assertNull(safeCoSigner(emptyList(), null))
    }

    private fun txRequest(to: String, value: BigInteger, data: ByteArray, chainId: Long = 100) = SafeProtocol.Request.Tx(
        safe = safe.address,
        chainId = chainId,
        tx = SafeProtocol.SafeTx(to, value, data, BigInteger.ZERO),
        typedData = JSONObject(),
        hash = ByteArray(32),
    )

    @Test fun `a co-sign request leads with what signing lets the Safe do`() {
        val gnosis = BuiltInChains.GNOSIS
        assertEquals(
            "Send 2 xDAI to 0xd8dA…6045 from 0xaaaa…aaaa",
            safeCoSignHeadline(txRequest(recipient, BigInteger.TWO.multiply(BigInteger.TEN.pow(18)), ByteArray(0)), gnosis, null),
        )
        val xbzz = "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da"
        val transfer = Erc20.transferData(recipient, BigInteger.TEN.pow(16))
        assertEquals("Send 1 xBZZ to 0xd8dA…6045 from 0xaaaa…aaaa", safeCoSignHeadline(txRequest(xbzz, BigInteger.ZERO, transfer), gnosis, null))
        assertEquals(
            "Call contract 0xd8dA…6045 from Safe 0xaaaa…aaaa",
            safeCoSignHeadline(txRequest(recipient, BigInteger.ZERO, byteArrayOf(1, 2, 3, 4)), gnosis, null),
        )
        val self = txRequest(safe.address, BigInteger.ZERO, ByteArray(0))
        assertEquals("Cancel a waiting transaction of Safe 0xaaaa…aaaa", safeCoSignHeadline(self, gnosis, SafeSelfCall.Cancel))
        assertEquals("Change the settings of Safe 0xaaaa…aaaa", safeCoSignHeadline(self, gnosis, SafeSelfCall.Unknown))
        // A chain this phone doesn't have: the raw amount, never a guessed currency.
        assertEquals(
            "Send 5 base units to 0xd8dA…6045 from 0xaaaa…aaaa",
            safeCoSignHeadline(txRequest(recipient, BigInteger.valueOf(5), ByteArray(0), chainId = 77), null, null),
        )
    }

    // W36

    @Test fun `Create says what's missing, an owner from this wallet first`() {
        assertEquals(SafeCreateBlocker.NoLocalOwner, safeCreateBlocker(local = 0, owners = 2, needed = 2))
        assertEquals(SafeCreateBlocker.NoLocalOwner, safeCreateBlocker(local = 0, owners = 0, needed = 3))
        assertEquals(SafeCreateBlocker.ChooseMore(2), safeCreateBlocker(local = 1, owners = 1, needed = 3))
        assertNull(safeCreateBlocker(local = 1, owners = 3, needed = 3))
        assertEquals("Choose an owner from this wallet", safeCreateLabel(SafeCreateBlocker.NoLocalOwner))
        assertEquals("Choose 1 more owner", safeCreateLabel(SafeCreateBlocker.ChooseMore(1)))
        assertEquals("Choose 2 more owners", safeCreateLabel(SafeCreateBlocker.ChooseMore(2)))
        assertEquals("Create", safeCreateLabel(null))
    }

    // W37

    @Test fun `the amount is checked against the Safe's balance as it's typed`() {
        val held = BigInteger.valueOf(100)
        assertNull(safeAmountProblem("", null, held))
        assertNull(safeAmountProblem("1", BigInteger.valueOf(100), held))
        assertEquals(SafeAmountProblem.TOO_MUCH, safeAmountProblem("1", BigInteger.valueOf(101), held))
        // Not read yet: nothing to check against, so no complaint.
        assertNull(safeAmountProblem("1", BigInteger.valueOf(101), null))
        assertEquals(SafeAmountProblem.INVALID, safeAmountProblem("1.2.3", null, held))
        assertEquals(SafeAmountProblem.ZERO, safeAmountProblem("0", null, held))
        assertEquals(SafeAmountProblem.ZERO, safeAmountProblem("0,00", null, held))
        assertEquals(SafeAmountProblem.INVALID, safeAmountProblem("0.0.0", null, held))
    }

    // W38: regression — "Try again" after a failed Add did nothing.

    @Test fun `a failed Add never turns the list's footer into a Try again with nothing to read`() {
        // Five accounts read, all asked for: the Add failure is the Add bar's own error.
        assertFalse(ledgerNeedsRead(found = 5, wanted = 5))
        assertEquals(LedgerFooter.SHOW_MORE, ledgerFooter(loadError = null, found = 5))
    }

    @Test fun `Try again shows only for a failed read, and then there is something to read`() {
        assertEquals(LedgerFooter.TRY_AGAIN, ledgerFooter(loadError = "Unlock your Ledger", found = 0))
        assertTrue(ledgerNeedsRead(found = 0, wanted = 5))
        // A failed Show more: the next page is still wanted, so Try again reads it.
        assertEquals(LedgerFooter.TRY_AGAIN, ledgerFooter(loadError = "Lost the connection", found = 5))
        assertTrue(ledgerNeedsRead(found = 5, wanted = 10))
        assertEquals(LedgerFooter.NONE, ledgerFooter(loadError = null, found = 0))
    }

    // W39

    @Test fun `the first account not already in the wallet is picked`() {
        val found = listOf("44'/60'/0'/0/0" to mine1.address, "44'/60'/1'/0/0" to other, "44'/60'/2'/0/0" to recipient)
        assertEquals(found[1], ledgerFirstNew(found, setOf(mine1.address.lowercase())))
        assertEquals(found[0], ledgerFirstNew(found, emptySet()))
        assertNull(ledgerFirstNew(found.take(1), setOf(mine1.address.lowercase())))
        assertNull(ledgerFirstNew(emptyList(), emptySet()))
    }
}
