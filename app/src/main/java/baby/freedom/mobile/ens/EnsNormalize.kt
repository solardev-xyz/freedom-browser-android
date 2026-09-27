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
 * Unlike desktop's `fastNormalize` there is no pure-ASCII shortcut: the
 * spec also rejects some ASCII names (`ab--c.eth`, `a_b.eth`, `a..eth`),
 * and the full pass is microseconds.
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
            throw InvalidNameException(e.message ?: "invalid name", e)
        }

    /** [normalize], or `null` when ENSIP-15 rejects [name]. */
    fun normalizeOrNull(name: String): String? =
        try {
            normalize(name)
        } catch (_: InvalidNameException) {
            null
        }
}
