package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.render.IconAtlas;
import com.volcanicmg.mapsketch.render.PanelLayout;
import com.volcanicmg.mapsketch.render.PanelLayout.Rect;
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
 * Offline mock of the toolbar and the full properties panel at UI scale 1 and 2, drawn with the real icon atlas and
 * {@link PanelLayout}, at GUI scale 2 ({@code ./gradlew panelPreview -Pout=...}). Text uses AWT's sans font as a
 * stand-in for the vanilla font; it is for reviewing layout, not pixels. Rows mirror {@code EditorUi.panel()} with
 * every optional section shown.
 */
public final class PanelPreview {
    private static final int GUI = 2;
    private static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFFFFFFFF};
    private static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFF1E1E1E};
    private static Graphics2D g;
    private static Map<String, IconAtlas.Icon> icons;
    private static BufferedImage[] mips;
    private static PanelLayout lay;
    private static String hoverId;

    public static void main(String[] args) throws Exception {
        icons = IconAtlas.parse(Files.readAllLines(new File(IconSheet.RES + "icons/atlas.txt").toPath()));
        mips = IconSheet.mips(ImageIO.read(new File(IconSheet.RES + "textures/gui/icons.png")));
        // Each column is a 480 (uiScale 1) / 840 (uiScale 2) GUI px wide screen.
        int[] colW = {480, 840};
        BufferedImage img = new BufferedImage((colW[0] + colW[1]) * GUI, 1460 * GUI, BufferedImage.TYPE_INT_ARGB);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x8DA36B));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8 * GUI));
        for (int c = 0; c < 2; c++) {
            double scale = c + 1;
            int ox = c * colW[0];
            text("uiScale " + (int) scale + (c == 0 ? ", no FTB Teams (Team disabled)" : ", FTB Teams"), ox + 6, 4, 0xFF000000);
            toolbar(ox + colW[c] / 2, scale);
            panel(ox + 4, 24 + (int) Math.round(PanelLayout.TOOL * scale) + (int) Math.round(8 * scale), scale, c == 1);
        }
        g.dispose();
        File out = new File(args[0]);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    private static void toolbar(int cx, double scale) {
        int s = (int) Math.round(PanelLayout.TOOL * scale), gap = (int) Math.round(PanelLayout.TOOL_GAP * scale), n = 13;
        String[] tools = {"hand", "select", "rectangle", "diamond", "ellipse", "arrow", "line", "pen", "text", "stamp", "eraser", "laser"};
        int x = cx - (n * (s + gap) + gap * 2) / 2, y = 24, p = (int) Math.round(3 * scale);
        int end = x + s + gap * 3 + tools.length * (s + gap) - gap;
        fill(x - p, y - p, end - x + 2 * p, s + 2 * p, PanelLayout.PANEL_BG);
        outline(x - p, y - p, end - x + 2 * p, s + 2 * p, PanelLayout.PANEL_BORDER);
        button(new Rect(x, y, s, s), "#draw", true, false, false);
        x += s + gap * 3;
        for (String t : tools) {
            button(new Rect(x, y, s, s), "#" + t, t.equals("select"), false, false);
            x += s + gap;
        }
    }

    private static void panel(int px, int py, double scale, boolean teams) {
        lay = new PanelLayout(scale, px, py);
        // Measure first (same rows), then draw over the background.
        Runnable rows = () -> {
            swatches("Stroke", STROKES, 0xFF1E1E1E);
            swatches("Background", FILLS, 0);
            row("Fill", new String[]{"#fill_none", "#fill_hachure", "#fill_cross", "#fill_solid"}, 1, -1);
            row("Stroke width", new String[]{"#width_thin", "#width_bold", "#width_extra"}, 0, -1);
            row("Stroke style", new String[]{"#style_solid", "#style_dashed", "#style_dotted"}, 0, -1);
            row("Sloppiness", new String[]{"#slop_0", "#slop_1", "#slop_2"}, 1, -1);
            row("Edges", new String[]{"#edge_sharp", "#edge_round"}, 1, -1);
            label("Opacity");
            Rect sl = lay.slider();
            if (drawing) {
                text("100%", sl.x() + sl.w() - width("100%"), sl.y() - PanelLayout.LABEL_H - lay.u(PanelLayout.LABEL_GAP), PanelLayout.LABEL);
                int mid = sl.y() + sl.h() / 2;
                fill(sl.x(), mid - 1, sl.w(), 2, PanelLayout.ACCENT);
                fill(sl.x() + sl.w() - lay.u(6), sl.y(), lay.u(6), sl.h(), PanelLayout.ACCENT);
            }
            row("Start arrowhead", new String[]{"#head_none@m", "#head_arrow@m", "#head_triangle@m", "#head_bar@m", "#head_dot@m"}, 0, -1);
            row("End arrowhead", new String[]{"#head_none", "#head_arrow", "#head_triangle", "#head_bar", "#head_dot"}, 1, -1);
            label("Stamp");
            Rect st = lay.wide();
            if (drawing) {
                button(st, "      Village", false, false, true);
                icon("stamp", st.x() + lay.u(3), st.y() + lay.u(2), st.h() - lay.u(4), st.h() - lay.u(4), 0xFFE03131);
            }
            row("Text size", new String[]{"S", "M", "L", "XL"}, 1, -1);
            row("Text align", new String[]{"#align_left", "#align_center", "#align_right"}, 0, -1);
            row("Visible to", new String[]{"Only me", "Team", "Everyone"}, 2, teams ? -1 : 1);
            row("Layers", new String[]{"#to_back", "#backward", "#forward", "#to_front"}, -1, -1);
            row("Actions", new String[]{"#duplicate", "#delete", "#group", "#ungroup", "#lock"}, -1, -1);
        };
        drawing = false;
        rows.run();
        int h = lay.bottom() - py;
        fill(px, py, lay.width(), h, PanelLayout.PANEL_BG);
        outline(px, py, lay.width(), h, PanelLayout.PANEL_BORDER);
        lay = new PanelLayout(scale, px, py);
        hoverId = "#width_bold";
        drawing = true;
        rows.run();
    }

    private static boolean drawing;

    private static void label(String s) {
        int y = lay.label();
        if (drawing) text(s, lay.innerX(), y, PanelLayout.LABEL);
    }

    private static void swatches(String name, int[] colors, int current) {
        label(name);
        Rect[] r = lay.swatches(colors.length);
        if (!drawing) return;
        for (int i = 0; i < colors.length; i++) {
            Rect b = r[i];
            if (colors[i] == 0) checker(b.x(), b.y(), b.w(), b.h());
            else fill(b.x(), b.y(), b.w(), b.h(), colors[i]);
            outline(b.x(), b.y(), b.w(), b.h(), PanelLayout.SWATCH_BORDER);
            if (colors[i] == current) outline(b.x() - 2, b.y() - 2, b.w() + 4, b.h() + 4, PanelLayout.ACCENT);
        }
        Rect c = r[colors.length];
        button(c, "", false, false, true);
        int in = lay.u(3);
        if (current == 0) checker(c.x() + in, c.y() + in, c.w() - 2 * in, c.h() - 2 * in);
        else fill(c.x() + in, c.y() + in, c.w() - 2 * in, c.h() - 2 * in, current);
        outline(c.x() + in, c.y() + in, c.w() - 2 * in, c.h() - 2 * in, PanelLayout.SWATCH_BORDER);
    }

    private static void row(String name, String[] labels, int active, int disabled) {
        label(name);
        Rect[] r = lay.row(labels.length);
        if (!drawing) return;
        for (int i = 0; i < labels.length; i++) button(r[i], labels[i], i == active, i == disabled, true);
    }

    /** Same states as EditorUi.buttons: normal / hover / selected (light accent + border) / disabled. */
    private static void button(Rect b, String label, boolean active, boolean disabled, boolean panel) {
        boolean hover = label.equals(hoverId);
        int bg = disabled ? PanelLayout.BUTTON_DISABLED : active ? PanelLayout.SELECTED_BG : hover ? PanelLayout.BUTTON_HOVER : panel ? PanelLayout.BUTTON : 0;
        if (bg != 0) fill(b.x(), b.y(), b.w(), b.h(), bg);
        if (active) outline(b.x(), b.y(), b.w(), b.h(), PanelLayout.ACCENT);
        int ink = disabled ? 0xFF9AA0A6 : active ? 0xFF364FC7 : 0xFF2B2B2B;
        if (label.startsWith("#")) icon(label.substring(1), b.x(), b.y(), b.w(), b.h(), ink);
        else if (!label.isEmpty()) {
            int c = disabled ? PanelLayout.TEXT_DISABLED : active ? PanelLayout.TEXT_SELECTED : PanelLayout.TEXT;
            text(label, b.x() + (b.w() - width(label)) / 2, b.y() + (b.h() - 8 + 1) / 2, c);
        }
    }

    private static void icon(String name, int x, int y, int w, int h, int argb) {
        boolean mirror = name.endsWith("@m");
        IconAtlas.Icon ic = icons.get(mirror ? name.substring(0, name.length() - 2) : name);
        if (ic == null) throw new IllegalArgumentException("not in atlas: " + name);
        // Draw at screen resolution (GUI scale), mirrored about the button centre for start arrowheads.
        var old = g.getTransform();
        if (mirror) {
            g.translate((2 * x + w) * GUI, 0);
            g.scale(-1, 1);
        }
        IconSheet.draw(g, mips, ic, x * GUI, y * GUI, w * GUI, h * GUI, argb);
        g.setTransform(old);
    }

    private static void text(String s, int x, int y, int argb) {
        g.setColor(new Color(argb, true));
        g.drawString(s, x * GUI, (y + 7) * GUI);
    }

    private static int width(String s) {
        return (int) Math.ceil(g.getFontMetrics().stringWidth(s) / (double) GUI);
    }

    private static void fill(int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x * GUI, y * GUI, w * GUI, h * GUI);
    }

    private static void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, w, 1, argb);
        fill(x, y + h - 1, w, 1, argb);
        fill(x, y, 1, h, argb);
        fill(x + w - 1, y, 1, h, argb);
    }

    private static void checker(int x, int y, int w, int h) {
        for (int yy = 0; yy < h; yy += 3)
            for (int xx = 0; xx < w; xx += 3)
                fill(x + xx, y + yy, Math.min(3, w - xx), Math.min(3, h - yy), ((xx + yy) / 3 & 1) == 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
    }
}
