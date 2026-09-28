package baby.freedom.mobile.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger

class AdblockUpdatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val publisher = TestManifestSigner(BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16))
    private val attacker = TestManifestSigner(BigInteger.valueOf(0x1234567))

    /** The "Swarm" the updater talks to: ref → blob, and the feed's payload. */
    private val blobs = HashMap<String, ByteArray>()
    private var feed: ByteArray? = null
    private val downloaded = ArrayList<String>()
    private var activations = 0

    private val store by lazy { AdblockListStore(File(tmp.root, "adblock")) }

    private fun ref(text: String) = sha256Hex(text.toByteArray()) // any 64-hex name will do

    /** A list entry for [text], published into [blobs]. */
    private fun list(category: String, listId: String, text: String): Map<String, Any?> {
        val bytes = text.toByteArray()
        blobs[ref(text)] = bytes
        return mapOf(
            "category" to category, "list_id" to listId, "title" to listId,
            "source_url" to "https://example.org/$listId.txt", "license" to "CC BY-SA 3.0",
            "ref" to ref(text), "sha256" to sha256Hex(bytes), "bytes" to bytes.size.toLong(), "rule_count" to 1L,
        )
    }

    private fun publish(version: Long, vararg lists: Map<String, Any?>, by: TestManifestSigner = publisher) {
        val manifest = mapOf(
            "schema" to 1L,
            "version" to version,
            "generated_at" to "2026-09-0${version % 9 + 1}T00:00:00.000Z",
            "engines" to mapOf("adblock_rs" to "0.12.3"),
            "platforms" to mapOf("desktop" to mapOf("lists" to lists.toList()), "ios" to mapOf("lists" to emptyList<Any>())),
        )
        feed = CanonicalJson.stringify(by.sign(manifest)).toByteArray()
    }

    private fun update(vararg enabled: String) = runBlocking {
        runAdblockUpdate(
            store = store,
            enabled = enabled.toSet(),
            signer = publisher.address,
            readFeed = { feed },
            download = { ref, max ->
                downloaded += ref
                blobs[ref]?.takeIf { it.size <= max }
            },
            activate = { activations++ },
        )
    }

    private fun text(category: String) = store.updatedList(category)?.first

    @Test
    fun `a signed update is downloaded, checked and applied, and the engine is rebuilt`() {
        publish(5, list("ads", "easylist", "||ads.example^"), list("privacy", "easyprivacy", "||track.example^"), list("cookies", "cookies", "##.cookie"))
        assertEquals(AdblockUpdateOutcome.Applied(5), update("ads", "privacy"))
        assertEquals(1, activations)
        assertEquals("||ads.example^", text("ads"))
        assertEquals("||track.example^", text("privacy"))
        // Only the categories that are on are fetched.
        assertNull(text("cookies"))
        assertEquals(listOf(ref("||ads.example^"), ref("||track.example^")), downloaded)
        assertEquals(5L, store.updatedList("ads")!!.second.version)
    }

    @Test
    fun `a list that doesn't match its signed hash is rejected and nothing changes`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        update("ads")
        publish(6, list("ads", "easylist", "||ads2.example^"), list("privacy", "easyprivacy", "||track.example^"))
        // The node (or an external endpoint) hands back other bytes of
        // the same length for the ref.
        blobs[ref("||track.example^")] = "@@||example.org^".toByteArray()
        assertEquals("||track.example^".length, "@@||example.org^".length)
        assertEquals(AdblockUpdateOutcome.HashMismatch("privacy"), update("ads", "privacy"))
        assertEquals(1, activations)
        assertEquals("||ads.example^", text("ads"))
        assertEquals(5L, store.applied()!!.version)
        assertEquals(listOf("updated"), File(tmp.root, "adblock").list()!!.sorted())
    }

    @Test
    fun `a manifest signed by anyone else is rejected before anything is downloaded`() {
        publish(5, list("ads", "easylist", "||ads.example^"), by = attacker)
        assertEquals(AdblockUpdateOutcome.Rejected("wrong_signer"), update("ads"))
        assertEquals(emptyList<String>(), downloaded)
        assertNull(store.applied())
        assertEquals(0, activations)
    }

    @Test
    fun `a tampered manifest is rejected`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        val evil = list("ads", "easylist", "@@*\$document")
        feed = feed!!.toString(Charsets.UTF_8)
            .replace(sha256Hex("||ads.example^".toByteArray()), evil["sha256"] as String)
            .replace(ref("||ads.example^"), evil["ref"] as String)
            .toByteArray()
        assertEquals(AdblockUpdateOutcome.Rejected("wrong_signer"), update("ads"))
        assertEquals(emptyList<String>(), downloaded)
        assertNull(store.applied())
    }

    @Test
    fun `the same or an older version is not applied again`() {
        publish(6, list("ads", "easylist", "||six.example^"))
        update("ads")
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.UpToDate(6), update("ads"))
        // A replayed older manifest, validly signed, is a downgrade.
        publish(5, list("ads", "easylist", "||five.example^"))
        assertEquals(AdblockUpdateOutcome.UpToDate(6), update("ads"))
        assertEquals(emptyList<String>(), downloaded)
        assertEquals("||six.example^", text("ads"))
        assertEquals(1, activations)
    }

    @Test
    fun `an unchanged list is reused, not downloaded again`() {
        val ads = list("ads", "easylist", "||ads.example^")
        publish(5, ads, list("privacy", "easyprivacy", "||t1.example^"))
        update("ads", "privacy")
        downloaded.clear()
        publish(6, ads, list("privacy", "easyprivacy", "||t2.example^"))
        assertEquals(AdblockUpdateOutcome.Applied(6), update("ads", "privacy"))
        assertEquals(listOf(ref("||t2.example^")), downloaded)
        assertEquals("||ads.example^", text("ads"))
        assertEquals("||t2.example^", text("privacy"))
    }

    @Test
    fun `a category switched on later is fetched from the version already applied`() {
        publish(5, list("ads", "easylist", "||ads.example^"), list("cookies", "cookies", "##.cookie"))
        update("ads")
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.Applied(5), update("ads", "cookies"))
        assertEquals(listOf(ref("##.cookie")), downloaded)
        assertEquals("##.cookie", text("cookies"))
        assertEquals("||ads.example^", text("ads"))
        // …and once it's there, the same version is up to date again.
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads", "cookies"))
    }

    @Test
    fun `a category switched off keeps its copy while the new version lists it unchanged`() {
        val cookies = list("cookies", "cookies", "##.cookie")
        publish(5, list("ads", "easylist", "||a5.example^"), cookies)
        update("ads", "cookies")
        publish(6, list("ads", "easylist", "||a6.example^"), cookies)
        update("ads")
        assertEquals("##.cookie", text("cookies"))
        publish(7, list("ads", "easylist", "||a7.example^"), list("cookies", "cookies", "##.cookie2"))
        update("ads")
        assertNull(text("cookies"))
    }

    @Test
    fun `an unreadable feed or a failed download changes nothing`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        update("ads")
        feed = null
        assertEquals(AdblockUpdateOutcome.FeedUnavailable, update("ads"))
        publish(6, list("ads", "easylist", "||new.example^"))
        blobs.remove(ref("||new.example^"))
        assertEquals(AdblockUpdateOutcome.DownloadFailed("ads"), update("ads"))
        assertEquals("||ads.example^", text("ads"))
        assertEquals(1, activations)
    }

    @Test
    fun `a blob bigger than the manifest says is refused`() {
        val ads = list("ads", "easylist", "||ads.example^")
        blobs[ads["ref"] as String] = "||ads.example^ and then some".toByteArray()
        publish(5, ads)
        assertEquals(AdblockUpdateOutcome.DownloadFailed("ads"), update("ads"))
    }

    @Test
    fun `a damaged list on disk falls back to the bundled one`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        update("ads")
        File(tmp.root, "adblock/updated/easylist.txt").writeText("@@*\$document")
        assertNull(store.updatedList("ads"))
        // And the next check fetches it again rather than reusing it.
        publish(6, list("ads", "easylist", "||ads.example^"))
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.Applied(6), update("ads"))
        assertEquals(listOf(ref("||ads.example^")), downloaded)
        assertEquals("||ads.example^", text("ads"))
    }

    @Test
    fun `nothing is checked while every category is off`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        assertEquals(AdblockUpdateOutcome.NothingEnabled, update())
        assertNull(store.applied())
    }

    @Test
    fun `a swap the process died in is finished from the previous update`() {
        publish(5, list("ads", "easylist", "||ads.example^"))
        update("ads")
        // Killed between moving updated/ aside and promoting updated.next/.
        val root = File(tmp.root, "adblock")
        assertTrue(File(root, "updated").renameTo(File(root, "updated.prev")))
        assertEquals(5L, store.applied()!!.version)
        assertEquals("||ads.example^", text("ads"))
        assertEquals(listOf("updated"), root.list()!!.sorted())
        // And the version floor holds: 5 isn't applied again.
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads"))
        assertEquals(emptyList<String>(), downloaded)
    }

    @Test
    fun `a list's date is read from its header, in each form the lists use`() {
        val minutes = { s: String -> java.time.Instant.parse(s).epochSecond / 60 }
        assertEquals(
            minutes("2026-09-27T22:48:00Z"),
            filterListTimestamp("[Adblock Plus 2.0]\n! Version: 202609272248\n! Title: EasyList\n! Last modified: 27 Sep 2026 22:48 UTC\n||a^"),
        )
        assertEquals(
            minutes("2026-09-26T03:50:00Z"),
            filterListTimestamp("! Checksum: x\n! Title: Easylist Cookie List\n! Last modified: 2026-09-26 03:50 UTC\n##.c"),
        )
        // Only a version stamp.
        assertEquals(minutes("2026-09-18T20:41:00Z"), filterListTimestamp("[Adblock Plus 2.0]\n! Version: 202609182041\n||a^"))
        assertNull(filterListTimestamp("||ads.example^"))
        assertNull(filterListTimestamp("! Last modified: yesterday\n||a^"))
        // A date further down, among the rules, isn't the header's.
        assertNull(filterListTimestamp("||a^\n! Last modified: 27 Sep 2026 22:48 UTC"))
    }

    @Test
    fun `every bundled list carries a date the floor check can read`() {
        for (category in AdblockCategory.entries) {
            val text = File("src/main/assets/adblock/${category.file}").readText()
            assertTrue(category.file, filterListTimestamp(text) != null)
        }
    }

    @Test
    fun `an update older than the bundled list doesn't replace it`() {
        val bundled = "[Adblock Plus 2.0]\n! Last modified: 27 Sep 2026 22:48 UTC\n||new.example^"
        val older = "[Adblock Plus 2.0]\n! Last modified: 18 Sep 2026 20:00 UTC\n||old.example^"
        val newer = "[Adblock Plus 2.0]\n! Last modified: 2026-09-28 01:00 UTC\n||newer.example^"
        assertFalse(updatedListIsNewer(older, bundled))
        assertTrue(updatedListIsNewer(newer, bundled))
        assertTrue(updatedListIsNewer(bundled, bundled))
        // The signed update is taken when either side has no date.
        assertTrue(updatedListIsNewer("||x^", bundled))
        assertTrue(updatedListIsNewer(older, "||x^"))
    }

    @Test
    fun `Settings names the lists in use and how the last check ended`() {
        assertEquals("Using the built-in lists", adblockListsLine(AdblockStatus(loading = false, filterCount = 1)))
        assertEquals(
            "Using update 142 of 2026-09-18",
            adblockListsLine(AdblockStatus(false, 1, listsVersion = 142, listsGeneratedAt = "2026-09-18T20:41:06.821Z")),
        )
        assertNull(adblockUpdateLine(AdblockUpdateState()))
        assertEquals("Checking…", adblockUpdateLine(AdblockUpdateState(checking = true)))
        assertEquals(
            "Refused an update that failed verification (wrong_signer); the current lists stay",
            adblockUpdateLine(AdblockUpdateState(last = AdblockUpdateOutcome.Rejected("wrong_signer"))),
        )
        assertEquals(
            "The ads list didn't match its signed hash; the current lists stay",
            adblockUpdateLine(AdblockUpdateState(last = AdblockUpdateOutcome.HashMismatch("ads"))),
        )
    }
}
