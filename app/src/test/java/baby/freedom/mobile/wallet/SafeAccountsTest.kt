package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.erc20Transfer
import baby.freedom.mobile.browser.safePendingState
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
import kotlinx.coroutines.runBlocking
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

    private fun fakeRpc(answer: (String, org.json.JSONArray) -> String) = WalletRpc(
        ChainDataRouter(
            chains = { listOf<Chain>(BuiltInChains.GNOSIS.copy(rpcUrls = listOf("https://a.example"))) },
            transport = RpcTransport { _, body, _ ->
                val req = JSONObject(body)
                """{"jsonrpc":"2.0","id":1,"result":${answer(req.getString("method"), req.getJSONArray("params"))}}"""
            },
        ),
    )
}
