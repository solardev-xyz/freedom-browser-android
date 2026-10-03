package baby.freedom.mobile.node;

import baby.freedom.swarm.NodeInfo;
import baby.freedom.swarm.IpfsInfo;
import baby.freedom.swarm.RadicleInfo;
import baby.freedom.mobile.node.INodeCallback;
import android.os.ParcelFileDescriptor;

/**
 * Cross-process interface to the node service running in the `:node`
 * process. The UI process consumes Swarm + IPFS state updates through
 * [registerCallback]; see the companion [INodeCallback] interface.
 */
interface INodeService {
    NodeInfo getState();
    IpfsInfo getIpfsState();
    /**
     * The IPFS node's retrieval-progress snapshot (freedom-ipfs
     * `progress_snapshot_json`: `{"active":[…],"events":[…]}`), or
     * null while the node isn't running. Polled by the UI a few times
     * a second while an `ipfs://` / `ipns://` page loads, to show which
     * phase the fetch is in (resolving, finding providers, fetching…).
     */
    @nullable String getIpfsProgress();

    /**
     * The IPFS node's cumulative retrieval / routing counters
     * (freedom-ipfs `FreedomIpfsDiagnostics` as `long[11]`: block
     * count, total bytes, cache hits, HTTP-provider blocks, Bitswap
     * blocks, delegated lookups / results / errors, DHT lookups /
     * results / errors), or null while the node isn't running. The UI
     * reads phases off their deltas when the snapshot above is empty.
     */
    @nullable long[] getIpfsCounters();

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

    /**
     * The UI came to the foreground. Re-warms the Swarm peer
     * connections and resumes IPFS discovery — after Android froze
     * the process or the network flipped, the nodes otherwise sit on
     * dead sockets while still reporting Running.
     */
    void onAppForeground();

    /** The UI went to the background: quiesce background work. */
    void onAppBackground();

    /**
     * Something on the UI side saw a dweb fetch fail against a node
     * that reports Running: prompt both nodes to drop stale
     * connections and redial, without a restart.
     */
    void recoverNetwork();

    /**
     * The node identities on disk may have changed (#77: a wallet was
     * created, imported or removed — see NodeIdentitySync): restart the
     * Swarm node, and the Radicle node (#328), if what it runs as isn't
     * what it would boot as now.
     * Also called on every bind, to catch a change made while unbound.
     */
    void reloadIdentity();

    /**
     * The Swarm node's mode (#114): light (publishing, on Gnosis) or
     * ultra-light (browsing only, no chain; the RPCs ignored). The setting
     * lives in the UI process's DataStore, so the UI relays it on every
     * bind and whenever it or the Gnosis RPCs change; the service restarts
     * the Swarm node if it runs in another mode. A light node reads Gnosis
     * through the chain-data router (#273), over `gnosisUserRpcs` (the
     * user's own, Chain.userRpcUrls) and `gnosisRpcs` (Chain.rpcUrls);
     * `gnosisRpc`, the first of them, is the one it sends confirmed
     * transactions on. All three are null while the UI hasn't read its
     * chain list yet (#300 R3-M1): the service then keeps the Gnosis it
     * has and a light node's `gnosisRpc` is the first of those. Any of
     * these can carry an API key: never log them.
     */
    void setSwarmMode(boolean light, String gnosisRpc, in List<String> gnosisUserRpcs, in List<String> gnosisRpcs);

    RadicleInfo getRadicleState();

    /**
     * Start the embedded Radicle node (#73) if it isn't already up, then
     * dial its seed book. Called when the user turns Radicle on; the
     * service also starts it by itself on boot while the persisted
     * setting is on. No-op while Starting / Running.
     */
    void startRadicle();

    /** Shut the Radicle node down. Leaves Swarm and IPFS untouched. */
    void stopRadicle();

    /**
     * Seed and fetch the repository `rid` names (`rad:z…`, `rad://z…` or
     * a bare `z…`). Progress arrives through [RadicleInfo.seed] on the
     * callback. Ignored while another seed is in flight.
     */
    void seedRadicleRepo(String rid);

    /**
     * Stop seeding the repository `rid` (a `rad:z…` from the seeded
     * list); it drops out of [RadicleInfo.seededRepos].
     */
    void unseedRadicleRepo(String rid);

    /**
     * One of the browser's Radicle reads or writes (#124): `method` is a
     * name in RadicleNode.BROWSER_CALLS, `argsJson` its arguments as a
     * JSON object. Returns the read end of a pipe the answer (the
     * library's JSON, or `{"error": …, "reason": …}`) is written to and
     * then closed — a pipe, not a String, because a repository's issue
     * list or a blob can outgrow a binder transaction. Returns at once;
     * the call runs on the service's own threads, and closing the read
     * end early abandons it.
     */
    ParcelFileDescriptor radicleCall(String method, String argsJson);

    /**
     * Postage stamps (#116) and the chequebook (#117): `method` is one of
     * `status`, `quote`, `extendQuote`, `discover`
     * (no transaction) or `buy`, `extend`, `deposit` (SPEND, from the stamp
     * and chequebook screens' confirmation only), or `connect` (#115:
     * registers a batch the wallet bought for the node; a first one also
     * sets up the chequebook) — with
     * `argsJson` its arguments. Returns the read end of a pipe the answer
     * (ant's JSON, or `{"error": …}`) is written to, like [radicleCall].
     * A spend that has started runs to the end even if the read end is
     * closed; one the reader gave up on before it started doesn't run.
     */
    ParcelFileDescriptor stampCall(String method, String argsJson);

    /**
     * The user turned the node off (#116). Returns true if a postage spend
     * is running inside ant: the service then stays up and stops itself
     * once it ends — the caller just unbinds, and must not stopService,
     * which would exit the process mid-spend. Returns false if none runs:
     * stop the service now. Either way no new spend starts until the
     * service is started again.
     */
    boolean stopWhenIdle();

    /**
     * The recent log lines (#276) of one of this process's nodes —
     * `source` is a NodeLogSource ordinal: Swarm, IPFS or Radicle — oldest
     * first, newline-joined, from a bounded in-memory ring with page
     * addresses already taken out (NodeLogs). "" for an unknown source.
     */
    String getLogs(int source);

    /** Forget every node's kept log lines (#276): Clear cookies & site data. */
    oneway void clearLogs();

    /**
     * The *Colibri proofs* switch (Settings → Name resolution, #329),
     * which also decides whether the chain-data router's Gnosis reads for
     * the Swarm node go to the Colibri prover. The setting lives in the UI
     * process's DataStore, so the UI relays it on every bind and change
     * (ColibriReads); oneway, so they arrive in the order sent.
     */
    oneway void setColibriReads(boolean on);
}
