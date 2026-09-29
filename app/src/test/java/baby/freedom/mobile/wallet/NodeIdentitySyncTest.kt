package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.nodeIdentityNotice
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived node identities on disk (#77): sealed, tied to the vault
 * they came from, and kept in step with the wallet — adopted on create,
 * import and a pre-#77 wallet's first unlock, left alone on a plain
 * unlock, dropped on Remove.
 */
class NodeIdentitySyncTest {
    /** A software AES key standing in for the Keystore one. */
    private class SoftKeys : NodeIdentityStore.Keys {
        var key: SecretKey? = null
        override fun key(create: Boolean): SecretKey? {
            if (key == null && create) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return key
        }

        override fun delete() {
            key = null
        }
    }

    private val dir: File = Files.createTempDirectory("node-identity").toFile()
    private val file = File(dir, "wallet/node-identity.json")
    private val keys = SoftKeys()
    private val store = NodeIdentityStore(file, keys)

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val vaultStore = VaultTest.FakeStore()
    private val auth = VaultTest.FakeAuth()
    private val vault = Vault(vaultStore, scope, clock = { 1_000_000L }, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)
    private val sync = NodeIdentitySync(vault, store, scope, io = Dispatchers.Unconfined)
    private val changes = mutableListOf<NodeIdentitySync.Change>()

    init {
        sync.onChanged = { changes += it }
    }

    private val abandon12 = Mnemonic.parse(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    )
    private val legal12 = Mnemonic.parse("legal winner thank year wave sausage worth useful legal winner thank yellow")

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    private suspend fun reconcile() = sync.reconcile(vault.state.value)

    @Test
    fun `create adopts the derived identity and seals it`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        assertEquals(NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"), reconcile())
        val tag = vault.identityTag()!!
        assertEquals(tag, store.storedTag())
        val read = store.read(tag)!!
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", read.swarmAddress)
        assertEquals("12D3KooWKavfSLKnBEoUdrcsZKHE2tCWxrPkND6psrNRyL8DgtYW", read.peerId)
        // Neither key is on disk in the clear.
        val onDisk = file.readText()
        assertFalse(onDisk.contains("9a983cb3d832fbde5ab49d692b7a8bf5b5d232479c99333d0fc8e1d21f1b55b6"))
        assertFalse(onDisk.contains("6d7c32198dff963096b93296acb383c9b4f2bd85a4e52c123bfee1e5cd00c749"))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(String(read.swarmKey, Charsets.ISO_8859_1)))
    }

    @Test
    fun `unlocking the same wallet again changes nothing`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        val before = file.readText()
        vault.lock()
        assertNull(reconcile())
        vault.unlock(auth)
        assertNull(reconcile())
        assertEquals(before, file.readText())
        assertEquals(1, changes.size)
    }

    @Test
    fun `a wallet from before node identities gets them on its first unlock`() = runBlocking {
        vault.create(legal12, auth, imported = true)
        vault.lock()
        assertTrue(store.isEmpty())
        vault.unlock(auth)
        assertEquals(NodeIdentitySync.Change.Adopted("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C"), reconcile())
    }

    @Test
    fun `remove drops the identity and its key`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        vault.remove()
        assertEquals(NodeIdentitySync.Change.Dropped, reconcile())
        assertFalse(file.exists())
        assertNull(keys.key)
        // Nothing to drop the next time round.
        assertNull(reconcile())
        assertEquals(2, changes.size)
    }

    @Test
    fun `a new wallet replaces a leftover identity from the old one`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        val oldTag = vault.identityTag()!!
        // Removed while the sync wasn't looking (the process died first).
        vault.remove()
        vault.create(legal12, auth, imported = true)
        val newTag = vault.identityTag()!!
        assertNotEquals(oldTag, newTag)
        // The leftover is never handed out for the new wallet.
        assertNull(store.read(newTag))
        assertNull(store.boot(vaultStore))
        assertEquals(NodeIdentitySync.Change.Adopted("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C"), reconcile())
        assertNull(store.read(oldTag))
        assertEquals("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C", store.read(newTag)!!.swarmAddress)
    }

    @Test
    fun `a lost sealing key is re-derived on the next unlock`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        keys.key = null
        assertNull(store.read(vault.identityTag()!!))
        vault.lock()
        vault.unlock(auth)
        assertNotNull(reconcile())
        assertNotNull(store.read(vault.identityTag()!!))
    }

    @Test
    fun `the sealed keys are bound to their vault tag`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        // Relabelling the file for another vault fails GCM authentication.
        val forged = file.readText().replace(vault.identityTag()!!, "00".repeat(32))
        file.writeText(forged)
        assertNull(store.read("00".repeat(32)))
    }

    @Test
    fun `boot hands the node the ant identity for the wallet on the device, while locked`() = runBlocking {
        assertNull(store.boot(vaultStore))
        vault.create(abandon12, auth, imported = false)
        reconcile()
        vault.lock()
        val boot = store.boot(vaultStore)!!
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", boot.swarmAddress)
        val json = org.json.JSONObject(String(boot.antIdentity))
        assertEquals("9a983cb3d832fbde5ab49d692b7a8bf5b5d232479c99333d0fc8e1d21f1b55b6", json.getString("signing_key"))
        assertEquals("0".repeat(64), json.getString("overlay_nonce"))
        // Once the wallet is gone the node boots as its own, even before the sync wipes.
        vaultStore.record = null
        assertNull(store.boot(vaultStore))
    }

    @Test
    fun `a locked or missing wallet derives nothing`() = runBlocking {
        assertNull(sync.reconcile(Vault.State.Empty))
        assertTrue(store.isEmpty())
        vault.create(abandon12, auth, imported = false)
        vault.lock()
        assertNull(reconcile())
        assertTrue(store.isEmpty())
    }

    @Test
    fun `mark backed up keeps the vault tag`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        val tag = vault.identityTag()
        vault.markBackedUp()
        assertEquals(tag, vault.identityTag())
    }

    @Test
    fun `notices say what changed and whether the node restarts`() {
        val adopted = NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0")
        assertEquals(
            "Your Swarm node now uses your wallet's identity (0x6Fac…b9C0). Restarting it…",
            nodeIdentityNotice(adopted, restarting = true),
        )
        assertEquals(
            "Your Swarm node will use your wallet's identity (0x6Fac…b9C0) when it next starts.",
            nodeIdentityNotice(adopted, restarting = false),
        )
        assertEquals(
            "Wallet removed. Your Swarm node is restarting with this device's own identity.",
            nodeIdentityNotice(NodeIdentitySync.Change.Dropped, restarting = true),
        )
    }
}
