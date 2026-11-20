package baby.freedom.mobile.names

import baby.freedom.mobile.R
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.l10n.Said
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameAbi.toHexString
import java.math.BigInteger

/**
 * Why a name operation can't go ahead. [code] is desktop's `DomainError`
 * code (`DOMAIN_INVALID`, `DOMAIN_STALE`, …) without the prefix, so the
 * agent's `domain_*` tools can hand the same codes on later; [message] is
 * already the user's language, and [english] the same words for the agent's
 * model (#599), which is told nothing in the phone's language.
 */
class NameException(
    val code: Code,
    message: String,
    val english: String = message,
    /** The operation can never go as prepared (its record or the name's authority changed): it's marked stale. */
    val outdated: Boolean = false,
) : Exception(message) {
    constructor(code: Code, said: Said, outdated: Boolean = false) : this(code, said.text, said.english, outdated)

    enum class Code { INVALID, UNSUPPORTED, UNAVAILABLE, UNVERIFIED, STALE, QUOTE_CHANGED, CONFLICT, SECURE_STORAGE, FUNDS }
}

internal fun refuse(code: NameException.Code, message: String): Nothing = throw NameException(code, message)

/** [said] for the screen, and in English ([NameException.english]) for the agent's `domain_*` tools. */
internal fun refuse(code: NameException.Code, said: Said): Nothing = throw NameException(code, said)

/** The three namespaces Freedom registers, renews and writes records in (#579, #599). */
enum class NameSpace(val tld: String) {
    /** ENS, `ETHRegistrarController` commit/reveal. */
    ETH("eth"),

    /** WNS, a NameNFT. */
    WEI("wei"),

    /** GNS, a NameNFT like WNS. */
    GWEI("gwei"),
    ;

    val nft: Boolean get() = this != ETH

    companion object {
        fun of(tld: String): NameSpace? = entries.firstOrNull { it.tld == tld }
    }
}

/**
 * A name as the contracts know it, desktop's `parseName`: [name] in its
 * registered spelling, its [label] (the first one), its ENS [node]
 * (namehash) and the NFT [tokenId] — the labelhash for `.eth` (the
 * BaseRegistrar's id), the namehash for `.wei`/`.gwei`.
 */
data class ParsedName(
    val name: String,
    val space: NameSpace,
    val label: String,
    val topLevel: Boolean,
    val node: ByteArray,
    val tokenId: BigInteger,
) {
    override fun equals(other: Any?) = other is ParsedName && name == other.name && space == other.space

    override fun hashCode() = name.hashCode()
}

/**
 * The pinned mainnet deployments, their ABIs and their code hashes — a
 * port of desktop's `src/main/domains/contracts.js`. Addresses and ABIs
 * ship with the app; nothing a page, a model or an RPC says chooses one.
 */
object NameContracts {
    const val CHAIN_ID = 1L

    /** 365 days: the term a `.wei`/`.gwei` transaction adds, and the default for `.eth`. */
    const val YEAR = 31_536_000L

    /** The longest `.eth` term one transaction buys or adds (desktop's ten years). */
    const val MAX_YEARS = 10

    /**
     * What a payment sends on top of the quote, in percent (desktop's
     * `PRICE_BUFFER_PERCENT`, the ENS app's 2%): a price update between
     * signing and mining doesn't revert it, and every supported
     * controller refunds the unused part in the same transaction.
     */
    const val PRICE_BUFFER_PERCENT = 2

    /** The NameNFT contracts' fixed commitment window: at least 60 s old, at most 24 h. */
    const val NFT_MIN_COMMITMENT_AGE = 60L
    const val NFT_MAX_COMMITMENT_AGE = 86_400L

    enum class Role(val address: String, val displayName: String, val codeHash: String) {
        REGISTRY("0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e", "ENS Registry", "0xd6bfd5d6f1384a1f6ea57b8a8412de5552f138d42021cf7c4941e33206f529e4"),
        BASE("0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85", "ENS BaseRegistrar", "0x15557327bea19f454db3ec207da1a440b6d9318af56e52e2a3eba440d4b62e0a"),
        CONTROLLER("0x59E16fcCd424Cc24e280Be16E11Bcd56fb0CE547", "ENS ETHRegistrarController", "0x64051f251ade7a7b7def7b199339ab1e6d44c28fabd824fe6523a658a0b9c93d"),
        WRAPPED_CONTROLLER("0x253553366Da8546fC250F225fe3d25d0C782303b", "ENS WrappedETHRegistrarController", "0x56aa3e2c09a6a30222598db1cffa6e657a5e5e6eef765d3cd60e44372d005cab"),
        WRAPPER("0xD4416b13d2b3a9aBae7AcD5D6C2BbDBE25686401", "ENS NameWrapper", "0xb2d3098f0579718d88a04ee65d3c6827a76823316ceed387945ad993fc6dbb6c"),
        RESOLVER("0xF29100983E058B709F3D539b0c765937B804AC15", "ENS PublicResolver", "0x186d03e26f6e0224269c8f7f13a6334d625f1b83812db2045ac7de4f292e9336"),
        WEI("0x0000000000696760E15f265e828DB644A0c242EB", "WNS NameNFT (.wei)", "0x5b791c832d4373a8d4f977c37d6973a5dbe0924c6d287a2effaa549be31c0221"),
        GWEI("0x9D51D507BC7264d4fE8Ad1cf7Fe191933A0a81d6", "GNS NameNFT (.gwei)", "0xb0e20c7e72a371b06d4456cc5056ea06ab2366da03f8cf49698a1f956e60b068"),
        ;

        companion object {
            fun nft(space: NameSpace): Role = when (space) {
                NameSpace.WEI -> WEI
                NameSpace.GWEI -> GWEI
                NameSpace.ETH -> error("not a NameNFT")
            }

            fun byAddress(address: String): Role? = entries.firstOrNull { it.address.equals(address, ignoreCase = true) }
        }
    }

    /** The ENS `register`/`makeCommitment` tuple. */
    private const val REGISTRATION = "(string,address,uint256,bytes32,address,bytes[],uint8,bytes32)"

    val ZERO_ADDRESS = "0x" + "0".repeat(40)

    private val CONTROLS = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")

    /**
     * [value] as a top-level or deeper `.eth`, `.wei` or `.gwei` name, or
     * [NameException]: a full name only (no scheme, path or spaces), every
     * label non-empty, ENSIP-15 normalized. A `.wei`/`.gwei` name must
     * already be in its normalized form after ASCII lowercasing — the
     * NameNFT keeps non-ASCII bytes as given, so normalizing would turn a
     * legacy name into a different token.
     */
    fun parse(value: String): ParsedName {
        val invalid = Strings.said(R.string.namereg_error_full_name)
        if (value.length > 255 || value != value.trim() || CONTROLS.containsMatchIn(value)) refuse(NameException.Code.INVALID, invalid)
        val labels = value.split('.')
        val space = NameSpace.of(labels.last().lowercase())
        if (space == null || labels.size < 2 || labels.any { it.isEmpty() }) refuse(NameException.Code.INVALID, invalid)
        var normalized = try {
            EnsNormalize.normalize(value)
        } catch (e: IllegalArgumentException) {
            refuse(NameException.Code.INVALID, Strings.said(R.string.namereg_error_not_valid))
        }
        if (space.nft) {
            val ascii = value.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
            if (ascii != normalized) refuse(NameException.Code.UNSUPPORTED, Strings.said(R.string.namereg_error_legacy_unicode))
            normalized = ascii
        }
        val parts = normalized.split('.')
        var node = ByteArray(32)
        for (label in parts.reversed()) node = Keccak256.digest(node + Keccak256.digest(label.toByteArray(Charsets.UTF_8)))
        val tokenId = BigInteger(1, if (space == NameSpace.ETH) Keccak256.digest(parts[0].toByteArray(Charsets.UTF_8)) else node)
        return ParsedName(normalized, space, parts[0], parts.size == 2, node, tokenId)
    }

    /** [wei] plus [PRICE_BUFFER_PERCENT]. */
    fun withBuffer(wei: BigInteger): BigInteger =
        wei.multiply(BigInteger.valueOf(100L + PRICE_BUFFER_PERCENT)).divide(BigInteger.valueOf(100))

    /**
     * The registration tuple: [label] to [owner] for [duration] seconds,
     * the public resolver set and no records, no reverse record, no
     * referrer — desktop's `registrationTuple`.
     */
    private fun registration(label: String, owner: String, duration: Long, secret: ByteArray): NameAbi.Tuple = NameAbi.Tuple(
        listOf(
            NameAbi.string(label),
            NameAbi.address(owner),
            NameAbi.uint(duration),
            NameAbi.bytes32(secret),
            NameAbi.address(Role.RESOLVER.address),
            NameAbi.BytesArray(emptyList()),
            NameAbi.uint(0),
            NameAbi.bytes32(ByteArray(32)),
        ),
    )

    /**
     * The commitment for registering [label] in [space] to [owner] with
     * [secret]: ENS hashes the whole registration tuple (so [duration] is
     * bound too); a NameNFT hashes `abi.encode(bytes label, owner, secret)`.
     */
    fun commitment(space: NameSpace, label: String, owner: String, duration: Long, secret: ByteArray): ByteArray {
        require(secret.size == 32) { "a 32-byte secret" }
        val encoded = if (space == NameSpace.ETH) {
            NameAbi.encode(listOf(registration(label, owner, duration, secret)))
        } else {
            NameAbi.encode(listOf(NameAbi.string(label), NameAbi.address(owner), NameAbi.bytes32(secret)))
        }
        return Keccak256.digest(encoded)
    }

    // Writes.

    fun commitData(commitment: ByteArray): ByteArray = NameAbi.call("commit(bytes32)", NameAbi.bytes32(commitment))

    fun registerData(label: String, owner: String, duration: Long, secret: ByteArray): ByteArray =
        NameAbi.call("register($REGISTRATION)", registration(label, owner, duration, secret))

    fun revealData(label: String, secret: ByteArray): ByteArray =
        NameAbi.call("reveal(string,bytes32)", NameAbi.string(label), NameAbi.bytes32(secret))

    /** An unwrapped `.eth` renewal: `renew(label, duration, referrer = 0)`. */
    fun renewData(label: String, duration: Long): ByteArray =
        NameAbi.call("renew(string,uint256,bytes32)", NameAbi.string(label), NameAbi.uint(duration), NameAbi.bytes32(ByteArray(32)))

    /** A wrapped `.eth` renewal, through the wrapped controller. */
    fun wrappedRenewData(label: String, duration: Long): ByteArray =
        NameAbi.call("renew(string,uint256)", NameAbi.string(label), NameAbi.uint(duration))

    /** A NameNFT renewal: one year for the token. */
    fun nftRenewData(tokenId: BigInteger): ByteArray = NameAbi.call("renew(uint256)", NameAbi.uint(tokenId))

    /**
     * A name's record writes (#599): on the ENS public resolver keyed by
     * the name's node (`bytes32`), on a NameNFT by its token (`uint256`).
     * The two are the same 32 bytes; only the function signature differs.
     */
    private fun key(parsed: ParsedName): Pair<String, NameAbi.Word> =
        if (parsed.space == NameSpace.ETH) "bytes32" to NameAbi.bytes32(parsed.node) else "uint256" to NameAbi.uint(parsed.tokenId)

    fun setContenthashData(parsed: ParsedName, contenthash: ByteArray): ByteArray =
        key(parsed).let { (t, k) -> NameAbi.call("setContenthash($t,bytes)", k, NameAbi.Bytes(contenthash)) }

    fun setAddrData(parsed: ParsedName, address: String): ByteArray =
        key(parsed).let { (t, k) -> NameAbi.call("setAddr($t,address)", k, NameAbi.address(address)) }

    fun setTextData(parsed: ParsedName, textKey: String, value: String): ByteArray =
        key(parsed).let { (t, k) -> NameAbi.call("setText($t,string,string)", k, NameAbi.string(textKey), NameAbi.string(value)) }

    fun contenthashData(parsed: ParsedName) = key(parsed).let { (t, k) -> NameAbi.call("contenthash($t)", k) }

    fun textData(parsed: ParsedName, textKey: String) = key(parsed).let { (t, k) -> NameAbi.call("text($t,string)", k, NameAbi.string(textKey)) }

    /** The address record: ENS `addr(bytes32)`; a NameNFT's `resolve(uint256)` (its owner's address when unset, zero once expired). */
    fun addressData(parsed: ParsedName) =
        if (parsed.space == NameSpace.ETH) NameAbi.call("addr(bytes32)", NameAbi.bytes32(parsed.node)) else NameAbi.call("resolve(uint256)", NameAbi.uint(parsed.tokenId))

    fun registryResolverData(node: ByteArray) = NameAbi.call("resolver(bytes32)", NameAbi.bytes32(node))
    fun isApprovedForAllData(owner: String, operator: String) =
        NameAbi.call("isApprovedForAll(address,address)", NameAbi.address(owner), NameAbi.address(operator))
    fun isApprovedForData(owner: String, node: ByteArray, delegate: String) =
        NameAbi.call("isApprovedFor(address,bytes32,address)", NameAbi.address(owner), NameAbi.bytes32(node), NameAbi.address(delegate))
    fun nftRecordVersionData(tokenId: BigInteger) = NameAbi.call("recordVersion(uint256)", NameAbi.uint(tokenId))

    /**
     * The contenthash of a Swarm manifest [reference] (64 lowercase hex
     * digits): desktop's `swarmContenthash`, `0xe40101fa011b20` ‖ reference.
     */
    fun swarmContenthash(reference: String): String {
        if (!Regex("^[0-9a-f]{64}$").matches(reference)) refuse(NameException.Code.INVALID, Strings.said(R.string.namereg_error_reference))
        return "0xe40101fa011b20$reference"
    }

    /** Longest existing record read for a direct edit (desktop's `boundedRecord`, 8 KiB). */
    const val MAX_RECORD_BYTES = 8192

    /** Longest text record key and value a write takes (desktop's 128 and 2048). */
    const val MAX_TEXT_KEY = 128
    const val MAX_TEXT_VALUE = 2048

    // Reads.

    fun availableData(label: String) = NameAbi.call("available(string)", NameAbi.string(label))
    fun validData(label: String) = NameAbi.call("valid(string)", NameAbi.string(label))
    fun rentPriceData(label: String, duration: Long) = NameAbi.call("rentPrice(string,uint256)", NameAbi.string(label), NameAbi.uint(duration))
    fun commitmentsData(commitment: ByteArray) = NameAbi.call("commitments(bytes32)", NameAbi.bytes32(commitment))
    fun minCommitmentAgeData() = NameAbi.call("minCommitmentAge()")
    fun maxCommitmentAgeData() = NameAbi.call("maxCommitmentAge()")
    fun minRegistrationDurationData() = NameAbi.call("MIN_REGISTRATION_DURATION()")
    fun registryOwnerData(node: ByteArray) = NameAbi.call("owner(bytes32)", NameAbi.bytes32(node))
    fun nameExpiresData(tokenId: BigInteger) = NameAbi.call("nameExpires(uint256)", NameAbi.uint(tokenId))
    fun ownerOfData(tokenId: BigInteger) = NameAbi.call("ownerOf(uint256)", NameAbi.uint(tokenId))
    fun controllersData(controller: String) = NameAbi.call("controllers(address)", NameAbi.address(controller))
    fun wrapperGetData(node: ByteArray) = NameAbi.call("getData(uint256)", NameAbi.Word(node.copyOf()))
    fun nftNormalizeData(label: String) = NameAbi.call("normalize(string)", NameAbi.string(label))
    fun nftComputeIdData(name: String) = NameAbi.call("computeId(string)", NameAbi.string(name))
    fun nftIsAvailableData(label: String, parent: BigInteger) = NameAbi.call("isAvailable(string,uint256)", NameAbi.string(label), NameAbi.uint(parent))
    fun nftRecordsData(tokenId: BigInteger) = NameAbi.call("records(uint256)", NameAbi.uint(tokenId))
    fun nftGetFeeData(length: Int) = NameAbi.call("getFee(uint256)", NameAbi.uint(length.toLong()))
    fun nftGetPremiumData(tokenId: BigInteger) = NameAbi.call("getPremium(uint256)", NameAbi.uint(tokenId))

    fun hex(b: ByteArray): String = "0x" + b.toHexString()
}
