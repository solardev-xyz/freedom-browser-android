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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    private val chain = FakeNameChain()
    private val journal = NameJournal.Memory()

    private fun registrar(j: NameJournal = journal) = NameRegistrar(
        chain, j, PlainNameSecrets, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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
        assertEquals(NameRegistrar.Dispatched.Unknown(), d)
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
    fun `a reveal the wallet may have sent keeps its hash, so a revert settles it`() = runBlocking {
        val r = registrar()
        val op = r.start(r.check("freedom.gwei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        r.refresh(op.id)
        chain.timestamp += 61
        r.refresh(op.id)
        val d = r.dispatch(r.plan(op.id)) { WalletSender.Broadcast.Failed("no node answered", true, "no node answered", hash = hash2) }
        assertEquals(NameRegistrar.Dispatched.Unknown(), d)
        assertEquals(State.DISPATCH_UNKNOWN, r.get(op.id)!!.state)
        assertEquals(hash2, r.get(op.id)!!.transactionHash)
        assertEquals(hash2, journal.saved.single().transactionHash)
        // And it reads back after a restart.
        assertEquals(hash2, NameJournalCodec.decode(NameJournalCodec.encode(journal.saved)).single().transactionHash)
        // Not mined yet: still unknown.
        assertEquals(State.DISPATCH_UNKNOWN, r.refresh(op.id)!!.state)
        // Try again in the wallet sent the same bytes, and they reverted: still revealable in its window.
        chain.mine(hash2, ok = false)
        val back = r.refresh(op.id)!!
        assertEquals(State.READY, back.state)
        assertEquals(Step.REVEAL, back.step)
        assertNull(back.transactionHash)
    }

    @Test
    fun `a renewal the wallet may have sent that reverted ends failed`() = runBlocking {
        chain.available["wei"] = false
        chain.owner["wei"] = me
        chain.expiry["wei"] = BigInteger.valueOf(chain.timestamp + 1000)
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.RENEW, me, 0)
        r.dispatch(r.plan(op.id)) { WalletSender.Broadcast.Failed("no node answered", true, "no node answered", hash = hash1) }
        assertEquals(State.DISPATCH_UNKNOWN, r.get(op.id)!!.state)
        chain.mine(hash1, ok = false)
        assertEquals(State.FAILED, r.refresh(op.id)!!.state)
    }

    @Test
    fun `an outcome the disk refuses is held here, not left dispatching, and saved with the next write`() = runBlocking {
        val j = NameJournal.Memory()
        val r = registrar(j)
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        val plan = r.plan(op.id)
        val d = r.dispatch(plan) {
            // The disk fills while the wallet has it.
            j.failSaves = true
            WalletSender.Broadcast.Sent(hash1)
        }
        assertEquals(NameRegistrar.Dispatched.Sent(hash1, unsaved = true), d)
        assertEquals(State.PENDING, r.get(op.id)!!.state)
        assertEquals(hash1, r.get(op.id)!!.transactionHash)
        assertEquals(State.DISPATCHING, j.saved.single().state)
        // Followed as any sent step: its commitment and receipt move it on, and that save writes it.
        j.failSaves = false
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        assertEquals(State.WAITING_COMMITMENT, r.refresh(op.id)!!.state)
        assertEquals(hash1, j.saved.single().commitHash)
        // An unknown outcome is held the same way, and can be dismissed.
        val other = r.start(r.check("other.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        val u = r.dispatch(r.plan(other.id)) {
            j.failSaves = true
            WalletSender.Broadcast.Failed("no node answered", true, "no node answered")
        }
        assertEquals(NameRegistrar.Dispatched.Unknown(unsaved = true), u)
        assertEquals(State.DISPATCH_UNKNOWN, r.get(other.id)!!.state)
        j.failSaves = false
        r.dismiss(other.id)
        assertNull(r.get(other.id))
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
        assertEquals(op.id, r.start(check, Intent.REGISTER, me, 0).id)
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
    fun `starting a name with an open operation under a different choice says so instead of handing that one back`() = runBlocking {
        val r = registrar()
        val oneYear = r.check("freedom.eth", NameContracts.YEAR)
        val op = r.start(oneYear, Intent.REGISTER, me, 0)
        val other = listOf<suspend () -> NameOperation>(
            { r.start(oneYear, Intent.REGISTER, someoneElse, 1) },
            { r.start(r.check("freedom.eth", 3 * NameContracts.YEAR), Intent.REGISTER, me, 0) },
        )
        for (attempt in other) {
            try {
                attempt()
                fail("handed back an operation for another choice")
            } catch (e: NameException) {
                assertEquals(NameException.Code.CONFLICT, e.code)
            }
        }
        assertEquals(listOf(op.id), r.operations.value!!.map { it.id })
        // Dismissed, the new choice starts.
        r.dismiss(op.id)
        val three = r.start(r.check("freedom.eth", 3 * NameContracts.YEAR), Intent.REGISTER, someoneElse, 1)
        assertEquals(3 * NameContracts.YEAR, three.duration)
        assertEquals(someoneElse, three.account)
    }

    @Test
    fun `a reveal in its premium window goes at the reviewed value while the premium decays`() = runBlocking {
        chain.premium = BigInteger.TEN.pow(17)
        val r = registrar()
        val op = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.commitments[op.commitment!!] = BigInteger.valueOf(chain.timestamp)
        chain.mine(hash1)
        r.refresh(op.id)
        chain.timestamp += 61
        assertEquals(State.READY, r.refresh(op.id)!!.state)
        val reveal = r.plan(op.id)
        assertEquals(NameContracts.withBuffer(chain.nftFee + chain.premium), reveal.value)
        // A block later the premium is a little lower: the reviewed value still pays it, the contract refunds the rest.
        chain.block++
        chain.timestamp += 12
        chain.premium = chain.premium - BigInteger.valueOf(1_000_000_000)
        chain.simulated.clear()
        assertEquals(NameRegistrar.Dispatched.Sent(hash2), r.dispatch(reveal, sent(hash2)))
        // Checked at the value that's actually sent.
        assertEquals(reveal.value, chain.simulated.single().second)
        assertEquals(State.PENDING, r.get(op.id)!!.state)
    }

    @Test
    fun `an eth renewal whose oracle price fell during the review goes at the reviewed value`() = runBlocking {
        chain.available["eth"] = false
        chain.owner["eth"] = me
        chain.expiry["eth"] = BigInteger.valueOf(chain.timestamp + 5000)
        val r = registrar()
        val op = r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.RENEW, me, 0)
        val plan = r.plan(op.id)
        chain.base = chain.base - BigInteger.ONE
        assertEquals(NameRegistrar.Dispatched.Sent(hash1), r.dispatch(plan, sent(hash1)))
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

    @Test
    fun `an unreadable journal is set aside, not overwritten, and removing the wallet deletes it too`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("names").toFile()
        try {
            val file = java.io.File(dir, "operations.json")
            file.writeText("{ not json")
            val disk = NameJournal.FileBacked(file)
            val r = registrar(disk)
            assertEquals(emptyList<NameOperation>(), r.operations.value)
            val aside = dir.listFiles()!!.filter { it.name.startsWith("operations.json.unreadable-") }
            assertEquals(1, aside.size)
            assertEquals("{ not json", aside.single().readText())
            r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
            assertEquals(1, NameJournal.FileBacked(file).load().size)
            r.wipe()
            assertEquals(emptyList<NameOperation>(), NameJournal.FileBacked(file).load())
            assertTrue(dir.listFiles()!!.none { it.name.contains("unreadable") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a journal with an entry that doesn't read back is copied aside before the next save drops it`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("names").toFile()
        try {
            val file = java.io.File(dir, "operations.json")
            val maker = registrar()
            val good = maker.start(maker.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
            val json = NameJournalCodec.encode(listOf(good))
            // A newer app's state, say: this one can't decode it.
            json.getJSONArray("operations").put(NameJournalCodec.encode(good.copy(id = "name_" + "1".repeat(24))).put("state", "SOMETHING_NEW"))
            val original = json.toString()
            file.writeText(original)
            val r = registrar(NameJournal.FileBacked(file))
            assertEquals(listOf(good.id), r.operations.value!!.map { it.id })
            val aside = dir.listFiles()!!.filter { it.name.startsWith("operations.json.unreadable-") }
            assertEquals(original, aside.single().readText())
            // Read again before anything is saved: still one copy.
            NameJournal.FileBacked(file).load()
            assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("operations.json.unreadable-") })
            r.wipe()
            assertTrue(dir.listFiles()!!.none { it.name.contains("unreadable") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a journal of another version is set aside whole`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("names").toFile()
        try {
            val file = java.io.File(dir, "operations.json")
            file.writeText("""{"version":2,"operations":[]}""")
            val r = registrar(NameJournal.FileBacked(file))
            assertEquals(emptyList<NameOperation>(), r.operations.value)
            assertFalse(file.exists())
            assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("operations.json.unreadable-") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a wipe the disk refuses still forgets every operation here, and says the file stayed`() = runBlocking {
        val j = NameJournal.Memory()
        val r = registrar(j)
        r.start(r.check("freedom.eth", NameContracts.YEAR), Intent.REGISTER, me, 0)
        j.failSaves = true
        try {
            r.wipe()
            fail("a wipe that left the journal reported success")
        } catch (e: NameException) {
            assertEquals(NameException.Code.SECURE_STORAGE, e.code)
        }
        assertEquals(emptyList<NameOperation>(), r.operations.value)
        assertTrue("another try is owed though the list is empty", r.eraseOwed)
        // Tried again on the same instance (the wallet page, with no wallet): the disk takes it this time.
        j.failSaves = false
        r.wipe()
        assertFalse(r.eraseOwed)
        assertEquals(emptyList<NameOperation>(), j.saved)
        // After a restart, what a failed wipe left is read back: the list itself says a try is due.
        val left = r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
        val restarted = registrar(NameJournal.Memory(j.saved))
        assertFalse(restarted.eraseOwed)
        assertEquals(listOf(left.id), restarted.operations.value!!.map { it.id })
    }

    @Test
    fun `a journal that can't be rewritten is deleted instead when the wallet goes`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("names").toFile()
        try {
            val file = java.io.File(dir, "operations.json")
            val r = registrar(NameJournal.FileBacked(file))
            r.start(r.check("freedom.wei", NameContracts.YEAR), Intent.REGISTER, me, 0)
            assertTrue(file.exists())
            java.io.File(dir, "operations.json.unreadable-1").writeText("{}")
            // A directory where the temporary file goes: every save fails, as on a full disk.
            java.io.File(dir, "operations.json.tmp").mkdir()
            r.wipe()
            assertFalse(file.exists())
            assertTrue(dir.listFiles()!!.none { it.name.contains("unreadable") })
            assertEquals(emptyList<NameOperation>(), r.operations.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ownWei() {
        chain.owner["wei"] = me
        chain.expiry["wei"] = BigInteger.valueOf(chain.timestamp + NameContracts.YEAR)
        chain.available["wei"] = false
    }

    private fun refused(code: NameException.Code, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("not refused")
        } catch (e: NameException) {
            assertEquals(e.english, code, e.code)
        }
    }

    @Test
    fun `a text record write on a wei name is built from what it holds now and settles when the record is set`() = runBlocking {
        ownWei()
        val r = registrar()
        val record = NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "https://alice.example")
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0, chat = "chat-1")
        assertEquals(Step.RECORD, op.step)
        assertEquals(State.READY, op.state)
        assertEquals("", op.before)
        assertEquals("chat-1", op.chat)
        assertNull(op.encryptedSecret)

        val plan = r.plan(op.id)
        assertEquals(Role.WEI.address, plan.to)
        assertEquals(BigInteger.ZERO, plan.value)
        assertArrayEquals(NameContracts.setTextData(NameContracts.parse("alice.wei"), "url", "https://alice.example"), plan.data)

        assertEquals(NameRegistrar.Dispatched.Sent(hash1), r.dispatch(plan, sent(hash1)))
        chain.text["wei"] = hashMapOf("url" to "https://alice.example")
        chain.mine(hash1)
        assertEquals(State.COMPLETED, r.refresh(op.id)!!.state)
        // A record operation reads back from the journal whole.
        assertEquals(r.get(op.id), NameJournalCodec.decode(NameJournalCodec.encode(journal.saved)).single().copy(revision = r.get(op.id)!!.revision))
    }

    @Test
    fun `a record that already holds the value, a name the account doesn't own and an unsupported resolver are refused`() {
        ownWei()
        chain.text["wei"] = hashMapOf("url" to "same")
        val r = registrar()
        refused(NameException.Code.INVALID) {
            r.startRecord("alice.wei", Intent.SET_TEXT, NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "same"), me, 0)
        }
        // An unsent write of what's already there would read back as done: nothing is prepared.
        assertTrue(journal.saved.isEmpty())
        refused(NameException.Code.UNSUPPORTED) {
            r.startRecord("alice.wei", Intent.SET_TEXT, NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new"), someoneElse, 1)
        }
        chain.owner["eth"] = me
        chain.expiry["eth"] = BigInteger.valueOf(chain.timestamp + NameContracts.YEAR)
        chain.resolver["eth"] = someoneElse
        refused(NameException.Code.UNSUPPORTED) {
            r.startRecord("freedom.eth", Intent.CLEAR_WEBSITE, NameRegistrar.recordFor(Intent.CLEAR_WEBSITE), me, 0)
        }
        assertTrue(journal.saved.isEmpty())
    }

    @Test
    fun `an eth address write goes to the public resolver, and an approved operator may write it`() = runBlocking {
        chain.owner["eth"] = someoneElse
        chain.expiry["eth"] = BigInteger.valueOf(chain.timestamp + NameContracts.YEAR)
        chain.available["eth"] = false
        chain.approvedForAll += someoneElse to me
        val r = registrar()
        val record = NameRegistrar.recordFor(Intent.SET_ADDRESS, address = me)
        val op = r.startRecord("freedom.eth", Intent.SET_ADDRESS, record, me, 0)
        val plan = r.plan(op.id)
        assertEquals(Role.RESOLVER.address, plan.to)
        assertArrayEquals(NameContracts.setAddrData(NameContracts.parse("freedom.eth"), me), plan.data)
        // The approval is withdrawn before it's sent: nothing goes, and it's out of date.
        chain.approvedForAll.clear()
        refused(NameException.Code.UNSUPPORTED) { r.dispatch(plan, sent(hash1)) }
    }

    @Test
    fun `a record write whose owner, generation or record changed after it was prepared is never sent`() = runBlocking {
        ownWei()
        val r = registrar()
        val record = NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new")
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0)
        chain.recordVersion["wei"] = BigInteger.TWO
        refused(NameException.Code.STALE) { r.plan(op.id) }
        // Out of date for good: marked stale, so it doesn't hold the name open for a fresh write.
        assertEquals(State.STALE, r.get(op.id)!!.state)
        val next = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0)
        val plan = r.plan(next.id)
        chain.text["wei"] = hashMapOf("url" to "someone else's")
        var sends = 0
        refused(NameException.Code.STALE) { r.dispatch(plan) { sends++; WalletSender.Broadcast.Sent(hash1) } }
        assertEquals(0, sends)
        assertEquals(State.STALE, r.get(next.id)!!.state)
    }

    @Test
    fun `a record write the wallet may have sent is settled by the record on chain, never sent again`() = runBlocking {
        ownWei()
        val r = registrar()
        val record = NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new")
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0)
        assertEquals(NameRegistrar.Dispatched.Unknown(), r.dispatch(r.plan(op.id)) { throw IllegalStateException("lost") })
        assertEquals(State.DISPATCH_UNKNOWN, r.refresh(op.id)!!.state)
        chain.text["wei"] = hashMapOf("url" to "new")
        assertEquals(State.COMPLETED, r.refresh(op.id)!!.state)
    }

    @Test
    fun `a record is checked as desktop checks it, and a saved one that doesn't match its intent is refused`() = runBlocking {
        assertEquals(NameRecord(NameRecord.Kind.CONTENTHASH, "0xe40101fa011b20" + "cd".repeat(32)), NameRegistrar.recordFor(Intent.SET_WEBSITE, reference = "cd".repeat(32)))
        assertEquals(NameRecord(NameRecord.Kind.CONTENTHASH, "0x"), NameRegistrar.recordFor(Intent.CLEAR_WEBSITE))
        assertEquals(
            NameRecord(NameRecord.Kind.ADDRESS, "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"),
            NameRegistrar.recordFor(Intent.SET_ADDRESS, address = "0x9858effd232b4033e47d90003d41ec34ecaeda94"),
        )
        for (bad in listOf(
            { NameRegistrar.recordFor(Intent.SET_ADDRESS, address = NameContracts.ZERO_ADDRESS) },
            { NameRegistrar.recordFor(Intent.SET_ADDRESS, address = "0x9858EfFD232B4033E47d90003D41EC34EcaEda9") },
            { NameRegistrar.recordFor(Intent.SET_ADDRESS, address = "0x9858EFFD232B4033E47d90003D41EC34EcaEda94") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "", value = "x") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "k".repeat(129), value = "x") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "x".repeat(2049)) },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "a\u202Eb") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "a\u200Bb") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "a\uFEFFb") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "a\uDB40\uDC01b") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "u\u200Drl", value = "x") },
            { NameRegistrar.recordFor(Intent.SET_TEXT, key = "u\nrl", value = "x") },
            { NameRegistrar.recordFor(Intent.SET_WEBSITE, reference = "zz") },
        )) {
            try {
                bad()
                fail("accepted")
            } catch (e: NameException) {
                assertEquals(NameException.Code.INVALID, e.code)
            }
        }
        ownWei()
        val r = registrar()
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new"), me, 0)
        // A journal edited to point the write somewhere else isn't built.
        val tampered = op.copy(intent = Intent.SET_WEBSITE)
        val r2 = registrar(NameJournal.Memory(listOf(tampered)))
        refused(NameException.Code.UNSUPPORTED) { r2.plan(op.id) }
    }

    @Test
    fun `a text value may hold the joiners and tag characters emoji are built from`() {
        // Family (ZWJ), a Persian word with ZWNJ, and Scotland's flag (tag characters U+E0067… U+E007F).
        for (value in listOf("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F")) {
            assertEquals(value, NameRegistrar.recordFor(Intent.SET_TEXT, key = "description", value = value).value)
        }
    }

    @Test
    fun `two prepares on one name at once leave one open operation`() = runBlocking {
        ownWei()
        val r = registrar()
        fun text(v: String) = NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = v)
        // Both pass the open-operation check, then wait inside their chain reads together.
        chain.gate = CompletableDeferred()
        val a = async { runCatching { r.startRecord("alice.wei", Intent.SET_TEXT, text("a"), me, 0, chat = "chat-1") } }
        val b = async { runCatching { r.startRecord("alice.wei", Intent.SET_TEXT, text("b"), me, 0, chat = "chat-2") } }
        chain.openGate(readers = 2)
        val results = listOf(a.await(), b.await())
        // The later chat is handed the earlier chat's operation, unchanged, for it to refuse unread (never its refusal text).
        val ops = results.map { it.getOrThrow() }
        assertEquals(ops[0].id, ops[1].id)
        assertEquals(1, r.operations.value!!.count { it.name == "alice.wei" && !it.state.terminal })
        // The Names page, though, is told why: the open operation is named.
        val page = runCatching { r.startRecord("alice.wei", Intent.SET_TEXT, text("d"), me, 0) }
        assertEquals(NameException.Code.CONFLICT, (page.exceptionOrNull() as NameException).code)

        // The same choice, prepared twice at once, is one operation handed to both.
        r.dismiss(ops[0].id)
        chain.gate = CompletableDeferred()
        val c = async { r.startRecord("alice.wei", Intent.SET_TEXT, text("c"), me, 0) }
        val d = async { r.startRecord("alice.wei", Intent.SET_TEXT, text("c"), me, 0) }
        chain.openGate(readers = 2)
        assertEquals(c.await().id, d.await().id)
        assertEquals(1, r.operations.value!!.count { it.name == "alice.wei" && !it.state.terminal })
    }

    @Test
    fun `a mined record write is checked at its receipt's block, so a later change doesn't leave it unverified`() = runBlocking {
        ownWei()
        val r = registrar()
        val record = NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new")
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0)
        r.dispatch(r.plan(op.id), sent(hash1))
        chain.mine(hash1)
        // The write landed at its block; another app changed the record again before this refresh.
        chain.textAt[chain.block] = mapOf("wei" to mapOf("url" to "new"))
        chain.text["wei"] = hashMapOf("url" to "someone else's")
        chain.block++
        assertEquals(State.COMPLETED, r.refresh(op.id)!!.state)

        // One left unverified (its block's state not shown then) is judged by that block again on the next refresh.
        chain.text["wei"] = hashMapOf()
        val next = r.startRecord("alice.wei", Intent.SET_TEXT, record, me, 0)
        r.dispatch(r.plan(next.id), sent(hash2))
        chain.mine(hash2)
        val mined = chain.block
        assertEquals(State.VERIFICATION_NEEDED, r.refresh(next.id)!!.state)
        chain.block++
        assertEquals(State.VERIFICATION_NEEDED, r.refresh(next.id)!!.state)
        chain.textAt[mined] = mapOf("wei" to mapOf("url" to "new"))
        assertEquals(State.COMPLETED, r.refresh(next.id)!!.state)
    }

    @Test
    fun `a record over 8 KiB doesn't stop a write to another, and is refused only as its own write`() = runBlocking {
        ownWei()
        chain.contenthash["wei"] = "0x" + "ab".repeat(NameContracts.MAX_RECORD_BYTES + 1)
        val r = registrar()
        val op = r.startRecord("alice.wei", Intent.SET_TEXT, NameRegistrar.recordFor(Intent.SET_TEXT, key = "url", value = "new"), me, 0)
        r.plan(op.id)
        r.dismiss(op.id)
        refused(NameException.Code.UNSUPPORTED) { r.startRecord("alice.wei", Intent.CLEAR_WEBSITE, NameRegistrar.recordFor(Intent.CLEAR_WEBSITE), me, 0) }
        // A record that grew past the bound after it was prepared changed: never sent.
        chain.contenthash["wei"] = "0x"
        val set = r.startRecord("alice.wei", Intent.SET_TEXT, NameRegistrar.recordFor(Intent.SET_TEXT, key = "big", value = "x"), me, 0)
        chain.text["wei"] = hashMapOf("big" to "y".repeat(NameContracts.MAX_RECORD_BYTES + 1))
        refused(NameException.Code.STALE) { r.plan(set.id) }
    }
}
