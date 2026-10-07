package baby.freedom.mobile.browser

import baby.freedom.mobile.data.SwarmCacheSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmCacheTest {
    private val mb = 1024L * 1024L

    /** ant_cache_status's JSON, as ant v0.5.60 writes it. */
    private val statusJson = """{"disk_enabled":true,"used_bytes":${312 * mb},"capacity_bytes":${512 * mb},""" +
        """"chunks":79872,"pinned_bytes":${1234 * mb},"pinned_chunks":315904,"file_bytes":${1600 * mb},""" +
        """"memory_chunks":4000,"memory_capacity_chunks":8192}"""

    private fun status(used: Long = 312 * mb, capacity: Long = 512 * mb, pinned: Long = 0, disk: Boolean = true) =
        SwarmCacheStatus(disk, used, capacity, 0, pinned, 0, 0, 0, 8192)

    @Test
    fun `status parses every figure`() {
        val s = SwarmCacheStatus.parse(statusJson)
        assertEquals(
            SwarmCacheStatus(true, 312 * mb, 512 * mb, 79872, 1234 * mb, 315904, 1600 * mb, 4000, 8192),
            s,
        )
    }

    @Test
    fun `status with the disk cache off reads as such`() {
        val s = SwarmCacheStatus.parse("""{"disk_enabled":false,"used_bytes":0,"capacity_bytes":0,"chunks":0,""" +
            """"pinned_bytes":0,"pinned_chunks":0,"file_bytes":0,"memory_chunks":3,"memory_capacity_chunks":8192}""")
        assertNotNull(s)
        assertFalse(s!!.diskEnabled)
        assertEquals(3L, s.memoryChunks)
    }

    @Test
    fun `an unreadable or foreign answer is no status, and a missing or negative figure is 0`() {
        assertNull(SwarmCacheStatus.parse(null))
        assertNull(SwarmCacheStatus.parse(""))
        assertNull(SwarmCacheStatus.parse("not json"))
        assertNull(SwarmCacheStatus.parse("""{"error":"the Swarm node isn't running"}"""))
        val s = SwarmCacheStatus.parse("""{"disk_enabled":true,"used_bytes":-5}""")!!
        assertEquals(0L, s.usedBytes)
        assertEquals(0L, s.capacityBytes)
    }

    @Test
    fun `a clear's report parses, with the status after it`() {
        val r = SwarmCacheCleared.parse(
            """{"freed_bytes":${300 * mb},"removed_chunks":76800,"file_bytes_before":127938736,""" +
                """"file_bytes_after":69632,"memory_chunks_removed":4000,"status":$statusJson}""",
        )
        val c = r.getOrThrow()
        assertEquals(300 * mb, c.freedBytes)
        assertEquals(127938736L, c.fileBytesBefore)
        assertEquals(69632L, c.fileBytesAfter)
        assertEquals(312 * mb, c.status!!.usedBytes)
    }

    @Test
    fun `a clear's error answer, or an unreadable one, is a failure carrying its message`() {
        val failed = SwarmCacheCleared.parse("""{"error":"the Swarm node isn't running"}""")
        assertTrue(failed.isFailure)
        assertEquals("the Swarm node isn't running", failed.exceptionOrNull()!!.message)
        assertTrue(SwarmCacheCleared.parse("garbage").isFailure)
        assertTrue(SwarmCacheCleared.parse(null).isFailure)
        assertTrue(SwarmCacheCleared.parse("{}").isFailure)
    }

    @Test
    fun `summary is used of size, with pinned only when there is some`() {
        assertEquals("312 MB of 512 MB", swarmCacheSummary(status()))
        assertEquals("312 MB of 512 MB · 1.2 GB pinned", swarmCacheSummary(status(pinned = 1234 * mb)))
        assertEquals("0 B of 1.0 GB", swarmCacheSummary(status(used = 0, capacity = 1024 * mb)))
    }

    @Test
    fun `freed line names what the clear removed`() {
        assertEquals("Freed 300 MB", swarmCacheFreedLine(SwarmCacheCleared(300 * mb, 0, 0, null)))
        assertEquals("Freed 0 B", swarmCacheFreedLine(SwarmCacheCleared(0, 0, 0, null)))
    }

    @Test
    fun `size labels are whole numbers, the default marked in the picker`() {
        assertEquals(
            listOf("256 MB", "512 MB", "1 GB", "2 GB", "4 GB"),
            SwarmCacheSize.entries.map(::swarmCacheSizeLabel),
        )
        assertEquals(
            listOf("256 MB", "512 MB (default)", "1 GB", "2 GB", "4 GB"),
            SwarmCacheSize.entries.map(::swarmCacheSizeOption),
        )
    }

    @Test
    fun `only a smaller size counts as shrinking`() {
        assertTrue(swarmCacheShrinks(SwarmCacheSize.MB_512, SwarmCacheSize.MB_256))
        assertTrue(swarmCacheShrinks(SwarmCacheSize.GB_4, SwarmCacheSize.GB_1))
        assertFalse(swarmCacheShrinks(SwarmCacheSize.MB_512, SwarmCacheSize.MB_512))
        assertFalse(swarmCacheShrinks(SwarmCacheSize.MB_256, SwarmCacheSize.GB_2))
    }

    @Test
    fun `the cache line says why there are no figures, and clear is offered only when it can act`() {
        val stopped = SwarmCacheView(running = false, status = null, size = SwarmCacheSize.MB_512, clear = SwarmCache.Clear.Idle)
        assertEquals("Shown while the Swarm node is running.", swarmCacheLine(stopped))
        assertFalse(swarmCacheClearEnabled(stopped))

        val reading = stopped.copy(running = true)
        assertEquals("Checking…", swarmCacheLine(reading))
        assertFalse(swarmCacheClearEnabled(reading))

        val noDisk = reading.copy(status = status(used = 0, capacity = 0, disk = false))
        assertTrue(swarmCacheLine(noDisk).startsWith("The cache on this device couldn't be opened"))
        assertFalse(swarmCacheClearEnabled(noDisk))

        val ready = reading.copy(status = status())
        assertEquals("312 MB of 512 MB", swarmCacheLine(ready))
        assertTrue(swarmCacheClearEnabled(ready))
        assertFalse(swarmCacheClearEnabled(ready.copy(clear = SwarmCache.Clear.Running)))
        assertTrue(swarmCacheClearEnabled(ready.copy(clear = SwarmCache.Clear.Failed("x"))))
    }

    @Test
    fun `settings search finds the cache size by cache, storage and disk space`() {
        val rows = listOf(swarmCacheSizeRow(SwarmCacheSize.GB_1))
        for (q in listOf("cache", "Swarm cache size", "storage", "disk space", "1 GB", "clear cache")) {
            assertEquals(q, setOf("swarm-cache-size"), visibleSettingsRows(q, "Nodes", rows))
        }
        assertTrue(visibleSettingsRows("cookies", "Nodes", rows).isEmpty())
    }

    @Test
    fun `while ant is still counting after init the row says so, not 0 B`() {
        val json = """{"disk_enabled":true,"used_bytes":0,"capacity_bytes":${2048 * mb},"chunks":0,""" +
            """"pinned_bytes":0,"pinned_chunks":0,"file_bytes":${2000 * mb},"counting":true}"""
        val s = SwarmCacheStatus.parse(json)!!
        assertTrue(s.counting)
        assertEquals("Counting… (2.0 GB max)", swarmCacheSummary(s))
        val view = SwarmCacheView(running = true, status = s, size = SwarmCacheSize.DEFAULT, clear = SwarmCache.Clear.Idle)
        assertEquals("Counting… (2.0 GB max)", swarmCacheLine(view))
        // No mark: the figures as they are, a real empty cache included.
        assertFalse(SwarmCacheStatus.parse(statusJson)!!.counting)
        assertEquals("0 B of 1.0 GB", swarmCacheSummary(status(used = 0, capacity = 1024 * mb)))
    }

    @Test
    fun `Delete browsing data's clear reports a failure, and only a failure`() {
        val failures = mutableListOf<String>()
        kotlinx.coroutines.runBlocking {
            SwarmCache.clearInBackground({ Result.failure(IllegalStateException("node gone")) }) { failures += it }.join()
            SwarmCache.clearInBackground({ throw RuntimeException("binder died") }) { failures += it }.join()
            SwarmCache.clearInBackground({ Result.success(SwarmCacheCleared(1, 2, 1, null)) }) { failures += it }.join()
        }
        assertEquals(listOf("node gone", "binder died"), failures)
        assertEquals("Couldn't clear the Swarm node cache: node gone", swarmCacheClearFailedLine("node gone"))
    }
}
