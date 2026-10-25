package com.volcanicmg.mapsketch.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Per-stamp-id cache of resolved art (bounded by the registries): hits and misses are both cached, and a loader that
 * throws disables only that id (logged once) instead of every stamp. {@link #clear()} forgets everything, e.g. after
 * a resource reload. Pure.
 */
public final class StampCache<T> {
    /** Resolves one id; null = no usable art (cached as a miss). */
    public interface Loader<T> {
        T load(String id) throws Exception;
    }

    private final Map<String, Optional<T>> resolved = new HashMap<>();
    private final Set<String> failed = new HashSet<>();

    /**
     * @param onFailure called once per id whose loader throws
     * @return the art, or null when missing or failed (the caller draws a plain tile)
     */
    public T get(String id, Loader<T> loader, Consumer<Throwable> onFailure) {
        if (failed.contains(id)) return null;
        Optional<T> hit = resolved.get(id);
        if (hit != null) return hit.orElse(null);
        try {
            T v = loader.load(id);
            resolved.put(id, Optional.ofNullable(v));
            return v;
        } catch (Throwable t) {
            failed.add(id);
            onFailure.accept(t);
            return null;
        }
    }

    public void clear() {
        resolved.clear();
        failed.clear();
    }

    /** @return ids remembered (hits, misses and failures) */
    public int size() {
        return resolved.size() + failed.size();
    }
}
