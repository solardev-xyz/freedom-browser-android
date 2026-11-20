package baby.freedom.mobile.names

import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.names.NameContracts.Role
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameOperation.State
import baby.freedom.mobile.names.NameOperation.Step
import baby.freedom.mobile.wallet.WalletSender
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The registration and renewal state machine against a fake mainnet: the
 * commit/reveal timing, the journal written before anything is signed, an
 * unknown outcome settled from chain state only, and the checks that stop
 * a send (code hashes, availability, price, unverified reads).
 */
class NameRegistrarTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val someoneElse = "0x2222222222222222222222222222222222222222"

    /** A tiny mainnet: the name contracts' state, keyed by what the registrar asks. */
    private class FakeChain : NameReads {
        var block = 100L
        var timestamp = 1_800_000_000L
        val available = HashMap<String, Boolean>()
        val owner = HashMap<String, String>()
        val expiry = HashMap<String, BigInteger>()
        val commitments = HashMap<String, BigInteger>()
        val receipts = HashMap<String, JSONObject>()
        var base = BigInteger("3000000000000000")
        var premium = BigInteger.ZERO
        var nftFee = BigInteger("1000000000000000")
        var unverified = false
        var receiptsUnverified = false
        var balance: BigInteger = BigInteger.TEN.pow(18)
        var revertSimulation = false
        val tampered = HashSet<String>()
        val simulated = ArrayList<Pair<ByteArray, BigInteger?>>()

        fun code(address: String) = "0x" + address.removePrefix("0x").lowercase() + if (address.lowercase() in tampered) "ff" else ""

        val pinned: (Role) -> String = { role -> NameContracts.hex(Keccak256.digest(NameRegistrar.hexBytes("0x" + role.address.removePrefix("0x").lowercase()))) }

        private fun check() {
            if (unverified) refuse(NameException.Code.UNVERIFIED, "unverified")
        }

        override suspend fun latest(): NameChain.Snapshot {
            check()
            return NameChain.Snapshot(block, timestamp)
        }

        override suspend fun at(number: Long) = NameChain.Snapshot(number, timestamp)

        override suspend fun code(address: String, block: Long): String {
            check()
            return code(address)
        }

        override suspend fun receipt(hash: String): JSONObject? {
            if (receiptsUnverified) refuse(NameException.Code.UNVERIFIED, "unverified")
            return receipts[hash]
        }

        override suspend fun balance(address: String, block: Long): BigInteger = balance

        private fun word(v: BigInteger) = NameAbi.uint(v).bytes
        private fun ret(vararg words: ByteArray) = "0x" + words.joinToString("") { w -> w.joinToString("") { "%02x".format(it) } }
        private fun addr(a: String) = NameAbi.address(a).bytes
        private fun bool(b: Boolean) = word(if (b) BigInteger.ONE else BigInteger.ZERO)
        private fun str(s: String): String = "0x" + NameAbi.encode(listOf(NameAbi.string(s))).joinToString("") { "%02x".format(it) }

        override suspend fun call(to: String, data: ByteArray, block: Long, from: String?, value: BigInteger?): String {
            check()
            val sel = data.copyOfRange(0, 4)
            fun s(sig: String) = sel.contentEquals(NameAbi.selector(sig))
            val role = Role.byAddress(to) ?: error("unknown contract $to")
            if (from != null) {
                simulated += data to value
                if (revertSimulation) throw ChainRpcException.Rpc(3, "execution reverted", "0x")
                return "0x"
            }
            val words = NameAbi.words("0x" + data.copyOfRange(4, data.size).joinToString("") { "%02x".format(it) })!!
            val label = { NameAbi.dynamicString(words, 0)!! }
            return when {
                s("owner(bytes32)") -> ret(addr(owner["eth"] ?: NameContracts.ZERO_ADDRESS))
                s("nameExpires(uint256)") -> ret(word(expiry["eth"] ?: BigInteger.ZERO))
                s("ownerOf(uint256)") -> ret(addr(owner[if (role == Role.BASE) "eth" else role.name.lowercase()]!!))
                s("controllers(address)") -> ret(bool(true))
                s("available(string)") -> ret(bool(available["eth"] ?: true))
                s("valid(string)") -> ret(bool(label().length >= 3))
                s("rentPrice(string,uint256)") -> ret(word(base), word(premium))
                s("commitments(bytes32)") -> ret(word(commitments[NameContracts.hex(words[0])] ?: BigInteger.ZERO))
                s("minCommitmentAge()") -> ret(word(BigInteger.valueOf(60)))
                s("maxCommitmentAge()") -> ret(word(BigInteger.valueOf(86_400)))
                s("MIN_REGISTRATION_DURATION()") -> ret(word(BigInteger.valueOf(28 * 86_400)))
                s("isAvailable(string,uint256)") -> ret(bool(available[role.name.lowercase()] ?: true))
                s("records(uint256)") -> {
                    val key = role.name.lowercase()
                    val l = if (owner[key] != null) "freedom" else ""
                    val enc = NameAbi.encode(
                        listOf(NameAbi.string(l), NameAbi.uint(0), NameAbi.uint(expiry[key] ?: BigInteger.ZERO), NameAbi.uint(1), NameAbi.uint(0)),
                    )
                    "0x" + enc.joinToString("") { "%02x".format(it) }
                }
                s("getFee(uint256)") -> ret(word(nftFee))
                s("getPremium(uint256)") -> ret(word(premium))
                s("normalize(string)") -> str(label())
                s("computeId(string)") -> ret(word(NameContracts.parse(label()).tokenId))
                else -> error("unexpected call ${sel.joinToString("") { "%02x".format(it) }}")
            }
        }

        fun mine(hash: String, ok: Boolean = true) {
            block++
            receipts[hash] = JSONObject().put("transactionHash", hash).put("status", if (ok) "0x1" else "0x0").put("blockNumber", "0x" + block.toString(16))
        }
    }

    private object PlainSecrets : NameSecrets {
        override fun seal(secret: ByteArray): String = Base64.getEncoder().encodeToString(secret)
        override fun open(sealed: String): ByteArray = Base64.getDecoder().decode(sealed)
    }

    private val chain = FakeChain()
    private val journal = NameJournal.Memory()

    private fun registrar(j: NameJournal = journal) = NameRegistrar(
        chain, j, PlainSecrets, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        random = SecureRandom(), io = Dispatchers.Unconfined, pinned = chain.pinned,
    ).also { runBlocking { it.awaitLoaded() } }

    private val hash1 = "0x" + "a1".repeat(32)
    private val hash2 = "0x" + "b2".repeat(32)

    private fun sent(hash: String): suspend () -> WalletSender.Broadcast = { WalletSender.Broadcast.Sent(hash) }

    @Test
    fun `an eth registration commits, waits the minimum age, registers and checks the owner`() = runBlocking {
        val r = registrar()
        val check = r.check("freedom.eth", NameContracts.YEAR)
        assertTrue(check.canRegister)
        assertEquals(chain.base, check.priceWei)

        val op = r.start(check, Intent.REGISTER, me, 0)
        assertEquals(Step.COMMIT, op.step)
        assertEquals(State.READY, op.state)
        // The secret and commitment are on "flash" before anything can be signed.
        assertNotNull(journal.saved.single().encryptedSecret)
        assertNotNull(journal.saved.single().commitment)

        val commit = r.plan(op.id)
        assertEquals(Role.CONTROLLER.address, commit.to)
        assertEquals(BigInteger.ZERO, commit.value)
        assertArrayEquals(NameContracts.commitData(NameRegistrar.hexBytes(op.commitment!!)), commit.data)

        var stateWhenSigning: State? = null
        val d = r.dispatch(commit) {
            stateWhenSigning = journal.saved.single().state
            WalletSender.Broadcast.Sent(hash1)
        }
        assertEquals(NameRegistrar.Dispatched.Sent(hash1), d)
        // Marked dispatching on disk before the wallet had it.
        assertEquals(State.DISPATCHING, stateWhenSigning)
        assertEquals(State.PENDING, r.get(op.id)!!.state)

        // No receipt yet: nothing changes.
        r.refresh(op.id)
        assertEquals(State.PENDING, r.get(op.id)!!.state)

        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        var now = r.refresh(op.id)!!
        assertEquals(Step.REVEAL, now.step)
        assertEquals(State.WAITING_COMMITMENT, now.state)
        assertEquals(chain.timestamp + 60, now.readyAt)
        assertEquals(hash1, now.commitHash)
        try {
            r.plan(op.id)
            fail("planned a reveal before the commitment's minimum age")
        } catch (e: NameException) {
            assertEquals(NameException.Code.STALE, e.code)
        }

        // Judged by the block's time, not the phone's.
        chain.timestamp += 59
        assertEquals(State.WAITING_COMMITMENT, r.refresh(op.id)!!.state)
        chain.timestamp += 1
        now = r.refresh(op.id)!!
        assertEquals(State.READY, now.state)

        val reveal = r.plan(op.id)
        assertEquals(Step.REVEAL, reveal.step)
        assertEquals(NameContracts.withBuffer(chain.base), reveal.value)
        assertTrue(reveal.data.copyOfRange(0, 4).contentEquals(NameAbi.selector("register((string,address,uint256,bytes32,address,bytes[],uint8,bytes32))")))
        // Simulated from the paying account with the value it sends.
        assertEquals(reveal.value, chain.simulated.last().second)

        assertEquals(NameRegistrar.Dispatched.Sent(hash2), r.dispatch(reveal, sent(hash2)))
        chain.available["eth"] = false
        chain.owner["eth"] = me
        chain.expiry["eth"] = BigInteger.valueOf(chain.timestamp + NameContracts.YEAR)
        chain.mine(hash2)
        now = r.refresh(op.id)!!
        assertEquals(State.COMPLETED, now.state)
        assertNull("the secret goes once the name is registered", now.encryptedSecret)
        assertNull(journal.saved.single().encryptedSecret)
    }

    @Test
    fun `a commit the wallet may have sent is never sent again, and is settled from the chain`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        val plan = r.plan(op.id)
        val d = r.dispatch(plan) { WalletSender.Broadcast.Failed("no node answered", mayHaveGone = true, english = "no node answered") }
        assertEquals(NameRegistrar.Dispatched.Unknown, d)
        assertEquals(State.DISPATCH_UNKNOWN, r.get(op.id)!!.state)
        try {
            r.plan(op.id)
            fail("planned a resend of an unknown outcome")
        } catch (e: NameException) {
            assertEquals(NameException.Code.STALE, e.code)
        }
        try {
            r.dispatch(plan, sent(hash1))
            fail("dispatched an unknown outcome again")
        } catch (e: NameException) {
            assertEquals(NameException.Code.CONFLICT, e.code)
        }
        // Nothing on chain: it stays unknown.
        assertEquals(State.DISPATCH_UNKNOWN, r.refresh(op.id)!!.state)
        // The commitment shows up (Try again in the wallet sent the same bytes): on to the reveal.
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        val now = r.refresh(op.id)!!
        assertEquals(Step.REVEAL, now.step)
        assertEquals(State.WAITING_COMMITMENT, now.state)
    }

    @Test
    fun `a send the wallet didn't make leaves the step ready to review again`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        for (outcome in listOf(WalletSender.Broadcast.Rejected, WalletSender.Broadcast.Busy, WalletSender.Broadcast.Stale(false), WalletSender.Broadcast.Failed("refused", false, "refused"))) {
            val d = r.dispatch(r.plan(op.id)) { outcome }
            assertTrue(d is NameRegistrar.Dispatched.NotSent)
            assertEquals(State.READY, r.get(op.id)!!.state)
            assertNotNull(r.get(op.id)!!.message)
        }
    }

    @Test
    fun `a journal saved mid-dispatch reads back as unknown after a restart`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.gwei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        val disk = NameJournal.Memory()
        // What the file holds while the wallet has it.
        val dispatching = op.copy(state = State.DISPATCHING)
        val decoded = NameJournalCodec.decode(NameJournalCodec.encode(listOf(dispatching)))
        assertEquals(State.DISPATCH_UNKNOWN, decoded.single().state)
        assertEquals(op.commitment, decoded.single().commitment)
        assertEquals(op.encryptedSecret, decoded.single().encryptedSecret)
        disk.save(decoded)
        val restarted = registrar(disk)
        assertEquals(State.DISPATCH_UNKNOWN, restarted.get(op.id)!!.state)
    }

    @Test
    fun `a broken journal entry drops only itself`() {
        val r = registrar()
        val op = runBlocking { r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0) }
        val json = NameJournalCodec.encode(listOf(op, op.copy(id = "name_" + "0".repeat(24), name = "not a name")))
        assertEquals(listOf(op.id), NameJournalCodec.decode(json).map { it.id })
    }

    @Test
    fun `nothing starts when the journal can't be saved`() = runBlocking {
        val failing = NameJournal.Memory(failSaves = true)
        val r = registrar(failing)
        try {
            r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
            fail("started without saving")
        } catch (e: NameException) {
            assertEquals(NameException.Code.SECURE_STORAGE, e.code)
        }
        assertEquals(emptyList<NameOperation>(), r.operations.value)
    }

    @Test
    fun `a contract whose code changed is never written to`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        chain.tampered += Role.CONTROLLER.address.lowercase()
        try {
            r.plan(op.id)
            fail("planned against a changed contract")
        } catch (e: NameException) {
            assertEquals(NameException.Code.UNSUPPORTED, e.code)
        }
    }

    @Test
    fun `a read only one server vouched for is never acted on`() = runBlocking {
        val r = registrar()
        chain.unverified = true
        try {
            r.check("freedom.eth", NameContracts.YEAR)
            fail("checked on an unverified read")
        } catch (e: NameException) {
            assertEquals(NameException.Code.UNVERIFIED, e.code)
        }
    }

    @Test
    fun `a name taken before the commit is stale, and one the contract would refuse isn't sent`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        chain.revertSimulation = true
        try {
            r.plan(op.id)
            fail("planned a reverting call")
        } catch (e: NameException) {
            assertEquals(NameException.Code.STALE, e.code)
        }
        chain.revertSimulation = false
        val plan = r.plan(op.id)
        chain.available["eth"] = false
        var sentAnything = false
        try {
            r.dispatch(plan) {
                sentAnything = true
                WalletSender.Broadcast.Sent(hash1)
            }
            fail("dispatched for a taken name")
        } catch (e: NameException) {
            assertEquals(NameException.Code.STALE, e.code)
        }
        assertFalse(sentAnything)
        assertEquals(State.STALE, r.get(op.id)!!.state)
    }

    @Test
    fun `a reveal whose price rose during the review isn't sent and stays ready`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        r.refresh(op.id)
        chain.timestamp += 61
        assertEquals(State.READY, r.refresh(op.id)!!.state)
        val reveal = r.plan(op.id)
        assertEquals(NameContracts.withBuffer(chain.nftFee), reveal.value)
        chain.nftFee = chain.nftFee.add(BigInteger.ONE)
        var sentAnything = false
        try {
            r.dispatch(reveal) {
                sentAnything = true
                WalletSender.Broadcast.Sent(hash2)
            }
            fail("sent a reveal at a changed price")
        } catch (e: NameException) {
            assertEquals(NameException.Code.QUOTE_CHANGED, e.code)
        }
        assertFalse(sentAnything)
        assertEquals(State.READY, r.get(op.id)!!.state)
        // Quoted afresh, it goes.
        assertEquals(NameRegistrar.Dispatched.Sent(hash2), r.dispatch(r.plan(op.id), sent(hash2)))
    }

    @Test
    fun `a reverted reveal keeps its commitment, and a commitment past its age expires`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.gwei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        r.refresh(op.id)
        chain.timestamp += 61
        r.refresh(op.id)
        r.dispatch(r.plan(op.id), sent(hash2))
        chain.mine(hash2, ok = false)
        val back = r.refresh(op.id)!!
        assertEquals(State.READY, back.state)
        assertEquals(Step.REVEAL, back.step)
        assertNull(back.transactionHash)
        // NameNFT: still fine exactly at its maximum age, expired just past it.
        chain.timestamp = back.expiresAt!!
        assertEquals(State.READY, r.refresh(op.id)!!.state)
        chain.timestamp += 1
        assertEquals(State.EXPIRED, r.refresh(op.id)!!.state)
    }

    @Test
    fun `an eth commitment expires at its maximum age`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        val waiting = r.refresh(op.id)!!
        chain.timestamp = waiting.expiresAt!!
        assertEquals(State.EXPIRED, r.refresh(op.id)!!.state)
    }

    @Test
    fun `a wei renewal pays the fee plus the buffer and checks the expiry moved by a year`() = runBlocking {
        chain.available["wei"] = false
        chain.owner["wei"] = someoneElse
        val expiry = BigInteger.valueOf(chain.timestamp + 1000)
        chain.expiry["wei"] = expiry
        val r = registrar()
        val check = r.check("freedom.wei", NameContracts.YEAR)
        assertFalse(check.canRegister)
        assertTrue(check.canRenew)
        val op = r.start(check, Intent.RENEW, me, 0)
        assertEquals(Step.RENEW, op.step)
        val plan = r.plan(op.id)
        assertEquals(Role.WEI.address, plan.to)
        assertEquals(NameContracts.withBuffer(chain.nftFee), plan.value)
        assertArrayEquals(NameContracts.nftRenewData(NameContracts.parse("freedom.wei").tokenId), plan.data)
        r.dispatch(plan, sent(hash1))
        chain.mine(hash1)
        // Mined, but the expiry hasn't moved on the chain read: never "done", never resent.
        assertEquals(State.VERIFICATION_NEEDED, r.refresh(op.id)!!.state)
        chain.expiry["wei"] = expiry + BigInteger.valueOf(NameContracts.YEAR)
        val done = r.refresh(op.id)!!
        assertEquals(State.COMPLETED, done.state)
        // Renewing doesn't make the payer the owner.
        assertEquals(someoneElse, chain.owner["wei"])
    }

    @Test
    fun `an eth renewal goes through the controller with the term`() = runBlocking {
        chain.available["eth"] = false
        chain.owner["eth"] = me
        chain.expiry["eth"] = BigInteger.valueOf(chain.timestamp + 5000)
        val r = registrar()
        val op = r.start(r.check("freedom.eth", 2 * NameContracts.YEAR), Intent.RENEW, me, 0)
        val plan = r.plan(op.id)
        assertEquals(Role.CONTROLLER.address, plan.to)
        assertArrayEquals(NameContracts.renewData("freedom", 2 * NameContracts.YEAR), plan.data)
        // A name renewed by someone else meanwhile: what was checked no longer holds.
        chain.expiry["eth"] = chain.expiry["eth"]!! + BigInteger.TEN
        try {
            r.plan(op.id)
            fail("renewed a name that changed since its check")
        } catch (e: NameException) {
            assertEquals(NameException.Code.STALE, e.code)
        }
    }

    @Test
    fun `one open operation per name, one being handed to the wallet can't be dismissed, a sent one can`() = runBlocking {
        val r = registrar()
        val check = r.check("freedom.eth", NameContracts.YEAR)
        val op = r.start(check, Intent.REGISTER, me, 0)
        assertEquals(op.id, r.start(check, Intent.REGISTER, someoneElse, 1).id)
        r.dispatch(r.plan(op.id)) {
            try {
                r.dismiss(op.id)
                fail("dismissed while the wallet had it")
            } catch (e: NameException) {
                assertEquals(NameException.Code.CONFLICT, e.code)
            }
            WalletSender.Broadcast.Sent(hash1)
        }
        chain.mine(hash1, ok = false)
        assertEquals(State.FAILED, r.refresh(op.id)!!.state)
        r.dismiss(op.id)
        assertEquals(emptyList<NameOperation>(), r.operations.value)
        // A sent commit that never lands doesn't hold the name for good.
        val again = r.start(check, Intent.REGISTER, me, 0)
        r.dispatch(r.plan(again.id), sent(hash2))
        assertEquals(State.PENDING, r.get(again.id)!!.state)
        r.dismiss(again.id)
        assertEquals(emptyList<NameOperation>(), r.operations.value)
    }

    @Test
    fun `a sent step whose receipt no second server confirms settles from its effect on chain`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.receiptsUnverified = true
        chain.mine(hash1)
        // Mined, but only a lone server says so, and the commitment isn't seen: still pending.
        assertEquals(State.PENDING, r.refresh(op.id)!!.state)
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        val now = r.refresh(op.id)!!
        assertEquals(Step.REVEAL, now.step)
        assertEquals(State.WAITING_COMMITMENT, now.state)
        assertEquals(hash1, now.commitHash)
    }

    @Test
    fun `an account that can't pay is told what it holds, and nothing is marked stale`() = runBlocking {
        chain.available["wei"] = false
        chain.owner["wei"] = someoneElse
        chain.expiry["wei"] = BigInteger.valueOf(chain.timestamp + 1000)
        chain.balance = BigInteger.valueOf(1000)
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.RENEW, me, 0)
        try {
            r.plan(op.id)
            fail("planned a renewal the account can't pay")
        } catch (e: NameException) {
            assertEquals(NameException.Code.FUNDS, e.code)
            assertTrue(e.message!!.contains("0.00102 ETH"))
            assertTrue(e.message!!.contains("0.000000000000001 ETH"))
        }
        assertEquals(State.READY, r.get(op.id)!!.state)
        // A commit sends nothing, so needs only the fee (the wallet's own check).
        chain.available["gwei"] = true
        val commit = r.start(r.check("freedom.gwei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        assertEquals(BigInteger.ZERO, r.plan(commit.id).value)
    }

    @Test
    fun `removing the wallet forgets every operation and its secret`() = runBlocking {
        val r = registrar()
        r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.wipe()
        assertEquals(emptyList<NameOperation>(), r.operations.value)
        assertEquals(emptyList<NameOperation>(), journal.saved)
    }
}
