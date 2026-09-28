package baby.freedom.swarm

/**
 * Raw JNI surface over the embedded Arti (Tor) client (`freedom_tor_*`
 * inside `libfreedom_mobile_ffi.so`, freedom-mobile-ffi feature `tor`),
 * bridged by `src/main/cpp/tor_jni.c`. See [TorNode] for the wrapper the
 * app uses. One client per process; every call may block briefly.
 */
internal object TorNative {
    init {
        // Pulls in libfreedom_mobile_ffi.so transitively via DT_NEEDED.
        System.loadLibrary("freedom_jni")
    }

    /**
     * Start the client with a SOCKS5 listener on a free loopback port.
     * `null` on success (the port is in [statusJson]), else the failure
     * message as UTF-8.
     */
    external fun start(stateDir: String, cacheDir: String): ByteArray?

    /** Close the listener and every stream, drop the client. */
    external fun stop()

    /** `freedom_tor_status_json` as UTF-8 (see freedom_tor.h). */
    external fun statusJson(): ByteArray?

    /** The linked Arti version, e.g. `0.46.0`. */
    external fun version(): String
}
