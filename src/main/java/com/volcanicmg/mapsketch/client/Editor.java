package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Bindings;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.FracIndex;
import com.volcanicmg.mapsketch.model.Geometry;
import com.volcanicmg.mapsketch.net.LeasePayload;
import com.volcanicmg.mapsketch.sync.Live;
import com.volcanicmg.mapsketch.sync.SyncServer;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * The drawing editor on Xaero's world map: draw mode, tools, selection and transforms, text editing, arrow
 * binding, clipboard and undo/redo of your own edits. Input arrives from {@code WorldMapHooks} as GUI-pixel
 * coordinates plus the current {@link MapView}; returning true consumes the event so Xaero never sees it.
 */
public final class Editor {
    public enum Tool {
        HAND('H', '\0'), SELECT('V', '1'), RECTANGLE('R', '2'), DIAMOND('D', '3'), ELLIPSE('O', '4'), ARROW('A', '5'),
        LINE('L', '6'), FREEDRAW('P', '7'), TEXT('T', '8'), ERASER('E', '0'), LASER('K', '\0');

        public final char key, digit;

        Tool(char key, char digit) {
            this.key = key;
            this.digit = digit;
        }
    }

    /** Style for the next shape; also mirrors the selection so the panel shows it. */
    public static final class Style {
        public int stroke = Config.parseColor(Config.DEFAULT_STROKE.get(), 0xFF1E1E1E);
        public int fill = Config.parseColor(Config.DEFAULT_FILL.get(), 0);
        public Element.FillStyle fillStyle = Element.FillStyle.HACHURE;
        public int width = Config.DEFAULT_WIDTH.get().ordinal();
        public Element.StrokeStyle strokeStyle = Element.StrokeStyle.SOLID;
        public int roughness = Config.DEFAULT_ROUGHNESS.get();
        public boolean round = true;
        public int opacity = 100;
        public Element.Head arrowStart = Element.Head.NONE, arrowEnd = Element.Head.ARROW;
        public int font = 1;
        public Element.Align align = Element.Align.LEFT;
        /** Null until the user picks one: the server default applies. */
        public Element.Visibility visibility;
        public boolean locked;
    }

    private enum Drag { NONE, CREATE, LINE, FREEDRAW, MOVE, RESIZE, ROTATE, BOX, ERASE, POINT, LASER }

    /** One element's undo record; the state token we left it in detects edits by others since (undo then skips it). */
    private static final class Change {
        final Element before, after; // before null = created; after.deleted = deleted
        int expect; // nonce of the state our last write left
        boolean expectDeleted;

        Change(Element before, Element after) {
            this.before = before;
            this.after = after;
            this.expect = after.nonce;
            this.expectDeleted = after.deleted;
        }
    }

    public static final float[] WIDTHS = {1, 2, 4};
    public static final float[] FONTS = {8, 12, 18, 28};
    private static final long DOUBLE_CLICK_MS = 300, LIVE_MS = 50;

    private static boolean drawMode, mapOpen, spaceDown, liveActive;
    private static Tool tool = Tool.SELECT;
    public static final Style style = new Style();
    private static final LinkedHashSet<UUID> selection = new LinkedHashSet<>();
    private static Drag drag = Drag.NONE;
    private static int handleX, handleZ, pointIndex;
    private static double startX, startZ, startGX, startGY, curX, curZ;
    private static final Map<UUID, Element> snapshot = new HashMap<>();
    // Arrows dragged along by bindings: their drag-start state, for undo and sending.
    private static final Map<UUID, Element> followers = new HashMap<>();
    private static double[] frame; // cx, cz, angle, hw, hh at drag start
    private static Element draft; // shape being created, not in the store yet
    private static Element editing; // text being edited, in the store locally
    private static Element editingBefore; // its state before editing, null if new
    private static final Set<UUID> erasing = new HashSet<>();
    private static final List<double[]> laser = new ArrayList<>(); // x, z, time ms
    private static final Deque<List<Change>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private static List<Element> clipboard = List.of();
    private static long lastClickMs, lastLiveMs, lastRenewMs, streamKeyMs;
    // Lease of the running gesture: request id (0 = none) and the ids asked for.
    private static int leaseRequest, gestureLease;
    private static final Set<UUID> leased = new LinkedHashSet<>();
    // Bound arrows leased as optional ids of the running grab; only these follow it.
    private static final Set<UUID> followable = new LinkedHashSet<>();
    // Freehand streaming: which draft, how many points the others already have.
    private static UUID streamId;
    private static int streamSent;
    private static double lastClickGX, lastClickGY;
    private static MapView view;
    private static double mouseGX, mouseGY;

    private Editor() {
    }

    // ---- state accessors for the UI and glue ----

    public static boolean drawMode() {
        return drawMode;
    }

    public static Tool tool() {
        return tool;
    }

    public static Set<UUID> selection() {
        return selection;
    }

    public static boolean editingText() {
        return editing != null;
    }

    /** @return true when draw mode can be used (server has MapSketch) */
    public static boolean available() {
        return Config.ENABLED.get() && ClientStore.connected();
    }

    public static void toggleDrawMode() {
        if (!drawMode && !ClientStore.connected()) {
            ClientStore.toast(net.minecraft.network.chat.Component.translatable("mapsketch.no_server"));
            return;
        }
        drawMode = !drawMode;
        if (!drawMode) cancelAll();
    }

    public static void setTool(Tool t) {
        finishPolyline();
        commitText();
        tool = t;
        if (t != Tool.SELECT) selection.clear();
    }

    public static void onMapOpened() {
        mapOpen = true;
    }

    public static void onMapClosed() {
        cancelAll();
        mapOpen = false;
        spaceDown = false;
        sendLiveInactive();
    }

    public static void onDisconnect() {
        drawMode = false;
        cancelAll();
        undo.clear();
        redo.clear();
        clipboard = List.of();
    }

    private static void cancelAll() {
        EditorUi.closePicker();
        finishPolyline();
        commitText();
        if (drag != Drag.NONE) endDrag();
        draft = null;
        erasing.clear();
        laser.clear();
        selection.clear();
    }

    /**
     * Remote updates may delete or hide selected elements, or force them back (rollback): a gesture on such an
     * element stops, keeping the server's state.
     */
    static void onRemoteChange(List<UUID> touched) {
        selection.removeIf(id -> live(id) == null);
        boolean textHit = editing != null && editingBefore != null && (ClientStore.get(editing.id) == null || touched.contains(editing.id));
        boolean grabHit = isGrab() && touched.stream().anyMatch(id -> snapshot.containsKey(id) || followers.containsKey(id));
        // The touched elements already hold the server's state; everything else in the gesture goes back.
        if (textHit || grabHit) cancelGesture(new HashSet<>(touched));
    }

    /** The server refused part of an op (it already resynced those elements). */
    static void onRejected(List<UUID> ids) {
        onRemoteChange(ids);
    }

    /**
     * Lease answer or revocation ({@code requestId = -1}). Refused or revoked while the gesture runs: roll back.
     * Expired (the lease lifetime cap) while it still runs: ask again. Granted: bound arrows the server did not grant
     * stop following.
     */
    static void onLeaseReply(int requestId, boolean granted, List<UUID> ids, String reason) {
        if (granted) {
            if (requestId != gestureLease) return;
            for (UUID id : new ArrayList<>(followable))
                if (!ids.contains(id)) {
                    followable.remove(id);
                    Element before = followers.remove(id);
                    if (before != null) ClientStore.restore(before);
                }
            return;
        }
        boolean ours = requestId == -1 ? ids.stream().anyMatch(leased::contains) : requestId == gestureLease && gestureLease != 0;
        if (!ours) return;
        if (requestId == -1 && "expired".equals(reason) && (isGrab() || editing != null)) {
            gestureLease = ++leaseRequest;
            ClientStore.lease(gestureLease, LeasePayload.Action.ACQUIRE, new ArrayList<>(leased), new ArrayList<>(followable));
            return;
        }
        rollbackGesture(reason, requestId == -1 ? ids : null);
    }

    /** Ends the running grab or text edit without sending: elements not in {@code keep} go back to their start state. */
    private static void cancelGesture(Set<UUID> keep) {
        if (isGrab()) {
            followers.forEach((id, e) -> {
                if (!keep.contains(id)) ClientStore.restore(e);
            });
            snapshot.forEach((id, e) -> {
                if (!keep.contains(id)) ClientStore.restore(e);
            });
            snapshot.clear();
            followers.clear();
            followable.clear();
            drag = Drag.NONE;
        }
        if (editing != null && editingBefore != null) {
            if (!keep.contains(editing.id)) ClientStore.restore(editingBefore);
            editing = null;
            editingBefore = null;
        }
        releaseLease();
    }

    private static boolean isGrab() {
        return drag == Drag.MOVE || drag == Drag.RESIZE || drag == Drag.ROTATE || drag == Drag.POINT;
    }

    /** Puts grabbed elements back to their drag-start state (nothing was sent) and tells the user why. */
    /** @param revoked ids the server took away (null = the whole request was refused, nothing is held) */
    private static void rollbackGesture(String reason, List<UUID> revoked) {
        // The server already dropped those; whatever else the gesture held is released by cancelGesture.
        if (revoked == null) {
            leased.clear();
            followable.clear();
        } else {
            revoked.forEach(leased::remove);
            revoked.forEach(followable::remove);
        }
        gestureLease = 0;
        cancelGesture(Set.of());
        ClientStore.toast(ClientStore.reasonText(reason));
    }

    /**
     * Asks the server for an edit lease on {@code ids} for the gesture that starts now; the gesture goes ahead
     * optimistically. Refuses at once when someone else is already known to hold one.
     */
    private static boolean beginLease(Set<UUID> ids, Set<UUID> optional) {
        ServerInfoPayload info = ClientStore.info();
        boolean op = info != null && info.op();
        for (UUID id : ids) {
            ClientStore.Holder h = ClientStore.heldBy(id);
            Element e = live(id);
            // Owners and operators take a lease over (the server tells the holder); everyone else waits.
            if (h != null && !op && (e == null || !me().equals(e.owner))) {
                ClientStore.toast(net.minecraft.network.chat.Component.translatable("mapsketch.held", h.name()));
                return false;
            }
        }
        if (ids.size() > SyncServer.MAX_LEASES) {
            ClientStore.toast(net.minecraft.network.chat.Component.translatable("mapsketch.too_many_grab", SyncServer.MAX_LEASES));
            return false;
        }
        // Copy first: optional may be the followable set, which releasing the previous lease would clear.
        List<UUID> opt = new ArrayList<>(optional);
        releaseLease();
        followable.addAll(opt);
        if (ids.isEmpty()) return true;
        gestureLease = ++leaseRequest;
        leased.addAll(ids);
        lastRenewMs = System.currentTimeMillis();
        if (ids.size() + opt.size() > SyncServer.MAX_LEASES) opt = opt.subList(0, SyncServer.MAX_LEASES - ids.size());
        ClientStore.lease(gestureLease, LeasePayload.Action.ACQUIRE, new ArrayList<>(ids), opt);
        return true;
    }

    /** Sends pending edits first, then gives the lease back. */
    private static void releaseLease() {
        if (leased.isEmpty() && followable.isEmpty()) return;
        ClientStore.flush();
        List<UUID> all = new ArrayList<>(leased);
        all.addAll(followable);
        // Release in chunks the server decoder accepts.
        for (int i = 0; i < all.size(); i += SyncServer.MAX_LEASES)
            ClientStore.lease(0, LeasePayload.Action.RELEASE, all.subList(i, Math.min(all.size(), i + SyncServer.MAX_LEASES)), List.of());
        followable.clear();
        leased.clear();
        gestureLease = 0;
    }

    private static Element live(UUID id) {
        Element e = ClientStore.get(id);
        return e == null || e.deleted ? null : e;
    }

    private static UUID me() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? new UUID(0, 0) : mc.player.getUUID();
    }

    /** Client-side mirror of the server's edit rule, so the UI does not offer edits that will bounce. */
    public static boolean canEdit(Element e) {
        ServerInfoPayload info = ClientStore.info();
        if (me().equals(e.owner) || info != null && info.op()) return true;
        return !e.locked && (info == null || info.teamCanEdit());
    }

    /** Blocks per GUI pixel rounded to a power of two: presets drawn zoomed out stay visible at that zoom. */
    private static double zoomFactor(MapView v) {
        return Math.pow(2, Math.round(Math.log(v.blocks(1)) / Math.log(2)));
    }

    // ---- element creation and edits ----

    private static Element create(Element.Type type, double x, double z, MapView v) {
        Element e = new Element();
        e.id = UUID.randomUUID();
        e.dim = v.dim();
        e.type = type;
        e.x = x;
        e.z = z;
        e.seed = ThreadLocalRandom.current().nextInt();
        e.owner = me();
        e.stroke = style.stroke;
        e.fill = style.fill;
        e.fillStyle = style.fillStyle;
        e.strokeWidth = (float) Math.max(com.volcanicmg.mapsketch.model.Rules.MIN_STROKE, WIDTHS[style.width] * zoomFactor(v));
        e.strokeStyle = style.strokeStyle;
        e.roughness = style.roughness;
        e.round = style.round && type != Element.Type.FREEDRAW;
        e.opacity = style.opacity;
        if (type == Element.Type.ARROW) {
            e.arrowStart = style.arrowStart;
            e.arrowEnd = style.arrowEnd;
        }
        e.fontSize = (float) Math.max(0.5, FONTS[style.font] * zoomFactor(v));
        e.align = style.align;
        ServerInfoPayload info = ClientStore.info();
        e.visibility = style.visibility != null ? style.visibility
                : info != null ? info.defaultVisibility() : Element.Visibility.PUBLIC;
        List<Element> all = ClientStore.sorted(v.dim());
        e.order = FracIndex.between(all.isEmpty() ? null : all.get(all.size() - 1).order, null);
        return e;
    }

    /** Stores and sends edited copies, recording one undo step from their current states. */
    private static void commit(List<Element> edited) {
        List<Change> changes = new ArrayList<>();
        for (Element e : edited) {
            Element cur = ClientStore.get(e.id);
            Element before = cur == null || cur.deleted ? null : cur.copy();
            ClientStore.local(e, true);
            changes.add(new Change(before, e.copy()));
        }
        record(changes);
    }

    private static void record(List<Change> changes) {
        if (changes.isEmpty()) return;
        undo.push(changes);
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
    }

    /** Applies {@code mut} to copies of every editable selected element and commits them. */
    public static void editSelection(Consumer<Element> mut) {
        List<Element> out = new ArrayList<>();
        for (UUID id : selection) {
            Element e = live(id);
            if (e == null || !canEdit(e)) continue;
            Element c = e.copy();
            mut.accept(c);
            if (c.type == Element.Type.TEXT) fitText(c);
            if (!c.sameContent(e)) out.add(c);
        }
        if (out.isEmpty()) return;
        // Bound arrows follow moved shapes (nudges, resizes from the panel), in the same undo step and op.
        if (view != null) {
            Map<UUID, Element> targets = new HashMap<>();
            for (Element c : out) if (!c.deleted) targets.put(c.id, c);
            Set<UUID> ids = new HashSet<>(targets.keySet());
            for (UUID id : Bindings.followers(ClientStore.sorted(view.dim()), ids)) {
                Element a = live(id);
                if (a == null || !canEdit(a) || ClientStore.heldBy(id) != null) continue;
                Element f = Bindings.follow(a, targets, view.blocks(4));
                if (!f.sameContent(a)) out.add(f);
            }
        }
        commit(out);
    }

    // Live preview (colour picker): the state before it and the token of our preview copy, per element.
    private static final Map<UUID, Element> previewBefore = new java.util.LinkedHashMap<>();
    private static final Map<UUID, Integer> previewNonce = new HashMap<>();

    /**
     * Shows {@code mut} on the editable selection locally only: nothing is sent and no undo step is recorded until
     * {@link #endPreview}. Each call starts again from the pre-preview state, so it can run every frame of a drag.
     */
    public static void previewSelection(Consumer<Element> mut) {
        for (UUID id : selection) {
            Element cur = live(id);
            if (cur == null || !canEdit(cur)) continue;
            Integer mine = previewNonce.get(id);
            // Someone else's state replaced our preview (or none yet): start from it.
            if (mine == null || cur.nonce != mine) previewBefore.put(id, cur.copy());
            Element c = previewBefore.get(id).copy();
            mut.accept(c);
            if (c.type == Element.Type.TEXT) fitText(c);
            ClientStore.local(c, false);
            previewNonce.put(id, c.nonce);
        }
    }

    /**
     * Ends a preview: kept = one property change, one undo step and one op for all changed elements; otherwise the
     * pre-preview states come back. Elements someone else changed meanwhile are left as they are.
     */
    public static void endPreview(boolean keep) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<UUID, Element> en : previewBefore.entrySet()) {
            Element cur = live(en.getKey());
            Integer mine = previewNonce.get(en.getKey());
            if (cur == null || mine == null || cur.nonce != mine) continue;
            Element before = en.getValue();
            if (!keep || before.sameContent(cur)) {
                ClientStore.restore(before);
                continue;
            }
            ClientStore.send(cur.id);
            changes.add(new Change(before, cur.copy()));
        }
        previewBefore.clear();
        previewNonce.clear();
        record(changes);
    }

    public static void deleteSelection() {
        editSelection(e -> e.deleted = true);
        selection.clear();
    }

    private static void undo(boolean redoIt) {
        Deque<List<Change>> from = redoIt ? redo : undo, to = redoIt ? undo : redo;
        if (from.isEmpty()) return;
        List<Change> step = from.pop();
        int skipped = 0;
        for (Change c : step) {
            // Includes our own tombstones, which the store keeps for exactly this.
            Element cur = ClientStore.get(c.after.id);
            // Someone else changed (or deleted, or restored) it since our write: skip rather than clobber.
            if (!com.volcanicmg.mapsketch.model.Rules.unchangedSince(c.expect, c.expectDeleted, cur)) {
                skipped++;
                continue;
            }
            Element target = redoIt ? c.after : c.before;
            Element e;
            if (target == null || target.deleted) {
                e = cur.copy();
                e.deleted = true;
            } else {
                e = target.copy();
                e.deleted = false;
            }
            // local() bases it on the current state (version and token), so the server applies it exactly there.
            ClientStore.local(e, true);
            c.expect = e.nonce;
            c.expectDeleted = e.deleted;
        }
        to.push(step);
        selection.removeIf(id -> live(id) == null);
        if (skipped > 0) ClientStore.toast(net.minecraft.network.chat.Component.translatable("mapsketch.undo_skipped", skipped));
    }

    // ---- input ----

    /** @return true to consume */
    public static boolean mousePressed(MapView v, double gx, double gy, int button) {
        pressConsumed = pressed(v, gx, gy, button);
        return pressConsumed;
    }

    private static boolean pressConsumed;

    private static boolean pressed(MapView v, double gx, double gy, int button) {
        view = v;
        mouseGX = gx;
        mouseGY = gy;
        if (EditorUi.mousePressed(gx, gy, button)) return true;
        if (!drawMode) return false;
        if (spaceDown || tool == Tool.HAND) {
            commitText();
            return false;
        }
        if (button != 0) {
            // Right-click cancels a shape or grab in progress; Xaero's context menu stays off while drawing.
            if (button == 1) {
                draft = null;
                if (isGrab()) cancelGesture(Set.of());
                else if (drag != Drag.NONE) {
                    drag = Drag.NONE;
                    erasing.clear();
                }
            }
            return true;
        }
        long now = System.currentTimeMillis();
        boolean dbl = now - lastClickMs < DOUBLE_CLICK_MS && Math.abs(gx - lastClickGX) < 4 && Math.abs(gy - lastClickGY) < 4;
        lastClickMs = now;
        lastClickGX = gx;
        lastClickGY = gy;
        double wx = v.worldX(gx), wz = v.worldZ(gy);
        startX = curX = wx;
        startZ = curZ = wz;
        startGX = gx;
        startGY = gy;
        if (editing != null) {
            if (Geometry.hit(editing, wx, wz, v.blocks(4))) return pressConsumed = true;
            commitText();
        }
        boolean shift = Screen.hasShiftDown();
        switch (tool) {
            case SELECT -> pressSelect(v, wx, wz, gx, gy, shift, dbl);
            case RECTANGLE, DIAMOND, ELLIPSE -> {
                draft = create(Element.Type.valueOf(tool.name()), wx, wz, v);
                drag = Drag.CREATE;
            }
            case ARROW, LINE -> pressLine(v, wx, wz, dbl);
            case FREEDRAW -> {
                draft = create(Element.Type.FREEDRAW, wx, wz, v);
                draft.pts = new float[]{0, 0};
                drag = Drag.FREEDRAW;
            }
            case TEXT -> {
                Element hit = topAt(v, wx, wz);
                startTextEdit(v, hit != null && hit.type != Element.Type.FREEDRAW ? hit : null, wx, wz);
            }
            case ERASER -> {
                drag = Drag.ERASE;
                eraseAt(v, wx, wz);
            }
            case LASER -> {
                drag = Drag.LASER;
                laser.add(new double[]{wx, wz, now});
            }
            default -> {
            }
        }
        return true;
    }

    private static void pressSelect(MapView v, double wx, double wz, double gx, double gy, boolean shift, boolean dbl) {
        if (selection.size() == 1) {
            Element one = live(selection.iterator().next());
            if (one != null && one.linear() && canEdit(one)) {
                int idx = pointHandleAt(v, one, gx, gy);
                if (idx >= 0) {
                    if (!beginSnapshot()) return;
                    pointIndex = idx;
                    drag = Drag.POINT;
                    return;
                }
            }
        }
        int[] h = handleAt(v, gx, gy);
        if (h != null) {
            frame = frame();
            if (!beginSnapshot()) return;
            handleX = h[0];
            handleZ = h[1];
            drag = h[2] == 1 ? Drag.ROTATE : Drag.RESIZE;
            return;
        }
        Element hit = topAt(v, wx, wz);
        if (dbl) {
            startTextEdit(v, hit != null && hit.type != Element.Type.FREEDRAW ? hit : null, wx, wz);
            return;
        }
        if (hit == null) {
            if (!shift) selection.clear();
            drag = Drag.BOX;
            return;
        }
        Set<UUID> group = groupOf(hit);
        if (shift) {
            if (selection.containsAll(group)) selection.removeAll(group);
            else selection.addAll(group);
            return;
        }
        if (!selection.contains(hit.id)) {
            selection.clear();
            selection.addAll(group);
        }
        syncStyleFromSelection();
        if (beginSnapshot()) drag = Drag.MOVE;
    }

    private static void pressLine(MapView v, double wx, double wz, boolean dbl) {
        if (draft != null && drag == Drag.NONE) {
            // Polyline in progress: a click fixes the trailing point and starts a new one; double-click finishes.
            if (dbl) {
                finishPolyline();
                return;
            }
            float[] p = draft.pts;
            float[] q = java.util.Arrays.copyOf(p, p.length + 2);
            q[p.length] = (float) (wx - draft.x);
            q[p.length + 1] = (float) (wz - draft.z);
            draft.pts = q;
            if (draft.pointCount() >= maxPoints()) finishPolyline();
            return;
        }
        draft = create(tool == Tool.ARROW ? Element.Type.ARROW : Element.Type.LINE, wx, wz, v);
        draft.pts = new float[]{0, 0, 0, 0};
        if (tool == Tool.LINE) draft.round = false;
        drag = Drag.LINE;
    }

    public static boolean mouseDragged(MapView v, double gx, double gy, int button) {
        view = v;
        mouseGX = gx;
        mouseGY = gy;
        if (EditorUi.mouseDragged(gx, gy)) return true;
        if (!drawMode || button != 0 || drag == Drag.NONE) return false;
        curX = v.worldX(gx);
        curZ = v.worldZ(gy);
        boolean shift = Screen.hasShiftDown();
        switch (drag) {
            case CREATE -> sizeDraft(shift);
            case LINE -> {
                setLastPoint(draft, curX, curZ, shift);
                int n = draft.pointCount();
                previewBind(draft.id, draft.x + draft.pts[(n - 1) * 2], draft.z + draft.pts[(n - 1) * 2 + 1]);
            }
            case FREEDRAW -> {
                float[] p = draft.pts;
                if (draft.pointCount() < maxPoints()) {
                    float[] q = java.util.Arrays.copyOf(p, p.length + 2);
                    q[p.length] = (float) (curX - draft.x);
                    q[p.length + 1] = (float) (curZ - draft.z);
                    draft.pts = q;
                }
            }
            case MOVE -> transform(e -> {
                e.x += curX - startX;
                e.z += curZ - startZ;
            });
            case RESIZE -> resize(shift);
            case ROTATE -> rotate(shift);
            case POINT -> {
                movePoint();
                Element pe = live(snapshot.keySet().iterator().next());
                if (pe != null && Bindings.binder(pe) && (pointIndex == 0 || pointIndex == pe.pointCount() - 1))
                    previewBind(pe.id, curX, curZ);
                else bindPreview = null;
            }
            case ERASE -> eraseAt(v, curX, curZ);
            case LASER -> laser.add(new double[]{curX, curZ, System.currentTimeMillis()});
            default -> {
            }
        }
        return true;
    }

    public static boolean mouseReleased(MapView v, double gx, double gy, int button) {
        view = v;
        if (EditorUi.mouseReleased(gx, gy)) {
            pressConsumed = false;
            return true;
        }
        boolean consumed = pressConsumed;
        pressConsumed = false;
        if (!drawMode || button != 0 || drag == Drag.NONE) return consumed;
        curX = v.worldX(gx);
        curZ = v.worldZ(gy);
        boolean moved = Math.abs(gx - startGX) > 3 || Math.abs(gy - startGY) > 3;
        switch (drag) {
            case CREATE -> {
                drag = Drag.NONE;
                if (moved) finishDraft(true);
                else draft = null;
            }
            case LINE -> {
                drag = Drag.NONE;
                if (moved) finishPolyline();
                // A click starts a polyline: keep the draft with its trailing point following the mouse.
            }
            case FREEDRAW -> {
                drag = Drag.NONE;
                draft.pts = Geometry.simplify(draft.pts, v.blocks(0.5));
                if (draft.pointCount() > maxPoints()) draft.pts = subsample(draft.pts, maxPoints());
                if (draft.pointCount() < 2) draft.pts = new float[]{0, 0, (float) v.blocks(0.5), 0};
                finishDraft(false);
            }
            case BOX -> {
                drag = Drag.NONE;
                double minX = Math.min(startX, curX), maxX = Math.max(startX, curX);
                double minZ = Math.min(startZ, curZ), maxZ = Math.max(startZ, curZ);
                for (Element e : ClientStore.sorted(v.dim()))
                    if (Geometry.insideBox(e, minX, minZ, maxX, maxZ)) selection.addAll(groupOf(e));
                syncStyleFromSelection();
            }
            case ERASE -> {
                drag = Drag.NONE;
                List<Element> del = new ArrayList<>();
                for (UUID id : erasing) {
                    Element e = live(id);
                    if (e == null) continue;
                    Element c = e.copy();
                    c.deleted = true;
                    del.add(c);
                }
                erasing.clear();
                if (!del.isEmpty()) commit(del);
            }
            case LASER -> drag = Drag.NONE;
            default -> endDrag();
        }
        return true;
    }

    /** Ends a move/resize/rotate/point drag: binds arrow ends, sends, records one undo step. */
    private static void endDrag() {
        Drag was = drag;
        drag = Drag.NONE;
        if (snapshot.isEmpty()) {
            releaseLease();
            return;
        }
        if (was == Drag.POINT) {
            Element e = live(snapshot.keySet().iterator().next());
            bindPreview = null;
            if (e != null && Bindings.binder(e) && (pointIndex == 0 || pointIndex == e.pointCount() - 1)) {
                Element c = e.copy();
                bindEnd(c, pointIndex == 0);
                ClientStore.local(c, false);
            }
        }
        List<Change> changes = new ArrayList<>();
        Map<UUID, Element> touched = new HashMap<>(followers);
        touched.putAll(snapshot);
        for (Map.Entry<UUID, Element> t : touched.entrySet()) {
            Element now = live(t.getKey());
            if (now != null && !t.getValue().sameContent(now)) {
                changes.add(new Change(t.getValue(), now.copy()));
                ClientStore.send(t.getKey());
            }
        }
        snapshot.clear();
        followers.clear();
        record(changes);
        releaseLease();
    }

    /** Snapshots the editable selection for a grab and asks for its lease; false if someone else holds it. */
    private static boolean beginSnapshot() {
        // The previous gesture's lease goes before this one's state is built.
        releaseLease();
        snapshot.clear();
        followers.clear();
        followable.clear();
        for (UUID id : selection) {
            Element e = live(id);
            if (e != null && canEdit(e)) snapshot.put(id, e.copy());
        }
        // Arrows bound to grabbed shapes follow them: lease them too where nobody else holds them (best effort).
        if (view != null)
            for (UUID id : Bindings.followers(ClientStore.sorted(view.dim()), snapshot.keySet())) {
                Element a = live(id);
                if (a != null && canEdit(a) && ClientStore.heldBy(id) == null) followable.add(id);
            }
        if (beginLease(snapshot.keySet(), followable)) return true;
        snapshot.clear();
        followable.clear();
        return false;
    }

    /**
     * Applies {@code mut} to fresh copies of the drag-start snapshot, stores them locally and moves bound arrows.
     */
    private static void transform(Consumer<Element> mut) {
        for (Element start : snapshot.values()) {
            Element e = start.copy();
            Element cur = live(e.id);
            if (cur == null) continue;
            e.version = cur.version;
            mut.accept(e);
            ClientStore.local(e, false);
        }
        // Local only while dragging: others see live ghosts; the op goes out on release.
        followBindings(drag == Drag.MOVE);
    }

    /** Arrows bound to moved shapes follow them; a moved arrow whose target stayed put lets go. */
    private static void followBindings(boolean unbindMoved) {
        if (view == null) return;
        Set<UUID> moved = snapshot.keySet();
        Map<UUID, Element> targets = new HashMap<>();
        for (UUID id : moved) {
            Element t = live(id);
            if (t != null) targets.put(id, t);
        }
        for (UUID id : moved) {
            Element a = live(id);
            if (a == null || !Bindings.binder(a) || !unbindMoved) continue;
            boolean s = a.startBind == null || moved.contains(a.startBind), en = a.endBind == null || moved.contains(a.endBind);
            if (s && en) continue;
            Element c = a.copy();
            if (!s) {
                c.startBind = null;
                c.startFixed = null;
            }
            if (!en) {
                c.endBind = null;
                c.endFixed = null;
            }
            ClientStore.local(c, false);
        }
        for (UUID id : followable) {
            Element a = live(id);
            if (a == null) continue;
            followers.putIfAbsent(id, a.copy());
            ClientStore.local(Bindings.follow(a, targets, view.blocks(4)), false);
        }
    }

    /** Rewrites a linear element without rotation: origin at its first world point. */
    private static Element bake(Element e) {
        return Bindings.bake(e);
    }

    /** @return points in world coordinates as doubles packed into floats relative to nothing (absolute) */
    private static float[] worldPoints(Element e) {
        float[] out = new float[e.pts.length];
        for (int i = 0; i < e.pts.length; i += 2) {
            double[] w = Geometry.toWorld(e, e.pts[i], e.pts[i + 1]);
            out[i] = (float) w[0];
            out[i + 1] = (float) w[1];
        }
        return out;
    }

    /** Moves a bound arrow end onto its target's outline, aimed from the neighbouring point. */
    private static void snapEnd(Element a, boolean start) {
        Element target = live(start ? a.startBind : a.endBind);
        if (target == null) return;
        Bindings.place(a, start, target, view == null ? 1 : view.blocks(4));
        a.fitPoints();
    }

    /** GUI-pixel radius of a link node hit, and width of the edge band; scaled to world blocks at the zoom. */
    private static final double DOT_PX = 6, BAND_PX = 8;

    /**
     * Binds a dropped arrow/line end, Excalidraw style: on a link node, at a fixed point well inside a shape, or on
     * its outline when dropped near it; Ctrl held = no binding.
     */
    private static void bindEnd(Element a, boolean start) {
        bake(a);
        int n = a.pointCount(), i = start ? 0 : n - 1;
        Bindings.Pick p = Screen.hasControlDown() ? null : pickAt(a.id, a.x + a.pts[i * 2], a.z + a.pts[i * 2 + 1]);
        Bindings.bind(a, start, p == null ? null : p.target(), p == null ? null : p.hit(), view == null ? 1 : view.blocks(4));
        a.fitPoints();
    }

    /** @return what an end at world (wx, wz) would bind to, topmost shape first, or null */
    private static Bindings.Pick pickAt(UUID self, double wx, double wz) {
        if (view == null) return null;
        List<Element> top = new ArrayList<>(ClientStore.sorted(view.dim()));
        Collections.reverse(top);
        return Bindings.pick(top, self, wx, wz, view.blocks(BAND_PX), view.blocks(DOT_PX));
    }

    /** Binding preview while an arrow/line end is dragged; null when none. */
    public record BindPreview(Element target, Bindings.Hit hit, double x, double z) {
    }

    private static BindPreview bindPreview;

    public static BindPreview bindPreview() {
        return bindPreview;
    }

    /** Recomputes the preview for the end being dragged (or the trailing point of a click-built line). */
    private static void previewBind(UUID self, double wx, double wz) {
        bindPreview = null;
        if (Screen.hasControlDown() || view == null) return;
        Bindings.Pick p = pickAt(self, wx, wz);
        if (p != null) {
            bindPreview = new BindPreview(p.target(), p.hit(), wx, wz);
            return;
        }
        // Near a shape but not binding yet: show its link nodes so they can be aimed at.
        List<Element> top = new ArrayList<>(ClientStore.sorted(view.dim()));
        Collections.reverse(top);
        Bindings.Pick near = Bindings.pick(top, self, wx, wz, view.blocks(BAND_PX * 3), view.blocks(DOT_PX));
        if (near != null) bindPreview = new BindPreview(near.target(), new Bindings.Hit(Bindings.Kind.NONE, 0, 0, -1), wx, wz);
    }

    private static void sizeDraft(boolean square) {
        double w = curX - startX, h = curZ - startZ;
        if (square) {
            double s = Math.max(Math.abs(w), Math.abs(h));
            w = Math.copySign(s, w);
            h = Math.copySign(s, h);
        }
        draft.x = Math.min(startX, startX + w);
        draft.z = Math.min(startZ, startZ + h);
        draft.w = Math.abs(w);
        draft.h = Math.abs(h);
    }

    /** Last point follows the cursor; shift snaps the segment to 15-degree steps. */
    private static void setLastPoint(Element e, double wx, double wz, boolean snap) {
        int n = e.pointCount();
        double px = e.x + e.pts[(n - 2) * 2], pz = e.z + e.pts[(n - 2) * 2 + 1];
        if (snap) {
            double a = Math.atan2(wz - pz, wx - px), len = Math.hypot(wx - px, wz - pz);
            a = Math.round(a / Math.toRadians(15)) * Math.toRadians(15);
            wx = px + Math.cos(a) * len;
            wz = pz + Math.sin(a) * len;
        }
        e.pts[(n - 1) * 2] = (float) (wx - e.x);
        e.pts[(n - 1) * 2 + 1] = (float) (wz - e.z);
        e.fitPoints();
    }

    private static void finishDraft(boolean select) {
        Element e = draft;
        draft = null;
        if (e == null) return;
        e.fitPoints();
        bindPreview = null;
        if (Bindings.binder(e)) {
            bindEnd(e, true);
            bindEnd(e, false);
        }
        List<Element> one = new ArrayList<>(List.of(e));
        commit(one);
        if (select) {
            selection.clear();
            selection.add(e.id);
            tool = Tool.SELECT;
        }
    }

    /** Finishes a click-built polyline, dropping the trailing point that followed the mouse. */
    private static void finishPolyline() {
        if (draft == null || !draft.linear() || draft.type == Element.Type.FREEDRAW) return;
        if (drag == Drag.NONE && draft.pointCount() > 2) draft.pts = java.util.Arrays.copyOf(draft.pts, draft.pts.length - 2);
        float[] p = draft.pts;
        double len = Math.hypot(p[p.length - 2] - p[0], p[p.length - 1] - p[1]);
        if (draft.pointCount() < 2 || draft.pointCount() == 2 && len < 1e-3) {
            draft = null;
            return;
        }
        finishDraft(true);
    }

    private static int maxPoints() {
        ServerInfoPayload info = ClientStore.info();
        // Stay well under the serverbound payload limit.
        return Math.min(2500, info == null ? 2000 : info.maxPoints());
    }

    private static float[] subsample(float[] p, int max) {
        int n = p.length / 2;
        float[] out = new float[max * 2];
        for (int i = 0; i < max; i++) {
            int k = (int) Math.round((double) i * (n - 1) / (max - 1));
            out[i * 2] = p[k * 2];
            out[i * 2 + 1] = p[k * 2 + 1];
        }
        return out;
    }

    private static void eraseAt(MapView v, double wx, double wz) {
        Element hit = topAt(v, wx, wz);
        if (hit != null && canEdit(hit)) erasing.addAll(groupOf(hit));
    }

    /** @return topmost element under the world point, or null */
    private static Element topAt(MapView v, double wx, double wz) {
        List<Element> all = ClientStore.sorted(v.dim());
        double tol = v.blocks(4);
        for (int i = all.size() - 1; i >= 0; i--) if (Geometry.hit(all.get(i), wx, wz, tol)) return all.get(i);
        return null;
    }

    /** @return the element plus everything sharing its outermost group */
    private static Set<UUID> groupOf(Element e) {
        Set<UUID> out = new LinkedHashSet<>();
        out.add(e.id);
        if (e.groups.length == 0 || view == null) return out;
        UUID g = e.groups[e.groups.length - 1];
        for (Element o : ClientStore.sorted(e.dim)) for (UUID x : o.groups) if (x.equals(g)) out.add(o.id);
        return out;
    }

    // ---- selection frame, resize, rotate ----

    /** @return {cx, cz, angle, halfW, halfH} of the selection: the element's own frame for one, else world AABB */
    static double[] frame() {
        List<Element> sel = new ArrayList<>();
        for (UUID id : selection) {
            Element e = live(id);
            if (e != null) sel.add(e);
        }
        if (sel.isEmpty()) return null;
        if (sel.size() == 1) {
            Element e = sel.get(0);
            double[] b = e.localBounds(), c = e.center();
            return new double[]{c[0], c[1], e.angle, (b[2] - b[0]) / 2, (b[3] - b[1]) / 2};
        }
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Element e : sel) {
            double[] b = e.worldBounds();
            minX = Math.min(minX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxX = Math.max(maxX, b[2]);
            maxZ = Math.max(maxZ, b[3]);
        }
        return new double[]{(minX + maxX) / 2, (minZ + maxZ) / 2, 0, (maxX - minX) / 2, (maxZ - minZ) / 2};
    }

    /** World position of frame point (hx, hz) in {-1, 0, 1}, with {@code out} pixels of extra offset outward. */
    static double[] framePoint(double[] f, double hx, double hz, double extraZ) {
        double lx = hx * f[3], lz = hz * f[4] + extraZ;
        double cos = Math.cos(f[2]), sin = Math.sin(f[2]);
        return new double[]{f[0] + lx * cos - lz * sin, f[1] + lx * sin + lz * cos};
    }

    /** @return {hx, hz, isRotate} of the handle under the GUI point, or null */
    private static int[] handleAt(MapView v, double gx, double gy) {
        if (selection.isEmpty() || single(true)) return null;
        double[] f = frame();
        if (f == null) return null;
        double[] r = framePoint(f, 0, -1, -v.blocks(16));
        if (near(v, r, gx, gy)) return new int[]{0, -1, 1};
        for (int hx = -1; hx <= 1; hx++)
            for (int hz = -1; hz <= 1; hz++) {
                if (hx == 0 && hz == 0) continue;
                if (near(v, framePoint(f, hx, hz, 0), gx, gy)) return new int[]{hx, hz, 0};
            }
        return null;
    }

    /** @return true when exactly one element is selected and it is a line/arrow (point handles instead of a frame) */
    static boolean single(boolean linear) {
        if (selection.size() != 1) return false;
        Element e = live(selection.iterator().next());
        return e != null && e.linear() == linear && e.type != Element.Type.FREEDRAW;
    }

    private static int pointHandleAt(MapView v, Element e, double gx, double gy) {
        if (e.type == Element.Type.FREEDRAW) return -1;
        for (int i = 0; i < e.pointCount(); i++)
            if (near(v, Geometry.toWorld(e, e.pts[i * 2], e.pts[i * 2 + 1]), gx, gy)) return i;
        return -1;
    }

    private static boolean near(MapView v, double[] w, double gx, double gy) {
        return Math.abs(v.guiX(w[0]) - gx) <= 5 && Math.abs(v.guiY(w[1]) - gy) <= 5;
    }

    private static void resize(boolean keepRatio) {
        double[] f = frame;
        double cos = Math.cos(-f[2]), sin = Math.sin(-f[2]);
        double dx = curX - f[0], dz = curZ - f[1];
        double mx = dx * cos - dz * sin, mz = dx * sin + dz * cos;
        double ax = -handleX * f[3], az = -handleZ * f[4];
        double min = view == null ? 0.5 : view.blocks(2);
        double newW = handleX != 0 ? Math.max(min, (mx - ax) * handleX) : 2 * f[3];
        double newH = handleZ != 0 ? Math.max(min, (mz - az) * handleZ) : 2 * f[4];
        double sx = f[3] == 0 ? 1 : newW / (2 * f[3]), sz = f[4] == 0 ? 1 : newH / (2 * f[4]);
        if (keepRatio && handleX != 0 && handleZ != 0) sx = sz = Math.max(sx, sz);
        double fsx = sx, fsz = sz, fax = handleX == 0 ? 0 : ax, faz = handleZ == 0 ? 0 : az;
        transform(e -> scaleElement(e, f, fax, faz, fsx, fsz));
    }

    /** Maps a world point through the frame scale about the anchor. */
    private static double[] mapPoint(double[] f, double ax, double az, double sx, double sz, double wx, double wz) {
        double cos = Math.cos(f[2]), sin = Math.sin(f[2]);
        double dx = wx - f[0], dz = wz - f[1];
        double lx = dx * cos + dz * sin, lz = -dx * sin + dz * cos;
        lx = ax + (lx - ax) * sx;
        lz = az + (lz - az) * sz;
        return new double[]{f[0] + lx * cos - lz * sin, f[1] + lx * sin + lz * cos};
    }

    private static void scaleElement(Element e, double[] f, double ax, double az, double sx, double sz) {
        if (e.linear()) {
            float[] w = worldPoints(e);
            e.angle = 0;
            double[] p0 = mapPoint(f, ax, az, sx, sz, w[0], w[1]);
            e.x = p0[0];
            e.z = p0[1];
            for (int i = 0; i < w.length; i += 2) {
                double[] p = mapPoint(f, ax, az, sx, sz, w[i], w[i + 1]);
                e.pts[i] = (float) (p[0] - e.x);
                e.pts[i + 1] = (float) (p[1] - e.z);
            }
            e.fitPoints();
            return;
        }
        double[] c = e.center();
        double[] nc = mapPoint(f, ax, az, sx, sz, c[0], c[1]);
        if (e.type == Element.Type.TEXT) {
            e.fontSize = (float) Math.max(0.5, e.fontSize * sz);
            fitText(e);
        } else {
            e.w *= sx;
            e.h *= sz;
        }
        e.x = nc[0] - e.w / 2;
        e.z = nc[1] - e.h / 2;
    }

    private static void rotate(boolean snap) {
        double[] f = frame;
        double a0 = Math.atan2(startZ - f[1], startX - f[0]), a1 = Math.atan2(curZ - f[1], curX - f[0]);
        double d = a1 - a0;
        if (snap) {
            double target = Math.round((f[2] + d) / Math.toRadians(15)) * Math.toRadians(15);
            d = target - f[2];
        }
        double cos = Math.cos(d), sin = Math.sin(d), fd = d;
        transform(e -> {
            double[] c = e.center();
            double dx = c[0] - f[0], dz = c[1] - f[1];
            e.x += f[0] + dx * cos - dz * sin - c[0];
            e.z += f[1] + dx * sin + dz * cos - c[1];
            e.angle = (float) ((e.angle + fd) % (2 * Math.PI));
        });
    }

    private static void movePoint() {
        Element start = snapshot.values().iterator().next();
        Element cur = live(start.id);
        if (cur == null) return;
        Element e = bake(start.copy());
        e.version = cur.version;
        if (pointIndex >= e.pointCount()) return;
        e.pts[pointIndex * 2] = (float) (curX - e.x);
        e.pts[pointIndex * 2 + 1] = (float) (curZ - e.z);
        if (pointIndex == 0) {
            e.startBind = null;
            e.startFixed = null;
        }
        if (pointIndex == e.pointCount() - 1) {
            e.endBind = null;
            e.endFixed = null;
        }
        e.fitPoints();
        ClientStore.local(e, false);
    }

    // ---- text ----

    private static void startTextEdit(MapView v, Element target, double wx, double wz) {
        commitText();
        if (target != null && !canEdit(target)) return;
        if (target != null && !beginLease(Set.of(target.id), Set.of())) return;
        if (target != null) {
            editingBefore = target.copy();
            editing = target.copy();
        } else {
            editingBefore = null;
            editing = create(Element.Type.TEXT, wx, wz - FONTS[style.font] * zoomFactor(v) / 2, v);
            editing.stroke = style.stroke;
            fitText(editing);
            ClientStore.local(editing, false);
        }
        selection.clear();
        selection.add(editing.id);
    }

    /** TEXT boxes are sized from the font: width of the longest line, 10/9 line height. */
    static void fitText(Element e) {
        if (e.type != Element.Type.TEXT) return;
        Font font = Minecraft.getInstance().font;
        String[] lines = e.text.split("\n", -1);
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        double s = e.fontSize / 9.0;
        e.w = Math.max(w, 4) * s;
        e.h = (lines.length * 10 - 1) * s;
    }

    private static void typeText(String add, boolean backspace) {
        Element cur = live(editing.id);
        Element e = (cur != null ? cur : editing).copy();
        if (backspace) {
            if (!e.text.isEmpty()) e.text = e.text.substring(0, e.text.offsetByCodePoints(e.text.length(), -1));
        } else {
            ServerInfoPayload info = ClientStore.info();
            int max = info == null ? 500 : info.maxText();
            e.text = (e.text + add).substring(0, Math.min(max, e.text.length() + add.length()));
        }
        fitText(e);
        ClientStore.local(e, false);
        editing = e;
    }

    /** Ends text editing: empty TEXT elements are removed, everything else is sent with one undo step. */
    public static void commitText() {
        if (editing == null) return;
        Element e = live(editing.id);
        Element before = editingBefore;
        editing = null;
        editingBefore = null;
        if (e == null) {
            releaseLease();
            return;
        }
        if (e.type == Element.Type.TEXT && e.text.isBlank()) {
            selection.remove(e.id);
            if (before == null) {
                ClientStore.forget(e.id);
                return;
            }
            e = e.copy();
            e.deleted = true;
            ClientStore.local(e, true);
            record(List.of(new Change(before, e.copy())));
            releaseLease();
            return;
        }
        if (before == null || !before.sameContent(e)) {
            ClientStore.send(e.id);
            record(List.of(new Change(before, e.copy())));
        }
        releaseLease();
    }

    // ---- keyboard ----

    public static boolean keyPressed(MapView v, int key, int scancode, int mods) {
        view = v;
        if (EditorUi.keyPressed(key)) return true;
        if (MapSketchKeys.TOGGLE.matches(key, scancode) && editing == null) {
            toggleDrawMode();
            return true;
        }
        if (!drawMode) return false;
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0 || (mods & GLFW.GLFW_MOD_SUPER) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (editing != null) {
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> commitText();
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (shift || ctrl) commitText();
                    else typeText("\n", false);
                }
                case GLFW.GLFW_KEY_BACKSPACE -> typeText("", true);
                case GLFW.GLFW_KEY_V -> {
                    if (ctrl) typeText(Minecraft.getInstance().keyboardHandler.getClipboard(), false);
                }
                default -> {
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            spaceDown = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (draft != null) {
                if (draft.type == Element.Type.LINE || draft.type == Element.Type.ARROW) finishPolyline();
                else draft = null;
                return true;
            }
            if (!selection.isEmpty()) {
                selection.clear();
                return true;
            }
            return false;
        }
        if (key == GLFW.GLFW_KEY_ENTER && draft != null) {
            finishPolyline();
            return true;
        }
        if (ctrl) return ctrlKey(v, key, shift);
        switch (key) {
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                deleteSelection();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                double step = v.blocks(shift ? 20 : 4);
                double dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
                double dz = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
                editSelection(e -> {
                    e.x += dx;
                    e.z += dz;
                });
                return !selection.isEmpty();
            }
            default -> {
            }
        }
        String name = GLFW.glfwGetKeyName(key, scancode);
        if (name == null || name.length() != 1) return false;
        char ch = Character.toUpperCase(name.charAt(0));
        for (Tool t : Tool.values())
            if (t.key == ch || t.digit != '\0' && t.digit == ch) {
                setTool(t);
                return true;
            }
        return false;
    }

    private static boolean ctrlKey(MapView v, int key, boolean shift) {
        switch (key) {
            case GLFW.GLFW_KEY_Z -> undo(shift);
            case GLFW.GLFW_KEY_Y -> undo(true);
            case GLFW.GLFW_KEY_A -> {
                selection.clear();
                for (Element e : ClientStore.sorted(v.dim())) selection.add(e.id);
                tool = Tool.SELECT;
            }
            case GLFW.GLFW_KEY_C -> copy();
            case GLFW.GLFW_KEY_X -> {
                copy();
                deleteSelection();
            }
            case GLFW.GLFW_KEY_V -> paste(v, clipboard, v.worldX(mouseGX), v.worldZ(mouseGY));
            case GLFW.GLFW_KEY_D -> duplicate();
            case GLFW.GLFW_KEY_G -> {
                if (shift) ungroup();
                else group();
            }
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> reorder(shift ? 2 : 1);
            case GLFW.GLFW_KEY_LEFT_BRACKET -> reorder(shift ? -2 : -1);
            default -> {
                return false;
            }
        }
        return true;
    }

    public static boolean keyReleased(int key) {
        if (key == GLFW.GLFW_KEY_SPACE && spaceDown) {
            spaceDown = false;
            return drawMode;
        }
        return false;
    }

    public static boolean charTyped(char c) {
        if (EditorUi.charTyped(c)) return true;
        if (editing == null) return false;
        if (c >= ' ' && c != 127) typeText(String.valueOf(c), false);
        return true;
    }

    // ---- clipboard, grouping, z-order ----

    private static List<Element> selected() {
        List<Element> out = new ArrayList<>();
        for (UUID id : selection) {
            Element e = live(id);
            if (e != null) out.add(e.copy());
        }
        out.sort(ClientStore.Z_ORDER);
        return out;
    }

    private static void copy() {
        if (!selection.isEmpty()) clipboard = selected();
    }

    public static void duplicate() {
        if (view == null || selection.isEmpty()) return;
        List<Element> src = selected();
        double[] f = frame();
        double off = view.blocks(10);
        paste(view, src, f[0] + off, f[1] + off);
    }

    /** Pastes copies with new ids centred on (wx, wz); groups and bindings inside the set are remapped. */
    private static void paste(MapView v, List<Element> src, double wx, double wz) {
        if (src.isEmpty()) return;
        Map<UUID, UUID> ids = new HashMap<>();
        for (Element e : src) ids.put(e.id, UUID.randomUUID());
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Element e : src) {
            double[] b = e.worldBounds();
            minX = Math.min(minX, b[0]);
            minZ = Math.min(minZ, b[1]);
            maxX = Math.max(maxX, b[2]);
            maxZ = Math.max(maxZ, b[3]);
        }
        double dx = wx - (minX + maxX) / 2, dz = wz - (minZ + maxZ) / 2;
        List<Element> all = ClientStore.sorted(v.dim());
        String top = all.isEmpty() ? null : all.get(all.size() - 1).order;
        List<Element> out = new ArrayList<>();
        for (Element s : src) {
            Element e = s.copy();
            e.id = ids.get(s.id);
            e.dim = v.dim();
            e.x += dx;
            e.z += dz;
            for (int i = 0; i < e.groups.length; i++) e.groups[i] = ids.computeIfAbsent(e.groups[i], k -> UUID.randomUUID());
            e.startBind = s.startBind == null ? null : ids.get(s.startBind);
            e.endBind = s.endBind == null ? null : ids.get(s.endBind);
            if (e.startBind == null) e.startFixed = null;
            if (e.endBind == null) e.endFixed = null;
            e.order = top = FracIndex.between(top, null);
            e.owner = me();
            e.version = 0;
            e.locked = false;
            out.add(e);
        }
        commit(out);
        selection.clear();
        out.forEach(e -> selection.add(e.id));
        tool = Tool.SELECT;
    }

    public static void group() {
        if (selection.size() < 2) return;
        UUID g = UUID.randomUUID();
        editSelection(e -> {
            UUID[] n = java.util.Arrays.copyOf(e.groups, e.groups.length + 1);
            n[e.groups.length] = g;
            e.groups = n;
        });
    }

    public static void ungroup() {
        editSelection(e -> {
            if (e.groups.length > 0) e.groups = java.util.Arrays.copyOf(e.groups, e.groups.length - 1);
        });
    }

    /**
     * Z-order of the selection.
     *
     * @param how 2 = to front, 1 = forward one, -1 = backward one, -2 = to back
     */
    public static void reorder(int how) {
        if (view == null || selection.isEmpty()) return;
        List<Element> all = new ArrayList<>(ClientStore.sorted(view.dim()));
        List<Element> sel = selected();
        Set<UUID> ids = new HashSet<>();
        sel.forEach(e -> ids.add(e.id));
        List<Element> rest = new ArrayList<>();
        for (Element e : all) if (!ids.contains(e.id)) rest.add(e);
        Map<UUID, String> orders = new HashMap<>();
        if (how == 2 || how == -2) {
            String lo = how == 2 ? (rest.isEmpty() ? null : rest.get(rest.size() - 1).order) : null;
            String hi = how == 2 ? null : (rest.isEmpty() ? null : rest.get(0).order);
            for (Element e : sel) orders.put(e.id, lo = FracIndex.between(lo, hi));
        } else {
            // One step: past the nearest unselected element above (or below) the selection's top (or bottom).
            int edge = -1;
            for (int i = 0; i < all.size(); i++) if (ids.contains(all.get(i).id)) edge = how > 0 ? i : (edge < 0 ? i : edge);
            int k = edge;
            do k += how; while (k >= 0 && k < all.size() && ids.contains(all.get(k).id));
            if (k < 0 || k >= all.size()) return;
            String lo, hi;
            if (how > 0) {
                lo = all.get(k).order;
                hi = k + 1 < all.size() ? all.get(k + 1).order : null;
            } else {
                hi = all.get(k).order;
                lo = k - 1 >= 0 ? all.get(k - 1).order : null;
            }
            if (lo != null && hi != null && lo.compareTo(hi) >= 0) hi = null;
            for (Element e : sel) orders.put(e.id, lo = FracIndex.between(lo, hi));
        }
        editSelection(e -> e.order = orders.getOrDefault(e.id, e.order));
    }

    /** Copies the first selected element's style into the panel. */
    private static void syncStyleFromSelection() {
        Element e = selection.isEmpty() ? null : live(selection.iterator().next());
        if (e == null || view == null) return;
        style.stroke = e.stroke;
        style.fill = e.fill;
        style.fillStyle = e.fillStyle;
        style.strokeStyle = e.strokeStyle;
        style.roughness = e.roughness;
        style.round = e.round;
        style.opacity = e.opacity;
        style.align = e.align;
        style.visibility = e.visibility;
        style.locked = e.locked;
        if (e.type == Element.Type.ARROW) {
            style.arrowStart = e.arrowStart;
            style.arrowEnd = e.arrowEnd;
        }
        double z = zoomFactor(view);
        style.width = nearest(WIDTHS, e.strokeWidth / z);
        style.font = nearest(FONTS, e.fontSize / z);
    }

    private static int nearest(float[] presets, double v) {
        int best = 0;
        for (int i = 1; i < presets.length; i++)
            if (Math.abs(Math.log(presets[i] / v)) < Math.abs(Math.log(presets[best] / v))) best = i;
        return best;
    }

    /** Stroke width preset in blocks at the current zoom. */
    public static float widthPreset(int i) {
        return (float) Math.max(com.volcanicmg.mapsketch.model.Rules.MIN_STROKE, WIDTHS[i] * (view == null ? 1 : zoomFactor(view)));
    }

    public static float fontPreset(int i) {
        return (float) Math.max(0.5, FONTS[i] * (view == null ? 1 : zoomFactor(view)));
    }

    // ---- per-frame ----

    /** Remembers the frame's view and mouse; the world map hooks call this every render. */
    public static void onFrame(MapView v, double gx, double gy) {
        view = v;
        mouseGX = gx;
        mouseGY = gy;
        if (draft != null && drag == Drag.NONE && (draft.type == Element.Type.LINE || draft.type == Element.Type.ARROW)) {
            setLastPoint(draft, v.worldX(gx), v.worldZ(gy), Screen.hasShiftDown());
            int n = draft.pointCount();
            previewBind(draft.id, draft.x + draft.pts[(n - 1) * 2], draft.z + draft.pts[(n - 1) * 2 + 1]);
        } else if (drag != Drag.LINE && drag != Drag.POINT) bindPreview = null;
        long now = System.currentTimeMillis();
        laser.removeIf(p -> now - p[2] > 1000);
    }

    /** Ids the map renderer should skip because the editor draws them itself, and ids to fade. */
    public static Set<UUID> faded() {
        return erasing;
    }

    public static Element draft() {
        return draft;
    }

    public static Element editing() {
        return editing;
    }

    public static List<double[]> laser() {
        return laser;
    }

    public static boolean boxSelecting() {
        return drag == Drag.BOX;
    }

    public static double[] boxWorld() {
        return new double[]{Math.min(startX, curX), Math.min(startZ, curZ), Math.max(startX, curX), Math.max(startZ, curZ)};
    }

    /**
     * At 20 Hz while drawing on an open map: renews the gesture lease every 2 s and sends live state (cursor, laser,
     * ghosts of the shape being drawn, of grabbed elements and of text being typed). Freehand strokes stream only
     * new points, with a full copy every second so late joiners catch up. One "inactive" when drawing stops.
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (!leased.isEmpty() && mapOpen && now - lastRenewMs > 2000) {
            lastRenewMs = now;
            ClientStore.lease(0, LeasePayload.Action.RENEW, List.of(), List.of());
        }
        boolean active = drawMode && mapOpen && view != null && ClientStore.connected();
        if (!active) {
            sendLiveInactive();
            return;
        }
        if (now - lastLiveMs < LIVE_MS) return;
        lastLiveMs = now;
        double cx = view.worldX(mouseGX), cz = view.worldZ(mouseGY);
        List<Element> ghosts = new ArrayList<>();
        List<Integer> append = new ArrayList<>();
        if (draft != null) {
            Element g = draft.copy();
            int n = g.pointCount();
            if (g.type == Element.Type.FREEDRAW && g.id.equals(streamId) && now - streamKeyMs < 1000 && streamSent <= n) {
                g.pts = java.util.Arrays.copyOfRange(g.pts, streamSent * 2, n * 2);
                append.add(streamSent);
            } else {
                append.add(-1);
                streamKeyMs = now;
            }
            streamId = g.id;
            streamSent = n;
            ghosts.add(g);
        }
        if (isGrab()) {
            for (UUID id : snapshot.keySet()) addGhost(ghosts, append, id);
            for (UUID id : followers.keySet()) addGhost(ghosts, append, id);
        }
        if (editing != null) addGhost(ghosts, append, editing.id);
        int from = Math.max(0, laser.size() - Live.MAX_LASER);
        float[] trail = new float[(laser.size() - from) * 2];
        for (int i = from; i < laser.size(); i++) {
            trail[(i - from) * 2] = (float) (laser.get(i)[0] - cx);
            trail[(i - from) * 2 + 1] = (float) (laser.get(i)[1] - cz);
        }
        ServerInfoPayload info = ClientStore.info();
        Element.Visibility vis = style.visibility != null ? style.visibility
                : info != null ? info.defaultVisibility() : Element.Visibility.PUBLIC;
        ClientStore.sendLive(new Live(view.dim(), cx, cz, true, vis, ghosts,
                append.stream().mapToInt(Integer::intValue).toArray(), trail));
        liveActive = true;
    }

    private static void addGhost(List<Element> ghosts, List<Integer> append, UUID id) {
        Element e = live(id);
        if (e == null || ghosts.size() >= Live.MAX_GHOSTS) return;
        ghosts.add(e.copy());
        append.add(-1);
    }

    private static void sendLiveInactive() {
        if (!liveActive) return;
        liveActive = false;
        streamId = null;
        ClientStore.sendLive(Live.inactive());
    }

    static List<Element> selectedLive() {
        List<Element> out = new ArrayList<>();
        for (UUID id : selection) {
            Element e = live(id);
            if (e != null) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }
}
