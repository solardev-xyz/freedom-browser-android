package baby.freedom.mobile.browser

import baby.freedom.mobile.l10n.Text
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val TAG = "AdblockUpdate"

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** One list an applied update carries, as `state.json` records it. */
internal data class AppliedList(
    val category: String,
    val file: String,
    val sha256: String,
    val bytes: Long,
    val ruleCount: Long,
    val title: String?,
)

/** The update applied on disk: the feed [version] and the lists it carries, by category. */
internal data class AppliedUpdate(
    val version: Long,
    val generatedAt: String,
    val lists: Map<String, AppliedList>,
)

/**
 * The filter lists updates bring (#127), under [root] (the app's
 * `files/adblock/`):
 *
 * - `updated/` — the update in use: `state.json` (its feed version and
 *   each list's file and sha256) and one `<list_id>.txt` per list;
 * - `updated.next/` — an update being staged; never read;
 * - `updated.prev/` — the update `updated/` replaced, for the moment of
 *   the swap. Found alone (the process died between the swap's two
 *   renames), it is put back as `updated/` before anything reads.
 *
 * A list is taken from `updated/` only while its bytes still hash to
 * what `state.json` records; otherwise — and for any category the
 * update doesn't carry — the bundled asset serves. The engine also
 * keeps the bundled list where it is newer than the update's
 * ([updatedListIsNewer]). So the bundled lists are a floor no bad,
 * stale or damaged update can go below.
 *
 * [lock] covers the swap and every read of `updated/`, so a read never
 * sees half of one update and half of the next.
 */
internal class AdblockListStore(val root: File) {
    val lock = Any()

    private val active get() = File(root, "updated")
    private val staging get() = File(root, "updated.next")
    private val previous get() = File(root, "updated.prev")

    /** The applied update's state, or `null` when there is none (or it is unreadable). */
    fun applied(): AppliedUpdate? = synchronized(lock) {
        recoverLocked()
        readState(File(active, STATE))
    }

    /**
     * The text of [category]'s list from the applied update, if it
     * carries that category and the file still matches its hash.
     * Returns the list with the update it came from.
     */
    fun updatedList(category: String): Pair<String, AppliedUpdate>? = synchronized(lock) {
        recoverLocked()
        val state = readState(File(active, STATE)) ?: return null
        val entry = state.lists[category] ?: return null
        val bytes = runCatching { File(active, entry.file).readBytes() }.getOrNull() ?: return null
        if (sha256Hex(bytes) != entry.sha256) {
            Log.w(TAG, "updated ${entry.file} no longer matches its hash; using the bundled list")
            return null
        }
        bytes.toString(Charsets.UTF_8) to state
    }

    /**
     * The categories of the applied update whose list is on disk and
     * still hashes to what `state.json` records — those
     * [updatedList] can serve. Empty when nothing is applied.
     */
    fun intactCategories(): Set<String> = synchronized(lock) {
        recoverLocked()
        val state = readState(File(active, STATE)) ?: return emptySet()
        state.lists.values.filterTo(LinkedHashSet()) { entry ->
            runCatching { File(active, entry.file).readBytes() }.getOrNull()
                ?.let { sha256Hex(it) == entry.sha256 } == true
        }.mapTo(LinkedHashSet()) { it.category }
    }

    /** The applied copy of [entry]'s list, if one is on disk with the same hash. */
    fun reusable(entry: AdblockManifestList): ByteArray? = synchronized(lock) {
        recoverLocked()
        val state = readState(File(active, STATE)) ?: return null
        val applied = state.lists[entry.category] ?: return null
        if (applied.sha256 != entry.sha256) return null
        runCatching { File(active, applied.file).readBytes() }.getOrNull()
            ?.takeIf { sha256Hex(it) == entry.sha256 }
    }

    /**
     * Write [lists] (each already hash-checked) as update [manifest]
     * into `updated.next/`, then swap it in for `updated/`. Throws on
     * any failure, leaving `updated/` as it was.
     */
    fun stageAndPromote(manifest: AdblockManifest, lists: List<Pair<AdblockManifestList, ByteArray>>) {
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("can't create $staging")
        val state = LinkedHashMap<String, AppliedList>()
        for ((entry, bytes) in lists) {
            val file = "${entry.listId}.txt"
            // Belt and braces with the manifest's list_id check: the file
            // must land directly in the staging directory.
            val dest = File(staging, file)
            if (dest.parentFile?.canonicalPath != staging.canonicalPath) throw IOException("unsafe list id")
            dest.writeBytes(bytes)
            state[entry.category] = AppliedList(
                entry.category, file, entry.sha256, entry.bytes, entry.ruleCount, entry.title,
            )
        }
        File(staging, STATE).writeText(writeState(AppliedUpdate(manifest.version, manifest.generatedAt, state)))
        synchronized(lock) {
            recoverLocked()
            previous.deleteRecursively()
            if (active.exists() && !active.renameTo(previous)) {
                staging.deleteRecursively()
                throw IOException("can't move the applied update aside")
            }
            if (!staging.renameTo(active)) {
                // Put the old update back, so there's never none.
                if (previous.exists()) previous.renameTo(active)
                staging.deleteRecursively()
                throw IOException("can't promote the staged update")
            }
            previous.deleteRecursively()
        }
    }

    /**
     * Finish a swap the process died in the middle of: `updated/` was
     * moved aside to `updated.prev/` but `updated.next/` never took its
     * place. Put the previous update back, so a crash never drops the
     * lists (and the version floor) to the bundled ones.
     */
    private fun recoverLocked() {
        if (!active.exists() && previous.exists() && !previous.renameTo(active)) {
            Log.w(TAG, "can't restore $previous")
        }
    }

    fun discardStaging() {
        staging.deleteRecursively()
    }

    private fun readState(file: File): AppliedUpdate? = runCatching {
        val root = CanonicalJson.parse(file.readBytes()) as Map<*, *>
        val lists = (root["lists"] as Map<*, *>).entries.associate { (k, v) ->
            val e = v as Map<*, *>
            val file = e["file"] as String
            // The state file is ours, but a name that isn't a plain file
            // token is never read from.
            require(file.matches(Regex("^[a-z0-9][a-z0-9_-]{0,63}\\.txt$")))
            (k as String) to AppliedList(
                k, file, e["sha256"] as String, e["bytes"] as Long, e["rule_count"] as Long, e["title"] as String?,
            )
        }
        AppliedUpdate(root["version"] as Long, root["generated_at"] as String, lists)
    }.getOrNull()

    private fun writeState(state: AppliedUpdate): String = CanonicalJson.stringify(
        mapOf(
            "version" to state.version,
            "generated_at" to state.generatedAt,
            "lists" to state.lists.mapValues { (_, l) ->
                mapOf(
                    "file" to l.file, "sha256" to l.sha256, "bytes" to l.bytes,
                    "rule_count" to l.ruleCount, "title" to l.title,
                )
            },
        ),
    )

    private companion object {
        const val STATE = "state.json"
    }
}

/**
 * When a filter list's text says it was last changed, from its header
 * (`! Last modified: 27 Sep 2026 22:48 UTC` or `2026-09-26 03:50 UTC`,
 * else `! Version: 202609272248`), in epoch minutes — or `null` if the
 * header has neither.
 */
internal fun filterListTimestamp(text: String): Long? {
    var version: Long? = null
    for (line in text.lineSequence().take(HEADER_LINES)) {
        if (!line.startsWith("!")) {
            if (line.isBlank() || line.startsWith("[")) continue
            break
        }
        val body = line.removePrefix("!").trim()
        when {
            body.startsWith("Last modified:", ignoreCase = true) ->
                parseLastModified(body.substringAfter(':').trim())?.let { return it }
            body.startsWith("Version:", ignoreCase = true) && version == null ->
                version = parseVersionStamp(body.substringAfter(':').trim())
        }
    }
    return version
}

/**
 * Whether an update's [updated] list should serve instead of the
 * [bundled] one: unless the bundled list is dated later. An APK built
 * after the feed's latest manifest ships fresher lists than that
 * manifest (or a lagging feed read can hand back an even older one);
 * the signed update is taken whenever either date is missing.
 */
internal fun updatedListIsNewer(updated: String, bundled: String): Boolean =
    !bundledListBeats(filterListTimestamp(updated), filterListTimestamp(bundled))

/**
 * Whether a bundled list dated [bundled] is newer than an update's
 * list dated [updated] (both epoch minutes). The one rule the engine
 * ([updatedListIsNewer]) and the updater ([runAdblockUpdate]) share: a
 * missing date on either side means the signed update wins.
 */
internal fun bundledListBeats(updated: Long?, bundled: Long?): Boolean =
    updated != null && bundled != null && updated < bundled

/** A manifest's `generated_at` (ISO 8601) in epoch minutes, or `null`. */
internal fun manifestTimestamp(generatedAt: String): Long? =
    runCatching { java.time.Instant.parse(generatedAt).epochSecond / 60 }.getOrNull()

/** The date in the header of the list [bytes], reading only as far as the header can reach. */
private fun listBytesTimestamp(bytes: ByteArray): Long? =
    filterListTimestamp(String(bytes, 0, minOf(bytes.size, HEADER_BYTES), Charsets.UTF_8))

private const val HEADER_BYTES = 16 * 1024

private const val HEADER_LINES = 40

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private fun parseLastModified(s: String): Long? {
    Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(s)?.let { m ->
        val (y, mo, d, h, mi) = m.destructured
        return epochMinutes(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
    }
    Regex("""^(\d{1,2}) ([A-Za-z]{3})[A-Za-z]* (\d{4}) (\d{2}):(\d{2})""").find(s)?.let { m ->
        val (d, mon, y, h, mi) = m.destructured
        val mo = MONTHS.indexOf(mon.lowercase()) + 1
        if (mo == 0) return null
        return epochMinutes(y.toInt(), mo, d.toInt(), h.toInt(), mi.toInt())
    }
    return null
}

private fun parseVersionStamp(s: String): Long? {
    val m = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})$""").find(s) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    return epochMinutes(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
}

private fun epochMinutes(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long? = runCatching {
    java.time.LocalDateTime.of(y, mo, d, h, mi).toEpochSecond(java.time.ZoneOffset.UTC) / 60
}.getOrNull()

/** How one update check ended. */
internal sealed interface AdblockUpdateOutcome {
    /**
     * A new update is in place; the engine is being rebuilt from it.
     * [olderThanBuiltIn] names the enabled categories (keys) whose
     * bundled list is newer than the update's, so the bundled one
     * keeps serving them.
     */
    data class Applied(val version: Long, val olderThanBuiltIn: List<String> = emptyList()) : AdblockUpdateOutcome

    /**
     * The feed's [version] is newer than what's applied, but every list
     * it has for the enabled categories is older than the bundled one,
     * so the bundled lists stay in use.
     */
    data class BuiltInNewer(val version: Long) : AdblockUpdateOutcome

    /** The feed has nothing newer than what's applied. */
    data class UpToDate(val version: Long) : AdblockUpdateOutcome

    /** The feed couldn't be read: node not running, no peers, a timeout. Nothing was checked. */
    data object FeedUnavailable : AdblockUpdateOutcome

    /** The manifest failed verification ([verifyAdblockManifest]'s reason). */
    data class Rejected(val reason: String) : AdblockUpdateOutcome

    /** A list didn't download. */
    data class DownloadFailed(val category: String) : AdblockUpdateOutcome

    /** A downloaded list didn't hash to what the signed manifest says. */
    data class HashMismatch(val category: String) : AdblockUpdateOutcome

    /** Every category is off, so there's nothing to update. */
    data object NothingEnabled : AdblockUpdateOutcome

    /**
     * Writing the update failed, or it couldn't run; [message] says why,
     * read when shown so it follows a change of the app language (#280).
     */
    data class Failed(private val why: Text) : AdblockUpdateOutcome {
        constructor(message: String) : this(Text.raw(message))

        val message: String get() = why.text
    }
}

/**
 * One update cycle (#127), as desktop's `update-manager.js`: read the
 * signed manifest from the pinned feed, verify it
 * ([verifyAdblockManifest]), fetch the lists of the [enabled]
 * categories whose hashes changed and check each against the signed
 * sha256, stage them, swap them in ([AdblockListStore]) and [activate].
 * A failure at any step leaves the lists in use untouched.
 *
 * A category switched on after the applied version landed isn't in
 * it, and one whose applied copy no longer matches its hash on disk
 * can't be served from it; the bundled list serves either meanwhile,
 * and the same version is accepted again (never an older one) to fetch
 * it. A category switched
 * off keeps its applied copy while the new manifest still names the
 * same bytes, so switching it back on doesn't drop to the bundled list.
 *
 * The bundled lists stay the floor here too, as in the engine
 * ([bundledListBeats]): a list whose manifest was generated before the
 * bundled list's date ([bundledTime]) can't be newer than it, so it
 * isn't downloaded at all; one whose own header turns out older once
 * downloaded is kept on disk (so the next check doesn't fetch it
 * again) and reported in [AdblockUpdateOutcome.Applied.olderThanBuiltIn].
 * When no list the enabled categories need beats its bundled one, the
 * outcome is [AdblockUpdateOutcome.BuiltInNewer], never "applied".
 *
 * [readFeed] returns the feed's payload or `null` when it can't be read;
 * [download] returns a blob of at most `maxBytes` or `null`;
 * [bundledTime] gives a category's bundled list date (epoch minutes),
 * or `null` when it has none.
 */
internal suspend fun runAdblockUpdate(
    store: AdblockListStore,
    enabled: Set<String>,
    signer: String,
    readFeed: suspend () -> ByteArray?,
    download: suspend (ref: String, maxBytes: Long) -> ByteArray?,
    bundledTime: (category: String) -> Long?,
    activate: () -> Unit,
): AdblockUpdateOutcome {
    if (enabled.isEmpty()) return AdblockUpdateOutcome.NothingEnabled
    val applied = store.applied()
    val appliedVersion = applied?.version ?: 0L
    // Only the copies that still hash right count as applied: a damaged
    // one is fetched again, even at the same feed version.
    val appliedCategories = if (applied == null) emptySet() else store.intactCategories()
    val needsBackfill = applied != null && enabled.any { it !in appliedCategories }

    val payload = readFeed() ?: return AdblockUpdateOutcome.FeedUnavailable
    val manifest = when (
        val verdict = verifyAdblockManifest(payload, signer, appliedVersion, allowRepublish = needsBackfill)
    ) {
        is AdblockManifestVerdict.Rejected -> {
            if (verdict.reason == "not_newer") return AdblockUpdateOutcome.UpToDate(appliedVersion)
            Log.w(TAG, "rejected manifest: ${verdict.reason}")
            return AdblockUpdateOutcome.Rejected(verdict.reason)
        }
        is AdblockManifestVerdict.Ok -> verdict.manifest
    }

    val wanted = manifest.lists.filter { it.category in enabled }
    if (wanted.isEmpty()) return AdblockUpdateOutcome.UpToDate(appliedVersion)
    // A list generated before its bundled one's date is older than it:
    // not worth downloading, the engine would never serve it.
    val generated = manifestTimestamp(manifest.generatedAt)
    val (stale, fetch) = wanted.partition { bundledListBeats(generated, bundledTime(it.category)) }
    // The republished version brings nothing this install is missing.
    if (manifest.version == appliedVersion && fetch.all { it.category in appliedCategories }) {
        return AdblockUpdateOutcome.UpToDate(appliedVersion)
    }
    if (fetch.isEmpty()) {
        Log.i(TAG, "update ${manifest.version} is older than the bundled lists; not downloading it")
        return AdblockUpdateOutcome.BuiltInNewer(manifest.version)
    }

    val lists = ArrayList<Pair<AdblockManifestList, ByteArray>>()
    val olderThanBuiltIn = stale.mapTo(ArrayList()) { it.category }
    for (entry in fetch) {
        val bytes = store.reusable(entry) ?: run {
            val blob = download(entry.ref, entry.bytes)
                ?: return AdblockUpdateOutcome.DownloadFailed(entry.category)
            if (sha256Hex(blob) != entry.sha256) {
                Log.w(TAG, "sha256 mismatch for ${entry.category}; keeping the current lists")
                return AdblockUpdateOutcome.HashMismatch(entry.category)
            }
            blob
        }
        lists += entry to bytes
        if (bundledListBeats(listBytesTimestamp(bytes), bundledTime(entry.category))) olderThanBuiltIn += entry.category
    }
    // Switched-off categories the new version still lists unchanged.
    for (entry in manifest.lists) {
        if (entry.category in enabled || entry.category !in appliedCategories) continue
        store.reusable(entry)?.let { lists += entry to it }
    }

    try {
        store.stageAndPromote(manifest, lists)
    } catch (e: Exception) {
        Log.w(TAG, "couldn't write the update", e)
        store.discardStaging()
        return AdblockUpdateOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
    activate()
    Log.i(TAG, "applied filter-list update ${manifest.version} (${fetch.size} lists, older than bundled: $olderThanBuiltIn)")
    // Applied all the same (the version floor moves, nothing is fetched
    // again), but no list of it serves: don't call that an update.
    if (olderThanBuiltIn.size == wanted.size) return AdblockUpdateOutcome.BuiltInNewer(manifest.version)
    val order = wanted.map { it.category }
    return AdblockUpdateOutcome.Applied(manifest.version, olderThanBuiltIn.sortedBy { order.indexOf(it) })
}

/**
 * Runs [check] on [context] and hands its outcome to [record] (a
 * failure becomes [AdblockUpdateOutcome.Failed]) before returning it.
 *
 * [record] runs inside the [withContext] block, with no suspension
 * point between [check] returning and it: a check cancelled while its
 * blocking swap and `activate()` were already running (Keep up to date
 * switched off mid-check) finishes them, and its outcome — an update
 * that landed — is still recorded and stamped, even though the
 * [withContext] call then throws the cancellation to the caller (#188 R6-F2).
 */
internal suspend fun runAndRecordAdblockCheck(
    context: CoroutineContext,
    check: suspend () -> AdblockUpdateOutcome,
    record: (AdblockUpdateOutcome) -> Unit,
): AdblockUpdateOutcome = withContext(context) {
    val outcome = try {
        check()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "update check failed", e)
        AdblockUpdateOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
    record(outcome)
    outcome
}

/**
 * The feed's current payload from the Swarm gateway in use
 * ([Gateways.swarmBase] — the embedded node's, or the user's external
 * endpoint), or `null` if it can't be had within [FEED_DEADLINE_MS].
 * The endpoint needn't be trusted: [verifyAdblockManifest] decides.
 */
internal suspend fun readAdblockFeed(base: String): ByteArray? {
    val owner = AdblockFeed.OWNER.removePrefix("0x").lowercase()
    return httpGetCapped("$base/feeds/$owner/${AdblockFeed.TOPIC_HEX}", MAX_ADBLOCK_MANIFEST_BYTES.toLong(), FEED_DEADLINE_MS)
}

/** The blob [ref] from the Swarm gateway at [base], at most [maxBytes], or `null`. */
internal suspend fun downloadSwarmBytes(base: String, ref: String, maxBytes: Long): ByteArray? =
    httpGetCapped("$base/bytes/$ref", maxBytes, LIST_DEADLINE_MS)

/** A feed lookup walks the feed's updates over the network; give it a minute and a half. */
internal const val FEED_DEADLINE_MS = 90_000L

/** A list is a couple of megabytes of chunks; a slow node gets five minutes. */
internal const val LIST_DEADLINE_MS = 5 * 60_000L

/**
 * GET [url], the body if it's a 200 of at most [maxBytes], else `null`.
 * [withHardDeadline] bounds the whole call, not just each read: at
 * [deadlineMs] the connection is closed from outside the blocked thread.
 */
private suspend fun httpGetCapped(url: String, maxBytes: Long, deadlineMs: Long): ByteArray? =
    withHardDeadline(deadlineMs) { guard ->
        val conn = (TorRouting.openConnection(URL(url)) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            useCaches = false
        }
        if (!guard.register { conn.disconnect() }) return@withHardDeadline null
        try {
            if (conn.responseCode != 200) return@withHardDeadline null
            conn.inputStream.use { readAtMost(it, maxBytes) }
        } catch (e: IOException) {
            Log.i(TAG, "GET failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

private fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
}
