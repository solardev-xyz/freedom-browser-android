package baby.freedom.mobile.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class AdblockSiteStateTest {

    private val engine = AdblockEngine.build(listOf("||ads.example^\n@@||exempt.example^\$document"))

    private fun state(url: String, allowlisted: Boolean = false, engine: AdblockEngine? = this.engine, loading: Boolean = false) =
        adblockSiteState(url, adblockSiteFor(url)!!, allowlisted, engine, loading)

    @Test
    fun `the switch is on only where filters apply`() {
        val s = state("https://news.example/story")
        assertEquals(AdblockSiteState.BLOCKING, s)
        assertTrue(s.checked)
        assertTrue(s.toggleable)
    }

    @Test
    fun `with every category off the switch is off and can't be tapped`() {
        val s = state("https://news.example/", engine = null)
        assertEquals(AdblockSiteState.OFF, s)
        assertFalse(s.checked)
        assertFalse(s.toggleable)
        assertEquals(AdblockSiteState.LOADING, state("https://news.example/", engine = null, loading = true))
        assertFalse(AdblockSiteState.LOADING.checked)
        assertFalse(AdblockSiteState.LOADING.toggleable)
    }

    @Test
    fun `a page a list exempts with document shows off and can't be tapped`() {
        val s = state("https://www.exempt.example/page")
        assertEquals(AdblockSiteState.EXEMPT, s)
        assertFalse(s.checked)
        assertFalse(s.toggleable)
    }

    @Test
    fun `the user's own allowlisting shows off and can always be lifted`() {
        for (s in listOf(
            state("https://news.example/", allowlisted = true),
            state("https://news.example/", allowlisted = true, engine = null),
            state("https://exempt.example/", allowlisted = true),
        )) {
            assertEquals(AdblockSiteState.ALLOWED, s)
            assertFalse(s.checked)
            assertTrue(s.toggleable)
        }
    }

    @Test
    fun `only the states a tap changes go without a note`() {
        assertEquals(null, AdblockSiteState.BLOCKING.note)
        AdblockSiteState.entries.filter { it != AdblockSiteState.BLOCKING }.forEach { assertTrue(it.note != null) }
    }

    /** A fake settings file whose writes take as long as [latency] says. */
    private class FakeStorage(val latency: (AllowlistWrite) -> Long) {
        val file: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val log: MutableList<AllowlistWrite> = Collections.synchronizedList(ArrayList())
        suspend fun persist(w: AllowlistWrite) {
            delay(latency(w))
            if (w.add) file += w.host else file -= w.host
            log += w
        }
    }

    @Test
    fun `a quick allow then remove lands in that order even when the allow is slower`() = runBlocking {
        val storage = FakeStorage { if (it.add) 80 else 0 }
        val store = AllowlistStore(storage::persist) {}
        val runner = launch(Dispatchers.Default) { store.run() }
        store.write(add = true, host = "news.example")
        store.write(add = false, host = "news.example")
        assertFalse("news.example" in store.current)
        withTimeout(5_000) { while (storage.log.size < 2) delay(5) }
        assertEquals(
            listOf(AllowlistWrite(true, "news.example"), AllowlistWrite(false, "news.example")),
            storage.log.toList(),
        )
        assertFalse("news.example" in storage.file)
        runner.cancel()
    }

    @Test
    fun `a read from before a write landed doesn't undo it`() = runBlocking {
        val storage = FakeStorage { 60 }
        storage.file += listOf("kept.example", "gone.example")
        val store = AllowlistStore(storage::persist) {}
        store.saved(storage.file.toList())
        val runner = launch(Dispatchers.Default) { store.run() }
        store.write(add = true, host = "news.example")
        store.write(add = false, host = "gone.example")
        // Storage re-emits its old content while both writes are in flight.
        store.saved(listOf("kept.example", "gone.example"))
        assertEquals(setOf("kept.example", "news.example"), store.current)
        withTimeout(5_000) { while (storage.log.size < 2) delay(5) }
        store.saved(storage.file.toList())
        assertEquals(setOf("kept.example", "news.example"), store.current)
        assertEquals(store.current, storage.file.toSet())
        runner.cancel()
    }

    @Test
    fun `many writes with random latency persist in call order`() = runBlocking {
        val rnd = java.util.Random(7)
        val storage = FakeStorage { rnd.nextInt(3).toLong() }
        val seen = Collections.synchronizedList(ArrayList<Set<String>>())
        val store = AllowlistStore(storage::persist) { seen += it }
        val runner = launch(Dispatchers.Default) { store.run() }
        val calls = (0 until 200).map { AllowlistWrite(add = it % 3 != 2, host = "s${it % 5}.example") }
        calls.forEach { store.write(it.add, it.host) }
        val expected = store.current
        withTimeout(10_000) { while (storage.log.size < calls.size) delay(5) }
        assertEquals(calls, storage.log.toList())
        assertEquals(expected, storage.file.toSet())
        store.saved(storage.file.toList())
        assertEquals(expected, store.current)
        runner.cancel()
    }

    @Test
    fun `a landed write stays in current before storage's next read`() = runBlocking {
        val storage = FakeStorage { 0 }
        storage.file += "kept.example"
        val seen = Collections.synchronizedList(ArrayList<Set<String>>())
        val store = AllowlistStore(storage::persist) { seen += it }
        store.saved(storage.file.toList())
        val runner = launch(Dispatchers.Default) { store.run() }
        store.write(add = true, host = "news.example")
        store.write(add = false, host = "kept.example")
        withTimeout(5_000) { while (storage.log.size < 2) delay(5) }
        // Both writes are in storage, but no read of it has come back yet.
        val want = setOf("news.example")
        assertEquals(want, store.current)
        // And no step on the way ever dropped the landed add.
        assertTrue(seen.drop(seen.indexOfFirst { "news.example" in it }).all { "news.example" in it })
        store.saved(storage.file.toList())
        assertEquals(want, store.current)
        runner.cancel()
    }

    @Test
    fun `reads lagging behind landed writes never undo them`() = runBlocking {
        val storage = FakeStorage { 0 }
        val store = AllowlistStore(storage::persist) {}
        val runner = launch(Dispatchers.Default) { store.run() }
        // Allow, remove, allow again: storage goes {} → {a} → {} → {a}.
        store.write(add = true, host = "a.example")
        store.write(add = false, host = "a.example")
        store.write(add = true, host = "a.example")
        withTimeout(5_000) { while (storage.log.size < 3) delay(5) }
        // Every read storage went through, delivered late and in order.
        for (read in listOf(emptySet(), setOf("a.example"), emptySet(), setOf("a.example"))) {
            store.saved(read)
            assertEquals(setOf("a.example"), store.current)
        }
        runner.cancel()
    }

    @Test
    fun `a failed write is dropped`() = runBlocking {
        val store = AllowlistStore({ error("disk full") }) {}
        val runner = launch(Dispatchers.Default) { store.run() }
        store.write(add = true, host = "a.example")
        assertEquals(setOf("a.example"), store.current)
        withTimeout(5_000) { while (store.current.isNotEmpty()) delay(5) }
        runner.cancel()
    }
}
