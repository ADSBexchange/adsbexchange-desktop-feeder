import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Generates a synthetic 1090 MHz capture for smoke-testing readsb without a dongle.
 *
 * Writes 8-bit unsigned interleaved I/Q (readsb --iformat UC8) at 2.4 MSps containing ADS-B
 * extended squitters (identification, airborne position, airborne velocity) from a fixed set
 * of aircraft, plus the expected decode as JSON for the smoke test to compare against.
 *
 * Usage: java AdsbSim.java <out.iq> <expected.json> [seconds]
 */
public class AdsbSim {
    static final double SAMPLE_RATE = 2.4e6;
    static final double RX_LAT = 43.0, RX_LON = -76.0; // receiver position passed to readsb

    record Aircraft(int icao, String callsign, double lat, double lon, int altFt,
                    int eastKt, int northKt, int vrateFpm, double amplitude) {}

    static final List<Aircraft> AIRCRAFT = List.of(
        new Aircraft(0xA1B2C3, "TEST001", 43.20, -75.80, 35000, 450, 0, 0, 90),
        new Aircraft(0xA4D5E6, "SMOKE02", 42.85, -76.30, 12000, 0, -250, -1024, 60),
        new Aircraft(0xABCDEF, "PHONE3", 43.50, -76.60, 5000, -150, 100, 640, 40),
        new Aircraft(0xC0FFEE, "RTLSDR4", 42.60, -75.40, 24000, 300, 300, 0, 25),
        new Aircraft(0x0D15EA, "ADSBX5", 43.05, -76.05, 2500, -90, -90, -320, 70));

    static final int MODES_ONLY_ICAO = 0x7C1234;
    static final int MODES_ONLY_ALT_FT = 18000;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java AdsbSim.java <out.iq> <expected.json> [seconds]");
            System.exit(2);
        }
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 20;
        selfTest();

        int total = (int) (seconds * SAMPLE_RATE);
        float[] envelope = new float[total];           // per-sample pulse amplitude
        float[] owner = new float[total];              // which aircraft's carrier (index + 1)
        Random rnd = new Random(1090);

        int frames = 0;
        for (int a = 0; a < AIRCRAFT.size(); a++) {
            Aircraft ac = AIRCRAFT.get(a);
            // Stagger aircraft by 7 ms and use rates that keep frames from overlapping.
            double offset = 0.013 + a * 0.007;
            int n = 0;
            for (double t = offset; t < seconds - 0.001; t += 0.1, n++) {
                byte[] msg;
                if (n % 25 == 0) msg = identification(ac);          // every 2.5 s
                else if (n % 5 == 2) msg = velocity(ac);            // every 0.5 s
                else if (n % 5 == 0 || n % 5 == 4) continue;
                else msg = position(ac, n % 5 == 3 ? 1 : 0);        // even then odd CPR
                renderFrame(envelope, owner, t, msg, ac.amplitude, a + 1);
                frames++;
            }
        }

        // A Mode S-only aircraft (no ADS-B positions): DF11 squitters and DF4 altitude replies,
        // the traffic MLAT exists for. Its own 1 ms slot keeps it clear of the others.
        int modesId = AIRCRAFT.size() + 1;
        int n5 = 0;
        for (double t = 0.048; t < seconds - 0.001; t += 0.1, n5++) {
            byte[] msg = n5 % 10 == 0 ? df11(MODES_ONLY_ICAO) : df4(MODES_ONLY_ICAO, MODES_ONLY_ALT_FT);
            renderFrame(envelope, owner, t, msg, 55, modesId);
            frames++;
        }

        // Modulate: each aircraft has its own small carrier offset; add Gaussian noise.
        double[] freq = new double[AIRCRAFT.size() + 2];
        double[] phase = new double[AIRCRAFT.size() + 2];
        for (int i = 1; i < freq.length; i++) freq[i] = (rnd.nextDouble() - 0.5) * 100e3;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(args[0]), 1 << 20)) {
            byte[] buf = new byte[2 * 65536];
            int k = 0;
            for (int s = 0; s < total; s++) {
                double i = 127.5 + rnd.nextGaussian() * 2.0;
                double q = 127.5 + rnd.nextGaussian() * 2.0;
                int o = (int) owner[s];
                for (int p = 1; p < phase.length; p++) phase[p] += 2 * Math.PI * freq[p] / SAMPLE_RATE;
                if (o > 0 && envelope[s] > 0) {
                    i += envelope[s] * Math.cos(phase[o]);
                    q += envelope[s] * Math.sin(phase[o]);
                }
                buf[k++] = (byte) clamp(i);
                buf[k++] = (byte) clamp(q);
                if (k == buf.length) {
                    out.write(buf, 0, k);
                    k = 0;
                }
            }
            out.write(buf, 0, k);
        }

        try (PrintWriter w = new PrintWriter(args[1], "UTF-8")) {
            w.println("{");
            w.printf(Locale.US, "  \"seconds\": %s,%n", seconds % 1 == 0 ? String.valueOf((long) seconds) : String.valueOf(seconds));
            w.printf(Locale.US, "  \"receiver\": {\"lat\": %.5f, \"lon\": %.5f},%n", RX_LAT, RX_LON);
            w.println("  \"aircraft\": [");
            for (int a = 0; a < AIRCRAFT.size(); a++) {
                Aircraft ac = AIRCRAFT.get(a);
                double gs = Math.hypot(ac.eastKt, ac.northKt);
                double track = (Math.toDegrees(Math.atan2(ac.eastKt, ac.northKt)) + 360) % 360;
                w.printf(Locale.US,
                    "    {\"hex\": \"%06x\", \"flight\": \"%s\", \"lat\": %.5f, \"lon\": %.5f, \"alt_baro\": %d, "
                        + "\"gs\": %.1f, \"track\": %.1f, \"baro_rate\": %d}%s%n",
                    ac.icao, ac.callsign, ac.lat, ac.lon, ac.altFt, gs, track, ac.vrateFpm,
                    a + 1 < AIRCRAFT.size() ? "," : "");
            }
            w.println("  ],");
            w.printf(Locale.US, "  \"modes_only\": {\"hex\": \"%06x\", \"alt_baro\": %d}%n", MODES_ONLY_ICAO, MODES_ONLY_ALT_FT);
            w.println("}");
        }
        System.out.printf(Locale.US, "wrote %s: %.1f s, %d frames from %d aircraft (%.1f MB)%n",
            args[0], seconds, frames, AIRCRAFT.size(), total * 2 / 1e6);
    }

    // ---- Mode S / ADS-B encoding ----------------------------------------------------------

    static byte[] df17(int icao, long me56) {
        byte[] m = new byte[14];
        m[0] = (byte) ((17 << 3) | 5); // DF=17, CA=5
        m[1] = (byte) (icao >>> 16);
        m[2] = (byte) (icao >>> 8);
        m[3] = (byte) icao;
        for (int i = 0; i < 7; i++) m[4 + i] = (byte) (me56 >>> (48 - 8 * i));
        int crc = crc24(m, 11);
        m[11] = (byte) (crc >>> 16);
        m[12] = (byte) (crc >>> 8);
        m[13] = (byte) crc;
        return m;
    }

    /** DF11 all-call reply / acquisition squitter (IID 0): parity = CRC. */
    static byte[] df11(int icao) {
        byte[] m = new byte[7];
        m[0] = (byte) ((11 << 3) | 5);
        m[1] = (byte) (icao >>> 16);
        m[2] = (byte) (icao >>> 8);
        m[3] = (byte) icao;
        int crc = crc24(m, 4);
        m[4] = (byte) (crc >>> 16);
        m[5] = (byte) (crc >>> 8);
        m[6] = (byte) crc;
        return m;
    }

    /** DF4 altitude reply, 25 ft coding; address/parity: parity = CRC xor ICAO. */
    static byte[] df4(int icao, int altFt) {
        int n = (altFt + 1000) / 25;
        int ac13 = ((n & 0x7e0) << 2) | ((n & 0x10) << 1) | 0x10 | (n & 0xf);
        byte[] m = new byte[7];
        m[0] = (byte) (4 << 3);
        m[2] = (byte) ((ac13 >>> 8) & 0x1f);
        m[3] = (byte) ac13;
        int p = crc24(m, 4) ^ icao;
        m[4] = (byte) (p >>> 16);
        m[5] = (byte) (p >>> 8);
        m[6] = (byte) p;
        return m;
    }

    static int crc24(byte[] m, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (m[i] & 0xFF) << 16;
            for (int b = 0; b < 8; b++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) crc ^= 0x1FFF409;
            }
        }
        return crc & 0xFFFFFF;
    }

    static byte[] identification(Aircraft ac) {
        String cs = String.format("%-8s", ac.callsign);
        long me = (4L << 51); // TC=4, emitter category 0
        for (int c = 0; c < 8; c++) {
            char ch = cs.charAt(c);
            int v = ch == ' ' ? 32 : (ch >= '0' && ch <= '9') ? ch : ch - 'A' + 1;
            me |= (long) v << (42 - 6 * c);
        }
        return df17(ac.icao, me);
    }

    static byte[] position(Aircraft ac, int odd) {
        int n = (ac.altFt + 1000) / 25;
        long alt12 = ((n >> 4) << 5) | (1 << 4) | (n & 0xF); // Q-bit = 1, 25 ft resolution
        long[] cpr = cprEncode(ac.lat, ac.lon, odd);
        long me = (11L << 51) | (alt12 << 36) | ((long) odd << 34) | (cpr[0] << 17) | cpr[1];
        return df17(ac.icao, me);
    }

    static byte[] velocity(Aircraft ac) {
        long sEw = ac.eastKt < 0 ? 1 : 0, vEw = Math.abs(ac.eastKt) + 1;
        long sNs = ac.northKt < 0 ? 1 : 0, vNs = Math.abs(ac.northKt) + 1;
        long sVr = ac.vrateFpm < 0 ? 1 : 0, vr = Math.abs(ac.vrateFpm) / 64 + 1;
        long me = (19L << 51) | (1L << 48)       // TC=19, subtype 1 (ground speed, subsonic)
            | (1L << 43)                          // NACv = 1
            | (sEw << 42) | (vEw << 32) | (sNs << 31) | (vNs << 21)
            | (1L << 20)                          // vertical rate source: baro
            | (sVr << 19) | (vr << 10);
        return df17(ac.icao, me);
    }

    /** Airborne CPR encoding (17-bit), per DO-260B. Returns {latCpr, lonCpr}. */
    static long[] cprEncode(double lat, double lon, int odd) {
        double scale = 1 << 17;
        double dlat = 360.0 / (60 - odd);
        long yz = (long) Math.floor(scale * mod(lat, dlat) / dlat + 0.5);
        double rlat = dlat * (yz / scale + Math.floor(lat / dlat));
        double dlon = 360.0 / Math.max(nl(rlat) - odd, 1);
        long xz = (long) Math.floor(scale * mod(lon, dlon) / dlon + 0.5);
        return new long[] {yz & 0x1FFFF, xz & 0x1FFFF};
    }

    static int nl(double lat) {
        lat = Math.abs(lat);
        if (lat == 0) return 59;
        if (lat == 87) return 2;
        if (lat > 87) return 1;
        double a = 1 - Math.cos(Math.PI / 30);
        double b = Math.pow(Math.cos(Math.PI / 180 * lat), 2);
        return (int) Math.floor(2 * Math.PI / Math.acos(1 - a / b));
    }

    static double mod(double x, double y) {
        return x - y * Math.floor(x / y);
    }

    // ---- Pulse rendering ------------------------------------------------------------------

    /** Mode S PPM: preamble pulses at 0, 1, 3.5, 4.5 µs; data bits from 8 µs, 1 µs each. */
    static void renderFrame(float[] env, float[] owner, double t0, byte[] msg, double amp, int id) {
        double us = 1e-6;
        double[] preamble = {0, 1.0, 3.5, 4.5};
        for (double p : preamble) pulse(env, owner, t0 + p * us, amp, id);
        for (int bit = 0; bit < msg.length * 8; bit++) {
            boolean one = ((msg[bit / 8] >> (7 - bit % 8)) & 1) != 0;
            pulse(env, owner, t0 + (8 + bit + (one ? 0 : 0.5)) * us, amp, id);
        }
    }

    /** Adds a 0.5 µs rectangular pulse, integrated over each sample period it overlaps. */
    static void pulse(float[] env, float[] owner, double start, double amp, int id) {
        double end = start + 0.5e-6;
        int s0 = (int) Math.floor(start * SAMPLE_RATE);
        int s1 = (int) Math.floor(end * SAMPLE_RATE);
        for (int s = s0; s <= s1 && s < env.length; s++) {
            double a = Math.max(start, s / SAMPLE_RATE), b = Math.min(end, (s + 1) / SAMPLE_RATE);
            if (b <= a) continue;
            env[s] += (float) (amp * (b - a) * SAMPLE_RATE);
            owner[s] = id;
        }
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Checks the CRC against a published DF17 frame (8D4840D6202CC371C32CE0576098, KLM1023). */
    static void selfTest() {
        byte[] m = new byte[14];
        String hex = "8D4840D6202CC371C32CE0576098";
        for (int i = 0; i < 14; i++) m[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        int crc = crc24(m, 11);
        if (crc != 0x576098) throw new AssertionError(String.format("CRC self-test failed: %06x", crc));
    }
}
