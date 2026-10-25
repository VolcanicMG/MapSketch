package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.PixelArt;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Preview sheet of the pixel stamps ({@code ./gradlew stampSheet -Pout=... [-Pres=<minecraft client jar>]}): each
 * symbol at 8x in three tints, then the vanilla map icons cropped as drawn (read from the client jar in the Gradle cache
 * by default; nothing is committed). Not part of {@code check}.
 */
public final class StampSheet {
    private static final int SCALE = 8, CELL = 16 * SCALE + 24;
    private static final int[] TINTS = {0xE03131, 0x1971C2, 0x1E1E1E};

    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<>(PixelArt.names());
        List<String> decos = new ArrayList<>(com.volcanicmg.mapsketch.model.Rules.DECORATIONS);
        int cols = 8, symRows = (names.size() + cols - 1) / cols * TINTS.length, decoRows = (decos.size() + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * CELL, (symRows + decoRows) * CELL + 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF4F1E8));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int row = 0;
        for (int tint : TINTS) {
            int[] tones = PixelArt.tones(tint);
            for (int i = 0; i < names.size(); i++) {
                int cx = i % cols * CELL + 12, cy = (row + i / cols) * CELL + 6;
                String[] grid = PixelArt.grid(names.get(i));
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++) {
                        int t = PixelArt.tone(grid, x, y);
                        if (t < 0) continue;
                        g.setColor(new Color(tones[t]));
                        g.fillRect(cx + x * SCALE, cy + y * SCALE, SCALE, SCALE);
                    }
                g.setColor(Color.DARK_GRAY);
                g.drawString(names.get(i), cx, cy + 16 * SCALE + 14);
            }
            row += (names.size() + cols - 1) / cols;
        }
        String res = args.length > 1 && !args[1].isEmpty() ? args[1]
                : System.getProperty("user.home") + "/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar";
        if (new File(res).isFile()) try (ZipFile zip = new ZipFile(res)) {
            for (int i = 0; i < decos.size(); i++) {
                var entry = zip.getEntry("assets/minecraft/textures/map/decorations/" + decos.get(i) + ".png");
                if (entry == null) continue;
                BufferedImage icon;
                try (InputStream in = zip.getInputStream(entry)) {
                    icon = ImageIO.read(in);
                }
                int cx = i % cols * CELL + 12, cy = (row + i / cols) * CELL + 6;
                // Cropped to the square around its opaque pixels, as StampArt draws it; whole-texel scale.
                int x0 = 99, y0 = 99, x1 = -1, y1 = -1;
                for (int y = 0; y < icon.getHeight(); y++)
                    for (int x = 0; x < icon.getWidth(); x++)
                        if ((icon.getRGB(x, y) >>> 24) != 0) {
                            x0 = Math.min(x0, x);
                            y0 = Math.min(y0, y);
                            x1 = Math.max(x1, x);
                            y1 = Math.max(y1, y);
                        }
                int[] q = PixelArt.squareAround(x0, y0, x1, y1, icon.getWidth(), icon.getHeight());
                int k = 16 * SCALE / q[2];
                g.drawImage(icon.getSubimage(q[0], q[1], q[2], q[2]), cx, cy, q[2] * k, q[2] * k, null);
                g.setColor(Color.DARK_GRAY);
                g.drawString("deco:" + decos.get(i), cx, cy + 16 * SCALE + 14);
            }
        }
        g.dispose();
        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
