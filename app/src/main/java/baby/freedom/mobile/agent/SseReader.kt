package baby.freedom.mobile.agent

import okio.BufferedSource

/**
 * Server-sent events off a response body (#574), as the HTML spec reads
 * them: `event:` and `data:` fields (several `data:` lines joined with
 * `\n`), dispatched on a blank line; `:` comments, `id:` and `retry:`
 * ignored. Bounded: a line longer than [maxLine] bytes, or an event's
 * data past [maxEvent], throws [TooLarge] instead of buffering a server
 * that never sends a line break.
 */
internal class SseReader(
    private val source: BufferedSource,
    private val maxLine: Long = MAX_LINE_BYTES,
    private val maxEvent: Int = MAX_EVENT_CHARS,
) {
    class TooLarge : Exception()

    /** Calls [onEvent] for each event until the body ends (an event left open at the end is dispatched too). */
    fun forEach(onEvent: (event: String?, data: String) -> Unit) {
        var name: String? = null
        val data = StringBuilder()
        var hasData = false
        fun dispatch() {
            if (hasData) onEvent(name, data.toString())
            name = null
            data.setLength(0)
            hasData = false
        }
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> name = value
                "data" -> {
                    if (data.length + value.length + 1 > maxEvent) throw TooLarge()
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
        dispatch()
    }

    /** The next line without its line break, or null at the end of the body. */
    private fun readLine(): String? {
        val buffer = source.buffer
        // Only the bytes that came since the last look are searched.
        var scanned = 0L
        while (true) {
            val i = buffer.indexOf('\n'.code.toByte(), scanned)
            if (i >= 0) {
                if (i > maxLine) throw TooLarge()
                val line = buffer.readUtf8(i)
                buffer.skip(1)
                return line.removeSuffix("\r")
            }
            if (buffer.size > maxLine) throw TooLarge()
            scanned = buffer.size
            if (!source.request(buffer.size + 1)) {
                return if (buffer.size == 0L) null else buffer.readUtf8().removeSuffix("\r")
            }
        }
    }

    companion object {
        const val MAX_LINE_BYTES = 2L * 1024 * 1024
        const val MAX_EVENT_CHARS = 2 * 1024 * 1024
    }
}
