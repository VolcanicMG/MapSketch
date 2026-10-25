package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.PanelLayout;
import com.volcanicmg.mapsketch.render.UiIconArt;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Icon contact sheet with the real vanilla button sprites and font, on the inventory-grey panel: each icon in every
 * button state, at 1:1 and at 3x (nearest) ({@code ./gradlew iconSheet -Pout=...}).
 */
public final class IconSheet {
    public static void main(String[] args) throws Exception {
        List<String> names = new ArrayList<>(UiIconArt.names());
        int cols = 4, cellW = 150, cellH = 24, w = cols * cellW + 8, h = (names.size() + cols - 1) / cols * cellH + 20;
        BufferedImage img = new BufferedImage(w * 4 + 8, h * 3, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        McAssets one = new McAssets(g, 1), three = new McAssets(g, 3);
        three.fill(0, 0, img.getWidth(), img.getHeight(), 0xFF2B2B2B);
        draw(one, names, cols, cellW, cellH, w, h);
        g.translate(w + 8, 0);
        draw(three, names, cols, cellW, cellH, w, h);
        g.dispose();
        BufferedImage out = img.getSubimage(0, 0, w + 8 + w * 3, h * 3);
        File f = new File(args[0]);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        ImageIO.write(out, "png", f);
        System.out.println("wrote " + f);
    }

    private static void draw(McAssets a, List<String> names, int cols, int cellW, int cellH, int w, int h) throws Exception {
        PanelPreview.panelFrame(a, 0, 0, w, h);
        a.text("normal  hover  selected  disabled", 6, 6, PanelLayout.LABEL);
        for (int i = 0; i < names.size(); i++) {
            int x = 6 + i % cols * cellW, y = 18 + i / cols * cellH;
            for (int s = 0; s < 4; s++) PanelPreview.button(a, x + s * 22, y, 20, 20, "#" + names.get(i), s == 2, s == 3, s == 1);
            a.text(names.get(i), x + 90, y + 6, PanelLayout.LABEL);
        }
    }
}
