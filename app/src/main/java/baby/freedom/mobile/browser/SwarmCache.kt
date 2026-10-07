package baby.freedom.mobile.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import baby.freedom.mobile.R
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.data.SwarmCacheSize
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Swarm node's chunk cache figures, ant's `ant_cache_status`:
 * [usedBytes] is the unpinned content counted against [capacityBytes];
 * pinned content ([pinnedBytes]) sits outside the cap. [fileBytes] is the
 * database's size on disk. [diskEnabled] false: the database couldn't be
 * opened at init, and every disk figure is 0.
 */
internal data class SwarmCacheStatus(
    val diskEnabled: Boolean,
    val usedBytes: Long,
    val capacityBytes: Long,
    val chunks: Long,
    val pinnedBytes: Long,
    val pinnedChunks: Long,
    val fileBytes: Long,
    val memoryChunks: Long,
    val memoryCapacityChunks: Long,
    /**
     * `:node`'s mark (SwarmNode.markCacheCounting): every disk counter
     * reads 0 over a large file right after init — ant's background count
     * hasn't finished, so the 0s aren't the cache's size.
     */
    val counting: Boolean = false,
) {
    companion object {
        /**
         * [json] as ant writes it, or null when it isn't an object with
         * `disk_enabled`. A missing or negative figure reads as 0.
         */
        fun parse(json: String?): SwarmCacheStatus? {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            return from(o)
        }

        fun from(o: JSONObject?): SwarmCacheStatus? {
            if (o == null || !o.has("disk_enabled")) return null
            fun n(key: String) = o.optLong(key, 0L).coerceAtLeast(0L)
            return SwarmCacheStatus(
                diskEnabled = o.optBoolean("disk_enabled", false),
                usedBytes = n("used_bytes"),
                capacityBytes = n("capacity_bytes"),
                chunks = n("chunks"),
                pinnedBytes = n("pinned_bytes"),
                pinnedChunks = n("pinned_chunks"),
                fileBytes = n("file_bytes"),
                memoryChunks = n("memory_chunks"),
                memoryCapacityChunks = n("memory_capacity_chunks"),
                counting = o.optBoolean("counting", false),
            )
        }
    }
}

/** What a clear did, ant's `ant_cache_clear`: chunk bytes removed, and the files' size before and after. */
internal data class SwarmCacheCleared(
    val freedBytes: Long,
    val fileBytesBefore: Long,
    val fileBytesAfter: Long,
    val status: SwarmCacheStatus?,
) {
    companion object {
        /**
         * `:node`'s answer to a clear: ant's report, or its `{"error": …}`
         * (an unreadable answer too) as a failure carrying the message.
         */
        fun parse(json: String?): Result<SwarmCacheCleared> {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return Result.failure(IllegalStateException(Strings.get(R.string.node_call_failed)))
            o.optString("error").takeIf { it.isNotEmpty() }?.let { return Result.failure(IllegalStateException(it)) }
            if (!o.has("freed_bytes")) return Result.failure(IllegalStateException(Strings.get(R.string.node_call_failed)))
            fun n(key: String) = o.optLong(key, 0L).coerceAtLeast(0L)
            return Result.success(
                SwarmCacheCleared(
                    freedBytes = n("freed_bytes"),
                    fileBytesBefore = n("file_bytes_before"),
                    fileBytesAfter = n("file_bytes_after"),
                    status = SwarmCacheStatus.from(o.optJSONObject("status")),
                ),
            )
        }
    }
}

/**
 * "312 MB of 512 MB · 1.2 GB pinned": pinned only when there is some.
 * While ant is still counting after init ([SwarmCacheStatus.counting]),
 * "Counting… (512 MB max)" rather than a "0 B of 512 MB" that would read
 * as nothing to clear.
 */
internal fun swarmCacheSummary(status: SwarmCacheStatus): String {
    if (status.counting) return Strings.get(R.string.node_cache_counting, formatBytes(status.capacityBytes))
    val used = Strings.get(R.string.node_cache_used, formatBytes(status.usedBytes), formatBytes(status.capacityBytes))
    return if (status.pinnedBytes > 0) {
        "$used · ${Strings.get(R.string.node_cache_pinned, formatBytes(status.pinnedBytes))}"
    } else {
        used
    }
}

/** Delete browsing data's toast when the Swarm cache box's clear failed. */
internal fun swarmCacheClearFailedLine(message: String): String =
    Strings.get(R.string.delete_data_swarm_cache_failed, message.ifEmpty { Strings.get(R.string.node_call_failed) })

/** "Freed 300 MB". */
internal fun swarmCacheFreedLine(cleared: SwarmCacheCleared): String =
    Strings.get(R.string.node_cache_freed, formatBytes(cleared.freedBytes))

/** A cache size as the picker and the rows name it: "512 MB", "1 GB" — whole numbers, as ant's sizes are. */
internal fun swarmCacheSizeLabel(size: SwarmCacheSize): String {
    val mb = size.bytes / (1024L * 1024L)
    return if (mb >= 1024) Strings.get(R.string.library_size_gb, (mb / 1024).toString())
    else Strings.get(R.string.library_size_mb, mb.toString())
}

/** The picker's label: the default one says so. */
internal fun swarmCacheSizeOption(size: SwarmCacheSize): String =
    if (size == SwarmCacheSize.DEFAULT) Strings.get(R.string.node_cache_size_default, swarmCacheSizeLabel(size))
    else swarmCacheSizeLabel(size)

/** Picking [to] over [from] shrinks the cache, which ant evicts down to at once. */
internal fun swarmCacheShrinks(from: SwarmCacheSize, to: SwarmCacheSize): Boolean = to.bytes < from.bytes

/**
 * The UI process's way to the Swarm node's cache, which lives in `:node`
 * ([baby.freedom.mobile.node.INodeService.getSwarmCacheStatus] and
 * [baby.freedom.mobile.node.INodeService.clearSwarmCache]), over the
 * binder [StampClient] holds. A clear runs here rather than in a screen,
 * so it finishes, and its outcome stays, if the page is left meanwhile.
 */
internal object SwarmCache {
    sealed interface Clear {
        data object Idle : Clear
        data object Running : Clear
        data class Done(val cleared: SwarmCacheCleared) : Clear
        data class Failed(val message: String) : Clear
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _clear = MutableStateFlow<Clear>(Clear.Idle)

    /** The latest clear from the Nodes page: running, how it went, or none. */
    val clear: StateFlow<Clear> = _clear.asStateFlow()

    /** The cache's figures now, or null (node not running, not bound). Blocking. */
    fun status(): SwarmCacheStatus? =
        SwarmCacheStatus.parse(runCatching { StampClient.service?.swarmCacheStatus }.getOrNull())

    /** Clears the cache now. Blocking: the clear itself can take seconds. */
    fun clearNow(): Result<SwarmCacheCleared> {
        val binder = StampClient.service
            ?: return Result.failure(IllegalStateException(Strings.get(R.string.node_cache_unbound)))
        val answer = try {
            binder.clearSwarmCache()
        } catch (e: Exception) {
            return Result.failure(IllegalStateException(Strings.get(R.string.node_call_failed)))
        }
        return SwarmCacheCleared.parse(answer)
    }

    /** The Nodes page's Clear cache: runs once at a time, its outcome in [clear]. */
    fun startClear() {
        synchronized(this) {
            if (_clear.value == Clear.Running) return
            _clear.value = Clear.Running
        }
        scope.launch {
            _clear.value = clearNow().fold({ Clear.Done(it) }, { Clear.Failed(it.message.orEmpty()) })
        }
    }

    /** Forgets a finished clear's outcome, once the page that showed it closes. */
    fun forgetOutcome() {
        synchronized(this) { if (_clear.value != Clear.Running) _clear.value = Clear.Idle }
    }

    /**
     * Delete browsing data's Swarm node cache box: clears off the caller's
     * thread. "Browsing data deleted" is already up by the time the clear
     * ends, so a failure says so in a toast of its own rather than only in
     * the log: the user would otherwise believe the cache gone.
     */
    fun clearInBackground(context: Context) {
        val app = context.applicationContext
        clearInBackground(::clearNow) { message ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, swarmCacheClearFailedLine(message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** [clearInBackground] over its clear, [onFailed] getting the failure's message; for tests. */
    internal fun clearInBackground(
        clear: () -> Result<SwarmCacheCleared>,
        onFailed: (String) -> Unit,
    ): Job = scope.launch {
        val result = try {
            clear()
        } catch (e: Exception) {
            Result.failure(e)
        }
        result
            .onSuccess { Log.i(TAG, "cleared from Delete browsing data: ${it.freedBytes} bytes freed") }
            .onFailure {
                Log.w(TAG, "clear from Delete browsing data failed: ${it.message}")
                onFailed(it.message.orEmpty())
            }
    }

    private const val TAG = "SwarmCache"
}

/**
 * The user picked [to] over [from] — on the Nodes page or in Settings,
 * both through here: saved (MainActivity relays it to the node, which
 * applies it at once), and a shrink says it frees space right away.
 */
internal fun pickSwarmCacheSize(
    context: Context,
    scope: CoroutineScope,
    settings: NodeSettings,
    from: SwarmCacheSize,
    to: SwarmCacheSize,
) {
    if (to == from) return
    scope.launch { settings.setSwarmCacheSize(to) }
    if (swarmCacheShrinks(from, to)) {
        Toast.makeText(
            context,
            Strings.get(R.string.node_cache_size_shrunk, swarmCacheSizeLabel(to)),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * The cache size picker: the five sizes as radio rows (48 dp, one control
 * each to TalkBack), a note that a smaller one frees space at once, and
 * Cancel. Picking one applies it ([onPick]) and closes.
 */
@Composable
internal fun SwarmCacheSizeDialog(
    current: SwarmCacheSize,
    onPick: (SwarmCacheSize) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.node_cache_size_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (size in SwarmCacheSize.entries) {
                    EngineRadioRow(
                        label = swarmCacheSizeOption(size),
                        selected = size == current,
                        onClick = { onPick(size) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.node_cache_size_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** How often the Nodes page re-reads the cache's figures while it's on screen. */
internal const val SWARM_CACHE_POLL_MS = 3_000L

/**
 * The cache's figures while [running], re-read every [SWARM_CACHE_POLL_MS]
 * while the app is in the foreground, and at once whenever [refresh]
 * changes (a clear ended); null while the node isn't running. A failed
 * read keeps the last figures.
 */
@Composable
internal fun rememberSwarmCacheStatus(running: Boolean, refresh: Any): SwarmCacheStatus? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status by produceState<SwarmCacheStatus?>(null, running, refresh, lifecycle) {
        if (!running) {
            value = null
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withContext(Dispatchers.IO) { SwarmCache.status() }?.let { value = it }
                delay(SWARM_CACHE_POLL_MS)
            }
        }
    }
    return status
}
