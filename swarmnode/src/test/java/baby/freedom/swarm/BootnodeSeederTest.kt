package baby.freedom.swarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BootnodeSeederTest {
    private val hel = "/ip4/135.181.84.53/tcp/1634/p2p/QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK"
    private val helWss = "/ip4/135.181.84.53/tcp/1635/tls/sni/135-181-84-53.x.libp2p.direct/ws/p2p/QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK"
    private val ams = "/ip4/159.223.6.181/tcp/1634/p2p/QmP9b7MxjyEfrJrch5jUThmuFaGzvUPpWEJewCpx5Ln6i8"

    @Test
    fun parsesCloudflareQuotedAndGoogleBareTxt() {
        val cf = """{"Status":0,"Answer":[{"type":16,"data":"\"dnsaddr=/dnsaddr/emea.mainnet.ethswarm.org\""}]}"""
        assertEquals(listOf("dnsaddr=/dnsaddr/emea.mainnet.ethswarm.org"), BootnodeSeeder.parseDohTxt(cf))
        val google = """{"Status":0,"Answer":[{"type":16,"data":"dnsaddr=/dnsaddr/emea.mainnet.ethswarm.org"}]}"""
        assertEquals(listOf("dnsaddr=/dnsaddr/emea.mainnet.ethswarm.org"), BootnodeSeeder.parseDohTxt(google))
    }

    @Test
    fun joinsMultiPartTxtAndSkipsNonTxtAnswers() {
        val body = """{"Status":0,"Answer":[{"type":5,"data":"alias."},{"type":16,"data":"\"dnsaddr=/ip4/1.2\" \".3.4/tcp/1\""}]}"""
        assertEquals(listOf("dnsaddr=/ip4/1.2.3.4/tcp/1"), BootnodeSeeder.parseDohTxt(body))
    }

    @Test
    fun errorStatusOrGarbageIsFailure() {
        assertNull(BootnodeSeeder.parseDohTxt("""{"Status":2}"""))
        assertNull(BootnodeSeeder.parseDohTxt("<html>blocked</html>"))
        assertEquals(emptyList<String>(), BootnodeSeeder.parseDohTxt("""{"Status":0}"""))
    }

    @Test
    fun walksTreeKeepsTcpLeavesAndSurvivesFailedBranch() {
        val zone = mapOf(
            "_dnsaddr.mainnet.ethswarm.org" to listOf("dnsaddr=/dnsaddr/emea.mainnet.ethswarm.org"),
            "_dnsaddr.emea.mainnet.ethswarm.org" to listOf(
                "dnsaddr=/dnsaddr/hel.mainnet.ethswarm.org",
                "dnsaddr=/dnsaddr/broken.mainnet.ethswarm.org",
                "dnsaddr=/dnsaddr/ams.mainnet.ethswarm.org",
                "unrelated=txt",
            ),
            "_dnsaddr.hel.mainnet.ethswarm.org" to listOf("dnsaddr=$helWss", "dnsaddr=$hel"),
            "_dnsaddr.ams.mainnet.ethswarm.org" to listOf("dnsaddr=$ams"),
        )
        assertEquals(listOf(hel, ams), BootnodeSeeder.walk(BootnodeSeeder.ROOT) { zone[it] })
    }

    @Test
    fun walkStopsAtMaxDepthAndOnCycles() {
        var queries = 0
        val out = BootnodeSeeder.walk("/dnsaddr/a") { name ->
            queries++
            listOf("dnsaddr=/dnsaddr/${name.removePrefix("_dnsaddr.")}x", "dnsaddr=/dnsaddr/a")
        }
        assertTrue(out.isEmpty())
        assertEquals(4, queries)
    }

    @Test
    fun totalFailureYieldsEmpty() {
        assertTrue(BootnodeSeeder.walk(BootnodeSeeder.ROOT) { null }.isEmpty())
    }

    @Test
    fun peerstoreJsonMatchesAntSnapshotSchema() {
        val snap = JSONObject(BootnodeSeeder.peerstoreJson(listOf(hel, helWss, ams, hel.replace("1634", "1636"))))
        assertEquals(1, snap.getInt("version"))
        val peers = snap.getJSONArray("peers")
        assertEquals(2, peers.length())
        val first = peers.getJSONObject(0)
        assertEquals("QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK", first.getString("peer_id"))
        assertEquals(2, first.getJSONArray("addrs").length())
        assertEquals("", first.getString("overlay"))
        assertEquals(0, first.getLong("last_seen_unix"))
        assertEquals(0, first.getInt("fail_count"))
    }

    @Test
    fun fallbackListIsAllDialable() {
        val snap = JSONObject(BootnodeSeeder.peerstoreJson(BootnodeSeeder.FALLBACK_BOOTNODES))
        assertEquals(BootnodeSeeder.FALLBACK_BOOTNODES.size, snap.getJSONArray("peers").length())
    }

    @Test
    fun seedsOnlyMissingOrEmptyPeerstore() {
        val dir = Files.createTempDirectory("ant").toFile()
        val f = File(dir, "peers.json")
        assertTrue(BootnodeSeeder.needsSeed(f))
        f.writeText("""{"version":1,"peers":[]}""")
        assertTrue(BootnodeSeeder.needsSeed(f))
        f.writeText(BootnodeSeeder.peerstoreJson(listOf(ams)))
        assertFalse(BootnodeSeeder.needsSeed(f))
        f.writeText("""{"version":2,"peers":[]}""")
        assertFalse(BootnodeSeeder.needsSeed(f))
        f.writeText("not json")
        assertFalse(BootnodeSeeder.needsSeed(f))
        dir.deleteRecursively()
    }
}
