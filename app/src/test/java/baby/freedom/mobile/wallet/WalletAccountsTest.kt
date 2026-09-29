package baby.freedom.mobile.wallet

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The wallet's accounts (#104): desktop's derivation path and addresses,
 * the public-only list on disk tied to its vault, verified on unlock,
 * usable while locked, and gone with Remove wallet.
 */
class WalletAccountsTest {
    private val dir: File = Files.createTempDirectory("wallet-accounts").toFile()
    private val file = File(dir, "wallet/accounts.json")
    private val store = WalletAccountStore(file)

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val vaultStore = VaultTest.FakeStore()
    private val auth = VaultTest.FakeAuth()
    private val vault = Vault(vaultStore, scope, clock = { 1_000_000L }, io = Dispatchers.Unconfined, compute = Dispatchers.Unconfined)
    private val balances = WalletBalances { error("not read here") }

    private fun accounts() = WalletAccounts(vault, store, scope, Dispatchers.Unconfined, Dispatchers.Unconfined, balances)

    private val abandon12 = Mnemonic.parse(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    )
    private val legal12 = Mnemonic.parse("legal winner thank year wave sausage worth useful legal winner thank yellow")

    // ethers v6 HDNodeWallet.fromMnemonic(m, "m/44'/60'/{i}'/0/0") — desktop's deriveUserWallet.
    private val abandonAddresses = listOf(
        "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
        "0x78839F6054d7ed13918bAe0473BA31b1Ca9D7265",
        "0x07B5FdfEB4E11826D233403Fe8Db0611CCF4c231",
    )
    private val legalAddresses = listOf(
        "0x58A57ed9d8d624cBD12e2C467D34787555bB1b25",
        "0x44f649fc8Dc77694E6ABEF1aA966983Dd6ED7C64",
        "0x35dbDb98d7e85585E516554836550fb570653C55",
    )

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `addresses match desktop's derivation for every account index`() {
        val seedA = abandon12.seed()
        val seedL = legal12.seed()
        for (i in 0..2) {
            assertEquals(abandonAddresses[i], EthAccounts.address(seedA, i))
            assertEquals(legalAddresses[i], EthAccounts.address(seedL, i))
        }
        assertEquals("m/44'/60'/1'/0/0", WalletAccount.pathFor(1))
    }

    @Test
    fun `no account is the Swarm node's key`() {
        val seed = abandon12.seed()
        val swarm = NodeIdentity.derive(seed).swarmAddress
        assertTrue((0..5).none { EthAccounts.address(seed, it) == swarm })
    }

    @Test
    fun `opening the wallet derives account 0 and saves only public data`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        val list = a.accounts.value!!
        assertEquals(listOf(WalletAccount(0, "Account 1", abandonAddresses[0])), list.accounts)
        assertEquals(abandonAddresses[0], list.active.address)
        val saved = JSONObject(file.readText())
        assertEquals(setOf("vault", "active", "accounts"), saved.keys().asSequence().toSet())
        assertEquals(setOf("index", "name", "address"), saved.getJSONArray("accounts").getJSONObject(0).keys().asSequence().toSet())
        assertEquals(vault.identityTag(), saved.getString("vault"))
    }

    @Test
    fun `add derives the next index and makes it active, select switches`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        assertEquals(WalletAccount(1, "Account 2", abandonAddresses[1]), a.add())
        assertEquals(WalletAccount(2, "Account 3", abandonAddresses[2]), a.add())
        assertEquals(2, a.accounts.value!!.activeIndex)
        a.select(0)
        assertEquals(abandonAddresses[0], a.accounts.value!!.active.address)
        a.select(7) // no such account: nothing changes
        assertEquals(0, a.accounts.value!!.activeIndex)
        assertEquals(a.accounts.value, store.read(vault.identityTag()!!))
    }

    @Test
    fun `a locked wallet shows the saved list and switches without the seed, but can't add`() = runBlocking {
        accounts().apply {
            vault.create(abandon12, auth, imported = true)
            reconcile(vault.state.value)
            add()
        }
        vault.lock()
        // A new process: the vault comes back locked.
        val a = accounts()
        a.reconcile(vault.state.value)
        assertEquals(abandonAddresses.take(2), a.accounts.value!!.accounts.map { it.address })
        a.select(0)
        assertEquals(0, store.read(vault.identityTag()!!)!!.activeIndex)
        try {
            a.add()
            fail("adding needs the seed")
        } catch (_: VaultLockedException) {
        }
        assertEquals(2, a.accounts.value!!.accounts.size)
    }

    @Test
    fun `an unlock corrects a saved address that isn't the seed's`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        a.add()
        val tag = vault.identityTag()!!
        val tampered = store.read(tag)!!.let { l ->
            l.copy(accounts = l.accounts.map { if (it.index == 1) it.copy(address = legalAddresses[1], name = "Savings") else it })
        }
        store.write(tag, tampered)
        vault.lock()
        val b = accounts()
        b.reconcile(vault.state.value)
        assertEquals(legalAddresses[1], b.accounts.value!!.accounts[1].address) // locked: what's saved
        vault.unlock(auth)
        b.reconcile(vault.state.value)
        assertEquals(WalletAccount(1, "Savings", abandonAddresses[1]), b.accounts.value!!.accounts[1])
        assertEquals(abandonAddresses[1], store.read(tag)!!.accounts[1].address)
    }

    @Test
    fun `another vault's list is ignored, and Remove wallet wipes the list and the balances`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        a.add()
        val oldTag = vault.identityTag()!!
        vault.remove()
        a.reconcile(vault.state.value)
        assertNull(a.accounts.value)
        assertTrue(!file.exists())
        assertTrue(balances.byAddress.value.isEmpty())

        // A list left behind by a different vault never shows for a new one.
        store.write(oldTag, WalletAccountList(listOf(WalletAccount(0, "Old", abandonAddresses[0])), 0))
        vault.create(legal12, auth, imported = true)
        assertNull(store.read(vault.identityTag()!!))
        a.reconcile(vault.state.value)
        assertEquals(listOf(legalAddresses[0]), a.accounts.value!!.accounts.map { it.address })
    }

    @Test
    fun `a list that can't be saved reports it and a retry recovers`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        // Where the list's folder should be, a file: every save fails (as on a full disk).
        file.parentFile!!.writeText("in the way")
        a.reconcile(vault.state.value)
        assertNull(a.accounts.value)
        assertTrue(a.syncFailed.value)
        a.retry() // still failing: still reported, never thrown
        assertTrue(a.syncFailed.value)
        file.parentFile!!.delete()
        a.retry()
        assertEquals(abandonAddresses[0], a.accounts.value!!.active.address)
        assertEquals(false, a.syncFailed.value)
    }

    @Test
    fun `a switch that can't be saved throws for the page to report, and changes nothing`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        a.add()
        val before = a.accounts.value
        file.parentFile!!.deleteRecursively()
        file.parentFile!!.writeText("in the way")
        try {
            a.select(0)
            fail("expected the failed save to throw")
        } catch (_: Exception) {
        }
        assertEquals(before, a.accounts.value)
    }

    @Test
    fun `a corrupt or malformed list reads as none`() {
        val tag = "t"
        file.parentFile!!.mkdirs()
        for (bad in listOf(
            "not json",
            """{"vault":"t","active":0,"accounts":[]}""",
            """{"vault":"t","active":0,"accounts":[{"index":0,"name":"A","address":"0x12"}]}""",
            """{"vault":"t","active":0,"accounts":[{"index":-1,"name":"A","address":"${abandonAddresses[0]}"}]}""",
            """{"vault":"t","active":0,"accounts":[{"index":0,"name":"A","address":"${abandonAddresses[0]}"},""" +
                """{"index":0,"name":"B","address":"${abandonAddresses[1]}"}]}""",
        )) {
            file.writeText(bad)
            assertNull(bad, store.read(tag))
        }
        file.writeText("""{"vault":"t","active":5,"accounts":[{"index":0,"name":"","address":"${abandonAddresses[0]}"}]}""")
        val list = store.read(tag)!!
        assertEquals("Account 1", list.active.name) // blank name → default; unknown active → first
    }

    // ---- Ledger accounts (#142) ----

    private val ledgerKey = baby.freedom.mobile.wallet.ledger.LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X 1A2B")
    private val ledgerAddress = legalAddresses[2]

    @Test
    fun `a Ledger account is added without the seed, listed with the others and saved with its device`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        vault.lock()
        val added = a.addLedger(ledgerKey, ledgerAddress, "  ")
        assertEquals(WalletAccount(-1, "Ledger 1", ledgerAddress, ledgerKey), added)
        assertEquals("m/44'/60'/0'/0/0", added.path)
        assertEquals(-1, a.accounts.value!!.activeIndex)
        assertEquals(listOf(abandonAddresses[0], ledgerAddress), a.accounts.value!!.accounts.map { it.address })
        // Read back from disk as it was written: path, device and its name.
        assertEquals(a.accounts.value, store.read(vault.identityTag()!!))
        // A second one gets the next place; the same address twice is refused.
        val key2 = ledgerKey.copy(path = "44'/60'/1'/0/0")
        assertEquals(-2, a.addLedger(key2, legalAddresses[1], "Cold").index)
        try {
            a.addLedger(key2, legalAddresses[1].lowercase(), "")
            fail("added twice")
        } catch (_: DuplicateAccountException) {
        }
        try {
            a.addLedger(ledgerKey, abandonAddresses[0], "")
            fail("one of the seed's accounts added as a Ledger's")
        } catch (_: DuplicateAccountException) {
        }
    }

    @Test
    fun `an unlock checks the seed's accounts and keeps the Ledger's as they are`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        a.addLedger(ledgerKey, ledgerAddress, "Cold")
        a.add() // a software account after it: index 1, not -1 + 1
        assertEquals(listOf(0, -1, 1), a.accounts.value!!.accounts.map { it.index })
        vault.lock()
        val b = accounts()
        b.reconcile(vault.state.value)
        vault.unlock(auth)
        b.reconcile(vault.state.value)
        val list = b.accounts.value!!
        assertEquals(listOf(abandonAddresses[0], ledgerAddress, abandonAddresses[1]), list.accounts.map { it.address })
        assertEquals(WalletAccount(-1, "Cold", ledgerAddress, ledgerKey), list.accounts[1])
    }

    @Test
    fun `the next account isn't added beside a Ledger account with its address`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        // A Ledger holding this same phrase: its Ledger Live account 1 is the seed's account 1.
        a.addLedger(ledgerKey.copy(path = "44'/60'/1'/0/0"), abandonAddresses[1], "")
        try {
            a.add()
            fail("one address listed twice")
        } catch (_: DuplicateAccountException) {
        }
        assertEquals(listOf(abandonAddresses[0], abandonAddresses[1]), a.accounts.value!!.accounts.map { it.address })
        assertEquals(a.accounts.value, store.read(vault.identityTag()!!))
        // With the Ledger's entry gone, the seed's own takes its address.
        a.removeLedger(-1)
        assertEquals(abandonAddresses[1], a.add().address)
    }

    @Test
    fun `removing a Ledger account leaves the seed's, which can't be removed`() = runBlocking {
        val a = accounts()
        vault.create(abandon12, auth, imported = true)
        a.reconcile(vault.state.value)
        a.addLedger(ledgerKey, ledgerAddress, "")
        a.removeLedger(0) // a software account: nothing happens
        assertEquals(2, a.accounts.value!!.accounts.size)
        a.removeLedger(-1)
        assertEquals(listOf(abandonAddresses[0]), a.accounts.value!!.accounts.map { it.address })
        assertEquals(0, a.accounts.value!!.activeIndex)
        assertEquals(a.accounts.value, store.read(vault.identityTag()!!))
    }

    @Test
    fun `a saved Ledger entry that isn't one is not read`() {
        val tag = "t"
        store.write(tag, WalletAccountList(listOf(WalletAccount(0, "A", abandonAddresses[0]), WalletAccount(-1, "L", ledgerAddress, ledgerKey)), 0))
        assertEquals(2, store.read(tag)!!.accounts.size)
        val text = file.readText()
        // A Ledger entry at a seed index, and one with a path outside 44'/60'.
        file.writeText(text.replace("\"index\":-1", "\"index\":3"))
        assertNull(store.read(tag))
        file.writeText(text.replace("44'/60'/0'/0/0", "44'/0'/0'/0/0"))
        assertNull(store.read(tag))
        // A list of nothing but Ledger accounts isn't one this wallet wrote.
        store.write(tag, WalletAccountList(listOf(WalletAccount(-1, "L", ledgerAddress, ledgerKey)), -1))
        assertNull(store.read(tag))
    }
}
