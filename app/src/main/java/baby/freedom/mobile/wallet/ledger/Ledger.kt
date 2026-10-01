package baby.freedom.mobile.wallet.ledger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import baby.freedom.mobile.R
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Strings
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a Ledger account's key lives (#142): its path in the device's
 * format (`44'/60'/0'/0/0`) and the Ledger it was added from — its
 * Bluetooth address, or `usb:…` for one added over USB (#319) ([device]),
 * and the name it showed ([deviceName]). Public data only; the key never
 * leaves the Ledger.
 */
data class LedgerKey(val path: String, val device: String, val deviceName: String)

/**
 * A Ledger the phone can see, has paired with, or has plugged in
 * ([usb], #319), offered on the Connect Ledger page.
 */
data class LedgerDevice(val id: String, val name: String, val paired: Boolean) {
    val usb: Boolean get() = Ledger.isUsbId(id)
}

/** How accounts are laid out on a Ledger: desktop's `PATH_SCHEMES`. */
enum class LedgerScheme(@StringRes private val labelRes: Int) {
    LIVE(R.string.signing_ledger_scheme_live),
    LEGACY(R.string.signing_ledger_scheme_legacy),
    ;

    val label: String get() = Strings.get(labelRes)

    fun path(i: Int): String = when (this) {
        LIVE -> "44'/60'/$i'/0/0"
        LEGACY -> "44'/60'/0'/$i"
    }
}

/**
 * The phone's Ledger (#142): finds Bluetooth Ledgers and Ledgers plugged
 * in over USB (#319), reads their Ethereum accounts, and signs on them —
 * one device conversation at a time, the link opened for it and closed
 * after, as desktop does. Both links carry the same APDUs
 * ([LedgerEthApp]); only the framing under them differs.
 *
 * Reading a Ledger's accounts talks to the Ledger that was tapped, over
 * the link it was listed under — never to another one — so what's added
 * is saved under the Ledger it came from. Signing goes to the Ledger that
 * holds the account, whichever link that takes ([accountRoutes]): the
 * Ledgers plugged in over USB first (plugging in is the user picking the
 * cable; the one the account was added from first), then the account's
 * own Bluetooth Ledger — or, for an account added over USB, the paired
 * Bluetooth Ledgers of that model (a Nano X set up over a cable and later
 * used without it). Each is asked for the account's address, and one
 * that doesn't hold it, or can't be reached or read (unplugged, out of
 * range, access refused), is passed over before anything is shown on it.
 * One waiting on the user (locked, on another app, Android's USB access
 * prompt, pairing) doesn't hold up the next: that one is started too,
 * and every Ledger waiting is waited on at once — the first found to hold
 * the account is the one used ([Ledger.Companion.inTurn]).
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

    enum class Stage { CONNECTING, PAIRING, USB_PERMISSION, UNLOCK, OPEN_APP, READING, CONFIRM }

    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()

    private val conversation = Mutex()

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val usbManager: UsbManager?
        get() = context.getSystemService(UsbManager::class.java)

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

    /** Whether the phone can take a Ledger on its USB port (USB host / OTG). */
    fun hasUsbHost(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /**
     * The Ledgers plugged in over USB (#319), updated as they're plugged
     * in and out. Listing them needs no permission; Android asks for
     * access to one only when it's first talked to.
     *
     * The attach/detach broadcasts only prompt a re-read of
     * [UsbManager.getDeviceList] — the list is never taken from them — so
     * the receiver can be exported without trusting whoever sends one.
     */
    fun usbDevices(): Flow<List<LedgerDevice>> = callbackFlow {
        val read = { trySend(LedgerUsbLink.devices(usbManager).map { LedgerDevice(USB_PREFIX + it.deviceName, LedgerUsbLink.name(it), paired = true) }) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                read()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        read()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private val plugInLauncher: ComponentName
        get() = ComponentName(context.packageName, PLUG_IN_LAUNCHER)

    /**
     * Whether plugging in a Ledger opens Freedom (#319): off unless the
     * user turns it on. On, Android offers to open Freedom when a Ledger
     * is plugged in — and, with "always", gives it access to that Ledger
     * without asking each time. Off, nothing happens on plug-in; access
     * is asked when the Ledger is first talked to.
     */
    var openOnPlugIn: Boolean
        get() = context.packageManager.getComponentEnabledSetting(plugInLauncher) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        set(on) {
            context.packageManager.setComponentEnabledSetting(
                plugInLauncher,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }

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
        session({ deviceRoutes(device) }, Strings.get(R.string.signing_ledger_purpose_read_accounts)) { app, turn ->
            val first = scheme.path(start)
            awaitReady(turn::stage) { app.address(first) }
            turn.stage(Stage.READING)
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
        val sig = session({ routesFor(key) }, Strings.get(R.string.signing_ledger_purpose_confirm_transaction)) { app, turn ->
            verified(app, key, account.address, turn) { if (!fresh()) throw QuoteStaleException() }
            app.signTransaction(key.path, payload)
        }
        return tx.signedWith(recover(sig, Keccak256.digest(payload), account.address), account.address)
    }

    /** `personal_sign` of [message] on the Ledger holding [account]: `0x` + r ‖ s ‖ v. */
    suspend fun signPersonal(account: WalletAccount, message: ByteArray): String {
        val key = account.ledger ?: error("not a Ledger account")
        val sig = session({ routesFor(key) }, Strings.get(R.string.signing_ledger_purpose_confirm_message)) { app, turn ->
            verified(app, key, account.address, turn)
            app.signPersonal(key.path, message)
        }
        return "0x" + recover(sig, MessageSigning.personalDigest(message), account.address).rsv().toHex()
    }

    /** `eth_signTypedData_v4` of [data] (whose digest is [digest]) on the Ledger holding [account]. */
    suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray): String =
        signTypedData(account) { data to digest }

    /**
     * Typed data made by [prepare] (with its digest), signed on the Ledger
     * holding [account]. [prepare] is called once the Ledger is connected,
     * unlocked, on the Ethereum app and checked to hold [account], just
     * before it shows the data: data with a deadline in it (an x402
     * authorization's `validBefore`) starts its clock there, not before a
     * Bluetooth connect and an unlock that can take most of it (#218 R1-F1).
     */
    suspend fun signTypedData(account: WalletAccount, prepare: () -> Pair<Eip712.TypedData, ByteArray>): String {
        val key = account.ledger ?: error("not a Ledger account")
        var digest = ByteArray(0)
        val sig = session({ routesFor(key) }, Strings.get(R.string.signing_ledger_purpose_sign_data)) { app, turn ->
            verified(app, key, account.address, turn)
            val (data, d) = prepare()
            digest = d
            app.signTypedData(key.path, data)
        }
        return "0x" + recover(sig, digest, account.address).rsv().toHex()
    }

    /**
     * Waits for the Ledger to be ready and checks it holds [address] at
     * [key]'s path ([holding]: a Ledger that doesn't, or that can't be
     * read — unplugged, never unlocked, out of range — is passed over),
     * takes the conversation for it ([Turn.claim]: any other Ledger still
     * being tried is let go, before anything is shown on this one), runs
     * [before] (which may still refuse), then asks for the confirmation.
     */
    private suspend fun verified(
        app: LedgerEthApp,
        key: LedgerKey,
        address: String,
        turn: Turn,
        before: () -> Unit = {},
    ) {
        holding(address, turn::stage) { app.address(key.path) }
        turn.claim()
        before()
        turn.stage(Stage.CONFIRM)
    }

    /**
     * The one Ledger reading accounts from [device] talks to: the device
     * tapped, over the link it was listed under — never a Ledger plugged
     * in meanwhile, whose accounts would be saved under [device]'s name.
     */
    private fun deviceRoutes(device: LedgerDevice): List<Route> {
        val route = Route(device.id, device.name)
        return when {
            LedgerDevLinks.handles(device.id) -> listOf(route)
            device.usb -> {
                if (usbRoutes().none { it.id == device.id }) throw LedgerUsbLink.unplugged()
                listOf(route)
            }
            else -> {
                bluetoothProblem()?.let { throw it }
                listOf(route)
            }
        }
    }

    /** The Ledgers the account at [key] may be on, in the order they're tried ([accountRoutes]). */
    @SuppressLint("MissingPermission")
    private fun routesFor(key: LedgerKey): List<Route> {
        if (LedgerDevLinks.handles(key.device)) return listOf(Route(key.device, key.deviceName))
        val problem = bluetoothProblem()
        val bonded = if (problem != null) {
            null
        } else {
            runCatching { adapter?.bondedDevices.orEmpty().filter { isLedgerName(it.name) }.map { Route(it.address, it.name ?: "Ledger") } }.getOrNull()
        }
        val routes = accountRoutes(key, usbRoutes(), bonded)
        if (routes.isNotEmpty()) return routes
        throw when {
            !isUsbId(key.device) -> problem ?: LedgerException(LedgerException.Kind.NOT_FOUND)
            usbOnlyModel(key.deviceName) -> LedgerException(LedgerException.Kind.NOT_FOUND, Strings.said(R.string.signing_ledger_usb_not_plugged_in))
            else -> LedgerException(LedgerException.Kind.NOT_FOUND, Strings.said(R.string.signing_ledger_usb_or_bluetooth_not_found))
        }
    }

    private fun usbRoutes(): List<Route> =
        LedgerUsbLink.devices(usbManager).map { Route(USB_PREFIX + it.deviceName, LedgerUsbLink.name(it)) }

    /**
     * One conversation with a Ledger, one at a time: [routes] (listed once
     * the conversation's turn comes) are tried ([inTurn]) — for each, a
     * link opened, [block] run with it, the link closed, whatever happens —
     * and the first Ledger that can be reached and holds what's asked for
     * is the one used. Cancel ([Activity.cancel]) ends every one of them
     * with [LedgerException.Kind.CANCELLED].
     */
    private suspend fun <T> session(
        routes: () -> List<Route>,
        purpose: String,
        block: suspend (LedgerEthApp, Turn) -> T,
    ): T = conversation.withLock {
        try {
            coroutineScope {
                val cancelled = AtomicBoolean(false)
                var cancel: () -> Unit = {}
                // Created unstarted, so Cancel exists before anything can be shown with it.
                val work = async(start = CoroutineStart.LAZY) {
                    // Listed only once it's this conversation's turn: one queued behind
                    // another sees the Ledgers plugged in and paired by then (#350 R2-M1).
                    inTurn(routes(), show = { name, s -> _activity.value = Activity(name, s, purpose, cancel) }) { route, turn ->
                        converse(route, turn, block)
                    }
                }
                cancel = {
                    cancelled.set(true)
                    work.cancel()
                }
                work.start()
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (cancelled.get()) throw LedgerException(LedgerException.Kind.CANCELLED)
                    throw e
                }
            }
        } catch (e: LedgerException) {
            Log.i(TAG, "Ledger: ${e.kind}${(e.cause as? LedgerException.StatusWord)?.let { " (${it.message})" } ?: ""}")
            throw e
        } finally {
            _activity.value = null
        }
    }

    /**
     * [block] with the Ledger at [route]. A link that can't be opened
     * (out of range, unplugged, USB access refused) ends as
     * [NotThisLedger], as does a Ledger that can't be read or doesn't
     * hold the account ([holding], before anything is shown on it), so
     * another route can be used.
     */
    private suspend fun <T> converse(
        route: Route,
        turn: Turn,
        block: suspend (LedgerEthApp, Turn) -> T,
    ): T {
        turn.stage(Stage.CONNECTING)
        val link = try {
            if (isUsbId(route.id)) {
                val manager = usbManager ?: throw LedgerUsbLink.unplugged()
                val usb = LedgerUsbLink.devices(manager).firstOrNull { USB_PREFIX + it.deviceName == route.id } ?: throw LedgerUsbLink.unplugged()
                LedgerUsbLink.open(context, manager, usb, onPermission = { turn.stage(Stage.USB_PERMISSION) }).also { turn.stage(Stage.CONNECTING) }
            } else {
                open(route.id, onPairing = { turn.stage(Stage.PAIRING) }, onPaired = { turn.stage(Stage.CONNECTING) })
            }
        } catch (e: LedgerException) {
            throw NotThisLedger(e)
        }
        return try {
            block(LedgerEthApp(link), turn)
        } finally {
            link.close()
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

        /** A USB Ledger's id: this and its USB device path, which only tells two plugged in at once apart. */
        private const val USB_PREFIX = "usb:"

        /** The activity-alias [openOnPlugIn] turns on: MainActivity, started by a Ledger's plug-in. */
        private const val PLUG_IN_LAUNCHER = "baby.freedom.mobile.LedgerPlugIn"

        fun isUsbId(id: String): Boolean = id.startsWith(USB_PREFIX)

        /** A Ledger a conversation can go to: its id ([LedgerDevice.id]) and the name it shows. */
        internal data class Route(val id: String, val name: String)

        /** A route that couldn't be used — not reachable, or not the Ledger holding the account — with why. */
        internal class NotThisLedger(val reason: LedgerException) : Exception(reason)

        /** The stages where a Ledger waits on the user, in the order the dialog puts them first. */
        private val WAITING_ON_USER = listOf(Stage.PAIRING, Stage.USB_PERMISSION, Stage.UNLOCK, Stage.OPEN_APP)

        /**
         * What the dialog shows while several Ledgers are tried at once:
         * the stage that most needs the user (pairing or Android's USB
         * prompt, then unlocking, then opening the app, then confirming,
         * reading, connecting), with the name of every Ledger at that
         * stage. [stages] is each route's current stage, null once it's
         * been let go.
         */
        internal fun shown(routes: List<Route>, stages: List<Stage?>): Pair<String, Stage>? {
            val order = WAITING_ON_USER + listOf(Stage.CONFIRM, Stage.READING, Stage.CONNECTING)
            val stage = stages.filterNotNull().minByOrNull { order.indexOf(it) } ?: return null
            val names = routes.indices.filter { stages[it] == stage }.map { routes[it].name }.distinct()
            return names.joinToString(" · ") to stage
        }

        /**
         * One route's part in [inTurn]: [stage] says what it's doing (and
         * that it's waiting on the user, which lets the next route start);
         * [claim] takes the conversation for it once it's been checked to
         * hold the account — every other route still being tried is let
         * go, and the one that comes second is let go too.
         */
        internal class Turn internal constructor(private val index: Int, private val race: Race) {
            fun stage(s: Stage) = race.stage(index, s)

            fun claim() {
                if (!race.claim(index)) throw CancellationException("another Ledger holds the account")
            }
        }

        /** The routes being tried at once: their stages, their jobs, and the one that's claimed the conversation. */
        internal class Race(private val routes: List<Route>, private val show: (String, Stage) -> Unit) {
            private val stages = arrayOfNulls<Stage>(routes.size)
            private val jobs = arrayOfNulls<Job>(routes.size)
            val waiting = List(routes.size) { CompletableDeferred<Unit>() }
            private var winner = -1

            @Synchronized
            fun started(i: Int, job: Job) {
                jobs[i] = job
            }

            @Synchronized
            fun stage(i: Int, s: Stage) {
                if (winner >= 0 && winner != i) return
                stages[i] = s
                if (s in WAITING_ON_USER) waiting[i].complete(Unit)
                publish()
            }

            /** Route [i] is over: it no longer shows, and the next one may start. */
            @Synchronized
            fun ended(i: Int) {
                stages[i] = null
                waiting[i].complete(Unit)
                publish()
            }

            @Synchronized
            fun claim(i: Int): Boolean {
                if (winner >= 0) return winner == i
                winner = i
                for (j in routes.indices) {
                    if (j == i) continue
                    stages[j] = null
                    jobs[j]?.cancel()
                }
                publish()
                return true
            }

            @Synchronized
            fun claimed(): Boolean = winner >= 0

            private fun publish() {
                shown(routes, stages.toList())?.let { (name, s) -> show(name, s) }
            }
        }

        /**
         * [attempt] on [routes], until one doesn't end in [NotThisLedger];
         * anything else (a refusal on the device, Cancel, a confirmation
         * timing out) ends it there.
         *
         * They're started in order, each once the one before it has ended
         * or is waiting on the user (locked, on another app, Android's USB
         * access prompt, pairing — [Turn.stage]): a Ledger that answers at
         * once is used without anything being asked of the ones after it,
         * and one waiting on the user doesn't hold up the rest. Every one
         * waiting is waited on at the same time ([holding], up to
         * [READY_MS] each), so whichever the user unlocks — the account's
         * own Ledger on a cable or over Bluetooth, or another Ledger
         * plugged in — is picked up then (#350 R2-F1, R3-F1, R3-M1). The
         * first found to hold the account takes the conversation
         * ([Turn.claim]); the others are let go before anything is shown
         * on them.
         *
         * None left, the reason given is [reasonFrom]'s.
         */
        internal suspend fun <T> inTurn(
            routes: List<Route>,
            show: (String, Stage) -> Unit = { _, _ -> },
            attempt: suspend (Route, Turn) -> T,
        ): T = coroutineScope {
            val race = Race(routes, show)
            val result = CompletableDeferred<T>()
            val reasons = arrayOfNulls<LedgerException>(routes.size)
            val starter = launch {
                val jobs = ArrayList<Job>()
                for ((i, route) in routes.withIndex()) {
                    if (result.isCompleted || race.claimed()) break
                    val job = launch(start = CoroutineStart.LAZY) {
                        try {
                            result.complete(attempt(route, Turn(i, race)))
                        } catch (e: NotThisLedger) {
                            reasons[i] = e.reason
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            result.completeExceptionally(e)
                        } finally {
                            race.ended(i)
                        }
                    }
                    race.started(i, job)
                    jobs += job
                    job.start()
                    race.waiting[i].await()
                }
                jobs.joinAll()
                result.completeExceptionally(reasonFrom(reasons.toList()))
            }
            try {
                result.await()
            } finally {
                // Whatever's still being tried (only ever a Ledger that hasn't held the account) is let go.
                starter.cancel()
            }
        }

        /**
         * Why no Ledger could be used, from each route's reason (null for
         * one never tried): a Ledger left locked or on another app, if
         * any — unlocking it is the one thing that can still work (#350
         * R3-M2) — else a Ledger reached that doesn't hold the account (the
         * one the user has in hand), else the last route's reason.
         */
        internal fun reasonFrom(reasons: List<LedgerException?>): LedgerException {
            val got = reasons.filterNotNull()
            return got.lastOrNull { it.kind == LedgerException.Kind.LOCKED || it.kind == LedgerException.Kind.APP_NOT_OPEN }
                ?: got.lastOrNull { it.kind == LedgerException.Kind.WRONG_DEVICE }
                ?: got.lastOrNull()
                ?: LedgerException(LedgerException.Kind.NOT_FOUND)
        }

        /**
         * The address [read] gives once the Ledger is unlocked with the
         * Ethereum app open: while it's locked or on another app, [stage]
         * says so and it's read again every [pollMs], up to [readyMs].
         */
        internal suspend fun awaitReady(
            stage: (Stage) -> Unit,
            readyMs: Long = READY_MS,
            pollMs: Long = POLL_MS,
            read: suspend () -> String,
        ): String {
            val deadline = System.currentTimeMillis() + readyMs
            while (true) {
                try {
                    return read()
                } catch (e: LedgerException) {
                    val waiting = when (e.kind) {
                        LedgerException.Kind.LOCKED -> Stage.UNLOCK
                        LedgerException.Kind.APP_NOT_OPEN -> Stage.OPEN_APP
                        else -> throw e
                    }
                    if (System.currentTimeMillis() >= deadline) throw e
                    stage(waiting)
                    delay(pollMs)
                }
            }
        }

        /**
         * Checks the Ledger [read] reads from holds [address], once it's
         * ready ([awaitReady]). Anything that stops that — it doesn't hold
         * it, it's unplugged or drops out of range, it stays locked or on
         * another app — ends as [NotThisLedger]: nothing has been shown on
         * it yet, so another Ledger can still be used.
         */
        internal suspend fun holding(
            address: String,
            stage: (Stage) -> Unit,
            readyMs: Long = READY_MS,
            pollMs: Long = POLL_MS,
            read: suspend () -> String,
        ) {
            val got = try {
                awaitReady(stage, readyMs, pollMs, read)
            } catch (e: LedgerException) {
                throw NotThisLedger(e)
            }
            if (!got.equals(address, ignoreCase = true)) throw NotThisLedger(LedgerException(LedgerException.Kind.WRONG_DEVICE))
        }

        /**
         * Where the account at [key] may be, in the order it's looked for:
         * the Ledgers [plugged] in over USB (the one it was added from
         * first, though its USB path changes on every replug), then over
         * Bluetooth — its own Ledger, or, added over USB, the [bonded]
         * Ledgers of its model that have Bluetooth. [bonded] is null when
         * Bluetooth can't be used. Every one is checked to hold the
         * account's address before it's asked to sign.
         */
        internal fun accountRoutes(key: LedgerKey, plugged: List<Route>, bonded: List<Route>?): List<Route> {
            val usb = plugged.filter { it.id == key.device } + plugged.filter { it.id != key.device }
            val radio = when {
                bonded == null -> emptyList()
                !isUsbId(key.device) -> listOf(Route(key.device, key.deviceName))
                else -> bonded.filter { bluetoothModelMatches(key.deviceName, it.name) }
            }
            return usb + radio
        }

        private val USB_ONLY_MODELS = setOf("Nano S", "Nano S Plus", "Blue")
        private val BLUETOOTH_MODELS = listOf("Nano X", "Stax", "Flex")

        private fun model(usbName: String): String = usbName.removePrefix("Ledger").trim()

        /** Whether the Ledger a USB account was added from ([usbName]: "Ledger Nano S Plus") has no Bluetooth. */
        internal fun usbOnlyModel(usbName: String): Boolean = model(usbName) in USB_ONLY_MODELS

        /** Whether a paired Bluetooth Ledger named [bleName] ("Nano X 1A2B") can be the one [usbName] names. */
        internal fun bluetoothModelMatches(usbName: String, bleName: String): Boolean {
            val m = model(usbName)
            return when {
                m in USB_ONLY_MODELS -> false
                m in BLUETOOTH_MODELS -> bleName.contains(m)
                else -> true
            }
        }

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
