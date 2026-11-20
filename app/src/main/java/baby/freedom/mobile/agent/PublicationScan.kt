package baby.freedom.mobile.agent

import baby.freedom.mobile.wallet.Bip39English
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException

/**
 * The pre-publish check for `swarm_publish` (#597), a port of desktop's
 * `publication-scan.js`: one pass over the exact bytes that will be
 * uploaded, before the approval card is shown. Secrets (keys, tokens,
 * recovery phrases, passwords) block until the user resolves each one;
 * personal data (emails, phone numbers, IBANs, street addresses) and, for
 * HTML and scripts, third-party loads are warnings. A finding carries a
 * redacted excerpt for the card only; the model gets counts and rule
 * names ([summary]), never an excerpt.
 *
 * The rules are written against each provider's documented format, as on
 * desktop. Two engine differences are handled here rather than trusted:
 * desktop's patterns are JavaScript's, whose `\s` covers every Unicode
 * space (a BOM, a no-break space, U+2028) and whose `\b`, `\w` and `\d`
 * are ASCII-only, while Java's (the JVM's in tests, ICU's on the phone)
 * differ from both and from each other. So no pattern here uses `\s`,
 * `\b`, `\w` or `\d`: spaces are [isJsSpace], a word boundary is [B], and
 * classes are spelled out. And open-ended runs (a token's tail, a quoted
 * value) are walked by [runEnd] after a bounded pattern head, as desktop
 * does, so a run of millions of token characters can't blow the regexp
 * engine's stack. A rule that throws anyway fails the file closed with a
 * blocking `scan-incomplete` finding.
 */
internal object PublicationScan {
    const val MAX_FINDINGS = 200

    /** Caps warnings only: every secret in a file is listed, or one the card never shows couldn't be held back. */
    const val MAX_WARNINGS_PER_FILE = 25
    const val MIN_SEED_WORDS = 12

    enum class Kind(val wire: String) { Secret("secret"), Personal("personal"), Metadata("metadata"), ThirdParty("third-party") }

    /**
     * One finding, as the card lists it. [line] is null for a whole-file
     * finding. [excerpt] is desktop's English excerpt; the card builds its
     * own from [shown] (the redacted value) and [words] or [chars].
     */
    data class Finding(
        val id: String = "",
        val path: String,
        val kind: Kind,
        val rule: String,
        val label: String,
        val line: Int?,
        val excerpt: String,
        val blocking: Boolean,
        val shown: String = excerpt,
        val words: Int? = null,
        val chars: Int? = null,
        /** Where the value starts and ends in the scanned text (UTF-16 indices), for the card's context past its preview. */
        val at: Int? = null,
        val until: Int? = null,
    )

    /** A redacted value: what may be [shown], and how many [words] or [chars] the whole had. */
    data class Redacted(val shown: String, val words: Int? = null, val chars: Int? = null)

    /** More secrets than the card could list one by one: desktop's `PUBLICATION_TOO_MANY_FINDINGS`. */
    class TooManyFindings(val count: Int) : Exception("The pre-publish check found $count possible secrets")

    data class Match(val index: Int, val value: String)

    class Rule(val id: String, val kind: Kind, val label: String, val matches: (String) -> Sequence<Match>)

    // --- Character classes, as desktop's JavaScript patterns read them ---

    /** JavaScript's `\s`: ASCII whitespace, the Unicode space separators, U+2028/9 and the BOM. */
    fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    private fun isLineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAsciiDigit(c: Char) = c in '0'..'9'
    private fun isAlnum(c: Char) = isAsciiLetter(c) || isAsciiDigit(c)
    private fun isWord(c: Char) = isAlnum(c) || c == '_'
    private fun isToken(c: Char) = isWord(c) || c == '-'
    private fun isDashToken(c: Char) = isAlnum(c) || c == '-'
    private fun isHex(c: Char) = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'

    /** The `\s` class as a regex fragment (inside brackets). */
    private const val S = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** JavaScript's `\b`: a boundary between an ASCII word character and anything else. */
    private const val B = "(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"

    /** Where the run of characters [keep] accepts, from [from], ends. */
    private inline fun runEnd(text: String, from: Int, keep: (Char) -> Boolean): Int {
        var i = from
        while (i < text.length && keep(text[i])) i++
        return i
    }

    /** The end of the space run at [from] when the character after it is one of [next]; -1 otherwise. */
    private fun spaceThen(text: String, from: Int, next: String): Int {
        val end = runEnd(text, from, ::isJsSpace)
        return if (end < text.length && text[end] in next) end else -1
    }

    // --- Placeholders and checksums ---

    private val PLACEHOLDER = Pattern.compile(
        "your[_ -]|example|placeholder|changeme|change[_-]me|dummy|sample|redacted|insert|replace[_-]?me|<|>|\\.\\.\\.|…|\\{\\{|\\$\\{|process\\.env",
        Pattern.CASE_INSENSITIVE,
    )
    private val REPEATED = Pattern.compile("(.)\\1{7}")
    private val XS = Pattern.compile("x{6}", Pattern.CASE_INSENSITIVE)

    fun placeholderValue(value: String): Boolean =
        PLACEHOLDER.matcher(value).find() || REPEATED.matcher(value).find() || XS.matcher(value).find()

    /** 0x000…, 0xfff…, 0x1234…1234 and similar documentation values. */
    fun lowEntropyHex(hex: String): Boolean {
        val digits = hex.lowercase()
        if (digits.toSet().size <= 3) return true
        for (size in listOf(2, 4, 8, 16)) {
            if (size <= digits.length && digits.length % size == 0 && digits == digits.take(size).repeat(digits.length / size)) return true
        }
        return "0123456789abcdef".repeat(4).startsWith(digits)
    }

    fun ibanValid(value: String): Boolean {
        val compact = value.replace(" ", "").uppercase()
        if (compact.length < 15 || compact.length > 34) return false
        val rearranged = compact.substring(4) + compact.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val digits = if (c in 'A'..'Z') (c.code - 55).toString() else c.toString()
            for (d in digits) {
                if (d !in '0'..'9') return false
                remainder = (remainder * 10 + (d - '0')) % 97
            }
        }
        return remainder == 1
    }

    private val PLACEHOLDER_EMAIL_DOMAIN = Pattern.compile(
        "(?:^|\\.)(?:example\\.(?:com|org|net)|example|test|invalid|localhost|domain\\.com|email\\.com|yourdomain\\.com|company\\.com)$",
        Pattern.CASE_INSENSITIVE,
    )
    private val FILE_LIKE_TLD = Pattern.compile(
        "^(?:png|jpe?g|gif|svg|webp|avif|ico|js|mjs|cjs|css|html?|json|map|ts|tsx|jsx|md|txt|xml|woff2?|ttf)$",
        Pattern.CASE_INSENSITIVE,
    )
    private val KEY_CONTEXT = Pattern.compile(
        "(?:priv(?:ate)?[${S}_-]{0,8}key|privkey|secret|signing[${S}_-]{0,8}key|signer|deployer|mnemonic|wallet[${S}_-]{0,8}key|${B}pk$B|${B}sk$B)",
        Pattern.CASE_INSENSITIVE,
    )
    private val HEX64 = Pattern.compile("[0-9a-fA-F]{64}")

    // --- Pattern heads and their walked tails ---

    /**
     * What a pattern's tail decides, given where its bounded head ended:
     * the value runs to [end] (from [start] when set; scanning resumes at
     * [next], default [end]), or, with no [end], the text doesn't complete
     * the match and scanning resumes at [next].
     */
    private data class Tail(val end: Int? = null, val next: Int? = null, val start: Int? = null)

    private fun tail(keep: (Char) -> Boolean): (String, Int, Matcher) -> Tail = { text, headEnd, _ -> Tail(end = runEnd(text, headEnd, keep)) }

    /** Each match [pattern] (bounded) and [then] (walked) accept, as desktop's `regexRule`. */
    private fun regexRule(
        pattern: Pattern,
        accept: (value: String, text: String, index: Int) -> Boolean = { _, _, _ -> true },
        then: ((String, Int, Matcher) -> Tail)? = null,
    ): (String) -> Sequence<Match> = { text ->
        sequence {
            val m = pattern.matcher(text)
            val named = pattern.pattern().contains("(?<v>")
            var from = 0
            while (from <= text.length && m.find(from)) {
                val headEnd = m.end()
                var index = if (named) m.start("v") else m.start()
                var end = if (named) m.end("v") else headEnd
                from = m.end().coerceAtLeast(m.start() + 1)
                if (then != null) {
                    val t = then(text, headEnd, m)
                    from = maxOf(t.next ?: t.end ?: 0, m.start() + 1)
                    if (t.end == null) continue
                    if (t.start != null) index = t.start
                    end = t.end
                }
                val value = text.substring(index, end)
                if (accept(value, text, index)) yield(Match(index, value))
            }
        }
    }

    private fun jwtTail(text: String, headEnd: Int): Tail {
        val first = runEnd(text, headEnd, ::isToken)
        if (first >= text.length || text[first] != '.') return Tail(next = first)
        val second = runEnd(text, first + 1, ::isToken)
        if (second - first - 1 < 12 || second >= text.length || text[second] != '.') return Tail(next = first)
        val third = runEnd(text, second + 1, ::isToken)
        return if (third - second - 1 < 12) Tail(next = first) else Tail(end = third)
    }

    private fun urlPasswordTail(text: String, headEnd: Int): Tail {
        val user = runEnd(text, headEnd) { !(isJsSpace(it) || it in "/:@'\"<>") }
        if (user == headEnd || user >= text.length || text[user] != ':') return Tail()
        val password = runEnd(text, user + 1) { !(isJsSpace(it) || it in "/@'\"<>") }
        if (password == user + 1 || password >= text.length || text[password] != '@') return Tail()
        return Tail(start = user + 1, end = password, next = password + 1)
    }

    private fun quotedValueTail(text: String, headEnd: Int): Tail {
        val end = runEnd(text, headEnd) { it != '"' && it != '\'' && it != '\r' && it != '\n' }
        return if (end < text.length && (text[end] == '"' || text[end] == '\'')) Tail(end = end, next = end + 1) else Tail()
    }

    /**
     * A character of an unquoted value: anything but a space, a quote, a `#`
     * (a comment follows) or the brackets and separators of code, so
     * `Tr0ub4dor&3horse` reads whole while `os.environ["X"]` or `getpass()`
     * doesn't read as a value.
     */
    internal fun isBareValue(c: Char) = !isJsSpace(c) && c !in "\"'`()[]{},;#"

    /** The value must end its line, optionally followed by blanks and a # comment. */
    private fun bareValueTail(text: String, headEnd: Int): Tail {
        // `$VAR` refers to a value kept elsewhere.
        if (headEnd < text.length && text[headEnd] == '$') return Tail()
        val end = runEnd(text, headEnd, ::isBareValue)
        val after = runEnd(text, end) { isJsSpace(it) && !isLineEnd(it) }
        return if (after == text.length || text[after] == '#' || isLineEnd(text[after])) Tail(end = end) else Tail()
    }

    private const val CREDENTIAL_NAMES = "password|passwd|api[_-]?key|secret|token|access[_-]?token|auth[_-]?token|private[_-]?key"

    private fun isKeyName(c: Char) = isWord(c) || c == '.' || c == '-'
    private val KEY_SUFFIXES = listOf("key", "value")
    private const val MAX_KEY_PREFIX = 256

    /**
     * Where a credential name whose keyword ends at [keywordEnd] ends: the
     * keyword alone, or followed by a `key`/`value` part, as in `SECRET_KEY`,
     * `secretKey` or `token.value`. Other suffixes (`token_count`,
     * `secret_name`) name something else.
     */
    internal fun credentialNameEnd(text: String, keywordEnd: Int): Int {
        val at = if (keywordEnd < text.length && text[keywordEnd] in "_.-") keywordEnd + 1 else keywordEnd
        for (suffix in KEY_SUFFIXES) {
            if (text.regionMatches(at, suffix, 0, suffix.length, ignoreCase = true)) return at + suffix.length
        }
        return keywordEnd
    }

    /**
     * The credential name whose keyword starts at [keywordStart] opens a line,
     * optionally after `export ` and a prefix that ends at a word boundary:
     * `DB_PASSWORD`, `app.secret`, `dbPassword`, but not `nopassword`.
     */
    internal fun credentialKeyOpensLine(text: String, keywordStart: Int): Boolean {
        var key = keywordStart
        while (key > 0 && isKeyName(text[key - 1])) {
            key--
            if (keywordStart - key > MAX_KEY_PREFIX) return false
        }
        if (key < keywordStart) {
            val before = text[keywordStart - 1]
            val camel = (before in 'a'..'z' || isAsciiDigit(before)) && text[keywordStart] in 'A'..'Z'
            if (before !in "_.-" && !camel) return false
        }
        if (opensLine(text, key)) return true
        var export = key
        while (export > 0 && isJsSpace(text[export - 1])) export--
        return export < key && export >= 6 && text.regionMatches(export - 6, "export", 0, 6, ignoreCase = true) &&
            opensLine(text, export - 6)
    }

    /** key ["']? \s* [:=] \s* ["'] value{8,} ["'] */
    private fun quotedAssignmentTail(text: String, keywordEnd: Int): Tail {
        val headEnd = credentialNameEnd(text, keywordEnd)
        val name = headEnd + if (headEnd < text.length && (text[headEnd] == '"' || text[headEnd] == '\'')) 1 else 0
        val operator = spaceThen(text, name, ":=")
        if (operator == -1) return Tail()
        val quote = spaceThen(text, operator + 1, "\"'")
        if (quote == -1) return Tail()
        val result = quotedValueTail(text, quote + 1)
        val end = result.end ?: return Tail()
        return if (end - quote - 1 >= 8) result.copy(start = quote + 1) else Tail()
    }

    /** The key opened a line: `^\s*` under the m flag, where `\s` also crosses blank lines. */
    internal fun opensLine(text: String, index: Int): Boolean {
        var start = index
        while (start > 0 && isJsSpace(text[start - 1])) {
            start -= 1
            if (isLineEnd(text[start])) return true
        }
        return start == 0
    }

    /** ^\s* (export\s+)? prefix? key suffix? \s* [:=] \s* value{8,} \s* (#.*)? $ */
    private fun bareAssignmentTail(text: String, keywordEnd: Int, match: Matcher): Tail {
        if (!credentialKeyOpensLine(text, match.start())) return Tail()
        val operator = spaceThen(text, credentialNameEnd(text, keywordEnd), ":=")
        if (operator == -1) return Tail()
        val start = runEnd(text, operator + 1, ::isJsSpace)
        val result = bareValueTail(text, start)
        val end = result.end ?: return Tail()
        return if (end - start >= 8) result.copy(start = start) else Tail()
    }

    /** -----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----, with the title walked. */
    private fun pemTitleTail(text: String, headEnd: Int): Tail {
        val end = runEnd(text, headEnd) { it in 'A'..'Z' || it == ' ' }
        val title = "PRIVATE KEY"
        if (end - headEnd < title.length || !text.startsWith("-----", end) || !text.startsWith(title, end - title.length)) return Tail()
        val prefix = end - title.length - headEnd
        return if (prefix == 0 || (prefix >= 2 && text[end - title.length - 1] == ' ')) Tail(end = end + 5) else Tail()
    }

    /** `"name" \s* :` (then [valueAt] of what follows), anywhere in [text]. */
    private fun jsonKeyFollowedBy(text: String, name: Pattern, valueAt: (Int) -> Boolean): Boolean {
        val m = name.matcher(text)
        var from = 0
        while (from <= text.length && m.find(from)) {
            val colon = spaceThen(text, m.end(), ":")
            if (colon != -1 && valueAt(runEnd(text, colon + 1, ::isJsSpace))) return true
            from = m.start() + 1
        }
        return false
    }

    private fun isLabel(c: Char) = isAlnum(c) || c == '-'

    /** An email's domain: labels, then a 2-24 letter TLD that ends the run, taking the most labels that work. */
    private fun emailDomainTail(text: String, headEnd: Int): Tail {
        var best: Int? = null
        var at = headEnd
        var labels = 0
        while (true) {
            val end = runEnd(text, at, ::isLabel)
            if (labels > 0 && end - at in 2..24 && (at until end).all { isAsciiLetter(text[it]) }) best = end
            if (end == at || end >= text.length || text[end] != '.') break
            at = end + 1
            labels++
        }
        return if (best == null) Tail(next = headEnd) else Tail(end = best)
    }

    /** "ciphertext" \s* : \s* "hex{32,}" */
    private fun keystoreCiphertextTail(text: String, headEnd: Int): Tail {
        val colon = spaceThen(text, headEnd, ":")
        val quote = if (colon == -1) -1 else spaceThen(text, colon + 1, "\"")
        if (quote == -1) return Tail()
        val end = runEnd(text, quote + 1, ::isHex)
        return if (end - quote - 1 >= 32 && end < text.length && text[end] == '"') Tail(end = end + 1) else Tail()
    }

    private fun p(regex: String, ci: Boolean = false): Pattern = Pattern.compile(regex, if (ci) Pattern.CASE_INSENSITIVE else 0)

    private val notPlaceholder: (String, String, Int) -> Boolean = { v, _, _ -> !placeholderValue(v) }
    private val BIP39 by lazy { Bip39English.words.toHashSet() }

    private fun seedPhraseMatches(text: String): Sequence<Match> = sequence {
        data class W(val word: String, val index: Int, val end: Int)
        var run = mutableListOf<W>()
        fun flushed(): Match? {
            val r = run
            run = mutableListOf()
            // "test test … junk" and "abandon … about" are public development
            // mnemonics printed in tool documentation; they protect nothing.
            if (r.size >= MIN_SEED_WORDS && r.map { it.word }.toSet().size >= 4) {
                return Match(r[0].index, r.joinToString(" ") { it.word })
            }
            return null
        }
        var i = 0
        while (i < text.length) {
            if (!isAsciiLetter(text[i])) {
                i++
                continue
            }
            val index = i
            val end = runEnd(text, i, ::isAsciiLetter)
            val word = text.substring(index, end)
            i = end
            val lower = word.lowercase()
            val previous = run.lastOrNull()
            // Words may be separated by whitespace, list numbering or simple
            // punctuation, as in a "1. word 2. word" backup sheet; anything
            // else (prose punctuation runs, markup) ends the run.
            val contiguous = previous == null || (
                index - previous.end in 1..8 &&
                    (previous.end until index).all { isJsSpace(text[it]) || isAsciiDigit(text[it]) || text[it] in ".,;:)(\"'|-" }
                )
            if (!contiguous) flushed()?.let { yield(it) }
            if (lower in BIP39 && (word == lower || word == word.uppercase() || run.isEmpty())) {
                run.add(W(lower, index, end))
            } else {
                flushed()?.let { yield(it) }
            }
        }
        flushed()?.let { yield(it) }
    }

    val RULES: List<Rule> = listOf(
        Rule("private-key-block", Kind.Secret, "Private key block", regexRule(p("-----BEGIN "), then = { t, e, _ -> pemTitleTail(t, e) })),
        Rule("aws-access-key", Kind.Secret, "AWS access key", regexRule(p("$B(?:AKIA|ASIA)[A-Z0-9]{16}$B"), notPlaceholder)),
        Rule("openai-api-key", Kind.Secret, "OpenAI-style API key",
            regexRule(p("${B}sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20}"), notPlaceholder, tail(::isToken))),
        Rule("github-token", Kind.Secret, "GitHub token",
            regexRule(p("$B(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20}"), notPlaceholder, tail(::isWord))),
        Rule("slack-token", Kind.Secret, "Slack token", regexRule(p("${B}xox[baprs]-[A-Za-z0-9-]{10}"), notPlaceholder, tail(::isDashToken))),
        Rule("jwt", Kind.Secret, "JSON Web Token", regexRule(p("${B}eyJ[A-Za-z0-9_-]{12}"), then = { t, e, _ -> jwtTail(t, e) })),
        Rule("url-credentials", Kind.Secret, "Password in a URL",
            regexRule(
                p("$B(?:https?|wss?|ftp|postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqps?)://", ci = true),
                { v, _, _ -> !placeholderValue(v) && !Regex("^(?:pass(?:word)?|secret|pwd)$", RegexOption.IGNORE_CASE).matches(v) && v.any { it != '*' } },
                { t, e, _ -> urlPasswordTail(t, e) },
            )),
        // Wider than desktop's pattern: the key may carry a prefix
        // (DB_PASSWORD, dbPassword, export GITHUB_TOKEN) or a key/value
        // suffix (SECRET_KEY), and an unquoted value may hold punctuation
        // (Tr0ub4dor&3horse), as .env and YAML files write them.
        Rule("credential-assignment", Kind.Secret, "Password or key assignment") { text ->
            val quoted = regexRule(
                p("(?:$CREDENTIAL_NAMES|authorization|credential)", ci = true),
                { v, _, _ ->
                    !Regex("^(?:\\$\\{|process\\.env\\.|your[_ -]|example|placeholder|changeme|test|dummy|<)", RegexOption.IGNORE_CASE).containsMatchIn(v) &&
                        !placeholderValue(v)
                },
                { t, e, _ -> quotedAssignmentTail(t, e) },
            )(text)
            val bare = regexRule(
                p("(?:$CREDENTIAL_NAMES)", ci = true),
                { v, _, _ ->
                    !Regex("^(?:your[_-]|example|placeholder|changeme|test|dummy)", RegexOption.IGNORE_CASE).containsMatchIn(v) &&
                        !placeholderValue(v)
                },
                { t, e, m -> bareAssignmentTail(t, e, m) },
            )(text)
            quoted + bare
        },
        // A bare 0x + 64 hex is also every transaction hash and Swarm
        // reference, so the key must sit on a line that names it as a key.
        // The line's verdict is worked out once per line, so one long line
        // of keys stays linear.
        Rule("ethereum-private-key", Kind.Secret, "Ethereum private key") { text ->
            var lineEnd = -1
            var named = false
            regexRule(p("(?<![0-9A-Za-z])(?:0x)?(?<v>[0-9a-fA-F]{64})(?![0-9A-Za-z])"), { v, t, index ->
                if (lowEntropyHex(v)) {
                    false
                } else {
                    if (index >= lineEnd) {
                        val start = t.lastIndexOf('\n', index - 1) + 1
                        val end = t.indexOf('\n', index).let { if (it == -1) t.length else it }
                        lineEnd = end
                        named = KEY_CONTEXT.matcher(HEX64.matcher(t.substring(start, end)).replaceAll("")).find()
                    }
                    named
                }
            })(text)
        },
        Rule("bip39-seed-phrase", Kind.Secret, "Wallet recovery phrase", ::seedPhraseMatches),
        // Whether the text has a "kdf" and a "crypto" key is worked out once
        // per text, on the first candidate.
        Rule("keystore-json", Kind.Secret, "Encrypted wallet keystore") { text ->
            var keystore: Boolean? = null
            regexRule(p("\"ciphertext\""), { _, t, _ ->
                keystore ?: (
                    jsonKeyFollowedBy(t, p("\"kdf\"", ci = true)) { at ->
                        Regex("^\"(?:scrypt|pbkdf2)\"", RegexOption.IGNORE_CASE).containsMatchIn(t.substring(at, minOf(t.length, at + 8)))
                    } && jsonKeyFollowedBy(t, p("\"(?:crypto|Crypto)\"")) { true }
                    ).also { keystore = it }
            }, { t, e, _ -> keystoreCiphertextTail(t, e) })(text)
        },
        Rule("rpc-url-key", Kind.Secret, "RPC URL with an embedded key") { text ->
            regexRule(p("$B(?:https?|wss?)://[a-z0-9-]{1,63}\\.infura\\.io/(?:v3|ws/v3)/[0-9a-f]{32}", ci = true), notPlaceholder)(text) +
                regexRule(p("$B(?:https?|wss?)://[a-z0-9-]{1,63}\\.g\\.alchemy\\.com/v2/[A-Za-z0-9_-]{20}", ci = true), notPlaceholder, tail(::isToken))(text) +
                regexRule(p("$B(?:https?|wss?)://(?:[a-z0-9-]{1,63}\\.){1,8}quiknode\\.pro/[0-9a-f]{20}", ci = true), notPlaceholder, tail(::isHex))(text) +
                regexRule(p("$B(?:https?|wss?)://rpc\\.ankr\\.com/[a-z0-9_]{1,64}/[0-9a-f]{40}", ci = true), notPlaceholder, tail(::isHex))(text)
        },
        Rule("stripe-secret-key", Kind.Secret, "Stripe secret key",
            regexRule(p("$B(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16}"), notPlaceholder) { t, e, _ ->
                val end = runEnd(t, e, ::isAlnum)
                if (end < t.length && t[end] == '_') Tail() else Tail(end = end) // the key ends at a non-word character
            }),
        Rule("google-api-key", Kind.Secret, "Google API key", regexRule(p("${B}AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])"), notPlaceholder)),
        Rule("slack-webhook", Kind.Secret, "Slack webhook URL",
            regexRule(p("https://hooks\\.slack\\.com/(?:services|workflows|triggers)/[A-Za-z0-9_/+-]{20}"), notPlaceholder,
                tail { isWord(it) || it in "/+-" })),
        Rule("discord-webhook", Kind.Secret, "Discord webhook URL",
            regexRule(p("https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]{5}"), notPlaceholder) { t, e, _ ->
                val id = runEnd(t, e, ::isAsciiDigit)
                if (id >= t.length || t[id] != '/') {
                    Tail()
                } else {
                    val end = runEnd(t, id + 1, ::isToken)
                    if (end - id - 1 >= 30) Tail(end = end) else Tail()
                }
            }),
        Rule("twilio-api-key", Kind.Secret, "Twilio API key",
            regexRule(p("${B}SK[0-9a-fA-F]{32}$B"), { v, _, _ -> !lowEntropyHex(v.substring(2)) })),
        Rule("sendgrid-api-key", Kind.Secret, "SendGrid API key",
            regexRule(p("${B}SG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])"), notPlaceholder)),
        // Personal data: warnings, never blocking. The local part is bounded
        // by the RFC 5321 limit of 64; the domain is walked, any length.
        Rule("email-address", Kind.Personal, "Email address",
            regexRule(p("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@"), { v, _, _ ->
                val at = v.indexOf('@')
                val domain = v.substring(at + 1).lowercase()
                val local = v.substring(0, at).lowercase()
                !PLACEHOLDER_EMAIL_DOMAIN.matcher(domain).find() && !FILE_LIKE_TLD.matcher(domain.substringAfterLast('.')).find() &&
                    local !in setOf("git", "you", "user", "name", "someone", "email", "me")
            }, { t, e, _ -> emailDomainTail(t, e) })),
        Rule("phone-number", Kind.Personal, "Phone number",
            regexRule(p("(?:tel:|(?<![A-Za-z0-9_+]))\\+[0-9]{1,3}(?:[$S.-]?\\(?[0-9]{1,4}\\)?){2,5}(?![A-Za-z0-9_])"), { v, _, _ ->
                val digits = v.filter(::isAsciiDigit)
                digits.length in 8..15 && !Regex("^([0-9])\\1+$").matches(digits.takeLast(7)) && "1234567" !in digits
            })),
        Rule("iban", Kind.Personal, "Bank account number (IBAN)",
            regexRule(p("$B[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?$B"), { v, _, _ ->
                ibanValid(v) && !Regex("^DE89 ?3704 ?0044 ?0532 ?0130 ?00$").matches(v) && !v.startsWith("GB82 WEST") && !v.startsWith("GB82WEST")
            })),
        Rule("postal-address", Kind.Personal, "Street address") { text ->
            regexRule(
                p("$B[0-9]{1,5}[A-Za-z]?[$S]{1,8}(?:[A-Z][a-z]{1,30}[$S]{1,8}){1,3}(?:Street|St\\.|Avenue|Ave\\.|Road|Rd\\.|Boulevard|Blvd\\.|Lane|Drive|Court|Place)(?![A-Za-z])"),
                { v, _, _ -> !Regex("$B(?:Main|Example|Sample|Fake)$B").containsMatchIn(v) },
            )(text) + regexRule(
                p("$B[A-ZÄÖÜ][a-zäöüß]{1,40}(?:straße|strasse|str\\.|weg|gasse|platz|allee)[$S]{1,8}[0-9]{1,4}[a-z]?$B"),
                { v, _, _ -> !v.startsWith("Muster") && !v.startsWith("Beispiel") },
            )(text)
        },
    )

    /** Line numbers for one text, carried on from the last one asked for while asked in order. */
    private class LineCounter(private val text: String) {
        private var at = 0
        private var line = 1

        fun of(index: Int): Int {
            if (index < at) {
                at = 0
                line = 1
            }
            var cursor = text.indexOf('\n', at)
            while (cursor != -1 && cursor < index) {
                line++
                at = cursor + 1
                cursor = text.indexOf('\n', cursor + 1)
            }
            return line
        }
    }

    private const val DOTS4 = "••••"
    private const val DOTS8 = "••••••••"

    /** What the user sees: enough to recognise the value, not enough to reuse it. */
    fun redactParts(ruleId: String, value: String): Redacted {
        when (ruleId) {
            "bip39-seed-phrase" -> {
                val words = value.split(' ')
                return Redacted("${words[0]} $DOTS4", words = words.size)
            }
            "email-address" -> {
                val at = value.indexOf('@')
                return Redacted("${value[0]}•••${value.substring(at)}")
            }
            "phone-number", "iban" -> {
                val compact = value.removePrefix("tel:")
                return Redacted("${compact.take(4)}$DOTS4${compact.takeLast(2)}")
            }
            "postal-address" -> return Redacted(value.map { if (isAsciiDigit(it)) '•' else it }.joinToString(""))
        }
        if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(value)) return Redacted(value.substring(0, value.lastIndexOf('/') + 1) + DOTS8)
        val shown = minOf(6, value.length / 4)
        return Redacted("${value.take(shown)}$DOTS8", chars = value.length)
    }

    /** Desktop's excerpt, in its English. */
    fun redact(ruleId: String, value: String): String = redactParts(ruleId, value).let { r ->
        when {
            r.words != null -> "${r.shown} (${r.words} words)"
            r.chars != null -> "${r.shown} (${r.chars} characters)"
            else -> r.shown
        }
    }

    private fun incomplete(path: String) = Finding(
        path = path, kind = Kind.Secret, rule = "scan-incomplete", label = "Could not be fully checked", line = null,
        excerpt = "The pre-publish check stopped on this file", blocking = true,
    )

    /**
     * The findings for [text]: every secret, at most [MAX_WARNINGS_PER_FILE]
     * warnings; [secretsOnly] for binary content. A rule that throws makes
     * the file a blocking `scan-incomplete` instead of letting it through.
     */
    fun scanText(path: String, text: String, secretsOnly: Boolean = false, rules: List<Rule> = RULES): List<Finding> {
        val findings = mutableListOf<Finding>()
        // Where each finding's value sits in [text], for telling one value apart from another on the same line.
        val spans = java.util.IdentityHashMap<Finding, IntRange>()
        val seen = HashSet<String>()
        val lines = LineCounter(text)
        var warnings = 0
        for (rule in rules) {
            if (secretsOnly && rule.kind != Kind.Secret) continue
            try {
                for (match in rule.matches(text)) {
                    if (!seen.add("${rule.id}:${match.index}")) continue
                    if (rule.kind != Kind.Secret) {
                        if (warnings >= MAX_WARNINGS_PER_FILE) continue
                        warnings++
                    }
                    val r = redactParts(rule.id, match.value)
                    val finding = Finding(
                        path = path, kind = rule.kind, rule = rule.id, label = rule.label, line = lines.of(match.index),
                        excerpt = redact(rule.id, match.value), blocking = rule.kind == Kind.Secret,
                        shown = r.shown, words = r.words, chars = r.chars,
                        at = match.index, until = match.index + match.value.length,
                    )
                    findings += finding
                    spans[finding] = match.index until match.index + match.value.length
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A regexp engine limit (StackOverflowError is no Exception) or an unexpected input.
                if (findings.none { it.rule == "scan-incomplete" }) findings += incomplete(path)
            }
        }
        // A key assigned to PRIVATE_KEY = '…' is one value: report the specific
        // rule. Only the same value, though: a second secret on that line (a
        // password next to a token in one-line JSON) is its own finding.
        val specific = findings.filter { it.kind == Kind.Secret && it.rule != "credential-assignment" }.mapNotNull { spans[it] }
        findings.removeAll { f ->
            f.rule == "credential-assignment" && spans[f]?.let { s -> specific.any { it.first <= s.last && s.first <= it.last } } == true
        }
        // The broader credential check stays the backstop: text it refuses is
        // reported even when no named rule recognised the value. It runs with
        // every named secret's value cut out, so a credential only it knows
        // (a password the named rule took for a placeholder) is still listed
        // next to a token on another line, while the token itself isn't
        // reported twice. A line break ends every value the backstop reads.
        if (findings.none { it.rule == "scan-incomplete" }) {
            val named = findings.filter { it.kind == Kind.Secret }.mapNotNull { spans[it] }
            val rest = if (named.isEmpty()) text else StringBuilder(text).apply { for (s in named) for (i in s) setCharAt(i, '\n') }.toString()
            val possible = try {
                SecretBackstop.containsSecret(rest)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                findings += incomplete(path)
                false
            }
            if (possible) {
                findings += Finding(
                    path = path, kind = Kind.Secret, rule = "possible-credential", label = "Possible credential", line = null,
                    excerpt = "Value hidden", blocking = true,
                )
            }
        }
        return findings.sortedBy { it.line ?: 0 }
    }

    // --- Generated-code hygiene (warnings only; content is never rewritten) ---
    //
    // A site published to Swarm is served from bzz://, so anything it loads
    // from an http(s) origin is a third party: the page breaks when that host
    // goes away, and the host learns who opens it. A form that posts
    // elsewhere sends what visitors type to that host.

    private val MARKUP_FILE = Regex("\\.(?:html?|xhtml|svg|xml)$", RegexOption.IGNORE_CASE)

    /**
     * The media types a browser renders as markup, loading what its tags name,
     * as the file extensions above. Plain XML counts: an element in the XHTML
     * (or SVG) namespace inside it is a live `<script>` or `<img>`.
     */
    private val MARKUP_TYPE = Regex("^(?:text/html|application/xhtml\\+xml|image/svg\\+xml|text/xml|application/xml)(?![A-Za-z0-9_])")
    private val SCRIPT_FILE = Regex("\\.(?:m?js|cjs)$", RegexOption.IGNORE_CASE)
    private val HYGIENE_TAG = p("<(?:[A-Za-z_][A-Za-z0-9_.-]{0,64}:)?(script|link|img|form)(?=[$S/>])", ci = true)
    private val HYGIENE_FETCH = p("${B}fetch[ \\t]{0,16}\\([ \\t\\r\\n]{0,16}([\"'`])")
    private val HYGIENE_ATTRIBUTE = p(
        "(?:^|[$S])(src|href|action)[ \\t\\r\\n]{0,16}=[ \\t\\r\\n]{0,16}(?:\"([^\"]{0,2048})\"|'([^']{0,2048})'|([^$S\"'>]{1,2048}))",
        ci = true,
    )
    private val LINK_NOT_LOADED = p(
        "(?:^|[$S])rel[ \\t\\r\\n]{0,16}=[ \\t\\r\\n]{0,16}[\"']?(?:canonical|alternate|author|me|license|help|next|prev|bookmark|external|nofollow|noopener|noreferrer)[\"'$S>]",
        ci = true,
    )
    private const val MAX_TAG_CHARS = 4096
    private const val MAX_URL_CHARS = 2048

    private class Hygiene(val rule: String, val label: String, val attribute: String?)

    private val HYGIENE = mapOf(
        "script" to Hygiene("third-party-script", "Script from a third-party site", "src"),
        "link" to Hygiene("third-party-link", "Stylesheet or resource from a third-party site", "href"),
        "img" to Hygiene("third-party-image", "Image from a third-party site", "src"),
        "form" to Hygiene("external-form", "Form sends its data to another site", "action"),
        "fetch" to Hygiene("third-party-fetch", "Request to a third-party site (fetch)", null),
    )

    /**
     * The origin of an absolute http(s) or protocol-relative URL; null for a
     * relative, bzz://, data: or other in-site reference. Parsed by hand the
     * way a browser splits an authority (a backslash counts as a slash, the
     * user info goes, a default port is dropped).
     */
    fun thirdPartyOrigin(value: String): String? {
        val url = value.trim { isJsSpace(it) || it.code <= 0x20 }
        val scheme: String
        val rest: String
        val lower = url.lowercase()
        when {
            lower.startsWith("http://") -> { scheme = "http"; rest = url.substring(7) }
            lower.startsWith("https://") -> { scheme = "https"; rest = url.substring(8) }
            url.startsWith("//") -> { scheme = "https"; rest = url.substring(2) }
            else -> return null
        }
        val authority = rest.substring(0, rest.indexOfFirst { it in "/?#\\" }.let { if (it == -1) rest.length else it })
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close == -1) return null
            hostPort.substring(0, close + 1) to hostPort.substring(close + 1).removePrefix(":")
        } else {
            hostPort.substringBefore(':') to (if (':' in hostPort) hostPort.substringAfter(':') else "")
        }
        if (host.isEmpty() || host.any { isJsSpace(it) || it.code < 0x20 || it in "<>\"^`{|}" }) return null
        if (port.isNotEmpty() && (!port.all(::isAsciiDigit) || port.length > 5 || port.toInt() > 65535)) return null
        val shownPort = port.takeIf { it.isNotEmpty() }?.toInt()?.takeIf { !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        return "$scheme://${host.lowercase()}" + (shownPort?.let { ":$it" } ?: "")
    }

    fun hygieneFindings(path: String, text: String, markup: Boolean, script: Boolean): List<Finding> {
        val findings = mutableListOf<Finding>()
        val seen = HashSet<String>()
        val lines = LineCounter(text)
        fun add(kind: String, origin: String, index: Int) {
            val spec = HYGIENE.getValue(kind)
            // One finding per rule and origin in a file: ten images from one CDN are one decision.
            if (findings.size >= MAX_WARNINGS_PER_FILE || !seen.add("${spec.rule}:$origin")) return
            // The excerpt is the origin only: a URL's path or query can carry a key.
            findings += Finding(
                path = path, kind = Kind.ThirdParty, rule = spec.rule, label = spec.label, line = lines.of(index),
                excerpt = origin, blocking = false, at = index,
            )
        }
        if (markup) {
            // Each tag is read up to its '>' or the next '<', with both positions carried forward.
            var nextClose = -1
            var nextOpen = -1
            val m = HYGIENE_TAG.matcher(text)
            while (m.find()) {
                val start = m.end()
                if (nextClose < start) nextClose = text.indexOf('>', start).let { if (it == -1) text.length else it }
                if (nextOpen < start) nextOpen = text.indexOf('<', start).let { if (it == -1) text.length else it }
                val tag = text.substring(start, minOf(nextClose, nextOpen, start + MAX_TAG_CHARS, text.length))
                val kind = m.group(1)!!.lowercase()
                // rel=canonical/alternate/… only names another page; nothing is loaded.
                if (kind == "link" && LINK_NOT_LOADED.matcher(tag).find()) continue
                val a = HYGIENE_ATTRIBUTE.matcher(tag)
                while (a.find()) {
                    if (a.group(1)!!.lowercase() != HYGIENE.getValue(kind).attribute) continue
                    val origin = thirdPartyOrigin(a.group(2) ?: a.group(3) ?: a.group(4) ?: "")
                    if (origin != null) add(kind, origin, m.start())
                    break
                }
            }
        }
        if (markup || script) {
            val m = HYGIENE_FETCH.matcher(text)
            while (m.find()) {
                val start = m.end()
                val end = text.indexOf(m.group(1)!!, start)
                if (end == -1 || end - start > MAX_URL_CHARS) continue
                thirdPartyOrigin(text.substring(start, end))?.let { add("fetch", it, m.start()) }
            }
        }
        return findings
    }

    /**
     * A file's bytes, read as text (the agent's inline text skips this,
     * [scanBytes]' `inlineText`): a NUL in the first
     * 8 KiB means binary (images, fonts, databases), which is read as
     * Latin-1, one character per byte, so an ASCII credential anywhere in
     * it is still seen, and only the secret rules run there.
     */
    fun textOf(bytes: ByteArray): Pair<String, Boolean> {
        val binary = (0 until minOf(bytes.size, 8192)).any { bytes[it] == 0.toByte() }
        return if (binary) String(bytes, Charsets.ISO_8859_1) to true else String(bytes, Charsets.UTF_8) to false
    }

    /**
     * [inlineText] is text the agent wrote, not a file: it is always read as
     * UTF-8 and gets every rule, NUL or not (a NUL in it says nothing about
     * the rest being binary).
     */
    fun scanBytes(path: String, bytes: ByteArray, contentType: String? = null, inlineText: Boolean = false): List<Finding> {
        val (text, binary) = if (inlineText) String(bytes, Charsets.UTF_8) to false else textOf(bytes)
        val findings = scanText(path, text, secretsOnly = binary).toMutableList()
        val type = contentType.orEmpty().trimStart { isJsSpace(it) }.lowercase()
        val markup = MARKUP_FILE.containsMatchIn(path) || MARKUP_TYPE.containsMatchIn(type)
        val script = SCRIPT_FILE.containsMatchIn(path) || Regex("^(?:text|application)/javascript(?![A-Za-z0-9_])").containsMatchIn(type)
        if (!binary && (markup || script)) {
            try {
                findings += hygieneFindings(path, text, markup, script)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // A warning pass that fails never blocks or hides the secret scan.
            }
        }
        return findings
    }

    /** Every blocking finding reaches the card, or it couldn't be held back; warnings fill what room is left. */
    fun capFindings(findings: List<Finding>): List<Finding> {
        val blocking = findings.count { it.blocking }
        if (blocking > MAX_FINDINGS) throw TooManyFindings(blocking)
        var room = MAX_FINDINGS - blocking
        return findings.filter { it.blocking || room-- > 0 }.mapIndexed { i, f -> f.copy(id = "finding_${i + 1}") }
    }

    /** The findings for a text publication of [bytes] (the frozen snapshot), capped and numbered. */
    fun scanPublication(name: String, bytes: ByteArray, contentType: String?): List<Finding> =
        capFindings(scanBytes(name, bytes, contentType, inlineText = true))

    /** The only scan detail that may reach the model: counts and rule names. */
    fun summary(findings: List<Finding>): org.json.JSONObject = org.json.JSONObject()
        .put("findings", findings.size)
        .put("rules", org.json.JSONArray(findings.map { it.rule }.distinct()))
        .put("excludedFiles", 0)
        .put("metadataStripped", 0)
}

/**
 * Desktop's `historyContainsSecret` (workspace-history-policy.js): the
 * broader credential check kept as the scan's backstop. Every pattern is
 * bounded and open-ended runs are walked, as in [PublicationScan]. Its
 * key assignments read the same wider key names and unquoted values as
 * the `credential-assignment` rule.
 */
internal object SecretBackstop {
    private const val S = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val B = "(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"
    private val TOKENS = Pattern.compile(
        "$B(?:AKIA|ASIA)[A-Z0-9]{16}$B|$B(?:sk-(?:proj-)?|gh[pousr]_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{20}",
    )
    private val JWT = Pattern.compile("${B}eyJ[A-Za-z0-9_-]{12}")
    private val URL = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE)
    private val ASSIGNMENTS = Pattern.compile(
        "(?:password|passwd|api[_-]?key|secret|token|access[_-]?token|auth[_-]?token|authorization|private[_-]?key|credential)",
        Pattern.CASE_INSENSITIVE,
    )
    private val BARE = Pattern.compile(
        "(?:password|passwd|api[_-]?key|secret|token|access[_-]?token|auth[_-]?token|private[_-]?key)",
        Pattern.CASE_INSENSITIVE,
    )
    private val QUOTED_PLACEHOLDER = Regex("^(?:\\$\\{|process\\.env\\.|your[_ -]|example|placeholder|changeme|test|dummy|<)", RegexOption.IGNORE_CASE)
    private val BARE_PLACEHOLDER = Regex("^(?:your[_-]|example|placeholder|changeme|test|dummy)", RegexOption.IGNORE_CASE)

    private fun isSpace(c: Char) = PublicationScan.isJsSpace(c)
    private fun isLineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
    private fun isToken(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private inline fun runEnd(text: String, from: Int, keep: (Char) -> Boolean): Int {
        var i = from
        while (i < text.length && keep(text[i])) i++
        return i
    }

    private fun spaceThen(text: String, from: Int, next: String): Int {
        val end = runEnd(text, from, ::isSpace)
        return if (end < text.length && text[end] in next) end else -1
    }

    fun containsSecret(text: String): Boolean {
        if (TOKENS.matcher(text).find()) return true
        // -----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----
        var pem = text.indexOf("-----BEGIN ")
        while (pem != -1) {
            val start = pem + "-----BEGIN ".length
            val end = runEnd(text, start) { it in 'A'..'Z' || it == ' ' }
            val prefix = end - start - "PRIVATE KEY".length
            if (prefix >= 0 && text.startsWith("PRIVATE KEY-----", end - "PRIVATE KEY".length) &&
                (prefix == 0 || (prefix >= 2 && text[start + prefix - 1] == ' '))
            ) {
                return true
            }
            pem = text.indexOf("-----BEGIN ", pem + 1)
        }
        val jwt = JWT.matcher(text)
        var from = 0
        while (from <= text.length && jwt.find(from)) {
            val first = runEnd(text, jwt.end(), ::isToken)
            val second = if (first < text.length && text[first] == '.') runEnd(text, first + 1, ::isToken) else -1
            val third = if (second - first > 12 && second < text.length && text[second] == '.') runEnd(text, second + 1, ::isToken) else -1
            if (third - second > 12) return true
            from = maxOf(first, jwt.start() + 1)
        }
        val url = URL.matcher(text)
        while (url.find()) {
            val user = runEnd(text, url.end()) { !(isSpace(it) || it == '/' || it == ':') }
            if (user == url.end() || user >= text.length || text[user] != ':') continue
            val password = runEnd(text, user + 1) { !(isSpace(it) || it == '/' || it == '@') }
            if (password > user + 1 && password < text.length && text[password] == '@') return true
        }
        // ["']? key ["']? \s* [:=] \s* ["'] value{8,} ["']
        val a = ASSIGNMENTS.matcher(text)
        from = 0
        while (from <= text.length && a.find(from)) {
            from = a.start() + 1
            val name = PublicationScan.credentialNameEnd(text, a.end())
            val operator = spaceThen(text, name + if (name < text.length && (text[name] == '"' || text[name] == '\'')) 1 else 0, ":=")
            val quote = if (operator == -1) -1 else spaceThen(text, operator + 1, "\"'")
            if (quote == -1) continue
            val end = runEnd(text, quote + 1) { it != '"' && it != '\'' && it != '\r' && it != '\n' }
            if (end - quote - 1 < 8 || end >= text.length || (text[end] != '"' && text[end] != '\'')) continue
            // A placeholder value is skipped whole.
            from = end + 1
            if (!QUOTED_PLACEHOLDER.containsMatchIn(text.substring(quote + 1, minOf(text.length, quote + 41)))) return true
        }
        // ^\s* (export\s+)? prefix? key suffix? \s* [:=] \s* value{8,} \s* (#.*)? $, multiline.
        val bare = BARE.matcher(text)
        while (bare.find()) {
            if (!PublicationScan.credentialKeyOpensLine(text, bare.start())) continue
            val operator = spaceThen(text, PublicationScan.credentialNameEnd(text, bare.end()), ":=")
            if (operator == -1) continue
            val start = runEnd(text, operator + 1, ::isSpace)
            if (start < text.length && text[start] == '$') continue
            // The value must end its line, optionally followed by blanks and a # comment.
            val end = runEnd(text, start, PublicationScan::isBareValue)
            val after = runEnd(text, end) { isSpace(it) && !isLineEnd(it) }
            if (end - start >= 8 &&
                (after == text.length || text[after] == '#' || isLineEnd(text[after])) &&
                !BARE_PLACEHOLDER.containsMatchIn(text.substring(start, minOf(text.length, start + 40)))
            ) {
                return true
            }
        }
        return false
    }
}
