package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Nodes & networks overview's rows (#416) and the menu row's note. */
class NodesOverviewTest {
    private fun rows(input: NodeOverviewInput = NodeOverviewInput()) = nodeOverviewRows(input)
    private fun row(input: NodeOverviewInput, d: NodeDestination) = rows(input).single { it.destination == d }

    @Test
    fun `every node then every remote service, in order, each leading to its own page`() {
        val all = rows()
        assertEquals(
            listOf(
                NodeDestination.Swarm,
                NodeDestination.Ipfs,
                NodeDestination.Radicle,
                NodeDestination.Tor,
                NodeDestination.LightClient,
                NodeDestination.Rpc,
                NodeDestination.Gateways,
            ),
            all.map { it.destination },
        )
        assertEquals(
            listOf("Swarm", "IPFS", "Radicle", "Tor", "Ethereum light client", "Ethereum RPC providers", "Gateways"),
            all.map { it.title },
        )
        assertEquals(List(5) { NodeOverviewGroup.OnDevice } + List(2) { NodeOverviewGroup.Remote }, all.map { it.group })
    }

    @Test
    fun `a running Swarm node says its peers and mode`() {
        val running = NodeOverviewInput(nodeInfo = NodeInfo(status = NodeStatus.Running, connectedPeers = 106))
        assertEquals("Running · 106 peers · Ultra-light", row(running, NodeDestination.Swarm).status)
        assertEquals(NodeHealth.Ok, row(running, NodeDestination.Swarm).health)
        val light = running.copy(nodeInfo = NodeInfo(status = NodeStatus.Running, connectedPeers = 1, lightMode = true))
        assertEquals("Running · 1 peer · Light", row(light, NodeDestination.Swarm).status)
        val many = running.copy(nodeInfo = NodeInfo(status = NodeStatus.Running, connectedPeers = 1234))
        assertEquals("Running · 1,234 peers · Ultra-light", row(many, NodeDestination.Swarm).status)
    }

    @Test
    fun `a Swarm node that isn't running says only its state, in the page's colour`() {
        fun swarm(status: NodeStatus) = row(NodeOverviewInput(nodeInfo = NodeInfo(status = status)), NodeDestination.Swarm)
        assertEquals("Starting…" to NodeHealth.Busy, swarm(NodeStatus.Starting).let { it.status to it.health })
        assertEquals("Stopped" to NodeHealth.Off, swarm(NodeStatus.Stopped).let { it.status to it.health })
        assertEquals("Error" to NodeHealth.Error, swarm(NodeStatus.Error).let { it.status to it.health })
    }

    @Test
    fun `your own Swarm node and IPFS gateway are named, whatever the embedded ones do`() {
        val input = NodeOverviewInput(
            nodeInfo = NodeInfo(status = NodeStatus.Error),
            externalSwarm = "http://192.168.1.10:1633",
            ipfsInfo = IpfsInfo(status = IpfsStatus.Stopped),
            externalIpfs = "http://192.168.1.10:8080",
        )
        val swarm = row(input, NodeDestination.Swarm)
        assertEquals("Using your node at http://192.168.1.10:1633", swarm.status)
        assertEquals(NodeHealth.Ok, swarm.health)
        assertEquals("Using your gateway at http://192.168.1.10:8080", row(input, NodeDestination.Ipfs).status)
        assertEquals("Swarm: your own · IPFS: your own", row(input, NodeDestination.Gateways).status)
        assertEquals("Swarm: this device · IPFS: this device", row(NodeOverviewInput(), NodeDestination.Gateways).status)
    }

    @Test
    fun `IPFS switched off reads Off, not Disconnected, and running gives no peer count`() {
        fun ipfs(status: IpfsStatus) =
            row(NodeOverviewInput(ipfsInfo = IpfsInfo(status = status, connectedPeers = 42)), NodeDestination.Ipfs)
        assertEquals("Off" to NodeHealth.Off, ipfs(IpfsStatus.Stopped).let { it.status to it.health })
        // connectedPeers is blocks fetched for freedom-ipfs, not peers.
        assertEquals("Running" to NodeHealth.Ok, ipfs(IpfsStatus.Running).let { it.status to it.health })
        assertEquals("Connecting…" to NodeHealth.Busy, ipfs(IpfsStatus.Starting).let { it.status to it.health })
        assertEquals("Error" to NodeHealth.Error, ipfs(IpfsStatus.Error).let { it.status to it.health })
        // The IPFS page's own line says the same.
        assertEquals("Off", ipfsStatusTriple(IpfsInfo()).label)
    }

    @Test
    fun `Radicle reads as its Settings row does`() {
        fun radicle(info: RadicleInfo, enabled: Boolean) =
            row(NodeOverviewInput(radicleInfo = info, radicleEnabled = enabled), NodeDestination.Radicle)
        assertEquals("Off" to NodeHealth.Off, radicle(RadicleInfo(), false).let { it.status to it.health })
        val running = radicle(RadicleInfo(status = RadicleStatus.Running, connectedPeers = 3), true)
        assertEquals("Running · 3 peers" to NodeHealth.Ok, running.status to running.health)
        assertEquals(NodeHealth.Busy, radicle(RadicleInfo(status = RadicleStatus.Starting), true).health)
        assertEquals(NodeHealth.Error, radicle(RadicleInfo(status = RadicleStatus.Error), true).health)
    }

    @Test
    fun `Tor is off until switched on, then connecting with its progress, then connected`() {
        fun tor(c: TorControls) = row(NodeOverviewInput(tor = c), NodeDestination.Tor).let { it.status to it.health }
        // Off in Settings: whatever the client last reported.
        assertEquals("Off" to NodeHealth.Off, tor(TorControls(info = TorInfo(status = TorStatus.Running))))
        // On in Settings, not started on the Tor page.
        assertEquals("Off" to NodeHealth.Off, tor(TorControls(enabled = true)))
        val on = TorControls(enabled = true, running = true)
        assertEquals(
            "Connecting… 45%" to NodeHealth.Busy,
            tor(on.copy(info = TorInfo(status = TorStatus.Starting, progress = 45))),
        )
        assertEquals("Connected" to NodeHealth.Ok, tor(on.copy(info = TorInfo(status = TorStatus.Running))))
        assertEquals("Error" to NodeHealth.Error, tor(on.copy(info = TorInfo(status = TorStatus.Error))))
    }

    @Test
    fun `the light client says each chain's state`() {
        val off = row(NodeOverviewInput(), NodeDestination.LightClient)
        assertEquals("Ethereum: Off · Gnosis: Off" to NodeHealth.Off, off.status to off.health)

        val synced = MyotisChainStatus(
            chainId = 1L,
            beaconState = "SYNCED",
            running = true,
            snapServingPeers = 1,
            elReaderAvailable = true,
        )
        val input = NodeOverviewInput(
            myotisInfo = MyotisInfo(status = MyotisStatus.Running, chains = listOf(synced)),
            myotisRunning = setOf(MyotisNetwork.Mainnet),
        )
        val ethOnly = row(input, NodeDestination.LightClient)
        assertEquals("Ethereum: Synced · Gnosis: Off" to NodeHealth.Ok, ethOnly.status to ethOnly.health)

        // Gnosis just switched on beside it: still booting, so amber.
        val both = row(input.copy(myotisRunning = MyotisNetwork.entries.toSet()), NodeDestination.LightClient)
        assertEquals("Ethereum: Synced · Gnosis: Starting…", both.status)

        // Not known yet which chains are on: shown as none.
        assertEquals(
            "Ethereum: Off · Gnosis: Off",
            row(input.copy(myotisRunning = null), NodeDestination.LightClient).status,
        )
    }

    @Test
    fun `the RPC providers row is the configuration, Checking until it's read`() {
        assertEquals("Checking…", row(NodeOverviewInput(), NodeDestination.Rpc).status)
        val config = EnsRpcConfig(colibri = true)
        val rpc = row(NodeOverviewInput(rpcConfig = config), NodeDestination.Rpc)
        assertEquals(namesPageSummary(true, config.sources.size), rpc.status)
        assertEquals(NodeHealth.None, rpc.health)
    }

    @Test
    fun `the menu note is there only while a node has failed`() {
        assertNull(nodesMenuNote(rows()))
        assertNull(nodesMenuNote(rows(NodeOverviewInput(nodeInfo = NodeInfo(status = NodeStatus.Running, connectedPeers = 9)))))
        val swarmDown = NodeOverviewInput(nodeInfo = NodeInfo(status = NodeStatus.Error))
        assertEquals("Swarm has a problem", nodesMenuNote(rows(swarmDown)))
        val twoDown = swarmDown.copy(ipfsInfo = IpfsInfo(status = IpfsStatus.Error))
        assertEquals("2 nodes have a problem", nodesMenuNote(rows(twoDown)))
        // A failed embedded node the user's own endpoint stands in for isn't a problem.
        assertNull(nodesMenuNote(rows(swarmDown.copy(externalSwarm = "http://192.168.1.10:1633"))))
    }

    @Test
    fun `a row's dot is the colour of its page's own status icon`() {
        assertEquals(NodeHealth.Ok, nodeStatusTriple(NodeStatus.Running).health)
        assertEquals(NodeHealth.Busy, nodeStatusTriple(NodeStatus.Starting).health)
        assertEquals(NodeHealth.Off, nodeStatusTriple(NodeStatus.Stopped).health)
        assertEquals(NodeHealth.Error, nodeStatusTriple(NodeStatus.Error).health)
    }
}
