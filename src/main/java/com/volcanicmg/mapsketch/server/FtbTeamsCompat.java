package com.volcanicmg.mapsketch.server;

import com.mojang.authlib.GameProfile;
import com.volcanicmg.mapsketch.Config;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** All FTB Teams code. Only loaded when {@code ftbteams} is installed. */
public final class FtbTeamsCompat {
    private FtbTeamsCompat() {
    }

    public static void init() {
        SketchServer.sameTeam = FtbTeamsCompat::sameTeam;
        SketchServer.teamsAvailable = true;
        // Login after FTB has the player's team; plain PlayerLoggedInEvent can race it.
        TeamEvent.PLAYER_LOGGED_IN.register(e -> SketchServer.onLogin(e.getPlayer()));
        // Team changes resync only the players whose view can change: old and new team members and the mover.
        TeamEvent.PLAYER_CHANGED.register(e -> {
            Set<UUID> s = members(e.getTeam());
            e.getPreviousTeam().ifPresent(t -> s.addAll(t.getMembers()));
            s.add(e.getPlayerId());
            SketchServer.resyncSoon(s);
        });
        TeamEvent.PLAYER_JOINED_PARTY.register(e -> {
            Set<UUID> s = members(e.getTeam());
            if (e.getPreviousTeam() != null) s.addAll(e.getPreviousTeam().getMembers());
            SketchServer.resyncSoon(s);
        });
        TeamEvent.PLAYER_LEFT_PARTY.register(e -> {
            Set<UUID> s = members(e.getTeam());
            if (e.getPlayerTeam() != null) s.addAll(e.getPlayerTeam().getMembers());
            s.add(e.getPlayerId());
            SketchServer.resyncSoon(s);
        });
        TeamEvent.ADD_ALLY.register(e -> SketchServer.resyncSoon(allies(e.getTeam(), e.getPlayers())));
        TeamEvent.REMOVE_ALLY.register(e -> SketchServer.resyncSoon(allies(e.getTeam(), e.getPlayers())));
        TeamEvent.DELETED.register(e -> SketchServer.resyncSoon(members(e.getTeam())));
    }

    private static Set<UUID> members(Team t) {
        return t == null ? new HashSet<>() : new HashSet<>(t.getMembers());
    }

    private static Set<UUID> allies(Team t, java.util.Collection<GameProfile> players) {
        Set<UUID> s = members(t);
        for (GameProfile p : players) s.add(p.getId());
        return s;
    }

    /** @return true if {@code viewer} is in {@code owner}'s team, or allied with it when allies share */
    private static boolean sameTeam(UUID owner, UUID viewer) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) return false;
        Optional<Team> team = FTBTeamsAPI.api().getManager().getTeamForPlayerID(owner);
        if (team.isEmpty()) return false;
        if (team.get().getMembers().contains(viewer)) return true;
        return Config.SHARE_WITH_ALLIES.get() && team.get().getRankForPlayer(viewer).isAllyOrBetter();
    }
}
