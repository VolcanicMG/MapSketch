package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.sync.SyncServer;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: acquire (all of {@code ids} or none; {@code optional} ids where free), renew (all held) or
 * release edit leases. Both lists together are capped at {@link SyncServer#MAX_LEASES}.
 */
public record LeasePayload(int requestId, Action action, List<UUID> ids, List<UUID> optional) implements CustomPacketPayload {
    public enum Action { ACQUIRE, RENEW, RELEASE }

    public static final Type<LeasePayload> TYPE = Wire.type("lease");
    public static final StreamCodec<RegistryFriendlyByteBuf, LeasePayload> CODEC =
            CustomPacketPayload.codec(LeasePayload::write, LeasePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeEnum(action);
        Wire.writeIds(buf, ids);
        Wire.writeIds(buf, optional);
    }

    private static LeasePayload read(FriendlyByteBuf buf) {
        return new LeasePayload(buf.readVarInt(), buf.readEnum(Action.class), Wire.readIds(buf, SyncServer.MAX_LEASES),
                Wire.readIds(buf, SyncServer.MAX_LEASES));
    }

    @Override
    public Type<LeasePayload> type() {
        return TYPE;
    }
}
