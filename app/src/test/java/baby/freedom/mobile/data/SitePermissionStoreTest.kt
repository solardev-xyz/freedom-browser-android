package baby.freedom.mobile.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    /**
     * Reads follow [script]: `true` succeeds once (emits and ends the
     * read, like a file that later goes bad), `false` fails; past the
     * end, reads hang.
     */
    private class ScriptedStore(private val script: List<Boolean>) : DataStore<Preferences> {
        private var reads = 0
        override val data: Flow<Preferences> = flow {
            val ok = script.getOrNull(reads++) ?: awaitCancellation()
            if (!ok) throw IOException("transient")
            emit(emptyPreferences())
            throw IOException("went bad after a good read")
        }
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw UnsupportedOperationException()
    }

    @Test
    fun readBackOffResetsAfterASuccessfulRead() = runBlocking {
        // 6 failures in a row, then a good read, then 2 more failures.
        // (Each good read here is followed by a failure of its own.)
        val script = List(6) { false } + true + listOf(false)
        val delays = mutableListOf<Long>()
        val store = SitePermissionStore(
            ScriptedStore(script),
            readRetryMs = 1,
            backOff = { delays += it },
        )
        val collector = launch { store.all.collect {} }
        withTimeout(5_000) { while (delays.size < 8) yield() }
        collector.cancel()
        // Doubles up to the 32× cap, then starts again at 1× after the
        // good read instead of staying pinned at 32×.
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 1L, 2L), delays)
    }
}
