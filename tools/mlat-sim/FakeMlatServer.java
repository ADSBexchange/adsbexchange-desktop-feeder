import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A stand-in for the ADS-B Exchange MLAT server, for the smoke test. It speaks the server side of
 * mlat-client's TCP JSON protocol well enough to exercise the app's client:
 *
 *  - reads the handshake line and answers with compress "zlib2" (both directions compressed);
 *  - asks for the aircraft listed on the command line (start_sending) and sends a stats message;
 *  - records every line the client sends, then writes a JSON summary and exits.
 *
 * Usage: java FakeMlatServer.java <port> <summary.json> <seconds> <hex icao>...
 * Binds 127.0.0.1; the Android emulator reaches it as 10.0.2.2.
 */
public class FakeMlatServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String summaryPath = args[1];
        long deadline = System.currentTimeMillis() + (long) (Double.parseDouble(args[2]) * 1000);
        List<String> wanted = new ArrayList<>();
        for (int i = 3; i < args.length; i++) wanted.add(args[i].toLowerCase());

        String handshake = null;
        int lines = 0, syncs = 0, mlats = 0, rateReports = 0, heartbeats = 0;
        boolean inputConnected = false, clockReset = false;
        Set<String> mlatAddresses = new LinkedHashSet<>(), syncAddresses = new LinkedHashSet<>(), seen = new LinkedHashSet<>();
        String error = null;

        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket - test stand-in on the PC's loopback
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout((int) Math.max(1000, deadline - System.currentTimeMillis()));
            System.out.println("fake mlat server listening on 127.0.0.1:" + port);
            try (Socket s = server.accept()) {
                s.setSoTimeout(500);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                Inflater inflater = new Inflater();
                Deflater deflater = new Deflater(1);
                boolean compressed = false;
                byte[] chunk = new byte[16384];
                while (System.currentTimeMillis() < deadline) {
                    int n;
                    try {
                        n = in.read(chunk);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    if (n < 0) break;
                    buf.write(chunk, 0, n);
                    byte[] b = buf.toByteArray();
                    int used = 0;
                    List<String> got = new ArrayList<>();
                    if (!compressed) {
                        int nl = indexOf(b, (byte) '\n');
                        if (nl < 0) continue;
                        handshake = new String(b, 0, nl, StandardCharsets.US_ASCII).trim();
                        used = nl + 1;
                        compressed = true;
                        send(out, deflater, "{\"compress\":\"zlib2\",\"motd\":\"fake MLAT server for smoke tests\"}", false);
                        // Everything from here on is zlib2 framed.
                        StringBuilder req = new StringBuilder("{\"start_sending\":[");
                        for (int i = 0; i < wanted.size(); i++) req.append(i == 0 ? "" : ",").append('"').append(wanted.get(i)).append('"');
                        send(out, deflater, req.append("]}").toString(), true);
                        send(out, deflater, "{\"stats\":{\"peer_count\":7,\"outlier_percent\":1.5,\"bad_sync_timeout\":0}}", true);
                    }
                    // Client packets: 2-byte length, deflate data without the 00 00 FF FF tail.
                    while (used + 2 < b.length) {
                        int len = ((b[used] & 0xff) << 8) | (b[used + 1] & 0xff);
                        if (used + 2 + len > b.length) break;
                        byte[] data = new byte[len + 4];
                        System.arraycopy(b, used + 2, data, 0, len);
                        data[len] = 0; data[len + 1] = 0; data[len + 2] = (byte) 0xff; data[len + 3] = (byte) 0xff;
                        inflater.setInput(data);
                        ByteArrayOutputStream text = new ByteArrayOutputStream();
                        byte[] o = new byte[16384];
                        int k;
                        while ((k = inflater.inflate(o)) > 0) text.write(o, 0, k);
                        for (String l : text.toString(StandardCharsets.US_ASCII).split("\n")) if (!l.isEmpty()) got.add(l);
                        used += 2 + len;
                    }
                    buf.reset();
                    buf.write(b, used, b.length - used);
                    for (String l : got) {
                        lines++;
                        if (l.startsWith("{\"sync\":")) { syncs++; addr(l, "\"em\":\"", syncAddresses); }
                        else if (l.startsWith("{\"mlat\":")) { mlats++; addr(l, "\"m\":\"", mlatAddresses); }
                        else if (l.startsWith("{\"rate_report\":")) rateReports++;
                        else if (l.startsWith("{\"heartbeat\":")) heartbeats++;
                        else if (l.startsWith("{\"input_connected\":")) inputConnected = true;
                        else if (l.startsWith("{\"clock_reset\":")) clockReset = true;
                        else if (l.startsWith("{\"seen\":")) {
                            Matcher m = Pattern.compile("\"([0-9a-f]{6})\"").matcher(l);
                            while (m.find()) seen.add(m.group(1));
                        }
                    }
                }
            }
        } catch (Exception e) {
            error = e.toString();
        }

        try (PrintWriter w = new PrintWriter(summaryPath, "UTF-8")) {
            w.println("{");
            w.println("  \"handshake\": " + (handshake == null ? "null" : handshake) + ",");
            w.println("  \"lines\": " + lines + ", \"syncs\": " + syncs + ", \"mlat\": " + mlats + ",");
            w.println("  \"rate_reports\": " + rateReports + ", \"heartbeats\": " + heartbeats + ",");
            w.println("  \"input_connected\": " + inputConnected + ", \"clock_reset\": " + clockReset + ",");
            w.println("  \"sync_addresses\": " + list(syncAddresses) + ",");
            w.println("  \"mlat_addresses\": " + list(mlatAddresses) + ",");
            w.println("  \"seen\": " + list(seen) + ",");
            w.println("  \"error\": " + (error == null ? "null" : "\"" + error.replace("\"", "'") + "\""));
            w.println("}");
        }
        System.out.println("fake mlat server done: " + lines + " lines, " + syncs + " syncs, " + mlats + " mlat");
    }

    /** DF17/DF11 carry the address in bytes 1-3; DF4 etc. are address/parity (CRC residual). */
    static void addr(String line, String key, Set<String> into) {
        int i = line.indexOf(key);
        if (i < 0) return;
        String hex = line.substring(i + key.length(), line.indexOf('"', i + key.length()));
        int df = (Integer.parseInt(hex.substring(0, 2), 16) >> 3) & 31;
        if (df == 11 || df == 17) {
            into.add(hex.substring(2, 8));
        } else {
            byte[] m = new byte[hex.length() / 2];
            for (int k = 0; k < m.length; k++) m[k] = (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
            int crc = crc24(m, m.length - 3);
            int parity = ((m[m.length - 3] & 0xff) << 16) | ((m[m.length - 2] & 0xff) << 8) | (m[m.length - 1] & 0xff);
            into.add(String.format("%06x", crc ^ parity));
        }
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

    /** Handshake reply is a plain line; after that, zlib2: length-prefixed sync-flushed packets. */
    static void send(OutputStream out, Deflater def, String line, boolean zlib) throws Exception {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.US_ASCII);
        if (!zlib) {
            out.write(bytes);
            out.flush();
            return;
        }
        def.setInput(bytes);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = def.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH)) > 0) {
            packet.write(chunk, 0, n);
            if (n < chunk.length) break;
        }
        byte[] data = packet.toByteArray();
        int len = data.length - 4; // strip 00 00 FF FF
        out.write(new byte[] {(byte) (len >> 8), (byte) len});
        out.write(data, 0, len);
        out.flush();
    }

    static int indexOf(byte[] b, byte v) {
        for (int i = 0; i < b.length; i++) if (b[i] == v) return i;
        return -1;
    }

    static String list(Set<String> s) {
        StringBuilder sb = new StringBuilder("[");
        for (String x : s) sb.append(sb.length() > 1 ? ", " : "").append('"').append(x).append('"');
        return sb.append("]").toString();
    }
}
