package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import baby.freedom.mobile.ens.Keccak256
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * A key a site can sign its Swarm feeds with (#119) — desktop's
 * publisher identities (`swarm/feed-store.js`), which iOS keys the same way:
 *
 *  - [Mode.APP_SCOPED]: a key of the site's own at [APP_SCOPED_PATH]
 *    (`m/44'/73406'/{index}'/0/0`), never funded, and not linkable by
 *    anyone else to the wallet's other accounts or to other sites. Each one gets the next unused index, so no
 *    two identities on this wallet ever share a key.
 *  - [Mode.ANT_WALLET]: the Swarm node's own account
 *    ([NodeIdentity.SWARM_PATH]), for a site that should publish as the
 *    node — and be seen as the same publisher everywhere it's used.
 *
 * Only this metadata is kept; every key is derived from the seed when
 * it's needed ([PublisherKeys]), so nothing here is secret.
 */
data class PublisherIdentity(
    val mode: Mode,
    /** The app-scoped key's index; null for [Mode.ANT_WALLET]. */
    val publisherKeyIndex: Int?,
    val label: String,
    val createdAt: Long,
) {
    /** Values as desktop's `swarm-feeds.json` writes them. */
    enum class Mode(val wire: String) {
        APP_SCOPED("app-scoped"),
        ANT_WALLET("bee-wallet"),
    }

    init {
        require((mode == Mode.APP_SCOPED) == (publisherKeyIndex != null)) { "only app-scoped identities have an index" }
        require(publisherKeyIndex == null || publisherKeyIndex >= 0) { "negative publisher key index" }
    }

    /** Desktop's identity id: `app-scoped:<index>` or `bee-wallet`. */
    val id: String get() = if (mode == Mode.APP_SCOPED) "app-scoped:$publisherKeyIndex" else ANT_WALLET_ID

    /** The BIP-44 path of this identity's secp256k1 key. */
    val derivationPath: String
        get() = if (mode == Mode.APP_SCOPED) appScopedPath(publisherKeyIndex!!) else NodeIdentity.SWARM_PATH

    /** "App-scoped" / "Ant wallet": what kind of key it is, under its label. */
    val kind: String get() = if (mode == Mode.APP_SCOPED) "App-scoped" else "Ant wallet"

    companion object {
        const val ANT_WALLET_ID = "bee-wallet"
        const val ANT_WALLET_LABEL = "Ant wallet identity"
        const val APP_SCOPED_PATH = "m/44'/73406'/{index}'/0/0"

        /** Longest label, in UTF-8 bytes — desktop's limit. */
        const val MAX_LABEL_BYTES = 80

        fun appScopedPath(index: Int): String = "m/44'/73406'/$index'/0/0"

        /** Desktop's fallback name for an app-scoped identity with no label of its own. */
        fun defaultAppScopedLabel(index: Int) = "App-scoped identity ${index + 1}"

        /** The node account as a choice, before any site has picked it. */
        fun antWallet(createdAt: Long = 0) = PublisherIdentity(Mode.ANT_WALLET, null, ANT_WALLET_LABEL, createdAt)

        /**
         * [raw] trimmed, as a label, or why it can't be one: empty, over
         * [MAX_LABEL_BYTES], or holding a control character (a line break
         * would split the one-line rows it's shown in) — see [isRefusedInLabel].
         */
        fun checkLabel(raw: String): Result<String> {
            val label = raw.trim()
            return when {
                label.isEmpty() -> Result.failure(IllegalArgumentException("Enter a name for this identity."))
                label.toByteArray(Charsets.UTF_8).size > MAX_LABEL_BYTES ->
                    Result.failure(IllegalArgumentException("Keep the name to $MAX_LABEL_BYTES bytes or fewer."))
                label.codePoints().anyMatch(::isRefusedInLabel) ->
                    Result.failure(IllegalArgumentException("The name can’t contain line breaks or control characters."))
                else -> Result.success(label)
            }
        }

        /**
         * Whether [cp] can't be in a label: a C0/C1 control (Cc), a line or
         * paragraph separator (U+2028/U+2029), or an invisible format
         * character (Cf) — bidi overrides and isolates would reorder the
         * one-line row around the name. The Cf characters emoji are built
         * from stay allowed: the zero-width joiner/non-joiner and the tag
         * characters of subdivision flags (U+E0020–U+E007F).
         */
        internal fun isRefusedInLabel(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            Character.FORMAT -> cp != 0x200C && cp != 0x200D && cp !in 0xE0020..0xE007F
            else -> false
        }
    }
}

/**
 * One site's publisher identities: the ones it has used or been given
 * ([identities], oldest first) and which of them signs its feeds now
 * ([activeId]). [origin] is the provider's origin key
 * ([baby.freedom.mobile.browser.providerOriginKey]).
 */
data class SitePublisher(
    val origin: String,
    val activeId: String,
    val identities: List<PublisherIdentity>,
    val addedAt: Long,
) {
    val active: PublisherIdentity get() = identities.first { it.id == activeId }

    /**
     * What the site can switch to: its app-scoped identities by index,
     * then the Ant wallet identity whether or not it has used it yet —
     * desktop's selector order.
     */
    val choices: List<PublisherIdentity>
        get() = identities.filter { it.mode == PublisherIdentity.Mode.APP_SCOPED }.sortedBy { it.publisherKeyIndex } +
            (identities.firstOrNull { it.mode == PublisherIdentity.Mode.ANT_WALLET } ?: PublisherIdentity.antWallet())
}

/**
 * The sites' publisher identities (#119), on this device only
 * (`noBackupFilesDir`, like the wallet). The file names the vault it
 * belongs to ([VaultRecord.identityTag]): a publisher key index only means
 * something under the seed it was allocated for, so another wallet's
 * entries are never shown or used, and Remove wallet wipes the file.
 *
 * Reads never throw: a missing or unreadable file reads as no sites. A
 * file that can't be parsed is moved aside (`*.corrupt*.json`) before
 * anything new is written, so its index allocation isn't lost for good —
 * starting again at index 0 would hand a new site another site's key.
 * Changes throw [IOException] when the file can't be read (or set aside)
 * or written — never starting afresh over one they couldn't read — and
 * [IllegalStateException] when there's no wallet.
 *
 * Allocation also never goes below [floor], a file holding only the
 * highest next index this device has ever written — no origins, labels
 * or vault tag. It outlives [wipe] and a change of wallet: the vault tag
 * is new for every vault, even one imported from the same phrase, so
 * without it Remove wallet and a re-import would start again at index 0
 * and give a new site the key an earlier site published with.
 */
class PublisherIdentityStore internal constructor(
    private val file: File,
    private val vaultTag: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val floor: File = File(file.parentFile, "publisher-key-index-floor"),
) {
    private class Data(val nextIndex: Int, val sites: Map<String, SitePublisher>)

    private val lock = Any()

    /** Every site with a publisher identity, most recently added first. */
    fun sites(): List<SitePublisher> = synchronized(lock) {
        load()?.sites?.values?.sortedByDescending { it.addedAt }.orEmpty()
    }

    /** [origin]'s identities, or null if it has none yet. */
    fun site(origin: String): SitePublisher? = synchronized(lock) { load()?.sites?.get(origin) }

    /**
     * [origin]'s identities, giving it a new app-scoped identity of its
     * own first if it has none — the default desktop gives a site the
     * first time it's asked about.
     */
    fun ensureSite(origin: String): SitePublisher = synchronized(lock) {
        val data = loadForWrite()
        data.sites[origin]?.let { return it }
        createAppScopedIn(data, origin, label = null)
    }

    /** Gives [origin] a new app-scoped identity named [label] and makes it the active one. */
    fun createAppScoped(origin: String, label: String): SitePublisher = synchronized(lock) {
        val checked = PublisherIdentity.checkLabel(label).getOrThrow()
        createAppScopedIn(loadForWrite(), origin, checked)
    }

    /**
     * Makes [identityId] the one [origin] signs its feeds with: one of its
     * own identities, or the Ant wallet identity (added on first use).
     */
    fun activate(origin: String, identityId: String): SitePublisher = synchronized(lock) {
        val data = loadForWrite()
        val site = data.sites[origin] ?: throw IllegalArgumentException("no publisher identities for this site")
        if (site.activeId == identityId) return site
        val identities = when {
            site.identities.any { it.id == identityId } -> site.identities
            identityId == PublisherIdentity.ANT_WALLET_ID -> site.identities + PublisherIdentity.antWallet(clock())
            else -> throw IllegalArgumentException("not one of this site's identities")
        }
        val updated = site.copy(activeId = identityId, identities = identities)
        save(Data(data.nextIndex, data.sites + (origin to updated)))
        updated
    }

    /**
     * Remove wallet: every site's publisher identities go with it, and so
     * do any unparseable copies set aside (they hold the same origins and
     * labels). Only [floor] stays, so a later wallet — maybe the same
     * phrase again — never reuses an index.
     */
    fun wipe() = synchronized(lock) {
        file.delete()
        tmp().delete()
        file.parentFile?.listFiles()?.forEach { if (isSetAside(it)) it.delete() }
    }

    private fun isSetAside(f: File): Boolean {
        val base = file.nameWithoutExtension
        return f.name == "$base.corrupt.json" || Regex(Regex.escape(base) + "\\.corrupt-\\d+\\.json").matches(f.name)
    }

    private fun createAppScopedIn(data: Data, origin: String, label: String?): SitePublisher {
        val now = clock()
        val index = data.nextIndex
        val identity = PublisherIdentity(
            PublisherIdentity.Mode.APP_SCOPED,
            index,
            label ?: PublisherIdentity.defaultAppScopedLabel(index),
            now,
        )
        val existing = data.sites[origin]
        val site = SitePublisher(
            origin = origin,
            activeId = identity.id,
            identities = existing?.identities.orEmpty() + identity,
            addedAt = existing?.addedAt ?: now,
        )
        save(Data(index + 1, data.sites + (origin to site)))
        return site
    }

    /** This vault's data, or null for none (no wallet, no file, another wallet's file, or unreadable). */
    private fun load(): Data? {
        val tag = vaultTag() ?: return null
        return try {
            read(tag)
        } catch (e: IOException) {
            Log.w(TAG, "reading publisher identities failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * This vault's data to change, or a fresh start when there's none yet.
     * Throws [IOException] if the file is there but can't be read (or set
     * aside): starting afresh over it would reuse its key indexes.
     */
    private fun loadForWrite(): Data {
        val tag = vaultTag() ?: throw IllegalStateException("there is no wallet")
        val stored = read(tag)
        // An unparseable floor is only safe to overlook when this vault's
        // own file still holds a counter (the next save rewrites the floor
        // from it). With no file — straight after Remove wallet — it's the
        // only record of the indexes already handed out, and 0 could be one:
        // skip well past anything it could have held instead (recoverFloor).
        val least = readFloor() ?: if (stored != null) 0 else recoverFloor()
        val data = stored ?: Data(0, emptyMap())
        return if (data.nextIndex >= least) data else Data(least, data.sites)
    }

    /**
     * The highest next index ever written on this device, 0 if none, or
     * null if the file doesn't hold a number (it never should). Throws
     * [IOException] if the file is there but can't be read.
     */
    private fun readFloor(): Int? {
        if (!floor.exists()) return 0
        val text = floor.readText().trim()
        return text.toIntOrNull()?.takeIf { it >= 0 } ?: run {
            Log.w(TAG, "publisher key index floor can't be parsed")
            null
        }
    }

    /**
     * A new floor over one that can't be parsed, with no file of this
     * vault's to rebuild it from: the lost number could have been
     * anything handed out so far, so allocation restarts a whole
     * [RECOVERY_STRIDE] above the last restart — indexes are handed out
     * one per new identity, so no device gets through a stride. The
     * unparseable floor is kept as `*.corrupt*` (wipe leaves those too),
     * and their count says how many strides are already used. The new
     * floor is written here, before anything is allocated from it, so a
     * failed save can't leave no floor (which reads as 0) behind.
     * Throws [IOException] if that can't be done, or every stride is
     * used up.
     */
    private fun recoverFloor(): Int {
        val dir = floor.parentFile ?: throw IOException("no directory")
        var n = 0
        var target: File
        do {
            target = File(dir, "${floor.name}.corrupt${if (n == 0) "" else "-$n"}")
            n++
        } while (target.exists())
        // n is now how many restarts there have been, this one included.
        if (n.toLong() * RECOVERY_STRIDE > Int.MAX_VALUE) {
            throw IOException("the publisher key index floor was lost too many times to restart it")
        }
        Log.w(TAG, "publisher key index floor can't be parsed and there's nothing to rebuild it from; restarting at stride $n")
        // Copied, not moved: the floor is only ever replaced in one step.
        floor.copyTo(target)
        val next = n * RECOVERY_STRIDE
        writeFloor(next)
        return next
    }

    /**
     * Raises [floor] to [next], written before the file that uses it; an
     * unparseable floor is replaced ([loadForWrite] only gets here then
     * with [next] from this vault's own counter).
     */
    private fun raiseFloor(next: Int) {
        val current = readFloor()
        if (current != null && next <= current) return
        writeFloor(next)
    }

    private fun writeFloor(next: Int) {
        val tmp = File(floor.parentFile, "${floor.name}.tmp")
        tmp.writeText(next.toString())
        if (!tmp.renameTo(floor)) {
            tmp.delete()
            throw IOException("couldn't write the publisher key index floor")
        }
    }

    /** Null if there's no file, it's another wallet's, or it couldn't be parsed (and was set aside). */
    private fun read(tag: String): Data? {
        if (!file.exists()) return null
        val text = file.readText()
        val o = try {
            JSONObject(text)
        } catch (e: JSONException) {
            setAside()
            return null
        } catch (e: StackOverflowError) {
            setAside()
            return null
        }
        if (o.optString("vault") != tag) return null
        return try {
            parse(o)
        } catch (e: RuntimeException) {
            setAside()
            null
        }
    }

    private fun parse(o: JSONObject): Data {
        if (o.getInt("version") != VERSION) throw IllegalArgumentException("unknown version")
        var next = o.getInt("nextPublisherKeyIndex")
        require(next >= 0) { "negative next index" }
        val origins = o.getJSONObject("origins")
        val sites = mutableMapOf<String, SitePublisher>()
        for (origin in origins.keys()) {
            val entry = origins.getJSONObject(origin)
            val list = entry.getJSONObject("identities")
            val identities = list.keys().asSequence().map { id ->
                val i = list.getJSONObject(id)
                val mode = PublisherIdentity.Mode.entries.first { it.wire == i.getString("mode") }
                val index = if (mode == PublisherIdentity.Mode.APP_SCOPED) i.getInt("publisherKeyIndex") else null
                PublisherIdentity(mode, index, i.getString("label"), i.optLong("createdAt"))
                    .also { require(it.id == id) { "identity id doesn't match its key" } }
            }.sortedBy { it.createdAt }.toList()
            val active = entry.getString("activeIdentityId")
            require(identities.any { it.id == active }) { "active identity missing" }
            // Never hand out an index that's already in use, whatever the counter says.
            identities.mapNotNull { it.publisherKeyIndex }.maxOrNull()?.let { next = maxOf(next, it + 1) }
            sites[origin] = SitePublisher(origin, active, identities, entry.optLong("addedAt"))
        }
        return Data(next, sites)
    }

    private fun save(data: Data) {
        val tag = vaultTag() ?: throw IllegalStateException("there is no wallet")
        val origins = JSONObject()
        for ((origin, site) in data.sites) {
            val identities = JSONObject()
            for (i in site.identities) {
                identities.put(
                    i.id,
                    JSONObject()
                        .put("id", i.id)
                        .put("mode", i.mode.wire)
                        .put("publisherKeyIndex", i.publisherKeyIndex ?: JSONObject.NULL)
                        .put("label", i.label)
                        .put("createdAt", i.createdAt),
                )
            }
            origins.put(
                origin,
                JSONObject()
                    .put("activeIdentityId", site.activeId)
                    .put("identities", identities)
                    .put("addedAt", site.addedAt),
            )
        }
        val text = JSONObject()
            .put("version", VERSION)
            .put("vault", tag)
            .put("nextPublisherKeyIndex", data.nextIndex)
            .put("origins", origins)
            .toString()
        file.parentFile?.mkdirs()
        raiseFloor(data.nextIndex)
        val tmp = tmp()
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("couldn't write the publisher identities")
        }
    }

    /** Moves an unparseable file out of the way, keeping it for recovery; [IOException] if it can't be. */
    private fun setAside() {
        Log.w(TAG, "publisher identities file can't be parsed; setting it aside")
        val dir = file.parentFile ?: throw IOException("no directory")
        var n = 0
        var target: File
        do {
            target = File(dir, "${file.nameWithoutExtension}.corrupt${if (n == 0) "" else "-$n"}.json")
            n++
        } while (target.exists())
        if (!file.renameTo(target)) throw IOException("couldn't set the unreadable publisher identities aside")
    }

    private fun tmp() = File(file.parentFile, "${file.name}.tmp")

    companion object {
        private const val TAG = "PublisherIdentities"
        private const val VERSION = 1

        /** How far past a lost floor allocation restarts ([recoverFloor]). */
        internal const val RECOVERY_STRIDE = 1 shl 24

        @Volatile
        private var instance: PublisherIdentityStore? = null

        /** The store on this device, for the wallet on it. */
        fun get(context: Context): PublisherIdentityStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val vault = Vault.get(app)
                PublisherIdentityStore(File(app.noBackupFilesDir, "wallet/publisher-identities.json"), vault::identityTag)
            }.also { instance = it }
        }
    }
}

/**
 * The keys behind publisher identities, derived from the wallet's seed
 * when needed and never kept: the owner addresses the identities page
 * shows, and the signing key a feed write (#120) will use.
 */
object PublisherKeys {
    /**
     * The EIP-55 owner address of each of [identities], by [PublisherIdentity.id].
     * Throws [VaultLockedException] if the wallet is locked.
     */
    fun owners(vault: Vault, identities: List<PublisherIdentity>): Map<String, String> =
        vault.withSeed { seed -> owners(seed, identities) }

    internal fun owners(seed: ByteArray, identities: List<PublisherIdentity>): Map<String, String> =
        identities.associate { identity ->
            val key = HdKeys.secp256k1(seed, identity.derivationPath)
            try {
                identity.id to address(key)
            } finally {
                key.fill(0)
            }
        }

    /**
     * The 32-byte secp256k1 key [identity] signs with. Secret: the caller
     * zeroes it after the one signature, and never logs or stores it.
     */
    fun signingKey(vault: Vault, identity: PublisherIdentity): ByteArray =
        vault.withSeed { seed -> HdKeys.secp256k1(seed, identity.derivationPath) }

    internal fun address(privateKey: ByteArray): String {
        val pub = Secp256k1Keys.publicKeyUncompressed(privateKey)
        return NodeIdentity.checksum(Keccak256.digest(pub).copyOfRange(12, 32))
    }
}
