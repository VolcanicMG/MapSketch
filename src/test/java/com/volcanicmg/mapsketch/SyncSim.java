package com.volcanicmg.mapsketch;

import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Rules;
import com.volcanicmg.mapsketch.sync.ElementStore;
import com.volcanicmg.mapsketch.sync.Live;
import com.volcanicmg.mapsketch.sync.Outbox;
import com.volcanicmg.mapsketch.sync.SyncServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Headless multiplayer simulation ({@code ./gradlew syncSim}, also run by {@code check}): drives {@link SyncServer}
 * with fake clients that apply what they receive in sequence order, interleaving ops to hit the races. Clients
 * behave like the real one: an edit names the state it was made from (version + state token), the server assigns
 * the stored version, and the sender adopts the stored state on ack.
 */
public final class SyncSim {
    static final UUID ALICE = new UUID(1, 1), BOB = new UUID(2, 2), CAROL = new UUID(3, 3), OPER = new UUID(9, 9);
    static final String DIM = "minecraft:overworld";
    static final Random RND = new Random(7);

    /** One fake client: its copy of the elements plus everything the server told it. */
    static final class Client {
        final UUID id;
        final Map<UUID, Element> elements = new HashMap<>();
        long lastSeq;
        int gaps, resets, forced, liveMsgs, op;
        boolean dropNext;
        final List<String> acks = new ArrayList<>(), replies = new ArrayList<>(), revoked = new ArrayList<>();
        final Map<UUID, Integer> assigned = new HashMap<>();
        final Map<UUID, Set<UUID>> leases = new HashMap<>();
        final Map<UUID, Live> live = new HashMap<>();

        Client(UUID id) {
            this.id = id;
        }

        void seq(long seq, boolean reset) {
            if (reset) resets++;
            else if (seq != lastSeq + 1) gaps++;
            lastSeq = seq;
        }
    }

    static final Map<UUID, Client> clients = new HashMap<>();
    static long now = 1_000_000;
    static final Set<UUID> ops = new HashSet<>();
    static int maxPerDim = 20000, maxPerPlayer = 2000;
    static long editBytes = 1 << 20;
    static final Map<UUID, String> teams = new HashMap<>();
    static int passed;

    static final Outbox OUT = new Outbox() {
        @Override
        public void elements(UUID to, long seq, boolean reset, boolean force, List<Element> elements) {
            Client c = clients.get(to);
            if (c.dropNext) {
                c.dropNext = false;
                return;
            }
            c.seq(seq, reset);
            if (reset) c.elements.clear();
            if (force) c.forced += elements.size();
            for (Element e : elements)
                if (force || Rules.replaces(e, c.elements.get(e.id), false)) c.elements.put(e.id, e.copy());
        }

        @Override
        public void remove(UUID to, long seq, List<UUID> ids) {
            Client c = clients.get(to);
            c.seq(seq, false);
            ids.forEach(c.elements::remove);
        }

        @Override
        public void ack(UUID to, int opId, List<UUID> rejected, String reason, List<UUID> accepted, int[] versions) {
            Client c = clients.get(to);
            c.acks.add(rejected.isEmpty() ? "ok" : "rejected:" + reason);
            for (int i = 0; i < accepted.size(); i++) c.assigned.put(accepted.get(i), versions[i]);
        }

        @Override
        public void leaseReply(UUID to, int requestId, boolean granted, List<UUID> ids, String reason) {
            clients.get(to).replies.add(granted ? "granted" : "denied:" + reason);
        }

        @Override
        public void leaseRevoked(UUID to, List<UUID> ids, String reason) {
            clients.get(to).revoked.add(reason);
        }

        @Override
        public void leases(UUID to, UUID holder, String name, List<UUID> added, List<UUID> removed, boolean reset) {
            Set<UUID> set = clients.get(to).leases.computeIfAbsent(holder, k -> new HashSet<>());
            if (reset) set.clear();
            set.addAll(added);
            removed.forEach(set::remove);
        }

        @Override
        public void live(UUID to, UUID from, String name, Live l) {
            Client c = clients.get(to);
            c.liveMsgs++;
            if (l.active()) c.live.put(from, l);
            else c.live.remove(from);
        }
    };

    static final SyncServer.Env ENV = new SyncServer.Env() {
        @Override
        public boolean isOp(UUID p) {
            return ops.contains(p);
        }

        @Override
        public boolean dimensionExists(String dim) {
            return dim.equals(DIM);
        }

        @Override
        public boolean sameTeam(UUID owner, UUID viewer) {
            return teams.get(owner) != null && teams.get(owner).equals(teams.get(viewer));
        }

        @Override
        public SyncServer.Settings settings() {
            return new SyncServer.Settings(new Rules.Limits(2000, 500), true, maxPerPlayer, maxPerDim, editBytes, 1 << 20);
        }

        @Override
        public long now() {
            return now;
        }
    };

    static SyncServer server;
    static ElementStore store;

    public static void main(String[] args) {
        reset();
        grabRace();
        allOrNothing();
        groupLease();
        deleteHeld();
        deleteVsEdit();
        undoOwnDelete();
        concurrentEdits();
        serverAssignedVersions();
        zOrderTies();
        arrowTargetDeleted();
        pasteIdCollision();
        unknownIdWithBase();
        visibilityMidEdit();
        removalFanOut();
        leaseExpiry();
        disconnectMidGesture();
        sequenceGap();
        liveRelay();
        operators();
        dimensionChange();
        teamChange();
        serverRestart();
        reset();
        tombstoneSpam();
        throttles();
        leaseCaps();
        reacquireCooldown();
        takeoverRejectsHolder();
        staleHolderOutline();
        groupInjection();
        rateLimitNoAmplification();
        System.out.println("SyncSim: " + passed + " scenarios passed");
    }

    static void reset() {
        clients.clear();
        ops.clear();
        teams.clear();
        store = new ElementStore(() -> {
        });
        server = new SyncServer(store, ENV, OUT);
        join(ALICE, "Alice");
        join(BOB, "Bob");
    }

    static Client join(UUID id, String name) {
        Client c = new Client(id);
        clients.put(id, c);
        server.join(id, name);
        return c;
    }

    static Client c(UUID id) {
        return clients.get(id);
    }

    static String last(List<String> l) {
        return l.isEmpty() ? "" : l.get(l.size() - 1);
    }

    /** A new element: version 0, no base. */
    static Element rect(UUID owner) {
        Element e = new Element();
        e.id = UUID.randomUUID();
        e.dim = DIM;
        e.type = Element.Type.RECTANGLE;
        e.w = 10;
        e.h = 10;
        e.owner = owner;
        e.nonce = RND.nextInt();
        return e;
    }

    /** An edit made from {@code base}: same version, base token = its nonce, a fresh token of its own. */
    static Element from(Element base) {
        Element e = base.copy();
        e.baseNonce = base.nonce;
        e.nonce = RND.nextInt();
        return e;
    }

    /** An edit made from the client's own current copy. */
    static Element next(UUID who, UUID id) {
        return from(c(who).elements.get(id));
    }

    /** Sends one op; accepted elements become the sender's copy as the server stored them (the ack). */
    static String op(UUID who, Element... els) {
        Client cl = c(who);
        List<Element> list = new ArrayList<>();
        for (Element e : els) list.add(e.copy());
        server.edit(who, ++cl.op, list);
        for (Element e : els) {
            Element stored = store.get(e.id);
            if (stored != null && stored.nonce == e.nonce) cl.elements.put(e.id, stored.copy());
        }
        return last(cl.acks);
    }

    static Element deleted(Element e) {
        e.deleted = true;
        return e;
    }

    static String acquire(UUID who, Element... els) {
        List<UUID> ids = new ArrayList<>();
        for (Element e : els) ids.add(e.id);
        now += 60; // past the lease request rate limit
        server.acquire(who, 1, ids, List.of());
        return last(c(who).replies);
    }

    static void pass(String name, String detail) {
        passed++;
        System.out.println("  PASS " + name + " — " + detail);
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    // ---- scenarios ----

    static void grabRace() {
        Element r = rect(ALICE);
        check(op(ALICE, r).equals("ok"), "create");
        String a = acquire(ALICE, r), b = acquire(BOB, r);
        check(a.equals("granted") && b.equals("denied:held:Alice"), a + " " + b);
        check(c(BOB).leases.get(ALICE).contains(r.id), "Bob sees Alice's lease");
        // Bob's optimistic move lands anyway: rejected, and Bob gets Alice's authoritative state back.
        Element bob = next(BOB, r.id);
        bob.x = 7;
        String res = op(BOB, bob);
        check(res.equals("rejected:held:Alice") && c(BOB).elements.get(r.id).x == 0, res);
        Element alice = next(ALICE, r.id);
        alice.x = 5;
        check(op(ALICE, alice).equals("ok") && c(BOB).elements.get(r.id).x == 5, "holder's move reaches Bob");
        server.release(ALICE, List.of());
        check(c(BOB).leases.get(ALICE).isEmpty() && acquire(BOB, r).equals("granted"), "after release Bob may grab");
        server.release(BOB, List.of());
        pass("grab race", "first lease wins; loser denied with holder name, its optimistic edit rolled back");
    }

    static void allOrNothing() {
        Element r1 = rect(ALICE), r2 = rect(ALICE);
        op(ALICE, r1, r2);
        acquire(ALICE, r1);
        check(acquire(BOB, r1, r2).equals("denied:held:Alice"), "multi grab denied");
        check(acquire(BOB, r2).equals("granted"), "r2 stayed free");
        server.release(ALICE, List.of());
        server.release(BOB, List.of());
        pass("multi-element grab", "all or nothing: one held id denies the whole set, nothing partially leased");
    }

    static void groupLease() {
        Element a = rect(ALICE), b = rect(ALICE);
        UUID g = UUID.randomUUID();
        a.groups = new UUID[]{g};
        b.groups = new UUID[]{g};
        op(ALICE, a, b);
        acquire(ALICE, a);
        check(acquire(BOB, b).equals("denied:held:Alice"), "group member leased via its group");
        server.release(ALICE, List.of());
        pass("group lease", "grabbing one member leases the whole group");
    }

    static void deleteHeld() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(ALICE, r);
        check(op(BOB, deleted(next(BOB, r.id))).equals("rejected:held:Alice") && !store.get(r.id).deleted, "delete of held rejected");
        check(c(BOB).elements.containsKey(r.id), "Bob gets it back");
        server.release(ALICE, List.of());
        pass("delete vs held", "deleting an element someone holds is rejected and restored");
    }

    static Element r5;

    static void deleteVsEdit() {
        r5 = rect(ALICE);
        op(ALICE, r5);
        Element stale = c(BOB).elements.get(r5.id).copy();
        check(op(ALICE, deleted(next(ALICE, r5.id))).equals("ok"), "delete");
        check(!c(BOB).elements.containsKey(r5.id), "Bob saw the delete");
        // Bob edits from the copy he had before he saw the delete: dropped, Bob told.
        Element late = from(stale);
        late.x = 99;
        c(BOB).elements.put(late.id, late.copy());
        check(op(BOB, late).equals("rejected:deleted") && !c(BOB).elements.containsKey(r5.id), "edit after delete dropped");
        pass("delete vs edit", "an edit to an element deleted meanwhile is dropped; the editor is told and forgets it");
    }

    static void undoOwnDelete() {
        // Alice's client keeps her tombstone (as the real store does) and restores from it.
        Element tomb = c(ALICE).elements.get(r5.id);
        check(tomb != null && tomb.deleted, "tombstone kept locally");
        Element back = from(tomb);
        back.deleted = false;
        check(op(ALICE, back).equals("ok") && c(BOB).elements.containsKey(r5.id), "undo of own delete");
        Element r = rect(ALICE);
        op(ALICE, r);
        op(ALICE, deleted(next(ALICE, r.id)));
        Element revive = from(store.get(r.id));
        revive.deleted = false;
        check(op(BOB, revive).equals("rejected:deleted"), "others cannot revive, even with the right base");
        pass("undo/redo of a delete", "only whoever deleted may restore (their undo), from the kept tombstone");
    }

    static void concurrentEdits() {
        Element r = rect(ALICE);
        op(ALICE, r);
        // Both edit the same stored state without a lease (panel property changes).
        Element a = next(ALICE, r.id), b = next(BOB, r.id);
        a.stroke = 1;
        b.stroke = 2;
        check(op(ALICE, a).equals("ok"), "first");
        check(op(BOB, b).equals("rejected:stale"), "second, made from the older state, is stale");
        check(c(BOB).elements.get(r.id).stroke == 1 && c(BOB).elements.get(r.id).version == 2, "Bob rebased onto Alice's");
        Element b2 = next(BOB, r.id);
        b2.stroke = 3;
        check(op(BOB, b2).equals("ok") && c(ALICE).elements.get(r.id).stroke == 3, "Bob's retry from the new state lands");
        pass("concurrent unleased edits", "an edit only applies to the exact state it was made from; the loser is resynced and retries");
    }

    static void serverAssignedVersions() {
        Element r = rect(ALICE);
        op(ALICE, r);
        check(c(ALICE).assigned.get(r.id) == 1 && store.get(r.id).version == 1, "create assigned 1");
        // Two edits queued before any ack: the second is made from the predicted state of the first.
        Element e1 = next(ALICE, r.id);
        e1.x = 1;
        Element predicted = e1.copy();
        predicted.version++;
        Element e2 = from(predicted);
        e2.x = 2;
        check(op(ALICE, e1).equals("ok") && c(ALICE).assigned.get(r.id) == 2, "first queued edit, version 2 echoed");
        check(op(ALICE, e2).equals("ok") && c(ALICE).assigned.get(r.id) == 3 && store.get(r.id).x == 2, "queued follow-up survives");
        // The client does not choose versions: a made-up one is just a wrong base.
        Element lie = next(ALICE, r.id);
        lie.version = Integer.MAX_VALUE;
        check(op(ALICE, lie).equals("rejected:stale") && store.get(r.id).version == 3, "client-chosen version refused");
        // Undo: Alice's guard sees her own last state and restores the earlier content from it.
        Element mine = c(ALICE).elements.get(r.id);
        check(Rules.unchangedSince(e2.nonce, false, mine), "undo guard: unchanged");
        Element undo = from(mine);
        undo.x = 1;
        check(op(ALICE, undo).equals("ok") && store.get(r.id).x == 1 && store.get(r.id).version == 4, "undo applied");
        Element bob = next(BOB, r.id);
        bob.x = 50;
        op(BOB, bob);
        check(!Rules.unchangedSince(undo.nonce, false, c(ALICE).elements.get(r.id)), "undo guard: Bob changed it, skip");
        // At the ceiling the element refuses edits instead of overflowing.
        Element top = store.get(r.id).copy();
        top.version = Integer.MAX_VALUE;
        store.put(top);
        check(op(ALICE, from(top)).equals("rejected:version limit") && store.get(r.id).version == Integer.MAX_VALUE, "no overflow");
        pass("server-assigned versions", "server stores base+1 and acks it; queued follow-ups, undo guard and stale bases behave; no overflow");
    }

    static void zOrderTies() {
        Element a = rect(ALICE), b = rect(BOB);
        a.order = "a5";
        b.order = "a5";
        op(ALICE, a);
        op(BOB, b);
        java.util.Comparator<Element> z = java.util.Comparator.<Element, String>comparing(e -> e.order).thenComparing(e -> e.id);
        List<Element> sa = new ArrayList<>(), sb = new ArrayList<>();
        for (Element e : c(ALICE).elements.values()) if (e.order.equals("a5")) sa.add(e);
        for (Element e : c(BOB).elements.values()) if (e.order.equals("a5")) sb.add(e);
        sa.sort(z);
        sb.sort(z);
        check(sa.size() == 2 && sa.get(0).id.equals(sb.get(0).id), "same order on both clients");
        pass("z-order ties", "racing reorders that pick the same fractional index sort identically (id tiebreak)");
    }

    static void arrowTargetDeleted() {
        Element box = rect(BOB);
        op(BOB, box);
        Element arrow = rect(ALICE);
        arrow.type = Element.Type.ARROW;
        arrow.pts = new float[]{0, 0, 5, 5};
        arrow.w = arrow.h = 0;
        arrow.endBind = box.id;
        op(ALICE, arrow);
        op(BOB, deleted(next(BOB, box.id)));
        check(store.get(arrow.id).endBind == null && c(ALICE).elements.get(arrow.id).endBind == null
                && c(BOB).elements.get(arrow.id).endBind == null, "arrow unbound everywhere");
        Element more = next(ALICE, arrow.id);
        more.x = 3;
        check(op(ALICE, more).equals("ok"), "Alice keeps editing from the server-made state");
        pass("arrow target deleted", "server unbinds arrows bound to a deleted shape and tells every viewer");
    }

    static void pasteIdCollision() {
        Element r = rect(ALICE);
        op(ALICE, r);
        Element dup = rect(BOB);
        dup.id = r.id;
        check(op(BOB, dup).equals("rejected:id taken"), "colliding paste rejected");
        pass("paste id collision", "a fresh element whose id already exists is rejected");
    }

    static void unknownIdWithBase() {
        Element ghost = rect(BOB);
        ghost.version = 3;
        ghost.baseNonce = 12345;
        check(op(BOB, ghost).equals("rejected:deleted") && store.get(ghost.id) == null, "non-new edit to an unknown id");
        pass("edit to an unknown id", "an edit made from some state of an id the server no longer has is 'deleted meanwhile', never a create");
    }

    static void visibilityMidEdit() {
        Element r = rect(ALICE);
        op(ALICE, r);
        check(acquire(BOB, r).equals("granted"), "Bob edits Alice's public rect");
        Element priv = next(ALICE, r.id);
        priv.visibility = Element.Visibility.PRIVATE;
        // The owner does not wait: her edit takes Bob's lease over, and Bob loses sight of the element.
        check(op(ALICE, priv).equals("ok"), "owner edit lands while Bob holds it");
        check(last(c(BOB).revoked).equals("taken over by the owner") && !c(BOB).elements.containsKey(r.id)
                && c(ALICE).leases.getOrDefault(BOB, Set.of()).isEmpty(), "Bob told, lease gone, element hidden");
        pass("visibility change mid-edit", "the owner takes the lease over; once private, the element vanishes for the others");
    }

    static void removalFanOut() {
        join(CAROL, "Carol");
        Element pub = rect(ALICE), del = rect(ALICE), tm = rect(ALICE);
        teams.put(ALICE, "red");
        teams.put(BOB, "red");
        tm.visibility = Element.Visibility.TEAM;
        op(ALICE, pub, del, tm);
        check(c(BOB).elements.containsKey(pub.id) && c(CAROL).elements.containsKey(pub.id) && c(BOB).elements.containsKey(tm.id)
                && !c(CAROL).elements.containsKey(tm.id), "initial views");
        Element priv = next(ALICE, pub.id);
        priv.visibility = Element.Visibility.PRIVATE;
        op(ALICE, priv);
        check(!c(BOB).elements.containsKey(pub.id) && !c(CAROL).elements.containsKey(pub.id), "privatize removes for both");
        op(ALICE, deleted(next(ALICE, del.id)));
        check(!c(BOB).elements.containsKey(del.id) && !c(CAROL).elements.containsKey(del.id), "delete removes for both");
        int carolSeq = (int) c(CAROL).lastSeq;
        op(ALICE, deleted(next(ALICE, tm.id)));
        check(!c(BOB).elements.containsKey(tm.id) && c(CAROL).lastSeq == carolSeq, "team delete: Bob removed, Carol told nothing");
        teams.clear();
        server.leave(CAROL);
        clients.remove(CAROL);
        pass("removal fan-out", "privatizing or deleting sends removals to every peer that knew the id, and nothing to those who never did");
    }

    static void leaseExpiry() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(ALICE, r);
        now += 6000;
        server.tick();
        check(last(c(ALICE).revoked).equals("expired") && acquire(BOB, r).equals("granted"), "expiry");
        server.release(BOB, List.of());
        pass("lease expiry", "an idle lease expires after 5 s and the holder is told");
    }

    static void disconnectMidGesture() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(ALICE, r);
        now += 100;
        server.live(ALICE, new Live(DIM, 1, 1, true, Element.Visibility.PUBLIC, List.of(store.get(r.id).copy()), new int[]{-1}, new float[0]));
        check(c(BOB).live.containsKey(ALICE), "Bob sees Alice live");
        server.leave(ALICE);
        check(c(BOB).leases.get(ALICE).isEmpty() && !c(BOB).live.containsKey(ALICE), "lease and ghost dropped");
        check(acquire(BOB, r).equals("granted"), "Bob may grab");
        server.release(BOB, List.of());
        int resets = c(ALICE).resets;
        server.join(ALICE, "Alice");
        check(c(ALICE).resets == resets + 1 && c(ALICE).elements.containsKey(r.id), "reconnect gets a full resync");
        pass("disconnect mid-gesture", "leases and live ghosts drop on disconnect; reconnect resyncs fully");
    }

    static void sequenceGap() {
        c(BOB).dropNext = true;
        op(ALICE, rect(ALICE));
        op(ALICE, rect(ALICE));
        check(c(BOB).gaps == 1, "gap detected");
        now += SyncServer.RESYNC_MIN_MS;
        server.requestResync(BOB);
        long n = store.all().stream().filter(e -> !e.deleted && Rules.canSee(BOB, e, (o, v) -> false)).count();
        check(c(BOB).elements.size() == n, "resync after gap");
        pass("lost/out-of-order message", "per-player sequence numbers expose the gap; the client's resync request repairs it");
    }

    static void liveRelay() {
        Element held = rect(ALICE), free = rect(ALICE);
        op(ALICE, held, free);
        acquire(ALICE, held);
        Element moved = store.get(held.id).copy();
        moved.x = 50;
        Element sneaky = store.get(free.id).copy();
        sneaky.x = 77;
        now += 100;
        server.live(ALICE, new Live(DIM, 3, 4, true, Element.Visibility.PUBLIC, List.of(moved, sneaky), new int[]{-1, -1}, new float[]{0, 0, 1, 1}));
        Live got = c(BOB).live.get(ALICE);
        check(got.ghosts().size() == 1 && got.ghosts().get(0).x == 50, "only leased ghosts relayed");
        Element pen = rect(ALICE);
        pen.type = Element.Type.FREEDRAW;
        pen.w = pen.h = 0;
        pen.pts = new float[]{0, 0, 1, 1};
        now += 100;
        server.live(ALICE, new Live(DIM, 0, 0, true, Element.Visibility.PUBLIC, List.of(pen), new int[]{-1}, new float[0]));
        Element more = pen.copy();
        more.pts = new float[]{2, 2};
        now += 100;
        server.live(ALICE, new Live(DIM, 0, 0, true, Element.Visibility.PUBLIC, List.of(more), new int[]{2}, new float[0]));
        check(c(BOB).live.get(ALICE).appendFrom()[0] == 2, "append relayed");
        Element bad = more.copy();
        bad.strokeWidth = 0.0001f;
        now += 100;
        server.live(ALICE, new Live(DIM, 0, 0, true, Element.Visibility.PUBLIC, List.of(bad), new int[]{3}, new float[]{Float.NaN, 0}));
        check(c(BOB).live.get(ALICE).appendFrom()[0] == 2, "invalid append / NaN laser dropped");
        now += 100;
        server.live(ALICE, new Live(DIM, 0, 0, true, Element.Visibility.PRIVATE, List.of(), new int[0], new float[0]));
        check(!c(BOB).live.containsKey(ALICE), "private live hidden");
        server.release(ALICE, List.of());
        pass("live relay", "ghosts only for leased (or new) elements, appends validated and relayed, private cursors withheld");
    }

    static void operators() {
        Element r = rect(ALICE);
        op(ALICE, r);
        check(acquire(BOB, r).equals("granted"), "Bob holds Alice's rect");
        ops.add(OPER);
        join(OPER, "Op");
        check(acquire(OPER, r).equals("granted") && last(c(BOB).revoked).equals("taken over by an operator"), "op takes over");
        check(server.forceRelease(OPER) == 1 && last(c(OPER).revoked).equals("released by an operator"), "force release");
        server.leave(OPER);
        clients.remove(OPER);
        ops.clear();
        pass("operators / owners break leases", "an op (or the owner) takes a held lease over with a notice; /mapsketch release frees leases");
    }

    static void dimensionChange() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(ALICE, r);
        now += 100;
        server.live(ALICE, new Live(DIM, 1, 1, true, Element.Visibility.PUBLIC, List.of(), new int[0], new float[0]));
        server.interrupt(ALICE, "dimension changed");
        check(last(c(ALICE).revoked).equals("dimension changed") && !c(BOB).live.containsKey(ALICE)
                && c(BOB).leases.get(ALICE).isEmpty(), "interrupt");
        pass("dimension change / death", "leases revoked, live state dropped for everyone");
    }

    static void teamChange() {
        teams.put(ALICE, "red");
        teams.put(BOB, "red");
        Element r = rect(ALICE);
        r.visibility = Element.Visibility.TEAM;
        op(ALICE, r);
        check(c(BOB).elements.containsKey(r.id), "teammate sees it");
        acquire(BOB, r);
        teams.put(BOB, "blue");
        server.resync(Set.of(ALICE, BOB));
        check(!c(BOB).elements.containsKey(r.id) && last(c(BOB).revoked).equals("permission lost"), "left the team");
        teams.clear();
        pass("team change", "leaving the team revokes the lease and the element vanishes");
    }

    static void serverRestart() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(ALICE, r);
        server = new SyncServer(store, ENV, OUT);
        server.join(ALICE, "Alice");
        server.join(BOB, "Bob");
        check(acquire(BOB, r).equals("granted") && store.get(r.id) != null, "no leases after restart, data kept");
        pass("server restart", "elements persist, leases do not");
    }

    // ---- review findings ----

    static void tombstoneSpam() {
        Element ghost = rect(BOB);
        int before = store.tombstones();
        check(op(BOB, deleted(ghost)).equals("ok") && store.tombstones() == before && store.get(ghost.id) == null, "unknown delete no-op");
        Element alices = rect(ALICE);
        op(ALICE, alices);
        op(ALICE, deleted(next(ALICE, alices.id)));
        maxPerPlayer = 100;
        for (int i = 0; i < 150; i++) {
            now += 5;
            Element e = rect(BOB);
            op(BOB, e);
            op(BOB, deleted(next(BOB, e.id)));
        }
        long bobs = store.all().stream().filter(e -> e.deleted && BOB.equals(e.owner)).count();
        check(bobs <= 100 && store.get(alices.id) != null && store.get(alices.id).deleted, "Bob's own oldest pruned, Alice's kept: " + bobs);
        maxPerPlayer = 2000;
        pass("tombstone spam", "unknown deletes store nothing; a delete loop only prunes that player's own oldest tombstones");
    }

    static void throttles() {
        now += 5000;
        int resets = c(BOB).resets;
        for (int i = 0; i < 5; i++) server.requestResync(BOB);
        check(c(BOB).resets == resets + 1, "five requests, one resync");
        server.tick();
        check(c(BOB).resets == resets + 1, "pending waits");
        now += SyncServer.RESYNC_MIN_MS;
        server.tick();
        check(c(BOB).resets == resets + 2, "pending served once after the throttle");
        Element r = rect(ALICE);
        op(ALICE, r);
        int denied = 0;
        for (int i = 0; i < 40; i++) {
            server.acquire(BOB, i, List.of(r.id), List.of());
            if (last(c(BOB).replies).equals("denied:rate limit")) denied++;
        }
        server.release(BOB, List.of());
        check(denied >= 15, "lease requests rate limited: " + denied);
        now += 100;
        server.live(ALICE, new Live(DIM, 1, 1, true, Element.Visibility.PUBLIC, List.of(), new int[0], new float[0]));
        check(c(BOB).live.containsKey(ALICE), "active relayed");
        // Inactive right after an active update (inside the rate gate) still gets through, and carries nothing.
        server.live(ALICE, new Live(DIM, 1, 1, false, Element.Visibility.PUBLIC, List.of(rect(ALICE)), new int[]{-1}, new float[0]));
        check(!c(BOB).live.containsKey(ALICE), "inactive bypasses the gate, relayed as inactive only");
        now += 100;
        int msgs = c(BOB).liveMsgs;
        for (int i = 0; i < 10; i++) server.live(ALICE, new Live(DIM, 1, 1, true, Element.Visibility.PUBLIC, List.of(), new int[0], new float[0]));
        check(c(BOB).liveMsgs - msgs == 1, "a burst in one instant relays once: " + (c(BOB).liveMsgs - msgs));
        pass("throttles", "resync once per 2 s with one coalesced follow-up; lease requests and live updates rate-limited; inactive never dropped");
    }

    static void leaseCaps() {
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i <= SyncServer.MAX_LEASES; i++) many.add(UUID.randomUUID());
        now += 100;
        server.acquire(BOB, 7, many, List.of());
        check(last(c(BOB).replies).equals("denied:too many"), "more than the cap denied");
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(BOB, r);
        boolean revoked = false;
        for (int t = 0; t < 70 && !revoked; t += 2) {
            now += 2000;
            server.renew(BOB);
            server.tick();
            revoked = last(c(BOB).revoked).equals("expired");
        }
        check(revoked, "lifetime cap ends renewals");
        // Nobody else asked: the holder may take it again at once.
        check(acquire(BOB, r).equals("granted"), "uncontested re-acquire");
        server.release(BOB, List.of());
        pass("lease caps", "at most " + SyncServer.MAX_LEASES + " ids; renewals stop at the 60 s lifetime");
    }

    static void reacquireCooldown() {
        join(CAROL, "Carol");
        Element r = rect(ALICE);
        op(ALICE, r);
        check(acquire(BOB, r).equals("granted"), "Bob holds");
        check(acquire(CAROL, r).equals("denied:held:Bob"), "Carol wants it too");
        int revokedBefore = c(BOB).revoked.size();
        while (c(BOB).revoked.size() == revokedBefore) {
            now += 2000;
            server.renew(BOB);
            server.tick();
        }
        check(last(c(BOB).revoked).equals("expired"), "lifetime cap");
        check(acquire(BOB, r).equals("denied:cooldown"), "contested: Bob waits");
        check(acquire(CAROL, r).equals("granted"), "Carol gets her turn");
        server.release(CAROL, List.of());
        now += SyncServer.COOLDOWN_MS;
        check(acquire(BOB, r).equals("granted"), "after the cooldown Bob may again");
        server.release(BOB, List.of());
        server.leave(CAROL);
        clients.remove(CAROL);
        pass("re-acquire cooldown", "losing a contested lease to the lifetime cap (or a take-over) blocks re-grabbing for 3 s");
    }

    static void takeoverRejectsHolder() {
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(BOB, r);
        Element inflight = next(BOB, r.id);
        inflight.x = 40;
        check(acquire(ALICE, r).equals("granted") && last(c(BOB).revoked).equals("taken over by the owner"), "owner takes over");
        check(op(BOB, inflight).equals("rejected:held:Alice") && store.get(r.id).x == 0, "holder's in-flight op rejected");
        check(acquire(BOB, r).startsWith("denied"), "and he cannot grab it straight back");
        server.release(ALICE, List.of());
        // Take-over by editing: the holder's in-flight op is made from the old state, so it is stale.
        now += SyncServer.COOLDOWN_MS;
        acquire(BOB, r);
        Element inflight2 = next(BOB, r.id);
        inflight2.x = 41;
        Element owner = next(ALICE, r.id);
        owner.x = 5;
        check(op(ALICE, owner).equals("ok"), "owner edit takes over");
        check(op(BOB, inflight2).equals("rejected:stale") && store.get(r.id).x == 5, "stale in-flight op rejected");
        pass("take-over vs in-flight ops", "after an owner/op take-over the old holder's queued ops are refused (held, or a stale base)");
    }

    static void staleHolderOutline() {
        join(CAROL, "Carol");
        Element r = rect(ALICE);
        op(ALICE, r);
        acquire(BOB, r);
        check(c(CAROL).leases.get(BOB).contains(r.id), "Carol sees Bob holding it");
        Element priv = next(ALICE, r.id);
        priv.visibility = Element.Visibility.PRIVATE;
        op(ALICE, priv);
        check(!c(CAROL).elements.containsKey(r.id) && !c(CAROL).leases.getOrDefault(BOB, Set.of()).contains(r.id),
                "Carol lost sight of it and still got the lease removal");
        server.leave(CAROL);
        clients.remove(CAROL);
        pass("stale holder outline", "lease removals reach every peer that was told about the lease, even after it lost sight of the element");
    }

    static void groupInjection() {
        UUID g = UUID.randomUUID();
        Element mine = rect(ALICE);
        mine.groups = new UUID[]{g};
        op(ALICE, mine);
        Element trap = rect(BOB);
        trap.groups = new UUID[]{g};
        trap.visibility = Element.Visibility.PRIVATE;
        trap.locked = true;
        op(BOB, trap);
        check(acquire(ALICE, mine).equals("granted"), "a private member planted in her group does not block Alice");
        server.release(ALICE, List.of());
        Element secret = rect(ALICE);
        secret.visibility = Element.Visibility.PRIVATE;
        op(ALICE, secret);
        acquire(ALICE, secret);
        Element probe = from(store.get(secret.id));
        check(op(BOB, probe).equals("rejected:not visible"), "visibility checked before the lease");
        server.release(ALICE, List.of());
        pass("group injection", "expansion only takes members the requester can see and edit; invisible elements answer \"not visible\"");
    }

    static void rateLimitNoAmplification() {
        Element big = rect(ALICE);
        big.text = "x".repeat(400);
        op(ALICE, big);
        editBytes = 3000;
        server.join(BOB, "Bob");
        int forced = c(BOB).forced;
        List<Element> spam = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Element e = rect(BOB);
            e.text = "y".repeat(400);
            spam.add(e);
        }
        server.edit(BOB, 99, spam);
        check(last(c(BOB).acks).equals("rejected:rate limit") && c(BOB).forced == forced, "rate-limited rejects resend nothing");
        int resets = c(BOB).resets;
        now += SyncServer.RESYNC_MIN_MS;
        server.tick();
        check(c(BOB).resets == resets + 1, "one throttled full resync instead");
        // Repeating one stale element in an op resyncs it once, not per copy.
        editBytes = 1 << 20;
        server.join(BOB, "Bob");
        forced = c(BOB).forced;
        List<Element> dup = new ArrayList<>();
        Element base = c(BOB).elements.get(big.id).copy();
        Element first = from(base);
        dup.add(first);
        for (int i = 0; i < 20; i++) dup.add(from(base));
        server.edit(BOB, 100, dup);
        check(c(BOB).forced == forced + 1, "stale duplicates resynced once: " + (c(BOB).forced - forced));
        pass("rate-limit rejects", "no per-element resync on rate limiting (one throttled full resync instead); a repeated stale element resyncs once");
    }
}
