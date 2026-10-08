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
 * Runs once the external endpoints are known, and again whenever the set
 * of gateway ports changes ([Gateways.loopbackGatewayPortsFlow]): the
 * embedded IPFS gateway's port is only known once the node reports it, and
 * the user can point an external endpoint at this device. Like the
 * Disconnect it stands in for, it leaves a site's feed records and
 * publisher identities: those are keys and data, not a connection, and
 * are used again only by a site connected again.
 */
object GatewayOriginSweep {
    private const val TAG = "GatewayOriginSweep"

    /** Follow the gateway ports for this process; main thread. */
    suspend fun run(context: Context) {
        val app = context.applicationContext
        Gateways.awaitExternalEndpoints()
        Gateways.loopbackGatewayPortsFlow.collect { sweep(app) }
    }

    /** The [origins] that are a gateway's own origin ([isLoopbackGatewayOrigin]), each once. */
    internal fun gatewayOrigins(origins: Iterable<String>): List<String> =
        origins.filter(::isLoopbackGatewayOrigin).distinct()

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
            for (a in store.allowances.first().filter { isLoopbackGatewayOrigin(it.origin) }) {
                if (!store.revoke(a.origin, a.chainId, a.asset, a.account)) Log.w(TAG, "couldn't take a gateway origin's x402 allowance")
            }
        }
        step("site permissions") {
            val broker = SitePermissionBroker.get(app)
            for (entry in broker.entries.first()) {
                if (isLoopbackGatewayOrigin(entry.origin) || isLoopbackGatewayOrigin(entry.top)) broker.revoke(entry)
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
