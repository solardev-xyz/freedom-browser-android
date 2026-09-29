package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.ledger.LedgerKey
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One of the wallet's accounts (#104): the secp256k1 key at desktop's
 * `m/44'/60'/{index}'/0/0` (`identity/derivation.js` `deriveUserWallet`),
 * so the same recovery phrase shows the same accounts, in the same
 * order, on desktop. Account 0 is desktop's main wallet; the Swarm
 * node's key (`m/44'/60'/0'/0/1`, [NodeIdentity.SWARM_PATH]) is never
 * one of them. Public data only: [address] is EIP-55 checksummed.
 *
 * Or a Ledger's (#142, [ledger] set): the key is on the device, which
 * signs everything from this account; [index] is only the account's
 * place in the list (negative, so it never names a key of the seed's).
 */
data class WalletAccount(val index: Int, val name: String, val address: String, val ledger: LedgerKey? = null) {
    val path: String get() = ledger?.let { "m/" + it.path } ?: pathFor(index)

    val isLedger: Boolean get() = ledger != null

    companion object {
        fun pathFor(index: Int) = "m/44'/60'/$index'/0/0"

        fun defaultName(index: Int) = "Account ${index + 1}"
    }
}

/** The accounts derived so far, in creation order, and which one the wallet shows. */
data class WalletAccountList(val accounts: List<WalletAccount>, val activeIndex: Int) {
    init {
        require(accounts.isNotEmpty()) { "there is always a first account" }
    }

    /** The account the wallet shows; the first one if [activeIndex] names none. */
    val active: WalletAccount get() = accounts.firstOrNull { it.index == activeIndex } ?: accounts.first()
}

/** Ethereum addresses from the wallet's seed. */
internal object EthAccounts {
    /** The checksummed address of account [index] (see [WalletAccount]); the private key is zeroed before returning. */
    fun address(seed: ByteArray, index: Int): String {
        require(index in 0 until MAX_INDEX) { "account index out of range" }
        val key = HdKeys.secp256k1(seed, WalletAccount.pathFor(index))
        return try {
            val pub = Secp256k1Keys.publicKeyUncompressed(key)
            NodeIdentity.checksum(Keccak256.digest(pub).copyOfRange(12, 32))
        } finally {
            key.fill(0)
        }
    }

    /** BIP-44 account indices are hardened, so 31 bits. */
    const val MAX_INDEX = 0x7fffffff
}

/**
 * The account list on disk: which indices exist, their names, their
 * addresses, and the active one — public data, kept so the switcher and
 * balances work while the wallet is locked (always, after a relaunch).
 * Never a key. Tied to the vault it was derived from
 * ([VaultRecord.identityTag]): a list left over from a removed or
 * replaced wallet reads as none. Lives in `noBackupFilesDir` beside the
 * vault, so it goes wherever the vault goes (nowhere).
 */
class WalletAccountStore internal constructor(private val file: File) {
    /** The list saved for the vault tagged [vaultTag], or null if there's none (or it's another vault's, or unreadable). */
    fun read(vaultTag: String): WalletAccountList? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        if (o.optString("vault") != vaultTag) return null
        val arr = o.getJSONArray("accounts")
        if (arr.length() !in 1..MAX_ACCOUNTS) return null
        val accounts = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val index = a.getInt("index")
            val address = a.getString("address")
            val ledger = a.optJSONObject("ledger")?.let { l ->
                LedgerKey(l.getString("path"), l.getString("device"), l.optString("deviceName").take(MAX_NAME).ifBlank { "Ledger" })
                    .also { require(index < 0 && LEDGER_PATH.matches(it.path) && it.device.isNotBlank()) }
            }
            require(ADDRESS.matches(address) && (ledger != null || index in 0 until EthAccounts.MAX_INDEX))
            WalletAccount(index, a.optString("name").take(MAX_NAME).ifBlank { WalletAccount.defaultName(index) }, address, ledger)
        }
        if (accounts.map { it.index }.toSet().size != accounts.size) return null
        if (accounts.none { it.ledger == null }) return null
        WalletAccountList(accounts, o.optInt("active", accounts.first().index))
    }.getOrNull()

    fun write(vaultTag: String, list: WalletAccountList) {
        val o = JSONObject()
            .put("vault", vaultTag)
            .put("active", list.activeIndex)
            .put(
                "accounts",
                JSONArray().apply {
                    list.accounts.forEach {
                        val o = JSONObject().put("index", it.index).put("name", it.name).put("address", it.address)
                        it.ledger?.let { l -> o.put("ledger", JSONObject().put("path", l.path).put("device", l.device).put("deviceName", l.deviceName)) }
                        put(o)
                    }
                },
            )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("couldn't save the account list")
        }
    }

    fun wipe() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    companion object {
        const val MAX_ACCOUNTS = 100
        const val MAX_NAME = 64
        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        /** A Ledger account's path, in the device's format: `44'/60'/…`, at most ten levels. */
        internal val LEDGER_PATH = Regex("^44'/60'(/\\d{1,10}'?){1,8}$")

        fun get(context: Context) = WalletAccountStore(File(context.applicationContext.noBackupFilesDir, "wallet/accounts.json"))
    }
}

/** The account is in the list already (a Ledger account added twice, or one the seed also holds). */
class DuplicateAccountException : IllegalStateException("this account is already in the wallet")

/** No more accounts can be added ([WalletAccountStore.MAX_ACCOUNTS]). */
class TooManyAccountsException : IllegalStateException("this wallet has as many accounts as it can hold")

/**
 * The wallet's accounts (#104), kept in step with the [Vault]:
 *
 *  - While the vault is locked, the list saved for it ([WalletAccountStore])
 *    is what [accounts] shows — addresses are public, so the switcher and
 *    the balances work without unlocking.
 *  - Each time the vault opens, every account's address is derived again
 *    from the seed and the saved list corrected if it differs (a list
 *    from before #104 — or none at all — becomes account 0), so an
 *    address the wallet shows is one its own seed produced.
 *  - [add] derives the next account (needs the vault open); [select]
 *    makes one the active account (doesn't).
 *  - Remove wallet: the list is wiped, [accounts] goes null, and the
 *    session's [balances] are forgotten.
 */
class WalletAccounts internal constructor(
    private val vault: Vault,
    private val store: WalletAccountStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** The session's balance readings; forgotten with the wallet. */
    val balances: WalletBalances = WalletBalances { error("no balance reader") },
) {
    private val _accounts = MutableStateFlow<WalletAccountList?>(null)

    /** The wallet's accounts, or null when there's no wallet — or it has never been opened since #104. */
    val accounts: StateFlow<WalletAccountList?> = _accounts.asStateFlow()

    private val _syncFailed = MutableStateFlow(false)

    /**
     * Whether the last attempt to bring [accounts] in line with the vault
     * failed (the list couldn't be read, derived or saved). The wallet
     * page offers [retry] then, instead of waiting on a list that isn't coming.
     */
    val syncFailed: StateFlow<Boolean> = _syncFailed.asStateFlow()

    private val mutex = Mutex()
    private var started = false

    /** Follows the vault from now on. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { vault.state.collect { reconcile(it) } }
    }

    /** Brings [accounts] and the store in line with [state]. Never throws. */
    internal suspend fun reconcile(state: Vault.State) {
        try {
            mutex.withLock {
                when (state) {
                    is Vault.State.Unlocked -> verify()
                    is Vault.State.Locked -> if (_accounts.value == null) {
                        _accounts.value = withContext(io) { vault.identityTag()?.let { store.read(it) } }
                    }
                    Vault.State.Empty -> {
                        _accounts.value = null
                        balances.forget()
                        withContext(io) { store.wipe() }
                    }
                    Vault.State.Unreadable -> _accounts.value = null
                }
            }
            _syncFailed.value = false
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Never a key or the seed: only what went wrong.
            Log.w(TAG, "account sync failed: ${t.javaClass.simpleName}")
            _syncFailed.value = true
        }
    }

    /** Tries again to bring [accounts] in line with the vault as it is now (after [syncFailed]). Never throws. */
    suspend fun retry() = reconcile(vault.state.value)

    private suspend fun verify() {
        val tag = withContext(io) { vault.identityTag() } ?: return
        val saved = withContext(io) { store.read(tag) }
        // A Ledger's accounts aren't the seed's: they're kept as they are, in their place.
        val indices = saved?.accounts?.filter { it.ledger == null }?.map { it.index } ?: listOf(0)
        val addresses = try {
            withContext(compute) { vault.withSeed { seed -> indices.map { EthAccounts.address(seed, it) } } }
        } catch (_: VaultLockedException) {
            // Locked again meanwhile: show what's saved; the next unlock checks it.
            if (_accounts.value == null) _accounts.value = saved
            return
        }
        val derived = indices.zip(addresses).toMap()
        val accounts = saved?.accounts?.map { a ->
            if (a.ledger != null) a else WalletAccount(a.index, a.name, derived.getValue(a.index))
        } ?: listOf(WalletAccount(0, WalletAccount.defaultName(0), addresses[0]))
        val list = WalletAccountList(accounts, saved?.activeIndex ?: 0)
        if (list != saved) withContext(io) { store.write(tag, list) }
        _accounts.value = list
    }

    /**
     * Derives the next account (one past the highest index so far, as
     * desktop numbers them) and makes it the active one. Throws
     * [VaultLockedException] if the vault isn't open.
     */
    suspend fun add(): WalletAccount = mutex.withLock {
        val tag = withContext(io) { vault.identityTag() } ?: throw VaultLockedException()
        val current = _accounts.value ?: withContext(io) { store.read(tag) }
        if (current != null && current.accounts.size >= WalletAccountStore.MAX_ACCOUNTS) throw TooManyAccountsException()
        val index = (current?.accounts?.filter { it.ledger == null }?.maxOfOrNull { it.index } ?: -1) + 1
        val indices = if (current == null) listOf(0, 1) else listOf(index)
        val addresses = withContext(compute) { vault.withSeed { seed -> indices.map { EthAccounts.address(seed, it) } } }
        val added = indices.mapIndexed { i, n -> WalletAccount(n, WalletAccount.defaultName(n), addresses[i]) }
        val list = WalletAccountList((current?.accounts ?: emptyList()) + added, added.last().index)
        withContext(io) { store.write(tag, list) }
        _accounts.value = list
        added.last()
    }

    /**
     * Adds the Ledger account at [key]'s path, whose address the device
     * gave as [address] (#142), and makes it the active one. Needs a
     * wallet (the list belongs to it) but not an open one: no key of the
     * seed's is involved. An address already in the list is refused
     * ([DuplicateAccountException]).
     */
    suspend fun addLedger(key: LedgerKey, address: String, name: String): WalletAccount = mutex.withLock {
        require(WalletAccountStore.LEDGER_PATH.matches(key.path) && EthTransaction.ADDRESS.matches(address)) { "not a Ledger account" }
        val tag = withContext(io) { vault.identityTag() } ?: throw VaultLockedException()
        val current = _accounts.value ?: withContext(io) { store.read(tag) } ?: throw VaultLockedException()
        if (current.accounts.size >= WalletAccountStore.MAX_ACCOUNTS) throw TooManyAccountsException()
        if (current.accounts.any { it.address.equals(address, ignoreCase = true) }) throw DuplicateAccountException()
        val index = minOf(0, current.accounts.minOf { it.index }) - 1
        val shown = name.trim().take(WalletAccountStore.MAX_NAME).ifBlank { "Ledger ${current.accounts.count { it.ledger != null } + 1}" }
        val added = WalletAccount(index, shown, address, key)
        val list = WalletAccountList(current.accounts + added, index)
        withContext(io) { store.write(tag, list) }
        _accounts.value = list
        added
    }

    /**
     * Takes the Ledger account [index] off the list (#142); the device
     * keeps its key, and it can be added again. The first software
     * account becomes active if it was. A software account can't be removed.
     */
    suspend fun removeLedger(index: Int) = mutex.withLock {
        val current = _accounts.value ?: return@withLock
        if (current.accounts.none { it.index == index && it.ledger != null }) return@withLock
        val tag = withContext(io) { vault.identityTag() } ?: return@withLock
        val rest = current.accounts.filter { it.index != index }
        val active = if (current.activeIndex == index) rest.first { it.ledger == null }.index else current.activeIndex
        val list = WalletAccountList(rest, active)
        withContext(io) { store.write(tag, list) }
        _accounts.value = list
    }

    /**
     * Makes account [index] the one the wallet shows. Throws if the
     * choice can't be saved (the caller reports it; [accounts] is unchanged).
     */
    suspend fun select(index: Int) = mutex.withLock {
        val current = _accounts.value ?: return@withLock
        if (current.activeIndex == index || current.accounts.none { it.index == index }) return@withLock
        val tag = withContext(io) { vault.identityTag() } ?: return@withLock
        val list = current.copy(activeIndex = index)
        withContext(io) { store.write(tag, list) }
        _accounts.value = list
    }

    companion object {
        private const val TAG = "WalletAccounts"

        @Volatile
        private var instance: WalletAccounts? = null

        fun get(context: Context): WalletAccounts = instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }

        /**
         * Builds an instance from the *application* context only: this lives
         * for the whole process, and the balance fetcher's lambda would
         * otherwise pin whatever was passed in (the first `MainActivity`,
         * with its whole view tree and WebViews) for as long as the node's
         * foreground service keeps the process alive.
         */
        @VisibleForTesting
        internal fun create(context: Context): WalletAccounts {
            val app = context.applicationContext
            return WalletAccounts(
                vault = Vault.get(app),
                store = WalletAccountStore.get(app),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                balances = WalletBalances { BalanceFetcher(WalletRpc(ChainDataRouter.get(app))) },
            )
        }
    }
}
