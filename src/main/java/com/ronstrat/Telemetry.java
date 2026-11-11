package com.ronstrat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayerScoresEnum;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player measurements for the self-play harness, read from the game itself (units, buildings and
 * stock by owner name) and not from the bot, so that the current brain and a frozen older one are
 * measured the same way. Sampled every 10 game seconds; written into the result line as
 * teams[][].metrics and summarised by tools/metrics.py.
 */
public final class Telemetry {
    private static final int STEP = 200;          // ticks between samples
    private static final int SERIES_EVERY = 3;    // every third sample (30 s) goes into the series
    private static final Map<String, Telemetry> ALL = new HashMap<>();

    private int samples, lowFood, lowWood, lowOre, highFood, highWood, highOre;
    private int tcSamples, tcIdle;                 // while the villager count is under 40
    private long idleWorkerSum, workerSum;
    private int peakWorkers, peakArmy, retreatsSeen;
    private final Map<String, Integer> firstBuilt = new LinkedHashMap<>(); // building class -> second its first one was placed
    private final Map<String, Integer> reached = new LinkedHashMap<>();    // milestone -> second
    private final List<int[]> series = new ArrayList<>(); // t, workers, army, food, wood, ore, farms, barracks, armoured, enchanted, idle
    private final List<int[]> arrivals = new ArrayList<>(); // t, soldiers near enemy buildings (each time an attack arrives)
    private boolean atEnemy;
    private long leftEnemyAt = -100000;

    public static void reset() { ALL.clear(); }

    /** call every tick of a running match */
    public static void tick(long gameTicks, Iterable<Agent> agents) {
        if (gameTicks % STEP != 0) return;
        List<LivingEntity> units = new ArrayList<>(UnitServerEvents.getAllUnits());
        List<BuildingPlacement> buildings = new ArrayList<>(BuildingServerEvents.getBuildings());
        for (Agent a : agents) {
            if (!BotManager.isRtsName(a.name())) continue;
            try {
                ALL.computeIfAbsent(a.name(), k -> new Telemetry()).sample((int) (gameTicks / 20), a, units, buildings);
            } catch (Throwable t) {
                RonStrategist.LOG.warn("telemetry failed for {}: {}", a.name(), t.toString());
            }
        }
    }

    private void sample(int sec, Agent a, List<LivingEntity> units, List<BuildingPlacement> buildings) {
        String me = a.name();
        int workers = 0, idle = 0, army = 0, armoured = 0, enchanted = 0;
        List<LivingEntity> soldiers = new ArrayList<>();
        for (LivingEntity e : units) {
            if (!(e instanceof Unit u) || !e.isAlive() || !me.equals(u.getOwnerName())) continue;
            if (e instanceof WorkerUnit w) {
                workers++;
                if (WorkerUnit.isIdle(w)) idle++;
            } else if (!u.isScout() && !(e instanceof com.solegendary.reignofnether.unit.units.villagers.MilitiaUnit)) {
                army++;
                soldiers.add(e);
                if (e.hasItemInSlot(EquipmentSlot.CHEST)) armoured++;
                if (e.getMainHandItem().isEnchanted()) enchanted++;
            }
        }
        int farms = 0, barracks = 0, mine = 0;
        boolean tcBuilt = false, tcBusy = false;
        List<BuildingPlacement> hostile = new ArrayList<>();
        for (BuildingPlacement b : buildings) {
            if (me.equals(b.ownerName)) {
                String kind = b.getBuilding().getClass().getSimpleName();
                firstBuilt.putIfAbsent(kind, sec);
                mine++;
                if (kind.contains("Farm")) farms++;
                if (kind.equals("Barracks")) barracks++;
                if (b.isCapitol && b.isBuilt && b instanceof ProductionPlacement pp) {
                    tcBuilt = true;
                    if (!pp.productionQueue.isEmpty()) tcBusy = true;
                }
            } else if (b.ownerName != null && !b.ownerName.isEmpty() && !AlliancesServerEvents.isAllied(me, b.ownerName)) {
                hostile.add(b);
            }
        }
        Resources r = null;
        for (Resources res : ResourcesServerEvents.resourcesList) if (res.ownerName.equals(me)) { r = res; break; }
        int food = r == null ? 0 : r.food, wood = r == null ? 0 : r.wood, ore = r == null ? 0 : r.ore;

        samples++;
        if (food < 50) lowFood++;
        if (wood < 50) lowWood++;
        if (ore < 50) lowOre++;
        if (food > 800) highFood++;
        if (wood > 800) highWood++;
        if (ore > 800) highOre++;
        if (tcBuilt && workers < 40) { tcSamples++; if (!tcBusy) tcIdle++; }
        idleWorkerSum += idle;
        workerSum += workers;
        peakWorkers = Math.max(peakWorkers, workers);
        peakArmy = Math.max(peakArmy, army);
        for (int n : new int[] { 20, 30, 38 }) if (workers >= n) reached.putIfAbsent("workers" + n, sec);
        for (int n : new int[] { 1, 10, 20, 30 }) if (army >= n) reached.putIfAbsent("army" + n, sec);
        if (armoured > 0) reached.putIfAbsent("armour", sec);
        if (enchanted > 0) reached.putIfAbsent("enchant", sec);

        // an attack "arrives" when four or more soldiers stand within 40 blocks of a hostile building
        int near = 0;
        for (LivingEntity s : soldiers) {
            for (BuildingPlacement b : hostile) {
                double dx = s.getX() - b.centrePos.getX(), dz = s.getZ() - b.centrePos.getZ();
                if (dx * dx + dz * dz <= 40 * 40) { near++; break; }
            }
        }
        if (near >= 4) {
            if (!atEnemy && sec * 20L - leftEnemyAt > 20 * 60) {
                arrivals.add(new int[] { sec, near });
                reached.putIfAbsent("attack", sec);
            }
            atEnemy = true;
        } else if (atEnemy && near == 0) {
            atEnemy = false;
            leftEnemyAt = sec * 20L;
        }
        retreatsSeen = (int) a.stat("retreats");

        if (samples % SERIES_EVERY == 0)
            series.add(new int[] { sec, workers, army, food, wood, ore, farms, barracks, armoured, enchanted, idle, mine,
                    got(me, RTSPlayerScoresEnum.FOOD_HARVESTED), got(me, RTSPlayerScoresEnum.WOOD_HARVESTED), got(me, RTSPlayerScoresEnum.ORES_HARVESTED) });
    }

    /** what the player has gathered so far (RON's own score sheet) */
    private static int got(String name, RTSPlayerScoresEnum what) {
        var rp = PlayerServerEvents.getRTSPlayer(name);
        return rp == null ? 0 : rp.scores.scoreList.getOrDefault(what, 0);
    }

    /** the metrics of one player for the result line (null when it was never sampled) */
    public static JsonObject json(String name) {
        Telemetry t = ALL.get(name);
        if (t == null || t.samples == 0) return null;
        JsonObject o = new JsonObject();
        double n = t.samples;
        o.addProperty("samples", t.samples);
        o.addProperty("lowFood", round(t.lowFood / n));
        o.addProperty("lowWood", round(t.lowWood / n));
        o.addProperty("lowOre", round(t.lowOre / n));
        o.addProperty("highFood", round(t.highFood / n));
        o.addProperty("highWood", round(t.highWood / n));
        o.addProperty("highOre", round(t.highOre / n));
        o.addProperty("tcIdle", t.tcSamples == 0 ? 0 : round(t.tcIdle / (double) t.tcSamples));
        o.addProperty("idleWorkers", t.workerSum == 0 ? 0 : round(t.idleWorkerSum / (double) t.workerSum));
        o.addProperty("peakWorkers", t.peakWorkers);
        o.addProperty("peakArmy", t.peakArmy);
        o.addProperty("retreats", t.retreatsSeen);
        JsonObject fb = new JsonObject();
        t.firstBuilt.forEach(fb::addProperty);
        o.add("firstBuilt", fb);
        JsonObject re = new JsonObject();
        t.reached.forEach(re::addProperty);
        o.add("reached", re);
        o.add("arrivals", rows(t.arrivals));
        o.addProperty("seriesColumns", "t,workers,army,food,wood,ore,farms,barracks,armoured,enchanted,idle,buildings,gotFood,gotWood,gotOre");
        o.add("series", rows(t.series));
        return o;
    }

    private static JsonArray rows(List<int[]> rows) {
        JsonArray out = new JsonArray();
        for (int[] row : rows) {
            JsonArray a = new JsonArray();
            for (int v : row) a.add(v);
            out.add(a);
        }
        return out;
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }
}
