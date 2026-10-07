package baby.freedom.swarm

import android.util.Log
import java.math.BigInteger

/**
 * What one spend the user confirmed in the app may broadcast (#116,
 * #117): the exact transactions ant's own flow sends for it, and no
 * others. [owner] is the node's own account (40 lowercase hex, no
 * `0x`), which signs every one of them.
 */
sealed interface SpendPlan {
    val owner: String

    /**
     * Buying a postage batch (`ant_storage_buy_xdai`): swap the xBZZ
     * shortfall ([maxSwapWei] at most, the xDAI total the confirmation
     * showed), approve `amountPerChunk × 2^depth` to the postage contract,
     * `createBatch` with exactly these parameters, then settlement: deploy
     * the node's chequebook if it has none, and bring a new or adopted
     * chequebook's deposit up to the settlement target (ant 0.5.51+ tops
     * up an existing one on any buy, not only the first).
     */
    data class BuyStamp(
        override val owner: String,
        val depth: Int,
        val amountPerChunk: BigInteger,
        val immutable: Boolean,
        val maxSwapWei: BigInteger,
    ) : SpendPlan

    /**
     * Extending one batch (`ant_storage_topup_xdai`): swap, approve
     * `amountPerChunk × 2^depth`, and `topUp` of [batchId] (64 lowercase
     * hex) by exactly [amountPerChunk].
     */
    data class ExtendStamp(
        override val owner: String,
        val batchId: String,
        val depth: Int,
        val amountPerChunk: BigInteger,
        val maxSwapWei: BigInteger,
    ) : SpendPlan

    /**
     * Depositing into the node's chequebook (#117, ant's `POST
     * /chequebook/deposit`): one xBZZ `transfer` of exactly [amountPlur]
     * to [chequebook] (40 lowercase hex), the chequebook the confirmation
     * named. No swap: the xBZZ must already be in the node's account.
     */
    data class DepositChequebook(
        override val owner: String,
        val chequebook: String,
        val amountPlur: BigInteger,
    ) : SpendPlan

    /**
     * Connecting a batch the wallet bought for the node (#115,
     * `ant_storage_connect_batch`): it buys nothing, but on a first
     * connect ant sets up the chequebook — deploys it (issuer = the node)
     * and moves at most [SpendPermit.MAX_SETTLEMENT_DEPOSIT_PLUR] of the
     * node's xBZZ into it, as a first buy does. Nothing else: no swap, no
     * approval, no batch.
     */
    data class ConnectBatch(override val owner: String) : SpendPlan
}

/**
 * The Swarm node's broadcast gate (#114, #116, #117).
 *
 * `ant_jni.c` installs a chain transport on every node, and every
 * `eth_send*` ant makes — from its HTTP gateway, which any app on the
 * device and (via redirects) any page can reach, or from the storage
 * calls the app makes itself — comes here first, on one of ant's
 * threads, through [admit]. With no [SpendPermit] open, every one is
 * refused: nothing outside the app can spend the node's funds. While the
 * app runs a spend the user confirmed ([during]), a broadcast goes
 * through only if it is one of that plan's transactions, each kind at
 * most once — so whatever else reaches the node at the same time, the
 * most that can leave the account is what the user confirmed. A gateway
 * request racing the app's own spend can at worst use up one of its
 * slots, which makes the app's spend fail rather than spend twice.
 *
 * One slot doesn't pin its recipient: a buy's (or a batch connect's,
 * #115) chequebook settlement deposit ([SpendPermit.Slot.SettlementDeposit], at most
 * [SpendPermit.MAX_SETTLEMENT_DEPOSIT_PLUR] = 0.001 xBZZ) may be a `transfer` to any
 * address. On a first buy the chequebook it funds is created in the same
 * flow, so its address can't be known before ant sends the deposit; and
 * since ant 0.5.51 any buy or connect may also top up the deposit of a
 * chequebook ant adopted (one deployed earlier, on this device or
 * another), whose address the app doesn't pin either. That is safe only
 * because no ant gateway route transfers xBZZ to an address the caller
 * chooses. Several routes transfer xBZZ — `POST /chequebook/deposit`
 * (#117), `POST /v0/settlement/deposit`, and `POST /stamps`, whose
 * after-buy hook deploys or tops up a chequebook — but every one pays
 * only the node's own chequebook, the one ant loaded or adopted. So
 * during a buy or connect the only `transfer`s ant can sign go to the
 * node's chequebooks. Recheck this on every ant bump: if ant ever gains a
 * route that pays a caller-chosen address (a withdraw, a cash-out), pin
 * this slot to the node's chequebook first — until then a request racing
 * a buy could send up to 0.001 xBZZ to an address of its choice.
 */
object SpendGuard {
    @Volatile
    private var permit: SpendPermit? = null

    /**
     * Runs [block] (the blocking native spend) with a permit for [plan]
     * open, and closes it however [block] ends. One at a time: a second
     * spend while one runs throws.
     */
    fun <T> during(plan: SpendPlan, block: () -> T): T {
        val p = SpendPermit(plan)
        synchronized(this) {
            check(permit == null) { "another payment is already running" }
            permit = p
        }
        try {
            return block()
        } finally {
            synchronized(this) { if (permit === p) permit = null }
        }
    }

    /**
     * The transport's question (`ant_jni.c`): may the JSON-RPC request
     * [requestJson] go out? Only an `eth_sendRawTransaction` the open
     * permit admits may; never throws (the native side refuses on any
     * exception anyway).
     */
    @JvmStatic
    fun admit(requestJson: String?): Boolean {
        val p = permit
        val verdict = try {
            p != null && requestJson != null && p.admit(requestJson)
        } catch (t: Throwable) {
            false
        }
        // The transaction's kind only; the request itself names nothing secret
        // but isn't worth the log line.
        Log.i(TAG, if (verdict) "admitted a broadcast for the confirmed ${p?.plan?.javaClass?.simpleName}" else "refused a broadcast")
        return verdict
    }

    private const val TAG = "SpendGuard"
}

/**
 * The transactions one [SpendPlan] may broadcast, each kind ([Slot]) at
 * most once. An identical signed transaction admitted before is admitted
 * again: that's ant retrying the same broadcast, not a second spend.
 */
class SpendPermit(val plan: SpendPlan) {
    enum class Slot { Swap, Approve, CreateBatch, TopUp, DeployChequebook, SettlementDeposit, Deposit }

    private val used = HashSet<Slot>()
    private val admitted = HashSet<String>()

    /** Is [requestJson] a broadcast this permit lets through? Marks its slot used if so. */
    @Synchronized
    fun admit(requestJson: String): Boolean {
        val raw = rawTransaction(requestJson) ?: return false
        if (raw in admitted) return true
        val tx = LegacyTx.decode(hexBytes(raw) ?: return false) ?: return false
        val slot = slotFor(tx) ?: return false
        if (!used.add(slot)) return false
        admitted += raw
        return true
    }

    /** Which of the plan's transactions [tx] is, or null if it's none of them. */
    internal fun slotFor(tx: LegacyTx): Slot? {
        if (tx.chainId != GNOSIS_CHAIN_ID) return null
        val to = tx.to ?: return null
        // ant pays 2 gwei; anything near this cap isn't ant's own transaction.
        if (tx.gasPrice.multiply(tx.gasLimit) > MAX_GAS_WEI) return null
        val data = tx.data
        if (data.size < 4) return null
        val selector = hex(data.copyOfRange(0, 4))
        val words = data.size - 4
        fun word(i: Int): ByteArray = data.copyOfRange(4 + 32 * i, 4 + 32 * (i + 1))
        fun uint(i: Int): BigInteger = BigInteger(1, word(i))
        fun address(i: Int): String? = word(i).takeIf { w -> (0 until 12).all { w[it] == 0.toByte() } }
            ?.let { hex(it.copyOfRange(12, 32)) }
        val owner = plan.owner

        if (plan is SpendPlan.DepositChequebook) {
            // One transfer of exactly the confirmed xBZZ to exactly the confirmed chequebook.
            return Slot.Deposit.takeIf {
                tx.value.signum() == 0 && to == BZZ_TOKEN && selector == SEL_TRANSFER && words == 64 &&
                    address(0) == plan.chequebook && uint(1) == plan.amountPlur
            }
        }
        if (tx.value.signum() != 0) {
            // Only the swap carries xDAI, and never more than the confirmation showed.
            val maxSwap = when (plan) {
                is SpendPlan.BuyStamp -> plan.maxSwapWei
                is SpendPlan.ExtendStamp -> plan.maxSwapWei
                is SpendPlan.DepositChequebook, is SpendPlan.ConnectBatch -> return null
            }
            return Slot.Swap.takeIf {
                to == SWAP_HELPER && selector == SEL_SWAP && words == 64 &&
                    address(0) == owner && tx.value <= maxSwap
            }
        }
        // What sets up the chequebook: a first buy's last two, or all a connect may send.
        val setsUpChequebook = plan is SpendPlan.BuyStamp || plan is SpendPlan.ConnectBatch
        val (depth, amount) = when (plan) {
            is SpendPlan.BuyStamp -> plan.depth to plan.amountPerChunk
            is SpendPlan.ExtendStamp -> plan.depth to plan.amountPerChunk
            is SpendPlan.DepositChequebook -> return null
            is SpendPlan.ConnectBatch -> null to null
        }
        return when {
            depth != null && amount != null && to == BZZ_TOKEN && selector == SEL_APPROVE && words == 64 ->
                Slot.Approve.takeIf { address(0) == POSTAGE_STAMP && uint(1) == amount.shiftLeft(depth) }
            plan is SpendPlan.BuyStamp && to == POSTAGE_STAMP && selector == SEL_CREATE_BATCH && words == 192 ->
                Slot.CreateBatch.takeIf {
                    address(0) == owner && uint(1) == plan.amountPerChunk &&
                        uint(2) == BigInteger.valueOf(plan.depth.toLong()) &&
                        uint(3) == BigInteger.valueOf(BUCKET_DEPTH) &&
                        uint(5) == (if (plan.immutable) BigInteger.ONE else BigInteger.ZERO)
                }
            plan is SpendPlan.ExtendStamp && to == POSTAGE_STAMP && selector == SEL_TOP_UP && words == 64 ->
                Slot.TopUp.takeIf { hex(word(0)) == plan.batchId && uint(1) == plan.amountPerChunk }
            setsUpChequebook && to == CHEQUEBOOK_FACTORY && selector == SEL_DEPLOY_CHEQUEBOOK && words == 96 ->
                Slot.DeployChequebook.takeIf { address(0) == owner }
            setsUpChequebook && to == BZZ_TOKEN && selector == SEL_TRANSFER && words == 64 ->
                // The chequebook's settlement deposit: ant's 0.001 xBZZ target at most.
                // The recipient (the chequebook ant just deployed) isn't known here, so
                // any address passes — safe only while no ant gateway route transfers
                // xBZZ to a caller-chosen address; see the class KDoc.
                Slot.SettlementDeposit.takeIf {
                    address(0) != null && uint(1).signum() > 0 && uint(1) <= MAX_SETTLEMENT_DEPOSIT_PLUR
                }
            else -> null
        }
    }

    companion object {
        const val GNOSIS_CHAIN_ID = 100L
        const val POSTAGE_STAMP = "45a1502382541cd610cc9068e88727426b696293"
        const val BZZ_TOKEN = "dbf3ea6f5bee45c02255b2c26a16f300502f68da"
        /** ant's `BzzSwapHelper` (CREATE2, already on Gnosis; its deploy is never admitted). */
        const val SWAP_HELPER = "5f346eee2056edfae2a98e6675f63f34702c3f68"
        const val CHEQUEBOOK_FACTORY = "c2d5a532cf69aa9a1378737d8ccdef884b6e7420"
        const val BUCKET_DEPTH = 16L

        private const val SEL_SWAP = "3b88d7af" // swapXdaiForBzz(address,uint256)
        private const val SEL_APPROVE = "095ea7b3" // approve(address,uint256)
        private const val SEL_TRANSFER = "a9059cbb" // transfer(address,uint256)
        private const val SEL_CREATE_BATCH = "5239af71" // createBatch(address,uint256,uint8,uint8,bytes32,bool)
        private const val SEL_TOP_UP = "b67644b9" // topUp(bytes32,uint256)
        private const val SEL_DEPLOY_CHEQUEBOOK = "15efd8a7" // deploySimpleSwap(address,uint256,bytes32)

        /** 0.001 xBZZ, ant's chequebook settlement deposit target. */
        val MAX_SETTLEMENT_DEPOSIT_PLUR: BigInteger = BigInteger.TEN.pow(13)

        /** 0.01 xDAI of gas per transaction; ant's biggest (the chequebook deploy) is 0.003. */
        val MAX_GAS_WEI: BigInteger = BigInteger.TEN.pow(16)

        /** The transactions a buy ([buy]) or an extend may broadcast, each at most once. */
        fun slotsFor(buy: Boolean): Set<Slot> = if (buy) {
            setOf(Slot.Swap, Slot.Approve, Slot.CreateBatch, Slot.DeployChequebook, Slot.SettlementDeposit)
        } else {
            setOf(Slot.Swap, Slot.Approve, Slot.TopUp)
        }

        /** What connecting a batch (#115) may broadcast: the chequebook's setup, as on a first buy. */
        val CONNECT_SLOTS: Set<Slot> = setOf(Slot.DeployChequebook, Slot.SettlementDeposit)

        /** The most gas connecting a batch can cost: [MAX_GAS_WEI] for each of [CONNECT_SLOTS]. */
        val CONNECT_MAX_GAS_WEI: BigInteger = MAX_GAS_WEI.multiply(BigInteger.valueOf(CONNECT_SLOTS.size.toLong()))

        /** What a chequebook deposit (#117) may broadcast: its one transfer. */
        val DEPOSIT_SLOTS: Set<Slot> = setOf(Slot.Deposit)

        /**
         * The most gas a buy or an extend can cost: [MAX_GAS_WEI] for each
         * of its transactions. It comes on top of the swap's `maxSwapWei`,
         * so this plus that is the hard bound on the xDAI one spend uses.
         */
        fun maxGasWei(buy: Boolean): BigInteger = MAX_GAS_WEI.multiply(BigInteger.valueOf(slotsFor(buy).size.toLong()))

        /** The most gas a chequebook deposit can cost: [MAX_GAS_WEI] for its one transaction. */
        val DEPOSIT_MAX_GAS_WEI: BigInteger = MAX_GAS_WEI.multiply(BigInteger.valueOf(DEPOSIT_SLOTS.size.toLong()))

        private val METHOD = Regex("\"method\"\\s*:\\s*\"([^\"\\\\]*)\"")
        private val PARAMS = Regex("\"params\"\\s*:\\s*\\[\\s*\"(0x[0-9a-fA-F]*)\"\\s*]")

        /**
         * The signed transaction of a single `eth_sendRawTransaction`
         * request, lowercase hex without `0x`; null for any other request,
         * or one that isn't exactly that shape (a batch, two methods).
         */
        internal fun rawTransaction(requestJson: String): String? {
            val methods = METHOD.findAll(requestJson).toList()
            if (methods.size != 1 || methods[0].groupValues[1] != "eth_sendRawTransaction") return null
            val params = PARAMS.findAll(requestJson).toList()
            if (params.size != 1 || requestJson.trimStart().startsWith("[")) return null
            return params[0].groupValues[1].removePrefix("0x").lowercase()
        }

        internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        private fun hexBytes(s: String): ByteArray? {
            if (s.length % 2 != 0 || s.isEmpty()) return null
            return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        }
    }
}

/**
 * An EIP-155 legacy transaction, the only kind ant signs:
 * `rlp([nonce, gasPrice, gas, to, value, data, v, r, s])`.
 */
class LegacyTx(
    val gasPrice: BigInteger,
    val gasLimit: BigInteger,
    /** 40 lowercase hex, or null for a contract creation. */
    val to: String?,
    val value: BigInteger,
    val data: ByteArray,
    /** From `v` (`chainId × 2 + 35 + {0,1}`); -1 for a pre-EIP-155 signature. */
    val chainId: Long,
) {
    companion object {
        /** The transaction in [raw], or null if it isn't a well-formed signed legacy one. */
        fun decode(raw: ByteArray): LegacyTx? = try {
            val items = Rlp(raw).list()
            if (items == null || items.size != 9) {
                null
            } else {
                val to = items[3]
                val numbers = listOf(items[1], items[2], items[4], items[6])
                val v = BigInteger(1, items[6])
                when {
                    to.isNotEmpty() && to.size != 20 -> null
                    // Canonical RLP integers: no leading zero byte, at most 256 bits.
                    numbers.any { it.size > 32 || (it.isNotEmpty() && it[0] == 0.toByte()) } -> null
                    else -> LegacyTx(
                        gasPrice = BigInteger(1, items[1]),
                        gasLimit = BigInteger(1, items[2]),
                        to = if (to.isEmpty()) null else SpendPermit.hex(to),
                        value = BigInteger(1, items[4]),
                        data = items[5],
                        chainId = if (v >= V_EIP155) v.subtract(V_EIP155).shiftRight(1).toLong() else -1L,
                    )
                }
            }
        } catch (e: RuntimeException) {
            null
        }

        private val V_EIP155 = BigInteger.valueOf(35)
    }

    /** Just enough RLP for one flat list of byte strings; anything else is refused. */
    private class Rlp(private val b: ByteArray) {
        private var i = 0

        fun atEnd() = i == b.size

        fun list(): List<ByteArray>? {
            val (isList, len) = header() ?: return null
            if (!isList) return null
            val end = i + len
            if (end != b.size) return null
            val out = ArrayList<ByteArray>()
            while (i < end) {
                val (itemIsList, itemLen) = header() ?: return null
                if (itemIsList || i + itemLen > end) return null
                out += b.copyOfRange(i, i + itemLen)
                i += itemLen
            }
            return out
        }

        /** (is a list, payload length), with [i] moved past the header; a lone byte < 0x80 is its own item. */
        private fun header(): Pair<Boolean, Int>? {
            if (i >= b.size) return null
            val p = b[i].toInt() and 0xff
            return when {
                p < 0x80 -> false to 1 // the byte itself, not moved past
                p <= 0xb7 -> {
                    i++
                    val n = p - 0x80
                    if (n == 1 && i < b.size && (b[i].toInt() and 0xff) < 0x80) null else false to n
                }
                p <= 0xbf -> long(p - 0xb7)?.let { false to it }
                p <= 0xf7 -> {
                    i++
                    true to (p - 0xc0)
                }
                else -> long(p - 0xf7)?.let { true to it }
            }
        }

        private fun long(lenOfLen: Int): Int? {
            i++
            if (lenOfLen > 3 || i + lenOfLen > b.size || b[i] == 0.toByte()) return null
            var n = 0
            repeat(lenOfLen) { n = (n shl 8) or (b[i++].toInt() and 0xff) }
            return if (n < 56) null else n
        }
    }
}
