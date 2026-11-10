package com.ronstrat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Headless self-play harness. Start a dedicated server with
 *   -Dronstrat.autotest="expert vs normal*5" -Dronstrat.seed=3 -Dronstrat.out=results.jsonl
 * and it builds an arena, plays the match with no human on the server (as fast as the CPU allows),
 * appends one JSON line with the result and shuts down. The tuner runs many of these in parallel.
 */
public final class AutoTest {
    private AutoTest() {}

    private static Match match;
    private static MinecraftServer server;
    private static long ticks;
    private static long realStart;
    private static boolean done;
    private static Field nextTickTime;
    private static long lastProgress;

    static boolean enabled() { return System.getProperty("ronstrat.autotest") != null || System.getProperty("ronstrat.lobbytest") != null; }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent evt) {
        if (!enabled()) return;
        server = evt.getServer();
        ticks = 0;
        done = false;
        if (Boolean.parseBoolean(System.getProperty("ronstrat.fast", "true"))) {
            try {
                nextTickTime = ObfuscationReflectionHelper.findField(MinecraftServer.class, "f_129726_");
                nextTickTime.setAccessible(true);
            } catch (Exception e) {
                RonStrategist.LOG.warn("RON Strategist: fast mode unavailable: {}", e.toString());
            }
        }
    }

    /** -Dronstrat.lobbytest=normal: seat a computer player on every lobby position and check RON starts them */
    private static void lobbyTest() {
        if (ticks == 80) {
            Difficulty d = Difficulty.parse(System.getProperty("ronstrat.lobbytest"));
            int n = 0;
            for (var sp : new java.util.ArrayList<>(com.solegendary.reignofnether.startpos.StartPosServerEvents.startPoses)) {
                if (!sp.enabled) continue;
                LobbyAi.setSeat(sp.pos, d);
                // lobby settings check: the first two seats one team with a handicap on the first, the rest another team
                LobbySettings.set(sp.pos, n < 2 ? 1 : 2, n == 0 ? 2 : 0);
                n++;
            }
            RonStrategist.LOG.info("lobbytest: seated {} computer players, countdown running={}", n,
                    com.solegendary.reignofnether.startpos.StartPosServerEvents.isStartingGame());
        }
        if (ticks == 400 || ticks == 2400) {
            RonStrategist.LOG.info("lobbytest: t={} rtsPlayers={} bots={}", ticks, PlayerServerEvents.rtsPlayers.size(), BotManager.bots().size());
            for (Bot b : BotManager.bots()) RonStrategist.LOG.info("lobbytest:   {}", b.status());
            java.util.List<Bot> all = new java.util.ArrayList<>(BotManager.bots());
            for (int i = 0; i < all.size(); i++)
                for (int j = i + 1; j < all.size(); j++)
                    RonStrategist.LOG.info("lobbytest: allied {} {} = {}", all.get(i).name, all.get(j).name,
                            com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(all.get(i).name, all.get(j).name));
        }
        if (ticks == 2400) { done = true; server.halt(false); }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent evt) {
        if (server == null || evt.phase != TickEvent.Phase.END || done) return;
        ticks++;
        if (System.getProperty("ronstrat.lobbytest") != null) {
            if (nextTickTime != null) try { nextTickTime.setLong(server, Util.getMillis()); } catch (Exception ignored) { }
            lobbyTest();
            return;
        }
        if (nextTickTime != null) {
            try { nextTickTime.setLong(server, Util.getMillis()); } catch (Exception ignored) { }
        }
        if (ticks == 50 && System.getProperty("ronstrat.dumpTerrain") != null) {
            // -Dronstrat.dumpTerrain=x1,z1,x2,z2: surface height and block per column (map study)
            String[] q = System.getProperty("ronstrat.dumpTerrain").split(",");
            int x1 = Integer.parseInt(q[0]), z1 = Integer.parseInt(q[1]), x2 = Integer.parseInt(q[2]), z2 = Integer.parseInt(q[3]);
            ServerLevel lv = server.overworld();
            for (int z = z1; z <= z2; z++) {
                StringBuilder hb = new StringBuilder(), bb = new StringBuilder();
                for (int x = x1; x <= x2; x++) {
                    int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    hb.append(String.format("%3d", y));
                    String n = lv.getBlockState(new BlockPos(x, y - 1, z)).getBlock().getDescriptionId();
                    n = n.substring(n.lastIndexOf('.') + 1);
                    bb.append(n.contains("stair") ? 'S' : n.contains("slab") ? 's' : n.contains("water") ? '~' : n.contains("brick") ? 'B' : n.contains("grass") || n.contains("dirt") ? '.' : n.contains("log") || n.contains("leaves") ? 'T' : n.contains("fence") || n.contains("wall") ? '#' : '?');
                }
                RonStrategist.LOG.info("TERRAIN z={} H{}", z, hb);
                RonStrategist.LOG.info("TERRAIN z={} B {}", z, bb);
            }
        }
        if (ticks == 60) {
            long seed = Long.getLong("ronstrat.seed", 1L);
            match = new Match(MatchSpec.parse(System.getProperty("ronstrat.autotest")), seed);
            ServerLevel level = server.overworld();
            if (System.getProperty("ronstrat.map") != null) match.useMapSeats(level);
            match.start(level, new BlockPos(0, 0, 0).atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)));
            realStart = System.currentTimeMillis();
            RonStrategist.LOG.info("RON Strategist autotest started: {} seed={}", match.spec.text, seed);
            return;
        }
        if (match == null) return;
        long gameTicks = PlayerServerEvents.rtsGameTicks - match.startTick;
        long timeout = Long.getLong("ronstrat.timeoutMinutes", 45L) * 60 * 20;
        if (gameTicks - lastProgress >= 20 * 60) {
            lastProgress = gameTicks;
            StringBuilder sb = new StringBuilder();
            for (Bot b : BotManager.bots()) {
                sb.append("\n  ").append(b.status());
                if (b.verbose && gameTicks < 20 * 60 * 8) sb.append(b.workerDump());
            }
            RonStrategist.LOG.info("autotest t={}s real={}s{}", gameTicks / 20, (System.currentTimeMillis() - realStart) / 1000, sb);
        }
        if (match.decided() || gameTicks >= timeout) { finish(gameTicks, gameTicks >= timeout && !match.decided(), -1); return; }
        // decisive stop: once a side has no army left and is out-scored 5:1 for two straight minutes,
        // razing the remaining houses only costs wall time
        if (gameTicks % 200 == 0) match.trackPeaks();
        if (gameTicks % 200 == 0 && gameTicks > 20 * 60 * 8 && !match.spec.ffa) {
            int lead = match.dominantTeam(5.0);
            if (lead >= 0 && lead == domTeam) {
                if (gameTicks - domSince >= 20 * 120) finish(gameTicks, false, lead);
            } else {
                domTeam = lead;
                domSince = gameTicks;
            }
        }
    }

    private static int domTeam = -1;
    private static long domSince;

    private static void finish(long gameTicks, boolean timedOut, int decisiveWinner) {
        done = true;
        List<Integer> alive = match.aliveTeams();
        int winner = decisiveWinner;
        if (!timedOut && !match.spec.ffa && alive.size() == 1) winner = alive.get(0);
        if (!timedOut && match.spec.ffa) {
            for (int i = 0; i < match.teams.size(); i++)
                for (Bot b : match.teams.get(i)) if (BotManager.isRtsName(b.name)) winner = i;
        }
        if (timedOut) {
            double best = -1;
            for (int i : alive) { double s = match.teamScore(i); if (s > best) { best = s; winner = i; } }
        }
        JsonObject o = new JsonObject();
        o.addProperty("spec", match.spec.text);
        o.addProperty("seed", match.seed);
        o.addProperty("winnerTeam", winner);
        o.addProperty("timeout", timedOut);
        o.addProperty("decisive", decisiveWinner >= 0);
        o.addProperty("arena", System.getProperty("ronstrat.map") != null ? "map:" + System.getProperty("ronstrat.map") : Arena.summary(match.spec.players()));
        o.addProperty("gameSeconds", gameTicks / 20);
        o.addProperty("realSeconds", (System.currentTimeMillis() - realStart) / 1000);
        o.addProperty("paramsExpert", System.getProperty("ronstrat.params.expert", ""));
        JsonArray teams = new JsonArray();
        for (int i = 0; i < match.teams.size(); i++) {
            JsonArray t = new JsonArray();
            for (Bot b : match.teams.get(i)) {
                JsonObject bo = new JsonObject();
                bo.addProperty("name", b.name);
                bo.addProperty("difficulty", b.difficulty.key());
                bo.addProperty("alive", BotManager.isRtsName(b.name));
                bo.addProperty("peakWorkers", b.peakWorkers);
                bo.addProperty("peakArmy", b.peakArmy);
                bo.addProperty("attacks", b.army.attacksLaunched);
                bo.addProperty("retreats", b.army.retreats);
                bo.addProperty("buildings", b.intel.own.size());
                t.add(bo);
            }
            teams.add(t);
        }
        o.add("teams", teams);
        String line = new Gson().toJson(o);
        RonStrategist.LOG.info("RON Strategist autotest result: {}", line);
        String out = System.getProperty("ronstrat.out");
        if (out != null) {
            try {
                Files.writeString(Path.of(out), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                RonStrategist.LOG.error("could not write result", e);
            }
        }
        server.halt(false);
    }
}
