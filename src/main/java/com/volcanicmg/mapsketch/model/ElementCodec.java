package com.volcanicmg.mapsketch.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * The one element encoding, a byte array used on the wire and on disk. Plain java.io so the self-check runs without
 * Minecraft. Hard caps here only stop absurd payloads; the configurable limits live in {@link Rules#validate}.
 */
public final class ElementCodec {
    // 2 added baseNonce; 1 is still read (pre-release saves).
    private static final int FORMAT = 2;
    public static final int MAX_POINTS = 10_000, MAX_TEXT = 4096, MAX_GROUPS = 16, MAX_ORDER = 64;

    private ElementCodec() {
    }

    private static void write(DataOutputStream buf, Element e) throws IOException {
        buf.writeByte(FORMAT);
        uuid(buf, e.id);
        utf(buf, e.dim, 256);
        buf.writeByte(e.type.ordinal());
        buf.writeDouble(e.x);
        buf.writeDouble(e.z);
        buf.writeDouble(e.w);
        buf.writeDouble(e.h);
        buf.writeInt(e.pts.length);
        for (float f : e.pts) buf.writeFloat(f);
        buf.writeFloat(e.angle);
        buf.writeInt(e.stroke);
        buf.writeInt(e.fill);
        buf.writeByte(e.fillStyle.ordinal());
        buf.writeFloat(e.strokeWidth);
        buf.writeByte(e.strokeStyle.ordinal());
        buf.writeByte(e.roughness);
        buf.writeBoolean(e.round);
        buf.writeByte(e.opacity);
        buf.writeByte(e.arrowStart.ordinal());
        buf.writeByte(e.arrowEnd.ordinal());
        utf(buf, e.text, MAX_TEXT);
        buf.writeFloat(e.fontSize);
        buf.writeByte(e.align.ordinal());
        writeNullable(buf, e.startBind);
        writeNullable(buf, e.endBind);
        buf.writeInt(e.seed);
        buf.writeInt(e.groups.length);
        for (UUID g : e.groups) uuid(buf, g);
        utf(buf, e.order, MAX_ORDER);
        buf.writeInt(e.version);
        buf.writeInt(e.nonce);
        buf.writeInt(e.baseNonce);
        writeNullable(buf, e.owner);
        buf.writeByte(e.visibility.ordinal());
        buf.writeBoolean(e.locked);
        buf.writeBoolean(e.deleted);
        buf.writeLong(e.updated);
    }

    private static Element read(DataInputStream buf) throws IOException {
        int format = buf.readByte();
        if (format < 1 || format > FORMAT) throw new IllegalArgumentException("unknown element format");
        Element e = new Element();
        e.id = uuid(buf);
        e.dim = utf(buf, 256);
        e.type = pick(Element.Type.values(), buf.readByte());
        e.x = buf.readDouble();
        e.z = buf.readDouble();
        e.w = buf.readDouble();
        e.h = buf.readDouble();
        int n = buf.readInt();
        if (n < 0 || n > MAX_POINTS * 2 || (n & 1) != 0) throw new IllegalArgumentException("bad point count");
        e.pts = new float[n];
        for (int i = 0; i < n; i++) e.pts[i] = buf.readFloat();
        e.angle = buf.readFloat();
        e.stroke = buf.readInt();
        e.fill = buf.readInt();
        e.fillStyle = pick(Element.FillStyle.values(), buf.readByte());
        e.strokeWidth = buf.readFloat();
        e.strokeStyle = pick(Element.StrokeStyle.values(), buf.readByte());
        e.roughness = buf.readByte();
        e.round = buf.readBoolean();
        e.opacity = buf.readByte();
        e.arrowStart = pick(Element.Head.values(), buf.readByte());
        e.arrowEnd = pick(Element.Head.values(), buf.readByte());
        e.text = utf(buf, MAX_TEXT);
        e.fontSize = buf.readFloat();
        e.align = pick(Element.Align.values(), buf.readByte());
        e.startBind = readNullable(buf);
        e.endBind = readNullable(buf);
        e.seed = buf.readInt();
        int g = buf.readInt();
        if (g < 0 || g > MAX_GROUPS) throw new IllegalArgumentException("too many groups");
        e.groups = new UUID[g];
        for (int i = 0; i < g; i++) e.groups[i] = uuid(buf);
        e.order = utf(buf, MAX_ORDER);
        e.version = buf.readInt();
        e.nonce = buf.readInt();
        if (format >= 2) e.baseNonce = buf.readInt();
        e.owner = readNullable(buf);
        e.visibility = pick(Element.Visibility.values(), buf.readByte());
        e.locked = buf.readBoolean();
        e.deleted = buf.readBoolean();
        e.updated = buf.readLong();
        return e;
    }

    public static byte[] toBytes(Element e) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size(e));
        try {
            write(new DataOutputStream(bytes), e);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    /** @throws IllegalArgumentException on a malformed or oversized element */
    public static Element fromBytes(byte[] bytes) {
        try {
            return read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException ex) {
            throw new IllegalArgumentException("truncated element", ex);
        }
    }

    /** @return encoded size in bytes, for payload splitting and rate limits */
    public static int size(Element e) {
        return 140 + e.pts.length * 4 + e.text.length() * 3 + e.groups.length * 16 + e.dim.length() + e.order.length();
    }

    private static <T> T pick(T[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("bad enum");
        return values[ordinal];
    }

    private static void writeNullable(DataOutputStream buf, UUID id) throws IOException {
        buf.writeBoolean(id != null);
        if (id != null) uuid(buf, id);
    }

    private static UUID readNullable(DataInputStream buf) throws IOException {
        return buf.readBoolean() ? uuid(buf) : null;
    }

    private static void uuid(DataOutputStream buf, UUID id) throws IOException {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream buf) throws IOException {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static void utf(DataOutputStream buf, String s, int max) throws IOException {
        if (s.length() > max) throw new IllegalArgumentException("string too long");
        buf.writeUTF(s);
    }

    private static String utf(DataInputStream buf, int max) throws IOException {
        String s = buf.readUTF();
        if (s.length() > max) throw new IllegalArgumentException("string too long");
        return s;
    }
}
