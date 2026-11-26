package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement;
import com.solegendary.reignofnether.building.production.ProductionItems;
import com.solegendary.reignofnether.research.ResearchServerEvents;
import com.solegendary.reignofnether.unit.UnitAction;
import com.solegendary.reignofnether.unit.units.monsters.CreeperUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

/**
 * v18 chargedCreepers (the user's 11-26 public-server loss: two charged creepers blew up his whole skeleton and
 * zombie army). The monsters' laboratory researches the lightning rod (it needs a dungeon); from then on the
 * laboratory calls lightning (60 s cooldown, 25 blocks around it) onto our own creepers waiting at home: a
 * charged creeper blasts 5 blocks instead of 3 and hits 1.7x as hard. The bolt hurts whatever stands under it,
 * so only a spot with no other own unit within 3 blocks is struck.
 */
final class Lightning {
    private final Bot bot;
    int strikes, rodsStarted;
    private long lastTry = -1000;

    Lightning(Bot bot) { this.bot = bot; }

    boolean on() {
        return bot.difficulty == Difficulty.EXPERT && bot.params.b("chargedCreepers")
                && bot.plan.faction == com.solegendary.reignofnether.faction.Factions.MONSTERS;
    }

    boolean rod() {
        return ResearchServerEvents.playerHasResearch(bot.name, ProductionItems.RESEARCH_LAB_LIGHTNING_ROD);
    }

    /** the laboratory stands and the rod is still to be researched: its ore is saved for (Economy, Production) */
    boolean wantsRod(Intel in) {
        return on() && !rod() && in.built(Buildings.LABORATORY) > 0;
    }

    /** Builder: a laboratory once the dungeon stands and creepers are part of the army */
    boolean wantLab(Intel in) {
        return on() && in.total(Buildings.LABORATORY) == 0 && in.built(Buildings.DUNGEON) > 0 && creepers(in) >= 2;
    }

    int creepers(Intel in) {
        int n = 0;
        for (Mob m : in.army) if (m instanceof CreeperUnit) n++;
        return n;
    }

    void tick(Intel in) {
        if (!on() || bot.now - lastTry < 20) return;
        lastTry = bot.now;
        for (BuildingPlacement b : in.own) {
            if (b.getBuilding() != Buildings.LABORATORY || !b.isBuilt) continue;
            // the research: once, while nothing else is being made there
            if (!rod()) {
                if (b instanceof ProductionPlacement pp && pp.productionQueue.isEmpty() && b.getUpgradeLevel() == 0) {
                    var item = ProductionItems.RESEARCH_LAB_LIGHTNING_ROD;
                    var c = Production.cost(item);
                    if (c != null && in.canAfford(c.food, c.wood, c.ore) && pp.startProductionItem(item)) {
                        bot.ledger.spent(c.food, c.wood, c.ore);
                        in.food -= c.food; in.wood -= c.wood; in.ore -= c.ore;
                        rodsStarted++;
                        bot.log("laboratory: lightning rod research");
                    }
                }
                continue;
            }
            if (b.getUpgradeLevel() == 0 || !b.isAbilityOffCooldown(UnitAction.CALL_LIGHTNING)) continue;
            BlockPos lab = b.centrePos;
            Mob best = null;
            int bestN = 0;
            for (Mob m : in.army) {
                if (!(m instanceof CreeperUnit c) || c.isPowered() || !m.isAlive()) continue;
                if (m.blockPosition().distSqr(lab) > 23 * 23) continue;
                int others = 0, creepersHere = 0;
                for (Mob o : in.army) {
                    if (o == m || o.distanceToSqr(m) > 3.2 * 3.2) continue;
                    if (o instanceof CreeperUnit oc && !oc.isPowered()) creepersHere++; else others++;
                }
                if (others > 0) continue;
                if (best == null || creepersHere + 1 > bestN) { best = m; bestN = creepersHere + 1; }
            }
            if (best == null) {
                // no creeper by the laboratory: one idle at home walks there to be charged (not during an attack)
                if (bot.army.mode == Army.Mode.ATTACK) continue;
                for (Mob m : in.army) {
                    if (!(m instanceof CreeperUnit c) || c.isPowered() || !((com.solegendary.reignofnether.unit.interfaces.Unit) m).isIdle()) continue;
                    if (m.blockPosition().distSqr(lab) <= 23 * 23) continue;
                    BlockPos to = lab.offset(Integer.signum(m.getBlockX() - lab.getX()) * 7, 0, Integer.signum(m.getBlockZ() - lab.getZ()) * 7);
                    Orders.move(bot.name, java.util.List.of(m), to);
                    break;
                }
                continue;
            }
            strikes++;
            bot.log("lightning onto " + bestN + " creeper(s) at " + best.blockPosition().toShortString());
            // the building from selectedBuildingPos, the clicked block from preselectedBlockPos (UnitActionItem)
            com.solegendary.reignofnether.unit.UnitServerEvents.addActionItem(bot.name, UnitAction.CALL_LIGHTNING, -1, new int[0], best.blockPosition(), b.originPos);
        }
    }
}
