package baby.freedom.mobile.data

/**
 * The sizes the Swarm node's disk chunk cache can be set to (Settings →
 * Nodes & networks → Swarm cache size, and the Nodes page): ant's own
 * default, 512 MiB, plus two below it and three above, all inside the
 * 64 MiB–16 GiB ant clamps to. Stored by [bytes] in [NodeSettings]; ant
 * doesn't persist the cap, so the `:node` process hands it to every init
 * and applies a change live.
 */
enum class SwarmCacheSize(val bytes: Long) {
    MB_256(256L * MIB),
    MB_512(512L * MIB),
    GB_1(1024L * MIB),
    GB_2(2048L * MIB),
    GB_4(4096L * MIB),
    ;

    companion object {
        /** ant's default, and the setting's until the user picks another. */
        val DEFAULT = MB_512

        /**
         * The size [bytes] stores; [DEFAULT] for nothing stored, or for a
         * value that is none of the sizes (a file from a later build with
         * more of them, or a torn write), rather than handing ant an
         * arbitrary cap.
         */
        fun fromBytes(bytes: Long?): SwarmCacheSize = entries.firstOrNull { it.bytes == bytes } ?: DEFAULT
    }
}

private const val MIB = 1024L * 1024L
