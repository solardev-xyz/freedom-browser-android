package baby.freedom.mobile.ens

import io.github.adraffy.ens.ENSNormalize
import io.github.adraffy.ens.InvalidLabelException

/**
 * ENSIP-15 name normalization — what desktop gets from
 * `@adraffy/ens-normalize`, here from the same author's Java port of it
 * (`io.github.adraffy:ens-normalize`, same spec data, pure JVM, no
 * dependencies).
 *
 * A name's namehash is taken over its *normalized* form, so a resolver
 * that only lowercases ASCII hashes `Ⓜ️.eth`, `ＶＩＴＡＬＩＫ.eth` or a
 * decomposed `café.eth` to nodes nobody owns — and resolves nothing (or,
 * worse, something else) where every other client resolves the real name.
 * ENSIP-15 also *refuses* names that can't be registered in normalized form
 * at all (disallowed characters, illegal script mixtures, whole-script
 * confusables, leading combining marks, `ab--c` label extensions, …); the
 * resolver reports those as `INVALID_NAME` instead of querying a node.
 *
 * The resolver goes through [fastNormalize], desktop's shortcut of the
 * same name: a name that lowercases to plain `[a-z0-9.-]` is used as-is,
 * without the ENSIP-15 pass. That keeps pre-ENSIP-15 registrations such
 * as punycode `xn--2i8h.eth` or `ab--c.eth` (which ENSIP-15 refuses for
 * the `--` at positions 3–4) resolvable, as they are on desktop.
 *
 * The library decodes its spec tables on first use — a few hundred ms on
 * a cold ART — so [warm] runs that off the main thread at startup,
 * and the address-bar paths ([EnsInput]) never need it for ASCII input.
 */
object EnsNormalize {
    /**
     * Why [normalize] refused a name, e.g.
     * `Invalid label "a．b": disallowed character: {FF0E}`.
     */
    class InvalidNameException(message: String, cause: Throwable) :
        IllegalArgumentException(message, cause)

    /**
     * The ENSIP-15 normalized form of [name] (`Vitalik.ETH` → `vitalik.eth`,
     * `Ⓜ️.eth` → `m.eth`). Throws [InvalidNameException] for a name ENSIP-15
     * rejects. The empty string normalizes to itself.
     */
    fun normalize(name: String): String =
        try {
            ENSNormalize.ENSIP15.normalize(name)
        } catch (e: InvalidLabelException) {
            throw InvalidNameException(cleanMessage(e.message), e)
        }

    private val pureAsciiHost = Regex("^[a-z0-9.-]+$")

    /**
     * Desktop's `fastNormalize` (`src/main/ens-resolver.js`): a name that
     * lowercases to `[a-z0-9.-]` only is taken as-is, anything else gets
     * the full [normalize]. Throws [InvalidNameException] like [normalize].
     */
    fun fastNormalize(name: String): String {
        val lowered = name.lowercase()
        return if (pureAsciiHost.matches(lowered)) lowered else normalize(name)
    }

    /**
     * Force the library's one-time spec decode (`spec.bin`/`nf.bin`, a
     * static initializer — so JVM class init makes it thread-safe and
     * idempotent). Call off the main thread at startup so the first
     * non-ASCII name typed or restored doesn't pay for it mid-composition.
     */
    fun warm() {
        normalizeOrNull("a.eth")
    }

    /**
     * The library wraps the offending label in U+200E LEFT-TO-RIGHT MARKs
     * (so an RTL label prints the right way round); they are invisible
     * and come along when the reason is copied off the error page, so
     * drop them — and any other bidi controls — from the text we show.
     */
    internal fun cleanMessage(message: String?): String =
        (message ?: "invalid name")
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .ifBlank { "invalid name" }

    /** [normalize], or `null` when ENSIP-15 rejects [name]. */
    fun normalizeOrNull(name: String): String? =
        try {
            normalize(name)
        } catch (_: InvalidNameException) {
            null
        }
}
