package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** EIP-191 and EIP-712 signing (#110), against vectors from ethers v6 and the EIP-712 spec's own example. */
class MessageSigningTest {
    /** The EIP-712 spec's key: keccak256("cow"), address 0xCD2a…D826. */
    private val cow = Keccak256.digest("cow".toByteArray())
    private val cowAddress = "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"

    private val mail = """
        {"types":{
           "EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},
                           {"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],
           "Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],
           "Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},
         "primaryType":"Mail",
         "domain":{"name":"Ether Mail","version":"1","chainId":1,"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},
         "message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},
                    "to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},
                    "contents":"Hello, Bob!"}}
    """.trimIndent()

    @Test
    fun `the EIP-712 spec's Mail example hashes and signs as the spec and ethers do`() {
        val data = Eip712.parse(mail)
        assertEquals(
            "f2cee375fa42b42143804025fc449deafd50cc031ca257e0b194a650a912090f",
            Eip712.hashStruct(data.types, "EIP712Domain", data.domain, 0).toHex(),
        )
        val digest = Eip712.digest(data)
        assertEquals("be609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", digest.toHex())
        assertEquals(
            "0x4355c47d63924e8a72e509b65029052eb6c299d53a04e167c5775fd466751c9d" +
                "07299936d304c153f6443dfa05f40ff007d72911b6f72307f996231605b915621c",
            MessageSigning.sign(cow, cowAddress, digest),
        )
        assertEquals(1L.toBigInteger(), data.chainId)
    }

    @Test
    fun `typed data given as an object hashes the same as its JSON text`() {
        assertEquals(Eip712.digest(Eip712.parse(mail)).toHex(), Eip712.digest(Eip712.parse(JSONObject(mail))).toHex())
    }

    @Test
    fun `arrays, fixed arrays of structs, negative ints, bytes and bytesN match ethers`() {
        // ethers.TypedDataEncoder.hash({name:'X', chainId:100}, types, value); no EIP712Domain in
        // types, so it's built from the domain's own fields as ethers and viem do.
        val json = """
            {"types":{
               "Order":[{"name":"amounts","type":"uint256[]"},{"name":"delta","type":"int8"},{"name":"blob","type":"bytes"},
                        {"name":"tag","type":"bytes4"},{"name":"ok","type":"bool"},{"name":"people","type":"Person[2]"}],
               "Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}]},
             "primaryType":"Order",
             "domain":{"name":"X","chainId":100},
             "message":{"amounts":[1,"0x10","1000000000000000000000"],"delta":-5,"blob":"0x0102","tag":"0xdeadbeef","ok":true,
                        "people":[{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},
                                  {"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"}]}}
        """.trimIndent()
        assertEquals("0d0fbf54d68fa1d57a36eee9b90183dd985584f16ea5498512e00f6471b53c0b", Eip712.digest(Eip712.parse(json)).toHex())
    }

    @Test
    fun `personal_sign matches ethers signMessage for text and for bytes`() {
        assertEquals(
            "0x2452a50a1b27db559e685e82ef59445ff08ca6843b5089aa1c32a70db206d47d" +
                "693e5ae94daffccbbf590c5d2a72ad5706994748d2c8d3a8b39355589e16e8751c",
            MessageSigning.sign(cow, cowAddress, MessageSigning.personalDigest("hello".toByteArray())),
        )
        val sig = MessageSigning.sign(cow, cowAddress, MessageSigning.personalDigest(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
        assertEquals(
            "0x7a962b63cef41a9cc1d3a6805da9f982a2a562b2d7a1ee75c78e5cd4464db9bf" +
                "6e2b425bae2ce2c74a47631a2ec67c7efcc3dade1201fd5306443b85ec6116071b",
            sig,
        )
        assertEquals(cowAddress.lowercase(), Secp256k1.recoverPersonalSign(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), sig)?.lowercase())
    }

    @Test
    fun `a signature for another account never comes back`() {
        assertThrows(IllegalStateException::class.java) {
            MessageSigning.sign(cow, "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB", ByteArray(32) { 1 })
        }
    }

    @Test
    fun `bad typed data is refused, never a crash`() {
        fun bad(json: String) = assertThrows(Eip712.Invalid::class.java) { Eip712.digest(Eip712.parse(json)) }
        bad("not json")
        bad("""{"types":{},"primaryType":"Mail","domain":{},"message":{}}""")
        bad("""{"types":{"M":[{"name":"a","type":"address"}]},"primaryType":"M","domain":{},"message":{"a":"0x12"}}""")
        bad("""{"types":{"M":[{"name":"a","type":"uint8"}]},"primaryType":"M","domain":{},"message":{"a":256}}""")
        bad("""{"types":{"M":[{"name":"a","type":"int8"}]},"primaryType":"M","domain":{},"message":{"a":-129}}""")
        bad("""{"types":{"M":[{"name":"a","type":"uint256"}]},"primaryType":"M","domain":{},"message":{}}""")
        bad("""{"types":{"M":[{"name":"a","type":"bytes2"}]},"primaryType":"M","domain":{},"message":{"a":"0x010203"}}""")
        bad("""{"types":{"M":[{"name":"a","type":"uint256[2]"}]},"primaryType":"M","domain":{},"message":{"a":[1]}}""")
        bad("""{"types":{"M":[{"name":"a","type":"Nope"}]},"primaryType":"M","domain":{},"message":{"a":1}}""")
        // A type that contains itself, as deep as the page likes.
        val deep = "{\"n\":".repeat(60) + "null" + "}".repeat(60)
        bad("""{"types":{"M":[{"name":"n","type":"M"}]},"primaryType":"M","domain":{},"message":$deep}""")
    }

    @Test
    fun `a struct under several array suffixes is still a dependency, and a malformed suffix isn't an array`() {
        val types = mapOf(
            "Main" to listOf(Eip712.Field("grid", "Person[2][][3]"), Eip712.Field("odd", "Thing[x]")),
            "Person" to listOf(Eip712.Field("name", "string")),
            "Thing" to listOf(Eip712.Field("v", "uint8")),
        )
        assertEquals("Main(Person[2][][3] grid,Thing[x] odd)Person(string name)", Eip712.encodeType(types, "Main"))
    }
}
