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
}
