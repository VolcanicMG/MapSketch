package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Toolbar (top centre), properties panel (left), selection frames, handles and remote cursors, all in GUI pixels.
 * Widgets are rebuilt every frame from the editor state, so there is no widget state to keep in sync.
 */
public final class EditorUi {
    private static final int BG = 0xE0FFFFFF, BORDER = 0xFF9AA0A6, ACTIVE = 0xFFC9D7FF, ACCENT = 0xFF4C6EF5, TEXT = 0xFF1E1E1E;
    private static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFFFFFFFF};
    private static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFF1E1E1E};
    private static final String[] TOOL_GLYPHS = {"✋", "↖", "□", "◇", "○", "→", "—", "✎", "A", "⌫", "✦"};

    private record Btn(int x, int y, int w, int h, String label, boolean active, int swatch, Runnable action, String tip) {
        boolean hit(double gx, double gy) {
            return gx >= x && gx < x + w && gy >= y && gy < y + h;
        }
    }

    private static final List<Btn> buttons = new ArrayList<>();
    private static int panelX, panelY, panelW, panelH, sliderX, sliderY, sliderW;
    private static boolean sliderDrag;
    private static String hexTarget; // "stroke" or "fill" while typing a hex colour
    private static StringBuilder hex = new StringBuilder();

    private EditorUi() {
    }

    private static int u(double px) {
        return (int) Math.round(px * Config.UI_SCALE.get());
    }

    /** Rebuilds the widget list for this frame. */
    private static void layout(int screenW) {
        buttons.clear();
        panelW = 0;
        if (!Config.ENABLED.get()) return;
        int s = u(18), gap = u(2);
        Editor.Tool[] tools = Editor.Tool.values();
        int n = Editor.drawMode() ? tools.length + 1 : 1;
        int x = screenW / 2 - (n * (s + gap)) / 2, y = 24;
        buttons.add(new Btn(x, y, s, s, "✐", Editor.drawMode(), 0, Editor::toggleDrawMode,
                Component.translatable("mapsketch.ui.draw_mode", MapSketchKeys.TOGGLE.getTranslatedKeyMessage()).getString()));
        if (!Editor.drawMode()) return;
        x += s + gap * 3;
        for (int i = 0; i < tools.length; i++) {
            Editor.Tool t = tools[i];
            String key = t.digit != '\0' ? t.key + " / " + t.digit : String.valueOf(t.key);
            buttons.add(new Btn(x, y, s, s, TOOL_GLYPHS[i], Editor.tool() == t, 0, () -> Editor.setTool(t),
                    Component.translatable("mapsketch.tool." + t.name().toLowerCase()).getString() + " — " + key));
            x += s + gap;
        }
        boolean creating = switch (Editor.tool()) {
            case RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT -> true;
            default -> false;
        };
        if (Editor.selection().isEmpty() && !creating) return;
        panel();
    }

    private static void panel() {
        Editor.Style st = Editor.style;
        List<Element> sel = Editor.selectedLive();
        panelX = 4;
        panelY = 34;
        panelW = u(124);
        int x0 = panelX + u(4), y = panelY + u(4), sw = u(14), gap = u(2), row = u(10) + sw + gap;
        // Stroke colour
        label(x0, y, "stroke");
        int yy = y + u(10), x = x0;
        for (int c : STROKES) {
            buttons.add(new Btn(x, yy, sw, sw, "", st.stroke == c, c, () -> apply(s -> s.stroke = c, e -> e.stroke = c), hexOf(c)));
            x += sw + gap;
        }
        hexField(x, yy, "stroke", st.stroke);
        y += row;
        // Background
        label(x0, y, "background");
        yy = y + u(10);
        x = x0;
        for (int c : FILLS) {
            int fc = c;
            buttons.add(new Btn(x, yy, sw, sw, c == 0 ? "∅" : "", st.fill == c, c, () -> apply(s -> s.fill = fc, e -> e.fill = fc),
                    c == 0 ? Component.translatable("mapsketch.ui.transparent").getString() : hexOf(c)));
            x += sw + gap;
        }
        hexField(x, yy, "fill", st.fill);
        y += row;
        y = options(x0, y, "fill", new String[]{"∅", "▧", "▦", "■"}, st.fillStyle.ordinal(), i -> {
            Element.FillStyle f = Element.FillStyle.values()[i];
            apply(s -> s.fillStyle = f, e -> e.fillStyle = f);
        });
        y = options(x0, y, "width", new String[]{"thin", "bold", "extra"}, st.width, i ->
                apply(s -> s.width = i, e -> e.strokeWidth = Editor.widthPreset(i)));
        y = options(x0, y, "stroke_style", new String[]{"—", "- -", "···"}, st.strokeStyle.ordinal(), i -> {
            Element.StrokeStyle f = Element.StrokeStyle.values()[i];
            apply(s -> s.strokeStyle = f, e -> e.strokeStyle = f);
        });
        y = options(x0, y, "sloppiness", new String[]{"─", "∿", "≈"}, st.roughness, i ->
                apply(s -> s.roughness = i, e -> e.roughness = i));
        y = options(x0, y, "edges", new String[]{"┐", "╮"}, st.round ? 1 : 0, i ->
                apply(s -> s.round = i == 1, e -> e.round = i == 1 && e.type != Element.Type.FREEDRAW));
        // Opacity slider
        label(x0, y, "opacity");
        sliderX = x0;
        sliderY = y + u(10);
        sliderW = panelW - u(8);
        y += u(10) + u(10) + gap;
        boolean arrows = Editor.tool() == Editor.Tool.ARROW || Editor.tool() == Editor.Tool.LINE
                || sel.stream().anyMatch(e -> e.type == Element.Type.ARROW || e.type == Element.Type.LINE);
        if (arrows) {
            String[] heads = {"✕", "→", "▶", "|", "●"};
            y = options(x0, y, "arrow_start", heads, st.arrowStart.ordinal(), i -> {
                Element.Head h = Element.Head.values()[i];
                apply(s -> s.arrowStart = h, e -> {
                    if (e.type == Element.Type.ARROW || e.type == Element.Type.LINE) e.arrowStart = h;
                });
            });
            y = options(x0, y, "arrow_end", heads, st.arrowEnd.ordinal(), i -> {
                Element.Head h = Element.Head.values()[i];
                apply(s -> s.arrowEnd = h, e -> {
                    if (e.type == Element.Type.ARROW || e.type == Element.Type.LINE) e.arrowEnd = h;
                });
            });
        }
        boolean text = Editor.tool() == Editor.Tool.TEXT || sel.stream().anyMatch(e -> !e.text.isEmpty() || e.type == Element.Type.TEXT);
        if (text) {
            y = options(x0, y, "font_size", new String[]{"S", "M", "L", "XL"}, st.font, i ->
                    apply(s -> s.font = i, e -> e.fontSize = Editor.fontPreset(i)));
            y = options(x0, y, "align", new String[]{"⫷", "≡", "⫸"}, st.align.ordinal(), i -> {
                Element.Align a = Element.Align.values()[i];
                apply(s -> s.align = a, e -> e.align = a);
            });
        }
        ServerInfoPayload info = ClientStore.info();
        boolean teams = info != null && info.teams();
        String[] vis = teams ? new String[]{"private", "team", "public"} : new String[]{"private", "public"};
        Element.Visibility cur = st.visibility != null ? st.visibility : info != null ? info.defaultVisibility() : Element.Visibility.PUBLIC;
        int visIdx = teams ? cur.ordinal() : cur == Element.Visibility.PRIVATE ? 0 : 1;
        y = options(x0, y, "visibility", vis, visIdx, i -> {
            Element.Visibility v = teams ? Element.Visibility.values()[i] : i == 0 ? Element.Visibility.PRIVATE : Element.Visibility.PUBLIC;
            apply(s -> s.visibility = v, e -> {
                if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(e.owner)
                        || info != null && info.op()) e.visibility = v;
            });
        });
        if (!sel.isEmpty()) {
            y = actions(x0, y, "layers", new String[]{"⤓", "↓", "↑", "⤒"}, new Runnable[]{
                    () -> Editor.reorder(-2), () -> Editor.reorder(-1), () -> Editor.reorder(1), () -> Editor.reorder(2)},
                    new String[]{"to_back", "backward", "forward", "to_front"});
            boolean locked = sel.stream().allMatch(e -> e.locked);
            y = actions(x0, y, "actions", new String[]{"⧉", "✖", "▣", "▢", locked ? "⚿" : "⚷"}, new Runnable[]{
                    Editor::duplicate, Editor::deleteSelection, Editor::group, Editor::ungroup,
                    () -> Editor.editSelection(e -> {
                        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(e.owner)
                                || info != null && info.op()) e.locked = !locked;
                    })}, new String[]{"duplicate", "delete", "group", "ungroup", locked ? "unlock" : "lock"});
        }
        panelH = y - panelY + u(2);
    }

    private static void apply(Consumer<Editor.Style> s, Consumer<Element> e) {
        s.accept(Editor.style);
        Editor.editSelection(e);
    }

    private static final List<int[]> labels = new ArrayList<>();
    private static final List<String> labelKeys = new ArrayList<>();

    private static void label(int x, int y, String key) {
        labels.add(new int[]{x, y});
        labelKeys.add(key);
    }

    private static int options(int x, int y, String key, String[] glyphs, int active, java.util.function.IntConsumer pick) {
        label(x, y, key);
        int w = (panelW - u(8) - (glyphs.length - 1) * u(2)) / glyphs.length, h = u(14);
        for (int i = 0; i < glyphs.length; i++) {
            int fi = i;
            String g = glyphs[i];
            // Lowercase words are translation keys; anything else is a glyph.
            String tip = g.matches("[a-z_]+") ? Component.translatable("mapsketch.ui." + g).getString() : g;
            buttons.add(new Btn(x + i * (w + u(2)), y + u(10), w, h, tip, i == active, 0, () -> pick.accept(fi), tip));
        }
        return y + u(10) + h + u(2);
    }

    private static int actions(int x, int y, String key, String[] glyphs, Runnable[] run, String[] tips) {
        label(x, y, key);
        int w = (panelW - u(8) - (glyphs.length - 1) * u(2)) / glyphs.length, h = u(14);
        for (int i = 0; i < glyphs.length; i++)
            buttons.add(new Btn(x + i * (w + u(2)), y + u(10), w, h, glyphs[i], false, 0, run[i],
                    Component.translatable("mapsketch.ui." + tips[i]).getString()));
        return y + u(10) + h + u(2);
    }

    private static void hexField(int x, int y, String target, int color) {
        boolean typing = target.equals(hexTarget);
        String text = typing ? "#" + hex + (System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "") : "#";
        buttons.add(new Btn(x, y, panelW - u(4) - (x - panelX), u(14), text, typing, 0, () -> {
            hexTarget = target;
            hex = new StringBuilder(hexOf(color).substring(1));
        }, Component.translatable("mapsketch.ui.hex").getString()));
    }

    private static String hexOf(int c) {
        return (c >>> 24) == 0xFF ? String.format("#%06x", c & 0xFFFFFF) : String.format("#%08x", c);
    }

    // ---- input ----

    static boolean mousePressed(double gx, double gy, int button) {
        if (button != 0) return over(gx, gy);
        if (hexTarget != null) applyHex();
        if (panelW > 0 && gx >= sliderX && gx < sliderX + sliderW && gy >= sliderY && gy < sliderY + u(10)) {
            sliderDrag = true;
            slide(gx);
            return true;
        }
        for (Btn b : buttons)
            if (b.hit(gx, gy)) {
                b.action().run();
                return true;
            }
        return over(gx, gy);
    }

    /** @return true if the point is on our toolbar or panel */
    public static boolean over(double gx, double gy) {
        if (panelW > 0 && gx >= panelX && gx < panelX + panelW && gy >= panelY && gy < panelY + panelH) return true;
        for (Btn b : buttons) if (b.hit(gx, gy)) return true;
        return false;
    }

    static boolean mouseDragged(double gx, double gy) {
        if (!sliderDrag) return false;
        slide(gx);
        return true;
    }

    static boolean mouseReleased() {
        if (!sliderDrag) return false;
        sliderDrag = false;
        int o = Editor.style.opacity;
        Editor.editSelection(e -> e.opacity = o);
        return true;
    }

    private static void slide(double gx) {
        Editor.style.opacity = (int) Math.round(Math.max(0, Math.min(1, (gx - sliderX) / sliderW)) * 100);
    }

    static boolean keyPressed(int key) {
        if (hexTarget == null) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> applyHex();
            case GLFW.GLFW_KEY_ESCAPE -> hexTarget = null;
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!hex.isEmpty()) hex.setLength(hex.length() - 1);
            }
            default -> {
            }
        }
        return true;
    }

    static boolean charTyped(char c) {
        if (hexTarget == null) return false;
        if (Character.digit(c, 16) >= 0 && hex.length() < 8) hex.append(Character.toLowerCase(c));
        return true;
    }

    private static void applyHex() {
        String target = hexTarget;
        hexTarget = null;
        int fallback = target.equals("stroke") ? Editor.style.stroke : Editor.style.fill;
        int c = Config.parseColor(hex.toString(), fallback);
        if (target.equals("stroke")) apply(s -> s.stroke = c, e -> e.stroke = c);
        else apply(s -> s.fill = c, e -> e.fill = c);
    }

    // ---- rendering ----

    /** Draws the overlay above Xaero's map, in GUI pixels. */
    public static void render(GuiGraphics g, MapView v, int mouseX, int mouseY, int screenW) {
        labels.clear();
        labelKeys.clear();
        layout(screenW);
        if (!Config.ENABLED.get()) return;
        Font font = Minecraft.getInstance().font;
        others(g, v);
        if (Editor.drawMode()) selectionOverlay(g, v);
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int w = font.width(toast);
            int ty = (int) (v.winH() / v.guiScale()) - 40;
            g.fill(screenW / 2 - w / 2 - 4, ty - 3, screenW / 2 + w / 2 + 4, ty + 11, 0xE0202124);
            g.drawString(font, toast, screenW / 2 - w / 2, ty, 0xFFFFFFFF, false);
        }
        if (panelW > 0) {
            g.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG);
            outline(g, panelX, panelY, panelW, panelH, BORDER);
            for (int i = 0; i < labels.size(); i++)
                g.drawString(font, Component.translatable("mapsketch.ui." + labelKeys.get(i)), labels.get(i)[0], labels.get(i)[1], 0xFF5F6368, false);
            g.fill(sliderX, sliderY + u(4), sliderX + sliderW, sliderY + u(6), BORDER);
            int knob = sliderX + sliderW * Editor.style.opacity / 100;
            g.fill(knob - u(2), sliderY, knob + u(2), sliderY + u(10), ACCENT);
            g.drawString(font, Editor.style.opacity + "%", sliderX + sliderW - font.width("100%"), sliderY - u(10), 0xFF5F6368, false);
        }
        Btn hover = null;
        for (Btn b : buttons) {
            boolean toolbar = b.y() == 24;
            if (toolbar) g.fill(b.x() - 1, b.y() - 1, b.x() + b.w() + 1, b.y() + b.h() + 1, BG);
            if (b.swatch() != 0) {
                g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), b.swatch());
            } else if (b.active()) g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), ACTIVE);
            outline(g, b.x(), b.y(), b.w(), b.h(), b.active() ? ACCENT : BORDER);
            if (!b.label().isEmpty()) {
                int tw = font.width(b.label());
                g.drawString(font, b.label(), b.x() + (b.w() - tw) / 2, b.y() + (b.h() - 8) / 2, b.swatch() == 0xFF1E1E1E ? 0xFFFFFFFF : TEXT, false);
            }
            if (b.hit(mouseX, mouseY)) hover = b;
        }
        if (hover != null && !hover.tip().isEmpty()) g.renderTooltip(font, Component.literal(hover.tip()), mouseX, mouseY + 12);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private static void selectionOverlay(GuiGraphics g, MapView v) {
        if (Editor.boxSelecting()) {
            double[] b = Editor.boxWorld();
            int x1 = (int) v.guiX(b[0]), y1 = (int) v.guiY(b[1]), x2 = (int) v.guiX(b[2]), y2 = (int) v.guiY(b[3]);
            g.fill(x1, y1, x2, y2, 0x304C6EF5);
            outline(g, x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), ACCENT);
        }
        List<Element> sel = Editor.selectedLive();
        if (sel.isEmpty()) return;
        if (sel.size() > 1)
            for (Element e : sel) {
                double[] b = e.worldBounds();
                guiBox(g, v, new double[]{(b[0] + b[2]) / 2, (b[1] + b[3]) / 2, 0, (b[2] - b[0]) / 2, (b[3] - b[1]) / 2}, 0x804C6EF5, 0);
            }
        if (Editor.single(true)) {
            Element e = sel.get(0);
            for (int i = 0; i < e.pointCount(); i++) {
                double[] w = com.volcanicmg.mapsketch.model.Geometry.toWorld(e, e.pts[i * 2], e.pts[i * 2 + 1]);
                handle(g, v.guiX(w[0]), v.guiY(w[1]), true);
            }
            return;
        }
        double[] f = Editor.frame();
        if (f == null) return;
        guiBox(g, v, f, ACCENT, v.blocks(4));
        for (int hx = -1; hx <= 1; hx++)
            for (int hz = -1; hz <= 1; hz++) {
                if (hx == 0 && hz == 0) continue;
                double[] p = Editor.framePoint(f, hx, hz, 0);
                handle(g, v.guiX(p[0]), v.guiY(p[1]), false);
            }
        double[] r = Editor.framePoint(f, 0, -1, -v.blocks(16));
        handle(g, v.guiX(r[0]), v.guiY(r[1]), true);
    }

    /** Rotated rectangle outline of a frame {cx, cz, angle, hw, hh}, padded by {@code pad} blocks. */
    private static void guiBox(GuiGraphics g, MapView v, double[] f, int color, double pad) {
        double[] padded = {f[0], f[1], f[2], f[3] + pad, f[4] + pad};
        float[] pts = new float[10];
        int[][] order = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
        for (int i = 0; i < 5; i++) {
            double[] p = Editor.framePoint(padded, order[i][0], order[i][1], 0);
            pts[i * 2] = (float) v.guiX(p[0]);
            pts[i * 2 + 1] = (float) v.guiY(p[1]);
        }
        SketchRenderer.drawPolyline(g, pts, 1, color, 4);
    }

    private static void handle(GuiGraphics g, double x, double y, boolean round) {
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        g.fill(ix - 3, iy - 3, ix + 4, iy + 4, ACCENT);
        g.fill(ix - 2, iy - 2, ix + 3, iy + 3, round ? ACTIVE : 0xFFFFFFFF);
    }

    /** Other players: cursors with names, name tags on their live ghosts, outlines of what they are editing. */
    private static void others(GuiGraphics g, MapView v) {
        if (!Config.SHOW_OTHERS_CURSORS.get()) return;
        Font font = Minecraft.getInstance().font;
        for (ClientStore.Holder h : ClientStore.holders()) {
            int c = colorOf(h.player());
            boolean first = true;
            for (UUID id : h.ids()) {
                Element e = ClientStore.get(id);
                if (e == null || e.deleted || !e.dim.equals(v.dim())) continue;
                double[] b = e.worldBounds();
                double[] f = {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2, 0, (b[2] - b[0]) / 2, (b[3] - b[1]) / 2};
                guiBox(g, v, f, c, v.blocks(3));
                if (first) tag(g, font, h.name(), (int) v.guiX(b[0]), (int) v.guiY(b[1]) - 12, c);
                first = false;
            }
        }
        for (ClientStore.Remote r : ClientStore.remotes()) {
            int c = colorOf(r.player);
            for (Element gh : r.ghosts.values()) {
                if (!gh.dim.equals(v.dim()) || ClientStore.heldBy(gh.id) != null) continue;
                double[] b = gh.worldBounds();
                tag(g, font, r.name, (int) v.guiX(b[0]), (int) v.guiY(b[1]) - 12, c);
                break;
            }
            if (!r.showCursor() || !r.live.dim().equals(v.dim())) continue;
            int x = (int) v.guiX(r.live.x()), y = (int) v.guiY(r.live.z());
            SketchRenderer.drawPolyline(g, new float[]{x, y, x, y + 10, x + 3, y + 7, x + 7, y + 7, x, y}, 1.5, c, 4);
            tag(g, font, r.name, x + 8, y + 8, c);
        }
    }

    private static void tag(GuiGraphics g, Font font, String name, int x, int y, int c) {
        g.fill(x, y, x + 4 + font.width(name), y + 11, c);
        g.drawString(font, name, x + 2, y + 2, 0xFFFFFFFF, false);
    }

    private static Component toast;
    private static long toastUntil;

    /** Short message at the bottom of the map (the action bar is hidden under Xaero's screen). */
    public static void toast(Component c) {
        toast = c;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    /** Stable per-player colour. */
    static int colorOf(UUID id) {
        return 0xFF000000 | net.minecraft.util.Mth.hsvToRgb((id.hashCode() & 0xFFFF) / 65536f, 0.65f, 0.85f);
    }
}
