package baby.freedom.mobile.wallet

import android.content.Context
import android.util.Log
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.Keccak256
import java.io.File
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
 */
data class WalletAccount(val index: Int, val name: String, val address: String) {
    val path: String get() = pathFor(index)

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
            require(index in 0 until EthAccounts.MAX_INDEX && ADDRESS.matches(address))
            WalletAccount(index, a.optString("name").take(MAX_NAME).ifBlank { WalletAccount.defaultName(index) }, address)
        }
        if (accounts.map { it.index }.toSet().size != accounts.size) return null
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
                        put(JSONObject().put("index", it.index).put("name", it.name).put("address", it.address))
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

        fun get(context: Context) = WalletAccountStore(File(context.applicationContext.noBackupFilesDir, "wallet/accounts.json"))
    }
}

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
        } catch (t: Throwable) {
            // Never a key or the seed: only what went wrong.
            Log.w(TAG, "account sync failed: ${t.javaClass.simpleName}")
        }
    }

    private suspend fun verify() {
        val tag = withContext(io) { vault.identityTag() } ?: return
        val saved = withContext(io) { store.read(tag) }
        val indices = saved?.accounts?.map { it.index } ?: listOf(0)
        val addresses = try {
            withContext(compute) { vault.withSeed { seed -> indices.map { EthAccounts.address(seed, it) } } }
        } catch (_: VaultLockedException) {
            // Locked again meanwhile: show what's saved; the next unlock checks it.
            if (_accounts.value == null) _accounts.value = saved
            return
        }
        val accounts = indices.mapIndexed { i, index ->
            WalletAccount(index, saved?.accounts?.get(i)?.name ?: WalletAccount.defaultName(index), addresses[i])
        }
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
        val index = (current?.accounts?.maxOf { it.index } ?: -1) + 1
        val indices = if (current == null) listOf(0, 1) else listOf(index)
        val addresses = withContext(compute) { vault.withSeed { seed -> indices.map { EthAccounts.address(seed, it) } } }
        val added = indices.mapIndexed { i, n -> WalletAccount(n, WalletAccount.defaultName(n), addresses[i]) }
        val list = WalletAccountList((current?.accounts ?: emptyList()) + added, added.last().index)
        withContext(io) { store.write(tag, list) }
        _accounts.value = list
        added.last()
    }

    /** Makes account [index] the one the wallet shows. */
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
            instance ?: WalletAccounts(
                vault = Vault.get(context),
                store = WalletAccountStore.get(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                balances = WalletBalances { BalanceFetcher(WalletRpc(ChainDataRouter.get(context))) },
            ).also { instance = it }
        }
    }
}
