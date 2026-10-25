package com.volcanicmg.mapsketch.model;

import java.util.ArrayList;
import java.util.List;

/** Outlines, simplification and hit-testing in element-local and world space. */
public final class Geometry {
    private Geometry() {
    }

    /**
     * Unrotated, unroughened outline in local coordinates (relative to the element origin).
     *
     * @return flat {@code x,z} pairs; closed shapes do not repeat the first point
     */
    public static float[] outline(Element e) {
        double w = e.w, h = e.h;
        return switch (e.type) {
            case RECTANGLE, TEXT -> e.round && e.type == Element.Type.RECTANGLE ? roundRect(w, h)
                    : new float[]{0, 0, (float) w, 0, (float) w, (float) h, 0, (float) h};
            case DIAMOND -> {
                float[] d = {(float) w / 2, 0, (float) w, (float) h / 2, (float) w / 2, (float) h, 0, (float) h / 2};
                yield e.round ? roundCorners(d, 0.2f) : d;
            }
            case ELLIPSE -> ellipse(w, h, Math.max(24, Math.min(128, (int) ((w + h) / 2))));
            case LINE, ARROW -> e.round && e.pointCount() > 2 ? catmullRom(e.pts, 8) : e.pts;
            case FREEDRAW -> e.pts;
        };
    }

    private static float[] roundRect(double w, double h) {
        float r = (float) (Math.min(w, h) * 0.25);
        float[] corners = {(float) w - r, r, (float) w - r, (float) h - r, r, (float) h - r, r, r};
        float[] out = new float[4 * 7 * 2];
        int k = 0;
        for (int c = 0; c < 4; c++) {
            double start = -Math.PI / 2 + c * Math.PI / 2;
            for (int i = 0; i < 7; i++) {
                double a = start + i * (Math.PI / 2) / 6;
                out[k++] = corners[c * 2] + (float) (Math.cos(a) * r);
                out[k++] = corners[c * 2 + 1] + (float) (Math.sin(a) * r);
            }
        }
        return out;
    }

    /** Replaces each corner of a closed polygon with a quadratic curve cutting {@code k} of each edge. */
    private static float[] roundCorners(float[] p, float k) {
        int n = p.length / 2, steps = 6;
        float[] out = new float[n * (steps + 1) * 2];
        int o = 0;
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n, next = (i + 1) % n;
            float cx = p[i * 2], cz = p[i * 2 + 1];
            float ax = cx + (p[prev * 2] - cx) * k, az = cz + (p[prev * 2 + 1] - cz) * k;
            float bx = cx + (p[next * 2] - cx) * k, bz = cz + (p[next * 2 + 1] - cz) * k;
            for (int s = 0; s <= steps; s++) {
                float t = s / (float) steps, u = 1 - t;
                out[o++] = u * u * ax + 2 * u * t * cx + t * t * bx;
                out[o++] = u * u * az + 2 * u * t * cz + t * t * bz;
            }
        }
        return out;
    }

    public static float[] ellipse(double w, double h, int n) {
        float[] out = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            out[i * 2] = (float) (w / 2 + Math.cos(a) * w / 2);
            out[i * 2 + 1] = (float) (h / 2 + Math.sin(a) * h / 2);
        }
        return out;
    }

    /** Centripetal-free uniform Catmull-Rom through every point, {@code steps} samples per segment. */
    public static float[] catmullRom(float[] p, int steps) {
        int n = p.length / 2;
        float[] out = new float[((n - 1) * steps + 1) * 2];
        int o = 0;
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1), i2 = i + 1, i3 = Math.min(n - 1, i + 2);
            for (int s = 0; s < steps; s++) {
                float t = s / (float) steps;
                out[o++] = cr(p[i0 * 2], p[i * 2], p[i2 * 2], p[i3 * 2], t);
                out[o++] = cr(p[i0 * 2 + 1], p[i * 2 + 1], p[i2 * 2 + 1], p[i3 * 2 + 1], t);
            }
        }
        out[o++] = p[(n - 1) * 2];
        out[o] = p[(n - 1) * 2 + 1];
        return out;
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Ramer–Douglas–Peucker on flat {@code x,z} pairs; keeps both endpoints. */
    public static float[] simplify(float[] p, double epsilon) {
        int n = p.length / 2;
        if (n < 3) return p.clone();
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        ArrayList<int[]> stack = new ArrayList<>();
        stack.add(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] r = stack.remove(stack.size() - 1);
            double max = -1;
            int idx = -1;
            for (int i = r[0] + 1; i < r[1]; i++) {
                double d = segDist(p[i * 2], p[i * 2 + 1], p[r[0] * 2], p[r[0] * 2 + 1], p[r[1] * 2], p[r[1] * 2 + 1]);
                if (d > max) {
                    max = d;
                    idx = i;
                }
            }
            if (max > epsilon) {
                keep[idx] = true;
                stack.add(new int[]{r[0], idx});
                stack.add(new int[]{idx, r[1]});
            }
        }
        int k = 0;
        for (boolean b : keep) if (b) k++;
        float[] out = new float[k * 2];
        int o = 0;
        for (int i = 0; i < n; i++)
            if (keep[i]) {
                out[o++] = p[i * 2];
                out[o++] = p[i * 2 + 1];
            }
        return out;
    }

    /** @return distance from (px, pz) to segment a-b */
    public static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
        double qx = ax + t * dx - px, qz = az + t * dz - pz;
        return Math.sqrt(qx * qx + qz * qz);
    }

    /** @return world point (wx, wz) in the element's local unrotated frame */
    public static double[] toLocal(Element e, double wx, double wz) {
        double[] c = e.center();
        double cos = Math.cos(-e.angle), sin = Math.sin(-e.angle);
        double dx = wx - c[0], dz = wz - c[1];
        return new double[]{c[0] + dx * cos - dz * sin - e.x, c[1] + dx * sin + dz * cos - e.z};
    }

    /** @return local point in world coordinates */
    public static double[] toWorld(Element e, double lx, double lz) {
        double[] c = e.center();
        double cos = Math.cos(e.angle), sin = Math.sin(e.angle);
        double dx = e.x + lx - c[0], dz = e.z + lz - c[1];
        return new double[]{c[0] + dx * cos - dz * sin, c[1] + dx * sin + dz * cos};
    }

    /**
     * @param tol extra world-space tolerance (a few screen pixels)
     * @return true if the world point hits the element's stroke, its filled interior, or a text box
     */
    public static boolean hit(Element e, double wx, double wz, double tol) {
        double[] b = e.worldBounds();
        double pad = tol + e.strokeWidth;
        if (wx < b[0] - pad || wx > b[2] + pad || wz < b[1] - pad || wz > b[3] + pad) return false;
        double[] l = toLocal(e, wx, wz);
        float[] o = outline(e);
        boolean closed = e.closed() || e.type == Element.Type.TEXT;
        boolean solidInside = e.type == Element.Type.TEXT || e.closed() && (e.fillStyle != Element.FillStyle.NONE
                && (e.fill >>> 24) != 0 || !e.text.isEmpty());
        if (closed && solidInside && inside(o, l[0], l[1])) return true;
        return distToPolyline(o, closed, l[0], l[1]) <= tol + e.strokeWidth / 2;
    }

    public static double distToPolyline(float[] p, boolean closed, double x, double z) {
        int n = p.length / 2;
        if (n == 1) return Math.hypot(x - p[0], z - p[1]);
        double best = Double.MAX_VALUE;
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            best = Math.min(best, segDist(x, z, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]));
        }
        return best;
    }

    /** Even-odd ray cast. */
    public static boolean inside(float[] p, double x, double z) {
        boolean in = false;
        int n = p.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = p[i * 2], zi = p[i * 2 + 1], xj = p[j * 2], zj = p[j * 2 + 1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    /** @return true if the element's world bounds lie fully inside the box */
    public static boolean insideBox(Element e, double minX, double minZ, double maxX, double maxZ) {
        double[] b = e.worldBounds();
        return b[0] >= minX && b[1] >= minZ && b[2] <= maxX && b[3] <= maxZ;
    }

    /**
     * Where a bound arrow end should sit: the target's outline point on the line from {@code (fromX, fromZ)} to its
     * centre, pushed out by {@code gap}.
     *
     * @return world {x, z}, or the centre when the line never crosses the outline
     */
    public static double[] bindPoint(Element target, double fromX, double fromZ, double gap) {
        float[] o = outline(target);
        double[] c = target.center();
        double[] lf = toLocal(target, fromX, fromZ);
        double lcx = c[0] - target.x, lcz = c[1] - target.z;
        double best = -1;
        int n = o.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double t = intersect(lf[0], lf[1], lcx, lcz, o[i * 2], o[i * 2 + 1], o[j * 2], o[j * 2 + 1]);
            if (t >= 0 && (best < 0 || t < best)) best = t;
        }
        if (best < 0) return c;
        double len = Math.hypot(lcx - lf[0], lcz - lf[1]);
        double t = len == 0 ? best : Math.max(0, best - gap / len);
        return toWorld(target, lf[0] + (lcx - lf[0]) * t, lf[1] + (lcz - lf[1]) * t);
    }

    /** @return parameter t along p0-p1 where it crosses segment a-b, or -1 */
    private static double intersect(double p0x, double p0z, double p1x, double p1z, double ax, double az, double bx, double bz) {
        double rx = p1x - p0x, rz = p1z - p0z, sx = bx - ax, sz = bz - az;
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-12) return -1;
        double t = ((ax - p0x) * sz - (az - p0z) * sx) / den;
        double u = ((ax - p0x) * rz - (az - p0z) * rx) / den;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : -1;
    }

    /** Splits a polyline into dash runs of {@code on} length separated by {@code off}. */
    public static List<float[]> dashes(float[] p, boolean closed, double on, double off) {
        List<float[]> out = new ArrayList<>();
        int n = p.length / 2;
        if (n < 2) return out;
        FloatList cur = new FloatList();
        boolean drawing = true;
        double left = on;
        int segs = closed ? n : n - 1;
        cur.add(p[0], p[1]);
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            double ax = p[i * 2], az = p[i * 2 + 1], bx = p[j * 2], bz = p[j * 2 + 1];
            double len = Math.hypot(bx - ax, bz - az), pos = 0;
            while (len - pos > left) {
                pos += left;
                float qx = (float) (ax + (bx - ax) * pos / len), qz = (float) (az + (bz - az) * pos / len);
                if (drawing) {
                    cur.add(qx, qz);
                    out.add(cur.toArray());
                    cur = new FloatList();
                } else cur.add(qx, qz);
                drawing = !drawing;
                left = drawing ? on : off;
            }
            left -= len - pos;
            if (drawing) cur.add((float) bx, (float) bz);
        }
        if (drawing && cur.size() >= 4) out.add(cur.toArray());
        return out;
    }

    /** Growable float array. */
    public static final class FloatList {
        private float[] a = new float[32];
        private int n;

        public void add(float x, float z) {
            if (n + 2 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x;
            a[n++] = z;
        }

        public int size() {
            return n;
        }

        public float[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
