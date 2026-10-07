package baby.freedom.mobile.wallet.ledger

/**
 * What one route has seen of the USB paths of its Ledger's model, for
 * following its Ledger to the new path it comes back under after
 * re-enumerating (opening or quitting an app — #350 R1-F1).
 *
 * Kept over the whole conversation rather than as one list replaced each
 * time the Ledger answers (#350 R4-F1): a same-model Ledger that's off the
 * bus re-enumerating when this one answers isn't listed then, yet it's
 * plugged in and may come back at any time. So every path of the model
 * listed while this route's Ledger was known to be at its own ([seen]) is
 * never where it comes back, and every other path that went missing while
 * it answered is [gone] — another Ledger that may come back under a new
 * path too — until a new path is listed while this route's Ledger
 * answers (that Ledger back), or until this route's Ledger has answered
 * for [goneMs] since without one: a Ledger re-enumerates in a few
 * seconds, so one away that long was unplugged.
 *
 * What it can't tell: a Ledger unplugged and plugged back in (or a new
 * one plugged in) while this one is off the bus looks like this one
 * coming back, as with one Ledger; and one plugged in while another is
 * off the bus re-enumerating looks like that one back.
 */
internal class LedgerUsbTrail(
    own: String,
    listed: Collection<String>,
    private val goneMs: Long = GONE_MS,
    private val clock: () -> Long = Ledger::monotonicMs,
) {
    /** Where this route's Ledger is (or was, until it dropped out). */
    private var own = own

    /** Every path listed while this route's Ledger was at [own]: none of them is where it comes back. */
    private val seen = HashSet<String>()

    /** The other paths of the model listed the last time this route's Ledger answered. */
    private var others: Set<String> = emptySet()

    /** The other paths that went missing while this route's Ledger answered, and when that was first seen. */
    private val gone = LinkedHashMap<String, Long>()

    init {
        answered(listed)
    }

    /** This route's Ledger answered at [own]: what's [listed] of its model now. */
    @Synchronized
    fun answered(listed: Collection<String>) {
        val at = clock()
        val now = listed.toSet() - own
        for (p in others - now) gone.putIfAbsent(p, at)
        gone.keys.removeAll(now)
        // A path new since, listed while this one answered, is another Ledger back: the one gone longest.
        repeat(now.count { it !in seen }) { gone.keys.firstOrNull()?.let(gone::remove) }
        gone.entries.removeAll { at - it.value >= goneMs }
        seen += listed
        seen += own
        others = now
    }

    /**
     * Where this route's Ledger came back, of its model's paths [now],
     * none of them [held] by another route's link ([Ledger.usbHeld]):
     * the one new path, only once every other Ledger [gone] (or gone
     * since the last answer) is accounted for by a new path another
     * route holds (#350 R3-F1, R4-F1). Two or more new paths: there's no
     * telling which is this one's (#350 R2-F1).
     */
    @Synchronized
    fun reappeared(held: Set<String>, now: List<String>): Back {
        val fresh = now.filter { it !in seen && it != own }.distinct()
        val missing = (gone.keys + others).count { it !in now }
        val free = fresh.filter { it !in held }
        if (fresh.count { it in held } < missing) return Back(null, unsure = free.isNotEmpty())
        return Back(free.singleOrNull(), unsure = free.size > 1)
    }

    /** This route's Ledger was followed to [path], with what's [listed] of its model then. */
    @Synchronized
    fun followed(path: String, listed: Collection<String>) {
        own = path
        // Every Ledger gone was accounted for by a path another route holds ([reappeared]).
        gone.clear()
        seen += listed
        seen += path
        others = listed.toSet() - path
    }

    /**
     * [path]: where this route's Ledger came back, if that can be told.
     * [unsure]: a new path is listed that may be it, but may as well be
     * another same-model Ledger's — none is taken (#350 R4-M1).
     */
    internal data class Back(val path: String?, val unsure: Boolean)

    companion object {
        /** How long another Ledger may be off the bus before it's taken as unplugged: as long as one is given to come back. */
        const val GONE_MS = 10_000L
    }
}
