package com.volcanicmg.mapsketch.client.xaero;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.client.ConfigButton;
import com.volcanicmg.mapsketch.client.Editor;
import com.volcanicmg.mapsketch.client.EditorUi;
import com.volcanicmg.mapsketch.client.MapLayer;
import com.volcanicmg.mapsketch.client.MapView;
import com.volcanicmg.mapsketch.mixin.GuiMapAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;
import xaero.map.gui.GuiWorldMapSettings;

/**
 * Glue between Xaero's World Map screen and the editor. Only loaded when Xaero's World Map is installed.
 * Input uses NeoForge's cancellable screen events, so no Xaero input method is patched.
 */
public final class WorldMapHooks {
    private static boolean mixinSeen, drewInMap, broken;

    private WorldMapHooks() {
    }

    public static void register(ModContainer container) {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, safe(e -> {
            if (e.getScreen() instanceof GuiWorldMapSettings) ConfigButton.add(e, container);
            if (e.getScreen() instanceof GuiMap) Editor.onMapOpened();
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Closing.class, safe(e -> {
            if (e.getScreen() instanceof GuiMap) Editor.onMapClosed();
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, safe(WorldMapHooks::onRender));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseButtonPressed.Pre.class, safe(e -> {
            MapView v = viewOf(e.getScreen());
            if (v == null) return;
            if (!EditorUi.over(e.getMouseX(), e.getMouseY()) && overXaeroWidget(e.getScreen(), e.getMouseX(), e.getMouseY())) return;
            if (Editor.mousePressed(v, e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseButtonReleased.Pre.class, safe(e -> {
            MapView v = viewOf(e.getScreen());
            if (v != null && Editor.mouseReleased(v, e.getMouseX(), e.getMouseY(), e.getButton())) e.setCanceled(true);
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseDragged.Pre.class, safe(e -> {
            MapView v = viewOf(e.getScreen());
            if (v != null && Editor.mouseDragged(v, e.getMouseX(), e.getMouseY(), e.getMouseButton())) e.setCanceled(true);
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre.class, safe(e -> {
            MapView v = viewOf(e.getScreen());
            if (v == null || e.getScreen().getFocused() instanceof EditBox) return;
            if (Editor.keyPressed(v, e.getKeyCode(), e.getScanCode(), e.getModifiers())) e.setCanceled(true);
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyReleased.Pre.class, safe(e -> {
            if (e.getScreen() instanceof GuiMap && Editor.keyReleased(e.getKeyCode())) e.setCanceled(true);
        }));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.CharacterTyped.Pre.class, safe(e -> {
            if (!(e.getScreen() instanceof GuiMap) || e.getScreen().getFocused() instanceof EditBox) return;
            if (Editor.charTyped(e.getCodePoint())) e.setCanceled(true);
        }));
    }

    /** Any Xaero-facing failure turns MapSketch's map layer off once, with a log line, instead of crashing the map. */
    private static <E> java.util.function.Consumer<E> safe(java.util.function.Consumer<E> c) {
        return e -> {
            if (broken) return;
            try {
                c.accept(e);
            } catch (Throwable t) {
                fail(t);
            }
        };
    }

    private static void fail(Throwable t) {
        if (broken) return;
        broken = true;
        MapSketch.LOGGER.error("MapSketch world map layer failed; disabled for this session", t);
    }

    private static boolean overXaeroWidget(Screen screen, double x, double y) {
        for (GuiEventListener l : screen.children())
            if (l instanceof AbstractWidget w && w.visible && w.isMouseOver(x, y)) return true;
        return false;
    }

    private static MapView viewOf(Screen screen) {
        return screen instanceof GuiMap map ? view(map) : null;
    }

    /** @return this frame's camera and viewed dimension, or null while Xaero has no map loaded */
    static MapView view(GuiMap map) {
        if (broken) return null;
        try {
            MapProcessor mp = map.getMapProcessor();
            if (mp == null || mp.getMapWorld() == null || mp.getMapWorld().getCurrentDimension() == null) return null;
            GuiMapAccessor a = (GuiMapAccessor) map;
            if (a.mapsketch$scale() <= 0) return null;
            Window w = Minecraft.getInstance().getWindow();
            return new MapView(a.mapsketch$cameraX(), a.mapsketch$cameraZ(), a.mapsketch$scale(),
                    mp.getMapWorld().getCurrentDimension().getDimId().location().toString(), w.getGuiScale(), w.getWidth(), w.getHeight());
        } catch (Throwable t) {
            // Xaero internals changed: turn drawing off rather than crash the map.
            broken = true;
            MapSketch.LOGGER.error("MapSketch cannot read Xaero's World Map camera; drawing disabled", t);
            return null;
        }
    }

    /** Called by the GuiMap mixin right before Xaero draws waypoints; the pose is already in map space. */
    public static void renderInMap(GuiGraphics g, GuiMap map) {
        mixinSeen = true;
        MapView v = view(map);
        if (v == null) return;
        drewInMap = true;
        try {
            MapLayer.renderWorldMap(g, v);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void onRender(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof GuiMap map)) return;
        MapView v = view(map);
        if (v == null) return;
        GuiGraphics g = e.getGuiGraphics();
        // Without the mixin, draw over the map (and over waypoints) from here instead.
        if (!mixinSeen && !drewInMap) {
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.scale((float) (1 / v.guiScale()), (float) (1 / v.guiScale()), 1);
            pose.translate(v.winW() / 2, v.winH() / 2, 0);
            pose.scale((float) v.scale(), (float) v.scale(), 1);
            try {
                MapLayer.renderWorldMap(g, v);
            } finally {
                pose.popPose();
            }
        }
        drewInMap = false;
        Editor.onFrame(v, e.getMouseX(), e.getMouseY());
        EditorUi.render(g, v, e.getMouseX(), e.getMouseY(), e.getScreen().width);
    }
}
