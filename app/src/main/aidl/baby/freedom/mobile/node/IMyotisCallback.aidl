package baby.freedom.mobile.node;

import baby.freedom.swarm.MyotisInfo;

/**
 * UI-side listener registered with [IMyotisService]: called on every
 * light-client state change so the UI never has to poll.
 */
oneway interface IMyotisCallback {
    void onMyotisStateChanged(in MyotisInfo info);
}
