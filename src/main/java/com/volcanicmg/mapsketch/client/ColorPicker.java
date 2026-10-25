package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Colors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * Colour picker popover for the stroke or background colour: saturation/value square, hue bar, opacity bar,
 * old/new preview, palette, recent colours and a hex field. Changes preview live on the selection and the
 * next-shape style; closing with Enter or a click outside keeps them as one edit, Esc reverts.
 */
final class ColorPicker {
    enum Target { STROKE, FILL }

    private enum Part { NONE, SV, HUE, ALPHA }

    private static final int BG = 0xFFF8F9FA, BORDER = 0xFF9AA0A6, ACCENT = 0xFF4C6EF5;
    private static final List<Integer> recent = new ArrayList<>();

    private static Target target;
    private static int old, x, y;
    private static float h, s, v;
    private static int alpha;
    private static Part drag = Part.NONE;
    private static StringBuilder hex;

    private ColorPicker() {
    }

    private static int u(double px) {
        return (int) Math.round(px * Config.UI_SCALE.get());
    }

    static boolean open() {
        return target != null;
    }

    static Target target() {
        return target;
    }

    /** Opens for {@code t}, anchored right of the panel at {@code anchorY}, kept inside the screen. */
    static void open(Target t, int anchorX, int anchorY, int screenW, int screenH) {
        if (target != null) close(true);
        target = t;
        old = current();
        float[] hsv = Colors.rgbToHsv(old);
        h = hsv[0];
        s = hsv[1];
        v = hsv[2];
        alpha = Colors.alpha(old);
        // A transparent background opens as opaque white-ish so dragging shows something.
        if (t == Target.FILL && alpha == 0) alpha = 255;
        hex = null;
        x = Math.max(2, Math.min(anchorX, screenW - width() - 2));
        y = Math.max(2, Math.min(anchorY, screenH - height() - 2));
    }

    private static int current() {
        return target == Target.STROKE ? Editor.style.stroke : Editor.style.fill;
    }

    /** Ends the session: kept = one property change and undo step; otherwise the old colour comes back. */
    static void close(boolean keep) {
        if (target == null) return;
        int now = current();
        if (!keep) setStyle(old);
        Editor.endPreview(keep);
        if (keep && now != old) {
            recent.remove((Integer) now);
            recent.add(0, now);
            if (recent.size() > 8) recent.remove(8);
        }
        target = null;
        drag = Part.NONE;
        hex = null;
    }

    private static void setStyle(int c) {
        if (target == Target.STROKE) Editor.style.stroke = c;
        else Editor.style.fill = c;
    }

    /** Applies a colour to the style and previews it on the selection (local only). */
    private static void set(int c) {
        setStyle(c);
        Target t = target;
        Editor.previewSelection(e -> {
            if (t == Target.STROKE) e.stroke = c;
            else e.fill = c;
        });
    }

    private static void fromHsv() {
        set(Colors.argb(alpha, Colors.hsvToRgb(h, s, v)));
    }

    private static void pick(int c) {
        float[] hsv = Colors.rgbToHsv(c);
        if (hsv[1] > 0) h = hsv[0];
        s = hsv[1];
        v = hsv[2];
        alpha = Colors.alpha(c);
        set(c);
    }

    // ---- layout ----

    private static int pad() {
        return u(4);
    }

    private static int sq() {
        return u(100);
    }

    private static int bar() {
        return u(10);
    }

    private static int width() {
        return pad() * 4 + sq() + bar() * 2;
    }

    private static int height() {
        return pad() * 5 + sq() + u(14) * 3;
    }

    private static int[] svBox() {
        return new int[]{x + pad(), y + pad(), sq(), sq()};
    }

    private static int[] hueBox() {
        return new int[]{x + pad() * 2 + sq(), y + pad(), bar(), sq()};
    }

    private static int[] alphaBox() {
        return new int[]{x + pad() * 3 + sq() + bar(), y + pad(), bar(), sq()};
    }

    /** Old/new preview and hex field row. */
    private static int rowY(int i) {
        return y + pad() * (2 + i) + sq() + u(14) * i;
    }

    private static int[] palette() {
        return target == Target.STROKE ? EditorUi.STROKES : EditorUi.FILLS;
    }

    private static boolean in(int[] b, double gx, double gy) {
        return gx >= b[0] && gx < b[0] + b[2] && gy >= b[1] && gy < b[1] + b[3];
    }

    static boolean over(double gx, double gy) {
        return target != null && gx >= x && gx < x + width() && gy >= y && gy < y + height();
    }

    // ---- input ----

    /** @return true when consumed (always while open, except a middle click outside, which pans) */
    static boolean mousePressed(double gx, double gy, int button) {
        if (target == null) return false;
        if (!over(gx, gy)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return false;
            close(true);
            return true;
        }
        if (button != 0) return true;
        if (hex != null) applyHex();
        if (in(svBox(), gx, gy)) drag = Part.SV;
        else if (in(hueBox(), gx, gy)) drag = Part.HUE;
        else if (in(alphaBox(), gx, gy)) drag = Part.ALPHA;
        else {
            clickRows(gx, gy);
            return true;
        }
        update(gx, gy);
        return true;
    }

    private static void clickRows(double gx, double gy) {
        int cw = u(14), gap = u(2);
        // Row 0: old (click = back to it), new, hex field.
        int r0 = rowY(0);
        if (gy >= r0 && gy < r0 + cw) {
            if (gx >= x + pad() && gx < x + pad() + cw * 2) pick(old);
            else if (gx >= x + pad() + cw * 4 + gap) hex = new StringBuilder(Colors.hex(current()).substring(1));
            return;
        }
        int[] rows = {rowY(1), rowY(2)};
        for (int r = 0; r < 2; r++) {
            if (gy < rows[r] || gy >= rows[r] + cw) continue;
            int i = (int) ((gx - x - pad()) / (cw + gap));
            List<Integer> list = new ArrayList<>();
            if (r == 0) for (int c : palette()) list.add(c);
            else list.addAll(recent);
            if (i >= 0 && i < list.size()) pick(list.get(i));
        }
    }

    static boolean mouseDragged(double gx, double gy) {
        if (target == null || drag == Part.NONE) return false;
        update(gx, gy);
        return true;
    }

    /** @return true only when the press started a drag on the picker */
    static boolean mouseReleased(double gx, double gy) {
        boolean was = drag != Part.NONE;
        drag = Part.NONE;
        return was;
    }

    private static void update(double gx, double gy) {
        switch (drag) {
            case SV -> {
                int[] b = svBox();
                s = clamp((float) ((gx - b[0]) / b[2]));
                v = 1 - clamp((float) ((gy - b[1]) / b[3]));
            }
            case HUE -> {
                int[] b = hueBox();
                h = clamp((float) ((gy - b[1]) / b[3]));
            }
            case ALPHA -> {
                int[] b = alphaBox();
                alpha = Math.round((1 - clamp((float) ((gy - b[1]) / b[3]))) * 255);
            }
            default -> {
                return;
            }
        }
        fromHsv();
    }

    private static float clamp(float f) {
        return Math.max(0, Math.min(1, f));
    }

    /** While open every key is ours: Esc reverts, Enter keeps, the hex field takes the rest. */
    static boolean keyPressed(int key) {
        if (target == null) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (hex != null) hex = null;
                else close(false);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (hex != null) applyHex();
                else close(true);
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hex != null && !hex.isEmpty()) hex.setLength(hex.length() - 1);
            }
            default -> {
            }
        }
        return true;
    }

    static boolean charTyped(char c) {
        if (target == null) return false;
        if (hex != null && Character.digit(c, 16) >= 0 && hex.length() < 8) hex.append(Character.toLowerCase(c));
        return true;
    }

    private static void applyHex() {
        int c = Colors.parse(hex.toString(), current());
        hex = null;
        pick(c);
    }

    // ---- rendering ----

    static void render(GuiGraphics g) {
        if (target == null) return;
        Font font = Minecraft.getInstance().font;
        int w = width(), hgt = height();
        g.pose().pushPose();
        try {
            // Above the panel and its labels.
            g.pose().translate(0, 0, 300);
            g.fill(x, y, x + w, y + hgt, BG);
            outline(g, x, y, w, hgt, BORDER);
            int[] b = svBox();
            for (int i = 0; i < b[2]; i++) {
                int top = 0xFF000000 | Colors.hsvToRgb(h, i / (float) Math.max(1, b[2] - 1), 1);
                g.fillGradient(b[0] + i, b[1], b[0] + i + 1, b[1] + b[3], top, 0xFF000000);
            }
            outline(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, BORDER);
            int mx = b[0] + Math.round(s * (b[2] - 1)), my = b[1] + Math.round((1 - v) * (b[3] - 1));
            outline(g, mx - 3, my - 3, 7, 7, 0xFFFFFFFF);
            outline(g, mx - 2, my - 2, 5, 5, 0xFF000000);
            int[] hb = hueBox();
            for (int i = 0; i < hb[3]; i++)
                g.fill(hb[0], hb[1] + i, hb[0] + hb[2], hb[1] + i + 1, 0xFF000000 | Colors.hsvToRgb(i / (float) hb[3], 1, 1));
            marker(g, hb, Math.round(h * (hb[3] - 1)));
            int[] ab = alphaBox();
            checker(g, ab[0], ab[1], ab[2], ab[3]);
            int rgb = Colors.hsvToRgb(h, s, v);
            g.fillGradient(ab[0], ab[1], ab[0] + ab[2], ab[1] + ab[3], 0xFF000000 | rgb, rgb);
            marker(g, ab, Math.round((1 - alpha / 255f) * (ab[3] - 1)));
            int cw = u(14), gap = u(2), r0 = rowY(0);
            // Old | new preview, then the hex field.
            checker(g, x + pad(), r0, cw * 4, cw);
            g.fill(x + pad(), r0, x + pad() + cw * 2, r0 + cw, old);
            g.fill(x + pad() + cw * 2, r0, x + pad() + cw * 4, r0 + cw, current());
            outline(g, x + pad(), r0, cw * 4, cw, BORDER);
            int hx = x + pad() + cw * 4 + gap, hw = x + w - pad() - hx;
            boolean typing = hex != null;
            g.fill(hx, r0, hx + hw, r0 + cw, typing ? 0xFFFFFFFF : 0xFFF1F3F4);
            outline(g, hx, r0, hw, cw, typing ? ACCENT : BORDER);
            String text = typing ? "#" + hex + (System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "") : Colors.hex(current());
            g.drawString(font, text, hx + 2, r0 + (cw - 8) / 2, 0xFF1E1E1E, false);
            swatches(g, palette(), rowY(1), cw, gap);
            int[] rec = recent.stream().mapToInt(Integer::intValue).toArray();
            swatches(g, rec, rowY(2), cw, gap);
            if (rec.length == 0) g.drawString(font, net.minecraft.network.chat.Component.translatable("mapsketch.ui.no_recent"),
                    x + pad(), rowY(2) + (cw - 8) / 2, 0xFF9AA0A6, false);
        } finally {
            g.pose().popPose();
        }
    }

    private static void swatches(GuiGraphics g, int[] colors, int ry, int cw, int gap) {
        for (int i = 0; i < colors.length; i++) {
            int sx = x + pad() + i * (cw + gap);
            if (sx + cw > x + width() - pad()) break;
            checker(g, sx, ry, cw, cw);
            g.fill(sx, ry, sx + cw, ry + cw, colors[i]);
            outline(g, sx, ry, cw, cw, colors[i] == current() ? ACCENT : BORDER);
        }
    }

    private static void marker(GuiGraphics g, int[] b, int off) {
        g.fill(b[0] - 2, b[1] + off - 1, b[0] + b[2] + 2, b[1] + off + 2, 0xFF000000);
        g.fill(b[0] - 1, b[1] + off, b[0] + b[2] + 1, b[1] + off + 1, 0xFFFFFFFF);
    }

    /** Light/dark checkerboard behind translucent colours. */
    static void checker(GuiGraphics g, int x0, int y0, int w, int h) {
        int c = Math.max(2, u(4));
        for (int yy = 0; yy < h; yy += c)
            for (int xx = 0; xx < w; xx += c)
                g.fill(x0 + xx, y0 + yy, x0 + Math.min(w, xx + c), y0 + Math.min(h, yy + c),
                        (xx / c + yy / c) % 2 == 0 ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    private static void outline(GuiGraphics g, int x0, int y0, int w, int h, int c) {
        g.fill(x0, y0, x0 + w, y0 + 1, c);
        g.fill(x0, y0 + h - 1, x0 + w, y0 + h, c);
        g.fill(x0, y0, x0 + 1, y0 + h, c);
        g.fill(x0 + w - 1, y0, x0 + w, y0 + h, c);
    }
}
