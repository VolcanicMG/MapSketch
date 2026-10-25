package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.model.Element;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server op: element upserts, deletes as tombstones ({@code deleted = true}). Answered by
 * {@link AckPayload} with the same {@code opId}.
 */
public record EditPayload(int opId, List<Element> elements) implements CustomPacketPayload {
    public static final int MAX_ELEMENTS = 256;
    /** Kept under the vanilla 32 KiB serverbound custom payload limit. */
    public static final int MAX_BYTES = 24 * 1024;
    public static final Type<EditPayload> TYPE = Wire.type("edit");
    public static final StreamCodec<RegistryFriendlyByteBuf, EditPayload> CODEC =
            CustomPacketPayload.codec(EditPayload::write, EditPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(opId);
        Wire.writeElements(buf, elements);
    }

    private static EditPayload read(FriendlyByteBuf buf) {
        return new EditPayload(buf.readVarInt(), Wire.readElements(buf, MAX_ELEMENTS));
    }

    @Override
    public Type<EditPayload> type() {
        return TYPE;
    }
}
