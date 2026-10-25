package com.volcanicmg.mapsketch.server;

import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.ElementCodec;
import com.volcanicmg.mapsketch.model.FracIndex;
import com.volcanicmg.mapsketch.sync.ElementStore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Persists the {@link ElementStore} on the overworld as {@code mapsketch}, each element in its wire encoding. */
public final class SketchData extends SavedData {
    private static final String NAME = "mapsketch";
    // 2 = Excalidraw-style z-order keys. Older saves are re-keyed once on load.
    private static final int FORMAT = 2;
    public final ElementStore store = new ElementStore(this::setDirty);

    public static SketchData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(SketchData::new, SketchData::load), NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Element e : store.all()) list.add(new ByteArrayTag(ElementCodec.toBytes(e)));
        tag.put("elements", list);
        tag.putInt("format", FORMAT);
        return tag;
    }

    private static SketchData load(CompoundTag tag, HolderLookup.Provider registries) {
        SketchData data = new SketchData();
        for (Tag t : tag.getList("elements", Tag.TAG_BYTE_ARRAY)) {
            try {
                data.store.load(ElementCodec.fromBytes(((ByteArrayTag) t).getAsByteArray()));
            } catch (RuntimeException ex) {
                MapSketch.LOGGER.warn("Skipping unreadable drawing in saved data", ex);
            }
        }
        if (tag.getInt("format") < FORMAT) data.rekeyAll();
        return data;
    }

    /**
     * Pre-format-2 saves used another z-order key scheme; some of its keys even parse as new ones, so every element
     * is re-keyed, per dimension, in its old order (old keys compared as plain strings, ties by id).
     */
    private void rekeyAll() {
        java.util.Map<String, java.util.List<Element>> byDim = new java.util.HashMap<>();
        for (Element e : store.all()) byDim.computeIfAbsent(e.dim, k -> new java.util.ArrayList<>()).add(e);
        byDim.forEach((dim, list) -> {
            list.sort(java.util.Comparator.<Element, String>comparing(e -> e.order).thenComparing(e -> e.id));
            String key = null;
            for (Element e : list) {
                Element c = e.copy();
                c.order = key = FracIndex.between(key, null);
                // Old-rule versions (0 or MAX_VALUE) would wedge the element under server-assigned versions.
                c.version = 1;
                c.nonce = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                store.load(c);
            }
        });
        setDirty();
    }
}
