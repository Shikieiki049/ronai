package com.ronstrat;

import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.BuildingBlock;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.BuildingUtils;
import com.solegendary.reignofnether.building.BuildingValidators;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Build planner. Each think it ranks what the base needs (houses before supply blocks, farms when
 * hunting runs dry, a stockpile when the walk gets long, production, then tech) and places the top
 * affordable item. If the top item is not affordable the cost is escrowed so unit production
 * cannot starve it, which is how strong AoE2 scripts avoid "never quite affording the castle".
 */
public final class Builder {
    private final Bot bot;
    private Request reserved;
    private final Map<Building, Long> lastFail = new HashMap<>();
    private long lastPlaced = -1000;

    record Request(Building building, double priority, BlockPos anchor, int rMin, int rMax) {}

    Builder(Bot bot) { this.bot = bot; }

    /** resources held back for the top build request */
    public int[] reservedCost() {
        if (reserved == null || bot.params.get("spendingEscrow") <= 0) return new int[3];
        double k = bot.params.get("spendingEscrow");
        Building b = reserved.building;
        return new int[] { (int) (b.cost.food * k), (int) (b.cost.wood * k), (int) (b.cost.ore * k) };
    }

    void think(Intel in) {
        FactionPlan plan = bot.plan;
        BotParams p = bot.params;
        List<Request> reqs = new ArrayList<>();
        BlockPos home = in.home();
        int workers = in.workers.size();

        // even game (no 1vN handicap feeding us): the fields come before every luxury - a human builds six
        // farms by minute four and only then a second town centre, tech or shrine
        boolean evenLean = bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.built(plan.farm) < 8 && in.food < 400;
        if (in.total(plan.capitol) == 0) {
            reqs.add(new Request(plan.capitol, 1000, bot.startPos, 0, 14));
        } else {
            // supply: never get housed (AoE2 rule #1)
            int unbuiltHouses = in.total(plan.house) - in.built(plan.house);
            int freeSupply = in.popCap - in.pop;
            int buffer = p.i("houseBuffer") + workers / 12;
            int maxHouses = p.i("maxParallelHouses");
            // a lean start (1v1 from a seat: 150 wood) cannot afford three houses ahead: one at a time,
            // just ahead of need, so the first farms get the wood
            if (bot.difficulty == Difficulty.EXPERT && in.popCap <= 30 && in.wood < 250) { buffer = Math.min(buffer, 3); maxHouses = 1; }
            // the player's own games: first house by 0:30, five by 4:30, ten by 10:00 - the town centre
            // never stops for supply (the ally expert sat at 10/10, 20/20, 40/40 four times in ten minutes)
            boolean evenEx = bot.difficulty == Difficulty.EXPERT && !bot.outnumbered();
            if (evenEx) {
                double hs = com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks / 20.0;
                int sched = hs < 270 ? (hs < 120 ? 1 : hs < 150 ? 2 : hs < 210 ? 3 : 4) : Math.min(10, 5 + (int) ((hs - 270) / 66));
                // ahead of farms only once supply is getting close; with room left the fields come first
                // (four houses and no field at 2:30 left a start without hunt at 125 food in 5 minutes)
                if (in.total(plan.house) < sched && in.popCap < 150 && in.built(plan.capitol) > 0 && unbuiltHouses < 2 && freeSupply <= 10)
                    reqs.add(new Request(plan.house, freeSupply <= 6 ? 98 : 88, home, 9, 44));
                buffer = Math.max(buffer, 5);
            }
            if (in.popCap < 150 && freeSupply <= buffer && unbuiltHouses < maxHouses
                    && in.built(plan.capitol) > 0) {
                // about to be housed: ahead of farms and barracks, a stalled town centre costs more than either
                reqs.add(new Request(plan.house, freeSupply <= 3 ? (evenEx ? 108 : 100) : 90 - freeSupply, home, 9, 44));
            }
            // even game: the field schedule a good human player keeps (from the player's own games: 3 farms
            // by minute 3, 6 by 4.5, 10 by 6.5, 14 by 9) - fields are what keeps the town centre busy
            if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.built(plan.capitol) > 0) {
                double mins = com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks / 1200.0;
                int sched = mins < 1.5 ? 1 : mins < 3 ? 3 : mins < 4.5 ? 6 : mins < 6.5 ? 10 : mins < 9 ? 14 : 16;
                // the first six fields outrank everything; later ones must not starve the barracks
                // no animals to hunt near home: fields are the only food - the first two before anything
                boolean noWildFood = bot.economy.animalsNearHome <= 3 && in.food < 200;
                if (in.total(plan.farm) < sched && workers >= (noWildFood ? 4 : 6)) reqs.add(new Request(plan.farm, noWildFood && in.total(plan.farm) < 2 ? 104 : in.total(plan.farm) < 6 ? 96 : 84, home, 10, 34));
            }
            // farms
            int farms = in.total(plan.farm);
            if (bot.economy.farmsWanted > farms && in.built(plan.capitol) > 0 && workers >= 7) {
                // farms are the food economy: short of the wanted number they outrank everything but houses
                double pri = in.food > 900 ? 35 : farms < bot.economy.farmsWanted * 0.75 ? (bot.difficulty == Difficulty.EXPERT && workers < 34 ? 94 : 82) : 60;
                reqs.add(new Request(plan.farm, pri + Math.min(6, bot.economy.farmsWanted - farms), home, 10, 34));
            }
            // even game: carry bags (200 food + 200 wood at a stockpile) double what a villager brings per
            // trip - 50 to 100. Without a stockpile the expert never researched them in a whole game
            if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.total(plan.stockpile) == 0
                    && workers >= 20 && in.built(plan.farm) >= 6) {
                // by the town centre (a spot in the woods found no room): it is there for the research
                reqs.add(new Request(plan.stockpile, workers >= 24 ? 92 : 80, home, 8, 26));
            }
            // no drop site left at all (capitols and stockpiles destroyed): nothing can be delivered
            if (bot.economy.dropSites(in).isEmpty() && in.total(plan.stockpile) - in.built(plan.stockpile) == 0) {
                reqs.add(new Request(plan.stockpile, 110, home, 2, 20));
            }
            // drop site
            if (bot.economy.stockpileWanted != null && in.total(plan.stockpile) - in.built(plan.stockpile) == 0
                    && workers >= 4 && !dropSiteNear(in, bot.economy.stockpileWanted, 12)) {
                // villagers walking around with full packs they cannot drop anywhere: nothing else matters
                // (not right after one was abandoned: wolves chasing the builders make that a wood sink)
                boolean stuckLoads = bot.now - bot.economy.deliveryTrouble < 2400 && bot.economy.stockpileWanted.distSqr(home) < 40 * 40 && bot.now - lastStockpileCancel > 3000;
                reqs.add(new Request(plan.stockpile, stuckLoads ? 110 : 75, bot.economy.stockpileWanted, 2, 14));
            }
            // boom: extra capitols double and triple villager production (the AoE2 multi-TC boom)
            int capitols = in.total(plan.capitol);
            int extra = p.i("extraCapitols");
            // even game: one town centre like a human; a second only once resources pile up unspent
            if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.food + in.wood < 1500
                    && bot.now - bot.economy.deliveryTrouble > 6000) extra = 0;
            // the villagers cannot reach the first town centre (it stands up on a ledge): a second one down
            // where they work is the way out - right away, whatever the field count
            boolean stranded = bot.difficulty == Difficulty.EXPERT && bot.now - bot.economy.deliveryTrouble < 6000;
            if (stranded) earlyBoomOverride = true;
            // the first extra one early and urgent: villager output is capped by capitols (15 s each),
            // and the start leaves ore lying idle that pays most of it
            // ...but not before the food economy stands: a second town centre with no food to train
            // villagers from is 350 wood sunk while the fields stay unbuilt (the lean 1v1 seat start)
            boolean earlyBoom = capitols == 1 && extra > 0 && (workers >= 12 && in.ore >= 200 && in.built(plan.farm) >= 5 || earlyBoomOverride);
            earlyBoomOverride = false;
            if (capitols < 1 + extra && in.built(plan.capitol) == capitols && (earlyBoom || workers >= 14 + 10 * (capitols - 1)
                    && in.built(plan.barracks) > 0 && in.built(plan.farm) >= 3 + 2 * (capitols - 1)) && (!evenLean || stranded)) {
                BlockPos at = bot.economy.stockpileWanted != null ? bot.economy.stockpileWanted : home;
                int rMin = at == home ? 24 : 4, rMax = at == home ? 40 : 16;
                reqs.add(new Request(plan.capitol, earlyBoom ? 92 : 80, at, rMin, rMax));
            }
            // military production
            int barracks = in.total(plan.barracks);
            int firstAt = bot.difficulty == Difficulty.EXPERT ? 8 : bot.difficulty == Difficulty.HARD ? 10 : 13;
            firstAt = Math.min(firstAt, Math.max(6, p.i("workerTarget") - 3)); // every tier fields an army
            int wantBarracks = workers < firstAt ? 0
                    : Math.min(p.i("maxBarracks"), 1 + (workers - firstAt) / Math.max(1, p.i("barracksPerWorkers")));
            // production buildings are useless without the food to feed them
            wantBarracks = Math.min(wantBarracks, 1 + in.built(plan.farm) / 3);
            boolean earlyArmy = bot.outnumbered() && p.b("rushWhenOutnumbered");
            if (earlyArmy) {
                // 1vN opening: barracks right after the capitol, a second one soon, fed by the hunt
                wantBarracks = workers >= 6 ? (workers >= 13 ? 2 : 1) : 0;
                wantBarracks = Math.max(wantBarracks, Math.min(p.i("maxBarracks"), 1 + in.built(plan.farm) / 3));
            }
            // expert, even game (1v1 / FFA): boom to ~22 villagers, then barracks fast and three of them by
            // 30 villagers - the army has to exist by minute 12, when a human's first wave can come
            boolean evenExpert = bot.difficulty == Difficulty.EXPERT && !earlyArmy;
            if (evenExpert && workers >= 18) wantBarracks = Math.max(wantBarracks, Math.min(p.i("maxBarracks"), workers >= 26 ? 4 : workers >= 22 ? 3 : 2));
            // being pushed with two barracks up: new ones would go up in the enemy army's face - spend on units
            boolean pushed = evenExpert && bot.army.sinceThreat() < 1200;
            if (pushed && barracks >= 2) wantBarracks = barracks;
            // even game: the first fields before the first barracks (150 wood each, the barracks idles without food)
            if (evenExpert && in.built(plan.farm) < 4 && barracks == 0 && !(bot.army.sinceThreat() < 2400) && bot.army.safeToBoom(in)) wantBarracks = 0;
            // unsafe and nothing to make soldiers with: the first barracks now, whatever else waits
            if (evenExpert && barracks == 0 && workers >= 10 && in.built(plan.farm) >= 2 && (!bot.army.safeToBoom(in) || bot.army.homeCampStrength(in) > 0)) wantBarracks = Math.max(wantBarracks, 1);
            // monsters keep eating villagers (a wolf den beside the base): a few soldiers clear it, villagers cannot
            boolean beastTrouble = evenExpert && bot.hazardDeaths >= 4 && workers >= 6 && barracks == 0 && in.built(plan.farm) >= 2;
            if (beastTrouble) wantBarracks = 1;
            if (barracks < wantBarracks) {
                double pri = earlyArmy ? (barracks == 0 ? 95 : 84) : (barracks == 0 ? 80 : 62) - barracks * 4;
                if (evenExpert && workers >= 18) pri = barracks < 2 ? 95 : 88;
                if (beastTrouble || (evenExpert && barracks == 0 && workers >= 10 && in.built(plan.farm) >= 2 && (!bot.army.safeToBoom(in) || bot.army.homeCampStrength(in) > 0))) pri = 105;
                reqs.add(new Request(plan.barracks, pri, pushed ? bot.army.rearAnchor(in) : bot.army.frontAnchor(in), 10, 24));
            }
            // tech
            int techOwned = 0;
            for (FactionPlan.TechStep t : plan.tech) if (in.total(t.building) > 0) techOwned++;
            // outnumbered: tech that does not make the army bigger right now is a luxury; only the golem smith
            boolean floating = in.food + in.wood + in.ore > 2500;
            if (techOwned < p.i("maxTechBuildings") && (!bot.outnumbered() || floating) && (!evenLean || floating)) {
                for (FactionPlan.TechStep t : plan.tech) {
                    if (in.total(t.building) > 0) continue;
                    if (t.level > p.i("maxTechBuildings")) continue;
                    if (workers < t.minWorkers) continue;
                    boolean ok = true;
                    for (Building r : t.requires) if (in.built(r) == 0) { ok = false; break; }
                    if (!ok) continue;
                    // a tech building paid mostly in a resource that is piling up costs us nothing: hurry it
                    boolean surplus = t.building.cost.ore > 0 && in.ore >= t.building.cost.ore + 250
                            && bot.difficulty.ordinal() >= Difficulty.HARD.ordinal();
                    reqs.add(new Request(t.building, (surplus ? 70 : 45) - t.level, home, 12, 28));
                    break;
                }
            }
            // golem economy: golems cost no food, so against many food-starved opponents the expert turns
            // ore into its army - blacksmith early, a second one once ore piles up
            if (p.get("golemShare") >= 0.3 && bot.outnumbered() && in.built(plan.barracks) > 0) {
                Building smith = bot.plan.option(com.solegendary.reignofnether.building.production.ProductionItems.IRON_GOLEM).producer;
                int smiths = in.total(smith);
                // ore piling up (a rich start, nothing else wants it): the golem smith turns it into army
                if (smiths == 0 && workers >= 12 && (!evenLean || in.ore >= 450)) reqs.add(new Request(smith, 83, home, 12, 30));
                else if (smiths == 1 && in.built(smith) == 1 && in.ore >= 600 && workers >= 24)
                    reqs.add(new Request(smith, 66, home, 12, 34));
            }
            // even game: food is the bottleneck while the starting/stray ore sits idle - golems cost no
            // food and beat the first vindicator raids, so a golem smith once the barracks are going
            if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.built(plan.barracks) >= 1
                    && workers >= 18 && in.ore >= 350) {
                Building smith = bot.plan.option(com.solegendary.reignofnether.building.production.ProductionItems.IRON_GOLEM).producer;
                // a rich start (handicap ore) sat unused for 18 min behind barracks and houses: go first
                if (in.total(smith) == 0) reqs.add(new Request(smith, in.ore >= 550 || !bot.army.safeToBoom(in) ? 97 : 86, home, 12, 30));
                // ore keeps piling up with one smith busy: a second one turns it into army twice as fast
                else if (in.total(smith) == 1 && in.built(smith) == 1 && in.ore >= 700 && workers >= 28) reqs.add(new Request(smith, 70, home, 12, 34));
            }
            // market once the economy is lopsided (one resource piling up while another starves)
            if (p.b("useMarket") && bot.plan.market != null && in.total(bot.plan.market) == 0 && workers >= 22) {
                int hi = Math.max(in.food, Math.max(in.wood, in.ore)), lo = Math.min(in.food, Math.min(in.wood, in.ore));
                if (hi > 700 && lo < 200) reqs.add(new Request(bot.plan.market, hi >= 1000 && lo < 150 ? 88 : 74, home, 12, 34));
                // the expert puts one up ahead of need: once the wood is gone the market is the only
                // way to get any, and it costs wood itself
                else if (bot.difficulty == Difficulty.EXPERT && workers >= 26 && in.wood >= 200 && in.ore >= 150)
                    reqs.add(new Request(bot.plan.market, 60, home, 12, 34));
            }
            // shrine of prosperity: cheap, and its heroes cost no resources at all
            if (p.b("heroes") && in.total(com.solegendary.reignofnether.building.Buildings.SHRINE_OF_PROSPERITY) == 0
                    && in.built(plan.barracks) > 0 && workers >= 16 && !evenLean) {
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.SHRINE_OF_PROSPERITY, 72, home, 10, 30));
            }
            // defensive towers once someone has come for us
            if (p.b("buildTowers") && bot.army.timesRaided > 0 && in.total(plan.tower) < 2 && workers >= 18) {
                reqs.add(new Request(plan.tower, 50, bot.army.threatAnchor(in), 4, 12));
            }
            // planned tower ring facing the enemy (expert): defend at home while booming, win the
            // fights against many weaker enemies one wave at a time
            int towers = p.i("towers");
            boolean towerTime = bot.outnumbered()
                    ? workers >= 14 + 6 * in.total(plan.tower) && in.built(plan.barracks) > 0
                    : workers >= 28 + 4 * in.total(plan.tower) && in.built(plan.barracks) > 0 && in.built(plan.farm) >= 8;
            if (in.total(plan.tower) < towers && towerTime) {
                reqs.add(new Request(plan.tower, 58, bot.army.frontAnchor(in), 8, 18));
            }
        }

        reqs.sort((a, b) -> Double.compare(b.priority, a.priority));
        reserved = null;
        for (Request r : reqs) {
            Building b = r.building;
            if (bot.now - lastFail.getOrDefault(b, -100000L) < 200) continue; // no spot found recently
            boolean dropSiteEmergency = b == plan.stockpile && (bot.economy.dropSites(in).isEmpty() || (bot.now - bot.economy.deliveryTrouble < 2400 && r.priority >= 110));
            int woodReserve = dropSiteEmergency ? 0 : Production.WOOD_RESERVE;
            if (!in.canAfford(b.cost.food, b.cost.wood + woodReserve, b.cost.ore)) {
                if (reserved == null && r.priority >= 40) reserved = r;
                // an important building we are saving for must not be starved by cheaper, less
                // important ones that use the same resource (the escrow rule)
                if (r.priority >= 60 && bot.params.get("spendingEscrow") > 0) break;
                continue;
            }
            if (reserved != null && sharesInput(reserved.building, b)) continue;
            if (bot.now - lastPlaced < 10) break;
            if (place(in, r)) {
                lastPlaced = bot.now;
                // spend reservation is now actually spent; let the next think re-rank
                if (bot.economy.stockpileWanted != null && b == plan.stockpile) bot.economy.stockpileWanted = null;
            } else {
                lastFail.put(b, bot.now);
                bot.log("no spot for " + name(b) + " near " + r.anchor.toShortString() + " r" + r.rMin + "-" + r.rMax);
            }
            break;
        }
        keepBuildersOnSites(in);
    }

    private long lastStockpileCancel = -100000;

    private static boolean sharesInput(Building a, Building b) {
        return (a.cost.wood > 0 && b.cost.wood > 0) || (a.cost.ore > 0 && b.cost.ore > 0) || (a.cost.food > 0 && b.cost.food > 0);
    }

    private boolean place(Intel in, Request r) {
        Building b = r.building;
        BlockPos origin = findSpot(in, b, r.anchor, r.rMin, r.rMax);
        if (origin == null) return false;
        int n;
        if (b == bot.plan.capitol) n = in.total(b) == 0 ? in.workers.size() : 3;
        else if (b == bot.plan.house) n = (in.popCap - in.pop <= 3 && bot.difficulty.ordinal() >= Difficulty.HARD.ordinal()) ? 2 : 1;
        else if (b == bot.plan.farm || b == bot.plan.stockpile) n = 1;
        else n = Math.max(1, bot.params.i("buildersPerBuilding"));
        List<Mob> builders = bot.economy.borrow(in, origin, n);
        if (builders.isEmpty()) return false;
        BuildingPlacement placed = BuildingServerEvents.placeBuilding(b, origin, Rotation.NONE, bot.name,
                Orders.ids(builders), false, false, false, true);
        if (placed == null) {
            for (Mob m : builders) bot.economy.setJob(m, Economy.Job.IDLE);
            return false;
        }
        bot.log("build " + name(b) + " at " + origin.toShortString() + " with " + builders.size());
        return true;
    }

    /** unfinished buildings whose builders died or wandered off get new builders */
    private final Map<BlockPos, int[]> siteProgress = new HashMap<>(); // origin -> {blocksPlaced, tick of last change}
    private final List<BlockPos> badSpots = new ArrayList<>();
    private boolean earlyBoomOverride;

    private boolean nearBadSpot(int x, int z) {
        for (BlockPos p : badSpots) if (Math.abs(p.getX() - x) <= 6 && Math.abs(p.getZ() - z) <= 6) return true;
        return false;
    }

    /**
     * A foundation nobody can build (builders cannot path to it, it sits in a hole): no block placed
     * for over a minute. Cancel it and never try that spot again, or the whole game waits on it.
     */
    private void cancelStalledSites(Intel in) {
        for (BuildingPlacement b : new ArrayList<>(in.own)) {
            if (b.isBuilt || b.isDestroyedServerside) { siteProgress.remove(b.originPos); continue; }
            if (bot.difficulty == Difficulty.EXPERT && !b.isCapitol && in.dangerous(b.centrePos, 14)) {
                siteProgress.remove(b.originPos);
                badSpots.add(b.originPos);
                bot.log("cancel " + name(b.getBuilding()) + " at " + b.originPos.toShortString() + ": monsters there");
                if (b.getBuilding() == bot.plan.stockpile) lastStockpileCancel = bot.now;
                BuildingServerEvents.cancelBuilding(b, bot.name);
                continue;
            }
            int placed = b.getBlocksPlaced();
            int[] pr = siteProgress.computeIfAbsent(b.originPos, k -> new int[] { placed, (int) bot.now });
            if (placed != pr[0]) { pr[0] = placed; pr[1] = (int) bot.now; continue; }
            if (bot.now - pr[1] < 1500) continue;
            // the first town centre is the whole base: cancelling it loses the game on the spot
            // (4Mountains: a stalled start site was cancelled at 0:76 - "lost all their buildings")
            if (b.isCapitol && in.built(bot.plan.capitol) == 0) { pr[1] = (int) bot.now; continue; }
            siteProgress.remove(b.originPos);
            badSpots.add(b.originPos);
            bot.log("cancel stalled " + name(b.getBuilding()) + " at " + b.originPos.toShortString());
            if (b.getBuilding() == bot.plan.stockpile) lastStockpileCancel = bot.now;
            BuildingServerEvents.cancelBuilding(b, bot.name);
        }
    }

    private void keepBuildersOnSites(Intel in) {
        cancelStalledSites(in);
        for (BuildingPlacement b : in.own) {
            if (b.isBuilt || b.isDestroyedServerside) continue;
            boolean anyBuilder = false;
            for (Mob w : in.workers) {
                var g = ((WorkerUnit) w).getBuildRepairGoal();
                if (g != null && g.getBuildingTarget() == b) { anyBuilder = true; break; }
            }
            if (anyBuilder) continue;
            List<Mob> ws = bot.economy.borrow(in, b.centrePos, b.isCapitol ? 3 : 1);
            if (!ws.isEmpty()) Orders.buildRepair(bot.name, ws, b.centrePos);
        }
    }

    private boolean dropSiteNear(Intel in, BlockPos p, int r) {
        for (BuildingPlacement b : in.own) {
            if (b.getBuilding().canAcceptResources && b.centrePos.distSqr(p) <= r * r) return true;
        }
        return false;
    }

    public static String name(Building b) {
        String s = b.structureName;
        return s == null ? b.getClass().getSimpleName() : s;
    }

    // ------------------------------------------------------------------ placement search

    BlockPos findSpot(Intel in, Building b, BlockPos anchor, int rMin, int rMax) {
        BlockPos p = findSpot(b, anchor, rMin, rMax, 1, 6);
        // real maps are hilly: widen the search and accept a little more slope before giving up
        if (p == null) p = findSpot(b, anchor, rMax, (int) (rMax * 1.8) + 8, 2, 12);
        return p;
    }

    private BlockPos findSpot(Building b, BlockPos anchor, int rMin, int rMax, int flatTol, int yTol) {
        ArrayList<BuildingBlock> rel = b.getRelativeBlockData(bot.level);
        Vec3i size = BuildingUtils.getBuildingSize(rel);
        int sx = size.getX(), sz = size.getZ();
        int gap = 2;
        boolean dropSite = b.canAcceptResources;
        int checks = 0;
        // a deterministic spiral of candidate centres, nearest ring first
        for (int r = rMin; r <= rMax; r += 2) {
            int steps = Math.max(8, (int) (2 * Math.PI * Math.max(1, r) / 3));
            double off = (anchor.getX() * 31 + anchor.getZ() * 17 + r) % 360 * Math.PI / 180;
            for (int s = 0; s < steps; s++) {
                double a = off + 2 * Math.PI * s / steps;
                int cx = anchor.getX() + (int) Math.round(Math.cos(a) * r);
                int cz = anchor.getZ() + (int) Math.round(Math.sin(a) * r);
                int ox = cx - sx / 2, oz = cz - sz / 2;
                if (!clearOfBuildings(ox, oz, sx, sz, gap, dropSite)) continue;
                if (nearBadSpot(ox, oz)) continue;
                int y = bot.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                if (Math.abs(y - anchor.getY()) > yTol && b != bot.plan.capitol) continue;
                // never put a foundation where monsters have been killing villagers: the builders die on it
                // and the site blocks that building for good (a 1v1 with no barracks for 20 minutes)
                if (bot.difficulty == Difficulty.EXPERT && bot.intel.dangerous(new BlockPos(cx, y, cz), 18)) continue;
                // a town centre is a 350-wood, three-builder job that then draws villagers for good: none
                // within reach of any monster seen or any monster kill (one went up 21 blocks from a camp)
                if (bot.difficulty == Difficulty.EXPERT && b == bot.plan.capitol && bot.intel.nearestDanger(new BlockPos(cx, y, cz), 32) != null) continue;
                if (!flatEnough(ox, oz, sx, sz, y, flatTol)) continue;
                for (int dy : flatTol > 1 ? new int[] { 0, -1, 1 } : new int[] { 0, -1 }) {
                    BlockPos origin = new BlockPos(ox, y + dy, oz);
                    if (++checks > 500) return null;
                    if (BuildingValidators.isPlacementValid(bot.level, b, origin, bot.name, Rotation.NONE, false, false, true)) {
                        return origin;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Keep walkways: 2 blocks between buildings, and a 4 block ring around every drop site so
     * workers can always reach it (a boxed-in town centre silently kills the whole economy).
     */
    private boolean clearOfBuildings(int ox, int oz, int sx, int sz, int gap, boolean selfIsDropSite) {
        for (BuildingPlacement p : BuildingServerEvents.getBuildings()) {
            int g = (selfIsDropSite || p.getBuilding().canAcceptResources) ? Math.max(gap, 4) : gap;
            int x0 = ox - g, z0 = oz - g, x1 = ox + sx - 1 + g, z1 = oz + sz - 1 + g;
            if (p.maxCorner.getX() < x0 || p.minCorner.getX() > x1) continue;
            if (p.maxCorner.getZ() < z0 || p.minCorner.getZ() > z1) continue;
            return false;
        }
        return true;
    }

    private boolean flatEnough(int ox, int oz, int sx, int sz, int y, int tol) {
        int[][] pts = { { ox, oz }, { ox + sx - 1, oz }, { ox, oz + sz - 1 }, { ox + sx - 1, oz + sz - 1 }, { ox + sx / 2, oz + sz / 2 } };
        for (int[] q : pts) {
            int h = bot.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q[0], q[1]);
            if (Math.abs(h - y) > tol) return false;
        }
        return true;
    }
}
