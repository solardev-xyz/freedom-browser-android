package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsQuorum
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import java.security.SecureRandom

/**
 * A tab's pending "Continue once" past the *not cross-checked* warning
 * (#96): the ENS answer only one RPC server gave, which the user may
 * choose to load anyway.
 *
 * The warning is the [ErrorPage] with `error=ens_unverified`; its
 * Continue button navigates to [continueUrl] — a URL no network serves,
 * caught in `shouldOverrideUrlLoading` and handed to the submit flow,
 * which re-submits [retryUrl] with [uri] let through. [token] is random
 * and lives only in the tab and in that page's URL, so a website can't
 * wave its own navigation past the warning by linking to a continue URL.
 *
 * An onchain app's warning (#123, `error=web3_unverified`) uses the same
 * gate: [name] is then the app's `web3://` address and [uri] the
 * keccak256 of the code the one server returned — "Continue once" runs
 * exactly that code ([OnchainAppTab.takePending]).
 */
internal class EnsGate(
    val token: String,
    /** The name the answer is for. */
    val name: String,
    /** The unverified answer the user is asked about. */
    val uri: String,
    /** The routable form of the navigation to re-submit. */
    val retryUrl: String,
) {
    companion object {
        private const val CONTINUE_PREFIX = "freedom-ens-continue:"

        /** The warnings a gate is made for: an ENS answer's, and an onchain app's code (#123). */
        private val UNVERIFIED_ERRORS = setOf("ens_unverified", "web3_unverified")
        private val random = SecureRandom()

        fun create(name: String, uri: String, retryUrl: String): EnsGate {
            val bytes = ByteArray(16).also(random::nextBytes)
            val token = bytes.joinToString("") { "%02x".format(it) }
            return EnsGate(token, name, uri, retryUrl)
        }

        fun continueUrl(gate: EnsGate): String = CONTINUE_PREFIX + gate.token

        /** The token in a continue URL; `null` if [url] isn't one. */
        fun continueToken(url: String): String? =
            if (url.startsWith(CONTINUE_PREFIX)) url.removePrefix(CONTINUE_PREFIX) else null

        /**
         * The warning's details for an unverified [result]: what the one
         * server said, who it was, and at which block — whole, since the
         * user is deciding whether to trust exactly that answer.
         */
        fun unverifiedDetail(result: EnsResult.Ok): String = buildList {
            add("Answer: ${result.uri}")
            if (result.trust.agreed.isNotEmpty()) add("From: ${result.trust.agreed.joinToString(", ")}")
            add("Block: " + (result.trust.block?.let { "#$it" } ?: "latest"))
            tooFewNote(result.trust)?.let(::add)
        }.joinToString("\n")

        /**
         * Why nothing was cross-checked when the user enabled too few RPC
         * endpoints to (#102): then no answer ever is, and the warning
         * says so rather than suggesting a server let the others down.
         */
        private fun tooFewNote(trust: EnsTrust): String? =
            if (trust.verified || !trust.tooFewServers) {
                null
            } else {
                "Fewer than ${EnsQuorum.MIN_PROVIDERS} RPC endpoints are enabled in " +
                    "Settings → RPC providers, so no answer can be cross-checked."
            }

        /**
         * [detail] for a "nothing to load" answer, plus a note when only
         * one server gave it — the page's verdict is then that server's
         * word, not the servers' agreement (#96).
         */
        fun withTrustNote(detail: String, trust: EnsTrust): String {
            if (trust.verified) return detail
            val from = trust.agreed.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "one RPC server"
            val block = trust.block?.let { "#$it" } ?: "latest"
            val note = "$detail\nNot cross-checked: only $from answered (block $block)"
            return tooFewNote(trust)?.let { "$note\n$it" } ?: note
        }

        /**
         * Where a tap on a warning's Continue goes: [continueUrl] itself
         * when its token is still [gate]'s. A warning the tab no longer
         * holds a gate for — reached again via Back, or restored with the
         * tab — would otherwise do nothing; if the page tapped on
         * ([pageUrl]) is one of our not-cross-checked warnings (an ENS
         * answer's, or an onchain app's, #123), its navigation is
         * run again instead, unapproved, so the user gets a fresh warning
         * (or the page, if the servers now agree). `null`: drop it — a
         * continue URL from anywhere else is a page trying its luck.
         */
        fun continueDestination(continueUrl: String, gate: EnsGate?, pageUrl: String?): String? {
            val token = continueToken(continueUrl) ?: return null
            if (gate != null && gate.token == token) return continueUrl
            if (ErrorPage.paramFor(pageUrl, "error") !in UNVERIFIED_ERRORS) return null
            return ErrorPage.paramFor(pageUrl, "retry")?.takeIf { continueToken(it) == null }
        }

        /** Each distinct answer and the servers that gave it. */
        fun conflictDetail(result: EnsResult.Conflict): String = buildList {
            val block = result.block?.let { "#$it" } ?: "the anchor block"
            add(
                when (result.subject) {
                    EnsResult.Conflict.Subject.RECORD -> "Answers at block $block:"
                    EnsResult.Conflict.Subject.BLOCK -> "Hashes reported for block $block:"
                    EnsResult.Conflict.Subject.HEAD -> "Chain heads reported:"
                },
            )
            for (group in result.groups) {
                add("")
                add(group.answer)
                add("  from ${group.hosts.joinToString(", ")}")
            }
        }.joinToString("\n")
    }
}
