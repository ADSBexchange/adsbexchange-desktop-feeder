package com.adsbexchange.feeder.mlat

/*
 * Beast-format input for the MLAT client: a Kotlin port of mlat-client's modes_reader.c
 * (feed_beast, timestamp_check/timestamp_update, filter_message) for the 12 MHz "dump1090"
 * input type that readsb's --net-bo-port provides.
 */

sealed interface ReaderEvent {
    data class Message(val message: ModeSMessage) : ReaderEvent
    /** Timestamps stopped tracking wall-clock time (sent to the server as a clock jump). */
    data object TimestampJump : ReaderEvent
}

class BeastReader(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val frequency = 12_000_000L

    /** ICAO addresses heard in CRC-checked DF11/17/18 since the last [takeSeen], even if filtered. */
    private val seen = HashSet<Int>()
    /** DFs always passed (all aircraft). mlat-client passes every DF17. */
    private val defaultFilter = BooleanArray(32).also { it[17] = true }
    /** Addresses whose DF0/4/5/11/16/20/21 messages the server wants for MLAT. */
    val interestedMlat = HashSet<Int>()
    private val specificDfs = setOf(0, 4, 5, 11, 16, 20, 21)

    private var lastTimestamp = 0L
    private var lastTsMono = 0L
    private var monotonic = 0L
    private var outliers = 0

    var receivedMessages = 0L; private set
    var suppressedMessages = 0L; private set

    fun takeSeen(): Set<Int> = HashSet(seen).also { seen.clear() }

    fun reset() {
        seen.clear()
        lastTimestamp = 0
        lastTsMono = 0
        outliers = 0
    }

    /**
     * Parses as much of [buf] (bytes [0, len)) as forms complete frames.
     * @return bytes consumed; the caller keeps the rest for the next call.
     * @throws IllegalStateException if the stream is not Beast (lost sync).
     */
    fun feed(buf: ByteArray, len: Int, out: MutableList<ReaderEvent>): Int {
        var p = 0
        val data = ByteArray(14)
        while (p + 2 <= len) {
            if (buf[p] != 0x1a.toByte()) {
                if (out.isNotEmpty()) return p
                throw IllegalStateException("Lost sync with input stream: expected 0x1A at $p")
            }
            val type = buf[p + 1].toInt().toChar()
            val messageLen = when (type) {
                '1' -> 2; '2' -> 7; '3' -> 14; '4' -> 14
                '5' -> 21
                else -> {
                    if (out.isNotEmpty()) return p
                    throw IllegalStateException("Lost sync with input stream: unexpected type 0x%02x".format(type.code))
                }
            }
            val hasTs = type != '5'
            var m = p + 2
            var eom = m + messageLen + if (hasTs) 7 else 0
            if (eom > len) break

            // Read one byte, un-escaping 0x1A 0x1A. Returns -1 when the frame isn't complete yet.
            var truncated = false
            fun next(): Int {
                val v = buf[m++].toInt() and 0xff
                if (v == 0x1a) {
                    if (m < len && buf[m] != 0x1a.toByte()) throw IllegalStateException("Lost sync: expected 0x1A after 0x1A escape")
                    m++
                    eom++
                    if (eom > len) truncated = true
                }
                return v
            }

            var timestamp = 0L
            var signal = 0
            if (hasTs) {
                for (i in 0 until 6) {
                    timestamp = (timestamp shl 8) or next().toLong()
                    if (truncated) return p
                }
                signal = next()
                if (truncated) return p
            }
            for (i in 0 until messageLen) {
                val v = next()
                if (truncated) return p
                if (i < 14) data[i] = v.toByte()
            }
            p = m

            if (type == '1' || type == '4' || type == '5') continue // Mode A/C, Radarcape status/position

            if (hasTs && !isSynthetic(timestamp)) {
                if (!timestampCheck(timestamp) && outliers > OUTLIER_LIMIT) out += ReaderEvent.TimestampJump
                // Beast timestamps are at the end of the preamble+56 bits; move to frame start.
                val adjust = 768L
                timestamp = if (timestamp < adjust) 0 else timestamp - adjust
                timestampUpdate(timestamp)
            }

            val message = ModeSMessage(timestamp, signal, data.copyOf(messageLen))
            receivedMessages++
            if (wanted(message)) out += ReaderEvent.Message(message) else suppressedMessages++
        }
        return p
    }

    private fun isSynthetic(ts: Long) = ts == 0L || (ts >= MAGIC_MLAT_TIMESTAMP && ts <= MAGIC_MLAT_TIMESTAMP + 10)

    /** True if [timestamp] advanced roughly in step with wall-clock time since the last one. */
    private fun timestampCheck(timestamp: Long): Boolean {
        monotonic = clock()
        if (lastTimestamp == 0L) return true
        val tsElapsed = timestamp - lastTimestamp
        val sysElapsed = (monotonic - lastTsMono) * (frequency / 1000)
        val maxOffset = (1.25 * frequency).toLong()
        if (tsElapsed > sysElapsed + maxOffset || tsElapsed < sysElapsed - maxOffset) {
            outliers++
            return false
        }
        outliers = 0
        return true
    }

    private fun timestampUpdate(timestamp: Long) {
        if (isSynthetic(timestamp)) return
        if (lastTimestamp == 0L) {
            lastTsMono = monotonic
            lastTimestamp = timestamp
            return
        }
        if (lastTimestamp > timestamp && lastTimestamp - timestamp < 90 * frequency) return // small moves back
        if (outliers in 1..OUTLIER_LIMIT) return
        lastTimestamp = timestamp
        lastTsMono = monotonic
    }

    private fun wanted(message: ModeSMessage): Boolean {
        if (message.timestamp == MAGIC_MLAT_TIMESTAMP) return false
        if (outliers > 0) return false // drop while timestamps are jumping
        if (lastTimestamp > message.timestamp) return false // backwards
        if (message.df == DF_MODEAC) return false // Mode A/C MLAT not used
        if (!message.valid) return false
        if (message.df == 11 || message.df == 17 || message.df == 18) seen += message.address
        if (message.timestamp == 0L) return false
        if (defaultFilter.getOrElse(message.df) { false }) return true
        return message.df in specificDfs && message.address in interestedMlat
    }

    companion object {
        private const val MAGIC_MLAT_TIMESTAMP = 0xFF004D4C4154L
        private const val OUTLIER_LIMIT = 1

        /** Settings command readsb/dump1090 accept on a Beast connection: binary, CRC on, no Mode A/C. */
        val SETTINGS_MESSAGE = byteArrayOf(0x1a, '1'.code.toByte(), 'C'.code.toByte(),
            0x1a, '1'.code.toByte(), 'd'.code.toByte(),
            0x1a, '1'.code.toByte(), 'f'.code.toByte(),
            0x1a, '1'.code.toByte(), 'j'.code.toByte())
    }
}
