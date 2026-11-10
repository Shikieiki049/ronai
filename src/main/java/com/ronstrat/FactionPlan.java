package com.ronstrat;

import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.building.production.ProductionItem;
import com.solegendary.reignofnether.building.production.ProductionItems;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.faction.Factions;

import java.util.ArrayList;
import java.util.List;

/**
 * Faction knowledge the bot needs: what is the capitol, the house, the farm, which building makes
 * which unit, and the client-side unlock rules (RON only checks those on the client, so the bot
 * enforces them on itself to play by the same rules as a human).
 */
public final class FactionPlan {
    public enum Role { MELEE, RANGED, TANK, CASTER, SUPPORT, SIEGE }

    public static final class UnitOption {
        public final ProductionItem item;
        public final Role role;
        public final Building producer;
        public final List<Building> requires;
        public final int minWorkers;

        UnitOption(ProductionItem item, Role role, Building producer, int minWorkers, Building... requires) {
            this.item = item;
            this.role = role;
            this.producer = producer;
            this.minWorkers = minWorkers;
            this.requires = List.of(requires);
        }
    }

    public static final class TechStep {
        public final Building building;
        public final List<Building> requires;
        public final int minWorkers;
        public final int level; // compared with maxTechBuildings order

        TechStep(Building building, int minWorkers, int level, Building... requires) {
            this.building = building;
            this.minWorkers = minWorkers;
            this.level = level;
            this.requires = List.of(requires);
        }
    }

    public static final class ResearchStep {
        public final ProductionItem item;
        public final Building at;
        public final int level;

        ResearchStep(ProductionItem item, Building at, int level) {
            this.item = item;
            this.at = at;
            this.level = level;
        }
    }

    public Faction faction;
    public Building capitol, house, farm, stockpile, barracks, tower, market;
    public ProductionItem marketUpgrade;
    public ProductionItem worker, scout;
    public final List<UnitOption> units = new ArrayList<>();
    public final List<TechStep> tech = new ArrayList<>();
    public final List<ResearchStep> research = new ArrayList<>();

    private static FactionPlan villagers;

    public static FactionPlan forFaction(Faction f) {
        // Villagers are the faction the AI is built and tuned for; the structure is faction-agnostic.
        return villagers();
    }

    public static synchronized FactionPlan villagers() {
        if (villagers != null) return villagers;
        FactionPlan p = new FactionPlan();
        p.faction = Factions.VILLAGERS;
        p.capitol = Buildings.TOWN_CENTRE;
        p.house = Buildings.VILLAGER_HOUSE;
        p.farm = Buildings.WHEAT_FARM;
        p.stockpile = Buildings.OAK_STOCKPILE;
        p.barracks = Buildings.BARRACKS;
        p.tower = Buildings.WATCHTOWER;
        p.market = Buildings.VILLAGER_MARKET;
        p.marketUpgrade = ProductionItems.RESEARCH_MARKET_UPGRADE_VILLAGER;
        p.worker = ProductionItems.VILLAGER;
        p.scout = ProductionItems.SCOUT_DOG;

        p.units.add(new UnitOption(ProductionItems.VINDICATOR, Role.MELEE, Buildings.BARRACKS, 0));
        p.units.add(new UnitOption(ProductionItems.PILLAGER, Role.RANGED, Buildings.BARRACKS, 0));
        p.units.add(new UnitOption(ProductionItems.IRON_GOLEM, Role.TANK, Buildings.BLACKSMITH, 12));
        p.units.add(new UnitOption(ProductionItems.EVOKER, Role.CASTER, Buildings.ARCANE_TOWER, 24));
        p.units.add(new UnitOption(ProductionItems.WITCH, Role.SUPPORT, Buildings.WITCH_HUT, 28));
        p.units.add(new UnitOption(ProductionItems.RAVAGER, Role.SIEGE, Buildings.CASTLE, 34));

        // unlock rules mirror the build buttons in RON's building classes
        p.tech.add(new TechStep(Buildings.BLACKSMITH, 16, 1, Buildings.BARRACKS));
        p.tech.add(new TechStep(Buildings.ARCANE_TOWER, 22, 2, Buildings.BARRACKS));
        p.tech.add(new TechStep(Buildings.WITCH_HUT, 28, 3, Buildings.BARRACKS));
        p.tech.add(new TechStep(Buildings.LIBRARY, 34, 4, Buildings.BARRACKS));
        p.tech.add(new TechStep(Buildings.CASTLE, 36, 5, Buildings.WITCH_HUT, Buildings.BLACKSMITH, Buildings.ARCANE_TOWER));

        p.research.add(new ResearchStep(ProductionItems.RESEARCH_RESOURCE_CAPACITY, Buildings.OAK_STOCKPILE, 1));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_MARKET_UPGRADE_VILLAGER, Buildings.VILLAGER_MARKET, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_SUPERIOR_BLACKSMITH, Buildings.BLACKSMITH, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_CASTLE_FLAG, Buildings.CASTLE, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_HEALING_POTIONS, Buildings.WITCH_HUT, 3));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_EVOKER_VEXES, Buildings.LIBRARY, 3));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_GRAND_LIBRARY, Buildings.LIBRARY, 3)); // sharpness II, vigor
        villagers = p;
        return p;
    }

    public UnitOption option(ProductionItem item) {
        for (UnitOption o : units) if (o.item == item) return o;
        return null;
    }
}
