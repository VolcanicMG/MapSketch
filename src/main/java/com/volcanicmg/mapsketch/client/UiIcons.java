package com.volcanicmg.mapsketch.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.render.IconAtlas;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;

/**
 * Draws the UI icons from the prebuilt atlas (Excalidraw / Tabler line icons, see icons/LICENSE-*.txt): linear
 * filtering with mipmaps, tinted, at 65% of the button's short side, centred.
 */
final class UiIcons {
    /** Ink colours: normal, selected (the accent's darker shade), disabled. */
    static final int NORMAL = 0xFF2B2B2B, SELECTED = 0xFF364FC7, DISABLED = 0xFF9AA0A6;
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(MapSketch.MODID, "textures/gui/icons.png");
    private static final ResourceLocation MANIFEST = ResourceLocation.fromNamespaceAndPath(MapSketch.MODID, "icons/atlas.txt");
    private static Map<String, IconAtlas.Icon> icons = Map.of();
    private static boolean registered;

    private UiIcons() {
    }

    /** @return true if {@code name} (optionally suffixed "@m" = mirrored) is in the atlas */
    static boolean has(String name) {
        ensure();
        return icons.containsKey(base(name));
    }

    private static String base(String name) {
        return name.endsWith("@m") ? name.substring(0, name.length() - 2) : name;
    }

    private static void ensure() {
        if (registered) return;
        registered = true;
        Minecraft.getInstance().getTextureManager().register(TEX, new Atlas());
    }

    /**
     * Draws icon {@code name} centred in the box. Draws immediately, so flush batched GUI fills under it first.
     *
     * @param name atlas id; an "@m" suffix mirrors it horizontally (start arrowheads)
     */
    static void draw(GuiGraphics g, String name, float x, float y, float w, float h, int color) {
        ensure();
        IconAtlas.Icon ic = icons.get(base(name));
        if (ic == null) return;
        float s = Math.min(w, h) * 0.65f;
        // Wide icons (the 40x20 arrowheads) keep their height and grow sideways within the button.
        s = Math.max(s, Math.min(s * ic.aspect(), w * 0.85f));
        float x0 = x + (w - s) / 2, y0 = y + (h - s) / 2;
        float tw = IconAtlas.COLS * IconAtlas.CELL, th = IconAtlas.height(icons.size());
        float u0 = ic.cellX() / tw, u1 = (ic.cellX() + IconAtlas.CELL) / tw, v0 = ic.cellY() / th, v1 = (ic.cellY() + IconAtlas.CELL) / th;
        if (name.endsWith("@m")) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        RenderSystem.setShaderTexture(0, TEX);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(m, x0, y0, 0).setUv(u0, v0).setColor(color);
        b.addVertex(m, x0, y0 + s, 0).setUv(u0, v1).setColor(color);
        b.addVertex(m, x0 + s, y0 + s, 0).setUv(u1, v1).setColor(color);
        b.addVertex(m, x0 + s, y0, 0).setUv(u1, v0).setColor(color);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** The atlas texture with box-filtered mip levels (white ink, so only alpha is averaged). Reloads with resources. */
    private static final class Atlas extends AbstractTexture {
        @Override
        public void load(ResourceManager rm) throws IOException {
            try (InputStream in = rm.open(MANIFEST)) {
                icons = IconAtlas.parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
            }
            NativeImage[] mips = new NativeImage[IconAtlas.MIP_LEVELS + 1];
            try (InputStream in = rm.open(TEX)) {
                mips[0] = NativeImage.read(in);
            }
            for (int l = 1; l < mips.length; l++) {
                NativeImage p = mips[l - 1], n = new NativeImage(p.getWidth() / 2, p.getHeight() / 2, false);
                for (int y = 0; y < n.getHeight(); y++)
                    for (int x = 0; x < n.getWidth(); x++) {
                        int a = (p.getPixelRGBA(2 * x, 2 * y) >>> 24) + (p.getPixelRGBA(2 * x + 1, 2 * y) >>> 24)
                                + (p.getPixelRGBA(2 * x, 2 * y + 1) >>> 24) + (p.getPixelRGBA(2 * x + 1, 2 * y + 1) >>> 24);
                        n.setPixelRGBA(x, y, (a + 2) / 4 << 24 | 0xFFFFFF);
                    }
                mips[l] = n;
            }
            TextureUtil.prepareImage(getId(), IconAtlas.MIP_LEVELS, mips[0].getWidth(), mips[0].getHeight());
            for (int l = 0; l < mips.length; l++)
                mips[l].upload(l, 0, 0, 0, 0, mips[l].getWidth(), mips[l].getHeight(), true, true, true, true);
        }
    }
}
