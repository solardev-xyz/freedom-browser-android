package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import baby.freedom.mobile.browser.SwarmProvider.FeedRecord
import baby.freedom.mobile.wallet.Vault
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The sites' Swarm feed access and feed records (#120), per wallet —
 * the rest of desktop's `swarm-feeds.json` origin entry (`feedGranted`,
 * `feeds`); the identities themselves are
 * [baby.freedom.mobile.wallet.PublisherIdentityStore]'s. On this device
 * only (`noBackupFilesDir`), naming the vault it belongs to
 * ([Vault.identityTag]): a feed record names the identity that signs it,
 * which only means something under that wallet's seed, so another
 * wallet's file reads as empty — its sites must be granted feed access
 * again — and Remove wallet deletes it ([wipe]).
 *
 * Nothing here is secret: owners, topics and manifest references are
 * public on Swarm. Reads never throw (a missing, unreadable or other
 * wallet's file reads as nothing); writes throw [IOException], or
 * [IllegalStateException] when there's no wallet. A file that can't be
 * parsed is set aside (`*.corrupt*.json`) and started afresh: a lost
 * record costs only a new manifest, since a feed's topic and owner are
 * derived, not stored.
 */
class SwarmFeedStore internal constructor(
    private val file: File,
    private val vaultTag: () -> String?,
) {
    private class Site(val granted: Boolean, val feeds: Map<String, FeedRecord>)

    private val lock = Any()

    fun granted(origin: String): Boolean = synchronized(lock) { load()[origin]?.granted == true }

    fun feed(origin: String, name: String): FeedRecord? = synchronized(lock) { load()[origin]?.feeds?.get(name) }

    /** [origin]'s feeds, oldest first. */
    fun all(origin: String): List<FeedRecord> = synchronized(lock) {
        load()[origin]?.feeds?.values?.sortedBy { it.createdAt }.orEmpty()
    }

    /** Origins with feed access or feeds. */
    fun origins(): Set<String> = synchronized(lock) { load().keys }

    /** Gives [origin] feed access. */
    fun grant(origin: String) = synchronized(lock) {
        val sites = load()
        val site = sites[origin]
        if (site?.granted == true) return@synchronized
        save(sites + (origin to Site(true, site?.feeds.orEmpty())))
    }

    /** Takes [origin]'s feed access away (the user disconnected it); its feed records stay, as on desktop. */
    fun revoke(origin: String) = synchronized(lock) {
        val sites = load()
        val site = sites[origin] ?: return@synchronized
        if (!site.granted) return@synchronized
        save(sites + (origin to Site(false, site.feeds)))
    }

    /** Adds or replaces [record] among [origin]'s feeds. */
    fun put(origin: String, record: FeedRecord) = synchronized(lock) {
        val sites = load()
        val site = sites[origin]
        save(sites + (origin to Site(site?.granted == true, site?.feeds.orEmpty() + (record.name to record))))
    }

    /** Remove wallet: every site's feed access and records go with it. */
    fun wipe() = synchronized(lock) {
        file.delete()
        tmp().delete()
        file.parentFile?.listFiles()?.forEach { if (it.name.startsWith("${file.nameWithoutExtension}.corrupt")) it.delete() }
    }

    private fun load(): Map<String, Site> {
        val tag = vaultTag() ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        return try {
            val o = JSONObject(file.readText())
            if (o.optString("vault") != tag) return emptyMap()
            parse(o)
        } catch (e: IOException) {
            Log.w(TAG, "reading Swarm feeds failed: ${e.javaClass.simpleName}")
            emptyMap()
        } catch (e: JSONException) {
            setAside()
            emptyMap()
        } catch (e: RuntimeException) {
            setAside()
            emptyMap()
        } catch (e: StackOverflowError) {
            setAside()
            emptyMap()
        }
    }

    private fun parse(o: JSONObject): Map<String, Site> {
        require(o.getInt("version") == VERSION) { "unknown version" }
        val origins = o.getJSONObject("origins")
        return origins.keys().asSequence().associateWith { origin ->
            val entry = origins.getJSONObject(origin)
            val list = entry.getJSONObject("feeds")
            val feeds = list.keys().asSequence().associateWith { name ->
                val f = list.getJSONObject(name)
                FeedRecord(
                    name = name,
                    topic = f.getString("topic"),
                    owner = f.getString("owner"),
                    manifestReference = f.getString("manifestReference"),
                    identityId = f.getString("identityId"),
                    createdAt = f.optLong("createdAt"),
                    lastUpdated = f.optLong("lastUpdated", -1).takeIf { it >= 0 },
                    lastReference = f.optString("lastReference").takeIf { it.isNotEmpty() },
                )
            }
            Site(entry.optBoolean("feedGranted"), feeds)
        }
    }

    private fun save(sites: Map<String, Site>) {
        val tag = vaultTag() ?: throw IllegalStateException("there is no wallet")
        val origins = JSONObject()
        for ((origin, site) in sites) {
            val feeds = JSONObject()
            for ((name, f) in site.feeds) {
                feeds.put(
                    name,
                    JSONObject()
                        .put("topic", f.topic)
                        .put("owner", f.owner)
                        .put("manifestReference", f.manifestReference)
                        .put("identityId", f.identityId)
                        .put("createdAt", f.createdAt)
                        .apply {
                            f.lastUpdated?.let { put("lastUpdated", it) }
                            f.lastReference?.let { put("lastReference", it) }
                        },
                )
            }
            origins.put(origin, JSONObject().put("feedGranted", site.granted).put("feeds", feeds))
        }
        val text = JSONObject().put("version", VERSION).put("vault", tag).put("origins", origins).toString()
        file.parentFile?.mkdirs()
        val tmp = tmp()
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("couldn't write the Swarm feeds")
        }
    }

    private fun setAside() {
        Log.w(TAG, "Swarm feeds file can't be parsed; setting it aside")
        val dir = file.parentFile ?: return
        var n = 0
        var target: File
        do {
            target = File(dir, "${file.nameWithoutExtension}.corrupt${if (n == 0) "" else "-$n"}.json")
            n++
        } while (target.exists())
        if (!file.renameTo(target)) file.delete()
    }

    private fun tmp() = File(file.parentFile, "${file.name}.tmp")

    companion object {
        private const val TAG = "SwarmFeedStore"
        private const val VERSION = 1

        @Volatile
        private var instance: SwarmFeedStore? = null

        fun get(context: Context): SwarmFeedStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                SwarmFeedStore(File(app.noBackupFilesDir, "wallet/swarm-feeds.json"), Vault.get(app)::identityTag)
            }.also { instance = it }
        }
    }
}
