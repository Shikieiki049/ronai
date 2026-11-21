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
import net.minecraft.world.phys.Vec3;

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
    /** since when the builder's top want has been waiting for wood (-1: not waiting) - woodDemand reads it */
    long woodWaitSince = -1;

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
                // lean opening: no house ahead of the schedule's clock while the town centre has no food to train with
                if (in.total(plan.house) < sched && in.popCap < 150 && in.built(plan.capitol) > 0 && unbuiltHouses < 2
                        && freeSupply <= (bot.economy.foodStarved(in) ? 2 : 10))
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
            // (not while the start capitol is still going up: it is the drop site - a stockpile went up before the
            // locked first house)
            boolean capitolUp = in.built(plan.capitol) > 0;
            if (capitolUp && bot.economy.dropSites(in).isEmpty() && in.total(plan.stockpile) - in.built(plan.stockpile) == 0) {
                reqs.add(new Request(plan.stockpile, 110, home, 2, 20));
            }
            // drop site
            if (bot.economy.stockpileWanted != null && p.b("stockpileCooldown") && recentlyCancelled(bot.economy.stockpileWanted)) bot.economy.stockpileWanted = null;
            // not one for every new stand of trees (the user: 19 stockpiles in a monster game): at most one per four
            // woodcutters (4 at least) - beyond that only when the carriers really cannot deliver
            int woodcutters = bot.economy.counts.getOrDefault(Economy.Job.WOOD, 0);
            boolean stockFull = p.b("stockCap") && (plan.portals ? in.popCap >= 150 : in.total(plan.stockpile) >= Math.max(4, woodcutters / 4))
                    && bot.now - bot.economy.deliveryTrouble > 2400;
            if (capitolUp && !stockFull && bot.economy.stockpileWanted != null && in.total(plan.stockpile) - in.built(plan.stockpile) == 0
                    && workers >= 4 && !dropSiteNear(in, bot.economy.stockpileWanted, 12)) {
                // villagers walking around with full packs they cannot drop anywhere: nothing else matters
                // (not right after one was abandoned: wolves chasing the builders make that a wood sink)
                boolean stuckLoads = bot.now - bot.economy.deliveryTrouble < 2400 && bot.economy.stockpileWanted.distSqr(home) < 40 * 40 && bot.now - lastStockpileCancel > 3000;
                // wood short and the woods far from every drop site: woodcutters walk 80-170 blocks a trip
                // (a player's game: 27 woodcutters brought in 350 wood in 8 minutes) - a stockpile there now
                boolean farWood = bot.difficulty == Difficulty.EXPERT && bot.economy.stockpileFor == com.solegendary.reignofnether.resources.ResourceName.WOOD
                        && in.wood < 200 && !dropSiteNear(in, bot.economy.stockpileWanted, 30);
                // the woodcutters' walk has grown long (they move on tree by tree, away from the drop site)
                boolean longWalk = p.b("woodDrop") && bot.economy.farWoodTrip && bot.economy.stockpileFor == com.solegendary.reignofnether.resources.ResourceName.WOOD;
                // stockpileEager: 75 wood that shortens every woodcutter's trip pays back within minutes - ahead of the next house or farm
                int base = p.b("stockpileEager") && bot.economy.counts.getOrDefault(Economy.Job.WOOD, 0) >= p.i("stockpileEagerWorkers")
                        && in.built(plan.farm) >= p.i("stockpileEagerFarms") ? 96 : 75;
                reqs.add(new Request(plan.stockpile, stuckLoads ? 110 : farWood ? 100 : longWalk ? 98 : base, bot.economy.stockpileWanted, 2, 14));
            }
            // boom: extra capitols double and triple villager production (the AoE2 multi-TC boom)
            int capitols = in.total(plan.capitol);
            // the user's rule: never a second town centre (mausoleum / central portal) - one capitol per game
            int extra = 0;
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
            // money piling up with room for an army and every barracks busy (the user: late game lots of money, no
            // army): more barracks, past maxBarracks, up to 10
            if (bot.difficulty == Difficulty.EXPERT && p.b("extraBarracks") && in.food + in.wood + in.ore > 800 && in.popCap - in.pop >= 10 && barracks >= wantBarracks
                    && barracks < 10 && allBarracksBusy(in)) wantBarracks = barracks + 1;
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
            // a sparring style with a fixed opening (rush / timed push): barracks at a set villager count
            int styleAt = p.i("styleBarracksAtWorkers");
            boolean styleNow = styleAt > 0 && workers >= styleAt && barracks < (workers >= styleAt + 6 ? 2 : 1);
            if (styleNow) wantBarracks = Math.max(wantBarracks, barracks + 1);
            // food banking up with every barracks at work (parameter floatBarracks): one more barracks turns it
            // into army - mid-game armies stayed at a third to a half of a human's while food sat unspent
            if (evenExpert && p.b("floatBarracks") && in.food >= 600 && in.wood >= 150 && barracks >= 1 && bot.production.allBarracksBusy(in))
                wantBarracks = Math.max(wantBarracks, Math.min(p.i("maxBarracks") + 2, barracks + 1));
            // foodBarracks: food piling up while every barracks trains (a 2v2 the user won: the allied expert had
            // 500-760 food in the bank from minute 13 and one barracks until minute 15 - two soldiers a minute
            // against the user's six from three barracks by minute 11). Vindicators are food only: the next
            // barracks goes ahead of fields, stockpiles and spare houses.
            boolean foodBarracks = bot.difficulty == Difficulty.EXPERT && p.b("foodBarracks") && barracks >= 1 && workers >= 14
                    && in.food >= p.get("foodBarracksFood") && bot.production.allBarracksBusy(in) && in.built(plan.barracks) == barracks;
            if (foodBarracks) wantBarracks = Math.max(wantBarracks, Math.min(p.i("maxBarracks") + 2, barracks + 1));
            // boomDone (parameter boomBarracks): the town centre is near the villager cap - from here food goes to
            // the army, so the barracks it needs go up ahead of more fields (the user: three barracks by minute 11,
            // the expert one at minute 10 while every 150 wood went to field after field)
            boolean boomDone = bot.difficulty == Difficulty.EXPERT && p.b("boomBarracks") && workers >= p.i("vilCap") - p.i("boomBarracksSlack")
                    && in.built(plan.farm) >= p.i("boomBarracksFarms");
            if (boomDone) wantBarracks = Math.max(wantBarracks, Math.min(p.i("maxBarracks"), 1 + in.built(plan.farm) / 3));
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
            // rush defence: two barracks now, ahead of everything but supply
            boolean rushDef = evenExpert && (bot.army.rushDefence(in) || (Strategy.respondTo(bot, Strategy.RUSH) && workers >= 8 && BuildOrder.minutes() < 12));
            if (rushDef) wantBarracks = Math.max(wantBarracks, 2);
            boolean campRush = evenExpert && bot.army.campRush(in) && workers >= 6;
            if (campRush) wantBarracks = Math.max(wantBarracks, 1);
            if (barracks < wantBarracks) {
                double pri = rushDef || campRush ? 106 : earlyArmy ? (barracks == 0 ? 95 : 84) : (barracks == 0 ? 80 : 62) - barracks * 4;
                if (evenExpert && workers >= 18) pri = barracks < 2 ? 95 : 88;
                if (styleNow) pri = 105;
                if (foodBarracks || boomDone) pri = Math.max(pri, 101);
                if (beastTrouble || (evenExpert && barracks == 0 && workers >= 10 && in.built(plan.farm) >= 2 && (!bot.army.safeToBoom(in) || bot.army.homeCampStrength(in) > 0))) pri = 105;
                reqs.add(new Request(plan.barracks, pri, pushed ? bot.army.rearAnchor(in) : bot.army.frontAnchor(in), 10, 24));
            }
            // tech
            int techOwned = 0;
            for (FactionPlan.TechStep t : plan.tech) if (in.total(t.building) > 0) techOwned++;
            // outnumbered: tech that does not make the army bigger right now is a luxury; only the golem smith
            boolean floating = in.food + in.wood + in.ore > 2500;
            // even expert: enchants were 0 all game - the library (4th in line) never came while wood was short.
            // After the blacksmith it is next, ahead of the arcane tower and witch hut
            if (plan.villagers && bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.total(com.solegendary.reignofnether.building.Buildings.LIBRARY) == 0
                    && in.built(com.solegendary.reignofnether.building.Buildings.BLACKSMITH) > 0 && in.built(plan.barracks) >= 2 && workers >= 30
                    // not before minute 15 and only when safe: its 300 wood at minute 9 left Snowplow without the army for the first wave
                    && com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks >= 18000 && bot.army.safeToBoom(in))
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.LIBRARY, 76, home, 12, 28));
            // armour from minute smithFrom (even expert): the blacksmith once a barracks stands and its ore is in
            if (plan.villagers && bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && BuildOrder.minutes() >= p.get("smithFrom")
                    && in.total(com.solegendary.reignofnether.building.Buildings.BLACKSMITH) == 0 && in.built(plan.barracks) >= 1
                    && in.ore >= com.solegendary.reignofnether.building.Buildings.BLACKSMITH.cost.ore && bot.army.sinceThreat() > 600)
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.BLACKSMITH, 89, home, 12, 30));
            // monsters (monsterCounter): the dungeon is the core of the army (wraiths dodge half of all damage,
            // creepers) - right after the first graveyard, ahead of fields and catalysts; it sat at priority 44-69
            // behind every field for 20 minutes
            if (plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS && p.b("monsterCounter")
                    && in.total(com.solegendary.reignofnether.building.Buildings.DUNGEON) == 0 && in.built(plan.barracks) >= 1 && workers >= 18)
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.DUNGEON, 93, home, 10, 30));
            // ...and the slime pit once the enemy fields crossbows or evokers
            if (plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS && bot.production.slimesWanted(in)
                    && in.total(com.solegendary.reignofnether.building.Buildings.SLIME_PIT) == 0 && in.built(plan.barracks) >= 1 && workers >= 20)
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.SLIME_PIT, 91, home, 10, 30));
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
            if (plan.villagers && p.get("golemShare") >= 0.3 && bot.outnumbered() && in.built(plan.barracks) > 0) {
                Building smith = bot.plan.option(com.solegendary.reignofnether.building.production.ProductionItems.IRON_GOLEM).producer;
                int smiths = in.total(smith);
                // ore piling up (a rich start, nothing else wants it): the golem smith turns it into army
                if (smiths == 0 && workers >= 12 && (!evenLean || in.ore >= 450)) reqs.add(new Request(smith, 83, home, 12, 30));
                else if (smiths == 1 && in.built(smith) == 1 && in.ore >= 600 && workers >= 24)
                    reqs.add(new Request(smith, 66, home, 12, 34));
            }
            // even game: food is the bottleneck while the starting/stray ore sits idle - golems cost no
            // food and beat the first vindicator raids, so a golem smith once the barracks are going
            if (plan.villagers && bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && in.built(plan.barracks) >= 1
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
                // ore piling up while wood is gone (a player's game: 1185 ore, 0 wood, no market in 22 min - the
                // 88 request never beat the fields and houses to the 75 wood): the market comes first
                if (bot.difficulty == Difficulty.EXPERT && in.ore >= 400 && in.wood < 150) reqs.add(new Request(bot.plan.market, 106, home, 12, 34));
                else if (hi > 700 && lo < 200) reqs.add(new Request(bot.plan.market, hi >= 1000 && lo < 150 ? 88 : 74, home, 12, 34));
                // the expert puts one up ahead of need: once the wood is gone the market is the only
                // way to get any, and it costs wood itself
                else if (bot.difficulty == Difficulty.EXPERT && workers >= 26 && in.wood >= 200 && in.ore >= 150)
                    reqs.add(new Request(bot.plan.market, 60, home, 12, 34));
            }
            // golem route: the blacksmith as soon as its ore is in, then golems built by villagers
            if (plan.villagers && Strategy.golems(bot) && in.built(plan.capitol) > 0) {
                if (in.total(com.solegendary.reignofnether.building.Buildings.BLACKSMITH) == 0 && in.ore >= com.solegendary.reignofnether.building.Buildings.BLACKSMITH.cost.ore && workers >= 8)
                    reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.BLACKSMITH, 99, home, 12, 30));
                boolean smithing = com.solegendary.reignofnether.research.ResearchServerEvents.playerHasResearch(bot.name, com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_GOLEM_SMITHING);
                int building = in.total(com.solegendary.reignofnether.building.Buildings.IRON_GOLEM_BUILDING) - in.built(com.solegendary.reignofnether.building.Buildings.IRON_GOLEM_BUILDING);
                if (smithing && building < p.i("golemSites") && in.ore >= com.solegendary.reignofnether.building.Buildings.IRON_GOLEM_BUILDING.cost.ore && in.popCap - in.pop >= 4)
                    reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.IRON_GOLEM_BUILDING, 95, bot.army.forwardHome(in), 4, 16));
            }
            // witch hut early (a player's advice): witches are the villagers' only healing, and their
            // harming potions hit hard - right after the first barracks, ahead of the blacksmith
            if (plan.villagers && Strategy.witches(bot) && !bot.outnumbered()
                    && in.total(com.solegendary.reignofnether.building.Buildings.WITCH_HUT) == 0 && in.built(plan.barracks) >= 1
                    && workers >= 18 && BuildOrder.minutes() >= p.get("witchFrom"))
                reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.WITCH_HUT, 91, home, 12, 30));
            // early shrine (a player's advice): right after the first house - heroes cost nothing but time,
            // so the sooner the shrine stands the more of the game they fight in
            // ...or a little later (shrineEarlyMinute / shrineEarlyFields): its 125 wood at minute 1 slowed the
            // first fields, which the brutal bot's rushes punished
            if (p.b("heroes") && p.b("shrineEarly") && in.total(plan.heroBuilding) == 0
                    && in.total(plan.house) >= 1 && workers >= 6
                    && BuildOrder.minutes() >= p.get("shrineEarlyMinute")
                    && in.built(plan.farm) >= p.get("shrineEarlyFields") + (bot.economy.foodStarved(in) ? 2 : 0))
                reqs.add(new Request(plan.heroBuilding, 99, home, 10, 30));
            // the shrine at mid game (parameter shrineMid): at priority 72 it lost to every field, house and
            // barracks for wood and most games never had a hero; from minute shrineMinute it goes ahead of them
            if (p.b("heroes") && p.b("shrineMid") && bot.difficulty == Difficulty.EXPERT
                    && in.total(plan.heroBuilding) == 0
                    && in.built(plan.barracks) > 0 && BuildOrder.minutes() >= p.get("shrineMinute"))
                reqs.add(new Request(plan.heroBuilding, 92, home, 10, 30));
            // shrine of prosperity: cheap, and its heroes cost no resources at all
            if (p.b("heroes") && in.total(plan.heroBuilding) == 0
                    && in.built(plan.barracks) > 0 && workers >= 16 && !evenLean) {
                reqs.add(new Request(plan.heroBuilding, 72, home, 10, 30));
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

        // evokerVsMon: against monsters the arcane tower comes right after the first barracks (it came at minute
        // 15-30, after the zombie sea had already won)
        if (plan.villagers && bot.composition.evokerRush(in) && in.built(plan.barracks) > 0 && workers >= 16
                && in.total(com.solegendary.reignofnether.building.Buildings.ARCANE_TOWER) == 0)
            reqs.add(new Request(com.solegendary.reignofnether.building.Buildings.ARCANE_TOWER, 92, home, 12, 30));
        // the composition planner's tech: the building of a unit that beats what we can train
        if (plan.villagers && p.b("composition") && bot.difficulty == Difficulty.EXPERT && in.built(plan.barracks) > 0 && workers >= 18) {
            Building tech = bot.composition.techWanted(in);
            if (tech != null && in.total(tech) == 0) {
                boolean ok = tech != com.solegendary.reignofnether.building.Buildings.CASTLE
                        || in.built(com.solegendary.reignofnether.building.Buildings.WITCH_HUT) > 0
                        && in.built(com.solegendary.reignofnether.building.Buildings.BLACKSMITH) > 0
                        && in.built(com.solegendary.reignofnether.building.Buildings.ARCANE_TOWER) > 0;
                if (ok) reqs.add(new Request(tech, 82, home, 12, 30));
            }
        }
        BuildOrder.shape(bot, in, reqs, home);
        // fieldsAlways (v12): the user's field curve holds under threat too - the opening's shaping stopped for
        // every enemy seen in the last minute, and in real games the fields stalled at 9 from minute 8 to 11
        // (the user: 12 -> 14 -> 16). Behind the curve by 2 or more, a field outranks barracks.
        if (p.b("fieldsAlways") && bot.difficulty == Difficulty.EXPERT && in.built(plan.capitol) > 0 && workers >= 4) {
            double m = BuildOrder.minutes() + p.get("openingLead");
            int want = (int) Math.ceil(BuildOrder.at(BuildOrder.FIELDS, Math.min(m, 12)) * p.get("fieldsScale") - 0.01);
            if (m > 12) want = Math.max(want, (int) Math.min(16 * p.get("fieldsScale"), 15 + (m - 12)));
            int have = in.total(plan.farm);
            if (have < want && in.total(plan.farm) - in.built(plan.farm) < 2) {
                reqs.removeIf(r -> r.building() == plan.farm);
                reqs.add(new Request(plan.farm, have <= want - 2 ? 102 : 97, home, 10, 34));
            }
        }
        // LOCKED opening rule (the user's, every faction): after the capitol the first building is the
        // population building and the second the hero building - heroes level up and only get stronger, a
        // hero that does not pay off means its play is wrong, not the rule. Only emergencies (priority 113+:
        // no drop site, a dead economy) go ahead of them.
        if (in.built(plan.capitol) > 0 && plan.heroBuilding != null) {
            // a wide ring: a crowded start (a city map) has no room for a house within 30 blocks
            if (in.total(plan.house) == 0) reqs.add(new Request(plan.house, 112, home, 6, 48));
            else if (in.total(plan.heroBuilding) == 0 && p.b("heroes")) reqs.add(new Request(plan.heroBuilding, 111, home, 8, 48));
            else lockedSince = -1;
        }
        // stall protection: no field standing or going up, the bank empty of food and the hunt gone -
        // the town centre is dead until a field grows, so the first field outranks everything
        if (p.b("stallFarm") && in.built(plan.capitol) > 0 && in.total(plan.farm) - in.built(plan.farm) == 0
                && in.built(plan.farm) < 2 && in.food < 50 && workers >= 3) {
            if (stallSince < 0) stallSince = bot.now;
            if (bot.now - stallSince >= 600) {
                reqs.add(new Request(plan.farm, 115, home, 6, 34));
                if (!stallLogged) { stallLogged = true; bot.stalls++; bot.log("stall: no food, " + in.built(plan.farm) + " fields - a field first"); }
            }
        } else { stallSince = -1; stallLogged = false; }
        if (plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS) monsterRules(in, reqs, workers, home);
        // piglins: the fields found no nether ground (a small island: the workers stopped at 18) - another portal
        // spreads 20 blocks more of it, and holds 15 more population
        if (plan.portals && bot.now - groundShortAt < 600 && in.total(com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC)
                - in.built(com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC) == 0 && workers >= 8)
            reqs.add(new Request(plan.house, 99, home, 8, 30));
        if ((plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS || plan.portals) && p.b("darkPath")) darkPath(in, reqs, workers, home);
        if (plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS && p.b("darkPath")) workDark(in, reqs);
        reqs.removeIf(r -> r.building == null); // a building this faction does not have (piglins: no tower)
        // population at RON's cap (150): no more houses for population (22 piglin portals in one game) - a dark
        // path catalyst or a portal for nether ground (priority 99+) still goes up
        if (in.popCap >= 150 && p.b("stockCap")) reqs.removeIf(r -> r.building == plan.house && r.priority < 99);
        reqs.sort((a, b) -> Double.compare(b.priority, a.priority));
        reserved = null;
        note = reqs.isEmpty() ? "nothing wanted" : "";
        if (bot.verbose && bot.now - lastSpendLog >= 200) {
            lastSpendLog = bot.now;
            StringBuilder sb = new StringBuilder("spend: bank " + in.food + "/" + in.wood + "/" + in.ore + " wants");
            for (int i = 0; i < Math.min(5, reqs.size()); i++) sb.append(' ').append(name(reqs.get(i).building)).append(':').append((int) reqs.get(i).priority);
            if (p.b("composition")) sb.append(" | comp ").append(bot.composition.describe());
            sb.append(" | last: ").append(lastNote).append(" | units: ").append(bot.production.idleReason.isEmpty() ? "training" : bot.production.idleReason);
            bot.log(sb.toString());
        }
        for (Request r : reqs) {
            Building b = r.building;
            // the locked opening (population building, then the hero building) waits for its spot: nothing else
            // takes the wood meanwhile - on a crowded city map the house found no spot at first and a stockpile
            // went up first (the user saw it). After two minutes of no spot the rest may go on.
            // (a piglin hero portal needs nether ground: it does not hold the rest up - a portal spreads the
            // ground first; a city map stalled the piglins at 25/25 with 2200 wood waiting for it)
            boolean locked = r.priority >= 111 && r.priority < 113 && (b == plan.house || b == plan.heroBuilding)
                    && !(plan.portals && b == plan.heroBuilding);
            if (locked && lockedSince >= 0 && bot.now - lockedSince >= 2400) locked = false; // waited long enough
            if (locked && bot.now - lastFail.getOrDefault(b, -100000L) < 40) {
                if (lockedSince < 0) lockedSince = bot.now;
                if (note.isEmpty()) note = "looking for a spot for " + name(b);
                break;
            }
            if (!locked && bot.now - lastFail.getOrDefault(b, -100000L) < 200) { if (note.isEmpty()) note = "no spot for " + name(b); continue; } // no spot found recently
            boolean dropSiteEmergency = b == plan.stockpile && (bot.economy.dropSites(in).isEmpty() || (bot.now - bot.economy.deliveryTrouble < 2400 && r.priority >= 110));
            // the reserve is replant wood (1 per crop); zombie villagers replant for free (GatherResourcesGoal.getReplantWoodCost):
            // a monster start (150 wood) never afforded its first catalyst (125 + reserve) and sat at 8/10 population
            int woodReserve = dropSiteEmergency || !plan.villagers ? 0 : p.i("buildWoodReserve");
            Ledger L = bot.ledger;
            boolean affordable = L.on()
                    ? in.food - L.fundFood >= b.cost.food && in.wood - L.fundWood >= b.cost.wood + woodReserve && in.ore - L.fundOre >= b.cost.ore
                    : in.canAfford(b.cost.food, b.cost.wood + woodReserve, b.cost.ore);
            // supply and emergencies may still take the fund (a housed-out town centre stops everything)
            if (!affordable && L.on() && r.priority >= 105) affordable = in.canAfford(b.cost.food, b.cost.wood + woodReserve, b.cost.ore);
            if (!affordable) {
                if (note.isEmpty()) note = "saving for " + name(b) + " (" + b.cost.food + "/" + b.cost.wood + "/" + b.cost.ore + ")"
                        + (r.priority >= 60 && bot.params.get("spendingEscrow") > 0 ? ", everything else waits" : "");
                if (reserved == null && r.priority >= 40) reserved = r;
                // an important building we are saving for must not be starved by cheaper, less
                // important ones that use the same resource (the escrow rule)
                // escrowSplit: only what needs the same resource waits behind it (a food- or ore-only want
                // goes ahead while the top one saves wood)
                if (r.priority >= 60 && bot.params.get("spendingEscrow") > 0 && !p.b("escrowSplit")) break;
                continue;
            }
            if (reserved != null && sharesInput(reserved.building, b)) continue;
            // saving 350 wood for healing potions: only urgent buildings meanwhile
            if (r.priority < 100 && b.cost.wood > 0 && bot.production.savingForPotions(in)) continue;
            if (bot.now - lastPlaced < 10) break;
            if (place(in, r)) {
                if (note.isEmpty()) note = "placed " + name(b);
                lastPlaced = bot.now;
                // spend reservation is now actually spent; let the next think re-rank
                if (bot.economy.stockpileWanted != null && b == plan.stockpile) bot.economy.stockpileWanted = null;
            } else {
                lastFail.put(b, bot.now);
                if (plan.portals && (b == plan.farm || b == plan.heroBuilding)) groundShortAt = bot.now; // no nether ground for it
                bot.placeFails++;
                bot.log("no spot for " + name(b) + " near " + r.anchor.toShortString() + " r" + r.rMin + "-" + r.rMax);
            }
            break;
        }
        if (!note.isEmpty()) lastNote = note;
        // waiting on wood: the top unaffordable want (priority 60+) is short of wood and not of food or ore
        boolean woodWait = false;
        if (reserved != null && reserved.priority >= 60) {
            Building rb = reserved.building;
            int wr = plan.villagers ? p.i("buildWoodReserve") : 0;
            woodWait = in.wood < rb.cost.wood + wr && in.food >= rb.cost.food && in.ore >= rb.cost.ore;
        }
        if (woodWait) { if (woodWaitSince < 0) woodWaitSince = bot.now; } else woodWaitSince = -1;
        keepBuildersOnSites(in);
        if (plan.portals) transformPortals(in);
    }

    // ---- piglins: a house, drop site or barracks is a basic portal transformed by a research on the portal
    private final Map<BlockPos, Building> portalIntent = new HashMap<>();
    private long groundShortAt = -100000, lockedSince = -1;

    /** what a basic portal of ours was placed to become (null: not decided) */
    Building portalIntentOf(BlockPos origin) { return portalIntent.get(origin); }

    static boolean isPortalType(Building b) {
        return b == com.solegendary.reignofnether.building.Buildings.PORTAL_CIVILIAN || b == com.solegendary.reignofnether.building.Buildings.PORTAL_MILITARY
                || b == com.solegendary.reignofnether.building.Buildings.PORTAL_TRANSPORT;
    }

    private static com.solegendary.reignofnether.building.production.ProductionItem portalResearch(Building b) {
        if (b == com.solegendary.reignofnether.building.Buildings.PORTAL_MILITARY) return com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_PORTAL_FOR_MILITARY;
        if (b == com.solegendary.reignofnether.building.Buildings.PORTAL_TRANSPORT) return com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_PORTAL_FOR_TRANSPORT;
        return com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_PORTAL_FOR_CIVILIAN;
    }

    /** every finished basic portal of ours gets the research that turns it into what it was placed for */
    private void transformPortals(Intel in) {
        for (BuildingPlacement b : in.own) {
            if (!(b instanceof com.solegendary.reignofnether.building.buildings.placements.PortalPlacement pp) || !b.isBuilt) continue;
            if (pp.getPortalType() != com.solegendary.reignofnether.building.buildings.placements.PortalPlacement.PortalType.BASIC) { portalIntent.remove(b.originPos); continue; }
            if (!pp.productionQueue.isEmpty()) continue;
            Building want = portalIntent.get(b.originPos);
            // not recorded (a resumed game): a house when the population is close to the cap, else barracks
            if (want == null) want = in.popCap - in.pop <= 6 || in.built(bot.plan.barracks) > 0 ? bot.plan.house : bot.plan.barracks;
            var item = portalResearch(want);
            var c = Production.cost(item);
            if (c == null || !in.canAfford(c.food, c.wood, c.ore)) continue;
            if (pp.startProductionItem(item)) {
                bot.ledger.spent(c.food, c.wood, c.ore);
                in.food -= c.food; in.wood -= c.wood; in.ore -= c.ore;
                bot.log("portal at " + b.originPos.toShortString() + " -> " + name(want));
            }
        }
    }

    private long lastDarkPath = -100000;
    int darkPathCatalysts;

    /**
     * Monsters (the user): the night has to reach where the army goes. Two monster allies whose darkness did not
     * meet could not help each other by day - the soldiers burned on the way - and the army had no dark road
     * to the enemy. Along our surface route to every ally's capitol and to the nearest enemy building, the
     * first point outside any night source gets a sculk catalyst (night 30 blocks, +12 population); one at a
     * time, at most darkPathMax of them, once the economy stands (workers 20, a graveyard).
     */
    /** every finished barracks-type building of ours has something in production */
    private boolean allBarracksBusy(Intel in) {
        int n = 0;
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || b.getBuilding() != bot.plan.barracks) continue;
            n++;
            if (b instanceof com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement pp && pp.productionQueue.isEmpty()) return false;
        }
        return n > 0;
    }

    private long lastWorkDark = -100000;
    private BlockPos workDarkAt;

    /**
     * Monsters (the user): zombie villagers slow down in the sun - where three or more of them gather out of the
     * night (a far wood line, an ore seam), a sculk catalyst goes up right there.
     */
    private void workDark(Intel in, List<Request> reqs) {
        if (bot.now - lastWorkDark < 600 || in.workers.size() < 10) return;
        lastWorkDark = bot.now;
        Building cat = com.solegendary.reignofnether.building.Buildings.SCULK_CATALYST;
        if (in.total(cat) - in.built(cat) > 1) return;
        List<Mob> out = new ArrayList<>();
        for (Mob w : in.workers) {
            Economy.Job j = bot.economy.job(w);
            if ((j == Economy.Job.WOOD || j == Economy.Job.ORE) && !com.solegendary.reignofnether.time.NightUtils.isInRangeOfNightSource(w.position(), false)) out.add(w);
        }
        for (Mob w : out) {
            int n = 0;
            double x = 0, z = 0;
            for (Mob o : out) if (o.distanceToSqr(w) < 16 * 16) { n++; x += o.getX(); z += o.getZ(); }
            if (n < 3) continue;
            BlockPos at = new BlockPos((int) (x / n), w.getBlockY(), (int) (z / n));
            reqs.add(new Request(cat, 97, at, 4, 14));
            workDarkAt = at;
            if (bot.verbose) bot.log("work dark: " + n + " workers out of the night at " + at.toShortString() + " - a catalyst there");
            return;
        }
    }

    private void darkPath(Intel in, List<Request> reqs, int workers, BlockPos home) {
        if (workers < 20 || in.built(bot.plan.barracks) == 0 || bot.now - lastDarkPath < 300) return;
        Building cat = com.solegendary.reignofnether.building.Buildings.SCULK_CATALYST;
        if (darkPathCatalysts >= bot.params.i("darkPathMax") || bot.now < darkPathWait) return;
        // the last one still going up: its night / nether is not there yet
        if (darkPathLast != null) {
            for (BuildingPlacement o : in.own) if (o.originPos.equals(darkPathLast) && !o.isBuilt) return;
        }
        lastDarkPath = bot.now;
        List<BlockPos> goals = new ArrayList<>();
        for (BuildingPlacement b : BuildingServerEvents.getBuildings())
            if (b.isCapitol && b.ownerName != null && in.allies.contains(b.ownerName) && !b.ownerName.equals(bot.name)) goals.add(b.centrePos);
        BlockPos enemy = null;
        for (Intel.EnemyMemory em : in.enemies.values()) {
            BlockPos eb = em.alive ? em.anyBuilding() : null;
            if (eb != null && (enemy == null || eb.distSqr(home) < enemy.distSqr(home))) enemy = eb;
        }
        if (enemy != null) goals.add(enemy);
        // from a worker near home, not from the capitol's centre (inside the building: no route starts there -
        // the dark path never found a single spot, the user saw none)
        Mob start = null;
        for (Mob w : in.workers) if (start == null || w.distanceToSqr(Vec3.atCenterOf(home)) < start.distanceToSqr(Vec3.atCenterOf(home))) start = w;
        if (start == null) return;
        for (BlockPos g : goals) {
            List<BlockPos> r = Terrain.route(bot.level, start.blockPosition(), g, bot.now);
            if (r == null || r.size() < 2) { if (bot.verbose) bot.log("dark path: no route to " + g.toShortString()); continue; }
            for (BlockPos q : r) {
                if (q.distSqr(g) < 30 * 30) break; // the far end is theirs
                // piglins: the nether ground (their buildings stand on it) instead of the night - a portal spreads it
                if (bot.plan.portals ? com.solegendary.reignofnether.nether.NetherBlocks.isNetherBlock(bot.level, q.below())
                        : com.solegendary.reignofnether.time.NightUtils.isInRangeOfNightSource(net.minecraft.world.phys.Vec3.atCenterOf(q), false)) continue;
                reqs.add(new Request(bot.plan.portals ? bot.plan.house : cat, 101, q, 0, 12));
                darkPathPending = q;
                if (bot.verbose) bot.log("dark path: catalyst wanted at " + q.toShortString() + " toward " + g.toShortString() + " (" + darkPathCatalysts + " laid)");
                return;
            }
        }
    }
    private BlockPos darkPathPending, darkPathLast;
    private long darkPathWait;

    private long stallSince = -1;
    private boolean stallLogged;

    /** what the builder did with the money this think, for the verbose spending log */
    private String note = "", lastNote = "";
    private long lastSpendLog = -1000;

    private long lastStockpileCancel = -100000;
    /** stockpiles given up (stalled, monsters): the same spot is not tried again for three minutes
     *  (one game: ten stockpiles placed, four cancelled, much of it at the same few spots) */
    private final Map<BlockPos, Long> cancelledStockpiles = new HashMap<>();
    private boolean recentlyCancelled(BlockPos p) {
        cancelledStockpiles.values().removeIf(t -> bot.now - t > 3600);
        for (BlockPos c : cancelledStockpiles.keySet()) if (c.distSqr(p) < 10 * 10) return true;
        return false;
    }

    private static boolean sharesInput(Building a, Building b) {
        return (a.cost.wood > 0 && b.cost.wood > 0) || (a.cost.ore > 0 && b.cost.ore > 0) || (a.cost.food > 0 && b.cost.food > 0);
    }

    /**
     * Our own monster rules (M7ron: the laboratory came at minute 21 in 24 of 42 games, no catalyst ever).
     * huskRush: zombies that do not burn are an army that may leave the night by day - the laboratory early
     * (the husks themselves are paid from the bank, Production). catalystHouse: a sculk catalyst holds 12
     * population for 125 wood, a haunted house 10 for 100 - the same price per head, and the catalyst brings
     * its own night (30 blocks): the first ones replace houses, placed toward the enemy at the mausoleum's
     * night edge, so the two nights overlap little and the army's way out stays dark.
     */
    private void monsterRules(Intel in, List<Request> reqs, int workers, BlockPos home) {
        FactionPlan plan = bot.plan;
        BotParams p = bot.params;
        Building lab = com.solegendary.reignofnether.building.Buildings.LABORATORY;
        Building cat = com.solegendary.reignofnether.building.Buildings.SCULK_CATALYST;
        // monsterLean: the user's monster game (2026-11-15, won with husks against two expert villagers) built
        // catalysts, pumpkin fields, graveyards, the altar and one laboratory - nothing else - and spent it on
        // zombies, then husks from minute 15. Our monster AI had a spider lair, dungeon, slime pit, stronghold,
        // watchtower and market up by minute 25, and 4 fields at minute 5 against his 10. Theory: the monsters'
        // strength is the cheap mass (zombie 75 food, 1 pop); every other building is a luxury until the bank floats.
        if (p.b("monsterLean") && in.food + in.wood + in.ore < 2500) {
            java.util.Set<Building> keep = java.util.Set.of(plan.capitol, plan.house, plan.farm, plan.stockpile, plan.barracks, plan.heroBuilding, lab);
            reqs.removeIf(r -> !keep.contains(r.building()));
            // fields ahead: one per two workers until 14 (his 10 by minute 5)
            int fields = in.total(plan.farm);
            if (fields < Math.min(14, workers / 2) && in.built(plan.capitol) > 0 && in.total(plan.farm) - in.built(plan.farm) < 2)
                reqs.add(new Request(plan.farm, 96, home, 8, 30));
        }
        if (p.b("huskRush") && in.total(lab) == 0 && in.built(plan.barracks) >= 1 && workers >= p.i("huskWorkers")
                && !com.solegendary.reignofnether.research.ResearchServerEvents.playerHasResearch(bot.name,
                        com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HUSKS))
            reqs.add(new Request(lab, 90, home, 10, 26));
        if (p.b("catalystHouse") && in.total(cat) < p.i("catalystMax")) {
            for (int i = 0; i < reqs.size(); i++) {
                Request r = reqs.get(i);
                if (r.building() != plan.house) continue;
                int k = in.total(cat);
                BlockPos f = bot.army.frontAnchor(in);   // 8 blocks from home toward the enemy
                double dx = f.getX() - home.getX(), dz = f.getZ() - home.getZ();
                double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
                // the k-th catalyst: along the front, the second one turned 50 degrees to a side
                double ang = k == 0 ? 0 : (k % 2 == 1 ? 0.87 : -0.87);
                double cx = dx / len, cz = dz / len;
                double rx = cx * Math.cos(ang) - cz * Math.sin(ang), rz = cx * Math.sin(ang) + cz * Math.cos(ang);
                double dist = p.get("catalystDist");
                BlockPos at = new BlockPos((int) Math.round(home.getX() + rx * dist), home.getY(), (int) Math.round(home.getZ() + rz * dist));
                reqs.set(i, new Request(cat, r.priority(), at, 0, 12));
                break;
            }
        }
    }

    /**
     * Only what a human player can build: RON registers a few buildings without a build-menu key (Factions.
     * registerBuildings(faction, building) - the haunted house, the beacon), so no player can place them. The AI
     * placed haunted houses all game (the user found it): never again, whatever a plan or rule asks for.
     */
    static boolean playerBuildable(Building b) {
        // the same for the piglins: civilian, military, transport and pocket portals have no key either - a
        // player gets them only by transforming a basic portal with a research on it (transformPortals)
        return b != com.solegendary.reignofnether.building.Buildings.HAUNTED_HOUSE
                && b != com.solegendary.reignofnether.building.Buildings.BEACON
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_POCKET
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_CIVILIAN
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_MILITARY
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_TRANSPORT;
    }

    /** a piglin building that must stand on nether blocks (all but the central, basic and pocket portals) */
    private boolean needsNether(Building b) {
        return bot.plan.portals && b.getFaction() == com.solegendary.reignofnether.faction.Factions.PIGLINS
                && !(b instanceof com.solegendary.reignofnether.building.buildings.piglins.CentralPortal)
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC
                && b != com.solegendary.reignofnether.building.Buildings.PORTAL_POCKET;
    }

    /** buildings that widen our ground: a sculk catalyst (its night, +12 pop) and every piglin portal (its nether) */
    private boolean territory(Building b) {
        return b == com.solegendary.reignofnether.building.Buildings.SCULK_CATALYST
                || (bot.plan.portals && b == com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC);
    }

    /**
     * expandOut (the user: catalysts and portals go further out - they widen the ground): the k-th territory
     * building stands expandDist from home, the first toward the enemy, each next one turned by the golden
     * angle so they fan out round the base instead of piling on one spot (their night / nether barely overlaps).
     */
    private BlockPos expandSpot(Intel in, Building b) {
        int k = 0;
        for (BuildingPlacement o : in.own) if (territory(o.getBuilding()) || (bot.plan.portals && o.getBuilding() instanceof com.solegendary.reignofnether.building.buildings.piglins.AbstractPortal)) k++;
        BlockPos home = in.home(), f = bot.army.frontAnchor(in);
        double base = Math.atan2(f.getZ() - home.getZ(), f.getX() - home.getX());
        double d0 = bot.params.get("expandDist");
        for (int t = 0; t < 9; t++) {
            // 24 blocks out first, then closer and further (small islands, hills)
            double dist = t < 4 ? d0 : t < 7 ? d0 * 0.65 : d0 * 1.35;
            double ang = base + (k + t) * 2.39996;
            BlockPos at = new BlockPos((int) Math.round(home.getX() + Math.cos(ang) * dist), home.getY(), (int) Math.round(home.getZ() + Math.sin(ang) * dist));
            BlockPos spot = findSpot(b, at, 0, 8, 1, 6);
            if (spot != null) return spot;
        }
        return null;
    }

    private boolean place(Intel in, Request r) {
        Building b = r.building;
        Building intent = null;
        if (bot.plan.portals && isPortalType(b)) { intent = b; b = com.solegendary.reignofnether.building.Buildings.PORTAL_BASIC; }
        if (!playerBuildable(b)) return false;
        BlockPos origin = null;
        BotParams p = bot.params;
        boolean ring = p.b("farmRing");
        // (not the locked first house: it goes up next to home - one sent 24 blocks out met a monster camp, its builder
        // died and the piglins sat at 10/10 population for eight minutes)
        if (p.b("expandOut") && territory(b) && r.priority < 111 && !r.anchor.equals(darkPathPending) && !r.anchor.equals(workDarkAt)) origin = expandSpot(in, b);
        if (origin != null) { /* spread out: placed */ }
        else if (ring && b == bot.plan.farm) {
            // fields hug the drop sites, the town centre first, then the stockpiles nearest home: every
            // harvest walks a few blocks instead of 10-30 (a friend's report; a player rings the town centre)
            List<BlockPos> sites = new ArrayList<>(bot.economy.dropSites(in));
            BlockPos home = in.home();
            sites.sort(java.util.Comparator.comparingDouble(s -> s.distSqr(home)));
            for (BlockPos s : sites) {
                origin = findSpot(b, s, 4, p.i("farmRingMax"), 1, 6);
                if (origin != null) break;
            }
        } else if (ring && b != bot.plan.stockpile && b != bot.plan.capitol && r.anchor.equals(in.home())) {
            // the ring next to the town centre is kept for fields: other buildings go further out
            int rMin = Math.max(r.rMin, p.i("farmRingKeep"));
            origin = findSpot(in, b, r.anchor, rMin, Math.max(r.rMax, rMin + 16));
        }
        // piglins: a building that needs nether ground is looked for around every portal of ours (the nether
        // spreads from them), nearest to the wanted anchor first
        if (origin == null && needsNether(b)) {
            List<BuildingPlacement> portals = new ArrayList<>();
            for (BuildingPlacement o : in.own)
                if (o.isBuilt && o.getBuilding() instanceof com.solegendary.reignofnether.building.buildings.piglins.AbstractPortal) portals.add(o);
            BlockPos an = r.anchor;
            portals.sort(java.util.Comparator.comparingDouble(o -> o.centrePos.distSqr(an)));
            for (BuildingPlacement o : portals) {
                origin = findSpot(b, o.centrePos, 0, o.isCapitol ? 32 : 22, 1, 8);
                if (origin != null) break;
            }
        }
        if (origin == null) origin = findSpot(in, b, r.anchor, r.rMin, r.rMax);
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
        if (intent != null) portalIntent.put(placed.originPos, intent);
        if (darkPathPending != null && r.anchor.equals(darkPathPending)) { darkPathCatalysts++; darkPathPending = null; darkPathLast = placed.originPos;
            darkPathWait = bot.now + (bot.plan.portals ? 1800 : 600); // a portal's nether takes a while to spread
            bot.log("dark path: " + name(b) + " laid at " + placed.originPos.toShortString() + " (" + darkPathCatalysts + ")"); }
        bot.log("build " + name(b) + (intent != null ? " (to be " + name(intent) + ")" : "") + " at " + origin.toShortString() + " with " + builders.size());
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
            if (bot.difficulty == Difficulty.EXPERT && !b.isCapitol && in.dangerous(b.centrePos, 14, false)) {
                siteProgress.remove(b.originPos);
                badSpots.add(b.originPos);
                bot.log("cancel " + name(b.getBuilding()) + " at " + b.originPos.toShortString() + ": monsters there");
                if (b.getBuilding() == bot.plan.stockpile) { lastStockpileCancel = bot.now; cancelledStockpiles.put(b.originPos, bot.now); }
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
            if (b.getBuilding() == bot.plan.stockpile) { lastStockpileCancel = bot.now; cancelledStockpiles.put(b.originPos, bot.now); }
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
        repairDamaged(in);
    }

    /**
     * Damaged buildings were never repaired (a player saw the AI's base worn down over a long game).
     * Below 85% health and no enemy within 20 blocks: one or two villagers (three for a town centre)
     * mend it, two buildings at a time, town centres first.
     */
    private void repairDamaged(Intel in) {
        if (bot.now - lastRepairCheck < 100) return;
        lastRepairCheck = bot.now;
        List<BuildingPlacement> hurt = new ArrayList<>();
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || b.isDestroyedServerside) continue;
            int max = b.getMaxHealth();
            if (max <= 0 || b.getHealth() >= max * 0.85) continue;
            boolean enemyNear = false;
            for (net.minecraft.world.entity.LivingEntity e : in.visibleEnemies)
                if (!UnitEval.isWorker(e) && e.blockPosition().distSqr(b.centrePos) < 20 * 20) { enemyNear = true; break; }
            if (!enemyNear) hurt.add(b);
        }
        hurt.sort((x, y) -> Boolean.compare(y.isCapitol, x.isCapitol) != 0 ? Boolean.compare(y.isCapitol, x.isCapitol)
                : Double.compare(x.getHealth() / (double) x.getMaxHealth(), y.getHealth() / (double) y.getMaxHealth()));
        int n = 0;
        for (BuildingPlacement b : hurt) {
            if (n++ >= 2) break;
            int on = 0;
            for (Mob w : in.workers) {
                var g = ((WorkerUnit) w).getBuildRepairGoal();
                if (g != null && g.getBuildingTarget() == b) on++;
            }
            int want = b.isCapitol ? 3 : b.getHealth() < b.getMaxHealth() * 0.5 ? 2 : 1;
            if (on >= want) continue;
            List<Mob> ws = bot.economy.borrow(in, b.centrePos, want - on);
            if (!ws.isEmpty()) {
                Orders.buildRepair(bot.name, ws, b.centrePos);
                if (bot.now - lastRepairLog > 600) { lastRepairLog = bot.now; bot.log("repair " + name(b.getBuilding()) + " at " + b.centrePos.toShortString() + " (" + b.getHealth() + "/" + b.getMaxHealth() + ") with " + ws.size()); }
            }
        }
    }
    private long lastRepairCheck, lastRepairLog = -10000;

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
        // real maps are hilly: a little more slope near home first, and only then further out - a hilltop
        // start sent the first house 40 blocks off to the foot of a cliff the builders never reached
        if (p == null) p = findSpot(b, anchor, rMin, rMax, 2, 8);
        if (p == null) p = findSpot(b, anchor, rMax, (int) (rMax * 1.8) + 8, 2, 12);
        return p;
    }

    private BlockPos findSpot(Building b, BlockPos anchor, int rMin, int rMax, int flatTol, int yTol) {
        ArrayList<BuildingBlock> rel = b.getRelativeBlockData(bot.level);
        Vec3i size = BuildingUtils.getBuildingSize(rel);
        int sx = size.getX(), sz = size.getZ();
        int gap = 2;
        boolean dropSite = b.canAcceptResources;
        // farmRing: fields pack tight - one block between fields, two to a drop site (a player's ring of
        // fields round the town centre); crops do not block a walk, so the walkways stay open
        boolean tightFarm = b == bot.plan.farm && bot.params.b("farmRing");
        boolean netherOnly = needsNether(b);
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
                if (tightFarm ? !clearForField(ox, oz, sx, sz) : !clearOfBuildings(ox, oz, sx, sz, gap, dropSite)) continue;
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
                // piglin buildings stand on nether ground (80% of the blocks under them): only spots whose centre
                // already is nether go to RON's check - on a concrete city map the 500 checks were spent on
                // concrete before the patches of nether were reached (the user: "the nether is right there")
                if (netherOnly && !com.solegendary.reignofnether.nether.NetherBlocks.isNetherBlock(bot.level, new BlockPos(cx, y - 1, cz))) continue;
                // never on top of resources: logs, ore, berries under or inside the footprint are buried for good
                // (the user saw buildings put on wood piles and ore in all three factions)
                if (b != bot.plan.capitol && bot.params.b("avoidResources") && coversResources(ox, oz, sx, sz, y)) continue;
                for (int dy : flatTol > 1 ? new int[] { 0, -1, 1 } : new int[] { 0, -1 }) {
                    BlockPos origin = new BlockPos(ox, y + dy, oz);
                    if (++checks > 500) return null;
                    if (BuildingValidators.isPlacementValid(bot.level, b, origin, bot.name, Rotation.NONE, false, false, true)) {
                        // the workers must be able to walk there (the user: buildings put where no worker gets to -
                        // up a ledge, across water, behind a wall): our own surface route from home
                        if (b != bot.plan.capitol && !walkableFromHome(new BlockPos(cx, y, cz))) { unreachableSkips++; break; }
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

    private boolean clearForField(int ox, int oz, int sx, int sz) {
        for (BuildingPlacement p : BuildingServerEvents.getBuildings()) {
            int g = p.getBuilding() == bot.plan.farm ? 1 : p.getBuilding().canAcceptResources ? 2 : 2;
            int x0 = ox - g, z0 = oz - g, x1 = ox + sx - 1 + g, z1 = oz + sz - 1 + g;
            if (p.maxCorner.getX() < x0 || p.minCorner.getX() > x1) continue;
            if (p.maxCorner.getZ() < z0 || p.minCorner.getZ() > z1) continue;
            return false;
        }
        return true;
    }

    /**
     * More than two resource blocks under or inside this footprint (sampled every other block, 2 below to 5 above):
     * anything RON gathers from (logs, ore, mushrooms, bee nests, hay, berries...) - not plain stone, leaves and
     * the like, which are everywhere (RON counts stone as an ore source: every rocky spot would be ruled out).
     */
    int unreachableSkips;

    /**
     * A surface route from our nearest worker reaches this spot (within a few blocks). From a worker, not from
     * the town centre: its centre is inside the building, where no route starts - every spot came out
     * unreachable and nothing was built.
     */
    private boolean walkableFromHome(BlockPos at) {
        Mob from = null;
        for (Mob w : bot.intel.workers) if (from == null || w.distanceToSqr(at.getX(), at.getY(), at.getZ()) < from.distanceToSqr(at.getX(), at.getY(), at.getZ())) from = w;
        if (from == null || from.blockPosition().distSqr(at) < 10 * 10) return true;
        List<BlockPos> r = Terrain.route(bot.level, from.blockPosition(), at, bot.now);
        return Terrain.reaches(r, at);
    }

    private static final java.util.Set<String> CHEAP_SOURCES = java.util.Set.of("Stone", "Dripstone", "Leaves", "Unknown Leaves",
            "Farmland", "Soul Sand", "Stick", "Saplings");

    private boolean coversResources(int ox, int oz, int sx, int sz, int y) {
        int n = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int x = ox - 1; x <= ox + sx; x += 2)
            for (int z = oz - 1; z <= oz + sz; z += 2)
                for (int yy = y - 2; yy <= y + 5; yy++) {
                    q.set(x, yy, z);
                    var bs = bot.level.getBlockState(q);
                    if (bs.isAir()) continue;
                    // every block RON gathers from (logs, ore, mushrooms, bee nests, hay, berries, crops...) except the
                    // ones everywhere: plain stone, dripstone, leaves, farmland, soul sand, sticks and saplings
                    var src = com.solegendary.reignofnether.resources.ResourceSources.getFromBlockState(bs);
                    boolean value = src != null && !CHEAP_SOURCES.contains(src.name);
                    if (!value) value = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(bs.getBlock()).getPath().endsWith("_ore");
                    if (value && ++n > 2) return true;
                }
        return false;
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
