package baby.freedom.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Radicle grant tiers across an identity change (#328). */
class RadicleGrantStoreTest {
    private val dir: File = Files.createTempDirectory("radicle-grants").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = RadicleGrantStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "grants.preferences_pb") },
    )

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a new identity takes every site back to the connection tier`() = runBlocking {
        assertTrue(store.connect("https://a.example"))
        assertTrue(store.grantSigning("https://a.example"))
        assertTrue(store.connect("https://b.example"))
        assertTrue(store.connect("https://c.example"))
        assertTrue(store.grantSigning("https://c.example"))

        assertTrue(store.dropSigning())

        // Still connected, none signing: each asks before it learns the new identity.
        assertEquals(
            listOf(
                RadicleGrantStore.Grant("https://a.example", signing = false),
                RadicleGrantStore.Grant("https://b.example", signing = false),
                RadicleGrantStore.Grant("https://c.example", signing = false),
            ),
            store.all.first(),
        )
        // And may be granted it again.
        assertTrue(store.grantSigning("https://a.example"))
        assertEquals(true, store.grantFor("https://a.example")?.signing)
    }
}
