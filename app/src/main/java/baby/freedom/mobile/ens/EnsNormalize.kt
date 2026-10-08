package baby.freedom.mobile.ens

import baby.freedom.mobile.R
import baby.freedom.mobile.browser.BidiControls
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
     *
     * A `%XX`-escaped name is read as its UTF-8 bytes first: that is how
     * [tezosDisplay] spells a lookalike (`p%D0%B0ypal.tez` is
     * `pаypal.tez`), so the shown form reloads, edits and copies as the
     * same name. Only escapes of bytes 0x80 and above are read; an ASCII
     * escape (`%2F`, `%25`) stays as written (#490 R3-M1), so no decoded
     * name holds a `/`, `%` or control character it didn't already, and
     * decoding twice gives what decoding once did. `%` is never part of a registrable label, so this can't
     * take a name from anyone. An ASCII `xn--` label is *not* mapped
     * (#490 R2-F1): the registry keys the literal ASCII name
     * (`xn--rh8hs4h.tez`) apart from its Unicode reading (`🌮🥷.tez`),
     * and either can be registered on its own.
     */
    fun tezosForm(name: String): String? =
        tezosFormOf(if (name.lowercase().endsWith(TEZ)) percentDecoded(name) ?: name else name)

    private fun tezosFormOf(name: String): String? {
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

    /**
     * [name] with its `%XX` escapes of bytes 0x80 and above read as
     * UTF-8, or `null` if it has none, an escape is malformed, or the
     * bytes aren't valid UTF-8. An escape of an ASCII byte (`%2F`, `%25`,
     * `%0A`) is left as written: [escaped] only ever escapes non-ASCII,
     * so only those escapes are a shown form being read back.
     */
    private fun percentDecoded(name: String): String? {
        if ('%' !in name) return null
        val bytes = java.io.ByteArrayOutputStream(name.length)
        var decodedAny = false
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '%') {
                if (i + 2 >= name.length) return null
                val hi = Character.digit(name[i + 1], 16)
                val lo = Character.digit(name[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                val b = hi * 16 + lo
                if (b < 0x80) {
                    // An ASCII escape stays as written (#490 R3-M1):
                    // [escaped] never makes one, and decoding it would
                    // put `/`, `%` or a control character into the name
                    // (`paypal.com%2F.tez`, or `x%2561.tez` decoded again
                    // wherever the name was already decoded once).
                    for (k in 0..2) bytes.write(name[i + k].code)
                } else {
                    bytes.write(b)
                    decodedAny = true
                }
                i += 3
            } else {
                if (c.code >= 0x80) return null
                bytes.write(c.code)
                i++
            }
        }
        if (!decodedAny) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private const val TEZ = ".tez"

    /**
     * How [name] may be *shown* (#465): a `.tez` name with a non-ASCII
     * label `%XX`-escaped unless ENSIP-15 accepts it unchanged.
     *
     * Tezos Domains registers any IDNA2008 name ([tezosForm] never
     * refuses), so `pаypal.tez` (Cyrillic а) is a real, resolvable name
     * that reads exactly like `paypal.tez` — next to a Verified shield.
     * ENS-family names don't need this: they only resolve once ENSIP-15
     * has passed them, and ENSIP-15 refuses mixed scripts and whole-script
     * confusables. For display only, a `.tez` name gets the same test:
     * a name ENSIP-15 leaves as it is (U+FE0F aside, which [tezosForm]
     * drops) is shown in Unicode (`café.tez`, `❤.tez`, `σοφος.tez`); any
     * other — refused, or one ENSIP-15 would spell differently — shows
     * each non-ASCII character as its UTF-8 `%XX` bytes
     * (`p%D0%B0ypal.tez`), the way a browser shows a non-ASCII URL path.
     *
     * Not `xn--` Punycode, as Chromium shows a DNS host that fails its
     * IDN spoof check (#490 R2-F1): in Tezos Domains the ASCII
     * `xn--pypal-4ve.tez` is a separate name anyone can register, so
     * that spelling would name — and send a reload, copy or bookmark
     * to — someone else. A `%` is never part of a registrable label,
     * and [tezosForm] reads the escapes back, so the shown form is still
     * this name and no other.
     *
     * Fails closed: before [warm] has decoded the spec tables (never
     * decoded here, on what is often the main thread) the name is shown
     * escaped. Anything that isn't a non-ASCII `.tez` name comes back
     * unchanged.
     */
    fun tezosDisplay(name: String): String {
        if (name.all { it.code < 0x80 } || tezosForm(name) == null) return name
        if (!isWarm) return escaped(name)
        // Memoized (#490 R2-M2): the capsule asks again on every
        // recomposition, and ENSIP-15 isn't free.
        synchronized(shownCache) { shownCache[name] }?.let { return it }
        val clean = try {
            normalize(name).replace("\uFE0F", "") == name.replace("\uFE0F", "")
        } catch (_: InvalidNameException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        val shown = if (clean) name else escaped(name)
        synchronized(shownCache) { shownCache[name] = shown }
        return shown
    }

    /** [tezosDisplay]'s answers once warm, the most recent [SHOWN_CACHE_SIZE]. */
    private val shownCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > SHOWN_CACHE_SIZE
    }
    private const val SHOWN_CACHE_SIZE = 64

    /**
     * Drop [tezosDisplay]'s memo (#490 R4-M2): it holds the names it was
     * asked about, private tabs' included, so the private session's end
     * clears it like the app's other in-memory traces of those tabs.
     * Only a memo — the next ask works the answer out again.
     */
    fun forgetShown() {
        synchronized(shownCache) { shownCache.clear() }
    }

    /** How many names [tezosDisplay]'s memo holds (for tests). */
    internal val shownCount: Int get() = synchronized(shownCache) { shownCache.size }

    /** [name] with each non-ASCII code point as its UTF-8 bytes, `%XX` (upper-case hex). */
    private fun escaped(name: String): String = buildString {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (cp < 0x80) {
                append(cp.toChar())
            } else {
                // A lone surrogate has no UTF-8; U+FFFD stands in for it.
                val ch = if (cp in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(cp))
                for (b in ch.toByteArray(Charsets.UTF_8)) {
                    append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            i += Character.charCount(cp)
        }
    }

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

    /** Set by [warm]; tests put it back to `false` to act out startup. */
    @Volatile
    internal var warmed = false

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
     * drop them — and any other bidi control ([BidiControls], ALM
     * included) — from the text we show.
     */
    internal fun cleanMessage(message: String?): String =
        BidiControls.stripped(message ?: Strings.get(R.string.names_error_invalid_name))
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
