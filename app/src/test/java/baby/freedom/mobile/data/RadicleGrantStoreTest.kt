package baby.freedom.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Radicle grant tiers across an identity change (#328). */
class RadicleGrantStoreTest {
    private val dir: File = Files.createTempDirectory("radicle-grants").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "grants.preferences_pb") }
    private val store = RadicleGrantStore(prefs)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a new identity takes every site back to the connection tier`() = runBlocking {
        assertTrue(store.connect("https://a.example"))
        assertTrue(store.grantSigning("https://a.example", DEVICE))
        assertTrue(store.connect("https://b.example"))
        assertTrue(store.connect("https://c.example"))
        assertTrue(store.grantSigning("https://c.example", DEVICE))

        assertTrue(store.dropSigning())

        // Still connected, none signing: each asks before it learns the new
        // identity, and remembers the one it could sign as for that prompt.
        assertEquals(
            listOf(
                RadicleGrantStore.Grant("https://a.example", signedAs = DEVICE),
                RadicleGrantStore.Grant("https://b.example"),
                RadicleGrantStore.Grant("https://c.example", signedAs = DEVICE),
            ),
            store.all.first(),
        )
        assertTrue(store.all.first().none { it.signing })
        // Connecting again keeps that; a second change doesn't lose it.
        assertTrue(store.connect("https://c.example"))
        assertTrue(store.dropSigning())
        assertEquals(DEVICE, store.grantFor("https://c.example")?.signedAs)
        // And may be granted it again.
        assertTrue(store.grantSigning("https://a.example", WALLET))
        assertEquals(RadicleGrantStore.Grant("https://a.example", signingAs = WALLET), store.grantFor("https://a.example"))
        // Disconnecting forgets both.
        assertTrue(store.revoke("https://c.example"))
        assertNull(store.grantFor("https://c.example"))
    }

    @Test
    fun `a signing grant names the one identity it covers`() = runBlocking {
        assertTrue(store.connect("https://a.example"))
        assertTrue(store.grantSigning("https://a.example", DEVICE))
        val grant = store.grantFor("https://a.example")!!
        assertTrue(grant.signsAs(DEVICE))
        assertFalse("not the wallet's, even if dropSigning never ran", grant.signsAs(WALLET))
        assertFalse(grant.signsAs(""))
        // Connecting again keeps it; nothing grants signing for no identity.
        assertTrue(store.connect("https://a.example"))
        assertEquals(DEVICE, store.grantFor("https://a.example")?.signingAs)
        assertFalse(store.grantSigning("https://a.example", ""))
        assertFalse("not connected", store.grantSigning("https://b.example", DEVICE))
        assertNull(store.grantFor("https://b.example"))
    }

    @Test
    fun `a signing grant from before identities were named counts as connection, once allowed the device's`() = runBlocking {
        prefs.edit { it[stringPreferencesKey("grant:https://old.example")] = "signing" }
        // Connection only, but it could sign as the device's own identity
        // (DID not recorded), so a prompt for the wallet's can say so.
        val legacy = RadicleGrantStore.Grant("https://old.example", signedAs = "")
        assertEquals(legacy, store.grantFor("https://old.example"))
        assertFalse(legacy.signing)
        // An identity change keeps that, and so does connecting again.
        assertTrue(store.dropSigning())
        assertEquals(legacy, store.grantFor("https://old.example"))
        assertTrue(store.connect("https://old.example"))
        assertEquals(legacy, store.grantFor("https://old.example"))
        // Granted signing for a named DID, it's an ordinary grant.
        assertTrue(store.grantSigning("https://old.example", WALLET))
        assertEquals(RadicleGrantStore.Grant("https://old.example", signingAs = WALLET), store.grantFor("https://old.example"))
    }

    private companion object {
        const val DEVICE = "did:key:z6MkDevice"
        const val WALLET = "did:key:z6MkWallet"
    }

    @Test
    fun `the always-allow-writes rule rides on the grant and goes with it (#544)`() = runBlocking {
        val a = "https://a.example"
        assertTrue(store.connect(a))
        assertFalse("connection only: no rule", store.allowAutoWrites(a, DEVICE))
        assertTrue(store.grantSigning(a, DEVICE))
        assertFalse("not for another identity", store.allowAutoWrites(a, WALLET))
        assertTrue(store.allowAutoWrites(a, DEVICE))
        assertEquals(RadicleGrantStore.Grant(a, signingAs = DEVICE, autoWrites = true), store.grantFor(a))
        // Asked again for the same identity, or connected again: kept.
        assertTrue(store.grantSigning(a, DEVICE))
        assertTrue(store.connect(a))
        assertTrue(store.grantFor(a)!!.autoWrites)
        // Ask each time keeps the grant.
        assertTrue(store.stopAutoWrites(a))
        assertEquals(RadicleGrantStore.Grant(a, signingAs = DEVICE), store.grantFor(a))
        // An identity change drops it, and a grant for the new one starts without.
        assertTrue(store.allowAutoWrites(a, DEVICE))
        assertTrue(store.dropSigning())
        assertEquals(RadicleGrantStore.Grant(a, signedAs = DEVICE), store.grantFor(a))
        assertTrue(store.grantSigning(a, WALLET))
        assertFalse(store.grantFor(a)!!.autoWrites)
        // Allowed for the wallet's, then granted the device's again: gone.
        assertTrue(store.allowAutoWrites(a, WALLET))
        assertTrue(store.grantSigning(a, DEVICE))
        assertFalse(store.grantFor(a)!!.autoWrites)
        // Disconnected: gone, and nothing turns it on for a site not connected.
        assertTrue(store.allowAutoWrites(a, DEVICE))
        assertTrue(store.revoke(a))
        assertNull(store.grantFor(a))
        assertFalse(store.allowAutoWrites(a, DEVICE))
        assertNull(store.grantFor(a))
    }

    @Test
    fun `at most 50 writes without asking in any 24 hours, kept on disk (#544)`() = runBlocking {
        val a = "https://a.example"
        val day = RadicleGrantStore.DAY_MS
        val t0 = 1_000_000_000L
        repeat(RadicleGrantStore.MAX_AUTO_WRITES_PER_DAY) { assertTrue(store.countAutoWrite(a, t0 + it)) }
        assertFalse(store.countAutoWrite(a, t0 + 100))
        assertTrue("per site", store.countAutoWrite("https://b.example", t0 + 100))
        // A new store on the same file: a restart doesn't give the site a new day.
        val again = RadicleGrantStore(prefs)
        assertFalse(again.countAutoWrite(a, t0 + day - 1))
        // A day after the first, one slot is back.
        assertTrue(again.countAutoWrite(a, t0 + day))
        assertFalse(again.countAutoWrite(a, t0 + day))
        // The clock set back: the times ahead of it still count.
        assertFalse(again.countAutoWrite(a, t0 - 5 * day))
        // Disconnecting forgets the count.
        assertTrue(again.revoke(a))
        assertTrue(again.countAutoWrite(a, t0 + day))
    }

}
