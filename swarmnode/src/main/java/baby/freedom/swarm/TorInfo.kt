package baby.freedom.swarm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class TorStatus {
    /** Not running: `.onion` requests fail. */
    Stopped,

    /** Running and listening, still bootstrapping into the Tor network. */
    Starting,

    /** Bootstrapped: ready to reach onion services. */
    Running,

    /** Failed to start (or stopped by a fault); `.onion` requests fail. */
    Error,
}

/**
 * Snapshot of the embedded Arti client, marshalled across the UI ↔ `:tor`
 * AIDL boundary like [RadicleInfo].
 */
@Parcelize
data class TorInfo(
    val status: TorStatus = TorStatus.Stopped,
    /**
     * The loopback SOCKS5 port while the listener is up (Starting and
     * Running), else 0. The only thing `.onion` traffic may be routed to.
     */
    val socksPort: Int = 0,
    /** Bootstrap progress, 0–100. */
    val progress: Int = 0,
    /** Arti's own one-line bootstrap summary, or `""`. */
    val summary: String = "",
    /** The linked Arti version (e.g. `0.46.0`), or `""` before the library loaded. */
    val version: String = "",
    val errorMessage: String? = null,
) : Parcelable
