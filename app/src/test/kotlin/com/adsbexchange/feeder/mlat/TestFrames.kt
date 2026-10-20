package com.adsbexchange.feeder.mlat

/** Helpers to build valid Mode S frames for tests. */
object TestFrames {
    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** DF17 with the given 56-bit ME field and a correct CRC. */
    fun df17(icao: Int, me: Long): ByteArray {
        val m = ByteArray(14)
        m[0] = ((17 shl 3) or 5).toByte()
        m[1] = (icao shr 16).toByte(); m[2] = (icao shr 8).toByte(); m[3] = icao.toByte()
        for (i in 0 until 7) m[4 + i] = (me ushr (48 - 8 * i)).toByte()
        return withParity(m, 0)
    }

    /** Airborne position (TC 11, so NUCp 7) with 25 ft altitude coding and non-zero CPR. */
    fun df17Position(icao: Int, altFt: Int, odd: Boolean, lat17: Int = 0x12345, lon17: Int = 0x0abcd): ByteArray {
        val n = (altFt + 1000) / 25
        val alt12 = ((n shr 4) shl 5) or (1 shl 4) or (n and 0xf)
        val me = (11L shl 51) or (alt12.toLong() shl 36) or ((if (odd) 1L else 0L) shl 34) or
            (lat17.toLong() shl 17) or lon17.toLong()
        return df17(icao, me)
    }

    /** DF11 all-call reply (IID 0) from [icao]. */
    fun df11(icao: Int): ByteArray {
        val m = ByteArray(7)
        m[0] = ((11 shl 3) or 5).toByte()
        m[1] = (icao shr 16).toByte(); m[2] = (icao shr 8).toByte(); m[3] = icao.toByte()
        return withParity(m, 0)
    }

    /** DF4 altitude reply from [icao]: address/parity, so the CRC residual is the address. */
    fun df4(icao: Int, ac13: Int): ByteArray {
        val m = ByteArray(7)
        m[0] = (4 shl 3).toByte()
        m[2] = ((ac13 shr 8) and 0x1f).toByte()
        m[3] = ac13.toByte()
        return withParity(m, icao)
    }

    private fun withParity(m: ByteArray, xor: Int): ByteArray {
        val p = ModeSMessage.crc(m, m.size - 3) xor xor
        m[m.size - 3] = (p shr 16).toByte(); m[m.size - 2] = (p shr 8).toByte(); m[m.size - 1] = p.toByte()
        return m
    }

    /** Beast frame '2' or '3' with a 6-byte 12 MHz timestamp, escaping 0x1A bytes. */
    fun beast(timestamp: Long, signal: Int, msg: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x1a)
        out.write(if (msg.size == 7) '2'.code else '3'.code)
        fun put(b: Int) {
            out.write(b and 0xff)
            if (b and 0xff == 0x1a) out.write(0x1a)
        }
        for (i in 5 downTo 0) put((timestamp shr (8 * i)).toInt())
        put(signal)
        for (b in msg) put(b.toInt())
        return out.toByteArray()
    }
}
