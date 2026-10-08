package baby.freedom.mobile.data

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #462: a write the repository fires and forgets must not take the app
 * down when the database throws (storage full), and must not stop the
 * writes that come after it.
 */
class BrowsingRepositoryWriteScopeTest {
    private val escaped = mutableListOf<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun catchUncaught() {
        previous = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { _, e -> escaped += e }
    }

    @After
    fun restore() {
        Thread.currentThread().uncaughtExceptionHandler = previous
    }

    @Test
    fun `a failed write never reaches the uncaught handler`() {
        val scope = BrowsingRepository.writeScope(Dispatchers.Unconfined)
        scope.launch { throw SQLiteFullException("database or disk is full") }
        assertEquals(emptyList<Throwable>(), escaped)
    }

    @Test
    fun `writes after a failed one still run`() {
        val scope = BrowsingRepository.writeScope(Dispatchers.Unconfined)
        scope.launch { throw SQLiteFullException("database or disk is full") }
        scope.launch { error("some other failure") }
        var ran = false
        scope.launch { ran = true }
        assertTrue(scope.isActive)
        assertTrue(ran)
        assertEquals(emptyList<Throwable>(), escaped)
    }
}
