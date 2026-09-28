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

    /** The bundled lists' header dates, by category (epoch minutes); none by default. */
    private val bundledTimes = HashMap<String, Long>()

    private fun minutes(iso: String) = java.time.Instant.parse(iso).epochSecond / 60

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
            bundledTime = { bundledTimes[it] },
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
    fun `a damaged list on disk is fetched again from the version already applied`() {
        publish(5, list("ads", "easylist", "||ads.example^"), list("privacy", "easyprivacy", "||t.example^"))
        update("ads", "privacy")
        File(tmp.root, "adblock/updated/easylist.txt").writeText("@@*\$document")
        assertEquals(setOf("privacy"), store.intactCategories())
        // The feed is still on version 5: the damaged copy is fetched
        // again, the intact one reused, rather than "up to date".
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.Applied(5), update("ads", "privacy"))
        assertEquals(listOf(ref("||ads.example^")), downloaded)
        assertEquals("||ads.example^", text("ads"))
        assertEquals("||t.example^", text("privacy"))
        assertEquals(setOf("ads", "privacy"), store.intactCategories())
        // …and once it's whole again, version 5 is up to date.
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads", "privacy"))
        assertEquals(emptyList<String>(), downloaded)
        // An older version still isn't accepted to repair one.
        File(tmp.root, "adblock/updated/easylist.txt").writeText("@@*\$document")
        publish(4, list("ads", "easylist", "||old.example^"))
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads", "privacy"))
        assertNull(text("ads"))
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
    fun `a manifest generated before the bundled lists is neither downloaded nor reported as an update`() {
        // Version 5 is generated 2026-09-06; this APK's lists are from the 27th.
        bundledTimes["ads"] = minutes("2026-09-27T22:48:00Z")
        bundledTimes["privacy"] = minutes("2026-09-27T22:48:00Z")
        publish(5, list("ads", "easylist", "||ads.example^"), list("privacy", "easyprivacy", "||track.example^"))
        assertEquals(AdblockUpdateOutcome.BuiltInNewer(5), update("ads", "privacy"))
        assertEquals(emptyList<String>(), downloaded)
        assertNull(store.applied())
        assertEquals(0, activations)
    }

    @Test
    fun `only the lists newer than the bundled ones are fetched, and the rest are named`() {
        bundledTimes["ads"] = minutes("2026-09-27T22:48:00Z")
        bundledTimes["privacy"] = minutes("2026-09-01T00:00:00Z")
        publish(5, list("ads", "easylist", "||ads.example^"), list("privacy", "easyprivacy", "||track.example^"))
        assertEquals(AdblockUpdateOutcome.Applied(5, listOf("ads")), update("ads", "privacy"))
        assertEquals(listOf(ref("||track.example^")), downloaded)
        assertNull(text("ads"))
        assertEquals("||track.example^", text("privacy"))
        // The next check doesn't take ads' absence for a category to backfill.
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads", "privacy"))
        assertEquals(emptyList<String>(), downloaded)
        assertEquals(1, activations)
    }

    @Test
    fun `a downloaded list whose own header is older than the bundled one is kept but not called an update`() {
        bundledTimes["ads"] = minutes("2026-09-05T12:00:00Z")
        bundledTimes["privacy"] = minutes("2026-09-05T12:00:00Z")
        val oldAds = "[Adblock Plus 2.0]\n! Last modified: 2026-09-04 00:00 UTC\n||ads.example^"
        val oldPrivacy = "[Adblock Plus 2.0]\n! Version: 202609040000\n||track.example^"
        val newPrivacy = "[Adblock Plus 2.0]\n! Version: 202609060000\n||track2.example^"
        // Generated on the 6th, after the bundled date, but carrying lists from the 4th.
        publish(5, list("ads", "easylist", oldAds), list("privacy", "easyprivacy", oldPrivacy))
        assertEquals(AdblockUpdateOutcome.BuiltInNewer(5), update("ads", "privacy"))
        // Kept, so the version floor moves and the same lists aren't fetched again.
        assertEquals(5L, store.applied()!!.version)
        downloaded.clear()
        assertEquals(AdblockUpdateOutcome.UpToDate(5), update("ads", "privacy"))
        assertEquals(emptyList<String>(), downloaded)
        // A later version with one list newer: an update, naming the other.
        publish(6, list("ads", "easylist", oldAds), list("privacy", "easyprivacy", newPrivacy))
        assertEquals(AdblockUpdateOutcome.Applied(6, listOf("ads")), update("ads", "privacy"))
        assertEquals(listOf(ref(newPrivacy)), downloaded)
    }

    @Test
    fun `Settings says which lists an update serves and which the bundled ones do`() {
        val applied = AdblockStatus(false, 1, listsVersion = 2, listsGeneratedAt = "2026-09-28T01:00:00.000Z")
        assertEquals(
            "Using update 2 of 2026-09-28",
            adblockListsLine(applied.copy(updatedLists = listOf("EasyList", "EasyPrivacy"))),
        )
        assertEquals(
            "Using update 2 of 2026-09-28 for EasyPrivacy; the built-in EasyList",
            adblockListsLine(applied.copy(updatedLists = listOf("EasyPrivacy"), builtInLists = listOf("EasyList"))),
        )
        assertEquals(
            "Using the built-in lists (newer than update 2)",
            adblockListsLine(
                applied.copy(builtInLists = listOf("EasyList", "EasyPrivacy"), newerBuiltInLists = listOf("EasyList", "EasyPrivacy")),
            ),
        )
        // An update that doesn't carry the enabled lists (a category
        // switched on since it applied, or a copy that failed its hash)
        // isn't called older than them.
        assertEquals(
            "Using the built-in lists (update 2 doesn't include them)",
            adblockListsLine(applied.copy(builtInLists = listOf("Fanboy's Cookie List"))),
        )
        assertEquals(
            "Using the built-in lists (EasyList newer than update 2's; update 2 doesn't include Fanboy's Cookie List)",
            adblockListsLine(
                applied.copy(builtInLists = listOf("EasyList", "Fanboy's Cookie List"), newerBuiltInLists = listOf("EasyList")),
            ),
        )
        // A copy that failed its hash check is named as such, not as
        // one the update doesn't carry.
        assertEquals(
            "Using the built-in lists (update 2's copies failed their hash check)",
            adblockListsLine(applied.copy(builtInLists = listOf("EasyList"), damagedLists = listOf("EasyList"))),
        )
        assertEquals(
            "Using the built-in lists (EasyList newer than update 2's; update 2's EasyPrivacy failed its hash check; " +
                "update 2 doesn't include Fanboy's Cookie List)",
            adblockListsLine(
                applied.copy(
                    builtInLists = listOf("EasyList", "EasyPrivacy", "Fanboy's Cookie List"),
                    newerBuiltInLists = listOf("EasyList"),
                    damagedLists = listOf("EasyPrivacy"),
                ),
            ),
        )
        assertEquals(
            "Using update 2 of 2026-09-28 for EasyPrivacy; the built-in EasyList (update 2's EasyList failed its hash check)",
            adblockListsLine(
                applied.copy(updatedLists = listOf("EasyPrivacy"), builtInLists = listOf("EasyList"), damagedLists = listOf("EasyList")),
            ),
        )
        assertEquals("Updated to version 2", adblockUpdateLine(AdblockUpdateState(last = AdblockUpdateOutcome.Applied(2))))
        assertEquals(
            "Updated to version 2; the built-in EasyList stays, it's newer",
            adblockUpdateLine(AdblockUpdateState(last = AdblockUpdateOutcome.Applied(2, listOf("ads")))),
        )
        assertEquals(
            "Version 142 on the feed is older than the built-in lists; they stay in use",
            adblockUpdateLine(AdblockUpdateState(last = AdblockUpdateOutcome.BuiltInNewer(142))),
        )
    }

    @Test
    fun `Settings names the lists in use and how the last check ended`() {
        assertEquals("Using the built-in lists", adblockListsLine(AdblockStatus(loading = false, filterCount = 1)))
        assertEquals(
            "Using update 142 of 2026-09-18",
            adblockListsLine(
                AdblockStatus(
                    false, 1, listsVersion = 142, listsGeneratedAt = "2026-09-18T20:41:06.821Z",
                    updatedLists = listOf("EasyList"),
                ),
            ),
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
