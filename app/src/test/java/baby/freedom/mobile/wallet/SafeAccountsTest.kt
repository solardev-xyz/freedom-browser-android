package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.erc20Transfer
import baby.freedom.mobile.browser.SafeMovedOn
import baby.freedom.mobile.browser.safeAbandonedSpent
import baby.freedom.mobile.browser.safeDiscardText
import baby.freedom.mobile.browser.safeMovedOn
import baby.freedom.mobile.browser.safePendingState
import baby.freedom.mobile.browser.safePolicy
import baby.freedom.mobile.browser.safePolicyChangeNotice
import baby.freedom.mobile.browser.safePendingTitle
import baby.freedom.mobile.browser.safeRowSubtitle
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.toHex
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Safe accounts (#141): the records on disk tied to their vault, the
 * create rules, the one-pending-SafeTx slot, signatures checked before
 * they count, an activation or execution followed through the wallet's
 * own sends, and the chain reads behind "needs funds".
 */
class SafeAccountsTest {
    private val dir: File = Files.createTempDirectory("safes").toFile()
    private val file = File(dir, "wallet/safes.json")
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val vault = Vault(VaultTest.FakeStore(), scope, clock = { 1_000_000L }, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)
    private val auth = VaultTest.FakeAuth()
    private val abandon12 = Mnemonic.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")

    // Accounts 1 and 2 of the abandon phrase (desktop's derivation), and key 0x…01 on "another device".
    private val account1 = WalletAccount(0, "Account 1", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
    private val account2 = WalletAccount(1, "Account 2", "0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265")
    private val otherKey = ByteArray(32).also { it[31] = 1 }
    private val other = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
    private val strangerKey = ByteArray(32).also { it[31] = 2 }
    private val stranger = "0x2B5AD5c4795c026514f8317c7a215E218DcCD6cF"
    private val local = listOf(account1.address, account2.address)

    private fun safes() = SafeAccounts(vault, SafeStore(file), scope, Dispatchers.Unconfined, clock = { 42L })

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    private suspend fun opened(): SafeAccounts {
        vault.create(abandon12, auth, imported = true)
        return safes().also { it.reconcile(vault.state.value) }
    }

    private fun expectSafeError(block: suspend () -> Unit): String = runBlocking {
        try {
            block()
            fail("expected a SafeException")
            ""
        } catch (e: SafeException) {
            e.message!!
        }
    }

    @Test
    fun `a Safe is created free with a fresh salt, its predicted address, and kept for its own vault only`() = runBlocking {
        val s = opened()
        val safe = s.create("  Team  ", listOf(account1.address, account2.address.lowercase(), other), 2, local)
        assertEquals("Team", safe.name)
        assertEquals(listOf(account1.address, account2.address, other), safe.owners)
        assertEquals(SafeProtocol.predictAddress(safe.owners, 2, safe.saltNonce), safe.address)
        assertFalse(safe.deployed)
        // A second one gets another salt, so another address, even with the same owners.
        val again = s.create("", listOf(account1.address, account2.address, other), 2, local)
        assertFalse(again.address == safe.address)
        assertEquals("Safe 2", again.name)

        // On disk, public data only, read back as it was — and not for another wallet.
        val raw = file.readText()
        assertFalse(raw.contains("abandon"))
        val fresh = safes().also { it.reconcile(vault.state.value) }
        assertEquals(listOf(safe, again), fresh.state.value!!.safes)
        assertEquals(SafeState.EMPTY, SafeStore(file).read("someone-else"))
        // Frozen params that no longer give the address: the file isn't trusted at all.
        file.writeText(raw.replace(safe.saltNonce, "1"))
        assertNull(SafeStore(file).read(vault.identityTag()!!))
    }

    @Test
    fun `only 1 of 2 and 2 of 3, no repeated owner, at least one owner here, and no Safe owning a Safe`() = runBlocking<Unit> {
        val s = opened()
        assertTrue(expectSafeError { s.create("", listOf(account1.address, other), 2, local) }.contains("1 of 2 or 2 of 3"))
        assertTrue(expectSafeError { s.create("", listOf(account1.address, account1.address.lowercase()), 1, local) }.contains("twice"))
        assertTrue(expectSafeError { s.create("", listOf(other, stranger), 1, local) }.contains("in this wallet"))
        val safe = s.create("", listOf(account1.address, other), 1, local)
        assertTrue(expectSafeError { s.create("", listOf(account1.address, safe.address), 1, local) }.contains("Safe can’t own"))
    }

    @Test
    fun `a transaction needs an active Safe, and one waits at a time`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val pay = SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null)
        assertTrue(expectSafeError { s.proposeTx(safe, tx, pay) }.contains("Activate"))
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, tx, pay)
        assertEquals("0x" + SafeProtocol.hash(SafeProtocol.safeTxTypedData(safe.address, 100, tx)).toHex(), p.id)
        assertEquals(tx, p.safeTx())
        assertTrue(expectSafeError { s.proposeTx(safe, tx.copy(nonce = BigInteger.ONE), pay) }.contains("already waiting"))
        // Messages don't take the transaction's slot; the same words are the same message.
        val m = s.proposeMessage(safe, "hello safe")
        assertEquals(m, s.proposeMessage(safe, "hello safe"))
        assertEquals(2, s.state.value!!.pendingFor(safe.address).size)
        assertEquals("Send 0.000000000000000001 xDAI", safePendingTitle(p))
        assertEquals("Message: “hello safe”", safePendingTitle(m))
        assertEquals("1 of 2 owners must sign · 1 transaction waiting · 1 message waiting", safeRowSubtitle(s.state.value!!.safes[0], s.state.value!!.pending))
    }

    @Test
    fun `a message needs an active Safe too, whoever asks`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        // Its EIP-1271 check runs in the Safe's contract, which isn't there before activation.
        assertTrue(expectSafeError { s.proposeMessage(safe, "hello safe") }.contains("Activate"))
        assertTrue(s.state.value!!.pending.isEmpty())
        s.markDeployed(safe.address)
        assertEquals(SafePending.Kind.MESSAGE, s.proposeMessage(safe, "hello safe").kind)
    }

    @Test
    fun `another wallet never sees or inherits the last one’s Safes, even if the Empty in between was missed`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("Old", listOf(account1.address, other), 1, local)
        val oldTag = vault.identityTag()!!
        // The wallet goes and another comes, with no reconcile(Empty) seen in between (a conflated flow skipped it).
        vault.remove()
        vault.create(Mnemonic.parse("legal winner thank year wave sausage worth useful legal winner thank yellow"), auth, imported = true)
        assertFalse(vault.identityTag() == oldTag)
        // A change before any reconcile reads the new wallet's own list, not the old one's in memory.
        s.markDeployed(safe.address)
        assertEquals(SafeState.EMPTY, SafeStore(file).read(vault.identityTag()!!))
        assertTrue(s.state.value!!.safes.isEmpty())

        // And a reconcile on Unlocked/Locked reloads when the tag changed, though something was loaded.
        val t = safes().also { it.reconcile(vault.state.value) }
        t.create("New", listOf(other, account1.address), 1, local)
        vault.remove()
        vault.create(abandon12, auth, imported = true)
        t.reconcile(vault.state.value)
        assertTrue(t.state.value!!.safes.isEmpty())
    }

    @Test
    fun `signatures count only from owners, for exactly this request, once each`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, account2.address, other), 2, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.TEN, ByteArray(0), BigInteger.valueOf(3)), SafePending.Payment(other, BigInteger.TEN, "xDAI", 18, null))
        val hash = SafeProtocol.hash(JSONObject(p.typedData))
        assertEquals("0 of 2 signatures", safePendingState(p))

        // On this phone, with the wallet open.
        vault.unlock(auth)
        val signed = s.signWith(p.id, account1)
        assertTrue(signed.hasSigned(account1.address))
        assertEquals(account1.address, SafeProtocol.recoverSigner(hash, signed.signatures.single().data))

        // From another device: someone who isn't an owner, garbage, and a signature of something else are all refused.
        assertTrue(expectSafeError { s.addSignature(p.id, MessageSigning.sign(strangerKey, stranger, hash)) }.contains("isn’t an owner"))
        assertTrue(expectSafeError { s.addSignature(p.id, "0x1234") }.contains("130 hex digits"))
        val otherHash = ByteArray(32) { 7 }
        assertTrue(expectSafeError { s.addSignature(p.id, MessageSigning.sign(otherKey, other, otherHash)) }.contains("isn’t an owner"))

        val sig = MessageSigning.sign(otherKey, other, hash)
        val ready = s.addSignature(p.id, sig)
        assertTrue(ready.ready)
        assertEquals("2 of 2 signatures · ready to execute", safePendingState(ready))
        // The same owner twice is still one signature; v written as 0/1 is taken as 27/28.
        assertEquals(ready, s.addSignature(p.id, sig.dropLast(2) + "%02x".format(sig.takeLast(2).toInt(16) - 27)))
        // Kept across a restart.
        val fresh = safes().also { it.reconcile(vault.state.value) }
        assertEquals(ready, fresh.state.value!!.pending.single())
    }

    @Test
    fun `the Safe’s own sends are followed, so a mined activation marks it active, a mined execution clears its transaction`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("Team", listOf(account1.address, other), 1, local)
        val gnosis = BuiltInChains.GNOSIS
        fun status(data: ByteArray, to: String, activates: Boolean, stage: SendStatus.Stage, hash: String?) = SendStatus(
            SendQuote(
                SendRequest(gnosis, TokenRegistry.native(gnosis), account1, to, BigInteger.ZERO, DappCall(null, data, null, SafeCallLabel(safe.address, safe.name, activates))),
                EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(300_000), to, BigInteger.ZERO, data, EthTransaction.Fees.Legacy(BigInteger.ONE)),
                BigInteger.TEN, null, 0L, ChainTrustsForTest.unverified,
            ),
            stage,
            hash,
        )
        val deploy = SafeProtocol.deploymentData(safe.owners, 1, safe.saltNonce)
        s.noteSend(status(deploy, SafeProtocol.FACTORY, true, SendStatus.Stage.Pending, "0x" + "aa".repeat(32)))
        assertFalse(s.state.value!!.safes.single().deployed)
        s.noteSend(status(deploy, SafeProtocol.FACTORY, true, SendStatus.Stage.Confirmed(10, null), "0x" + "aa".repeat(32)))
        assertTrue(s.state.value!!.safes.single().deployed)

        val tx = SafeProtocol.SafeTx(account1.address, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(s.state.value!!.safes.single(), tx, SafePending.Payment(account1.address, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        val signed = s.signWith(p.id, account1)
        val exec = SafeProtocol.execTransactionData(tx, signed.signatures)
        val hash = "0x" + "bb".repeat(32)
        // Another Safe call to it (not this transaction's data) changes nothing.
        s.noteSend(status(byteArrayOf(1, 2), safe.address, false, SendStatus.Stage.Confirmed(11, null), hash))
        assertNull(s.state.value!!.pending.single().execHash)
        s.noteSend(status(exec, safe.address, false, SendStatus.Stage.Pending, hash))
        assertEquals(hash, s.state.value!!.pending.single().execHash)
        assertEquals("1 of 1 signatures · executing", safePendingState(s.state.value!!.pending.single()))
        // Reverted: its signatures stay, to try again.
        s.noteSend(status(exec, safe.address, false, SendStatus.Stage.Reverted(12, null), hash))
        assertNull(s.state.value!!.pending.single().execHash)
        s.noteSend(status(exec, safe.address, false, SendStatus.Stage.Confirmed(13, null), hash))
        assertTrue(s.state.value!!.pending.isEmpty())
    }

    @Test
    fun `a ready transaction takes no more signatures, so its execution still clears it once mined`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, account2.address, other), 2, local)
        s.markDeployed(safe.address)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(safe, tx, SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val hash = SafeProtocol.hash(JSONObject(p.typedData))
        vault.unlock(auth)
        s.signWith(p.id, account1)
        val ready = s.addSignature(p.id, MessageSigning.sign(otherKey, other, hash))
        assertTrue(ready.ready)
        val exec = SafeProtocol.execTransactionData(tx, ready.signatures)
        val gnosis = BuiltInChains.GNOSIS
        val sent = "0x" + "cc".repeat(32)
        fun status(stage: SendStatus.Stage) = SendStatus(
            SendQuote(
                SendRequest(gnosis, TokenRegistry.native(gnosis), account1, safe.address, BigInteger.ZERO, DappCall(null, exec, null, SafeCallLabel(safe.address, safe.name, false))),
                EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(300_000), safe.address, BigInteger.ZERO, exec, EthTransaction.Fees.Legacy(BigInteger.ONE)),
                BigInteger.TEN, null, 0L, ChainTrustsForTest.unverified,
            ),
            stage,
            sent,
        )
        s.noteSend(status(SendStatus.Stage.Pending))
        assertEquals(sent, s.state.value!!.pending.single().execHash)
        // The third owner, here on this phone, signing while it's out changes nothing …
        assertEquals(s.state.value!!.pending.single(), s.signWith(p.id, account2))
        assertEquals(ready.signatures, s.state.value!!.pending.single().signatures)
        // … so the mined execution still matches the entry and clears it.
        s.noteSend(status(SendStatus.Stage.Confirmed(20, null)))
        assertTrue(s.state.value!!.pending.isEmpty())
    }

    private fun execStatus(safe: SafeAccount, data: ByteArray, activates: Boolean, stage: SendStatus.Stage, hash: String): SendStatus {
        val gnosis = BuiltInChains.GNOSIS
        val to = if (activates) SafeProtocol.FACTORY else safe.address
        return SendStatus(
            SendQuote(
                SendRequest(gnosis, TokenRegistry.native(gnosis), account1, to, BigInteger.ZERO, DappCall(null, data, null, SafeCallLabel(safe.address, safe.name, activates))),
                EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(300_000), to, BigInteger.ZERO, data, EthTransaction.Fees.Legacy(BigInteger.ONE)),
                BigInteger.TEN, null, 0L, ChainTrustsForTest.unverified,
            ),
            stage,
            hash,
        )
    }

    @Test
    fun `Stop tracking an execution frees its transaction, and another send taking its place does too`() = runBlocking<Unit> {
        vault.create(abandon12, auth, imported = true)
        val sends = MutableStateFlow<SendStatus?>(null)
        suspend fun publish(v: SendStatus?) {
            sends.value = v
            yield()
        }
        val s = safes().also { it.start(sends) }
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(safe, tx, SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        val signed = s.signWith(p.id, account1)
        val exec = SafeProtocol.execTransactionData(tx, signed.signatures)
        val first = "0x" + "d1".repeat(32)
        for (stage in listOf(SendStatus.Stage.Pending, SendStatus.Stage.Unconfirmed, SendStatus.Stage.Failed("gone?", true))) {
            // It went out, then was left pending, unconfirmed, or may-have-gone …
            publish(execStatus(safe, exec, false, SendStatus.Stage.Pending, first))
            publish(execStatus(safe, exec, false, stage, first))
            assertEquals(first, s.state.value!!.pending.single().execHash)
            // Stop tracking: the sender publishes null. Nothing is being followed, so Discard opens up again —
            // but the abandoned execution can still land, and Discard says so.
            publish(null)
            assertNull(s.state.value!!.pending.single().execHash)
            assertEquals(listOf(SafePending.AbandonedExec(first, account1.address, BigInteger.ONE)), s.state.value!!.pending.single().abandonedExecs)
            assertTrue(safeDiscardText(s.state.value!!.pending.single()).contains("can still be mined and make this payment"))
        }
        // It's on disk: a fresh process reads the warning back.
        assertEquals(s.state.value!!.pending.single(), SafeStore(file).read(vault.identityTag()!!)!!.pending.single())
        // A different send replacing it (the abandoned nonce reused) frees it just the same.
        publish(execStatus(safe, exec, false, SendStatus.Stage.Pending, first))
        publish(execStatus(safe, byteArrayOf(9), false, SendStatus.Stage.Pending, "0x" + "d2".repeat(32)))
        assertNull(s.state.value!!.pending.single().execHash)
        // A later execution's hash isn't cleared by the end of an earlier one.
        s.noteExecution(p.id, "0x" + "d3".repeat(32))
        s.noteDropped(execStatus(safe, exec, false, SendStatus.Stage.Pending, first))
        assertEquals("0x" + "d3".repeat(32), s.state.value!!.pending.single().execHash)
        s.discard(p.id)
        assertTrue(s.state.value!!.pending.isEmpty())
    }

    @Test
    fun `a Ledger owner signs on its Ledger, with the typed data and its digest, the vault staying locked`() = runBlocking<Unit> {
        vault.create(abandon12, auth, imported = true)
        val ledgerOwner = WalletAccount(7, "Ledger 1", other, baby.freedom.mobile.wallet.ledger.LedgerKey("44'/60'/0'/0/0", "AA:BB", "Nano X"))
        var asked: Pair<Eip712.TypedData, ByteArray>? = null
        val s = SafeAccounts(vault, SafeStore(file), scope, Dispatchers.Unconfined, clock = { 42L }, ledgerSign = { account, data, digest ->
            assertEquals(ledgerOwner, account)
            asked = data to digest
            MessageSigning.sign(otherKey, other, digest)
        }).also { it.reconcile(vault.state.value) }
        val safe = s.create("", listOf(other, stranger), 1, listOf(other))
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(stranger, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(stranger, BigInteger.ONE, "xDAI", 18, null))
        vault.lock()
        val signed = s.signWith(p.id, ledgerOwner)
        assertTrue(signed.ready)
        assertEquals("SafeTx", asked!!.first.primaryType)
        assertTrue(asked!!.second.contentEquals(SafeProtocol.hash(JSONObject(p.typedData))))
        // A seed owner still needs the vault open.
        try {
            s.ownerSignature(account1, p.typedData)
            fail("expected VaultLockedException")
        } catch (e: VaultLockedException) {
        }
    }

    @Test
    fun `a transaction marked superseded while an abandoned execution's receipt lagged settles as executed once it's known`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val h1 = "0x" + "c1".repeat(32)
        s.noteExecution(p.id, h1, account1.address, BigInteger.valueOf(3))
        s.clearExecution(p.id, h1, abandoned = true)
        // The Safe's nonce moved on and h1's receipt wasn't known yet: marked superseded.
        assertEquals(SafeMovedOn.SUPERSEDED, safeMovedOn(s.state.value!!.pending.single()) { null })
        s.markSuperseded(p.id)
        val marked = s.state.value!!.pending.single()
        assertTrue(marked.superseded)
        // Its abandoned executions are kept, so the page's later look finds h1 did land.
        assertEquals(listOf(h1), marked.abandonedExecs.map { it.hash })
        assertEquals(SafeMovedOn.EXECUTED, safeMovedOn(marked) { if (it == h1) true else null })
    }

    @Test
    fun `every abandoned execution is kept, so an earlier one that got mined still counts as executed`() = runBlocking<Unit> {
        vault.create(abandon12, auth, imported = true)
        val sends = MutableStateFlow<SendStatus?>(null)
        suspend fun publish(v: SendStatus?) {
            sends.value = v
            yield()
        }
        val s = safes().also { it.start(sends) }
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(safe, tx, SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        val exec = SafeProtocol.execTransactionData(tx, s.signWith(p.id, account1).signatures)
        val h1 = "0x" + "a1".repeat(32)
        val h2 = "0x" + "a2".repeat(32)
        // Execute → Stop tracking → Execute again (the same account nonce) → Stop tracking.
        publish(execStatus(safe, exec, false, SendStatus.Stage.Pending, h1))
        publish(null)
        publish(execStatus(safe, exec, false, SendStatus.Stage.Pending, h2))
        publish(null)
        val entry = s.state.value!!.pending.single()
        assertEquals(listOf(h1, h2), entry.abandonedExecs.map { it.hash })
        assertEquals(entry, SafeStore(file).read(vault.identityTag()!!)!!.pending.single())
        // h1 is the one mined; h2 never will be: the transaction executed, it wasn't superseded.
        assertEquals(SafeMovedOn.EXECUTED, safeMovedOn(entry) { if (it == h1) true else null })
        assertEquals(SafeMovedOn.EXECUTED, safeMovedOn(entry) { if (it == h2) true else null })
        assertEquals(SafeMovedOn.SUPERSEDED, safeMovedOn(entry) { null })
        assertEquals(SafeMovedOn.SUPERSEDED, safeMovedOn(entry) { false })
        // One still going out whose receipt isn't known yet: not settled either way.
        assertEquals(SafeMovedOn.UNKNOWN, safeMovedOn(entry.copy(execHash = "0x" + "a3".repeat(32))) { null })
        // The list is bounded; the newest are the ones kept.
        repeat(SafePending.MAX_ABANDONED + 3) { i ->
            val h = "0x" + "%064x".format(i + 16)
            s.noteExecution(p.id, h, account1.address, BigInteger.valueOf(i.toLong()))
            s.clearExecution(p.id, h, abandoned = true)
        }
        val many = s.state.value!!.pending.single().abandonedExecs
        assertEquals(SafePending.MAX_ABANDONED, many.size)
        assertEquals(BigInteger.valueOf(SafePending.MAX_ABANDONED + 2L), many.last().nonce)
        assertEquals(account1.address, many.last().from)
    }

    @Test
    fun `Discard stops warning about an abandoned execution once its account nonce is used`() {
        val a = SafePending.AbandonedExec("0x" + "b1".repeat(32), "0x" + "11".repeat(20), BigInteger.valueOf(5))
        val p = SafePending(
            "0x" + "00".repeat(32), "0x" + "22".repeat(20), SafePending.Kind.TX, 100, "{}", 1, emptyList(), 0L,
            abandonedExecs = listOf(a),
        )
        assertTrue(safeDiscardText(p).contains("can still be mined"))
        // Five mined: nonce 5 is still free, so it can still land.
        assertFalse(safeAbandonedSpent(a.nonce!!, BigInteger.valueOf(5)))
        // Not known (the read failed): still warn.
        assertFalse(safeAbandonedSpent(a.nonce!!, null))
        // Six mined: another send took nonce 5, so it can't.
        assertTrue(safeAbandonedSpent(a.nonce!!, BigInteger.valueOf(6)))
        assertFalse(safeDiscardText(p, live = emptyList()).contains("still be mined"))
        // Two still live: both named.
        val b = a.copy(hash = "0x" + "b2".repeat(32))
        assertTrue(safeDiscardText(p.copy(abandonedExecs = listOf(a, b))).let { it.contains("0xb1b1b1b1") && it.contains("0xb2b2b2b2") && it.contains("any one of them") })
    }

    @Test
    fun `a record from before the list reads its one abandoned execution back`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val tag = vault.identityTag()!!
        val o = JSONObject(file.readText())
        val entry = o.getJSONArray("pending").getJSONObject(0)
        entry.remove("abandonedExecs")
        entry.remove("execFrom")
        entry.remove("execNonce")
        entry.put("abandonedExec", "0x" + "c1".repeat(32))
        file.writeText(o.toString())
        assertEquals(listOf(SafePending.AbandonedExec("0x" + "c1".repeat(32))), SafeStore(file).read(tag)!!.pending.single().abandonedExecs)
    }

    @Test
    fun `a reverted execution is settled, so Discard carries no still-may-land warning`() = runBlocking<Unit> {
        vault.create(abandon12, auth, imported = true)
        val sends = MutableStateFlow<SendStatus?>(null)
        suspend fun publish(v: SendStatus?) {
            sends.value = v
            yield()
        }
        val s = safes().also { it.start(sends) }
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(safe, tx, SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        val exec = SafeProtocol.execTransactionData(tx, s.signWith(p.id, account1).signatures)
        val hash = "0x" + "d4".repeat(32)
        publish(execStatus(safe, exec, false, SendStatus.Stage.Pending, hash))
        publish(execStatus(safe, exec, false, SendStatus.Stage.Reverted(10, null), hash))
        publish(null)
        val entry = s.state.value!!.pending.single()
        assertNull(entry.execHash)
        assertTrue(entry.abandonedExecs.isEmpty())
        assertFalse(safeDiscardText(entry).contains("still be mined"))
    }

    @Test
    fun `a mined activation or execution published before the Safes are read is applied once they are`() = runBlocking<Unit> {
        val safe = opened().create("", listOf(account1.address, other), 1, local)
        val deploy = SafeProtocol.deploymentData(safe.owners, 1, safe.saltNonce)
        // A fresh process: the journal brings back the mined activation before safes.json is read.
        val s = safes()
        s.noteSend(execStatus(safe, deploy, true, SendStatus.Stage.Confirmed(10, null), "0x" + "e1".repeat(32)))
        assertNull(s.state.value)
        val sends = MutableStateFlow<SendStatus?>(execStatus(safe, deploy, true, SendStatus.Stage.Confirmed(10, null), "0x" + "e1".repeat(32)))
        s.start(sends)
        assertTrue(s.state.value!!.safes.single().deployed)
    }

    @Test
    fun `a superseded transaction takes no more signatures, and removing the wallet takes the Safes with it`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        s.markSuperseded(p.id)
        val hash = SafeProtocol.hash(JSONObject(p.typedData))
        assertTrue(expectSafeError { s.addSignature(p.id, MessageSigning.sign(otherKey, other, hash)) }.contains("can no longer"))
        assertEquals("0 of 1 signatures · can no longer execute", safePendingState(s.state.value!!.pending.single()))
        s.remove(safe.address)
        assertTrue(s.state.value!!.safes.isEmpty() && s.state.value!!.pending.isEmpty())
        s.create("", listOf(account1.address, other), 1, local)
        vault.remove()
        s.reconcile(vault.state.value)
        assertNull(s.state.value)
        assertFalse(file.exists())
    }

    @Test
    fun `chain reads give deployed, nonce, owners, and activation's price against what the payer holds`() = runBlocking<Unit> {
        var code = "0x"
        var balance = BigInteger.valueOf(1_000_000)
        val owners = listOf(account1.address, other)
        val rpc = fakeRpc { method, params ->
            when (method) {
                "eth_getCode" -> "\"$code\""
                "eth_call" -> when (params.getJSONObject(0).getString("data")) {
                    SafeProtocol.NONCE_CALL -> "\"0x" + "5".padStart(64, '0') + "\""
                    SafeProtocol.OWNERS_CALL -> "\"0x" + listOf(32, 2).joinToString("") { it.toString(16).padStart(64, '0') } +
                        owners.joinToString("") { "0".repeat(24) + it.substring(2).lowercase() } + "\""
                    else -> "\"0x\""
                }
                "eth_estimateGas" -> "\"0x3d090\"" // 250 000
                "eth_getBlockByNumber" -> """{"number":"0x10","baseFeePerGas":"0x1"}"""
                "eth_maxPriorityFeePerGas" -> "\"0x1\""
                "eth_getBalance" -> "\"0x${balance.toString(16)}\""
                else -> "null"
            }
        }
        val chain = SafeChain(rpc)
        val safe = SafeAccount(SafeProtocol.predictAddress(owners, 1, "7"), "", owners, 1, "7", 100, false, 0)
        assertFalse(chain.deployed(100, safe.address))
        code = "0x6080"
        assertTrue(chain.deployed(100, safe.address))
        assertEquals(BigInteger.valueOf(5), chain.nonce(100, safe.address))
        assertEquals(owners, chain.owners(100, safe.address))
        val a = chain.activation(safe, account1.address)
        // 250 000 + 20%, at the oracle's cap (twice the base fee plus its floor tip).
        assertEquals(BigInteger.valueOf(300_000), a.maxFee / GasOracle(rpc).fees(100).maxPerGas)
        assertTrue(a.needsFunds)
        assertEquals(a.maxFee - balance, a.shortfall)
        balance = a.maxFee
        assertFalse(chain.activation(safe, account1.address).needsFunds)
        assertEquals(account1, SafeChain.executor(safe, listOf(account2, account1)))
        assertNull(SafeChain.executor(safe, listOf(account2)))
    }

    @Test
    fun `a snapshot reads the Safe's nonce, owners, modules and balance at one block`() = runBlocking<Unit> {
        // R2-F1: separate "latest" reads can land on nodes at different heights; every snapshot read names one block.
        val owners = listOf(account1.address, other)
        val blocks = mutableListOf<String>()
        val rpc = fakeRpc { method, params ->
            when (method) {
                "eth_blockNumber" -> "\"0x2a\""
                "eth_call" -> {
                    blocks += params.getString(1)
                    when (params.getJSONObject(0).getString("data")) {
                        SafeProtocol.NONCE_CALL -> "\"0x" + "5".padStart(64, '0') + "\""
                        SafeProtocol.OWNERS_CALL -> "\"0x" + listOf(32, 2).joinToString("") { it.toString(16).padStart(64, '0') } +
                            owners.joinToString("") { "0".repeat(24) + it.substring(2).lowercase() } + "\""
                        SafeProtocol.MODULES_CALL -> "\"0x" + listOf(64, 1, 0).joinToString("") { it.toString(16).padStart(64, '0') } + "\""
                        else -> "\"0x\""
                    }
                }
                "eth_getBalance" -> {
                    blocks += params.getString(1)
                    "\"0x10\""
                }
                else -> "null"
            }
        }
        val safe = SafeProtocol.predictAddress(owners, 1, "7")
        val s = SafeChain(rpc).snapshot(100, safe, withBalance = true)
        assertEquals(42L, s.block)
        assertEquals(BigInteger.valueOf(5), s.nonce)
        assertEquals(owners, s.owners)
        assertEquals(emptyList<String>(), s.modules)
        assertEquals(BigInteger.valueOf(16), s.balance)
        assertEquals(List(4) { "0x2a" }, blocks)
        blocks.clear()
        assertNull(SafeChain(rpc).snapshot(100, safe).balance)
        assertEquals(List(3) { "0x2a" }, blocks)
    }

    @Test
    fun `a threshold raised on chain is followed, so one signature no longer reads as ready to execute`() = runBlocking<Unit> {
        // #341: a 1-of-2 Safe that executed changeThreshold(2). The record said 1, so one local signature
        // offered an Execute that would revert (GS020) at the executor's expense.
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val m = s.proposeMessage(safe, "hello safe")
        vault.unlock(auth)
        assertTrue(s.signWith(p.id, account1).ready)
        assertTrue(s.signWith(m.id, account1).ready)

        val change = s.applyOnChain(safe.address, reading(listOf(account1.address, other), 2, block = 101))
        assertEquals(SafePolicyChange(thresholdBefore = 1, thresholdNow = 2, ownersChanged = false, droppedSignatures = 0), change)
        assertEquals("This Safe now needs 2 signatures.", safePolicyChangeNotice(change!!))
        val st = s.state.value!!
        // Every pending item counts against the new threshold: neither the transaction nor the message is ready.
        assertEquals(listOf(2, 2), st.pendingFor(safe.address).map { it.threshold })
        assertFalse(st.pendingFor(safe.address).any { it.ready })
        assertEquals("1 of 2 signatures", safePendingState(st.pending.first { it.id == p.id }))
        // The init params stay (the address is still theirs); what the chain says is kept beside them.
        val now = st.safe(safe.address)!!
        assertEquals(1, now.threshold)
        assertEquals(2, now.thresholdNow)
        assertEquals(safe.owners, now.ownersNow)
        assertEquals("2 of 2 owners must sign", safePolicy(now))
        // Kept across a restart, and the record still passes the address check.
        val fresh = safes().also { it.reconcile(vault.state.value) }
        assertEquals(st, fresh.state.value)
        // The second owner's signature from another device makes it ready again.
        val hash = SafeProtocol.hash(JSONObject(p.typedData))
        assertTrue(s.addSignature(p.id, MessageSigning.sign(otherKey, other, hash)).ready)
        // Read again with nothing new: nothing to tell.
        assertNull(s.applyOnChain(safe.address, reading(listOf(other, account1.address), 2, block = 102)))
        // Lowered back to what it was created with: the record is the init params again.
        assertEquals("This Safe now needs 1 signature.", safePolicyChangeNotice(s.applyOnChain(safe.address, reading(listOf(account1.address, other), 1, block = 103))!!))
        assertNull(s.state.value!!.safe(safe.address)!!.currentThreshold)
        assertNull(s.state.value!!.safe(safe.address)!!.currentOwners)
        assertTrue(s.state.value!!.pending.all { it.ready })
    }

    @Test
    fun `after an owner is swapped on chain, the new owner's signature counts and the old one's doesn't`() = runBlocking<Unit> {
        // #341: swapOwner(Account 1 → stranger) on a 2-of-3 Safe. The record still trusted Account 1's local
        // key and refused the stranger's signature as "not an owner".
        val s = opened()
        val safe = s.create("", listOf(account1.address, account2.address, other), 2, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val hash = SafeProtocol.hash(JSONObject(p.typedData))
        vault.unlock(auth)
        s.signWith(p.id, account1)
        assertTrue(expectSafeError { s.addSignature(p.id, MessageSigning.sign(strangerKey, stranger, hash)) }.contains("isn’t an owner"))

        val onChain = listOf(stranger, account2.address, other)
        val change = s.applyOnChain(safe.address, reading(onChain.map { it.lowercase() }, 2, block = 104))!!
        assertEquals(SafePolicyChange(2, 2, ownersChanged = true, droppedSignatures = 1), change)
        assertEquals(
            "This Safe’s owners have changed. The list here now matches the Safe. " +
                "1 signature was from an account that’s no longer an owner, so it no longer counts.",
            safePolicyChangeNotice(change),
        )
        val now = s.state.value!!.safe(safe.address)!!
        assertEquals(onChain, now.ownersNow)
        assertEquals(safe.owners, now.owners)
        assertTrue(s.state.value!!.pending.single().signatures.isEmpty())
        // Account 1 is no longer an owner: its key signs nothing for the Safe, and the next local owner pays.
        assertTrue(expectSafeError { s.signWith(p.id, account1) }.contains("isn’t an owner"))
        assertEquals(account2, SafeChain.executor(now, listOf(account1, account2)))
        // The new owner's signature counts, and with Account 2's it's ready.
        s.addSignature(p.id, MessageSigning.sign(strangerKey, stranger, hash))
        val ready = s.signWith(p.id, account2)
        assertTrue(ready.ready)
        assertEquals(2, ready.countedBy(onChain))
        assertEquals(1, ready.countedBy(safe.owners))
    }

    @Test
    fun `each pending item's page tells what it lost, whichever page applied the reading`() = runBlocking<Unit> {
        // #447 R3-M1: A, B, C at 2; a transaction signed by A and B, a message by A; swapOwner(A → stranger)
        // on another device. The message's page applied the reading first and said "2 signatures" (the
        // Safe's total), and the transaction's page — the item that went from ready to 1 of 2 — said nothing.
        val s = opened()
        val safe = s.create("", listOf(account1.address, account2.address, other), 2, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        val m = s.proposeMessage(safe, "hello safe")
        vault.unlock(auth)
        s.signWith(p.id, account1)
        assertTrue(s.signWith(p.id, account2).ready)
        s.signWith(m.id, account1)

        val total = s.applyOnChain(safe.address, reading(listOf(stranger, account2.address, other), 2, block = 120))!!
        assertEquals(2, total.droppedSignatures)
        // Each item keeps its own, on disk too.
        assertEquals(s.state.value, safes().also { it.reconcile(vault.state.value) }.state.value)
        // The message's page: its one signature.
        assertEquals(
            "This Safe’s owners have changed. The list here now matches the Safe. " +
                "1 signature was from an account that’s no longer an owner, so it no longer counts.",
            safePolicyChangeNotice(s.takePolicyChange(m.id)!!),
        )
        assertNull(s.takePolicyChange(m.id))
        // A later reading with nothing new adds nothing, and the transaction's page still says what it lost.
        assertNull(s.applyOnChain(safe.address, reading(listOf(stranger, account2.address, other), 2, block = 121)))
        val tx = s.takePolicyChange(p.id)!!
        assertEquals(SafePolicyChange(2, 2, ownersChanged = true, droppedSignatures = 1), tx)
        assertFalse(s.state.value!!.pending.first { it.id == p.id }.ready)
        assertNull(s.takePolicyChange(p.id))

        // Changes the page hasn't told yet add up: the first threshold, the last, every drop.
        s.applyOnChain(safe.address, reading(listOf(stranger, account2.address, other), 3, block = 122))
        s.applyOnChain(safe.address, reading(listOf(stranger, other), 1, block = 123))
        assertEquals(SafePolicyChange(2, 1, ownersChanged = true, droppedSignatures = 1), s.takePolicyChange(p.id))
        // Raised and lowered back with nothing dropped: nothing to tell.
        s.applyOnChain(safe.address, reading(listOf(stranger, other), 2, block = 124))
        s.applyOnChain(safe.address, reading(listOf(stranger, other), 1, block = 125))
        assertNull(s.takePolicyChange(p.id))
    }

    @Test
    fun `an execution going out keeps its signatures when the owners change, so it's still recognised once mined`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, account2.address, other), 2, local)
        s.markDeployed(safe.address)
        val tx = SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO)
        val p = s.proposeTx(safe, tx, SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        s.signWith(p.id, account1)
        val ready = s.signWith(p.id, account2)
        val exec = SafeProtocol.execTransactionData(tx, ready.signatures)
        val sent = "0x" + "cd".repeat(32)
        s.noteSend(execStatus(safe, exec, activates = false, SendStatus.Stage.Pending, sent))
        val change = s.applyOnChain(safe.address, reading(listOf(stranger, account2.address, other), 3, block = 105))!!
        assertEquals(0, change.droppedSignatures)
        assertEquals(ready.signatures, s.state.value!!.pending.single().signatures)
        assertEquals(3, s.state.value!!.pending.single().threshold)
        s.noteSend(execStatus(safe, exec, activates = false, SendStatus.Stage.Confirmed(20, null), sent))
        assertTrue(s.state.value!!.pending.isEmpty())
    }

    @Test
    fun `what the chain says must be a policy a Safe can have, here and on disk`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(account1.address, other), 3, block = 106)) }.contains("owner"))
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(account1.address, other), 0, block = 107)) }.contains("owner"))
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(emptyList(), 1, block = 108)) }.contains("owner"))
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(other, other.lowercase()), 1, block = 109)) }.contains("owner"))
        assertEquals(1, s.state.value!!.safe(safe.address)!!.thresholdNow)
        s.applyOnChain(safe.address, reading(listOf(account1.address, other), 2, block = 110))
        val raw = file.readText()
        // A threshold without its owner list, or one above it, means the file was changed: not trusted.
        file.writeText(raw.replace("\"currentOwners\":[\"${account1.address}\",\"$other\"]", "\"currentOwners\":null"))
        assertNull(SafeStore(file).read(vault.identityTag()!!))
        file.writeText(raw.replace("\"currentThreshold\":2", "\"currentThreshold\":5"))
        assertNull(SafeStore(file).read(vault.identityTag()!!))
        // A file from before #341 (no such fields) reads as the init params.
        file.writeText(raw.replace(",\"currentOwners\":[\"${account1.address}\",\"$other\"]", "").replace(",\"currentThreshold\":2", ""))
        assertEquals(1, SafeStore(file).read(vault.identityTag()!!)!!.safe(safe.address)!!.thresholdNow)
    }

    @Test
    fun `the Safe's owners, threshold and nonce are read at one block, and confirmed only when verified`() = runBlocking<Unit> {
        val owners = listOf(account1.address, other)
        var threshold = 2
        val blocks = mutableListOf<String>()
        val answer = { method: String, params: org.json.JSONArray ->
            when (method) {
                "eth_blockNumber" -> "\"0x2a\""
                "eth_call" -> {
                    blocks += params.getString(1)
                    when (params.getJSONObject(0).getString("data")) {
                        SafeProtocol.NONCE_CALL -> "\"0x" + "5".padStart(64, '0') + "\""
                        SafeProtocol.OWNERS_CALL -> "\"0x" + listOf(32, 2).joinToString("") { it.toString(16).padStart(64, '0') } +
                            owners.joinToString("") { "0".repeat(24) + it.substring(2).lowercase() } + "\""
                        SafeProtocol.THRESHOLD_CALL -> "\"0x" + threshold.toString(16).padStart(64, '0') + "\""
                        else -> "\"0x\""
                    }
                }
                else -> "null"
            }
        }
        val safe = SafeProtocol.predictAddress(owners, 1, "7")
        // Three providers agreeing: verified.
        val quorum = SafeChain(fakeRpc(listOf("https://a.example", "https://b.example", "https://c.example"), answer)).policy(100, safe)
        assertEquals(SafeChain.Policy(42, BigInteger.valueOf(5), owners, 2, confirmed = true), quorum)
        assertTrue(blocks.isNotEmpty() && blocks.all { it == "0x2a" })
        // One public RPC's word: read, but not confirmed — Execute stays off and the record stays as it is.
        assertFalse(SafeChain(fakeRpc(answer = answer)).policy(100, safe).confirmed)
        // An answer no Safe gives is no reading at all.
        threshold = 3
        assertTrue(expectSafeError { SafeChain(fakeRpc(answer = answer)).policy(100, safe) }.contains("owner"))
        threshold = 0
        assertTrue(expectSafeError { SafeChain(fakeRpc(answer = answer)).policy(100, safe) }.contains("owner"))
    }

    @Test
    fun `a reading pinned to one RPC's stale head isn't confirmed, however well the calls agree`() = runBlocking<Unit> {
        // #447 R1-F1: RPC a answers eth_blockNumber 0x10, from before changeThreshold(2) at block 0x20; b and c are
        // at the tip. Every RPC honestly answers the calls at 0x10 with threshold 1, so the three calls agree —
        // but the head they were pinned to is a's word alone, and the Safe has needed 2 since 0x20.
        val owners = listOf(account1.address, other)
        val safe = SafeProtocol.predictAddress(owners, 1, "7")
        val heads = mapOf("https://a.example" to 0x10L, "https://b.example" to 0x2aL, "https://c.example" to 0x2bL)
        fun router(heads: Map<String, Long>) = WalletRpc(
            ChainDataRouter(
                chains = { listOf<Chain>(BuiltInChains.GNOSIS.copy(rpcUrls = heads.keys.toList())) },
                transport = RpcTransport { url, body, _ ->
                    val req = JSONObject(body)
                    val params = req.getJSONArray("params")
                    val head = heads.getValue(url)
                    val result = when (req.getString("method")) {
                        "eth_blockNumber" -> "\"0x" + head.toString(16) + "\""
                        "eth_getBlockByNumber" ->
                            if (params.getString(0).substring(2).toLong(16) <= head) """{"number":"${params.getString(0)}"}""" else "null"
                        "eth_call" -> {
                            val at = params.getString(1).substring(2).toLong(16)
                            when (params.getJSONObject(0).getString("data")) {
                                SafeProtocol.NONCE_CALL -> "\"0x" + (if (at < 0x20) "5" else "6").padStart(64, '0') + "\""
                                SafeProtocol.OWNERS_CALL -> "\"0x" + listOf(32, 2).joinToString("") { it.toString(16).padStart(64, '0') } +
                                    owners.joinToString("") { "0".repeat(24) + it.substring(2).lowercase() } + "\""
                                SafeProtocol.THRESHOLD_CALL -> "\"0x" + (if (at < 0x20) 1 else 2).toString(16).padStart(64, '0') + "\""
                                else -> "\"0x\""
                            }
                        }
                        else -> "null"
                    }
                    """{"jsonrpc":"2.0","id":1,"result":$result}"""
                },
            ),
        )
        // The router pins to a's head; the calls at 0x10 agree everywhere, but the head is a's word and 0x18 exists.
        val reading = SafeChain(router(heads)).policy(100, safe)
        assertEquals(0x10L, reading.block)
        assertEquals(1, reading.threshold)
        assertFalse(reading.confirmed)
        // Heads a block apart at the tip, as honest RPCs are: the block past the window doesn't exist yet, so it's current.
        val tip = SafeChain(router(mapOf("https://a.example" to 0x2aL, "https://b.example" to 0x2bL, "https://c.example" to 0x2aL))).policy(100, safe)
        assertTrue(tip.confirmed)
        assertEquals(2, tip.threshold)
        // Every RPC stuck at the old head is a stale head too, agreed on: the calls and the head agree, so it's what
        // the chain says as far as anyone can tell — the record's own block floor is what refuses going back.
        val allOld = SafeChain(router(heads.mapValues { 0x10L })).policy(100, safe)
        assertEquals(1, allOld.threshold)
    }

    @Test
    fun `the record never goes back to an older reading, and a transaction the Safe is past isn't recounted`() = runBlocking<Unit> {
        val s = opened()
        val safe = s.create("", listOf(account1.address, other), 1, local)
        s.markDeployed(safe.address)
        val p = s.proposeTx(safe, SafeProtocol.SafeTx(other, BigInteger.ONE, ByteArray(0), BigInteger.ZERO), SafePending.Payment(other, BigInteger.ONE, "xDAI", 18, null))
        vault.unlock(auth)
        assertTrue(s.signWith(p.id, account1).ready)
        // changeThreshold(2) seen at block 50.
        assertNotNull(s.applyOnChain(safe.address, reading(listOf(account1.address, other), 2, block = 50)))
        assertEquals(50L, s.state.value!!.safe(safe.address)!!.checkedBlock)
        // A reading from block 40 (a server behind, or a stale head that slipped past the window) is refused.
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(account1.address, other), 1, block = 40)) }.contains("older block"))
        assertEquals(2, s.state.value!!.safe(safe.address)!!.thresholdNow)
        assertEquals(2, s.state.value!!.pending.single().threshold)
        // A reading from block 49 that says what the record says (an RPC one block behind) confirms it: no change,
        // nothing refused, and the floor stays at 50.
        val stateBefore = s.state.value
        assertNull(s.applyOnChain(safe.address, reading(listOf(other.lowercase(), account1.address), 2, block = 49)))
        assertEquals(stateBefore, s.state.value)
        assertEquals(50L, s.state.value!!.safe(safe.address)!!.checkedBlock)
        // But an older reading with the same threshold and other owners is still refused.
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(stranger, other), 2, block = 49)) }.contains("older block"))
        // The floor survives a restart.
        assertEquals(50L, safes().also { it.reconcile(vault.state.value) }.state.value!!.safe(safe.address)!!.checkedBlock)
        // Another device executed the transaction (nonce 0) with a swapOwner after it; the Safe's nonce is 2 now.
        // The item is the nonce guard's to settle: its signatures and threshold stay as they were.
        val before = s.state.value!!.pending.single()
        val change = s.applyOnChain(safe.address, reading(listOf(stranger, other), 1, block = 60, nonce = 2))!!
        assertEquals(0, change.droppedSignatures)
        assertEquals(before, s.state.value!!.pending.single())
        // An unconfirmed reading is never applied.
        assertTrue(expectSafeError { s.applyOnChain(safe.address, reading(listOf(account1.address, other), 1, block = 70).copy(confirmed = false)) }.contains("owner"))
    }

    @Test
    fun `a co-sign request is a scanned code of its own`() {
        val safe = SafeProtocol.predictAddress(listOf(account1.address, other), 1, "7")
        val td = SafeProtocol.safeTxTypedData(safe, 100, SafeProtocol.SafeTx(other, BigInteger.ONE, Erc20.transferData(other, BigInteger.TEN), BigInteger.ZERO))
        val code = ScannedCode.parse(td.toString())
        assertTrue(code is ScannedCode.SafeRequest)
        assertNotNull(SafeProtocol.parseRequest((code as ScannedCode.SafeRequest).json))
        val bad = ScannedCode.parse(JSONObject(td.toString()).also { it.getJSONObject("message").put("operation", 1) }.toString())
        assertTrue((bad as ScannedCode.Unrecognized).reason.contains("delegate call"))
        assertEquals(other to BigInteger.TEN, erc20Transfer(Erc20.transferData(other, BigInteger.TEN)))
        assertNull(erc20Transfer(ByteArray(4)))
    }

    /** A confirmed [SafeChain.policy] reading of [owners] / [threshold] at [block], the Safe's nonce [nonce]. */
    private fun reading(owners: List<String>, threshold: Int, block: Long, nonce: Long = 0) =
        SafeChain.Policy(block, BigInteger.valueOf(nonce), owners, threshold, confirmed = true)

    private fun fakeRpc(urls: List<String> = listOf("https://a.example"), answer: (String, org.json.JSONArray) -> String) = WalletRpc(
        ChainDataRouter(
            chains = { listOf<Chain>(BuiltInChains.GNOSIS.copy(rpcUrls = urls)) },
            transport = RpcTransport { _, body, _ ->
                val req = JSONObject(body)
                """{"jsonrpc":"2.0","id":1,"result":${answer(req.getString("method"), req.getJSONArray("params"))}}"""
            },
        ),
    )
}
