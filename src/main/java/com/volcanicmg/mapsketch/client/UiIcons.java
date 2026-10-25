package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.render.UiIconArt;
import net.minecraft.client.gui.GuiGraphics;

/** Draws the {@link UiIconArt} icons crisply: whole-pixel scale, centred, one colour. */
final class UiIcons {
    /** Ink colours: normal, selected (the accent's darker shade), disabled. */
    static final int NORMAL = 0xFF2B2B2B, SELECTED = 0xFF364FC7, DISABLED = 0xFF9AA0A6;

    private UiIcons() {
    }

    static boolean has(String name) {
        return UiIconArt.has(name);
    }

    /** Draws icon {@code name} centred in the box at the largest whole-pixel scale that fits. */
    static void draw(GuiGraphics g, String name, int x, int y, int w, int h, int color) {
        if (!UiIconArt.has(name)) return;
        int n = UiIconArt.SIZE, px = Math.max(1, Math.min(w, h) / n), size = px * n;
        int ox = x + (w - size) / 2, oy = y + (h - size) / 2;
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++)
                if (UiIconArt.ink(name, c, r)) g.fill(ox + c * px, oy + r * px, ox + (c + 1) * px, oy + (r + 1) * px, color);
    }
}
