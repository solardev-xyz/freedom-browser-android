package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;

/**
 * UI-side listener registered with [INodeService]. Called on every
 * NodeInfo update so the UI process never has to poll.
 */
oneway interface INodeCallback {
    void onStateChanged(in NodeInfo info);
}
