package com.volcanicmg.mapsketch.net;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: forget these elements (deleted, or no longer visible to you). {@code seq} as in {@link ElementsPayload}. */
public record RemovePayload(long seq, List<UUID> ids) implements CustomPacketPayload {
    public static final Type<RemovePayload> TYPE = Wire.type("remove");
    public static final StreamCodec<RegistryFriendlyByteBuf, RemovePayload> CODEC =
            CustomPacketPayload.codec(RemovePayload::write, RemovePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(seq);
        Wire.writeIds(buf, ids);
    }

    private static RemovePayload read(FriendlyByteBuf buf) {
        return new RemovePayload(buf.readVarLong(), Wire.readIds(buf, 1_000_000));
    }

    @Override
    public Type<RemovePayload> type() {
        return TYPE;
    }
}
