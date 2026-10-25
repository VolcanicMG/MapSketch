package com.volcanicmg.mapsketch.sync;

import com.volcanicmg.mapsketch.model.Element;
import java.util.List;
import java.util.UUID;

/** Server-to-client messages of {@link SyncServer}; the Minecraft adapter turns them into payloads. */
public interface Outbox {
    /** Element upserts. {@code reset}: drop everything first; {@code force}: replace regardless of version. */
    void elements(UUID to, long seq, boolean reset, boolean force, List<Element> elements);

    /** Forget these ids (deleted, or no longer visible). */
    void remove(UUID to, long seq, List<UUID> ids);

    /**
     * Result of op {@code opId}: rejected ids (resynced just before) and the versions the server assigned to the
     * accepted ones.
     */
    void ack(UUID to, int opId, List<UUID> rejected, String reason, List<UUID> accepted, int[] versions);

    /** Answer to a lease request: the ids granted, or the reason ({@code held:<name>}) when denied. */
    void leaseReply(UUID to, int requestId, boolean granted, List<UUID> ids, String reason);

    /** The server took leases away (expired, permission lost, taken over by the owner or an operator, ...). */
    void leaseRevoked(UUID to, List<UUID> ids, String reason);

    /**
     * Change in what {@code holder} is editing, as far as {@code to} may see. {@code reset}: {@code added} is the
     * holder's whole set (join, resync).
     */
    void leases(UUID to, UUID holder, String name, List<UUID> added, List<UUID> removed, boolean reset);

    void live(UUID to, UUID from, String name, Live live);
}
