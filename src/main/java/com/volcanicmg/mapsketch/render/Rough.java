package com.volcanicmg.mapsketch.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Rough.js-style hand-drawn wobble. All randomness comes from {@link Random} seeded per element (its algorithm is
 * part of the Java spec), so every client draws the same wobble for the same seed.
 */
public final class Rough {
    private Rough() {
    }

    /**
     * Straight-edged shapes: each edge becomes its own bowed, slightly overshooting stroke, drawn twice.
     *
     * @return polylines in the same space as {@code p}
     */
    public static List<float[]> edges(float[] p, boolean closed, int roughness, double sw, Random rnd) {
        List<float[]> out = new ArrayList<>();
        int n = p.length / 2, segs = closed ? n : n - 1;
        int passes = roughness == 0 ? 1 : 2;
        for (int pass = 0; pass < passes; pass++)
            for (int i = 0; i < segs; i++) {
                int j = (i + 1) % n;
                out.add(roughness == 0 ? new float[]{p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]}
                        : bow(p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], roughness, sw, rnd));
            }
        return out;
    }

    /** One straight line as a bowed quadratic curve with jittered, overshooting ends. */
    public static float[] bow(double ax, double az, double bx, double bz, int roughness, double sw, Random rnd) {
        double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
        if (len < 1e-9) return new float[]{(float) ax, (float) az, (float) bx, (float) bz};
        double amp = roughness * sw * Math.max(0.3, Math.min(1.5, len / (sw * 20)));
        double ux = dx / len, uz = dz / len, nx = -uz, nz = ux;
        double over0 = (rnd.nextDouble() - 0.3) * amp, over1 = (rnd.nextDouble() - 0.3) * amp;
        double sx = ax - ux * over0 + nx * (rnd.nextDouble() - 0.5) * amp;
        double sz = az - uz * over0 + nz * (rnd.nextDouble() - 0.5) * amp;
        double ex = bx + ux * over1 + nx * (rnd.nextDouble() - 0.5) * amp;
        double ez = bz + uz * over1 + nz * (rnd.nextDouble() - 0.5) * amp;
        double bowAmt = (rnd.nextDouble() * 2 - 1) * amp;
        double cx = (sx + ex) / 2 + nx * bowAmt, cz = (sz + ez) / 2 + nz * bowAmt;
        int steps = 8;
        float[] out = new float[(steps + 1) * 2];
        for (int s = 0; s <= steps; s++) {
            double t = s / (double) steps, u = 1 - t;
            out[s * 2] = (float) (u * u * sx + 2 * u * t * cx + t * t * ex);
            out[s * 2 + 1] = (float) (u * u * sz + 2 * u * t * cz + t * t * ez);
        }
        return out;
    }

    /**
     * Curves (ellipses, rounded corners, smoothed lines): smooth low-frequency displacement along the normal; closed
     * curves overshoot past their start like a pen stroke. Drawn twice.
     */
    public static List<float[]> curve(float[] p, boolean closed, int roughness, double sw, Random rnd) {
        List<float[]> out = new ArrayList<>();
        int n = p.length / 2;
        if (roughness == 0 || n < 3) {
            out.add(closed ? close(p) : p);
            return out;
        }
        double total = 0;
        for (int i = 1; i < n; i++) total += Math.hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1]);
        double amp = roughness * sw * Math.max(0.3, Math.min(1.5, total / (sw * 60)));
        for (int pass = 0; pass < 2; pass++) {
            double f1 = (1 + rnd.nextInt(2)) * 2 * Math.PI / Math.max(total, 1e-6), f2 = f1 * (2 + rnd.nextInt(2));
            double p1 = rnd.nextDouble() * 2 * Math.PI, p2 = rnd.nextDouble() * 2 * Math.PI;
            int extra = closed ? Math.max(1, n / 12) : 0;
            float[] q = new float[(n + extra + (closed ? 1 : 0)) * 2];
            double s = 0;
            for (int k = 0; k < n + extra + (closed ? 1 : 0); k++) {
                int i = k % n, prev = (i + n - 1) % n, next = (i + 1) % n;
                if (k > 0) s += Math.hypot(p[i * 2] - p[prev * 2], p[i * 2 + 1] - p[prev * 2 + 1]);
                double tx = p[next * 2] - p[prev * 2], tz = p[next * 2 + 1] - p[prev * 2 + 1];
                if (!closed && (i == 0 || i == n - 1)) {
                    tx = p[Math.min(i + 1, n - 1) * 2] - p[Math.max(i - 1, 0) * 2];
                    tz = p[Math.min(i + 1, n - 1) * 2 + 1] - p[Math.max(i - 1, 0) * 2 + 1];
                }
                double tl = Math.hypot(tx, tz);
                double nx = tl == 0 ? 0 : -tz / tl, nz = tl == 0 ? 0 : tx / tl;
                double d = amp * (0.6 * Math.sin(s * f1 + p1) + 0.4 * Math.sin(s * f2 + p2));
                q[k * 2] = (float) (p[i * 2] + nx * d);
                q[k * 2 + 1] = (float) (p[i * 2 + 1] + nz * d);
            }
            out.add(q);
        }
        return out;
    }

    private static float[] close(float[] p) {
        float[] q = java.util.Arrays.copyOf(p, p.length + 2);
        q[p.length] = p[0];
        q[p.length + 1] = p[1];
        return q;
    }
}
