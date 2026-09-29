package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** One token's balance as the wallet last read it (#104). */
sealed interface TokenBalance {
    /** [raw] base units, read through the chain's verification ladder with [trust]. */
    data class Known(val raw: BigInteger, val trust: ChainTrust) : TokenBalance

    /**
     * The last read failed ([reason], for the user). [previous] is the
     * balance read before, if any — shown as not updated rather than
     * dropped, but never passed off as current.
     */
    data class Failed(val reason: String, val previous: Known?) : TokenBalance
}

/**
 * Reads token balances (#104) — iOS's `TokenBalanceFetcher`, desktop's
 * `balance-service.js`: the native currency by `eth_getBalance`, each
 * ERC-20 by `eth_call(balanceOf)`, all at once, every read through
 * [WalletRpc] so it gets the chain's verification ladder and says how it
 * was checked. A failed read is a [TokenBalance.Failed], never a zero.
 */
class BalanceFetcher(private val rpc: WalletRpc) {
    suspend fun fetch(holder: String, tokens: List<Token>): Map<String, TokenBalance> = coroutineScope {
        tokens.map { token -> async { token.key to fetchOne(holder, token) } }.awaitAll().toMap()
    }

    private suspend fun fetchOne(holder: String, token: Token): TokenBalance = try {
        if (token.address == null) {
            val r = rpc.balance(token.chainId, holder)
            TokenBalance.Known(r.value, r.trust)
        } else {
            val call = JSONObject().put("to", token.address).put("data", Erc20.balanceOfData(holder))
            val r = rpc.call(token.chainId, call)
            val value = Erc20.decodeUint256(r.value)
            if (value == null) {
                TokenBalance.Failed("the ${token.symbol} contract gave no balance", null)
            } else {
                TokenBalance.Known(value, r.trust)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChainRpcException) {
        TokenBalance.Failed(failureReason(e), null)
    }

    companion object {
        internal fun failureReason(e: ChainRpcException): String = when (e) {
            is ChainRpcException.UnknownChain -> "the chain isn’t set up"
            is ChainRpcException.AllSourcesFailed -> "no RPC answered"
            is ChainRpcException.Rpc -> "the RPC answered with an error (${e.code})"
            is ChainRpcException.InvalidResponse -> "the RPC’s answer made no sense"
            else -> "couldn’t read it"
        }
    }
}

/**
 * The balances read this session, per address (lower case) and token
 * key, so a page reopened or an account switched back to shows its last
 * reading at once while a fresh one runs. Memory only: nothing about
 * what an address holds is written to disk. [forget] drops it all
 * (Remove wallet).
 */
class WalletBalances(private val fetcher: () -> BalanceFetcher) {
    private val _byAddress = MutableStateFlow<Map<String, Map<String, TokenBalance>>>(emptyMap())
    val byAddress: StateFlow<Map<String, Map<String, TokenBalance>>> = _byAddress.asStateFlow()

    /**
     * Reads [tokens] for [holder] and files each answer; a failure keeps
     * the earlier reading as [TokenBalance.Failed.previous].
     */
    suspend fun refresh(holder: String, tokens: List<Token>) {
        val generation = synchronized(this) { this.generation }
        val fresh = fetcher().fetch(holder, tokens)
        val key = holder.lowercase()
        synchronized(this) {
            // The wallet was removed while this read ran: file nothing.
            if (generation != this.generation) return
            file(key, fresh)
        }
    }

    private fun file(key: String, fresh: Map<String, TokenBalance>) {
        _byAddress.update { all ->
            val before = all[key].orEmpty()
            val merged = before + fresh.mapValues { (token, balance) ->
                if (balance is TokenBalance.Failed) {
                    val previous = when (val old = before[token]) {
                        is TokenBalance.Known -> old
                        is TokenBalance.Failed -> old.previous
                        null -> null
                    }
                    balance.copy(previous = previous)
                } else {
                    balance
                }
            }
            all + (key to merged)
        }
    }

    private var generation = 0L

    fun forget() {
        synchronized(this) {
            generation++
            _byAddress.value = emptyMap()
        }
    }
}
