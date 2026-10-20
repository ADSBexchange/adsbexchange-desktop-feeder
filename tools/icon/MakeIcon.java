import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the app icon (app/src/main/composeResources/drawable/icon.svg: the phone feeder's green
 * stealth plane with signal arcs, on black) at the sizes Windows uses, into one .ico file for the
 * installer, the Start menu and the app's .exe.
 *
 * Usage: java tools/icon/MakeIcon.java app/icons/icon.ico
 */
public class MakeIcon {
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 256};

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java MakeIcon.java <out.ico>");
            System.exit(2);
        }
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", png);
            pngs.add(png.toByteArray());
        }
        Path out = Path.of(args[0]);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, ico(pngs));
    }

    /** The SVG's drawing, in its own coordinates (viewBox 24 20 60 60). */
    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 60.0, size / 60.0);
        g.translate(-24, -20);

        g.setColor(Color.BLACK);
        g.fill(new RoundRectangle2D.Double(24, 20, 60, 60, 24, 24));
        fill(g, 0x1E9E60, 54, 42, 33, 68, 37.5, 75, 47, 69);
        fill(g, 0x136B41, 54, 42, 75, 68, 70.5, 75, 61, 69);
        fill(g, 0x29D682, 54, 42, 48.5, 60, 54, 73);
        fill(g, 0x1A8651, 54, 42, 59.5, 60, 54, 73);

        // Three arcs over the nose, centred on (54, 43), from 45 to 135 degrees.
        g.setColor(new Color(0x29D682));
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double r : new double[] {8, 14, 20}) {
            g.draw(new Arc2D.Double(54 - r, 43 - r, 2 * r, 2 * r, 45, 90, Arc2D.OPEN));
        }
        g.dispose();
        return img;
    }

    static void fill(Graphics2D g, int rgb, double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        g.setColor(new Color(rgb));
        g.fill(p);
    }

    /** An .ico holding PNG images (supported since Windows Vista). */
    static byte[] ico(List<byte[]> pngs) {
        int header = 6 + 16 * pngs.size();
        int total = header + pngs.stream().mapToInt(p -> p.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());
        int offset = header;
        for (int i = 0; i < pngs.size(); i++) {
            int size = SIZES[i];
            b.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size));
            b.put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32);
            b.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) b.put(p);
        return b.array();
    }
}
