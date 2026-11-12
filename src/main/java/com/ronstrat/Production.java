package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement;
import com.solegendary.reignofnether.building.production.ActiveProduction;
import com.solegendary.reignofnether.building.production.ProductionBuilding;
import com.solegendary.reignofnether.building.production.ProductionItem;
import com.solegendary.reignofnether.research.ResearchServerEvents;
import com.solegendary.reignofnether.resources.ResourceCost;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.EnumMap;
import java.util.Map;

/**
 * Unit and research production. Workers are produced without pause up to the target (the single
 * biggest difference between strong and weak RTS AIs); military production follows a role mix
 * that shifts toward counters of what the enemy has been seen fielding.
 */
public final class Production {
    private final Bot bot;
    private int scoutsMade;
    private int queuedFoodUnits;
    static final int WOOD_RESERVE = 40;
    private static final ProductionItem[] HEROES = {
            com.solegendary.reignofnether.building.production.ProductionItems.ROYAL_GUARD,
            com.solegendary.reignofnether.building.production.ProductionItems.ENCHANTER,
            com.solegendary.reignofnether.building.production.ProductionItems.ROYAL_GUARD_REVIVE,
            com.solegendary.reignofnether.building.production.ProductionItems.ENCHANTER_REVIVE };
    public String idleReason = "";

    Production(Bot bot) { this.bot = bot; }

    public int plannedFoodUnits() { return queuedFoodUnits; }

    /** town centre ticks: busy, housed, no food, army first, enough villagers, other */
    final long[] tcIdle = new long[6];

    String tcIdleText() {
        long n = 0; for (long v : tcIdle) n += v;
        if (n == 0) return "";
        return String.format(" tc=busy%d/house%d/food%d/army%d/full%d/other%d", 100 * tcIdle[0] / n, 100 * tcIdle[1] / n, 100 * tcIdle[2] / n, 100 * tcIdle[3] / n, 100 * tcIdle[4] / n, 100 * tcIdle[5] / n);
    }

    void think(Intel in) {
        BotParams p = bot.params;
        FactionPlan plan = bot.plan;
        int[] escrow = bot.builder.reservedCost();
        // always keep a little wood: farms need it to replant, and without farms the whole economy dies
        int f = in.food - escrow[0], w = in.wood - escrow[1] - WOOD_RESERVE, o = in.ore - escrow[2];
        queuedFoodUnits = 0;

        // ---- workers
        int queuedWorkers = 0;
        for (BuildingPlacement b : in.own) {
            if (b instanceof ProductionPlacement pp)
                for (ActiveProduction ap : pp.productionQueue) if (ap.item == plan.worker) queuedWorkers++;
        }
        boolean turtle = bot.outnumbered();
        int workerTarget = turtle ? Math.min(p.i("workerTarget"), p.i("turtleWorkers")) : p.i("workerTarget");
        // even game: a human stops at 35-40 villagers around minute 9 and pours everything into the army
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered()) workerTarget = Math.min(workerTarget, 38);
        // the rewritten economy: the user stops at about 34 and puts the food into the army
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && p.b("econV2") && p.b("econPhase2")) workerTarget = Math.min(workerTarget, p.i("econWorkers"));
        int workersNow = in.workers.size() + in.militia.size(); // militia are villagers on the bell
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || !b.isCapitol || !(b instanceof ProductionPlacement pp)) continue;
            // the worker line comes before escrow: villagers pay for everything else
            // past the boom threshold the army comes first: villagers only from spare food
            double armyPop = 0;
            for (Mob m : in.army) armyPop += UnitEval.popValue(m);
            boolean armyFirst = workersNow >= (turtle ? 30 : p.i("boomWorkers")) && armyPop < workersNow * p.get("armyPerWorker")
                    && in.built(plan.barracks) > 0;
            // even game: a human keeps the town centre busy nonstop until ~38 villagers (about minute 9-10);
            // the army comes from the extra income, not from villagers held back
            if (armyFirst && bot.difficulty == Difficulty.EXPERT && !turtle && workersNow < 38 && bot.now < 12000
                    && bot.army.sinceThreat() > 1200 && bot.army.safeToBoom(in)) armyFirst = false;
            // not safe (what can come is bigger than what we have): soldiers before more villagers
            if (!armyFirst && bot.difficulty == Difficulty.EXPERT && !turtle && workersNow >= 16
                    && in.built(plan.barracks) > 0 && !bot.army.safeToBoom(in)) armyFirst = true;
            // ...but a gutted economy cannot pay for an army either: below 26 villagers they still come
            // from any food past one soldier's price (16 villagers for 10 minutes starved the army too)
            int foodFloor = armyFirst ? (bot.difficulty == Difficulty.EXPERT && !turtle && workersNow < 26 ? 120 : 220) : 0;
            while (workersNow + queuedWorkers < workerTarget && pp.productionQueue.size() < p.i("workerQueue")
                    && in.food >= cost(plan.worker).food + foodFloor && room(in, plan.worker)) {
                if (!bot.attentive()) break;
                if (!pp.startProductionItem(plan.worker)) break;
                queuedWorkers++;
                in.food -= cost(plan.worker).food;
                in.pop += cost(plan.worker).population;
                f -= cost(plan.worker).food;
            }
            // why the town centre stands idle (status line): every idle second is a villager lost
            if (pp.productionQueue.isEmpty()) {
                int why = workersNow + queuedWorkers >= workerTarget ? 4 : !room(in, plan.worker) ? 1
                        : in.food < cost(plan.worker).food ? 2 : foodFloor > 0 ? 3 : 5;
                tcIdle[why]++;
            } else tcIdle[0]++;
            if (p.b("scout") && in.scouts.isEmpty() && scoutsMade < 2 && plan.scout != null
                    && in.workers.size() >= 9 && pp.productionQueue.isEmpty() && in.food >= cost(plan.scout).food + 50
                    && room(in, plan.scout)) {
                if (pp.startProductionItem(plan.scout)) { scoutsMade++; in.food -= cost(plan.scout).food; }
            }
        }

        // ---- research
        int level = p.i("researchLevel");
        for (FactionPlan.ResearchStep r : plan.research) {
            if (r.level > level) continue;
            // building upgrades (grand library, superior blacksmith, grand market) belong to one
            // building, not to the player: never "researched", so pick a building not yet upgraded
            boolean perBuilding = r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GRAND_LIBRARY
                    || r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_SUPERIOR_BLACKSMITH
                    || r.item == plan.marketUpgrade;
            if (!perBuilding && ResearchServerEvents.playerHasResearch(bot.name, r.item)) continue;
            if (r.item.itemIsBeingProduced(false, bot.name)) continue;
            ResourceCost c = cost(r.item);
            // research is luxury spending: only from a healthy surplus, except bigger carry bags,
            // which pay for themselves within minutes
            boolean urgent = r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_RESOURCE_CAPACITY
                    || r.item == plan.marketUpgrade;
            int margin = urgent ? 0 : 150;
            // carry bags (expert): paid from the bank, not what is left after escrow - every other spend
            // came first and the research waited the whole game
            // (the market upgrade too: buying with emeralds is the way out of a wood famine)
            boolean bags = (r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_RESOURCE_CAPACITY || r.item == plan.marketUpgrade)
                    && bot.difficulty == Difficulty.EXPERT && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore;
            if (!bags && (f < c.food + margin || w < c.wood + margin || o < c.ore + margin / 2)) continue;
            for (BuildingPlacement b : in.own) {
                if (!b.isBuilt || b.getBuilding() != r.at || !(b instanceof ProductionPlacement pp)) continue;
                if (perBuilding && b.getUpgradeLevel() > 0) continue;
                if (!pp.productionQueue.isEmpty()) continue;
                if (pp.startProductionItem(r.item)) {
                    f -= c.food; w -= c.wood; o -= c.ore;
                    in.food -= c.food; in.wood -= c.wood; in.ore -= c.ore;
                    bot.log("research " + r.item.getItemName());
                }
                break;
            }
        }

        // ---- heroes: free units from the shrine (RON enforces the hero limit and revive rules)
        if (p.b("heroes")) {
            for (BuildingPlacement b : in.own) {
                if (!b.isBuilt || b.getBuilding() != com.solegendary.reignofnether.building.Buildings.SHRINE_OF_PROSPERITY
                        || !(b instanceof ProductionPlacement pp) || !pp.productionQueue.isEmpty()) continue;
                for (ProductionItem item : HEROES) {
                    ResourceCost c = cost(item);
                    if (!item.canProduce(pp) || !pays(c, f, w, o) || !room(in, item)) continue;
                    // a revive costs food the army needs: only from a surplus
                    if (c.food > 0 && f < c.food + 300) continue;
                    if (pp.startProductionItem(item)) {
                        bot.log("hero " + item.getItemName());
                        f -= c.food; w -= c.wood; o -= c.ore;
                        in.food -= c.food; in.wood -= c.wood; in.ore -= c.ore;
                        in.pop += c.population;
                        break;
                    }
                }
            }
        }

        // ---- army
        Map<FactionPlan.Role, Double> want = roleMix(in);
        Map<FactionPlan.Role, Double> have = new EnumMap<>(FactionPlan.Role.class);
        double totalPop = 0;
        for (Mob m : in.army) {
            FactionPlan.Role role = roleOf(m);
            double pv = UnitEval.popValue(m);
            have.merge(role, pv, Double::sum);
            totalPop += pv;
        }
        int perBuilding = bot.difficulty == Difficulty.EXPERT ? 2 : 1;
        double queuedArmyPop = 0;
        for (BuildingPlacement qb : in.own) if (qb instanceof ProductionPlacement qp && !qb.isCapitol)
            for (ActiveProduction ap : qp.productionQueue) if (plan.option(ap.item) != null) queuedArmyPop += cost(ap.item).population;
        boolean armyCapped = totalPop + queuedArmyPop >= p.get("maxArmyPop");
        // the opening is pure economy: every 160-food vindicator in the first minutes is three
        // villagers not made, and the first waves come much later. A raid turns the army back on.
        boolean ecoOpening = bot.difficulty == Difficulty.EXPERT && in.workers.size() < p.i("armyFromWorkers")
                && bot.army.sinceThreat() > 2400
                // monsters picking off villagers near the base: soldiers are needed now to clear them
                && bot.hazardDeaths < 2
                // even game: the opening ends when an army could be on its way and ours would not hold it
                && (bot.outnumbered() || bot.army.safeToBoom(in))
                // monsters camped by the base: the first soldiers go and clear them before the boom
                && bot.army.homeCampStrength(in) == 0;
        if (ecoOpening) { armyCapped = true; idleReason = "eco opening"; }
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || !(b instanceof ProductionPlacement pp) || b.isCapitol) continue;
            if (!(b.getBuilding() instanceof ProductionBuilding)) continue;
            if (b.getBuilding() == com.solegendary.reignofnether.building.Buildings.SHRINE_OF_PROSPERITY) continue;
            if (pp.productionQueue.size() >= perBuilding) continue;
            if (armyCapped) { idleReason = "army cap"; continue; }
            if (!bot.attentive()) continue;
            FactionPlan.UnitOption best = null;
            double bestGap = -1e9;
            // the lower tiers stubbornly wait for their preferred unit; the upper tiers build the best
            // unit they can afford right now instead of idling the building
            boolean flexible = bot.difficulty.ordinal() >= Difficulty.HARD.ordinal();
            for (FactionPlan.UnitOption u : plan.units) {
                if (flexible) {
                    ResourceCost cu = cost(u.item);
                    if (!pays(cu, f, wFor(u, in, w, o), o) || !room(in, u.item)) continue;
                }
                if (u.producer != b.getBuilding()) continue;
                if (in.workers.size() < u.minWorkers) continue;
                boolean unlocked = true;
                for (var req : u.requires) if (in.built(req) == 0) { unlocked = false; break; }
                if (!unlocked) continue;
                double share = want.getOrDefault(u.role, 0.0);
                if (share <= 0) continue;
                double gap = share - have.getOrDefault(u.role, 0.0) / Math.max(1, totalPop);
                // spend what is piling up: a unit made of surplus resources is worth more right now
                if (bot.difficulty.ordinal() >= Difficulty.HARD.ordinal()) gap += p.get("resourceFitWeight") * resourceFit(cost(u.item), f, w, o);
                // even game: food and wood run short while ore piles up - a unit paid in ore is army for free
                if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && o >= 400) {
                    ResourceCost cu = cost(u.item);
                    if (cu.ore >= 2 * (cu.food + cu.wood)) gap += 0.5;
                }
                if (gap > bestGap) { bestGap = gap; best = u; }
            }
            if (best == null) { idleReason = "nothing affordable f=" + f + " w=" + w + " o=" + o + " pop=" + in.pop + "/" + in.popCap; continue; }
            ResourceCost c = cost(best.item);
            if (!pays(c, f, wFor(best, in, w, o), o) || !room(in, best.item)) { idleReason = "cannot afford " + best.item.getItemName(); continue; }
            if (pp.startProductionItem(best.item)) {
                bot.log("train " + best.item.getItemName());
                idleReason = "";
                f -= c.food; w -= c.wood; o -= c.ore;
                in.food -= c.food; in.wood -= c.wood; in.ore -= c.ore;
                in.pop += c.population;
                have.merge(best.role, (double) Math.max(1, c.population), Double::sum);
                totalPop += Math.max(1, c.population);
                if (c.food > 0) queuedFoodUnits++;
            }
        }
    }

    /** desired share of army population per role */
    Map<FactionPlan.Role, Double> roleMix(Intel in) {
        BotParams p = bot.params;
        Map<FactionPlan.Role, Double> m = new EnumMap<>(FactionPlan.Role.class);
        double golem = p.get("golemShare"), caster = p.get("casterShare");
        if (bot.outnumbered() && golem >= 0.3 && in.ore > 500) golem = 0.6; // ore-fed army when food is the war
        // even game, ore idle while food is short: golems cost no food
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && golem > 0 && in.ore >= 350) golem = Math.max(golem, 0.35);
        double melee = 0.5, ranged = 0.5;
        // counter what the enemy fields: ranged armies are punished by melee and tanks, melee by ranged
        double k = p.get("counterComposition");
        if (k > 0) {
            int er = 0, em = 0;
            for (LivingEntity e : in.visibleEnemies) {
                if (UnitEval.isWorker(e)) continue;
                if (UnitEval.isRanged(e)) er++; else em++;
            }
            if (er + em >= 3) {
                double rf = er / (double) (er + em);
                melee = 0.5 + k * (rf - 0.5) * 0.6;
                ranged = 1 - melee;
                golem += k * Math.max(0, rf - 0.5) * 0.2;
            }
        }
        if (bot.difficulty == Difficulty.EASY) { melee = 0.5; ranged = 0.5; }
        double rest = Math.max(0.2, 1 - golem - caster - 0.08 - 0.08);
        m.put(FactionPlan.Role.MELEE, melee * rest);
        m.put(FactionPlan.Role.RANGED, ranged * rest);
        m.put(FactionPlan.Role.TANK, golem);
        // an arcane tower standing idle is wasted wood and ore: once it is up, evokers are a real part of the army
        if (bot.difficulty == Difficulty.EXPERT && caster > 0 && in.built(com.solegendary.reignofnether.building.Buildings.ARCANE_TOWER) > 0)
            caster = Math.max(caster, 0.22);
        m.put(FactionPlan.Role.CASTER, caster);
        m.put(FactionPlan.Role.SUPPORT, p.get("casterShare") > 0 ? 0.08 : 0);
        m.put(FactionPlan.Role.SIEGE, p.get("golemShare") > 0.15 ? 0.08 : 0);
        return m;
    }

    FactionPlan.Role roleOf(LivingEntity e) {
        if (e instanceof Unit u) {
            for (FactionPlan.UnitOption o : bot.plan.units) {
                if (o.item.getItemName().equalsIgnoreCase(nameOf(u))) return o.role;
            }
        }
        return UnitEval.isRanged(e) ? FactionPlan.Role.RANGED : FactionPlan.Role.MELEE;
    }

    private static String nameOf(Unit u) {
        String s = ((LivingEntity) u).getType().getDescriptionId(); // entity.reignofnether.pillager_unit
        int i = s.lastIndexOf('.');
        s = i >= 0 ? s.substring(i + 1) : s;
        s = s.replace("_unit", "").replace('_', ' ');
        return s;
    }

    /**
     * Wood available to this unit. Ore piling up while food is the war (1vN): golems turn it into army
     * for a little wood, so they may use wood that is being saved for buildings.
     */
    private int wFor(FactionPlan.UnitOption u, Intel in, int w, int o) {
        if (u.item == com.solegendary.reignofnether.building.production.ProductionItems.IRON_GOLEM
                && (bot.outnumbered() && o >= 450 || bot.difficulty == Difficulty.EXPERT && (o >= 600 || o >= 250 && !bot.army.safeToBoom(in)))) return Math.max(w, in.wood - WOOD_RESERVE);
        return w;
    }

    /** only the inputs a unit actually needs count: escrow may push an unused resource below zero */
    static boolean pays(ResourceCost c, int f, int w, int o) {
        return (c.food <= 0 || f >= c.food) && (c.wood <= 0 || w >= c.wood) && (c.ore <= 0 || o >= c.ore);
    }

    /** 0..1: how comfortably current stock pays for several of this unit, judged by its scarcest input */
    static double resourceFit(ResourceCost c, int f, int w, int o) {
        double fit = 1;
        if (c.food > 0) fit = Math.min(fit, f / (4.0 * c.food));
        if (c.wood > 0) fit = Math.min(fit, w / (4.0 * c.wood));
        if (c.ore > 0) fit = Math.min(fit, o / (4.0 * c.ore));
        return Math.max(0, Math.min(1, fit));
    }

    private boolean room(Intel in, ProductionItem item) {
        return in.pop + cost(item).population <= in.popCap;
    }

    static ResourceCost cost(ProductionItem item) {
        return item.getCost(false, "");
    }
}
