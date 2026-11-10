package com.ronstrat;

import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.fogofwar.FogOfWarServerEvents;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.resources.Resources;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the bot knows. Rebuilt every think from the world, limited to what its own units and
 * buildings can see while fog of war is on, plus a memory of enemy bases it has scouted.
 */
public final class Intel {
    public static final class EnemyMemory {
        public final String name;
        public final Map<BlockPos, Long> buildings = new HashMap<>(); // building origin -> last seen tick
        public BlockPos capitol;
        public double armyStrength;     // last observed, decays
        public int armyCount;
        public long lastSeenArmy = -100000;
        public boolean alive = true;
        /** enemy military units we have laid eyes on, and when */
        public final Map<Integer, LivingEntity> seenUnits = new HashMap<>();
        public final Map<Integer, Long> seenAt = new HashMap<>();

        /** strength of the army we know about: units seen in the last few minutes that we did not see die */
        public double knownStrength(long now) {
            List<LivingEntity> live = new ArrayList<>();
            seenUnits.entrySet().removeIf(e -> {
                LivingEntity le = e.getValue();
                long at = seenAt.getOrDefault(e.getKey(), 0L);
                if (!le.isAlive() || le.isRemoved() || now - at > 20 * 240) { seenAt.remove(e.getKey()); return true; }
                return false;
            });
            live.addAll(seenUnits.values());
            return UnitEval.strength(live);
        }

        EnemyMemory(String name) { this.name = name; }

        public BlockPos anyBuilding() {
            if (capitol != null) return capitol;
            for (BlockPos p : buildings.keySet()) return p;
            return null;
        }
    }

    private final Bot bot;
    public final List<Mob> workers = new ArrayList<>();
    public final List<Mob> army = new ArrayList<>();
    public final List<Mob> scouts = new ArrayList<>();
    public final List<Mob> garrisoned = new ArrayList<>();
    public final List<Mob> militia = new ArrayList<>();
    public final List<BuildingPlacement> own = new ArrayList<>();
    public final Map<Building, Integer> built = new HashMap<>();
    public final Map<Building, Integer> total = new HashMap<>();
    public BuildingPlacement capitol;
    public int food, wood, ore, emerald;
    public int pop, popCap;
    public final List<LivingEntity> visibleEnemies = new ArrayList<>();
    /** neutral map monsters (unowned units: wolves, bears...) seen near us, remembered for a while */
    public final List<LivingEntity> neutrals = new ArrayList<>();
    private final Map<Integer, BlockPos> neutralPos = new HashMap<>();
    private final Map<Integer, Long> neutralTick = new HashMap<>();


    /** where a worker was just killed by a monster we never saw: avoid it for a while too */
    private final Map<BlockPos, Long> deathSpots = new HashMap<>();
    private long lastTick;

    public void markDanger(BlockPos p) { deathSpots.put(p, lastTick); }

    /** the army went there and found nothing: forget that danger */
    public void forgetDanger(BlockPos p, double r) {
        double r2 = r * r;
        deathSpots.keySet().removeIf(n -> n.distSqr(p) <= r2);
        neutralPos.values().removeIf(n -> n.distSqr(p) <= r2);
    }
    /** nearest remembered monster position or monster-kill spot within r of p, or null */
    public BlockPos nearestDanger(BlockPos p, double r) {
        BlockPos best = null;
        double bd = r * r;
        for (BlockPos n : deathSpots.keySet()) if (n.distSqr(p) < bd) { bd = n.distSqr(p); best = n; }
        for (BlockPos n : neutralPos.values()) if (n.distSqr(p) < bd) { bd = n.distSqr(p); best = n; }
        return best;
    }

    /** the latest spot within r of p where a monster killed one of ours in the last `within` ticks */
    public BlockPos recentDeath(BlockPos p, double r, long within) {
        BlockPos best = null;
        long bt = Long.MIN_VALUE;
        for (Map.Entry<BlockPos, Long> e : deathSpots.entrySet())
            if (lastTick - e.getValue() <= within && e.getKey().distSqr(p) < r * r && e.getValue() > bt) { bt = e.getValue(); best = e.getKey(); }
        return best;
    }

    /** a remembered neutral monster within r of p: workers keep away from there */
    public boolean dangerous(BlockPos p, double r) {
        if (bot.difficulty != Difficulty.EXPERT) return false; // the lower tiers stay as naive as they were
        double r2 = r * r;
        // a monster merely seen: keep a small berth only - a wide one once monsters have been killing villagers
        // (camps killed 4-6 villagers per game in the first ten minutes on Mir's: the wide berth from the start)
        double cap = 18;
        double rn = Math.min(r, cap) * Math.min(r, cap);
        for (BlockPos n : neutralPos.values()) if (n.distSqr(p) <= rn) return true;
        for (BlockPos n : deathSpots.keySet()) if (n.distSqr(p) <= r2) return true;
        // a camp too strong for our soldiers stays dangerous after its memory fades, until it is cleared
        if (bot.army != null && bot.army.nearStrongCamp(p, Math.max(r, 22))) return true;
        return false;
    }
    public final List<BuildingPlacement> visibleEnemyBuildings = new ArrayList<>();
    public final Map<String, EnemyMemory> enemies = new HashMap<>();
    public final Set<String> allies = new HashSet<>();
    public boolean fog;

    Intel(Bot bot) { this.bot = bot; }

    public int built(Building b) { return built.getOrDefault(b, 0); }
    public int total(Building b) { return total.getOrDefault(b, 0); }

    public BlockPos home() {
        return capitol != null ? capitol.centrePos : bot.startPos;
    }

    private record Eye(double x, double z, double r2) {}

    void refresh(long tick) {
        workers.clear(); army.clear(); scouts.clear(); garrisoned.clear(); militia.clear(); own.clear(); built.clear(); total.clear();
        visibleEnemies.clear(); visibleEnemyBuildings.clear(); allies.clear();
        capitol = null;
        fog = FogOfWarServerEvents.isEnabled();
        String me = bot.name;

        Set<String> enemyNames = new HashSet<>();
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) {
                if (p.name.equals(me)) continue;
                if (AlliancesServerEvents.isAllied(me, p.name)) allies.add(p.name);
                else enemyNames.add(p.name);
            }
        }
        for (String n : enemyNames) enemies.computeIfAbsent(n, EnemyMemory::new).alive = true;
        for (EnemyMemory m : enemies.values()) if (!enemyNames.contains(m.name)) m.alive = false;

        List<Eye> eyes = new ArrayList<>();
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (!me.equals(b.ownerName)) continue;
            own.add(b);
            total.merge(b.getBuilding(), 1, Integer::sum);
            if (b.isBuilt) built.merge(b.getBuilding(), 1, Integer::sum);
            // home is the first capitol (the oldest built one), not whichever expansion finished last
            if (b.isCapitol && (capitol == null || (!capitol.isBuilt && b.isBuilt))) capitol = b;
            int r = b.getSightRange();
            eyes.add(new Eye(b.centrePos.getX(), b.centrePos.getZ(), (double) r * r));
        }

        List<LivingEntity> allUnits = new ArrayList<>(UnitServerEvents.getAllUnits());
        for (LivingEntity e : allUnits) {
            if (!(e instanceof Unit u) || !e.isAlive()) continue;
            if (!me.equals(u.getOwnerName())) continue;
            Mob m = (Mob) e;
            if (UnitEval.isWorker(e)) workers.add(m);
            else if (UnitEval.isScout(e)) scouts.add(m);
            else if (e instanceof com.solegendary.reignofnether.unit.units.villagers.MilitiaUnit) militia.add(m);
            else if (u.isGarrisoned()) garrisoned.add(m);
            else army.add(m);
            int r = Math.max(8, u.getSightRange());
            eyes.add(new Eye(e.getX(), e.getZ(), (double) r * r));
        }

        for (LivingEntity e : allUnits) {
            if (!(e instanceof Unit u) || !e.isAlive()) continue;
            String owner = u.getOwnerName();
            if (!enemyNames.contains(owner)) continue;
            if (fog && !seen(eyes, e.getX(), e.getZ())) continue;
            visibleEnemies.add(e);
        }
        lastTick = tick;
        deathSpots.values().removeIf(t -> tick - t > 6000);
        neutrals.clear();
        java.util.Set<Integer> aliveIds = new java.util.HashSet<>();
        for (LivingEntity e : allUnits) {
            if (!(e instanceof Unit u) || !e.isAlive()) continue;
            String owner = u.getOwnerName();
            if (owner != null && !owner.isEmpty()) continue;
            aliveIds.add(e.getId());
            if (fog && !seen(eyes, e.getX(), e.getZ())) continue;
            neutrals.add(e);
            neutralPos.put(e.getId(), e.blockPosition());
            neutralTick.put(e.getId(), tick);
        }
        neutralPos.keySet().removeIf(id -> !aliveIds.contains(id) || tick - neutralTick.getOrDefault(id, 0L) > 6000);
        Map<String, List<LivingEntity>> seenArmies = new HashMap<>();
        for (LivingEntity e : visibleEnemies) {
            if (UnitEval.isWorker(e)) continue;
            seenArmies.computeIfAbsent(((Unit) e).getOwnerName(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<LivingEntity>> en : seenArmies.entrySet()) {
            EnemyMemory m = enemies.get(en.getKey());
            for (LivingEntity e : en.getValue()) { m.seenUnits.put(e.getId(), e); m.seenAt.put(e.getId(), tick); }
            double s = UnitEval.strength(en.getValue());
            // keep the larger of the fresh sighting and the decayed memory: a partial sighting
            // must not convince the bot the enemy has no army
            double decayed = m.armyStrength * Math.pow(0.5, (tick - m.lastSeenArmy) / 2400.0);
            m.armyStrength = Math.max(s, decayed);
            m.armyCount = Math.max(en.getValue().size(), m.armyCount);
            m.lastSeenArmy = tick;
        }

        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (!enemyNames.contains(b.ownerName)) continue;
            EnemyMemory m = enemies.get(b.ownerName);
            boolean vis = !fog || seen(eyes, b.centrePos.getX(), b.centrePos.getZ());
            if (vis) {
                visibleEnemyBuildings.add(b);
                m.buildings.put(b.originPos, tick);
                if (b.isCapitol) m.capitol = b.centrePos;
            }
        }
        // forget destroyed buildings we can see are gone (or all of them without fog)
        for (EnemyMemory m : enemies.values()) {
            m.buildings.keySet().removeIf(origin -> {
                BuildingPlacement live = null;
                for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
                    if (b.originPos.equals(origin) && m.name.equals(b.ownerName)) { live = b; break; }
                }
                if (live != null) return false;
                return !fog || seen(eyes, origin.getX(), origin.getZ());
            });
            if (m.capitol != null && !m.buildings.isEmpty()) {
                boolean stillThere = false;
                for (BlockPos o : m.buildings.keySet()) {
                    BuildingPlacement b = find(o);
                    if (b != null && b.isCapitol) { stillThere = true; break; }
                }
                if (!stillThere) m.capitol = null;
            } else if (m.buildings.isEmpty()) {
                m.capitol = null;
            }
        }

        Resources r = null;
        for (Resources res : ResourcesServerEvents.resourcesList) if (res.ownerName.equals(me)) { r = res; break; }
        if (r != null) { food = r.food; wood = r.wood; ore = r.ore; emerald = r.emerald; }
        pop = UnitServerEvents.getCurrentPopulation(me);
        popCap = BuildingServerEvents.getTotalPopulationSupply(me);
    }

    private static BuildingPlacement find(BlockPos origin) {
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) if (b.originPos.equals(origin)) return b;
        return null;
    }

    private static boolean seen(List<Eye> eyes, double x, double z) {
        for (Eye e : eyes) {
            double dx = e.x - x, dz = e.z - z;
            if (dx * dx + dz * dz <= e.r2) return true;
        }
        return false;
    }

    public List<LivingEntity> enemiesNear(Vec3 p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        double r2 = r * r;
        for (LivingEntity e : visibleEnemies) if (e.position().distanceToSqr(p) <= r2) out.add(e);
        return out;
    }

    public boolean canAfford(int f, int w, int o) { return food >= f && wood >= w && ore >= o; }
}
