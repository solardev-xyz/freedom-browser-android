package baby.freedom.mobile.names

import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.names.NameContracts.Role
import java.math.BigInteger
import java.util.Base64
import org.json.JSONObject

/** A tiny mainnet: the name contracts' state, keyed by what the registrar asks. */
internal class FakeNameChain : NameReads {
    var block = 100L
    var timestamp = 1_800_000_000L
    val available = HashMap<String, Boolean>()
    val owner = HashMap<String, String>()
    val expiry = HashMap<String, BigInteger>()
    val commitments = HashMap<String, BigInteger>()
    val receipts = HashMap<String, JSONObject>()
    var base = BigInteger("3000000000000000")
    var premium = BigInteger.ZERO
    var nftFee = BigInteger("1000000000000000")
    var unverified = false
    var receiptsUnverified = false
    var balance: BigInteger = BigInteger.TEN.pow(18)
    var revertSimulation = false
    val tampered = HashSet<String>()
    val simulated = ArrayList<Pair<ByteArray, BigInteger?>>()

    // Records (#599), keyed like [owner] ("eth", "wei", "gwei").
    val resolver = HashMap<String, String>()
    val contenthash = HashMap<String, String>()
    val addr = HashMap<String, String>()
    val text = HashMap<String, HashMap<String, String>>()
    val recordVersion = HashMap<String, BigInteger>()
    /** (owner, operator) pairs the public resolver approves for all names. */
    val approvedForAll = HashSet<Pair<String, String>>()

    fun code(address: String) = "0x" + address.removePrefix("0x").lowercase() + if (address.lowercase() in tampered) "ff" else ""

    val pinned: (Role) -> String = { role -> NameContracts.hex(Keccak256.digest(NameRegistrar.hexBytes("0x" + role.address.removePrefix("0x").lowercase()))) }

    private fun check() {
        if (unverified) refuse(NameException.Code.UNVERIFIED, "unverified")
    }

    override suspend fun latest(): NameChain.Snapshot {
        check()
        return NameChain.Snapshot(block, timestamp)
    }

    override suspend fun at(number: Long) = NameChain.Snapshot(number, timestamp)

    override suspend fun code(address: String, block: Long): String {
        check()
        return code(address)
    }

    override suspend fun receipt(hash: String): JSONObject? {
        if (receiptsUnverified) refuse(NameException.Code.UNVERIFIED, "unverified")
        return receipts[hash]
    }

    override suspend fun balance(address: String, block: Long): BigInteger = balance

    private fun word(v: BigInteger) = NameAbi.uint(v).bytes
    private fun ret(vararg words: ByteArray) = "0x" + words.joinToString("") { w -> w.joinToString("") { "%02x".format(it) } }
    private fun addr(a: String) = NameAbi.address(a).bytes
    private fun bool(b: Boolean) = word(if (b) BigInteger.ONE else BigInteger.ZERO)
    private fun str(s: String): String = "0x" + NameAbi.encode(listOf(NameAbi.string(s))).joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String): String = "0x" + NameAbi.encode(listOf(NameAbi.Bytes(NameRegistrar.hexBytes(hex)))).joinToString("") { "%02x".format(it) }

    override suspend fun call(to: String, data: ByteArray, block: Long, from: String?, value: BigInteger?): String {
        check()
        val sel = data.copyOfRange(0, 4)
        fun s(sig: String) = sel.contentEquals(NameAbi.selector(sig))
        val role = Role.byAddress(to) ?: error("unknown contract $to")
        if (from != null) {
            simulated += data to value
            if (revertSimulation) throw ChainRpcException.Rpc(3, "execution reverted", "0x")
            return "0x"
        }
        val words = NameAbi.words("0x" + data.copyOfRange(4, data.size).joinToString("") { "%02x".format(it) })!!
        val label = { NameAbi.dynamicString(words, 0)!! }
        return when {
            s("owner(bytes32)") -> ret(addr(owner["eth"] ?: NameContracts.ZERO_ADDRESS))
            s("nameExpires(uint256)") -> ret(word(expiry["eth"] ?: BigInteger.ZERO))
            s("ownerOf(uint256)") -> ret(addr(owner[if (role == Role.BASE) "eth" else role.name.lowercase()]!!))
            s("controllers(address)") -> ret(bool(true))
            s("available(string)") -> ret(bool(available["eth"] ?: true))
            s("valid(string)") -> ret(bool(label().length >= 3))
            s("rentPrice(string,uint256)") -> ret(word(base), word(premium))
            s("commitments(bytes32)") -> ret(word(commitments[NameContracts.hex(words[0])] ?: BigInteger.ZERO))
            s("minCommitmentAge()") -> ret(word(BigInteger.valueOf(60)))
            s("maxCommitmentAge()") -> ret(word(BigInteger.valueOf(86_400)))
            s("MIN_REGISTRATION_DURATION()") -> ret(word(BigInteger.valueOf(28 * 86_400)))
            s("isAvailable(string,uint256)") -> ret(bool(available[role.name.lowercase()] ?: true))
            s("records(uint256)") -> {
                val key = role.name.lowercase()
                val l = if (owner[key] != null) "freedom" else ""
                val enc = NameAbi.encode(
                    listOf(NameAbi.string(l), NameAbi.uint(0), NameAbi.uint(expiry[key] ?: BigInteger.ZERO), NameAbi.uint(1), NameAbi.uint(0)),
                )
                "0x" + enc.joinToString("") { "%02x".format(it) }
            }
            s("getFee(uint256)") -> ret(word(nftFee))
            s("getPremium(uint256)") -> ret(word(premium))
            s("normalize(string)") -> str(label())
            s("computeId(string)") -> ret(word(NameContracts.parse(label()).tokenId))
            s("resolver(bytes32)") -> ret(addr(resolver["eth"] ?: Role.RESOLVER.address))
            s("contenthash(bytes32)") || s("contenthash(uint256)") -> bytes(contenthash[key(role)] ?: "0x")
            s("addr(bytes32)") -> ret(addr(addr["eth"] ?: NameContracts.ZERO_ADDRESS))
            s("resolve(uint256)") -> {
                val k = key(role)
                val live = (expiry[k] ?: BigInteger.ZERO) > BigInteger.valueOf(timestamp)
                ret(addr(if (!live) NameContracts.ZERO_ADDRESS else addr[k] ?: owner[k] ?: NameContracts.ZERO_ADDRESS))
            }
            s("text(bytes32,string)") || s("text(uint256,string)") -> str(text[key(role)]?.get(NameAbi.dynamicString(words, 1)!!) ?: "")
            s("isApprovedForAll(address,address)") ->
                ret(bool((NameAbi.wordAddress(words[0])!! to NameAbi.wordAddress(words[1])!!).let { (o, op) -> approvedForAll.any { it.first.equals(o, true) && it.second.equals(op, true) } }))
            s("isApprovedFor(address,bytes32,address)") -> ret(bool(false))
            s("recordVersion(uint256)") -> ret(word(recordVersion[key(role)] ?: BigInteger.ONE))
            else -> error("unexpected call ${sel.joinToString("") { "%02x".format(it) }}")
        }
    }

    private fun key(role: Role) = if (role == Role.RESOLVER || role == Role.REGISTRY || role == Role.BASE) "eth" else role.name.lowercase()

    fun mine(hash: String, ok: Boolean = true) {
        block++
        receipts[hash] = JSONObject().put("transactionHash", hash).put("status", if (ok) "0x1" else "0x0").put("blockNumber", "0x" + block.toString(16))
    }
}

internal object PlainNameSecrets : NameSecrets {
    override fun seal(secret: ByteArray): String = Base64.getEncoder().encodeToString(secret)
    override fun open(sealed: String): ByteArray = Base64.getDecoder().decode(sealed)
}
