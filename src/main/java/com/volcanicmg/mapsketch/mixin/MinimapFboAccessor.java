package com.volcanicmg.mapsketch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/** The minimap's in-map element handler (draws into the rotated framebuffer that Xaero masks to the map shape). */
@Mixin(value = MinimapFBORenderer.class, remap = false)
public interface MinimapFboAccessor {
    @Accessor("minimapElementMapRendererHandler")
    MinimapElementMapRendererHandler mapsketch$handler();
}
