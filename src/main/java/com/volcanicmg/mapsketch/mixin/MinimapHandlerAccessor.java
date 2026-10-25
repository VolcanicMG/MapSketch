package com.volcanicmg.mapsketch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

/** Rotation (sin/cos as ps/pc), zoom and half view size the minimap set for this frame's in-map elements. */
@Mixin(value = MinimapElementMapRendererHandler.class, remap = false)
public interface MinimapHandlerAccessor {
    @Accessor("ps")
    double mapsketch$ps();

    @Accessor("pc")
    double mapsketch$pc();

    @Accessor("zoom")
    double mapsketch$zoom();

    @Accessor("halfWView")
    float mapsketch$halfWView();
}
