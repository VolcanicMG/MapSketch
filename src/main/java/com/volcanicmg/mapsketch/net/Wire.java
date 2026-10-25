package com.volcanicmg.mapsketch.net;

import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.ElementCodec;
import com.volcanicmg.mapsketch.sync.Live;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Shared payload helpers: element lists with count and size caps. */
final class Wire {
    static final int MAX_ELEMENT_BYTES = 64 * 1024;

    private Wire() {
    }

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MapSketch.MODID, path));
    }

    static void writeElements(FriendlyByteBuf buf, List<Element> list) {
        buf.writeVarInt(list.size());
        for (Element e : list) buf.writeByteArray(ElementCodec.toBytes(e));
    }

    static List<Element> readElements(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many elements");
        List<Element> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ElementCodec.fromBytes(buf.readByteArray(MAX_ELEMENT_BYTES)));
        return out;
    }

    static void writeIds(FriendlyByteBuf buf, List<UUID> ids) {
        buf.writeVarInt(ids.size());
        for (UUID id : ids) buf.writeUUID(id);
    }

    static List<UUID> readIds(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many ids");
        List<UUID> ids = new ArrayList<>(Math.min(n, 4096));
        for (int i = 0; i < n; i++) ids.add(buf.readUUID());
        return ids;
    }

    static void writeLive(FriendlyByteBuf buf, Live l) {
        buf.writeUtf(l.dim(), 256);
        buf.writeDouble(l.x());
        buf.writeDouble(l.z());
        buf.writeBoolean(l.active());
        buf.writeEnum(l.visibility());
        writeElements(buf, l.ghosts());
        buf.writeVarIntArray(l.appendFrom());
        buf.writeVarInt(l.laser().length);
        for (float f : l.laser()) buf.writeFloat(f);
    }

    static Live readLive(FriendlyByteBuf buf) {
        String dim = buf.readUtf(256);
        double x = buf.readDouble(), z = buf.readDouble();
        boolean active = buf.readBoolean();
        Element.Visibility vis = buf.readEnum(Element.Visibility.class);
        List<Element> ghosts = readElements(buf, Live.MAX_GHOSTS);
        int[] append = buf.readVarIntArray(Live.MAX_GHOSTS);
        int n = buf.readVarInt();
        if (n < 0 || n > Live.MAX_LASER * 2 || (n & 1) != 0) throw new IllegalArgumentException("bad laser");
        float[] laser = new float[n];
        for (int i = 0; i < n; i++) laser[i] = buf.readFloat();
        return new Live(dim, x, z, active, vis, ghosts, append, laser);
    }
}
