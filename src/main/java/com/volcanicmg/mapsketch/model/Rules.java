package com.volcanicmg.mapsketch.model;

import java.util.UUID;
import java.util.function.BiPredicate;

/** Reconciliation, visibility, edit permission and validation. Pure so the self-check covers it. */
public final class Rules {
    private Rules() {
    }

    /** Thinnest stroke in blocks; keeps dash and hachure counts finite. */
    public static final float MIN_STROKE = 0.05f;

    /** Server-configured limits. */
    public record Limits(int maxPoints, int maxText) {
    }

    /**
     * Server-side reconciliation with server-assigned versions: an edit applies only to the exact state it was made
     * from ({@code version} and {@code baseNonce} name it; 0/0 = a new element).
     *
     * @return true if {@code edit} was made from {@code stored}
     */
    public static boolean basedOn(Element edit, Element stored) {
        if (stored == null) return edit.version == 0;
        return edit.version == stored.version && edit.baseNonce == stored.nonce;
    }

    /**
     * Client-side: does a state from the server replace ours? Higher version always; the same version with another
     * state token only when we have nothing pending for it (else our op's ack or forced resync decides).
     */
    public static boolean replaces(Element incoming, Element mine, boolean pending) {
        if (mine == null) return true;
        if (incoming.version != mine.version) return incoming.version > mine.version;
        return incoming.nonce != mine.nonce && !pending;
    }

    /**
     * Undo/redo guard: our last write left the element in the state named {@code nonce} (deleted or not).
     *
     * @return true if nobody changed it since, so undoing cannot clobber someone else's edit
     */
    public static boolean unchangedSince(int nonce, boolean deleted, Element current) {
        return current != null && current.nonce == nonce && current.deleted == deleted;
    }

    /**
     * @param sameTeam (owner, viewer) are on the same team or allied; always false without FTB Teams
     */
    public static boolean canSee(UUID viewer, Element e, BiPredicate<UUID, UUID> sameTeam) {
        if (viewer.equals(e.owner)) return true;
        return switch (e.visibility) {
            case PUBLIC -> true;
            case TEAM -> e.owner != null && sameTeam.test(e.owner, viewer);
            case PRIVATE -> false;
        };
    }

    /**
     * @param existing stored element, or null for a new one
     * @return null if {@code viewer} may write {@code incoming}, else the reason
     */
    public static String canEdit(UUID viewer, boolean op, boolean teamCanEdit, Element existing, Element incoming,
                                 BiPredicate<UUID, UUID> sameTeam) {
        if (existing == null || op) return null;
        boolean owner = viewer.equals(existing.owner);
        if (owner) return null;
        if (existing.locked) return "locked";
        if (!teamCanEdit || !canSee(viewer, existing, sameTeam)) return "not yours";
        if (incoming.visibility != existing.visibility || incoming.locked != existing.locked)
            return "only the owner can change visibility or lock";
        return null;
    }

    /** @return null if the element is well formed and within limits, else the reason */
    public static String validate(Element e, Limits limits) {
        if (e.id == null || e.dim == null || e.type == null) return "missing fields";
        if (!finite(e.x) || !finite(e.z) || Math.abs(e.x) > 3.0e7 || Math.abs(e.z) > 3.0e7) return "bad position";
        if (!finite(e.w) || !finite(e.h) || e.w < 0 || e.h < 0 || e.w > 1e5 || e.h > 1e5) return "bad size";
        if (!Float.isFinite(e.angle)) return "bad angle";
        if (e.pointCount() > limits.maxPoints()) return "too many points";
        for (float f : e.pts) if (!Float.isFinite(f) || Math.abs(f) > 1e5) return "bad point";
        if (e.linear() && e.pointCount() < 2 && !e.deleted) return "too few points";
        if (e.text.length() > limits.maxText()) return "text too long";
        if (!(e.strokeWidth >= MIN_STROKE && e.strokeWidth <= 1000)) return "bad stroke width";
        if (!(e.fontSize >= 0.5f && e.fontSize <= 2000)) return "bad font size";
        if (e.roughness < 0 || e.roughness > 2 || e.opacity < 0 || e.opacity > 100) return "bad style";
        if (!FracIndex.valid(e.order)) return "bad order";
        if (!validFixed(e.startFixed, e.startBind) || !validFixed(e.endFixed, e.endBind)) return "bad binding";
        if (e.type == Element.Type.STAMP ? !validStamp(e.stamp) : !e.stamp.isEmpty()) return "bad stamp";
        return null;
    }

    /**
     * Stamp id syntax: {@code sym:<known symbol>} or {@code item:<namespace>:<path>} with resource-location
     * characters. Whether an item id exists is checked by the server against its registry.
     */
    public static boolean validStamp(String id) {
        if (id == null || id.isEmpty() || id.length() > ElementCodec.MAX_STAMP) return false;
        if (id.startsWith("sym:")) return com.volcanicmg.mapsketch.render.Symbols.has(id.substring(4));
        if (!id.startsWith("item:")) return false;
        String rl = id.substring(5);
        // Air has no icon: it would be an invisible, still selectable stamp.
        if (rl.equals("minecraft:air")) return false;
        int colon = rl.indexOf(':');
        if (colon <= 0 || colon == rl.length() - 1 || rl.indexOf(':', colon + 1) >= 0) return false;
        for (int i = 0; i < rl.length(); i++) {
            char c = rl.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == ':'
                    || c == '/' && i > colon;
            if (!ok) return false;
        }
        return true;
    }

    /** A fixed binding point: absent, or a finite normalized pair on an end that is actually bound. */
    private static boolean validFixed(float[] f, UUID bind) {
        if (f == null) return true;
        if (bind == null || f.length != 2) return false;
        for (float v : f) if (!Float.isFinite(v) || v < 0 || v > 1) return false;
        return true;
    }

    private static boolean finite(double d) {
        return Double.isFinite(d);
    }
}
