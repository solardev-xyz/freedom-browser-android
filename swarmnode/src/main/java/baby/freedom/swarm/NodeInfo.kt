package baby.freedom.swarm

data class NodeInfo(
    val status: NodeStatus = NodeStatus.Stopped,
    val walletAddress: String = "",
    val connectedPeers: Long = 0L,
    val errorMessage: String? = null,
)
