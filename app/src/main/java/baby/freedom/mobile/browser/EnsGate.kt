package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
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
        }.joinToString("\n")

        /** Each distinct answer and the servers that gave it. */
        fun conflictDetail(result: EnsResult.Conflict): String = buildList {
            val block = result.block?.let { "#$it" } ?: "the anchor block"
            add(
                when (result.subject) {
                    EnsResult.Conflict.Subject.RECORD -> "Answers at block $block:"
                    EnsResult.Conflict.Subject.BLOCK -> "Hashes reported for block $block:"
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
