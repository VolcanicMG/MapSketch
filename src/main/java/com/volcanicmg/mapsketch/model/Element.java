package com.volcanicmg.mapsketch.model;

import java.util.Arrays;
import java.util.UUID;

/**
 * One vector drawing element in world block coordinates. Mutable; edits work on a {@link #copy()} and bump
 * {@link #version}. Pure Java so the self-check can exercise it without Minecraft.
 */
public final class Element {
    // New types go last: the codec stores the ordinal.
    public enum Type { RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, STAMP }

    public enum FillStyle { NONE, HACHURE, CROSS_HATCH, SOLID }

    public enum StrokeStyle { SOLID, DASHED, DOTTED }

    public enum Head { NONE, ARROW, TRIANGLE, BAR, DOT }

    public enum Align { LEFT, CENTER, RIGHT }

    public enum Visibility { PRIVATE, TEAM, PUBLIC }

    public UUID id;
    public String dim;
    public Type type;
    public double x, z, w, h;
    public float[] pts = new float[0];
    public float angle;
    public int stroke = 0xFF1E1E1E, fill = 0;
    public FillStyle fillStyle = FillStyle.NONE;
    public float strokeWidth = 1;
    public StrokeStyle strokeStyle = StrokeStyle.SOLID;
    public int roughness = 1;
    public boolean round;
    public int opacity = 100;
    public Head arrowStart = Head.NONE, arrowEnd = Head.NONE;
    public String text = "";
    /** STAMP only: {@code item:<namespace>:<path>} (an item/block icon) or {@code sym:<name>} (built-in symbol). */
    public String stamp = "";
    public float fontSize = 6;
    public Align align = Align.CENTER;
    public UUID startBind, endBind;
    /**
     * Fixed binding point of each end in the target's local frame, normalized to its unrotated box (0..1), or null
     * for an edge binding (end sits on the outline, aimed at the other end).
     */
    public float[] startFixed, endFixed;
    public int seed;
    public UUID[] groups = new UUID[0];
    public String order = "a0";
    /**
     * Server-assigned version, and {@code nonce} = random token naming this exact state. On a client-to-server
     * edit, {@code version}/{@code baseNonce} name the state the edit was made from (0/0 = new element).
     */
    public int version, nonce, baseNonce;
    public UUID owner;
    public Visibility visibility = Visibility.PUBLIC;
    public boolean locked, deleted;
    public long updated;

    /**
     * @return true for a TEXT element with a background box: a non-transparent background colour with fill style
     * SOLID (what the panel sets; older text that merely inherited a shape fill style stays without a box)
     */
    public boolean textBackground() {
        return type == Type.TEXT && (fill >>> 24) != 0 && fillStyle == FillStyle.SOLID;
    }

    /** @return padding of a text background box around the text, in blocks (0 without a background) */
    public double textPad() {
        return textBackground() ? fontSize * 0.25 : 0;
    }

    public boolean linear() {
        return type == Type.ARROW || type == Type.LINE || type == Type.FREEDRAW;
    }

    public boolean closed() {
        return type == Type.RECTANGLE || type == Type.DIAMOND || type == Type.ELLIPSE;
    }

    public int pointCount() {
        return pts.length / 2;
    }

    /** Recomputes {@link #w}/{@link #h} of a linear element from its points (min corner may be negative). */
    public void fitPoints() {
        if (!linear() || pts.length < 2) return;
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < pts.length; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minZ = Math.min(minZ, pts[i + 1]);
            maxZ = Math.max(maxZ, pts[i + 1]);
        }
        // Keep the first point at the origin; bounds are [x+minX, x+maxX].
        w = maxX - minX;
        h = maxZ - minZ;
    }

    /** @return local-space bounds {minX, minZ, maxX, maxZ} relative to (x, z), unrotated */
    public double[] localBounds() {
        if (!linear()) {
            double p = textPad();
            return p == 0 ? new double[]{0, 0, w, h} : new double[]{-p, -p, w + p, h + p};
        }
        double minX = 0, minZ = 0, maxX = 0, maxZ = 0;
        for (int i = 0; i < pts.length; i += 2) {
            minX = Math.min(minX, pts[i]);
            maxX = Math.max(maxX, pts[i]);
            minZ = Math.min(minZ, pts[i + 1]);
            maxZ = Math.max(maxZ, pts[i + 1]);
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    /** @return rotation centre in world coordinates {x, z} */
    public double[] center() {
        double[] b = localBounds();
        return new double[]{x + (b[0] + b[2]) / 2, z + (b[1] + b[3]) / 2};
    }

    /** @return axis-aligned world bounds {minX, minZ, maxX, maxZ} including rotation */
    public double[] worldBounds() {
        double[] b = localBounds(), c = center();
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[][] corners = {{b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}};
        for (double[] p : corners) {
            double dx = x + p[0] - c[0], dz = z + p[1] - c[1];
            double wx = c[0] + dx * cos - dz * sin, wz = c[1] + dx * sin + dz * cos;
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minZ = Math.min(minZ, wz);
            maxZ = Math.max(maxZ, wz);
        }
        return new double[]{minX, minZ, maxX, maxZ};
    }

    public Element copy() {
        Element e = new Element();
        e.id = id;
        e.dim = dim;
        e.type = type;
        e.x = x;
        e.z = z;
        e.w = w;
        e.h = h;
        e.pts = pts.clone();
        e.angle = angle;
        e.stroke = stroke;
        e.fill = fill;
        e.fillStyle = fillStyle;
        e.strokeWidth = strokeWidth;
        e.strokeStyle = strokeStyle;
        e.roughness = roughness;
        e.round = round;
        e.opacity = opacity;
        e.arrowStart = arrowStart;
        e.arrowEnd = arrowEnd;
        e.text = text;
        e.stamp = stamp;
        e.fontSize = fontSize;
        e.align = align;
        e.startBind = startBind;
        e.endBind = endBind;
        e.startFixed = startFixed == null ? null : startFixed.clone();
        e.endFixed = endFixed == null ? null : endFixed.clone();
        e.seed = seed;
        e.groups = groups.clone();
        e.order = order;
        e.version = version;
        e.nonce = nonce;
        e.baseNonce = baseNonce;
        e.owner = owner;
        e.visibility = visibility;
        e.locked = locked;
        e.deleted = deleted;
        e.updated = updated;
        return e;
    }

    /** @return true when everything a user edits (geometry, style, flags) is equal; ignores ownership and versioning */
    public boolean sameContent(Element o) {
        return type == o.type && x == o.x && z == o.z && w == o.w && h == o.h && Arrays.equals(pts, o.pts)
                && angle == o.angle && stroke == o.stroke && fill == o.fill && fillStyle == o.fillStyle
                && strokeWidth == o.strokeWidth && strokeStyle == o.strokeStyle && roughness == o.roughness
                && round == o.round && opacity == o.opacity && arrowStart == o.arrowStart && arrowEnd == o.arrowEnd
                && text.equals(o.text) && stamp.equals(o.stamp) && fontSize == o.fontSize && align == o.align
                && java.util.Objects.equals(startBind, o.startBind) && java.util.Objects.equals(endBind, o.endBind)
                && Arrays.equals(startFixed, o.startFixed) && Arrays.equals(endFixed, o.endFixed)
                && Arrays.equals(groups, o.groups) && order.equals(o.order) && visibility == o.visibility
                && locked == o.locked && deleted == o.deleted;
    }
}
