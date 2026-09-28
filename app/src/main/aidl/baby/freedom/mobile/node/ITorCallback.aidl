package baby.freedom.mobile.node;

import baby.freedom.swarm.TorInfo;

/**
 * UI-side listener registered with [ITorService]: called on every Tor
 * state change, so the UI (and the `.onion` routing it owns) never polls.
 */
oneway interface ITorCallback {
    void onTorStateChanged(in TorInfo info);
}
