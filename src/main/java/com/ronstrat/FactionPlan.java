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
    /** the villagers' own rules (blacksmith armour, library enchants, witches, golems) apply only to them */
    public boolean villagers;
    public Building capitol, house, farm, stockpile, barracks, tower, market;
    /** heroes: the building that makes them and the heroes / revives, best first */
    public Building heroBuilding;
    public List<ProductionItem> heroes = List.of();
    /** monsters: buildings that keep the night around them (sun-burning units are safe there) */
    public List<Building> nightSources = List.of();
    public ProductionItem marketUpgrade;
    public ProductionItem worker, scout;
    public final List<UnitOption> units = new ArrayList<>();
    public final List<TechStep> tech = new ArrayList<>();
    public final List<ResearchStep> research = new ArrayList<>();

    private static FactionPlan villagersPlan, monstersPlan;

    public static FactionPlan forFaction(Faction f) {
        if (f == Factions.MONSTERS) return monsters();
        return villagers();
    }

    /**
     * The monsters: what builds what and what unlocks what, read from RON (buildings/monsters/*.java, the
     * build buttons' hasFinishedBuilding checks). The worker counts and levels are only starting points for
     * our own tests (tools/solo.py), not a build order copied from anyone.
     * Costs (measured, -Dronstrat.dumpCosts): zombie 75f pop 1, skeleton 50f 45w pop 1, spider 80f 20w 20o pop 2,
     * creeper 40f 120o pop 2, warden 275f 125o pop 5; pumpkin farm 200w, (haunted house: not in the build menu), sculk
     * catalyst 125w +12 pop and night 30-50 blocks, graveyard 150w, laboratory 250w 150o.
     */
    public static synchronized FactionPlan monsters() {
        if (monstersPlan != null) return monstersPlan;
        FactionPlan p = new FactionPlan();
        p.faction = Factions.MONSTERS;
        p.villagers = false;
        p.capitol = Buildings.MAUSOLEUM;
        // the haunted house is not in the player's build menu (RON registers it without a key): the
        // sculk catalyst is the monsters' house - 12 population, and its own night
        p.house = Buildings.SCULK_CATALYST;
        p.farm = Buildings.PUMPKIN_FARM;
        p.stockpile = Buildings.SPRUCE_STOCKPILE;
        p.barracks = Buildings.GRAVEYARD;
        p.tower = Buildings.DARK_WATCHTOWER;
        p.market = Buildings.MONSTER_MARKET;
        p.marketUpgrade = ProductionItems.RESEARCH_MARKET_UPGRADE_MONSTERS;
        p.worker = ProductionItems.ZOMBIE_VILLAGER;
        p.scout = ProductionItems.BAT;
        p.heroBuilding = Buildings.ALTAR_OF_DARKNESS;
        p.heroes = List.of(ProductionItems.NECROMANCER, ProductionItems.WRETCHED_WRAITH, ProductionItems.NECROMANCER_REVIVE, ProductionItems.WRETCHED_WRAITH_REVIVE);
        p.nightSources = List.of(Buildings.MAUSOLEUM, Buildings.SCULK_CATALYST, Buildings.STRONGHOLD);

        p.units.add(new UnitOption(ProductionItems.ZOMBIE, Role.MELEE, Buildings.GRAVEYARD, 0));
        p.units.add(new UnitOption(ProductionItems.SKELETON, Role.RANGED, Buildings.GRAVEYARD, 0));
        p.units.add(new UnitOption(ProductionItems.SPIDER, Role.TANK, Buildings.SPIDER_LAIR, 14));
        p.units.add(new UnitOption(ProductionItems.CREEPER, Role.SIEGE, Buildings.DUNGEON, 20));
        p.units.add(new UnitOption(ProductionItems.WRAITH, Role.CASTER, Buildings.DUNGEON, 20));
        p.units.add(new UnitOption(ProductionItems.SLIME, Role.SUPPORT, Buildings.SLIME_PIT, 24));
        p.units.add(new UnitOption(ProductionItems.WARDEN, Role.TANK, Buildings.STRONGHOLD, 30));

        p.tech.add(new TechStep(Buildings.SPIDER_LAIR, 16, 1, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.LABORATORY, 20, 2, Buildings.GRAVEYARD, Buildings.MAUSOLEUM));
        p.tech.add(new TechStep(Buildings.DUNGEON, 22, 3, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.SLIME_PIT, 28, 4, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.STRONGHOLD, 34, 5, Buildings.DUNGEON, Buildings.LABORATORY, Buildings.SLIME_PIT, Buildings.SPIDER_LAIR));

        p.research.add(new ResearchStep(ProductionItems.RESEARCH_RESOURCE_CAPACITY, Buildings.SPRUCE_STOCKPILE, 1));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_HUSKS, Buildings.LABORATORY, 2));      // zombies become husks: no sun burn
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_MARKET_UPGRADE_MONSTERS, Buildings.MONSTER_MARKET, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_POISON_SPIDERS, Buildings.LABORATORY, 3));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_STRAYS, Buildings.LABORATORY, 3));     // skeletons become strays
        monstersPlan = p;
        return p;
    }

    public static synchronized FactionPlan villagers() {
        if (villagersPlan != null) return villagersPlan;
        FactionPlan p = new FactionPlan();
        p.faction = Factions.VILLAGERS;
        p.villagers = true;
        p.heroBuilding = Buildings.SHRINE_OF_PROSPERITY;
        p.heroes = List.of(ProductionItems.ROYAL_GUARD, ProductionItems.ENCHANTER, ProductionItems.ROYAL_GUARD_REVIVE, ProductionItems.ENCHANTER_REVIVE);
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
        villagersPlan = p;
        return p;
    }

    public UnitOption option(ProductionItem item) {
        for (UnitOption o : units) if (o.item == item) return o;
        return null;
    }
}
