import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes the radar's airport list (app/src/main/resources/airports/airports.tsv) from OurAirports'
 * airports.csv (public domain, the Unlicense), pinned to one commit of its GitHub mirror and checked
 * against its SHA-256. To move to newer data, change SOURCE_COMMIT and SOURCE_SHA256 together.
 *
 * Kept: large and medium airports, and the small ones with an ICAO code (ICAO-style, four letters,
 * as the GPS code in many countries) or scheduled service. Left out: heliports, seaplane bases,
 * balloonports, closed airports, and small fields with only a local code (mostly private strips).
 *
 * Usage: java tools/airports/MakeAirports.java app/src/main/resources/airports/airports.tsv
 */
public class MakeAirports {
    static final String SOURCE_COMMIT = "b529623d2a0b09a9be78d37fc7a7a5e1e16e3451";
    static final String SOURCE_DATE = "2026-10-16";
    static final String SOURCE_SHA256 = "0499dbd88de2fc735ac5c09f59796ccc3af544e17ffda807f793c0c6fe6c3d75";
    static final String SOURCE_URL =
        "https://raw.githubusercontent.com/davidmegginson/ourairports-data/" + SOURCE_COMMIT + "/airports.csv";

    record Airport(String code, char size, double lat, double lon) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java MakeAirports.java <out.tsv>");
            System.exit(2);
        }
        byte[] csv = download();
        String digest = sha256(csv);
        if (!digest.equals(SOURCE_SHA256)) {
            throw new IllegalStateException("airports.csv doesn't match its pinned SHA-256 (got " + digest + ")");
        }
        List<List<String>> rows = parseCsv(new String(csv, StandardCharsets.UTF_8));
        List<String> header = rows.get(0);
        Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < header.size(); i++) col.put(header.get(i), i);

        List<Airport> airports = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            String type = r.get(col.get("type"));
            String icao = r.get(col.get("icao_code"));
            String gps = r.get(col.get("gps_code"));
            String iata = r.get(col.get("iata_code"));
            String ident = r.get(col.get("ident"));
            boolean scheduled = "yes".equals(r.get(col.get("scheduled_service")));
            char size;
            switch (type) {
                case "large_airport" -> size = 'L';
                case "medium_airport" -> size = 'M';
                case "small_airport" -> {
                    if (icao.isEmpty() && !gps.matches("[A-Z]{4}") && !scheduled) continue;
                    size = 'S';
                }
                default -> { continue; }
            }
            String code = !icao.isEmpty() ? icao : !gps.isEmpty() ? gps : !iata.isEmpty() ? iata : ident;
            if (code.isEmpty() || code.contains("\t")) continue;
            airports.add(new Airport(code, size,
                Double.parseDouble(r.get(col.get("latitude_deg"))), Double.parseDouble(r.get(col.get("longitude_deg")))));
        }
        airports.sort(Comparator.comparingInt((Airport a) -> "LMS".indexOf(a.size())).thenComparing(Airport::code)
            .thenComparingDouble(Airport::lat).thenComparingDouble(Airport::lon));

        StringBuilder out = new StringBuilder()
            .append("# Airports for the radar, from OurAirports (https://ourairports.com/data/), public domain (the Unlicense).\n")
            .append("# Source: github.com/davidmegginson/ourairports-data ").append(SOURCE_COMMIT).append(" (").append(SOURCE_DATE)
            .append(") airports.csv, SHA-256 ").append(SOURCE_SHA256).append(".\n")
            .append("# Made by tools/airports/MakeAirports.java. Columns: code, size (L large, M medium, S small), latitude, longitude.\n");
        for (Airport a : airports) {
            out.append(a.code()).append('\t').append(a.size()).append('\t')
                .append(String.format(Locale.US, "%.4f", a.lat())).append('\t')
                .append(String.format(Locale.US, "%.4f", a.lon())).append('\n');
        }
        Path target = Path.of(args[0]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, out, StandardCharsets.UTF_8);
        long[] counts = {0, 0, 0};
        for (Airport a : airports) counts["LMS".indexOf(a.size())]++;
        System.out.printf("wrote %s: %d airports (%d large, %d medium, %d small), %d bytes%n",
            target, airports.size(), counts[0], counts[1], counts[2], Files.size(target));
    }

    static byte[] download() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(HttpRequest.newBuilder(URI.create(SOURCE_URL)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + SOURCE_URL);
        return response.body();
    }

    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** RFC 4180: quoted fields may hold commas, doubled quotes and line breaks. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }
}
