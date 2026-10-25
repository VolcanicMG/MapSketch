package com.volcanicmg.mapsketch.net;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: change in what {@code holder} is editing (that you can see). {@code reset}: {@code added} is
 * the whole set (join, resync).
 */
public record LeasesPayload(UUID holder, String name, List<UUID> added, List<UUID> removed, boolean reset)
        implements CustomPacketPayload {
    public static final Type<LeasesPayload> TYPE = Wire.type("leases");
    public static final StreamCodec<RegistryFriendlyByteBuf, LeasesPayload> CODEC =
            CustomPacketPayload.codec(LeasesPayload::write, LeasesPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(holder);
        buf.writeUtf(name, 64);
        Wire.writeIds(buf, added);
        Wire.writeIds(buf, removed);
        buf.writeBoolean(reset);
    }

    private static LeasesPayload read(FriendlyByteBuf buf) {
        return new LeasesPayload(buf.readUUID(), buf.readUtf(64), Wire.readIds(buf, 100_000), Wire.readIds(buf, 100_000),
                buf.readBoolean());
    }

    @Override
    public Type<LeasesPayload> type() {
        return TYPE;
    }
}
