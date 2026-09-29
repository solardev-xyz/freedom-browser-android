package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.Erc20
import baby.freedom.mobile.wallet.SafeProtocol
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A co-sign request whose call is from the Safe to itself: decoded and flagged, not raw hex (#235, audit #229). */
class SafeSelfCallTest {
    private val safe = "0x6d21181D5e0F3a4a438F0CC65FACFd418443b096"
    private val attacker = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val owner = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val sentinel = "0x0000000000000000000000000000000000000001"

    private fun word(address: String) = "0".repeat(24) + address.removePrefix("0x").lowercase()
    private fun word(n: Long) = n.toString(16).padStart(64, '0')
    private fun call(name: String, vararg words: String) = (SAFE_SELECTORS.getValue(name) + words.joinToString("")).hexBytes()
    private fun String.hexBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A shared co-sign request, parsed exactly as the co-sign page does. */
    private fun request(to: String, data: ByteArray): SafeProtocol.Request =
        SafeProtocol.parseRequest(SafeProtocol.safeTxTypedData(safe, 100, SafeProtocol.SafeTx(to, BigInteger.ZERO, data, BigInteger.ZERO)).toString())

    @Test
    fun `the audit's takeover request is read as adding an owner, not shown as hex`() {
        // #229: a co-owner shares `to` = the Safe, `addOwnerWithThreshold(attacker, 1)`.
        val r = request(safe, call("addOwnerWithThreshold", word(attacker), word(1)))
        val decoded = safeSelfCall(r)
        assertEquals(SafeSelfCall.AddOwner(attacker, BigInteger.ONE), decoded)
        assertFalse(decoded!!.harmless)
        assertTrue(safeSelfCallRisk(decoded).contains("new owner"))
        // Any spelling of the Safe's address is still the Safe.
        val lower = SafeProtocol.parseRequest(
            SafeProtocol.safeTxTypedData(safe, 100, SafeProtocol.SafeTx(safe.lowercase(), BigInteger.ZERO, call("enableModule", word(attacker)), BigInteger.ZERO))
                .toString(),
        )
        assertEquals(SafeSelfCall.EnableModule(attacker), safeSelfCall(lower))
    }

    @Test
    fun `the selectors are the Safe's own`() {
        assertEquals(
            mapOf(
                "addOwnerWithThreshold" to "0d582f13",
                "removeOwner" to "f8dc5dd9",
                "swapOwner" to "e318b52b",
                "changeThreshold" to "694e80c3",
                "enableModule" to "610b5925",
                "disableModule" to "e009cfde",
                "setGuard" to "e19a9dd9",
                "setFallbackHandler" to "f08a0323",
            ),
            SAFE_SELECTORS,
        )
    }

    @Test
    fun `every admin call is decoded`() {
        assertEquals(SafeSelfCall.RemoveOwner(sentinel, owner, BigInteger.TWO), safeSelfCall(call("removeOwner", word(sentinel), word(owner), word(2))))
        assertEquals(SafeSelfCall.SwapOwner(sentinel, owner, attacker), safeSelfCall(call("swapOwner", word(sentinel), word(owner), word(attacker))))
        assertEquals(SafeSelfCall.ChangeThreshold(BigInteger.ONE), safeSelfCall(call("changeThreshold", word(1))))
        assertEquals(SafeSelfCall.EnableModule(attacker), safeSelfCall(call("enableModule", word(attacker))))
        assertEquals(SafeSelfCall.DisableModule(sentinel, attacker), safeSelfCall(call("disableModule", word(sentinel), word(attacker))))
        assertEquals(SafeSelfCall.SetGuard(attacker), safeSelfCall(call("setGuard", word(attacker))))
        assertEquals(SafeSelfCall.SetFallbackHandler(attacker), safeSelfCall(call("setFallbackHandler", word(attacker))))
        for (c in SAFE_SELECTORS.keys) {
            val decoded = safeSelfCall(request(safe, call(c, *Array(if (c in setOf("removeOwner", "swapOwner")) 3 else if (c in setOf("addOwnerWithThreshold", "disableModule")) 2 else 1) { word(attacker) })))
            assertFalse(c, decoded!!.harmless)
            assertTrue(c, decoded != SafeSelfCall.Unknown)
        }
    }

    @Test
    fun `anything not exactly an admin call is unknown, and still flagged`() {
        val dirty = "ff" + word(attacker).substring(2)
        val cases = listOf(
            call("enableModule", dirty),
            call("enableModule", word(attacker), word(1)),
            call("enableModule"),
            call("addOwnerWithThreshold", word(attacker)),
            call("setGuard", word(attacker)) + byteArrayOf(0),
            byteArrayOf(0x12, 0x34),
            ("6a761202" + word(1)).hexBytes(), // execTransaction and anything else unread
            Erc20.transferData(attacker, BigInteger.TEN),
        )
        for (data in cases) {
            val decoded = safeSelfCall(request(safe, data))
            assertEquals(data.toHex(), SafeSelfCall.Unknown, decoded)
            assertFalse(decoded!!.harmless)
        }
    }

    @Test
    fun `a call with no data to the Safe is a cancellation, and a call elsewhere is no self-call`() {
        val cancel = safeSelfCall(request(safe, ByteArray(0)))
        assertEquals(SafeSelfCall.Cancel, cancel)
        assertTrue(cancel!!.harmless)
        assertNull(safeSelfCall(request(attacker, call("addOwnerWithThreshold", word(attacker), word(1)))))
        assertNull(safeSelfCall(SafeProtocol.parseRequest(SafeProtocol.shareText(SafeProtocol.messageTypedData(safe, 100, "hi"), "hi"))))
    }

    @Test
    fun `the threshold detail checks the named owner is, or isn't, already an owner`() {
        val three = listOf(owner, sentinel.replace("1", "2"), "0x" + "3".repeat(40))
        // Adding an existing owner reverts (GS204): no "any one of 4 owners" claim.
        assertEquals(
            "The new owner already owns this Safe: this transaction would fail.",
            safeSelfCallThreshold(SafeSelfCall.AddOwner(owner.lowercase(), BigInteger.ONE), three, safe),
        )
        assertEquals(
            "Any one of 4 owners alone can then move everything in the Safe.",
            safeSelfCallThreshold(SafeSelfCall.AddOwner(attacker, BigInteger.ONE), three, safe),
        )
        // Removing a non-owner reverts too: no "t of 2 owners" claim.
        assertEquals(
            "The removed owner doesn’t own this Safe: this transaction would fail.",
            safeSelfCallThreshold(SafeSelfCall.RemoveOwner(sentinel, attacker, BigInteger.ONE), three, safe),
        )
        assertEquals("2 of 2 owners must then sign.", safeSelfCallThreshold(SafeSelfCall.RemoveOwner(sentinel, owner, BigInteger.TWO), three, safe))
        assertEquals("Not possible with 3 owners: this transaction would fail.", safeSelfCallThreshold(SafeSelfCall.ChangeThreshold(BigInteger.valueOf(4)), three, safe))
        // Nothing to say before the owners are read, or for a call with no threshold.
        assertNull(safeSelfCallThreshold(SafeSelfCall.AddOwner(attacker, BigInteger.ONE), null, safe))
        assertNull(safeSelfCallThreshold(SafeSelfCall.SwapOwner(sentinel, owner, attacker), three, safe))
    }

    @Test
    fun `an owner call the Safe would revert says so, including a wrong prevOwner`() {
        val second = "0x" + "2".repeat(40)
        val third = "0x" + "3".repeat(40)
        val three = listOf(owner, second, third)
        val wrongPrev = "It names the wrong owner before the removed one in the Safe’s owner list: this transaction would fail."
        // removeOwner: prevOwner must be the owner right before it in getOwners() order, the sentinel for the first.
        assertEquals("2 of 2 owners must then sign.", safeSelfCallThreshold(SafeSelfCall.RemoveOwner(owner, second, BigInteger.TWO), three, safe))
        assertEquals("2 of 2 owners must then sign.", safeSelfCallThreshold(SafeSelfCall.RemoveOwner(second.uppercase().replace("0X", "0x"), third, BigInteger.TWO), three, safe))
        assertEquals(wrongPrev, safeSelfCallThreshold(SafeSelfCall.RemoveOwner(sentinel, second, BigInteger.TWO), three, safe))
        assertEquals(wrongPrev, safeSelfCallThreshold(SafeSelfCall.RemoveOwner(owner, owner, BigInteger.TWO), three, safe))
        assertEquals(wrongPrev, safeSelfCallThreshold(SafeSelfCall.RemoveOwner(third, owner, BigInteger.TWO), three, safe))
        // swapOwner: the old owner must own the Safe with the right prevOwner, the new one must not already.
        assertNull(safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, owner, attacker), three, safe))
        assertNull(safeSelfCallFailure(SafeSelfCall.SwapOwner(second, third, attacker), three, safe))
        assertEquals(
            "The replaced owner doesn’t own this Safe: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, attacker, second), three, safe),
        )
        assertEquals(
            "It names the wrong owner before the replaced one in the Safe’s owner list: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, third, attacker), three, safe),
        )
        assertEquals(
            "The new owner already owns this Safe: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, owner, third.uppercase().replace("0X", "0x")), three, safe),
        )
        assertEquals("The new owner is no address: this transaction would fail.", safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, owner, sentinel), three, safe))
        assertEquals("The new owner is no address: this transaction would fail.", safeSelfCallFailure(SafeSelfCall.AddOwner(SafeProtocol.ZERO_ADDRESS, BigInteger.ONE), three, safe))
        // Nothing to say before the owners are read, or for a call that isn't about owners.
        assertNull(safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, attacker, second), null, safe))
        assertNull(safeSelfCallFailure(SafeSelfCall.ChangeThreshold(BigInteger.ONE), three, safe))
    }

    @Test
    fun `adding or swapping in the Safe itself as an owner would revert`() {
        val three = listOf(owner, "0x" + "2".repeat(40), "0x" + "3".repeat(40))
        val self = "The new owner is this Safe itself, which can’t own itself: this transaction would fail."
        // GS203: no "any one of 4 owners" claim for a call that always reverts, whatever the address's case.
        assertEquals(self, safeSelfCallThreshold(SafeSelfCall.AddOwner(safe.lowercase(), BigInteger.ONE), three, safe))
        assertEquals(self, safeSelfCallFailure(SafeSelfCall.SwapOwner(sentinel, owner, safe), three, safe))
        assertEquals(self, safeSelfCallFailure(SafeSelfCall.AddOwner(safe, BigInteger.TWO), three, safe))
    }

    @Test
    fun `a cancellation only claims to use up the nonce when the Safe can pay what it sends`() {
        val n = BigInteger.valueOf(7)
        val ten = BigInteger.TEN
        assertEquals(
            "A call from the Safe to itself with no data. It only uses up Safe nonce 7, so no other transaction with that nonce can execute.",
            safeCancelDetail(n, BigInteger.ZERO, null, "0 xDAI"),
        )
        // More than it holds: execTransaction reverts (GS013) and the nonce stays open.
        val short = safeCancelDetail(n, ten, BigInteger.ONE, "10 wei")
        assertTrue(short, short.contains("more than the Safe now holds") && short.contains("nonce 7 would stay open"))
        assertFalse(short, short.contains("only uses up"))
        // Enough now: one untrusted read, and the Safe can be drained before execution, so still hedged.
        val enough = safeCancelDetail(n, ten, ten, "10 wei")
        assertTrue(enough, enough.contains("uses up Safe nonce 7") && enough.contains("as long as the Safe still holds that much when it executes"))
        assertFalse(enough, enough.contains("only uses up"))
        // Unread: hedged, not a claim either way.
        val unread = safeCancelDetail(n, ten, null, "10 wei")
        assertTrue(unread, unread.contains("only if the Safe holds that much") && !unread.contains("It only uses up"))
    }

    @Test
    fun `signing is cleared by one predicate`() {
        assertTrue(safeSelfCallCleared(null, acknowledged = false))
        assertTrue(safeSelfCallCleared(SafeSelfCall.Cancel, acknowledged = false))
        assertFalse(safeSelfCallCleared(SafeSelfCall.EnableModule(attacker), acknowledged = false))
        assertTrue(safeSelfCallCleared(SafeSelfCall.EnableModule(attacker), acknowledged = true))
        assertFalse(safeSelfCallCleared(SafeSelfCall.Unknown, acknowledged = false))
        assertFalse(null.needsAcknowledgement)
        assertFalse(SafeSelfCall.Cancel.needsAcknowledgement)
        assertTrue(SafeSelfCall.Unknown.needsAcknowledgement)
    }

    @Test
    fun `a removed owner that is this wallet's own account is named`() {
        val accounts = listOf(WalletAccount(0, "Account 1", owner))
        assertEquals("Account 1 (this phone)", safeOwnAccountLabel(owner.lowercase(), accounts))
        assertNull(safeOwnAccountLabel(attacker, accounts))
    }

    @Test
    fun `a module, guard or fallback handler call the Safe would revert says so`() {
        val modules = listOf(owner, attacker)
        val zero = SafeProtocol.ZERO_ADDRESS
        // GS101: no address, whether or not the modules are read.
        val noModule = "The module is no address: this transaction would fail."
        assertEquals(noModule, safeSelfCallFailure(SafeSelfCall.EnableModule(zero), null, safe))
        assertEquals(noModule, safeSelfCallFailure(SafeSelfCall.EnableModule(sentinel), null, safe))
        assertEquals(noModule, safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, sentinel), null, safe))
        // GS102: already enabled, in any case.
        assertEquals(
            "This module is already enabled on the Safe: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.EnableModule(attacker.lowercase()), null, safe, modules = modules),
        )
        assertNull(safeSelfCallFailure(SafeSelfCall.EnableModule(safe.replace("6d", "7d")), null, safe, modules = modules))
        assertNull(safeSelfCallFailure(SafeSelfCall.EnableModule(attacker), null, safe))
        // GS103: not enabled, or the wrong prevModule (the sentinel for the first).
        assertNull(safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, owner), null, safe, modules = modules))
        assertNull(safeSelfCallFailure(SafeSelfCall.DisableModule(owner, attacker), null, safe, modules = modules))
        assertEquals(
            "It names the wrong module before this one in the Safe’s module list: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, attacker), null, safe, modules = modules),
        )
        assertEquals(
            "This module isn’t enabled on the Safe: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, safe), null, safe, modules = modules),
        )
        assertEquals(
            "This module isn’t enabled on the Safe: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, owner), null, safe, modules = emptyList()),
        )
        assertNull(safeSelfCallFailure(SafeSelfCall.DisableModule(sentinel, attacker), null, safe))
        // GS300: a guard that doesn't answer as one right now gets a hedge, never "would fail": its author
        // controls the code and the answer and can change both before execution. Removing the guard (zero)
        // is never checked.
        val guardNote = safeSelfCallFailure(SafeSelfCall.SetGuard(attacker), null, safe, guardSupported = false)!!
        assertTrue(guardNote.contains("don’t count on this failing"))
        assertFalse(guardNote.contains("would fail"))
        assertNull(safeSelfCallFailure(SafeSelfCall.SetGuard(attacker), null, safe, guardSupported = true))
        assertNull(safeSelfCallFailure(SafeSelfCall.SetGuard(attacker), null, safe))
        assertNull(safeSelfCallFailure(SafeSelfCall.SetGuard(zero), null, safe, guardSupported = false))
        // GS400: the Safe as its own fallback handler, in any case; anything else goes through.
        assertEquals(
            "The fallback handler is this Safe itself, which it refuses: this transaction would fail.",
            safeSelfCallFailure(SafeSelfCall.SetFallbackHandler(safe.lowercase()), null, safe),
        )
        assertNull(safeSelfCallFailure(SafeSelfCall.SetFallbackHandler(zero), null, safe))
        assertNull(safeSelfCallFailure(SafeSelfCall.SetFallbackHandler(attacker), null, safe))
        // A self-call with nothing to refuse, and owner calls before owners are read.
        assertNull(safeSelfCallFailure(SafeSelfCall.Unknown, listOf(owner), safe, modules, false))
        assertNull(safeSelfCallFailure(SafeSelfCall.AddOwner(owner, BigInteger.ONE), null, safe, modules, false))
    }

    @Test
    fun `the module list and guard check read the Safe's own ABI`() {
        // `Guard`'s ERC-165 id, as Safe v1.4.1's `setGuard` asks for it.
        assertEquals("e6d7a83a", SafeProtocol.GUARD_INTERFACE_ID.toHex())
        assertEquals("0x01ffc9a7" + "e6d7a83a" + "0".repeat(56), SafeProtocol.SUPPORTS_GUARD_CALL)
        assertEquals("0xcc2f8452" + word(sentinel) + word(64), SafeProtocol.MODULES_CALL)
        // `(address[] array, address next)`: whole only when `next` is the sentinel.
        fun modulesReturn(next: String, vararg modules: String) =
            "0x" + word(64) + word(next) + word(modules.size.toLong()) + modules.joinToString("") { word(it) }
        assertEquals(listOf(owner, attacker), SafeProtocol.decodeModules(modulesReturn(sentinel, owner, attacker)))
        assertEquals(emptyList<String>(), SafeProtocol.decodeModules(modulesReturn(sentinel)))
        assertNull(SafeProtocol.decodeModules(modulesReturn(attacker, owner, attacker)))
        assertNull(SafeProtocol.decodeModules(modulesReturn(SafeProtocol.ZERO_ADDRESS)))
        assertNull(SafeProtocol.decodeModules("0x"))
        assertNull(SafeProtocol.decodeModules(modulesReturn(sentinel, owner) + "00"))
        // abicoder v1 `bool`: any non-zero word is true; less than a word is no answer.
        assertEquals(true, SafeProtocol.decodeBool("0x" + word(1)))
        assertEquals(true, SafeProtocol.decodeBool("0x" + word(2)))
        assertEquals(false, SafeProtocol.decodeBool("0x" + word(0)))
        assertNull(SafeProtocol.decodeBool("0x"))
    }

    @Test
    fun `a self-call queued behind the Safe's next nonce isn't checked against its owners or modules now`() {
        // R1-F1: {A, B, X} threshold 2, next nonce N. X's removal is queued at N, and
        // addOwnerWithThreshold(X, 1) at N+1: read now, X still owns the Safe, so it looked harmless.
        val n = BigInteger.valueOf(7)
        val next = n + BigInteger.ONE
        val readdX = SafeSelfCall.AddOwner(attacker, BigInteger.ONE)
        val owners = listOf(owner, safe.replace('6', '7'), attacker)
        assertTrue(safeSelfCallFailure(readdX, owners, safe)!!.contains("would fail"))
        // The page only counts against what it read when this is the next transaction.
        assertTrue(safeStateApplies(n, n))
        assertFalse(safeStateApplies(n, next))
        assertFalse(safeStateApplies(null, n))
        assertFalse(safeStateApplies(next, n))
        // …so a queued one gets no "would fail" and no owner count, and says why.
        val queuedOwners = owners.takeIf { safeStateApplies(n, next) }
        assertNull(safeSelfCallFailure(readdX, queuedOwners, safe))
        assertNull(safeSelfCallThreshold(readdX, queuedOwners, safe))
        val note = safeSelfCallQueuedNote(readdX, n, next)!!
        assertTrue(note.contains("before Safe nonce 8"))
        assertTrue(note.contains("don’t count on it failing"))
        assertTrue(note.contains("owners"))
        // Modules too: "already enabled" read now says nothing about nonce N+1.
        assertTrue(safeSelfCallQueuedNote(SafeSelfCall.EnableModule(attacker), n, next)!!.contains("modules"))
        assertTrue(safeSelfCallQueuedNote(SafeSelfCall.DisableModule(sentinel, attacker), n, next)!!.contains("modules"))
        for (c in listOf(
            SafeSelfCall.RemoveOwner(sentinel, attacker, BigInteger.ONE),
            SafeSelfCall.SwapOwner(sentinel, owner, attacker),
            SafeSelfCall.ChangeThreshold(BigInteger.ONE),
        )) assertTrue(safeSelfCallQueuedNote(c, n, next)!!.contains("owners"))
        // No note for the next transaction, before the nonce is read, or for calls that don't depend on owners or modules.
        assertNull(safeSelfCallQueuedNote(readdX, n, n))
        assertNull(safeSelfCallQueuedNote(readdX, null, next))
        assertNull(safeSelfCallQueuedNote(SafeSelfCall.SetGuard(attacker), n, next))
        assertNull(safeSelfCallQueuedNote(SafeSelfCall.SetFallbackHandler(attacker), n, next))
        assertNull(safeSelfCallQueuedNote(SafeSelfCall.Cancel, n, next))
        assertNull(safeSelfCallQueuedNote(SafeSelfCall.Unknown, n, next))
    }
}
