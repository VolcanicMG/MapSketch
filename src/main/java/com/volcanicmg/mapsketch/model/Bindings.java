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
        double[] sAim = n == 2 && e != null ? e.center() : null, eAim = n == 2 && s != null ? s.center() : null;
        if (s != null) place(c, true, s, gap, sAim);
        if (e != null) place(c, false, e, gap, eAim);
        c.fitPoints();
        return c;
    }

    /** Moves one end of {@code a} (already baked) onto its binding on {@code t}, aimed from the neighbouring point. */
    public static void place(Element a, boolean start, Element t, double gap) {
        place(a, start, t, gap, null);
    }

    /** @param aim world point the end is aimed from, or null for the neighbouring point */
    public static void place(Element a, boolean start, Element t, double gap, double[] aim) {
        int n = a.pointCount(), i = start ? 0 : n - 1, j = start ? 1 : n - 2;
        double fromX = aim != null ? aim[0] : a.x + a.pts[j * 2], fromZ = aim != null ? aim[1] : a.z + a.pts[j * 2 + 1];
        double[] p = Geometry.bindPoint(t, fromX, fromZ, gap);
        a.pts[i * 2] = (float) (p[0] - a.x);
        a.pts[i * 2 + 1] = (float) (p[1] - a.z);
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
