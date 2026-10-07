package baby.freedom.mobile.browser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The regular tabs on disk, so they come back after the app was closed
 * for real (#400 item 13): swiped away from Recents, force-stopped,
 * updated, or the phone rebooted. A process killed in the background
 * already gets them back from the saved instance state ([TabsSession]);
 * this file is what a cold start without one reads.
 *
 * It holds exactly what [TabsState.saveForProcessDeath] keeps — each
 * regular tab's address, title and committed/stopped flags, their order,
 * and which one was active — and nothing of a private tab (#86), which
 * that leaves out before anything else reads the tabs. No back/forward
 * history, no thumbnails, no reopen stack.
 *
 * Written whole each time, to a temp file that is synced and then renamed
 * over the old one, so a crash or power loss mid-write leaves either the
 * old list or the new one. Kept in `noBackupFilesDir`: open tabs aren't
 * something to upload to a cloud backup.
 *
 * A restore that brings back a page which crashes the app would crash it
 * again on every launch. So every restore leaves a mark ([RESTORE_MARK])
 * with its time ([markRestored]), which stays until the restored page has
 * loaded and then [CRASH_WINDOW_MS] more have passed ([settleRestore]).
 * The next launch reads and removes it, and if the previous process died
 * of a crash or an ANR while it was still there (and at most
 * [MARK_MAX_MS] after the restore, [skipsRestore]), the tabs aren't
 * loaded: the app starts on a home tab, and offers them back instead.
 *
 * Tabs held back like that move to a file of their own ([HELD]) at once,
 * before anything else is written: the open list is about to become the
 * lone home tab. They stay there until the user acts on the offer
 * ([releaseHeld]) — restores them, dismisses it, or clears history — so
 * a launch that ends before that (another crash, a swipe) offers them
 * again rather than losing them. A held list is never loaded on its own,
 * so it can't crash-loop the app.
 */
class TabsStore internal constructor(
    private val dir: File,
    /**
     * When the run of the app's main process that was going at [since]
     * (wall clock ms) ended, if it ended in a crash — the first exit of
     * that process from then on, not merely the latest one (R4-M2).
     */
    private val crashAfter: (since: Long) -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dir, FILE)
    private val mark = File(dir, RESTORE_MARK)
    private val heldFile = File(dir, HELD)
    private val writeLock = Mutex()

    /** The newest [save] asked for; older ones still queued are skipped. */
    @Volatile
    private var latest = 0L

    /** The newest save sequence number that reached the disk (under [writeLock]). */
    private var written = 0L

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** An exit of the main process: when, and whether it was a crash or an ANR. */
    class Exit(val at: Long, val crashed: Boolean)

    /** What a cold start finds on disk. */
    sealed interface Found {
        /** Nothing saved, or only a home tab. */
        data object Nothing : Found

        /**
         * The tabs to bring back — the caller marks the restore
         * ([markRestored]) once it has — and [held], tabs still held back
         * from an earlier crashed restore, to offer beside them.
         */
        class Tabs(val saved: TabsState.SavedTabs, val held: TabsState.SavedTabs? = null) : Found

        /**
         * Nothing to load, but tabs held back ([HELD]): the last restore
         * crashed the app (see [skipsRestore]), now or on an earlier
         * launch whose offer the user never answered.
         */
        class SkippedAfterCrash(val saved: TabsState.SavedTabs) : Found
    }

    /**
     * Read the saved tabs for a cold start. Blocking: call off the main
     * thread. Tabs a crash skips are moved to the held file before this
     * returns, so the list written next can't lose them.
     */
    fun load(): Found = synchronized(this) {
        val markAt = takeMark()
        val saved = read(file)?.takeUnless { it.isJustHome() }
        var held = read(heldFile)?.takeUnless { it.isJustHome() }
        val crashAt = markAt?.let { runCatching { crashAfter(it) }.getOrNull() }
        if (saved != null && skipsRestore(markAt, crashAt)) {
            Log.w(TAG, "the last restore crashed the app; starting on a home tab")
            // Moved, not copied: the next launch offers them again
            // rather than loading them (if the move failed, they're
            // still where they were — loaded next time, as before).
            held = holdNow(saved)?.also { delete() } ?: merged(held, saved)
            return Found.SkippedAfterCrash(held)
        }
        return when {
            saved != null -> Found.Tabs(saved, held)
            held != null -> Found.SkippedAfterCrash(held)
            else -> Found.Nothing
        }
    }

    /**
     * What a relaunch from saved instance state finds on disk: whether
     * the previous process left an unsettled restore mark ([unsettled],
     * taken here like [load] takes it), and tabs still held from an
     * offer it never answered ([held]).
     */
    class Resumed(val unsettled: Boolean, val held: TabsState.SavedTabs?)

    /**
     * Read what a relaunch from saved instance state needs ([Resumed]).
     * The open-tabs file isn't read: the saved instance state is newer.
     * Blocking: call off the main thread.
     */
    fun resume(): Resumed = synchronized(this) {
        Resumed(unsettled = takeMark() != null, held = read(heldFile)?.takeUnless { it.isJustHome() })
    }

    /**
     * Add [saved] to the held tabs (see [HELD]) — tabs read from disk
     * that this run isn't loading after all — and return everything held
     * now. Blocking I/O: call on [Dispatchers.IO].
     */
    suspend fun hold(saved: TabsState.SavedTabs): TabsState.SavedTabs =
        writeLock.withLock { synchronized(this) { holdNow(saved) ?: merged(read(heldFile), saved) } }

    /**
     * The held tabs' offer is answered (restored, dismissed, or the
     * reopen stack cleared): forget them on disk. If restored, they're
     * open tabs now and saved as such.
     */
    fun releaseHeld() {
        writes.launch {
            writeLock.withLock { if (!heldFile.delete() && heldFile.exists()) Log.w(TAG, "couldn't drop the held tabs") }
        }
    }

    /**
     * The held tabs were brought back (the offer's Restore, or Reopen
     * closed tab): they're [saved] among the open tabs now. In one go,
     * write that list, mark the restore ([markRestored]) and only then
     * drop the held file — so there's no moment where a crash finds them
     * in neither file (R4-F1), nor one where they're open but unguarded
     * (R4-M1). If the list can't be written the held file stays, and
     * they're offered again next time. Completes with the mark.
     *
     * Its place in line among the saves is taken here, on the caller's
     * thread, as [saveLater]'s is: a save the caller asked for before
     * this (a list from before the restore) can't land after it and
     * delete the list the held file was dropped for (R5-M1). Runs on the
     * store's own scope, so it lands even if the caller goes away.
     */
    fun adoptHeld(saved: TabsState.SavedTabs): Deferred<Long> {
        val seq = nextSeq()
        return writes.async { adopt(seq, saved) }
    }

    private suspend fun adopt(seq: Long, saved: TabsState.SavedTabs): Long {
        val at = clock()
        writeLock.withLock {
            // A newer list already on disk holds them too (unless the
            // user closed them since, which is theirs to do).
            val listed = seq < written || try {
                if (saved.isJustHome()) delete() else writeAtomically(file, encode(saved))
                written = seq
                true
            } catch (e: Exception) {
                Log.w(TAG, "couldn't save the restored tabs", e)
                false
            }
            writeMark(at)
            if (listed && !heldFile.delete() && heldFile.exists()) Log.w(TAG, "couldn't drop the held tabs")
        }
        return at
    }

    /**
     * Tabs were just loaded from disk (or from the held ones): mark it,
     * so a crash before [settleRestore] skips them on the next launch.
     * Returns the mark, for [settleRestore]. Blocking I/O.
     */
    suspend fun markRestored(): Long {
        val at = clock()
        writeLock.withLock { writeMark(at) }
        return at
    }

    /**
     * The restore marked [at] got through its page's load and the crash
     * window after it: a crash from now on isn't its fault. Leaves a
     * newer restore's mark alone. Blocking I/O.
     */
    suspend fun settleRestore(at: Long) {
        writeLock.withLock {
            val current = runCatching { mark.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
            if (current == at) mark.delete()
        }
    }

    /** Merge [saved] into the held file; null (and the merged list unwritten) if that failed. */
    private fun holdNow(saved: TabsState.SavedTabs): TabsState.SavedTabs? {
        val all = merged(read(heldFile)?.takeUnless { it.isJustHome() }, saved)
        return try {
            writeAtomically(heldFile, encode(all))
            all
        } catch (e: Exception) {
            Log.w(TAG, "couldn't hold the skipped tabs", e)
            null
        }
    }

    /**
     * [save] in the background, on a scope of its own that outlives the
     * screen and the session that asked: a save asked for from `onStop`
     * still lands. Its place in line is taken now, so a later call wins
     * over this one whichever reaches the disk first.
     */
    fun saveLater(saved: TabsState.SavedTabs) {
        val seq = nextSeq()
        writes.launch { write(seq, saved) }
    }

    /**
     * Write [saved] as the tabs to restore, replacing what was there —
     * or remove the file when there is nothing worth restoring (a lone
     * home tab, or none). Blocking I/O: call on [Dispatchers.IO].
     * Serialised: the last call wins, even if an earlier one is still
     * waiting for the lock. Never throws.
     */
    suspend fun save(saved: TabsState.SavedTabs) = write(nextSeq(), saved)

    private fun nextSeq(): Long = synchronized(this) { ++latest }

    private suspend fun write(seq: Long, saved: TabsState.SavedTabs) {
        writeLock.withLock {
            if (seq < latest) return
            try {
                if (saved.isJustHome()) delete() else writeAtomically(file, encode(saved))
                written = seq
            } catch (e: Exception) {
                Log.w(TAG, "couldn't save the open tabs", e)
            }
        }
    }

    private fun read(file: File): TabsState.SavedTabs? = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) null else decode(file.readText())
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the saved tabs", e)
        null
    }

    private fun delete() {
        file.delete()
        File(dir, "$FILE.tmp").delete()
    }

    private fun writeAtomically(file: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no directory $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed")
        }
        syncDir()
    }

    /** The rename itself survives a power loss only once the directory is synced. */
    private fun syncDir() {
        runCatching {
            android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0).let { fd ->
                try {
                    android.system.Os.fsync(fd)
                } finally {
                    android.system.Os.close(fd)
                }
            }
        }
    }

    /** The time of the last restore, removed as it's read: it speaks for the previous run only. */
    private fun takeMark(): Long? {
        val at = runCatching { mark.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()
        mark.delete()
        return at
    }

    private fun writeMark(at: Long) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val tmp = File(dir, "$RESTORE_MARK.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(at.toString().toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(mark)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't mark the restore", e)
        }
    }

    companion object {
        private const val TAG = "TabsStore"
        const val FILE = "open-tabs.json"
        const val RESTORE_MARK = "restore-mark"

        /** Tabs a crashed restore held back, until the user answers their offer. */
        const val HELD = "held-tabs.json"
        private const val VERSION = 1

        /**
         * A crash this soon after the restored page has loaded still
         * counts as caused by it ([settleRestore]): a page can crash the
         * app a while after its load finishes. A crash later than that,
         * while the user was browsing, restores their tabs.
         */
        const val CRASH_WINDOW_MS = 60_000L

        /**
         * The longest a restore stays to blame when its page never
         * finishes loading (a dweb page waiting on a node that doesn't
         * start, say). Long enough for a slow node start; a crash after
         * that, even unsettled, isn't pinned on the restore.
         */
        const val MARK_MAX_MS = 10 * 60_000L

        /**
         * A saved list is at most [TabsState.MAX_SAVED_CHARS] chars of
         * addresses and titles; anything much bigger isn't ours.
         */
        private const val MAX_FILE_BYTES = 512L * 1024

        /** How long tab changes settle before they're written. */
        const val SAVE_DEBOUNCE_MS = 1_000L

        @Volatile
        private var instance: TabsStore? = null

        fun get(context: Context): TabsStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                TabsStore(
                    dir = File(app.noBackupFilesDir, "tabs"),
                    crashAfter = { since -> mainProcessCrashAfter(app, since) },
                ).also { instance = it }
            }
        }

        /**
         * Skip the restore: the previous run restored tabs at [markAt]
         * and hadn't settled that restore yet ([settleRestore]), and its
         * process then died of a crash or an ANR at [crashAt], within
         * [MARK_MAX_MS] of it.
         */
        fun skipsRestore(markAt: Long?, crashAt: Long?, windowMs: Long = MARK_MAX_MS): Boolean {
            if (markAt == null || crashAt == null) return false
            return crashAt - markAt in 0..windowMs
        }

        /**
         * When the app's main process ended in a crash (Java or native)
         * or an ANR, if the run that was going at [since] ended in one:
         * the first exit of that process from [since] on. Later exits
         * (a start in the background for a job or an alarm, reclaimed
         * for memory) are other runs and don't mask it (R4-M2). Other
         * processes of the app (the nodes') don't count.
         */
        private fun mainProcessCrashAfter(context: Context, since: Long): Long? {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val main = context.applicationInfo.processName ?: context.packageName
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .filter { it.processName == main }
                .map { Exit(it.timestamp, it.reason in CRASH_REASONS) }
            return firstExitCrash(exits, since)
        }

        /** The time of the first of [exits] at or after [since], if that one was a crash. */
        fun firstExitCrash(exits: List<Exit>, since: Long): Long? {
            val first = exits.filter { it.at >= since }.minByOrNull { it.at } ?: return null
            return first.at.takeIf { first.crashed }
        }

        /**
         * [older] held tabs, then [newer] ones, the newer list's active
         * tab active. Bounded like a saved list ([TabsState.MAX_SAVED_CHARS]),
         * the oldest dropped first, so repeated crashes can't grow it
         * past what [read] accepts.
         */
        fun merged(older: TabsState.SavedTabs?, newer: TabsState.SavedTabs): TabsState.SavedTabs {
            if (older == null || older.tabs.isEmpty()) return newer
            var all = older.tabs + newer.tabs
            var active = older.tabs.size + newer.activeIndex
            while (all.size > newer.tabs.size && all.sumOf { it.address.length + it.title.length } > TabsState.MAX_SAVED_CHARS) {
                all = all.drop(1)
                active--
            }
            return TabsState.SavedTabs(all, active.coerceIn(0, all.lastIndex))
        }

        private val CRASH_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )

        fun encode(saved: TabsState.SavedTabs): String = JSONObject()
            .put("version", VERSION)
            .put("active", saved.activeIndex)
            .put(
                "tabs",
                JSONArray().apply {
                    for (tab in saved.tabs) {
                        put(
                            JSONObject()
                                .put("address", tab.address)
                                .put("title", tab.title)
                                .put("committed", tab.committed)
                                .put("stopped", tab.loadStopped),
                        )
                    }
                },
            )
            .toString()

        /** Null for anything that isn't a list this version wrote. */
        fun decode(text: String): TabsState.SavedTabs? = try {
            val json = JSONObject(text)
            if (json.optInt("version") != VERSION) {
                null
            } else {
                val array = json.getJSONArray("tabs")
                val tabs = (0 until array.length()).map { i ->
                    val tab = array.getJSONObject(i)
                    TabsState.SavedTab(
                        title = tab.optString("title").take(TabsState.MAX_SAVED_TITLE),
                        address = tab.getString("address"),
                        committed = tab.optBoolean("committed"),
                        loadStopped = tab.optBoolean("stopped"),
                    )
                }.filter { it.address.length <= TabsState.MAX_SAVED_ADDRESS }
                TabsState.SavedTabs(tabs, json.optInt("active").coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
            }
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }
}
