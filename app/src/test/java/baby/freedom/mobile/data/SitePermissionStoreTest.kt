package baby.freedom.mobile.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Storage trouble must never escape [SitePermissionStore]: the broker
 * calls it on the main thread while a WebView permission request waits
 * for an answer, so a throw there would crash the app and leave the
 * request hanging.
 */
class SitePermissionStoreTest {

    /** A store whose reads and/or writes fail with [error]. */
    private class BrokenStore(
        private val error: IOException,
        failReads: Boolean,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            if (failReads) flow { throw error } else MutableStateFlow(emptyPreferences())

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw error
    }

    /** A working in-memory store. */
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(data.value).also { data.value = it }
    }

    @Test
    fun corruptFileReadsAsNoDecisions() = runBlocking {
        val store = SitePermissionStore(BrokenStore(CorruptionException("bad"), failReads = true))
        assertEquals(emptyList<SitePermissionStore.Record>(), store.all.first())
        assertEquals(emptyMap<String, String>(), store.decisionsFor("https://a.example"))
    }

    @Test
    fun unreadableFileReadsAsNoDecisions() = runBlocking {
        val store = SitePermissionStore(BrokenStore(IOException("io"), failReads = true))
        assertEquals(emptyMap<String, String>(), store.decisionsFor("https://a.example"))
    }

    @Test
    fun failedWritesReportFalseInsteadOfThrowing() = runBlocking {
        val store = SitePermissionStore(BrokenStore(IOException("disk full"), failReads = false))
        assertFalse(store.set("https://a.example", "camera", "allow"))
        assertFalse(store.remove("https://a.example", "camera"))
    }

    @Test
    fun workingStoreRoundTrips() = runBlocking {
        val store = SitePermissionStore(MemoryStore())
        assertTrue(store.set("https://a.example", "camera", "allow"))
        assertTrue(store.set("https://a.example", "microphone", "deny"))
        assertEquals(
            mapOf("camera" to "allow", "microphone" to "deny"),
            store.decisionsFor("https://a.example"),
        )
        assertTrue(store.remove("https://a.example", "camera"))
        assertEquals(mapOf("microphone" to "deny"), store.decisionsFor("https://a.example"))
    }

    /** Reads fail until [healed]; then it behaves like [MemoryStore]. */
    private class FlakyStore : DataStore<Preferences> {
        @Volatile var healed = false
        val backing = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow {
            if (!healed) throw IOException("transient")
            emitAll(backing)
        }
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(backing.value).also { backing.value = it }
    }

    @Test
    fun transientReadFailureDoesNotEndTheFlow() = runBlocking {
        val flaky = FlakyStore()
        val store = SitePermissionStore(flaky, readRetryMs = 1)
        val seen = Channel<List<SitePermissionStore.Record>>(Channel.UNLIMITED)
        val collector = launch { store.all.collect { seen.send(it) } }
        // The failure reads as "none"…
        assertEquals(emptyList<SitePermissionStore.Record>(), seen.receive())
        flaky.healed = true
        assertTrue(store.set("https://a.example", "camera", "allow"))
        // …and a later write still reaches the same collector.
        val expected = listOf(SitePermissionStore.Record("https://a.example", "camera", "allow"))
        withTimeout(5_000) {
            while (seen.receive() != expected) Unit
        }
        collector.cancel()
    }
}
