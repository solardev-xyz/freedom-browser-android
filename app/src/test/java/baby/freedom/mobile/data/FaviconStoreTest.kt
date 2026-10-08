package baby.freedom.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #482: a favicon that didn't change is neither rewritten nor re-emitted. */
class FaviconStoreTest {
    private class FakeDao : FaviconDao {
        val rows = HashMap<String, FaviconEntry>()
        var upserts = 0
        var restamps = 0

        override suspend fun upsert(entry: FaviconEntry) { upserts++; rows[entry.origin] = entry }
        override fun get(origin: String): Flow<ByteArray?> = flowOf(rows[origin]?.data)
        override suspend fun dataOnce(origin: String): ByteArray? = rows[origin]?.data
        override suspend fun clear() = rows.clear()
        override suspend fun stampsSince(since: Long) =
            rows.values.filter { it.updatedAt >= since }.map { FaviconStamp(it.origin, it.updatedAt) }
        override suspend fun delete(origin: String) { rows.remove(origin) }
        override suspend fun undate(origin: String) = restamp(origin, 0L)
        override suspend fun restamp(origin: String, at: Long) {
            restamps++
            rows[origin]?.let { rows[origin] = it.copy(updatedAt = at) }
        }
    }

    @Test
    fun `a new icon is written`() = runBlocking {
        val dao = FakeDao()
        storeFaviconRow(dao, FaviconEntry("https://a.example", byteArrayOf(1, 2), 10L))
        assertEquals(1, dao.upserts)
        assertArrayEquals(byteArrayOf(1, 2), dao.rows.getValue("https://a.example").data)
    }

    @Test
    fun `the same icon again only moves the time`() = runBlocking {
        val dao = FakeDao()
        storeFaviconRow(dao, FaviconEntry("https://a.example", byteArrayOf(1, 2), 10L))
        storeFaviconRow(dao, FaviconEntry("https://a.example", byteArrayOf(1, 2), 20L))
        assertEquals(1, dao.upserts)
        assertEquals(1, dao.restamps)
        // Still dated by the latest visit, for Delete browsing data (#480).
        assertEquals(listOf(FaviconStamp("https://a.example", 20L)), dao.stampsSince(15L))
    }

    @Test
    fun `a changed icon replaces the old one`() = runBlocking {
        val dao = FakeDao()
        storeFaviconRow(dao, FaviconEntry("https://a.example", byteArrayOf(1, 2), 10L))
        storeFaviconRow(dao, FaviconEntry("https://a.example", byteArrayOf(3), 20L))
        assertEquals(2, dao.upserts)
        assertEquals(0, dao.restamps)
        assertArrayEquals(byteArrayOf(3), dao.rows.getValue("https://a.example").data)
    }

    @Test
    fun `an unchanged icon read again as a new array is not passed on`() = runBlocking {
        val seen = flowOf(null, byteArrayOf(1, 2), byteArrayOf(1, 2), byteArrayOf(1, 2), byteArrayOf(3), null, null)
            .distinctIcons()
            .toList()
        assertEquals(4, seen.size)
        assertNull(seen[0])
        assertArrayEquals(byteArrayOf(1, 2), seen[1])
        assertArrayEquals(byteArrayOf(3), seen[2])
        assertNull(seen[3])
    }
}
