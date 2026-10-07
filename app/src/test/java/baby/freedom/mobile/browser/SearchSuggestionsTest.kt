package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Search-engine suggestions: when they may be asked for, parsing, debounce and cancel (#443). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsTest {

    @Before
    fun realIcu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    private val ddg = SearchEngines.DEFAULT.template
    private val custom = "https://search.example.org/find?q={searchTerms}"

    @Test
    fun `off by default rule - nothing is sent while the setting is off`() {
        assertNull(SearchSuggestions.requestUrl(enabled = false, private = false, query = "swarm", searchTemplate = ddg))
    }

    @Test
    fun `never from a private tab, even with the setting on`() {
        assertNull(SearchSuggestions.requestUrl(enabled = true, private = true, query = "swarm", searchTemplate = ddg))
    }

    @Test
    fun `on, in a regular tab, a search term is sent to the engine's suggestion service`() {
        assertEquals(
            "https://duckduckgo.com/ac/?q=swarm+storage&type=list",
            SearchSuggestions.requestUrl(true, false, " swarm storage ", ddg),
        )
        for (engine in SearchEngines.BUILT_IN) {
            val url = SearchSuggestions.requestUrl(true, false, "bee", engine.template)
            assertNotNull(engine.id, url)
            assertTrue(engine.id, url!!.startsWith("https://"))
            assertTrue(engine.id, SearchSuggestions.supported(engine.template))
        }
        assertEquals(
            "https://duckduckgo.com/ac/?q=caf%C3%A9+%26+b%C3%A4r%3F&type=list",
            SearchSuggestions.requestUrl(true, false, "café & bär?", ddg),
        )
    }

    @Test
    fun `addresses, dweb names, blank and overlong text are never sent`() {
        for (q in listOf(
            "example.com", "https://bank.example/login?token=abc", "10.0.0.1:8080",
            "vitalik.eth", "bzz://" + "ab".repeat(32), "ipfs://name.eth", "", "   ",
            "a".repeat(SearchSuggestions.MAX_QUERY_LENGTH + 1),
        )) {
            assertNull(q, SearchSuggestions.requestUrl(true, false, q, ddg))
        }
    }

    @Test
    fun `a custom engine has no known suggestion service`() {
        assertNull(SearchSuggestions.requestUrl(true, false, "swarm", custom))
        assertEquals(false, SearchSuggestions.supported(custom))
    }

    @Test
    fun `parses OpenSearch answers, de-duplicated, without the query, capped`() {
        assertEquals(
            listOf("swarm bee", "swarm storage", "swarm network", "swarm intelligence"),
            SearchSuggestions.parse(
                """["swarm",["swarm","swarm bee","Swarm Bee"," swarm storage ","","swarm network",""" +
                    """ "swarm intelligence","swarm five"]]""",
                "swarm",
            ),
        )
        assertEquals(listOf("a b"), SearchSuggestions.parse("""["a",["a b", 3, null, {"x":1}]]""", "a"))
    }

    @Test
    fun `anything but an OpenSearch answer is no suggestions`() {
        for (body in listOf("", "not json", "{}", "[]", """["q"]""", """["q","x"]""", "[".repeat(100_000))) {
            assertEquals(body.take(20), emptyList<String>(), SearchSuggestions.parse(body, "q"))
        }
        val long = "x".repeat(201)
        assertEquals(emptyList<String>(), SearchSuggestions.parse("""["q",["$long"]]""", "q"))
    }

    @Test
    fun `debounced - only the query typing paused on is asked for`() = runTest {
        val requests = MutableStateFlow<SuggestRequest?>(null)
        val asked = mutableListOf<String>()
        val out = mutableListOf<EngineSuggestions?>()
        val job = launch {
            engineSuggestions(requests, debounceMs = 250) { r -> asked += r.query; listOf("${r.query}!") }
                .toList(out)
        }
        runCurrent()
        for (q in listOf("s", "sw", "swa", "swar")) {
            requests.value = SuggestRequest(q, "u/$q")
            advanceTimeBy(100)
        }
        advanceTimeBy(300)
        assertEquals(listOf("swar"), asked)
        assertEquals(EngineSuggestions("swar", listOf("swar!")), out.last())
        job.cancel()
    }

    @Test
    fun `a newer query cancels the request in flight, so a stale answer never shows`() = runTest {
        val requests = MutableStateFlow<SuggestRequest?>(SuggestRequest("old", "u/old"))
        val cancelled = mutableListOf<String>()
        val out = mutableListOf<EngineSuggestions?>()
        val job = launch {
            engineSuggestions(requests, debounceMs = 10) { r ->
                if (r.query == "old") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled += r.query
                    }
                }
                listOf("${r.query} answer")
            }.toList(out)
        }
        advanceTimeBy(50) // "old" is now in flight, hanging
        requests.value = SuggestRequest("new", "u/new")
        advanceTimeBy(50)
        assertEquals(listOf("old"), cancelled)
        assertEquals(listOf(EngineSuggestions("new", listOf("new answer"))), out.filterNotNull())
        job.cancel()
    }

    @Test
    fun `turning it off, going private or typing an address clears at once`() = runTest {
        val requests = MutableStateFlow<SuggestRequest?>(SuggestRequest("swarm", "u"))
        val out = mutableListOf<EngineSuggestions?>()
        val job = launch { engineSuggestions(requests, debounceMs = 250) { listOf("x") }.toList(out) }
        advanceTimeBy(300)
        assertEquals(EngineSuggestions("swarm", listOf("x")), out.last())
        requests.value = null
        runCurrent()
        assertNull(out.last())
        job.cancel()
    }

    @Test
    fun `an answer for the previous query is withheld until the new query's own answer lands`() = runTest {
        val requests = MutableStateFlow<SuggestRequest?>(SuggestRequest("swarm", "u1"))
        val out = mutableListOf<EngineSuggestions?>()
        val job = launch {
            engineSuggestions(requests, debounceMs = 250) { listOf(it.query + " answer") }.toList(out)
        }
        advanceTimeBy(300)
        assertEquals(listOf("swarm answer"), shownEngineSuggestions(requests.value, out.last()))
        // Typed on: the old answer is still the latest emitted, but it
        // answers "swarm", not "swarm b" — nothing shows meanwhile.
        requests.value = SuggestRequest("swarm b", "u2")
        advanceTimeBy(100)
        assertEquals(EngineSuggestions("swarm", listOf("swarm answer")), out.last())
        assertEquals(emptyList<String>(), shownEngineSuggestions(requests.value, out.last()))
        advanceTimeBy(300)
        assertEquals(listOf("swarm b answer"), shownEngineSuggestions(requests.value, out.last()))
        assertEquals(emptyList<String>(), shownEngineSuggestions(null, out.last()))
        assertEquals(emptyList<String>(), shownEngineSuggestions(requests.value, null))
        job.cancel()
    }
}
