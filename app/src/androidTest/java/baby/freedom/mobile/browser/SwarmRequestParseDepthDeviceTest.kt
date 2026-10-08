package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #486 R2-M1: a request is parsed off the main thread, and org.json
 * parses recursively. Nesting as deep as the container cap allows parses
 * within the main thread's stack, so it must on the parse threads too,
 * not be refused as invalid for overflowing a smaller worker stack.
 */
@RunWith(AndroidJUnit4::class)
class SwarmRequestParseDepthDeviceTest {
    // The id, method and params objects leave room for this many below them.
    private val depth = MAX_SWARM_REQUEST_CONTAINERS - 2

    private fun request(open: String, close: String, leaf: String): String =
        """{"id":7,"method":"swarm_publishData","params":{"x":""" +
            open.repeat(depth) + leaf + close.repeat(depth) + "}}"

    private fun parsedOnParseThread(data: String): SwarmRequest? = runBlocking {
        withContext(SwarmRequestParsing.dispatcher) {
            assertTrue(Thread.currentThread().name.startsWith("swarm-request-parse-"))
            parseSwarmRequest(data)
        }
    }

    @Test
    fun deeplyNestedArraysParse() {
        val data = request("[", "]", "1")
        assertTrue(jsonShapeWithin(data, MAX_SWARM_REQUEST_VALUES, MAX_SWARM_REQUEST_CONTAINERS))
        val parsed = parsedOnParseThread(data)
        assertNotNull(parsed)
        assertEquals(7L, parsed!!.id)
    }

    @Test
    fun deeplyNestedObjectsParse() {
        val data = request("""{"a":""", "}", "1")
        assertTrue(jsonShapeWithin(data, MAX_SWARM_REQUEST_VALUES, MAX_SWARM_REQUEST_CONTAINERS))
        val parsed = parsedOnParseThread(data)
        assertNotNull(parsed)
        assertEquals("swarm_publishData", parsed!!.method)
    }
}
