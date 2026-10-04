package baby.freedom.mobile.browser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * with its time; the next launch reads and removes it, and if the
 * previous process died of a crash or an ANR within [CRASH_WINDOW_MS] of
 * that restore ([skipsRestore]), the tabs aren't loaded: the app starts
 * on a home tab, and offers them back on the reopen stack instead.
 */
class TabsStore internal constructor(
    private val dir: File,
    /** When the previous run of the app's main process crashed, if it did (wall clock ms). */
    private val lastCrashAt: () -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dir, FILE)
    private val mark = File(dir, RESTORE_MARK)
    private val writeLock = Mutex()

    /** The newest [save] asked for; older ones still queued are skipped. */
    @Volatile
    private var latest = 0L

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What a cold start finds on disk. */
    sealed interface Found {
        /** Nothing saved, or only a home tab. */
        data object Nothing : Found

        /** The tabs to bring back. */
        class Tabs(val saved: TabsState.SavedTabs) : Found

        /** The last restore crashed the app (see [skipsRestore]): don't load these. */
        class SkippedAfterCrash(val saved: TabsState.SavedTabs) : Found
    }

    /**
     * Read the saved tabs for a cold start. Blocking: call off the main
     * thread. When it returns [Found.Tabs], the caller is about to load
     * them, and the restore is marked so a crash it causes is caught on
     * the next launch.
     */
    fun load(): Found {
        val markAt = takeMark()
        val saved = read() ?: return Found.Nothing
        if (saved.isJustHome()) return Found.Nothing
        if (skipsRestore(markAt, runCatching { lastCrashAt() }.getOrNull())) {
            Log.w(TAG, "the last restore crashed the app; starting on a home tab")
            return Found.SkippedAfterCrash(saved)
        }
        writeMark(clock())
        return Found.Tabs(saved)
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
                if (saved.isJustHome()) delete() else writeAtomically(encode(saved))
            } catch (e: Exception) {
                Log.w(TAG, "couldn't save the open tabs", e)
            }
        }
    }

    private fun read(): TabsState.SavedTabs? = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) null else decode(file.readText())
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the saved tabs", e)
        null
    }

    private fun delete() {
        file.delete()
        File(dir, "$FILE.tmp").delete()
    }

    private fun writeAtomically(text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no directory $dir")
        val tmp = File(dir, "$FILE.tmp")
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
        private const val VERSION = 1

        /**
         * A crash this soon after a restore counts as caused by it: long
         * enough for a slow dweb page to load (and crash), short enough
         * that a crash much later, while the user was browsing, still
         * restores their tabs.
         */
        const val CRASH_WINDOW_MS = 60_000L

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
                    lastCrashAt = { lastMainProcessCrash(app) },
                ).also { instance = it }
            }
        }

        /**
         * Skip the restore: the previous run restored tabs at [markAt],
         * and its process then died of a crash or an ANR at [crashAt],
         * within [CRASH_WINDOW_MS] of it.
         */
        fun skipsRestore(markAt: Long?, crashAt: Long?, windowMs: Long = CRASH_WINDOW_MS): Boolean {
            if (markAt == null || crashAt == null) return false
            return crashAt - markAt in 0..windowMs
        }

        /**
         * When the app's main process last ended in a crash (Java or
         * native) or an ANR, if its most recent exit was one. Other
         * processes of the app (the nodes') don't count.
         */
        private fun lastMainProcessCrash(context: Context): Long? {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val main = context.applicationInfo.processName ?: context.packageName
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .filter { it.processName == main }
                .maxByOrNull { it.timestamp } ?: return null
            return last.timestamp.takeIf { last.reason in CRASH_REASONS }
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
