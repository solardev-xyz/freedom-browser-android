package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;
import baby.freedom.swarm.IpfsInfo;
import baby.freedom.mobile.node.INodeCallback;

/**
 * Cross-process interface to the node service running in the `:node`
 * process. The UI process consumes Swarm + IPFS state updates through
 * [registerCallback]; see the companion [INodeCallback] interface.
 */
interface INodeService {
    NodeInfo getState();
    IpfsInfo getIpfsState();
    void registerCallback(INodeCallback cb);
    void unregisterCallback(INodeCallback cb);

    /**
     * Idempotently spin up the IPFS node. Called from the UI when
     * the user flips the IPFS toggle on, or the first time a
     * navigation actually needs `ipfs://` / `ipns://` — so cold-start
     * doesn't pay the Kubo bootstrap cost for users who never visit
     * IPFS content. No-op if the node is already Starting / Running.
     */
    void ensureIpfsStarted();

    /**
     * Shut down the IPFS node if it's running. Called from the UI
     * when the user flips the IPFS toggle off. Leaves the Swarm
     * node untouched. No-op if the node is already stopped.
     */
    void stopIpfs();
}
