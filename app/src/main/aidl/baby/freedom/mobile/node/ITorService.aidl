package baby.freedom.mobile.node;

import baby.freedom.swarm.TorInfo;
import baby.freedom.mobile.node.ITorCallback;

/**
 * Cross-process interface to [TorService], the embedded Arti (Tor) client
 * in the `:tor` process (#143). The client starts when the service is
 * created (the UI binds only while the user has Tor running) and stops
 * when it's destroyed.
 */
interface ITorService {
    TorInfo getState();
    void registerCallback(ITorCallback cb);
    void unregisterCallback(ITorCallback cb);

    /** The Tor client's recent log lines (#276), like INodeService.getLogs. */
    String getLogs();

    /** Forget the kept log lines, like INodeService.clearLogs. */
    oneway void clearLogs();
}
