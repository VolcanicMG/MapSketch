package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Rules;
import com.volcanicmg.mapsketch.render.PixelArt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * Stamp picker popover under the toolbar: a search field (focused, with a clear button), tabs for Symbols, Map
 * icons and Items, a shared recent row, and the grid. Typing searches all three at once and shows grouped results;
 * {@code @modid} limits items to a mod. Arrow keys move a highlight, Enter picks it, Esc clears then closes.
 */
final class StampPicker {
    private enum Tab { SYMBOLS, DECOS, ITEMS }

    private static final int BG = 0xFFF8F9FA, BORDER = 0xFF9AA0A6, ACCENT = 0xFF4C6EF5, COLS = 9, LINES = 7;
    private static final List<String> recent = new ArrayList<>();
    private static boolean open, forTool;
    private static String search = "", builtFor;
    private static Tab tab = Tab.SYMBOLS;
    private static int scroll, highlight, x, y;
    // Display lines (a header String or a row of ids) and the ids in display order, rebuilt when search/tab change.
    private static List<Object> lines = List.of();
    private static List<String> results = List.of();
    // Hit boxes drawn this frame.
    private static final List<int[]> cells = new ArrayList<>();
    private static final List<String> cellIds = new ArrayList<>();
    private static int[] clearBox, prevBox, nextBox;
    private static final int[][] tabBoxes = new int[3][];

    private StampPicker() {
    }

    private static int u(double px) {
        return (int) Math.round(px * Config.UI_SCALE.get());
    }

    static boolean open() {
        return open;
    }

    /** @param tool opened by the Stamp tool (picks set the tool's stamp only) or from the panel (also the selection) */
    static void open(boolean tool) {
        open = true;
        forTool = tool;
        search = "";
        builtFor = null;
        scroll = highlight = 0;
    }

    static void close() {
        open = false;
    }

    private static int cell() {
        return u(18);
    }

    private static int width() {
        return u(8) + COLS * cell();
    }

    private static int height() {
        return u(8) + u(14) + u(4) + u(14) + u(4) + u(10) + cell() + u(4) + LINES * cell() + u(14);
    }

    static boolean over(double gx, double gy) {
        return open && gx >= x && gx < x + width() && gy >= y && gy < y + height();
    }

    // ---- search ----

    // Every item id with its lower-cased display name, built once (names need an ItemStack each).
    private static List<String[]> index;

    private static List<String[]> index() {
        if (index != null) return index;
        List<String[]> out = new ArrayList<>();
        boolean logged = false;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString(), name = id;
            try {
                ItemStack st = new ItemStack(item);
                if (st.isEmpty()) continue;
                name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
            } catch (Throwable t) {
                // A mod item that cannot even name itself stays searchable by id; log once.
                if (!logged) com.volcanicmg.mapsketch.MapSketch.LOGGER.warn("Stamp picker: cannot name item {}", id, t);
                logged = true;
            }
            out.add(new String[]{id, name});
        }
        out.sort(java.util.Comparator.comparing(e -> e[0]));
        index = out;
        return index;
    }

    /** Rebuilds the display lines for the current search or tab. */
    private static void build() {
        String key = search + "\u0000" + tab;
        if (key.equals(builtFor)) return;
        builtFor = key;
        List<Object> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String q = search.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            List<String> all = switch (tab) {
                case SYMBOLS -> PixelArt.names().stream().map(n -> "sym:" + n).toList();
                case DECOS -> Rules.DECORATIONS.stream().map(n -> "deco:" + n).toList();
                case ITEMS -> index().stream().map(e -> "item:" + e[0]).toList();
            };
            group(out, ids, null, all);
        } else {
            // "@mod rest": items of that namespace only (prefix match), filtered by the rest.
            String ns = null, term = q;
            if (q.startsWith("@")) {
                int sp = q.indexOf(' ');
                ns = sp < 0 ? q.substring(1) : q.substring(1, sp);
                term = sp < 0 ? "" : q.substring(sp + 1).trim();
            }
            if (ns == null) {
                List<String> syms = new ArrayList<>(), decos = new ArrayList<>();
                for (String n : PixelArt.names()) if (n.contains(term) || name("sym:" + n).toLowerCase(Locale.ROOT).contains(term)) syms.add("sym:" + n);
                for (String n : Rules.DECORATIONS) if (n.contains(term) || pretty(n).toLowerCase(Locale.ROOT).contains(term)) decos.add("deco:" + n);
                group(out, ids, tr("stamp_tab_symbols"), syms);
                group(out, ids, tr("stamp_tab_decos"), decos);
            }
            List<String> items = new ArrayList<>();
            for (String[] e : index()) {
                if (ns != null && !e[0].substring(0, e[0].indexOf(':')).startsWith(ns)) continue;
                if (term.isEmpty() || e[0].contains(term) || e[1].contains(term)) items.add("item:" + e[0]);
            }
            group(out, ids, tr("stamp_tab_items") + " (" + items.size() + ")", items);
        }
        lines = out;
        results = ids;
        scroll = 0;
        highlight = 0;
    }

    private static void group(List<Object> out, List<String> ids, String header, List<String> group) {
        if (group.isEmpty()) return;
        if (header != null) out.add(header);
        for (int i = 0; i < group.size(); i += COLS) out.add(group.subList(i, Math.min(group.size(), i + COLS)));
        ids.addAll(group);
    }

    private static String tr(String key) {
        return Component.translatable("mapsketch.ui." + key).getString();
    }

    private static void pick(String id) {
        Editor.style.stamp = id;
        recent.remove(id);
        recent.add(0, id);
        if (recent.size() > 8) recent.remove(8);
        if (!forTool) Editor.editSelection(e -> {
            if (e.type == Element.Type.STAMP) e.stamp = id;
        });
        close();
    }

    // ---- input ----

    /** @return true when consumed; a click outside closes the picker and goes on to the map (placing a stamp) */
    static boolean mousePressed(double gx, double gy, int button) {
        if (!open) return false;
        if (!over(gx, gy)) {
            if (button == 0) close();
            return false;
        }
        if (button != 0) return true;
        if (hit(clearBox, gx, gy)) {
            search = "";
            return true;
        }
        for (int t = 0; t < 3; t++)
            if (hit(tabBoxes[t], gx, gy)) {
                tab = Tab.values()[t];
                search = "";
                return true;
            }
        if (hit(prevBox, gx, gy)) scroll = Math.max(0, scroll - LINES);
        if (hit(nextBox, gx, gy)) scroll = Math.min(Math.max(0, lines.size() - LINES), scroll + LINES);
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            if (gx >= c[0] && gx < c[0] + c[2] && gy >= c[1] && gy < c[1] + c[2]) {
                pick(cellIds.get(i));
                return true;
            }
        }
        return true;
    }

    static boolean mouseScrolled(double gx, double gy, double delta) {
        if (!over(gx, gy)) return false;
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - LINES), scroll - (int) Math.signum(delta)));
        return true;
    }

    private static boolean hit(int[] b, double gx, double gy) {
        return b != null && gx >= b[0] && gx < b[0] + b[2] && gy >= b[1] && gy < b[1] + b[3];
    }

    // Ctrl+A selected the whole search: the next character or Backspace replaces it.
    private static boolean all;

    /** While open the search field takes every key (Ctrl+A/C/V/X act on the whole search). */
    static boolean keyPressed(int key, int mods) {
        if (!open) return false;
        build();
        if (EditorUi.ctrl(mods)) {
            switch (key) {
                case GLFW.GLFW_KEY_A -> all = !search.isEmpty();
                case GLFW.GLFW_KEY_C -> EditorUi.setClipboard(search);
                case GLFW.GLFW_KEY_X -> {
                    EditorUi.setClipboard(search);
                    search = "";
                }
                case GLFW.GLFW_KEY_V -> {
                    String add = EditorUi.clipboard().replaceAll("[\\p{Cntrl}]", "");
                    search = ((all ? "" : search) + add).substring(0, Math.min(40, (all ? 0 : search.length()) + add.length()));
                    all = false;
                }
                default -> {
                }
            }
            return true;
        }
        boolean wasAll = all;
        all = false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (!search.isEmpty()) search = "";
                else close();
            }
            case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
                if (wasAll) search = "";
                else if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) search = search.substring(0, search.length() - 1);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!results.isEmpty()) pick(results.get(Math.min(highlight, results.size() - 1)));
            }
            case GLFW.GLFW_KEY_RIGHT -> move(1);
            case GLFW.GLFW_KEY_LEFT -> move(-1);
            case GLFW.GLFW_KEY_DOWN -> move(COLS);
            case GLFW.GLFW_KEY_UP -> move(-COLS);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll = Math.min(Math.max(0, lines.size() - LINES), scroll + LINES);
            case GLFW.GLFW_KEY_PAGE_UP -> scroll = Math.max(0, scroll - LINES);
            default -> {
            }
        }
        return true;
    }

    /** Moves the highlight and scrolls so its row is visible. */
    private static void move(int d) {
        if (results.isEmpty()) return;
        highlight = Math.max(0, Math.min(results.size() - 1, highlight + d));
        String id = results.get(highlight);
        for (int l = 0; l < lines.size(); l++)
            if (lines.get(l) instanceof List<?> row && row.contains(id)) {
                if (l < scroll) scroll = l;
                if (l >= scroll + LINES) scroll = l - LINES + 1;
                break;
            }
    }

    static boolean charTyped(char c) {
        if (!open) return false;
        if (all) search = "";
        all = false;
        if (c >= ' ' && search.length() < 40) search += c;
        return true;
    }

    // ---- rendering ----

    /** Draws the popover centred under the toolbar, kept on screen. */
    static void render(GuiGraphics g, int screenW, int screenH, int mouseX, int mouseY) {
        cells.clear();
        cellIds.clear();
        if (!open) return;
        build();
        Font font = Minecraft.getInstance().font;
        int w = width(), h = height(), c = cell(), pad = u(4);
        x = Math.max(2, Math.min(screenW / 2 - w / 2, screenW - w - 2));
        y = Math.max(2, Math.min(24 + u(22), screenH - h - 2));
        String hover = null;
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, 300);
            g.fill(x, y, x + w, y + h, BG);
            outline(g, x, y, w, h, BORDER);
            // Search field with a clear button.
            int yy = y + pad, fh = u(14);
            g.fill(x + pad, yy, x + w - pad, yy + fh, 0xFFFFFFFF);
            outline(g, x + pad, yy, w - pad * 2, fh, ACCENT);
            boolean empty = search.isEmpty();
            String caret = System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "";
            if (all && !empty) g.fill(x + pad + 2, yy + 2, x + pad + 4 + font.width(search), yy + fh - 2, 0xFFC9D7FF);
            g.drawString(font, empty ? caret + tr("stamp_search") : search + caret, x + pad + 3, yy + (fh - 8) / 2,
                    empty ? 0xFF9AA0A6 : 0xFF1E1E1E, false);
            clearBox = empty ? null : new int[]{x + w - pad - fh, yy, fh, fh};
            if (clearBox != null) g.drawString(font, "×", clearBox[0] + (fh - font.width("×")) / 2, yy + (fh - 8) / 2, 0xFF5F6368, false);
            yy += fh + u(4);
            // Tabs (greyed while a search shows everything).
            int tw = (w - pad * 2 - u(4)) / 3;
            String[] names = {tr("stamp_tab_symbols"), tr("stamp_tab_decos"), tr("stamp_tab_items")};
            for (int t = 0; t < 3; t++) {
                int tx = x + pad + t * (tw + u(2));
                tabBoxes[t] = new int[]{tx, yy, tw, fh};
                boolean on = empty && tab.ordinal() == t;
                if (on) g.fill(tx, yy, tx + tw, yy + fh, 0xFFC9D7FF);
                outline(g, tx, yy, tw, fh, on ? ACCENT : BORDER);
                g.drawString(font, names[t], tx + (tw - font.width(names[t])) / 2, yy + (fh - 8) / 2, empty ? 0xFF1E1E1E : 0xFF9AA0A6, false);
            }
            yy += fh + u(4);
            g.drawString(font, tr("stamp_recent"), x + pad, yy + u(1), 0xFF5F6368, false);
            yy += u(10);
            hover = row(g, recent, yy, mouseX, mouseY, hover, false);
            yy += c + u(4);
            // Results / tab content, scrolled by lines.
            int bottom = yy + LINES * c;
            for (int l = scroll; l < lines.size() && yy + c <= bottom + 1; l++) {
                Object line = lines.get(l);
                if (line instanceof String header) {
                    g.drawString(font, header, x + pad, yy + u(2), 0xFF5F6368, false);
                    yy += u(11);
                } else {
                    @SuppressWarnings("unchecked") List<String> ids = (List<String>) line;
                    hover = row(g, ids, yy, mouseX, mouseY, hover, true);
                    yy += c;
                }
            }
            if (results.isEmpty()) g.drawString(font, tr("stamp_none"), x + pad, yy + u(2), 0xFF9AA0A6, false);
            int py = y + h - u(14);
            prevBox = new int[]{x + pad, py, u(14), u(12)};
            nextBox = new int[]{x + w - pad - u(14), py, u(14), u(12)};
            boolean more = scroll + LINES < lines.size();
            g.drawString(font, "▲", prevBox[0] + 3, py + 2, scroll > 0 ? 0xFF1E1E1E : 0xFFCCCCCC, false);
            g.drawString(font, "▼", nextBox[0] + 3, py + 2, more ? 0xFF1E1E1E : 0xFFCCCCCC, false);
            String count = results.size() + "";
            g.drawString(font, count, x + w / 2 - font.width(count) / 2, py + 2, 0xFF5F6368, false);
        } finally {
            g.pose().popPose();
        }
        if (hover != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name(hover)));
            if (!hover.startsWith("sym:")) tip.add(Component.literal(hover).withStyle(net.minecraft.ChatFormatting.GRAY));
            g.renderComponentTooltip(font, tip, mouseX, mouseY + 12);
        }
    }

    /** One row of stamp cells; returns the hovered id (or the previous one). */
    private static String row(GuiGraphics g, List<String> ids, int top, int mouseX, int mouseY, String hover, boolean results) {
        int c = cell(), x0 = x + u(4);
        for (int i = 0; i < Math.min(ids.size(), COLS); i++) {
            int cx = x0 + i * c;
            String id = ids.get(i);
            boolean hl = results && !StampPicker.results.isEmpty() && id.equals(StampPicker.results.get(Math.min(highlight, StampPicker.results.size() - 1)));
            if (id.equals(Editor.style.stamp)) g.fill(cx, top, cx + c, top + c, 0xFFC9D7FF);
            boolean over = mouseX >= cx && mouseX < cx + c && mouseY >= top && mouseY < top + c;
            if (over || hl) outline(g, cx, top, c, c, ACCENT);
            icon(g, id, cx + 1, top + 1, c - 2);
            cells.add(new int[]{cx, top, c});
            cellIds.add(id);
            if (over) hover = id;
        }
        return hover;
    }

    /** Draws a stamp in a GUI square through the same textured path as on the map (pixel symbols in the stroke colour). */
    static void icon(GuiGraphics g, String id, int ix, int iy, int size) {
        Element e = new Element();
        e.id = new java.util.UUID(id.hashCode(), size);
        e.dim = "";
        e.type = Element.Type.STAMP;
        e.stamp = id;
        e.x = ix + size * 0.06;
        e.z = iy + size * 0.06;
        e.w = e.h = size * 0.88;
        e.stroke = Editor.style.stroke;
        e.roughness = 0;
        SketchRenderer.drawLive(g, List.of(e), 0, 0, 1, 1f);
    }

    /** @return a readable name: the symbol's translated name, the map icon's, or the item's */
    static String name(String id) {
        if (id.startsWith("sym:")) return Component.translatable("mapsketch.stamp." + id.substring(4)).getString();
        if (id.startsWith("deco:")) return pretty(id.substring(5));
        ItemStack st = SketchRenderer.stack(id);
        return st == null ? id : st.getHoverName().getString();
    }

    /** {@code woodland_mansion} → {@code Woodland mansion}. */
    private static String pretty(String n) {
        String s = n.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void outline(GuiGraphics g, int x0, int y0, int w, int h, int c) {
        g.fill(x0, y0, x0 + w, y0 + 1, c);
        g.fill(x0, y0 + h - 1, x0 + w, y0 + h, c);
        g.fill(x0, y0, x0 + 1, y0 + h, c);
        g.fill(x0 + w - 1, y0, x0 + w, y0 + h, c);
    }
}
