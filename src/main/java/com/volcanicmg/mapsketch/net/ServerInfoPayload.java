package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.model.Element;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client on login: defaults and limits the editor should respect. */
public record ServerInfoPayload(Element.Visibility defaultVisibility, boolean teams, boolean teamCanEdit, boolean op,
                                int maxPoints, int maxText) implements CustomPacketPayload {
    public static final Type<ServerInfoPayload> TYPE = Wire.type("info");
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfoPayload> CODEC =
            CustomPacketPayload.codec(ServerInfoPayload::write, ServerInfoPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(defaultVisibility);
        buf.writeBoolean(teams);
        buf.writeBoolean(teamCanEdit);
        buf.writeBoolean(op);
        buf.writeVarInt(maxPoints);
        buf.writeVarInt(maxText);
    }

    private static ServerInfoPayload read(FriendlyByteBuf buf) {
        return new ServerInfoPayload(buf.readEnum(Element.Visibility.class), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<ServerInfoPayload> type() {
        return TYPE;
    }
}
