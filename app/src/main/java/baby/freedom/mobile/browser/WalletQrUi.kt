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
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.ledger.Ledger
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val SCAN_TITLE: String get() = Strings.get(R.string.wallet_qr_scan_title)

/**
 * [content] as a QR code's modules: iOS's `ReceiveView` settings — error
 * correction M, and no margin here, since [QrCodeImage] draws the quiet
 * zone itself.
 *
 * Text beyond ASCII (a Safe message request's own words: curly quotes,
 * `€`, Cyrillic, CJK, emoji) is encoded as UTF-8 and marked so with an
 * ECI header, which ZXing's reader (and every current scanner) honours.
 * Without the hint ZXing writes ISO-8859-1 and turns everything outside
 * Latin-1 into `?`, so the text scanned on the other phone no longer
 * matches what it's asked to sign. Plain ASCII (addresses, payment URIs,
 * signatures) stays exactly as before, with no ECI header an older
 * scanner might stumble on.
 */
internal fun qrMatrix(content: String): BitMatrix = QRCodeWriter().encode(
    content,
    BarcodeFormat.QR_CODE,
    0,
    0,
    buildMap {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        put(EncodeHintType.MARGIN, 0)
        if (content.any { it.code > 0x7F }) put(EncodeHintType.CHARACTER_SET, Charsets.UTF_8.name())
    },
)

/**
 * How many bytes [content] takes in a QR code: what a code's capacity
 * limits, not its character count — a CJK character is 3 bytes, an emoji 4.
 */
internal fun qrBytes(content: String): Int = content.toByteArray(Charsets.UTF_8).size

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
 * [address] in groups of four for reading aloud or comparing (#422):
 * `0x5aAe b605 3F3E … eAed` — the `0x` stays on the first group. Joined
 * with spaces it's what Receive shows; Copy and Share hand over the
 * address itself, without them.
 */
internal fun addressGroups(address: String): List<String> {
    val hex = address.removePrefix("0x").removePrefix("0X")
    if (hex.length == address.length) return hex.chunked(4)
    val groups = hex.chunked(4)
    return listOf(address.take(2) + groups.firstOrNull().orEmpty()) + groups.drop(1)
}

/** [address] as Receive shows it: grouped, the first and last group emphasised (they're what people check). */
@Composable
internal fun groupedAddress(address: String): AnnotatedString {
    val groups = addressGroups(address)
    val strong = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    val muted = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    return buildAnnotatedString {
        groups.forEachIndexed { i, group ->
            if (i > 0) append(' ')
            withStyle(if (i == 0 || i == groups.lastIndex) strong else muted) { append(group) }
        }
    }
}

/** Hands [address] to another app through the system share sheet (#422). */
internal fun shareAddress(context: Context, address: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, address)
    }
    // A device with no share target at all would throw.
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * The receive page (#106): [account]'s address as a QR code and as
 * text in groups of four, with Copy and Share and a one-line note that
 * it's the same on every network (#422). The code holds the plain
 * address, as on iOS — the same address takes funds on every EVM chain,
 * and plenty of scanners don't read EIP-681 `ethereum:` links.
 */
@Composable
internal fun ReceivePage(account: WalletAccount, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    // A Ledger account's Verify on Ledger (#365): held here, above the list, so scrolling never resets it.
    val ledger = remember(context) { Ledger.get(context) }
    val scope = rememberCoroutineScope()
    var verifying by remember(account.address) { mutableStateOf(false) }
    var verified by remember(account.address) { mutableStateOf(false) }
    var verifyError by remember(account.address) { mutableStateOf<String?>(null) }
    FullScreenScaffold(title = stringResource(R.string.wallet_qr_receive_title), onDismiss = onBack) {
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
                            description = stringResource(R.string.wallet_qr_receive_code_description, account.name),
                            modifier = Modifier.fillMaxWidth().widthIn(max = 300.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(
                            groupedAddress(account.address),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wallet_qr_receive_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    SheetButtonRow {
                        Button(onClick = { copyToClipboard(context, account.address) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.common_copy_address))
                        }
                        OutlinedButton(onClick = { shareAddress(context, account.address) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.wallet_qr_share_address))
                        }
                    }
                }
            }
            if (account.ledger != null) item("verify") {
                LedgerVerifyCard(
                    verifying = verifying,
                    verified = verified,
                    error = verifyError,
                    onVerify = {
                        verifying = true
                        verifyError = null
                        scope.launch {
                            try {
                                ledger.verifyAddress(account)
                                verified = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                verified = false
                                verifyError = ledgerVerifyFailure(e)
                            } finally {
                                verifying = false
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Receive's Verify on Ledger (#365): asks the Ledger holding the account
 * to show its address on its own screen, so what's shared was checked on
 * the device rather than taken from the phone; then says how that went.
 */
@Composable
private fun LedgerVerifyCard(verifying: Boolean, verified: Boolean, error: String?, onVerify: () -> Unit) {
    SectionCard(title = stringResource(R.string.signing_ledger_verify_on_ledger)) {
        when {
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("ledger-verify-error").semantics { liveRegion = LiveRegionMode.Polite },
            )
            verified -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.signing_ledger_verify_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("ledger-verify-ok").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            else -> Text(stringResource(R.string.signing_ledger_verify_hint), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onVerify,
            enabled = !verifying,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-verify"),
        ) {
            Text(stringResource(R.string.signing_ledger_verify_on_ledger))
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
        ?.let { Strings.get(R.string.wallet_qr_own_account, it.name) }
    return when (code) {
        is ScannedCode.Address ->
            listOf(ScannedLine(Strings.get(R.string.wallet_qr_line_address), code.address, own(code.address)))
        is ScannedCode.Payment -> {
            val chain = code.chainId?.let { id -> chains.firstOrNull { it.id == id } }
            val network = when {
                code.chainId == null -> ScannedLine(
                    Strings.get(R.string.wallet_qr_line_network),
                    Strings.get(R.string.wallet_qr_not_given),
                    Strings.get(R.string.wallet_qr_network_not_given_note),
                )
                chain == null -> ScannedLine(
                    Strings.get(R.string.wallet_qr_line_network),
                    // The ID as the request wrote it, never locale-formatted: it's what to look up.
                    Strings.get(R.string.wallet_qr_chain_id, code.chainId.toString()),
                    Strings.get(R.string.wallet_qr_network_unknown_note),
                )
                else -> ScannedLine(Strings.get(R.string.wallet_qr_line_network), chain.name)
            }
            val token = code.token?.let { contract ->
                TokenRegistry.builtins.firstOrNull { it.chainId == code.chainId && it.address.equals(contract, true) }
            }
            val asset = when {
                code.token == null -> null
                token != null -> ScannedLine(
                    Strings.get(R.string.wallet_qr_line_token),
                    Strings.get(R.string.wallet_qr_token_symbol_name, token.symbol, token.name),
                    code.token,
                )
                else -> ScannedLine(
                    Strings.get(R.string.wallet_qr_line_token),
                    code.token,
                    Strings.get(R.string.wallet_qr_token_unknown_note),
                )
            }
            val amount = code.amount?.let { raw ->
                val decimalsAndSymbol = when {
                    code.token == null && chain != null -> chain.decimals to chain.symbol
                    token != null -> token.decimals to token.symbol
                    else -> null
                }
                if (decimalsAndSymbol != null) {
                    val (decimals, symbol) = decimalsAndSymbol
                    ScannedLine(Strings.get(R.string.wallet_qr_line_amount), "${exactAmount(raw, decimals)} $symbol")
                } else {
                    ScannedLine(
                        Strings.get(R.string.wallet_qr_line_amount),
                        // The raw number as the request wrote it (no grouping): an amount stays fixed-format.
                        Strings.plural(R.plurals.wallet_qr_base_units, pluralCount(raw), raw.toString()),
                        if (code.token == null) {
                            Strings.get(R.string.wallet_qr_base_units_network_note)
                        } else {
                            Strings.get(R.string.wallet_qr_base_units_token_note)
                        },
                    )
                }
            } ?: ScannedLine(Strings.get(R.string.wallet_qr_line_amount), Strings.get(R.string.wallet_qr_not_given))
            listOfNotNull(
                ScannedLine(Strings.get(R.string.wallet_qr_line_pay_to), code.recipient, own(code.recipient)),
                network,
                asset,
                amount,
            )
        }
        is ScannedCode.Pairing, is ScannedCode.SafeRequest, is ScannedCode.Unrecognized -> emptyList()
    }
}

/**
 * [raw] as the count that picks its plural form: itself when it fits an
 * Int, else a number with the same last nine digits (what plural rules look
 * at), kept above one so it never reads as singular.
 */
private fun pluralCount(raw: BigInteger): Int =
    if (raw.bitLength() < Int.SIZE_BITS) raw.toInt() else raw.mod(BigInteger.valueOf(1_000_000_000L)).toInt() + 1_000_000_000

/** [raw] base units with every digit kept: a request's amount must not be rounded. */
internal fun exactAmount(raw: BigInteger, decimals: Int): String =
    TokenAmounts.format(raw, decimals, maxFraction = maxOf(decimals, 1))

/**
 * The scan page (#106): the camera ([QrScanner]) or a pasted link, and
 * what the wallet makes of it ([ScannedCode]). An address or a payment
 * request Send can pay offers Send / Pay (#422), which opens the Send
 * page filled in ([onSend]) — nothing is signed before its own review —
 * with Copy as the second choice; a request Send can't pay says why.
 *
 * The decoded text lives only in plain `remember` state: a pairing code
 * is a live session secret, and nothing scanned belongs in the
 * saved-instance-state bundle.
 */
@Composable
internal fun ScanPage(
    chains: List<Chain>,
    accounts: List<WalletAccount>,
    onSafeRequest: (String) -> Unit,
    onSend: (SendPrefill) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val session = remember(context) { OpenLvSession.get(context) }
    var result by remember { mutableStateOf<ScannedCode?>(null) }
    // The text [result] was read from: what Send / Pay reads the request from.
    var resultText by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    // A pairing code connects as it's read (#113): the sheets ask before anything is signed or shared.
    fun show(text: String) {
        val code = ScannedCode.parse(text)
        resultText = text
        result = code
        if (code is ScannedCode.Pairing) session.start(code.uri)
        // Another Safe owner's request (#141) opens its own review: nothing is signed before the user asks.
        if (code is ScannedCode.SafeRequest) onSafeRequest(code.json)
    }
    // The same code seen frame after frame reads once while it stays in view (iOS's lastCode).
    val dedup = remember { ScanDedup() }
    // The codes still in front of the camera must not replace what was just
    // pasted: not on their next frame, nor after the camera was stopped or
    // missed them for a while. Clearing the paste field lets them read again
    // on the next frame.
    fun readPasted() {
        dedup.holdRecent()
        show(pasted)
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
                SectionCard(title = stringResource(R.string.wallet_qr_camera_title)) {
                    QrScanner(
                        permission = cameraPermission,
                        onCode = { text -> if (dedup.isNew(text)) show(text) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.wallet_qr_camera_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            result?.let { code ->
                item("result") {
                    if (code is ScannedCode.Pairing) {
                        PairingSection(code.uri)
                    } else {
                        ScannedCodeSection(
                            code,
                            scannedLines(code, chains, accounts),
                            send = scannedRecipient(resultText),
                            onCopy = { copyToClipboard(context, it) },
                            onSend = onSend,
                        )
                    }
                }
            }
            item("paste") {
                SectionCard(title = stringResource(R.string.wallet_qr_paste_title)) {
                    // A pairing link is a session secret: the keyboard neither corrects nor learns it.
                    TabTextInput(private = true) {
                        NoSuggestionsTextInput {
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = {
                                    pasted = it
                                    if (it.isBlank()) dedup.release()
                                },
                                placeholder = { Text(stringResource(R.string.wallet_qr_paste_placeholder)) },
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
                        ) { Text(stringResource(R.string.wallet_qr_read)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedCodeSection(
    code: ScannedCode,
    lines: List<ScannedLine>,
    send: ScannedRecipient,
    onCopy: (String) -> Unit,
    onSend: (SendPrefill) -> Unit,
) {
    val title = when (code) {
        is ScannedCode.Address -> stringResource(R.string.wallet_qr_line_address)
        is ScannedCode.Payment -> stringResource(R.string.wallet_qr_kind_payment)
        is ScannedCode.Pairing -> stringResource(R.string.wallet_qr_kind_pairing)
        is ScannedCode.SafeRequest -> stringResource(R.string.wallet_qr_kind_safe_request)
        is ScannedCode.Unrecognized -> stringResource(R.string.wallet_qr_kind_unrecognized)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (send) {
                        is ScannedRecipient.Fill -> Button(
                            onClick = { onSend(send.sendPrefill()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (code is ScannedCode.Payment) R.string.wallet_qr_pay else R.string.wallet_qr_send_to_address,
                                ),
                            )
                        }
                        // A request Send can't pay (another network, an unknown token): why, in a sentence.
                        is ScannedRecipient.Refused -> Text(
                            send.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(onClick = { onCopy(address) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_copy_address))
                    }
                }
            }
            is ScannedCode.Pairing, is ScannedCode.SafeRequest -> Unit
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
 * Decides whether a decoded code should replace the scan result.
 *
 * The code the result already shows doesn't read again while it stays in
 * view: [isNew] is false for it until it has been out of view for
 * [goneAfterMs] (a code in view is decoded several times a second, so its
 * window keeps renewing), and pointing the camera at it again after that
 * reads it again. A code not seen within the window reads at once.
 *
 * A code that *was* seen within the window but isn't the one shown (the
 * camera read A, then B, and is back on A) reads once the decoder has
 * returned it on [steadyReads] frames in a row with no other code in
 * between. That is what tells "the camera is on A now" apart from two
 * codes in frame, where the decoder reads A, B, A, B… and letting each
 * read as new would flip the result between them every frame: an
 * alternation never forms a streak, so the result stays on whichever
 * code read first, while a code the camera settles on reads within a few
 * frames even if it was in view moments ago.
 *
 * A paste ([holdRecent]) is stronger than that window. The window runs on
 * the clock, which also runs while the camera is stopped (app in the
 * background, camera card scrolled away) or can't decode a frame (blur,
 * glare), so a code still in front of the camera would read as new again
 * and replace what was pasted. Instead, the codes the camera was reading
 * when the paste landed are held back with no time limit, however steady:
 * its last code, and every other code it reported within [goneAfterMs] of
 * that one (two codes in frame, or a neighbouring code the decoder picked
 * up between frames of the first). A code outside that set replaces the
 * paste; [release] (the paste field was cleared) lets the held codes read
 * again on their very next frame, even if they never left view.
 */
internal class ScanDedup(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val goneAfterMs: Long = 2_000,
    private val steadyReads: Int = 3,
) {
    private var last: String? = null
    /** How many frames in a row [last] was read, this one included. */
    private var streak = 0
    /** The code the result shows, if it came from the camera. */
    private var shown: String? = null
    /** Every code reported within [goneAfterMs] of the latest report, with when it was last seen. */
    private val recent = HashMap<String, Long>()
    private var held: Set<String> = emptySet()
    /**
     * The codes [release] let go of: each reads once on its next frame. The
     * camera's own history ([last], [recent]) is kept, so a later paste
     * still holds them even if nothing was decoded in between.
     */
    private var readOnce: Set<String> = emptySet()

    fun isNew(text: String): Boolean {
        val now = clock()
        if (text in readOnce) {
            readOnce = readOnce - text
            note(text, now)
            shown = text
            return true
        }
        // Another code decoded: the camera has moved on, and the released codes go back to the normal rules.
        readOnce = emptySet()
        if (held.isNotEmpty()) {
            if (text in held) {
                note(text, now)
                return false
            }
            held = emptySet()
        }
        val seen = recent[text]?.let { now - it in 0 until goneAfterMs } == true
        note(text, now)
        val new = when {
            !seen -> true
            text == shown -> false
            else -> streak >= steadyReads
        }
        if (new) shown = text
        return new
    }

    private fun note(text: String, now: Long) {
        streak = if (text == last) streak + 1 else 1
        last = text
        recent[text] = now
        recent.values.removeAll { now - it !in 0 until goneAfterMs }
    }

    /** A result was pasted: the codes the camera was reading mustn't replace it, however long they're gone. */
    fun holdRecent() {
        val latest = last ?: return
        held = held + readOnce + recent.keys + latest
        readOnce = emptySet()
        shown = null
    }

    /**
     * The paste was cleared: the held codes read again on their very next
     * frame, even while still in view. What the camera read last is not
     * forgotten, so pasting again before it decodes anything holds the
     * same codes back.
     */
    fun release() {
        readOnce = readOnce + held
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
                stringResource(R.string.wallet_qr_camera_blocked)
            } else {
                stringResource(R.string.wallet_qr_camera_needed)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (blocked) {
            OutlinedButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.common_open_android_settings)) }
        } else if (permission.asked && !permission.requesting) {
            Button(onClick = { permission.request() }) { Text(stringResource(R.string.wallet_qr_allow_camera)) }
        }
    }
}

/** Whether the camera failed to start, and why, in a sentence. */
private sealed class CameraProblem(@StringRes val text: Int) {
    object NoCamera : CameraProblem(R.string.wallet_qr_camera_none)
    object Failed : CameraProblem(R.string.wallet_qr_camera_failed)
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
                stringResource(current.text),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

internal fun openAppSettings(context: Context) {
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
    clipboard.setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.wallet_qr_clip_label), address))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, Strings.get(R.string.wallet_qr_address_copied), Toast.LENGTH_SHORT).show()
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
