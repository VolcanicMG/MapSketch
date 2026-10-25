package com.volcanicmg.mapsketch.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.volcanicmg.mapsketch.model.Element;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * {@code /mapsketch clear player <p> | dimension <d> | all}, {@code /mapsketch release <player> | all} (force-release
 * edit leases) and {@code /mapsketch stats}, op level 2.
 */
public final class SketchCommands {
    private SketchCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mapsketch").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("clear")
                        .then(Commands.literal("all").executes(ctx -> report(ctx.getSource(),
                                SketchServer.sync(ctx.getSource().getServer()).clear(e -> true))))
                        .then(Commands.literal("player").then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> {
                                    Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
                                    Set<UUID> ids = profiles.stream().map(GameProfile::getId).collect(Collectors.toSet());
                                    return report(ctx.getSource(), SketchServer.sync(ctx.getSource().getServer())
                                            .clear(e -> ids.contains(e.owner)));
                                })))
                        .then(Commands.literal("dimension").then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(ctx -> {
                                    String dim = DimensionArgument.getDimension(ctx, "dimension").dimension().location().toString();
                                    return report(ctx.getSource(), SketchServer.sync(ctx.getSource().getServer())
                                            .clear(e -> e.dim.equals(dim)));
                                }))))
                .then(Commands.literal("release")
                        .then(Commands.literal("all").executes(ctx -> released(ctx.getSource(),
                                SketchServer.sync(ctx.getSource().getServer()).forceRelease(null))))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> {
                            int n = 0;
                            for (GameProfile gp : GameProfileArgument.getGameProfiles(ctx, "player"))
                                n += SketchServer.sync(ctx.getSource().getServer()).forceRelease(gp.getId());
                            return released(ctx.getSource(), n);
                        }))));
    }

    private static int released(CommandSourceStack src, int n) {
        src.sendSuccess(() -> Component.translatable("mapsketch.command.released", n), true);
        return n;
    }

    private static int report(CommandSourceStack src, int n) {
        src.sendSuccess(() -> Component.translatable("mapsketch.command.cleared", n), true);
        return n;
    }

    private static int stats(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        int live = 0, tombstones = 0;
        Map<String, Integer> dims = new HashMap<>();
        Map<UUID, Integer> owners = new HashMap<>();
        for (Element e : SketchData.get(server).store.all()) {
            if (e.deleted) {
                tombstones++;
                continue;
            }
            live++;
            dims.merge(e.dim, 1, Integer::sum);
            if (e.owner != null) owners.merge(e.owner, 1, Integer::sum);
        }
        int liveF = live, tombF = tombstones;
        src.sendSuccess(() -> Component.translatable("mapsketch.command.stats", liveF, tombF, owners.size()), false);
        dims.forEach((dim, n) -> src.sendSuccess(() -> Component.literal("  " + dim + ": " + n), false));
        owners.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(5)
                .forEach(en -> {
                    String name = server.getProfileCache() == null ? en.getKey().toString()
                            : server.getProfileCache().get(en.getKey()).map(GameProfile::getName).orElse(en.getKey().toString());
                    src.sendSuccess(() -> Component.literal("  " + name + ": " + en.getValue()), false);
                });
        return live;
    }
}
