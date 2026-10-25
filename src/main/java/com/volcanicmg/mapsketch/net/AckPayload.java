package com.volcanicmg.mapsketch.net;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: op {@code opId} done; {@code rejected} ids were resynced to their authoritative state,
 * {@code accepted} ids were stored at the server-assigned {@code versions}.
 */
public record AckPayload(int opId, List<UUID> rejected, String reason, List<UUID> accepted, int[] versions)
        implements CustomPacketPayload {
    public static final Type<AckPayload> TYPE = Wire.type("ack");
    public static final StreamCodec<RegistryFriendlyByteBuf, AckPayload> CODEC =
            CustomPacketPayload.codec(AckPayload::write, AckPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(opId);
        Wire.writeIds(buf, rejected);
        buf.writeUtf(reason == null ? "" : reason, 128);
        Wire.writeIds(buf, accepted);
        buf.writeVarIntArray(versions);
    }

    private static AckPayload read(FriendlyByteBuf buf) {
        return new AckPayload(buf.readVarInt(), Wire.readIds(buf, 100_000), buf.readUtf(128), Wire.readIds(buf, 100_000),
                buf.readVarIntArray(100_000));
    }

    @Override
    public Type<AckPayload> type() {
        return TYPE;
    }
}
