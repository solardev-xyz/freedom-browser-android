package baby.freedom.swarm

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * One chain's sync-state directory as handed to the engine. [id] is null
 * for the chain directory itself (the implicit bundled generation every
 * install starts on).
 */
data class MyotisGeneration(
    val id: String?,
    val directory: File,
    val origin: Origin,
    /** The quorum-agreed checkpoint a [Origin.Verified] generation bootstraps from. */
    val checkpoint: MyotisCheckpointRecord?,
) {
    enum class Origin(val code: String) {
        /** Bootstrapped from the engine's embedded checkpoint (`myotis_create`). */
        Bundled("bundled"),

        /** Bootstrapped from a quorum-agreed checkpoint (`myotis_create_with_checkpoint`). */
        Verified("verified"),
    }
}

/**
 * Stale-anchor checkpoint recovery (#195), part 3: per-chain sync-state
 * *generations*. Port of iOS `MyotisGenerationStore.swift` (desktop
 * `checkpoint-store.js` minus its multi-process ownership receipts). Every
 * call runs on [MyotisNode]'s op queue, after the chain's previous engine
 * has stopped.
 *
 * Layout under `<dataDir>/<network>/`:
 *
 *     …engine files…                     the implicit bundled generation (no pointer)
 *     verified-sync.json                 pointer {schemaVersion, chainId, generation}
 *     verified-sync-backup-<uuid>.json   the old pointer, byte for byte (repair only)
 *     verified-sync/<uuid>/anchor.json   {origin: bundled|verified, checkpoint?}
 *     verified-sync/<uuid>/rejected.json the engine contradicted its checkpoint
 *     verified-sync/<uuid>/…             that generation's engine files, incl. the
 *                                        engine's own sync-anchor[-net].json marker
 *
 * Android difference from iOS: with no pointer the chain directory itself
 * is the bundled generation — where #72's engines already keep their
 * state — so turning recovery on doesn't make every install resync from
 * scratch. A pointer is written the first time recovery (or a repair)
 * mints a generation.
 *
 * Invariants: a generation's `anchor.json` is written once and never
 * edited; the host never writes the engine's marker, it only refuses a
 * generation whose existing marker disagrees with its record ([MyotisCheckpointError.Storage]
 * → Repair). Retired generations are kept for a while (the last
 * [KEEP_RETIRED], plus any a repair backup points at) and then deleted: a Gnosis install that sits closed for
 * more than ~34 h mints one on every return, so keeping them all, as iOS
 * does, would grow without bound.
 */
class MyotisGenerationStore(private val baseDir: File) {

    fun chainDirectory(network: MyotisNetwork) = File(baseDir, network.engineName)

    private fun pointerFile(network: MyotisNetwork) = File(chainDirectory(network), POINTER)

    private fun generationsDirectory(network: MyotisNetwork) = File(chainDirectory(network), GENERATIONS)

    private fun generationDirectory(network: MyotisNetwork, id: String) = File(generationsDirectory(network), id)

    /**
     * The generation the engine should run: the pointed-at one, or the
     * chain directory when there's no pointer yet. Throws
     * [MyotisCheckpointError.Storage] for a pointer or record that isn't
     * intact — never a guess at some other directory.
     */
    fun load(network: MyotisNetwork): MyotisGeneration {
        val generation = loadIntact(network)
        // A generation the engine contradicted ([reject]) never boots again.
        if (generation.id != null && exists(File(generation.directory, REJECTED))) {
            throw MyotisCheckpointException(MyotisCheckpointError.AnchorMismatch)
        }
        return generation
    }

    private fun loadIntact(network: MyotisNetwork): MyotisGeneration {
        val pointer = pointerFile(network)
        if (!exists(pointer)) {
            return MyotisGeneration(null, chainDirectory(network), MyotisGeneration.Origin.Bundled, null)
        }
        val id = readJson(pointer)?.let { p ->
            (p.opt("generation") as? String).takeIf {
                MyotisHex.uint(p.opt("schemaVersion")) == SCHEMA_VERSION.toLong() &&
                    MyotisHex.uint(p.opt("chainId")) == network.chainId
            }
        }?.takeIf(::isGenerationId) ?: throw storage()
        val directory = generationDirectory(network, id)
        val anchor = readJson(File(directory, ANCHOR)) ?: throw storage()
        if (MyotisHex.uint(anchor.opt("schemaVersion")) != SCHEMA_VERSION.toLong() ||
            MyotisHex.uint(anchor.opt("chainId")) != network.chainId ||
            anchor.opt("generation") != id ||
            MyotisHex.uint(anchor.opt("nativeCheckpointApi")) != NATIVE_CHECKPOINT_API.toLong()
        ) throw storage()
        val record = anchor.optJSONObject("checkpoint")
        return when (anchor.opt("origin")) {
            MyotisGeneration.Origin.Bundled.code -> {
                if (anchor.has("checkpoint")) throw storage()
                MyotisGeneration(id, directory, MyotisGeneration.Origin.Bundled, null)
            }
            MyotisGeneration.Origin.Verified.code -> {
                val checkpoint = record?.let(MyotisCheckpointRecord::fromJson) ?: throw storage()
                val valid = try {
                    checkpoint.validated(network.chainId, nowMs = 0L, fresh = false)
                } catch (_: MyotisCheckpointException) {
                    throw storage()
                }
                MyotisGeneration(id, directory, MyotisGeneration.Origin.Verified, valid)
            }
            else -> throw storage()
        }
    }

    /**
     * The engine's own verified finalized root contradicted [generation]'s
     * checkpoint: mark it so [load] refuses it with
     * [MyotisCheckpointError.AnchorMismatch] from now on, across restarts, until
     * a recovery mints a replacement. Only a verified generation carries a
     * checkpoint to contradict.
     */
    fun reject(generation: MyotisGeneration) {
        if (generation.id == null || generation.origin != MyotisGeneration.Origin.Verified) return
        try {
            writeAtomic(File(generation.directory, REJECTED), JSONObject().put("reason", "mismatch").toString())
        } catch (e: IOException) {
            throw map(e)
        } catch (e: SecurityException) {
            throw map(e)
        }
    }

    /** Mint a verified generation for [checkpoint] and point at it; the previous one is retired. */
    fun replace(network: MyotisNetwork, checkpoint: MyotisCheckpointRecord, nowMs: Long): MyotisGeneration {
        val valid = checkpoint.validated(network.chainId, nowMs, fresh = true)
        return create(network, MyotisGeneration.Origin.Verified, valid)
    }

    /**
     * "Repair sync data": back up the pointer byte for byte, then start a
     * fresh bundled generation. If the embedded anchor is itself stale the
     * ordinary recovery runs next.
     */
    fun repair(network: MyotisNetwork): MyotisGeneration {
        val pointer = pointerFile(network)
        if (exists(pointer)) {
            try {
                pointer.copyTo(File(chainDirectory(network), "$BACKUP_PREFIX${newId()}.json"))
            } catch (e: IOException) {
                throw map(e)
            }
        }
        return create(network, MyotisGeneration.Origin.Bundled, null)
    }

    /**
     * Before a generation is handed to the engine: an existing native
     * marker must agree with the generation's record. Absent is fine (the
     * engine writes it on the first checkpoint create); present on a
     * bundled generation, unreadable, not a regular file, or disagreeing
     * is [MyotisCheckpointError.Storage].
     */
    fun checkNativeMarker(generation: MyotisGeneration, network: MyotisNetwork) {
        val marker = File(generation.directory, nativeMarkerName(network))
        if (!exists(marker)) return
        val record = generation.checkpoint
        if (generation.origin != MyotisGeneration.Origin.Verified || record == null) throw storage()
        val body = readJson(marker) ?: throw storage()
        if (MyotisHex.root(body.opt("checkpointRoot")) != record.root ||
            MyotisHex.uint(body.opt("checkpointSlot")) != record.slot
        ) throw storage()
    }

    private fun create(
        network: MyotisNetwork,
        origin: MyotisGeneration.Origin,
        checkpoint: MyotisCheckpointRecord?,
    ): MyotisGeneration {
        val id = newId()
        val directory = generationDirectory(network, id)
        val previous = currentDirectory(network)
        val anchor = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chainId", network.chainId)
            .put("generation", id)
            .put("nativeCheckpointApi", NATIVE_CHECKPOINT_API)
            .put("origin", origin.code)
        checkpoint?.let { anchor.put("checkpoint", it.toJson()) }
        val pointer = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("chainId", network.chainId)
            .put("generation", id)
        try {
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException("mkdirs ${directory.path}")
            inheritPeerCaches(network, previous, directory)
            writeAtomic(File(directory, ANCHOR), anchor.toString())
            writeAtomic(pointerFile(network), pointer.toString())
        } catch (e: IOException) {
            throw map(e)
        } catch (e: SecurityException) {
            throw map(e)
        }
        pruneRetired(network, keep = id)
        return MyotisGeneration(id, directory, origin, checkpoint)
    }

    /** The directory the pointer names now, or the chain directory without one. */
    private fun currentDirectory(network: MyotisNetwork): File {
        val id = readJson(pointerFile(network))?.opt("generation") as? String
        return if (id != null && isGenerationId(id)) generationDirectory(network, id) else chainDirectory(network)
    }

    /**
     * The engine's learned peer lists — addresses, not sync state — carry
     * over into a new generation, so a recovery doesn't start from an
     * empty peer pool (a cold pool is what makes the first minutes after
     * "ready" fail every read, myotis #465). Best effort.
     */
    private fun inheritPeerCaches(network: MyotisNetwork, from: File, into: File) {
        for (name in peerCacheNames(network)) {
            val source = File(from, name)
            val target = File(into, name)
            if (source == target || !source.isFile || target.exists()) continue
            runCatching { source.copyTo(target) }
        }
    }

    /**
     * Delete retired generations beyond the [KEEP_RETIRED] most recent.
     * One a repair backup points at is never deleted: Repair promises the
     * old data is kept. Best effort.
     */
    private fun pruneRetired(network: MyotisNetwork, keep: String) {
        val backedUp = backedUpGenerations(network)
        val retired = generationsDirectory(network).listFiles()
            ?.filter { it.isDirectory && it.name != keep && it.name !in backedUp && isGenerationId(it.name) }
            ?.sortedByDescending { File(it, ANCHOR).lastModified() }
            ?: return
        for (dir in retired.drop(KEEP_RETIRED)) runCatching { dir.deleteRecursively() }
    }

    /** Generations named by a `verified-sync-backup-*.json` repair left behind. */
    private fun backedUpGenerations(network: MyotisNetwork): Set<String> =
        chainDirectory(network).listFiles()
            ?.filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(".json") }
            ?.mapNotNull { (readJson(it)?.opt("generation") as? String)?.takeIf(::isGenerationId) }
            ?.toSet()
            ?: emptySet()

    private fun readJson(file: File): JSONObject? {
        if (!isRegular(file) || file.length() > MAX_RECORD_BYTES) return null
        return try {
            JSONObject(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.${newId()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("rename ${tmp.path}")
        } finally {
            tmp.delete()
        }
    }

    /** Presence judged on the directory entry itself, never following a link (as the engine does). */
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isRegular(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun storage() = MyotisCheckpointException(MyotisCheckpointError.Storage)

    /** A filesystem refusal (full, read-only, permissions) is [MyotisCheckpointError.StorageIO]. */
    private fun map(e: Exception) = MyotisCheckpointException(MyotisCheckpointError.StorageIO).also { it.initCause(e) }

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * The engine ABI that introduced the native anchor-marker contract
         * generations are stamped with. A constant, like desktop's and
         * iOS's — not the current engine ABI; bumping it would orphan every
         * verified generation on disk.
         */
        const val NATIVE_CHECKPOINT_API = 26

        const val MAX_RECORD_BYTES = 16L * 1024

        /** Retired generations kept beside the current one. */
        const val KEEP_RETIRED = 2

        private const val POINTER = "verified-sync.json"
        private const val GENERATIONS = "verified-sync"
        private const val ANCHOR = "anchor.json"
        private const val BACKUP_PREFIX = "verified-sync-backup-"

        /** Written into a generation the engine contradicted ([reject]). */
        private const val REJECTED = "rejected.json"

        /** The engine's marker for a caller-supplied anchor (`persistence_suffix`). */
        fun nativeMarkerName(network: MyotisNetwork) =
            if (network == MyotisNetwork.Mainnet) "sync-anchor.json" else "sync-anchor-${network.engineName}.json"

        /** `peers[-net].cache` (execution layer) and `cl-peers[-net].cache` (beacon side). */
        fun peerCacheNames(network: MyotisNetwork): List<String> {
            val suffix = if (network == MyotisNetwork.Mainnet) "" else "-${network.engineName}"
            return listOf("peers$suffix.cache", "cl-peers$suffix.cache")
        }

        private fun newId() = UUID.randomUUID().toString().lowercase()

        fun isGenerationId(id: String): Boolean =
            id.length == 36 && id == id.lowercase() && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
