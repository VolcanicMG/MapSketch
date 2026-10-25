package com.volcanicmg.mapsketch.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.render.PixelArt;
import com.volcanicmg.mapsketch.render.Tessellator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * Where a stamp's pixels come from, as textured layers: pixel symbols from our baked tone-mask texture (four layers
 * tinted outline/shade/fill/highlight), map icons from the vanilla {@code map_decorations} atlas, items from their
 * flat sprite in the block/item atlas. Every stamp is drawn as flat quads (no item-model rendering). Sprites are
 * cached per id (bounded by the registries) and cleared on resource reload.
 */
final class StampArt {
    /** One textured quad over the element box, drawn in {@code argb}. */
    record Layer(ResourceLocation atlas, float u0, float v0, float u1, float v1, int argb) {
    }

    static final ResourceLocation SYMBOLS = ResourceLocation.fromNamespaceAndPath(MapSketch.MODID, "stamp_symbols");
    private static final ResourceLocation DECO_ATLAS = ResourceLocation.withDefaultNamespace("textures/atlas/map_decorations.png");
    /** One sprite layer of an item or map icon, with its tint (opaque white = untinted). */
    private record Tinted(TextureAtlasSprite sprite, int argb) {
    }

    // Per stamp id (bounded by the registries), cleared on resource reload.
    private static final Map<String, List<Tinted>> sprites = new HashMap<>();
    private static List<String> symbolRows;
    private static boolean failed;

    private StampArt() {
    }

    /** Resource reload: atlases were rebuilt, sprites move. */
    static void clear() {
        sprites.clear();
    }

    /** @return the layers of a stamp in its current colours, or null (draw a tile instead) */
    static List<Layer> layers(Element e) {
        if (failed) return null;
        try {
            if (e.stamp.startsWith("sym:")) return symbol(e.stamp.substring(4), e.stroke, e.opacity);
            List<Tinted> tinted = e.stamp.startsWith("item:") ? itemLayers(e.stamp) : deco(e.stamp);
            if (tinted == null) return null;
            List<Layer> out = new ArrayList<>(tinted.size());
            for (Tinted t : tinted) {
                TextureAtlasSprite s = t.sprite();
                out.add(new Layer(s.atlasLocation(), s.getU0(), s.getV0(), s.getU1(), s.getV1(), Tessellator.withOpacity(t.argb(), e.opacity)));
            }
            return out;
        } catch (Throwable t) {
            failed = true;
            MapSketch.LOGGER.error("MapSketch cannot texture stamps; drawing tiles instead", t);
            return null;
        }
    }

    /** Four tone layers of a pixel symbol: column {@code tone} of our mask texture, row of the symbol. */
    private static List<Layer> symbol(String name, int stroke, int opacity) {
        int row = ensureSymbols().indexOf(name);
        if (row < 0) return null;
        int[] tones = PixelArt.tones(stroke & 0xFFFFFF);
        float cols = PixelArt.TONES.length(), rows = symbolRows.size();
        List<Layer> out = new ArrayList<>(4);
        for (int t = 0; t < tones.length; t++)
            out.add(new Layer(SYMBOLS, t / cols, row / rows, (t + 1) / cols, (row + 1) / rows,
                    Tessellator.withOpacity(0xFF000000 | tones[t], opacity)));
        return out;
    }

    /** Builds (once) the symbol texture: per symbol a row of four 16x16 white masks, one per tone; nearest filter. */
    private static List<String> ensureSymbols() {
        if (symbolRows != null) return symbolRows;
        List<String> names = new ArrayList<>(PixelArt.names());
        int n = PixelArt.SIZE, tones = PixelArt.TONES.length();
        NativeImage img = new NativeImage(n * tones, n * names.size(), true);
        img.fillRect(0, 0, img.getWidth(), img.getHeight(), 0);
        for (int r = 0; r < names.size(); r++) {
            String[] grid = PixelArt.grid(names.get(r));
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    int t = PixelArt.tone(grid, x, y);
                    if (t >= 0) img.setPixelRGBA(t * n + x, r * n + y, 0xFFFFFFFF);
                }
        }
        DynamicTexture tex = new DynamicTexture(img);
        tex.setFilter(false, false);
        Minecraft.getInstance().getTextureManager().register(SYMBOLS, tex);
        symbolRows = names;
        return names;
    }

    /** @return the map icon's sprite as one untinted layer, or null when missing */
    private static List<Tinted> deco(String stamp) {
        List<Tinted> cached = sprites.get(stamp);
        if (cached != null) return cached;
        if (!com.volcanicmg.mapsketch.model.Rules.DECORATIONS.contains(stamp.substring(5))) return null;
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(DECO_ATLAS);
        if (!(t instanceof TextureAtlas atlas)) return null;
        t.setFilter(false, false);
        TextureAtlasSprite s = atlas.getSprite(ResourceLocation.withDefaultNamespace(stamp.substring(5)));
        if (missing(s)) return null;
        List<Tinted> out = List.of(new Tinted(s, 0xFFFFFFFF));
        sprites.put(stamp, out);
        return out;
    }

    /**
     * An item's flat icon as layers, like the GUI item renderer draws a generated (flat) item model: its front-facing
     * quads in order, one per sprite and tint index, each coloured by the item's {@code ItemColors} when tinted (spawn
     * eggs, potions, leather, tipped arrows, firework stars, maps). Block and other 3D models fall back to their
     * particle icon, untinted.
     *
     * @return the layers, or null when the item has no usable texture
     */
    private static List<Tinted> itemLayers(String stamp) {
        List<Tinted> cached = sprites.get(stamp);
        if (cached != null) return cached;
        ItemStack st = SketchRenderer.stack(stamp);
        if (st == null) return null;
        BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(st, null, null, 0);
        List<Tinted> out = new ArrayList<>();
        if (!model.isGui3d()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            RandomSource rnd = RandomSource.create(42);
            List<BakedQuad> quads = new ArrayList<>(model.getQuads(null, null, rnd));
            quads.addAll(model.getQuads(null, net.minecraft.core.Direction.SOUTH, rnd));
            for (BakedQuad q : quads) {
                // Generated item models have a front (south) and a back face per layer; the front is what the GUI shows.
                if (q.getDirection() != net.minecraft.core.Direction.SOUTH || missing(q.getSprite())) continue;
                if (!seen.add(q.getSprite().contents().name() + "#" + q.getTintIndex())) continue;
                int color = q.isTinted()
                        ? 0xFF000000 | Minecraft.getInstance().getItemColors().getColor(st, q.getTintIndex()) & 0xFFFFFF
                        : 0xFFFFFFFF;
                out.add(new Tinted(q.getSprite(), color));
            }
        }
        if (out.isEmpty()) {
            TextureAtlasSprite p = model.getParticleIcon();
            if (missing(p)) return null;
            out.add(new Tinted(p, 0xFFFFFFFF));
        }
        out = List.copyOf(out);
        sprites.put(stamp, out);
        return out;
    }

    private static boolean missing(TextureAtlasSprite s) {
        return s == null || s.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }
}
