package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.ElementCodec;
import com.volcanicmg.mapsketch.model.FracIndex;
import com.volcanicmg.mapsketch.model.Geometry;
import com.volcanicmg.mapsketch.model.Rules;
import com.volcanicmg.mapsketch.render.Rough;
import com.volcanicmg.mapsketch.render.Tessellator;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;

/** The one runnable check ({@code ./gradlew selfCheck}, also run by {@code check}): model, rules and geometry. */
public final class SelfCheck {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    public static void main(String[] args) {
        reconcile();
        permissions();
        validation();
        simplify();
        hitTest();
        rough();
        order();
        orderAtScale();
        tessellationCaps();
        codec();
        System.out.println("MapSketch self-check passed");
    }

    private static Element rect(double x, double z, double w, double h) {
        Element e = new Element();
        e.id = UUID.randomUUID();
        e.dim = "minecraft:overworld";
        e.type = Element.Type.RECTANGLE;
        e.x = x;
        e.z = z;
        e.w = w;
        e.h = h;
        e.owner = A;
        return e;
    }

    private static void reconcile() {
        Element stored = rect(0, 0, 1, 1);
        stored.version = 4;
        stored.nonce = 77;
        Element edit = stored.copy();
        edit.baseNonce = 77;
        edit.nonce = 5;
        check(Rules.basedOn(edit, stored), "edit made from the stored state applies");
        edit.baseNonce = 76;
        check(!Rules.basedOn(edit, stored), "same version, other state: stale");
        edit.baseNonce = 77;
        edit.version = 3;
        check(!Rules.basedOn(edit, stored), "older version: stale");
        edit.version = 0;
        check(Rules.basedOn(edit, null) && !Rules.basedOn(edit, stored), "version 0 = new element only");
        edit.version = 2;
        check(!Rules.basedOn(edit, null), "non-new edit to an unknown id");
        // Client side: higher versions replace; the same version replaces only without pending local work.
        Element mine = stored.copy(), theirs = stored.copy();
        theirs.nonce = 99;
        check(Rules.replaces(theirs, mine, false) && !Rules.replaces(theirs, mine, true), "same version: pending wins");
        theirs.version = 5;
        check(Rules.replaces(theirs, mine, true) && !Rules.replaces(mine, theirs, false), "higher version wins");
        check(!Rules.replaces(mine, mine.copy(), false), "identical does not replace");
        // Undo guard: skip when someone changed (or restored) the element since our write.
        check(Rules.unchangedSince(77, false, stored) && !Rules.unchangedSince(76, false, stored), "undo only on our state");
        Element tomb = stored.copy();
        tomb.deleted = true;
        check(Rules.unchangedSince(77, true, tomb) && !Rules.unchangedSince(77, true, stored) && !Rules.unchangedSince(77, true, null),
                "undo of our delete only while our tombstone is there");
    }

    private static void permissions() {
        BiPredicate<UUID, UUID> team = (o, v) -> (o.equals(A) || o.equals(B)) && (v.equals(A) || v.equals(B));
        BiPredicate<UUID, UUID> none = (o, v) -> false;
        Element e = rect(0, 0, 1, 1);
        e.visibility = Element.Visibility.PRIVATE;
        check(Rules.canSee(A, e, team) && !Rules.canSee(B, e, team), "private: owner only");
        e.visibility = Element.Visibility.TEAM;
        check(Rules.canSee(B, e, team) && !Rules.canSee(C, e, team), "team: teammates");
        check(!Rules.canSee(B, e, none), "team without FTB Teams: owner only");
        e.visibility = Element.Visibility.PUBLIC;
        check(Rules.canSee(C, e, none), "public: everyone");

        e.visibility = Element.Visibility.TEAM;
        Element edit = e.copy();
        check(Rules.canEdit(A, false, true, e, edit, team) == null, "owner edits");
        check(Rules.canEdit(B, false, true, e, edit, team) == null, "teammate edits with teamCanEdit");
        check(Rules.canEdit(B, false, false, e, edit, team) != null, "teammate blocked without teamCanEdit");
        check(Rules.canEdit(C, false, true, e, edit, team) != null, "outsider blocked");
        check(Rules.canEdit(C, true, false, e, edit, team) == null, "op always edits");
        check(Rules.canEdit(C, false, true, null, edit, team) == null, "anyone creates");
        Element vis = e.copy();
        vis.visibility = Element.Visibility.PUBLIC;
        check(Rules.canEdit(B, false, true, e, vis, team) != null, "teammate cannot change visibility");
        e.locked = true;
        check(Rules.canEdit(B, false, true, e, edit, team) != null, "locked: teammate blocked");
        check(Rules.canEdit(A, false, true, e, edit, team) == null, "locked: owner edits");
        check(Rules.canEdit(C, true, true, e, edit, team) == null, "locked: op edits");
    }

    private static void validation() {
        Rules.Limits lim = new Rules.Limits(100, 10);
        Element e = rect(5, 5, 10, 10);
        check(Rules.validate(e, lim) == null, "valid rect");
        e.x = Double.NaN;
        check(Rules.validate(e, lim) != null, "NaN rejected");
        e.x = 5;
        e.text = "x".repeat(11);
        check(Rules.validate(e, lim) != null, "long text rejected");
        e.text = "";
        e.order = "a0V0";
        check(Rules.validate(e, lim) != null, "order ending in 0 rejected");
        e.order = "a0";
        e.type = Element.Type.LINE;
        e.pts = new float[202];
        check(Rules.validate(e, lim) != null, "too many points rejected");
        e.pts = new float[]{0, 0};
        check(Rules.validate(e, lim) != null, "one-point line rejected");
    }

    private static void simplify() {
        // A straight run of 100 collinear points plus one corner collapses to 3 points.
        float[] p = new float[202];
        for (int i = 0; i <= 99; i++) {
            p[i * 2] = i;
            p[i * 2 + 1] = 0;
        }
        p[200] = 99;
        p[201] = 50;
        float[] s = Geometry.simplify(p, 0.5);
        check(Arrays.equals(s, new float[]{0, 0, 99, 0, 99, 50}), "RDP keeps the corner: " + Arrays.toString(s));
        // Jitter below epsilon is dropped, above is kept.
        check(Geometry.simplify(new float[]{0, 0, 5, 0.2f, 10, 0}, 0.5).length == 4, "small wiggle dropped");
        check(Geometry.simplify(new float[]{0, 0, 5, 2f, 10, 0}, 0.5).length == 6, "big wiggle kept");
    }

    private static void hitTest() {
        Element r = rect(10, 10, 20, 10);
        check(Geometry.hit(r, 10, 15, 0.5), "on left edge");
        check(!Geometry.hit(r, 20, 15, 0.5), "unfilled interior misses");
        r.fillStyle = Element.FillStyle.SOLID;
        r.fill = 0xFF00FF00;
        check(Geometry.hit(r, 20, 15, 0.5), "filled interior hits");
        check(!Geometry.hit(r, 40, 15, 0.5), "outside misses");
        // Rotated 90 degrees about its centre (20, 15): the box now spans x 15..25, z 5..25.
        r.angle = (float) (Math.PI / 2);
        check(Geometry.hit(r, 20, 6, 0.5) && !Geometry.hit(r, 12, 15, 0.5), "rotated hit");
        Element line = rect(0, 0, 0, 0);
        line.type = Element.Type.LINE;
        line.pts = new float[]{0, 0, 10, 10};
        line.fitPoints();
        check(Geometry.hit(line, 5, 5.3, 0.5) && !Geometry.hit(line, 5, 8, 0.5), "line hit");
        check(Geometry.insideBox(line, -1, -1, 11, 11) && !Geometry.insideBox(line, 1, -1, 11, 11), "box select");
        // Arrow bound to a rectangle from the left lands on its left edge, outside by the gap.
        Element target = rect(10, -5, 10, 10);
        double[] bp = Geometry.bindPoint(target, 0, 0, 1);
        check(Math.abs(bp[0] - 9) < 1e-6 && Math.abs(bp[1]) < 1e-6, "bind point " + Arrays.toString(bp));
    }

    private static void rough() {
        Element e = rect(0, 0, 50, 30);
        e.seed = 1234;
        e.roughness = 2;
        e.fillStyle = Element.FillStyle.HACHURE;
        e.fill = 0xFF336699;
        Tessellator.Mesh a = Tessellator.tessellate(e, 1, 2), b = Tessellator.tessellate(e.copy(), 1, 2);
        check(a.count > 0 && a.count == b.count && Arrays.equals(a.xy, b.xy), "same seed, same wobble");
        e.seed = 99;
        Tessellator.Mesh c = Tessellator.tessellate(e, 1, 2);
        check(!Arrays.equals(a.xy, c.xy), "different seed, different wobble");
        e.roughness = 0;
        float[] straight = Rough.edges(new float[]{0, 0, 10, 0}, false, 0, 1, new Random(1)).get(0);
        check(Arrays.equals(straight, new float[]{0, 0, 10, 0}), "architect is straight");
        e.type = Element.Type.ELLIPSE;
        e.roughness = 1;
        check(Tessellator.tessellate(e, 1, 2).count > 0, "rough ellipse tessellates");
    }

    private static void order() {
        String a = FracIndex.between(null, null);
        String b = FracIndex.between(a, null), z = FracIndex.between(null, a);
        check(z.compareTo(a) < 0 && a.compareTo(b) < 0, "before/after");
        // Repeated insertion at the same spot keeps finding room and stays valid.
        String lo = a, hi = b;
        for (int i = 0; i < 200; i++) {
            String m = FracIndex.between(lo, hi);
            check(lo.compareTo(m) < 0 && m.compareTo(hi) < 0 && FracIndex.valid(m), "between " + lo + " " + hi + " -> " + m);
            if ((i & 1) == 0) hi = m;
            else lo = m;
        }
        check(FracIndex.between("a0", "a1").compareTo("a1") < 0, "adjacent integers");
    }

    /** Excalidraw-style keys stay short for appends/prepends and valid under random inserts (finding #4). */
    private static void orderAtScale() {
        String k = null;
        for (int i = 0; i < 100_000; i++) {
            String n = FracIndex.between(k, null);
            check(k == null || k.compareTo(n) < 0, "append order");
            k = n;
        }
        check(FracIndex.valid(k) && k.length() <= 6, "100k appends stay short: " + k);
        k = null;
        for (int i = 0; i < 100_000; i++) {
            String n = FracIndex.between(null, k);
            check(k == null || n.compareTo(k) < 0, "prepend order");
            k = n;
        }
        check(FracIndex.valid(k) && k.length() <= 6, "100k prepends stay short: " + k);
        java.util.List<String> keys = new java.util.ArrayList<>(java.util.List.of(FracIndex.between(null, null)));
        Random rnd = new Random(42);
        int longest = 0;
        for (int i = 0; i < 20_000; i++) {
            int at = rnd.nextInt(keys.size() + 1);
            String n = FracIndex.between(at == 0 ? null : keys.get(at - 1), at == keys.size() ? null : keys.get(at));
            check(FracIndex.valid(n), "random insert valid: " + n);
            keys.add(at, n);
            longest = Math.max(longest, n.length());
        }
        for (int i = 1; i < keys.size(); i++) check(keys.get(i - 1).compareTo(keys.get(i)) < 0, "random inserts sorted");
        check(longest <= 16, "random inserts stay short: " + longest);
    }

    /** Huge thin dotted/dashed shapes and long zigzag strokes stay under the vertex cap (finding #1). */
    private static void tessellationCaps() {
        Element big = rect(0, 0, 1e5, 1e5);
        big.strokeWidth = Rules.MIN_STROKE;
        big.strokeStyle = Element.StrokeStyle.DOTTED;
        big.roughness = 2;
        big.fillStyle = Element.FillStyle.CROSS_HATCH;
        big.fill = 0xFF00FF00;
        long t0 = System.nanoTime();
        Tessellator.Mesh m = Tessellator.tessellate(big, Rules.MIN_STROKE, 64);
        check(m.count > 0 && m.count <= Tessellator.MAX_VERTICES, "dotted 1e5 rect capped: " + m.count);
        big.strokeStyle = Element.StrokeStyle.DASHED;
        check(Tessellator.tessellate(big, Rules.MIN_STROKE, 64).count <= Tessellator.MAX_VERTICES, "dashed capped");
        Element zig = rect(0, 0, 0, 0);
        zig.type = Element.Type.FREEDRAW;
        zig.pts = new float[4000];
        for (int i = 0; i < 2000; i++) {
            zig.pts[i * 2] = (i % 2) * 99_000f;
            zig.pts[i * 2 + 1] = i * 40f;
        }
        zig.fitPoints();
        zig.strokeWidth = Rules.MIN_STROKE;
        zig.strokeStyle = Element.StrokeStyle.DOTTED;
        check(Tessellator.tessellate(zig, Rules.MIN_STROKE, 64).count <= Tessellator.MAX_VERTICES, "dotted zigzag capped");
        zig.strokeStyle = Element.StrokeStyle.SOLID;
        check(Tessellator.tessellate(zig, 50, 64).count <= Tessellator.MAX_VERTICES, "solid zigzag capped");
        // A 2000-point sloppy dashed line (straight edges, so each segment used to be its own rough stroke).
        Element line = rect(0, 0, 0, 0);
        line.type = Element.Type.LINE;
        line.pts = zig.pts.clone();
        line.fitPoints();
        line.roughness = 2;
        line.strokeWidth = Rules.MIN_STROKE;
        line.strokeStyle = Element.StrokeStyle.DASHED;
        for (int i = 0; i < 3; i++) Tessellator.tessellate(line, Rules.MIN_STROKE, 64);
        long l0 = System.nanoTime();
        Tessellator.Mesh lm = Tessellator.tessellate(line, Rules.MIN_STROKE, 64);
        double ms = (System.nanoTime() - l0) / 1e6;
        check(lm.count > 0 && lm.count <= Tessellator.MAX_VERTICES && ms < 60, "2000-point dashed line: " + ms + " ms, " + lm.count + " verts");
        System.out.printf("  2000-point sloppy dashed line: %.1f ms, %d vertices%n", ms, lm.count);
        // A realistic long polyline (gentle wave): mitred joins keep it far under the cap.
        Element wave = rect(0, 0, 0, 0);
        wave.type = Element.Type.LINE;
        wave.pts = new float[4000];
        for (int i = 0; i < 2000; i++) {
            wave.pts[i * 2] = i * 5f;
            wave.pts[i * 2 + 1] = (float) (Math.sin(i * 0.05) * 50);
        }
        wave.fitPoints();
        wave.strokeWidth = 2;
        wave.roughness = 1;
        Tessellator.Mesh solid = Tessellator.tessellate(wave, 2, 2);
        check(solid.count < Tessellator.MAX_VERTICES / 4, "solid 2000-point wave well under the cap: " + solid.count);
        check((System.nanoTime() - t0) / 1e9 < 5, "caps keep tessellation fast");
        Element thin = rect(0, 0, 10, 10);
        thin.strokeWidth = 0.001f;
        check(Rules.validate(thin, new Rules.Limits(100, 10)) != null, "sub-minimum stroke rejected");
    }

    private static void codec() {
        Element e = rect(-123.5, 1e6, 3, 4);
        e.type = Element.Type.ARROW;
        e.pts = new float[]{0, 0, 3.5f, -2};
        e.text = "hi";
        e.groups = new UUID[]{B};
        e.endBind = C;
        e.version = 7;
        e.nonce = -3;
        e.baseNonce = 11;
        Element back = ElementCodec.fromBytes(ElementCodec.toBytes(e));
        check(back.sameContent(e) && back.id.equals(e.id) && back.version == 7 && back.nonce == -3 && back.baseNonce == 11
                && back.owner.equals(A), "codec round trip");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
