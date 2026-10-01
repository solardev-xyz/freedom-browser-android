package baby.freedom.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONArray
import org.json.JSONObject

/**
 * x402 payments (#140): the per-site allowances the user granted for
 * paying without being asked, and the history of every payment signed —
 * desktop's `x402/permissions.js` and the x402 rows of `payment-history.js`.
 *
 *     "allow:<origin> <chainId> <asset> <account>" → {"cap": "…", "spent": "…", "each": "…", "payTo": "0x…",
 *                                           "created": ms, "expires": ms, "symbol": "USDC", "decimals": 6}
 *     "history" → [{…}, …] newest first, at most [MAX_HISTORY]
 *     "held:<origin>" → ms: the site is held after a Refused paid request (#237, #347)
 *
 * An allowance is keyed by the site's origin (the provider origin key),
 * the chain, the token and the paying account: "1 USDC for example.com
 * from Account 1" never lets the site spend another token, the same token
 * on another chain, or another account's funds — after an account switch
 * the site asks again, from the account now active (#218 R2-F1). An
 * allowance written before the account was part of its key doesn't decode,
 * so it pays nothing. It pays only the address the payment that granted
 * it went to ([Allowance.payTo]), and each payment at most that
 * payment's amount ([Allowance.each]): a site can't take the rest of the
 * cap in one request, nor have it sent to another address (#237); one
 * written before those were part of it doesn't decode either. It is a total
 * over one window, never renewed by itself: when [Allowance.expires]
 * passes or [Allowance.spent] reaches [Allowance.cap], the site asks again.
 * Amounts are base-unit integers in text.
 *
 * A site whose paid request was answered Refused is held ([hold]): its
 * allowances pay nothing silently until the user navigates to it
 * themselves ([lift]) — however long that is, across restarts, as the
 * server may keep the refused authorization and collect it anyway (#347).
 *
 * Public data only — addresses, amounts, sites — never a key or a
 * signature. A private tab never reads or writes here. Never throws for
 * storage trouble: an unreadable file reads as nothing (and a corrupt one
 * is replaced with an empty one), a failed write reports `false`, and a
 * payment that can't be counted against an allowance isn't made.
 */
class X402Store internal constructor(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Allowance(
        val origin: String,
        val chainId: Long,
        /** The token contract, lower case. */
        val asset: String,
        /** The account it pays from, lower case (#218 R2-F1). */
        val account: String,
        val symbol: String,
        val decimals: Int,
        val cap: BigInteger,
        val spent: BigInteger,
        val created: Long,
        val expires: Long,
        /** The only address it pays, lower case: the one the payment that granted it went to (#237). */
        val payTo: String,
        /** The most it pays at a time: the amount of the payment that granted it (#237). */
        val each: BigInteger,
    ) {
        val remaining: BigInteger get() = (cap - spent).max(BigInteger.ZERO)

        /** It may pay [amount] to [to] as one payment, the window and the rest aside. */
        fun allows(to: String, amount: BigInteger): Boolean =
            to.lowercase() == payTo && amount.signum() > 0 && amount <= each && amount <= remaining
    }

    enum class Status {
        /** Signed and sent with the request again; the site's answer isn't in yet. */
        PENDING,

        /** The site answered the paid request with its page. */
        PAID,

        /** The site answered the paid request with an error ([Payment.httpStatus]); it may still have taken the payment. */
        REFUSED,

        /** No answer to the paid request was seen (a network error, or the tab moved on); it may have been taken. */
        UNCONFIRMED,
    }

    data class Payment(
        val id: String,
        val at: Long,
        val origin: String,
        val url: String,
        val chainId: Long,
        val asset: String,
        val symbol: String,
        val decimals: Int,
        val amount: BigInteger,
        val payTo: String,
        val from: String,
        /** Paid from an allowance, without asking. */
        val auto: Boolean,
        /** The EIP-3009 authorization's nonce: how the transfer shows on chain (`AuthorizationUsed`). */
        val nonce: String,
        val status: Status,
        val httpStatus: Int? = null,
    )

    /**
     * Every allowance still in its window with something left, by site.
     * Emitted again whenever one's window opens or closes, not only when
     * the store changes: an allowance that runs out with nothing else
     * written leaves the list on its own (#218 R2-M2).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allowances: Flow<List<Allowance>> = data().transformLatest { prefs ->
        val all = prefs?.asMap()?.mapNotNull { (k, v) ->
            if (!k.name.startsWith(ALLOW)) return@mapNotNull null
            decodeAllowance(k.name.removePrefix(ALLOW), v as? String ?: return@mapNotNull null)
        }.orEmpty().sortedWith(compareBy({ it.origin }, { it.chainId }, { it.asset }, { it.account }))
        while (true) {
            val now = clock()
            emit(all.filter { live(it, now) })
            // The next moment the list changes by the clock alone.
            val next = all.filter { it.spent < it.cap }
                .flatMap { listOf(it.created - CLOCK_SLACK_MS, it.expires) }
                .filter { it > now }
                .minOrNull() ?: break
            delay((next - now).coerceIn(1, MAX_WAKE_MS))
        }
    }

    /** Every payment, newest first. */
    val history: Flow<List<Payment>> = data().map { prefs ->
        prefs?.get(HISTORY)?.let(::decodeHistory).orEmpty()
    }

    private fun data(): Flow<Preferences?> = store.data
        .map<Preferences, Preferences?> { it }
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "reading x402 payments failed", e)
            emit(null)
        }

    private fun live(a: Allowance, now: Long) =
        now < a.expires && now >= a.created - CLOCK_SLACK_MS && a.spent < a.cap

    /**
     * Let [origin] pay up to [cap] of [asset] on [chainId] from [account] without asking,
     * to [payTo] only and at most [each] at a time,
     * for [windowMs] from now, with [spentNow] (the payment the user just
     * approved) already counted. Replaces any allowance it had for that
     * token. `false` if it couldn't be written, or [spentNow] or [each] is over [cap].
     */
    suspend fun grant(
        origin: String,
        chainId: Long,
        asset: String,
        account: String,
        payTo: String,
        each: BigInteger,
        symbol: String,
        decimals: Int,
        cap: BigInteger,
        windowMs: Long,
        spentNow: BigInteger,
    ): Boolean {
        if (cap.signum() <= 0 || spentNow.signum() < 0 || spentNow > cap || windowMs <= 0) return false
        if (each.signum() <= 0 || each > cap) return false
        val now = clock()
        val a = Allowance(
            origin, chainId, asset.lowercase(), account.lowercase(), symbol, decimals, cap, spentNow, now, now + windowMs,
            payTo.lowercase(), each,
        )
        return write { prefs ->
            dropDead(prefs, now)
            prefs[allowKey(origin, chainId, asset, account)] = encodeAllowance(a)
        }
    }

    /**
     * The allowance [origin] has for [asset] on [chainId] from [account]
     * if it covers [amount] to [payTo] right now, else null. Nothing is counted: see [consume].
     */
    fun covering(
        all: List<Allowance>,
        origin: String,
        chainId: Long,
        asset: String,
        account: String,
        payTo: String,
        amount: BigInteger,
    ): Allowance? {
        val now = clock()
        return all.firstOrNull {
            it.origin == origin && it.chainId == chainId && it.asset == asset.lowercase() &&
                it.account == account.lowercase() && live(it, now) && it.allows(payTo, amount)
        }
    }

    /**
     * Count [amount] to [payTo] against [origin]'s allowance for [asset] on [chainId]
     * from [account], in one write: `true` only if the allowance is live, covers it, and
     * the new total was saved. Two payments racing for the last of an
     * allowance can't both get `true`.
     */
    suspend fun consume(origin: String, chainId: Long, asset: String, account: String, payTo: String, amount: BigInteger): Boolean {
        if (amount.signum() <= 0) return false
        var ok = false
        val written = write { prefs ->
            ok = false
            val key = allowKey(origin, chainId, asset, account)
            val a = prefs[key]?.let { decodeAllowance(key.name.removePrefix(ALLOW), it) } ?: return@write
            if (!live(a, clock()) || !a.allows(payTo, amount)) return@write
            prefs[key] = encodeAllowance(a.copy(spent = a.spent + amount))
            ok = true
        }
        return written && ok
    }

    /** An allowance the user grants along with the payment they approve: up to [cap] in all, for [windowMs]. */
    data class NewAllowance(val symbol: String, val decimals: Int, val cap: BigInteger, val windowMs: Long)

    /** What [commit] did. */
    sealed interface Commit {
        /**
         * [Payment] recorded, and the allowance counted or granted.
         * [allowanceCreated] names the allowance touched (its
         * [Allowance.created]) for [withdraw]; null if none was.
         * [replaced] is the allowance a grant replaced, as it was stored
         * (null if there was none), for [withdraw] to put back (#346).
         */
        data class Done(val allowanceCreated: Long?, val replaced: String? = null) : Commit

        /** The payment was to come from an allowance that no longer covers it: nothing written. */
        data object NotCovered : Commit

        /** Couldn't be written (or the grant is invalid): nothing written. */
        data object Failed : Commit
    }

    /**
     * Record [payment] as [Status.PENDING] and, in the same write, count
     * it against [Payment.origin]'s allowance from the paying account
     * ([Payment.from]) ([Payment.auto]) or grant the allowance the user
     * asked for with it ([grant], this payment counted), for that account
     * only (#218 R2-F1). All of it is written or none of it (#218 R1-M2, R1-M4):
     * an allowance is never spent or granted for a payment the history
     * doesn't show. Undo with [withdraw] if the payment isn't sent after all.
     * An automatic payment is covered only to the allowance's payee and up
     * to its per-payment amount; a granted allowance takes both from
     * [payment] (#237).
     */
    suspend fun commit(payment: Payment, grant: NewAllowance?): Commit {
        val amount = payment.amount
        if (amount.signum() <= 0) return Commit.Failed
        if (grant != null && (payment.auto || grant.cap.signum() <= 0 || amount > grant.cap || grant.windowMs <= 0)) {
            return Commit.Failed
        }
        var result: Commit = Commit.Failed
        val written = write { prefs ->
            result = Commit.Failed
            val now = clock()
            val key = allowKey(payment.origin, payment.chainId, payment.asset, payment.from)
            var created: Long? = null
            var replaced: String? = null
            if (payment.auto) {
                val a = prefs[key]?.let { decodeAllowance(key.name.removePrefix(ALLOW), it) }
                if (a == null || !live(a, now) || !a.allows(payment.payTo, amount)) {
                    result = Commit.NotCovered
                    return@write
                }
                prefs[key] = encodeAllowance(a.copy(spent = a.spent + amount))
                created = a.created
            } else if (grant != null) {
                dropDead(prefs, now)
                replaced = prefs[key]
                val a = Allowance(
                    payment.origin, payment.chainId, payment.asset.lowercase(), payment.from.lowercase(),
                    grant.symbol, grant.decimals,
                    grant.cap, amount, now, now + grant.windowMs,
                    payTo = payment.payTo.lowercase(), each = amount,
                )
                prefs[key] = encodeAllowance(a)
                created = now
            }
            val list = prefs[HISTORY]?.let(::decodeHistory).orEmpty()
            prefs[HISTORY] = encodeHistory((listOf(payment) + list.filter { it.id != payment.id }).take(MAX_HISTORY))
            result = Commit.Done(created, replaced)
        }
        return if (written) result else Commit.Failed
    }

    /**
     * Undo a [commit] whose payment was never sent: its history entry
     * goes, an allowance payment is given back to that allowance, and an
     * allowance granted with it is taken away, and the allowance that
     * grant replaced ([Commit.Done.replaced]) put back as it was (#346),
     * charged with anything the granted one paid meanwhile besides this
     * payment, so no sent payment goes uncounted (#346 R1-F1) —
     * only if it's still the one [commit] touched ([Commit.Done.allowanceCreated]),
     * never one the user has revoked or replaced since. `false` if it
     * couldn't be written.
     *
     * The restore assumes the withdrawn grant is still the latest for its
     * key. With two overlapping granting payments withdrawn oldest-first
     * (P2 grants B, P4 grants C replacing B), withdrawing P2 finds C, not
     * B, so it changes nothing; withdrawing P4 then puts B back even
     * though P2, the payment that granted B, was withdrawn. B can
     * outlive its own withdrawn grant this way, but only as a cap the
     * user did approve on a sheet, never one they didn't.
     *
     * Deliberately conservative: an automatic payment carried over onto
     * the allowance put back is never given back to it. If that payment
     * is itself withdrawn later, its [Commit.Done.allowanceCreated] names
     * the granted allowance, which is gone, so nothing changes and the
     * restored one stays charged for it — an over-count that can only
     * leave the site less to spend, never more (#346 R2-M1).
     */
    suspend fun withdraw(payment: Payment, done: Commit.Done): Boolean = write { prefs ->
        val allowanceCreated = done.allowanceCreated
        prefs[HISTORY]?.let(::decodeHistory)?.let { list ->
            prefs[HISTORY] = encodeHistory(list.filter { it.id != payment.id })
        }
        if (allowanceCreated == null) return@write
        val key = allowKey(payment.origin, payment.chainId, payment.asset, payment.from)
        val a = prefs[key]?.let { decodeAllowance(key.name.removePrefix(ALLOW), it) } ?: return@write
        if (a.created != allowanceCreated) return@write
        if (payment.auto) {
            prefs[key] = encodeAllowance(a.copy(spent = (a.spent - payment.amount).max(BigInteger.ZERO)))
        } else if (done.replaced != null) {
            // Whatever the granted allowance paid besides this payment — an
            // automatic payment committed against it and sent meanwhile —
            // stays counted, now against the one put back (#346 R1-F1).
            val since = a.spent - payment.amount
            val back = if (since.signum() <= 0) done.replaced else {
                decodeAllowance(key.name.removePrefix(ALLOW), done.replaced)
                    ?.let { encodeAllowance(it.copy(spent = it.spent + since)) }
            }
            if (back != null) prefs[key] = back else prefs.remove(key)
        } else {
            prefs.remove(key)
        }
    }

    /** Take away [origin]'s allowance for [asset] on [chainId] from [account]; `false` if it couldn't be written. */
    suspend fun revoke(origin: String, chainId: Long, asset: String, account: String): Boolean =
        write { it.remove(allowKey(origin, chainId, asset, account)) }

    /** Record [payment] at the top of the history; `false` if it couldn't be written. */
    suspend fun record(payment: Payment): Boolean = write { prefs ->
        val list = prefs[HISTORY]?.let(::decodeHistory).orEmpty()
        prefs[HISTORY] = encodeHistory((listOf(payment) + list.filter { it.id != payment.id }).take(MAX_HISTORY))
    }

    /** Settle payment [id]'s outcome; `false` if it couldn't be written. A settled one isn't changed again. */
    suspend fun settle(id: String, status: Status, httpStatus: Int? = null): Boolean = write { prefs ->
        val list = prefs[HISTORY]?.let(::decodeHistory) ?: return@write
        prefs[HISTORY] = encodeHistory(
            list.map { if (it.id == id && it.status == Status.PENDING) it.copy(status = status, httpStatus = httpStatus) else it },
        )
    }

    /**
     * Payments still [Status.PENDING] from an earlier run: whatever the
     * site answered went unseen, so they're [Status.UNCONFIRMED].
     */
    suspend fun settleStale(): Boolean = write { prefs ->
        val list = prefs[HISTORY]?.let(::decodeHistory) ?: return@write
        if (list.none { it.status == Status.PENDING }) return@write
        prefs[HISTORY] = encodeHistory(list.map { if (it.status == Status.PENDING) it.copy(status = Status.UNCONFIRMED) else it })
    }

    /**
     * Every site held after a Refused paid request ([hold]), or null if
     * they couldn't be read — not "none": any site may be held (#347).
     */
    suspend fun holds(): Set<String>? = data().first()?.asMap()?.keys
        ?.mapNotNullTo(HashSet()) { k -> k.name.takeIf { it.startsWith(HELD) }?.removePrefix(HELD)?.ifEmpty { null } }

    /** Hold [origin] after a Refused paid request (#237, #347); `false` if it couldn't be written. */
    suspend fun hold(origin: String): Boolean = write { it[heldKey(origin)] = clock().toString() }

    /** Lift [origin]'s hold, if it has one (#347); `false` if it couldn't be written. */
    suspend fun lift(origin: String): Boolean = write { it.remove(heldKey(origin)) }

    /** Forget every allowance, payment and hold (the wallet was removed); `false` if it couldn't be written. */
    suspend fun clear(): Boolean {
        val n = synchronized(clears) { (++clearsStarted).also { clearsWriting += it } }
        var landed = false
        try {
            landed = write { it.clear() }
            return landed
        } finally {
            synchronized(clears) {
                clearsWriting -= n
                if (landed && n > clearLanded) clearLanded = n
            }
        }
    }

    /**
     * Tag for a hold queued now: [clearedSince] it later tells whether
     * the store was cleared after it (#347 R1-M1, R2-M1).
     */
    val clearEra: Long get() = synchronized(clears) { clearsStarted }

    /**
     * Whether a [clear] begun after [era] ([clearEra]) has landed (true),
     * is still being written (null: wait and ask again), or none did —
     * a clear that failed to write counts as none: what it would have
     * removed is still on disk (#347 R2-M1).
     */
    fun clearedSince(era: Long): Boolean? = synchronized(clears) {
        when {
            clearLanded > era -> true
            clearsWriting.any { it > era } -> null
            else -> false
        }
    }

    private val clears = Any()
    private var clearsStarted = 0L
    private var clearLanded = 0L
    private val clearsWriting = HashSet<Long>()

    private fun dropDead(prefs: MutablePreferences, now: Long) {
        prefs.asMap().forEach { (k, v) ->
            if (!k.name.startsWith(ALLOW)) return@forEach
            val a = decodeAllowance(k.name.removePrefix(ALLOW), v as? String ?: "")
            if (a == null || !live(a, now)) prefs.remove(k)
        }
    }

    private suspend fun write(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit(change)
        true
    } catch (e: IOException) {
        Log.w(TAG, "writing x402 payments failed", e)
        false
    }

    companion object {
        private const val TAG = "X402Store"
        private const val ALLOW = "allow:"
        private const val HELD = "held:"
        private val HISTORY = stringPreferencesKey("history")

        /** The newest payments kept. */
        internal const val MAX_HISTORY = 500

        /** The longest paid URL kept in the history (a page can make its URL megabytes long). */
        internal const val MAX_URL_CHARS = 2048

        /** How far behind an allowance's creation the clock may read before the allowance stops counting. */
        private const val CLOCK_SLACK_MS = 5 * 60 * 1000L

        /** The longest the allowance list waits before looking at the clock again (a wall clock can jump). */
        private const val MAX_WAKE_MS = 60 * 60 * 1000L

        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
        private val DIGITS = Regex("^[0-9]{1,78}$")

        private fun heldKey(origin: String) = stringPreferencesKey("$HELD$origin")

        private fun allowKey(origin: String, chainId: Long, asset: String, account: String) =
            stringPreferencesKey("$ALLOW$origin $chainId ${asset.lowercase()} ${account.lowercase()}")

        internal fun encodeAllowance(a: Allowance): String = JSONObject()
            .put("cap", a.cap.toString()).put("spent", a.spent.toString())
            .put("created", a.created).put("expires", a.expires)
            .put("symbol", a.symbol).put("decimals", a.decimals)
            .put("payTo", a.payTo).put("each", a.each.toString())
            .toString()

        internal fun decodeAllowance(key: String, json: String): Allowance? = try {
            val parts = key.split(' ')
            // Four parts: one keyed without its account (before #218 R2-F1) pays nothing.
            require(parts.size == 4)
            val o = JSONObject(json)
            val asset = parts[2]
            require(ADDRESS.matches(asset))
            val account = parts[3]
            require(ADDRESS.matches(account))
            Allowance(
                origin = parts[0],
                chainId = parts[1].toLong(),
                asset = asset,
                account = account,
                symbol = o.getString("symbol"),
                decimals = o.getInt("decimals").also { require(it in 0..36) },
                cap = o.getString("cap").also { require(DIGITS.matches(it)) }.toBigInteger(),
                spent = o.getString("spent").also { require(DIGITS.matches(it)) }.toBigInteger(),
                created = o.getLong("created"),
                expires = o.getLong("expires"),
                // Without these (written before #237) it pays nothing: the site asks again.
                payTo = o.getString("payTo").also { require(ADDRESS.matches(it)) }.lowercase(),
                each = o.getString("each").also { require(DIGITS.matches(it)) }.toBigInteger().also { require(it.signum() > 0) },
            )
        } catch (e: Exception) {
            null
        }

        internal fun encodeHistory(list: List<Payment>): String = JSONArray().apply {
            list.forEach { p ->
                put(
                    JSONObject()
                        .put("id", p.id).put("at", p.at).put("origin", p.origin).put("url", p.url.take(MAX_URL_CHARS))
                        .put("chainId", p.chainId).put("asset", p.asset).put("symbol", p.symbol).put("decimals", p.decimals)
                        .put("amount", p.amount.toString()).put("payTo", p.payTo).put("from", p.from).put("auto", p.auto)
                        .put("nonce", p.nonce).put("status", p.status.name)
                        .apply { p.httpStatus?.let { put("httpStatus", it) } },
                )
            }
        }.toString()

        internal fun decodeHistory(json: String): List<Payment> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    Payment(
                        id = o.getString("id"),
                        at = o.getLong("at"),
                        origin = o.getString("origin"),
                        url = o.getString("url"),
                        chainId = o.getLong("chainId"),
                        asset = o.getString("asset"),
                        symbol = o.getString("symbol"),
                        decimals = o.getInt("decimals"),
                        amount = o.getString("amount").also { require(DIGITS.matches(it)) }.toBigInteger(),
                        payTo = o.getString("payTo"),
                        from = o.getString("from"),
                        auto = o.optBoolean("auto"),
                        nonce = o.getString("nonce"),
                        status = Status.valueOf(o.getString("status")),
                        httpStatus = if (o.has("httpStatus")) o.getInt("httpStatus") else null,
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }

        private val Context.x402Store by preferencesDataStore(
            name = "freedom_x402",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        @Volatile
        private var instance: X402Store? = null

        fun get(context: Context): X402Store =
            instance ?: synchronized(this) {
                instance ?: X402Store(context.applicationContext.x402Store).also { instance = it }
            }
    }
}
