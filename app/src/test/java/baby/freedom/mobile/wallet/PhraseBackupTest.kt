package baby.freedom.mobile.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Google backup of the recovery phrase (#231) behind a fake Block Store:
 * what gets stored and when, the cloud flag following end-to-end
 * encryption, restore, turning it off, and Remove wallet.
 */
class PhraseBackupTest {
    /** Block Store as the app sees it: entries by key, each with the cloud flag it was stored with. */
    class FakeBlockStore(var e2ee: Boolean = true) : BlockStorePort {
        class Stored(val bytes: ByteArray, val cloud: Boolean)

        val entries = mutableMapOf<String, Stored>()
        var reachable = true
        var stores = 0
        var failDelete = false

        private fun reach() {
            if (!reachable) throw BackupUnavailableException("Google Play services isn’t available")
        }

        override suspend fun endToEndEncryptionAvailable(): Boolean {
            reach()
            return e2ee
        }

        override suspend fun store(key: String, bytes: ByteArray, backupToCloud: Boolean) {
            reach()
            stores++
            // Copied, as Play services parcels it: the caller zeroes its own array.
            entries[key] = Stored(bytes.copyOf(), backupToCloud)
        }

        override suspend fun retrieve(key: String): ByteArray? {
            reach()
            return entries[key]?.bytes?.copyOf()
        }

        override suspend fun delete(key: String) {
            reach()
            if (failDelete) throw BackupUnavailableException("Google Play services didn’t answer")
            entries.remove(key)
        }

        fun entry(): Stored? = entries[PhraseBackup.KEY]
        fun text(): String? = entry()?.bytes?.toString(Charsets.UTF_8)
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val store = VaultTest.FakeStore()
    private val auth = VaultTest.FakeAuth()
    private val blockStore = FakeBlockStore()
    private val backup = PhraseBackup(blockStore)

    private fun vault() = Vault(store, scope, clock = { 1_000_000L }, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)

    private val phrase = Mnemonic.parse(
        "void come effort suffer camp survey warrior heavy shoot primary clutch crush " +
            "open amazing screen patrol group space point ten exist slush involve unfold",
    )

    @After
    fun tearDown() = job.cancel()

    private fun info(v: Vault) = when (val s = v.state.value) {
        is Vault.State.Locked -> s.info
        is Vault.State.Unlocked -> s.info
        else -> null
    }

    // ---- PhraseBackup on its own ----

    @Test
    fun `availability follows Block Store's end-to-end encryption, and its absence`() = runBlocking {
        assertEquals(PhraseBackup.Availability.READY, backup.availability())
        blockStore.e2ee = false
        assertEquals(PhraseBackup.Availability.NOT_ENCRYPTED, backup.availability())
        blockStore.reachable = false
        assertEquals(PhraseBackup.Availability.UNSUPPORTED, backup.availability())
        assertNull(backup.exists())
    }

    @Test
    fun `stores for the cloud only when it will be end-to-end encrypted`() = runBlocking {
        blockStore.e2ee = false
        try {
            backup.store(phrase)
            fail("stored without end-to-end encryption")
        } catch (_: BackupNotEncryptedException) {
        }
        assertNull(blockStore.entry())
        assertEquals(0, blockStore.stores)

        blockStore.e2ee = true
        backup.store(phrase)
        assertTrue(blockStore.entry()!!.cloud)
        assertEquals(phrase.words, backup.read()!!.words)
        assertEquals(true, backup.exists())
    }

    @Test
    fun `reconcile takes the cloud copy down while encryption is gone, and puts it back`() = runBlocking {
        assertEquals(PhraseBackup.Status.NONE, backup.reconcile())
        backup.store(phrase)
        assertEquals(PhraseBackup.Status.CLOUD, backup.reconcile())
        assertEquals("nothing changed, nothing rewritten", 1, blockStore.stores)

        // The screen lock was removed: Block Store would upload it unencrypted.
        blockStore.e2ee = false
        assertEquals(PhraseBackup.Status.PAUSED, backup.reconcile())
        assertFalse(blockStore.entry()!!.cloud)
        assertEquals(2, blockStore.stores)
        assertEquals(PhraseBackup.Status.PAUSED, backup.reconcile())
        assertEquals(2, blockStore.stores)
        // Still on the phone, still restorable.
        assertEquals(phrase.words, backup.read()!!.words)

        blockStore.e2ee = true
        assertEquals(PhraseBackup.Status.CLOUD, backup.reconcile())
        assertTrue(blockStore.entry()!!.cloud)
        assertEquals(3, blockStore.stores)
    }

    @Test
    fun `reconcileQuietly never throws for a background caller`() = runBlocking {
        backup.store(phrase)
        blockStore.reachable = false
        assertNull(backup.reconcileQuietly())
        blockStore.reachable = true
        blockStore.entries[PhraseBackup.KEY] = FakeBlockStore.Stored("junk".toByteArray(), true)
        assertNull(backup.reconcileQuietly())
    }

    @Test
    fun `an unreadable entry never quotes its bytes`() = runBlocking {
        val secretish = "void come effort suffer {not json"
        blockStore.entries[PhraseBackup.KEY] = FakeBlockStore.Stored(secretish.toByteArray(), true)
        try {
            backup.read()
            fail("read junk")
        } catch (e: BackupUnreadableException) {
            assertNull(e.cause)
            assertFalse(e.message!!.contains("void"))
        }
        // Valid JSON, but not a phrase.
        blockStore.entries[PhraseBackup.KEY] = FakeBlockStore.Stored(
            PhraseBackup.encode(PhraseBackup.Entry("void come effort", true)), true,
        )
        try {
            backup.read()
            fail("read a non-phrase")
        } catch (e: BackupUnreadableException) {
            assertNull(e.cause)
        }
        // A future version isn't guessed at.
        blockStore.entries[PhraseBackup.KEY] = FakeBlockStore.Stored(
            """{"version":2,"phrase":"${phrase.phrase()}","cloud":true}""".toByteArray(), true,
        )
        try {
            backup.read()
            fail("read version 2")
        } catch (_: BackupUnreadableException) {
        }
    }

    // ---- Through the vault ----

    @Test
    fun `turning backup on asks the user, stores the phrase and records it`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        assertEquals(false, info(v)!!.cloudBackup)
        assertEquals(false, info(v)!!.cloudBackupOffered)
        assertNull(blockStore.entry())

        v.enableCloudBackup(auth, backup)
        assertEquals(listOf(VaultAuthPurpose.CREATE, VaultAuthPurpose.BACKUP), auth.asked)
        assertTrue(info(v)!!.cloudBackup)
        assertTrue(info(v)!!.cloudBackupOffered)
        assertTrue(store.record!!.cloudBackup)
        assertTrue(blockStore.entry()!!.cloud)
        assertEquals(phrase.words, backup.read()!!.words)
        // The vault file on disk still never holds the words.
        assertFalse(store.record!!.encode().contains("void come"))
    }

    @Test
    fun `a cancelled prompt or no encryption leaves backup off and nothing stored`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        auth.cancel = true
        try {
            v.enableCloudBackup(auth, backup)
            fail("enabled without the user")
        } catch (_: VaultAuthCancelledException) {
        }
        auth.cancel = false
        blockStore.e2ee = false
        try {
            v.enableCloudBackup(auth, backup)
            fail("enabled without encryption")
        } catch (_: BackupNotEncryptedException) {
        }
        assertNull(blockStore.entry())
        assertFalse(info(v)!!.cloudBackup)
    }

    @Test
    fun `if recording it fails the entry is taken out again`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        store.onWrite = { error("disk full") }
        try {
            v.enableCloudBackup(auth, backup)
            fail("claimed on")
        } catch (_: IllegalStateException) {
        }
        assertNull(blockStore.entry())
        // (The fake keeps the record it failed on; the page goes by the published state.)
        assertFalse(info(v)!!.cloudBackup)
    }

    @Test
    fun `turning backup off deletes the entry before saying off`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.enableCloudBackup(auth, backup)

        blockStore.failDelete = true
        try {
            v.disableCloudBackup(backup)
            fail("said off with the entry still there")
        } catch (_: BackupUnavailableException) {
        }
        assertTrue(info(v)!!.cloudBackup)
        assertTrue(blockStore.entry() != null)

        blockStore.failDelete = false
        v.disableCloudBackup(backup)
        assertNull(blockStore.entry())
        assertFalse(info(v)!!.cloudBackup)
        assertFalse(store.record!!.cloudBackup)
    }

    @Test
    fun `Not now answers the offer and turns nothing on`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = true)
        v.markCloudBackupOffered()
        assertTrue(info(v)!!.cloudBackupOffered)
        assertFalse(info(v)!!.cloudBackup)
        assertNull(blockStore.entry())
        // Survives the file round trip, like the other flags.
        val back = VaultRecord.decode(store.record!!.encode())!!
        assertTrue(back.cloudBackupOffered)
        assertFalse(back.cloudBackup)
    }

    @Test
    fun `a fresh install restores the backed-up wallet behind the screen lock`() = runBlocking {
        // The old install backed it up...
        val old = vault()
        old.create(phrase, auth, imported = false)
        old.enableCloudBackup(auth, backup)
        val seed = phrase.seed()
        // ...then the app was reinstalled: the vault file and key are gone, Block Store kept its entry.
        store.wipe()
        auth.asked.clear()

        val v = vault()
        assertEquals(Vault.State.Empty, v.state.value)
        assertEquals(true, backup.exists())
        v.restore(auth, backup)
        assertEquals(listOf(VaultAuthPurpose.RESTORE), auth.asked)
        assertEquals(VaultProtection.SCREEN_LOCK, store.keyProtection)
        val restored = (v.state.value as Vault.State.Unlocked).info
        assertTrue(restored.cloudBackup)
        assertTrue(restored.cloudBackupOffered)
        assertTrue("restored like an import: no reminder", restored.backedUp)
        assertTrue(v.withSeed { it.contentEquals(seed) })
        // The entry stays: it's this wallet's backup now.
        assertTrue(blockStore.entry() != null)
    }

    @Test
    fun `restore needs a screen lock and doesn't read the phrase without one`() = runBlocking {
        backup.store(phrase)
        store.secure = false
        blockStore.reachable = false // proves Block Store isn't even asked
        val v = vault()
        try {
            v.restore(auth, backup)
            fail("restored with no screen lock")
        } catch (_: RestoreNeedsScreenLockException) {
        }
        assertEquals(Vault.State.Empty, v.state.value)
        assertTrue(auth.asked.isEmpty())
    }

    @Test
    fun `a cancelled restore leaves no wallet and keeps the backup`() = runBlocking {
        backup.store(phrase)
        val v = vault()
        auth.cancel = true
        try {
            v.restore(auth, backup)
            fail("restored without the user")
        } catch (_: VaultAuthCancelledException) {
        }
        assertEquals(Vault.State.Empty, v.state.value)
        assertNull(store.record)
        assertTrue(blockStore.entry() != null)
    }

    @Test
    fun `restore with the entry gone meanwhile says so`() = runBlocking {
        val v = vault()
        try {
            v.restore(auth, backup)
            fail("restored nothing")
        } catch (_: BackupMissingException) {
        }
        assertEquals(Vault.State.Empty, v.state.value)
    }

    @Test
    fun `Remove wallet's wipe deletes the entry`() = runBlocking {
        val v = vault()
        v.create(phrase, auth, imported = false)
        v.enableCloudBackup(auth, backup)
        // As the wallet page calls it: the backup goes inside remove()'s own wipe.
        v.remove(alsoWipe = { backup.delete() })
        assertEquals(Vault.State.Empty, v.state.value)
        assertNull(blockStore.entry())
        assertEquals(false, backup.exists())
    }

    @Test
    fun `flags written before 231 read as off and not yet offered`() {
        val old = """{"version":1,"protection":"screen-lock","strongBox":false,"iv":"AQID","ciphertext":"BAU=","backedUp":true}"""
        val r = VaultRecord.decode(old)!!
        assertFalse(r.cloudBackup)
        assertFalse(r.cloudBackupOffered)
        assertTrue(r.backedUp)
        val round = VaultRecord.decode(r.copy(cloudBackup = true, cloudBackupOffered = true).encode())!!
        assertTrue(round.cloudBackup)
        assertTrue(round.cloudBackupOffered)
        assertTrue(round.backedUp)
    }
}
