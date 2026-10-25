package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Bindings;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Geometry;
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
    static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFFFFFFFF};
    static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFF1E1E1E};
    // Drawn icons (UiIconArt), in Tool order.
    private static final String[] TOOL_GLYPHS = {"#hand", "#select", "#rectangle", "#diamond", "#ellipse", "#arrow", "#line",
            "#pen", "#text", "#stamp", "#eraser", "#laser"};

    private record Btn(int x, int y, int w, int h, String label, boolean active, int swatch, Runnable action, String tip) {
        boolean hit(double gx, double gy) {
            return gx >= x && gx < x + w && gy >= y && gy < y + h;
        }
    }

    private static final List<Btn> buttons = new ArrayList<>();
    private static int panelX, panelY, panelW, panelH, sliderX, sliderY, sliderW;
    private static boolean sliderDrag;
    private static int screenH;
    // The panel's current-stamp icon {x, y, size} and id, drawn over its button.
    private static int[] stampChip;
    private static String stampChipId;
    // Current-colour chips {x, y, w, h, argb}: drawn over a checkerboard, they open the colour picker.
    private static final List<int[]> chips = new ArrayList<>();

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
        int s = u(20), gap = u(2);
        Editor.Tool[] tools = Editor.Tool.values();
        int n = Editor.drawMode() ? tools.length + 1 : 1;
        int x = screenW / 2 - (n * (s + gap)) / 2, y = 24;
        buttons.add(new Btn(x, y, s, s, "#draw", Editor.drawMode(), 0, Editor::toggleDrawMode,
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
            case RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, STAMP -> true;
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
        chip(x, yy, ColorPicker.Target.STROKE, st.stroke);
        y += row;
        // Background
        // Text has its own background box (none or solid); shapes have the full background and fill style.
        boolean textOnly = textContext(sel);
        int bg = textOnly ? st.textFill : st.fill;
        label(x0, y, textOnly ? "text_background" : "background");
        yy = y + u(10);
        x = x0;
        for (int c : FILLS) {
            int fc = c;
            buttons.add(new Btn(x, yy, sw, sw, c == 0 ? "#transparent" : "", bg == c, c, () -> apply(s -> {
                if (textOnly) s.textFill = fc;
                else s.fill = fc;
            }, e -> setBackground(e, fc)), c == 0 ? Component.translatable("mapsketch.ui.transparent").getString() : hexOf(c)));
            x += sw + gap;
        }
        chip(x, yy, textOnly ? ColorPicker.Target.TEXT_FILL : ColorPicker.Target.FILL, bg);
        y += row;
        if (textOnly) y = options(x0, y, "fill", new String[]{"#fill_none", "#fill_solid"}, (st.textFill >>> 24) != 0 ? 1 : 0, i -> {
            int c = i == 0 ? 0 : (st.textFill >>> 24) != 0 ? st.textFill : 0xFFFFFFFF;
            apply(s -> s.textFill = c, e -> setBackground(e, c));
        });
        else y = options(x0, y, "fill", new String[]{"#fill_none", "#fill_hachure", "#fill_cross", "#fill_solid"}, st.fillStyle.ordinal(), i -> {
            Element.FillStyle f = Element.FillStyle.values()[i];
            apply(s -> s.fillStyle = f, e -> {
                if (e.type != Element.Type.TEXT) e.fillStyle = f;
            });
        });
        y = options(x0, y, "width", new String[]{"#width_thin", "#width_bold", "#width_extra"}, st.width, i ->
                apply(s -> s.width = i, e -> e.strokeWidth = Editor.widthPreset(i)));
        y = options(x0, y, "stroke_style", new String[]{"#style_solid", "#style_dashed", "#style_dotted"}, st.strokeStyle.ordinal(), i -> {
            Element.StrokeStyle f = Element.StrokeStyle.values()[i];
            apply(s -> s.strokeStyle = f, e -> e.strokeStyle = f);
        });
        y = options(x0, y, "sloppiness", new String[]{"#slop_0", "#slop_1", "#slop_2"}, st.roughness, i ->
                apply(s -> s.roughness = i, e -> e.roughness = i));
        y = options(x0, y, "edges", new String[]{"#edge_sharp", "#edge_round"}, st.round ? 1 : 0, i ->
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
            String[] heads = {"#head_none", "#head_arrow", "#head_triangle", "#head_bar", "#head_dot"};
            String[] startHeads = {"#head_none@m", "#head_arrow@m", "#head_triangle@m", "#head_bar@m", "#head_dot@m"};
            y = options(x0, y, "arrow_start", startHeads, st.arrowStart.ordinal(), i -> {
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
        if (Editor.tool() == Editor.Tool.STAMP || sel.stream().anyMatch(e -> e.type == Element.Type.STAMP)) {
            label(x0, y, "stamp");
            int bh = u(18);
            String cur = sel.stream().filter(e -> e.type == Element.Type.STAMP).map(e -> e.stamp).findFirst().orElse(Editor.style.stamp);
            stampChip = new int[]{x0, y + u(10), bh};
            stampChipId = cur;
            buttons.add(new Btn(x0, y + u(10), panelW - u(8), bh, "      " + StampPicker.name(cur), StampPicker.open(), 0,
                    () -> {
                        if (StampPicker.open()) StampPicker.close();
                        else StampPicker.open(Editor.tool() == Editor.Tool.STAMP);
                    }, Component.translatable("mapsketch.ui.change_stamp").getString()));
            y += u(10) + bh + u(2);
        }
        boolean text = Editor.tool() == Editor.Tool.TEXT || sel.stream().anyMatch(e -> !e.text.isEmpty() || e.type == Element.Type.TEXT);
        if (text) {
            y = options(x0, y, "font_size", new String[]{"#font_s", "#font_m", "#font_l", "#font_xl"}, st.font, i ->
                    apply(s -> s.font = i, e -> e.fontSize = Editor.fontPreset(i)));
            y = options(x0, y, "align", new String[]{"#align_left", "#align_center", "#align_right"}, st.align.ordinal(), i -> {
                Element.Align a = Element.Align.values()[i];
                apply(s -> s.align = a, e -> e.align = a);
            });
        }
        ServerInfoPayload info = ClientStore.info();
        boolean teams = info != null && info.teams();
        String[] vis = teams ? new String[]{"#vis_private", "#vis_team", "#vis_public"} : new String[]{"#vis_private", "#vis_public"};
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
            y = actions(x0, y, "layers", new String[]{"#to_back", "#backward", "#forward", "#to_front"}, new Runnable[]{
                    () -> Editor.reorder(-2), () -> Editor.reorder(-1), () -> Editor.reorder(1), () -> Editor.reorder(2)},
                    new String[]{"to_back", "backward", "forward", "to_front"});
            boolean locked = sel.stream().allMatch(e -> e.locked);
            y = actions(x0, y, "actions", new String[]{"#duplicate", "#delete", "#group", "#ungroup", locked ? "#unlock" : "#lock"}, new Runnable[]{
                    Editor::duplicate, Editor::deleteSelection, Editor::group, Editor::ungroup,
                    () -> Editor.editSelection(e -> {
                        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(e.owner)
                                || info != null && info.op()) e.locked = !locked;
                    })}, new String[]{"duplicate", "delete", "group", "ungroup", locked ? "unlock" : "lock"});
        }
        panelH = y - panelY + u(2);
    }

    /** @return true when the panel edits text only: the Text tool with nothing selected, or a selection of only text */
    private static boolean textContext(List<Element> sel) {
        return sel.isEmpty() ? Editor.tool() == Editor.Tool.TEXT : sel.stream().allMatch(e -> e.type == Element.Type.TEXT);
    }

    /** Sets an element's background: text gets a solid box (or none when transparent), shapes just the colour. */
    static void setBackground(Element e, int c) {
        e.fill = c;
        if (e.type == Element.Type.TEXT)
            e.fillStyle = (c >>> 24) != 0 ? Element.FillStyle.SOLID : Element.FillStyle.NONE;
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
        int w = (panelW - u(8) - (glyphs.length - 1) * u(2)) / glyphs.length, h = u(18);
        for (int i = 0; i < glyphs.length; i++) {
            int fi = i;
            String g = glyphs[i];
            // "#name" is a drawn icon (UiIconArt; "@m" = mirrored), its tooltip the translation of the name.
            String tip = Component.translatable("mapsketch.ui." + g.substring(1).replace("@m", "")).getString();
            String label = g;
            buttons.add(new Btn(x + i * (w + u(2)), y + u(10), w, h, label, i == active, 0, () -> pick.accept(fi), tip));
        }
        return y + u(10) + h + u(2);
    }

    private static int actions(int x, int y, String key, String[] glyphs, Runnable[] run, String[] tips) {
        label(x, y, key);
        int w = (panelW - u(8) - (glyphs.length - 1) * u(2)) / glyphs.length, h = u(18);
        for (int i = 0; i < glyphs.length; i++)
            buttons.add(new Btn(x + i * (w + u(2)), y + u(10), w, h, glyphs[i], false, 0, run[i],
                    Component.translatable("mapsketch.ui." + tips[i]).getString()));
        return y + u(10) + h + u(2);
    }

    /** The current colour; clicking it opens (or closes) the picker beside the panel. */
    private static void chip(int x, int y, ColorPicker.Target target, int color) {
        int w = panelW - u(4) - (x - panelX), h = u(14);
        chips.add(new int[]{x, y, w, h, color});
        buttons.add(new Btn(x, y, w, h, "", ColorPicker.target() == target, 0, () -> {
            if (ColorPicker.target() == target) ColorPicker.close(true);
            else ColorPicker.open(target, panelX + panelW + u(4), y, Minecraft.getInstance().getWindow().getGuiScaledWidth(), screenH);
        }, Component.translatable("mapsketch.ui.pick_color").getString()));
    }

    private static String hexOf(int c) {
        return com.volcanicmg.mapsketch.model.Colors.hex(c);
    }

    // ---- input ----

    // The current press was consumed by our UI: only then are its drags and release ours too.
    private static boolean uiPress;

    static boolean mousePressed(double gx, double gy, int button) {
        uiPress = pressed(gx, gy, button);
        return uiPress;
    }

    private static boolean pressed(double gx, double gy, int button) {
        if (StampPicker.open() && (StampPicker.over(gx, gy) || !over(gx, gy))) return StampPicker.mousePressed(gx, gy, button);
        // The picker eats clicks over it; a click elsewhere closes it (kept) and is consumed, except middle (pan).
        if (ColorPicker.open() && (ColorPicker.over(gx, gy) || !over(gx, gy) || button != 0))
            return ColorPicker.mousePressed(gx, gy, button);
        if (ColorPicker.open() && button == 0 && !chipHit(gx, gy)) ColorPicker.close(true);
        if (button != 0) return over(gx, gy);
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

    private static boolean chipHit(double gx, double gy) {
        for (int[] c : chips) if (gx >= c[0] && gx < c[0] + c[2] && gy >= c[1] && gy < c[1] + c[3]) return true;
        return false;
    }

    /** @return true if the point is on our toolbar, panel or colour picker */
    public static boolean over(double gx, double gy) {
        if (ColorPicker.over(gx, gy) || StampPicker.over(gx, gy)) return true;
        if (panelW > 0 && gx >= panelX && gx < panelX + panelW && gy >= panelY && gy < panelY + panelH) return true;
        for (Btn b : buttons) if (b.hit(gx, gy)) return true;
        return false;
    }

    static boolean mouseDragged(double gx, double gy) {
        if (ColorPicker.mouseDragged(gx, gy)) return true;
        if (sliderDrag) {
            slide(gx);
            return true;
        }
        return uiPress;
    }

    /** @return true only for the release of a press our UI consumed (never strands Xaero's or the editor's drag) */
    static boolean mouseReleased(double gx, double gy) {
        boolean owned = uiPress;
        uiPress = false;
        if (ColorPicker.mouseReleased(gx, gy)) return true;
        if (sliderDrag) {
            sliderDrag = false;
            int o = Editor.style.opacity;
            Editor.editSelection(e -> e.opacity = o);
            return true;
        }
        return owned;
    }

    private static void slide(double gx) {
        Editor.style.opacity = (int) Math.round(Math.max(0, Math.min(1, (gx - sliderX) / sliderW)) * 100);
    }

    /** @return true when the wheel scrolled one of our popovers (Xaero must not zoom) */
    public static boolean mouseScrolled(double gx, double gy, double delta) {
        return StampPicker.open() && StampPicker.mouseScrolled(gx, gy, delta);
    }

    static boolean keyPressed(int key, int mods) {
        return ColorPicker.keyPressed(key, mods) || StampPicker.keyPressed(key, mods);
    }

    /** @return true while one of our popovers owns the keyboard (stamp search, colour picker / hex field) */
    static boolean isTyping() {
        return StampPicker.open() || ColorPicker.open();
    }

    /** Ctrl (Cmd on macOS) held. */
    static boolean ctrl(int mods) {
        return (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
    }

    static String clipboard() {
        return Minecraft.getInstance().keyboardHandler.getClipboard();
    }

    static void setClipboard(String s) {
        Minecraft.getInstance().keyboardHandler.setClipboard(s);
    }

    static boolean charTyped(char c) {
        return ColorPicker.charTyped(c) || StampPicker.charTyped(c);
    }

    /** The map closed or draw mode ended: a picker session still open is kept. */
    static void closePicker() {
        ColorPicker.close(true);
        StampPicker.close();
    }

    // ---- rendering ----

    /** Draws the overlay above Xaero's map, in GUI pixels. */
    public static void render(GuiGraphics g, MapView v, int mouseX, int mouseY, int screenW) {
        labels.clear();
        labelKeys.clear();
        chips.clear();
        stampChip = null;
        screenH = (int) (v.winH() / v.guiScale());
        layout(screenW);
        // The picker belongs to the panel: if the panel went away (selection/tool change), keep and close it.
        if (panelW == 0) ColorPicker.close(true);
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
            if (b.label().startsWith("#") && UiIcons.has(b.label().substring(1)))
                UiIcons.draw(g, b.label().substring(1), b.x(), b.y(), b.w(), b.h(), b.active() ? UiIcons.SELECTED : UiIcons.NORMAL);
            else if (!b.label().isEmpty()) {
                int tw = font.width(b.label());
                g.drawString(font, b.label(), b.x() + (b.w() - tw) / 2, b.y() + (b.h() - 8) / 2, b.swatch() == 0xFF1E1E1E ? 0xFFFFFFFF : TEXT, false);
            }
            if (b.hit(mouseX, mouseY)) hover = b;
        }
        for (int[] c : chips) {
            ColorPicker.checker(g, c[0] + 1, c[1] + 1, c[2] - 2, c[3] - 2);
            g.fill(c[0] + 1, c[1] + 1, c[0] + c[2] - 1, c[1] + c[3] - 1, c[4]);
        }
        if (stampChip != null) StampPicker.icon(g, stampChipId, stampChip[0] + 2, stampChip[1] + 1, stampChip[2] - 2);
        ColorPicker.render(g);
        StampPicker.render(g, screenW, screenH, mouseX, mouseY);
        if (ColorPicker.over(mouseX, mouseY) || StampPicker.over(mouseX, mouseY)) hover = null;
        if (hover != null && !hover.tip().isEmpty()) g.renderTooltip(font, Component.literal(hover.tip()), mouseX, mouseY + 12);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    /** While an arrow/line end is dragged: the target's link nodes, its outline for an edge binding, the fixed point. */
    private static void bindOverlay(GuiGraphics g, MapView v) {
        Editor.BindPreview bp = Editor.bindPreview();
        if (bp == null) return;
        Element t = ClientStore.get(bp.target().id);
        if (t == null || t.deleted) return;
        Bindings.Hit hit = bp.hit();
        if (hit.kind() == Bindings.Kind.EDGE) {
            float[] o = Geometry.outline(t);
            float[] pts = new float[o.length + 2];
            for (int i = 0; i <= o.length / 2; i++) {
                int k = i % (o.length / 2);
                double[] w = Geometry.toWorld(t, o[k * 2], o[k * 2 + 1]);
                pts[i * 2] = (float) v.guiX(w[0]);
                pts[i * 2 + 1] = (float) v.guiY(w[1]);
            }
            SketchRenderer.drawPolyline(g, pts, 2, 0xC04C6EF5, 4);
        }
        float[][] nodes = Bindings.anchors(t);
        for (int i = 0; i < nodes.length; i++) {
            double[] w = Bindings.fixedPoint(t, nodes[i][0], nodes[i][1]);
            int x = (int) Math.round(v.guiX(w[0])), y = (int) Math.round(v.guiY(w[1]));
            boolean on = hit.kind() == Bindings.Kind.DOT && hit.dot() == i;
            int r = on ? 4 : 3;
            g.fill(x - r, y - r, x + r + 1, y + r + 1, ACCENT);
            if (!on) g.fill(x - r + 1, y - r + 1, x + r, y + r, 0xFFFFFFFF);
        }
        if (hit.kind() == Bindings.Kind.INSIDE) {
            double[] w = Bindings.fixedPoint(t, hit.fx(), hit.fz());
            int x = (int) Math.round(v.guiX(w[0])), y = (int) Math.round(v.guiY(w[1]));
            g.fill(x - 2, y - 2, x + 3, y + 3, ACCENT);
        }
    }

    private static void selectionOverlay(GuiGraphics g, MapView v) {
        bindOverlay(g, v);
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
