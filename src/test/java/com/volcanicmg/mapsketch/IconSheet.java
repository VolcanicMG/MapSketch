package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.UiIconArt;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Preview sheet of the toolbar/panel icons at 8x, normal and selected ({@code ./gradlew iconSheet -Pout=...}). */
public final class IconSheet {
    private static final int SCALE = 8, CELL = 16 * SCALE + 16;

    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<>(UiIconArt.names());
        int cols = 6;
        BufferedImage img = new BufferedImage(cols * CELL * 2, (names.size() + cols - 1) / cols * (CELL + 18), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xF8F9FA));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int i = 0; i < names.size(); i++) {
            for (int sel = 0; sel < 2; sel++) {
                int ox = (i % cols * 2 + sel) * CELL + 8, oy = i / cols * (CELL + 18) + 8;
                if (sel == 1) {
                    g.setColor(new Color(0xC9D7FF));
                    g.fillRect(ox - 4, oy - 4, 16 * SCALE + 8, 16 * SCALE + 8);
                }
                g.setColor(new Color(sel == 1 ? 0x364FC7 : 0x2B2B2B));
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++)
                        if (UiIconArt.ink(names.get(i), x, y)) g.fillRect(ox + x * SCALE, oy + y * SCALE, SCALE, SCALE);
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString(names.get(i), i % cols * 2 * CELL + 8, i / cols * (CELL + 18) + 16 * SCALE + 24);
        }
        g.dispose();
        File out = new File(args[0]);
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
