package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.PanelLayout;
import com.volcanicmg.mapsketch.render.PanelLayout.Rect;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Offline mock of the toolbar and the full properties panel with the real vanilla font and widget sprites (read from
 * the Minecraft client jar at run time), at GUI scale 2 and 3 ({@code ./gradlew panelPreview -Pout=...}). Rows mirror
 * {@code EditorUi.panel()} with every optional section shown; drawing mirrors {@code EditorUi}'s button states.
 */
public final class PanelPreview {
    private static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFFFFFFFF};
    private static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFF1E1E1E};

    public static void main(String[] args) throws Exception {
        // Two 480x620 GUI-pixel screens: GUI scale 2 (no FTB Teams) and GUI scale 3 (FTB Teams).
        int sw = 480, sh = 620;
        BufferedImage img = new BufferedImage(sw * 2 + sw * 3, sh * 3, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x6E8B4E));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int k = 0; k < 2; k++) {
            McAssets a = new McAssets(g, k + 2);
            screen(a, sw, k == 1);
            g.translate(sw * 2, 0);
        }
        g.dispose();
        File out = new File(args[0]);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    private static void screen(McAssets a, int sw, boolean teams) throws Exception {
        a.shadowText("GUI scale " + a.scale + (teams ? ", FTB Teams" : ", no FTB Teams (Team disabled)"), 4, 4, 0xFFFFFFFF);
        String[] tools = {"hand", "select", "rectangle", "diamond", "ellipse", "arrow", "line", "pen", "text", "stamp", "eraser", "laser"};
        int s = PanelLayout.TOOL, gap = PanelLayout.TOOL_GAP, n = tools.length + 1;
        int x = sw / 2 - (n * (s + gap) + gap * 2) / 2, y = 24, end = x + s + gap * 3 + tools.length * (s + gap) - gap;
        panelFrame(a, x - 4, y - 4, end - x + 8, s + 8);
        button(a, x, y, s, s, "#draw", true, false, false);
        x += s + gap * 3;
        for (String t : tools) {
            button(a, x, y, s, s, "#" + t, t.equals("select"), false, t.equals("rectangle"));
            x += s + gap;
        }
        int px = 4, py = 24 + s + 8;
        PanelLayout m = new PanelLayout(px, py);
        rows(a, m, teams, false);
        panelFrame(a, px, py, PanelLayout.WIDTH, m.bottom() - py);
        rows(a, new PanelLayout(px, py), teams, true);
    }

    /** The panel's rows, as EditorUi builds them with every section present; {@code draw} false only measures. */
    private static void rows(McAssets a, PanelLayout lay, boolean teams, boolean draw) throws Exception {
        swatches(a, lay, "Stroke", STROKES, 0xFF1E1E1E, draw);
        swatches(a, lay, "Background", FILLS, 0, draw);
        row(a, lay, "Fill", new String[]{"#fill_none", "#fill_hachure", "#fill_cross", "#fill_solid"}, 1, -1, draw);
        row(a, lay, "Stroke width", new String[]{"#width_thin", "#width_bold", "#width_extra"}, 0, -1, draw);
        row(a, lay, "Stroke style", new String[]{"#style_solid", "#style_dashed", "#style_dotted"}, 0, -1, draw);
        row(a, lay, "Sloppiness", new String[]{"#slop_0", "#slop_1", "#slop_2"}, 1, -1, draw);
        row(a, lay, "Edges", new String[]{"#edge_sharp", "#edge_round"}, 1, -1, draw);
        Rect sl = lay.slider();
        if (draw) slider(a, sl.x(), sl.y(), sl.w(), sl.h(), 1.0, "Opacity: 100%");
        row(a, lay, "Start arrowhead", new String[]{"#head_none@m", "#head_arrow@m", "#head_triangle@m", "#head_bar@m", "#head_dot@m"}, 0, -1, draw);
        row(a, lay, "End arrowhead", new String[]{"#head_none", "#head_arrow", "#head_triangle", "#head_bar", "#head_dot"}, 1, -1, draw);
        label(a, lay, "Stamp", draw);
        Rect st = lay.wide();
        if (draw) {
            button(a, st.x(), st.y(), st.w(), st.h(), "", false, false, false);
            a.icon("stamp", st.x() + 2, st.y() + 2, 16, 16, 0xFFE03131);
            a.text("Village", st.x() + 22, st.y() + 6, PanelLayout.INK);
        }
        row(a, lay, "Text size", new String[]{"S", "M", "L", "XL"}, 1, -1, draw);
        row(a, lay, "Text align", new String[]{"#align_left", "#align_center", "#align_right"}, 0, -1, draw);
        row(a, lay, "Visible to", new String[]{"Only me", "Team", "Everyone"}, 2, teams ? -1 : 1, draw);
        row(a, lay, "Layers", new String[]{"#to_back", "#backward", "#forward", "#to_front"}, -1, -1, draw);
        row(a, lay, "Actions", new String[]{"#duplicate", "#delete", "#group", "#ungroup", "#lock"}, -1, -1, draw);
    }

    private static void label(McAssets a, PanelLayout lay, String s, boolean draw) {
        int y = lay.label();
        if (draw) a.text(s, lay.innerX() + 1, y, PanelLayout.LABEL);
    }

    private static void swatches(McAssets a, PanelLayout lay, String name, int[] colors, int current, boolean draw) throws Exception {
        label(a, lay, name, draw);
        Rect[] r = lay.swatches(colors.length);
        if (!draw) return;
        for (int i = 0; i < colors.length; i++) swatch(a, r[i].x(), r[i].y(), r[i].w(), r[i].h(), colors[i], colors[i] == current);
        Rect c = r[colors.length];
        button(a, c.x(), c.y() - 2, c.w(), c.h() + 4, "", false, false, false);
        swatch(a, c.x() + 3, c.y() + 1, c.w() - 6, c.h() - 2, current, false);
    }

    private static void row(McAssets a, PanelLayout lay, String name, String[] labels, int active, int disabled, boolean draw) throws Exception {
        label(a, lay, name, draw);
        Rect[] r = lay.row(labels.length);
        if (!draw) return;
        for (int i = 0; i < labels.length; i++)
            button(a, r[i].x(), r[i].y(), r[i].w(), r[i].h(), labels[i], i == active, i == disabled, labels[i].equals("#width_bold"));
    }

    /** Inventory-style frame: black outline with cut corners, 2 px white / #555555 bevel, #C6C6C6 body. */
    static void panelFrame(McAssets a, int x, int y, int w, int h) {
        a.fill(x + 1, y + 1, w - 2, h - 2, PanelLayout.PANEL);
        a.fill(x + 2, y, w - 4, 1, PanelLayout.OUTLINE);
        a.fill(x + 2, y + h - 1, w - 4, 1, PanelLayout.OUTLINE);
        a.fill(x, y + 2, 1, h - 4, PanelLayout.OUTLINE);
        a.fill(x + w - 1, y + 2, 1, h - 4, PanelLayout.OUTLINE);
        a.fill(x + 1, y + 1, 1, 1, PanelLayout.OUTLINE);
        a.fill(x + w - 2, y + 1, 1, 1, PanelLayout.OUTLINE);
        a.fill(x + 1, y + h - 2, 1, 1, PanelLayout.OUTLINE);
        a.fill(x + w - 2, y + h - 2, 1, 1, PanelLayout.OUTLINE);
        a.fill(x + 2, y + 1, w - 5, 2, PanelLayout.HIGHLIGHT);
        a.fill(x + 1, y + 2, 2, h - 5, PanelLayout.HIGHLIGHT);
        a.fill(x + 3, y + h - 3, w - 5, 2, PanelLayout.SHADOW);
        a.fill(x + w - 3, y + 3, 2, h - 5, PanelLayout.SHADOW);
    }

    /** Button states as EditorUi draws them: sprite button / highlighted (hover) / inset slot (selected) / disabled. */
    static void button(McAssets a, int x, int y, int w, int h, String label, boolean selected, boolean disabled, boolean hover) throws Exception {
        if (selected) {
            a.fill(x, y, w, h, PanelLayout.SLOT);
            a.fill(x, y, w - 1, 1, PanelLayout.SLOT_DARK);
            a.fill(x, y, 1, h - 1, PanelLayout.SLOT_DARK);
            a.fill(x + 1, y + h - 1, w - 1, 1, PanelLayout.SLOT_LIGHT);
            a.fill(x + w - 1, y + 1, 1, h - 1, PanelLayout.SLOT_LIGHT);
        } else a.sprite(disabled ? "widget/button_disabled" : hover ? "widget/button_highlighted" : "widget/button", x, y, w, h);
        int ink = disabled ? PanelLayout.INK_DISABLED : selected ? PanelLayout.INK_SELECTED : PanelLayout.INK;
        if (label.startsWith("#")) a.icon(label.substring(1), x, y, w, h, ink);
        else if (!label.isEmpty()) a.text(label, x + (w - a.width(label) + 1) / 2, y + (h - 8) / 2, ink);
    }

    static void swatch(McAssets a, int x, int y, int w, int h, int argb, boolean selected) {
        if (selected) a.fill(x - 2, y - 2, w + 4, h + 4, 0xFFFFFFFF);
        a.fill(x, y, w, h, PanelLayout.SWATCH_BORDER);
        if (argb == 0)
            for (int yy = 1; yy < h - 1; yy++)
                for (int xx = 1; xx < w - 1; xx++) a.fill(x + xx, y + yy, 1, 1, ((xx - 1) / 4 + (yy - 1) / 4) % 2 == 0 ? 0xFFFFFFFF : 0xFFBFBFBF);
        else a.fill(x + 1, y + 1, w - 2, h - 2, argb);
    }

    static void slider(McAssets a, int x, int y, int w, int h, double v, String text) throws Exception {
        a.sprite("widget/slider", x, y, w, h);
        a.sprite("widget/slider_handle", x + (int) (v * (w - 8)), y, 8, h);
        a.shadowText(text, x + (w - a.width(text) + 1) / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }
}
