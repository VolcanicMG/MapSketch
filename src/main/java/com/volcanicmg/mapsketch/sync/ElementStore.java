package com.volcanicmg.mapsketch.sync;

import com.volcanicmg.mapsketch.model.Element;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every element including tombstones, keyed by id, with live counts per owner and dimension, and indexes of arrow
 * bindings (target → arrows) and outermost groups (group → members), so ops never scan the whole store. Pure.
 */
public final class ElementStore {
    private final Map<UUID, Element> elements = new HashMap<>();
    private final Map<UUID, Integer> perOwner = new HashMap<>();
    private final Map<String, Integer> perDim = new HashMap<>();
    private final Map<UUID, Set<UUID>> boundTo = new HashMap<>();
    private final Map<UUID, Set<UUID>> groupMembers = new HashMap<>();
    private int tombstones;
    private final Runnable onChange;

    /** @param onChange called after every mutation (marks saved data dirty) */
    public ElementStore(Runnable onChange) {
        this.onChange = onChange;
    }

    public Element get(UUID id) {
        return elements.get(id);
    }

    public Collection<Element> all() {
        return elements.values();
    }

    /** Stores {@code e}, replacing any element with its id. */
    public void put(Element e) {
        load(e);
        onChange.run();
    }

    /** Stores without notifying (saved data load). */
    public void load(Element e) {
        Element old = elements.put(e.id, e);
        if (old != null) unindex(old);
        index(e);
    }

    /** Removes one tombstone (a player's oldest, past their tombstone cap). */
    public void dropTombstone(UUID id) {
        Element e = elements.get(id);
        if (e == null || !e.deleted) return;
        drop(id);
        onChange.run();
    }

    /** Removes an element entirely (tombstone pruning). */
    private void drop(UUID id) {
        Element old = elements.remove(id);
        if (old != null) unindex(old);
    }

    public int ownedBy(UUID owner) {
        return perOwner.getOrDefault(owner, 0);
    }

    public int inDimension(String dim) {
        return perDim.getOrDefault(dim, 0);
    }

    public int tombstones() {
        return tombstones;
    }

    /** @return live arrows bound (start or end) to {@code target} */
    public List<Element> arrowsBoundTo(UUID target) {
        List<Element> out = new ArrayList<>();
        for (UUID id : boundTo.getOrDefault(target, Set.of())) {
            Element a = elements.get(id);
            if (a != null && !a.deleted) out.add(a);
        }
        return out;
    }

    /** @return live elements whose outermost group is {@code group} */
    public List<Element> groupMembers(UUID group) {
        List<Element> out = new ArrayList<>();
        for (UUID id : groupMembers.getOrDefault(group, Set.of())) {
            Element e = elements.get(id);
            if (e != null && !e.deleted) out.add(e);
        }
        return out;
    }

    /**
     * Drops tombstones older than {@code maxAgeMs}, and the oldest beyond {@code maxCount} (down to 90 %, so the sort
     * is rare). Tombstones only exist to stop stale edits resurrecting a delete.
     *
     * @return ids dropped
     */
    public List<UUID> prune(long now, long maxAgeMs, int maxCount) {
        List<UUID> dropped = new ArrayList<>();
        for (Element e : elements.values()) if (e.deleted && now - e.updated > maxAgeMs) dropped.add(e.id);
        dropped.forEach(this::drop);
        if (tombstones > maxCount) {
            List<Element> tomb = new ArrayList<>();
            for (Element e : elements.values()) if (e.deleted) tomb.add(e);
            tomb.sort(Comparator.comparingLong(e -> e.updated));
            int excess = tombstones - maxCount * 9 / 10;
            for (int i = 0; i < excess && i < tomb.size(); i++) {
                drop(tomb.get(i).id);
                dropped.add(tomb.get(i).id);
            }
        }
        if (!dropped.isEmpty()) onChange.run();
        return dropped;
    }

    private void index(Element e) {
        if (e.deleted) {
            tombstones++;
            return;
        }
        if (e.owner != null) perOwner.merge(e.owner, 1, Integer::sum);
        perDim.merge(e.dim, 1, Integer::sum);
        if (e.startBind != null) boundTo.computeIfAbsent(e.startBind, k -> new HashSet<>()).add(e.id);
        if (e.endBind != null) boundTo.computeIfAbsent(e.endBind, k -> new HashSet<>()).add(e.id);
        if (e.groups.length > 0) groupMembers.computeIfAbsent(e.groups[e.groups.length - 1], k -> new HashSet<>()).add(e.id);
    }

    private void unindex(Element e) {
        if (e.deleted) {
            tombstones--;
            return;
        }
        if (e.owner != null) perOwner.merge(e.owner, -1, Integer::sum);
        perDim.merge(e.dim, -1, Integer::sum);
        if (e.startBind != null) removeFrom(boundTo, e.startBind, e.id);
        if (e.endBind != null) removeFrom(boundTo, e.endBind, e.id);
        if (e.groups.length > 0) removeFrom(groupMembers, e.groups[e.groups.length - 1], e.id);
    }

    private static void removeFrom(Map<UUID, Set<UUID>> index, UUID key, UUID id) {
        Set<UUID> s = index.get(key);
        if (s == null) return;
        s.remove(id);
        if (s.isEmpty()) index.remove(key);
    }
}
