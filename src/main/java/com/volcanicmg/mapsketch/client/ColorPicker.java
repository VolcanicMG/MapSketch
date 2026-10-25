package com.volcanicmg.mapsketch.client;

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
    /** TEXT_FILL: the background box of text (style.textFill; solid when opaque, none when transparent). */
    enum Target { STROKE, FILL, TEXT_FILL }

    private enum Part { NONE, SV, HUE, ALPHA }

    // Swatch / preview row height and the gap between rows.
    private static final int ROW = 16, ROW_GAP = 4;
    private static final List<Integer> recent = new ArrayList<>();

    private static Target target;
    private static int old, x, y;
    private static float h, s, v;
    private static int alpha;
    private static Part drag = Part.NONE;
    private static StringBuilder hex;

    private ColorPicker() {
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
        if (t != Target.STROKE && alpha == 0) alpha = 255;
        hex = null;
        x = Math.max(2, Math.min(anchorX, screenW - width() - 2));
        y = Math.max(2, Math.min(anchorY, screenH - height() - 2));
    }

    private static int current() {
        return switch (target) {
            case STROKE -> Editor.style.stroke;
            case FILL -> Editor.style.fill;
            case TEXT_FILL -> Editor.style.textFill;
        };
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
        switch (target) {
            case STROKE -> Editor.style.stroke = c;
            case FILL -> Editor.style.fill = c;
            case TEXT_FILL -> Editor.style.textFill = c;
        }
    }

    /** Applies a colour to the style and previews it on the selection (local only). */
    private static void set(int c) {
        setStyle(c);
        Target t = target;
        Editor.previewSelection(e -> {
            if (t == Target.STROKE) e.stroke = c;
            else EditorUi.setBackground(e, c);
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
        return 7;
    }

    private static int sq() {
        return 100;
    }

    private static int bar() {
        return 10;
    }

    private static int width() {
        return pad() * 2 + sq() + bar() * 2 + 8;
    }

    private static int height() {
        return rowY(3) - ROW_GAP - y + pad();
    }

    private static int[] svBox() {
        return new int[]{x + pad(), y + pad(), sq(), sq()};
    }

    private static int[] hueBox() {
        return new int[]{x + pad() + sq() + 4, y + pad(), bar(), sq()};
    }

    private static int[] alphaBox() {
        return new int[]{x + pad() + sq() + bar() + 8, y + pad(), bar(), sq()};
    }

    /** Old/new preview and hex field row. */
    private static int rowY(int i) {
        return y + pad() + sq() + 6 + i * (ROW + ROW_GAP);
    }

    private static int[] palette() {
        return target == Target.STROKE ? EditorUi.STROKES : EditorUi.FILLS;
    }

    private static boolean in(int[] b, double gx, double gy) {
        return gx >= b[0] && gx < b[0] + b[2] && gy >= b[1] && gy < b[1] + b[3];
    }

    /** @return {x, y, w, h} while open, else null */
    static int[] bounds() {
        return target == null ? null : new int[]{x, y, width(), height()};
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
        int cw = ROW, gap = 2;
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
    static boolean keyPressed(int key, int mods) {
        if (target == null) return false;
        if (EditorUi.ctrl(mods)) {
            // Copy/paste the colour as hex; pasting opens the hex field with it.
            if (key == GLFW.GLFW_KEY_C) EditorUi.setClipboard(Colors.hex(current()));
            if (key == GLFW.GLFW_KEY_V) {
                String v = EditorUi.clipboard().trim();
                hex = new StringBuilder(v.startsWith("#") ? v.substring(1) : v);
                if (hex.length() > 8) hex.setLength(8);
            }
            if (key == GLFW.GLFW_KEY_A && hex != null) hex.setLength(0);
            return true;
        }
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
            VanillaUi.panel(g, x, y, w, hgt);
            int[] b = svBox();
            inset(g, b);
            for (int i = 0; i < b[2]; i++) {
                int top = 0xFF000000 | Colors.hsvToRgb(h, i / (float) Math.max(1, b[2] - 1), 1);
                g.fillGradient(b[0] + i, b[1], b[0] + i + 1, b[1] + b[3], top, 0xFF000000);
            }
            int mx = b[0] + Math.round(s * (b[2] - 1)), my = b[1] + Math.round((1 - v) * (b[3] - 1));
            outline(g, mx - 3, my - 3, 7, 7, 0xFFFFFFFF);
            outline(g, mx - 2, my - 2, 5, 5, 0xFF000000);
            int[] hb = hueBox();
            inset(g, hb);
            for (int i = 0; i < hb[3]; i++)
                g.fill(hb[0], hb[1] + i, hb[0] + hb[2], hb[1] + i + 1, 0xFF000000 | Colors.hsvToRgb(i / (float) hb[3], 1, 1));
            marker(g, hb, Math.round(h * (hb[3] - 1)));
            int[] ab = alphaBox();
            inset(g, ab);
            checker(g, ab[0], ab[1], ab[2], ab[3]);
            int rgb = Colors.hsvToRgb(h, s, v);
            g.fillGradient(ab[0], ab[1], ab[0] + ab[2], ab[1] + ab[3], 0xFF000000 | rgb, rgb);
            marker(g, ab, Math.round((1 - alpha / 255f) * (ab[3] - 1)));
            int cw = ROW, gap = 2, r0 = rowY(0);
            // Old | new preview, then the hex field.
            g.fill(x + pad(), r0, x + pad() + cw * 4, r0 + cw, 0xFF373737);
            checker(g, x + pad() + 1, r0 + 1, cw * 4 - 2, cw - 2);
            g.fill(x + pad() + 1, r0 + 1, x + pad() + cw * 2, r0 + cw - 1, old);
            g.fill(x + pad() + cw * 2, r0 + 1, x + pad() + cw * 4 - 1, r0 + cw - 1, current());
            int hx = x + pad() + cw * 4 + gap * 2, hw = x + w - pad() - hx;
            boolean typing = hex != null;
            if (typing) VanillaUi.textField(g, hx, r0, hw, cw);
            else VanillaUi.slot(g, hx, r0, hw, cw);
            String text = typing ? "#" + hex + (System.currentTimeMillis() / 400 % 2 == 0 ? "_" : "") : Colors.hex(current());
            g.drawString(font, text, hx + 4, r0 + (cw - 8) / 2, typing ? 0xFFE0E0E0 : 0xFFFFFFFF, typing);
            swatches(g, palette(), rowY(1), cw, gap);
            int[] rec = recent.stream().mapToInt(Integer::intValue).toArray();
            swatches(g, rec, rowY(2), cw, gap);
            if (rec.length == 0) g.drawString(font, net.minecraft.network.chat.Component.translatable("mapsketch.ui.no_recent"),
                    x + pad() + 1, rowY(2) + (cw - 8) / 2, 0xFF404040, false);
        } finally {
            g.pose().popPose();
        }
    }

    /** A 1 px slot-style inset frame around a gradient box. */
    private static void inset(GuiGraphics g, int[] b) {
        VanillaUi.slot(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2);
    }

    private static void swatches(GuiGraphics g, int[] colors, int ry, int cw, int gap) {
        for (int i = 0; i < colors.length; i++) {
            int sx = x + pad() + i * (cw + gap);
            if (sx + cw > x + width() - pad()) break;
            VanillaUi.swatch(g, sx, ry, cw, cw, colors[i], colors[i] == current());
        }
    }

    private static void marker(GuiGraphics g, int[] b, int off) {
        g.fill(b[0] - 2, b[1] + off - 1, b[0] + b[2] + 2, b[1] + off + 2, 0xFF000000);
        g.fill(b[0] - 1, b[1] + off, b[0] + b[2] + 1, b[1] + off + 1, 0xFFFFFFFF);
    }

    /** Light/grey checkerboard behind translucent colours. */
    static void checker(GuiGraphics g, int x0, int y0, int w, int h) {
        VanillaUi.checker(g, x0, y0, w, h);
    }

    private static void outline(GuiGraphics g, int x0, int y0, int w, int h, int c) {
        g.fill(x0, y0, x0 + w, y0 + 1, c);
        g.fill(x0, y0 + h - 1, x0 + w, y0 + h, c);
        g.fill(x0, y0, x0 + 1, y0 + h, c);
        g.fill(x0 + w - 1, y0, x0 + w, y0 + h, c);
    }
}
