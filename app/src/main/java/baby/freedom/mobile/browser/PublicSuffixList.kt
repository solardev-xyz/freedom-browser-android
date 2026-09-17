package baby.freedom.mobile.browser

/**
 * The Public Suffix List, as vendored in
 * `src/main/resources/baby/freedom/mobile/browser/public_suffix_list.dat`
 * (regenerate with `infra/psl/vendor-list.py`).
 *
 * The address label rests on a *registrable domain* and asks the user to
 * read it as "who I am talking to", so the boundary it draws has to be
 * the real ownership boundary. A hand-written list of compound ccTLD
 * suffixes only knows the ICANN half of that: it gets `bbc.co.uk` right
 * and then collapses every tenant of a user-content platform —
 * `*.github.io`, `*.web.app`, `*.netlify.app`, `*.blogspot.com`,
 * `*.eth.limo`, … — onto the platform's own name, so a phishing tenant
 * and the site it imitates rest on the same bold label. The PSL's
 * PRIVATE section is exactly the registry of those boundaries, so we
 * ship the whole list and apply both sections.
 *
 * The list also carries the four `*.{bzz,ipfs,ipns,ens}.freedom.baby`
 * virtual-origin suffixes ([VirtualOrigin]) that are pending upstream
 * (issue #6): two content roots are two owners here too.
 *
 * Matching follows the algorithm at <https://publicsuffix.org/list/>:
 * exception (`!`) rules win, then the longest matching rule, wildcards
 * (`*`) match exactly one label, and a host matching nothing is treated
 * as if the rule `*` applied (its TLD is the public suffix).
 */
object PublicSuffixList {

    /**
     * Every rule, verbatim (`co.uk`, `*.ck`, `!www.ck`). Loaded lazily
     * off the classpath — this works both in the APK and in JVM unit
     * tests, and costs nothing until the first label is rendered.
     */
    private val rules: Set<String> by lazy { load() }

    private fun load(): Set<String> =
        try {
            PublicSuffixList::class.java.getResourceAsStream("public_suffix_list.dat")
                ?.bufferedReader()
                ?.useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("//") }
                        .toHashSet()
                }
                .orEmpty()
        } catch (e: Exception) {
            // A stripped or unreadable list must not silently widen the
            // label: [registrableDomain] then answers `null` for every
            // host and the caller shows the host whole, which never
            // vouches for a domain the user is not on.
            emptySet()
        }

    /**
     * The registrable domain of [host] — its public suffix plus one
     * label — or `null` when there isn't one: [host] *is* a public
     * suffix (`github.io`, `co.uk`), it has no label to spare, or the
     * vendored list could not be read. Callers show the host whole in
     * that case.
     *
     * [host] must already be lowercased, punycode-or-unicode (both rule
     * forms are in the list) and free of userinfo / port / trailing dot.
     */
    fun registrableDomain(host: String): String? {
        if (rules.isEmpty() || host.isEmpty()) return null
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return null
        val suffixLabels = publicSuffixLabels(labels)
        if (labels.size <= suffixLabels) return null
        return labels.takeLast(suffixLabels + 1).joinToString(".")
    }

    /** How many trailing labels of [labels] are the public suffix. */
    private fun publicSuffixLabels(labels: List<String>): Int {
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            // An exception rule names a host that is *not* a public
            // suffix, so the suffix is the rule minus its first label.
            if ("!$candidate" in rules) return labels.size - i - 1
            if (candidate in rules) return labels.size - i
            if (i + 1 < labels.size) {
                val wildcard = "*." + labels.subList(i + 1, labels.size).joinToString(".")
                if (wildcard in rules) return labels.size - i
            }
        }
        // The implicit `*` rule: an unknown TLD is a public suffix.
        return 1
    }
}
