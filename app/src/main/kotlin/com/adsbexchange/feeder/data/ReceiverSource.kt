package com.adsbexchange.feeder.data

/**
 * Settings > Receiver: where readsb gets its messages. An RTL-SDR stick on this PC, or another
 * receiver on the network (a Raspberry Pi running dump1090-fa or readsb, say) whose Beast output
 * readsb reads, so someone with one antenna and stick can feed from it without a second set.
 */
enum class ReceiverSource(val key: String, val label: String) {
    USB("usb", "USB stick on this PC"),
    NETWORK("network", "Another receiver on the network");

    companion object {
        /** From settings.json; anything else, or nothing, is [USB]. */
        fun ofKey(key: String?): ReceiverSource = entries.firstOrNull { it.key == key } ?: USB

        /** The usual Beast output port of dump1090-fa, readsb and the feeder images built on them. */
        const val DEFAULT_PORT = 30005

        /**
         * A host name or IPv4 address, as typed. Only letters, digits, '.' and '-': it goes into
         * readsb's comma-separated --net-connector, so a comma or space would add fields of its own.
         */
        private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")

        fun validHost(host: String): Boolean = HOST.matches(host)

        fun validPort(port: Int): Boolean = port in 1..65535

        /** "host,port" ([FeederSettings.networkSource]) as people write it: "host:port". */
        fun shown(source: String): String = source.replace(',', ':')
    }
}
