package com.adsbexchange.feeder.mlat

/*
 * Mode S message decoding for the MLAT client: a Kotlin port of the parts of mlat-client's
 * modes_message.c / modes_crc.c the client uses (github.com/adsbexchange/mlat-client, GPL-3).
 * Only what the coordinator needs is decoded: DF, address, CRC validity, altitude, CPR odd/even
 * and NUCp.
 */

/** Pseudo-DF for Mode A/C replies (2-byte messages), as in mlat-client. */
const val DF_MODEAC = 32

class ModeSMessage(
    /** 12 MHz ticks, already adjusted to the start of the frame (see [BeastReader]). */
    val timestamp: Long,
    val signal: Int,
    val data: ByteArray,
) {
    var df = 0; private set
    /** ICAO address, or for address/parity DFs the CRC residual (which is the address). */
    var address = 0; private set
    var valid = false; private set
    /** Feet, or null if absent or not decodable. */
    var altitude: Int? = null; private set
    var evenCpr = false; private set
    var oddCpr = false; private set
    var nuc = 0; private set

    init {
        decode()
    }

    val length get() = data.size

    /** Lowercase hex of the raw bytes, as mlat-client's str(message). */
    fun hex(): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    private fun u(i: Int) = data[i].toInt() and 0xff

    private fun decode() {
        if (data.size == 2) {
            df = DF_MODEAC
            address = (u(0) shl 8) or u(1)
            valid = true
            return
        }
        df = (u(0) shr 3) and 31
        if ((df < 16 && data.size != 7) || (df >= 16 && data.size != 14)) return
        if (df !in HANDLED_DFS) return

        val crc = crcResidual(data)
        when (df) {
            0, 4, 16, 20 -> {
                address = crc
                altitude = decodeAc13(((u(2) and 0x1f) shl 8) or u(3))
                valid = true
            }
            5, 21 -> {
                address = crc
                valid = true
            }
            11 -> {
                valid = (crc and 0x7f.inv()) == 0
                if (valid) address = (u(1) shl 16) or (u(2) shl 8) or u(3)
            }
            17 -> {
                valid = crc == 0
                if (!valid) return
                address = (u(1) shl 16) or (u(2) shl 8) or u(3)
                val metype = u(4) shr 3
                if ((metype in 9..18) || (metype in 20..21)) {
                    nuc = if (metype <= 18) 18 - metype else 29 - metype
                    if (u(6) and 0x04 != 0) oddCpr = true else evenCpr = true
                    altitude = decodeAc12((u(5) shl 4) or ((u(6) and 0xf0) shr 4))
                    // Crude check for missing CPR data, as upstream: mostly-zero lat or lon.
                    if ((u(7) == 0 && (u(8) and 0x7f) == 0) || (u(9) == 0 && u(10) == 0)) valid = false
                }
            }
        }
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private val HANDLED_DFS = setOf(0, 4, 5, 11, 16, 17, 20, 21)
        private const val GENERATOR = 0xfff409

        private val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 16
            repeat(8) { c = if (c and 0x800000 != 0) (c shl 1) xor GENERATOR else c shl 1 }
            c and 0xffffff
        }

        fun crc(buf: ByteArray, len: Int): Int {
            var rem = 0
            for (i in 0 until len) {
                val b = buf[i].toInt() and 0xff
                rem = ((rem and 0xffff) shl 8) xor CRC_TABLE[b xor ((rem and 0xff0000) shr 16)]
            }
            return rem
        }

        /** CRC over the body XORed with the transmitted parity: 0 for a clean DF17. */
        fun crcResidual(m: ByteArray): Int {
            val n = m.size
            if (n < 3) return 0
            var c = crc(m, n - 3)
            c = c xor ((m[n - 3].toInt() and 0xff) shl 16)
            c = c xor ((m[n - 2].toInt() and 0xff) shl 8)
            c = c xor (m[n - 1].toInt() and 0xff)
            return c
        }

        fun decodeAc13(ac13: Int): Int? {
            if (ac13 == 0) return null
            if (ac13 and 0x0040 != 0) return null // M bit (metres)
            if (ac13 and 0x0010 != 0) { // Q bit: 25 ft
                val n = ((ac13 and 0x1f80) shr 2) or ((ac13 and 0x0020) shr 1) or (ac13 and 0x000f)
                return n * 25 - 1000
            }
            // Gillham code
            if (ac13 and 0x1500 == 0) return null
            var h = 0
            if (ac13 and 0x1000 != 0) h = h xor 7 // C1
            if (ac13 and 0x0400 != 0) h = h xor 3 // C2
            if (ac13 and 0x0100 != 0) h = h xor 1 // C4
            if (h and 5 != 0) h = h xor 5
            if (h > 5) return null
            var f = 0
            if (ac13 and 0x0010 != 0) f = f xor 0x1ff // D1
            if (ac13 and 0x0004 != 0) f = f xor 0x0ff // D2
            if (ac13 and 0x0001 != 0) f = f xor 0x07f // D4
            if (ac13 and 0x0800 != 0) f = f xor 0x03f // A1
            if (ac13 and 0x0200 != 0) f = f xor 0x01f // A2
            if (ac13 and 0x0080 != 0) f = f xor 0x00f // A4
            if (ac13 and 0x0020 != 0) f = f xor 0x007 // B1
            if (ac13 and 0x0008 != 0) f = f xor 0x003 // B2
            if (ac13 and 0x0002 != 0) f = f xor 0x001 // B4
            if (f and 1 != 0) h = 6 - h
            val a = 500 * f + 100 * h - 1300
            return if (a < -1200) null else a
        }

        fun decodeAc12(ac12: Int): Int? = decodeAc13(((ac12 and 0x0fc0) shl 1) or (ac12 and 0x003f))
    }
}
