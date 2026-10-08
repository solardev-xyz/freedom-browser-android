package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import baby.freedom.mobile.data.AutoApproveStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.data.RadicleGrantStore
import baby.freedom.mobile.data.SwarmGrantStore
import baby.freedom.mobile.data.X402Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Takes away what an earlier release let a content gateway's own origin
 * on the device hold (#457): a raw gateway origin such as
 * `http://127.0.0.1:1633` is one origin for every Swarm or IPFS root
 * loaded through it, so it no longer gets a provider ([providerOriginKey])
 * or a site permission ([sitePermissionOriginKey]). What was granted to it
 * before can't be used any more, and mustn't be listed as if it could:
 * this disconnects it the way the user's own Disconnect does (its pages
 * hear `accountsChanged []` / `disconnect`), and forgets its remembered
 * site permissions.
 *
 * Only the embedded Swarm gateway's port ([Gateways.EMBEDDED_SWARM_BASE],
 * 1633 in every release) is swept, on every loopback name. That port has
 * been a gateway's in every release, so a grant under it can only have
 * been given to the gateway's shared origin. The other gateway ports
 * aren't: the embedded IPFS gateway draws an ephemeral port on each start,
 * and an external endpoint is wherever the user points it. A grant under
 * one of those ports may as well be a dev server's (`http://localhost:N`
 * connected while it ran, when nothing else held N), and nothing stored
 * tells the two apart, so taking it away for good would disconnect an
 * unrelated site. Such a grant is refused while a gateway holds its port
 * ([providerOriginKey], [sitePermissionOriginKey] read the live ports) and
 * stays listed in Connected sites, where the user can disconnect it; a
 * grant an earlier release left under an earlier run's IPFS port is, from
 * then on, a loopback site's grant like any other.
 *
 * Like the Disconnect it stands in for, it leaves a site's feed records
 * and publisher identities: those are keys and data, not a connection,
 * and are used again only by a site connected again.
 */
object GatewayOriginSweep {
    private const val TAG = "GatewayOriginSweep"

    /** Once per process; main thread. */
    suspend fun run(context: Context) = sweep(context.applicationContext)

    /** The embedded Swarm gateway's port, the one port swept. */
    private val SWEPT_PORT: Int? = permissionOriginKey(Gateways.EMBEDDED_SWARM_BASE)?.let(::loopbackPort)

    /**
     * Whether [origin] (a [permissionOriginKey]) is the embedded Swarm
     * gateway's own origin on some loopback name, `http` or `https`:
     * what the sweep takes. Not the live [isLoopbackGatewayOrigin], whose
     * ephemeral and user-set ports may belong to another site's grant.
     */
    internal fun isSweptOrigin(origin: String): Boolean =
        SWEPT_PORT != null && loopbackPort(origin) == SWEPT_PORT

    /** The [origins] the sweep takes ([isSweptOrigin]), each once. */
    internal fun gatewayOrigins(origins: Iterable<String>): List<String> =
        origins.filter(::isSweptOrigin).distinct()

    private suspend fun sweep(app: Context) {
        step("wallet connections") {
            // Unreadable: nothing is known to be left, and nothing is taken.
            val grants = DappGrantStore.get(app).allOrUnreadable.first().orEmpty().map { it.origin }
            val rules = AutoApproveStore.get(app).allOrUnreadable.first().orEmpty().map { it.origin }
            for (origin in gatewayOrigins(grants + rules)) {
                if (!EthereumProviders.disconnect(app, origin)) Log.w(TAG, "couldn't disconnect a gateway origin's wallet connection")
            }
        }
        step("Swarm connections") {
            for (origin in gatewayOrigins(SwarmGrantStore.get(app).all.first().map { it.origin })) {
                if (!SwarmProviders.disconnect(app, origin)) Log.w(TAG, "couldn't disconnect a gateway origin from Swarm")
            }
        }
        step("Radicle connections") {
            val store = RadicleGrantStore.get(app)
            for (origin in gatewayOrigins(store.all.first().map { it.origin })) {
                if (store.revoke(origin)) RadicleProviders.revoked(origin)
                else Log.w(TAG, "couldn't disconnect a gateway origin from Radicle")
            }
        }
        step("x402 allowances") {
            val store = X402Store.get(app)
            for (a in store.allowances.first().filter { isSweptOrigin(it.origin) }) {
                if (!store.revoke(a.origin, a.chainId, a.asset, a.account)) Log.w(TAG, "couldn't take a gateway origin's x402 allowance")
            }
        }
        step("site permissions") {
            val broker = SitePermissionBroker.get(app)
            for (entry in broker.entries.first()) {
                if (isSweptOrigin(entry.origin) || isSweptOrigin(entry.top)) broker.revoke(entry)
            }
        }
    }

    /** One store's part: a failure in one doesn't keep the others from being swept. */
    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sweeping $what failed", e)
        }
    }
}
