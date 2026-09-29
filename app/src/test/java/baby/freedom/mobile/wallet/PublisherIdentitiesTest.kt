package baby.freedom.mobile.wallet

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publisher identities (#119): the per-site store — create, switch,
 * never reuse a key index, tied to the wallet it was made under — and
 * the keys behind them, against desktop's derivation.
 */
class PublisherIdentitiesTest {
    private val dir: File = Files.createTempDirectory("publisher-identities").toFile()
    private val file = File(dir, "wallet/publisher-identities.json")
    private var tag: String? = "vault-a"
    private var now = 1_000L
    private val store = PublisherIdentityStore(file, { tag }, { now++ })

    private val siteA = "https://a.example"
    private val siteB = "https://b.example"

    @Test
    fun `a site first gets an app-scoped identity of its own`() {
        assertNull(store.site(siteA))
        val a = store.ensureSite(siteA)
        assertEquals("app-scoped:0", a.activeId)
        assertEquals("App-scoped identity 1", a.active.label)
        assertEquals("m/44'/73406'/0'/0/0", a.active.derivationPath)
        // Asking again changes nothing.
        assertEquals(a, store.ensureSite(siteA))
        // The next site gets the next key.
        assertEquals("app-scoped:1", store.ensureSite(siteB).activeId)
    }

    @Test
    fun `create makes a new identity active, and switching goes back`() {
        store.ensureSite(siteA)
        val created = store.createAppScoped(siteA, "  Blog  ")
        assertEquals("app-scoped:1", created.activeId)
        assertEquals("Blog", created.active.label)
        assertEquals(listOf("app-scoped:0", "app-scoped:1", "bee-wallet"), created.choices.map { it.id })

        val back = store.activate(siteA, "app-scoped:0")
        assertEquals("app-scoped:0", back.activeId)
        assertEquals(back, store.site(siteA))
    }

    @Test
    fun `the Ant wallet identity is always a choice and is kept once picked`() {
        val a = store.ensureSite(siteA)
        assertEquals(PublisherIdentity.ANT_WALLET_LABEL, a.choices.last().label)
        assertFalse(a.identities.any { it.mode == PublisherIdentity.Mode.ANT_WALLET })

        val ant = store.activate(siteA, PublisherIdentity.ANT_WALLET_ID)
        assertEquals(PublisherIdentity.ANT_WALLET_ID, ant.activeId)
        assertEquals(NodeIdentity.SWARM_PATH, ant.active.derivationPath)
        assertTrue(ant.identities.any { it.mode == PublisherIdentity.Mode.ANT_WALLET })
        // Switching away keeps it in the site's list.
        val away = store.activate(siteA, "app-scoped:0")
        assertEquals(2, away.identities.size)
    }

    @Test
    fun `a site can't switch to another site's identity`() {
        store.ensureSite(siteA)
        store.ensureSite(siteB)
        assertThrows(IllegalArgumentException::class.java) { store.activate(siteA, "app-scoped:1") }
        assertThrows(IllegalArgumentException::class.java) { store.activate("https://c.example", "app-scoped:0") }
    }

    @Test
    fun `labels are checked`() {
        store.ensureSite(siteA)
        assertThrows(IllegalArgumentException::class.java) { store.createAppScoped(siteA, "   ") }
        assertThrows(IllegalArgumentException::class.java) { store.createAppScoped(siteA, "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { store.createAppScoped(siteA, "é".repeat(41)) }
        assertEquals("é".repeat(40), store.createAppScoped(siteA, "é".repeat(40)).active.label)
        // Nothing was allocated for the refused ones.
        assertEquals("app-scoped:1", store.site(siteA)!!.activeId)
    }

    @Test
    fun `it survives a new store instance, most recently added site first`() {
        store.ensureSite(siteA)
        store.ensureSite(siteB)
        store.createAppScoped(siteA, "Second")
        val reopened = PublisherIdentityStore(file, { tag }, { now++ })
        assertEquals(listOf(siteB, siteA), reopened.sites().map { it.origin })
        assertEquals("Second", reopened.site(siteA)!!.active.label)
        assertEquals("app-scoped:3", reopened.ensureSite("https://c.example").activeId)
    }

    @Test
    fun `the file uses desktop's field names`() {
        store.ensureSite(siteA)
        store.activate(siteA, PublisherIdentity.ANT_WALLET_ID)
        val o = JSONObject(file.readText())
        assertEquals(1, o.getInt("nextPublisherKeyIndex"))
        val entry = o.getJSONObject("origins").getJSONObject(siteA)
        assertEquals("bee-wallet", entry.getString("activeIdentityId"))
        val app = entry.getJSONObject("identities").getJSONObject("app-scoped:0")
        assertEquals("app-scoped", app.getString("mode"))
        assertEquals(0, app.getInt("publisherKeyIndex"))
        assertEquals("bee-wallet", entry.getJSONObject("identities").getJSONObject("bee-wallet").getString("mode"))
    }

    @Test
    fun `another wallet's identities are never shown or reused`() {
        store.ensureSite(siteA)
        tag = "vault-b"
        assertTrue(store.sites().isEmpty())
        assertNull(store.site(siteA))
        // The new wallet starts its own allocation.
        assertEquals("app-scoped:0", store.ensureSite(siteB).activeId)
    }

    @Test
    fun `no wallet, no identities`() {
        tag = null
        assertTrue(store.sites().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.ensureSite(siteA) }
    }

    @Test
    fun `wipe forgets every site`() {
        store.ensureSite(siteA)
        store.wipe()
        assertFalse(file.exists())
        assertTrue(store.sites().isEmpty())
    }

    @Test
    fun `a corrupt file is set aside, not overwritten`() {
        store.ensureSite(siteA)
        val original = file.readText()
        file.writeText(original.dropLast(5))
        assertTrue(store.sites().isEmpty())
        val aside = File(file.parentFile, "publisher-identities.corrupt.json")
        assertTrue(aside.exists())
        assertEquals(original.dropLast(5), aside.readText())
        // A deeply nested body doesn't crash the reader either.
        file.writeText("[".repeat(100_000))
        assertTrue(store.sites().isEmpty())
        assertTrue(File(file.parentFile, "publisher-identities.corrupt-1.json").exists())
    }

    @Test
    fun `an entry that doesn't hold together is set aside`() {
        store.ensureSite(siteA)
        val o = JSONObject(file.readText())
        o.getJSONObject("origins").getJSONObject(siteA).put("activeIdentityId", "app-scoped:9")
        file.writeText(o.toString())
        assertTrue(store.sites().isEmpty())
        assertTrue(File(file.parentFile, "publisher-identities.corrupt.json").exists())
    }

    @Test
    fun `the next index never falls behind an index in use`() {
        store.ensureSite(siteA)
        store.createAppScoped(siteA, "Two")
        val o = JSONObject(file.readText())
        o.put("nextPublisherKeyIndex", 0)
        file.writeText(o.toString())
        assertEquals("app-scoped:2", store.ensureSite(siteB).activeId)
    }

    @Test
    fun `a file that can't be read isn't started afresh over`() {
        store.ensureSite(siteA)
        // A directory where the file should be: it exists, but reading it fails.
        file.delete()
        file.mkdirs()
        assertTrue(store.sites().isEmpty())
        assertThrows(IOException::class.java) { store.ensureSite(siteB) }
    }

    // ---- Keys: the same addresses as desktop (ethers HDNodeWallet at these paths) ----

    private val abandon12 = Mnemonic.parse(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    )
    private val abandon24 = Mnemonic.parse((List(23) { "abandon" } + "art").joinToString(" "))

    private fun app(index: Int) =
        PublisherIdentity(PublisherIdentity.Mode.APP_SCOPED, index, "x", 0)

    @Test
    fun `owner addresses match desktop's derivation`() {
        val ids = listOf(app(0), app(1), app(7), PublisherIdentity.antWallet())
        val owners12 = PublisherKeys.owners(abandon12.seed(), ids)
        assertEquals("0xA274614c1D3425568Fd100B30322287c86085220", owners12["app-scoped:0"])
        assertEquals("0xd2e7208e8026bf96945d56CBCae2f509cC6e4748", owners12["app-scoped:1"])
        assertEquals("0x568A46D10b845596702CC9a73F596Beb93dDd6cA", owners12["app-scoped:7"])
        // The Ant wallet identity is the Swarm node's own account (#77).
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", owners12["bee-wallet"])

        val owners24 = PublisherKeys.owners(abandon24.seed(), ids)
        assertEquals("0x22ebdbC445Ea91Dd71d4E227bF027d51DA72f0Db", owners24["app-scoped:0"])
        assertEquals("0xddBFA9da5E8cf122127C4C5Bc76832AF3eaaa204", owners24["app-scoped:1"])
        assertEquals("0x6A772bA193fde6c3052a234DE79f1442e480d967", owners24["app-scoped:7"])
        assertEquals("0xf785bD075874b8423D3583728a981399f31e95aA", owners24["bee-wallet"])
    }

    @Test
    fun `the signing key is the one at the identity's path, and needs an unlocked wallet`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val vault = Vault(
            VaultTest.FakeStore(), scope, clock = { 1_000_000L },
            io = kotlinx.coroutines.Dispatchers.Unconfined, compute = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        assertThrows(IllegalStateException::class.java) { PublisherKeys.signingKey(vault, app(0)) }
        kotlinx.coroutines.runBlocking { vault.create(abandon12, VaultTest.FakeAuth(), imported = true) }
        val key = PublisherKeys.signingKey(vault, app(0))
        assertEquals(
            "df966bcf0abf71a56bb1230058c13ce73a0e127f093ff77594de6a964ea521ad",
            key.joinToString("") { "%02x".format(it) },
        )
        vault.lock()
        assertThrows(VaultLockedException::class.java) { PublisherKeys.signingKey(vault, app(0)) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
