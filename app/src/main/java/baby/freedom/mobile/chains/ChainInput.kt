package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.WhatwgHost

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

    fun parseName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() && it.length <= Chain.MAX_NAME_LENGTH && it.none(Char::isISOControl) }

    fun parseSymbol(raw: String): String? =
        raw.trim().takeIf {
            it.isNotEmpty() && it.length <= Chain.MAX_SYMBOL_LENGTH && it.none { c -> c.isWhitespace() || c.isISOControl() }
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
    ): Chain? {
        val chainId = parseId(id) ?: return null
        val chainName = parseName(name) ?: return null
        val sym = parseSymbol(symbol) ?: return null
        val dec = parseDecimals(decimals) ?: return null
        val exp = if (explorer.isBlank()) null else normalizeExplorer(explorer) ?: return null
        val rpcs = rpcUrls.map { RpcUrls.normalize(it) ?: return null }.distinct()
        if (rpcs.isEmpty() || rpcs.size > Chain.MAX_RPC_URLS) return null
        return Chain(
            id = chainId,
            name = chainName,
            symbol = sym,
            currencyName = currencyName?.let(::parseName) ?: sym,
            decimals = dec,
            explorerUrl = exp,
            rpcUrls = rpcs,
            isTestnet = isTestnet,
        )
    }
}

private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
