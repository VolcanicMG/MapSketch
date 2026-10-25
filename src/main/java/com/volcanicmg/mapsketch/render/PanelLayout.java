package com.volcanicmg.mapsketch.render;

/**
 * Geometry and colours of the properties panel and toolbar, in GUI pixels. Pure, so the client ({@code EditorUi})
 * and the offline preview ({@code PanelPreview}) lay out the same rows. Sizes are at UI scale 1; text (the vanilla
 * font, 9 px line) does not scale.
 * <p>
 * Use: one instance per frame, then for each section {@link #label()} followed by a row ({@link #row},
 * {@link #swatches}, {@link #slider}, {@link #wide}); {@link #bottom()} gives the content's end.
 */
public final class PanelLayout {
    public static final int PANEL_BG = 0xF5FFFFFF, PANEL_BORDER = 0xFFDEE2E6, LABEL = 0xFF5F6368, TEXT = 0xFF1E1E1E,
            BUTTON = 0xFFF1F3F5, BUTTON_HOVER = 0xFFE3E6EA, BUTTON_DISABLED = 0xFFF8F9FA, SELECTED_BG = 0xFFE5EBFF,
            ACCENT = 0xFF4C6EF5, TEXT_SELECTED = 0xFF364FC7, TEXT_DISABLED = 0xFFADB5BD, SWATCH_BORDER = 0x33000000,
            SCROLLBAR = 0x40000000;
    /** Unscaled sizes: panel width, padding, gap between buttons, space after a row, button / swatch / slider / tool. */
    public static final int WIDTH = 184, PAD = 8, GAP = 4, SECTION = 8, LABEL_GAP = 3, BUTTON_H = 22, SWATCH = 18, SLIDER_H = 12,
            TOOL = 24, TOOL_GAP = 2;
    /** Height of a section label (the vanilla font's line). */
    public static final int LABEL_H = 9;

    public record Rect(int x, int y, int w, int h) {
        public boolean hit(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final double scale;
    private final int x;
    private int y;

    /** @param x panel left; @param y panel content top (already shifted by any scroll) */
    public PanelLayout(double scale, int x, int y) {
        this.scale = scale;
        this.x = x;
        this.y = y + u(PAD);
    }

    public int u(double px) {
        return (int) Math.round(px * scale);
    }

    public int width() {
        return u(WIDTH);
    }

    public int innerX() {
        return x + u(PAD);
    }

    public int innerW() {
        return width() - 2 * u(PAD);
    }

    /** @return the y of a section label; advances past it */
    public int label() {
        int ly = y;
        y += LABEL_H + u(LABEL_GAP);
        return ly;
    }

    /** @return {@code n} equal-width buttons in one row */
    public Rect[] row(int n) {
        int gap = u(GAP), w = (innerW() - (n - 1) * gap) / n, h = u(BUTTON_H);
        Rect[] r = new Rect[n];
        for (int i = 0; i < n; i++) r[i] = new Rect(innerX() + i * (w + gap), y, w, h);
        y += h + u(SECTION);
        return r;
    }

    /** @return {@code n} colour swatches, then the current-colour chip filling the rest of the row after a divider gap */
    public Rect[] swatches(int n) {
        int s = u(SWATCH), gap = u(GAP);
        Rect[] r = new Rect[n + 1];
        for (int i = 0; i < n; i++) r[i] = new Rect(innerX() + i * (s + gap), y, s, s);
        int cx = innerX() + n * (s + gap) + u(GAP) * 2;
        r[n] = new Rect(cx, y, innerX() + innerW() - cx, s);
        y += s + u(SECTION);
        return r;
    }

    /** @return a full-width slider track box */
    public Rect slider() {
        Rect r = new Rect(innerX(), y, innerW(), u(SLIDER_H));
        y += r.h() + u(SECTION);
        return r;
    }

    /** @return one full-width button */
    public Rect wide() {
        Rect r = new Rect(innerX(), y, innerW(), u(BUTTON_H));
        y += r.h() + u(SECTION);
        return r;
    }

    /** @return the content's bottom edge (last row plus padding) */
    public int bottom() {
        return y - u(SECTION) + u(PAD);
    }
}
