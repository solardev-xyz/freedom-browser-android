package baby.freedom.mobile.node

/**
 * The value last broadcast to bound clients, which is what a client that
 * asks ([current]) or binds ([join]) gets too, not the live value (#471).
 *
 * A throttled broadcast ([throttlePeerCount]) can hold the live peer count
 * back for a few seconds, and it only knows what *it* sent: handing a new
 * client the held-back count would leave that client stale once the count
 * goes back to the value last broadcast, since the throttle drops that as
 * nothing new. So every client sees the one sequence the throttle sends.
 *
 * [live] answers only until the first [publish], which the throttle sends
 * at once and to every client.
 *
 * [publish], [join] and [withCurrent] share a lock so a joining client
 * can't receive an older value after a newer broadcast already reached it;
 * the callbacks are `oneway`, so the lock is never held across a wait on
 * the app.
 *
 * The node's notification keeps one of these too (R4-M1): a re-promotion
 * to foreground posts [withCurrent]'s value, the last count the
 * notification's own throttle sent (even while demoted, when nothing was
 * posted), so the next count that throttle drops as unchanged is already
 * what the notification shows.
 */
internal class LastBroadcast<T : Any>(private val live: () -> T) {
    private val lock = Any()

    @Volatile
    private var sent: T? = null

    fun current(): T = sent ?: live()

    fun publish(value: T, send: (T) -> Unit) = synchronized(lock) {
        sent = value
        send(value)
    }

    fun join(register: () -> Unit, send: (T) -> Unit) = synchronized(lock) {
        register()
        send(current())
    }

    /** Runs [block] on [current], with no [publish] in between. */
    fun <R> withCurrent(block: (T) -> R): R = synchronized(lock) { block(current()) }
}
