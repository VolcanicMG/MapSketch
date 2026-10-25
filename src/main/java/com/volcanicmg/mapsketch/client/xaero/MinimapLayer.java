package com.volcanicmg.mapsketch.client.xaero;

import com.mojang.blaze3d.vertex.PoseStack;
import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.client.ClientStore;
import com.volcanicmg.mapsketch.client.SketchRenderer;
import com.volcanicmg.mapsketch.mixin.MinimapFboAccessor;
import com.volcanicmg.mapsketch.mixin.MinimapHandlerAccessor;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import xaero.common.HudMod;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/**
 * Drawings on Xaero's Minimap: one anchor element per frame in the IN_MINIMAP pass, which renders into the
 * rotated framebuffer Xaero then masks to the circle or square, so clipping is Xaero's. We apply the same rotation
 * and zoom the handler uses for its elements. Only loaded when Xaero's Minimap is installed.
 */
public final class MinimapLayer {
    private static Object registeredOn;
    private static boolean broken;

    private MinimapLayer() {
    }

    /** Registers with the FBO renderer's handler; again whenever Xaero rebuilds it. */
    public static void tick() {
        if (broken) return;
        try {
            HudMod mod = HudMod.INSTANCE;
            Minimap minimap = mod == null ? null : mod.getMinimap();
            MinimapFBORenderer fbo = minimap == null ? null : minimap.getMinimapFBORenderer();
            MinimapElementMapRendererHandler handler = fbo == null ? null : ((MinimapFboAccessor) fbo).mapsketch$handler();
            if (handler == null || handler == registeredOn) return;
            handler.add(new Renderer(handler));
            registeredOn = handler;
        } catch (Throwable t) {
            broken = true;
            MapSketch.LOGGER.error("MapSketch cannot hook Xaero's Minimap; minimap drawing disabled", t);
        }
    }

    private static void fail(Throwable t) {
        if (broken) return;
        broken = true;
        MapSketch.LOGGER.error("MapSketch minimap drawing failed; disabled for this session", t);
    }

    /** The single element: the minimap centre. */
    static final class Anchor {
        double x, z;
    }

    static final class Renderer extends MinimapElementRenderer<Anchor, Anchor> {
        private final MinimapElementMapRendererHandler handler;
        private boolean done;

        Renderer(MinimapElementMapRendererHandler handler) {
            super(new Reader(), new Provider(), new Anchor());
            this.handler = handler;
        }

        // Before entity radar icons.
        @Override
        public int getOrder() {
            return -1;
        }

        @Override
        public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider renderers) {
            try {
                context.x = info.renderPos.x;
                context.z = info.renderPos.z;
            } catch (Throwable t) {
                fail(t);
            }
        }

        @Override
        public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider renderers) {
        }

        @Override
        public boolean renderElement(Anchor a, boolean highlighted, boolean outOfBounds, double depth, float optionalScale,
                                     double partialX, double partialY, MinimapElementRenderInfo info, GuiGraphics g,
                                     MultiBufferSource.BufferSource buffers) {
            if (done || broken || !Config.ENABLED.get() || !Config.SHOW_ON_MINIMAP.get()) return false;
            try {
                MinimapHandlerAccessor h = (MinimapHandlerAccessor) handler;
                float ps = (float) h.mapsketch$ps(), pc = (float) h.mapsketch$pc(), zoom = (float) h.mapsketch$zoom();
                double r = h.mapsketch$halfWView() * 1.5 / zoom;
                ResourceKey<Level> dim = info.mapDimension != null ? info.mapDimension : info.renderEntityDimension;
                if (dim == null) return false;
                PoseStack pose = g.pose();
                pose.pushPose();
                try {
                    pose.translate(partialX, partialY, 0);
                    pose.mulPose(new Matrix4f(ps, pc, 0, 0, -pc, ps, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1));
                    pose.scale(zoom, zoom, 1);
                    SketchRenderer.drawAll(g, ClientStore.sorted(dim.location().toString()), a.x, a.z, zoom,
                            new double[]{a.x - r, a.z - r, a.x + r, a.z + r}, false, Set.of(), Set.of());
                } finally {
                    pose.popPose();
                }
            } catch (Throwable t) {
                done = true;
                fail(t);
            }
            return false;
        }

        @Override
        public boolean shouldRender(MinimapElementRenderLocation location) {
            return location == MinimapElementRenderLocation.IN_MINIMAP;
        }
    }

    static final class Provider extends MinimapElementRenderProvider<Anchor, Anchor> {
        private boolean given;

        @Override
        public void begin(MinimapElementRenderLocation location, Anchor ctx) {
            given = false;
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, Anchor ctx) {
            return !given;
        }

        @Override
        public Anchor getNext(MinimapElementRenderLocation location, Anchor ctx) {
            given = true;
            return ctx;
        }

        @Override
        public void end(MinimapElementRenderLocation location, Anchor ctx) {
        }
    }

    static final class Reader extends MinimapElementReader<Anchor, Anchor> {
        @Override
        public boolean isHidden(Anchor a, Anchor ctx) {
            return false;
        }

        @Override
        public double getRenderX(Anchor a, Anchor ctx, float partialTicks) {
            return a.x;
        }

        @Override
        public double getRenderY(Anchor a, Anchor ctx, float partialTicks) {
            return 64;
        }

        @Override
        public double getRenderZ(Anchor a, Anchor ctx, float partialTicks) {
            return a.z;
        }

        // Drawings are in the viewed map's coordinates already: no dimension scaling.
        @Override
        public double getCoordinateScale(Anchor a, Anchor ctx, MinimapElementRenderInfo info) {
            return info.backgroundCoordinateScale;
        }

        @Override
        public boolean isInteractable(MinimapElementRenderLocation location, Anchor a) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(Anchor a, Anchor ctx, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(Anchor a, Anchor ctx, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(Anchor a, Anchor ctx, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(Anchor a, Anchor ctx, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(Anchor a, Anchor ctx, float partialTicks) {
            return -10000;
        }

        @Override
        public int getRenderBoxRight(Anchor a, Anchor ctx, float partialTicks) {
            return 10000;
        }

        @Override
        public int getRenderBoxTop(Anchor a, Anchor ctx, float partialTicks) {
            return -10000;
        }

        @Override
        public int getRenderBoxBottom(Anchor a, Anchor ctx, float partialTicks) {
            return 10000;
        }

        @Override
        public int getLeftSideLength(Anchor a, Minecraft mc) {
            return 0;
        }

        @Override
        public String getMenuName(Anchor a) {
            return "MapSketch";
        }

        @Override
        public String getFilterName(Anchor a) {
            return "MapSketch";
        }

        @Override
        public int getMenuTextFillLeftPadding(Anchor a) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(Anchor a) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }
    }
}
