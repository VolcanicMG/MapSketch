package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.ElementCodec;
import com.volcanicmg.mapsketch.model.Rules;
import com.volcanicmg.mapsketch.net.AckPayload;
import com.volcanicmg.mapsketch.net.EditPayload;
import com.volcanicmg.mapsketch.net.ElementsPayload;
import com.volcanicmg.mapsketch.net.LeasePayload;
import com.volcanicmg.mapsketch.net.LeaseReplyPayload;
import com.volcanicmg.mapsketch.net.LeasesPayload;
import com.volcanicmg.mapsketch.net.LivePayload;
import com.volcanicmg.mapsketch.net.RemovePayload;
import com.volcanicmg.mapsketch.net.ResyncPayload;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import com.volcanicmg.mapsketch.sync.Live;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client copy of the server's elements plus everything live: pending ops, other players' leases and live state.
 * Main thread only (payload handlers run there). Local edits apply at once (optimistic); the server acks or
 * rejects each op and resyncs rejected elements with {@code force}.
 */
public final class ClientStore {
    /** Z-order: fractional index, ties broken by id so every client agrees. */
    public static final Comparator<Element> Z_ORDER = Comparator.<Element, String>comparing(e -> e.order).thenComparing(e -> e.id);
    private static final long FLUSH_MS = 50, LIVE_TIMEOUT_MS = 3000;

    private static final Map<UUID, Element> elements = new HashMap<>();
    private static final Map<String, List<Element>> sorted = new HashMap<>();
    private static final Set<UUID> outgoing = new LinkedHashSet<>();
    private static final Map<Integer, Set<UUID>> pendingOps = new HashMap<>();
    // Ops in flight per element: while any, a same-version server state does not replace our predicted one.
    private static final Map<UUID, Integer> inFlight = new HashMap<>();
    private static final Map<UUID, Remote> remotes = new LinkedHashMap<>();
    private static final Map<UUID, Holder> leases = new HashMap<>();
    private static ServerInfoPayload info;
    private static boolean connected, resyncAsked;
    private static long lastFlush, lastSeq;
    private static int nextOp;

    /** Another player's live state; ghosts by id, freehand ones grown by appends. */
    public static final class Remote {
        public final UUID player;
        public String name;
        public Live live;
        public final Map<UUID, Element> ghosts = new LinkedHashMap<>();
        long receivedAt;

        Remote(UUID player) {
            this.player = player;
        }

        /** @return true when the cursor should be drawn (the sender's visibility lets you see it) */
        public boolean showCursor() {
            return live.visibility() != Element.Visibility.PRIVATE;
        }
    }

    /** Who is editing which elements. */
    public record Holder(UUID player, String name, Set<UUID> ids) {
    }

    private ClientStore() {
    }

    public static boolean connected() {
        return connected;
    }

    public static ServerInfoPayload info() {
        return info;
    }

    public static void onLogin(boolean hasChannel) {
        clear();
        connected = hasChannel;
    }

    public static void clear() {
        elements.clear();
        sorted.clear();
        outgoing.clear();
        pendingOps.clear();
        inFlight.clear();
        remotes.clear();
        leases.clear();
        info = null;
        connected = false;
        resyncAsked = false;
        lastSeq = 0;
    }

    public static void onInfo(ServerInfoPayload p) {
        info = p;
        connected = true;
    }

    /** In-order check: a gap (lost or reordered message) asks the server for a full resync. */
    private static void sequence(long seq, boolean reset) {
        if (reset) resyncAsked = false;
        else if (seq != lastSeq + 1 && !resyncAsked) {
            resyncAsked = true;
            send(ResyncPayload.INSTANCE);
        }
        lastSeq = seq;
    }

    public static void onElements(ElementsPayload p) {
        sequence(p.seq(), p.reset());
        if (p.reset()) {
            elements.clear();
            sorted.clear();
        }
        List<UUID> rolled = new ArrayList<>();
        for (Element received : p.elements()) {
            // On an integrated server the payload holds the server's own instances; never mutate them.
            Element e = received.copy();
            Element mine = elements.get(e.id);
            if (!p.force() && !Rules.replaces(e, mine, pending(e.id))) continue;
            if (p.force()) {
                outgoing.remove(e.id);
                rolled.add(e.id);
            }
            // A server state is its own base: local edits made from it carry its version and nonce.
            e.baseNonce = e.nonce;
            if (e.deleted) remove(e.id);
            else put(e);
        }
        Editor.onRemoteChange(rolled);
    }

    public static void onRemove(RemovePayload p) {
        sequence(p.seq(), false);
        for (UUID id : p.ids()) {
            remove(id);
            outgoing.remove(id);
        }
        Editor.onRemoteChange(p.ids());
    }

    public static void onAck(AckPayload p) {
        Set<UUID> ids = pendingOps.remove(p.opId());
        if (ids != null) for (UUID id : ids) inFlight.computeIfPresent(id, (k, n) -> n <= 1 ? null : n - 1);
        // The server assigned exactly the version we predicted; only a lagging local copy needs it.
        for (int i = 0; i < p.accepted().size() && i < p.versions().length; i++) {
            Element e = elements.get(p.accepted().get(i));
            if (e != null && e.version < p.versions()[i]) e.version = p.versions()[i];
        }
        if (p.rejected().isEmpty()) return;
        toast(reasonText(p.reason()));
        Editor.onRejected(p.rejected());
    }

    public static void onLeaseReply(LeaseReplyPayload p) {
        Editor.onLeaseReply(p.requestId(), p.granted(), p.ids(), p.reason());
    }

    public static void onLeases(LeasesPayload p) {
        Holder h = leases.get(p.holder());
        if (p.reset() || h == null) h = new Holder(p.holder(), p.name(), new LinkedHashSet<>());
        if (p.reset()) h.ids().clear();
        h.ids().addAll(p.added());
        p.removed().forEach(h.ids()::remove);
        if (h.ids().isEmpty()) leases.remove(p.holder());
        else leases.put(p.holder(), h);
    }

    public static void onLive(LivePayload p) {
        if (p.from() == null) return;
        if (!p.live().active()) {
            remotes.remove(p.from());
            return;
        }
        Remote r = remotes.computeIfAbsent(p.from(), Remote::new);
        r.name = p.name();
        r.live = p.live();
        r.receivedAt = System.currentTimeMillis();
        Map<UUID, Element> next = new LinkedHashMap<>();
        for (int i = 0; i < p.live().ghosts().size(); i++) {
            Element g = p.live().ghosts().get(i);
            int from = p.live().appendFrom()[i];
            if (from >= 0) {
                // Freehand streaming: only new points were sent; grow the previous ghost or wait for a full copy.
                Element prev = r.ghosts.get(g.id);
                if (prev == null || prev.pointCount() != from) continue;
                float[] pts = java.util.Arrays.copyOf(prev.pts, prev.pts.length + g.pts.length);
                System.arraycopy(g.pts, 0, pts, prev.pts.length, g.pts.length);
                g.pts = pts;
                g.fitPoints();
            }
            next.put(g.id, g);
        }
        r.ghosts.clear();
        r.ghosts.putAll(next);
    }

    /** @return live remote players; drops ones silent for 3 s */
    public static Iterable<Remote> remotes() {
        long now = System.currentTimeMillis();
        remotes.values().removeIf(r -> now - r.receivedAt > LIVE_TIMEOUT_MS);
        return remotes.values();
    }

    /** @return who else holds the element, or null */
    public static Holder heldBy(UUID id) {
        for (Holder h : leases.values()) if (h.ids().contains(id)) return h;
        return null;
    }

    public static Iterable<Holder> holders() {
        return leases.values();
    }

    public static Element get(UUID id) {
        return elements.get(id);
    }

    /** @return live elements of a dimension in z-order (bottom first); do not modify */
    public static List<Element> sorted(String dim) {
        return sorted.computeIfAbsent(dim, d -> {
            List<Element> out = new ArrayList<>();
            for (Element e : elements.values()) if (e.dim.equals(d) && !e.deleted) out.add(e);
            out.sort(Z_ORDER);
            return out;
        });
    }

    private static boolean pending(UUID id) {
        return outgoing.contains(id) || inFlight.containsKey(id);
    }

    /**
     * Applies a local edit: a fresh state token (nonce), made from the stored state's version and token (so the
     * server can tell exactly what it was based on), and stores it. Tombstones of our own deletes stay, so undo can
     * restore from them.
     *
     * @param send queue it for the server (false for in-progress local-only states)
     */
    public static void local(Element e, boolean send) {
        Element old = elements.get(e.id);
        e.version = old == null ? 0 : old.version;
        e.baseNonce = old == null ? 0 : old.baseNonce;
        e.nonce = ThreadLocalRandom.current().nextInt();
        if (e.deleted) {
            remove(e.id);
            elements.put(e.id, e);
        } else put(e);
        if (send) outgoing.add(e.id);
    }

    /** Puts an older state back without sending (rolling back a gesture the server refused). */
    public static void restore(Element e) {
        put(e);
        outgoing.remove(e.id);
    }

    /** Queues the current state of an element (already stored locally) for the server. */
    public static void send(UUID id) {
        if (elements.containsKey(id)) outgoing.add(id);
    }

    /** Drops a local-only element the server never heard of. */
    public static void forget(UUID id) {
        remove(id);
        outgoing.remove(id);
    }

    private static void put(Element e) {
        Element old = elements.put(e.id, e);
        sorted.remove(e.dim);
        if (old != null && !old.dim.equals(e.dim)) sorted.remove(old.dim);
    }

    private static void remove(UUID id) {
        Element old = elements.remove(id);
        if (old != null) sorted.remove(old.dim);
        SketchRenderer.evict(id);
    }

    public static void tick() {
        if (System.currentTimeMillis() - lastFlush >= FLUSH_MS) flush();
    }

    /** Sends queued edits now, one op per payload under the serverbound size limit. */
    public static void flush() {
        lastFlush = System.currentTimeMillis();
        if (outgoing.isEmpty()) return;
        if (!connected || Minecraft.getInstance().getConnection() == null) {
            outgoing.clear();
            return;
        }
        List<Element> batch = new ArrayList<>();
        int bytes = 0;
        for (UUID id : outgoing) {
            Element e = elements.get(id);
            if (e == null) continue;
            int size = ElementCodec.size(e);
            if (!batch.isEmpty() && (bytes + size > EditPayload.MAX_BYTES || batch.size() >= EditPayload.MAX_ELEMENTS)) {
                sendOp(batch);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(e);
            bytes += size;
        }
        if (!batch.isEmpty()) sendOp(batch);
        outgoing.clear();
    }

    /**
     * Sends copies (version/baseNonce = the base state), then predicts: the server will store version + 1 with
     * our token, so follow-up edits are based on that and are not refused while the ack is in flight.
     */
    private static void sendOp(List<Element> batch) {
        int op = ++nextOp;
        Set<UUID> ids = new LinkedHashSet<>();
        List<Element> wire = new ArrayList<>();
        for (Element e : batch) {
            ids.add(e.id);
            wire.add(e.copy());
            inFlight.merge(e.id, 1, Integer::sum);
            Element predicted = e.copy();
            predicted.version = e.version + 1;
            predicted.baseNonce = e.nonce;
            if (predicted.deleted) elements.put(predicted.id, predicted);
            else put(predicted);
        }
        pendingOps.put(op, ids);
        PacketDistributor.sendToServer(new EditPayload(op, wire));
    }

    public static void lease(int requestId, LeasePayload.Action action, List<UUID> ids, List<UUID> optional) {
        send(new LeasePayload(requestId, action, ids, optional));
    }

    public static void sendLive(Live live) {
        send(new LivePayload(null, "", live));
    }

    private static void send(CustomPacketPayload p) {
        if (connected && Minecraft.getInstance().getConnection() != null) PacketDistributor.sendToServer(p);
    }

    /** Readable rejection reason; {@code held:<name>} becomes "being edited by name". */
    public static Component reasonText(String reason) {
        if (reason == null || reason.isEmpty()) return Component.translatable("mapsketch.rejected", "?");
        if (reason.startsWith("held:")) return Component.translatable("mapsketch.held", reason.substring(5));
        if (reason.equals("deleted")) return Component.translatable("mapsketch.deleted_meanwhile");
        if (reason.startsWith("taken over")) return Component.translatable("mapsketch.taken_over", reason);
        if (reason.equals("expired") || reason.equals("permission lost") || reason.equals("dimension changed") || reason.equals("died"))
            return Component.translatable("mapsketch.lease_ended", reason);
        return Component.translatable("mapsketch.rejected", reason);
    }

    public static void toast(Component c) {
        EditorUi.toast(c);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(c, true);
    }
}
