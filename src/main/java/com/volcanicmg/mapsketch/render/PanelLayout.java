package com.volcanicmg.mapsketch.render;

/**
 * Geometry and colours of the properties panel and toolbar in whole GUI pixels (Minecraft's GUI scale does the rest),
 * styled like vanilla container screens. Pure, so the client ({@code EditorUi}) and the offline preview
 * ({@code PanelPreview}) lay out the same rows.
 * <p>
 * Use: one instance per frame, then for each section {@link #label()} followed by a row ({@link #row},
 * {@link #swatches}, {@link #slider}, {@link #wide}); {@link #bottom()} gives the content's end.
 */
public final class PanelLayout {
    /** Inventory-style panel: body, top-left highlight, bottom-right shadow, outline; label text like container titles. */
    public static final int PANEL = 0xFFC6C6C6, HIGHLIGHT = 0xFFFFFFFF, SHADOW = 0xFF555555, OUTLINE = 0xFF000000,
            LABEL = 0xFF404040;
    /** Icon / button-text ink: normal and hover, selected (on the inset slot), disabled. */
    public static final int INK = 0xFF3F3F3F, INK_SELECTED = 0xFFFFFFFF, INK_DISABLED = 0xFFA0A0A0;
    /** Selected toggle: an inset like an inventory slot. */
    public static final int SLOT = 0xFF8B8B8B, SLOT_DARK = 0xFF373737, SLOT_LIGHT = 0xFFFFFFFF;
    public static final int SWATCH_BORDER = 0xFF373737;
    /** Sizes: panel width (vanilla inventory width), padding, gaps, button / swatch / slider / toolbar button. */
    public static final int WIDTH = 176, PAD = 7, GAP = 2, SECTION = 4, LABEL_GAP = 2, BUTTON_H = 20, SWATCH = 16,
            SLIDER_H = 20, TOOL = 20, TOOL_GAP = 2;
    /** Height of a section label (the vanilla font's line). */
    public static final int LABEL_H = 9;

    public record Rect(int x, int y, int w, int h) {
        public boolean hit(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final int x;
    private int y;

    /** @param x panel left; @param y panel content top (already shifted by any scroll) */
    public PanelLayout(int x, int y) {
        this.x = x;
        this.y = y + PAD;
    }

    public int width() {
        return WIDTH;
    }

    public int innerX() {
        return x + PAD;
    }

    public int innerW() {
        return WIDTH - 2 * PAD;
    }

    /** @return the y of a section label; advances past it */
    public int label() {
        int ly = y;
        y += LABEL_H + LABEL_GAP;
        return ly;
    }

    /** @return {@code n} equal-width buttons in one row, centred in the inner width */
    public Rect[] row(int n) {
        int w = (innerW() - (n - 1) * GAP) / n, x0 = innerX() + (innerW() - n * w - (n - 1) * GAP) / 2;
        Rect[] r = new Rect[n];
        for (int i = 0; i < n; i++) r[i] = new Rect(x0 + i * (w + GAP), y, w, BUTTON_H);
        y += BUTTON_H + SECTION;
        return r;
    }

    /** @return {@code n} colour swatches, then the current-colour chip filling the rest of the row after a gap */
    public Rect[] swatches(int n) {
        Rect[] r = new Rect[n + 1];
        for (int i = 0; i < n; i++) r[i] = new Rect(innerX() + i * (SWATCH + GAP), y, SWATCH, SWATCH);
        int cx = innerX() + n * (SWATCH + GAP) + GAP * 2;
        r[n] = new Rect(cx, y, innerX() + innerW() - cx, SWATCH);
        y += SWATCH + SECTION;
        return r;
    }

    /** @return a full-width slider */
    public Rect slider() {
        Rect r = new Rect(innerX(), y, innerW(), SLIDER_H);
        y += r.h() + SECTION;
        return r;
    }

    /** @return one full-width button */
    public Rect wide() {
        Rect r = new Rect(innerX(), y, innerW(), BUTTON_H);
        y += r.h() + SECTION;
        return r;
    }

    /** @return the content's bottom edge (last row plus padding) */
    public int bottom() {
        return y - SECTION + PAD;
    }
}
