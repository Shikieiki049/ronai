package com.ronstrat;

import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lobby settings per start position, set by the host from the settings screen next to RON's own lobby:
 * <ul>
 * <li>team: 0 = RON's default (the seat's colour decides), 1..8 = an explicit team. Seats with the same
 * team are allied at the start, different teams are enemies.</li>
 * <li>handicap: 0..4 levels, each +2 villagers and +0.5x the normal starting resources (the same bonus the
 * 1vN handicap gives).</li>
 * </ul>
 * Applied a moment after RON has started everybody (humans and computer players alike).
 */
public final class LobbySettings {
    private LobbySettings() {}

    public static final int MAX_TEAM = 8, MAX_HANDICAP = 4;

    /** server: seat -> {team, handicap} */
    private static final Map<BlockPos, int[]> SETTINGS = new LinkedHashMap<>();
    /** client: the same, as last told by the server */
    public static volatile Map<BlockPos, int[]> clientSettings = Map.of();

    /** who sat where, captured while the lobby still shows names (RON clears them when the game starts) */
    private static final Map<BlockPos, String> lastSeated = new HashMap<>();
    private static boolean wasActive;
    private static long ticks, applyAt = -1;
    private static int lastSentHash, timer;

    public static void set(BlockPos pos, int team, int handicap) {
        if (PlayerServerEvents.isGameActive()) return;
        team = Math.max(0, Math.min(MAX_TEAM, team));
        handicap = Math.max(0, Math.min(MAX_HANDICAP, handicap));
        if (team == 0 && handicap == 0) SETTINGS.remove(pos);
        else SETTINGS.put(pos.immutable(), new int[] { team, handicap });
        broadcast();
    }

    static void broadcast() {
        List<BlockPos> p = new ArrayList<>();
        List<Integer> t = new ArrayList<>(), h = new ArrayList<>();
        for (Map.Entry<BlockPos, int[]> e : SETTINGS.entrySet()) { p.add(e.getKey()); t.add(e.getValue()[0]); h.add(e.getValue()[1]); }
        lastSentHash = hash();
        Net.CH.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new Net.LobbySettingsSync(p, t, h));
    }

    private static int hash() {
        int x = 1;
        for (Map.Entry<BlockPos, int[]> e : SETTINGS.entrySet()) x = 31 * x + e.getKey().hashCode() * 7 + e.getValue()[0] * 13 + e.getValue()[1];
        return x;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        ticks++;
        boolean active = PlayerServerEvents.isGameActive();
        if (!active) {
            Map<BlockPos, String> now = new HashMap<>();
            for (StartPos sp : StartPosServerEvents.startPoses)
                if (sp.enabled && sp.playerName != null && !sp.playerName.isBlank()) now.put(sp.pos, sp.playerName);
            if (!now.isEmpty()) { lastSeated.clear(); lastSeated.putAll(now); }
        }
        if (active && !wasActive && !SETTINGS.isEmpty()) applyAt = ticks + 40; // let RON finish starting everyone
        wasActive = active;
        if (applyAt >= 0 && ticks >= applyAt) {
            applyAt = -1;
            try { apply(evt.getServer().overworld()); }
            catch (Exception ex) { RonStrategist.LOG.error("RON Strategist: could not apply lobby settings", ex); }
        }
        if (hash() != lastSentHash || !SETTINGS.isEmpty() && ++timer % 200 == 0) broadcast();
    }

    private static void apply(ServerLevel level) {
        // seat -> player that actually started there
        Map<String, BlockPos> seatOf = new LinkedHashMap<>();
        Map<String, Integer> colourOf = new HashMap<>();
        for (Map.Entry<BlockPos, String> e : lastSeated.entrySet()) {
            if (PlayerServerEvents.getRTSPlayer(e.getValue()) == null) continue;
            seatOf.put(e.getValue(), e.getKey());
            for (StartPos sp : StartPosServerEvents.startPoses) if (sp.pos.equals(e.getKey())) colourOf.put(e.getValue(), sp.colorId);
        }
        // teams: only touched when the host set at least one explicit team
        boolean teamsSet = SETTINGS.values().stream().anyMatch(v -> v[0] > 0);
        if (teamsSet) {
            Map<String, Integer> team = new HashMap<>();
            for (Map.Entry<String, BlockPos> e : seatOf.entrySet()) {
                int[] v = SETTINGS.get(e.getValue());
                team.put(e.getKey(), v != null && v[0] > 0 ? v[0] : 1000 + colourOf.getOrDefault(e.getKey(), 0));
            }
            List<String> names = new ArrayList<>(seatOf.keySet());
            for (int i = 0; i < names.size(); i++)
                for (int j = i + 1; j < names.size(); j++) {
                    String a = names.get(i), b = names.get(j);
                    boolean same = team.get(a).equals(team.get(b));
                    if (same && !AlliancesServerEvents.isAllied(a, b)) AlliancesServerEvents.addAlliance(a, b);
                    if (!same && AlliancesServerEvents.isAllied(a, b)) AlliancesServerEvents.removeAlliance(a, b);
                }
            AlliancesServerEvents.syncAlliances();
            RonStrategist.LOG.info("RON Strategist: lobby teams applied {}", team);
        }
        // handicaps
        for (Map.Entry<String, BlockPos> e : seatOf.entrySet()) {
            int[] v = SETTINGS.get(e.getValue());
            if (v == null || v[1] <= 0) continue;
            BotManager.applyHandicap(level, e.getKey(), e.getValue().above(), v[1]);
        }
    }
}
