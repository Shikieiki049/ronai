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
        int f = in.food - escrow[0], w = in.wood - escrow[1] - (bot.plan.villagers ? WOOD_RESERVE : 0), o = in.ore - escrow[2];   // zombie villagers replant free
        Ledger L = bot.ledger;
        if (L.on()) { f -= (int) L.fundFood; w -= (int) L.fundWood; o -= (int) L.fundOre; }
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
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered()) workerTarget = Math.min(workerTarget, p.i("vilCap"));
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
            // villagers first (parameter villagersFirst), the way a human booms: the town centre takes its 200
            // food a minute until the villager cap and the barracks get the rest - unless the enemy really
            // has more soldiers than we do (seen), a rush is on, or the base was attacked in the last minute.
            // (Army first from 24 villagers held the expert at 26 villagers from minute 9 to 15.)
            if (armyFirst && bot.difficulty == Difficulty.EXPERT && !turtle && p.b("villagersFirst")
                    && bot.army.sinceThreat() > 1200 && !bot.army.rushDefence(in) && bot.army.soldierGap(in) <= p.get("villagersFirstGap")) armyFirst = false;
            // ...but a gutted economy cannot pay for an army either: below 26 villagers they still come
            // from any food past one soldier's price (16 villagers for 10 minutes starved the army too)
            int foodFloor = armyFirst ? (bot.difficulty == Difficulty.EXPERT && !turtle && workersNow < 26 ? 120 : 220) : 0;
            // monsters camped at home: the soldiers to clear them first (parameter campRush)
            if (bot.difficulty == Difficulty.EXPERT && bot.army.campRush(in) && in.built(plan.barracks) > 0 && workersNow >= 6) continue;
            // rush defence: every bit of food into soldiers until the army has caught up with theirs
            if (bot.difficulty == Difficulty.EXPERT && bot.army.rushDefence(in) && workersNow >= 12) continue;
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

        // ---- golem route: golem smithing at the blacksmith, before anything else there
        if (plan.villagers && Strategy.golems(bot) && !ResearchServerEvents.playerHasResearch(bot.name, com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GOLEM_SMITHING)
                && !com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GOLEM_SMITHING.itemIsBeingProduced(false, bot.name)) {
            ResourceCost gc = cost(com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GOLEM_SMITHING);
            if (in.wood >= gc.wood && in.ore >= gc.ore)
                for (BuildingPlacement b : in.own) {
                    if (!b.isBuilt || b.getBuilding() != com.solegendary.reignofnether.building.Buildings.BLACKSMITH || !(b instanceof ProductionPlacement pp) || !pp.productionQueue.isEmpty()) continue;
                    if (pp.startProductionItem(com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GOLEM_SMITHING)) {
                        w -= gc.wood; o -= gc.ore; in.wood -= gc.wood; in.ore -= gc.ore;
                        bot.log("research Golem Smithing (golem route)");
                    }
                    break;
                }
        }
        // ---- research
        techSave = null;
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
            // upgradeTier (mid/late game, the army formed): the grand library (sharpness, multishot) and the superior
            // blacksmith (chainmail) are a planned spend, not leftovers - 12 against 12, chain + sharpness won +0.47
            boolean tier = p.b("upgradeTier") && in.army.size() >= p.i("upgradeTierArmy")
                    && (r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GRAND_LIBRARY
                        || r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_SUPERIOR_BLACKSMITH);
            if (tier) urgent = true;
            int margin = urgent ? 0 : 150;
            // carry bags (expert): paid from the bank, not what is left after escrow - every other spend
            // came first and the research waited the whole game
            // (the market upgrade too: buying with emeralds is the way out of a wood famine)
            boolean bags = (r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_RESOURCE_CAPACITY || r.item == plan.marketUpgrade)
                    && bot.difficulty == Difficulty.EXPERT && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore;
            // healing potions: the reason to have witches at all (parameter witchEarly) - from the bank
            boolean potions = r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HEALING_POTIONS
                    && Strategy.witches(bot) && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore;
            if (potions) bags = true;
            // monsters and piglins (the user: no husks or other unit upgrades, or too late): a unit upgrade is paid
            // from the bank once the army is forming (8 soldiers or minute 12), not from leftovers
            boolean unitTech = !plan.villagers && bot.difficulty == Difficulty.EXPERT && r.item != plan.marketUpgrade
                    && r.item != com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_RESOURCE_CAPACITY
                    && (in.army.size() >= 8 || BuildOrder.minutes() >= 12) && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore;
            if (unitTech && bot.params.b("techSave")) bags = true;
            // ...and when it cannot be paid yet, the army leaves its price in the bank until it can (the army spent
            // every coin and the laboratory never got its husks in a 20-minute game)
            boolean techWanted = !plan.villagers && bot.difficulty == Difficulty.EXPERT && r.item != plan.marketUpgrade
                    && r.item != com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_RESOURCE_CAPACITY
                    && (in.army.size() >= 8 || BuildOrder.minutes() >= 12) && in.built(r.at) > 0 && !unitTech && techSave == null;
            if (techWanted && bot.params.b("techSave")) techSave = c;
            // huskRush (monsters): zombies that do not burn are the day army - from the bank, first in line
            if (p.b("huskRush") && r.item == com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HUSKS
                    && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore) bags = true;
            if (tier && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore) bags = true;
            if (!bags && (f < c.food + margin || w < c.wood + margin || o < c.ore + margin / 2)) continue;
            for (BuildingPlacement b : in.own) {
                if (!b.isBuilt || b.getBuilding() != r.at || !(b instanceof ProductionPlacement pp)) continue;
                if (perBuilding && b.getUpgradeLevel() > 0) continue;
                // healing potions queue behind the witch in training (the hut never stood empty, so the
                // research never came and the healing throws did nothing)
                if (!pp.productionQueue.isEmpty() && !(potions && pp.productionQueue.size() < 2)) continue;
                boolean started = pp.startProductionItem(r.item);
                if (!started && potions && bot.verbose) bot.log("potions research refused at the witch hut (queue " + pp.productionQueue.size() + ")");
                if (started) {
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
                if (!b.isBuilt || b.getBuilding() != plan.heroBuilding
                        || !(b instanceof ProductionPlacement pp) || !pp.productionQueue.isEmpty()) continue;
                for (ProductionItem item : plan.heroes) {
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

        if (techSave != null) { f -= techSave.food; w -= techSave.wood; o -= techSave.ore; if (bot.verbose && bot.now % 1200 < 5) bot.log("army leaves " + techSave.food + "/" + techSave.wood + "/" + techSave.ore + " for a unit upgrade"); }
        // ---- army: what is left after villagers and escrow, or the army fund if that is more
        if (L.on()) {
            f = Math.max(f + (int) L.fundFood, (int) L.fundFood);
            w = Math.max(w + (int) L.fundWood, (int) L.fundWood);
            o = Math.max(o + (int) L.fundOre, (int) L.fundOre);
        }
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
            if (b.getBuilding() == bot.plan.heroBuilding) continue;
            if (pp.productionQueue.size() >= perBuilding) continue;
            if (armyCapped) { idleReason = "army cap"; continue; }
            if (!bot.attentive()) continue;
            FactionPlan.UnitOption best = null;
            double bestGap = -1e9;
            // the lower tiers stubbornly wait for their preferred unit; the upper tiers build the best
            // unit they can afford right now instead of idling the building
            boolean flexible = bot.difficulty.ordinal() >= Difficulty.HARD.ordinal();
            boolean saving = savingForPotions(in);
            for (FactionPlan.UnitOption u : plan.units) {
                if (saving && cost(u.item).wood > 0) continue;
                // the first witches are paid from the bank, ahead of buildings waiting for their wood
                boolean firstWitches = u.role == FactionPlan.Role.SUPPORT && Strategy.witches(bot)
                        && have.getOrDefault(FactionPlan.Role.SUPPORT, 0.0) < 3 * p.get("witchMin");
                if (flexible) {
                    ResourceCost cu = cost(u.item);
                    boolean ok = firstWitches ? in.food >= cu.food && in.wood >= cu.wood && in.ore >= cu.ore : pays(cu, f, wFor(u, in, w, o), o);
                    if (!ok || !room(in, u.item)) continue;
                }
                if (u.producer != b.getBuilding()) continue;
                // witches early (parameter witchEarly): the only healing the villagers have
                boolean earlyWitch = u.role == FactionPlan.Role.SUPPORT && Strategy.witches(bot);
                boolean evokerEarly = u.item == com.solegendary.reignofnether.building.production.ProductionItems.EVOKER && plan.villagers && bot.composition.evokerRush(in);
                if (in.workers.size() < (earlyWitch || evokerEarly ? 16 : u.minWorkers)) continue;
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
            boolean bankWitch = best.role == FactionPlan.Role.SUPPORT && Strategy.witches(bot)
                    && have.getOrDefault(FactionPlan.Role.SUPPORT, 0.0) < 3 * p.get("witchMin") && in.food >= c.food && in.wood >= c.wood && in.ore >= c.ore;
            if ((!bankWitch && !pays(c, f, wFor(best, in, w, o), o)) || !room(in, best.item)) { idleReason = "cannot afford " + best.item.getItemName(); continue; }
            if (pp.startProductionItem(best.item)) {
                bot.ledger.spent(c.food, c.wood, c.ore);
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

    private long savingSince = -1;

    /**
     * Healing potions (350 wood + 350 ore) never came: the wood was always spent first. With witches out,
     * the ore in and the research not yet done, wood is saved for it for up to 90 seconds.
     */
    boolean savingForPotions(Intel in) {
        check:
        {
            if (bot.difficulty != Difficulty.EXPERT || !Strategy.witches(bot) || bot.army.sinceThreat() < 600) break check;
            var item = com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HEALING_POTIONS;
            if (ResearchServerEvents.playerHasResearch(bot.name, item) || item.itemIsBeingProduced(false, bot.name)) break check;
            if (in.built(com.solegendary.reignofnether.building.Buildings.WITCH_HUT) == 0 || in.ore < 350) break check;
            int witches = 0;
            for (var a : in.army) if (a.getClass().getSimpleName().equals("WitchUnit")) witches++;
            if (witches < 1) break check;
            if (savingSince < 0) savingSince = bot.now;
            if (bot.now - savingSince < 1800) return true;
            if (bot.now - savingSince > 4800) savingSince = -1; // a new try a while later
            return false;
        }
        savingSince = -1;
        return false;
    }

    /** every finished barracks has something in its queue */
    boolean allBarracksBusy(Intel in) {
        int n = 0;
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || b.getBuilding() != bot.plan.barracks || !(b instanceof ProductionPlacement pp)) continue;
            n++;
            if (pp.productionQueue.isEmpty()) return false;
        }
        return n > 0;
    }

    /** desired share of army population per role */
    Map<FactionPlan.Role, Double> roleMix(Intel in) {
        if (bot.plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS && bot.params.b("monsterCounter")) return monsterCounter(in);
        Map<FactionPlan.Role, Double> m = roleMix0(in);
        // monsters (the user's rule): the enemy fields evokers - no wraiths, their share goes to the rest
        if (bot.plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS && enemyEvokers(in)) {
            double w = m.getOrDefault(FactionPlan.Role.CASTER, 0.0);
            if (w > 0) { m.put(FactionPlan.Role.CASTER, 0.0); m.merge(FactionPlan.Role.MELEE, w, Double::sum); }
        }
        return m;
    }

    /**
     * The user's monster rules (monsterCounter): wraiths dodge half of all damage - the core of the army,
     * with zombies (front) and skeletons (behind) always in it for mixed damage. Against a crossbow-heavy army
     * more slimes (they eat each other and grow) and wraiths; against vindicators and golems more wraiths and
     * creepers; against evokers no wraiths at all - slimes and a sea of skeletons. Shares of army population
     * by role: MELEE zombies, RANGED skeletons, CASTER wraiths, SIEGE creepers, SUPPORT slimes.
     */
    private Map<FactionPlan.Role, Double> monsterCounter(Intel in) {
        int ranged = 0, heavy = 0, all = 0;
        boolean evokers = enemyEvokers(in);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.List<LivingEntity> es = new java.util.ArrayList<>(in.visibleEnemies);
        for (Intel.EnemyMemory em : in.enemies.values()) es.addAll(em.seenUnits.values());
        for (LivingEntity e : es) {
            if (!seen.add(e.getId()) || UnitEval.isWorker(e) || UnitEval.isScout(e)) continue;
            all++;
            if (UnitEval.isRanged(e)) ranged++;
            if (e instanceof com.solegendary.reignofnether.unit.units.villagers.VindicatorUnit
                    || e instanceof com.solegendary.reignofnether.unit.units.villagers.IronGolemUnit
                    || e instanceof com.solegendary.reignofnether.unit.units.villagers.RavagerUnit) heavy++;
        }
        double z, sk, w, c, sl;
        String why;
        if (evokers) { z = 0.15; sk = 0.45; w = 0; c = 0.10; sl = 0.30; why = "evokers"; }
        else if (all >= 4 && ranged >= 0.5 * all) { z = 0.15; sk = 0.15; w = 0.40; c = 0.05; sl = 0.25; why = "crossbows"; }
        else if (all >= 4 && heavy >= 0.5 * all) { z = 0.15; sk = 0.20; w = 0.40; c = 0.25; sl = 0; why = "melee"; }
        else { z = 0.25; sk = 0.25; w = 0.35; c = 0.10; sl = 0.05; why = "mixed"; }
        // what cannot be made yet (no dungeon / slime pit) goes to the zombies and skeletons
        boolean dungeon = in.built(com.solegendary.reignofnether.building.Buildings.DUNGEON) > 0;
        boolean pit = in.built(com.solegendary.reignofnether.building.Buildings.SLIME_PIT) > 0;
        double spare = (dungeon ? 0 : w + c) + (pit ? 0 : sl);
        if (!dungeon) { w = 0; c = 0; }
        if (!pit) sl = 0;
        double zs = z + sk;
        z += spare * z / zs; sk += spare * sk / zs;
        if (!why.equals(counterWhy)) { counterWhy = why; bot.log("army mix vs " + why + ": zombies " + Math.round(z * 100) + "% skeletons " + Math.round(sk * 100)
                + "% wraiths " + Math.round(w * 100) + "% creepers " + Math.round(c * 100) + "% slimes " + Math.round(sl * 100) + "%"); }
        Map<FactionPlan.Role, Double> m = new java.util.EnumMap<>(FactionPlan.Role.class);
        // later game (the user): spiders as mounts for the skeletons (spider jockeys) once the lair stands
        double sp = bot.params.b("monsterSpiders") && BuildOrder.minutes() >= 15 && in.built(com.solegendary.reignofnether.building.Buildings.SPIDER_LAIR) > 0 ? 0.12 : 0;
        z = Math.max(0.08, z - sp);
        // monsterArena (v11): the same-cost arena results against what the enemy fields tilt zombies (husks once
        // researched), skeletons, creepers and spiders; wraiths and slimes keep their share (no arena data), and
        // zombies and skeletons never drop under 8% (the user: mixed damage always)
        if (bot.params.b("monsterArena")) {
            boolean husks = ResearchServerEvents.playerHasResearch(bot.name, com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HUSKS);
            double[] sc = arenaScores(es, husks);
            if (sc != null) {
                double[] v = { z, sk, c, sp };
                double before = z + sk + c + sp, after = 0;
                for (int i = 0; i < 4; i++) { v[i] *= Math.exp(sc[i] / 0.4); after += v[i]; }
                if (after > 0) for (int i = 0; i < 4; i++) v[i] *= before / after;
                z = Math.max(0.08, v[0]); sk = Math.max(0.08, v[1]); c = v[2]; sp = v[3];
            }
        }
        m.put(FactionPlan.Role.MELEE, z); m.put(FactionPlan.Role.RANGED, sk); m.put(FactionPlan.Role.CASTER, w);
        m.put(FactionPlan.Role.SIEGE, c); m.put(FactionPlan.Role.SUPPORT, sl); m.put(FactionPlan.Role.TANK, sp);
        return m;
    }
    private String counterWhy = "";

    // piglins against the other factions, same cost (arena APIG1 + AVX + ANIGHT at night). Rows by role:
    // brute (MELEE), headhunter (RANGED), magma cube (SUPPORT), ghast (SIEGE), hoglin (TANK), blaze (CASTER)
    private static final String[] PIG_FOES = { "vindicator", "pillager", "golem", "witch", "evoker", "zombie", "husk", "skeleton", "creeper", "spider" };
    private static final FactionPlan.Role[] PIG_ROLES = { FactionPlan.Role.MELEE, FactionPlan.Role.RANGED, FactionPlan.Role.SUPPORT,
            FactionPlan.Role.SIEGE, FactionPlan.Role.TANK, FactionPlan.Role.CASTER };
    private static final double[][] PIG_VALUE = {
            { 0.206, 0.479, 0.520, 0.350, 0.0, -0.019, 0.028, 0.535, 0.033, 0.418 },      // brute
            { -0.589, 0.373, -0.496, 0.194, 0.521, -0.668, -0.679, -0.369, 0.948, -0.375 }, // headhunter
            { 0.107, 0.432, 0.225, 0.414, 0.301, -0.093, -0.050, 0.294, 0.095, 0.375 },    // magma cube
            { 1.000, 0.021, 0.0, 0.0, 0.0, 1.000, 0.0, 0.640, 0.0, 0.0 },                  // ghast
            { -0.481, 0.122, -0.427, 0.477, -0.277, -0.623, -0.652, -0.601, 0.210, -0.342 }, // hoglin
            { -0.698, -0.508, -0.708, -0.797, 0.0, -0.568, -0.496, -0.469, 0.005, -0.495 } }; // blaze

    /** piglinArena (v11): the arena tilts the piglin mix against the enemy army seen; brutes stay the core (20%+) */
    private void piglinTilt(Intel in, Map<FactionPlan.Role, Double> m) {
        double[] w = new double[PIG_FOES.length];
        double tot = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.List<LivingEntity> es = new java.util.ArrayList<>(in.visibleEnemies);
        for (Intel.EnemyMemory em : in.enemies.values()) es.addAll(em.seenUnits.values());
        for (LivingEntity e : es) {
            if (!seen.add(e.getId()) || UnitEval.isWorker(e)) continue;
            String id = e.getType().getDescriptionId();
            String n = id.substring(id.lastIndexOf('.') + 1).replace("_unit", "").replace("iron_golem", "golem").replace("stray", "skeleton").replace("poison_spider", "spider");
            for (int i = 0; i < PIG_FOES.length; i++) if (PIG_FOES[i].equals(n)) { w[i]++; tot++; break; }
        }
        if (tot < 4) return;
        double before = 0, after = 0;
        double[] v = new double[PIG_ROLES.length];
        for (int k = 0; k < PIG_ROLES.length; k++) {
            v[k] = m.getOrDefault(PIG_ROLES[k], 0.0);
            before += v[k];
            double sc = 0;
            for (int i = 0; i < w.length; i++) sc += w[i] / tot * PIG_VALUE[k][i];
            v[k] *= Math.exp(sc / 0.4);
            after += v[k];
        }
        if (after <= 0) return;
        for (int k = 0; k < PIG_ROLES.length; k++) m.put(PIG_ROLES[k], v[k] * before / after);
        if (m.get(FactionPlan.Role.MELEE) < 0.2) m.put(FactionPlan.Role.MELEE, 0.2);
    }

    // arena ANIGHT (2026-11-19, at night), same cost, our unit's value against each enemy type (+1 = untouched)
    private static final String[] MON_FOES = { "vindicator", "pillager", "golem", "witch", "evoker",
            "brute", "headhunter", "hoglin", "blaze", "witherskeleton", "magmacube" };
    private static final double[][] MON_VALUE = { // at night (arena ANIGHT: the day run had zombies and skeletons burning)
            { 0.186, 0.610, 0.502, 1.000, -0.379, 0.089, 0.669, 0.600, 0.541, 0.802, 0.119 },     // zombie
            { 0.110, 0.659, 0.635, 1.000, -0.293, -0.028, 0.679, 0.652, 0.496, 0.774, 0.050 },    // husk
            { -0.466, 0.557, -0.425, 1.000, 0.301, -0.523, 0.402, 0.643, 0.488, 0.592, -0.317 },  // skeleton
            { -0.021, -0.538, -0.296, 0.040, -0.970, -0.033, -0.948, -0.210, -0.005, -0.006, -0.095 }, // creeper
            { -0.326, 0.486, 0.133, 0.962, -0.140, -0.418, 0.375, 0.342, 0.495, 0.650, -0.375 } };  // spider  // spider

    /** zombie (or husk), skeleton, creeper, spider: cost-weighted mean arena value against the enemy army seen; null if too few seen */
    private static double[] arenaScores(java.util.List<LivingEntity> es, boolean husks) {
        double[] w = new double[MON_FOES.length];
        double tot = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (LivingEntity e : es) {
            if (!seen.add(e.getId()) || UnitEval.isWorker(e)) continue;
            String id = e.getType().getDescriptionId();
            String n = id.substring(id.lastIndexOf('.') + 1).replace("_unit", "").replace("iron_golem", "golem").replace("wither_skeleton", "witherskeleton").replace("magma_cube", "magmacube");
            for (int i = 0; i < MON_FOES.length; i++) if (MON_FOES[i].equals(n)) { w[i]++; tot++; break; }
        }
        if (tot < 4) return null;
        double[] out = new double[4];
        int[] rows = { husks ? 1 : 0, 2, 3, 4 };
        for (int k = 0; k < 4; k++) { double s = 0; for (int i = 0; i < w.length; i++) s += w[i] / tot * MON_VALUE[rows[k]][i]; out[k] = s; }
        return out;
    }

    /** the enemy's mix calls for slimes: they eat each other to grow - every slime of ours gets the autocast */
    boolean slimesWanted(Intel in) { return bot.params.b("monsterCounter") && (enemyEvokers(in) || "crossbows".equals(counterWhy)); }

    private long lastConsume = -1000;
    private ResourceCost techSave;

    /**
     * Slimes and magma cubes grow by eating each other (RON's Consume: the eater, not at full size 6 or hurt,
     * takes a friendly one of its own size or smaller and gains half its health and damage). The autocast
     * switch did nothing for a computer player (the user saw none grow): every 5 s, out of a fight, the
     * biggest ones are told to eat the nearest smaller one.
     */
    void slimeAutocast(Intel in) {
        if (bot.now - lastConsume < 100) return;
        lastConsume = bot.now;
        java.util.List<com.solegendary.reignofnether.unit.units.monsters.SlimeUnit> slimes = new java.util.ArrayList<>();
        for (Mob m : in.army)
            if (m instanceof com.solegendary.reignofnether.unit.units.monsters.SlimeUnit su && m.isAlive() && in.enemiesNear(m.position(), 14).isEmpty()) slimes.add(su);
        if (slimes.size() < 2) return;
        slimes.sort((a, b) -> Integer.compare(b.getSize(), a.getSize()));
        java.util.Set<Integer> used = new java.util.HashSet<>();
        int orders = 0;
        for (var eater : slimes) {
            if (used.contains(eater.getId()) || (eater.getSize() >= 6 && eater.getHealth() >= eater.getMaxHealth())) continue;
            com.solegendary.reignofnether.unit.units.monsters.SlimeUnit food = null;
            for (var o : slimes) {
                if (o == eater || used.contains(o.getId()) || o.getSize() > eater.getSize()) continue;
                // a slime eats slimes, a magma cube magma cubes (the same kind)
                if ((o instanceof com.solegendary.reignofnether.unit.units.piglins.MagmaCubeUnit) != (eater instanceof com.solegendary.reignofnether.unit.units.piglins.MagmaCubeUnit)) continue;
                if (o.distanceToSqr(eater) > 24 * 24) continue;
                if (food == null || o.distanceToSqr(eater) < food.distanceToSqr(eater)) food = o;
            }
            if (food == null) continue;
            used.add(eater.getId()); used.add(food.getId());
            Orders.ability(bot.name, com.solegendary.reignofnether.unit.UnitAction.CONSUME_SLIME, food.getId(), java.util.List.of(eater), food.blockPosition());
            orders++;
        }
        if (orders > 0 && bot.verbose) bot.log("slimes: " + orders + " told to consume a smaller one");
    }

    /** any evoker in the enemy armies we have seen */
    private boolean enemyEvokers(Intel in) {
        for (Intel.EnemyMemory em : in.enemies.values())
            for (LivingEntity e : em.seenUnits.values())
                if (e instanceof com.solegendary.reignofnether.unit.units.villagers.EvokerUnit) return true;
        for (LivingEntity e : in.visibleEnemies)
            if (e instanceof com.solegendary.reignofnether.unit.units.villagers.EvokerUnit) return true;
        return false;
    }

    private Map<FactionPlan.Role, Double> roleMix0(Intel in) {
        BotParams p = bot.params;
        if (bot.plan.villagers && p.b("composition") && bot.difficulty == Difficulty.EXPERT) return bot.composition.roleMix(in);
        Map<FactionPlan.Role, Double> m = new EnumMap<>(FactionPlan.Role.class);
        // monsters: zombies take the hits, skeletons deal the damage behind them, creepers are the strongest
        // blow - the army is built to that ratio (a creeper the dungeon cannot make yet goes to the other two)
        // piglins (piglinMix): the arena's order - brutes the core, magma cubes once the springs stand,
        // headhunters against flyers, ghasts once the fortress stands
        if (bot.plan.portals && p.b("piglinMix")) {
            boolean springs = in.built(com.solegendary.reignofnether.building.Buildings.BASALT_SPRINGS) > 0;
            boolean fortress = in.built(com.solegendary.reignofnether.building.Buildings.FORTRESS) > 0;
            double ghast = fortress ? p.get("pigGhast") : 0, magma = springs ? p.get("pigMagma") : 0, head = p.get("pigHead");
            // later game (the user): hoglins as mounts for the headhunters, wither skeletons and blazes (they use
            // their skills now) - small shares once their buildings stand
            double mins = BuildOrder.minutes();
            double hog = mins >= 15 && in.built(com.solegendary.reignofnether.building.Buildings.HOGLIN_STABLES) > 0 ? 0.10 : 0;
            double wither = mins >= 18 && in.built(com.solegendary.reignofnether.building.Buildings.WITHER_SHRINE) > 0 ? 0.08 : 0;
            double blaze = mins >= 18 && in.built(com.solegendary.reignofnether.building.Buildings.FLAME_SANCTUARY) > 0 ? 0.08 : 0;
            m.put(FactionPlan.Role.SIEGE, ghast);
            m.put(FactionPlan.Role.SUPPORT, magma);
            m.put(FactionPlan.Role.RANGED, head);
            m.put(FactionPlan.Role.TANK, hog + wither);
            m.put(FactionPlan.Role.CASTER, blaze);
            m.put(FactionPlan.Role.MELEE, Math.max(0.2, 1 - ghast - magma - head - hog - wither - blaze));
            if (p.b("piglinArena")) piglinTilt(in, m);
            return m;
        }
        if (!bot.plan.villagers && p.b("monsterMix")) {
            double z = p.get("mixZombie"), s = p.get("mixSkeleton"), c = p.get("mixCreeper");
            if (in.built(com.solegendary.reignofnether.building.Buildings.DUNGEON) == 0) { double t = Math.max(1e-6, z + s); z /= t; s /= t; c = 0; }
            m.put(FactionPlan.Role.MELEE, z);
            m.put(FactionPlan.Role.RANGED, s);
            m.put(FactionPlan.Role.SIEGE, c);
            return m;
        }
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
        // lingering harming potions do most against packed melee armies: more witches when the enemy fields them
        double witchShare = p.get("witchShare");
        if (Strategy.witches(bot) && k > 0) {
            int em = 0, all = 0;
            for (LivingEntity e : in.visibleEnemies) { if (UnitEval.isWorker(e)) continue; all++; if (!UnitEval.isRanged(e)) em++; }
            if (all >= 6 && em >= 0.65 * all) witchShare *= 1.4;
        }
        m.put(FactionPlan.Role.SUPPORT, Strategy.witches(bot) ? witchShare : p.get("casterShare") > 0 ? 0.08 : 0);
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
