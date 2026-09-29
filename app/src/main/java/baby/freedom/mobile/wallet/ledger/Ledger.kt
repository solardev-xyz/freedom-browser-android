package baby.freedom.mobile.wallet.ledger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.EthSigning
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.QuoteStaleException
import baby.freedom.mobile.wallet.Secp256k1Keys
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a Ledger account's key lives (#142): its path in the device's
 * format (`44'/60'/0'/0/0`) and the Ledger it was added from — its
 * Bluetooth address ([device]) and the name it showed ([deviceName]).
 * Public data only; the key never leaves the Ledger.
 */
data class LedgerKey(val path: String, val device: String, val deviceName: String)

/** A Ledger the phone can see or has paired with, offered on the Connect Ledger page. */
data class LedgerDevice(val id: String, val name: String, val paired: Boolean)

/** How accounts are laid out on a Ledger: desktop's `PATH_SCHEMES`. */
enum class LedgerScheme(val label: String) {
    LIVE("Ledger Live"),
    LEGACY("Legacy (MEW / MyCrypto)"),
    ;

    fun path(i: Int): String = when (this) {
        LIVE -> "44'/60'/$i'/0/0"
        LEGACY -> "44'/60'/0'/$i"
    }
}

/**
 * The phone's Ledger (#142): finds Bluetooth Ledgers, reads their
 * Ethereum accounts, and signs on them — one device conversation at a
 * time, the link opened for it and closed after, as desktop does.
 *
 * While a conversation is on, [activity] says what the Ledger is waiting
 * for (connecting, pairing, unlock it, open the Ethereum app, confirm on
 * it) and carries Cancel; the wallet shows it over whatever is up
 * ([baby.freedom.mobile.browser.LedgerActivityDialog]). Every step is
 * bounded: a locked Ledger or a closed app is waited for up to
 * [READY_MS], a confirmation up to [LedgerEthApp.CONFIRM_MS], and a
 * disconnect ends it at once — failures are [LedgerException]s whose
 * words the wallet shows as they are.
 *
 * Before anything is signed the Ledger must derive the account's own
 * address at its path: another Ledger (another seed) never signs in its
 * name. After, the signature is recovered against the digest this app
 * worked out and must be the account's — the Ledger signed what the
 * phone showed, or nothing is used.
 */
class Ledger internal constructor(private val context: Context) {
    /** What the Ledger is waiting for, while a conversation is on. */
    data class Activity(val deviceName: String, val stage: Stage, val purpose: String, val cancel: () -> Unit)

    enum class Stage { CONNECTING, PAIRING, UNLOCK, OPEN_APP, READING, CONFIRM }

    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()

    private val conversation = Mutex()

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** The Android permissions reaching a Ledger needs on this version. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(): Boolean = permissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Whether a debug build's emulator links are set up (always false in a release build). */
    fun hasDevLinks(): Boolean = LedgerDevLinks.devices(context).isNotEmpty()

    fun hasBle(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Why Bluetooth can't be used now, or null if it can. */
    private fun bluetoothProblem(): LedgerException? = when {
        !hasBle() -> LedgerException(LedgerException.Kind.BLUETOOTH_UNAVAILABLE)
        !hasPermissions() -> LedgerException(LedgerException.Kind.PERMISSION)
        !bluetoothOn() -> LedgerException(LedgerException.Kind.BLUETOOTH_OFF)
        else -> null
    }

    /**
     * The Ledgers in reach, updated as they're found: those already
     * paired with the phone, and any advertising a Ledger's service.
     * Needs the permissions and Bluetooth on; empty otherwise.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<LedgerDevice>> = callbackFlow {
        val found = LinkedHashMap<String, LedgerDevice>()
        LedgerDevLinks.devices(context).forEach { found[it.id] = it }
        val a = adapter
        if (bluetoothProblem() != null || a == null) {
            trySend(found.values.toList())
            awaitClose { }
            return@callbackFlow
        }
        a.bondedDevices.orEmpty().filter { isLedgerName(it.name) }.forEach {
            found[it.address] = LedgerDevice(it.address, it.name ?: "Ledger", paired = true)
        }
        trySend(found.values.toList())
        val scanner = a.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: "Ledger"
                if (found[d.address]?.paired == true) return
                found[d.address] = LedgerDevice(d.address, name, paired = false)
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }
        val filters = LedgerBleFraming.SPECS.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it.service)).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner?.startScan(filters, settings, callback) }
        awaitClose { runCatching { scanner?.stopScan(callback) } }
    }

    /** [count] accounts from [start] on [device] under [scheme]: (path, address). */
    suspend fun accounts(device: LedgerDevice, scheme: LedgerScheme, start: Int, count: Int): List<Pair<String, String>> =
        session(device.id, device.name, "Reading accounts from your Ledger") { app, stage ->
            val first = scheme.path(start)
            ready(app, first, stage)
            stage(Stage.READING)
            (start until start + count).map { i -> scheme.path(i).let { it to app.address(it) } }
        }

    /**
     * [tx] signed on the Ledger holding [account], checked to recover to
     * it. [fresh] is asked once the Ledger is connected, unlocked and on
     * the Ethereum app, before it shows [tx]: false ends it with
     * [QuoteStaleException] — its fee was priced too long ago.
     */
    suspend fun signTransaction(account: WalletAccount, tx: EthTransaction, fresh: () -> Boolean = { true }): EthTransaction.Signed {
        val key = account.ledger ?: error("not a Ledger account")
        val payload = tx.signingPayload()
        val sig = session(key.device, key.deviceName, "Confirm the transaction on your Ledger") { app, stage ->
            verified(app, key, account.address, stage) { if (!fresh()) throw QuoteStaleException() }
            app.signTransaction(key.path, payload)
        }
        return tx.signedWith(recover(sig, Keccak256.digest(payload), account.address), account.address)
    }

    /** `personal_sign` of [message] on the Ledger holding [account]: `0x` + r ‖ s ‖ v. */
    suspend fun signPersonal(account: WalletAccount, message: ByteArray): String {
        val key = account.ledger ?: error("not a Ledger account")
        val sig = session(key.device, key.deviceName, "Confirm the message on your Ledger") { app, stage ->
            verified(app, key, account.address, stage)
            app.signPersonal(key.path, message)
        }
        return "0x" + recover(sig, MessageSigning.personalDigest(message), account.address).rsv().toHex()
    }

    /** `eth_signTypedData_v4` of [data] (whose digest is [digest]) on the Ledger holding [account]. */
    suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray): String {
        val key = account.ledger ?: error("not a Ledger account")
        val sig = session(key.device, key.deviceName, "Review and sign the data on your Ledger") { app, stage ->
            verified(app, key, account.address, stage)
            app.signTypedData(key.path, data)
        }
        return "0x" + recover(sig, digest, account.address).rsv().toHex()
    }

    /**
     * Waits for the Ledger to be ready and checks it holds [address] at
     * [key]'s path, runs [before] (which may still refuse), then asks for
     * the confirmation.
     */
    private suspend fun verified(app: LedgerEthApp, key: LedgerKey, address: String, stage: (Stage) -> Unit, before: () -> Unit = {}) {
        if (!ready(app, key.path, stage).equals(address, ignoreCase = true)) throw LedgerException(LedgerException.Kind.WRONG_DEVICE)
        before()
        stage(Stage.CONFIRM)
    }

    /**
     * The address at [path] once the Ledger is unlocked with the Ethereum
     * app open: while it's locked or on another app, [activity] says so
     * and it's asked again every [POLL_MS], up to [READY_MS].
     */
    private suspend fun ready(app: LedgerEthApp, path: String, stage: (Stage) -> Unit): String {
        val deadline = System.currentTimeMillis() + READY_MS
        while (true) {
            try {
                return app.address(path)
            } catch (e: LedgerException) {
                val waiting = when (e.kind) {
                    LedgerException.Kind.LOCKED -> Stage.UNLOCK
                    LedgerException.Kind.APP_NOT_OPEN -> Stage.OPEN_APP
                    else -> throw e
                }
                if (System.currentTimeMillis() >= deadline) throw e
                stage(waiting)
                delay(POLL_MS)
            }
        }
    }

    /**
     * One conversation with the Ledger [id]: the link is opened, [block]
     * run with it, and the link closed, whatever happens — one at a time.
     * Cancel ([Activity.cancel]) ends it with [LedgerException.Kind.CANCELLED].
     */
    private suspend fun <T> session(
        id: String,
        name: String,
        purpose: String,
        block: suspend (LedgerEthApp, (Stage) -> Unit) -> T,
    ): T = conversation.withLock {
        bluetoothProblem()?.takeUnless { LedgerDevLinks.handles(id) }?.let { throw it }
        coroutineScope {
            var stage: (Stage) -> Unit = {}
            val cancelled = AtomicBoolean(false)
            // Created unstarted, so Cancel exists before anything can call stage() with it.
            val work = async(start = CoroutineStart.LAZY) {
                val link = open(id, onPairing = { stage(Stage.PAIRING) }, onPaired = { stage(Stage.CONNECTING) })
                try {
                    block(LedgerEthApp(link), stage)
                } finally {
                    link.close()
                }
            }
            val cancel = {
                cancelled.set(true)
                work.cancel()
            }
            stage = { s: Stage -> _activity.value = Activity(name, s, purpose, cancel) }
            stage(Stage.CONNECTING)
            work.start()
            try {
                work.await()
            } catch (e: CancellationException) {
                if (cancelled.get()) throw LedgerException(LedgerException.Kind.CANCELLED)
                throw e
            } catch (e: LedgerException) {
                Log.i(TAG, "Ledger: ${e.kind}${(e.cause as? LedgerException.StatusWord)?.let { " (${it.message})" } ?: ""}")
                throw e
            } finally {
                _activity.value = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String, onPairing: () -> Unit, onPaired: () -> Unit): LedgerLink {
        LedgerDevLinks.open(context, id)?.let { return it }
        val a = adapter ?: throw LedgerException(LedgerException.Kind.BLUETOOTH_UNAVAILABLE)
        if (!BluetoothAdapter.checkBluetoothAddress(id)) throw LedgerException(LedgerException.Kind.NOT_FOUND)
        return LedgerBleLink.open(context, a.getRemoteDevice(id), onPairing, onPaired)
    }

    companion object {
        private const val TAG = "Ledger"

        /** How long a locked Ledger, or one on another app, is waited for. */
        const val READY_MS = 90_000L
        private const val POLL_MS = 1_500L

        @Volatile
        private var instance: Ledger? = null

        fun get(context: Context): Ledger = instance ?: synchronized(this) {
            instance ?: Ledger(context.applicationContext).also { instance = it }
        }

        /** A paired device's name that's a Ledger's: "Nano X 1A2B", "Ledger Stax …", "Ledger Flex …". */
        internal fun isLedgerName(name: String?): Boolean =
            name != null && (name.startsWith("Nano X") || name.startsWith("Ledger"))

        /**
         * The signature [sig] as the recovery id that makes it [address]'s
         * over [digest] (low-s, as a node takes it). The Ledger's own `v`
         * isn't trusted: its meaning varies with what was signed and it's
         * truncated for large chain ids. Neither recovering to [address]
         * means the Ledger signed something else: nothing is used.
         */
        internal fun recover(sig: LedgerSignature, digest: ByteArray, address: String): EthSigning.Signature {
            val n = Secp256k1Keys.N
            var s = BigInteger(1, sig.s)
            val r = BigInteger(1, sig.r)
            if (s > n.shiftRight(1)) s = n.subtract(s)
            if (r.signum() == 0 || r >= n || s.signum() == 0) throw LedgerException(LedgerException.Kind.MISMATCH)
            for (id in 0..1) {
                val candidate = EthSigning.Signature(r, s, id)
                val recovered = Secp256k1.recover(digest, "0x" + candidate.rsv().toHex())
                if (recovered != null && recovered.equals(address, ignoreCase = true)) return candidate
            }
            // Only reached for a signature over another digest (or by another key).
            Log.w(TAG, "signature doesn't recover to the account")
            throw LedgerException(LedgerException.Kind.MISMATCH)
        }
    }
}
