package com.volcanicmg.mapsketch.client;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.MapSketch;
import com.volcanicmg.mapsketch.client.xaero.MinimapLayer;
import com.volcanicmg.mapsketch.client.xaero.WorldMapHooks;
import com.volcanicmg.mapsketch.net.ElementsPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entrypoint: client config and screen, key binding, connection state, Xaero hooks. */
@Mod(value = MapSketch.MODID, dist = Dist.CLIENT)
public final class MapSketchClient {
    public MapSketchClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        // Mods list > MapSketch > Config; shows server settings too when they are editable (singleplayer).
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(MapSketchKeys.TOGGLE);
            e.register(MapSketchKeys.HINTS);
        });
        // Resource reloads rebuild the atlases: cached stamp sprites must be looked up again.
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class, e -> e.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> StampArt.clear()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                e -> ClientStore.onLogin(e.getPlayer().connection.hasChannel(ElementsPayload.TYPE)));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            Editor.onDisconnect();
            ClientStore.clear();
            SketchRenderer.clearCache();
        });
        boolean minimap = ModList.get().isLoaded("xaerominimap");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            Editor.tick();
            ClientStore.tick();
            if (minimap) MinimapLayer.tick();
        });
        if (ModList.get().isLoaded("xaeroworldmap")) WorldMapHooks.register(container);
    }
}
