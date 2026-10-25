package com.volcanicmg.mapsketch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.GuiMap;

/** Xaero's world map camera: centre in blocks and raw pixels per block. */
@Mixin(value = GuiMap.class, remap = false)
public interface GuiMapAccessor {
    @Accessor("cameraX")
    double mapsketch$cameraX();

    @Accessor("cameraZ")
    double mapsketch$cameraZ();

    @Accessor("scale")
    double mapsketch$scale();

    @Accessor("leftMouseButton")
    xaero.map.gui.MapMouseButtonPress mapsketch$leftMouseButton();
}
