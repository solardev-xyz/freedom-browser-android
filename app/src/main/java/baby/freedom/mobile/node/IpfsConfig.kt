package baby.freedom.mobile.node

/**
 * The IPFS node's start-time settings (#475): `ipfs_low_power` and
 * `ipfs_routing_mode`, which freedom-ipfs takes only at init.
 *
 * They live in the UI process's DataStore. `:node` opens the same file
 * with its own single-process DataStore, which reads it once and then
 * serves its in-memory copy, so a routing mode changed after that read
 * never reached it until `:node` restarted. The UI relays them instead
 * ([INodeService.setIpfsConfig]) on every bind and every change, the way
 * it relays the Swarm settings.
 */
internal data class IpfsConfig(val lowPower: Boolean, val routingMode: String) {
    companion object {
        /**
         * A relayed config, or null without a routing mode (AIDL strings
         * are nullable): the start then reads `:node`'s own copy. An
         * unknown mode is passed on; IpfsNode maps it to `auto`.
         */
        fun relayed(lowPower: Boolean, routingMode: String?): IpfsConfig? =
            routingMode?.let { IpfsConfig(lowPower, it) }

        /**
         * What a start uses: the UI's last relay, or — before this
         * process has heard one (a start racing the first bind) — [stored],
         * `:node`'s own read of the settings file.
         */
        suspend fun forStart(relayed: IpfsConfig?, stored: suspend () -> IpfsConfig): IpfsConfig =
            relayed ?: stored()
    }
}
