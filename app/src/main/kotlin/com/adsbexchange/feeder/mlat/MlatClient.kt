package com.adsbexchange.feeder.mlat

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.thread
import kotlin.random.Random

/** Server connection phase. */
private enum class Phase { NONE, HANDSHAKING, READY }

/**
 * ADS-B Exchange MLAT client: a Kotlin port of mlat-client 0.4.2's TCP ("--no-udp", as ADSBx
 * feeder kits run it) JSON protocol, receiver input and coordinator.
 *
 * It reads 12 MHz-timestamped Beast frames from readsb's loopback Beast output and talks to the
 * MLAT server with the feeder's UUID and name. One thread runs a select() loop, like upstream's
 * asyncore loop.
 *
 * @param isOwnListener checks that the Beast input is readsb in this process, given the listener's
 *   port and the client's local port. Any app can listen on loopback; this keeps another app's
 *   stream from being forwarded to ADS-B Exchange under this feeder's identity. Null skips the
 *   check (tests).
 */
class MlatClient(
    private val config: Config,
    private val isOwnListener: ((listenerPort: Int, clientPort: Int) -> Boolean)? = null,
    private val log: (String) -> Unit,
) {

    data class Config(
        val uuid: String,
        /** Shown on ADSBx's MLAT sync pages and map; [privacy] hides only the (rounded) location. */
        val user: String,
        val lat: Double,
        val lon: Double,
        /** Antenna height in metres. */
        val altM: Double,
        val privacy: Boolean,
        /** Null = ADSBx with its feed1/feed2 and 31090/64590 failover; otherwise a fixed server (tests). */
        val server: Pair<String, Int>? = null,
        val inputHost: String = "127.0.0.1",
        /** readsb's Beast output; the feeder service picks it afresh for each run. */
        val inputPort: Int,
    )

    enum class State { STARTING, CONNECTING, HANDSHAKING, CONNECTED, DISCONNECTED, DENIED, STOPPED }

    /** Snapshot for the UI; see [stats]. */
    data class Stats(
        val state: State = State.STARTING,
        val server: String? = null,
        val connectedSinceMs: Long? = null,
        val receiverConnected: Boolean = false,
        /** From the server's periodic stats: feeders we're synchronised with, and sync quality. */
        val peerCount: Int? = null,
        val outlierPercent: Double? = null,
        val badSyncTimeout: Int? = null,
        /** Aircraft the server has asked this feeder to send data for. */
        val requested: Int = 0,
        val adsbAircraft: Int = 0,
        val modesAircraft: Int = 0,
        val syncsSent: Long = 0,
        val mlatSent: Long = 0,
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        /** Beast data read from readsb on loopback. readsb counts it in its own bytes_out. */
        val inputBytes: Long = 0,
        val message: String? = null,
    )

    @Volatile var stats = Stats(); private set
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = thread(name = "mlat-client", isDaemon = true) {
            try {
                loop()
            } catch (e: Throwable) {
                log("mlat: stopped after error: $e")
            } finally {
                stats = stats.copy(state = State.STOPPED, connectedSinceMs = null, receiverConnected = false)
            }
        }
    }

    /** Asks the client to disconnect and end its thread; returns immediately. */
    fun stop() {
        running = false
        selector?.wakeup()
    }

    // --- event loop ------------------------------------------------------------------------

    private var selector: Selector? = null
    private val reader = BeastReader()
    private val server = ServerConnection()
    private val input = InputConnection()
    private val coordinator = MlatCoordinator(server, reader, reader.frequency)

    private fun now() = System.nanoTime() / 1e9

    private fun loop() {
        val sel = Selector.open()
        selector = sel
        log("mlat: client starting (name \"${config.user}\", privacy ${if (config.privacy) "on" else "off"})")
        server.reconnect()
        var nextHeartbeat = now() + 0.5
        var nextServerSend = now()
        try {
            while (running) {
                // Data wakes it; otherwise 250 ms, the flush cadence, is fine-grained enough.
                sel.select(250)
                val keys = sel.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    val conn = key.attachment() as Connection
                    try {
                        if (!key.isValid) continue
                        if (key.isConnectable) conn.onConnectable()
                        if (key.isValid && key.isReadable) conn.onReadable()
                        if (key.isValid && key.isWritable) conn.onWritable()
                    } catch (e: Exception) {
                        conn.fail(e)
                    }
                }
                val t = now()
                if (t >= nextServerSend) {
                    nextServerSend = t + 0.25
                    server.flushLines()
                }
                if (t >= nextHeartbeat) {
                    nextHeartbeat = t + 0.5
                    server.heartbeat(t)
                    input.heartbeat(t)
                    if (server.ready) coordinator.heartbeat(t)
                    publishStats()
                }
            }
        } finally {
            input.close("client stopping", reconnect = false)
            server.close("client stopping", reconnect = false)
            sel.close()
        }
    }

    private fun publishStats() {
        val (adsb, modes) = coordinator.trackedCounts()
        stats = stats.copy(
            requested = coordinator.requestedCount,
            adsbAircraft = adsb, modesAircraft = modes,
            syncsSent = coordinator.syncsSent, mlatSent = coordinator.mlatSent,
            bytesSent = server.txBytes, bytesReceived = server.rxBytes, inputBytes = input.rxBytes,
            receiverConnected = input.connected,
        )
    }

    /** A non-blocking TCP connection with upstream's reconnect-with-jitter behaviour. */
    private abstract inner class Connection(val label: String) {
        var channel: SocketChannel? = null
        var key: SelectionKey? = null
        val out = ByteArrayOutputStream()
        var reconnectAt: Double? = null
        var lastTry = 0.0
        var reconnectInterval = 10.0
        /** Reused for every read rather than allocated each time. */
        private val rxBuffer = ByteBuffer.allocate(16384)
        protected var pendingAddresses = ArrayDeque<InetSocketAddress>()

        abstract fun resolve(): List<InetSocketAddress>
        abstract fun onConnected()
        abstract fun onData(buf: ByteArray, len: Int)
        abstract fun onClosed()

        fun reconnect() {
            close("reconnecting", reconnect = false)
            lastTry = now()
            try {
                if (pendingAddresses.isEmpty()) pendingAddresses = ArrayDeque(resolve())
                val addr = pendingAddresses.removeFirstOrNull() ?: throw java.io.IOException("no addresses")
                val ch = SocketChannel.open().apply { configureBlocking(false) }
                channel = ch
                val connected = ch.connect(addr)
                key = ch.register(selector, if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT, this)
                if (connected) connectedNow()
            } catch (e: Exception) {
                log("mlat: connection to $label failed: ${e.message ?: e}")
                close(null, reconnect = true)
            }
        }

        fun onConnectable() {
            val ch = channel ?: return
            if (ch.finishConnect()) connectedNow()
        }

        private fun connectedNow() {
            pendingAddresses.clear() // re-resolve next time
            key?.interestOps(SelectionKey.OP_READ)
            onConnected()
        }

        fun onReadable() {
            val ch = channel ?: return
            rxBuffer.clear()
            val n = ch.read(rxBuffer)
            if (n < 0) {
                close("connection closed by peer", reconnect = true)
                return
            }
            if (n > 0) onData(rxBuffer.array(), n) // onData copies what it keeps
        }

        fun send(bytes: ByteArray) {
            out.write(bytes)
            key?.let { if (it.isValid) it.interestOps(it.interestOps() or SelectionKey.OP_WRITE) }
        }

        open fun onWritable() {
            val ch = channel ?: return
            if (out.size() > 0) {
                val bytes = out.toByteArray()
                val written = ch.write(ByteBuffer.wrap(bytes))
                out.reset()
                if (written < bytes.size) out.write(bytes, written, bytes.size - written)
                sent(written)
            }
            if (out.size() == 0) key?.let { if (it.isValid) it.interestOps(SelectionKey.OP_READ) }
        }

        open fun sent(n: Int) {}

        fun fail(e: Exception) {
            log("mlat: connection to $label lost: ${e.message ?: e}")
            close(null, reconnect = true)
        }

        fun close(reason: String?, reconnect: Boolean) {
            val wasOpen = channel != null
            key?.cancel()
            runCatching { channel?.close() }
            channel = null
            key = null
            out.reset()
            if (wasOpen) {
                if (reason != null) log("mlat: disconnecting from $label: $reason")
                onClosed()
            }
            if (reconnect) scheduleReconnect()
        }

        fun scheduleReconnect() {
            if (reconnectAt != null) return
            val t = now()
            var interval = if (pendingAddresses.isNotEmpty()) 0.5 else lastTry + reconnectInterval - t + 2 * Random.nextDouble()
            if (interval < 4 && pendingAddresses.isEmpty()) interval = 2 + 2 * Random.nextDouble()
            reconnectAt = t + interval
        }

        open fun heartbeat(t: Double) {
            val at = reconnectAt ?: return
            if (at <= t) {
                reconnectAt = null
                reconnect()
            }
        }
    }

    // --- Beast input from readsb -----------------------------------------------------------

    private inner class InputConnection : Connection("readsb (${config.inputHost}:${config.inputPort})") {
        var connected = false
        var rxBytes = 0L; private set
        private var lastData = 0.0
        private val residual = ByteArray(65536)
        private var residualLen = 0
        private val events = ArrayList<ReaderEvent>()

        override fun resolve() = listOf(InetSocketAddress(config.inputHost, config.inputPort))

        /** Whether the listener has been checked to be this process's readsb; see [isOwnListener]. */
        private var verified = false

        override fun onConnected() {
            connected = true
            verified = false
            lastData = now()
            residualLen = 0
            reader.reset()
            log("mlat: input connected to readsb")
            coordinator.inputConnected()
            send(BeastReader.SETTINGS_MESSAGE)
        }

        override fun onData(buf: ByteArray, len: Int) {
            // Checked on the first data, not on connecting: the kernel completes the connection
            // before readsb accepts it, and only the accepted socket shows the listener is ours.
            // readsb sends nothing until it has accepted.
            if (!verified) {
                val clientPort = channel?.socket()?.localPort ?: -1
                if (isOwnListener?.invoke(config.inputPort, clientPort) == false) {
                    log("mlat: port ${config.inputPort} isn't this app's readsb; not using its data")
                    close(null, reconnect = true)
                    return
                }
                verified = true
            }
            lastData = now()
            rxBytes += len
            if (residualLen + len > residual.size) throw IllegalStateException("parser broken - buffer not being consumed")
            System.arraycopy(buf, 0, residual, residualLen, len)
            residualLen += len
            events.clear()
            val consumed = reader.feed(residual, residualLen, events)
            System.arraycopy(residual, consumed, residual, 0, residualLen - consumed)
            residualLen -= consumed
            val t = now()
            for (e in events) when (e) {
                is ReaderEvent.Message -> coordinator.received(e.message, t)
                ReaderEvent.TimestampJump -> coordinator.timestampJump()
            }
        }

        override fun onClosed() {
            if (connected) coordinator.inputDisconnected()
            connected = false
        }

        override fun heartbeat(t: Double) {
            // Input is only wanted while the server connection is up (as upstream).
            if (!server.ready) {
                reconnectAt = null
                return
            }
            super.heartbeat(t)
            if (connected && t - lastData > INPUT_INACTIVITY_S) {
                close("no data from readsb for ${INPUT_INACTIVITY_S.toInt()} s", reconnect = false)
                reconnect()
            }
        }
    }

    // --- MLAT server -----------------------------------------------------------------------

    private inner class ServerConnection : Connection("MLAT server"), MlatCoordinator.Server {
        private var hostIndex = 0
        private var portIndex = 0
        private var host = config.server?.first ?: ADSBX_HOSTS[0]
        private var port = config.server?.second ?: ADSBX_PORTS[0]

        private var phase = Phase.NONE
        val ready get() = phase == Phase.READY

        private val lines = ArrayList<String>()
        private var compress = "none"
        private var deflater: Deflater? = null
        private var inflater: Inflater? = null
        private val readBuf = ByteArrayOutputStream()
        private var lastData = 0.0
        private var heartbeatAt: Double? = null
        private var lastClockJump = 0.0
        override var splitSync = false; private set

        var txBytes = 0L; private set
        var rxBytes = 0L; private set

        override fun resolve(): List<InetSocketAddress> {
            if (config.server == null) {
                // As upstream: alternate hosts and ports every time the address list runs out.
                portIndex = (portIndex + 1) % ADSBX_PORTS.size
                hostIndex = (hostIndex + 1) % ADSBX_HOSTS.size
                host = ADSBX_HOSTS[hostIndex]
                port = ADSBX_PORTS[portIndex]
            }
            stats = stats.copy(state = State.CONNECTING, server = "$host:$port")
            return InetAddress.getAllByName(host).map { InetSocketAddress(it, port) }
        }

        override fun onConnected() {
            log("mlat: connected to multilateration server at $host:$port, handshaking")
            phase = Phase.HANDSHAKING
            lastData = now()
            readBuf.reset()
            stats = stats.copy(state = State.HANDSHAKING, message = null)
            send((handshake() + " ".repeat(128) + "\n").toByteArray(Charsets.US_ASCII))
        }

        private fun handshake(): String = handshakeJson(config, reader.frequency)

        override fun onData(buf: ByteArray, len: Int) {
            lastData = now()
            rxBytes += len
            stats = stats.copy(bytesReceived = rxBytes)
            readBuf.write(buf, 0, len)
            // The connection is plain TCP: don't let a bad server (or anything in between) grow
            // the buffer without limit. Real server messages are a few hundred bytes.
            if (readBuf.size() > MAX_SERVER_MESSAGE) {
                close("server sent more than ${MAX_SERVER_MESSAGE / 1024} KiB without a complete message", reconnect = true)
                return
            }
            if (compress == "zlib2" && phase == Phase.READY) consumeZlib() else consumeLines()
        }

        private fun consumeLines() {
            while (true) {
                val bytes = readBuf.toByteArray()
                val nl = bytes.indexOf('\n'.code.toByte())
                if (nl < 0) return
                readBuf.reset()
                readBuf.write(bytes, nl + 1, bytes.size - nl - 1)
                handleLine(String(bytes, 0, nl, Charsets.US_ASCII))
                if (channel == null) return // closed while handling (e.g. denied)
                // The handshake response can switch the rest of the stream to zlib2 framing.
                if (compress == "zlib2" && phase == Phase.READY) return consumeZlib()
            }
        }

        private fun consumeZlib() {
            val inf = inflater ?: return
            val bytes = readBuf.toByteArray()
            val (lines, consumed) = zlibUnpack(inf, bytes)
            readBuf.reset()
            readBuf.write(bytes, consumed, bytes.size - consumed)
            for (l in lines) {
                handleLine(l)
                if (channel == null) return
            }
        }

        private fun handleLine(line: String) {
            if (line.isBlank()) return
            val msg = JSONObject(line)
            if (phase == Phase.HANDSHAKING) handshakeResponse(msg) else request(msg)
        }

        private fun handshakeResponse(r: JSONObject) {
            if (r.has("reconnect_in")) reconnectInterval = r.getDouble("reconnect_in")
            if (r.has("deny")) {
                val reasons = r.optJSONArray("deny")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                log("mlat: server rejected the connection: ${reasons.joinToString("; ")}")
                stats = stats.copy(state = State.DENIED, message = reasons.joinToString("; "))
                close(null, reconnect = true)
                return
            }
            val motd = r.optString("motd").takeIf { it.isNotBlank() }
            motd?.let { log("mlat: server says: $it") }
            compress = r.optString("compress", "none")
            when (compress) {
                "none" -> Unit
                "zlib" -> deflater = Deflater(1)
                "zlib2" -> { deflater = Deflater(1); inflater = Inflater() }
                else -> throw java.io.IOException("server asked for unsupported compression $compress")
            }
            if (r.has("udp_transport")) log("mlat: server offered UDP transport; staying on TCP")
            splitSync = r.optBoolean("split_sync", false)
            heartbeatAt = now() + HEARTBEAT_S
            phase = Phase.READY
            log("mlat: handshake complete: compression $compress, split sync ${if (splitSync) "enabled" else "disabled"}")
            stats = stats.copy(state = State.CONNECTED, connectedSinceMs = System.currentTimeMillis(), message = motd)
            coordinator.serverConnected()
            sendRateReport(emptyMap()) // dummy report: tells the server we'll send them
            if (!input.connected) input.reconnect()
        }

        private fun request(r: JSONObject) {
            when {
                r.has("start_sending") -> coordinator.serverStartSending(hexList(r.getJSONArray("start_sending")))
                r.has("stop_sending") -> coordinator.serverStopSending(hexList(r.getJSONArray("stop_sending")))
                r.has("heartbeat") || r.has("result") -> Unit
                r.has("stats") -> {
                    val s = r.getJSONObject("stats")
                    stats = stats.copy(
                        peerCount = s.optDouble("peer_count").takeUnless { it.isNaN() }?.toInt(),
                        outlierPercent = s.optDouble("outlier_percent").takeUnless { it.isNaN() },
                        badSyncTimeout = s.optDouble("bad_sync_timeout").takeUnless { it.isNaN() }?.toInt(),
                    )
                }
                else -> log("mlat: ignoring request from server: $r")
            }
        }

        private fun hexList(a: JSONArray) = (0 until a.length()).map { a.getString(it).toInt(16) }

        override fun onClosed() {
            val wasReady = phase == Phase.READY
            phase = Phase.NONE
            compress = "none"
            deflater?.end(); deflater = null
            inflater?.end(); inflater = null
            lines.clear()
            heartbeatAt = null
            stats = stats.copy(
                state = if (stats.state == State.DENIED) State.DENIED else State.DISCONNECTED,
                connectedSinceMs = null,
            )
            if (wasReady) coordinator.serverDisconnected()
            input.close("lost connection to the MLAT server, no need for input data", reconnect = false)
        }

        override fun sent(n: Int) {
            txBytes += n
        }

        override fun heartbeat(t: Double) {
            super.heartbeat(t)
            if (phase != Phase.NONE && t - lastData > SERVER_INACTIVITY_S) {
                close("no data (not even keepalives) for ${SERVER_INACTIVITY_S.toInt()} s", reconnect = false)
                reconnect()
                return
            }
            val hb = heartbeatAt
            if (hb != null && hb < t) {
                heartbeatAt = t + HEARTBEAT_S
                line("{\"heartbeat\":{\"client_time\":${String.format(Locale.US, "%.3f", System.currentTimeMillis() / 1000.0)}}}")
            }
        }

        /** Every 0.25 s: move queued lines into the socket buffer, compressed if negotiated. */
        fun flushLines() {
            if (phase != Phase.READY || lines.isEmpty()) return
            val def = deflater
            if (def == null) {
                send((lines.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII))
            } else {
                zlibPackets(def, lines).forEach(::send)
            }
            lines.clear()
        }

        private fun line(s: String) {
            if (phase == Phase.READY) lines += s
        }

        // --- MlatCoordinator.Server ---
        override fun sendMlat(m: ModeSMessage) = line("{\"mlat\":{\"t\":${m.timestamp},\"m\":\"${m.hex()}\"}}")
        override fun sendSync(even: ModeSMessage, odd: ModeSMessage) =
            line("{\"sync\":{\"et\":${even.timestamp},\"em\":\"${even.hex()}\",\"ot\":${odd.timestamp},\"om\":\"${odd.hex()}\"}}")
        override fun sendSplitSync(m: ModeSMessage) = line("{\"ssync\":{\"t\":${m.timestamp},\"m\":\"${m.hex()}\"}}")
        override fun sendSeen(addresses: Collection<Int>) = line("{\"seen\":${icaoList(addresses)}}")
        override fun sendLost(addresses: Collection<Int>) = line("{\"lost\":${icaoList(addresses)}}")
        override fun sendRateReport(report: Map<Int, Double>) =
            line(report.entries.joinToString(",", "{\"rate_report\":{", "}}") {
                "\"%06X\":%s".format(Locale.US, it.key, round2(it.value))
            })
        override fun sendInputConnected() = line("{\"input_connected\":\"connected\"}")
        override fun sendInputDisconnected() = line("{\"input_disconnected\":\"disconnected\"}")
        override fun sendClockJump() {
            val t = now()
            if (t > lastClockJump + 0.5) {
                lastClockJump = t
                line("{\"clock_jump\":true}")
            }
        }
        override fun sendClockReset(reason: String, frequency: Long?, mode: String?) {
            val extra = if (frequency != null) ",\"frequency\":$frequency,\"epoch\":null,\"mode\":${str(mode ?: "")}" else ""
            line("{\"clock_reset\":{\"reason\":${str(reason)}$extra}}")
        }

        private fun icaoList(a: Collection<Int>) = a.joinToString(",", "[", "]") { "\"%06x\"".format(it) }
    }

    companion object {
        /** mlat-client version this is a port of; the server may use it to pick behaviour. */
        const val CLIENT_VERSION = "0.4.2"
        val ADSBX_HOSTS = listOf("feed1.adsbexchange.com", "feed2.adsbexchange.com")
        val ADSBX_PORTS = listOf(31090, 64590)
        private const val HEARTBEAT_S = 120.0
        private const val SERVER_INACTIVITY_S = 60.0
        private const val INPUT_INACTIVITY_S = 150.0

        /** JSON string, ASCII-only like Python's json.dumps (non-ASCII as \\uXXXX). */
        internal fun str(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c.code < 0x20 || c.code > 0x7e -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }

        /** Like Python's float repr for the values we send: always has a decimal point. */
        internal fun num(d: Double): String = d.toString()

        internal fun round2(d: Double): String =
            java.math.BigDecimal(d).setScale(2, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros()
                .let { if (it.scale() <= 0) it.setScale(1) else it }.toPlainString()
    }
}

// --- Protocol helpers (top level so they can be unit-tested) -----------------------------

/** The handshake line, before its padding: keys sorted, ", " / ": " separators, as upstream's json.dumps(sort_keys=True). */
internal fun handshakeJson(config: MlatClient.Config, frequency: Long): String {
    val fields = sortedMapOf(
        "alt" to MlatClient.num(config.altM),
        "client_version" to MlatClient.str(MlatClient.CLIENT_VERSION),
        "clock_epoch" to "null",
        "clock_frequency" to frequency.toString(),
        "clock_type" to MlatClient.str("dump1090"),
        "compress" to "[\"none\", \"zlib\", \"zlib2\"]",
        "heartbeat" to "true",
        "lat" to MlatClient.num(config.lat),
        "lon" to MlatClient.num(config.lon),
        "privacy" to config.privacy.toString(),
        "return_result_format" to MlatClient.str("ecef"),
        "return_results" to "false",
        "return_stats" to "true",
        "selective_traffic" to "true",
        "udp_transport" to "false",
        "user" to MlatClient.str(config.user),
        "uuid" to MlatClient.str(config.uuid),
        "version" to "3",
    )
    return fields.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" }
}

private val SYNC_TAIL = byteArrayOf(0, 0, 0xff.toByte(), 0xff.toByte())

/** Most the server may send before a complete message, and most one zlib2 packet may inflate to. */
internal const val MAX_SERVER_MESSAGE = 1 shl 20

/**
 * Client-to-server zlib framing, as upstream's fill_zlib: lines are compressed into one stream,
 * Z_SYNC_FLUSHed at the end or every 32 kB, the 00 00 FF FF tail stripped, and each packet sent
 * with a 2-byte big-endian length.
 */
internal fun zlibPackets(def: Deflater, lines: List<String>): List<ByteArray> {
    val packets = ArrayList<ByteArray>()
    val packet = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var pending = false
    fun emit() {
        while (true) {
            val n = def.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
            packet.write(chunk, 0, n)
            if (n < chunk.size) break
        }
        val data = packet.toByteArray()
        check(data.size >= 4 && data.copyOfRange(data.size - 4, data.size).contentEquals(SYNC_TAIL))
        val body = data.copyOf(data.size - 4)
        packets += byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
        packet.reset()
        pending = false
    }
    for (l in lines) {
        def.setInput((l + "\n").toByteArray(Charsets.US_ASCII))
        while (!def.needsInput()) {
            val n = def.deflate(chunk, 0, chunk.size, Deflater.NO_FLUSH)
            packet.write(chunk, 0, n)
        }
        pending = true
        if (packet.size() >= 32768) emit()
    }
    if (pending) emit()
    return packets
}

/**
 * Server-to-client zlib2 framing, as upstream's consume_readbuf_zlib: complete length-prefixed
 * packets are inflated (with the stripped 00 00 FF FF put back) and split into lines.
 * @return the complete lines and the number of bytes consumed.
 */
internal fun zlibUnpack(inf: Inflater, bytes: ByteArray): Pair<List<String>, Int> {
    val lines = ArrayList<String>()
    var i = 0
    val chunk = ByteArray(16384)
    while (i + 2 < bytes.size) {
        val hlen = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        val end = i + 2 + hlen
        if (end > bytes.size) break
        inf.setInput(bytes.copyOfRange(i + 2, end) + SYNC_TAIL)
        val text = ByteArrayOutputStream()
        while (true) {
            val n = inf.inflate(chunk)
            if (n == 0 && (inf.needsInput() || inf.finished())) break
            text.write(chunk, 0, n)
            // A small packet can inflate to a huge one (a "zip bomb"); the caller reconnects.
            if (text.size() > MAX_SERVER_MESSAGE) throw java.io.IOException("zlib2 packet inflates past ${MAX_SERVER_MESSAGE / 1024} KiB")
        }
        lines += text.toString(Charsets.US_ASCII.name()).split('\n').dropLast(1)
        i = end
    }
    return lines to i
}
