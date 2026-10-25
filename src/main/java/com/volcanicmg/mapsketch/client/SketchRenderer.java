package com.volcanicmg.mapsketch.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.render.Tessellator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws elements with the pose already in block units centred on the camera (Xaero's world map pose, or the
 * minimap's rotated pose). Meshes are cached per element version and stroke-width bucket.
 */
public final class SketchRenderer {
    /** Thinnest drawn stroke, in screen pixels. */
    public static final double MIN_PX = 1.5;
    // Two meshes per element: the world map and the minimap usually draw it at different widths in one frame.
    // Live ghosts have their own cache, so a ghost and its stored element never churn each other's slots.
    private static final Map<UUID, Cached[]> cache = new HashMap<>(), liveCache = new HashMap<>();
    private static final int CACHE_LIMIT = 20_000;

    /** @param key element version for stored elements, a content hash for live ghosts */
    private record Cached(long key, int bucket, Tessellator.Mesh mesh) {
    }

    private SketchRenderer() {
    }

    static void evict(UUID id) {
        cache.remove(id);
        liveCache.remove(id);
    }

    public static void clearCache() {
        cache.clear();
        liveCache.clear();
    }

    /** Stroke width actually drawn and its cache bucket (0 = the element's own width). */
    private static double drawnWidth(Element e, double pxPerBlock, int[] bucketOut) {
        if (e.strokeWidth * pxPerBlock >= MIN_PX) {
            bucketOut[0] = 0;
            return e.strokeWidth;
        }
        int b = (int) Math.round(Math.log(pxPerBlock) / Math.log(2) * 4);
        bucketOut[0] = b == 0 ? Integer.MIN_VALUE : b;
        return MIN_PX / Math.pow(2, b / 4.0);
    }

    private static Tessellator.Mesh mesh(Map<UUID, Cached[]> cache, Element e, double pxPerBlock, long key) {
        int[] bucket = new int[1];
        double w = drawnWidth(e, pxPerBlock, bucket);
        Cached[] slots = cache.get(e.id);
        if (slots != null)
            for (Cached c : slots) if (c != null && c.key == key && c.bucket == bucket[0]) return c.mesh;
        Tessellator.Mesh m = Tessellator.tessellate(e, w, pxPerBlock);
        if (slots == null) {
            if (cache.size() > CACHE_LIMIT) cache.clear();
            cache.put(e.id, slots = new Cached[2]);
        }
        // Keep the two most recent meshes.
        slots[1] = slots[0];
        slots[0] = new Cached(key, bucket[0], m);
        return m;
    }

    /** Stored elements: version plus state token (local edits keep the version but always change the token). */
    private static long storedKey(Element e) {
        return (long) e.version << 32 ^ (e.nonce & 0xFFFFFFFFL);
    }

    /**
     * Content hash of a live ghost over everything the mesh depends on. The origin is left out on purpose: meshes
     * are relative to it, so a dragged ghost reuses its mesh and is only translated.
     */
    private static long liveKey(Element e) {
        long h = e.type.ordinal();
        h = h * 31 + Double.hashCode(e.w);
        h = h * 31 + Double.hashCode(e.h);
        h = h * 31 + java.util.Arrays.hashCode(e.pts);
        h = h * 31 + Float.hashCode(e.angle);
        h = h * 31 + e.stroke;
        h = h * 31 + e.fill;
        h = h * 31 + e.fillStyle.ordinal();
        h = h * 31 + Float.hashCode(e.strokeWidth);
        h = h * 31 + e.strokeStyle.ordinal();
        h = h * 31 + e.roughness;
        h = h * 31 + (e.round ? 1 : 0);
        h = h * 31 + e.opacity;
        h = h * 31 + e.arrowStart.ordinal() * 8 + e.arrowEnd.ordinal();
        h = h * 31 + e.seed;
        return h * 31 + Float.hashCode(e.fontSize) + e.text.hashCode() * 7 + e.align.ordinal();
    }

    /**
     * Draws the elements inside the view box, bottom first.
     *
     * @param depthOrdered give each element its own depth so its overlapping triangles blend once (world map only;
     *                     clears the depth buffer)
     * @param faded        ids drawn at 30% (eraser preview)
     * @param replaced     ids not drawn here because the caller draws a live copy
     */
    public static void drawAll(GuiGraphics g, List<Element> list, double camX, double camZ, double pxPerBlock,
                               double[] view, boolean depthOrdered, Set<UUID> faded, Set<UUID> replaced) {
        if (list.isEmpty()) return;
        Matrix4f pose = g.pose().last().pose();
        begin(depthOrdered);
        BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_COLOR);
        float step = 150f / Math.max(1, list.size());
        try {
            for (int i = 0; i < list.size(); i++) {
                Element e = list.get(i);
                if (replaced.contains(e.id) || !visible(e, view)) continue;
                emit(bb, pose, mesh(cache, e, pxPerBlock, storedKey(e)), e.x - camX, e.z - camZ, depthOrdered ? -200 + i * step : 0,
                        faded.contains(e.id) ? 0.3f : 1f);
            }
        } finally {
            end(bb, depthOrdered);
        }
        for (int i = 0; i < list.size(); i++) {
            Element e = list.get(i);
            if (!replaced.contains(e.id) && !e.text.isEmpty() && visible(e, view))
                drawText(g, e, camX, camZ, pxPerBlock, depthOrdered ? -200 + i * step + step / 2 : 0.1f);
        }
        g.flush();
    }

    /** Draws live elements on top (own shape in progress, other players' ghosts), cached by content. */
    public static void drawLive(GuiGraphics g, Collection<Element> list, double camX, double camZ, double pxPerBlock, float alpha) {
        if (list.isEmpty()) return;
        Matrix4f pose = g.pose().last().pose();
        begin(false);
        BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_COLOR);
        try {
            for (Element e : list) emit(bb, pose, mesh(liveCache, e, pxPerBlock, liveKey(e)), e.x - camX, e.z - camZ, 0, alpha);
        } finally {
            end(bb, false);
        }
        for (Element e : list) if (!e.text.isEmpty()) drawText(g, e, camX, camZ, pxPerBlock, 0.1f);
        g.flush();
    }

    /** Thick polyline in world coordinates (laser trails, selection outlines drawn in map space). */
    public static void drawPolyline(GuiGraphics g, float[] worldRel, double width, int argb, int segs) {
        Tessellator.Mesh m = Tessellator.polyline(worldRel, width, argb, segs);
        begin(false);
        BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_COLOR);
        try {
            emit(bb, g.pose().last().pose(), m, 0, 0, 0, 1);
        } finally {
            end(bb, false);
        }
    }

    private static boolean visible(Element e, double[] view) {
        double[] b = e.worldBounds();
        double pad = e.strokeWidth * 8 + e.fontSize;
        return b[2] + pad >= view[0] && b[0] - pad <= view[2] && b[3] + pad >= view[1] && b[1] - pad <= view[3];
    }

    private static void begin(boolean depthOrdered) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (depthOrdered) {
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LESS);
        } else RenderSystem.disableDepthTest();
    }

    /** Always finishes the buffer (so the shared Tesselator is free again) and restores GL state. */
    private static void end(BufferBuilder bb, boolean depthOrdered) {
        try {
            MeshData data = bb.build();
            if (data != null) BufferUploader.drawWithShader(data);
        } finally {
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
        }
    }

    private static void emit(BufferBuilder bb, Matrix4f pose, Tessellator.Mesh m, double ox, double oz, float z, float alpha) {
        float fx = (float) ox, fz = (float) oz;
        for (int i = 0; i < m.count; i++) {
            int c = m.argb[i];
            if (alpha != 1f) c = (int) ((c >>> 24) * alpha) << 24 | c & 0xFFFFFF;
            bb.addVertex(pose, fx + m.xy[i * 2], fz + m.xy[i * 2 + 1], z).setColor(c);
        }
    }

    /** Minecraft font scaled to the element's font size; skipped under 3 screen pixels. */
    static void drawText(GuiGraphics g, Element e, double camX, double camZ, double pxPerBlock, float z) {
        if (e.fontSize * pxPerBlock < 3) return;
        Font font = Minecraft.getInstance().font;
        String[] lines = e.text.split("\n", -1);
        double[] c = e.center();
        float s = e.fontSize / 9f;
        int color = Tessellator.withOpacity(e.stroke, e.opacity);
        if ((color >>> 24) < 8) return;
        PoseStack pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(c[0] - camX, c[1] - camZ, z);
            pose.mulPose(Axis.ZP.rotation(e.angle));
            pose.scale(s, s, 1);
            int total = lines.length * 10 - 1;
            float boxW = (float) (e.w / s);
            for (int i = 0; i < lines.length; i++) {
                int w = font.width(lines[i]);
                float x = e.type != Element.Type.TEXT ? -w / 2f : switch (e.align) {
                    case LEFT -> -boxW / 2;
                    case CENTER -> -w / 2f;
                    case RIGHT -> boxW / 2 - w;
                };
                g.drawString(font, lines[i], (int) x, -total / 2 + i * 10, color, false);
            }
        } finally {
            pose.popPose();
        }
    }
}
