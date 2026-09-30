package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import java.math.BigInteger
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The scheme of an EIP-681 payment link (#317). */
internal const val ETHEREUM_LINK_SCHEME = "ethereum"

/** Whether [url] is an EIP-681 `ethereum:` link (#317). */
internal fun isEthereumLink(url: String?): Boolean = schemeOf(url) == ETHEREUM_LINK_SCHEME

/**
 * What the Send page (#105) is opened with for a payment link (#317):
 * the asset ([tokenKey], a [baby.freedom.mobile.wallet.Token.key] on one
 * of the wallet's chains), who to pay ([recipient]: an address, or a name
 * the page looks up as it does a typed one, #277) and how much ([amount],
 * in the asset's base units; null when the link names none). [origin] is
 * the site whose link it was, null for one the user typed or pasted into
 * the address bar. [chainGuess]: the link named no network, and which
 * one was filled in for it, and why (null: the link named it).
 *
 * Only a starting point: every field stays editable, and nothing is sent
 * without the page's own review and confirmation.
 */
data class SendPrefill(
    val origin: String?,
    val tokenKey: String,
    val recipient: String,
    val amount: BigInteger?,
    val chainGuess: ChainGuess? = null,
)

/**
 * Why a payment link naming no network (#317) was filled in on the one
 * it was, so the Send page can say so truthfully.
 */
enum class ChainGuess {
    /** A native-currency link: Ethereum, EIP-681's mainnet default (and desktop's reading). */
    ETHEREUM_DEFAULT,

    /** A token link: the one wallet network Send knows that token on — Gnosis Chain for xBZZ, say. */
    ONLY_CHAIN_WITH_TOKEN,
}

/** Where a payment link goes (#317): the Send page, or a sentence saying why not. */
internal sealed interface EthereumLinkRoute {
    data class OpenSend(val prefill: SendPrefill) : EthereumLinkRoute

    data class Refuse(val reason: String) : EthereumLinkRoute

    /** Not the user's to open this way: dropped, with nothing shown. */
    data object Drop : EthereumLinkRoute
}

/**
 * What the address bar does with [input], submitted from [source]
 * (#317): null when it isn't an `ethereum:` link, and is loaded as usual.
 * A payment link is never loaded: typed or pasted by the user, it goes
 * where [ethereumLinkRoute] says, with no site asking; from anything
 * else it's dropped — a page's link goes through the WebView's own gate
 * ([EthereumLinks.fromPage]), and another app's has no tap here.
 */
internal fun addressBarEthereumLink(input: String, source: SubmitSource, private: Boolean, walletReady: Boolean): EthereumLinkRoute? {
    val text = input.trim()
    if (!isEthereumLink(text)) return null
    if (source != SubmitSource.User) return EthereumLinkRoute.Drop
    return ethereumLinkRoute(text, private, walletReady, origin = null)
}

/**
 * Where the `ethereum:` link [url] goes (#317), for a tab that is
 * [private] or not, with the wallet [walletReady] (created or imported,
 * locked or not) or not, from [origin] (null: the address bar).
 *
 * Read with the QR scanner's own EIP-681 parser ([ScannedCode.parseLink]),
 * so a link and a scanned code are understood alike — a name may be the
 * payee of a link, which Send can look up. A private tab is refused first:
 * the wallet is the user's one identity, which a private tab doesn't use
 * (as for `window.ethereum`). Then a missing wallet. Then what the parser
 * refuses, and what Send can't pay: a network other than the wallet's
 * ([TokenRegistry.WALLET_CHAIN_IDS]) or a token it doesn't know. A link
 * naming no network is taken as Ethereum's — desktop's reading, EIP-681's
 * mainnet default — and the page says so ([SendPrefill.chainGuess]); a
 * token with no network is looked for on the wallet's chains, and the page
 * names the one it was found on.
 */
internal fun ethereumLinkRoute(url: String, private: Boolean, walletReady: Boolean, origin: String?): EthereumLinkRoute {
    if (private) return EthereumLinkRoute.Refuse(Strings.get(R.string.send_link_private))
    if (!walletReady) return EthereumLinkRoute.Refuse(Strings.get(R.string.send_link_no_wallet))
    val payment = when (val code = ScannedCode.parseLink(url)) {
        is ScannedCode.Payment -> code
        is ScannedCode.Unrecognized -> return refused(code.reason)
        else -> return refused(Strings.get(R.string.wallet_scan_no_valid_address))
    }
    val token = payment.token
    val chainId = payment.chainId ?: if (token == null) {
        TokenRegistry.ETHEREUM
    } else {
        TokenRegistry.builtins
            .filter { it.address.equals(token, ignoreCase = true) && it.chainId in TokenRegistry.WALLET_CHAIN_IDS }
            .singleOrNull()?.chainId
            ?: return refused(Strings.get(R.string.send_link_token_no_network))
    }
    if (chainId !in TokenRegistry.WALLET_CHAIN_IDS) {
        val known = BuiltInChains.ALL.firstOrNull { it.id == chainId }
        return refused(
            if (known != null) {
                Strings.get(R.string.send_link_chain_unsupported_named, known.name)
            } else {
                // The ID as the link wrote it, never locale-formatted: it's what to look up.
                Strings.get(R.string.send_link_chain_unsupported, chainId.toString())
            },
        )
    }
    val key = if (token == null) {
        "$chainId:native"
    } else {
        TokenRegistry.builtins.firstOrNull { it.chainId == chainId && it.address.equals(token, ignoreCase = true) }?.key
            ?: return refused(
                Strings.get(
                    R.string.send_link_token_unknown,
                    token,
                    BuiltInChains.ALL.firstOrNull { it.id == chainId }?.name ?: chainId.toString(),
                ),
            )
    }
    return EthereumLinkRoute.OpenSend(
        SendPrefill(
            origin = origin,
            tokenKey = key,
            recipient = payment.recipient,
            amount = payment.amount,
            chainGuess = when {
                payment.chainId != null -> null
                token == null -> ChainGuess.ETHEREUM_DEFAULT
                else -> ChainGuess.ONLY_CHAIN_WITH_TOKEN
            },
        ),
    )
}

private fun refused(reason: String) = EthereumLinkRoute.Refuse(Strings.get(R.string.send_link_refused, reason))

/**
 * A page's `ethereum:` link (#317). Reached only for a main-frame
 * navigation the user's own tap in the top document started, one tap per
 * link — the gate an app link goes through ([externalLinkVerdict],
 * [UserGestureLatch]) — and the page's navigation is cancelled, so it
 * stays on screen. The Send page it opens is then put up through
 * `window.ethereum`'s per-tab ask gate ([EthereumProviders.askOnDocument]):
 * only while its tab is the one on screen, one at a time, dropped if the
 * page that asked is gone, and — once the user leaves a Send page a link
 * opened without sending — no more from that tab (nor any other of its
 * wallet sheets) until the user navigates it themselves.
 */
object EthereumLinks {
    private val scope = MainScope()

    /** Shows a short notice (the browser's snackbar), while the browser is composed. Main thread. */
    var onNotice: ((String) -> Unit)? = null

    /**
     * The link [url], tapped on [tab]'s page [pageUrl] while [doc] was
     * the tab's document ([EthereumProviders.currentDocument]). Main thread.
     */
    fun fromPage(context: Context, tab: BrowserState, pageUrl: String?, doc: Int, url: String) {
        val origin = permissionOriginKey(pageUrl)
        if (origin == null) {
            // As for an app link: a page with no web origin (a file, an
            // error page) has nobody to name as asking.
            Log.i(TAG, "payment link refused: no origin")
            return
        }
        when (val route = ethereumLinkRoute(url, tab.private, walletReady(context), origin)) {
            is EthereumLinkRoute.Refuse -> onNotice?.invoke(route.reason)
            EthereumLinkRoute.Drop -> Unit
            is EthereumLinkRoute.OpenSend -> scope.launch {
                val answer = EthereumProviders.askOnDocument(tab, doc, EthAsk.SendLink(origin, route.prefill))
                if (answer == EthAnswer.Paused) onNotice?.invoke(Strings.get(R.string.send_link_paused))
            }
        }
    }

    /**
     * Opens Send for a link the user named themselves, with no page's ask
     * behind it (the browser's Send page, while composed): the tab and
     * what to fill in. Main thread.
     */
    var onOpenSend: ((BrowserState, SendPrefill) -> Unit)? = null

    /**
     * The link [url] that the address the user submitted on [tab]
     * redirected to, from the site [askerUrl] whose hop answered with it
     * (R1-M1). The user's submit was the tap, so it opens as a link typed
     * into the address bar does — no ask of the tab's document, which
     * never asked, and nothing to pause if it's left. Main thread.
     */
    fun fromUserNamed(context: Context, tab: BrowserState, askerUrl: String?, url: String) {
        val origin = permissionOriginKey(askerUrl)
        when (val route = ethereumLinkRoute(url, tab.private, walletReady(context), origin)) {
            is EthereumLinkRoute.Refuse -> onNotice?.invoke(route.reason)
            EthereumLinkRoute.Drop -> Unit
            is EthereumLinkRoute.OpenSend -> onOpenSend?.invoke(tab, route.prefill)
        }
    }

    /** A wallet is there to send from, locked or not. */
    fun walletReady(context: Context): Boolean = when (Vault.get(context).state.value) {
        is Vault.State.Locked, is Vault.State.Unlocked -> true
        else -> false
    }

    private const val TAG = "EthereumLinks"
}

/**
 * A payment link's Send page on screen (#317): what it was filled in
 * with, and the page's ask it stands for ([EthAsk.SendLink]; null for a
 * link from the address bar, which the user named themselves).
 */
internal class LinkSend(val prefill: SendPrefill, val prompt: EthereumPromptRequest?) {
    /** A send from the page has started. */
    var started = false

    /** The Send page itself has been on screen (not only the wallet loading behind it). */
    var shown = false

    /**
     * The page is left: the ask is answered — approved if a send went out
     * from it; rejected if the user saw Send and left it, which pauses the
     * tab's wallet asks until the user navigates it
     * ([EthereumProviders.allowPrompts]); and, closed before Send ever
     * showed, refused without that pause ([EthAnswer.Unseen]).
     */
    fun closed() {
        prompt?.respond(linkSendAnswer(started, shown))
    }
}

/** How a payment link's ask is answered when its Send page is left (#317); see [LinkSend.closed]. */
internal fun linkSendAnswer(started: Boolean, shown: Boolean): EthAnswer = when {
    started -> EthAnswer.Approved()
    shown -> EthAnswer.Rejected
    else -> EthAnswer.Unseen
}
