package com.volcanicmg.mapsketch.server;

import com.volcanicmg.mapsketch.Config;
import com.volcanicmg.mapsketch.model.Element;
import com.volcanicmg.mapsketch.model.Rules;
import com.volcanicmg.mapsketch.net.AckPayload;
import com.volcanicmg.mapsketch.net.ElementsPayload;
import com.volcanicmg.mapsketch.net.LeaseReplyPayload;
import com.volcanicmg.mapsketch.net.LeasesPayload;
import com.volcanicmg.mapsketch.net.LivePayload;
import com.volcanicmg.mapsketch.net.RemovePayload;
import com.volcanicmg.mapsketch.net.ServerInfoPayload;
import com.volcanicmg.mapsketch.sync.Live;
import com.volcanicmg.mapsketch.sync.Outbox;
import com.volcanicmg.mapsketch.sync.SyncServer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Minecraft adapter around {@link SyncServer}: one instance per running server (leases are never persisted, so a
 * restart starts clean), payloads in, payloads out. Server thread only.
 */
public final class SketchServer {
    /** (owner, viewer) share a team; replaced by {@link FtbTeamsCompat} when FTB Teams is installed. */
    static BiPredicate<UUID, UUID> sameTeam = (owner, viewer) -> false;
    static boolean teamsAvailable;

    private static MinecraftServer server;
    private static SyncServer sync;
    private static int ticks;

    private SketchServer() {
    }

    /** @return the state machine for the running server, created on first use */
    public static SyncServer sync(MinecraftServer s) {
        if (sync == null || server != s) {
            server = s;
            sync = new SyncServer(SketchData.get(s).store, new Env(), new Payloads());
        }
        return sync;
    }

    public static void onServerStopped() {
        server = null;
        sync = null;
    }

    public static Element.Visibility defaultVisibility() {
        return switch (Config.DEFAULT_VISIBILITY.get()) {
            case AUTO -> teamsAvailable ? Element.Visibility.TEAM : Element.Visibility.PUBLIC;
            case PRIVATE -> Element.Visibility.PRIVATE;
            case TEAM -> Element.Visibility.TEAM;
            case PUBLIC -> Element.Visibility.PUBLIC;
        };
    }

    public static void onLogin(ServerPlayer p) {
        if (!p.connection.hasChannel(ElementsPayload.TYPE)) return;
        send(p, new ServerInfoPayload(defaultVisibility(), teamsAvailable, Config.TEAM_CAN_EDIT.get(), p.hasPermissions(2),
                Config.MAX_POINTS.get(), Config.MAX_TEXT.get()));
        sync(p.server).join(p.getUUID(), p.getGameProfile().getName());
    }

    public static void onLogout(ServerPlayer p) {
        sync(p.server).leave(p.getUUID());
    }

    // Pending debounced resync: players to resync, null entry set = everyone; ticks left.
    private static Set<UUID> resyncPlayers;
    private static boolean resyncEveryone;
    private static int resyncIn = -1;

    /** Config reload: everyone gets fresh server info and a resync (debounced). */
    public static void resyncAll() {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers())
                if (p.connection.hasChannel(ElementsPayload.TYPE))
                    send(p, new ServerInfoPayload(defaultVisibility(), teamsAvailable, Config.TEAM_CAN_EDIT.get(),
                            p.hasPermissions(2), Config.MAX_POINTS.get(), Config.MAX_TEXT.get()));
            resyncEveryone = true;
            if (resyncIn < 0) resyncIn = 20;
        });
    }

    /** Team events: resync these players a second after the first pending event, so bursts coalesce but never starve. */
    public static void resyncSoon(Set<UUID> players) {
        if (resyncPlayers == null) resyncPlayers = new HashSet<>();
        resyncPlayers.addAll(players);
        if (resyncIn < 0) resyncIn = 20;
    }

    public static void tick(MinecraftServer s) {
        if (sync == null) return;
        sync(s).tick();
        if (resyncIn > 0 && --resyncIn == 0) {
            sync(s).resync(resyncEveryone ? null : resyncPlayers);
            resyncPlayers = null;
            resyncEveryone = false;
            resyncIn = -1;
        }
        if (++ticks % 1200 == 0) sync(s).prune();
    }

    static void send(ServerPlayer p, CustomPacketPayload payload) {
        if (p != null && p.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(p, payload);
    }

    private static ServerPlayer player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static final class Env implements SyncServer.Env {
        @Override
        public boolean isOp(UUID player) {
            ServerPlayer p = player(player);
            return p != null && p.hasPermissions(2);
        }

        @Override
        public boolean dimensionExists(String dim) {
            ResourceLocation rl = ResourceLocation.tryParse(dim);
            return rl != null && server.getLevel(ResourceKey.create(Registries.DIMENSION, rl)) != null;
        }

        @Override
        public boolean itemExists(String id) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) return false;
            // Air (and anything whose default stack is empty) would be an invisible, still selectable stamp.
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            return item != net.minecraft.world.item.Items.AIR && !new net.minecraft.world.item.ItemStack(item).isEmpty();
        }

        @Override
        public boolean sameTeam(UUID owner, UUID viewer) {
            return sameTeam.test(owner, viewer);
        }

        @Override
        public SyncServer.Settings settings() {
            return new SyncServer.Settings(new Rules.Limits(Config.MAX_POINTS.get(), Config.MAX_TEXT.get()),
                    Config.TEAM_CAN_EDIT.get(), Config.MAX_PER_PLAYER.get(), Config.MAX_PER_DIMENSION.get(),
                    Config.EDIT_KBPS.get() * 1024L, Config.EDIT_KBPS.get() * 1024L);
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    }

    private static final class Payloads implements Outbox {
        @Override
        public void elements(UUID to, long seq, boolean reset, boolean force, List<Element> elements) {
            send(player(to), new ElementsPayload(seq, reset, force, elements));
        }

        @Override
        public void remove(UUID to, long seq, List<UUID> ids) {
            send(player(to), new RemovePayload(seq, ids));
        }

        @Override
        public void ack(UUID to, int opId, List<UUID> rejected, String reason, List<UUID> accepted, int[] versions) {
            send(player(to), new AckPayload(opId, rejected, reason, accepted, versions));
        }

        @Override
        public void leaseReply(UUID to, int requestId, boolean granted, List<UUID> ids, String reason) {
            send(player(to), new LeaseReplyPayload(requestId, granted, ids, reason));
        }

        @Override
        public void leaseRevoked(UUID to, List<UUID> ids, String reason) {
            send(player(to), new LeaseReplyPayload(-1, false, ids, reason));
        }

        @Override
        public void leases(UUID to, UUID holder, String name, List<UUID> added, List<UUID> removed, boolean reset) {
            send(player(to), new LeasesPayload(holder, name, added, removed, reset));
        }

        @Override
        public void live(UUID to, UUID from, String name, Live live) {
            send(player(to), new LivePayload(from, name, live));
        }
    }
}
