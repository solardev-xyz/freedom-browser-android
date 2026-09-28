package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepLinkQueueTest {

    private fun DeepLinkQueue.urls() = pending.value.map { it.url }

    @Test
    fun burstOfLinksIsKeptInOrderAndEachIsHandedOutInTurn() {
        val queue = DeepLinkQueue()
        // What the chained jobs do when the ENSIP-15 warm-up finishes:
        // publish back to back, before the UI recomposes.
        queue.offer("fox.eth")
        queue.offer("vitalik.eth")
        assertEquals(listOf("fox.eth", "vitalik.eth"), queue.urls())

        val opened = mutableListOf<String>()
        while (true) {
            val head = queue.pending.value.firstOrNull() ?: break
            opened += head.url
            queue.handled(head)
        }
        assertEquals(listOf("fox.eth", "vitalik.eth"), opened)
    }

    @Test
    fun staleHandledCallDoesNotDropAnUnopenedLink() {
        val queue = DeepLinkQueue()
        queue.offer("a.eth")
        val a = queue.pending.value.first()
        queue.handled(a)
        queue.offer("b.eth")
        queue.handled(a) // e.g. a repeated call for the link already opened
        assertEquals(listOf("b.eth"), queue.urls())
    }

    @Test
    fun repeatedUrlGetsATabEachTime() {
        val queue = DeepLinkQueue()
        queue.offer("same.eth")
        queue.offer("same.eth")
        val first = queue.pending.value.first()
        queue.handled(first)
        val second = queue.pending.value.first()
        // A distinct head, so a LaunchedEffect keyed on it runs again.
        assert(first != second)
        queue.handled(second)
        assertEquals(emptyList<String>(), queue.urls())
    }
}
