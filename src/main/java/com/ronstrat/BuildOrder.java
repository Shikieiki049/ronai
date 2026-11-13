package com.ronstrat;

import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The expert's opening in an even game (parameter econV2): the user's own build, minute by minute
 * (tools/human_curve.py over 13 of the user's games, the median). For the first ten minutes the
 * wood goes to fields, houses, the stockpile and a late first barracks - nothing else - so the fields
 * come early and keep growing food for the rest of the game.
 */
final class BuildOrder {
    private BuildOrder() {}

    /** the user's median count placed by the end of each minute (index = minute, 0..12) */
    static final int[] FIELDS =     { 0, 0, 0, 2, 4, 7, 9, 10, 11, 12, 13, 14, 15 };
    static final int[] BARRACKS =   { 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 3 };
    static final int[] STOCKPILES = { 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2 };

    static double minutes() { return PlayerServerEvents.rtsGameTicks / 1200.0; }

    /** the curve at a time in minutes, linear between the minute marks */
    static double at(int[] curve, double mins) {
        if (mins <= 0) return curve[0];
        if (mins >= curve.length - 1) return curve[curve.length - 1];
        int i = (int) mins;
        return curve[i] + (curve[i + 1] - curve[i]) * (mins - i);
    }

    static boolean active(Bot bot) {
        return bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && bot.params.b("econV2")
                && minutes() < bot.params.get("openingMinutes") && !bot.army.armyAheadLatch && !Strategy.respondTo(bot, Strategy.RUSH)
                && !bot.army.campRush(bot.intel);
    }

    /** after the opening: the army phase (parameter econPhase2) - workers still by need, not by share */
    static boolean phase2(Bot bot) {
        return bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && bot.params.b("econV2") && bot.params.b("econPhase2")
                && minutes() >= bot.params.get("openingMinutes");
    }

    /**
     * Rewrites the builder's wish list for the opening: fields to the curve (a little ahead of it), no
     * barracks before the curve has one, no tech, market, towers or extra town centres. Emergencies
     * (priority 105 and up: no drop site, housed out with a stalled town centre, beasts in the base)
     * are kept. Under attack the normal list stands.
     */
    static void shape(Bot bot, Intel in, List<Builder.Request> reqs, BlockPos home) {
        if (!active(bot) || bot.army.sinceThreat() < 1200) return;
        FactionPlan plan = bot.plan;
        double m = minutes() + bot.params.get("openingLead");
        // fieldsScale: more fields than the user's curve when there are more villagers to work them
        int fieldsWanted = (int) Math.ceil(at(FIELDS, m) * bot.params.get("fieldsScale") - 0.01);
        int barracksWanted = (int) Math.floor(at(BARRACKS, m) + 0.01);
        int stockWanted = (int) Math.floor(at(STOCKPILES, m) + 0.01);
        reqs.removeIf(r -> {
            Building b = r.building();
            if (r.priority() >= 105 || b == plan.capitol || b == plan.house) return false;
            if (b == plan.farm) return true; // re-added below to the curve
            if (b == com.solegendary.reignofnether.building.Buildings.SHRINE_OF_PROSPERITY && (bot.params.b("shrineEarly") || (bot.params.b("shrineMid") && minutes() >= bot.params.get("shrineMinute")))) return false;
            if (b == com.solegendary.reignofnether.building.Buildings.WITCH_HUT && Strategy.witches(bot)) return false;
            if ((b == com.solegendary.reignofnether.building.Buildings.BLACKSMITH || b == com.solegendary.reignofnether.building.Buildings.IRON_GOLEM_BUILDING) && Strategy.golems(bot)) return false;
            if (b == plan.barracks) return in.total(plan.barracks) >= barracksWanted;
            if (b == plan.stockpile) return in.total(plan.stockpile) >= Math.max(1, stockWanted) && r.priority() < (bot.economy.farWoodTrip ? 98 : 100);
            return true; // tech, market, towers, second town centre: after the opening
        });
        if (in.total(plan.farm) < fieldsWanted && in.workers.size() >= 4)
            reqs.add(new Builder.Request(plan.farm, 97, home, 10, 34));
        if (in.total(plan.barracks) < barracksWanted)
            reqs.add(new Builder.Request(plan.barracks, 93, home, 10, 36));
    }
}
