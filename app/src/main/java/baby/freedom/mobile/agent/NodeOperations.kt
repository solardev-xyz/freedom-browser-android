package baby.freedom.mobile.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One node request's receipt (desktop's `node-operation-store.js`): what
 * was asked (the method, the canonical path, the header names and the
 * body's hash, never the body), how far it got ([state]) and, once the
 * node answered, its [response].
 */
internal data class NodeOperation(
    val operationId: String,
    /** The chat it belongs to: only that chat can read it back. */
    val ownerId: String,
    val state: String,
    val service: String,
    val transport: String,
    val effect: String,
    val method: String,
    val path: String,
    val headerNames: List<String> = emptyList(),
    val bodySha256: String? = null,
    val response: NodeResponse? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Only a read may be sent again: a write whose outcome is unknown is never retried for the user. */
    val retrySafety: String get() = if (effect == Effect.READ.wire) "safe" else "unsafe"

    fun json(): JSONObject = JSONObject()
        .put("operationId", operationId)
        .put("ownerId", ownerId)
        .put("state", state)
        .put("service", service)
        .put("transport", transport)
        .put("effect", effect)
        .put("method", method)
        .put("path", path)
        .put("headerNames", JSONArray(headerNames))
        .apply {
            bodySha256?.let { put("bodySha256", it) }
            response?.let { put("response", it.json()) }
            errorCode?.let { put("errorCode", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        const val NOT_DISPATCHED = "not_dispatched"
        const val IN_FLIGHT = "in_flight"
        const val RESPONDED = "responded"
        const val DELIVERY_UNCERTAIN = "delivery_uncertain"
        private val STATES = setOf(NOT_DISPATCHED, IN_FLIGHT, RESPONDED, DELIVERY_UNCERTAIN)

        /** Null for a record that isn't one; an unknown state reads as uncertain (desktop's `rowToOperation`). */
        fun of(o: JSONObject): NodeOperation? = try {
            NodeOperation(
                operationId = o.getString("operationId"),
                ownerId = o.getString("ownerId"),
                state = o.getString("state").takeIf { it in STATES } ?: DELIVERY_UNCERTAIN,
                service = o.getString("service"),
                transport = o.getString("transport"),
                effect = o.getString("effect"),
                method = o.getString("method"),
                path = o.getString("path"),
                headerNames = o.optJSONArray("headerNames")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                bodySha256 = o.optString("bodySha256").ifEmpty { null },
                response = o.optJSONObject("response")?.let(NodeResponse::of),
                errorCode = o.optString("errorCode").ifEmpty { null },
                errorMessage = o.optString("errorMessage").ifEmpty { null },
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Where node operations are kept: a seam, so the tools are tested
 * against memory and never against a file.
 */
internal interface NodeOperationStore {
    suspend fun put(operation: NodeOperation)
    suspend fun get(operationId: String): NodeOperation?
    suspend fun listRecent(ownerId: String, limit: Int): List<NodeOperation>
    suspend fun deleteOwner(ownerId: String)
    suspend fun deleteAll()
}

/** The journal in memory only (tests). */
internal class MemoryNodeOperationStore : NodeOperationStore {
    private val ops = LinkedHashMap<String, NodeOperation>()

    override suspend fun put(operation: NodeOperation) = synchronized(ops) {
        ops[operation.operationId] = operation
    }

    override suspend fun get(operationId: String) = synchronized(ops) { ops[operationId] }

    override suspend fun listRecent(ownerId: String, limit: Int) = synchronized(ops) {
        ops.values.filter { it.ownerId == ownerId }.sortedByDescending { it.updatedAt }.take(limit)
    }

    override suspend fun deleteOwner(ownerId: String) = synchronized(ops) {
        ops.values.removeAll { it.ownerId == ownerId }
        Unit
    }

    override suspend fun deleteAll() = synchronized(ops) { ops.clear() }
}

/**
 * The journal on flash (in the app's no-backup files: a receipt is about
 * this phone's nodes and may hold what they answered, so it never
 * travels), one file per operation under [dir] (so an update
 * rewrites one receipt, not all of them), each written to a temporary
 * file, synced and renamed into place, and the directory synced too. Read
 * once per process, on first use: every receipt still `in_flight` then
 * is from a process that died before its answer came, and becomes
 * `delivery_uncertain` (desktop's `markStaleInFlightAsUncertain`). The
 * oldest finished receipts past [MAX_RETAINED] go (an in-flight one never).
 */
internal class FileNodeOperationStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) : NodeOperationStore {
    private val mutex = Mutex()
    private var ops: LinkedHashMap<String, NodeOperation>? = null

    private suspend fun loaded(): LinkedHashMap<String, NodeOperation> = ops ?: withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, NodeOperation>()
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.forEach { f ->
            val op = try {
                NodeOperation.of(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
            if (op == null || f.name != op.operationId + SUFFIX) {
                f.delete()
                return@forEach
            }
            map[op.operationId] = op
        }
        for (op in map.values.toList()) {
            if (op.state == NodeOperation.IN_FLIGHT) {
                val swept = op.copy(
                    state = NodeOperation.DELIVERY_UNCERTAIN,
                    errorCode = "NODE_DELIVERY_UNCERTAIN",
                    errorMessage = "Freedom restarted before the node response was received",
                    updatedAt = now(),
                )
                map[op.operationId] = swept
                runCatching { write(swept) }.onFailure { Log.w(TAG, "couldn't mark an interrupted node operation (${it.javaClass.simpleName})") }
            }
        }
        map.also { ops = it }
    }

    override suspend fun put(operation: NodeOperation) = mutex.withLock {
        val map = loaded()
        withContext(Dispatchers.IO) { write(operation) }
        map[operation.operationId] = operation
        val finished = map.values.filter { it.state != NodeOperation.IN_FLIGHT }.sortedByDescending { it.updatedAt }
        for (old in finished.drop(MAX_RETAINED)) {
            map.remove(old.operationId)
            withContext(Dispatchers.IO) { File(dir, old.operationId + SUFFIX).delete() }
        }
    }

    override suspend fun get(operationId: String): NodeOperation? = mutex.withLock { loaded()[operationId] }

    override suspend fun listRecent(ownerId: String, limit: Int): List<NodeOperation> = mutex.withLock {
        loaded().values.filter { it.ownerId == ownerId }.sortedByDescending { it.updatedAt }.take(limit)
    }

    override suspend fun deleteOwner(ownerId: String) = mutex.withLock {
        val map = loaded()
        for (op in map.values.filter { it.ownerId == ownerId }) {
            map.remove(op.operationId)
            withContext(Dispatchers.IO) { File(dir, op.operationId + SUFFIX).delete() }
        }
    }

    override suspend fun deleteAll() = mutex.withLock {
        loaded().clear()
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /** Throws when the receipt couldn't be made durable. */
    private fun write(op: NodeOperation) {
        dir.mkdirs()
        val target = File(dir, op.operationId + SUFFIX)
        val tmp = File(dir, op.operationId + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(op.json().toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
        runCatching { FileOutputStream(dir).use { it.fd.sync() } }
    }

    companion object {
        const val MAX_RETAINED = 500
        private const val SUFFIX = ".json"
        private const val TAG = "NodeOperations"
    }
}

/**
 * The node requests the agent sent (#598), process-wide: their [store],
 * and the ones still running. A write runs in the process's own [scope],
 * not the turn's: Stop (or the answer taking longer than the turn waits)
 * ends only the wait; the request goes on to its answer or its deadline
 * and the receipt says which. A read is the turn's own and ends with it.
 */
internal class NodeOperations(val store: NodeOperationStore, private val now: () -> Long = System::currentTimeMillis) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = java.util.concurrent.ConcurrentHashMap<String, Deferred<*>>()

    fun active(operationId: String): Deferred<*>? = active[operationId]

    fun track(operationId: String, job: Deferred<*>) {
        active[operationId] = job
        job.invokeOnCompletion { active.remove(operationId, job) }
    }

    fun now(): Long = now.invoke()

    companion object {
        private val random = SecureRandom()

        /** Desktop's opaque id: `node_op_` and 24 hex digits. */
        fun newId(): String {
            val b = ByteArray(12).also(random::nextBytes)
            return "node_op_" + b.joinToString("") { "%02x".format(it) }
        }

        val ID = Regex("^node_op_[a-f0-9]{24}$")

        @Volatile
        private var instance: NodeOperations? = null

        fun get(context: Context): NodeOperations = instance ?: synchronized(this) {
            instance ?: NodeOperations(FileNodeOperationStore(File(context.applicationContext.noBackupFilesDir, "agent-node-operations")))
                .also { instance = it }
        }

        /** The process's instance if one was made: what deleting chats clears. */
        fun existing(): NodeOperations? = instance
    }
}
