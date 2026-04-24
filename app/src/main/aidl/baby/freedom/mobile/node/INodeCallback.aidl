package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;
import baby.freedom.swarm.IpfsInfo;

/**
 * UI-side listener registered with [INodeService]. Called on every
 * Swarm or IPFS state update so the UI process never has to poll.
 */
oneway interface INodeCallback {
    void onStateChanged(in NodeInfo info);
    void onIpfsStateChanged(in IpfsInfo info);
}
