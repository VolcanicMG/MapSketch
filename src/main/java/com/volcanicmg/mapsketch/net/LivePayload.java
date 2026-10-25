package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.sync.Live;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Live state both ways: client to server with {@code from = null}; the server fills in the sender and relays. */
public record LivePayload(UUID from, String name, Live live) implements CustomPacketPayload {
    public static final Type<LivePayload> TYPE = Wire.type("live");
    public static final StreamCodec<RegistryFriendlyByteBuf, LivePayload> CODEC =
            CustomPacketPayload.codec(LivePayload::write, LivePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(from != null);
        if (from != null) {
            buf.writeUUID(from);
            buf.writeUtf(name, 64);
        }
        Wire.writeLive(buf, live);
    }

    private static LivePayload read(FriendlyByteBuf buf) {
        UUID from = null;
        String name = "";
        if (buf.readBoolean()) {
            from = buf.readUUID();
            name = buf.readUtf(64);
        }
        return new LivePayload(from, name, Wire.readLive(buf));
    }

    @Override
    public Type<LivePayload> type() {
        return TYPE;
    }
}
