package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.WhatwgHost
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.PublisherIdentity

/**
 * The Add chain form's rules (#107), one function per field so the form
 * can show each field's own hint, and [build] to turn a filled-in form
 * into a [Chain] — `null` until every field is valid.
 */
object ChainInput {
    /** A positive EIP-155 chain ID, decimal or `0x` hex, up to [Chain.MAX_ID]. */
    fun parseId(raw: String): Long? {
        val t = raw.trim()
        val id = if (t.startsWith("0x", ignoreCase = true)) {
            t.substring(2).takeIf { it.isNotEmpty() && it.all(Char::isHexDigitChar) }?.toLongOrNull(16)
        } else {
            t.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
        }
        return id?.takeIf { it in 1..Chain.MAX_ID }
    }

    /**
     * A chain or currency name that draws as what it is ([shownAsIs]). A
     * site names the chain it adds, and the approval sheets show it as
     * is: a bidi override would reorder the row, and a U+2028 or a run of
     * spaces or blank letters would leave a gap the row wraps at. The
     * sheets never put the chain ID inside the name's own text — it's on
     * a line of its own — so a plain name such as
     * "Ethereum (chain 1) Neeee…t" that wraps can't push it away either.
     */
    fun parseName(raw: String): String? = parseName(raw, strict = true)

    private fun parseName(raw: String, strict: Boolean): String? =
        raw.trim().takeIf {
            it.isNotEmpty() && it.length <= Chain.MAX_NAME_LENGTH && it.none(Char::isISOControl) && (!strict || shownAsIs(it))
        }

    fun parseSymbol(raw: String): String? = parseSymbol(raw, strict = true)

    private fun parseSymbol(raw: String, strict: Boolean): String? =
        raw.trim().takeIf {
            it.isNotEmpty() && it.length <= Chain.MAX_SYMBOL_LENGTH && it.none { c -> c.isWhitespace() || c.isISOControl() } &&
                (!strict || shownAsIs(it))
        }

    /**
     * Whether [s] draws as the characters it holds, on one line unless the
     * row itself is too narrow: no control, line/paragraph separator or
     * invisible format character ([PublisherIdentity.isRefusedInLabel]);
     * no space but single U+0020s between words, so a run of spaces can't
     * wrap the rest of the row away; nothing that draws blank or stacks
     * ink over the rows around it ([MessageSigning.hides]: Hangul fillers,
     * U+2800, variation selectors other than an emoji's own, unassigned
     * default-ignorables, a fourth combining mark in a row). The emoji
     * joiners and flag tag characters stay allowed, but only right after
     * something that draws (an emoji, a letter, another joiner in the same
     * sequence), never after a space or at the start; they draw nothing,
     * so two spaces with one between them still count as a run, and like
     * the other default-ignorables they don't end a run of combining marks.
     */
    private fun shownAsIs(s: String): Boolean {
        var prev = -1
        var afterSpace = true // at the start, as after a space: nothing drawn yet
        var marks = 0
        for (cp in s.codePoints().toArray()) {
            val emojiPart = cp == 0x200C || cp == 0x200D || cp in 0xE0020..0xE007F
            val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
            when {
                PublisherIdentity.isRefusedInLabel(cp) -> return false
                space -> if (cp != 0x20 || afterSpace) return false
                emojiPart -> if (afterSpace) return false
                MessageSigning.hides(cp, prev, marks) -> return false
            }
            marks = when {
                MessageSigning.isMark(cp) -> marks + 1
                emojiPart -> marks
                else -> 0
            }
            if (!emojiPart) afterSpace = space
            prev = cp
        }
        return true
    }

    fun parseDecimals(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in Chain.DECIMALS_RANGE }

    /**
     * A block explorer base URL: `https://` with a host and no user name
     * or password, trailing `/` dropped; `null` if it isn't one. The
     * explorer is only ever opened as a link, but a chain shouldn't
     * carry a cleartext or credentialed one either.
     */
    fun normalizeExplorer(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty() || t.length > RpcUrls.MAX_LENGTH) return null
        if (t.any { it.code <= 0x20 || it.code > 0x7e }) return null
        val parsed = WhatwgHost.parse(t) ?: return null
        if (parsed.scheme != "https" || parsed.hostname == null || parsed.hasCredentials) return null
        return t
    }

    /**
     * The chain the form describes, or `null` while a required field is
     * missing or invalid. [explorer] is optional (blank = none);
     * [rpcUrls] needs at least one entry, each already accepted by
     * [RpcUrls.validate] (duplicates collapse).
     *
     * [stored]: a chain read back from storage, whose name and symbol are
     * held only to the rules they were added under — a chain added before
     * [parseName] refused format characters mustn't vanish on upgrade.
     */
    fun build(
        id: String,
        name: String,
        symbol: String,
        decimals: String,
        explorer: String,
        rpcUrls: List<String>,
        currencyName: String? = null,
        isTestnet: Boolean = false,
        stored: Boolean = false,
    ): Chain? {
        val chainId = parseId(id) ?: return null
        val chainName = parseName(name, strict = !stored) ?: return null
        val sym = parseSymbol(symbol, strict = !stored) ?: return null
        val dec = parseDecimals(decimals) ?: return null
        val exp = if (explorer.isBlank()) null else normalizeExplorer(explorer) ?: return null
        val rpcs = rpcUrls.map { RpcUrls.normalize(it) ?: return null }.distinct()
        if (rpcs.isEmpty() || rpcs.size > Chain.MAX_RPC_URLS) return null
        return Chain(
            id = chainId,
            name = chainName,
            symbol = sym,
            currencyName = currencyName?.let { parseName(it, strict = !stored) } ?: sym,
            decimals = dec,
            explorerUrl = exp,
            rpcUrls = rpcs,
            isTestnet = isTestnet,
        )
    }
}

private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
