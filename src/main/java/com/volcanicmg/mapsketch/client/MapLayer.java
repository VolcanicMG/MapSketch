package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Element;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Everything drawn in map space on the world map: stored elements, the shape being drawn, remote previews and
 * laser trails, the text caret. The pose must already be in blocks centred on the camera.
 */
public final class MapLayer {
    private MapLayer() {
    }

    public static void renderWorldMap(GuiGraphics g, MapView v) {
        if (!Config.ENABLED.get()) return;
        List<Element> list = ClientStore.sorted(v.dim());
        // Other players' live ghosts replace the stored element they are dragging or typing into.
        Set<UUID> replaced = new HashSet<>();
        List<Element> ghosts = new ArrayList<>();
        if (Config.SHOW_OTHERS_CURSORS.get())
            for (ClientStore.Remote r : ClientStore.remotes()) {
                for (Element gh : r.ghosts.values())
                    if (gh.dim.equals(v.dim())) {
                        ghosts.add(gh);
                        replaced.add(gh.id);
                    }
                if (r.live.dim().equals(v.dim())) laser(g, v, r.live.laser(), r.live.x(), r.live.z());
            }
        SketchRenderer.drawAll(g, list, v.camX(), v.camZ(), v.scale(), v.box(), true, Editor.faded(), replaced);
        SketchRenderer.drawLive(g, ghosts, v.camX(), v.camZ(), v.scale(), 0.85f);
        if (!Editor.drawMode()) return;
        if (Editor.draft() != null) SketchRenderer.drawLive(g, List.of(Editor.draft()), v.camX(), v.camZ(), v.scale(), 1f);
        List<double[]> own = Editor.laser();
        if (!own.isEmpty()) {
            float[] pts = new float[own.size() * 2];
            for (int i = 0; i < own.size(); i++) {
                pts[i * 2] = (float) (own.get(i)[0] - v.camX());
                pts[i * 2 + 1] = (float) (own.get(i)[1] - v.camZ());
            }
            SketchRenderer.drawPolyline(g, pts, 4 / v.scale(), 0xE0FF3B30, 8);
        }
        caret(g, v);
    }

    private static void laser(GuiGraphics g, MapView v, float[] rel, double x, double z) {
        if (rel.length < 4) return;
        float[] pts = new float[rel.length];
        for (int i = 0; i < rel.length; i += 2) {
            pts[i] = (float) (x - v.camX() + rel[i]);
            pts[i + 1] = (float) (z - v.camZ() + rel[i + 1]);
        }
        SketchRenderer.drawPolyline(g, pts, 4 / v.scale(), 0xE0FF3B30, 8);
    }

    /** Blinking caret after the last line of the text being edited. */
    private static void caret(GuiGraphics g, MapView v) {
        Element ed = Editor.editing();
        if (ed == null || System.currentTimeMillis() / 500 % 2 == 1) return;
        Element e = ClientStore.get(ed.id);
        if (e == null) return;
        String[] lines = e.text.split("\n", -1);
        double s = e.fontSize / 9.0;
        int w = Minecraft.getInstance().font.width(lines[lines.length - 1]);
        double boxW = e.w / s;
        double lx = e.type != Element.Type.TEXT ? w / 2.0 : switch (e.align) {
            case LEFT -> -boxW / 2 + w;
            case CENTER -> w / 2.0;
            case RIGHT -> boxW / 2;
        };
        int total = lines.length * 10 - 1;
        double top = -total / 2.0 + (lines.length - 1) * 10;
        double[] c = e.center();
        double cos = Math.cos(e.angle), sin = Math.sin(e.angle);
        double ax = lx * s, az0 = top * s, az1 = (top + 9) * s;
        float[] pts = {
                (float) (c[0] - v.camX() + ax * cos - az0 * sin), (float) (c[1] - v.camZ() + ax * sin + az0 * cos),
                (float) (c[0] - v.camX() + ax * cos - az1 * sin), (float) (c[1] - v.camZ() + ax * sin + az1 * cos)};
        SketchRenderer.drawPolyline(g, pts, Math.max(s, 1.5 / v.scale()), 0xFF4C6EF5, 4);
    }
}
