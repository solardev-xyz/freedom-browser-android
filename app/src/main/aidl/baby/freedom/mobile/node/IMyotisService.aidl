package baby.freedom.mobile.node;

import baby.freedom.swarm.MyotisInfo;
import baby.freedom.mobile.node.IMyotisCallback;

/**
 * Cross-process interface to [MyotisService], the embedded Myotis
 * Ethereum / Gnosis light client in the `:myotis` process. The engines
 * start when the service is created (the UI binds only while the user
 * has the light client switched on) and stop when it's destroyed.
 */
interface IMyotisService {
    MyotisInfo getState();
    void registerCallback(IMyotisCallback cb);
    void unregisterCallback(IMyotisCallback cb);

    /** The UI came to the foreground: warm-restart the paused engines. */
    void onAppForeground();

    /** The UI went to the background: idle-sleep the engines. */
    void onAppBackground();

    /** "Retry" on a chain whose checkpoint recovery is blocked or waiting (#195). */
    void retryRecovery(long chainId);

    /** "Repair sync data" on a chain blocked on inconsistent sync data (#195). */
    void repairSyncData(long chainId);
}
