package com.ronstrat;

import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayerScoresEnum;

/**
 * The army's own budget (parameter ledger). A share of everything gathered goes into an army fund that
 * buildings and villagers cannot spend; soldiers spend it first, whatever building waits for wood.
 *
 * Why: the builder held back the cost of its top unaffordable building, so while one field waited for
 * wood every barracks stood idle - the expert's first soldier came at 7-8 minutes and it trailed RON's
 * brutal bot by 4-6 soldiers from minute 8 to 12 in most games (RON's styles: rush, 6 pop at ~3 min;
 * standard 10 pop at ~6; boom 14 pop at ~8; tech 10 pop at ~7, with 14-26 villagers).
 *
 * The share: nothing before minute ledgerFrom; ledgerShare1 to minute 10, ledgerShare2 after; the
 * rush defence or an enemy army ahead raise it to ledgerShareThreat.
 */
final class Ledger {
    private final Bot bot;
    double fundFood, fundWood, fundOre;
    private int lastFood = -1, lastWood = -1, lastOre = -1;

    Ledger(Bot bot) { this.bot = bot; }

    boolean on() { return bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && (bot.params.b("ledger") || bot.params.b("styleResponse")); }

    double share(Intel in) {
        BotParams p = bot.params;
        double m = BuildOrder.minutes();
        if (bot.army.rushDefence(in) || bot.army.armyAheadLatch && m < 12) return p.get("ledgerShareThreat");
        if (Strategy.respondTo(bot, Strategy.RUSH) && m < 12) return p.get("ledgerShareThreat");
        if (Strategy.respondTo(bot, Strategy.BOOM) && m >= 6) return Math.max(p.get("ledgerShare2"), 0.6);
        if (m < p.get("ledgerFrom")) return 0;
        return m < 10 ? p.get("ledgerShare1") : p.get("ledgerShare2");
    }

    void think(Intel in) {
        if (!on()) { fundFood = fundWood = fundOre = 0; return; }
        var rp = PlayerServerEvents.getRTSPlayer(bot.name);
        if (rp == null) return;
        int f = rp.scores.scoreList.getOrDefault(RTSPlayerScoresEnum.FOOD_HARVESTED, 0);
        int w = rp.scores.scoreList.getOrDefault(RTSPlayerScoresEnum.WOOD_HARVESTED, 0);
        int o = rp.scores.scoreList.getOrDefault(RTSPlayerScoresEnum.ORES_HARVESTED, 0);
        if (lastFood >= 0) {
            double k = share(in);
            fundFood += Math.max(0, f - lastFood) * k;
            fundWood += Math.max(0, w - lastWood) * k;
            fundOre += Math.max(0, o - lastOre) * k;
        }
        lastFood = f; lastWood = w; lastOre = o;
        // the fund is part of the bank, never more than it; and only what soldiers use: vindicators are
        // all food, pillagers want some wood - a fund that hoarded 1000 wood starved the fields
        fundFood = Math.min(fundFood, in.food);
        fundWood = Math.min(Math.min(fundWood, in.wood), bot.params.get("ledgerWoodCap"));
        fundOre = Math.min(Math.min(fundOre, in.ore), bot.params.get("ledgerOreCap"));
    }

    /** a soldier paid: from the fund first */
    void spent(int food, int wood, int ore) {
        fundFood = Math.max(0, fundFood - food);
        fundWood = Math.max(0, fundWood - wood);
        fundOre = Math.max(0, fundOre - ore);
    }
}
