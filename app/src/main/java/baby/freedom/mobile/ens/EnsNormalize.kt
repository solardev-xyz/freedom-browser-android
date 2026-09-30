package baby.freedom.mobile.ens

import baby.freedom.mobile.R
import baby.freedom.mobile.browser.WhatwgHost
import baby.freedom.mobile.l10n.Strings
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
     * Is ENSIP-15 the rule for [name]? Not for a Tezos Domains (`.tez`)
     * name ([tezosForm]): that registry isn't ENS and has its own
     * normalization, so ENSIP-15 would both rewrite names it owns and
     * refuse ones it allows (`ab--c.tez`).
     */
    fun appliesTo(name: String): Boolean = tezosForm(name) == null

    /**
     * [name] normalized the way Tezos Domains keys it, or `null` if it
     * isn't a `.tez` name. Tezos Domains' own rule
     * (developers.tezos.domains, *Name Resolution*) is UTS-46 ToUnicode,
     * nontransitional — not ENSIP-15 — so a non-ASCII name gets the
     * UTS-46 mapping ([WhatwgHost.Uts46.map]): case fold, NFC, fullwidth
     * → ASCII (`café.ｔｅｚ` is a `.tez` name) and U+FE0F dropped
     * (`❤️.tez` → `❤.tez`, the only form the registry can hold, and the
     * one Chromium leaves in the name's virtual host — so the typed name
     * and the host's name are one key). A pure-ASCII name is only
     * lowercased, as [TezosDomainsResolver] has always taken it.
     *
     * Never refuses: a name Tezos Domains' validation would reject
     * (`ab--c.tez`, bidi or joiner errors) keeps its mapped form, and one
     * with a character UTS-46 disallows outright stays lowercased as
     * typed (less any U+FE0F) — either way the registry answers "not
     * found".
     */
    fun tezosForm(name: String): String? {
        val lower = name.lowercase()
        if (lower.all { it.code < 0x80 }) return lower.takeIf { it.endsWith(TEZ) }
        val mapped = try {
            WhatwgHost.uts46.map(name)
        } catch (_: RuntimeException) {
            null
        }
        return mapped?.takeIf { it.endsWith(TEZ) }
            ?: lower.replace("\uFE0F", "").takeIf { it.endsWith(TEZ) }
    }

    private const val TEZ = ".tez"

    /**
     * Desktop's `fastNormalize` (`src/main/ens-resolver.js`): a name that
     * lowercases to `[a-z0-9.-]` only is taken as-is, anything else gets
     * the full [normalize]. A `.tez` name gets [tezosForm] instead.
     * Throws [InvalidNameException] like [normalize].
     */
    fun fastNormalize(name: String): String =
        tezosForm(name) ?: if (pureAsciiHost.matches(name.lowercase())) name.lowercase() else normalize(name)

    /**
     * Does [fastNormalize] get by without the ENSIP-15 spec tables? True
     * of a name that lowercases to `[a-z0-9.-]` and of every `.tez` name
     * ([appliesTo]).
     */
    fun isFastPath(name: String): Boolean =
        pureAsciiHost.matches(name.lowercase()) || !appliesTo(name)

    @Volatile
    private var warmed = false

    /** Have the spec tables been decoded ([warm] has returned)? */
    val isWarm: Boolean get() = warmed

    /**
     * Force the library's one-time spec decode (`spec.bin`/`nf.bin`, a
     * static initializer — so JVM class init makes it thread-safe and
     * idempotent). Call off the main thread at startup so the first
     * non-ASCII name typed or restored doesn't pay for it mid-composition.
     */
    fun warm() {
        normalizeOrNull("a.eth")
        warmed = true
    }

    /**
     * The library wraps the offending label in U+200E LEFT-TO-RIGHT MARKs
     * (so an RTL label prints the right way round); they are invisible
     * and come along when the reason is copied off the error page, so
     * drop them — and any other bidi controls — from the text we show.
     */
    internal fun cleanMessage(message: String?): String =
        (message ?: Strings.get(R.string.names_error_invalid_name))
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .ifBlank { Strings.get(R.string.names_error_invalid_name) }

    /**
     * [normalize], or `null` when ENSIP-15 rejects [name]. A `.tez` name
     * comes back in its [tezosForm], never refused.
     */
    fun normalizeOrNull(name: String): String? {
        tezosForm(name)?.let { return it }
        return try {
            normalize(name)
        } catch (_: InvalidNameException) {
            null
        }
    }
}
