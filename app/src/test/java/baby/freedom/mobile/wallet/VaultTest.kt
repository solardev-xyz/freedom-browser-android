package baby.freedom.mobile.wallet

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The vault's state machine (#76) over a software AES-GCM key standing
 * in for the Keystore one: what gets stored, when it asks the user, and
 * when it locks itself.
 */
class VaultTest {
    internal class FakeStore(var secure: Boolean = true) : VaultStore {
        var record: VaultRecord? = null
        var fileExists = false
        var key: SecretKey? = null
        var keyProtection: VaultProtection? = null

        override fun deviceSecure() = secure
        override fun read() = record
        override fun exists() = fileExists || record != null
        var onWrite: () -> Unit = {}

        override fun write(record: VaultRecord) {
            this.record = record
            onWrite()
        }

        override fun newSealingCipher(protection: VaultProtection): SealingCipher {
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            keyProtection = protection
            return SealingCipher(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }, strongBox = false)
        }

        override fun openingCipher(record: VaultRecord): Cipher {
            val k = key ?: throw VaultKeyLostException()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, record.iv)) }
        }

        var onWipe: () -> Unit = {}

        override fun wipe() {
            onWipe()
            record = null
            fileExists = false
            key = null
        }
    }

    internal class FakeAuth : VaultAuthenticator {
        val asked = mutableListOf<VaultAuthPurpose>()
        var cancel = false
        override suspend fun authenticate(cipher: Cipher, purpose: VaultAuthPurpose): Cipher {
            asked += purpose
            if (cancel) throw VaultAuthCancelledException()
            return cipher
        }
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private var now = 1_000_000L
    private val store = FakeStore()
    private val auth = FakeAuth()

    private fun vault() = Vault(store, scope, clock = { now }, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)

    private val phrase = Mnemonic.parse(
        "void come effort suffer camp survey warrior heavy shoot primary clutch crush " +
            "open amazing screen patrol group space point ten exist slush involve unfold",
    )

    @After
    fun tearDown() = job.cancel()

    @Test
    fun `opening the wallet or showing the phrase needs an explicit confirm after a face, sealing doesn't (#229)`() {
        assertTrue(VaultAuthPurpose.UNLOCK.confirmationRequired)
        assertTrue(VaultAuthPurpose.REVEAL.confirmationRequired)
        assertFalse(VaultAuthPurpose.CREATE.confirmationRequired)
        assertFalse(VaultAuthPurpose.IMPORT.confirmationRequired)
    }

    @Test
    fun `no vault on a fresh install`() {
        assertEquals(Vault.State.Empty, vault().state.value)
    }

    @Test
    fun `create seals the phrase under an authenticated key and opens it`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        assertEquals(listOf(VaultAuthPurpose.CREATE), auth.asked)
        assertEquals(VaultProtection.SCREEN_LOCK, store.keyProtection)
        val s = v.state.value as Vault.State.Unlocked
        assertEquals(Vault.Info(VaultProtection.SCREEN_LOCK, strongBox = false, backedUp = false), s.info)
        // Nothing stored carries the words.
        val stored = store.record!!
        // (Less the "screen-lock" label, as the phrase happens to hold "screen".)
        val onDisk = stored.encode().replace(VaultProtection.SCREEN_LOCK.wire, "")
        for (w in phrase.words.toSet()) assertFalse(w, onDisk.contains(w))
        assertFalse(String(stored.ciphertext, Charsets.ISO_8859_1).contains("void come"))
        assertArrayEquals(phrase.seed(), v.withSeed { it.copyOf() })
    }

    @Test
    fun `an imported phrase needs no backup reminder`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = true)
        assertEquals(listOf(VaultAuthPurpose.IMPORT), auth.asked)
        assertTrue((v.state.value as Vault.State.Unlocked).info.backedUp)
    }

    @Test
    fun `no screen lock - device-only key, no prompt`() = runBlocking {
        store.secure = false
        val v = vault()
        v.create(phrase, auth, imported = false)
        assertEquals(emptyList<VaultAuthPurpose>(), auth.asked)
        assertEquals(VaultProtection.DEVICE_ONLY, store.keyProtection)
        assertEquals(VaultProtection.DEVICE_ONLY, (v.state.value as Vault.State.Unlocked).info.protection)
        v.lock()
        v.unlock(auth)
        assertEquals(emptyList<VaultAuthPurpose>(), auth.asked)
        assertTrue(v.state.value is Vault.State.Unlocked)
    }

    @Test
    fun `cancelling the create prompt leaves nothing behind`() = runBlocking {
        auth.cancel = true
        val v = vault()
        try {
            v.create(phrase, auth, imported = false)
            fail("created")
        } catch (_: VaultAuthCancelledException) {
        }
        assertEquals(Vault.State.Empty, v.state.value)
        assertNull(store.record)
        assertNull(store.key)
    }

    @Test
    fun `a relaunch starts locked and unlocks with a prompt`() = runBlocking {
        vault().create(phrase, auth, imported = false)
        val relaunched = vault()
        assertTrue(relaunched.state.value is Vault.State.Locked)
        try {
            relaunched.withSeed { }
            fail("seed while locked")
        } catch (_: VaultLockedException) {
        }
        relaunched.unlock(auth)
        assertEquals(listOf(VaultAuthPurpose.CREATE, VaultAuthPurpose.UNLOCK), auth.asked)
        assertArrayEquals(phrase.seed(), relaunched.withSeed { it.copyOf() })
    }

    @Test
    fun `withSeed hands out a copy`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.withSeed { it.fill(0) }
        assertArrayEquals(phrase.seed(), v.withSeed { it.copyOf() })
    }

    @Test
    fun `lock forgets the seed`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.lock()
        assertTrue(v.state.value is Vault.State.Locked)
        try {
            v.withSeed { }
            fail("seed after lock")
        } catch (_: VaultLockedException) {
        }
    }

    @Test
    fun `idle for 15 minutes locks, wallet activity keeps it open`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        now += 14 * 60_000L
        v.noteActivity()
        now += 14 * 60_000L
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Unlocked)
        v.withSeed { } // counts as activity too
        now += 15 * 60_000L - 1
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Unlocked)
        now += 1
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `a Swarm signing key is derived without holding the idle lock off (#236)`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        val identity = PublisherIdentity(PublisherIdentity.Mode.APP_SCOPED, 0, "App-scoped identity 1", 1)
        // A page polling an always-allowed swarm_getSigningIdentity every 10 s.
        repeat(89) {
            now += 10_000L
            PublisherKeys.signingKey(v, identity).fill(0)
        }
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Unlocked)
        now += 10_000L
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `seed is refused once idle time is up even before the timer fires`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        now += 15 * 60_000L
        try {
            v.withSeed { }
            fail("seed after idle timeout")
        } catch (_: VaultLockedException) {
        }
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `a quick app switch keeps it open, a minute away locks it`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.onAppBackground()
        now += 59_000L
        v.onAppForeground()
        assertTrue(v.state.value is Vault.State.Unlocked)
        v.onAppBackground()
        now += 60_000L
        v.onAppForeground()
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `dapp activity in the background doesn't extend the grace`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.onAppBackground()
        now += 50_000L
        v.noteActivity()
        now += 10_000L
        v.lockIfExpired()
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `a lost key is reported as such`() = runBlocking {
        vault().create(phrase, auth, imported = false)
        store.key = null // e.g. the screen lock was removed
        val v = vault()
        try {
            v.unlock(auth)
            fail("unlocked")
        } catch (_: VaultKeyLostException) {
        }
        assertTrue(v.state.value is Vault.State.Locked)
    }

    @Test
    fun `an unreadable file is its own state, and Remove clears it`() = runBlocking {
        store.fileExists = true
        val v = vault()
        assertEquals(Vault.State.Unreadable, v.state.value)
        v.remove()
        assertEquals(Vault.State.Empty, v.state.value)
    }

    @Test
    fun `remove wipes the phrase and the key`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.remove()
        assertEquals(Vault.State.Empty, v.state.value)
        assertNull(store.record)
        assertNull(store.key)
        assertEquals(Vault.State.Empty, vault().state.value)
    }

    @Test
    fun `remove finishes even if its caller is cancelled during the wipe`() = runBlocking {
        val v = Vault(store, scope, clock = { now }, io = Dispatchers.IO, compute = Dispatchers.Unconfined)
        v.create(phrase, auth, imported = false)
        v.lock()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        store.onWipe = {
            entered.countDown()
            release.await()
        }
        // The Wallet page's scope, torn down while Keystore deletes the key.
        val derivedWiped = java.util.concurrent.atomic.AtomicBoolean(false)
        val page = CoroutineScope(Dispatchers.Default).launch { v.remove(alsoWipe = { derivedWiped.set(true) }) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        page.cancel()
        release.countDown()
        page.join()
        assertNull(store.record)
        assertEquals(Vault.State.Empty, v.state.value)
        // What was derived from it (publisher identities) goes too, cancelled or not.
        assertTrue(derivedWiped.get())
    }

    @Test
    fun `reveal asks every time and markBackedUp clears the reminder`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        assertEquals(phrase, v.revealMnemonic(auth))
        assertEquals(phrase, v.revealMnemonic(auth))
        assertEquals(listOf(VaultAuthPurpose.CREATE, VaultAuthPurpose.REVEAL, VaultAuthPurpose.REVEAL), auth.asked)
        v.markBackedUp()
        assertTrue((v.state.value as Vault.State.Unlocked).info.backedUp)
        assertTrue((vault().state.value as Vault.State.Locked).info.backedUp)
    }

    @Test
    fun `an auto-lock landing while markBackedUp writes stays locked`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        // The timer fires between the record write and the state update.
        store.onWrite = { v.lock() }
        v.markBackedUp()
        val s = v.state.value
        assertTrue(s is Vault.State.Locked)
        assertTrue((s as Vault.State.Locked).info.backedUp)
        assertFalse(v.unlockedNow())
    }

    @Test
    fun `requireUnlocked doesn't trust an Unlocked state past its deadline`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        assertTrue(v.unlockedNow())
        // Deep sleep: the idle time is up but the timer hasn't run yet.
        now += 15 * 60_000L
        assertTrue(v.state.value is Vault.State.Unlocked)
        val waiting = async(Dispatchers.Unconfined) { v.requireUnlocked("Publishing needs a wallet") }
        yield()
        assertTrue(v.state.value is Vault.State.Locked)
        assertFalse(waiting.isCompleted)
        v.setupRequest.value!!.finish(false)
        assertFalse(waiting.await())
    }

    @Test
    fun `only one vault per device`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        try {
            v.create(Mnemonic.generate(), auth, imported = false)
            fail("second vault")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(phrase.seed(), v.withSeed { it.copyOf() })
    }

    @Test
    fun `requireUnlocked - at once when open, else via the wallet page`() = runBlocking {
        val v = vault()
        val waiting = async(Dispatchers.Unconfined) { v.requireUnlocked("Publishing needs a wallet") }
        yield()
        val request = v.setupRequest.value!!
        assertEquals("Publishing needs a wallet", request.reason)
        request.finish(false)
        assertFalse(waiting.await())
        assertNull(v.setupRequest.value)

        v.create(phrase, auth, imported = false)
        assertTrue(v.requireUnlocked("again"))
        assertNull(v.setupRequest.value)
    }

    @Test
    fun `requireUnlocked - a cancelled caller doesn't strand another, and both reasons show`() = runBlocking {
        val v = vault()
        val first = async(Dispatchers.Unconfined) { v.requireUnlocked("Publishing needs a wallet") }
        val second = async(Dispatchers.Unconfined) { v.requireUnlocked("Payments need a wallet") }
        yield()
        assertEquals(listOf("Publishing needs a wallet", "Payments need a wallet"), v.setupRequest.value!!.reasons)
        // The same reason again joins without repeating the line.
        val third = async(Dispatchers.Unconfined) { v.requireUnlocked("Payments need a wallet") }
        yield()
        assertEquals(2, v.setupRequest.value!!.reasons.size)

        first.cancel()
        yield()
        // The page stays up for the callers still waiting…
        val request = v.setupRequest.value!!
        assertFalse(second.isCompleted)
        // …and its answer reaches them.
        v.create(phrase, auth, imported = false)
        request.finish(true)
        assertTrue(second.await())
        assertTrue(third.await())
        assertNull(v.setupRequest.value)
    }

    @Test
    fun `requireUnlocked - the page closes once every caller is gone`() = runBlocking {
        val v = vault()
        val a = async(Dispatchers.Unconfined) { v.requireUnlocked("A") }
        val b = async(Dispatchers.Unconfined) { v.requireUnlocked("B") }
        yield()
        a.cancel()
        yield()
        assertEquals(listOf("A", "B"), v.setupRequest.value!!.reasons)
        b.cancel()
        yield()
        assertNull(v.setupRequest.value)
        // A later call starts afresh, with only its own reason.
        val c = async(Dispatchers.Unconfined) { v.requireUnlocked("C") }
        yield()
        assertEquals(listOf("C"), v.setupRequest.value!!.reasons)
        v.setupRequest.value!!.finish(false)
        assertFalse(c.await())
        assertNull(v.setupRequest.value)
    }

    @Test
    fun `record round-trips and rejects other versions`() {
        val r = VaultRecord(VaultProtection.SCREEN_LOCK, true, byteArrayOf(1, 2, 3), byteArrayOf(4, 5), backedUp = false)
        val back = VaultRecord.decode(r.encode())!!
        assertEquals(VaultProtection.SCREEN_LOCK, back.protection)
        assertTrue(back.strongBox)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.iv)
        assertArrayEquals(byteArrayOf(4, 5), back.ciphertext)
        assertNull(VaultRecord.decode(r.encode().replace("\"version\":1", "\"version\":2")))
        assertNull(VaultRecord.decode("not json"))
    }
}
