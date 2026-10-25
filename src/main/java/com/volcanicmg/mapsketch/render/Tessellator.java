package com.volcanicmg.mapsketch.render;

import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Geometry;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Element to coloured triangles (Minecraft GUI has no line width). Vertices are relative to the element origin with
 * rotation applied, so the renderer only adds {@code origin - camera}. Pure: no Minecraft classes.
 */
public final class Tessellator {
    private Tessellator() {
    }

    /** Hard cap on vertices per element (about 2 MB); geometry past it is dropped. */
    public static final int MAX_VERTICES = 120_000;
    /** Most dashes or dots along one polyline; longer patterns are stretched to fit. */
    public static final int MAX_DASHES = 2000;

    /** Triangle list: {@code xy[2*i], xy[2*i+1]} and {@code argb[i]} per vertex, three vertices per triangle. */
    public static final class Mesh {
        public float[] xy = new float[192];
        public int[] argb = new int[96];
        public int count;

        /** @return true once the vertex cap is reached; callers stop generating geometry */
        boolean full() {
            return count + 3 > MAX_VERTICES;
        }

        void tri(double x1, double z1, double x2, double z2, double x3, double z3, int c) {
            if (count + 3 > MAX_VERTICES) return;
            if (count + 3 > argb.length) {
                argb = Arrays.copyOf(argb, argb.length * 2);
                xy = Arrays.copyOf(xy, xy.length * 2);
            }
            put(x1, z1, c);
            put(x2, z2, c);
            put(x3, z3, c);
        }

        private void put(double x, double z, int c) {
            xy[count * 2] = (float) x;
            xy[count * 2 + 1] = (float) z;
            argb[count++] = c;
        }
    }

    /**
     * @param width      stroke width actually drawn (stroke width raised to the minimum on-screen width)
     * @param pxPerBlock screen pixels per block, only used to pick circle segment counts
     */
    public static Mesh tessellate(Element e, double width, double pxPerBlock) {
        Mesh m = new Mesh();
        Random rnd = new Random(e.seed);
        int stroke = withOpacity(e.stroke, e.opacity), fill = withOpacity(e.fill, e.opacity);
        int discSegs = (int) Math.max(6, Math.min(20, Math.round(width * pxPerBlock * 0.8)));
        if (e.type == Element.Type.TEXT) {
            // Optional background box behind the text (the text itself is drawn by the client's font renderer).
            if (e.textBackground()) {
                float[] box = Geometry.outline(e);
                double cx = e.w / 2, cz = e.h / 2;
                int n = box.length / 2;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    m.tri(cx, cz, box[i * 2], box[i * 2 + 1], box[j * 2], box[j * 2 + 1], fill);
                }
                rotate(m, e);
            }
            return m;
        }
        // Stamps are textured quads drawn by the client (pixel symbols, map icons, item sprites).
        if (e.type == Element.Type.STAMP) return m;
        float[] outline = Geometry.outline(e);
        if (e.closed() && e.fillStyle != Element.FillStyle.NONE && (fill >>> 24) != 0) fill(m, e, outline, fill, width, rnd, discSegs);
        List<float[]> lines;
        boolean curved = e.type == Element.Type.ELLIPSE || e.round && e.type != Element.Type.FREEDRAW;
        // Many-segment polylines wobble as one curve: per-segment bows would make thousands of strokes.
        if (outline.length / 2 > 200) curved = true;
        if (e.type == Element.Type.FREEDRAW || e.roughness == 0 && curved) lines = List.of(e.closed() ? closeLoop(outline) : outline);
        else if (curved) lines = Rough.curve(outline, e.closed(), e.roughness, e.strokeWidth, rnd);
        else lines = Rough.edges(outline, e.closed(), e.roughness, e.strokeWidth, rnd);
        // Dashed/dotted strokes use a single pass, as Excalidraw does.
        if (e.strokeStyle != Element.StrokeStyle.SOLID && e.roughness > 0 && lines.size() > 1)
            lines = lines.subList(0, lines.size() / 2);
        // One dash budget for the whole element: the pattern stretches so all its polylines share MAX_DASHES.
        double stretch = 1;
        if (e.strokeStyle != Element.StrokeStyle.SOLID) {
            double total = 0;
            for (float[] l : lines) total += length(l);
            stretch = Math.max(1, total / (pattern(e.strokeStyle, width) * MAX_DASHES));
        }
        for (float[] l : lines) {
            if (m.full()) break;
            strokeStyled(m, l, e.strokeStyle, width, stroke, discSegs, stretch);
        }
        if (e.type == Element.Type.ARROW || e.type == Element.Type.LINE) {
            head(m, outline, false, e.arrowEnd, width, stroke, discSegs);
            head(m, outline, true, e.arrowStart, width, stroke, discSegs);
        }
        rotate(m, e);
        return m;
    }

    public static int withOpacity(int argb, int opacity) {
        int a = (argb >>> 24) * opacity / 100;
        return a << 24 | argb & 0xFFFFFF;
    }

    private static float[] closeLoop(float[] p) {
        float[] q = Arrays.copyOf(p, p.length + 2);
        q[p.length] = p[0];
        q[p.length + 1] = p[1];
        return q;
    }

    /** Length of one dash + gap (or dot + gap) at stroke width {@code w}. */
    private static double pattern(Element.StrokeStyle style, double w) {
        return style == Element.StrokeStyle.DASHED ? w * 7 : w * 2.51;
    }

    private static void strokeStyled(Mesh m, float[] p, Element.StrokeStyle style, double w, int c, int segs, double stretch) {
        if (style == Element.StrokeStyle.SOLID) {
            thick(m, p, w, c, segs);
            return;
        }
        double on = (style == Element.StrokeStyle.DASHED ? w * 4 : w * 0.01) * stretch;
        double off = (style == Element.StrokeStyle.DASHED ? w * 3 : w * 2.5) * stretch;
        for (float[] d : Geometry.dashes(p, false, on, off)) {
            if (m.full()) return;
            if (style == Element.StrokeStyle.DASHED) thick(m, d, w, c, Math.min(segs, 6));
            else disc(m, d[0], d[1], w / 2, c, Math.min(segs, 8));
        }
    }

    static double length(float[] p) {
        double total = 0;
        for (int i = 2; i < p.length; i += 2) total += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        return total;
    }

    /** @return a standalone thick polyline mesh (laser trails, outlines) */
    public static Mesh polyline(float[] p, double w, int argb, int segs) {
        Mesh m = new Mesh();
        thick(m, p, w, argb, segs);
        return m;
    }

    /**
     * Thick polyline: one quad per segment with mitred joins (shared offsets, so no gaps or extra triangles at gentle
     * bends), round discs only at the ends and at sharp corners (over 60 degrees).
     */
    static void thick(Mesh m, float[] p, double w, int c, int segs) {
        if (m.full()) return;
        double r = w / 2;
        // Drop repeated points so every segment has a direction.
        int n = 0;
        float[] q = new float[p.length];
        for (int i = 0; i < p.length; i += 2)
            if (n == 0 || p[i] != q[n * 2 - 2] || p[i + 1] != q[n * 2 - 1]) {
                q[n * 2] = p[i];
                q[n * 2 + 1] = p[i + 1];
                n++;
            }
        if (n == 1) {
            disc(m, q[0], q[1], r, c, segs);
            return;
        }
        double[] nx = new double[n - 1], nz = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            double dx = q[i * 2 + 2] - q[i * 2], dz = q[i * 2 + 3] - q[i * 2 + 1], len = Math.hypot(dx, dz);
            nx[i] = -dz / len;
            nz[i] = dx / len;
        }
        // Offset per vertex: the segment normal at ends and sharp corners, else the mitre (bisector) scaled to keep width.
        double[] ox = new double[n], oz = new double[n];
        boolean[] sharp = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (i == 0 || i == n - 1) {
                int s = i == 0 ? 0 : n - 2;
                ox[i] = nx[s] * r;
                oz[i] = nz[s] * r;
                continue;
            }
            double cos = nx[i - 1] * nx[i] + nz[i - 1] * nz[i];
            if (cos < 0.5) {
                sharp[i] = true;
                continue;
            }
            double mx = nx[i - 1] + nx[i], mz = nz[i - 1] + nz[i], ml = Math.hypot(mx, mz);
            double scale = r / Math.max(0.5, (mx * nx[i] + mz * nz[i]) / ml);
            ox[i] = mx / ml * scale;
            oz[i] = mz / ml * scale;
        }
        for (int i = 0; i < n - 1; i++) {
            if (m.full()) return;
            double ax = q[i * 2], az = q[i * 2 + 1], bx = q[i * 2 + 2], bz = q[i * 2 + 3];
            double aox = sharp[i] ? nx[i] * r : ox[i], aoz = sharp[i] ? nz[i] * r : oz[i];
            double box = sharp[i + 1] ? nx[i] * r : ox[i + 1], boz = sharp[i + 1] ? nz[i] * r : oz[i + 1];
            m.tri(ax + aox, az + aoz, bx + box, bz + boz, bx - box, bz - boz, c);
            m.tri(ax + aox, az + aoz, bx - box, bz - boz, ax - aox, az - aoz, c);
        }
        disc(m, q[0], q[1], r, c, segs);
        disc(m, q[(n - 1) * 2], q[(n - 1) * 2 + 1], r, c, segs);
        for (int i = 1; i < n - 1; i++) if (sharp[i]) disc(m, q[i * 2], q[i * 2 + 1], r, c, segs);
    }

    static void disc(Mesh m, double cx, double cz, double r, int c, int segs) {
        double prevX = cx + r, prevZ = cz;
        for (int i = 1; i <= segs; i++) {
            double a = 2 * Math.PI * i / segs;
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            m.tri(cx, cz, prevX, prevZ, x, z, c);
            prevX = x;
            prevZ = z;
        }
    }

    private static void fill(Mesh m, Element e, float[] poly, int c, double w, Random rnd, int segs) {
        if (e.fillStyle == Element.FillStyle.SOLID) {
            double cx = 0, cz = 0;
            int n = poly.length / 2;
            for (int i = 0; i < n; i++) {
                cx += poly[i * 2];
                cz += poly[i * 2 + 1];
            }
            cx /= n;
            cz /= n;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                m.tri(cx, cz, poly[i * 2], poly[i * 2 + 1], poly[j * 2], poly[j * 2 + 1], c);
            }
            return;
        }
        double gap = Math.max(w, e.strokeWidth) * 4;
        hachure(m, e, poly, Math.toRadians(-41), gap, c, w, rnd, segs);
        if (e.fillStyle == Element.FillStyle.CROSS_HATCH) hachure(m, e, poly, Math.toRadians(49), gap, c, w, rnd, segs);
    }

    /** Parallel lines at {@code angle}, {@code gap} apart, clipped to the convex polygon. */
    private static void hachure(Mesh m, Element e, float[] poly, double angle, double gap, int c, double w, Random rnd, int segs) {
        double dx = Math.cos(angle), dz = Math.sin(angle), nx = -dz, nz = dx;
        int n = poly.length / 2;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double o = poly[i * 2] * nx + poly[i * 2 + 1] * nz;
            min = Math.min(min, o);
            max = Math.max(max, o);
        }
        gap = Math.max(gap, (max - min) / 300);
        for (double off = min + gap / 2; off < max; off += gap) {
            double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ax = poly[i * 2], az = poly[i * 2 + 1], bx = poly[j * 2], bz = poly[j * 2 + 1];
                double oa = ax * nx + az * nz - off, ob = bx * nx + bz * nz - off;
                if ((oa > 0) == (ob > 0) || oa == ob) continue;
                double s = oa / (oa - ob);
                double t = (ax + (bx - ax) * s) * dx + (az + (bz - az) * s) * dz;
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
            if (tMin >= tMax) continue;
            double sx = dx * tMin + nx * off, sz = dz * tMin + nz * off, ex = dx * tMax + nx * off, ez = dz * tMax + nz * off;
            float[] line = e.roughness == 0 ? new float[]{(float) sx, (float) sz, (float) ex, (float) ez}
                    : Rough.bow(sx, sz, ex, ez, e.roughness, e.strokeWidth * 0.5, rnd);
            thick(m, line, w / 2, c, Math.max(4, segs / 2));
        }
    }

    private static void head(Mesh m, float[] p, boolean atStart, Element.Head head, double w, int c, int segs) {
        int n = p.length / 2;
        if (head == Element.Head.NONE || n < 2) return;
        int tip = atStart ? 0 : n - 1, dir = atStart ? 1 : -1;
        double tx = p[tip * 2], tz = p[tip * 2 + 1];
        double total = 0;
        for (int i = 1; i < n; i++) total += Math.hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1]);
        double size = Math.min(w * 6 + 2, total * 0.4);
        // Direction from a point about one head length back, so curve sampling noise does not skew it.
        int k = tip;
        while (k + dir >= 0 && k + dir < n && Math.hypot(p[k * 2] - tx, p[k * 2 + 1] - tz) < size) k += dir;
        double bx = p[k * 2] - tx, bz = p[k * 2 + 1] - tz, bl = Math.hypot(bx, bz);
        if (bl < 1e-9) return;
        bx /= bl;
        bz /= bl;
        double a = Math.toRadians(25), cos = Math.cos(a), sin = Math.sin(a);
        double lx = tx + (bx * cos - bz * sin) * size, lz = tz + (bx * sin + bz * cos) * size;
        double rx = tx + (bx * cos + bz * sin) * size, rz = tz + (-bx * sin + bz * cos) * size;
        switch (head) {
            case ARROW -> {
                thick(m, new float[]{(float) lx, (float) lz, (float) tx, (float) tz, (float) rx, (float) rz}, w, c, segs);
            }
            case TRIANGLE -> {
                m.tri(tx, tz, lx, lz, rx, rz, c);
                thick(m, new float[]{(float) lx, (float) lz, (float) tx, (float) tz, (float) rx, (float) rz, (float) lx, (float) lz}, w, c, segs);
            }
            case BAR -> {
                double h = size * 0.5;
                thick(m, new float[]{(float) (tx - bz * h), (float) (tz + bx * h), (float) (tx + bz * h), (float) (tz - bx * h)}, w, c, segs);
            }
            case DOT -> disc(m, tx, tz, Math.max(size * 0.25, w), c, Math.max(segs, 10));
            default -> {
            }
        }
    }

    /** Rotates the mesh around the element's rotation centre (relative to its origin). */
    private static void rotate(Mesh m, Element e) {
        if (e.angle == 0) return;
        double[] c = e.center();
        double cx = c[0] - e.x, cz = c[1] - e.z, cos = Math.cos(e.angle), sin = Math.sin(e.angle);
        for (int i = 0; i < m.count; i++) {
            double dx = m.xy[i * 2] - cx, dz = m.xy[i * 2 + 1] - cz;
            m.xy[i * 2] = (float) (cx + dx * cos - dz * sin);
            m.xy[i * 2 + 1] = (float) (cz + dx * sin + dz * cos);
        }
    }
}
