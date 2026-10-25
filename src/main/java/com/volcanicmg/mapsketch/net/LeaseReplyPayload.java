package com.volcanicmg.mapsketch.net;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: answer to a lease request, or (with {@code requestId = -1}) leases taken away.
 *
 * @param reason why denied/revoked; {@code held:<name>} when someone else holds it
 */
public record LeaseReplyPayload(int requestId, boolean granted, List<UUID> ids, String reason) implements CustomPacketPayload {
    public static final Type<LeaseReplyPayload> TYPE = Wire.type("lease_reply");
    public static final StreamCodec<RegistryFriendlyByteBuf, LeaseReplyPayload> CODEC =
            CustomPacketPayload.codec(LeaseReplyPayload::write, LeaseReplyPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeBoolean(granted);
        Wire.writeIds(buf, ids);
        buf.writeUtf(reason == null ? "" : reason, 128);
    }

    private static LeaseReplyPayload read(FriendlyByteBuf buf) {
        return new LeaseReplyPayload(buf.readVarInt(), buf.readBoolean(), Wire.readIds(buf, 100_000), buf.readUtf(128));
    }

    @Override
    public Type<LeaseReplyPayload> type() {
        return TYPE;
    }
}
