package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import org.json.JSONObject

/**
 * The Swarm node's ACT public key (#541, desktop #1188): the `publicKey`
 * of the node's `/addresses`, which a publisher adds to an ACT grantee
 * list to share access-controlled content with this node. Shown with a
 * copy button on the Swarm node page only. No page-facing method reads
 * it, and a page's request to this device's node for `/addresses` is
 * refused ([NodeApiGuard.DAPP_PATHS]); an external node's own CORS
 * settings decide whether pages can read its. On ant (as of 0.5.78)
 * the key is also the node's `pssPublicKey`, which a site holding the
 * messaging grant gets from `getMessagingIdentity`; Bee keeps a separate
 * PSS key.
 */
internal object ActPublicKey {
    /**
     * Compressed (66 hex, 02/03) or uncompressed (130 hex, 04) secp256k1
     * key, as Bee and ant report it; an optional `0x` is tolerated
     * (desktop's `ACT_PUBLIC_KEY_RE`).
     */
    private val KEY = Regex("^(?:0x)?(?:0[23][0-9a-f]{64}|04[0-9a-f]{128})$", RegexOption.IGNORE_CASE)

    /**
     * The key in an `/addresses` body, exactly as reported; null when
     * there's none, or it isn't a key. With an [overlay], also null
     * unless the body is that node's: this device's node is asked for
     * the key of the overlay the page shows, and a node restarted under
     * another identity in the meantime answers with another overlay.
     */
    fun from(body: String, overlay: String? = null): String? {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        if (overlay != null && !sameOverlay(json.opt("overlay"), overlay)) return null
        return (json.opt("publicKey") as? String)?.takeIf { KEY.matches(it) }
    }

    private fun sameOverlay(reported: Any?, expected: String): Boolean {
        val hex = (reported as? String)?.removeHexPrefix() ?: return false
        return hex.isNotEmpty() && hex.equals(expected.removeHexPrefix(), ignoreCase = true)
    }

    private fun String.removeHexPrefix() = removePrefix("0x").removePrefix("0X")

    /**
     * Which node the key is read from: the external Swarm endpoint the
     * user set (desktop reads whatever endpoint is in use), else this
     * device's node while it runs. Null while there's no node to ask.
     * The embedded node's source names its overlay, so a restart under
     * another identity (#77) is a different source and its key is read
     * again rather than kept; and the node's answer must name that
     * overlay too ([from]).
     */
    fun sourceFor(externalSwarm: String, nodeInfo: NodeInfo): Source? = when {
        externalSwarm.isNotEmpty() -> Source(externalSwarm, external = true, node = externalSwarm)
        nodeInfo.status == NodeStatus.Running && nodeInfo.overlay.isNotBlank() ->
            Source(SwarmNode.GATEWAY_URL, external = false, node = nodeInfo.overlay)
        else -> null
    }

    /** Where a key is read from: [base] is the gateway; [node] tells one node behind it from the next. */
    data class Source(val base: String, val external: Boolean, val node: String)

    /** What [source] last answered: its key, or null when it answered with none. */
    data class Read(val source: Source, val key: String?)

    /**
     * One read's answer: the node's [key], or null when the node answered
     * and has none (a 3xx, a 4xx, a 501/505, or a body with no key, or
     * another overlay's).
     * [fetch] returns no [Answer] at all when the node gave no answer.
     */
    data class Answer(val key: String?)

    /**
     * The key the page shows for [current]: [read]'s, only if it was read
     * from that same node, so a late answer from the node before a switch
     * (another identity, the external endpoint set or cleared) is never
     * shown or copied as this one's. Null reads Unknown.
     */
    fun shown(read: Read?, current: Source?): String? =
        read?.takeIf { current != null && it.source == current }?.key

    /**
     * One read of [source]'s `/addresses`: the node's [Answer] (its key, or
     * none when it answers a 3xx, a 4xx, a 501/505 or a body without
     * one), or null when it gave no answer — it couldn't be reached or
     * didn't answer in time, answered another 5xx, a 408 or a 429 (a
     * passing outage or load), or answered more than [MAX_ANSWER_BYTES].
     */
    suspend fun fetch(source: Source): Answer? = withContext(Dispatchers.IO) {
        try {
            // An onion endpoint while an external Tor proxy is being
            // re-checked waits for that verdict, as the manifest lookup does.
            val started = System.nanoTime()
            if (fetchMayReachOnion(URL(source.base))) TorRouting.awaitOnionRoute(READ_TIMEOUT_MS.toLong())
            val left = READ_TIMEOUT_MS - ((System.nanoTime() - started) / 1_000_000).toInt()
            if (left <= 0) return@withContext null
            val answer = GatewayHttp.requestAt(
                source.base, "GET", "/addresses", emptyMap(), null, left, maxBytes = MAX_ANSWER_BYTES,
            )
            val overlay = if (source.external) null else source.node
            answerOf(answer.status, answer.body, overlay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /**
     * [fetch]'s verdict on an HTTP answer of [status] with [body]. Only a
     * passing condition is no answer: a 5xx other than 501/505, a 408 or
     * a 429 (or a status line that couldn't be read). Anything else the
     * node says the same way each time it's asked, so it's an answer with
     * no key: a 4xx, a 501 or 505, and a 3xx too, since [fetch] follows no
     * redirect (an endpoint put behind a proxy that redirects to https or
     * a login page shows Unknown rather than the key read before).
     */
    internal fun answerOf(status: Int, body: ByteArray, overlay: String?): Answer? = when {
        status in 200..299 -> Answer(from(String(body, Charsets.UTF_8), overlay))
        status < 100 || status == 408 || status == 429 -> null
        status == 501 || status == 505 -> Answer(null)
        status in 500..599 -> null
        else -> Answer(null)
    }

    /**
     * Keeps [source]'s key read for the page, handing each answer to
     * [publish]: only while [lifecycle] is in the foreground (#472),
     * asking again (backing off) while the node has no key to give, and
     * once a key is read, once more on each return to the foreground.
     * An external endpoint is known only by its URL, so the node behind
     * it can be replaced or re-keyed while the page stays open; that
     * read on return shows and copies the new node's key rather than
     * the old one. A node that answers with no key (a 3xx, a 4xx, a
     * 501/505, or a body without one) blanks the key already read, as desktop shows Unknown
     * when its read fails, and is asked again, backing off. A re-read
     * that gets no answer at all keeps the key already read (a passing
     * outage shouldn't blank it) and asks again, backing off, until the
     * node answers. Never returns while the lifecycle lives: cancel the
     * calling coroutine to end it.
     */
    suspend fun keepRead(
        lifecycle: Lifecycle,
        source: Source,
        fetch: suspend (Source) -> Answer?,
        publish: (Read) -> Unit,
    ) {
        lifecycle.pollWhileStartedBackingOff(RETRY_MS, RETRY_MAX_MS) {
            val answer = fetch(source) ?: return@pollWhileStartedBackingOff true
            publish(Read(source, answer.key))
            answer.key == null
        }
    }

    fun copy(context: Context, key: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.node_act_key_clip_label), key))
        // Android 13+ confirms every copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, Strings.get(R.string.node_act_key_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_ANSWER_BYTES = 64 * 1024

    /** An unknown key is asked for again after this, then twice as long each time, up to [RETRY_MAX_MS]. */
    internal const val RETRY_MS = 5_000L
    internal const val RETRY_MAX_MS = 60_000L
}

/**
 * The ACT public key of the node [source] names, for the Swarm node
 * page: read while the page is on screen, asked again (5 s, then
 * backing off to a minute) until the node answers with one, then read
 * once more on each return to the foreground ([ActPublicKey.keepRead]).
 * An answer with no key reads Unknown; no answer keeps the key read.
 * A node switched on is a new source, read at once. Null reads Unknown.
 */
@Composable
internal fun rememberActPublicKey(source: ActPublicKey.Source?): String? {
    val lifecycle = currentLifecycle()
    val read by produceState<ActPublicKey.Read?>(null, source, lifecycle) {
        if (source == null) return@produceState
        ActPublicKey.keepRead(lifecycle, source, ActPublicKey::fetch) { value = it }
    }
    return ActPublicKey.shown(read, source)
}

/**
 * The ACT public key row of the Swarm node's Details card: the whole key
 * (a phone has no hover to show a shortened one's rest), a copy button,
 * what the key is for, and, when it's the external node's, which node.
 * Unknown with the copy button off while the node can't be asked.
 */
@Composable
internal fun ActPublicKeyRow(key: String?, source: ActPublicKey.Source?) {
    val context = LocalContext.current
    val label = stringResource(R.string.node_act_key)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            SelectionContainer {
                Text(
                    key ?: stringResource(R.string.node_act_key_unknown),
                    fontFamily = if (key != null) FontFamily.Monospace else FontFamily.Default,
                )
            }
            if (source?.external == true) {
                Text(
                    stringResource(R.string.node_act_key_external, source.base),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.node_act_key_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { key?.let { ActPublicKey.copy(context, it) } }, enabled = key != null) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.node_act_key_copy))
        }
    }
}
