package baby.freedom.mobile.browser

import baby.freedom.mobile.data.BrowsingRepository

/**
 * The address a bookmark's edited URL field saves (#264), read the way
 * the address bar reads what's typed into it ([AddressInput]):
 *
 *  - a dweb form — `name.eth`, `ens://…`, `bzz://…` (a hash or a name),
 *    `ipfs://…`, `ipns://…`, `rad://…` / `rad:…` — is kept as typed, so
 *    opening the bookmark resolves it again like typing it would, and a
 *    bare `name.eth` doesn't turn into `https://name.eth`;
 *  - an ordinary address gets what Enter would add ([UrlParser.toUrl]):
 *    `example.com` → `https://example.com`, `localhost:8080` →
 *    `http://localhost:8080`, a `.onion` host → `http://…`;
 *  - text Enter would search the web for is refused (a bookmark is an
 *    address, not a search), and so is a page history and bookmarks
 *    never keep ([BrowsingRepository.isRecordable]: `about:`,
 *    `javascript:`, `data:`, `blob:`).
 */
internal sealed interface BookmarkAddress {
    data class Ok(val url: String) : BookmarkAddress
    data class Invalid(val reason: String) : BookmarkAddress
}

internal fun bookmarkAddress(input: String): BookmarkAddress {
    val trimmed = input.trim()
    val url = when (AddressInput.classify(trimmed)) {
        null -> return BookmarkAddress.Invalid("Enter an address")
        AddressInput.Kind.Search ->
            return BookmarkAddress.Invalid("Not an address: the address bar would search the web for this")
        AddressInput.Kind.Dweb -> trimmed
        // Not a search, so [UrlParser.toUrl] never reaches its search
        // template.
        AddressInput.Kind.Url -> UrlParser.toUrl(trimmed, searchTemplate = "")
    }
    return if (BrowsingRepository.isRecordable(url)) {
        BookmarkAddress.Ok(url)
    } else {
        BookmarkAddress.Invalid("This address can't be bookmarked")
    }
}

/**
 * A bookmark's edited name as saved: line breaks and other control
 * characters become spaces (the lists show a name on one line), and the
 * ends are trimmed. Blank is allowed — the lists show the address then.
 */
internal fun bookmarkTitle(input: String): String =
    input.replace(Regex("[\\p{Cc}\\u2028\\u2029]+"), " ").trim()

/**
 * What the Bookmarks list offers to move the bookmark at [index] of
 * [ids] (#264) — the Move up/down menu items and the matching TalkBack
 * actions, the non-drag way to reorder — as (label, the bookmark it goes
 * after, null for first). Only moves that go somewhere are listed.
 */
internal fun bookmarkMoves(ids: List<Long>, index: Int): List<Pair<String, Long?>> = buildList {
    if (index !in ids.indices) return@buildList
    if (index > 0) {
        add("Move up" to ids.getOrNull(index - 2))
        if (index > 1) add("Move to top" to null)
    }
    if (index < ids.lastIndex) {
        add("Move down" to ids[index + 1])
        if (index < ids.lastIndex - 1) add("Move to bottom" to ids.last())
    }
}
