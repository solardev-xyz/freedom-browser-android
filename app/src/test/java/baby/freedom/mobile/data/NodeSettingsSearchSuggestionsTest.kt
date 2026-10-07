package baby.freedom.mobile.data

import androidx.datastore.preferences.core.emptyPreferences
import baby.freedom.mobile.data.NodeSettingsEnsRpcTest.MemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** Settings → Search → *Search suggestions* (#443): the consent holds only for the engine it named. */
class NodeSettingsSearchSuggestionsTest {

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun settings(file: MemoryStore) = NodeSettings.forTesting(
        file,
        ChainStore(MemoryStore()),
        RpcKeyStore(MemoryStore(), AesGcmCipher { aes }),
    )

    @Test
    fun `off by default, on for the engine it was turned on for`() = runBlocking {
        val file = MemoryStore(emptyPreferences())
        assertEquals(false, settings(file).searchSuggestions.first())
        settings(file).setSearchSuggestions(true)
        assertEquals(true, settings(file).searchSuggestions.first())
        settings(file).setSearchSuggestions(false)
        assertEquals(false, settings(file).searchSuggestions.first())
    }

    @Test
    fun `picking another engine turns it off, and it stays off on the way back`() = runBlocking {
        val file = MemoryStore(emptyPreferences())
        val s = settings(file)
        s.setSearchEngine("duckduckgo")
        s.setSearchSuggestions(true)
        s.setSearchEngine("google")
        assertEquals(false, s.searchSuggestions.first())
        s.setSearchEngine("duckduckgo")
        assertEquals(false, s.searchSuggestions.first())
        // Turned on again, it names Google now.
        s.setSearchEngine("google")
        s.setSearchSuggestions(true)
        assertEquals(true, s.searchSuggestions.first())
        // Re-picking the same engine keeps it.
        s.setSearchEngine("google")
        assertEquals(true, s.searchSuggestions.first())
    }

    @Test
    fun `a custom engine can't hold it, and a consent doesn't survive a switch to one`() = runBlocking {
        val file = MemoryStore(emptyPreferences())
        val s = settings(file)
        s.setSearchSuggestions(true)
        assertTrue(s.setCustomSearchTemplate("https://search.example/?q={searchTerms}"))
        assertEquals(false, s.searchSuggestions.first())
        s.setSearchSuggestions(true)
        assertEquals(false, s.searchSuggestions.first())
    }

    @Test
    fun `the consent is compared with the engine actually in use`() {
        assertEquals(true, searchSuggestionsFor("duckduckgo", "duckduckgo", null))
        assertEquals(false, searchSuggestionsFor("duckduckgo", "google", null))
        assertEquals(false, searchSuggestionsFor(null, "duckduckgo", null))
        assertEquals(false, searchSuggestionsFor("custom", "custom", "https://search.example/?q={searchTerms}"))
        // An unknown id or a `custom` without a valid template searches with
        // the default — a consent for the default holds, one for another doesn't.
        assertEquals(true, searchSuggestionsFor("duckduckgo", "gone-engine", null))
        assertEquals(false, searchSuggestionsFor("google", "custom", ""))
    }
}
