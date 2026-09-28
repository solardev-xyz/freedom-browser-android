package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsInput

/**
 * What the address bar's text is, as far as pressing Enter is concerned
 * (#171): an address to go to, a dweb name / content address to open, or
 * a web search. It mirrors the browser's submit path exactly — ENS and
 * the content schemes first, then [UrlParser] — so the top row built from
 * it ([addressActions]) always says what Enter will actually do.
 */
internal object AddressInput {
    enum class Kind {
        /** Enter searches it on the selected engine ([UrlParser.isSearch]). */
        Search,

        /** An ordinary address: `example.com`, `https://…`, `10.0.0.1:8080`. */
        Url,

        /**
         * A name the browser resolves itself (`vitalik.eth`, `ens://…`,
         * `bzz://name.eth`) or a content address on one of the embedded
         * gateways (`bzz://<hash>`, `ipfs://<cid>`, `ipns://…`), or a
         * Radicle repository (`rad://z…`, `rad:z…`, #124).
         */
        Dweb,
    }

    private val dwebSchemes = listOf("ens://", "bzz://", "ipfs://", "ipns://")

    /** `null` for blank input — there is nothing to go to or search for. */
    fun classify(input: String): Kind? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (EnsInput.parse(trimmed) != null ||
            EnsInput.parseConstrained(trimmed) != null ||
            dwebSchemes.any { trimmed.startsWith(it, ignoreCase = true) } ||
            RadUrl.parse(trimmed) != null
        ) return Kind.Dweb
        return if (UrlParser.isSearch(trimmed)) Kind.Search else Kind.Url
    }
}

/**
 * A row at the top of the address-bar suggestions (#171), above the
 * history and bookmark matches — Chrome's "what you typed" and
 * "<term> – Google Search" rows, Safari's "Search DuckDuckGo".
 * [submitText] is what tapping the row hands to the browser's submit path.
 */
internal sealed interface AddressAction {
    val submitText: String

    /**
     * Go to [input] as typed. It is submitted verbatim, like pressing
     * Enter, so ENS resolution, gateway probes and trust checks run
     * exactly as they would for a typed-and-entered address.
     */
    data class Go(val input: String, val kind: AddressInput.Kind) : AddressAction {
        override val submitText: String get() = input

        /**
         * The row's second line: what going there means. Each dweb label
         * is claimed only by input that really takes that path; anything
         * else (e.g. an `ens://` address [EnsInput] rejects, which submit
         * loads as a plain scheme URL) says the neutral "Go to address".
         */
        val subtitle: String
            get() = when {
                kind == AddressInput.Kind.Url -> "Go to address"
                EnsInput.parse(input) != null || EnsInput.parseConstrained(input) != null ->
                    "Open ENS name"
                input.startsWith("bzz://", ignoreCase = true) -> "Open on Swarm"
                input.startsWith("ipfs://", ignoreCase = true) ||
                    input.startsWith("ipns://", ignoreCase = true) -> "Open on IPFS"
                RadUrl.parse(input) != null -> "Open on Radicle"
                else -> "Go to address"
            }
    }

    /**
     * Search [query] with [engine] (the display name of the Settings
     * engine). [url] is built by [UrlParser.searchUrl], the same builder
     * Enter uses for non-addresses, so tapping this row and pressing
     * Enter on a search term load the same URL.
     */
    data class Search(val query: String, val engine: String, val url: String) : AddressAction {
        override val submitText: String get() = url
    }
}

/**
 * The action rows for [input], best first (nearest the address bar):
 * a search term gets just the search row; an address or dweb name gets
 * a go-to row, with searching for the text as the second choice. Blank
 * input gets none.
 */
internal fun addressActions(input: String, searchTemplate: String): List<AddressAction> {
    val kind = AddressInput.classify(input) ?: return emptyList()
    val query = input.trim()
    val search = AddressAction.Search(
        query = query,
        engine = SearchEngines.nameForTemplate(searchTemplate),
        url = UrlParser.searchUrl(query, searchTemplate),
    )
    return if (kind == AddressInput.Kind.Search) listOf(search)
    else listOf(AddressAction.Go(query, kind), search)
}
