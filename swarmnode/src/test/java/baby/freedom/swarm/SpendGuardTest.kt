package baby.freedom.swarm

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendGuardTest {
    private val amount = BigInteger("4325218560")
    private val maxSwap = BigInteger("50000000000000000") // 0.05 xDAI
    private val milliBzz = BigInteger.TEN.pow(13) // 0.001 xBZZ: a depth-17 plan's deposit (ant's floor)
    private val buy = SpendPlan.BuyStamp(
        TestTx.OWNER, 17, amount, immutable = false, maxSwapWei = maxSwap, settlementDepositPlur = milliBzz, chequebook = null,
    )
    private val batch = "ab".repeat(32)
    private val extend = SpendPlan.ExtendStamp(TestTx.OWNER, batch, 18, amount, maxSwap)

    private fun SpendPermit.admits(raw: ByteArray) = admit(TestTx.request(raw))

    @Test
    fun nothingGetsOutWithoutAPermit() {
        assertFalse(SpendGuard.admit(TestTx.request(TestTx.createBatch(TestTx.OWNER, amount, 17, false))))
        assertFalse(SpendGuard.admit(null))
        assertFalse(SpendGuard.admit("""{"jsonrpc":"2.0","id":1,"method":"eth_sendTransaction","params":[{}]}"""))
    }

    @Test
    fun aBuyAdmitsAntsWholeFlowOnce() {
        val p = SpendPermit(buy)
        assertTrue(p.admits(TestTx.swap(maxSwap)))
        assertTrue(p.admits(TestTx.approve(amount.shiftLeft(17))))
        assertTrue(p.admits(TestTx.createBatch(TestTx.OWNER, amount, 17, false)))
        assertTrue(p.admits(TestTx.deployChequebook()))
        assertTrue(p.admits(TestTx.transfer("cc".repeat(20), BigInteger.TEN.pow(13))))
        // Each kind once: a second batch, even the same one, doesn't get out.
        assertFalse(p.admits(TestTx.createBatch(TestTx.OWNER, amount, 17, false, batchNonce = 0x44)))
        assertFalse(p.admits(TestTx.swap(BigInteger.ONE)))
        assertFalse(p.admits(TestTx.approve(amount.shiftLeft(17), nonce = 12)))
    }

    @Test
    fun theSameSignedTransactionIsAdmittedAgain() {
        // ant retrying one broadcast is the same spend, not a second one.
        val p = SpendPermit(buy)
        val tx = TestTx.createBatch(TestTx.OWNER, amount, 17, false)
        assertTrue(p.admits(tx))
        assertTrue(p.admit(TestTx.request(tx, id = 8)))
    }

    @Test
    fun aBuyRefusesAnythingItDidntConfirm() {
        fun refused(raw: ByteArray) = assertFalse(SpendPermit(buy).admits(raw))
        // Another depth, amount, owner, bucket depth or mutability.
        refused(TestTx.createBatch(TestTx.OWNER, amount, 18, false))
        refused(TestTx.createBatch(TestTx.OWNER, amount.add(BigInteger.ONE), 17, false))
        refused(TestTx.createBatch("22".repeat(20), amount, 17, false))
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, false, bucketDepth = 17))
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, true))
        // An approval of more, or to someone else.
        refused(TestTx.approve(amount.shiftLeft(18)))
        refused(TestTx.approve(amount.shiftLeft(17), spender = "33".repeat(20)))
        // A swap of more xDAI than confirmed, or for someone else.
        refused(TestTx.swap(maxSwap.add(BigInteger.ONE)))
        refused(TestTx.swap(maxSwap, recipient = "44".repeat(20)))
        // xDAI sent anywhere but the swap helper; value on a contract call.
        refused(TestTx.tx("55".repeat(20), ByteArray(0), value = BigInteger.ONE))
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, false, value = BigInteger.ONE))
        // A settlement deposit over the confirmed 0.001 xBZZ; a chequebook for someone else.
        refused(TestTx.transfer("cc".repeat(20), BigInteger.TEN.pow(13).add(BigInteger.ONE)))
        refused(TestTx.deployChequebook(issuer = "66".repeat(20)))
        // A top-up, a dilute, a contract creation (the swap helper's deploy included).
        refused(TestTx.topUp(batch, amount))
        refused(TestTx.tx(SpendPermit.POSTAGE_STAMP, TestTx.call("3b0e2eb9", TestTx.bytes(batch), TestTx.word(BigInteger.valueOf(18)))))
        refused(TestTx.tx(null, ByteArray(40) { 1 }))
        refused(TestTx.tx("4e59b44847b379578588920ca78fbf26c0b4956c", ByteArray(32) + ByteArray(40) { 1 }))
        // Another chain; a pre-EIP-155 signature; a gas bill no ant transaction has.
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, false, v = 37)) // chain id 1
        refused(TestTx.approve(amount.shiftLeft(17), v = 27))
        refused(TestTx.approve(amount.shiftLeft(17), gasPrice = BigInteger.valueOf(20_000_000_000), gas = 1_000_000))
        // An address word with junk in its top bytes.
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("095ea7b3", ByteArray(11) + byteArrayOf(1) + TestTx.bytes(SpendPermit.POSTAGE_STAMP),
            TestTx.word(amount.shiftLeft(17)))))
    }

    @Test
    fun anExtendAdmitsOnlyItsOwnBatchAtItsDepth() {
        val p = SpendPermit(extend)
        assertFalse(p.admits(TestTx.topUp("cd".repeat(32), amount)))
        assertFalse(p.admits(TestTx.topUp(batch, amount.add(BigInteger.ONE))))
        assertFalse(p.admits(TestTx.approve(amount.shiftLeft(17))))
        assertFalse(p.admits(TestTx.createBatch(TestTx.OWNER, amount, 18, false)))
        assertFalse(p.admits(TestTx.deployChequebook()))
        assertFalse(p.admits(TestTx.transfer("cc".repeat(20), BigInteger.ONE)))
        assertTrue(p.admits(TestTx.swap(BigInteger.ONE)))
        assertTrue(p.admits(TestTx.approve(amount.shiftLeft(18))))
        assertTrue(p.admits(TestTx.topUp(batch, amount)))
        // Its one top-up is spent.
        assertFalse(p.admits(TestTx.topUp(batch, amount, nonce = 30)))
    }

    @Test
    fun onlyASingleWellFormedRawTransactionRequestCounts() {
        val raw = TestTx.hex(TestTx.approve(amount.shiftLeft(17)))
        assertEquals(raw, SpendPermit.rawTransaction("""{"jsonrpc":"2.0","id":1,"method":"eth_sendRawTransaction","params":["0x$raw"]}"""))
        assertEquals(raw, SpendPermit.rawTransaction("""{"id":1, "method" : "eth_sendRawTransaction", "params" : [ "0x${raw.uppercase()}" ]}"""))
        // A batch, a second method, extra params, another method, not hex.
        assertNull(SpendPermit.rawTransaction("""[{"jsonrpc":"2.0","id":1,"method":"eth_sendRawTransaction","params":["0x$raw"]}]"""))
        assertNull(SpendPermit.rawTransaction("""{"method":"eth_sendRawTransaction","params":["0x$raw"],"x":{"method":"eth_call"}}"""))
        assertNull(SpendPermit.rawTransaction("""{"method":"eth_sendRawTransaction","params":["0x$raw","0x00"]}"""))
        assertNull(SpendPermit.rawTransaction("""{"method":"eth_sendTransaction","params":["0x$raw"]}"""))
        assertNull(SpendPermit.rawTransaction("""{"method":"eth_sendRawTransaction","params":["0xzz"]}"""))
        // A typed (EIP-2718) transaction, trailing bytes, a truncated one, junk.
        val p = SpendPermit(buy)
        assertFalse(p.admit(TestTx.request(byteArrayOf(2) + TestTx.approve(amount.shiftLeft(17)))))
        assertFalse(p.admit(TestTx.request(TestTx.approve(amount.shiftLeft(17)) + byteArrayOf(0))))
        assertFalse(p.admit(TestTx.request(TestTx.approve(amount.shiftLeft(17)).dropLast(3).toByteArray())))
        assertFalse(p.admit("""{"method":"eth_sendRawTransaction","params":["0x"]}"""))
        assertFalse(p.admit("""{"method":"eth_sendRawTransaction","params":["0xc0"]}"""))
        // And the well-formed one does.
        assertTrue(p.admit(TestTx.request(TestTx.approve(amount.shiftLeft(17)))))
    }

    @Test
    fun decodesAntsLegacyTransaction() {
        val tx = LegacyTx.decode(TestTx.swap(BigInteger("123456789012345678")))!!
        assertEquals(SpendPermit.SWAP_HELPER, tx.to)
        assertEquals(BigInteger("123456789012345678"), tx.value)
        assertEquals(100L, tx.chainId)
        assertEquals(BigInteger.valueOf(2_000_000_000), tx.gasPrice)
        assertEquals(68, tx.data.size)
        // A creation has no recipient.
        assertNull(LegacyTx.decode(TestTx.tx(null, ByteArray(3)))!!.to)
    }

    @Test
    fun onePaymentAtATimeAndThePermitClosesWhateverHappens() {
        val raw = TestTx.request(TestTx.approve(amount.shiftLeft(17)))
        runCatching {
            SpendGuard.during(buy) {
                assertTrue(runCatching { SpendGuard.during(buy) { } }.isFailure)
                throw IllegalStateException("ant failed")
            }
        }
        assertFalse(SpendGuard.admit(raw))
        SpendGuard.during(buy) { assertTrue(SpendGuard.admit(raw)) }
        assertFalse(SpendGuard.admit(raw))
    }

    @Test
    fun theGasBoundCountsEveryTransactionASpendCanSend() {
        // Every kind of ant transaction, offered to a fresh permit of each plan:
        // what gets out is exactly slotsFor, so maxGasWei is the real gas bound.
        fun everything() = listOf(
            TestTx.swap(maxSwap),
            TestTx.approve(amount.shiftLeft(17)),
            TestTx.approve(amount.shiftLeft(18)),
            TestTx.createBatch(TestTx.OWNER, amount, 17, false),
            TestTx.topUp(batch, amount),
            TestTx.deployChequebook(),
            TestTx.transfer("cc".repeat(20), BigInteger.TEN.pow(13)),
        )
        for ((plan, isBuy) in listOf(buy to true, extend to false)) {
            val p = SpendPermit(plan)
            val slots = everything().mapNotNull { raw ->
                val slot = LegacyTx.decode(raw)?.let(p::slotFor)
                slot.takeIf { p.admits(raw) }
            }.toSet()
            assertEquals(SpendPermit.slotsFor(isBuy), slots)
        }
        assertEquals(BigInteger("50000000000000000"), SpendPermit.maxGasWei(buy = true)) // 5 × 0.01 xDAI
        assertEquals(BigInteger("30000000000000000"), SpendPermit.maxGasWei(buy = false)) // 3 × 0.01 xDAI

        // A deposit: of all of the above plus its own transfer, only that one.
        val p = SpendPermit(deposit)
        val slots = (everything() + TestTx.transfer(chequebook, depositAmount)).mapNotNull { raw ->
            val slot = LegacyTx.decode(raw)?.let(p::slotFor)
            slot.takeIf { p.admits(raw) }
        }.toSet()
        assertEquals(SpendPermit.DEPOSIT_SLOTS, slots)
        assertEquals(BigInteger("10000000000000000"), SpendPermit.DEPOSIT_MAX_GAS_WEI) // 1 × 0.01 xDAI
    }

    private val chequebook = "37".repeat(20)
    private val depositAmount = BigInteger.TEN.pow(13) // 0.001 xBZZ
    private val deposit = SpendPlan.DepositChequebook(TestTx.OWNER, chequebook, depositAmount)

    @Test
    fun aDepositAdmitsOneTransferOfTheConfirmedAmountToTheConfirmedChequebook() {
        val p = SpendPermit(deposit)
        assertTrue(p.admits(TestTx.transfer(chequebook, depositAmount)))
        // Once: a second deposit, even an identical one with another nonce, doesn't get out.
        assertFalse(p.admits(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount)), nonce = 10)))

        fun refused(raw: ByteArray) = assertFalse(SpendPermit(deposit).admits(raw))
        refused(TestTx.transfer("cc".repeat(20), depositAmount)) // another recipient
        refused(TestTx.transfer(chequebook, depositAmount.add(BigInteger.ONE))) // more
        refused(TestTx.transfer(chequebook, depositAmount.subtract(BigInteger.ONE))) // less
        refused(TestTx.approve(depositAmount, spender = chequebook)) // an allowance instead
        refused(TestTx.swap(BigInteger.ONE)) // no swap: the xBZZ is already there
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, false))
        refused(TestTx.deployChequebook())
        // A transfer that also carries xDAI, or goes to another token.
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount)), value = BigInteger.ONE))
        refused(TestTx.tx("dd".repeat(20), TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount))))
        // Another chain, a pre-EIP-155 signature, a high gas bill.
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount)), v = 37))
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount)), v = 27))
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount)), gasPrice = BigInteger.valueOf(200_000_000_000), gas = 100_000))
        // A dirty address word, and trailing data.
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", ByteArray(11) + byteArrayOf(1) + TestTx.bytes(chequebook), TestTx.word(depositAmount))))
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(depositAmount), TestTx.word(BigInteger.ONE))))
    }

    @Test
    fun aStampPermitAdmitsNoDepositBeyondItsSettlementSlot() {
        // A buy's settlement slot takes at most the confirmed deposit (here 0.001 xBZZ);
        // anything bigger — a deposit racing the buy — stays out.
        assertFalse(SpendPermit(buy).admits(TestTx.transfer(chequebook, depositAmount.add(BigInteger.ONE))))
        assertFalse(SpendPermit(extend).admits(TestTx.transfer(chequebook, depositAmount)))
    }

    private val connect = SpendPlan.ConnectBatch(TestTx.OWNER, depositAmount, chequebook = null)

    @Test
    fun aConnectAdmitsOnlyTheChequebookSetupOnce() {
        // #115: the wallet already bought the batch; connecting it may only set up
        // the node's chequebook, as a buy does.
        val p = SpendPermit(connect)
        assertTrue(p.admits(TestTx.deployChequebook()))
        assertTrue(p.admits(TestTx.transfer("cc".repeat(20), depositAmount)))
        assertFalse(p.admits(TestTx.tx(SpendPermit.CHEQUEBOOK_FACTORY, TestTx.call("15efd8a7", TestTx.addr(TestTx.OWNER), TestTx.word(BigInteger.ONE), ByteArray(32) { 6 }))))

        fun refused(raw: ByteArray) = assertFalse(SpendPermit(connect).admits(raw))
        refused(TestTx.swap(BigInteger.ONE)) // nothing to swap for
        refused(TestTx.approve(amount.shiftLeft(17)))
        refused(TestTx.createBatch(TestTx.OWNER, amount, 17, false)) // no second batch
        refused(TestTx.topUp(batch, amount))
        refused(TestTx.deployChequebook(issuer = "22".repeat(20))) // another issuer
        refused(TestTx.transfer("cc".repeat(20), depositAmount.add(BigInteger.ONE))) // more than the deposit
        refused(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr("cc".repeat(20)), TestTx.word(depositAmount)), value = BigInteger.ONE))

        // What a fresh connect permit lets out of every kind of ant transaction is exactly CONNECT_SLOTS.
        val slots = listOf(
            TestTx.swap(maxSwap), TestTx.approve(amount.shiftLeft(17)), TestTx.createBatch(TestTx.OWNER, amount, 17, false),
            TestTx.topUp(batch, amount), TestTx.deployChequebook(), TestTx.transfer("cc".repeat(20), depositAmount),
        ).let { all ->
            val fresh = SpendPermit(connect)
            all.mapNotNull { raw -> LegacyTx.decode(raw)?.let(fresh::slotFor)?.takeIf { fresh.admits(raw) } }.toSet()
        }
        assertEquals(SpendPermit.CONNECT_SLOTS, slots)
        assertEquals(BigInteger("20000000000000000"), SpendPermit.CONNECT_MAX_GAS_WEI) // 2 × 0.01 xDAI
    }

    // The settlement deposit scales with the plan (ant 0.5.68+): 0.1 xBZZ at depth 21,
    // 0.2 at 22. The permit carries the exact amount the purchase review showed.
    private val pointTwoBzz = BigInteger.TEN.pow(15).shiftLeft(1)
    private fun buyWith(deposit: BigInteger, chequebook: String? = null) =
        SpendPlan.BuyStamp(TestTx.OWNER, 22, amount, immutable = false, maxSwapWei = maxSwap, settlementDepositPlur = deposit, chequebook = chequebook)

    @Test
    fun theSettlementDepositAdmitsTheQuotedAmountAndNotACentMore() {
        for (plan in listOf(buyWith(pointTwoBzz), SpendPlan.ConnectBatch(TestTx.OWNER, pointTwoBzz, null))) {
            assertTrue(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), pointTwoBzz)))
            // Less is fine: the chequebook may already hold part of it.
            assertTrue(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), pointTwoBzz.subtract(milliBzz))))
            // A cent over (0.01 xBZZ), or one PLUR over, isn't.
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), pointTwoBzz.add(BigInteger.TEN.pow(14)))))
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), pointTwoBzz.add(BigInteger.ONE))))
        }
    }

    @Test
    fun aZeroQuotedDepositAdmitsNoDepositTransfer() {
        // The chequebook already holds the plan's target: the review showed no deposit.
        for (plan in listOf(buyWith(BigInteger.ZERO), SpendPlan.ConnectBatch(TestTx.OWNER, BigInteger.ZERO, null))) {
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), BigInteger.ONE)))
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), milliBzz)))
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), BigInteger.ZERO)))
        }
        // The rest of the buy still goes through.
        val p = SpendPermit(buyWith(BigInteger.ZERO))
        assertTrue(p.admits(TestTx.createBatch(TestTx.OWNER, amount, 22, false)))
    }

    @Test
    fun aPinnedChequebookRefusesAnyOtherRecipientAndASecondDeploy() {
        for (plan in listOf(buyWith(pointTwoBzz, chequebook), SpendPlan.ConnectBatch(TestTx.OWNER, pointTwoBzz, chequebook))) {
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), pointTwoBzz)))
            assertFalse(SpendPermit(plan).admits(TestTx.transfer("cc".repeat(20), BigInteger.ONE)))
            // The node has a chequebook: ant funds it and deploys none.
            assertFalse(SpendPermit(plan).admits(TestTx.deployChequebook()))
            val p = SpendPermit(plan)
            assertTrue(p.admits(TestTx.transfer(chequebook, pointTwoBzz)))
            // Once.
            assertFalse(p.admits(TestTx.tx(SpendPermit.BZZ_TOKEN, TestTx.call("a9059cbb", TestTx.addr(chequebook), TestTx.word(BigInteger.ONE)), nonce = 11)))
        }
        // Unpinned (a first buy deploys the chequebook it funds): any recipient, and the deploy.
        val first = SpendPermit(buyWith(pointTwoBzz))
        assertTrue(first.admits(TestTx.deployChequebook()))
        assertTrue(first.admits(TestTx.transfer("cc".repeat(20), pointTwoBzz)))
    }

    @Test
    fun theDepositNeverPassesAntsOneBzzCeiling() {
        val oneBzz = BigInteger.TEN.pow(16)
        assertEquals(oneBzz, SpendPermit.MAX_SETTLEMENT_DEPOSIT_PLUR)
        // A quote over ant's 1 xBZZ cap (which ant never sends) still moves at most 1 xBZZ.
        val over = buyWith(oneBzz.shiftLeft(1), chequebook)
        assertFalse(SpendPermit(over).admits(TestTx.transfer(chequebook, oneBzz.add(BigInteger.ONE))))
        assertFalse(SpendPermit(over).admits(TestTx.transfer(chequebook, oneBzz.shiftLeft(1))))
        assertTrue(SpendPermit(over).admits(TestTx.transfer(chequebook, oneBzz)))
        assertEquals(oneBzz, SpendPermit.settlementDepositCap(oneBzz.shiftLeft(1)))
        assertEquals(pointTwoBzz, SpendPermit.settlementDepositCap(pointTwoBzz))
        assertEquals(BigInteger.ZERO, SpendPermit.settlementDepositCap(BigInteger.ONE.negate()))
    }

    @Test
    fun aPlanRefusesANegativeDepositOrAMalformedChequebook() {
        fun throws(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
        throws { buyWith(BigInteger.ONE.negate()) }
        throws { buyWith(pointTwoBzz, "0x$chequebook") }
        throws { buyWith(pointTwoBzz, "AB".repeat(20)) }
        throws { SpendPlan.ConnectBatch(TestTx.OWNER, BigInteger.ONE.negate(), null) }
        throws { SpendPlan.ConnectBatch(TestTx.OWNER, pointTwoBzz, "37") }
    }

    @Test
    fun aChequebookWithdrawIsNeverAdmitted() {
        // ant 0.5.69 signs `withdraw(uint256)` to the chequebook (POST /chequebook/withdraw,
        // ant_storage_settlement_withdraw_amount). The app offers no withdraw, so no permit
        // admits one, and with no permit open nothing does.
        val withdraw = TestTx.withdraw(chequebook, depositAmount)
        assertFalse(SpendGuard.admit(TestTx.request(withdraw)))
        val plans = listOf(
            buy, extend, deposit, connect,
            buyWith(pointTwoBzz, chequebook), SpendPlan.ConnectBatch(TestTx.OWNER, pointTwoBzz, chequebook),
        )
        for (plan in plans) {
            val p = SpendPermit(plan)
            assertNull(LegacyTx.decode(withdraw)?.let(p::slotFor))
            assertFalse(p.admits(withdraw))
            assertFalse(p.admits(TestTx.withdraw(chequebook, BigInteger.ONE, nonce = 12)))
            assertFalse(p.admits(TestTx.withdraw("cc".repeat(20), depositAmount)))
        }
        // Inside a running spend too: the guard's own path refuses it.
        SpendGuard.during(deposit) {
            assertFalse(SpendGuard.admit(TestTx.request(withdraw)))
            assertTrue(SpendGuard.admit(TestTx.request(TestTx.transfer(chequebook, depositAmount))))
        }
    }
}
