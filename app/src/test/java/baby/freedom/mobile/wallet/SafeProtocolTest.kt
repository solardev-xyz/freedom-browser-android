package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe v1.4.1 (#141) against `@safe-global/protocol-kit` 8.0.7 and ethers
 * 6 — what desktop Freedom's `safe-executor.js` / `safe-messages.js` run.
 * Keys 1, 2 and 3 (`0x…01`, `0x…02`, `0x…03`) own a 2-of-3 Safe with salt
 * 2^128 - 1; the vectors are protocol-kit's `getAddress`,
 * `createSafeDeploymentTransaction`, `generateTypedData`, ethers'
 * `TypedDataEncoder.hash` and `Wallet.signTypedData`, `buildSignatureBytes`
 * and the `execTransaction` encoding (script in the PR).
 */
class SafeProtocolTest {
    private val keys = (1..3).map { i -> ByteArray(32).also { it[31] = i.toByte() } }
    private val owners = listOf(
        "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
        "0x2B5AD5c4795c026514f8317c7a215E218DcCD6cF",
        "0x6813Eb9362372EEF6200f3b1dbC3f819671cBA69",
    )
    private val salt = "340282366920938463463374607431768211455"
    private val safe = "0x6d21181D5e0F3a4a438F0CC65FACFd418443b096"

    private val safeTxTypedData = """{"types":{"EIP712Domain":[{"type":"uint256","name":"chainId"},{"type":"address","name":"verifyingContract"}],"SafeTx":[{"type":"address","name":"to"},{"type":"uint256","name":"value"},{"type":"bytes","name":"data"},{"type":"uint8","name":"operation"},{"type":"uint256","name":"safeTxGas"},{"type":"uint256","name":"baseGas"},{"type":"uint256","name":"gasPrice"},{"type":"address","name":"gasToken"},{"type":"address","name":"refundReceiver"},{"type":"uint256","name":"nonce"}]},"domain":{"verifyingContract":"0x6d21181D5e0F3a4a438F0CC65FACFd418443b096","chainId":100},"primaryType":"SafeTx","message":{"to":"0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf","value":"1","data":"0x","operation":0,"baseGas":"0","gasPrice":"0","gasToken":"0x0000000000000000000000000000000000000000","refundReceiver":"0x0000000000000000000000000000000000000000","nonce":5,"safeTxGas":"0"}}"""
    private val tokenTypedData = """{"types":{"EIP712Domain":[{"type":"uint256","name":"chainId"},{"type":"address","name":"verifyingContract"}],"SafeTx":[{"type":"address","name":"to"},{"type":"uint256","name":"value"},{"type":"bytes","name":"data"},{"type":"uint8","name":"operation"},{"type":"uint256","name":"safeTxGas"},{"type":"uint256","name":"baseGas"},{"type":"uint256","name":"gasPrice"},{"type":"address","name":"gasToken"},{"type":"address","name":"refundReceiver"},{"type":"uint256","name":"nonce"}]},"domain":{"verifyingContract":"0x6d21181D5e0F3a4a438F0CC65FACFd418443b096","chainId":100},"primaryType":"SafeTx","message":{"to":"0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da","value":"0","data":"0xa9059cbb0000000000000000000000006813eb9362372eef6200f3b1dbc3f819671cba690000000000000000000000000000000000000000000000000000000000003039","operation":0,"baseGas":"0","gasPrice":"0","gasToken":"0x0000000000000000000000000000000000000000","refundReceiver":"0x0000000000000000000000000000000000000000","nonce":0,"safeTxGas":"0"}}"""
    private val messageTypedData = """{"types":{"EIP712Domain":[{"type":"uint256","name":"chainId"},{"type":"address","name":"verifyingContract"}],"SafeMessage":[{"type":"bytes","name":"message"}]},"domain":{"chainId":100,"verifyingContract":"0x6d21181D5e0F3a4a438F0CC65FACFd418443b096"},"primaryType":"SafeMessage","message":{"message":"0x24ea1a91429db4353dd376ca20b21a8f0cda18ae9d4a872cfcb1d149f9e527e8"}}"""

    /** Order-independent JSON equality (numbers vs. strings kept apart: a `nonce` must stay a number). */
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        is Number -> "n" + v.toLong()
        else -> v.toString()
    }

    private fun sameJson(expected: String, actual: JSONObject) =
        assertEquals(canonical(JSONObject(expected)), canonical(JSONObject(actual.toString())))

    @Test
    fun `the counterfactual address is protocol-kit's`() {
        assertEquals(safe, SafeProtocol.predictAddress(owners, 2, salt))
        // A 1-of-2 with a small salt, the first vector taken on Gnosis.
        assertEquals(
            "0x184F8cc343c6266afE707Ab15F46255393c19B62",
            SafeProtocol.predictAddress(listOf("0x1111111111111111111111111111111111111111", "0x2222222222222222222222222222222222222222"), 1, "12345"),
        )
        // Any change to the frozen params is another address.
        assertFalse(SafeProtocol.predictAddress(owners, 2, "1") == safe)
        assertFalse(SafeProtocol.predictAddress(owners.reversed(), 2, salt) == safe)
    }

    @Test
    fun `the deployment is protocol-kit's createProxyWithNonce call`() {
        assertEquals(
            "0x1688f0b900000000000000000000000029fcb43b46531bca003ddc8fcb67ffe91900c76200000000000000000000000000" +
            "0000000000000000000000000000000000006000000000000000000000000000000000ffffffffffffffffffffffffffffff" +
            "ff00000000000000000000000000000000000000000000000000000000000001a4b63e800d00000000000000000000000000" +
            "0000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000" +
            "0200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000180000000000000000000000000fd0732dc9e303f09fcef3a7388ad10a83459ec99000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000030000000000000000000000007e5f4552091a69125d5dfcb7b8" +
            "c2659029395bdf0000000000000000000000002b5ad5c4795c026514f8317c7a215e218dccd6cf0000000000000000000000" +
            "006813eb9362372eef6200f3b1dbc3f819671cba690000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000000000",
            "0x" + SafeProtocol.deploymentData(owners, 2, salt).toHex(),
        )
    }

    @Test
    fun `a SafeTx is the typed data protocol-kit generates, with its hash`() {
        val tx = SafeProtocol.SafeTx(owners[0], BigInteger.ONE, ByteArray(0), BigInteger.valueOf(5))
        val td = SafeProtocol.safeTxTypedData(safe, 100, tx)
        sameJson(safeTxTypedData, td)
        assertEquals("0xd3ce8dcfdaaca24797480ef6c320a70cf53217ce6e274a32fca45083fcc0abcc", "0x" + SafeProtocol.hash(td).toHex())
        // An ERC-20 transfer, with call data.
        val transfer = SafeProtocol.SafeTx(
            "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da",
            BigInteger.ZERO,
            Erc20.transferData(owners[2], BigInteger.valueOf(12345)),
            BigInteger.ZERO,
        )
        val td2 = SafeProtocol.safeTxTypedData(safe, 100, transfer)
        sameJson(tokenTypedData, td2)
        assertEquals("0x489c83fd24613472dd5351c105880c3c0d3f51737320c4581563424033ce31b7", "0x" + SafeProtocol.hash(td2).toHex())
    }

    @Test
    fun `owners sign it exactly as ethers does, and the execTransaction call packs them sorted`() {
        val tx = SafeProtocol.SafeTx(owners[0], BigInteger.ONE, ByteArray(0), BigInteger.valueOf(5))
        val hash = SafeProtocol.hash(SafeProtocol.safeTxTypedData(safe, 100, tx))
        val sig0 = MessageSigning.sign(keys[0], owners[0], hash)
        val sig1 = MessageSigning.sign(keys[1], owners[1], hash)
        assertEquals("0x50e9273da2fc63568a257ccfb7f53a625bc15e35ab469fa88bf52e445acc1ac4040a0b9e8dff77420246f5498c54c29fc3dc427ba34fb4770f88ce53ba32aa9a1b", sig0)
        assertEquals("0xc3498f3266f485b699e93a4fa900e1bd87444152e64e2416906591464425da4d6df029099e6f7d046131554bba58da8a43bb731e0ab2875eb643e1126a77a4151b", sig1)
        assertEquals(owners[1], SafeProtocol.recoverSigner(hash, sig1))
        // Signed in either order, packed by signer address.
        val sigs = listOf(SafeProtocol.OwnerSignature(owners[0], sig0), SafeProtocol.OwnerSignature(owners[1], sig1))
        assertEquals("0xc3498f3266f485b699e93a4fa900e1bd87444152e64e2416906591464425da4d6df029099e6f7d046131554bba58da8a43bb731e0ab2875eb643e1126a77a4151b50e9273da2fc63568a257ccfb7f53a625bc15e35ab469fa88bf52e445acc1ac4040a0b9e8dff77420246f5498c54c29fc3dc427ba34fb4770f88ce53ba32aa9a1b", "0x" + SafeProtocol.signatureBytes(sigs).toHex())
        assertEquals("0xc3498f3266f485b699e93a4fa900e1bd87444152e64e2416906591464425da4d6df029099e6f7d046131554bba58da8a43bb731e0ab2875eb643e1126a77a4151b50e9273da2fc63568a257ccfb7f53a625bc15e35ab469fa88bf52e445acc1ac4040a0b9e8dff77420246f5498c54c29fc3dc427ba34fb4770f88ce53ba32aa9a1b", "0x" + SafeProtocol.signatureBytes(sigs.reversed()).toHex())
        assertEquals(
            "0x6a7612020000000000000000000000007e5f4552091a69125d5dfcb7b8c2659029395bdf00000000000000000000000000" +
            "0000000000000000000000000000000000000100000000000000000000000000000000000000000000000000000000000001" +
            "4000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000016000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000082c3498f3266f485b699e93a" +
            "4fa900e1bd87444152e64e2416906591464425da4d6df029099e6f7d046131554bba58da8a43bb731e0ab2875eb643e1126a" +
            "77a4151b50e9273da2fc63568a257ccfb7f53a625bc15e35ab469fa88bf52e445acc1ac4040a0b9e8dff77420246f5498c54" +
            "c29fc3dc427ba34fb4770f88ce53ba32aa9a1b000000000000000000000000000000000000000000000000000000000000",
            "0x" + SafeProtocol.execTransactionData(tx, sigs).toHex(),
        )
    }

    @Test
    fun `a SafeMessage is desktop's envelope over the EIP-191 digest, and its EIP-1271 signature packs sorted`() {
        val td = SafeProtocol.messageTypedData(safe, 100, "hello safe")
        sameJson(messageTypedData, td)
        val hash = SafeProtocol.hash(td)
        assertEquals("0x66462fb0e0b6eeaa1878ab3126a5013fc889acb537eb3522bb37f02262222c99", "0x" + hash.toHex())
        val sig2 = MessageSigning.sign(keys[2], owners[2], hash)
        val sig0 = MessageSigning.sign(keys[0], owners[0], hash)
        assertEquals("0xd8715164699a3e825e35b229030737c81331bedf1799cff034245547b1c4043a5013c481074bca75ba14f93542dce8263ba9503aab406612c52cbf931ade5fd91b", sig2)
        assertEquals("0xb87207e43bdc325962093e04bafb602fcb762892b0493fcc925adaf90f7145db57e46fda39956861267846517417a1beb121d89c6d706f0a1e6d714fca46295a1b", sig0)
        val pending = SafePending(
            "0x" + hash.toHex(), safe, SafePending.Kind.MESSAGE, 100, td.toString(), 2,
            listOf(SafeProtocol.OwnerSignature(owners[0], sig0), SafeProtocol.OwnerSignature(owners[2], sig2)), 0L, text = "hello safe",
        )
        assertEquals("0xd8715164699a3e825e35b229030737c81331bedf1799cff034245547b1c4043a5013c481074bca75ba14f93542dce8263ba9503aab406612c52cbf931ade5fd91bb87207e43bdc325962093e04bafb602fcb762892b0493fcc925adaf90f7145db57e46fda39956861267846517417a1beb121d89c6d706f0a1e6d714fca46295a1b", pending.combinedSignature())
    }

    @Test
    fun `a shared request reads back as what was signed`() {
        val tx = SafeProtocol.SafeTx(owners[0], BigInteger.ONE, ByteArray(0), BigInteger.valueOf(5))
        val r = SafeProtocol.parseRequest(safeTxTypedData) as SafeProtocol.Request.Tx
        assertEquals(safe, r.safe)
        assertEquals(100L, r.chainId)
        assertEquals(tx, r.tx)
        assertEquals("0xd3ce8dcfdaaca24797480ef6c320a70cf53217ce6e274a32fca45083fcc0abcc", "0x" + r.hash.toHex())
        // A message comes with its words, which must be what the digest is of.
        val shared = SafeProtocol.shareText(JSONObject(messageTypedData), "hello safe")
        val m = SafeProtocol.parseRequest(shared) as SafeProtocol.Request.Message
        assertEquals("hello safe", m.text)
        assertEquals("0x66462fb0e0b6eeaa1878ab3126a5013fc889acb537eb3522bb37f02262222c99", "0x" + m.hash.toHex())
        // Without the words, the 32-byte `message` could be any hash (a Permit2 permit's): refused.
        assertThrows(Eip712.Invalid::class.java) { SafeProtocol.parseRequest(messageTypedData.toString()) }
        assertThrows(Eip712.Invalid::class.java) {
            SafeProtocol.parseRequest(JSONObject(messageTypedData.toString()).put("text", JSONObject.NULL).toString())
        }
        assertThrows(Eip712.Invalid::class.java) { SafeProtocol.parseRequest(SafeProtocol.shareText(JSONObject(messageTypedData), "hello, safe")) }
    }

    @Test
    fun `requests Freedom wouldn't build are refused`() {
        fun with(change: (JSONObject) -> Unit) = JSONObject(safeTxTypedData).also(change).toString()
        val refused = listOf(
            with { it.getJSONObject("message").put("operation", 1) },
            with { it.getJSONObject("message").put("gasPrice", "1") },
            with { it.getJSONObject("message").put("refundReceiver", owners[1]) },
            with { it.getJSONObject("message").put("safeTxGas", "100000") },
            with { it.getJSONObject("domain").put("name", "Safe") },
            with { it.getJSONObject("types").getJSONArray("SafeTx").remove(9) },
            with { it.put("primaryType", "Mail") },
            with { it.getJSONObject("domain").remove("chainId") },
            "not json",
            "{" + "\"a\":".repeat(1) + "\"" + "x".repeat(SafeProtocol.MAX_REQUEST) + "\"}",
        )
        for (raw in refused) assertThrows(raw.take(80), Eip712.Invalid::class.java) { SafeProtocol.parseRequest(raw) }
    }

    @Test
    fun `signatures are taken with v 27 or 28, or 0 or 1, and nothing else`() {
        val sig = "0x50e9273da2fc63568a257ccfb7f53a625bc15e35ab469fa88bf52e445acc1ac4040a0b9e8dff77420246f5498c54c29fc3dc427ba34fb4770f88ce53ba32aa9a1b"
        assertEquals(sig, SafeProtocol.normalized(sig.uppercase().replace("0X", "0x")))
        assertEquals(sig, SafeProtocol.normalized(sig.dropLast(2) + "00"))
        assertNull(SafeProtocol.normalized(sig.dropLast(2) + "1f"))
        assertNull(SafeProtocol.normalized(sig.dropLast(2)))
        assertNull(SafeProtocol.normalized("0x" + "zz".repeat(65)))
    }

    @Test
    fun `presets are 1 of 2 and 2 of 3, and init params are checked`() {
        assertTrue(SafeProtocol.validPreset(2, 1))
        assertTrue(SafeProtocol.validPreset(3, 2))
        assertFalse(SafeProtocol.validPreset(2, 2))
        assertFalse(SafeProtocol.validPreset(3, 1))
        assertThrows(IllegalArgumentException::class.java) { SafeProtocol.predictAddress(listOf(owners[0], owners[0].lowercase()), 1, "1") }
        assertThrows(IllegalArgumentException::class.java) { SafeProtocol.predictAddress(listOf(owners[0], SafeProtocol.ZERO_ADDRESS), 1, "1") }
        assertThrows(IllegalArgumentException::class.java) { SafeProtocol.predictAddress(owners, 2, "-1") }
        val a = SafeProtocol.newSaltNonce()
        assertTrue(a.all { it.isDigit() } && BigInteger(a).bitLength() <= 128)
        assertFalse(a == SafeProtocol.newSaltNonce())
    }

    @Test
    fun `owner lists and nonces decode from the Safe's answers`() {
        val words = listOf(32, 2).joinToString("") { it.toString(16).padStart(64, '0') } +
            owners.take(2).joinToString("") { "0".repeat(24) + it.substring(2).lowercase() }
        assertEquals(owners.take(2), SafeProtocol.decodeAddresses("0x$words"))
        assertNull(SafeProtocol.decodeAddresses("0x" + words.dropLast(2)))
        assertNull(SafeProtocol.decodeAddresses("0x"))
        assertEquals("0xaffed0e0", SafeProtocol.NONCE_CALL)
        assertEquals("0xa0e67e2b", SafeProtocol.OWNERS_CALL)
    }
}
