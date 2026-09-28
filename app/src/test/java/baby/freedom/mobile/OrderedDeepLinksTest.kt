package baby.freedom.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OrderedDeepLinksTest {

    @Test
    fun fastLinkWithNothingPendingPublishesSynchronously() = runBlocking {
        val published = mutableListOf<String>()
        val links = OrderedDeepLinks(this, Dispatchers.Default) { published += it }
        links.submit(slow = false) { "ascii" }
        assertEquals(listOf("ascii"), published)
    }

    @Test
    fun fastLinkAfterPendingSlowLinkWaitsItsTurn() = runBlocking {
        val published = mutableListOf<String>()
        val links = OrderedDeepLinks(this, Dispatchers.Default) { published += it }
        val tablesWarm = CountDownLatch(1)
        links.submit(slow = true) {
            tablesWarm.await(5, TimeUnit.SECONDS)
            "unicode"
        }
        links.submit(slow = false) { "ascii" }
        // The ASCII link must not overtake the Unicode one still parsing.
        assertEquals(emptyList<String>(), published)
        tablesWarm.countDown()
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertEquals(listOf("unicode", "ascii"), published)

        // Queue drained: the next fast link is synchronous again.
        links.submit(slow = false) { "later" }
        assertEquals(listOf("unicode", "ascii", "later"), published)
    }

    @Test
    fun droppedLinkKeepsItsPlaceInTheOrder() = runBlocking {
        val published = mutableListOf<String>()
        val links = OrderedDeepLinks(this, Dispatchers.Default) { published += it }
        val release = CountDownLatch(1)
        links.submit(slow = true) { release.await(5, TimeUnit.SECONDS); "a" }
        links.submit(slow = true) { null }
        links.submit(slow = false) { "b" }
        release.countDown()
        coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        assertEquals(listOf("a", "b"), published)
    }
}
