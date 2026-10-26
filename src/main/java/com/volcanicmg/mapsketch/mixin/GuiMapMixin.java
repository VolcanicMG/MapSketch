package com.volcanicmg.mapsketch.mixin;

import com.volcanicmg.mapsketch.client.xaero.WorldMapHooks;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

/**
 * Draws drawings right before Xaero renders its map elements (waypoints, players), so they sit above terrain and
 * highlights and below markers. Optional: if the call moves, {@link WorldMapHooks} draws over the map instead.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 0, at = @At(value = "INVOKE",
            target = "Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"))
    private void mapsketch$drawShapes(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        WorldMapHooks.renderInMap(g, (GuiMap) (Object) this);
    }

    /**
     * The Over layer: right after the batch that ends Xaero's map-space pass (map elements, then the player arrows),
     * the third {@code endBatch} in {@code render} for World Map 1.47.0; the pose is still in map space there.
     */
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 2, shift = At.Shift.AFTER))
    private void mapsketch$drawOver(GuiGraphics g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        WorldMapHooks.renderInMapOver(g, (GuiMap) (Object) this);
    }
}
