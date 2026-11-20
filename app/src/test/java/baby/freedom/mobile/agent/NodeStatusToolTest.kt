package baby.freedom.mobile.agent

import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisRecovery
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStatusToolTest {
    private fun nodes(s: NodeSnapshot): Map<String, JSONObject> {
        val list = NodeStatusReport.build(s).getJSONArray("nodes")
        return (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }
    }

    private val synced = MyotisChainStatus(
        chainId = 1L, beaconState = MyotisChainStatus.SYNCED, peerCount = 12, snapPeers = 3, snapServingPeers = 2,
        running = true, elReaderAvailable = true,
    )

    @Test
    fun `desktop's six services, ids, fields and order`() {
        val report = NodeStatusReport.build(NodeSnapshot())
        val list = report.getJSONArray("nodes")
        assertEquals(
            listOf("ant", "ipfs", "radicle", "tor", "myotis-ethereum", "myotis-gnosis"),
            (0 until list.length()).map { list.getJSONObject(it).getString("id") },
        )
        val ant = list.getJSONObject(0)
        assertEquals("Swarm", ant.getString("name"))
        assertEquals("Ant", ant.getString("implementation"))
        assertEquals("bzz", ant.getJSONArray("protocols").getString(0))
        // Run node off is the Swarm node stopped, which node_lifecycle can start (#598).
        assertEquals(setOf("id", "name", "implementation", "protocols", "state", "mode", "running", "ready"), ant.keys().asSequence().toSet())
        assertEquals("stopped", ant.getString("state"))
        assertEquals(1L, list.getJSONObject(4).getLong("chainId"))
        assertEquals(100L, list.getJSONObject(5).getLong("chainId"))
        // Everything switched off: IPFS, Radicle and Tor off in Settings (or with no node to run in), the rest stopped.
        val summary = report.getJSONObject("summary")
        assertEquals(6, summary.getInt("total"))
        assertEquals(3, summary.getInt("disabled"))
        assertEquals("stopped", list.getJSONObject(4).getString("state"))
        assertEquals(0, summary.getInt("ready"))
    }

    @Test
    fun `states follow the nodes, as the Nodes & networks page reads them`() {
        val n = nodes(
            NodeSnapshot(
                swarm = NodeInfo(status = NodeStatus.Running, connectedPeers = 40),
                runNode = true,
                ipfs = IpfsInfo(status = IpfsStatus.Starting),
                radicle = RadicleInfo(status = RadicleStatus.Error),
                radicleEnabled = true,
                tor = TorInfo(status = TorStatus.Stopped),
                torEnabled = true,
                myotis = MyotisInfo(
                    status = MyotisStatus.Running,
                    chains = listOf(synced, synced.copy(chainId = 100L, beaconState = "SYNCING", peerCount = 5)),
                ),
                myotisRunning = setOf(MyotisNetwork.Mainnet, MyotisNetwork.Gnosis),
            ),
        )
        assertEquals("running", n["ant"]!!.getString("state"))
        assertTrue(n["ant"]!!.getBoolean("ready"))
        assertEquals("embedded", n["ant"]!!.getString("mode"))
        assertEquals("starting", n["ipfs"]!!.getString("state"))
        assertFalse(n["ipfs"]!!.getBoolean("running"))
        assertEquals("error", n["radicle"]!!.getString("state"))
        assertTrue(n["radicle"]!!.getString("recovery").isNotEmpty())
        assertEquals("stopped", n["tor"]!!.getString("state"))
        assertEquals("ready", n["myotis-ethereum"]!!.getString("state"))
        assertEquals(12, n["myotis-ethereum"]!!.getInt("peerCount"))
        assertEquals("syncing", n["myotis-gnosis"]!!.getString("state"))
        assertTrue(n["myotis-gnosis"]!!.getBoolean("running"))
        assertFalse(n["myotis-gnosis"]!!.getBoolean("ready"))
        val summary = NodeStatusReport.build(
            NodeSnapshot(
                swarm = NodeInfo(status = NodeStatus.Running), runNode = true, ipfs = IpfsInfo(status = IpfsStatus.Starting),
                radicle = RadicleInfo(status = RadicleStatus.Error), radicleEnabled = true, torEnabled = true,
                myotis = MyotisInfo(MyotisStatus.Running, listOf(synced)), myotisRunning = setOf(MyotisNetwork.Mainnet),
            ),
        ).getJSONObject("summary")
        assertEquals(6, summary.getInt("total"))
        assertEquals(2, summary.getInt("ready"))
        // Swarm, IPFS, the Ethereum chain; the Gnosis chain switched off is stopped.
        assertEquals(3, summary.getInt("active"))
        assertEquals(0, summary.getInt("disabled"))
        assertEquals(1, summary.getInt("attention"))
    }

    @Test
    fun `a chain switched on that failed, and the user's own endpoints`() {
        val n = nodes(
            NodeSnapshot(
                externalSwarm = true,
                externalIpfs = true,
                tor = TorInfo(status = TorStatus.Running),
                torEnabled = true,
                torProxy = true,
                myotis = MyotisInfo(MyotisStatus.Running, listOf(synced.copy(chainId = 100L, error = "boom"))),
                myotisRunning = setOf(MyotisNetwork.Gnosis),
            ),
        )
        assertEquals("external", n["ant"]!!.getString("mode"))
        assertEquals("running", n["ant"]!!.getString("state"))
        assertEquals("external", n["ipfs"]!!.getString("mode"))
        assertEquals("Orbot", n["tor"]!!.getString("implementation"))
        assertEquals("external", n["tor"]!!.getString("mode"))
        assertEquals("stopped", n["myotis-ethereum"]!!.getString("state"))
        assertEquals("error", n["myotis-gnosis"]!!.getString("state"))
    }

    @Test
    fun `a checkpoint recovery that needs the user is an error, one under way is syncing, and a phone without Tor says so`() {
        fun gnosis(recovery: MyotisRecovery?) = nodes(
            NodeSnapshot(
                myotis = MyotisInfo(MyotisStatus.Running, listOf(synced.copy(chainId = 100L, recovery = recovery))),
                myotisRunning = setOf(MyotisNetwork.Gnosis),
                torSupported = false,
            ),
        )
        assertEquals("error", gnosis(MyotisRecovery(MyotisRecovery.Phase.Blocked))["myotis-gnosis"]!!.getString("state"))
        assertEquals("syncing", gnosis(MyotisRecovery(MyotisRecovery.Phase.Checking))["myotis-gnosis"]!!.getString("state"))
        val tor = gnosis(null)["tor"]!!
        assertEquals("unavailable", tor.getString("state"))
        assertFalse(tor.getBoolean("ready"))
    }

    @Test
    fun `a paused chain on a stale anchor is stopped, as the page shows Paused, and one not paused is syncing`() {
        fun gnosis(paused: Boolean) = nodes(
            NodeSnapshot(
                myotis = MyotisInfo(
                    MyotisStatus.Running,
                    listOf(synced.copy(chainId = 100L, beaconState = MyotisChainStatus.STALE_ANCHOR, paused = paused)),
                ),
                myotisRunning = setOf(MyotisNetwork.Gnosis),
            ),
        )["myotis-gnosis"]!!
        val paused = gnosis(paused = true)
        assertEquals("stopped", paused.getString("state"))
        assertFalse(paused.getBoolean("running"))
        val parking = gnosis(paused = false)
        assertEquals("syncing", parking.getString("state"))
        assertTrue(parking.getBoolean("running"))
    }

    @Test
    fun `nothing of the user's leaves the phone - no address, ID, endpoint or error text`() {
        val text = NodeStatusReport.build(
            NodeSnapshot(
                swarm = NodeInfo(
                    status = NodeStatus.Error, errorMessage = "secret-error", accountAddress = "0xACCOUNT",
                    overlay = "OVERLAY", clientVersion = "ant-ffi/9",
                ),
                runNode = true,
                ipfs = IpfsInfo(status = IpfsStatus.Running, peerId = "PEERID", gatewayUrl = "http://127.0.0.1:58312"),
                radicle = RadicleInfo(status = RadicleStatus.Running, did = "did:key:DID", nid = "NID", alias = "ALIAS"),
                radicleEnabled = true,
                tor = TorInfo(status = TorStatus.Running, socksPort = 9150),
                torEnabled = true,
                myotis = MyotisInfo(MyotisStatus.Running, listOf(synced.copy(finalizedRootHex = "ROOTHEX", error = "chain-error"))),
                myotisRunning = setOf(MyotisNetwork.Mainnet),
            ),
        ).toString()
        for (secret in listOf("secret-error", "0xACCOUNT", "OVERLAY", "ant-ffi", "PEERID", "58312", "DID", "NID", "ALIAS", "9150", "ROOTHEX", "chain-error")) {
            assertFalse(secret, secret in text)
        }
    }

    @Test
    fun `the tool answers from the snapshot, and says so when there's none`() = runBlocking {
        val tool = NodeStatusTool(source = { { NodeSnapshot(runNode = true, swarm = NodeInfo(status = NodeStatus.Running)) } }, reading = Dispatchers.Unconfined)
        assertEquals("node_status", tool.spec.name)
        assertEquals(0, tool.spec.parameters.getJSONObject("properties").length())
        val out = tool.run(JSONObject())
        assertFalse(out.isError)
        val result = JSONObject(out.output).getJSONObject("result")
        assertEquals("running", result.getJSONArray("nodes").getJSONObject(0).getString("state"))
        val none = NodeStatusTool(source = { null }, reading = Dispatchers.Unconfined).run(JSONObject())
        assertTrue(none.isError)
        assertTrue(none.output.startsWith("[CAPABILITY_UNAVAILABLE]"))
    }

    @Test
    fun `a chat offers node_status`() {
        val names = ToolRegistry.forChat("s") { error("not needed to list the tools") }.specs.map { it.name }
        assertTrue("node_status" in names)
        // And Swarm publishing (#597), under desktop's names.
        assertTrue("swarm_publish" in names && "swarm_publication_status" in names)
    }
}
