package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.IconAtlas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Contact sheet of the icon atlas as the client draws it: box-filtered mip levels, bilinear, tinted, at 65% of
 * 16/20/32/40/60 px buttons on a light and a dark background ({@code ./gradlew iconSheet -Pout=...}).
 */
public final class IconSheet {
    static final String RES = "src/main/resources/assets/mapsketch/";
    private static final int[] BUTTONS = {16, 20, 32, 40, 60};

    public static void main(String[] args) throws Exception {
        Map<String, IconAtlas.Icon> icons = IconAtlas.parse(Files.readAllLines(new File(RES + "icons/atlas.txt").toPath()));
        BufferedImage[] mips = mips(ImageIO.read(new File(RES + "textures/gui/icons.png")));
        int rowW = 0;
        for (int b : BUTTONS) rowW += b + 6;
        int nameW = 100, half = nameW + rowW * 2 + 10, rowH = BUTTONS[BUTTONS.length - 1] + 6;
        BufferedImage img = new BufferedImage(half * 2, 12 + icons.size() * rowH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF8F9FA));
        g.fillRect(0, 0, half, img.getHeight());
        g.setColor(new Color(0x232329));
        g.fillRect(half, 0, half, img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int y = 6;
        for (var en : icons.entrySet()) {
            for (int dark = 0; dark < 2; dark++) {
                int x = dark * half + 6;
                g.setColor(dark == 0 ? Color.DARK_GRAY : Color.LIGHT_GRAY);
                g.drawString(en.getKey(), x, y + rowH / 2);
                x += nameW;
                // Normal then selected, each at every button size.
                for (int sel = 0; sel < 2; sel++)
                    for (int b : BUTTONS) {
                        int by = y + (rowH - 6 - b) / 2;
                        g.setColor(new Color(sel == 1 ? (dark == 0 ? 0xE5EBFF : 0x403E6A) : (dark == 0 ? 0xECECF4 : 0x2E2D39)));
                        g.fillRect(x, by, b, b);
                        int tint = sel == 1 ? (dark == 0 ? 0xFF364FC7 : 0xFFA8B8FF) : (dark == 0 ? 0xFF2B2B2B : 0xFFE3E3E8);
                        draw(g, mips, en.getValue(), x, by, b, b, tint);
                        x += b + 6;
                    }
            }
            y += rowH;
        }
        g.dispose();
        File out = new File(args[0]);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    /** Same sizing as the client's UiIcons.draw: 65% of the box's short side, widened for wide icons, centred. */
    static void draw(Graphics2D g, BufferedImage[] mips, IconAtlas.Icon ic, double x, double y, double w, double h, int argb) {
        double s = Math.min(w, h) * 0.65, sw = Math.min(s * Math.max(1, ic.aspect()), w * 0.85);
        s = Math.max(s, sw);
        int px = (int) Math.round(s);
        int level = 0;
        while (level < IconAtlas.MIP_LEVELS && (IconAtlas.CELL >> (level + 1)) >= px) level++;
        BufferedImage m = mips[level];
        int c = IconAtlas.CELL >> level;
        BufferedImage cell = m.getSubimage(ic.cellX() >> level, ic.cellY() >> level, c, c);
        BufferedImage tinted = new BufferedImage(c, c, BufferedImage.TYPE_INT_ARGB);
        for (int yy = 0; yy < c; yy++)
            for (int xx = 0; xx < c; xx++) {
                int a = (cell.getRGB(xx, yy) >>> 24) * (argb >>> 24) / 255;
                tinted.setRGB(xx, yy, a << 24 | argb & 0xFFFFFF);
            }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int ox = (int) Math.round(x + (w - px) / 2), oy = (int) Math.round(y + (h - px) / 2);
        g.drawImage(tinted, ox, oy, px, px, null);
    }

    /** Box-filtered mip chain, the same filter the client uploads. */
    static BufferedImage[] mips(BufferedImage base) {
        BufferedImage[] out = new BufferedImage[IconAtlas.MIP_LEVELS + 1];
        out[0] = base;
        for (int l = 1; l < out.length; l++) {
            BufferedImage p = out[l - 1], n = new BufferedImage(p.getWidth() / 2, p.getHeight() / 2, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < n.getHeight(); y++)
                for (int x = 0; x < n.getWidth(); x++) {
                    int a = (p.getRGB(2 * x, 2 * y) >>> 24) + (p.getRGB(2 * x + 1, 2 * y) >>> 24)
                            + (p.getRGB(2 * x, 2 * y + 1) >>> 24) + (p.getRGB(2 * x + 1, 2 * y + 1) >>> 24);
                    n.setRGB(x, y, (a + 2) / 4 << 24 | 0xFFFFFF);
                }
            out[l] = n;
        }
        return out;
    }
}
