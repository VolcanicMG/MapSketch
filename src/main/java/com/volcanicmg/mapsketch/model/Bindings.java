package com.volcanicmg.mapsketch.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Arrow binding maths: which lines/arrows follow a set of moved shapes and where their bound ends go. Pure so the
 * self-check covers it; the editor uses it for drags, nudges and every other geometry edit.
 */
public final class Bindings {
    private Bindings() {
    }

    /** @return true for elements arrows can bind to */
    public static boolean bindable(Element t) {
        return !t.deleted && (t.closed() || t.type == Element.Type.TEXT);
    }

    /** @return true for elements that can be bound (arrows and lines) */
    public static boolean binder(Element a) {
        return a.type == Element.Type.ARROW || a.type == Element.Type.LINE;
    }

    /** @return ids of arrows/lines (not in {@code moved}) bound at either end to something in {@code moved} */
    public static List<UUID> followers(Collection<Element> all, Set<UUID> moved) {
        List<UUID> out = new ArrayList<>();
        for (Element a : all)
            if (!a.deleted && binder(a) && !moved.contains(a.id)
                    && (a.startBind != null && moved.contains(a.startBind) || a.endBind != null && moved.contains(a.endBind)))
                out.add(a.id);
        return out;
    }

    /**
     * A copy of {@code a} whose ends bound to elements in {@code targets} sit where those targets now are (rotation
     * baked out first so points are plain world offsets); ends bound elsewhere stay.
     *
     * @param gap distance kept from the outline for edge bindings, in blocks
     */
    public static Element follow(Element a, Map<UUID, Element> targets, double gap) {
        Element c = bake(a.copy());
        int n = c.pointCount();
        if (n < 2) return c;
        Element s = c.startBind == null ? null : targets.get(c.startBind);
        Element e = c.endBind == null ? null : targets.get(c.endBind);
        // A straight arrow between two moved shapes aims centre to centre, not at the other end's stale spot.
        double[] sAim = n == 2 && e != null ? aimOf(c, false, e) : null, eAim = n == 2 && s != null ? aimOf(c, true, s) : null;
        if (s != null) place(c, true, s, gap, sAim);
        if (e != null) place(c, false, e, gap, eAim);
        c.fitPoints();
        return c;
    }

    /** Moves one end of {@code a} (already baked) onto its binding on {@code t}, aimed from the neighbouring point. */
    public static void place(Element a, boolean start, Element t, double gap) {
        place(a, start, t, gap, null);
    }

    /**
     * A fixed binding (inside point or link node) puts the end exactly there; an edge binding puts it on the outline.
     *
     * @param aim world point an edge-bound end is aimed from, or null for the neighbouring point
     */
    public static void place(Element a, boolean start, Element t, double gap, double[] aim) {
        int n = a.pointCount(), i = start ? 0 : n - 1, j = start ? 1 : n - 2;
        float[] fixed = start ? a.startFixed : a.endFixed;
        double[] p;
        if (fixed != null) p = fixedPoint(t, fixed[0], fixed[1]);
        else {
            double fromX = aim != null ? aim[0] : a.x + a.pts[j * 2], fromZ = aim != null ? aim[1] : a.z + a.pts[j * 2 + 1];
            p = Geometry.bindPoint(t, fromX, fromZ, gap);
        }
        a.pts[i * 2] = (float) (p[0] - a.x);
        a.pts[i * 2 + 1] = (float) (p[1] - a.z);
    }

    /** Where the other end will be: its fixed point, or its target's centre. */
    private static double[] aimOf(Element a, boolean start, Element t) {
        float[] f = start ? a.startFixed : a.endFixed;
        return f != null ? fixedPoint(t, f[0], f[1]) : t.center();
    }

    // ---- fixed points, link nodes, binding choice ----

    /** What a dropped end would bind to on one shape. */
    public enum Kind { NONE, DOT, INSIDE, EDGE }

    /** @param fx normalized local x of a DOT or INSIDE binding ({@code fz} likewise); unused for EDGE */
    public record Hit(Kind kind, float fx, float fz, int dot) {
        static final Hit NONE = new Hit(Kind.NONE, 0, 0, -1);
    }

    /** @return world position of normalized local point (fx, fz) of {@code t}: follows its move, resize and rotation */
    public static double[] fixedPoint(Element t, double fx, double fz) {
        return Geometry.toWorld(t, fx * t.w, fz * t.h);
    }

    /**
     * Link nodes of a shape, normalized: the 4 side midpoints and the centre; rectangles and diamonds also get their
     * 4 corners. Diamond sides and corners are the diamond's own, not its box's.
     */
    public static float[][] anchors(Element t) {
        if (t.type == Element.Type.DIAMOND)
            return new float[][]{{0.75f, 0.25f}, {0.75f, 0.75f}, {0.25f, 0.75f}, {0.25f, 0.25f}, {0.5f, 0.5f},
                    {0.5f, 0}, {1, 0.5f}, {0.5f, 1}, {0, 0.5f}};
        float[][] sides = {{0.5f, 0}, {1, 0.5f}, {0.5f, 1}, {0, 0.5f}, {0.5f, 0.5f}};
        if (t.type != Element.Type.RECTANGLE) return sides;
        return new float[][]{sides[0], sides[1], sides[2], sides[3], sides[4], {0, 0}, {1, 0}, {1, 1}, {0, 1}};
    }

    /**
     * How an end dropped at world (wx, wz) binds to {@code t}. Priority: a link node within {@code dot} blocks, then a
     * fixed point well inside (farther than {@code band} from the outline), then the edge (within {@code band} of the
     * outline, inside or out); else nothing.
     */
    public static Hit classify(Element t, double wx, double wz, double band, double dot) {
        if (!bindable(t)) return Hit.NONE;
        float[][] nodes = anchors(t);
        int best = -1;
        double bestD = dot;
        for (int i = 0; i < nodes.length; i++) {
            double[] p = fixedPoint(t, nodes[i][0], nodes[i][1]);
            double d = Math.hypot(p[0] - wx, p[1] - wz);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0) return new Hit(Kind.DOT, nodes[best][0], nodes[best][1], best);
        double[] l = Geometry.toLocal(t, wx, wz);
        float[] outline = Geometry.outline(t);
        double edge = Geometry.distToPolyline(outline, true, l[0], l[1]);
        if (edge <= band) return new Hit(Kind.EDGE, 0, 0, -1);
        if (Geometry.inside(outline, l[0], l[1]))
            return new Hit(Kind.INSIDE, (float) clamp(t.w == 0 ? 0.5 : l[0] / t.w), (float) clamp(t.h == 0 ? 0.5 : l[1] / t.h), -1);
        return Hit.NONE;
    }

    /** A binding choice: the shape and how the end binds to it. */
    public record Pick(Element target, Hit hit) {
    }

    /**
     * Picks the topmost bindable shape the end binds to.
     *
     * @param topFirst candidates, topmost first
     * @return the choice, or null
     */
    public static Pick pick(List<Element> topFirst, UUID self, double wx, double wz, double band, double dot) {
        for (Element t : topFirst) {
            if (t.id.equals(self)) continue;
            Hit h = classify(t, wx, wz, band, dot);
            if (h.kind() != Kind.NONE) return new Pick(t, h);
        }
        return null;
    }

    /** Applies a binding choice to one end of {@code a} (null target = unbind) and puts the end in place. */
    public static void bind(Element a, boolean start, Element target, Hit hit, double gap) {
        UUID id = target == null ? null : target.id;
        float[] fixed = target == null || hit.kind() == Kind.EDGE ? null : new float[]{hit.fx(), hit.fz()};
        if (start) {
            a.startBind = id;
            a.startFixed = fixed;
        } else {
            a.endBind = id;
            a.endFixed = fixed;
        }
        if (target != null) place(a, start, target, gap);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Rewrites a linear element without rotation: same world points, origin at the first one. */
    public static Element bake(Element e) {
        if (e.angle == 0) return e;
        double[] w = new double[e.pts.length];
        for (int i = 0; i < e.pts.length; i += 2) {
            double[] p = Geometry.toWorld(e, e.pts[i], e.pts[i + 1]);
            w[i] = p[0];
            w[i + 1] = p[1];
        }
        e.angle = 0;
        e.x = w[0];
        e.z = w[1];
        for (int i = 0; i < w.length; i += 2) {
            e.pts[i] = (float) (w[i] - e.x);
            e.pts[i + 1] = (float) (w[i + 1] - e.z);
        }
        return e;
    }
}
