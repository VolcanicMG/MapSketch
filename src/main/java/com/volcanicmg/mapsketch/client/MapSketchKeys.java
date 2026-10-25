package com.volcanicmg.mapsketch.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Key bindings. The toggle only acts while Xaero's world map is open. */
public final class MapSketchKeys {
    public static final KeyMapping TOGGLE = new KeyMapping("key.mapsketch.toggle", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G, "key.categories.mapsketch");

    private MapSketchKeys() {
    }
}
