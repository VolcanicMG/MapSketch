package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Bindings;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Geometry;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import com.volcanicmg.mapsketch.render.Hints;
import com.volcanicmg.mapsketch.render.PanelLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
    private static final int ACTIVE = 0xFFC9D7FF, ACCENT = 0xFF4C6EF5;
    static final int[] STROKES = {0xFF1E1E1E, 0xFFE03131, 0xFF2F9E44, 0xFF1971C2, 0xFFF08C00, 0xFFFFFFFF};
    static final int[] FILLS = {0, 0xFFFFC9C9, 0xFFB2F2BB, 0xFFA5D8FF, 0xFFFFEC99, 0xFF1E1E1E};
    // Atlas icons (UiIcons), in Tool order.
    private static final String[] TOOL_GLYPHS = {"#hand", "#select", "#rectangle", "#diamond", "#ellipse", "#arrow", "#line",
            "#pen", "#text", "#stamp", "#eraser", "#laser"};
    // Label of the transparent swatch: drawn as a checkerboard.
    private static final String TRANSPARENT = "~transparent";
    // Prefix of the stamp button's label: drawn left-aligned after the stamp icon.
    private static final String STAMP_LABEL = "~stamp:";
    private static final int TOOLBAR_Y = 24, SCROLLBAR = 8;

    /** A button: label # + atlas id = icon, else text; swatch != 0 = colour swatch; panel = clipped to the panel view. */
    private record Btn(int x, int y, int w, int h, String label, boolean active, int swatch, Runnable action, String tip,
                       boolean panel, boolean disabled) {
        boolean hit(double gx, double gy) {
            return gx >= x && gx < x + w && gy >= y && gy < y + h && (!panel || inPanelView(gy));
        }
    }

    private static final List<Btn> buttons = new ArrayList<>();
    private static int panelX, panelY, panelW, panelH, sliderX, sliderY, sliderW, sliderH;
    // Panel scroll offset and the full content height (the panel shows at most the screen's height).
    private static int panelScroll, panelContentH;
    private static boolean sliderDrag;
    private static int screenH;
    // The panel's current-stamp icon {x, y, size} and id, drawn over its button.
    private static int[] stampChip;
    private static String stampChipId;
    // Current-colour chips {x, y, w, h, argb}: drawn over a checkerboard, they open the colour picker.
    private static final List<int[]> chips = new ArrayList<>();

    private EditorUi() {
    }


    private static boolean inPanelView(double gy) {
        return gy >= panelY && gy < panelY + panelH;
    }

    private static String tr(String key) {
        return Component.translatable("mapsketch.ui." + key).getString();
    }

    /** Rebuilds the widget list for this frame. */
    private static void layout(int screenW) {
        buttons.clear();
        panelW = 0;
        if (!Config.ENABLED.get()) return;
        int s = PanelLayout.TOOL, gap = PanelLayout.TOOL_GAP;
        Editor.Tool[] tools = Editor.Tool.values();
        int n = Editor.drawMode() ? tools.length + 1 : 1;
        int x = screenW / 2 - (n * (s + gap) + gap * 2) / 2, y = TOOLBAR_Y;
        buttons.add(new Btn(x, y, s, s, "#draw", Editor.drawMode(), 0, Editor::toggleDrawMode,
                Component.translatable("mapsketch.ui.draw_mode", MapSketchKeys.TOGGLE.getTranslatedKeyMessage()).getString(), false, false));
        if (!Editor.drawMode()) return;
        x += s + gap * 3;
        for (int i = 0; i < tools.length; i++) {
            Editor.Tool t = tools[i];
            String key = t.digit != '\0' ? t.key + " / " + t.digit : String.valueOf(t.key);
            buttons.add(new Btn(x, y, s, s, TOOL_GLYPHS[i], Editor.tool() == t, 0, () -> Editor.setTool(t),
                    Component.translatable("mapsketch.tool." + t.name().toLowerCase()).getString() + " — " + key, false, false));
            x += s + gap;
        }
        boolean creating = switch (Editor.tool()) {
            case RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, STAMP -> true;
            default -> false;
        };
        if (Editor.selection().isEmpty() && !creating) return;
        panelX = 4;
        // Below the toolbar: on narrow screens the wider toolbar reaches over the panel's column.
        panelY = TOOLBAR_Y + s + 10;
        panel();
        // Fit the panel to the screen; scroll the rest. A clamped scroll lays the panel out again at once.
        panelH = Math.min(panelContentH, Math.max(60, screenH - panelY - 4));
        if (panelContentH > panelH) panelW += SCROLLBAR;
        int max = Math.max(0, panelContentH - panelH);
        if (panelScroll > max) {
            panelScroll = max;
            buttons.removeIf(Btn::panel);
            labels.clear();
            labelKeys.clear();
            chips.clear();
            stampChip = null;
            panel();
        }
    }

    private static void panel() {
        Editor.Style st = Editor.style;
        List<Element> sel = Editor.selectedLive();
        PanelLayout lay = new PanelLayout(panelX, panelY - panelScroll);
        panelW = lay.width();
        swatches(lay, "stroke", STROKES, st.stroke, ColorPicker.Target.STROKE, c -> apply(s -> s.stroke = c, e -> e.stroke = c));
        // Text has its own background box (none or solid); shapes have the full background and fill style.
        boolean textOnly = textContext(sel);
        swatches(lay, textOnly ? "text_background" : "background", FILLS, textOnly ? st.textFill : st.fill,
                textOnly ? ColorPicker.Target.TEXT_FILL : ColorPicker.Target.FILL, c -> apply(s -> {
                    if (textOnly) s.textFill = c;
                    else s.fill = c;
                }, e -> setBackground(e, c)));
        if (textOnly) options(lay, "fill", new String[]{"#fill_none", "#fill_solid"}, null, (st.textFill >>> 24) != 0 ? 1 : 0, -1, i -> {
            int c = i == 0 ? 0 : (st.textFill >>> 24) != 0 ? st.textFill : 0xFFFFFFFF;
            apply(s -> s.textFill = c, e -> setBackground(e, c));
        });
        else options(lay, "fill", new String[]{"#fill_none", "#fill_hachure", "#fill_cross", "#fill_solid"}, null, st.fillStyle.ordinal(), -1, i -> {
            Element.FillStyle f = Element.FillStyle.values()[i];
            apply(s -> s.fillStyle = f, e -> {
                if (e.type != Element.Type.TEXT) e.fillStyle = f;
            });
        });
        options(lay, "width", new String[]{"#width_thin", "#width_bold", "#width_extra"}, null, st.width, -1, i ->
                apply(s -> s.width = i, e -> e.strokeWidth = Editor.widthPreset(i)));
        options(lay, "stroke_style", new String[]{"#style_solid", "#style_dashed", "#style_dotted"}, null, st.strokeStyle.ordinal(), -1, i -> {
            Element.StrokeStyle f = Element.StrokeStyle.values()[i];
            apply(s -> s.strokeStyle = f, e -> e.strokeStyle = f);
        });
        options(lay, "sloppiness", new String[]{"#slop_0", "#slop_1", "#slop_2"}, null, st.roughness, -1, i ->
                apply(s -> s.roughness = i, e -> e.roughness = i));
        options(lay, "edges", new String[]{"#edge_sharp", "#edge_round"}, null, st.round ? 1 : 0, -1, i ->
                apply(s -> s.round = i == 1, e -> e.round = i == 1 && e.type != Element.Type.FREEDRAW));
        // Vanilla options style: the label sits inside the slider ("Opacity: 100%").
        PanelLayout.Rect sl = lay.slider();
        sliderX = sl.x();
        sliderY = sl.y();
        sliderW = sl.w();
        sliderH = sl.h();
        boolean arrows = Editor.tool() == Editor.Tool.ARROW || Editor.tool() == Editor.Tool.LINE
                || sel.stream().anyMatch(e -> e.type == Element.Type.ARROW || e.type == Element.Type.LINE);
        if (arrows) {
            String[] heads = {"#head_none", "#head_arrow", "#head_triangle", "#head_bar", "#head_dot"};
            String[] startHeads = {"#head_none@m", "#head_arrow@m", "#head_triangle@m", "#head_bar@m", "#head_dot@m"};
            options(lay, "arrow_start", startHeads, null, st.arrowStart.ordinal(), -1, i -> {
                Element.Head h = Element.Head.values()[i];
                apply(s -> s.arrowStart = h, e -> {
                    if (e.type == Element.Type.ARROW || e.type == Element.Type.LINE) e.arrowStart = h;
                });
            });
            options(lay, "arrow_end", heads, null, st.arrowEnd.ordinal(), -1, i -> {
                Element.Head h = Element.Head.values()[i];
                apply(s -> s.arrowEnd = h, e -> {
                    if (e.type == Element.Type.ARROW || e.type == Element.Type.LINE) e.arrowEnd = h;
                });
            });
        }
        if (Editor.tool() == Editor.Tool.STAMP || sel.stream().anyMatch(e -> e.type == Element.Type.STAMP)) {
            label(lay, "stamp");
            PanelLayout.Rect r = lay.wide();
            String cur = sel.stream().filter(e -> e.type == Element.Type.STAMP).map(e -> e.stamp).findFirst().orElse(Editor.style.stamp);
            stampChip = new int[]{r.x() + 2, r.y() + 2, r.h() - 4};
            stampChipId = cur;
            buttons.add(new Btn(r.x(), r.y(), r.w(), r.h(), STAMP_LABEL + StampPicker.name(cur), StampPicker.open(), 0,
                    () -> {
                        if (StampPicker.open()) StampPicker.close();
                        else StampPicker.open(Editor.tool() == Editor.Tool.STAMP);
                    }, tr("change_stamp"), true, false));
        }
        boolean text = Editor.tool() == Editor.Tool.TEXT || sel.stream().anyMatch(e -> !e.text.isEmpty() || e.type == Element.Type.TEXT);
        if (text) {
            options(lay, "font_size", new String[]{"S", "M", "L", "XL"}, new String[]{"font_s", "font_m", "font_l", "font_xl"}, st.font, -1, i ->
                    apply(s -> s.font = i, e -> e.fontSize = Editor.fontPreset(i)));
            options(lay, "align", new String[]{"#align_left", "#align_center", "#align_right"}, null, st.align.ordinal(), -1, i -> {
                Element.Align a = Element.Align.values()[i];
                apply(s -> s.align = a, e -> e.align = a);
            });
        }
        ServerInfoPayload info = ClientStore.info();
        boolean teams = info != null && info.teams();
        Element.Visibility cur = st.visibility != null ? st.visibility : info != null ? info.defaultVisibility() : Element.Visibility.PUBLIC;
        // Text, not icons; Team stays visible but disabled (with the reason) when the server has no FTB Teams.
        options(lay, "visibility", new String[]{tr("vis_private"), tr("team"), tr("vis_public")},
                new String[]{null, teams ? "vis_team" : "vis_team_unavailable", null}, cur.ordinal(), teams ? -1 : 1, i -> {
                    Element.Visibility v = Element.Visibility.values()[i];
                    apply(s -> s.visibility = v, e -> {
                        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(e.owner)
                                || info != null && info.op()) e.visibility = v;
                    });
                });
        if (!sel.isEmpty()) {
            Runnable[] layers = {() -> Editor.reorder(-2), () -> Editor.reorder(-1), () -> Editor.reorder(1), () -> Editor.reorder(2)};
            options(lay, "layers", new String[]{"#to_back", "#backward", "#forward", "#to_front"}, null, -1, -1, i -> layers[i].run());
            boolean locked = sel.stream().allMatch(e -> e.locked);
            Runnable[] acts = {Editor::duplicate, Editor::deleteSelection, Editor::group, Editor::ungroup,
                    () -> Editor.editSelection(e -> {
                        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.getUUID().equals(e.owner)
                                || info != null && info.op()) e.locked = !locked;
                    })};
            options(lay, "actions", new String[]{"#duplicate", "#delete", "#group", "#ungroup", locked ? "#unlock" : "#lock"}, null, -1, -1,
                    i -> acts[i].run());
        }
        panelContentH = lay.bottom() - (panelY - panelScroll);
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

    private static void label(PanelLayout lay, String key) {
        labels.add(new int[]{lay.innerX(), lay.label()});
        labelKeys.add(key);
    }

    /** A labelled colour row: swatches, then the current-colour chip that opens the picker. */
    private static void swatches(PanelLayout lay, String key, int[] colors, int current, ColorPicker.Target target, IntConsumer pick) {
        label(lay, key);
        PanelLayout.Rect[] r = lay.swatches(colors.length);
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            buttons.add(new Btn(r[i].x(), r[i].y(), r[i].w(), r[i].h(), c == 0 ? TRANSPARENT : "", current == c, c,
                    () -> pick.accept(c), c == 0 ? tr("transparent") : hexOf(c), true, false));
        }
        chip(r[colors.length], target, current);
    }

    /**
     * A labelled row of equal-width buttons.
     *
     * @param labels # + atlas id = icon ("@m" = mirrored), else text shown as is
     * @param tips   lang keys under mapsketch.ui. (null entry = none); null = derive from the icon ids
     * @param active selected index, -1 for none (action rows)
     * @param disabled index drawn greyed that ignores clicks (its tip says why), -1 for none
     */
    private static void options(PanelLayout lay, String key, String[] labels, String[] tips, int active, int disabled, IntConsumer pick) {
        label(lay, key);
        PanelLayout.Rect[] r = lay.row(labels.length);
        for (int i = 0; i < labels.length; i++) {
            int fi = i;
            String tipKey = tips != null ? tips[i] : labels[i].substring(1).replace("@m", "");
            buttons.add(new Btn(r[i].x(), r[i].y(), r[i].w(), r[i].h(), labels[i], i == active, 0, () -> pick.accept(fi),
                    tipKey == null ? "" : tr(tipKey), true, i == disabled));
        }
    }

    /** The current colour; clicking it opens (or closes) the picker beside the panel. */
    private static void chip(PanelLayout.Rect r, ColorPicker.Target target, int color) {
        chips.add(new int[]{r.x(), r.y(), r.w(), r.h(), color});
        buttons.add(new Btn(r.x(), r.y(), r.w(), r.h(), "", ColorPicker.target() == target, 0, () -> {
            if (ColorPicker.target() == target) ColorPicker.close(true);
            else ColorPicker.open(target, panelX + panelW + 4, r.y(), Minecraft.getInstance().getWindow().getGuiScaledWidth(), screenH);
        }, tr("pick_color"), true, false));
    }

    private static String hexOf(int c) {
        return com.volcanicmg.mapsketch.model.Colors.hex(c);
    }

    // ---- controls guide ----

    // Top edge for the guide (below Xaero's top-right widgets), set each frame by the map hook.
    private static int hintsTop = 4;
    // The guide's ? button {x, y, w, h} this frame, or null.
    private static int[] hintBtn;

    /** @param top the lowest bottom edge of Xaero's widgets in the top-right corner, in GUI px */
    public static void setHintsTop(int top) {
        hintsTop = top;
    }

    /** Shows or hides the guide and remembers the choice in the client config. */
    static void toggleHints() {
        Config.SHOW_HINTS.set(!Config.SHOW_HINTS.get());
        Config.SHOW_HINTS.save();
    }

    private static String hintText(String key) {
        return key.equals("@toggle") ? MapSketchKeys.TOGGLE.getTranslatedKeyMessage().getString() : Component.translatable(key).getString();
    }

    /** @return the guide's sections for the current state: what is happening now first, then General */
    private static List<String> hintSections() {
        String now = Editor.typingText() ? "typing" : Editor.placingLineEnd() ? "attach" : Editor.tool().name().toLowerCase();
        // A fresh arrow/line keeps its own section (it already says how to attach) until an end is actually being placed.
        if (now.equals("attach") && Editor.bindPreview() == null && (Editor.tool() == Editor.Tool.ARROW || Editor.tool() == Editor.Tool.LINE))
            now = Editor.tool().name().toLowerCase();
        return List.of(now, "general");
    }

    /**
     * The "Controls" guide, docked top-right in draw mode: key caps and actions for the current tool or state, then the
     * general controls; collapsed to its ? button when hidden. Hidden while a popover would overlap it; only the button
     * takes input.
     */
    private static void hints(GuiGraphics g, int screenW, int mx, int my) {
        hintBtn = null;
        if (!Editor.drawMode() || !Config.ENABLED.get()) return;
        Font font = Minecraft.getInstance().font;
        int w = Hints.WIDTH, x = screenW - 4 - w, y = Math.max(hintsTop, TOOLBAR_Y + PanelLayout.TOOL + 8);
        boolean open = Config.SHOW_HINTS.get();
        Hints.Laid laid = open ? Hints.layout(hintSections(), EditorUi::hintText, font::width) : null;
        // Short screens: drop General rather than run off the bottom.
        if (open && y + laid.height() + 2 * Hints.PAD + 20 > screenH - 4)
            laid = Hints.layout(hintSections().subList(0, 1), EditorUi::hintText, font::width);
        int h = open ? Hints.PAD + 18 + 2 + laid.height() + Hints.PAD : 20;
        int bx = open ? x + w - 4 - 18 : screenW - 4 - 20, by = open ? y + 4 : y, bs = open ? 18 : 20;
        int[] area = open ? new int[]{x, y, w, h} : new int[]{bx, by, bs, bs};
        for (int[] p : new int[][]{ColorPicker.bounds(), StampPicker.bounds()})
            if (p != null && p[0] < area[0] + area[2] && area[0] < p[0] + p[2] && p[1] < area[1] + area[3] && area[1] < p[1] + p[3]) return;
        hintBtn = new int[]{bx, by, bs, bs};
        if (open) {
            VanillaUi.panel(g, x, y, w, h);
            g.drawString(font, Component.translatable("mapsketch.hint.title"), x + Hints.PAD + 1, y + Hints.PAD + 2, PanelLayout.LABEL, false);
            int cx = x + Hints.PAD, cy = y + Hints.PAD + 20;
            for (Hints.Item it : laid.items()) {
                if (it.cap()) keyCap(g, font, cx + it.x(), cy + it.y(), it.w(), it.text());
                else if (it.title()) {
                    g.drawString(font, it.text(), cx + it.x() + 1, cy + it.y(), PanelLayout.LABEL, false);
                    g.fill(cx, cy + it.y() + 9, cx + it.w(), cy + it.y() + 10, PanelLayout.SLOT);
                } else g.drawString(font, it.text(), cx + it.x(), cy + it.y(), 0xFF555555, false);
            }
        }
        boolean hover = mx >= bx && mx < bx + bs && my >= by && my < by + bs;
        VanillaUi.button(g, bx, by, bs, bs, false, false, hover);
        UiIcons.draw(g, "help", bx, by, bs, bs, UiIcons.NORMAL);
    }

    /** A small vanilla-style key cap: dark outline, white / grey bevel, the key's label. */
    private static void keyCap(GuiGraphics g, Font font, int x, int y, int w, String label) {
        g.fill(x, y, x + w, y + Hints.CAP_H, PanelLayout.SLOT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + Hints.CAP_H - 1, PanelLayout.PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, PanelLayout.HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 2, y + Hints.CAP_H - 2, PanelLayout.HIGHLIGHT);
        g.fill(x + 2, y + Hints.CAP_H - 2, x + w - 1, y + Hints.CAP_H - 1, PanelLayout.SLOT);
        g.fill(x + w - 2, y + 2, x + w - 1, y + Hints.CAP_H - 1, PanelLayout.SLOT);
        g.drawString(font, label, x + 3, y + 2, PanelLayout.LABEL, false);
    }

    private static boolean hintHit(double gx, double gy) {
        return hintBtn != null && gx >= hintBtn[0] && gx < hintBtn[0] + hintBtn[2] && gy >= hintBtn[1] && gy < hintBtn[1] + hintBtn[3];
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
        if (button == 0 && hintHit(gx, gy)) {
            toggleHints();
            return true;
        }
        if (button != 0) return over(gx, gy);
        if (panelW > 0 && gx >= sliderX && gx < sliderX + sliderW && gy >= sliderY && gy < sliderY + sliderH && inPanelView(gy)) {
            sliderDrag = true;
            slide(gx);
            return true;
        }
        for (Btn b : buttons)
            if (b.hit(gx, gy)) {
                if (!b.disabled()) b.action().run();
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
        if (ColorPicker.over(gx, gy) || StampPicker.over(gx, gy) || hintHit(gx, gy)) return true;
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
        if (StampPicker.open() && StampPicker.mouseScrolled(gx, gy, delta)) return true;
        if (panelW == 0 || gx < panelX || gx >= panelX + panelW || !inPanelView(gy)) return false;
        // Over the panel the wheel scrolls it (clamped next layout), never zooms Xaero.
        panelScroll = Math.max(0, panelScroll - (int) Math.round(delta * 24));
        return true;
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
        boolean popover = ColorPicker.over(mouseX, mouseY) || StampPicker.over(mouseX, mouseY);
        int mx = popover ? Integer.MIN_VALUE : mouseX, my = popover ? Integer.MIN_VALUE : mouseY;
        if (panelW > 0) {
            VanillaUi.panel(g, panelX, panelY, panelW, panelH);
            g.enableScissor(panelX + 3, panelY + 3, panelX + panelW - 3, panelY + panelH - 3);
            // Always pop the scissor: a throw here must not leave Xaero's map clipped for the session.
            try {
                for (int i = 0; i < labels.size(); i++)
                    g.drawString(font, Component.translatable("mapsketch.ui." + labelKeys.get(i)), labels.get(i)[0] + 1, labels.get(i)[1], PanelLayout.LABEL, false);
                boolean sliderHover = sliderDrag || mx >= sliderX && mx < sliderX + sliderW && my >= sliderY && my < sliderY + sliderH && inPanelView(my);
                VanillaUi.slider(g, sliderX, sliderY, sliderW, sliderH, Editor.style.opacity / 100.0, sliderHover);
                Component op = Component.translatable("mapsketch.ui.opacity_value", Editor.style.opacity);
                g.drawCenteredString(font, op, sliderX + sliderW / 2, sliderY + (sliderH - 8) / 2, sliderHover ? 0xFFFFFFA0 : 0xFFFFFFFF);
                buttons(g, true, mx, my);
            } finally {
                g.disableScissor();
            }
            if (panelContentH > panelH) {
                int track = panelH - 8, thumb = Math.max(12, track * panelH / panelContentH);
                int ty = panelY + 4 + (track - thumb) * panelScroll / (panelContentH - panelH);
                VanillaUi.scroller(g, panelX + panelW - SCROLLBAR - 2, panelY + 4, track, ty, thumb);
            }
        }
        Btn first = buttons.isEmpty() ? null : buttons.get(0);
        if (first != null && !first.panel()) {
            Btn last = first;
            for (Btn b : buttons) if (!b.panel()) last = b;
            VanillaUi.panel(g, first.x() - 4, first.y() - 4, last.x() + last.w() - first.x() + 8, last.h() + 8);
        }
        buttons(g, false, mx, my);
        hints(g, screenW, mx, my);
        ColorPicker.render(g);
        StampPicker.render(g, screenW, screenH, mouseX, mouseY);
        Btn hover = null;
        for (Btn b : buttons) if (b.hit(mx, my)) hover = b;
        if (hover != null && !hover.tip().isEmpty()) g.renderTooltip(font, Component.literal(hover.tip()), mouseX, mouseY + 12);
        else if (hintHit(mx, my)) g.renderTooltip(font, Component.translatable("mapsketch.hint.toggle"), mouseX, mouseY + 12);
    }

    /**
     * Draws the panel's or the toolbar's buttons: vanilla button sprites (highlighted on hover, greyed when disabled),
     * a selected toggle as an inventory-slot inset with white ink, swatches with a white ring when selected; then the
     * pixel icons and labels on top.
     */
    private static void buttons(GuiGraphics g, boolean panel, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        for (Btn b : buttons) {
            if (b.panel() != panel) continue;
            if (b.label().equals(TRANSPARENT) || b.swatch() != 0) {
                VanillaUi.swatch(g, b.x(), b.y(), b.w(), b.h(), b.label().equals(TRANSPARENT) ? 0 : b.swatch(), b.active());
                if (!b.active() && b.hit(mx, my)) g.fill(b.x() + 1, b.y() + 1, b.x() + b.w() - 1, b.y() + b.h() - 1, 0x40FFFFFF);
                continue;
            }
            VanillaUi.button(g, b.x(), b.y(), b.w(), b.h(), b.active(), b.disabled(), b.hit(mx, my));
        }
        for (int[] c : chips)
            if (panel) VanillaUi.swatch(g, c[0] + 3, c[1] + 3, c[2] - 6, c[3] - 6, c[4], false);
        for (Btn b : buttons) {
            String l = b.label();
            if (b.panel() != panel || l.isEmpty() || l.equals(TRANSPARENT) || b.swatch() != 0) continue;
            int ink = VanillaUi.ink(b.active(), b.disabled());
            if (l.startsWith("#")) UiIcons.draw(g, l.substring(1), b.x(), b.y(), b.w(), b.h(), ink);
            else if (l.startsWith(STAMP_LABEL)) {
                String name = font.plainSubstrByWidth(l.substring(STAMP_LABEL.length()), b.w() - b.h() - 6);
                g.drawString(font, name, b.x() + b.h() + 2, b.y() + (b.h() - 8) / 2, ink, false);
            } else g.drawString(font, l, b.x() + (b.w() - font.width(l) + 1) / 2, b.y() + (b.h() - 8) / 2, ink, false);
        }
        if (panel && stampChip != null) StampPicker.icon(g, stampChipId, stampChip[0], stampChip[1], stampChip[2]);
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
                // The outline follows the holder's live ghost while they drag it.
                Element gh = ClientStore.ghostOf(id);
                if (gh != null) e = gh;
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
