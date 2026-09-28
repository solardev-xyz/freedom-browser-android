package baby.freedom.mobile.chains

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Chainlist.parse] on Android's own org.json, where [ChainlistService]
 * runs it (a `Dispatchers.Default` worker): its `JSONTokener` recurses
 * once per nesting level with no depth limit, so a deeply nested body
 * overflows the stack. That must read as "not a chain list", not crash
 * the app. (The JVM tests' org.json caps nesting itself and throws a
 * `JSONException`, so only the device can show this.)
 */
@RunWith(AndroidJUnit4::class)
class ChainlistParseTest {
    @Test
    fun deeplyNestedBodyIsNotAChainList() = runBlocking {
        val body = "[".repeat(100_000)
        assertNull(withContext(Dispatchers.Default) { Chainlist.parse(body) })
    }
}
