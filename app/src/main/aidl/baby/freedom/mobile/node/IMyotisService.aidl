package baby.freedom.mobile.node;

import baby.freedom.swarm.MyotisInfo;
import baby.freedom.mobile.node.IMyotisCallback;
import baby.freedom.mobile.node.IMyotisCallResult;

/**
 * Cross-process interface to [MyotisService], the embedded Myotis
 * Ethereum / Gnosis light client in the `:myotis` process. The UI binds
 * while at least one chain is switched on and says which ones
 * ([setNetworks]); the engines start with the first such call and stop
 * when the service is destroyed.
 */
interface IMyotisService {
    MyotisInfo getState();
    void registerCallback(IMyotisCallback cb);
    void unregisterCallback(IMyotisCallback cb);

    /**
     * Run only the chains in `chainIds` (#274): the first call starts them,
     * later ones start and stop single chains, the others untouched.
     */
    void setNetworks(in long[] chainIds);

    /** The UI came to the foreground: warm-restart the paused engines. */
    void onAppForeground();

    /** The UI went to the background: idle-sleep the engines. */
    void onAppBackground();

    /** "Retry" on a chain whose checkpoint recovery is blocked or waiting (#195). */
    void retryRecovery(long chainId);

    /** "Repair sync data" on a chain blocked on inconsistent sync data (#195). */
    void repairSyncData(long chainId);

    /**
     * A verified `eth_call` of `data` (0x-hex) on `to` at chain `chainId`'s
     * verified head, run by the light client against proven state (name
     * resolution, #101). Returns at once; the answer arrives on `result`
     * — `{"status":"unavailable",…}` straight away while the chain isn't
     * ready, `{"status":"unavailable","reason":"busy","busy":true}` while
     * every slot is taken. The caller bounds its own wait: the engine may
     * take up to ~90 s, and a dying process never answers at all. `result`
     * is answered even after the caller stopped waiting — that is how it
     * learns the engine let go of an abandoned call. `probe`: the
     * resolver's health probe, which has a slot of its own that lookups
     * can't take.
     */
    oneway void ethCall(long chainId, String to, String data, boolean probe, IMyotisCallResult result);

    /** The light client's recent log lines (#276), like INodeService.getLogs. */
    String getLogs();

    /** Forget the kept log lines, like INodeService.clearLogs. */
    oneway void clearLogs();
}
