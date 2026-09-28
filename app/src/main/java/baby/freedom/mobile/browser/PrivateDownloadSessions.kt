package baby.freedom.mobile.browser

/**
 * Which private session (#86) each private download belongs to.
 *
 * Private download rows get negative ids, handed out downwards, so one
 * id space covers both download lists. A session is a generation:
 * [end] closes it and returns the span of ids it handed out, and from
 * then on the ended session can neither hand out another id ([allocate]
 * returns null for its generation) nor pass for the live one
 * ([isLive]). That's what keeps a download accepted just before the
 * last private tab closed out of the next session's list — and the next
 * session's downloads out of the ended session's clean-up.
 *
 * Any thread.
 */
internal class PrivateDownloadSessions {
    private var generation = 0L

    /** The last id handed out (0: none yet). */
    private var last = 0L

    /** Ids from here up (to -1) belong to ended sessions. */
    private var floor = 0L

    /** The live session's generation, captured when a download is offered. */
    @Synchronized
    fun current(): Long = generation

    /** A fresh row id for a download of session [gen], or null if that session has ended. */
    @Synchronized
    fun allocate(gen: Long): Long? = if (gen == generation) --last else null

    /** Is private row [id] from the live session? */
    @Synchronized
    fun isLive(id: Long): Boolean = id < floor

    /** End the live session; the ids it handed out (possibly none). */
    @Synchronized
    fun end(): LongRange {
        val ended = last until floor
        floor = last
        generation++
        return ended
    }
}
