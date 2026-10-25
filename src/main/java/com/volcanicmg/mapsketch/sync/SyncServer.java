package com.volcanicmg.mapsketch.sync;

import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.ElementCodec;
import com.volcanicmg.mapsketch.model.Rules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * The server's sync state machine, free of Minecraft so the two-client simulation can drive it: stores and
 * validates ops, grants edit leases, tracks what each peer knows, numbers every state message per peer, relays
 * live state. Single-threaded (the server thread).
 *
 * <p>Ops: an {@code edit} carries an op id and is answered with {@code ack}; rejected elements are first resynced
 * with their authoritative state. Leases: a gesture asks for its element ids (expanded to the group members the
 * requester may edit); first request wins, all or nothing; held elements reject everyone else's edits, deletes
 * included, except the owner's and operators', which take the lease over.
 */
public final class SyncServer {
    public static final long LEASE_MS = 5000, LEASE_MAX_MS = 60_000, TOMBSTONE_MS = 60 * 60 * 1000L, LIVE_MIN_MS = 40,
            RESYNC_MIN_MS = 2000;
    /** Most elements one player may hold at once (also the lease payload's id cap). */
    public static final int MAX_LEASES = 512;
    /** Re-acquire cooldown after losing a lease to the lifetime cap (when contested) or to a take-over. */
    public static final long COOLDOWN_MS = 3000;
    private static final int CHUNK_BYTES = 512 * 1024, RESYNC_BYTES_PER_OP = 64 * 1024, LEASE_REQUESTS_PER_S = 20;

    /** Server config snapshot. */
    public record Settings(Rules.Limits limits, boolean teamCanEdit, int maxPerPlayer, int maxPerDimension,
                           long editBytesPerSecond, long liveBytesPerSecond) {
    }

    /** What the state machine needs from the world. */
    public interface Env {
        boolean isOp(UUID player);

        boolean dimensionExists(String dim);

        /** @param id item registry id, e.g. {@code minecraft:diamond} */
        boolean itemExists(String id);

        /** (owner, viewer) share a team (or allied); false without FTB Teams. */
        boolean sameTeam(UUID owner, UUID viewer);

        Settings settings();

        long now();
    }

    /** {@code granted}: first grant, so renewals cannot keep it past {@link #LEASE_MAX_MS}; {@code contested}: someone was refused it. */
    private static final class Lease {
        final UUID holder;
        final long granted;
        long expires;
        boolean contested;

        Lease(UUID holder, long expires, long granted) {
            this.holder = holder;
            this.expires = expires;
            this.granted = granted;
        }
    }

    private static final class Peer {
        final String name;
        final Set<UUID> known = new HashSet<>();
        long seq;
        TokenBucket edits, live, leaseRequests;
        long lastLive, lastResync = Long.MIN_VALUE / 2;
        boolean resyncPending;
        // Peers currently showing this player's live state, so "inactive" is sent once, not every frame.
        final Set<UUID> shownTo = new HashSet<>();
        // Lease ids told to this peer as held, per holder, so every one of them gets its removal.
        final Map<UUID, Set<UUID>> leaseSent = new HashMap<>();

        Peer(String name) {
            this.name = name;
        }
    }

    private final ElementStore store;
    private final Env env;
    private final Outbox out;
    private final BiPredicate<UUID, UUID> team;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final Map<UUID, Set<UUID>> heldBy = new HashMap<>();
    private final Map<UUID, UUID> deletedBy = new HashMap<>();
    // Per deleter, their tombstones oldest first: one player's delete loop only prunes their own.
    private final Map<UUID, java.util.ArrayDeque<UUID>> deletions = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> cooldown = new HashMap<>();

    public SyncServer(ElementStore store, Env env, Outbox out) {
        this.store = store;
        this.env = env;
        this.out = out;
        this.team = env::sameTeam;
    }

    public ElementStore store() {
        return store;
    }

    // ---- peers ----

    /** A player with the mod joined (or reconnected): full sync, plus everyone's current leases. */
    public void join(UUID p, String name) {
        if (peers.containsKey(p)) leave(p);
        peers.put(p, new Peer(name));
        fullSync(p);
        sendAllLeasesTo(p);
    }

    /** Disconnect: leases released, live state dropped for everyone. */
    public void leave(UUID p) {
        releaseAll(p, null);
        Peer peer = peers.remove(p);
        if (peer == null) return;
        for (UUID q : peer.shownTo) if (peers.containsKey(q)) out.live(q, p, peer.name, Live.inactive());
        for (Peer other : peers.values()) other.shownTo.remove(p);
    }

    /** Dimension change, death: drop leases and live state, keep the peer. */
    public void interrupt(UUID p, String reason) {
        releaseAll(p, reason);
        dropLive(p);
    }

    private void dropLive(UUID p) {
        Peer peer = peers.get(p);
        if (peer == null) return;
        for (UUID q : peer.shownTo) if (peers.containsKey(q)) out.live(q, p, peer.name, Live.inactive());
        peer.shownTo.clear();
    }

    /** Replaces what the peer knows with every element it may see, chunked. */
    public void fullSync(UUID p) {
        Peer peer = peers.get(p);
        if (peer == null) return;
        peer.resyncPending = false;
        peer.lastResync = env.now();
        peer.known.clear();
        List<Element> chunk = new ArrayList<>();
        int bytes = 0;
        boolean first = true;
        for (Element e : store.all()) {
            if (e.deleted || !canSee(p, e)) continue;
            peer.known.add(e.id);
            chunk.add(e);
            bytes += ElementCodec.size(e);
            if (bytes >= CHUNK_BYTES) {
                out.elements(p, ++peer.seq, first, false, chunk);
                first = false;
                chunk = new ArrayList<>();
                bytes = 0;
            }
        }
        if (first || !chunk.isEmpty()) out.elements(p, ++peer.seq, first, false, chunk);
    }

    /**
     * A client's resync request (sequence gap): served at most once per {@link #RESYNC_MIN_MS}; extra requests
     * coalesce into one pending resync that {@link #tick} sends when allowed.
     */
    public void requestResync(UUID p) {
        Peer peer = peers.get(p);
        if (peer == null) return;
        if (env.now() - peer.lastResync >= RESYNC_MIN_MS) fullSync(p);
        else peer.resyncPending = true;
    }

    /** Team or config change: the given players (all when null) resync; leases whose holder lost access go. */
    public void resync(Set<UUID> players) {
        revokeLost();
        for (UUID p : new ArrayList<>(peers.keySet())) {
            if (players != null && !players.contains(p)) continue;
            fullSync(p);
            sendAllLeasesTo(p);
        }
    }

    public void resyncAll() {
        resync(null);
    }

    // ---- permissions ----

    private boolean canSee(UUID p, Element e) {
        return Rules.canSee(p, e, team);
    }

    private String canEdit(UUID p, Element existing, Element incoming) {
        if (existing != null && !existing.deleted && !canSee(p, existing) && !env.isOp(p)) return "not visible";
        return Rules.canEdit(p, env.isOp(p), env.settings().teamCanEdit(), existing, incoming, team);
    }

    /** Owners and operators may take over someone else's lease. */
    private boolean mayBreak(UUID p, Element e) {
        return env.isOp(p) || p.equals(e.owner);
    }

    private String nameOf(UUID p) {
        Peer peer = peers.get(p);
        return peer == null ? "?" : peer.name;
    }

    // ---- leases ----

    /**
     * Grants all of {@code ids} (plus the group members {@code p} may edit) to {@code p}, or none; {@code optional}
     * ids (bound arrows) are added one by one where free. The owner or an operator takes over leases others hold.
     *
     * @return ids granted (empty when denied)
     */
    public List<UUID> acquire(UUID p, int requestId, List<UUID> ids, List<UUID> optional) {
        Peer peer = peers.get(p);
        if (peer == null) return List.of();
        long now = env.now();
        expire();
        if (peer.leaseRequests == null) peer.leaseRequests = new TokenBucket(LEASE_REQUESTS_PER_S, now);
        if (!peer.leaseRequests.ready(now)) return deny(p, requestId, "rate limit");
        peer.leaseRequests.charge(1, now);
        if (ids.size() + optional.size() > MAX_LEASES) return deny(p, requestId, "too many");
        Set<UUID> want = expandGroups(p, ids);
        Map<UUID, UUID> takeOver = new HashMap<>();
        Map<UUID, Long> cool = cooldown.getOrDefault(p, Map.of());
        for (UUID id : want) {
            Element e = store.get(id);
            if (e == null || e.deleted) return deny(p, requestId, "deleted");
            String no = canEdit(p, e, e);
            if (no != null) return deny(p, requestId, no);
            Lease l = leases.get(id);
            if (l != null && !l.holder.equals(p)) {
                if (!mayBreak(p, e)) {
                    l.contested = true;
                    return deny(p, requestId, "held:" + nameOf(l.holder));
                }
                takeOver.put(id, l.holder);
            }
            if (cool.getOrDefault(id, Long.MIN_VALUE) > now && !mayBreak(p, e)) return deny(p, requestId, "cooldown");
        }
        Set<UUID> extra = new LinkedHashSet<>();
        for (UUID id : optional) {
            Element e = store.get(id);
            Lease l = leases.get(id);
            if (e != null && !e.deleted && canEdit(p, e, e) == null && (l == null || l.holder.equals(p)) && !want.contains(id))
                extra.add(id);
        }
        int held = heldBy.getOrDefault(p, Set.of()).size();
        Set<UUID> all = new LinkedHashSet<>(want);
        all.addAll(extra);
        int fresh = 0;
        for (UUID id : all) if (!heldBy.getOrDefault(p, Set.of()).contains(id)) fresh++;
        if (held + fresh > MAX_LEASES) return deny(p, requestId, "too many");
        takeOver.forEach((id, holder) -> takeOver(p, holder, id));
        List<UUID> added = new ArrayList<>();
        for (UUID id : all) {
            Lease old = leases.get(id);
            boolean mine = old != null && old.holder.equals(p);
            if (mine) {
                old.expires = Math.min(now + LEASE_MS, old.granted + LEASE_MAX_MS);
                continue;
            }
            leases.put(id, new Lease(p, now + LEASE_MS, now));
            heldBy.computeIfAbsent(p, k -> new HashSet<>()).add(id);
            added.add(id);
        }
        List<UUID> granted = new ArrayList<>(all);
        out.leaseReply(p, requestId, true, granted, null);
        leaseDelta(p, added, List.of());
        return granted;
    }

    private List<UUID> deny(UUID p, int requestId, String reason) {
        out.leaseReply(p, requestId, false, List.of(), reason);
        return List.of();
    }

    /** Extends every lease {@code p} holds (the gesture is still going), never past its maximum lifetime. */
    public void renew(UUID p) {
        long now = env.now();
        for (UUID id : heldBy.getOrDefault(p, Set.of())) {
            Lease l = leases.get(id);
            if (l != null) l.expires = Math.min(now + LEASE_MS, l.granted + LEASE_MAX_MS);
        }
    }

    /** Releases {@code ids} (all when empty) held by {@code p}. */
    public void release(UUID p, List<UUID> ids) {
        Set<UUID> mine = heldBy.getOrDefault(p, Set.of());
        List<UUID> gone = new ArrayList<>();
        for (UUID id : ids.isEmpty() ? new ArrayList<>(mine) : ids)
            if (mine.contains(id)) gone.add(id);
        if (gone.isEmpty()) return;
        drop(p, gone);
        leaseDelta(p, List.of(), gone);
    }

    /** Drops all of {@code p}'s leases; with a reason, tells the holder. */
    public void releaseAll(UUID p, String reason) {
        List<UUID> held = new ArrayList<>(heldBy.getOrDefault(p, Set.of()));
        if (held.isEmpty()) return;
        if (reason != null) revoke(p, held, reason);
        else {
            drop(p, held);
            leaseDelta(p, List.of(), held);
        }
    }

    /** {@code p} (owner or operator) takes {@code id} from {@code holder}, who may not grab it back for a moment. */
    private void takeOver(UUID p, UUID holder, UUID id) {
        Element e = store.get(id);
        revoke(holder, List.of(id), e != null && env.isOp(p) && !p.equals(e.owner) ? "taken over by an operator" : "taken over by the owner");
        cooldown.computeIfAbsent(holder, k -> new HashMap<>()).put(id, env.now() + COOLDOWN_MS);
    }

    /** Takes {@code ids} away from {@code holder} and tells them and everyone else. */
    private void revoke(UUID holder, List<UUID> ids, String reason) {
        drop(holder, ids);
        if (peers.containsKey(holder)) out.leaseRevoked(holder, ids, reason);
        leaseDelta(holder, List.of(), ids);
    }

    private void drop(UUID holder, List<UUID> ids) {
        Set<UUID> mine = heldBy.get(holder);
        for (UUID id : ids) {
            Lease l = leases.get(id);
            if (l != null && l.holder.equals(holder)) leases.remove(id);
            if (mine != null) mine.remove(id);
        }
        if (mine != null && mine.isEmpty()) heldBy.remove(holder);
    }

    /** Op command: force-release one player's leases, or everyone's when {@code target} is null. */
    public int forceRelease(UUID target) {
        Set<UUID> holders = target == null ? new HashSet<>(heldBy.keySet()) : Set.of(target);
        int n = 0;
        for (UUID h : holders) {
            n += heldBy.getOrDefault(h, Set.of()).size();
            releaseAll(h, "released by an operator");
        }
        return n;
    }

    /** Expires idle leases and serves pending resyncs. */
    public void tick() {
        expire();
        long now = env.now();
        for (Map.Entry<UUID, Peer> en : new ArrayList<>(peers.entrySet()))
            if (en.getValue().resyncPending && now - en.getValue().lastResync >= RESYNC_MIN_MS) fullSync(en.getKey());
    }

    /** The one place leases expire, so the holder and everyone else always hear about it. */
    private void expire() {
        long now = env.now();
        Map<UUID, List<UUID>> expired = new HashMap<>();
        leases.forEach((id, l) -> {
            if (l.expires >= now) return;
            expired.computeIfAbsent(l.holder, k -> new ArrayList<>()).add(id);
            // Hit the lifetime cap while someone else wanted it: that someone gets a turn first.
            if (l.contested && l.expires >= l.granted + LEASE_MAX_MS)
                cooldown.computeIfAbsent(l.holder, k -> new HashMap<>()).put(id, now + COOLDOWN_MS);
        });
        expired.forEach((holder, ids) -> revoke(holder, ids, "expired"));
        cooldown.values().forEach(m -> m.values().removeIf(t -> t <= now));
        cooldown.values().removeIf(Map::isEmpty);
    }

    /** Drops old tombstones (by age, and the oldest beyond a count cap). */
    public void prune() {
        Settings s = env.settings();
        for (UUID id : store.prune(env.now(), TOMBSTONE_MS, tombstoneCap(s))) deletedBy.remove(id);
        deletions.values().forEach(d -> d.removeIf(id -> !deletedBy.containsKey(id)));
        deletions.values().removeIf(java.util.ArrayDeque::isEmpty);
    }

    /** Global backstop only; the per-deleter cap in {@link #recordDelete} does the real work. */
    private static int tombstoneCap(Settings s) {
        return Math.max(10_000, s.maxPerDimension() * 10);
    }

    /** Requested ids plus the other members of their outermost groups that {@code p} may see and edit. */
    private Set<UUID> expandGroups(UUID p, List<UUID> ids) {
        Set<UUID> outIds = new LinkedHashSet<>(ids);
        Set<UUID> groups = new HashSet<>();
        for (UUID id : ids) {
            Element e = store.get(id);
            if (e != null && e.groups.length > 0) groups.add(e.groups[e.groups.length - 1]);
        }
        // Members the requester cannot see or edit are skipped, so nobody can block a group by joining it.
        for (UUID g : groups)
            for (Element m : store.groupMembers(g))
                if (canSee(p, m) && canEdit(p, m, m) == null) outIds.add(m.id);
        return outIds;
    }

    /**
     * Tells everyone else what changed in {@code holder}'s leases: additions filtered to what each may see, removals
     * of every id that peer was told about (whether or not it still sees the element).
     */
    private void leaseDelta(UUID holder, List<UUID> added, List<UUID> removed) {
        if (added.isEmpty() && removed.isEmpty()) return;
        for (Map.Entry<UUID, Peer> en : peers.entrySet()) {
            UUID q = en.getKey();
            if (q.equals(holder)) continue;
            Set<UUID> sent = en.getValue().leaseSent.computeIfAbsent(holder, k -> new HashSet<>());
            List<UUID> a = new ArrayList<>(), r = new ArrayList<>();
            for (UUID id : added) {
                Element e = store.get(id);
                if (e != null && canSee(q, e) && sent.add(id)) a.add(id);
            }
            for (UUID id : removed) if (sent.remove(id)) r.add(id);
            if (sent.isEmpty()) en.getValue().leaseSent.remove(holder);
            if (!a.isEmpty() || !r.isEmpty()) out.leases(q, holder, nameOf(holder), a, r, false);
        }
    }

    private void sendAllLeasesTo(UUID to) {
        Peer peer = peers.get(to);
        if (peer == null) return;
        Set<UUID> holders = new HashSet<>(heldBy.keySet());
        holders.addAll(peer.leaseSent.keySet());
        for (UUID h : holders) {
            if (h.equals(to)) continue;
            Set<UUID> ids = new LinkedHashSet<>();
            for (UUID id : heldBy.getOrDefault(h, Set.of())) {
                Element e = store.get(id);
                if (e != null && canSee(to, e)) ids.add(id);
            }
            if (ids.isEmpty()) peer.leaseSent.remove(h);
            else peer.leaseSent.put(h, new HashSet<>(ids));
            out.leases(to, h, nameOf(h), new ArrayList<>(ids), List.of(), true);
        }
    }

    /** Leases whose holder can no longer see or edit the element are revoked. */
    private void revokeLost() {
        Map<UUID, List<UUID>> lost = new HashMap<>();
        leases.forEach((id, l) -> {
            Element e = store.get(id);
            if (e == null || e.deleted || canEdit(l.holder, e, e) != null)
                lost.computeIfAbsent(l.holder, k -> new ArrayList<>()).add(id);
        });
        lost.forEach((holder, ids) -> revoke(holder, ids, "permission lost"));
    }

    // ---- ops ----

    /**
     * Applies op {@code opId}. Each element names the state it was made from ({@code version}/{@code baseNonce});
     * it applies only to exactly that state, and the server assigns the stored version (base + 1) and acks it, so
     * clients never pick versions. Rejected elements are resynced (up to a byte cap per op; beyond it and on rate
     * limiting a throttled full resync follows), then the op is acked.
     */
    public void edit(UUID p, int opId, List<Element> elements) {
        Peer peer = peers.get(p);
        if (peer == null) return;
        long now = env.now();
        expire();
        Settings s = env.settings();
        if (peer.edits == null) peer.edits = new TokenBucket(s.editBytesPerSecond(), now);
        List<Element> accepted = new ArrayList<>(), resync = new ArrayList<>();
        List<UUID> forget = new ArrayList<>(), rejected = new ArrayList<>(), okIds = new ArrayList<>();
        List<Integer> okVersions = new ArrayList<>();
        String reason = null;
        int resyncBytes = 0;
        boolean holder = false;
        for (Element e : elements) {
            Element existing = store.get(e.id);
            String why = check(p, peer, s, existing, e, now);
            if ("noop".equals(why)) continue;
            if (why != null) {
                reason = why;
                rejected.add(e.id);
                // Rate limiting must not turn into amplification: no per-element resync, one throttled full one.
                if (why.equals("rate limit")) peer.resyncPending = true;
                else if (existing != null && !existing.deleted && canSee(p, existing)) {
                    // Once per element, however often an op repeats it.
                    if (resync.stream().anyMatch(x -> x.id.equals(existing.id))) continue;
                    resyncBytes += ElementCodec.size(existing);
                    if (resyncBytes <= RESYNC_BYTES_PER_OP) resync.add(existing);
                    else peer.resyncPending = true;
                } else forget.add(e.id);
                continue;
            }
            Lease l = leases.get(e.id);
            if (l != null && !l.holder.equals(p)) takeOver(p, l.holder, e.id);
            holder |= l != null && l.holder.equals(p);
            e.owner = existing == null ? p : existing.owner;
            e.updated = now;
            e.version = existing == null ? 1 : existing.version + 1;
            e.baseNonce = 0;
            if (e.deleted) {
                recordDelete(p, e.id, s);
                release(p, List.of(e.id));
            } else deletedBy.remove(e.id);
            store.put(e);
            accepted.add(e);
            okIds.add(e.id);
            okVersions.add(e.version);
        }
        if (holder) renew(p);
        if (!resync.isEmpty()) out.elements(p, ++peer.seq, false, true, resync);
        if (!forget.isEmpty()) {
            out.remove(p, ++peer.seq, forget);
            peer.known.removeAll(forget);
        }
        out.ack(p, opId, rejected, reason, okIds, okVersions.stream().mapToInt(Integer::intValue).toArray());
        if (accepted.isEmpty()) return;
        List<Element> follow = unbindDeleted(accepted);
        accepted.addAll(follow);
        Set<UUID> serverMade = new HashSet<>();
        follow.forEach(e -> serverMade.add(e.id));
        broadcast(accepted, p, serverMade);
        if (store.tombstones() > tombstoneCap(s)) prune();
        if (accepted.stream().anyMatch(e -> e.deleted || e.visibility != Element.Visibility.PUBLIC)) revokeLost();
    }

    /** Tracks who deleted what; past {@code maxPerPlayer} tombstones a player's oldest are dropped (theirs only). */
    private void recordDelete(UUID p, UUID id, Settings s) {
        deletedBy.put(id, p);
        java.util.ArrayDeque<UUID> mine = deletions.computeIfAbsent(p, k -> new java.util.ArrayDeque<>());
        mine.addLast(id);
        while (mine.size() > Math.max(100, s.maxPerPlayer())) {
            UUID old = mine.pollFirst();
            Element t = store.get(old);
            if (t != null && t.deleted && p.equals(deletedBy.get(old)) && !old.equals(id)) {
                store.dropTombstone(old);
                deletedBy.remove(old);
            }
        }
    }

    /** @return null to store, "noop" to drop silently, else the rejection reason */
    private String check(UUID p, Peer peer, Settings s, Element existing, Element e, long now) {
        if (!peer.edits.ready(now)) return "rate limit";
        peer.edits.charge(ElementCodec.size(e), now);
        String bad = Rules.validate(e, s.limits());
        if (bad != null) return bad;
        if (!env.dimensionExists(e.dim)) return "unknown dimension";
        if (e.type == Element.Type.STAMP && e.stamp.startsWith("item:") && !env.itemExists(e.stamp.substring(5)))
            return "unknown item";
        if (existing == null) {
            // Deleting something that never existed would only pile up tombstones.
            if (e.deleted) return "noop";
            // An edit made from some state of an id we no longer have: it was deleted (and pruned) meanwhile.
            if (e.version != 0) return "deleted";
        }
        // Visibility first, so held/taken answers never reveal elements the sender cannot see.
        if (existing != null && !existing.deleted && !canSee(p, existing) && !env.isOp(p)) return "not visible";
        if (existing != null && !existing.dim.equals(e.dim)) return "dimension mismatch";
        // Version 0 = a fresh element; an id that already exists was not freshly made.
        if (existing != null && e.version == 0) return existing.deleted ? "deleted" : "id taken";
        Lease l = leases.get(e.id);
        if (l != null && !l.holder.equals(p) && (existing == null || !mayBreak(p, existing))) return "held:" + nameOf(l.holder);
        if (existing != null && existing.deleted) {
            if (e.deleted) return "noop";
            // Only whoever deleted it may bring it back (their undo); everyone else's edit is dropped.
            if (!p.equals(deletedBy.get(e.id))) return "deleted";
        }
        String denied = canEdit(p, existing, e);
        if (denied != null) return denied;
        // Made from an older (or unknown) state: someone else's edit landed first.
        if (!Rules.basedOn(e, existing)) return "stale";
        if (existing != null && existing.version == Integer.MAX_VALUE) return "version limit";
        boolean revive = !e.deleted && (existing == null || existing.deleted);
        if (revive) {
            UUID owner = existing == null ? p : existing.owner;
            if (store.ownedBy(owner) >= s.maxPerPlayer()) return "too many drawings";
            if (store.inDimension(e.dim) >= s.maxPerDimension()) return "dimension full";
        }
        return null;
    }

    /** Arrows bound to deleted elements let go (server-authored edit), unless someone holds the arrow. */
    private List<Element> unbindDeleted(List<Element> accepted) {
        List<Element> changed = new ArrayList<>();
        for (Element gone : accepted) {
            if (!gone.deleted) continue;
            for (Element a : store.arrowsBoundTo(gone.id)) {
                if (leases.containsKey(a.id)) continue;
                Element c = a.copy();
                if (gone.id.equals(c.startBind)) {
                    c.startBind = null;
                    c.startFixed = null;
                }
                if (gone.id.equals(c.endBind)) {
                    c.endBind = null;
                    c.endFixed = null;
                }
                if (a.version == Integer.MAX_VALUE) continue;
                c.version = a.version + 1;
                c.nonce = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                c.updated = env.now();
                store.put(c);
                changed.add(c);
            }
        }
        return changed;
    }

    /** Each element to everyone who can see it; a removal to those who knew it but no longer can. */
    private void broadcast(List<Element> changed, UUID skip, Set<UUID> toSenderToo) {
        for (Map.Entry<UUID, Peer> en : peers.entrySet()) {
            UUID q = en.getKey();
            Peer peer = en.getValue();
            List<Element> up = new ArrayList<>();
            List<UUID> gone = new ArrayList<>();
            for (Element e : changed) {
                if (!e.deleted && canSee(q, e)) {
                    peer.known.add(e.id);
                    // The sender already has its own version; server-authored follow-ups still go to it.
                    if (!q.equals(skip) || toSenderToo.contains(e.id)) up.add(e);
                } else if (peer.known.remove(e.id) && !q.equals(skip)) gone.add(e.id);
            }
            if (!up.isEmpty()) out.elements(q, ++peer.seq, false, false, up);
            if (!gone.isEmpty()) out.remove(q, ++peer.seq, gone);
        }
    }

    /** Tombstones every live element matching {@code filter} (op command) and broadcasts the deletes. */
    public int clear(Predicate<Element> filter) {
        List<Element> changed = new ArrayList<>();
        for (Element e : new ArrayList<>(store.all())) {
            if (e.deleted || !filter.test(e)) continue;
            Element t = e.copy();
            t.deleted = true;
            if (e.version == Integer.MAX_VALUE) continue;
            t.version = e.version + 1;
            t.nonce = java.util.concurrent.ThreadLocalRandom.current().nextInt();
            t.updated = env.now();
            if (leases.containsKey(e.id)) revoke(leases.get(e.id).holder, List.of(e.id), "cleared by an operator");
            store.put(t);
            changed.add(t);
        }
        if (!changed.isEmpty()) {
            broadcast(changed, null, Set.of());
            if (store.tombstones() > tombstoneCap(env.settings())) prune();
        }
        return changed.size();
    }

    // ---- live ----

    /** A freehand append chunk: its style must validate like a full element, and it must stay under the point limit. */
    private boolean validAppend(Element g, int from) {
        if (g.type != Element.Type.FREEDRAW || from + g.pointCount() > env.settings().limits().maxPoints()) return false;
        Element v = g.copy();
        // A chunk may hold a single new point; validate the rest of the element with a stand-in pair.
        if (v.pointCount() < 2) v.pts = new float[]{0, 0, 0, 0};
        return Rules.validate(v, env.settings().limits()) == null;
    }

    private static boolean finite(float[] fs) {
        for (float f : fs) if (!Float.isFinite(f) || Math.abs(f) > 1e5) return false;
        return true;
    }

    /**
     * Relays live state. Ghosts of stored elements only go out while the sender holds their lease, and only to
     * players who can see them; new-shape ghosts and the cursor follow the sender's visibility. Over 25/s or over
     * the live byte rate the update is dropped; "inactive" is relayed only as inactive.
     */
    public void live(UUID p, Live in) {
        Peer peer = peers.get(p);
        if (peer == null) return;
        long now = env.now();
        // Inactive carries nothing and is only relayed to peers showing this player, once: never rate-limited.
        if (!in.active()) {
            dropLive(p);
            return;
        }
        if (now - peer.lastLive < LIVE_MIN_MS) return;
        if (peer.live == null) peer.live = new TokenBucket(env.settings().liveBytesPerSecond(), now);
        if (!peer.live.ready(now)) return;
        int bytes = 64 + in.laser().length * 4;
        for (Element g : in.ghosts()) bytes += ElementCodec.size(g);
        peer.live.charge(bytes, now);
        peer.lastLive = now;
        expire();
        if (!Double.isFinite(in.x()) || !Double.isFinite(in.z()) || in.ghosts().size() > Live.MAX_GHOSTS
                || in.appendFrom().length != in.ghosts().size() || !finite(in.laser())) return;
        List<Element> ghosts = new ArrayList<>();
        List<Integer> appends = new ArrayList<>();
        for (int i = 0; i < in.ghosts().size(); i++) {
            Element g = in.ghosts().get(i);
            Element stored = store.get(g.id);
            if (stored != null && !stored.deleted) {
                Lease l = leases.get(g.id);
                if (l == null || !l.holder.equals(p)) continue;
                g.owner = stored.owner;
                g.visibility = stored.visibility;
            } else {
                g.owner = p;
                g.visibility = in.visibility();
            }
            int from = in.appendFrom()[i];
            if (from < 0 ? Rules.validate(g, env.settings().limits()) != null : !validAppend(g, from)) continue;
            // Ghost stamps get the same registry check as stored ones: no made-up item ids reach other viewers.
            if (g.type == Element.Type.STAMP && g.stamp.startsWith("item:") && !env.itemExists(g.stamp.substring(5))) continue;
            ghosts.add(g);
            appends.add(from);
        }
        for (UUID q : peers.keySet()) {
            if (q.equals(p)) continue;
            boolean cursor = in.visibility() == Element.Visibility.PUBLIC
                    || in.visibility() == Element.Visibility.TEAM && env.sameTeam(p, q);
            List<Element> mine = new ArrayList<>();
            List<Integer> app = new ArrayList<>();
            for (int i = 0; i < ghosts.size(); i++)
                if (canSee(q, ghosts.get(i))) {
                    mine.add(ghosts.get(i));
                    app.add(appends.get(i));
                }
            if (!cursor && mine.isEmpty()) {
                if (peer.shownTo.remove(q)) out.live(q, p, peer.name, Live.inactive());
                continue;
            }
            peer.shownTo.add(q);
            // PRIVATE in a relayed state means "ghosts only, no cursor": position and laser are withheld too.
            out.live(q, p, peer.name, cursor
                    ? new Live(in.dim(), in.x(), in.z(), true, in.visibility(), mine, toArray(app), in.laser())
                    : new Live("", 0, 0, true, Element.Visibility.PRIVATE, mine, toArray(app), new float[0]));
        }
    }

    private static int[] toArray(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }
}
