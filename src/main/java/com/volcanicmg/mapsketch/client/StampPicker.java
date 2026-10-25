package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.render.Symbols;
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
 * Stamp picker popover under the toolbar: search box, the built-in symbols, recent stamps, and a paged grid of
 * every item/block icon filtered by the search. Picking sets the Stamp tool's stamp, and the stamp of selected
 * stamps when opened from the panel.
 */
final class StampPicker {
    private static final int BG = 0xF0FFFFFF, BORDER = 0xFF9AA0A6, ACCENT = 0xFF4C6EF5, COLS = 9, ROWS = 5;
    private static final List<String> recent = new ArrayList<>();
    private static boolean open, forTool;
    private static String search = "", filteredFor;
    private static List<String> items = List.of();
    private static int page, x, y;
    // Cells drawn this frame {x, y, size} with their stamp ids, for clicks and tooltips.
    private static final List<int[]> cells = new ArrayList<>();
    private static final List<String> cellIds = new ArrayList<>();
    private static int[] prev, next;

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
        page = 0;
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
        return u(8) + u(14) + u(10) + 2 * cell() + u(10) + cell() + u(10) + ROWS * cell() + u(14);
    }

    static boolean over(double gx, double gy) {
        return open && gx >= x && gx < x + width() && gy >= y && gy < y + height();
    }

    // Every item id with its lower-cased display name, built once (names need an ItemStack each).
    private static List<String[]> index;
    // Item ids whose icon failed to render: drawn as a tile from then on.
    private static final java.util.Set<String> broken = new java.util.HashSet<>();

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
                // A mod item that cannot even name itself: keep it searchable by id, log once.
                if (!logged) com.volcanicmg.mapsketch.MapSketch.LOGGER.warn("Stamp picker: cannot name item {}", id, t);
                logged = true;
            }
            out.add(new String[]{id, name});
        }
        out.sort(java.util.Comparator.comparing(e -> e[0]));
        index = out;
        return index;
    }

    /** All item ids, sorted, matching the search in their id or name. Recomputed only when the search changes. */
    private static List<String> filtered() {
        if (search.equals(filteredFor)) return items;
        String q = search.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String[] e : index()) if (q.isEmpty() || e[0].contains(q) || e[1].contains(q)) out.add(e[0]);
        items = out;
        filteredFor = search;
        page = 0;
        return items;
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
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            if (gx >= c[0] && gx < c[0] + c[2] && gy >= c[1] && gy < c[1] + c[2]) {
                pick(cellIds.get(i));
                return true;
            }
        }
        int pages = Math.max(1, (filtered().size() + COLS * ROWS - 1) / (COLS * ROWS));
        if (hit(prev, gx, gy)) page = Math.max(0, page - 1);
        if (hit(next, gx, gy)) page = Math.min(pages - 1, page + 1);
        return true;
    }

    private static boolean hit(int[] b, double gx, double gy) {
        return b != null && gx >= b[0] && gx < b[0] + b[2] && gy >= b[1] && gy < b[1] + b[3];
    }

    /** While open the search box takes every key: Esc closes, Enter picks the first result. */
    static boolean keyPressed(int key) {
        if (!open) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> close();
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                List<String> f = filtered();
                for (String s : Symbols.names()) if (s.contains(search.toLowerCase(Locale.ROOT)) && !search.isEmpty()) {
                    pick("sym:" + s);
                    return true;
                }
                if (!f.isEmpty()) pick("item:" + f.get(0));
            }
            case GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_RIGHT -> page++;
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_LEFT -> page = Math.max(0, page - 1);
            default -> {
            }
        }
        return true;
    }

    static boolean charTyped(char c) {
        if (!open) return false;
        if (c >= ' ' && search.length() < 40) search += c;
        return true;
    }

    // ---- rendering ----

    /** Draws the popover centred under the toolbar, kept on screen. */
    static void render(GuiGraphics g, int screenW, int screenH, int mouseX, int mouseY) {
        cells.clear();
        cellIds.clear();
        if (!open) return;
        Font font = Minecraft.getInstance().font;
        int w = width(), h = height(), c = cell(), pad = u(4);
        x = Math.max(2, Math.min(screenW / 2 - w / 2, screenW - w - 2));
        y = Math.max(2, Math.min(24 + u(22), screenH - h - 2));
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, 300);
            g.fill(x, y, x + w, y + h, BG);
            outline(g, x, y, w, h, BORDER);
            int yy = y + pad;
            g.fill(x + pad, yy, x + w - pad, yy + u(14), 0xFFFFFFFF);
            outline(g, x + pad, yy, w - pad * 2, u(14), ACCENT);
            String shown = search.isEmpty() ? Component.translatable("mapsketch.ui.stamp_search").getString() : search;
            g.drawString(font, shown + (System.currentTimeMillis() / 400 % 2 == 0 ? "_" : ""), x + pad + 3, yy + (u(14) - 8) / 2,
                    search.isEmpty() ? 0xFF9AA0A6 : 0xFF1E1E1E, false);
            yy += u(14);
            yy = label(g, font, "stamp_symbols", yy);
            List<String> syms = new ArrayList<>();
            for (String s : Symbols.names()) syms.add("sym:" + s);
            grid(g, syms, yy, 2, mouseX, mouseY);
            yy += 2 * c;
            yy = label(g, font, "stamp_recent", yy);
            grid(g, recent, yy, 1, mouseX, mouseY);
            yy += c;
            yy = label(g, font, "stamp_items", yy);
            List<String> f = filtered();
            int pages = Math.max(1, (f.size() + COLS * ROWS - 1) / (COLS * ROWS));
            page = Math.min(page, pages - 1);
            List<String> shownItems = new ArrayList<>();
            for (int i = page * COLS * ROWS; i < Math.min(f.size(), (page + 1) * COLS * ROWS); i++) shownItems.add("item:" + f.get(i));
            grid(g, shownItems, yy, ROWS, mouseX, mouseY);
            yy += ROWS * c;
            prev = new int[]{x + pad, yy + 1, u(14), u(12)};
            next = new int[]{x + w - pad - u(14), yy + 1, u(14), u(12)};
            g.drawString(font, "◀", prev[0] + 3, prev[1] + 2, page > 0 ? 0xFF1E1E1E : 0xFFCCCCCC, false);
            g.drawString(font, "▶", next[0] + 3, next[1] + 2, page < pages - 1 ? 0xFF1E1E1E : 0xFFCCCCCC, false);
            String pg = (page + 1) + " / " + pages + "  (" + f.size() + ")";
            g.drawString(font, pg, x + w / 2 - font.width(pg) / 2, yy + 3, 0xFF5F6368, false);
        } finally {
            g.pose().popPose();
        }
        for (int i = 0; i < cells.size(); i++) {
            int[] cl = cells.get(i);
            if (mouseX >= cl[0] && mouseX < cl[0] + cl[2] && mouseY >= cl[1] && mouseY < cl[1] + cl[2]) {
                g.renderTooltip(font, Component.literal(name(cellIds.get(i))), mouseX, mouseY + 12);
                break;
            }
        }
    }

    private static int label(GuiGraphics g, Font font, String key, int yy) {
        g.drawString(font, Component.translatable("mapsketch.ui." + key), x + u(4), yy + u(2), 0xFF5F6368, false);
        return yy + u(10);
    }

    /** One grid of stamp cells; symbols are tessellated in the stroke colour, items use the item renderer. */
    private static void grid(GuiGraphics g, List<String> ids, int top, int rows, int mouseX, int mouseY) {
        int c = cell(), x0 = x + u(4);
        for (int i = 0; i < Math.min(ids.size(), rows * COLS); i++) {
            int cx = x0 + i % COLS * c, cy = top + i / COLS * c;
            String id = ids.get(i);
            boolean sel = id.equals(Editor.style.stamp);
            if (sel) g.fill(cx, cy, cx + c, cy + c, 0xFFC9D7FF);
            if (mouseX >= cx && mouseX < cx + c && mouseY >= cy && mouseY < cy + c) outline(g, cx, cy, c, c, ACCENT);
            icon(g, id, cx + 1, cy + 1, c - 2);
            cells.add(new int[]{cx, cy, c});
            cellIds.add(id);
        }
    }

    /** Draws a stamp icon in a GUI square. */
    static void icon(GuiGraphics g, String id, int ix, int iy, int size) {
        if (id.startsWith("sym:")) {
            Element e = new Element();
            e.id = new java.util.UUID(id.hashCode(), size);
            e.dim = "";
            e.type = Element.Type.STAMP;
            e.stamp = id;
            e.x = ix + size * 0.1;
            e.z = iy + size * 0.1;
            e.w = e.h = size * 0.8;
            e.stroke = Editor.style.stroke;
            e.roughness = 0;
            SketchRenderer.drawLive(g, List.of(e), 0, 0, 1, 1f);
            return;
        }
        ItemStack st = SketchRenderer.stack(id);
        if (st == null) return;
        if (broken.contains(id)) {
            g.fill(ix + 2, iy + 2, ix + size - 2, iy + size - 2, 0x80000000 | Editor.style.stroke & 0xFFFFFF);
            return;
        }
        g.pose().pushPose();
        try {
            g.pose().translate(ix, iy, 0);
            g.pose().scale(size / 16f, size / 16f, 1);
            g.renderItem(st, 0, 0);
        } catch (Throwable t) {
            if (broken.isEmpty()) com.volcanicmg.mapsketch.MapSketch.LOGGER.warn("Stamp picker: item {} cannot render", id, t);
            broken.add(id);
        } finally {
            g.pose().popPose();
        }
    }

    /** @return a readable name: the item's name or the symbol's translated name */
    static String name(String id) {
        if (id.startsWith("sym:")) return Component.translatable("mapsketch.stamp." + id.substring(4)).getString();
        ItemStack st = SketchRenderer.stack(id);
        return st == null ? id : st.getHoverName().getString();
    }

    private static void outline(GuiGraphics g, int x0, int y0, int w, int h, int c) {
        g.fill(x0, y0, x0 + w, y0 + 1, c);
        g.fill(x0, y0 + h - 1, x0 + w, y0 + h, c);
        g.fill(x0, y0, x0 + 1, y0 + h, c);
        g.fill(x0 + w - 1, y0, x0 + w, y0 + h, c);
    }
}
