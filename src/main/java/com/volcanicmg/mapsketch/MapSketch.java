package com.volcanicmg.mapsketch;

import com.mojang.logging.LogUtils;
import com.volcanicmg.mapsketch.client.ClientStore;
import com.volcanicmg.mapsketch.net.AckPayload;
import com.volcanicmg.mapsketch.net.EditPayload;
import com.volcanicmg.mapsketch.net.ElementsPayload;
import com.volcanicmg.mapsketch.net.LeasePayload;
import com.volcanicmg.mapsketch.net.LeaseReplyPayload;
import com.volcanicmg.mapsketch.net.LeasesPayload;
import com.volcanicmg.mapsketch.net.LivePayload;
import com.volcanicmg.mapsketch.net.RemovePayload;
import com.volcanicmg.mapsketch.net.ResyncPayload;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import com.volcanicmg.mapsketch.server.FtbTeamsCompat;
import com.volcanicmg.mapsketch.server.SketchCommands;
import com.volcanicmg.mapsketch.server.SketchServer;
import com.volcanicmg.mapsketch.sync.SyncServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/** Common entrypoint: server config, payloads, server-side sync and commands. */
@Mod(MapSketch.MODID)
public final class MapSketch {
    public static final String MODID = "mapsketch";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MapSketch(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        modBus.addListener(MapSketch::registerPayloads);
        // Limits and defaults are part of the login sync.
        modBus.addListener(ModConfigEvent.Reloading.class, e -> {
            if (e.getConfig().getSpec() == Config.SERVER_SPEC) SketchServer.resyncAll();
        });
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> SketchCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> SketchServer.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> SketchServer.onServerStopped());
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) SketchServer.onLogout(p);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) SketchServer.sync(p.server).interrupt(p.getUUID(), "dimension changed");
        });
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) SketchServer.sync(p.server).interrupt(p.getUUID(), "died");
        });
        if (ModList.get().isLoaded("ftbteams")) FtbTeamsCompat.init();
        else NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) SketchServer.onLogin(p);
        });
    }

    private static SyncServer sync(IPayloadContext ctx) {
        return ctx.player() instanceof ServerPlayer sp ? SketchServer.sync(sp.server) : null;
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional both ways; client handlers sit behind lambdas so dedicated servers never load client classes.
        event.registrar("6").optional()
                .playToClient(ServerInfoPayload.TYPE, ServerInfoPayload.CODEC, (p, ctx) -> ClientStore.onInfo(p))
                .playToClient(ElementsPayload.TYPE, ElementsPayload.CODEC, (p, ctx) -> ClientStore.onElements(p))
                .playToClient(RemovePayload.TYPE, RemovePayload.CODEC, (p, ctx) -> ClientStore.onRemove(p))
                .playToClient(AckPayload.TYPE, AckPayload.CODEC, (p, ctx) -> ClientStore.onAck(p))
                .playToClient(LeaseReplyPayload.TYPE, LeaseReplyPayload.CODEC, (p, ctx) -> ClientStore.onLeaseReply(p))
                .playToClient(LeasesPayload.TYPE, LeasesPayload.CODEC, (p, ctx) -> ClientStore.onLeases(p))
                .playToServer(EditPayload.TYPE, EditPayload.CODEC, (p, ctx) -> {
                    SyncServer s = sync(ctx);
                    if (s != null) s.edit(ctx.player().getUUID(), p.opId(), p.elements());
                })
                .playToServer(LeasePayload.TYPE, LeasePayload.CODEC, (p, ctx) -> {
                    SyncServer s = sync(ctx);
                    if (s == null) return;
                    switch (p.action()) {
                        case ACQUIRE -> s.acquire(ctx.player().getUUID(), p.requestId(), p.ids(), p.optional());
                        case RENEW -> s.renew(ctx.player().getUUID());
                        case RELEASE -> s.release(ctx.player().getUUID(), p.ids());
                    }
                })
                .playToServer(ResyncPayload.TYPE, ResyncPayload.CODEC, (p, ctx) -> {
                    SyncServer s = sync(ctx);
                    if (s != null) s.requestResync(ctx.player().getUUID());
                })
                .playBidirectional(LivePayload.TYPE, LivePayload.CODEC, (p, ctx) -> {
                    SyncServer s = sync(ctx);
                    if (s != null) s.live(ctx.player().getUUID(), p.live());
                    else ClientStore.onLive(p);
                });
    }
}
