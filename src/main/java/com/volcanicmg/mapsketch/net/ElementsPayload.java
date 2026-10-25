package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.model.Element;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client element upserts.
 *
 * @param seq   per-player message number; a gap means a lost message and triggers a resync
 * @param reset drop everything known before applying (first chunk of a full sync)
 * @param force replace regardless of version (resync after a rejected edit)
 */
public record ElementsPayload(long seq, boolean reset, boolean force, List<Element> elements) implements CustomPacketPayload {
    public static final Type<ElementsPayload> TYPE = Wire.type("elements");
    public static final StreamCodec<RegistryFriendlyByteBuf, ElementsPayload> CODEC =
            CustomPacketPayload.codec(ElementsPayload::write, ElementsPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(seq);
        buf.writeBoolean(reset);
        buf.writeBoolean(force);
        Wire.writeElements(buf, elements);
    }

    private static ElementsPayload read(FriendlyByteBuf buf) {
        return new ElementsPayload(buf.readVarLong(), buf.readBoolean(), buf.readBoolean(), Wire.readElements(buf, 100_000));
    }

    @Override
    public Type<ElementsPayload> type() {
        return TYPE;
    }
}
