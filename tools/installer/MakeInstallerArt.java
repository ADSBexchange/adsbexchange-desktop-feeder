import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the installer's ADS-B Exchange artwork as the 24-bit BMPs WiX's standard dialogs use:
 *
 *   dialog.bmp  493 x 312  welcome and finish pages. WiX writes black text from about x = 180,
 *                          so the brand panel (black, logo, name) fills the left and the rest is white.
 *   banner.bmp  493 x 58   top strip of the other pages. WiX writes black text on the left, so the
 *                          logo sits on the right of a white strip with a green rule underneath.
 *
 * Colours and font are the app's (adsbexchange.com): black, #29D682 green, DM Sans.
 *
 * Usage: java tools/installer/MakeInstallerArt.java <out dir> <DM Sans font dir>
 */
public class MakeInstallerArt {
    static final Color GREEN = new Color(0x29D682);
    static final Color TEXT = new Color(0xF0F0F0);
    static final Color SECONDARY = new Color(0xA0A0A0);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java MakeInstallerArt.java <out dir> <font dir>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        Font bold = Font.createFont(Font.TRUETYPE_FONT, new File(args[1], "dm_sans_bold.ttf"));
        Font medium = Font.createFont(Font.TRUETYPE_FONT, new File(args[1], "dm_sans_medium.ttf"));

        BufferedImage dialog = canvas(493, 312);
        Graphics2D g = dialog.createGraphics();
        smooth(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 493, 312);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 164, 312);
        g.setColor(GREEN);
        g.fillRect(164, 0, 3, 312);
        drawPlane(g, 82, 92, 104);
        centred(g, "ADS-B Exchange", bold.deriveFont(17f), TEXT, 82, 186);
        centred(g, "Desktop Feeder", medium.deriveFont(14f), GREEN, 82, 208);
        centred(g, "Feed from your PC", medium.deriveFont(11f), SECONDARY, 82, 290);
        g.dispose();
        ImageIO.write(dialog, "bmp", out.resolve("dialog.bmp").toFile());

        BufferedImage banner = canvas(493, 58);
        g = banner.createGraphics();
        smooth(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 493, 58);
        g.setColor(Color.BLACK);
        g.fillRoundRect(436, 6, 44, 44, 10, 10);
        drawPlane(g, 458, 28, 40);
        g.setColor(GREEN);
        g.fillRect(0, 55, 493, 3);
        g.dispose();
        ImageIO.write(banner, "bmp", out.resolve("banner.bmp").toFile());
    }

    static BufferedImage canvas(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); // 24-bit, as WiX expects
    }

    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    static void centred(Graphics2D g, String s, Font f, Color c, int cx, int baseline) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f, baseline);
    }

    /**
     * The app icon's plane and signal arcs (icon.svg, drawn in its 60-unit box from (24, 20)),
     * centred on (cx, cy) and scaled to size pixels, without the icon's black square.
     */
    static void drawPlane(Graphics2D g, double cx, double cy, double size) {
        AffineTransform saved = g.getTransform();
        g.translate(cx, cy);
        g.scale(size / 60.0, size / 60.0);
        g.translate(-54, -50);
        fill(g, 0x1E9E60, 54, 42, 33, 68, 37.5, 75, 47, 69);
        fill(g, 0x136B41, 54, 42, 75, 68, 70.5, 75, 61, 69);
        fill(g, 0x29D682, 54, 42, 48.5, 60, 54, 73);
        fill(g, 0x1A8651, 54, 42, 59.5, 60, 54, 73);
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double r : new double[] {8, 14, 20}) {
            g.draw(new Arc2D.Double(54 - r, 43 - r, 2 * r, 2 * r, 45, 90, Arc2D.OPEN));
        }
        g.setTransform(saved);
    }

    static void fill(Graphics2D g, int rgb, double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        g.setColor(new Color(rgb));
        g.fill(p);
    }
}
