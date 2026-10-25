package com.volcanicmg.mapsketch.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "I missed a message (sequence gap), send me everything again." */
public record ResyncPayload() implements CustomPacketPayload {
    public static final ResyncPayload INSTANCE = new ResyncPayload();
    public static final Type<ResyncPayload> TYPE = Wire.type("resync");
    public static final StreamCodec<ByteBuf, ResyncPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<ResyncPayload> type() {
        return TYPE;
    }
}
