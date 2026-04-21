package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;
import baby.freedom.mobile.node.INodeCallback;

/**
 * Cross-process interface to the Swarm node service running in the
 * `:node` process. The UI process consumes NodeInfo updates through
 * [registerCallback]; see the companion [INodeCallback] interface.
 */
interface INodeService {
    NodeInfo getState();
    void registerCallback(INodeCallback cb);
    void unregisterCallback(INodeCallback cb);
}
