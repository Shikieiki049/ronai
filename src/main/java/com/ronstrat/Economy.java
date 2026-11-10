package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.buildings.placements.FarmPlacement;
import com.solegendary.reignofnether.resources.ResourceIndex;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourceSources;
import com.solegendary.reignofnether.unit.goals.GatherResourcesGoal;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Worker management. Mirrors what the strong AoE2 scripts do with gatherer percentages, but the
 * split is recomputed from what the bot actually wants to spend, never left static:
 * keep every worker busy, keep the walk to a drop site short, hunt early, farm later.
 */
public final class Economy {
    public enum Job { IDLE, FOOD, WOOD, ORE, BUILD, MILITARY }

    private final Bot bot;
    private final Map<Integer, Job> jobs = new HashMap<>();
    private final Map<Integer, Long> assignedAt = new HashMap<>();
    private final Map<Integer, Long> idleSince = new HashMap<>();
    private final Map<Integer, Long> stuckSince = new HashMap<>();
    /** where each gatherer was last sent, and when its pack last changed (= it is really gathering) */
    private final Map<Integer, BlockPos> assignedPos = new HashMap<>();
    private final Map<Integer, Integer> lastLoad = new HashMap<>();
    private final Map<Integer, Long> loadChangedAt = new HashMap<>();
    /** closest each gatherer has come to its block since it was sent there */
    private final Map<Integer, Double> approach = new HashMap<>();
    private final Map<Integer, BlockPos> approachTo = new HashMap<>();
    /** resource spots workers were sent to but never gathered from (unreachable): x, z, until */
    private final List<long[]> unreachable = new ArrayList<>();

    private boolean unreachableSpot(BlockPos p) {
        for (long[] u : unreachable) {
            if (bot.now < u[2] && Math.abs(p.getX() - u[0]) <= u[3] && Math.abs(p.getZ() - u[1]) <= u[3]) return true;
        }
        return false;
    }

    private static int load(Mob w) {
        int n = 0;
        for (var st : ((Unit) w).getItems()) n += st.getCount();
        return n;
    }
    public final EnumMap<Job, Integer> counts = new EnumMap<>(Job.class);
    /** a resource point that is too far from any drop site; the builder turns it into a stockpile */
    public BlockPos stockpileWanted;
    public ResourceName stockpileFor;
    public int farmsWanted;
    public int unreachableCount;
    public int animalsNearHome;
    public double[] lastShares = new double[3];

    Economy(Bot bot) { this.bot = bot; }

    public Job job(Mob w) { return jobs.getOrDefault(w.getId(), Job.IDLE); }

    /**
     * A villager was just killed by monsters at p: everyone working near there is pulled off and
     * re-assigned (assignments avoid remembered danger), instead of walking back into the same pack.
     */
    public void evacuate(BlockPos p) {
        if (bot.difficulty != Difficulty.EXPERT) return;
        // one or two wolves: a crowd of villagers kills them (what a human does) - running only gets
        // the villagers bitten one by one on the way home and leaves the wood line dead for minutes
        List<LivingEntity> beasts = new ArrayList<>();
        double beastHp = 0;
        for (LivingEntity e : bot.intel.neutrals) {
            if (!e.isAlive() || e.blockPosition().distSqr(p) > 14 * 14) continue;
            beasts.add(e);
            beastHp += e.getHealth();
        }
        if (!beasts.isEmpty() && beasts.size() <= 2 && beastHp <= 60) {
            List<Mob> crowd = new ArrayList<>();
            for (Mob w : bot.intel.workers) if (w.blockPosition().distSqr(p) < 20 * 20) crowd.add(w);
            if (crowd.size() >= 4) {
                crowd.sort(java.util.Comparator.comparingDouble(w -> w.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))));
                if (crowd.size() > 8) crowd = new ArrayList<>(crowd.subList(0, 8));
                LivingEntity target = beasts.get(0);
                for (Mob w : crowd) setJob(w, Job.IDLE);
                Orders.attack(bot.name, crowd, target);
                bot.log("monsters killed a villager at " + p.toShortString() + ": " + crowd.size() + " villagers fight "
                        + beasts.size() + " beast(s) hp " + (int) beastHp);
                return;
            }
        }
        int n = 0;
        for (Mob w : bot.intel.workers) {
            GatherResourcesGoal gg = ((WorkerUnit) w).getGatherResourceGoal();
            BlockPos t = gg != null && gg.getGatherTarget() != null ? gg.getGatherTarget() : assignedPos.get(w.getId());
            boolean near = w.blockPosition().distSqr(p) < 22 * 22 || t != null && t.distSqr(p) < 22 * 22;
            if (!near || gg != null && gg.isFarming()) continue;
            setJob(w, Job.IDLE);
            Orders.move(bot.name, List.of(w), bot.intel.home());
            n++;
        }
        if (n > 0) bot.log("monsters killed a villager at " + p.toShortString() + ": pulling " + n + " workers away (beasts " + beasts.size() + " hp " + (int) beastHp + ")");
    }

    public void setJob(Mob w, Job j) {
        jobs.put(w.getId(), j);
        assignedAt.put(w.getId(), bot.now);
        idleSince.remove(w.getId());
    }

    /** what the worker is really doing according to its RON goals */
    private Job observed(Mob m) {
        WorkerUnit w = (WorkerUnit) m;
        if (w.getBuildRepairGoal() != null && w.getBuildRepairGoal().getBuildingTarget() != null) return Job.BUILD;
        GatherResourcesGoal g = w.getGatherResourceGoal();
        LivingEntity t = ((Unit) m).getTargetGoal().getTarget();
        if (t instanceof Animal) return Job.FOOD;
        if (g != null) {
            if (g.isFarming() || g.getTargetFarm() != null) return Job.FOOD;
            ResourceName rn = g.getTargetResourceName();
            if (rn == ResourceName.FOOD) return Job.FOOD;
            if (rn == ResourceName.WOOD) return Job.WOOD;
            if (rn == ResourceName.ORE) return Job.ORE;
        }
        if (m.getNavigation().isInProgress() || ((Unit) m).getReturnResourcesGoal() != null
                && ((Unit) m).getReturnResourcesGoal().getBuildingTarget() != null) {
            return jobs.getOrDefault(m.getId(), Job.IDLE); // walking / dropping off: trust our record
        }
        return Job.IDLE;
    }

    private final Map<Integer, long[]> huntSince = new HashMap<>();
    private long fleeLog = -10000;
    static final java.util.Set<Integer> TEST_HOLD = new java.util.HashSet<>();
    private final java.util.Set<Integer> badPrey = new java.util.HashSet<>();
    /** where prey could not be caught (a pen): every animal standing there is off the list for 3 min */
    private final Map<BlockPos, Long> badPreySpots = new HashMap<>();

    private boolean badPrey(Animal a) {
        if (badPrey.contains(a.getId())) return true;
        for (Map.Entry<BlockPos, Long> e : badPreySpots.entrySet())
            if (bot.now - e.getValue() < 3600 && e.getKey().distSqr(a.blockPosition()) < 49) return true;
        return false;
    }

    /**
     * RON spawns the starting villagers 11 blocks from the start position, wherever that lands: on
     * 4Mountains seat 2 it is the top of an oak's crown, five blocks up. No path leads down, nobody can
     * build the town centre and the seat is dead (every tier, every version: "lost all buildings" at
     * 1:16). A spawn bug, not a game decision: in the first two minutes a villager standing on leaves
     * is set down on the ground below it.
     */
    private final Map<Integer, long[]> leafSince = new HashMap<>();
    private void unstickFromTrees(Intel in) {
        if (com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks > 2400) return;
        for (Mob w : in.workers) {
            BlockPos below = w.blockPosition().below();
            if (!(bot.level.getBlockState(below).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) { leafSince.remove(w.getId()); continue; }
            // a woodcutter steps on leaves all the time: only one that has stood up there for 10 s is stuck
            long[] ls = leafSince.computeIfAbsent(w.getId(), k -> new long[] { bot.now, w.getBlockX(), w.getBlockZ() });
            if (Math.abs(ls[1] - w.getBlockX()) + Math.abs(ls[2] - w.getBlockZ()) > 2) { ls[0] = bot.now; ls[1] = w.getBlockX(); ls[2] = w.getBlockZ(); }
            if (bot.now - ls[0] < 200) continue;
            int x = w.getBlockX(), z = w.getBlockZ();
            int y = bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y >= w.getBlockY()) continue;
            w.teleportTo(x + 0.5, y, z + 0.5);
            w.getNavigation().stop();
            bot.log("villager stuck on a tree crown at " + below.toShortString() + ": set down at y=" + y);
        }
    }

    /** at the population limit the army cannot grow: food stops mattering, upgrades and ore units do */
    boolean popMaxed(Intel in) { return in.popCap >= 150 && in.pop >= in.popCap - 10; }

    void think(Intel in) {
        BotParams p = bot.params;
        counts.clear();
        for (Job j : Job.values()) counts.put(j, 0);
        jobs.keySet().removeIf(id -> in.workers.stream().noneMatch(w -> w.getId() == id));

        List<Mob> idle = new ArrayList<>();
        Map<Job, List<Mob>> byJob = new EnumMap<>(Job.class);
        for (Job j : Job.values()) byJob.put(j, new ArrayList<>());
        unstickFromTrees(in);
        // a monster closing in on villagers: they run before the first one dies, not after (camps that
        // wander into the wood line killed 3-4 villagers per game in the first ten minutes)
        List<LivingEntity> chasers = new ArrayList<>();
        if (bot.difficulty == Difficulty.EXPERT)
            for (LivingEntity e : in.neutrals)
                if (e instanceof Mob m && m.getTarget() != null && m.getTarget().isAlive() && in.workers.contains(m.getTarget())) chasers.add(e);
        for (Mob w : in.workers) {
            if (TEST_HOLD.contains(w.getId())) continue; // autotest water drop: hands off these villagers
            Job recorded = jobs.getOrDefault(w.getId(), Job.IDLE);
            if (!chasers.isEmpty() && recorded != Job.MILITARY && !(((WorkerUnit) w).getGatherResourceGoal() != null && ((WorkerUnit) w).getGatherResourceGoal().isFarming())) {
                boolean hunted = false;
                for (LivingEntity c : chasers) if (c.distanceToSqr(w) < 12 * 12) { hunted = true; break; }
                if (hunted) {
                    if (bot.now - fleeLog > 200) { fleeLog = bot.now; bot.log("monsters closing in at " + w.blockPosition().toShortString() + ": villagers run home"); }
                    Orders.move(bot.name, List.of(w), in.home());
                    idle.add(w); jobs.put(w.getId(), Job.IDLE);
                    assignedAt.put(w.getId(), bot.now);
                    continue;
                }
            }
            if (recorded == Job.MILITARY) { byJob.get(Job.MILITARY).add(w); continue; }
            Job seen = observed(w);
            // standing around with a full pack (e.g. after a hunt, or pushed off its path): go drop it off
            if (seen == Job.IDLE && !w.getNavigation().isInProgress() && !((Unit) w).getItems().isEmpty()
                    && (((Unit) w).getReturnResourcesGoal() == null || ((Unit) w).getReturnResourcesGoal().getBuildingTarget() == null)) {
                long stuckFor = bot.now - stuckSince.computeIfAbsent(w.getId(), k -> bot.now);
                if (stuckFor >= 60) { Orders.returnResources(bot.name, List.of(w)); stuckSince.remove(w.getId()); }
            } else stuckSince.remove(w.getId());
            // RON's gather goal walks on to the next block by itself, which can lead straight into a
            // monster camp: pull such a worker out and give it a job somewhere safe
            // only a worker whose own work spot is in danger goes home: one merely walking past a spot
            // where monsters once killed someone would otherwise be sent back again and again (on a map
            // whose woods lie past a camp, wood income stopped for minutes)
            BlockPos workAt = assignedPos.get(w.getId());
            if (seen != Job.BUILD && in.dangerous(bot.difficulty == Difficulty.EXPERT && workAt != null ? workAt : w.blockPosition(), 20)
                    && bot.now - assignedAt.getOrDefault(w.getId(), -10000L) > 40) {
                Orders.move(bot.name, List.of(w), in.home());
                idle.add(w); jobs.put(w.getId(), Job.IDLE);
                continue;
            }
            // a hunter that has not caught its prey in 25 s cannot get at it (penned in, across water):
            // that animal is off the list and the worker takes other work (food stayed at 0 for 5 min)
            if (bot.difficulty.ordinal() >= Difficulty.HARD.ordinal() && ((Unit) w).getTargetGoal().getTarget() instanceof Animal prey && prey.isAlive() && !(prey instanceof Unit)) {
                long[] hs = huntSince.get(w.getId());
                // the clock restarts on every hit: a fleeing pig being chased down is fine
                long hp = (long) (prey.getHealth() * 10);
                if (hs == null || hs[0] != prey.getId() || hp < hs[2]) huntSince.put(w.getId(), new long[] { prey.getId(), bot.now, hp });
                else if (bot.now - hs[1] > 500) {
                    badPrey.add(prey.getId());
                    badPreySpots.put(prey.blockPosition(), bot.now);
                    huntSince.remove(w.getId());
                    bot.log("cannot catch " + prey.getType().toShortString() + " at " + prey.blockPosition().toShortString() + ", giving up on it");
                    Orders.move(bot.name, List.of(w), in.home());
                    idle.add(w); jobs.put(w.getId(), Job.IDLE);
                    continue;
                }
            } else huntSince.remove(w.getId());
            // a wood/ore gatherer whose pack has not changed for 30 s is not gathering: its tree or
            // ore vein is out of reach (cliff, fence, water). Remember the spot and send it elsewhere.
            if ((seen == Job.WOOD || seen == Job.ORE) && assignedPos.containsKey(w.getId())) {
                int ld = load(w);
                Integer prev = lastLoad.put(w.getId(), ld);
                GatherResourcesGoal gg = ((WorkerUnit) w).getGatherResourceGoal();
                // breaking a block (ore takes long) or a changed pack both count as real gathering
                if (prev == null || prev != ld || (gg != null && gg.isGathering())) loadChangedAt.put(w.getId(), bot.now);
                long quiet = bot.now - Math.max(loadChangedAt.getOrDefault(w.getId(), bot.now),
                        assignedAt.getOrDefault(w.getId(), bot.now));
                BlockPos cur = gg != null && gg.getGatherTarget() != null ? gg.getGatherTarget() : assignedPos.get(w.getId());
                // still closing in on a far block is a long walk, not a dead end
                double dd = Math.sqrt(cur.distSqr(w.blockPosition()));
                if (!cur.equals(approachTo.put(w.getId(), cur))) approach.remove(w.getId()); // moved on to another block
                Double bd = approach.get(w.getId());
                if (bd == null || dd < bd - 2) { approach.put(w.getId(), dd); loadChangedAt.put(w.getId(), bot.now); }
                quiet = bot.now - Math.max(loadChangedAt.getOrDefault(w.getId(), bot.now), assignedAt.getOrDefault(w.getId(), bot.now));
                // standing right at its block means it is working (or waiting on a drop-off), not lost
                var rg0 = ((Unit) w).getReturnResourcesGoal();
                if (quiet > 600 && ld >= 3 && rg0 != null && rg0.getBuildingTarget() != null && bot.useStockpilesNow()) {
                    // a full pack it cannot deliver: the drop site is what is out of reach (a town centre up on a
                    // ledge), not the tree. Put a stockpile down where the worker stands.
                    if (stockpileWanted == null || stockpileWanted.distSqr(w.blockPosition()) > 16 * 16) {
                        stockpileWanted = w.blockPosition();
                        stockpileFor = seen == Job.ORE ? ResourceName.ORE : ResourceName.WOOD;
                        bot.log("worker #" + w.getId() + " cannot deliver its load from " + w.blockPosition().toShortString() + ": stockpile wanted there");
                        deliveryTrouble = bot.now;
                    }
                    loadChangedAt.put(w.getId(), bot.now);
                    continue;
                }
                if (quiet > 600 && dd > 4) {
                    approach.remove(w.getId());
                    assignedPos.remove(w.getId());
                    BlockPos t = cur;
                    unreachable.removeIf(u -> bot.now >= u[2]);
                    // a second failure near an earlier one: the whole grove is cut off (hill, river), not one tree
                    int nearby = 0;
                    for (long[] u : unreachable) if (Math.abs(t.getX() - u[0]) <= 14 && Math.abs(t.getZ() - u[1]) <= 14) nearby++;
                    unreachable.add(nearby == 0 ? new long[] { t.getX(), t.getZ(), bot.now + 3600, 3 }
                            : new long[] { t.getX(), t.getZ(), bot.now + 6000, nearby >= 2 ? 16 : 10 });
                    var rg = ((Unit) w).getReturnResourcesGoal();
                    bot.log("worker #" + w.getId() + " got nothing from " + seen + " at " + t.toShortString() + ": skipping that spot"
                            + " [at " + w.blockPosition().toShortString() + " d=" + (int) Math.sqrt(cur.distSqr(w.blockPosition()))
                            + " load=" + ld + " nav=" + w.getNavigation().isInProgress()
                            + " gt=" + (gg.getGatherTarget() == null ? "null" : gg.getGatherTarget().toShortString())
                            + " ret=" + (rg != null && rg.getBuildingTarget() != null) + " quiet=" + quiet + "]");
                    unreachableCount++;
                    idle.add(w); jobs.put(w.getId(), Job.IDLE);
                    continue;
                }
            } else {
                // building, farming, walking home...: restart the clock when it gathers again
                lastLoad.remove(w.getId());
                loadChangedAt.put(w.getId(), bot.now);
            }
            boolean reallyIdle = seen == Job.IDLE && WorkerUnit.isIdle((WorkerUnit) w);
            if (reallyIdle) {
                long idleFor = bot.now - idleSince.computeIfAbsent(w.getId(), k -> bot.now);
                // a freshly ordered worker may need a moment to path; do not thrash it
                long since = bot.now - assignedAt.getOrDefault(w.getId(), -10000L);
                // idle for an instant between two blocks or after a drop-off is normal: only a worker
                // that has stood still for a while (and was not just given an order) needs a new job
                if (idleFor >= 30 && since > 60) { idle.add(w); jobs.put(w.getId(), Job.IDLE); idleSince.remove(w.getId()); continue; }
                seen = recorded;
            } else {
                idleSince.remove(w.getId());
            }
            if (seen == Job.IDLE) seen = recorded;
            jobs.put(w.getId(), seen);
            byJob.get(seen).add(w);
        }
        for (Job j : Job.values()) counts.put(j, byJob.get(j).size());
        counts.put(Job.IDLE, idle.size());

        // the lower tiers simply overlook idle villagers some of the time, like a distracted player
        if (!bot.attentive()) idle.clear();

        int gatherers = in.workers.size() - byJob.get(Job.BUILD).size() - byJob.get(Job.MILITARY).size();
        checkStagnation(in, byJob);
        double[] raw = desiredShares(in);
        // a resource that is gone (or out of reach) gets no workers until it shows up again
        for (int i = 0; i < 3; i++) if (bot.now < exhaustedUntil[i]) raw[i] = 0;
        // wood keeps the wheat farms replanting: never send every woodcutter to the fields
        if (bot.difficulty == Difficulty.EXPERT && in.built(bot.plan.farm) > 0 && bot.now >= exhaustedUntil[1]) raw[1] = Math.max(raw[1], 0.15);
        // early on food is what villagers are made of: keep at least half the gatherers on it while the
        // stock is low (the stock controller alone let wood, reserved for buildings, take 17 of 20)
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.workers.size() < 32 && in.food < 300 && bot.now >= exhaustedUntil[0]) {
            double rs0 = raw[0] + raw[1] + raw[2];
            if (rs0 > 0 && raw[0] / rs0 < 0.55) { double rest = rs0 - raw[0]; raw[0] = 0.55 * rest / 0.45; }
        }
        double rs = raw[0] + raw[1] + raw[2];
        if (rs > 0) for (int i = 0; i < 3; i++) raw[i] /= rs; else raw = desiredShares(in);
        // smooth the split: a worker walking between jobs gathers nothing
        if (smooth == null) smooth = raw.clone();
        for (int i = 0; i < 3; i++) smooth[i] += 0.15 * (raw[i] - smooth[i]);
        double[] share = smooth;
        lastShares = share;
        int[] want = new int[3];
        want[0] = (int) Math.round(gatherers * share[0]);
        want[2] = (int) Math.round(gatherers * share[2]);
        want[1] = Math.max(0, gatherers - want[0] - want[2]);
        // farms already built are the cheapest food there is: keep every one of them worked, and let
        // the market turn a food surplus into what is short, rather than leave fields standing empty
        if (bot.difficulty == Difficulty.EXPERT && in.food < (popMaxed(in) ? 400 : 1500)) {
            int fields = Math.min(in.built(bot.plan.farm), (int) (gatherers * 0.6));
            int extra = fields - want[0];
            if (extra > 0) {
                int minWood = (int) Math.ceil(gatherers * 0.15);
                int fromWood = Math.max(0, Math.min(extra, want[1] - minWood));
                want[1] -= fromWood; extra -= fromWood;
                int fromOre = Math.min(extra, want[2]);
                want[2] -= fromOre; extra -= fromOre;
                want[0] = fields - extra;
            }
        }
        int[] have = { byJob.get(Job.FOOD).size(), byJob.get(Job.WOOD).size(), byJob.get(Job.ORE).size() };

        // pull workers off surplus jobs when the split drifts too far
        int slack = p.i("rebalanceSlack");
        Job[] gj = { Job.FOOD, Job.WOOD, Job.ORE };
        boolean mayMove = bot.now - lastRebalance >= 300;
        for (int i = 0; i < 3 && mayMove; i++) {
            int deficit = Math.min(2, want[i] - have[i] - slack);
            if (deficit <= 0) continue;
            lastRebalance = bot.now;
            for (int k = 0; k < 3 && deficit > 0; k++) {
                if (k == i) continue;
                int surplus = have[k] - want[k];
                while (surplus > 0 && deficit > 0) {
                    Mob m = pickMovable(byJob.get(gj[k]));
                    if (m == null) break;
                    byJob.get(gj[k]).remove(m);
                    idle.add(m);
                    have[k]--; surplus--; deficit--;
                }
            }
        }

        // hand out jobs, neediest resource first
        for (Mob w : idle) {
            int best = -1;
            double bestGap = -1e9;
            for (int i = 0; i < 3; i++) {
                double gap = (want[i] - have[i]) / (double) Math.max(1, want[i]);
                if (want[i] == 0) gap = -1;
                if (gap > bestGap) { bestGap = gap; best = i; }
            }
            if (best < 0) best = 1;
            boolean ok = assign(in, w, gj[best]);
            if (!ok) {
                for (int i = 0; i < 3 && !ok; i++) if (i != best) ok = assign(in, w, gj[i]);
            }
            if (ok) have[jobIndex(job(w))]++;
        }
        farmsWanted = computeFarmsWanted(in, want[0]);
    }

    private final long[] farMissUntil = new long[3];
    /** a resource the economy would use but monsters guard (for the army), and when it was seen */
    public BlockPos blockedByDanger;
    /** last tick a worker could not deliver to any drop site (the town centre stands where they cannot reach) */
    public long deliveryTrouble = -100000;
    public long blockedAt = -100000;
    private long blockedCheck = -1000, bridgeCheck = -10000;
    private long lastFarLog = -10000;

    /**
     * A resource far from home is only worth it on our own side of the map: well away from every enemy
     * building we know of and every other start position (shown on the map to everyone), and nearer to
     * us than to any of them.
     */
    private boolean safeFar(Intel in, BlockPos pos) {
        BlockPos h = in.home();
        double dh = Math.sqrt(pos.distSqr(h));
        for (Intel.EnemyMemory m : in.enemies.values()) {
            if (!m.alive) continue;
            if (m.capitol != null && tooClose(pos, m.capitol, dh)) return false;
            for (BlockPos b : m.buildings.keySet()) if (tooClose(pos, b, dh)) return false;
        }
        for (var sp : com.solegendary.reignofnether.startpos.StartPosServerEvents.startPoses) {
            if (!sp.enabled || sp.pos.distSqr(bot.startPos) < 40 * 40) continue;
            if (sp.pos.distSqr(h) < 40 * 40) continue;
            if (tooClose(pos, sp.pos, dh)) return false;
        }
        return true;
    }

    private static boolean tooClose(BlockPos pos, BlockPos enemy, double fromHome) {
        double d = Math.sqrt(pos.distSqr(enemy));
        return d < 60 || d < fromHome;
    }

    private double[] smooth;
    final long[] exhaustedUntil = new long[3];
    private final int[] lastHarvest = { -1, -1, -1 };
    private long lastHarvestCheck;

    /** workers on a resource that has brought in nothing for two minutes are wasted: free them */
    private void checkStagnation(Intel in, Map<Job, List<Mob>> byJob) {
        if (bot.now - lastHarvestCheck < 2400) return;
        lastHarvestCheck = bot.now;
        var rp = com.solegendary.reignofnether.player.PlayerServerEvents.getRTSPlayer(bot.name);
        if (rp == null) return;
        var s = rp.scores.scoreList;
        int[] now = { s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.FOOD_HARVESTED, 0),
                s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.WOOD_HARVESTED, 0),
                s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.ORES_HARVESTED, 0) };
        Job[] gj = { Job.FOOD, Job.WOOD, Job.ORE };
        for (int i = 0; i < 3; i++) {
            if (lastHarvest[i] >= 0 && now[i] - lastHarvest[i] < 60 && byJob.get(gj[i]).size() >= 3) {
                exhaustedUntil[i] = bot.now + 2400;
                for (Mob w : byJob.get(gj[i])) setJob(w, Job.IDLE);
                bot.log("no " + gj[i] + " income for 2 min: freeing " + byJob.get(gj[i]).size() + " workers");
            }
            lastHarvest[i] = now[i];
        }
    }
    private final Map<Integer, Integer> stuck = new HashMap<>();
    private long lastRebalance = -1000;

    private static int jobIndex(Job j) {
        return j == Job.FOOD ? 0 : j == Job.ORE ? 2 : 1;
    }

    private Mob pickMovable(List<Mob> ms) {
        // move the worker that carries the least, so no resources are lost in transit
        return ms.stream()
                .filter(m -> !(((WorkerUnit) m).getGatherResourceGoal() != null && ((WorkerUnit) m).getGatherResourceGoal().isFarming()))
                .min(Comparator.comparingInt(m -> ((Unit) m).getItems().size()))
                .orElse(null);
    }

    /** food, wood, ore shares of the gathering workforce */
    double[] desiredShares(Intel in) {
        BotParams p = bot.params;
        int n = in.workers.size();
        double food, ore;
        // early game is all about food: every villager costs food and the start gives almost none
        if (n < 8) { food = 0.5; ore = 0.0; }
        else if (n < 14) { food = 0.45; ore = 0.05; }
        else { food = p.get("farmWorkersShare"); ore = p.get("oreShare"); }
        // even game: the army is vindicators (160 food) and pillagers (food + wood) - no ore at all. A human
        // mines none and puts the workers on fields; ore is only for the odd upgrade
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && n >= 14) { food = Math.max(food, 0.44); ore = Math.min(ore, 0.05); }
        // ...except a few miners from minute 5: golems (50 wood + 250 ore, no food) are the army food
        // cannot pay for when the first wave comes at minute 9-10 (Snowplow: the hard AI mined 1100 ore
        // by then and brought a golem; the expert had 3-5 soldiers and lost the base)
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && n >= 16
                && com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks > 6000
                && com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks < 18000) ore = Math.max(ore, 0.12);
        // no game to hunt near home: the opening lives on berries and the first fields - more hands on food
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && n < 14 && animalsNearHome == 0) food = Math.max(food, 0.6);
        double wood = 1 - food - ore;
        // stock controller: a resource piling up loses workers, a scarce one gains them
        // (the role sn-food/wood/gold-modifier rules play in AoE2 scripts, but continuous)
        int[] need = bot.builder.reservedCost();
        double scale = 1 + n / 25.0;
        double tf = 250 * scale + need[0], tw = 250 * scale + need[1], to = 120 * scale + need[2];
        // golems are paid in ore: keep enough coming once they can be built
        if (in.built(bot.plan.option(com.solegendary.reignofnether.building.production.ProductionItems.IRON_GOLEM).producer) > 0
                && p.get("golemShare") > 0.2) to = Math.max(to, bot.outnumbered() ? 900 : 600);
        if (bot.outnumbered() && p.get("golemShare") >= 0.3) ore = Math.max(ore, 0.22);
        // ore piling up while wood is short: golems (50 wood + 250 ore) are waiting for wood
        if (in.ore > 600 && p.get("golemShare") > 0.2) { tw += 300; ore = Math.min(ore, 0.08); }
        // population maxed: the army only replaces losses, banked food is wasted - wood and ore buy
        // armour, enchants and the ore units that pack the most fighting into each population slot
        if (bot.difficulty == Difficulty.EXPERT && popMaxed(in)) { tf = Math.min(tf, 300); to = Math.max(to, 500); tw = Math.max(tw, 400); ore = Math.max(ore, 0.2); }
        double k = 0.7;
        food *= 1 + k * clamp((tf - in.food) / tf);
        wood *= 1 + k * clamp((tw - in.wood) / tw);
        ore *= 1 + k * clamp((to - in.ore) / to);
        double sum = food + wood + ore;
        food /= sum; wood /= sum; ore /= sum;
        food = Math.max(0.2, Math.min(0.7, food));
        ore = Math.max(0.02, Math.min(0.4, ore));
        return new double[] { food, Math.max(0.1, 1 - food - ore), ore };
    }

    private static double clamp(double x) { return Math.max(-1, Math.min(1, x)); }

    private int computeFarmsWanted(Intel in, int foodWorkers) {
        // free food first (hunt, berries); farms cost wood and take time to grow
        int huntable = countAnimals(in, 40);
        animalsNearHome = huntable;
        int hunters = huntable == 0 ? 0 : Math.min(maxHunters(in), huntable);
        int foragers = 0;
        GatherResourcesGoal any = null;
        for (Mob w : in.workers) { any = ((WorkerUnit) w).getGatherResourceGoal(); if (any != null) break; }
        if (any != null) {
            GatherResourcesGoal g = any;
            BlockPos h = in.home();
            if (ResourceIndex.get(bot.level).findClosest(bot.level, h, 24, ResourceName.FOOD,
                    pos -> !com.solegendary.reignofnether.building.BuildingUtils.isPosInsideFarm(false, pos)
                            && g.canGather(pos, ResourceName.FOOD)).isPresent()) foragers = 2;
        }
        // hunting and foraging are a bonus: nearly every food worker still needs a farm to fall back on
        if (bot.difficulty != Difficulty.EXPERT) return Math.max(0, foodWorkers - hunters - foragers);
        int want = Math.max(Math.max(0, foodWorkers - 2), foodWorkers - hunters - foragers);
        // every wheat farm eats wood to replant: with no wood coming in, more farms only drain the
        // stock until none can replant (27 farms, 38 farmers and zero food in a timed-out game)
        // short of food: fields for nearly half the villagers, whatever the hunt promises
        if (in.food < 400 && !bot.outnumbered()) want = Math.max(want, (int) Math.round(in.workers.size() * 0.45) - Math.min(2, hunters));
        int farms = in.total(bot.plan.farm);
        if (bot.now < exhaustedUntil[1]) want = Math.min(want, farms);
        return Math.min(want, 20);
    }

    /** big game only: a chicken or rabbit is not worth a long walk */
    private static boolean worthHunting(Animal a) {
        return ResourceSources.isHuntableAnimal(a)
                && !(a instanceof net.minecraft.world.entity.animal.Chicken)
                && !(a instanceof net.minecraft.world.entity.animal.Rabbit);
    }

    /** hunt hard early (free food), then the farms carry the economy */
    private int maxHunters(Intel in) {
        int h = Math.max(2, bot.params.i("hunters"));
        return in.workers.size() >= 20 && bot.difficulty == Difficulty.EXPERT ? Math.min(2, h) : h;
    }

    private int countAnimals(Intel in, double r) {
        BlockPos h = in.home();
        AABB box = new AABB(h).inflate(r, 12, r);
        return bot.level.getEntitiesOfClass(Animal.class, box, a -> a.isAlive() && worthHunting(a) && !badPrey(a)
                && !(a instanceof Unit)).size();
    }

    // ------------------------------------------------------------------ assignment

    boolean assign(Intel in, Mob w, Job j) {
        switch (j) {
            case FOOD: return assignFood(in, w);
            case WOOD: return assignBlock(in, w, ResourceName.WOOD, Job.WOOD);
            case ORE: return assignBlock(in, w, ResourceName.ORE, Job.ORE);
            default: return false;
        }
    }

    private boolean assignFood(Intel in, Mob w) {
        // 1. an empty farm
        for (BuildingPlacement b : in.own) {
            if (!(b instanceof FarmPlacement) || !b.isBuilt) continue;
            if (farmTaken(in, b)) continue;
            Orders.farm(bot.name, List.of(w), b.centrePos);
            setJob(w, Job.FOOD);
            return true;
        }
        // 2. hunt near home
        int hunting = 0;
        for (Mob o : in.workers) if (((Unit) o).getTargetGoal().getTarget() instanceof Animal) hunting++;
        if (hunting < Math.max(1, maxHunters(in))) {
            BlockPos h = in.home();
            AABB box = new AABB(h).inflate(36, 12, 36);
            Optional<Animal> prey = bot.level.getEntitiesOfClass(Animal.class, box,
                            a -> a.isAlive() && !(a instanceof Unit) && worthHunting(a) && !badPrey(a) && !in.dangerous(a.blockPosition(), 26))
                    .stream().min(Comparator.comparingDouble(a -> a.distanceToSqr(w)));
            if (prey.isPresent()) {
                bot.log("hunt " + prey.get().getType().toShortString() + " d=" + (int) Math.sqrt(prey.get().distanceToSqr(w)) + " by #" + w.getId());
                Orders.attack(bot.name, List.of(w), prey.get());
                setJob(w, Job.FOOD);
                return true;
            }
        }
        // 3. forage food blocks outside farms (berries, mushrooms, melons...)
        return assignBlock(in, w, ResourceName.FOOD, Job.FOOD);
    }

    private boolean farmTaken(Intel in, BuildingPlacement farm) {
        for (Mob o : in.workers) {
            GatherResourcesGoal g = ((WorkerUnit) o).getGatherResourceGoal();
            if (g != null && g.getTargetFarm() == farm) return true;
        }
        return false;
    }

    private boolean assignBlock(Intel in, Mob w, ResourceName type, Job j) {
        GatherResourcesGoal g = ((WorkerUnit) w).getGatherResourceGoal();
        if (g == null) return false;
        List<BlockPos> drops = dropSites(in);
        if (drops.isEmpty()) drops = List.of(in.home());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos d : drops) {
            for (int range : new int[] { 14, 24, 40 }) {
                Optional<BlockPos> bp = ResourceIndex.get(bot.level).findClosest(bot.level, d, range, type,
                        pos -> Math.abs(pos.getY() - d.getY()) <= 8 && g.canGather(pos, type) && !in.dangerous(pos, 24) && !unreachableSpot(pos));
                if (bp.isPresent()) {
                    double dist = Math.sqrt(bp.get().distSqr(d));
                    double score = dist + 0.15 * Math.sqrt(bp.get().distSqr(w.blockPosition()));
                    if (score < bestScore) { bestScore = score; best = bp.get(); }
                    break;
                }
            }
        }
        // the nearest resource of this kind, monsters or not: if it is closer than what we settled for (or
        // all there is), monsters are keeping us from it - tell the army where (it clears such camps)
        if (bot.difficulty == Difficulty.EXPERT && bot.now - blockedCheck > 200) {
            blockedCheck = bot.now;
            Optional<BlockPos> any = ResourceIndex.get(bot.level).findClosest(bot.level, in.home(), 90, type,
                    pos -> g.canGather(pos, type) && in.dangerous(pos, 24) && !unreachableSpot(pos));
            if (any.isPresent() && (best == null || any.get().distSqr(in.home()) + 400 < best.distSqr(in.home()))) {
                blockedByDanger = any.get();
                blockedAt = bot.now;
            }
        }
        // the nearest resource lies across water (land route does not get there) and what we have is far
        // or nothing: bridge it, the way the army bridges to a target
        if (bot.difficulty == Difficulty.EXPERT && bot.params.b("bridges") && bot.now - bridgeCheck > 1200) {
            bridgeCheck = bot.now;
            Optional<BlockPos> any = ResourceIndex.get(bot.level).findClosest(bot.level, in.home(), 100, type,
                    pos -> g.canGather(pos, type) && !in.dangerous(pos, 24));
            if (any.isPresent() && (best == null || any.get().distSqr(in.home()) + 900 < best.distSqr(in.home()))) {
                List<BlockPos> r = Terrain.route(bot.level, in.home(), any.get(), bot.now);
                if (!Terrain.reaches(r, any.get())) {
                    bot.log(type + " across water at " + any.get().toShortString() + ": asking for a bridge");
                    bot.bridger.request(in.home(), any.get());
                }
            }
        }
        if (best == null) {
            // nothing near any drop site: look further out from the worker itself and ask for a stockpile
            Optional<BlockPos> far = ResourceIndex.get(bot.level).findClosest(bot.level, in.home(), 120, type,
                    pos -> g.canGather(pos, type) && !in.dangerous(pos, 24) && !unreachableSpot(pos));
            // nothing within reach of home: the expert looks across the map, on its own side only
            if (far.isEmpty() && bot.difficulty == Difficulty.EXPERT && bot.now >= farMissUntil[jobIndex(j)]) {
                far = ResourceIndex.get(bot.level).findClosest(bot.level, in.home(), 280, type,
                        pos -> safeFar(in, pos) && g.canGather(pos, type) && !in.dangerous(pos, 24) && !unreachableSpot(pos));
                if (far.isEmpty()) farMissUntil[jobIndex(j)] = bot.now + 1200;
                else if (bot.now - lastFarLog > 600) { lastFarLog = bot.now; bot.log("far " + type + " at " + far.get().toShortString() + " (d=" + (int) Math.sqrt(far.get().distSqr(in.home())) + ")"); }
            }
            if (far.isEmpty()) { exhaustedUntil[jobIndex(j)] = bot.now + 1200; return false; }
            best = far.get();
            bestScore = Math.sqrt(best.distSqr(nearest(drops, best)));
        }
        // a lean even start: every 75-wood stockpile is half a farm - walk a little further first
        double dropDist = bot.params.get("dropDistance");
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.built(bot.plan.farm) < 8) dropDist = Math.max(dropDist, 20);
        if (type != ResourceName.FOOD && bestScore > dropDist && bot.params.b("useStockpiles")) {
            stockpileWanted = best;
            stockpileFor = type;
        }
        Orders.gather(bot.name, List.of(w), best);
        if (j != Job.FOOD) { assignedPos.put(w.getId(), best); loadChangedAt.put(w.getId(), bot.now); }
        setJob(w, j);
        return true;
    }

    static BlockPos nearest(List<BlockPos> ps, BlockPos to) {
        BlockPos b = ps.get(0);
        for (BlockPos p : ps) if (p.distSqr(to) < b.distSqr(to)) b = p;
        return b;
    }

    public List<BlockPos> dropSites(Intel in) {
        List<BlockPos> out = new ArrayList<>();
        for (BuildingPlacement b : in.own) {
            if (b.isBuilt && b.getBuilding().canAcceptResources) out.add(b.centrePos);
        }
        return out;
    }

    /** workers the builder may borrow: prefer wood, then ore, never farmers mid-field unless needed */
    public List<Mob> borrow(Intel in, BlockPos near, int n) {
        List<Mob> pool = new ArrayList<>();
        for (Mob w : in.workers) {
            Job j = job(w);
            if (j == Job.BUILD || j == Job.MILITARY) continue;
            pool.add(w);
        }
        pool.sort(Comparator.comparingDouble(m -> {
            Job j = job(m);
            double pen = j == Job.IDLE ? -1000 : j == Job.WOOD ? 0 : j == Job.ORE ? 30 : 80;
            return pen + Math.sqrt(m.distanceToSqr(near.getX(), near.getY(), near.getZ()));
        }));
        List<Mob> out = new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
        for (Mob m : out) setJob(m, Job.BUILD);
        return out;
    }
}
