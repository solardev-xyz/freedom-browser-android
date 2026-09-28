package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;
import baby.freedom.swarm.IpfsInfo;
import baby.freedom.swarm.RadicleInfo;

/**
 * UI-side listener registered with [INodeService]. Called on every
 * Swarm, IPFS or Radicle state update so the UI process never has to poll.
 */
oneway interface INodeCallback {
    void onStateChanged(in NodeInfo info);
    void onIpfsStateChanged(in IpfsInfo info);
    void onRadicleStateChanged(in RadicleInfo info);
}
