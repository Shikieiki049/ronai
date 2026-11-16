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
    /** this faction's own bridge (oak / spruce / blackstone) */
    public Building bridge = Buildings.OAK_BRIDGE;
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

    /** piglins: houses, drop sites and barracks are basic portals transformed by a research on the portal */
    public boolean portals;

    private static FactionPlan villagersPlan, monstersPlan, piglinsPlan;

    public static FactionPlan forFaction(Faction f) {
        if (f == Factions.MONSTERS) return monsters();
        if (f == Factions.PIGLINS) return piglins();
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
        p.bridge = Buildings.SPRUCE_BRIDGE;
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
        p.units.add(new UnitOption(ProductionItems.CREEPER, Role.SIEGE, Buildings.DUNGEON, 18));
        p.units.add(new UnitOption(ProductionItems.WRAITH, Role.CASTER, Buildings.DUNGEON, 18));
        p.units.add(new UnitOption(ProductionItems.SLIME, Role.SUPPORT, Buildings.SLIME_PIT, 20));
        p.units.add(new UnitOption(ProductionItems.WARDEN, Role.TANK, Buildings.STRONGHOLD, 30));

        // the dungeon first (wraiths: half of all damage dodged; creepers), then the laboratory (husks) and the
        // slime pit; the spider lair last
        p.tech.add(new TechStep(Buildings.DUNGEON, 18, 1, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.LABORATORY, 20, 2, Buildings.GRAVEYARD, Buildings.MAUSOLEUM));
        p.tech.add(new TechStep(Buildings.SLIME_PIT, 22, 3, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.SPIDER_LAIR, 28, 4, Buildings.GRAVEYARD));
        p.tech.add(new TechStep(Buildings.STRONGHOLD, 34, 5, Buildings.DUNGEON, Buildings.LABORATORY, Buildings.SLIME_PIT, Buildings.SPIDER_LAIR));

        p.research.add(new ResearchStep(ProductionItems.RESEARCH_RESOURCE_CAPACITY, Buildings.SPRUCE_STOCKPILE, 1));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_HUSKS, Buildings.LABORATORY, 2));      // zombies become husks: no sun burn
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_MARKET_UPGRADE_MONSTERS, Buildings.MONSTER_MARKET, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_POISON_SPIDERS, Buildings.LABORATORY, 3));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_STRAYS, Buildings.LABORATORY, 3));     // skeletons become strays
        monstersPlan = p;
        return p;
    }

    /**
     * The piglins, read from RON 1.5.1c/d (buildings/piglins/*.java, the build and production buttons'
     * hasFinishedBuilding checks, ReignOfNetherCommonConfigs for the costs):
     * central portal 350w 250o +10 pop (capitol, drop site, makes grunts); basic portal 75w, no nether ground
     * needed, spreads the nether 3 -> 20 blocks; transformed on the portal itself by a research into a civilian
     * portal (+75w: +15 pop, drop site), a military portal (+100w: the army) or a transport portal (+175w).
     * Every other piglin building needs 80% nether blocks under it (the central portal's nether grows 6 -> 30
     * blocks in about a minute). Netherwart farm 150w; bastion 175w 150o (after a portal); hoglin stables
     * 150w 50o; flame sanctuary / wither shrine / basalt springs 150w 200o; fortress 400w 300o; infernal
     * portal 125w 50o (heroes). Units: grunt 50f; brute 120f pop 2; headhunter 90f 60w pop 2; hoglin 140f 70o
     * pop 3 (stables); blaze 40f 40w 100o pop 2 (sanctuary); wither skeleton 200f 125o pop 4 (shrine);
     * marauder 175f 75w 75o pop 4 (bastion); magma cube 40/40/40 pop 2 (springs); ghast 100f 100w 250o pop 6
     * (fortress). Our build order and numbers are our own (tests), not RON's bot.
     */
    public static synchronized FactionPlan piglins() {
        if (piglinsPlan != null) return piglinsPlan;
        FactionPlan p = new FactionPlan();
        p.faction = Factions.PIGLINS;
        p.villagers = false;
        p.portals = true;
        p.capitol = Buildings.CENTRAL_PORTAL;
        p.bridge = Buildings.BLACKSTONE_BRIDGE;
        p.house = Buildings.PORTAL_CIVILIAN;
        p.farm = Buildings.NETHERWART_FARM;
        p.stockpile = Buildings.PORTAL_CIVILIAN;
        p.barracks = Buildings.PORTAL_MILITARY;
        p.market = Buildings.PIGLIN_MARKET;
        p.marketUpgrade = ProductionItems.RESEARCH_MARKET_UPGRADE_PIGLINS;
        p.worker = ProductionItems.GRUNT;
        p.scout = null; // the baby hoglin: not in RON 1.5.0b2 (BabyHoglinProd), and the computer sees the whole map
        p.heroBuilding = Buildings.INFERNAL_PORTAL;
        p.heroes = List.of(ProductionItems.WILDFIRE, ProductionItems.PIGLIN_MERCHANT, ProductionItems.WILDFIRE_REVIVE, ProductionItems.PIGLIN_MERCHANT_REVIVE);

        // roles from the arena (APIG1, equal cost): brutes beat every ground army but ghasts; ghasts beat every
        // melee army; magma cubes next; headhunters only fair (but they shoot the ghasts); blazes, hoglins,
        // wither skeletons and marauders lose for their price - kept as options at zero share (piglinMix)
        p.units.add(new UnitOption(ProductionItems.BRUTE, Role.MELEE, Buildings.PORTAL_MILITARY, 0));
        p.units.add(new UnitOption(ProductionItems.HEADHUNTER, Role.RANGED, Buildings.PORTAL_MILITARY, 0));
        p.units.add(new UnitOption(ProductionItems.MAGMA_CUBE, Role.SUPPORT, Buildings.PORTAL_MILITARY, 18, Buildings.BASALT_SPRINGS));
        p.units.add(new UnitOption(ProductionItems.GHAST, Role.SIEGE, Buildings.PORTAL_MILITARY, 30, Buildings.FORTRESS));
        p.units.add(new UnitOption(ProductionItems.HOGLIN, Role.TANK, Buildings.PORTAL_MILITARY, 24, Buildings.HOGLIN_STABLES));
        p.units.add(new UnitOption(ProductionItems.BLAZE, Role.CASTER, Buildings.PORTAL_MILITARY, 28, Buildings.FLAME_SANCTUARY));
        p.units.add(new UnitOption(ProductionItems.WITHER_SKELETON, Role.TANK, Buildings.PORTAL_MILITARY, 28, Buildings.WITHER_SHRINE));

        // tech toward the magma cubes (basalt springs, after a bastion) and then the fortress (ghasts) - the
        // fortress needs the sanctuary (after the stables) and the shrine too
        p.tech.add(new TechStep(Buildings.BASTION, 18, 1, Buildings.PORTAL_BASIC));
        p.tech.add(new TechStep(Buildings.BASALT_SPRINGS, 20, 2, Buildings.BASTION));
        p.tech.add(new TechStep(Buildings.HOGLIN_STABLES, 28, 3, Buildings.PORTAL_BASIC));
        p.tech.add(new TechStep(Buildings.WITHER_SHRINE, 28, 3, Buildings.BASTION));
        p.tech.add(new TechStep(Buildings.FLAME_SANCTUARY, 30, 4, Buildings.HOGLIN_STABLES));
        p.tech.add(new TechStep(Buildings.FORTRESS, 32, 5, Buildings.BASALT_SPRINGS, Buildings.FLAME_SANCTUARY, Buildings.WITHER_SHRINE));

        p.research.add(new ResearchStep(ProductionItems.RESEARCH_RESOURCE_CAPACITY, Buildings.PORTAL_CIVILIAN, 1));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_BRUTE_SHIELDS, Buildings.BASTION, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_HOGLIN_CAVALRY, Buildings.HOGLIN_STABLES, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_MARKET_UPGRADE_PIGLINS, Buildings.PIGLIN_MARKET, 2));
        p.research.add(new ResearchStep(ProductionItems.RESEARCH_BLAZE_FIREWALL, Buildings.FLAME_SANCTUARY, 3));
        piglinsPlan = p;
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
