package baby.freedom.mobile.browser

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.math.BigInteger
import java.util.concurrent.Executors

internal const val RECEIVE_TITLE = "Receive"
internal const val SCAN_TITLE = "Scan QR code"

/**
 * [content] as a QR code's modules: iOS's `ReceiveView` settings — error
 * correction M, and no margin here, since [QrCodeImage] draws the quiet
 * zone itself.
 */
internal fun qrMatrix(content: String): BitMatrix = QRCodeWriter().encode(
    content,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
)

/** The quiet zone around a QR code, in modules: the spec's minimum. */
private const val QUIET_ZONE = 4

/**
 * [content] as a black-on-white QR code filling the (square) space it's
 * given, quiet zone included. Always black on white, whatever the theme:
 * plenty of scanners can't read an inverted code. Modules are drawn on
 * whole pixels so no edge blurs.
 */
@Composable
internal fun QrCodeImage(content: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { qrMatrix(content) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val modules = matrix.width + 2 * QUIET_ZONE
        val module = kotlin.math.floor(size.minDimension / modules).coerceAtLeast(1f)
        val origin = Offset(
            kotlin.math.floor((size.width - matrix.width * module) / 2),
            kotlin.math.floor((size.height - matrix.height * module) / 2),
        )
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                // One rectangle per run of dark modules on the row.
                val start = x
                while (x < matrix.width && matrix[x, y]) x++
                drawRect(
                    Color.Black,
                    topLeft = Offset(origin.x + start * module, origin.y + y * module),
                    size = Size((x - start) * module, module),
                )
            }
        }
    }
}

/**
 * The receive page (#106): [account]'s address as a QR code and as
 * text, with Copy. The code holds the plain address, as on iOS — the
 * same address takes funds on every EVM chain, and plenty of scanners
 * don't read EIP-681 `ethereum:` links.
 */
@Composable
internal fun ReceivePage(account: WalletAccount, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = RECEIVE_TITLE, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("code") {
                SectionCard(title = account.name) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodeImage(
                            account.address,
                            description = "QR code of ${account.name}’s address",
                            modifier = Modifier.fillMaxWidth().widthIn(max = 300.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        AddressText(
                            account.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { copyToClipboard(context, account.address) }) { Text("Copy address") }
                    }
                }
            }
            item("note") {
                Text(
                    "Send ETH, xDAI or tokens to this address on Ethereum, Gnosis or any other EVM " +
                        "chain: it’s the same address on all of them. Ask the sender which network " +
                        "they’re sending on, so you know where to look for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** A value line of a scanned code's card: what it is, then the value, then maybe a note under it. */
internal data class ScannedLine(val label: String, val value: String, val note: String? = null)

/**
 * The lines the scan page shows for [code]: who gets paid, on which
 * network, how much of what. [chains] are the user's networks and
 * [accounts] their own wallet accounts, so a code for one of them says so.
 */
internal fun scannedLines(code: ScannedCode, chains: List<Chain>, accounts: List<WalletAccount>): List<ScannedLine> {
    fun own(address: String) = accounts.firstOrNull { it.address.equals(address, ignoreCase = true) }
        ?.let { "This is your ${it.name}" }
    return when (code) {
        is ScannedCode.Address -> listOf(ScannedLine("Address", code.address, own(code.address)))
        is ScannedCode.Payment -> {
            val chain = code.chainId?.let { id -> chains.firstOrNull { it.id == id } }
            val network = when {
                code.chainId == null -> ScannedLine(
                    "Network",
                    "Not given",
                    "Ask the sender which network the payment is for.",
                )
                chain == null -> ScannedLine(
                    "Network",
                    "Chain ID ${code.chainId}",
                    "Not one of your networks.",
                )
                else -> ScannedLine("Network", chain.name)
            }
            val token = code.token?.let { contract ->
                TokenRegistry.builtins.firstOrNull { it.chainId == code.chainId && it.address.equals(contract, true) }
            }
            val asset = when {
                code.token == null -> null
                token != null -> ScannedLine("Token", "${token.symbol} (${token.name})", code.token)
                else -> ScannedLine("Token", code.token, "A token Freedom doesn’t know. Check it with the sender.")
            }
            val amount = code.amount?.let { raw ->
                val decimalsAndSymbol = when {
                    code.token == null && chain != null -> chain.decimals to chain.symbol
                    token != null -> token.decimals to token.symbol
                    else -> null
                }
                if (decimalsAndSymbol != null) {
                    val (decimals, symbol) = decimalsAndSymbol
                    ScannedLine("Amount", "${exactAmount(raw, decimals)} $symbol")
                } else {
                    val units = if (raw == BigInteger.ONE) "base unit" else "base units"
                    ScannedLine(
                        "Amount",
                        "$raw $units",
                        if (code.token == null) {
                            "The network isn’t known, so this is in its currency’s smallest unit (like wei for ETH)."
                        } else {
                            "The token’s decimals aren’t known, so this is its smallest unit."
                        },
                    )
                }
            } ?: ScannedLine("Amount", "Not given")
            listOfNotNull(ScannedLine("Pay to", code.recipient, own(code.recipient)), network, asset, amount)
        }
        is ScannedCode.Pairing, is ScannedCode.Unrecognized -> emptyList()
    }
}

/** [raw] base units with every digit kept: a request's amount must not be rounded. */
internal fun exactAmount(raw: BigInteger, decimals: Int): String =
    TokenAmounts.format(raw, decimals, maxFraction = maxOf(decimals, 1))

/**
 * The scan page (#106): the camera ([QrScanner]) or a pasted link, and
 * what the wallet makes of it ([ScannedCode]). Addresses and payment
 * requests can be copied; sending and pairing with desktop Freedom come
 * with their own features, so this page says what the code is and
 * doesn't act on it.
 *
 * The decoded text lives only in plain `remember` state: a pairing code
 * is a live session secret, and nothing scanned belongs in the
 * saved-instance-state bundle.
 */
@Composable
internal fun ScanPage(chains: List<Chain>, accounts: List<WalletAccount>, onBack: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<ScannedCode?>(null) }
    var pasted by remember { mutableStateOf("") }
    // The same code seen frame after frame reads once while it stays in view (iOS's lastCode).
    val dedup = remember { ScanDedup() }
    // The codes still in front of the camera must not replace what was just
    // pasted: not on their next frame, nor after the camera was stopped or
    // missed them for a while. Clearing the paste field lets them read again
    // on the next frame.
    fun readPasted() {
        dedup.holdRecent()
        result = ScannedCode.parse(pasted)
    }
    // Held here, not in the scanner: the scanner sits in a LazyColumn item,
    // whose plain remember is lost when it scrolls off screen.
    val cameraPermission = rememberCameraPermissionState()
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = SCAN_TITLE, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("camera") {
                SectionCard(title = "Camera") {
                    QrScanner(
                        permission = cameraPermission,
                        onCode = { text -> if (dedup.isNew(text)) result = ScannedCode.parse(text) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Point the camera at an address, a payment request or a pairing code. " +
                            "Codes are read on this phone; nothing the camera sees is saved or sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            result?.let { code ->
                item("result") {
                    ScannedCodeSection(code, scannedLines(code, chains, accounts), onCopy = { copyToClipboard(context, it) })
                }
            }
            item("paste") {
                SectionCard(title = "Or paste it") {
                    // A pairing link is a session secret: the keyboard neither corrects nor learns it.
                    TabTextInput(private = true) {
                        NoSuggestionsTextInput {
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = {
                                    pasted = it
                                    if (it.isBlank()) dedup.release()
                                },
                                placeholder = { Text("0x…, ethereum:… or openlv://…") },
                                singleLine = true,
                                keyboardOptions = urlKeyboardOptions(ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = { if (pasted.isNotBlank()) readPasted() },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { readPasted() },
                            enabled = pasted.isNotBlank(),
                        ) { Text("Read") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedCodeSection(code: ScannedCode, lines: List<ScannedLine>, onCopy: (String) -> Unit) {
    val title = when (code) {
        is ScannedCode.Address -> "Address"
        is ScannedCode.Payment -> "Payment request"
        is ScannedCode.Pairing -> "Pairing code"
        is ScannedCode.Unrecognized -> "Can’t use this code"
    }
    SectionCard(title = title) {
        for (line in lines) {
            Text(
                line.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.value.startsWith("0x") && line.value.length == 42) {
                SelectionContainer {
                    AddressText(
                        line.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(line.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            line.note?.let { note ->
                if (note.startsWith("0x") && note.length == 42) {
                    AddressText(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when (code) {
            is ScannedCode.Address, is ScannedCode.Payment -> {
                val address = if (code is ScannedCode.Address) code.address else (code as ScannedCode.Payment).recipient
                if (code is ScannedCode.Payment) {
                    Text(
                        "Sending from this wallet isn’t available yet. To pay from another wallet, " +
                            "copy the address and check the network and amount there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCopy(address) }) {
                        Text(if (code is ScannedCode.Payment) "Copy address to pay" else "Copy address")
                    }
                }
            }
            is ScannedCode.Pairing -> Text(
                "This code connects desktop Freedom to this phone, so the phone can sign for it. " +
                    "Signing for desktop Freedom isn’t available on Android yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is ScannedCode.Unrecognized -> Text(code.reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Reads a QR code off one camera frame: the luminance (Y) plane is all
 * a QR decoder needs. Tries the code as dark-on-light and then as
 * light-on-dark. Returns the decoded text, or null.
 */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: com.google.zxing.LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: Exception) {
        // NotFound/Checksum/Format, and anything else a garbled frame
        // throws: a frame that doesn't read is simply the next frame's turn.
        null
    } finally {
        reader.reset()
    }
}

/** The Y plane of [image], and its row stride. */
private fun ImageProxy.luminance(): Pair<ByteArray, Int> {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    // The last row can stop short of the stride: size for full rows anyway.
    val bytes = ByteArray(maxOf(buffer.remaining(), plane.rowStride * height))
    buffer.get(bytes, 0, buffer.remaining())
    return bytes to plane.rowStride
}

/**
 * iOS's `lastCode`, bounded in time: [isNew] is false for a code the
 * camera already reported less than [goneAfterMs] ago, true otherwise.
 * A code in view is decoded several times a second, so the window keeps
 * renewing while it stays there; once it has been out of view for
 * [goneAfterMs], pointing the camera at it again reads it again. The
 * window is kept per code, not just for the latest one: with two codes
 * in frame the decoder can read A, B, A, B…, and a single `lastCode`
 * would call each of them new every frame and flip the result between
 * them. So a code only reads again once it has itself been out of view
 * for the window, whatever else was read in between.
 *
 * A paste ([holdRecent]) is stronger than that window. The window runs on
 * the clock, which also runs while the camera is stopped (app in the
 * background, camera card scrolled away) or can't decode a frame (blur,
 * glare), so a code still in front of the camera would read as new again
 * and replace what was pasted. Instead, the codes the camera was reading
 * when the paste landed are held back with no time limit: its last code,
 * and every other code it reported within [goneAfterMs] of that one (two
 * codes in frame, or a neighbouring code the decoder picked up between
 * frames of the first). A code outside that set replaces the paste;
 * [release] (the paste field was cleared) lets the held codes read again
 * on their very next frame, even if they never left view.
 */
internal class ScanDedup(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val goneAfterMs: Long = 2_000,
) {
    private var last: String? = null
    /** Every code reported within [goneAfterMs] of the latest report, with when it was last seen. */
    private val recent = HashMap<String, Long>()
    private var held: Set<String> = emptySet()

    fun isNew(text: String): Boolean {
        val now = clock()
        if (held.isNotEmpty()) {
            if (text in held) {
                note(text, now)
                return false
            }
            held = emptySet()
        }
        val seen = recent[text]?.let { now - it in 0 until goneAfterMs } == true
        note(text, now)
        return !seen
    }

    private fun note(text: String, now: Long) {
        last = text
        recent[text] = now
        recent.values.removeAll { now - it !in 0 until goneAfterMs }
    }

    /** A result was pasted: the codes the camera was reading mustn't replace it, however long they're gone. */
    fun holdRecent() {
        val latest = last ?: return
        held = held + recent.keys + latest
    }

    /** The paste was cleared: the held codes read again at once, even while still in view. */
    fun release() {
        if (last in held) last = null
        recent.keys.removeAll(held)
        held = emptySet()
    }
}

/**
 * The scan page's camera-permission state, held by the page rather than
 * by [QrScanner]: whether it has asked yet (so the automatic ask happens
 * once per visit, not again each time the scanner scrolls back into
 * view — a second automatic dialog the user dismisses would count as
 * Android's permanent denial), and whether a system dialog is up now.
 */
internal class CameraPermissionState {
    var asked by mutableStateOf(false)
        private set
    /** A system permission dialog is on screen: nothing about the answer is known yet. */
    var requesting by mutableStateOf(false)
        internal set
    var refusedTick by mutableStateOf(0)
        internal set
    internal var launcher: (() -> Unit)? = null

    fun request() {
        val launch = launcher ?: return
        asked = true
        requesting = true
        launch()
    }
}

@Composable
internal fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val state = remember { CameraPermissionState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.requesting = false
        if (!ok) {
            context.findHostActivity()?.let { noteAndroidRefusal(it, Manifest.permission.CAMERA) }
            state.refusedTick++
        }
    }
    state.launcher = { launcher.launch(Manifest.permission.CAMERA) }
    return state
}

/**
 * The camera viewfinder, reporting every QR code it decodes to
 * [onCode] on the main thread (the same code over and over while it's
 * in view — the caller dedupes). Decoding is ZXing on-device; frames
 * are never kept.
 *
 * Camera permission ([permission], held by the page): asked for once,
 * as the scanner first appears (the user just chose to scan), through the same refusal bookkeeping as a
 * site's camera ([noteAndroidRefusal]), so a refusal here still lets a
 * site's later camera request point the user at Android settings. Once
 * Android won't ask again, the button opens the app's settings instead.
 */
@Composable
internal fun QrScanner(permission: CameraPermissionState, onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    // Re-checked on every resume: the user may come back from Android settings.
    val granted = remember(lifecycleState) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    // Only while the activity is in front: a system dialog must never pop over another app.
    LaunchedEffect(granted, lifecycleState) {
        if (!granted && !permission.asked && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            permission.request()
        }
    }
    if (granted) {
        CameraPreview(onCode, modifier)
        return
    }
    // Not before the first answer, nor while a dialog is up: the refusal
    // record can be stale (permissions reset in settings), and "isn't
    // allowed" must not sit behind a dialog that is asking right now.
    val blocked = remember(lifecycleState, permission.refusedTick, permission.asked, permission.requesting) {
        permission.asked && !permission.requesting &&
            (context.findHostActivity()?.let { androidPermissionBlocked(it, Manifest.permission.CAMERA) } ?: false)
    }
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (blocked) {
                "Freedom isn’t allowed to use the camera. Turn it on in Android settings to scan, " +
                    "or paste the code below."
            } else {
                "Scanning needs the camera. You can also paste the code below."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (blocked) {
            OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open Android settings") }
        } else if (permission.asked && !permission.requesting) {
            Button(onClick = { permission.request() }) { Text("Allow camera") }
        }
    }
}

/** Whether the camera failed to start, and why, in a sentence. */
private sealed class CameraProblem(val text: String) {
    object NoCamera : CameraProblem("This device has no camera Freedom can use. Paste the code below instead.")
    object Failed : CameraProblem("The camera couldn’t be started. Paste the code below instead.")
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    var problem by remember { mutableStateOf<CameraProblem?>(null) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            // A TextureView: composes like any other view (a SurfaceView punches through).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(lifecycleOwner, previewView) {
        val main = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor()
        val decoder = QrFrameDecoder()
        var disposed = false
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        analysis.setAnalyzer(executor) { image ->
            val text = try {
                val (bytes, stride) = image.luminance()
                decoder.decode(bytes, stride, image.width, image.height)
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
            if (text != null) main.post { if (!disposed) latestOnCode(text) }
        }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    problem = CameraProblem.NoCamera
                } else {
                    p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    provider = p
                }
            } catch (_: Exception) {
                problem = CameraProblem.Failed
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = problem
        if (current == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                current.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
    }
}

internal fun copyToClipboard(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Address", address))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
