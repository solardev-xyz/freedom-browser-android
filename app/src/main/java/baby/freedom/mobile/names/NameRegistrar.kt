package baby.freedom.mobile.names

import android.content.Context
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameContracts.Role
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameOperation.State
import baby.freedom.mobile.names.NameOperation.Step
import baby.freedom.mobile.wallet.WalletSender
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registering and renewing `.eth`, `.wei` and `.gwei` names (#579), a
 * port of desktop's `src/main/domains/service.js` for its `register` and
 * `renew` intents, as a screen of its own; the agent's `domain_*` tools
 * reuse it later.
 *
 * A registration is commit/reveal: [start] draws a secret, seals it and
 * saves the operation *before* anything is signed; [plan] builds each
 * step's transaction from fresh chain state (code hashes, availability,
 * price, the commitment's age) for the wallet's review; [dispatch]
 * checks it once more, marks it dispatching on disk, and only then hands
 * it to the wallet. An outcome that can't be told (the app stopped, or
 * the wallet may have sent it) is never sent again: [refresh] settles it
 * from the chain alone — the commitment on chain, the name owned, the
 * expiry moved.
 */
class NameRegistrar internal constructor(
    private val reads: NameReads,
    private val journal: NameJournal,
    private val secrets: NameSecrets,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    pinned: (Role) -> String = { it.codeHash },
) {
    private val chain = NameChain(reads, pinned)
    private val _operations = MutableStateFlow<List<NameOperation>?>(null)

    /** Every operation, newest first; null until the journal has been read. */
    val operations: StateFlow<List<NameOperation>?> = _operations.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writing = Mutex()

    /** Operations a [dispatch] holds: nothing else saves over them meanwhile. */
    private val dispatching = HashSet<String>()

    init {
        scope.launch(io) {
            val ops = journal.load()
            _operations.value = ops.sortedByDescending { it.createdAt }
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    fun get(id: String): NameOperation? = _operations.value?.firstOrNull { it.id == id }

    /** What a name costs and whether it's free, for the screen's check. */
    data class Check(
        val parsed: ParsedName,
        val state: NameChain.State,
        val duration: Long,
        /** Register: the registration price; renew: the renewal price; null when neither applies. */
        val priceWei: BigInteger?,
    ) {
        val canRegister: Boolean get() = parsed.topLevel && state.available
        val canRenew: Boolean get() = state.renewable
    }

    /** Desktop's `inspect` with its quote: availability, owner, expiry and price for [duration]. */
    suspend fun check(input: String, duration: Long): Check = readsGuarded {
        val parsed = NameContracts.parse(input)
        if (!parsed.topLevel) refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_top_level_only))
        checkDuration(parsed.space, duration)
        val state = chain.inspect(parsed)
        val price = when {
            state.available -> chain.price(parsed, duration, state.snapshot, registering = true)
            state.renewable -> chain.price(parsed, duration, state.snapshot, registering = false, wrapped = state.wrapped)
            else -> null
        }
        Check(parsed, state, duration, price)
    }

    /**
     * A new operation for [check]'s name, paid (and, for a registration,
     * owned) by [account] — the wallet's [accountIndex]: saved [State.READY]
     * at its first step. A registration's secret and commitment are on
     * flash before this returns, so no commit can go out without them.
     * An open operation for the same name is returned instead of a second.
     */
    suspend fun start(check: Check, intent: Intent, account: String, accountIndex: Int): NameOperation {
        loaded.await()
        val parsed = check.parsed
        checkDuration(parsed.space, check.duration)
        _operations.value.orEmpty().firstOrNull { it.name == parsed.name && !it.state.terminal }?.let { return it }
        if (intent == Intent.REGISTER && !check.canRegister) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_no_longer_available))
        if (intent == Intent.RENEW && !check.canRenew) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_cannot_renew))
        val idBytes = ByteArray(12).also(random::nextBytes)
        var op = NameOperation(
            id = "name_" + idBytes.joinToString("") { "%02x".format(it) },
            name = parsed.name,
            space = parsed.space,
            intent = intent,
            account = account,
            accountIndex = accountIndex,
            duration = check.duration,
            step = if (intent == Intent.REGISTER) Step.COMMIT else Step.RENEW,
            state = State.READY,
            createdAt = clock(),
            quotedWei = check.priceWei,
        )
        if (intent == Intent.REGISTER) {
            val secret = ByteArray(32).also(random::nextBytes)
            val sealed = try {
                secrets.seal(secret)
            } catch (e: Exception) {
                Log.w(TAG, "sealing the secret failed: ${e.javaClass.simpleName}")
                refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_secure_storage))
            }
            op = op.copy(
                encryptedSecret = sealed,
                commitment = NameContracts.hex(NameContracts.commitment(parsed.space, parsed.label, account, check.duration, secret)),
                commitQuoteWei = check.priceWei,
            )
            secret.fill(0)
        } else {
            op = op.copy(beforeExpiry = check.state.expiry, beforeWrapperExpiry = check.state.wrapperExpiry, wrapped = check.state.wrapped)
        }
        return save(op, expectedRevision = null)
    }

    /**
     * One step's transaction, built from fresh chain state: what the
     * wallet's review shows and [dispatch] sends. [quotedWei] is the
     * price at this block; [value] the price plus the buffer, or zero.
     */
    data class Plan(
        val operationId: String,
        val revision: Int,
        val step: Step,
        val to: String,
        val data: ByteArray,
        val value: BigInteger,
        val quotedWei: BigInteger?,
        val contract: Role,
    ) {
        /** Same transaction: what's signed is what was reviewed. */
        fun sameTransaction(other: Plan) = to.equals(other.to, ignoreCase = true) && data.contentEquals(other.data) && value == other.value

        override fun equals(other: Any?) = other is Plan && operationId == other.operationId && revision == other.revision &&
            step == other.step && sameTransaction(other) && quotedWei == other.quotedWei && contract == other.contract

        override fun hashCode() = operationId.hashCode() * 31 + data.contentHashCode()
    }

    /**
     * The current step of [id]'s transaction, from chain state read now.
     * For a reveal, the price is quoted afresh: the user approves it
     * again on the review, which says when it's dearer than at commit.
     */
    suspend fun plan(id: String): Plan = readsGuarded {
        val op = get(id) ?: refuse(NameException.Code.INVALID, Strings.get(R.string.namereg_error_gone))
        if (op.state != State.READY) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_not_ready))
        build(op)
    }

    private suspend fun build(op: NameOperation): Plan {
        val parsed = NameContracts.parse(op.name)
        if (parsed.space != op.space || !parsed.topLevel) refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_inconsistent))
        checkDuration(parsed.space, op.duration)
        val s = chain.snapshot()
        val state = chain.inspect(parsed, s)
        val eth = parsed.space == NameSpace.ETH
        if (eth) {
            checkCodes(s, Role.BASE, Role.REGISTRY, *(if (state.wrapped) arrayOf(Role.WRAPPER) else emptyArray()))
        } else {
            val role = Role.nft(parsed.space)
            chain.checkCode(role, s)
            if (chain.uint(role, NameContracts.nftComputeIdData(parsed.name), s) != parsed.tokenId) {
                refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_inconsistent))
            }
        }
        val plan = when (op.intent) {
            Intent.REGISTER -> buildRegister(op, parsed, state, s)
            Intent.RENEW -> buildRenew(op, parsed, state, s)
        }
        // An account that can't pay is told so, not that the contract would refuse.
        if (plan.value.signum() > 0) {
            val held = chain.balance(op.account, s)
            if (held < plan.value) {
                refuse(NameException.Code.FUNDS, Strings.get(R.string.namereg_error_funds, ethText(plan.value), ethText(held)))
            }
        }
        // Always simulated from the paying account, commits included.
        try {
            chain.simulate(op.account, plan.to, plan.data, plan.value, s)
        } catch (e: ChainRpcException.Rpc) {
            refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_would_revert))
        }
        return plan
    }

    private suspend fun buildRegister(op: NameOperation, parsed: ParsedName, state: NameChain.State, s: NameChain.Snapshot): Plan {
        if (!state.available) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_no_longer_available))
        val role = NameChain.commitRole(parsed.space)
        if (parsed.space == NameSpace.ETH) {
            checkCodes(s, Role.CONTROLLER, Role.RESOLVER)
            if (!chain.bool(Role.BASE, NameContracts.controllersData(Role.CONTROLLER.address), s)) {
                refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_controller_changed))
            }
            val minimum = chain.uint(Role.CONTROLLER, NameContracts.minRegistrationDurationData(), s)
            if (BigInteger.valueOf(op.duration) < minimum || !chain.bool(Role.CONTROLLER, NameContracts.validData(parsed.label), s)) {
                refuse(NameException.Code.INVALID, Strings.get(R.string.namereg_error_not_accepted))
            }
        } else if (chain.string(role, NameContracts.nftNormalizeData(parsed.label), s) != parsed.label) {
            refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_legacy_unicode))
        }
        val secret = openSecret(op)
        try {
            val expected = NameContracts.hex(NameContracts.commitment(parsed.space, parsed.label, op.account, op.duration, secret))
            if (!expected.equals(op.commitment, ignoreCase = true)) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_commitment_mismatch))
            val price = chain.price(parsed, op.duration, s, registering = true)
            return when (op.step) {
                Step.COMMIT -> Plan(op.id, op.revision, Step.COMMIT, role.address, NameContracts.commitData(hexBytes(expected)), BigInteger.ZERO, price, role)
                Step.REVEAL -> {
                    val window = commitmentWindow(op, s)
                    if (window.state != State.READY) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_not_ready))
                    val data = if (parsed.space == NameSpace.ETH) {
                        NameContracts.registerData(parsed.label, op.account, op.duration, secret)
                    } else {
                        NameContracts.revealData(parsed.label, secret)
                    }
                    Plan(op.id, op.revision, Step.REVEAL, role.address, data, NameContracts.withBuffer(price), price, role)
                }
                Step.RENEW -> refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_inconsistent))
            }
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun buildRenew(op: NameOperation, parsed: ParsedName, state: NameChain.State, s: NameChain.Snapshot): Plan {
        if (!state.renewable) refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_cannot_renew))
        // Renewing what was checked: a name that changed hands or expiry since is quoted afresh.
        if (state.expiry != op.beforeExpiry || state.wrapped != op.wrapped || state.wrapperExpiry != op.beforeWrapperExpiry) {
            refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_changed))
        }
        val role: Role
        val data: ByteArray
        if (parsed.space == NameSpace.ETH) {
            if (state.wrapped) {
                chain.checkCode(Role.WRAPPED_CONTROLLER, s)
                if (!chain.bool(Role.WRAPPER, NameContracts.controllersData(Role.WRAPPED_CONTROLLER.address), s) ||
                    !chain.bool(Role.BASE, NameContracts.controllersData(Role.WRAPPER.address), s)
                ) {
                    refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_controller_changed))
                }
                role = Role.WRAPPED_CONTROLLER
                data = NameContracts.wrappedRenewData(parsed.label, op.duration)
            } else {
                chain.checkCode(Role.CONTROLLER, s)
                if (!chain.bool(Role.BASE, NameContracts.controllersData(Role.CONTROLLER.address), s)) {
                    refuse(NameException.Code.UNSUPPORTED, Strings.get(R.string.namereg_error_controller_changed))
                }
                role = Role.CONTROLLER
                data = NameContracts.renewData(parsed.label, op.duration)
            }
        } else {
            role = Role.nft(parsed.space)
            data = NameContracts.nftRenewData(parsed.tokenId)
        }
        val price = chain.price(parsed, op.duration, s, registering = false, wrapped = state.wrapped)
        return Plan(op.id, op.revision, Step.RENEW, role.address, data, NameContracts.withBuffer(price), price, role)
    }

    /**
     * [roles]' code hashes, read side by side: a step is built twice
     * (for the review, then again at Confirm before anything is signed),
     * and the wallet's quote must still be fresh after the second.
     */
    private suspend fun checkCodes(s: NameChain.Snapshot, vararg roles: Role) = coroutineScope {
        roles.map { async { chain.checkCode(it, s) } }.awaitAll()
    }

    /** What happened to a [dispatch]. */
    sealed interface Dispatched {
        /** The wallet broadcast it; [refresh] follows it. */
        data class Sent(val hash: String) : Dispatched

        /** Nothing was sent; [message] says why, and the step can be reviewed again. */
        data class NotSent(val message: String) : Dispatched

        /** The wallet may have sent it: it's never sent again, only settled from chain state. */
        data object Unknown : Dispatched
    }

    /**
     * Sends [plan] (which the user just approved) through [send] — the
     * wallet's [WalletSender.submitAndAwaitBroadcast] of the reviewed
     * quote. First builds the step again from the chain as it is now and
     * refuses if anything in the transaction changed; then saves the
     * operation as dispatching (on flash) before [send] runs. Runs in
     * this object's scope, so leaving the screen can't cut it between
     * those two.
     */
    suspend fun dispatch(plan: Plan, send: suspend () -> WalletSender.Broadcast): Dispatched =
        scope.async { dispatchNow(plan, send) }.await()

    private suspend fun dispatchNow(plan: Plan, send: suspend () -> WalletSender.Broadcast): Dispatched {
        synchronized(dispatching) {
            if (!dispatching.add(plan.operationId)) refuse(NameException.Code.CONFLICT, Strings.get(R.string.namereg_error_busy))
        }
        try {
            var op = get(plan.operationId) ?: refuse(NameException.Code.INVALID, Strings.get(R.string.namereg_error_gone))
            if (op.state != State.READY || op.revision != plan.revision) refuse(NameException.Code.CONFLICT, Strings.get(R.string.namereg_error_changed))
            val fresh = try {
                readsGuarded { build(op) }
            } catch (e: NameException) {
                // A dearer reveal stays ready, to be quoted for a fresh approval.
                if (e.code == NameException.Code.UNVERIFIED || e.code == NameException.Code.UNAVAILABLE || e.code == NameException.Code.FUNDS) throw e
                if (op.step != Step.REVEAL) save(op.copy(state = State.STALE, message = e.message), op.revision)
                throw e
            }
            if (!fresh.sameTransaction(plan)) {
                if (op.step == Step.REVEAL || op.step == Step.RENEW) {
                    refuse(NameException.Code.QUOTE_CHANGED, Strings.get(R.string.namereg_error_quote_changed))
                }
                save(op.copy(state = State.STALE, message = Strings.get(R.string.namereg_error_changed)), op.revision)
                refuse(NameException.Code.STALE, Strings.get(R.string.namereg_error_changed))
            }
            op = save(op.copy(state = State.DISPATCHING, quotedWei = plan.quotedWei ?: op.quotedWei, message = null), op.revision)
            val outcome = try {
                send()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    record(op.id) { it.copy(state = State.DISPATCH_UNKNOWN, message = Strings.get(R.string.namereg_state_dispatch_unknown)) }
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
                record(op.id) { it.copy(state = State.DISPATCH_UNKNOWN, message = Strings.get(R.string.namereg_state_dispatch_unknown)) }
                return Dispatched.Unknown
            }
            return when (outcome) {
                is WalletSender.Broadcast.Sent -> {
                    record(op.id) { it.copy(state = State.PENDING, transactionHash = outcome.hash, message = null) }
                    Dispatched.Sent(outcome.hash)
                }
                is WalletSender.Broadcast.Failed -> if (outcome.mayHaveGone) {
                    record(op.id) { it.copy(state = State.DISPATCH_UNKNOWN, message = Strings.get(R.string.namereg_state_dispatch_unknown)) }
                    Dispatched.Unknown
                } else {
                    notSent(op.id, outcome.message)
                }
                WalletSender.Broadcast.Busy -> notSent(op.id, Strings.get(R.string.namereg_send_busy))
                is WalletSender.Broadcast.Stale -> notSent(op.id, Strings.get(R.string.namereg_send_stale))
                WalletSender.Broadcast.Rejected -> notSent(op.id, Strings.get(R.string.namereg_send_rejected))
            }
        } finally {
            synchronized(dispatching) { dispatching.remove(plan.operationId) }
        }
    }

    private suspend fun notSent(id: String, message: String): Dispatched {
        record(id) { it.copy(state = State.READY, message = message) }
        return Dispatched.NotSent(message)
    }

    /**
     * Looks at the chain for [id] and moves it on: a receipt for its
     * transaction, the commitment's age, or — for an outcome that
     * couldn't be told — the effect itself on chain. Never sends anything.
     */
    suspend fun refresh(id: String): NameOperation? = readsGuarded {
        loaded.await()
        val op = get(id) ?: return@readsGuarded null
        if (synchronized(dispatching) { id in dispatching }) return@readsGuarded op
        val next = observe(op) ?: return@readsGuarded op
        try {
            save(next, op.revision)
        } catch (e: NameException) {
            // Someone saved first (a dispatch's outcome): theirs is newer.
            if (e.code != NameException.Code.CONFLICT) throw e
            get(id)
        }
    }

    /** [op] moved on by what the chain says now, or null when nothing changed. */
    private suspend fun observe(op: NameOperation): NameOperation? {
        val hash = op.transactionHash
        return when (op.state) {
            State.PENDING -> {
                hash ?: return op.copy(state = State.DISPATCH_UNKNOWN, message = Strings.get(R.string.namereg_state_dispatch_unknown))
                // The receipt says a revert; when no receipt is undisputed yet, the effect on chain settles a success.
                val receipt = undisputedReceipt(hash) ?: return byEffect(op, chain.snapshot())?.let {
                    if (op.step == Step.COMMIT) it.copy(commitHash = hash) else it
                }
                mined(op, hash, receipt)
            }
            State.WAITING_COMMITMENT -> {
                val w = commitmentWindow(op, chain.snapshot())
                if (w.state == op.state && w.readyAt == op.readyAt) null else w
            }
            State.READY -> if (op.step == Step.REVEAL) {
                val w = commitmentWindow(op, chain.snapshot())
                if (w.state == State.EXPIRED || w.state == State.VERIFICATION_NEEDED) w else null
            } else {
                null
            }
            State.DISPATCH_UNKNOWN, State.VERIFICATION_NEEDED -> reconcile(op)
            State.DISPATCHING, State.COMPLETED, State.FAILED, State.EXPIRED, State.STALE -> null
        }
    }

    /** [op]'s transaction [hash] was mined with [receipt]. */
    private suspend fun mined(op: NameOperation, hash: String, receipt: org.json.JSONObject): NameOperation {
        val ok = NameChain.quantity(receipt.opt("status")) == BigInteger.ONE
        val s = chain.snapshotAfter(receipt)
        return when {
            !ok && op.step == Step.REVEAL -> {
                // A reverted reveal rolls back the payment and the commitment's use: still revealable in its window.
                val w = commitmentWindow(op.copy(transactionHash = null), s)
                if (w.state == State.READY) w.copy(message = Strings.get(R.string.namereg_state_reveal_reverted)) else w
            }
            !ok -> op.copy(state = State.FAILED, message = Strings.get(R.string.namereg_state_reverted))
            op.step == Step.COMMIT -> commitmentWindow(op.copy(step = Step.REVEAL, commitHash = hash, transactionHash = null), s)
            else -> verifyEffect(op, s) ?: op.copy(state = State.VERIFICATION_NEEDED, message = Strings.get(R.string.namereg_state_verification_needed))
        }
    }

    /** [op] completed, if the chain at [s] shows its registration or renewal done; else null. */
    private suspend fun verifyEffect(op: NameOperation, s: NameChain.Snapshot): NameOperation? {
        val state = chain.inspect(NameContracts.parse(op.name), s)
        val done = when (op.intent) {
            Intent.REGISTER -> !state.available && state.owner.equals(op.account, ignoreCase = true)
            Intent.RENEW -> {
                val before = op.beforeExpiry ?: return null
                val d = BigInteger.valueOf(op.duration)
                state.expiry != null && state.expiry >= before + d &&
                    (!op.wrapped || (op.beforeWrapperExpiry != null && state.wrapperExpiry != null && state.wrapperExpiry >= op.beforeWrapperExpiry + d))
            }
        }
        return if (done) {
            op.copy(state = State.COMPLETED, encryptedSecret = null, message = null, transactionHash = op.transactionHash)
        } else {
            null
        }
    }

    /**
     * An outcome nobody could tell: only the chain settles it. A commit
     * whose commitment is on chain moves to the reveal (the commit went
     * out — the reveal is a new step); a reveal or renewal is done once
     * its effect is there. Otherwise it stays, and is never resent.
     */
    private suspend fun reconcile(op: NameOperation): NameOperation? {
        val s = chain.snapshot()
        val hash = op.transactionHash
        if (hash != null && op.state == State.DISPATCH_UNKNOWN) {
            undisputedReceipt(hash)?.let { return mined(op, hash, it) }
        }
        return byEffect(op, s)
    }

    /** The receipt of [hash] when one is there and undisputed; null for none yet, or one only a lone server vouches for. */
    private suspend fun undisputedReceipt(hash: String): org.json.JSONObject? = try {
        chain.receipt(hash)
    } catch (e: NameException) {
        if (e.code != NameException.Code.UNVERIFIED) throw e
        null
    }

    /** [op] moved on by its effect on chain at [s] (the commitment there, the name owned, the expiry moved), or null. */
    private suspend fun byEffect(op: NameOperation, s: NameChain.Snapshot): NameOperation? {
        val hash = op.transactionHash
        return when (op.step) {
            Step.COMMIT -> {
                val commitment = op.commitment ?: return null
                if (chain.committedAt(op.space, hexBytes(commitment), s).signum() == 0) null else commitmentWindow(op.copy(step = Step.REVEAL, transactionHash = null), s)
            }
            Step.REVEAL -> verifyEffect(op, s) ?: if (op.state == State.VERIFICATION_NEEDED && hash == null) {
                // The commit was mined but its commitment wasn't seen yet: look again.
                commitmentWindow(op, s).takeIf { it.state != State.VERIFICATION_NEEDED }
            } else {
                null
            }
            Step.RENEW -> verifyEffect(op, s)
        }
    }

    /** [op] (at its reveal) placed in its commitment's window at [s]: waiting, ready or expired. */
    private suspend fun commitmentWindow(op: NameOperation, s: NameChain.Snapshot): NameOperation {
        val commitment = op.commitment ?: return op.copy(state = State.VERIFICATION_NEEDED)
        val at = chain.committedAt(op.space, hexBytes(commitment), s)
        if (at.signum() == 0) return op.copy(state = State.VERIFICATION_NEEDED, message = Strings.get(R.string.namereg_state_commitment_missing))
        val (min, max) = chain.commitmentAges(op.space, s)
        val readyAt = at.toLong() + min
        val expiresAt = at.toLong() + max
        val now = s.timestamp
        val expired = if (op.space == NameSpace.ETH) now >= expiresAt else now > expiresAt
        val state = when {
            expired -> State.EXPIRED
            now >= readyAt -> State.READY
            else -> State.WAITING_COMMITMENT
        }
        val message = when (state) {
            State.EXPIRED -> Strings.get(R.string.namereg_state_commitment_expired)
            else -> null
        }
        return op.copy(state = state, readyAt = readyAt, expiresAt = expiresAt, message = message)
    }

    /**
     * Forgets [id]. A dispatch in progress can't be dropped; a sent
     * transaction can (it may still go through: the screen says so), or a
     * commit that never lands would hold its name here for good.
     */
    suspend fun dismiss(id: String) {
        loaded.await()
        val op = get(id) ?: return
        // A sent transaction can be let go (the screen warns it may still go through); one being handed over can't.
        if (op.state == State.DISPATCHING || synchronized(dispatching) { id in dispatching }) {
            refuse(NameException.Code.CONFLICT, Strings.get(R.string.namereg_error_busy))
        }
        writing.withLock {
            val next = _operations.value.orEmpty().filter { it.id != id }
            if (!withContext(io) { journal.save(next) }) refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_save))
            _operations.value = next
        }
    }

    /**
     * Forgets every operation, the sealed secrets with them: Remove wallet
     * does this, since each names the wallet's addresses and the names
     * they were registering (as the wallet's history is wiped). An
     * outcome still on its way is dropped with them, as the wallet's own
     * send is.
     */
    suspend fun wipe() {
        loaded.await()
        writing.withLock {
            if (!withContext(io) { journal.save(emptyList()) }) refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_save))
            _operations.value = emptyList()
        }
    }

    /** Applies [change] to the newest copy of [id]: a dispatch's outcome is never dropped. */
    private suspend fun record(id: String, change: (NameOperation) -> NameOperation) {
        writing.withLock {
            val current = _operations.value.orEmpty().firstOrNull { it.id == id } ?: return
            saveLocked(change(current), current.revision)
        }
    }

    /**
     * Saves [op] if the stored copy is still at [expectedRevision] (null:
     * a new one), else [NameException.Code.CONFLICT]. Returns it as saved.
     */
    private suspend fun save(op: NameOperation, expectedRevision: Int?): NameOperation = writing.withLock { saveLocked(op, expectedRevision) }

    private suspend fun saveLocked(op: NameOperation, expectedRevision: Int?): NameOperation {
        val current = _operations.value.orEmpty()
        val stored = current.firstOrNull { it.id == op.id }
        if ((stored?.revision) != expectedRevision) refuse(NameException.Code.CONFLICT, Strings.get(R.string.namereg_error_changed))
        val saved = op.copy(revision = (expectedRevision ?: 0) + 1)
        var next = listOf(saved) + current.filter { it.id != op.id }
        next = next.sortedByDescending { it.createdAt }
        if (next.size > NameJournalCodec.MAX) {
            // Drop the oldest finished ones; never one that's still open.
            val drop = next.filter { it.state.terminal }.takeLast(next.size - NameJournalCodec.MAX).map { it.id }.toSet()
            next = next.filter { it.id !in drop }
        }
        if (!withContext(io) { journal.save(next) }) refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_save))
        _operations.value = next
        return saved
    }

    private fun openSecret(op: NameOperation): ByteArray {
        val sealed = op.encryptedSecret ?: refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_secret_lost))
        val secret = try {
            secrets.open(sealed)
        } catch (e: Exception) {
            Log.w(TAG, "opening the secret failed: ${e.javaClass.simpleName}")
            refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_secret_lost))
        }
        if (secret.size != 32) refuse(NameException.Code.SECURE_STORAGE, Strings.get(R.string.namereg_error_secret_lost))
        return secret
    }

    private inline fun <T> readsGuarded(block: () -> T): T = try {
        block()
    } catch (e: ChainRpcException) {
        refuse(NameException.Code.UNAVAILABLE, Strings.get(R.string.namereg_error_chain_unreachable))
    }

    companion object {
        private const val TAG = "NameRegistrar"

        /** Refuses a term the namespace doesn't sell: `.wei`/`.gwei` one year per transaction, `.eth` up to ten. */
        fun checkDuration(space: NameSpace, duration: Long) {
            val ok = if (space == NameSpace.ETH) duration > 0 && duration <= NameContracts.MAX_YEARS * NameContracts.YEAR else duration == NameContracts.YEAR
            if (!ok) refuse(NameException.Code.INVALID, Strings.get(R.string.namereg_error_term))
        }

        internal fun hexBytes(hex: String): ByteArray {
            val h = hex.removePrefix("0x")
            return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        @Volatile
        private var instance: NameRegistrar? = null

        fun get(context: Context): NameRegistrar = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                NameRegistrar(
                    RpcNameReads(WalletRpc(ChainDataRouter.get(app))),
                    NameJournal.FileBacked(File(app.noBackupFilesDir, "names/operations.json")),
                    NameSecrets.Keystore,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ).also { instance = it }
            }
        }
    }
}
