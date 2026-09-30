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
 * The derived node identities on disk (#77, #328): sealed, tied to the
 * vault they came from, and kept in step with the wallet — adopted on
 * create, import and a pre-#77 wallet's first unlock, completed with the
 * Radicle key on a pre-#328 wallet's first unlock, left alone on a plain
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
        sync.setOnChanged { changes += it }
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
        assertEquals(NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", ABANDON_DID), reconcile())
        val tag = vault.identityTag()!!
        assertEquals(tag, store.storedTag())
        val read = store.read(tag)!!
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", read.swarmAddress)
        assertEquals("12D3KooWKavfSLKnBEoUdrcsZKHE2tCWxrPkND6psrNRyL8DgtYW", read.peerId)
        assertEquals(ABANDON_DID, read.radicleDid)
        // No key is on disk in the clear.
        val onDisk = file.readText()
        assertFalse(onDisk.contains("9a983cb3d832fbde5ab49d692b7a8bf5b5d232479c99333d0fc8e1d21f1b55b6"))
        assertFalse(onDisk.contains("6d7c32198dff963096b93296acb383c9b4f2bd85a4e52c123bfee1e5cd00c749"))
        assertFalse(onDisk.contains(ABANDON_RADICLE_KEY))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(String(read.swarmKey, Charsets.ISO_8859_1)))
        assertFalse(raw.contains(String(read.radicleKey!!, Charsets.ISO_8859_1)))
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
        assertEquals(NodeIdentitySync.Change.Adopted("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C", LEGAL_DID), reconcile())
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
        assertEquals(NodeIdentitySync.Change.Adopted("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C", LEGAL_DID), reconcile())
        assertNull(store.read(oldTag))
        assertEquals("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C", store.read(newTag)!!.swarmAddress)
    }

    @Test
    fun `this vault's keys that can't be opened are sealed again, but that's no identity change`() = runBlocking {
        var takenBack = 0
        val withGrants = NodeIdentitySync(vault, store, scope, io = Dispatchers.Unconfined, beforeRadicleChange = { takenBack++ })
        vault.create(abandon12, auth, imported = false)
        withGrants.reconcile(vault.state.value)
        assertEquals(1, takenBack)
        // The sealing key is gone (or the Keystore failed once): unreadable.
        keys.key = null
        assertNull(store.read(vault.identityTag()!!))
        vault.lock()
        vault.unlock(auth)
        // Same seed, same identities: no grants taken back, no restart, no notice.
        assertNull(withGrants.reconcile(vault.state.value))
        assertEquals(1, takenBack)
        // Healed: the same keys, readable again.
        val healed = store.read(vault.identityTag()!!)!!
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", healed.swarmAddress)
        assertEquals(ABANDON_DID, healed.radicleDid)
    }

    @Test
    fun `this vault's version-1 keys that can't be opened get the new Radicle identity, keeping Swarm`() = runBlocking {
        var takenBack = 0
        val withGrants = NodeIdentitySync(vault, store, scope, io = Dispatchers.Unconfined, beforeRadicleChange = { takenBack++ })
        vault.create(abandon12, auth, imported = false)
        writeVersion1(vault.identityTag()!!, NodeIdentity.derive(abandon12.seed()))
        keys.key = null
        // The Radicle identity really is new (the node ran as its own key), the Swarm one isn't.
        assertEquals(
            NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", ABANDON_DID, swarmChanged = false),
            withGrants.reconcile(vault.state.value),
        )
        assertEquals(1, takenBack)
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
    fun `radicle hands the node the wallet's Radicle key, while locked`() = runBlocking {
        assertNull(store.radicle(vaultStore))
        vault.create(abandon12, auth, imported = false)
        reconcile()
        vault.lock()
        val host = store.radicle(vaultStore)!!
        assertEquals(ABANDON_DID, host.did)
        // The secret is the swarmnode module's own (internal); read it the long way.
        val secret = host.javaClass.getDeclaredField("secret").apply { isAccessible = true }.get(host) as ByteArray
        assertEquals(ABANDON_RADICLE_KEY, secret.joinToString("") { "%02x".format(it) })
        // Only the DID is printed.
        assertEquals("HostIdentity($ABANDON_DID)", host.toString())
        host.wipe()
        assertTrue(secret.all { it == 0.toByte() })
        // Once the wallet is gone the node runs as its own again.
        vaultStore.record = null
        assertNull(store.radicle(vaultStore))
    }

    @Test
    fun `radicle throws, rather than answering no wallet, when the wallet's keys can't be opened`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        reconcile()
        vault.lock()
        fun unreadable() = runCatching { store.radicle(vaultStore) }.exceptionOrNull() is IllegalStateException
        // The sealing key is gone (a Keystore failure): not "no wallet".
        val sealing = keys.key
        keys.key = null
        assertTrue(unreadable())
        keys.key = sealing
        // The file is this vault's but corrupt.
        val good = file.readText()
        file.writeText("{not json")
        assertTrue(unreadable())
        file.writeText(good)
        // The vault file is there but can't be read.
        val record = vaultStore.record
        vaultStore.record = null
        vaultStore.fileExists = true
        assertTrue(unreadable())
        vaultStore.fileExists = false
        vaultStore.record = record
        assertEquals(ABANDON_DID, store.radicle(vaultStore)!!.did)
        // Keys from another (replaced) wallet, or none yet, are plainly "none".
        vault.remove()
        vault.create(legal12, auth, imported = true)
        assertNull(store.radicle(vaultStore))
        store.wipe()
        assertNull(store.radicle(vaultStore))
    }

    @Test
    fun `keys stored before Radicle keep booting Swarm and get the Radicle key on the next unlock`() = runBlocking {
        vault.create(abandon12, auth, imported = false)
        val tag = vault.identityTag()!!
        writeVersion1(tag, NodeIdentity.derive(abandon12.seed()))
        vault.lock()
        // While locked: Swarm boots as the wallet, Radicle as its own key.
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", store.boot(vaultStore)!!.swarmAddress)
        assertNull(store.radicle(vaultStore))
        assertNull(store.read(tag)!!.radicleKey)
        // The next unlock adds the Radicle key; the Swarm account stays.
        vault.unlock(auth)
        assertEquals(
            NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", ABANDON_DID, swarmChanged = false),
            reconcile(),
        )
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("version"))
        assertEquals(ABANDON_DID, store.radicle(vaultStore)!!.did)
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", store.boot(vaultStore)!!.swarmAddress)
        // And then it's complete: a further unlock is a no-op.
        vault.lock()
        vault.unlock(auth)
        assertNull(reconcile())
    }

    /** The node identity file as #77 (version 1) wrote it: Swarm and IPFS keys only. */
    private fun writeVersion1(tag: String, identity: NodeIdentity) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keys.key(create = true))
        cipher.updateAAD(tag.toByteArray())
        val sealed = cipher.doFinal(identity.swarmKey + identity.ipfsKey)
        identity.wipe()
        file.parentFile!!.mkdirs()
        file.writeText(
            org.json.JSONObject()
                .put("version", 1)
                .put("vault", tag)
                .put("iv", java.util.Base64.getEncoder().encodeToString(cipher.iv))
                .put("sealed", java.util.Base64.getEncoder().encodeToString(sealed))
                .toString(),
        )
    }

    @Test
    fun `sites' Radicle signing grants are taken back before the identity changes`() = runBlocking {
        // What the store held each time the grants were taken back.
        val seen = mutableListOf<String?>()
        val withGrants = NodeIdentitySync(vault, store, scope, io = Dispatchers.Unconfined, beforeRadicleChange = {
            seen += store.storedTag()
        })
        vault.create(abandon12, auth, imported = false)
        withGrants.reconcile(vault.state.value)
        // Before the keys were written: nothing stored yet.
        assertEquals(listOf<String?>(null), seen)
        // A plain unlock changes nothing, so nothing is taken back.
        vault.lock()
        vault.unlock(auth)
        assertNull(withGrants.reconcile(vault.state.value))
        assertEquals(1, seen.size)
        // Remove: before the keys are wiped.
        val tag = vault.identityTag()
        vault.remove()
        assertEquals(NodeIdentitySync.Change.Dropped, withGrants.reconcile(vault.state.value))
        assertEquals(listOf(null, tag), seen)
    }

    @Test
    fun `a grant store that can't be written doesn't hold up an adoption or a removal`() = runBlocking {
        var calls = 0
        val flaky = NodeIdentitySync(vault, store, scope, io = Dispatchers.Unconfined, beforeRadicleChange = {
            calls++
            check(false) { "grant store unwritable" }
        })
        vault.create(abandon12, auth, imported = false)
        // Adopted anyway (grants name their DID, so none is honored for the
        // new identity): the Swarm node gets the wallet's account now, not
        // at some later vault change.
        val adopted = flaky.reconcile(vault.state.value) as NodeIdentitySync.Change.Adopted
        assertTrue(adopted.swarmChanged)
        assertFalse(store.isEmpty())
        assertEquals(1, calls)
        // A removal wipes the keys regardless.
        vault.remove()
        assertEquals(NodeIdentitySync.Change.Dropped, flaky.reconcile(vault.state.value))
        assertFalse(file.exists())
        assertEquals(2, calls)
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
    fun `a finished activity's listener is let go, a newer one's is kept`() = runBlocking {
        // The sync lives as long as the process; an activity's listener
        // (which reaches the activity) must not outlive it (R1-F1).
        val old = mutableListOf<NodeIdentitySync.Change>()
        val new = mutableListOf<NodeIdentitySync.Change>()
        val oldListener: (NodeIdentitySync.Change) -> Unit = { old += it }
        val newListener: (NodeIdentitySync.Change) -> Unit = { new += it }
        sync.setOnChanged(oldListener)
        // A recreated activity takes over before the old one is destroyed.
        sync.setOnChanged(newListener)
        sync.clearOnChanged(oldListener)
        vault.create(abandon12, auth, imported = false)
        reconcile()
        assertEquals(emptyList<NodeIdentitySync.Change>(), old)
        assertEquals(1, new.size)
        // The last activity finishing leaves nothing behind.
        sync.clearOnChanged(newListener)
        vault.remove()
        assertEquals(NodeIdentitySync.Change.Dropped, reconcile())
        assertEquals(1, new.size)
        assertEquals(emptyList<NodeIdentitySync.Change>(), changes)
    }

    @Test
    fun `notices say what changed and whether the node restarts`() {
        val adopted = NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", ABANDON_DID)
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

    @Test
    fun `notices mention the Radicle node only while it's on`() {
        val adopted = NodeIdentitySync.Change.Adopted("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", ABANDON_DID)
        val kept = "Repositories it seeds stay seeded; what you published before stays signed by this device's own identity."
        assertEquals(
            "Your Swarm node now uses your wallet's identity (0x6Fac…b9C0). Restarting it… " +
                "Your Radicle node is restarting as your wallet's identity (z6Mkgb…8gAn). $kept",
            nodeIdentityNotice(adopted, restarting = true, radicleOn = true, radicleRestarting = true),
        )
        assertEquals(
            "Your Swarm node will use your wallet's identity (0x6Fac…b9C0) when it next starts. " +
                "Your Radicle node will use your wallet's identity (z6Mkgb…8gAn) when it next starts. $kept",
            nodeIdentityNotice(adopted, restarting = false, radicleOn = true, radicleRestarting = false),
        )
        // A pre-#328 wallet's upgrade: only Radicle changed.
        val radicleOnly = adopted.copy(swarmChanged = false)
        assertEquals(
            "Your Radicle node is restarting as your wallet's identity (z6Mkgb…8gAn). $kept",
            nodeIdentityNotice(radicleOnly, restarting = true, radicleOn = true, radicleRestarting = true),
        )
        // ...and with Radicle off there's nothing to say.
        assertNull(nodeIdentityNotice(radicleOnly, restarting = true, radicleOn = false))
        assertEquals(
            "Wallet removed. Your Swarm node is restarting with this device's own identity. " +
                "Your Radicle node is restarting with this device's own identity.",
            nodeIdentityNotice(NodeIdentitySync.Change.Dropped, restarting = true, radicleOn = true, radicleRestarting = true),
        )
        assertEquals(
            "Wallet removed. Your Swarm node will use this device's own identity. " +
                "Your Radicle node will use this device's own identity.",
            nodeIdentityNotice(NodeIdentitySync.Change.Dropped, restarting = false, radicleOn = true),
        )
    }

    private companion object {
        /** Desktop's Radicle DID and key for `abandon ×11 about` (see NodeIdentityTest). */
        const val ABANDON_DID = "did:key:z6Mkgb93MjdiDEUrHVCY2X4EfaSwzoFCorViqqPnjoQX8gAn"
        const val ABANDON_RADICLE_KEY = "b262e62fc6a558fd045ca68dd7000e30a135f678bc0816935e13ebe6a97e14bd"
        const val LEGAL_DID = "did:key:z6MkeUQ9N8WURkVFFn8jrdrmTQBWjLdrqwBsNUjtFVFTuEZi"
    }
}
