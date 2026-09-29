package baby.freedom.mobile.browser

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.wallet.DuplicateAccountException
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.ledger.Ledger
import baby.freedom.mobile.wallet.ledger.LedgerDevice
import baby.freedom.mobile.wallet.ledger.LedgerException
import baby.freedom.mobile.wallet.ledger.LedgerKey
import baby.freedom.mobile.wallet.ledger.LedgerScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val CONNECT_LEDGER_TITLE = "Connect a Ledger"

/** How many accounts the picker reads from the Ledger at a time. */
private const val ACCOUNTS_PER_PAGE = 5

/** How long one Bluetooth scan for Ledgers runs before it's offered again. */
private const val SCAN_MS = 30_000L

/**
 * Connect a Ledger (#142), from the wallet's account card: Bluetooth and
 * its permission first (Nearby devices, or location before Android 12),
 * then the Ledgers in reach — paired ones and ones advertising — then
 * that Ledger's Ethereum accounts, five at a time, under Ledger Live's
 * layout or the legacy one, as on desktop. The one picked joins the
 * account list and becomes the active account; the key stays on the
 * Ledger. While the Ledger is being talked to, [LedgerActivityDialog]
 * says what it's waiting for ("Unlock your Ledger", "Open the Ethereum
 * app"), with Cancel.
 */
@Composable
internal fun LedgerConnectPage(accounts: List<WalletAccount>, onAdded: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val ledger = remember(context) { Ledger.get(context) }
    val walletAccounts = remember(context) { WalletAccounts.get(context) }
    var device by remember { mutableStateOf<LedgerDevice?>(null) }
    val back = {
        if (device != null) device = null else onBack()
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = CONNECT_LEDGER_TITLE, onDismiss = onBack) {
        val d = device
        if (d == null) {
            LedgerDevicesStep(ledger, onPick = { device = it })
        } else {
            LedgerAccountsStep(
                ledger = ledger,
                device = d,
                inWallet = accounts.map { it.address.lowercase() }.toSet(),
                add = { path, address, name ->
                    walletAccounts.addLedger(LedgerKey(path, d.id, d.name), address, name)
                    onAdded()
                },
            )
        }
    }
}

/** Bluetooth, its permission, and the Ledgers in reach. */
@Composable
private fun LedgerDevicesStep(ledger: Ledger, onPick: (LedgerDevice) -> Unit) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var refusedTick by remember { mutableIntStateOf(0) }
    // Re-checked on every resume (the user may come back from Android settings) and after each answer.
    val granted = remember(lifecycleState, refusedTick) { ledger.hasPermissions() }
    val bluetoothOn = remember(lifecycleState, refusedTick) { ledger.bluetoothOn() }
    val blocked = remember(lifecycleState, refusedTick) {
        val activity = context.hostActivity()
        !granted && activity != null && refusedTick > 0 && ledger.permissions().any { androidPermissionBlocked(activity, it) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // The same refusal bookkeeping a site's request goes through: before Android 12 this is location, which sites ask for too.
        context.hostActivity()?.let { activity -> result.filterValues { !it }.keys.forEach { noteAndroidRefusal(activity, it) } }
        refusedTick++
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refusedTick++ }
    var devices by remember { mutableStateOf<List<LedgerDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanTick by remember { mutableIntStateOf(0) }
    val devLinks = remember { ledger.hasDevLinks() }
    LaunchedEffect(granted, bluetoothOn, scanTick) {
        scanning = true
        try {
            withTimeoutOrNull(SCAN_MS) { ledger.scan().collect { devices = it } }
        } finally {
            scanning = false
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("how") {
            SectionCard(title = "Before you start") {
                Text(
                    "Unlock your Ledger (Nano X, Stax or Flex), open its Ethereum app and keep it close. " +
                        "The first time, Android asks to pair: check that the same code is on the Ledger and the phone, and confirm it on both.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your Ledger’s keys never leave it: every transaction and signature is shown and confirmed on the Ledger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            !ledger.hasBle() -> item("no-ble") {
                SectionCard(title = "Bluetooth") {
                    Text(LedgerException.Kind.BLUETOOTH_UNAVAILABLE.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            !granted -> item("permission") {
                SectionCard(title = "Nearby devices") {
                    Text(
                        if (blocked) {
                            "Android won’t ask again for the permission to find and connect to your Ledger. " +
                                "Turn on Nearby devices for Freedom in Android settings."
                        } else {
                            "Freedom needs Android’s permission to find and connect to your Ledger over Bluetooth. " +
                                "It’s used for nothing else."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (blocked) {
                            TextButton(onClick = { openAppSettings(context) }) { Text("Open Android settings") }
                        } else {
                            TextButton(
                                onClick = { permissions.launch(ledger.permissions()) },
                                modifier = Modifier.testTag("ledger-permission"),
                            ) { Text("Allow") }
                        }
                    }
                }
            }
            !bluetoothOn -> item("bluetooth-off") {
                SectionCard(title = "Bluetooth") {
                    Text(LedgerException.Kind.BLUETOOTH_OFF.message, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            try {
                                enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            } catch (_: ActivityNotFoundException) {
                            } catch (_: SecurityException) {
                            }
                        }) { Text("Turn on Bluetooth") }
                    }
                }
            }
        }
        if ((granted && bluetoothOn) || devLinks || devices.isNotEmpty()) item("devices") {
            SectionCard(title = "Ledgers") {
                if (devices.isEmpty()) {
                    Text(
                        if (scanning) "Looking for Ledgers…" else "No Ledger found. Check that it’s unlocked with Bluetooth on, then look again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                devices.forEach { d ->
                    PageRow(
                        title = d.name,
                        subtitle = when {
                            d.id.startsWith("dev:") -> "Emulator (debug build)"
                            d.paired -> "Paired with this phone"
                            else -> "Nearby · not paired yet"
                        },
                        style = PageRowStyle.Inset,
                        leadingIcon = if (d.id.startsWith("dev:")) Icons.Filled.Usb else Icons.Filled.Bluetooth,
                        onClick = { onPick(d) },
                        modifier = Modifier.testTag("ledger-device"),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        TextButton(onClick = { scanTick++ }) { Text("Look again") }
                    }
                }
            }
        }
    }
}

/** One Ledger's Ethereum accounts, five at a time, to pick one to add. */
@Composable
private fun LedgerAccountsStep(
    ledger: Ledger,
    device: LedgerDevice,
    inWallet: Set<String>,
    add: suspend (path: String, address: String, name: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var scheme by remember { mutableStateOf(LedgerScheme.LIVE) }
    var found by remember(scheme) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember(scheme) { mutableStateOf<Pair<String, String>?>(null) }
    var name by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) }
    // A page is read when asked for: the first on opening or switching layout, the next on Show more.
    var wanted by remember(scheme) { mutableIntStateOf(ACCOUNTS_PER_PAGE) }

    LaunchedEffect(device, scheme, wanted, loadTick) {
        if (found.size >= wanted) return@LaunchedEffect
        loading = true
        error = null
        try {
            found = found + ledger.accounts(device, scheme, found.size, wanted - found.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LedgerException) {
            error = e.message
        } catch (e: Exception) {
            error = LedgerException.Kind.UNKNOWN.message
        } finally {
            loading = false
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("device") {
            SectionCard(title = device.name) {
                Text("Layout", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerScheme.entries.forEach { s ->
                        FilterChip(
                            selected = s == scheme,
                            enabled = !loading && !adding,
                            onClick = { scheme = s },
                            label = { Text(s.label) },
                        )
                    }
                }
                Text(
                    "Ledger Live makes accounts at m/44'/60'/n'/0/0; wallets like MyEtherWallet used m/44'/60'/0'/n.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("accounts") {
            SectionCard(title = "Accounts on this Ledger") {
                found.forEachIndexed { i, (path, address) ->
                    val added = address.lowercase() in inWallet
                    if (i > 0) HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = picked?.first == path,
                                enabled = !added && !adding,
                                role = Role.RadioButton,
                                onClick = { picked = path to address },
                            )
                            .padding(vertical = 6.dp)
                            .testTag("ledger-account"),
                    ) {
                        RadioButton(selected = picked?.first == path, onClick = null, enabled = !added && !adding)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            AddressText(address, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (added) "m/$path · already in this wallet" else "m/$path",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reading accounts from the Ledger…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ledger-error"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (error != null) {
                        TextButton(onClick = { loadTick++ }, enabled = !loading) { Text("Try again") }
                    } else if (found.isNotEmpty()) {
                        TextButton(onClick = { wanted = found.size + ACCOUNTS_PER_PAGE }, enabled = !loading && !adding) { Text("Show more") }
                    }
                }
            }
        }
        if (picked != null) item("add") {
            SectionCard(title = "Add to the wallet") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    enabled = !adding,
                    singleLine = true,
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val (path, address) = picked ?: return@Button
                        adding = true
                        error = null
                        scope.launch {
                            try {
                                add(path, address, name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: DuplicateAccountException) {
                                error = "That account is already in this wallet."
                            } catch (e: Exception) {
                                error = "Couldn’t add the account. The phone may be out of storage."
                            } finally {
                                adding = false
                            }
                        }
                    },
                    enabled = !adding,
                    modifier = Modifier.fillMaxWidth().testTag("ledger-add"),
                ) { Text(if (adding) "Adding…" else "Add account") }
            }
        }
    }
}

/**
 * What the Ledger is waiting for while the phone talks to it (#142): from
 * the Connect page, the Send page, or a site's request — connecting,
 * pairing, "Unlock your Ledger", "Open the Ethereum app", or the
 * confirmation itself — with Cancel (Back too), which drops the link and
 * ends the request as cancelled. Shown over everything, whatever asked.
 */
@Composable
fun LedgerActivityDialog() {
    val context = LocalContext.current
    val ledger = remember(context) { Ledger.get(context) }
    val activity by ledger.activity.collectAsState()
    val a = activity ?: return
    val (title, text) = ledgerActivityText(a)
    Dialog(
        onDismissRequest = { a.cancel() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.testTag("ledger-activity")) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("ledger-activity-title"))
                }
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    a.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { a.cancel() }, modifier = Modifier.testTag("ledger-cancel")) { Text("Cancel") }
                }
            }
        }
    }
}

/** The dialog's heading and line for [a]'s stage. */
internal fun ledgerActivityText(a: Ledger.Activity): Pair<String, String> = when (a.stage) {
    Ledger.Stage.CONNECTING -> "Connecting to your Ledger…" to "Keep it unlocked and close to the phone."
    Ledger.Stage.PAIRING -> "Pair with your Ledger" to
        "Check that the same code is on the Ledger and on the phone, then confirm it on both."
    Ledger.Stage.UNLOCK -> "Unlock your Ledger" to "Enter your PIN on the Ledger. This waits for it."
    Ledger.Stage.OPEN_APP -> "Open the Ethereum app" to "On the Ledger, open the Ethereum app. This waits for it."
    Ledger.Stage.READING -> "Reading accounts…" to "Addresses only: nothing is signed."
    Ledger.Stage.CONFIRM -> a.purpose to "Check the details on the Ledger’s screen, then approve or reject them there."
}

private tailrec fun android.content.Context.hostActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.hostActivity()
    else -> null
}
