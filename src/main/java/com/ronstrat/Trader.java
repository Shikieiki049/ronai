package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.unit.UnitAction;

/**
 * The market, used the way strong AoE2 scripts use it: when one resource piles up and another
 * starves production, sell the surplus and buy what is missing. Same building, same rates and
 * same upgrade requirement as a human player has.
 */
public final class Trader {
    private final Bot bot;
    private long lastTrade = -1000;
    public int trades;

    Trader(Bot bot) { this.bot = bot; }

    /** wood is what keeps farms replanting: running dry is an emergency */
    private static boolean poorWood(int[] stock) { return stock[1] < 50; }

    void think(Intel in) {
        if (!bot.params.b("useMarket")) return;
        BuildingPlacement market = null;
        for (BuildingPlacement b : in.own) if (b.isBuilt && b.getBuilding() == bot.plan.market) { market = b; break; }
        if (market == null) return;
        if (bot.now - lastTrade < 15) return;

        int[] stock = { in.food, in.wood, in.ore };
        int poor = -1, rich = -1;
        // late game one resource overflows into the thousands while another (usually ore) sits
        // just above empty: trade well before it runs dry, the surplus is worth nothing piled up
        int max = Math.max(stock[0], Math.max(stock[1], stock[2]));
        int poorBar = max > 2000 ? 500 : 150;
        for (int i = 0; i < 3; i++) {
            if (stock[i] < poorBar && (poor < 0 || stock[i] < stock[poor])) poor = i;
            int richBar = (poorWood(stock)) ? 300 : 700;
            if (stock[i] > richBar && (rich < 0 || stock[i] > stock[rich])) rich = i;
        }
        if (poor < 0 || rich < 0 || poor == rich) return;
        boolean canBuy = market.getUpgradeLevel() > 0;
        UnitAction act = null;
        if (canBuy && in.emerald >= 100) {
            act = poor == 0 ? UnitAction.BUY_FOOD : poor == 1 ? UnitAction.BUY_WOOD : UnitAction.BUY_ORE;
        } else if (in.emerald < 300) {
            act = rich == 0 ? UnitAction.SELL_FOOD : rich == 1 ? UnitAction.SELL_WOOD : UnitAction.SELL_ORE;
        }
        if (act == null) return;
        Orders.buildingAbility(bot.name, act, market.centrePos);
        lastTrade = bot.now;
        trades++;
    }
}
