package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search-engine suggestions in the address bar (#443): off by default,
 * turned on in Settings → Search → *Search suggestions*. While on, what
 * the user types into the address bar of a regular tab is sent to the
 * selected engine's suggestion service, and its answers show as
 * "search for …" rows under the local matches.
 *
 * Never sent:
 *  - while the setting is off;
 *  - from a private tab;
 *  - for a custom engine, whose suggestion service we don't know;
 *  - for text that is an address or a dweb name rather than a search
 *    term ([AddressInput.classify]) — `bank.example/login?token=…` or
 *    `bzz://<hash>` is where the user is going, not something to ask a
 *    search engine about;
 *  - for text longer than [MAX_QUERY_LENGTH] (a pasted paragraph).
 *
 * The request carries the typed text and nothing else: no cookies (the
 * app installs no `CookieHandler`), no cache, no redirects followed, and
 * a fixed `User-Agent` instead of the platform's (which names the
 * device model and Android build). The engine still sees the IP address
 * and the time, as with any request.
 */
internal object SearchSuggestions {
    /** Wait for typing to pause this long before asking. */
    const val DEBOUNCE_MS = 250L

    /** The whole request, however slowly the server answers. */
    const val DEADLINE_MS = 3_000L

    const val MAX_QUERY_LENGTH = 100

    /** At most this many engine rows. */
    const val MAX_SUGGESTIONS = 4

    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_SUGGESTION_LENGTH = 200
    private const val TIMEOUT_MS = 3_000

    /**
     * Each built-in engine's OpenSearch suggestion service (JSON answer
     * `["query", ["suggestion", …]]`), keyed by its results template.
     */
    private val SUGGEST_TEMPLATES: Map<String, String> = mapOf(
        "duckduckgo" to "https://duckduckgo.com/ac/?q=${SearchEngines.PLACEHOLDER}&type=list",
        "google" to "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q=${SearchEngines.PLACEHOLDER}",
        "bing" to "https://api.bing.com/osjson.aspx?query=${SearchEngines.PLACEHOLDER}",
        "brave" to "https://search.brave.com/api/suggest?q=${SearchEngines.PLACEHOLDER}",
        "ecosia" to "https://ac.ecosia.org/autocomplete?q=${SearchEngines.PLACEHOLDER}&type=list",
        "startpage" to "https://www.startpage.com/osuggestions?q=${SearchEngines.PLACEHOLDER}",
    ).mapKeys { (id, _) -> SearchEngines.BUILT_IN.first { it.id == id }.template }

    /** Whether the engine behind [searchTemplate] has a suggestion service we know. */
    fun supported(searchTemplate: String): Boolean = searchTemplate in SUGGEST_TEMPLATES

    /**
     * The suggestion request for [query], or `null` when none may be
     * sent (see the rules on [SearchSuggestions]).
     */
    fun requestUrl(
        enabled: Boolean,
        private: Boolean,
        query: String,
        searchTemplate: String,
    ): String? {
        if (!enabled || private) return null
        val template = SUGGEST_TEMPLATES[searchTemplate] ?: return null
        val q = query.trim()
        if (q.isEmpty() || q.length > MAX_QUERY_LENGTH) return null
        if (AddressInput.classify(q) != AddressInput.Kind.Search) return null
        return template.replace(SearchEngines.PLACEHOLDER, URLEncoder.encode(q, "UTF-8"))
    }

    /**
     * The suggestions in an OpenSearch answer for [query]: at most
     * [MAX_SUGGESTIONS] distinct, non-blank strings, the query itself
     * left out (the Search row already offers it). Anything that isn't
     * that shape is no suggestions.
     */
    fun parse(body: String, query: String): List<String> {
        val list = try {
            JSONArray(body).optJSONArray(1) ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        } catch (_: StackOverflowError) {
            return emptyList()
        }
        val q = query.trim()
        val out = LinkedHashSet<String>()
        for (i in 0 until list.length()) {
            val s = (list.opt(i) as? String)?.trim() ?: continue
            if (s.isEmpty() || s.length > MAX_SUGGESTION_LENGTH || s.equals(q, ignoreCase = true)) continue
            if (out.none { it.equals(s, ignoreCase = true) }) out += s
            if (out.size == MAX_SUGGESTIONS) break
        }
        return out.toList()
    }

    /**
     * Ask the suggestion service at [url] for [query]'s suggestions,
     * bounded as a whole by [DEADLINE_MS] ([withHardDeadline]). Any
     * failure is no suggestions.
     */
    suspend fun fetch(url: String, query: String): List<String> =
        withHardDeadline(DEADLINE_MS) { guard ->
            val conn = TorRouting.openConnection(URL(url)) as HttpURLConnection
            if (!guard.register { conn.disconnect() }) return@withHardDeadline null
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "Freedom")
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withHardDeadline null
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BODY_BYTES) throw IOException("answer too large")
                    }
                    out.toByteArray()
                }
                parse(bytes.toString(Charsets.UTF_8), query)
            } finally {
                conn.disconnect()
            }
        } ?: emptyList()
}

/** A suggestion request: the typed [query] and the [url] that asks for it. */
internal data class SuggestRequest(val query: String, val url: String)

/** Engine suggestions and the query they answer. */
internal data class EngineSuggestions(val query: String, val suggestions: List<String>)

/**
 * The engine suggestions for a stream of [requests] (`null` when none
 * may be sent): each request waits [debounceMs] for typing to pause and
 * is cancelled — its connection closed — as soon as the next one
 * arrives, so a stale answer never shows over a newer query. A `null`
 * request clears the list at once, without waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun engineSuggestions(
    requests: Flow<SuggestRequest?>,
    debounceMs: Long = SearchSuggestions.DEBOUNCE_MS,
    fetch: suspend (SuggestRequest) -> List<String> = { SearchSuggestions.fetch(it.url, it.query) },
): Flow<EngineSuggestions?> =
    requests
        .distinctUntilChanged()
        .transformLatest { request ->
            if (request == null) {
                emit(null)
                return@transformLatest
            }
            delay(debounceMs)
            emit(EngineSuggestions(request.query, fetch(request)))
        }
