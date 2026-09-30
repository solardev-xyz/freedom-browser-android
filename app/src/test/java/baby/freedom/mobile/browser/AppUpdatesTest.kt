package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppUpdatesTest {
    private fun v(s: String) = checkNotNull(ReleaseVersion.parse(s)) { s }

    private fun release(tag: String) = LatestRelease(tag, v(tag))

    /** Trimmed from GitHub's real `releases/latest` answer for v0.6.10. */
    private fun releaseJson(
        tag: String = "v0.6.10",
        draft: Boolean = false,
        prerelease: Boolean = false,
        htmlUrl: String = "https://github.com/solardev-xyz/freedom-browser-android/releases/tag/$tag",
    ) = """
        {
          "url": "https://api.github.com/repos/solardev-xyz/freedom-browser-android/releases/250000000",
          "html_url": "$htmlUrl",
          "id": 250000000,
          "author": {"login": "github-actions[bot]", "id": 41898282},
          "tag_name": "$tag",
          "target_commitish": "main",
          "name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2026-09-29T23:05:12Z",
          "published_at": "2026-09-29T23:16:47Z",
          "assets": [
            {"name": "freedom-browser-$tag-arm64-v8a.apk", "size": 61234567},
            {"name": "SHA256SUMS", "size": 312}
          ],
          "body": "## What's Changed\n* Shrink the APK (#230)"
        }
    """.trimIndent()

    @Test
    fun `versions parse from tags and versionNames`() {
        assertEquals(listOf(0, 6, 10), v("v0.6.10").parts)
        assertEquals(listOf(0, 6, 10), v("0.6.10").parts)
        assertEquals(listOf(1), v("V1").parts)
        assertEquals(listOf(0, 7), v(" 0.7 ").parts)
    }

    @Test
    fun `anything but a plain numeric version does not parse`() {
        for (s in listOf(null, "", "v", "latest", "v0.7.0-rc1", "0.6.10-debug", "0..1", "1.", ".1", "v1.2.3.4.5", "1.2a", "v 1.2", "99999999999")) {
            assertNull(s, ReleaseVersion.parse(s))
        }
    }

    @Test
    fun `versions compare numerically, part by part`() {
        assertTrue(v("0.6.10") > v("0.6.9"))
        assertTrue(v("0.7.0") > v("0.6.10"))
        assertTrue(v("1.0") > v("0.99.99"))
        assertTrue(v("0.6.10.1") > v("0.6.10"))
        assertEquals(0, v("0.7").compareTo(v("0.7.0")))
        assertEquals(0, v("v0.6.10").compareTo(v("0.6.10")))
        assertTrue(v("0.6.9") < v("0.6.10"))
    }

    @Test
    fun `the release JSON names its tag and version`() {
        val latest = checkNotNull(parseLatestRelease(releaseJson()))
        assertEquals("v0.6.10", latest.tag)
        assertEquals(v("0.6.10"), latest.version)
        assertEquals("https://github.com/solardev-xyz/freedom-browser-android/releases/tag/v0.6.10", latest.url)
    }

    @Test
    fun `the release page is built from the tag, never taken from the answer`() {
        val latest = checkNotNull(parseLatestRelease(releaseJson(htmlUrl = "https://evil.example/freedom.apk")))
        assertEquals("https://github.com/solardev-xyz/freedom-browser-android/releases/tag/v0.6.10", latest.url)
    }

    @Test
    fun `drafts, pre-releases and unusable answers are not releases`() {
        assertNull(parseLatestRelease(releaseJson(draft = true)))
        assertNull(parseLatestRelease(releaseJson(prerelease = true)))
        assertNull(parseLatestRelease(releaseJson(tag = "v0.7.0-rc1")))
        assertNull(parseLatestRelease(releaseJson(tag = "nightly")))
        assertNull(parseLatestRelease("""{"message": "Not Found", "documentation_url": "https://docs.github.com"}"""))
        assertNull(parseLatestRelease("""{"tag_name": 7}"""))
        assertNull(parseLatestRelease("[]"))
        assertNull(parseLatestRelease(""))
        assertNull(parseLatestRelease("<html>rate limited</html>"))
        assertNull(parseLatestRelease("[".repeat(100_000)))
        assertNull(parseLatestRelease("{\"a\":".repeat(100_000)))
    }

    @Test
    fun `a newer release is available, the same or an older one is up to date`() {
        assertEquals(
            UpdateCheckOutcome.Available(release("v0.6.11")),
            compareRelease(v("0.6.10"), release("v0.6.11")),
        )
        assertEquals(
            UpdateCheckOutcome.UpToDate(release("v0.6.10")),
            compareRelease(v("0.6.10"), release("v0.6.10")),
        )
        assertEquals(
            UpdateCheckOutcome.UpToDate(release("v0.6.9")),
            compareRelease(v("0.6.10"), release("v0.6.9")),
        )
        assertTrue(compareRelease(null, release("v0.6.11")) is UpdateCheckOutcome.Failed)
    }

    @Test
    fun `a check is due once a day, and after a stamp from the future`() {
        val day = AppUpdates.CHECK_PERIOD_MS
        val now = 1_800_000_000_000L
        assertTrue(updateCheckDue(null, now))
        assertFalse(updateCheckDue(now, now))
        assertFalse(updateCheckDue(now - day + 1, now))
        assertTrue(updateCheckDue(now - day, now))
        assertTrue(updateCheckDue(now - 10 * day, now))
        // Written while the clock was a week ahead, since corrected.
        assertTrue(updateCheckDue(now + 7 * day, now))
        assertTrue(updateCheckDue(now + 1, now))
    }

    @Test
    fun `only app stores count as a store install`() {
        assertEquals("Google Play", storeFor("com.android.vending"))
        assertEquals("F-Droid", storeFor("org.fdroid.fdroid"))
        assertEquals("F-Droid", storeFor("org.fdroid.basic"))
        assertEquals("Aurora Store", storeFor("com.aurora.store"))
        assertNull(storeFor(null))
        assertNull(storeFor("com.google.android.packageinstaller"))
        assertNull(storeFor("com.android.packageinstaller"))
        assertNull(storeFor("com.android.chrome"))
        assertNull(storeFor("baby.freedom.mobile"))
    }

    @Test
    fun `the notice shows a newer release until closed, with checks on, never for a store install`() {
        val state = AppUpdateState(installedName = "0.6.10", latest = release("v0.6.11"), enabled = true)
        assertEquals(release("v0.6.11"), state.notice)
        assertNull(state.copy(dismissedTag = "v0.6.11").notice)
        // A later release than the closed one shows again.
        assertEquals(release("v0.6.12"), state.copy(dismissedTag = "v0.6.11", latest = release("v0.6.12")).notice)
        assertNull(state.copy(enabled = false).notice)
        assertNull(state.copy(store = "Google Play").notice)
        // Installed since: nothing to announce.
        assertNull(state.copy(installedName = "0.6.11").notice)
        assertNull(state.copy(installedName = "0.7.0").notice)
        // A local build not named as a release claims nothing.
        assertNull(state.copy(installedName = "0.6.10-dev").notice)
        assertNull(state.copy(latest = null).notice)
        // Before the setting is read, nothing shows.
        assertNull(AppUpdateState(installedName = "0.6.10", latest = release("v0.6.11")).notice)
    }

    @Test
    fun `the Check now line says what the last check found`() {
        val at: (Long) -> String = { "day $it" }
        val base = AppUpdateState(installedName = "0.6.10", enabled = true)
        assertEquals("Not checked yet", appUpdateLine(base, at))
        assertEquals("Checking…", appUpdateLine(base.copy(checking = true, latest = release("v0.6.11")), at))
        assertEquals(
            "Freedom 0.6.11 is out; this is 0.6.10 · last checked day 5",
            appUpdateLine(base.copy(latest = release("v0.6.11"), lastCheckedAt = 5), at),
        )
        assertEquals(
            "Up to date: 0.6.10 is the latest release · last checked day 5",
            appUpdateLine(base.copy(latest = release("v0.6.10"), lastCheckedAt = 5), at),
        )
        assertEquals(
            "The last check failed: couldn't reach GitHub · last checked day 5",
            appUpdateLine(
                base.copy(
                    latest = release("v0.6.10"),
                    lastCheckedAt = 5,
                    last = UpdateCheckOutcome.Failed("couldn't reach GitHub"),
                ),
                at,
            ),
        )
        // A stamp with nothing found yet (the process died mid-check).
        assertEquals("Last checked day 5", appUpdateLine(base.copy(lastCheckedAt = 5), at))
        assertEquals("Updates come from F-Droid", appUpdateLine(base.copy(store = "F-Droid"), at))
        assertEquals(
            "Installed from F-Droid, which keeps Freedom up to date: no check runs",
            appUpdateStoreLine(base.copy(store = "F-Droid")),
        )
        assertNull(appUpdateStoreLine(base))
    }

    @Test
    fun `of two overlapping saves, the one that writes last read the state last`() {
        val dir = Files.createTempDirectory("app-update").toFile()
        try {
            val f = java.io.File(dir, "state.json")
            val state = AtomicReference(AppUpdateState(lastCheckedAt = 1_000L, latest = release("v0.7.0")))
            val firstInside = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondSawFirstWrite = AtomicBoolean(false)
            // The check's save: snapshots the state, then stalls before writing it.
            val first = Thread {
                saveAppUpdateState(f) {
                    val s = state.get()
                    firstInside.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    s
                }
            }.apply { start() }
            assertTrue(firstInside.await(5, TimeUnit.SECONDS))
            // The user closes the notice meanwhile, and that save starts.
            state.set(state.get().copy(dismissedTag = "v0.7.0"))
            val second = Thread {
                saveAppUpdateState(f) {
                    secondSawFirstWrite.set(f.exists())
                    state.get()
                }
            }.apply { start() }
            Thread.sleep(200)
            releaseFirst.countDown()
            first.join(5_000)
            second.join(5_000)
            // The dismissal's snapshot was taken only once the older write had landed…
            assertTrue(secondSawFirstWrite.get())
            // …so the file holds it, not the check's older state.
            val json = JSONObject(f.readText())
            assertEquals("v0.7.0", json.getString("dismissed"))
            assertEquals("v0.7.0", json.getString("tag"))
            assertEquals(1_000L, json.getLong("checkedAt"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
