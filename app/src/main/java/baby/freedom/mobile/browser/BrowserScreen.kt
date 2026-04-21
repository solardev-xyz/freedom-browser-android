package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.ens.EnsInput
import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    nodeInfo: NodeInfo,
    initialUrl: String = "https://freedombrowser.eth.limo",
) {
    val state = remember { BrowserState() }
    val ensResolver = remember { EnsResolver() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showNodeSheet by rememberSaveable { mutableStateOf(false) }
    var resolvingEns by remember { mutableStateOf(false) }
    var didInitialLoad by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = state.canGoBack) {
        state.loadUrl("javascript:history.back();void(0);")
    }

    fun submit(raw: String) {
        keyboard?.hide()
        focusManager.clearFocus()

        val trimmed = raw.trim()
        // Let the user type the friendly form and re-submit to reload.
        val canonical = state.effectiveFetchUrl(trimmed)

        val ens = EnsInput.parse(canonical)
        if (ens != null) {
            state.addressBarText = "ens://${ens.name}${ens.suffix}"
            resolvingEns = true
            scope.launch {
                try {
                    val result = ensResolver.resolveContenthash(ens.name)
                    when (result) {
                        is EnsResult.Ok -> {
                            if (result.protocol == "bzz") {
                                val target = result.uri + ens.suffix
                                state.loadUrl(target, ensName = ens.name)
                            } else {
                                snackbarHostState.showSnackbar(
                                    "${ens.name} → ${result.protocol}:// (${result.decoded.take(12)}…) — not supported yet",
                                )
                            }
                        }
                        is EnsResult.NotFound -> snackbarHostState.showSnackbar(
                            "${ens.name}: no contenthash (${result.reason})",
                        )
                        is EnsResult.Unsupported -> snackbarHostState.showSnackbar(
                            "${ens.name}: unsupported contenthash codec ${result.codec}",
                        )
                        is EnsResult.Error -> snackbarHostState.showSnackbar(
                            "ENS lookup failed: ${result.reason}",
                        )
                    }
                } finally {
                    resolvingEns = false
                }
            }
            return
        }

        val url = UrlParser.toUrl(canonical)
        state.addressBarText = url
        state.clearEnsOverride()
        state.loadUrl(url)
    }

    // Kick off the homepage as soon as we're composed. The default lives on
    // a plain HTTPS gateway (eth.limo) so we don't need to wait for bee-lite
    // to be running.
    LaunchedEffect(Unit) {
        if (!didInitialLoad) {
            didInitialLoad = true
            submit(initialUrl)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                state = state,
                nodeInfo = nodeInfo,
                resolvingEns = resolvingEns,
                onSubmit = ::submit,
                onBack = { state.loadUrl("javascript:history.back();void(0);") },
                onForward = { state.loadUrl("javascript:history.forward();void(0);") },
                onReload = {
                    val raw = state.addressBarText.ifBlank { state.url }
                    val refetch = state.effectiveFetchUrl(raw)
                    state.loadUrl(refetch.ifBlank { state.pendingUrl })
                },
                onOpenNodePanel = { showNodeSheet = true },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                if (state.progress in 0..99 || resolvingEns) {
                    if (resolvingEns) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { state.progress / 100f },
                        )
                    }
                }
            }

            BrowserWebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) { data -> Snackbar(snackbarData = data) }
    }

    if (showNodeSheet) {
        NodeDetailsDialog(nodeInfo = nodeInfo, onDismiss = { showNodeSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: BrowserState,
    nodeInfo: NodeInfo,
    resolvingEns: Boolean,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onOpenNodePanel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var addressFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            enabled = state.canGoBack,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(
            onClick = onForward,
            enabled = state.canGoForward,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
        }

        // Custom-built address pill. We can't use M3's `TextField` here
        // because its filled variant's content padding shifts by a couple
        // of dp between focused / unfocused, which makes the pill appear
        // to grow when tapped. `BasicTextField` + a fixed-height Box gives
        // us a rock-steady 40 dp bubble.
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = state.addressBarText,
                onValueChange = { state.addressBarText = it },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { addressFocused = it.isFocused },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmit(state.addressBarText) },
                ),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (state.addressBarText.isEmpty()) {
                                Text(
                                    text = "Search or enter address",
                                    color = onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                        // Trailing action — sized to the pill, never pushes it taller.
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                addressFocused && state.addressBarText.isNotEmpty() -> {
                                    IconButton(
                                        onClick = { state.addressBarText = "" },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Clear,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                resolvingEns || state.progress in 0..99 -> {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                else -> {
                                    IconButton(
                                        onClick = onReload,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = "Reload",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }

        NodeStatusDot(
            nodeInfo = nodeInfo,
            onClick = onOpenNodePanel,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun NodeStatusDot(
    nodeInfo: NodeInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when (nodeInfo.status) {
        NodeStatus.Running -> Color(0xFF22C55E)
        NodeStatus.Starting -> Color(0xFFF59E0B)
        NodeStatus.Stopped -> Color(0xFF94A3B8)
        NodeStatus.Error -> Color(0xFFEF4444)
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
private fun NodeDetailsDialog(nodeInfo: NodeInfo, onDismiss: () -> Unit) {
    val (color, icon, label) = statusTriple(nodeInfo.status)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text("Swarm node — $label")
            }
        },
        text = {
            Column {
                NodeDetailRow("Mode", "ultra-light")
                NodeDetailRow("Peers", nodeInfo.connectedPeers.toString())
                if (nodeInfo.walletAddress.isNotEmpty()) {
                    NodeDetailRow("Wallet", nodeInfo.walletAddress, mono = true)
                }
                val err = nodeInfo.errorMessage
                if (!err.isNullOrBlank()) {
                    NodeDetailRow("Error", err)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Gateway: ${SwarmNode.GATEWAY_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private data class StatusTriple(
    val color: Color,
    val icon: ImageVector,
    val label: String,
)

private fun statusTriple(status: NodeStatus): StatusTriple = when (status) {
    NodeStatus.Running -> StatusTriple(Color(0xFF22C55E), Icons.Filled.CheckCircle, "Running")
    NodeStatus.Starting -> StatusTriple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…")
    NodeStatus.Stopped -> StatusTriple(Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Stopped")
    NodeStatus.Error -> StatusTriple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error")
}

@Composable
private fun NodeDetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

