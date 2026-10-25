package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.render.PanelLayout;
import com.volcanicmg.mapsketch.render.UiIconArt;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the {@link UiIconArt} pixel icons like vanilla sprites: 1 texel = 1 GUI pixel (Minecraft's GUI scale does the
 * rest, always an integer), centred at whole-pixel offsets, one colour.
 */
final class UiIcons {
    /** Ink colours: normal, selected (on the inset slot), disabled. */
    static final int NORMAL = PanelLayout.INK, SELECTED = PanelLayout.INK_SELECTED, DISABLED = PanelLayout.INK_DISABLED;

    private UiIcons() {
    }

    static boolean has(String name) {
        return UiIconArt.has(name);
    }

    /** Draws icon {@code name} ("@m" = mirrored) centred in the box; runs of ink become one fill each. */
    static void draw(GuiGraphics g, String name, int x, int y, int w, int h, int color) {
        if (!UiIconArt.has(name)) return;
        int n = UiIconArt.SIZE, ox = x + (w - n) / 2, oy = y + (h - n) / 2;
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) {
                if (!UiIconArt.ink(name, c, r)) continue;
                int e = c;
                while (e + 1 < n && UiIconArt.ink(name, e + 1, r)) e++;
                g.fill(ox + c, oy + r, ox + e + 1, oy + r + 1, color);
                c = e;
            }
    }
}
