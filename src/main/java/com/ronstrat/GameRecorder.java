package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.goals.GatherResourcesGoal;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The game recorder (server side: single player and any server with this mod). Every RTS game is written to
 * <game dir>/ronstrat_matches/<time>_server.jsonl, one JSON object per line, for tools/game_report.py:
 *  start  - map, the players (name, faction, team colour, computer or human)
 *  snap   - every 10 s for every player: banked food/wood/ore, gathered and spent so far, population and cap,
 *           workers by job (food/wood/ore/build/idle), the army by unit type and its population, buildings
 *           finished and under construction by type, the army's centre and spread
 *  bld    - a building placed / finished / destroyed (owner, type, position)
 *  born   - a unit made (owner, type)
 *  death  - a unit died (owner, type, position, the killer's owner and type, cause)
 *  res    - a research finished (owner, item)
 *  end    - the game is over: who is still in
 * Time t is RON's game clock in seconds. Gathered / spent come from the change of each player's bank every
 * tick (a rise = a delivery, a fall = spending), so they hold without any hook into RON.
 * Nothing is shown in game and nothing leaves the machine. Off in the test harness (it has its own telemetry).
 */
@Mod.EventBusSubscriber(modid = RonStrategist.MODID)
public final class GameRecorder {
    private GameRecorder() {}

    private static BufferedWriter out;
    private static boolean wasActive;
    private static long tick, lastSnap = -100000;
    private static final Map<BlockPos, String> seen = new HashMap<>();
    private static final Set<BlockPos> finished = new HashSet<>();
    private static final Set<Integer> bornSeen = new HashSet<>();
    private static final Map<String, int[]> last = new HashMap<>();            // name -> bank f,w,o
    private static final Map<String, long[]> flow = new HashMap<>();           // name -> got f,w,o, spent f,w,o
    private static final Map<String, Set<String>> researchSeen = new HashMap<>();

    static boolean enabled() { return (!AutoTest.enabled() || Boolean.getBoolean("ronstrat.recorder")) && !Boolean.getBoolean("ronstrat.noRecorder"); }

    private static int t() { return (int) (PlayerServerEvents.rtsGameTicks / 20); }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !enabled()) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        try {
            boolean active = PlayerServerEvents.isGameActive() && !PlayerServerEvents.rtsPlayers.isEmpty();
            if (active && !wasActive) open(server);
            if (!active && wasActive) { end(); close(); }
            wasActive = active;
            if (!active || out == null) return;
            tick++;
            flows();
            if (tick % 10 == 0) { buildings(); births(); }
            if (tick - lastSnap >= 200) { lastSnap = tick; snaps(); research(); out.flush(); }
        } catch (Exception ex) {
            RonStrategist.LOG.warn("RON Strategist: game recorder stopped ({})", ex.toString());
            close();
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (out == null || !(e.getEntity() instanceof Unit u) || e.getEntity().level().isClientSide) return;
        try {
            LivingEntity v = e.getEntity();
            Entity k = e.getSource().getEntity();
            String ko = k instanceof Unit ku && ku.getOwnerName() != null ? ku.getOwnerName() : "";
            String kt = k == null ? "" : type(k);
            write("{\"t\":" + t() + ",\"ev\":\"death\",\"owner\":" + q(owner(u)) + ",\"u\":" + q(type(v)) + ",\"x\":" + v.getBlockX() + ",\"z\":" + v.getBlockZ()
                    + ",\"by\":" + q(ko) + ",\"byU\":" + q(kt) + ",\"cause\":" + q(e.getSource().getMsgId()) + "}");
        } catch (IOException ignored) {}
    }

    private static void open(MinecraftServer server) throws IOException {
        close();
        Path dir = server.getServerDirectory().toPath().resolve("ronstrat_matches");
        Files.createDirectories(dir);
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "_server.jsonl";
        out = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
        seen.clear(); finished.clear(); bornSeen.clear(); last.clear(); flow.clear(); researchSeen.clear();
        tick = 0; lastSnap = -100000;
        StringBuilder sb = new StringBuilder("{\"t\":" + t() + ",\"ev\":\"start\",\"map\":" + q(server.getWorldData().getLevelName()) + ",\"players\":[");
        boolean first = true;
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(q(p.name)).append(",\"faction\":").append(q(p.faction == null ? "" : p.faction.getName()))
                        .append(",\"color\":").append(p.startPosColorId).append(",\"bot\":").append(BotManager.isBot(p.name)).append('}');
            }
        }
        write(sb.append("]}").toString());
        // what was there before the recorder saw the game (the start capitols) counts as placed at t=0
        RonStrategist.LOG.info("RON Strategist: recording this game to {}", dir.resolve(name));
    }

    private static void end() throws IOException {
        StringBuilder sb = new StringBuilder("{\"t\":" + t() + ",\"ev\":\"end\",\"alive\":[");
        boolean first = true;
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) { if (!first) sb.append(','); first = false; sb.append(q(p.name)); }
        }
        write(sb.append("]}").toString());
    }

    private static void close() {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
        out = null;
    }

    /** bank changes every tick: a rise is a delivery (gathered), a fall is spending */
    private static void flows() {
        {
            for (Resources r : new ArrayList<>(ResourcesServerEvents.resourcesList)) {
                int[] now = { r.food, r.wood, r.ore };
                int[] was = last.put(r.ownerName, now);
                if (was == null) continue;
                long[] f = flow.computeIfAbsent(r.ownerName, k -> new long[6]);
                for (int i = 0; i < 3; i++) {
                    int d = now[i] - was[i];
                    if (d > 0) f[i] += d; else f[3 + i] -= d;
                }
            }
        }
    }

    private static void buildings() throws IOException {
        Set<BlockPos> now = new HashSet<>();
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (b == null || b.getBuilding() == null || b.isDestroyedServerside) continue;
            BlockPos o = b.originPos;
            now.add(o);
            String owner = b.ownerName == null ? "" : b.ownerName, what = Builder.name(b.getBuilding());
            String key = owner + "|" + what;
            if (!key.equals(seen.get(o))) {
                if (seen.containsKey(o)) { // the same spot, another building: a piglin portal transformed
                    write("{\"t\":" + t() + ",\"ev\":\"bld\",\"what\":\"changed\",\"owner\":" + q(owner) + ",\"b\":" + q(what) + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
                } else {
                    write("{\"t\":" + t() + ",\"ev\":\"bld\",\"what\":\"placed\",\"owner\":" + q(owner) + ",\"b\":" + q(what) + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
                }
                seen.put(o, key);
            }
            if (b.isBuilt && finished.add(o))
                write("{\"t\":" + t() + ",\"ev\":\"bld\",\"what\":\"built\",\"owner\":" + q(owner) + ",\"b\":" + q(what) + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
        }
        for (BlockPos o : new ArrayList<>(seen.keySet())) {
            if (now.contains(o)) continue;
            String[] ow = seen.remove(o).split("\\|", 2);
            finished.remove(o);
            write("{\"t\":" + t() + ",\"ev\":\"bld\",\"what\":\"gone\",\"owner\":" + q(ow[0]) + ",\"b\":" + q(ow.length > 1 ? ow[1] : "") + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
        }
    }

    private static void births() throws IOException {
        for (LivingEntity e : new ArrayList<>(UnitServerEvents.getAllUnits())) {
            if (!(e instanceof Unit u) || !e.isAlive() || !bornSeen.add(e.getId())) continue;
            if (tick <= 10) continue; // the start units are not "made"
            write("{\"t\":" + t() + ",\"ev\":\"born\",\"owner\":" + q(owner(u)) + ",\"u\":" + q(type(e)) + "}");
        }
    }

    private static void snaps() throws IOException {
        Map<String, Map<String, Integer>> army = new TreeMap<>(), bdone = new TreeMap<>(), bbuild = new TreeMap<>();
        Map<String, int[]> jobs = new HashMap<>();          // food wood ore build idle
        Map<String, double[]> centre = new HashMap<>();     // x, z, n, pop
        Map<String, List<double[]>> pos = new HashMap<>();
        for (LivingEntity e : new ArrayList<>(UnitServerEvents.getAllUnits())) {
            if (!(e instanceof Unit u) || !e.isAlive()) continue;
            String o = owner(u);
            if (o.isEmpty()) continue;
            if (UnitEval.isWorker(e) && e instanceof WorkerUnit w) {
                jobs.computeIfAbsent(o, k -> new int[5])[job(w, (Mob) e)]++;
            } else if (!UnitEval.isScout(e)) {
                army.computeIfAbsent(o, k -> new TreeMap<>()).merge(type(e), 1, Integer::sum);
                double[] c = centre.computeIfAbsent(o, k -> new double[4]);
                c[0] += e.getX(); c[1] += e.getZ(); c[2]++; c[3] += UnitEval.popValue(e);
                pos.computeIfAbsent(o, k -> new ArrayList<>()).add(new double[] { e.getX(), e.getZ() });
            }
        }
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (b == null || b.getBuilding() == null || b.isDestroyedServerside || b.ownerName == null) continue;
            (b.isBuilt ? bdone : bbuild).computeIfAbsent(b.ownerName, k -> new TreeMap<>()).merge(Builder.name(b.getBuilding()), 1, Integer::sum);
        }
        List<String> names = new ArrayList<>();
        synchronized (PlayerServerEvents.rtsPlayers) { for (RTSPlayer p : PlayerServerEvents.rtsPlayers) names.add(p.name); }
        for (String n : names) {
            int[] bank = last.getOrDefault(n, new int[3]);
            long[] f = flow.getOrDefault(n, new long[6]);
            int[] j = jobs.getOrDefault(n, new int[5]);
            double[] c = centre.get(n);
            StringBuilder sb = new StringBuilder("{\"t\":" + t() + ",\"ev\":\"snap\",\"p\":" + q(n));
            sb.append(",\"bank\":[").append(bank[0]).append(',').append(bank[1]).append(',').append(bank[2]).append(']');
            sb.append(",\"got\":[").append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append(']');
            sb.append(",\"spent\":[").append(f[3]).append(',').append(f[4]).append(',').append(f[5]).append(']');
            sb.append(",\"pop\":").append(UnitServerEvents.getCurrentPopulation(n)).append(",\"cap\":").append(BuildingServerEvents.getTotalPopulationSupply(n));
            sb.append(",\"workers\":").append(j[0] + j[1] + j[2] + j[3] + j[4]);
            sb.append(",\"jobs\":{\"food\":").append(j[0]).append(",\"wood\":").append(j[1]).append(",\"ore\":").append(j[2])
                    .append(",\"build\":").append(j[3]).append(",\"idle\":").append(j[4]).append('}');
            sb.append(",\"army\":").append(map(army.get(n)));
            if (c != null && c[2] > 0) {
                double x = c[0] / c[2], z = c[1] / c[2], spread = 0;
                for (double[] p : pos.get(n)) spread += Math.hypot(p[0] - x, p[1] - z);
                sb.append(",\"armyPop\":").append((int) c[3]).append(",\"armyAt\":[").append((int) x).append(',').append((int) z).append(']')
                        .append(",\"armySpread\":").append((int) (spread / c[2]));
            }
            sb.append(",\"built\":").append(map(bdone.get(n))).append(",\"building\":").append(map(bbuild.get(n)));
            write(sb.append('}').toString());
        }
    }

    /** research finished since the last look (RON keeps them in a private list: read it by reflection) */
    @SuppressWarnings("unchecked")
    private static void research() throws IOException {
        List<?> items;
        try {
            var fld = com.solegendary.reignofnether.research.ResearchServerEvents.class.getDeclaredField("researchItems");
            fld.setAccessible(true);
            items = new ArrayList<>((List<?>) fld.get(null));
        } catch (Exception ex) { return; }
        for (Object o : items) {
            if (!(o instanceof com.mojang.datafixers.util.Pair<?, ?> pr)) continue;
            String who = String.valueOf(pr.getFirst()), what = String.valueOf(pr.getSecond());
            if (researchSeen.computeIfAbsent(who, k -> new HashSet<>()).add(what))
                write("{\"t\":" + t() + ",\"ev\":\"res\",\"owner\":" + q(who) + ",\"item\":" + q(what) + "}");
        }
    }

    private static int job(WorkerUnit w, Mob m) {
        if (w.getBuildRepairGoal() != null && w.getBuildRepairGoal().getBuildingTarget() != null) return 3;
        if (((Unit) w).getTargetGoal().getTarget() instanceof Animal) return 0;
        GatherResourcesGoal g = w.getGatherResourceGoal();
        if (g != null) {
            if (g.isFarming() || g.getTargetFarm() != null) return 0;
            ResourceName rn = g.getTargetResourceName();
            if (rn == ResourceName.FOOD) return 0;
            if (rn == ResourceName.WOOD) return 1;
            if (rn == ResourceName.ORE) return 2;
        }
        return 4;
    }

    private static String owner(Unit u) { return u.getOwnerName() == null ? "" : u.getOwnerName(); }

    private static String type(Entity e) { return net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath().replace("_unit", ""); }

    private static String map(Map<String, Integer> m) {
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var en : m.entrySet()) { if (!first) sb.append(','); first = false; sb.append(q(en.getKey())).append(':').append(en.getValue()); }
        return sb.append('}').toString();
    }

    private static void write(String line) throws IOException {
        if (out == null) return;
        out.write(line);
        out.newLine();
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
