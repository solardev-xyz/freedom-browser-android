package baby.freedom.mobile.browser

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Swarm-hosted permission manifests (#122): desktop's
 * `permission-manifests.js`, to its interoperability profile
 * (`research/swarm-app-permission-manifest.md`, version 1).
 *
 * A Swarm app publishes `freedom-manifest.json` at the root of its bzz
 * origin, naming the `window.swarm` capabilities it wants and why. The
 * browser asks for them together once — Allow all, Use individual
 * approvals, or Don't allow — remembers which grants that decision made
 * (so a later redeploy that drops a capability takes back only what the
 * manifest gave, never what the user granted by hand), and checks the
 * manifest again on every committed navigation before stored manifest
 * authority is used.
 *
 * Everything here runs in the app: the page never sends manifest bytes.
 */

/** The version 1 capability rows, in display order. */
enum class ManifestCapability(val wire: String) {
    Publish("publish"),
    Feeds("feeds"),
    Signing("signing"),
    Messaging("messaging"),
    ;

    companion object {
        fun of(wire: String): ManifestCapability? = entries.firstOrNull { it.wire == wire }
    }
}

/** The existing grants a capability row stands for (its projection). */
enum class ManifestProjection(val wire: String) {
    Connection("connection"),
    Identity("identity"),
    FeedGrant("feedGrant"),
    AutoPublish("autoApprove.publish"),
    AutoFeeds("autoApprove.feeds"),
    AutoSigning("autoApprove.signing"),
    ;

    companion object {
        fun of(wire: String): ManifestProjection? = entries.firstOrNull { it.wire == wire }
    }
}

/** A validated `freedom-manifest/1`. [capabilities] maps each requested row to the app's `why`, in [ManifestCapability] order. */
data class SwarmAppManifest(
    val name: String,
    val description: String,
    val capabilities: Map<ManifestCapability, String>,
) {
    val schema: String get() = SwarmManifestFormat.SCHEMA
}

/** A manifest that must not be used, and why (a developer diagnostic, never shown as authority). */
class InvalidManifestException(message: String) : Exception(message)

/** What looking for an app's manifest found (profile §2.3). */
sealed interface ManifestDiscovery {
    data class Found(val manifest: SwarmAppManifest, val rawHash: String, val fingerprint: String) : ManifestDiscovery

    /** A definitive 404: the app has no manifest. */
    data object Absent : ManifestDiscovery

    /** There was something, but it isn't a usable manifest (a 4xx, too large, not strict UTF-8 JSON, off-schema). */
    data class Invalid(val reason: String) : ManifestDiscovery

    /** The node, network or name resolution failed for now: neither absence nor a new answer. */
    data class Unresolved(val reason: String) : ManifestDiscovery

    /** The page isn't served over bzz (a web site, IPFS content, or a name that now points off Swarm). */
    data object Unsupported : ManifestDiscovery
}

/** Parsing and validating the version 1 manifest: strict, whole-file, before any consent model exists. */
object SwarmManifestFormat {
    const val FILE = "freedom-manifest.json"
    const val SCHEMA = "freedom-manifest/1"
    const val MAX_BYTES = 8 * 1024
    private const val MAX_NAME = 32
    private const val MAX_DESCRIPTION = 160
    private const val MAX_WHY = 140

    /** [bytes] as a validated manifest; throws [InvalidManifestException] for anything else. */
    fun parse(bytes: ByteArray): SwarmAppManifest {
        if (bytes.size > MAX_BYTES) throw InvalidManifestException("manifest exceeds 8 KiB")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw InvalidManifestException("manifest is not valid UTF-8")
        }
        return validate(StrictJson.parse(text))
    }

    /** The whole of profile §3: unknown fields, groups or rows invalidate the manifest, rather than being skipped. */
    fun validate(value: Any?): SwarmAppManifest {
        val root = exactKeys(value, setOf("schema", "name", "description", "capabilities"), "manifest")
        if (root["schema"] != SCHEMA) throw InvalidManifestException("unsupported manifest schema")
        val name = text(root["name"], MAX_NAME, "name")
        if (name.isBlank()) throw InvalidManifestException("invalid manifest name")
        val description = if ("description" in root) text(root["description"], MAX_DESCRIPTION, "description") else ""
        if ("capabilities" !in root) throw InvalidManifestException("manifest must have capabilities")
        val groups = exactKeys(root["capabilities"], setOf("swarm"), "capabilities")
        if ("swarm" !in groups) throw InvalidManifestException("capabilities must have swarm")
        val swarm = exactKeys(groups["swarm"], ManifestCapability.entries.map { it.wire }.toSet(), "capabilities.swarm")
        if (swarm.isEmpty()) throw InvalidManifestException("capabilities.swarm must not be empty")
        val capabilities = LinkedHashMap<ManifestCapability, String>()
        for (capability in ManifestCapability.entries) {
            val row = swarm[capability.wire] ?: continue
            val fields = exactKeys(row, setOf("why"), "capability ${capability.wire}")
            val why = text(fields["why"], MAX_WHY, "reason for ${capability.wire}")
            if (why.isBlank()) throw InvalidManifestException("invalid reason for ${capability.wire}")
            capabilities[capability] = why
        }
        return SwarmAppManifest(name, description, capabilities)
    }

    /** Schema plus sorted capability keys: what authority is bound to — never the wording (profile §5.2). */
    fun fingerprint(manifest: SwarmAppManifest): String {
        val keys = manifest.capabilities.keys.map { it.wire }.sorted()
        val canonical = "{\"schema\":\"${manifest.schema}\",\"capabilities\":[" +
            keys.joinToString(",") { "\"$it\"" } + "]}"
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun exactKeys(value: Any?, allowed: Set<String>, label: String): Map<String, Any?> {
        if (value !is Map<*, *>) throw InvalidManifestException("$label must be an object")
        for (key in value.keys) {
            if (key !in allowed) throw InvalidManifestException("$label contains unknown field: $key")
        }
        return value as Map<String, Any?>
    }

    private fun text(value: Any?, max: Int, label: String): String {
        if (value !is String || unsafeText(value, max)) throw InvalidManifestException("invalid manifest $label")
        return value
    }

    /**
     * Displayed strings are attacker-controlled: over [max] code points,
     * or holding a C0/C1 control, a line/paragraph separator, a bidi
     * embedding/override/isolate control, or a lone surrogate, they're
     * refused rather than stripped (profile §3).
     */
    internal fun unsafeText(value: String, max: Int): Boolean {
        var i = 0
        var count = 0
        while (i < value.length) {
            val c = value[i]
            val cp: Int
            if (Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                cp = Character.toCodePoint(c, value[i + 1])
                i += 2
            } else {
                if (Character.isSurrogate(c)) return true
                cp = c.code
                i++
            }
            count++
            if (count > max) return true
            if (cp <= 0x1f || cp in 0x7f..0x9f || cp in 0x202a..0x202e || cp == 0x2028 || cp == 0x2029 || cp in 0x2066..0x2069) {
                return true
            }
        }
        return false
    }
}

/**
 * RFC 8259 JSON, strictly: no comments, single quotes, unquoted keys or
 * trailing commas (all of which `org.json` lets through), no duplicate
 * keys, and a depth cap so a nested body can't exhaust the stack.
 * Objects come back as maps, arrays as lists, numbers as [Double].
 */
internal object StrictJson {
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val value = p.value(0)
        p.ws()
        if (p.i != text.length) p.fail("trailing data")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(what: String): Nothing = throw InvalidManifestException("manifest is not valid JSON: $what")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected character")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val key = str()
                if (key in out) fail("duplicate key")
                ws()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                ws()
                out[key] = value(depth + 1)
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                ws()
                out += value(depth + 1)
                ws()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c.code < 0x20 -> fail("control character in string")
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> b.append('"')
                            '\\' -> b.append('\\')
                            '/' -> b.append('/')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
                                b.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i].isAsciiDigit()) i++
            } else {
                fail("unexpected character")
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("bad number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("bad number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            return s.substring(start, i).toDouble()
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/**
 * The authority for manifest decisions (desktop's main-process
 * `permission-manifests.js`): what each origin's manifest last said,
 * which rows the user acknowledged (as managed or individual), which
 * grants a manifest made and so may take back ([Record.managed]), and
 * which the user has since taken over ([Record.detached]).
 *
 * Every mutation for one origin — a check, a decision, "ask each time",
 * a disconnect — runs under that origin's lock, so a stale sheet can't
 * overwrite newer state; a decision is bound to the pending consent it
 * answers, which is refused once expired, replaced, or out of date
 * against the record's revision or observed manifest. A decision that
 * touches the grant stores is journaled first ([Journal]) and replayed
 * after a crash, so a half-applied manifest grant is never mistaken for
 * a user-owned one.
 */
class SwarmManifests(
    private val store: Storage,
    private val projections: Projections,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Where the state lives: [SwarmManifestFile] in the app. [write] throws [IOException]. */
    interface Storage {
        fun read(): String?
        fun write(text: String)
    }

    /**
     * The grant stores a projection lands in. [set] throws [IOException]
     * when the change couldn't be saved. A projection that isn't
     * [available] (feed access and a publisher identity need a wallet)
     * is neither claimed nor applied: it's left to the ordinary sheet,
     * which sets a wallet up.
     */
    interface Projections {
        fun available(projection: ManifestProjection): Boolean
        suspend fun enabled(origin: String, projection: ManifestProjection): Boolean
        suspend fun set(origin: String, projection: ManifestProjection, on: Boolean)
    }

    class Ack(val decision: String, val source: String, val whyShown: String?, val decidedAt: Long)

    class Receipt(
        val decidedAt: Long,
        val outcome: String,
        val originShown: String,
        val nameShown: String,
        val descriptionShown: String,
        val rows: List<Pair<ManifestCapability, String>>,
        val rawHash: String,
    )

    class Observed(val fingerprint: String, val rawHash: String, val capabilities: Map<ManifestCapability, String>, val checkedAt: Long)

    /** One tracked origin. Mutable; cloned (through JSON) before any change that may not commit. */
    class Record(
        var revision: Int = 0,
        val acknowledged: MutableMap<ManifestCapability, Ack> = LinkedHashMap(),
        val managed: MutableMap<ManifestProjection, MutableList<ManifestCapability>> = LinkedHashMap(),
        val detached: MutableSet<ManifestProjection> = LinkedHashSet(),
        var observed: Observed? = null,
        var appName: String = "",
        var appDescription: String = "",
        val receipts: MutableList<Receipt> = ArrayList(),
    )

    /** What the sheet shows: [origin] first, the app's own words second, and client-owned labels for each row. */
    data class Consent(
        val origin: String,
        val name: String,
        val description: String,
        val rows: List<Pair<ManifestCapability, String>>,
        val removed: List<ManifestCapability>,
        val createsIdentity: Boolean,
        val preservedIdentity: Boolean,
        val needsWallet: Boolean,
        val isUpdate: Boolean,
    )

    sealed interface Check {
        /** No manifest authority is involved: the ordinary per-action flow applies. */
        data object Legacy : Check

        /** The manifest is fresh and nothing new needs deciding. */
        data object Ready : Check

        /** A tracked origin's manifest couldn't be checked for now; its stored authority must wait. */
        data class Unresolved(val retryAt: Long) : Check

        /** New rows need a decision: put [consent] to the user and [decide] with [token]. */
        data class Consent(val token: String, val consent: SwarmManifests.Consent) : Check
    }

    enum class Outcome { AllowAll, Individual, Deny }

    private class PendingConsent(
        val origin: String,
        val manifest: SwarmAppManifest,
        val fingerprint: String,
        val rawHash: String,
        val baseRevision: Int,
        val firstContact: Boolean,
        val changed: List<ManifestCapability>,
        var expiresAt: Long,
    )

    private class Backoff(val failures: Int, val retryAt: Long)

    private var records: MutableMap<String, Record>? = null
    private var pendingJournal: JSONObject? = null
    private val stateLock = Any()
    private val originLocks = HashMap<String, Mutex>()
    private val tokens = LinkedHashMap<String, PendingConsent>()
    private val completed = LinkedHashMap<String, Boolean>()
    private val backoffs = HashMap<String, Backoff>()

    private fun lockFor(origin: String): Mutex = synchronized(originLocks) { originLocks.getOrPut(origin) { Mutex() } }

    /**
     * Check [origin]'s manifest for its committed page. [eager] (an
     * explicit `requestAccess`) looks even for an origin with no manifest
     * record yet; otherwise an untracked origin stays on the ordinary
     * flow without a fetch. [discover] fetches and classifies the
     * manifest; it runs under the origin's lock.
     */
    suspend fun check(origin: String, eager: Boolean, discover: suspend () -> ManifestDiscovery): Check =
        lockFor(origin).withLock {
            recoverPending()
            val existing = records()[origin]
            if (existing == null && !eager) return@withLock Check.Legacy
            val firstContact = existing == null && !projections.enabled(origin, ManifestProjection.Connection)

            val now = clock()
            val backoff = synchronized(backoffs) { backoffs[origin] }
            // A wall-clock step back can't stretch the wait past its own length.
            if (backoff != null && backoff.retryAt - now in 1..BACKOFF_MS.last()) {
                return@withLock if (existing != null) Check.Unresolved(backoff.retryAt) else Check.Legacy
            }

            val found = discover()
            if (found is ManifestDiscovery.Unresolved) {
                val failures = (backoff?.failures ?: 0) + 1
                val retryAt = clock() + BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.size - 1)]
                synchronized(backoffs) { backoffs[origin] = Backoff(failures, retryAt) }
                return@withLock if (existing != null) Check.Unresolved(retryAt) else Check.Legacy
            }
            synchronized(backoffs) { backoffs.remove(origin) }
            if (found !is ManifestDiscovery.Found) {
                // Absent, invalid, or no longer on bzz: an empty capability
                // set — only what a manifest granted goes, and tracking with it.
                if (found is ManifestDiscovery.Invalid) Log.w(TAG, "invalid manifest for $origin: ${found.reason}")
                if (existing != null) prune(origin, existing)
                return@withLock Check.Legacy
            }

            val manifest = found.manifest
            val next = manifest.capabilities.keys
            val record = existing?.let(::copyOf) ?: Record()
            val removed = record.acknowledged.keys.filter { it !in next }
            val additions = next.filter { it !in record.acknowledged }
            val removals = removeOwners(record, removed)
            for (capability in removed) record.acknowledged.remove(capability)
            record.observed = Observed(found.fingerprint, found.rawHash, manifest.capabilities, clock())
            record.appName = manifest.name
            record.appDescription = manifest.description
            // A row whose whole projection the user already has by hand is
            // acknowledged as theirs, without a sheet and without taking it over.
            val satisfied = additions.filter { capability -> projectionOf(capability).all { projections.enabled(origin, it) } }
            for (capability in satisfied) {
                record.acknowledged[capability] = Ack(INDIVIDUAL, "existing-grant", null, clock())
            }
            val changed = additions.filter { it !in satisfied }
            if (removals.isNotEmpty() || removed.isNotEmpty() || satisfied.isNotEmpty()) record.revision++
            transaction(origin, record, removals)

            if (changed.isEmpty()) return@withLock Check.Ready
            val pending = PendingConsent(
                origin = origin,
                manifest = manifest,
                fingerprint = found.fingerprint,
                rawHash = found.rawHash,
                baseRevision = record.revision,
                firstContact = firstContact,
                changed = changed,
                expiresAt = clock() + TOKEN_TTL_MS,
            )
            val token = synchronized(tokens) {
                // Another tab of the same app checking the same state shares
                // this consent: the second answer replays the first.
                val shared = outstanding(pending)
                if (shared != null) {
                    tokens[shared]?.expiresAt = pending.expiresAt
                    shared
                } else {
                    newToken().also { tokens[it] = pending }
                }
            }
            Check.Consent(token, consentFor(origin, existing != null, manifest, changed, removed))
        }

    /**
     * The user's answer to the consent [token] stands for: whether the
     * request that raised it may go on. Throws [IllegalStateException]
     * for an unknown, expired or stale consent (nothing is granted), and
     * [IOException] if the decision couldn't be saved.
     */
    suspend fun decide(token: String, outcome: Outcome): Boolean {
        synchronized(tokens) { completed[token] }?.let { return it }
        val origin = synchronized(tokens) { tokens[token]?.origin } ?: throw IllegalStateException("Manifest consent expired")
        return lockFor(origin).withLock {
            recoverPending()
            synchronized(tokens) { completed[token] }?.let { return@withLock it }
            val pending = synchronized(tokens) { tokens[token] } ?: throw IllegalStateException("Manifest consent expired")
            val now = clock()
            if (expired(pending, now)) {
                synchronized(tokens) { tokens.remove(token) }
                throw IllegalStateException("Manifest consent expired")
            }
            val current = records()[origin]
            if (current?.observed?.fingerprint != pending.fingerprint || current.revision != pending.baseRevision) {
                synchronized(tokens) { tokens.remove(token) }
                throw IllegalStateException("Manifest consent is stale")
            }
            val allowed = outcome != Outcome.Deny
            if (allowed) {
                val record = copyOf(current)
                val operations = ArrayList<Pair<ManifestProjection, Boolean>>()
                if (outcome == Outcome.Individual) {
                    // Individual approvals still connect the app: the base
                    // connection is then the user's, never the manifest's.
                    record.detached += ManifestProjection.Connection
                    record.managed.remove(ManifestProjection.Connection)
                    if (!projections.enabled(origin, ManifestProjection.Connection)) {
                        operations += ManifestProjection.Connection to true
                    }
                }
                for (capability in pending.changed) {
                    val why = pending.manifest.capabilities.getValue(capability)
                    record.acknowledged[capability] = Ack(if (outcome == Outcome.AllowAll) MANAGED else INDIVIDUAL, "sheet", why, now)
                    if (outcome == Outcome.AllowAll) {
                        operations += addManaged(origin, record, capability)
                    } else {
                        operations += removeOwners(record, listOf(capability))
                    }
                }
                record.receipts += Receipt(
                    decidedAt = now,
                    outcome = if (outcome == Outcome.AllowAll) MANAGED else INDIVIDUAL,
                    originShown = origin,
                    nameShown = pending.manifest.name,
                    descriptionShown = pending.manifest.description,
                    rows = pending.changed.map { it to pending.manifest.capabilities.getValue(it) },
                    rawHash = pending.rawHash,
                )
                while (record.receipts.size > MAX_RECEIPTS) record.receipts.removeAt(0)
                record.revision = pending.baseRevision + 1
                transaction(origin, record, operations)
            } else if (pending.firstContact) {
                // Declining on first contact leaves nothing behind.
                transaction(origin, null, emptyList())
            }
            synchronized(tokens) {
                tokens.remove(token)
                completed[token] = allowed
                while (completed.size > MAX_COMPLETED) completed.remove(completed.keys.first())
            }
            allowed
        }
    }

    /** [origin]'s record, if it's tracked: for the connected-sites list. */
    suspend fun record(origin: String): Record? = lockFor(origin).withLock {
        recoverPending()
        records()[origin]?.let(::copyOf)
    }

    /** Every tracked origin's rows the manifest manages, for the connected-sites list. */
    fun managedRows(): Map<String, List<ManifestCapability>> = synchronized(stateLock) {
        runCatching { records() }.getOrDefault(emptyMap()).mapValues { (_, r) ->
            r.acknowledged.filterValues { it.decision == MANAGED }.keys.toList()
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * The user chose "Ask each time" for [origin]: every managed row
     * becomes individual, and what the manifest granted for it goes —
     * except the base connection, which becomes the user's. False only
     * if the change couldn't be saved ([IOException] from the stores).
     */
    suspend fun useIndividual(origin: String): Boolean = lockFor(origin).withLock {
        recoverPending()
        // Nothing managed (any more): already asking each time.
        val record = records()[origin]?.let(::copyOf) ?: return@withLock true
        val rows = record.acknowledged.filterValues { it.decision == MANAGED }.keys.toList()
        if (rows.isEmpty()) return@withLock true
        record.detached += ManifestProjection.Connection
        record.managed.remove(ManifestProjection.Connection)
        val operations = removeOwners(record, rows)
        for (capability in rows) {
            val ack = record.acknowledged.getValue(capability)
            record.acknowledged[capability] = Ack(INDIVIDUAL, ack.source, ack.whyShown, clock())
        }
        record.revision++
        transaction(origin, record, operations)
        true
    }

    /**
     * The user disconnected [origin] (the caller revoked its grants):
     * manifest tracking goes too, so a later visit starts afresh.
     * Identity records stay.
     */
    suspend fun forget(origin: String) = lockFor(origin).withLock {
        recoverPending()
        if (records()[origin] != null) transaction(origin, null, emptyList())
        synchronized(tokens) { tokens.values.removeAll { it.origin == origin } }
    }

    // -----------------------------------------------------------------

    private suspend fun prune(origin: String, record: Record) {
        val copy = copyOf(record)
        transaction(origin, null, removeOwners(copy, ManifestCapability.entries))
    }

    /**
     * Takes [capabilities] off every grant they own; a grant left with
     * no owner is turned off — last the connection, and never an
     * identity (removal keeps it, profile §4).
     */
    private fun removeOwners(record: Record, capabilities: Collection<ManifestCapability>): List<Pair<ManifestProjection, Boolean>> {
        val operations = ArrayList<Pair<ManifestProjection, Boolean>>()
        for (projection in record.managed.keys.toList()) {
            val owners = record.managed.getValue(projection)
            owners.removeAll(capabilities.toSet())
            if (owners.isEmpty()) {
                record.managed.remove(projection)
                if (projection != ManifestProjection.Identity) operations += projection to false
            }
        }
        return operations.sortedBy { it.first == ManifestProjection.Connection }
    }

    /** [capability]'s grants that are off and not the user's become the manifest's, and are turned on. */
    private suspend fun addManaged(origin: String, record: Record, capability: ManifestCapability): List<Pair<ManifestProjection, Boolean>> {
        val operations = ArrayList<Pair<ManifestProjection, Boolean>>()
        for (projection in projectionOf(capability)) {
            if (projection in record.detached) continue
            val owners = record.managed[projection]
            if (owners != null && owners.isNotEmpty()) {
                if (capability !in owners) owners += capability
                continue
            }
            if (!projections.enabled(origin, projection)) {
                record.managed[projection] = mutableListOf(capability)
                operations += projection to true
            }
        }
        return operations
    }

    /** [capability]'s projection, less what can't be applied now ([Projections.available]). */
    private fun projectionOf(capability: ManifestCapability): List<ManifestProjection> =
        PROJECTIONS.getValue(capability).filter { projections.available(it) }

    private suspend fun consentFor(
        origin: String,
        isUpdate: Boolean,
        manifest: SwarmAppManifest,
        changed: List<ManifestCapability>,
        removed: List<ManifestCapability>,
    ): Consent {
        val signs = changed.any { it == ManifestCapability.Feeds || it == ManifestCapability.Signing }
        val canHaveIdentity = projections.available(ManifestProjection.Identity)
        val hasIdentity = canHaveIdentity && projections.enabled(origin, ManifestProjection.Identity)
        return Consent(
            origin = origin,
            name = manifest.name,
            description = manifest.description,
            rows = changed.map { it to manifest.capabilities.getValue(it) },
            removed = removed,
            createsIdentity = signs && canHaveIdentity && !hasIdentity,
            preservedIdentity = signs && hasIdentity,
            needsWallet = signs && !canHaveIdentity,
            isUpdate = isUpdate,
        )
    }

    private fun outstanding(candidate: PendingConsent): String? {
        val now = clock()
        tokens.entries.removeAll { expired(it.value, now) }
        return tokens.entries.firstOrNull { (_, p) ->
            p.origin == candidate.origin && p.fingerprint == candidate.fingerprint &&
                p.baseRevision == candidate.baseRevision && p.changed == candidate.changed
        }?.key
    }

    /** Past its deadline — or with a deadline further off than a fresh one's, after the clock stepped back. */
    private fun expired(pending: PendingConsent, now: Long) = pending.expiresAt - now !in 0..TOKEN_TTL_MS

    private fun newToken(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // -----------------------------------------------------------------
    // Persistence, with a journal for the grant stores
    // -----------------------------------------------------------------

    /**
     * Writes [record] (null: stop tracking [origin]) after applying
     * [operations] to the grant stores. The intent is saved first, so a
     * crash part-way through is finished by [recoverPending] (the
     * operations are idempotent) rather than leaving grants the record
     * doesn't know it owns.
     */
    private suspend fun transaction(origin: String, record: Record?, operations: List<Pair<ManifestProjection, Boolean>>) {
        if (operations.isNotEmpty()) {
            val journal = JSONObject()
                .put("origin", origin)
                .put("record", record?.let(::recordJson) ?: JSONObject.NULL)
                .put("operations", JSONArray(operations.map { JSONObject().put("projection", it.first.wire).put("enabled", it.second) }))
            synchronized(stateLock) { pendingJournal = journal }
            try {
                save()
            } catch (e: IOException) {
                synchronized(stateLock) { pendingJournal = null }
                throw e
            }
            for ((projection, on) in operations) projections.set(origin, projection, on)
        }
        synchronized(stateLock) {
            val all = records()
            if (record != null) all[origin] = record else all.remove(origin)
            pendingJournal = null
        }
        save()
    }

    /** Finishes a transaction a crash interrupted: its operations again, then its record. */
    private suspend fun recoverPending() {
        val journal = synchronized(stateLock) {
            records()
            pendingJournal
        } ?: return
        try {
            val origin = journal.getString("origin")
            val ops = journal.getJSONArray("operations")
            for (k in 0 until ops.length()) {
                val op = ops.getJSONObject(k)
                val projection = ManifestProjection.of(op.getString("projection")) ?: continue
                projections.set(origin, projection, op.getBoolean("enabled"))
            }
            val record = journal.optJSONObject("record")?.let(::recordFrom)
            synchronized(stateLock) {
                val all = records()
                if (record != null) all[origin] = record else all.remove(origin)
                pendingJournal = null
            }
        } catch (e: JSONException) {
            Log.w(TAG, "dropping an unreadable manifest journal")
            synchronized(stateLock) { pendingJournal = null }
        }
        save()
    }

    private fun records(): MutableMap<String, Record> = synchronized(stateLock) {
        records ?: load().also { records = it }
    }

    private fun load(): MutableMap<String, Record> {
        val text = try {
            store.read()
        } catch (e: IOException) {
            Log.w(TAG, "reading manifest state failed: ${e.javaClass.simpleName}")
            null
        } ?: return LinkedHashMap()
        return try {
            val o = JSONObject(text)
            if (o.optInt("version") != VERSION) throw JSONException("unknown version")
            pendingJournal = o.optJSONObject("pending")
            val recs = o.getJSONObject("records")
            recs.keys().asSequence().associateWithTo(LinkedHashMap()) { recordFrom(recs.getJSONObject(it)) }
        } catch (e: JSONException) {
            Log.w(TAG, "manifest state can't be parsed; starting afresh")
            pendingJournal = null
            LinkedHashMap()
        } catch (e: RuntimeException) {
            Log.w(TAG, "manifest state can't be parsed; starting afresh")
            pendingJournal = null
            LinkedHashMap()
        } catch (e: StackOverflowError) {
            pendingJournal = null
            LinkedHashMap()
        }
    }

    private fun save() {
        val text = synchronized(stateLock) {
            val recs = JSONObject()
            for ((origin, r) in records()) recs.put(origin, recordJson(r))
            JSONObject().put("version", VERSION).put("records", recs).apply {
                pendingJournal?.let { put("pending", it) }
            }.toString()
        }
        store.write(text)
    }

    private fun copyOf(record: Record): Record = recordFrom(recordJson(record))

    private fun recordJson(r: Record): JSONObject = JSONObject()
        .put("revision", r.revision)
        .put(
            "acknowledged",
            JSONObject().apply {
                for ((c, a) in r.acknowledged) {
                    put(
                        c.wire,
                        JSONObject().put("decision", a.decision).put("source", a.source).put("decidedAt", a.decidedAt)
                            .apply { a.whyShown?.let { put("whyShown", it) } },
                    )
                }
            },
        )
        .put("managed", JSONObject().apply { for ((p, owners) in r.managed) put(p.wire, JSONArray(owners.map { it.wire })) })
        .put("detached", JSONArray(r.detached.map { it.wire }))
        .put("app", JSONObject().put("name", r.appName).put("description", r.appDescription))
        .apply {
            r.observed?.let { o ->
                put(
                    "observed",
                    JSONObject().put("fingerprint", o.fingerprint).put("rawHash", o.rawHash).put("checkedAt", o.checkedAt)
                        .put("capabilities", JSONObject().apply { for ((c, why) in o.capabilities) put(c.wire, why) }),
                )
            }
        }
        .put(
            "receipts",
            JSONArray(
                r.receipts.map { rc ->
                    JSONObject().put("decidedAt", rc.decidedAt).put("outcome", rc.outcome).put("originShown", rc.originShown)
                        .put("manifestNameShown", rc.nameShown).put("manifestDescriptionShown", rc.descriptionShown)
                        .put("rawHash", rc.rawHash).put("browserLabelVersion", 1)
                        .put("rows", JSONArray(rc.rows.map { (c, why) -> JSONObject().put("capability", c.wire).put("whyShown", why) }))
                },
            ),
        )

    private fun recordFrom(o: JSONObject): Record {
        val r = Record(revision = o.optInt("revision"))
        o.optJSONObject("acknowledged")?.let { acks ->
            for (key in acks.keys()) {
                val c = ManifestCapability.of(key) ?: continue
                val a = acks.getJSONObject(key)
                r.acknowledged[c] = Ack(a.getString("decision"), a.optString("source"), a.optString("whyShown").takeIf { a.has("whyShown") }, a.optLong("decidedAt"))
            }
        }
        o.optJSONObject("managed")?.let { managed ->
            for (key in managed.keys()) {
                val p = ManifestProjection.of(key) ?: continue
                val owners = managed.getJSONArray(key)
                r.managed[p] = (0 until owners.length()).mapNotNullTo(ArrayList()) { ManifestCapability.of(owners.getString(it)) }
            }
        }
        o.optJSONArray("detached")?.let { d -> for (k in 0 until d.length()) ManifestProjection.of(d.getString(k))?.let(r.detached::add) }
        o.optJSONObject("app")?.let {
            r.appName = it.optString("name")
            r.appDescription = it.optString("description")
        }
        o.optJSONObject("observed")?.let { ob ->
            val caps = LinkedHashMap<ManifestCapability, String>()
            ob.optJSONObject("capabilities")?.let { cs -> for (k in cs.keys()) ManifestCapability.of(k)?.let { caps[it] = cs.getString(k) } }
            r.observed = Observed(ob.getString("fingerprint"), ob.optString("rawHash"), caps, ob.optLong("checkedAt"))
        }
        o.optJSONArray("receipts")?.let { rs ->
            for (k in 0 until rs.length()) {
                val rc = rs.getJSONObject(k)
                val rows = rc.optJSONArray("rows")
                r.receipts += Receipt(
                    decidedAt = rc.optLong("decidedAt"),
                    outcome = rc.optString("outcome"),
                    originShown = rc.optString("originShown"),
                    nameShown = rc.optString("manifestNameShown"),
                    descriptionShown = rc.optString("manifestDescriptionShown"),
                    rows = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
                        val row = rows!!.getJSONObject(i)
                        ManifestCapability.of(row.optString("capability"))?.let { it to row.optString("whyShown") }
                    },
                    rawHash = rc.optString("rawHash"),
                )
            }
        }
        return r
    }

    companion object {
        const val MANAGED = "managed"
        const val INDIVIDUAL = "individual"
        const val TOKEN_TTL_MS = 5 * 60_000L
        const val MAX_RECEIPTS = 20
        private const val MAX_COMPLETED = 100
        private const val VERSION = 1
        private const val TAG = "SwarmManifests"

        /** Between unresolved attempts for one origin, this session (profile §2.2). */
        val BACKOFF_MS = longArrayOf(2_000, 10_000, 30_000, 60_000)

        /** Profile §4. Messaging (#121) isn't on this device yet, so its row is only the base connection. */
        val PROJECTIONS: Map<ManifestCapability, List<ManifestProjection>> = mapOf(
            ManifestCapability.Publish to listOf(ManifestProjection.Connection, ManifestProjection.AutoPublish),
            ManifestCapability.Feeds to listOf(
                ManifestProjection.Connection, ManifestProjection.Identity, ManifestProjection.FeedGrant, ManifestProjection.AutoFeeds,
            ),
            ManifestCapability.Signing to listOf(
                ManifestProjection.Connection, ManifestProjection.Identity, ManifestProjection.FeedGrant, ManifestProjection.AutoSigning,
            ),
            ManifestCapability.Messaging to listOf(ManifestProjection.Connection),
        )
    }
}

/** [SwarmManifests.Storage] as a file, written whole through a temporary file and a rename. */
class SwarmManifestFile(private val file: File) : SwarmManifests.Storage {
    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("couldn't write the Swarm manifest state")
        }
    }
}
