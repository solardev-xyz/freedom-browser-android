package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The address bar's bookmark/history lookup waits for typing to pause (#473). */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedLookupTest {
    @Test
    fun `the first query runs at once`() = runTest {
        val asked = mutableListOf<String>()
        val out = mutableListOf<String>()
        val job = launch {
            debouncedLookup(MutableStateFlow("git"), debounceMs = 100) { q -> asked += q; flowOf("$q!") }.toList(out)
        }
        runCurrent()
        assertEquals(listOf("git"), asked)
        assertEquals(listOf("git!"), out)
        job.cancel()
    }

    @Test
    fun `typing quickly runs one lookup for the text it stops at`() = runTest {
        val queries = MutableStateFlow("g")
        val asked = mutableListOf<String>()
        val out = mutableListOf<String>()
        val job = launch {
            debouncedLookup(queries, debounceMs = 100) { q -> asked += q; flowOf("$q!") }.toList(out)
        }
        runCurrent()
        for (q in listOf("gi", "git", "gith", "githu", "github")) {
            queries.value = q
            advanceTimeBy(40)
        }
        assertEquals(listOf("g"), asked)
        assertEquals(listOf("g!"), out) // the old results stay while typing
        advanceTimeBy(100)
        assertEquals(listOf("g", "github"), asked)
        assertEquals(listOf("g!", "github!"), out)
        job.cancel()
    }

    @Test
    fun `a newer query stops the older one's updates`() = runTest {
        val queries = MutableStateFlow("a")
        val tables = MutableStateFlow(0)
        val out = mutableListOf<String>()
        val job = launch {
            debouncedLookup(queries, debounceMs = 100) { q ->
                flow { tables.collect { emit("$q@$it") } }
            }.toList(out)
        }
        runCurrent()
        queries.value = "ab"
        advanceTimeBy(150)
        tables.value = 1 // a visit recorded: only the current query re-runs
        runCurrent()
        assertEquals(listOf("a@0", "ab@0", "ab@1"), out)
        job.cancel()
    }
}
