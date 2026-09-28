package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MyotisGenerationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val net = MyotisNetwork.Gnosis
    private val slot = 1_895_077L * 16
    private val root = "0x3f437998c4c0f8a4aa2d7175ab08fd49faf4e6814c76e5b36cfe9a7756122c3d"
    private val now = MyotisCheckpointNetwork.Gnosis.slotTimeMs(slot) + 60_000L
    private val record = MyotisCheckpointRecord(
        100L, "gnosis", root, slot, now,
        listOf("https://checkpoint.gnosischain.com", "https://checkpoint-sync-gnosis.dappnode.net"), 1_895_077L,
    )

    private fun store() = MyotisGenerationStore(tmp.root)
    private val chainDir get() = File(tmp.root, "gnosis")

    private fun expectStorage(block: () -> Unit) {
        try {
            block()
            fail("expected a storage error")
        } catch (e: MyotisCheckpointException) {
            assertEquals(MyotisCheckpointError.Storage, e.error)
        }
    }

    @Test
    fun `without a pointer the chain directory itself is the bundled generation`() {
        val g = store().load(net)
        assertNull(g.id)
        assertEquals(chainDir, g.directory)
        assertEquals(MyotisGeneration.Origin.Bundled, g.origin)
        assertNull(g.checkpoint)
    }

    @Test
    fun `replace mints a verified generation that loads back with its checkpoint`() {
        val store = store()
        val minted = store.replace(net, record, now)
        assertEquals(MyotisGeneration.Origin.Verified, minted.origin)
        assertEquals(File(chainDir, "verified-sync/${minted.id}"), minted.directory)
        val loaded = store.load(net)
        assertEquals(minted, loaded)
        assertEquals(record, loaded.checkpoint)
        // Stale by now, but a reload isn't judged on age.
        assertEquals(minted, MyotisGenerationStore(tmp.root).load(net))
    }

    @Test
    fun `replace refuses a record that isn't fresh`() {
        try {
            store().replace(net, record, now + 2 * 3_600_000L)
            fail()
        } catch (e: MyotisCheckpointException) {
            assertEquals(MyotisCheckpointError.Stale, e.error)
        }
        assertFalse(File(chainDir, "verified-sync.json").exists())
    }

    @Test
    fun `peer caches carry over into a new generation, sync state doesn't`() {
        chainDir.mkdirs()
        File(chainDir, "peers-gnosis.cache").writeText("el peers")
        File(chainDir, "cl-peers-gnosis.cache").writeText("cl peers")
        File(chainDir, "sync-state-gnosis.snapshot").writeText("old anchor state")
        val g = store().replace(net, record, now)
        assertEquals("el peers", File(g.directory, "peers-gnosis.cache").readText())
        assertEquals("cl peers", File(g.directory, "cl-peers-gnosis.cache").readText())
        assertFalse(File(g.directory, "sync-state-gnosis.snapshot").exists())
        // And from one generation to the next.
        File(g.directory, "peers-gnosis.cache").writeText("newer el peers")
        val next = store().replace(net, record.copy(verifiedAt = now + 1), now + 1)
        assertEquals("newer el peers", File(next.directory, "peers-gnosis.cache").readText())
    }

    @Test
    fun `an unreadable or foreign pointer is a storage error, never a guess`() {
        val store = store()
        store.replace(net, record, now)
        val pointer = File(chainDir, "verified-sync.json")
        pointer.writeText("{nope")
        expectStorage { store.load(net) }
        pointer.writeText("""{"schemaVersion":1,"chainId":1,"generation":"00000000-0000-0000-0000-000000000000"}""")
        expectStorage { store.load(net) }
        pointer.writeText("""{"schemaVersion":1,"chainId":100,"generation":"../../etc"}""")
        expectStorage { store.load(net) }
        pointer.writeText("""{"schemaVersion":1,"chainId":100,"generation":"00000000-0000-0000-0000-000000000000"}""")
        expectStorage { store.load(net) }
    }

    @Test
    fun `a tampered generation record is a storage error`() {
        val store = store()
        val g = store.replace(net, record, now)
        val anchor = File(g.directory, "anchor.json")
        val text = anchor.readText()
        anchor.writeText(text.replace("\"verified\"", "\"bundled\""))
        expectStorage { store.load(net) }
        anchor.writeText(text.replace(root, "0x" + "ab".repeat(32)).replace("checkpoint.gnosischain.com", "evil.example"))
        expectStorage { store.load(net) }
    }

    @Test
    fun `the engine's marker must agree with the generation's record`() {
        val store = store()
        val g = store.replace(net, record, now)
        store.checkNativeMarker(g, net) // absent: the engine writes it on first create
        val marker = File(g.directory, "sync-anchor-gnosis.json")
        marker.writeText("""{"checkpointRoot":"$root","checkpointSlot":$slot,"note":"x"}""")
        store.checkNativeMarker(g, net)
        marker.writeText("""{"checkpointRoot":"$root","checkpointSlot":${slot + 1}}""")
        expectStorage { store.checkNativeMarker(g, net) }
        marker.writeText("garbage")
        expectStorage { store.checkNativeMarker(g, net) }
        // A dangling symlink is an entry, not absence (as the engine judges it).
        marker.delete()
        Files.createSymbolicLink(marker.toPath(), File(tmp.root, "missing").toPath())
        expectStorage { store.checkNativeMarker(g, net) }
        // Any marker on a bundled generation is foreign.
        chainDir.mkdirs()
        File(chainDir, "sync-anchor-gnosis.json").writeText("""{"checkpointRoot":"$root","checkpointSlot":$slot}""")
        expectStorage { store.checkNativeMarker(MyotisGeneration(null, chainDir, MyotisGeneration.Origin.Bundled, null), net) }
    }

    @Test
    fun `mainnet's marker has no network suffix`() {
        assertEquals("sync-anchor.json", MyotisGenerationStore.nativeMarkerName(MyotisNetwork.Mainnet))
        assertEquals(listOf("peers.cache", "cl-peers.cache"), MyotisGenerationStore.peerCacheNames(MyotisNetwork.Mainnet))
    }

    @Test
    fun `repair backs up the pointer and starts a fresh bundled generation`() {
        val store = store()
        val old = store.replace(net, record, now)
        val oldPointer = File(chainDir, "verified-sync.json").readText()
        val fresh = store.repair(net)
        assertEquals(MyotisGeneration.Origin.Bundled, fresh.origin)
        assertTrue(fresh.id != null && fresh.id != old.id)
        assertEquals(fresh, store.load(net))
        val backups = chainDir.listFiles()!!.filter { it.name.startsWith("verified-sync-backup-") }
        assertEquals(listOf(oldPointer), backups.map { it.readText() })
        assertTrue(old.directory.isDirectory) // kept
    }

    @Test
    fun `only the last few retired generations are kept`() {
        val store = store()
        val minted = (0 until 5).map { i ->
            store.replace(net, record.copy(verifiedAt = now + i), now + i).also {
                // Distinct, increasing ages for the prune order.
                File(it.directory, "anchor.json").setLastModified(1_000_000L + i * 1_000L)
            }
        }
        val left = File(chainDir, "verified-sync").listFiles()!!.map { it.name }.toSet()
        // The current one plus KEEP_RETIRED before it.
        assertEquals(minted.takeLast(1 + MyotisGenerationStore.KEEP_RETIRED).map { it.id }.toSet(), left)
        assertEquals(minted.last(), store.load(net))
    }
}
