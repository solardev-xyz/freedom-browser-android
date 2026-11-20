package baby.freedom.mobile.names

import baby.freedom.mobile.R
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.chains.rpc.undisputed
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameContracts.Role
import java.math.BigInteger
import org.json.JSONObject

/**
 * Ethereum mainnet reads for the name contracts. Every answer that a
 * write is built on must be [undisputed] — a proof, a quorum agreeing,
 * or the user's own RPC unopposed — the bar the wallet holds a funding
 * price to ([chainReadTrusted][baby.freedom.mobile.browser.chainReadTrusted]);
 * a lone public RPC could otherwise say a taken name is free, or quote
 * a price, and the user pays for it.
 */
interface NameReads {
    /** The latest block: its number and timestamp (seconds). */
    suspend fun latest(): NameChain.Snapshot

    /** The block [number]'s snapshot. */
    suspend fun at(number: Long): NameChain.Snapshot

    /** `eth_call` [data] to [to] at [block], from [from] when given (for a simulation). */
    suspend fun call(to: String, data: ByteArray, block: Long, from: String? = null, value: BigInteger? = null): String

    /** The code at [address] at [block]. */
    suspend fun code(address: String, block: Long): String

    /** The receipt of [hash], or null while there is none. */
    suspend fun receipt(hash: String): JSONObject?

    /**
     * [address]'s balance at [block], only to say why a payment can't go:
     * nothing is sent on it (the wallet reads its own before signing).
     */
    suspend fun balance(address: String, block: Long): BigInteger
}

/** [NameReads] through the wallet's chain-data router, refusing any read that isn't [undisputed]. */
class RpcNameReads(private val rpc: WalletRpc) : NameReads {
    private val chain = NameContracts.CHAIN_ID

    private fun <T> trusted(r: WalletRpc.Reading<T>): T {
        if (!r.trust.undisputed) unverified(r.trust)
        return r.value
    }

    override suspend fun latest(): NameChain.Snapshot = guarded {
        val n = trusted(rpc.blockNumber(chain))
        NameChain.Snapshot(n, trusted(rpc.blockTimestamp(chain, n)))
    }

    override suspend fun at(number: Long): NameChain.Snapshot = guarded {
        NameChain.Snapshot(number, trusted(rpc.blockTimestamp(chain, number)))
    }

    override suspend fun call(to: String, data: ByteArray, block: Long, from: String?, value: BigInteger?): String = guarded {
        val tx = JSONObject().put("to", to).put("data", NameContracts.hex(data))
        from?.let { tx.put("from", it) }
        value?.let { tx.put("value", "0x" + it.toString(16)) }
        trusted(rpc.call(chain, tx, "0x" + block.toString(16)))
    }

    override suspend fun code(address: String, block: Long): String = guarded {
        trusted(rpc.code(chain, address, "0x" + block.toString(16)))
    }

    override suspend fun receipt(hash: String): JSONObject? = guarded {
        val r = rpc.receipt(chain, hash)
        // No receipt yet is no news, whoever said it; a receipt acts only when undisputed.
        if (r.value == null) null else trusted(r)
    }

    override suspend fun balance(address: String, block: Long): BigInteger = guarded {
        rpc.balance(chain, address, "0x" + block.toString(16)).value
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ChainRpcException.Rpc) {
        // A revert of a simulation or a read: the contract refused.
        throw e
    } catch (e: ChainRpcException) {
        refuse(NameException.Code.UNAVAILABLE, Strings.said(R.string.namereg_error_chain_unreachable))
    }

    private fun unverified(trust: ChainTrust): Nothing =
        refuse(NameException.Code.UNVERIFIED, Strings.said(R.string.namereg_error_unverified, trust.agreed.size + trust.dissented.size))
}

/**
 * What the chain says about one name, read at one block — desktop's
 * `DomainChain.inspect`, for registration and renewal and, with the
 * records asked for, for record writes (#599).
 */
class NameChain(
    private val reads: NameReads,
    /** The code hash each contract must have: its pinned [Role.codeHash] (tests pin their fake chain's). */
    private val pinned: (Role) -> String = { it.codeHash },
) {
    /** One block every read of an inspection is made at, and its timestamp. */
    data class Snapshot(val block: Long, val timestamp: Long)

    data class State(
        val parsed: ParsedName,
        val snapshot: Snapshot,
        /** The name can be registered now. */
        val available: Boolean,
        /** Owner of the name (the NFT holder; for a wrapped name, the wrapper's owner); zero when none. */
        val owner: String,
        /** When it expires (seconds), or null for an `.eth` subname. */
        val expiry: BigInteger?,
        /** An `.eth` name held by the NameWrapper. */
        val wrapped: Boolean = false,
        /** The NameWrapper's own expiry for a wrapped name. */
        val wrapperExpiry: BigInteger? = null,
        /** What a record write needs (#599); null when [inspect] wasn't asked for records. */
        val records: Records? = null,
    ) {
        /** Whether a renewal makes sense: registered and not free. */
        val renewable: Boolean get() = parsed.topLevel && !available && expiry != null && expiry.signum() > 0
    }

    suspend fun snapshot(): Snapshot = reads.latest()

    /** The snapshot at block [number]: the state as that block left it. */
    suspend fun snapshotAt(number: Long): Snapshot = reads.at(number)

    /**
     * A snapshot no earlier than the block [receipt] was mined in: a node
     * whose latest view hasn't caught up must not have the state checked
     * at an earlier block than the transaction it judges.
     */
    suspend fun snapshotAfter(receipt: JSONObject): Snapshot {
        val latest = reads.latest()
        val mined = quantity(receipt.opt("blockNumber"))?.toLong() ?: return latest
        return if (latest.block < mined) reads.at(mined) else latest
    }

    /**
     * Who may write a name's records, and what they hold (desktop's
     * `inspect` records half): [manager] is the ENS registry's owner of
     * the node (the NFT owner for a NameNFT), [recordOwner] whose say the
     * public resolver goes by (a wrapped name's wrapper owner), the
     * [resolver] and whether it's the one Freedom writes to
     * ([supportedResolver]), [active] whether the name is live, and the
     * record generation ([epoch], [parentEpoch]) a NameNFT bumps when it
     * changes hands. [contenthash] in hex (`0x` when none), [address]
     * (zero when none) and the asked [text] keys, each at most
     * [NameContracts.MAX_RECORD_BYTES]; all null with an unsupported resolver.
     * A record over that bound is left out of [contenthash]/[text] and
     * named in [tooLarge] (`contenthash`, or `text:` and its key) instead
     * of failing the whole read: the name's other records still read.
     */
    data class Records(
        val manager: String,
        val recordOwner: String,
        val resolver: String,
        val supportedResolver: Boolean,
        val active: Boolean,
        val fuses: BigInteger? = null,
        val epoch: BigInteger? = null,
        val parentEpoch: BigInteger? = null,
        val contenthash: String? = null,
        val address: String? = null,
        val text: Map<String, String> = emptyMap(),
        val tooLarge: Set<String> = emptySet(),
    )

    /**
     * [parsed] at [at] (the latest block when null). With [keys] the
     * records are read too ([State.records]): the resolver, the website,
     * the address and those text keys.
     */
    suspend fun inspect(parsed: ParsedName, at: Snapshot? = null, keys: List<String>? = null): State {
        val s = at ?: reads.latest()
        return if (parsed.space == NameSpace.ETH) inspectEth(parsed, s, keys) else inspectNft(parsed, s, keys)
    }

    private suspend fun inspectEth(parsed: ParsedName, s: Snapshot, keys: List<String>?): State {
        val manager = address(Role.REGISTRY, NameContracts.registryOwnerData(parsed.node), s)
        var owner = manager
        var expiry: BigInteger? = null
        if (parsed.topLevel) {
            expiry = uint(Role.BASE, NameContracts.nameExpiresData(parsed.tokenId), s)
            // ownerOf reverts during ENS grace, so it's only asked before expiry.
            owner = if (expiry > BigInteger.valueOf(s.timestamp)) address(Role.BASE, NameContracts.ownerOfData(parsed.tokenId), s) else NameContracts.ZERO_ADDRESS
        }
        val wrapped = manager.equals(Role.WRAPPER.address, ignoreCase = true)
        var wrapperExpiry: BigInteger? = null
        var fuses: BigInteger? = null
        if (wrapped) {
            val w = words(Role.WRAPPER, NameContracts.wrapperGetData(parsed.node), s, 3)
            owner = NameAbi.wordAddress(w[0]) ?: malformed()
            fuses = NameAbi.wordUint(w[1])
            wrapperExpiry = NameAbi.wordUint(w[2])
        }
        val available = parsed.topLevel && bool(Role.CONTROLLER, NameContracts.availableData(parsed.label), s)
        val records = keys?.let {
            val resolver = address(Role.REGISTRY, NameContracts.registryResolverData(parsed.node), s)
            val supported = resolver.equals(Role.RESOLVER.address, ignoreCase = true)
            val big = HashSet<String>()
            Records(
                manager = manager,
                recordOwner = if (wrapped) owner else manager,
                resolver = resolver,
                supportedResolver = supported,
                active = !manager.equals(NameContracts.ZERO_ADDRESS, ignoreCase = true) && (expiry == null || expiry > BigInteger.valueOf(s.timestamp)),
                fuses = fuses,
                contenthash = if (supported) bytesRecord(Role.RESOLVER, NameContracts.contenthashData(parsed), s, big) else null,
                address = if (supported) address(Role.RESOLVER, NameContracts.addressData(parsed), s) else null,
                text = if (supported) texts(Role.RESOLVER, parsed, keys, s, big) else emptyMap(),
                tooLarge = big,
            )
        }
        return State(parsed, s, available, owner, expiry, wrapped, wrapperExpiry, records)
    }

    private suspend fun inspectNft(parsed: ParsedName, s: Snapshot, keys: List<String>?): State {
        val role = Role.nft(parsed.space)
        val parentId = if (parsed.topLevel) BigInteger.ZERO else uint(role, NameContracts.nftComputeIdData(parsed.name.substringAfter('.')), s)
        val available = bool(role, NameContracts.nftIsAvailableData(parsed.label, parentId), s)
        val record = NameAbi.words(reads.call(role.address, NameContracts.nftRecordsData(parsed.tokenId), s.block)) ?: malformed()
        if (record.size < 5) malformed()
        val label = NameAbi.dynamicString(record, 0) ?: malformed()
        val expiresAt = NameAbi.wordUint(record[2])
        // ownerOf only for a recorded token: an error is never read as "no owner".
        val owner = if (label.isNotEmpty()) address(role, NameContracts.ownerOfData(parsed.tokenId), s) else NameContracts.ZERO_ADDRESS
        val records = keys?.let {
            val resolved = address(role, NameContracts.addressData(parsed), s)
            val big = HashSet<String>()
            Records(
                manager = owner,
                recordOwner = owner,
                resolver = role.address,
                supportedResolver = true,
                // Desktop's rule: the NameNFT resolves a live name to an address (its owner's when none is set), an expired one to zero.
                active = !resolved.equals(NameContracts.ZERO_ADDRESS, ignoreCase = true),
                epoch = uint(role, NameContracts.nftRecordVersionData(parsed.tokenId), s),
                parentEpoch = NameAbi.wordUint(record[4]),
                contenthash = bytesRecord(role, NameContracts.contenthashData(parsed), s, big),
                address = resolved,
                text = texts(role, parsed, keys, s, big),
                tooLarge = big,
            )
        }
        return State(parsed, s, available, owner, expiresAt, records = records)
    }

    /** The contenthash in hex; null past [NameContracts.MAX_RECORD_BYTES], named `contenthash` in [tooLarge]. */
    private suspend fun bytesRecord(role: Role, data: ByteArray, s: Snapshot, tooLarge: MutableSet<String>): String? {
        val b = NameAbi.dynamicBytes(NameAbi.words(reads.call(role.address, data, s.block)) ?: malformed(), 0) ?: malformed()
        if (b.size > NameContracts.MAX_RECORD_BYTES) {
            tooLarge += TOO_LARGE_CONTENTHASH
            return null
        }
        return NameContracts.hex(b)
    }

    /** [keys]' text records; one past [NameContracts.MAX_RECORD_BYTES] is left out and named in [tooLarge]. */
    private suspend fun texts(role: Role, parsed: ParsedName, keys: List<String>, s: Snapshot, tooLarge: MutableSet<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in keys) {
            val b = NameAbi.dynamicBytes(NameAbi.words(reads.call(role.address, NameContracts.textData(parsed, k), s.block)) ?: malformed(), 0) ?: malformed()
            if (b.size > NameContracts.MAX_RECORD_BYTES) tooLarge += TOO_LARGE_TEXT + k else out[k] = String(b, Charsets.UTF_8)
        }
        return out
    }

    /**
     * The price of [duration] seconds of [parsed] at [s], in wei: a
     * registration's ([registering]) includes any premium, a renewal's
     * doesn't. A wrapped `.eth` name renews through the wrapped controller.
     */
    suspend fun price(parsed: ParsedName, duration: Long, s: Snapshot, registering: Boolean, wrapped: Boolean = false): BigInteger {
        if (parsed.space == NameSpace.ETH) {
            val role = if (!registering && wrapped) Role.WRAPPED_CONTROLLER else Role.CONTROLLER
            val w = words(role, NameContracts.rentPriceData(parsed.label, duration), s, 2)
            return NameAbi.wordUint(w[0]) + if (registering) NameAbi.wordUint(w[1]) else BigInteger.ZERO
        }
        val role = Role.nft(parsed.space)
        val fee = uint(role, NameContracts.nftGetFeeData(parsed.label.toByteArray(Charsets.UTF_8).size), s)
        val premium = if (registering) uint(role, NameContracts.nftGetPremiumData(parsed.tokenId), s) else BigInteger.ZERO
        return fee + premium
    }

    /** Refuses unless the code at [role]'s address hashes to its pinned hash. */
    suspend fun checkCode(role: Role, s: Snapshot) {
        val code = reads.code(role.address, s.block)
        val hash = NameContracts.hex(Keccak256.digest(hexBytes(code)))
        if (!hash.equals(pinned(role), ignoreCase = true)) refuse(NameException.Code.UNSUPPORTED, Strings.said(R.string.namereg_error_contract_changed))
    }

    /** When [commitment] was committed (seconds), or zero when it isn't on chain. */
    suspend fun committedAt(space: NameSpace, commitment: ByteArray, s: Snapshot): BigInteger =
        uint(commitRole(space), NameContracts.commitmentsData(commitment), s)

    /** The commitment age window, (min, max) seconds. */
    suspend fun commitmentAges(space: NameSpace, s: Snapshot): Pair<Long, Long> =
        if (space == NameSpace.ETH) {
            uint(Role.CONTROLLER, NameContracts.minCommitmentAgeData(), s).toLong() to uint(Role.CONTROLLER, NameContracts.maxCommitmentAgeData(), s).toLong()
        } else {
            NameContracts.NFT_MIN_COMMITMENT_AGE to NameContracts.NFT_MAX_COMMITMENT_AGE
        }

    suspend fun bool(role: Role, data: ByteArray, s: Snapshot): Boolean =
        NameAbi.wordBool(words(role, data, s, 1)[0]) ?: malformed()

    suspend fun uint(role: Role, data: ByteArray, s: Snapshot): BigInteger = NameAbi.wordUint(words(role, data, s, 1)[0])

    suspend fun address(role: Role, data: ByteArray, s: Snapshot): String = NameAbi.wordAddress(words(role, data, s, 1)[0]) ?: malformed()

    suspend fun string(role: Role, data: ByteArray, s: Snapshot): String =
        NameAbi.dynamicString(NameAbi.words(reads.call(role.address, data, s.block)) ?: malformed(), 0) ?: malformed()

    /** Runs [data] to [to] with [value] from [from] at [s]; throws if it would revert. */
    suspend fun simulate(from: String, to: String, data: ByteArray, value: BigInteger, s: Snapshot) {
        reads.call(to, data, s.block, from, value)
    }

    suspend fun receipt(hash: String): JSONObject? = reads.receipt(hash)

    suspend fun balance(address: String, s: Snapshot): BigInteger = reads.balance(address, s.block)

    private suspend fun words(role: Role, data: ByteArray, s: Snapshot, atLeast: Int): List<ByteArray> {
        val w = NameAbi.words(reads.call(role.address, data, s.block)) ?: malformed()
        if (w.size < atLeast) malformed()
        return w
    }

    companion object {
        /** [Records.tooLarge]'s entries: the contenthash, and `text:` with a text record's key. */
        const val TOO_LARGE_CONTENTHASH = "contenthash"
        const val TOO_LARGE_TEXT = "text:"

        fun commitRole(space: NameSpace): Role = if (space == NameSpace.ETH) Role.CONTROLLER else Role.nft(space)

        internal fun quantity(v: Any?): BigInteger? = (v as? String)?.takeIf { it.startsWith("0x") && it.length > 2 }
            ?.let { runCatching { BigInteger(it.substring(2), 16) }.getOrNull() }

        private fun hexBytes(hex: String): ByteArray {
            val h = hex.removePrefix("0x")
            return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        private fun malformed(): Nothing = refuse(NameException.Code.UNAVAILABLE, Strings.said(R.string.namereg_error_unreadable))
    }
}
