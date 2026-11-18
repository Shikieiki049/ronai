package com.ronstrat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The "strategic numbers" of a bot (the AoE2 term). Every behaviour knob lives here so that the
 * self-play tuner can search over them without touching code. Values come from built-in defaults
 * per difficulty, then config/ronstrat/params-&lt;difficulty&gt;.json, then a -Dronstrat.params.&lt;difficulty&gt;
 * file (used by the tuner).
 */
public final class BotParams {
    private final Map<String, Double> v = new LinkedHashMap<>();
    public final Difficulty difficulty;

    private static final Map<String, BotParams> CACHE = new LinkedHashMap<>();

    private BotParams(Difficulty d) { this.difficulty = d; }

    public double get(String k) {
        Double d = v.get(k);
        if (d == null) throw new IllegalStateException("missing param " + k);
        return d;
    }
    public int i(String k) { return (int) Math.round(get(k)); }
    public boolean b(String k) { return get(k) >= 0.5; }

    public Map<String, Double> asMap() { return v; }

    public static BotParams of(Difficulty d) { return of(d, ""); }

    /**
     * profile: a named set of overrides on top of the difficulty's values (a sparring style such as
     * "rush7", or the switch of a new subsystem). Read from &lt;profile&gt;.json in the folder given by
     * -Dronstrat.profiles, else config/ronstrat/profiles.
     */
    public static synchronized BotParams of(Difficulty d, String profile) {
        String pf = profile == null ? "" : profile;
        return CACHE.computeIfAbsent(d.key() + ":" + pf, k -> load(d, pf));
    }

    public static synchronized void reload() { CACHE.clear(); }

    /** a private copy with some values replaced (per-map parameters): the shared instance stays as it is */
    public BotParams with(Map<String, Double> overrides) {
        BotParams c = new BotParams(difficulty);
        c.v.putAll(v);
        for (Map.Entry<String, Double> e : overrides.entrySet()) if (c.v.containsKey(e.getKey())) c.v.put(e.getKey(), e.getValue());
        return c;
    }

    private static BotParams load(Difficulty d, String profile) {
        BotParams p = defaults(d);
        // the built-in values are written out for reference; a params-<tier>.json next to them overrides
        Path dir = FMLPaths.CONFIGDIR.get().resolve("ronstrat");
        Path cfg = dir.resolve("params-" + d.key() + ".json");
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve("params-" + d.key() + ".defaults.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(p.v, w);
            }
            if (Files.exists(cfg)) p.merge(cfg);
        } catch (Exception e) {
            RonStrategist.LOG.warn("RON Strategist: could not read {}: {}", cfg, e.toString());
        }
        String override = System.getProperty("ronstrat.params." + d.key());
        if (override != null) {
            try { p.merge(Path.of(override)); }
            catch (Exception e) { RonStrategist.LOG.warn("RON Strategist: bad override {}: {}", override, e.toString()); }
        }
        if (!profile.isEmpty()) {
            String pd = System.getProperty("ronstrat.profiles");
            Path file = (pd != null ? Path.of(pd) : dir.resolve("profiles")).resolve(profile + ".json");
            // a misspelt profile must not quietly play as the plain difficulty: the test would measure nothing
            if (!Files.exists(file)) throw new IllegalStateException("no parameter profile " + file);
            try { p.merge(file); }
            catch (Exception e) { throw new IllegalStateException("bad parameter profile " + file + ": " + e); }
            RonStrategist.LOG.info("RON Strategist: {} profile '{}' loaded from {}", d.key(), profile, file);
        }
        return p;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private void merge(Path file) throws Exception {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            if (o == null) return;
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (v.containsKey(e.getKey()) && e.getValue().isJsonPrimitive()) {
                    v.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
        }
    }

    private void put(String k, double easy, double normal, double hard, double expert) {
        double x;
        switch (difficulty) {
            case EASY: x = easy; break;
            case NORMAL: x = normal; break;
            case HARD: x = hard; break;
            default: x = expert;
        }
        v.put(k, x);
    }

    static BotParams defaults(Difficulty d) {
        BotParams p = new BotParams(d);
        //                                  easy   normal  hard   expert
        // --- reaction speed ("APM") ---
        p.put("thinkTicks",                 60,    40,     25,    5);    // macro decision period
        p.put("microTicks",                 0,     0,      20,    2);    // 0 = no micro at all
        p.put("attentionChance",            0.5,   0.7,    0.85,  1.0);  // chance a manager acts on a given think
        // --- economy ---
        p.put("workerTarget",               9,     13,     18,    44);
        p.put("workerQueue",                1,     1,      1,     2);    // villagers queued at once per capitol
        p.put("armyFromWorkers",            0,     0,      0,     20);   // expert: no army before this many villagers (unless raided)
        p.put("boomWorkers",                99,    99,     20,    29);   // after this many workers the army gets food first
        p.put("armyPerWorker",              0,     0.5,    0.6,   0.8);  // ...until army pop reaches this many per worker
        p.put("hunters",                    0,     2,      3,     4);
        p.put("useStockpiles",              0,     1,      1,     1);
        p.put("dropDistance",               30,    24,     20,    16);   // build a stockpile when gathering further than this
        p.put("woodDrop",                   0,     0,      0,     1);   // a stockpile where the woodcutters cut once their walk is long
        p.put("witchEarly",                 0,     0,      0,     0);   // witch hut after the first barracks, witches witchShare of the army
        p.put("witchFrom",                  7,     7,      7,     7);   // ...not before this minute
        p.put("witchMin",                   2,     2,      2,     2);   // witches paid from the bank until this many
        p.put("witchShare",                 0.18,  0.18,   0.18,  0.18);
        p.put("witchMicro",                 0,     0,      0,     1);   // witches autocast harming potions, heal the worst-hurt soldier
        p.put("heroSkills",                 0,     0,      0,     1);   // heroes learn their skills and use them in fights
        p.put("floatBarracks",              0,     0,      0,     0);   // food 600+ with every barracks busy: one more (up to maxBarracks + 2)
        p.put("foodBarracks",               0,     0,      0,     1);   // food banked with every barracks busy: the next barracks ahead of fields and stockpiles
        p.put("foodBarracksFood",           300,   300,    300,   300);
        p.put("boomBarracks",               0,     0,      0,     1);   // villagers near the cap: barracks ahead of further fields
        p.put("boomBarracksSlack",          8,     8,      8,    14);   // ...from vilCap minus this many villagers
        p.put("boomBarracksFarms",          6,     6,      6,     6);   // ...once this many fields stand
        p.put("woodDemand",                 0,     0,      0,     0);   // the builder waiting on wood moves workers to wood, one per 30 s
        p.put("woodDemandWait",             600,   600,    600,   600); // ...after this many ticks of waiting
        p.put("woodDemandMax",              6,     6,      6,     6);
        p.put("woodDemandFood",             100,   100,    100,   100); // ...food workers only while this much food is banked
        p.put("escrowSplit",                0,     0,      0,     0);   // a saved-for building blocks only wants that share one of its resources
        p.put("enchantPolicy",              0,     0,      0,     0);   // no maiming; multishot for pillagers once the grand library stands
        p.put("upgradeTier",                0,     0,      0,     0);   // grand library + superior blacksmith paid from the bank once the army is formed
        p.put("upgradeTierArmy",            12,    12,     12,    12);
        p.put("simV2",                      0,     0,      0,     0);   // the fight forecast calibrated on the arena (flyers, per-type damage)
        p.put("retarget",                   0,     0,      0,     0);   // woodcutters/miners with no next block picked get a new one
        p.put("retargetTicks",              40,    40,     40,    40);
        p.put("chainCut",                   0,     0,      0,     0);   // a woodcutter that lost its block: the next one next to it at once
        p.put("chainCutTicks",              5,     5,      5,     5);
        p.put("farmChain",                  0,     0,      0,     0);   // a farmer with no block picked: the next ripe crop of its field at once
        p.put("fillFields",                 0,     0,      0,     0);   // every built field gets its farmer at once (a hunter first, then a woodcutter)
        p.put("guardWorkers",               0,     0,      0,     0);   // enemy fighters on our workers out at the trees: a raid, the army goes
        p.put("monsterLean",                0,     0,      0,     0);   // monsters: only catalysts, fields, graveyards, altar, laboratory until the bank floats
        p.put("fightIdle",                  0,     0,      0,     0);   // a soldier standing without a target near enemies: the nearest one at once
        p.put("nightPlan",                  0,     0,      0,     1);   // monsters: attack by night, home before dawn, burning units inside the night by day
        p.put("huskRush",                   0,     0,      0,     1);   // monsters: the laboratory early, husks from the bank
        p.put("monsterOpenWood",            0,     0,      0,     0);   // monsters: this share of workers on wood until monsterOpenFields fields stand
        p.put("monsterOpenFields",          4,     4,      4,     4);
        p.put("creeperMicro",               0,     0,      1,     1);   // creepers walk at the thickest knot of enemies and blow up when creeperBlast are in reach
        p.put("creeperBlast",               3,     3,      3,     3);
        p.put("bigArmyGo",                  0,     0,      0,     1);   // 20+ soldiers: a weak raid gets a detachment, the rest still attacks; monsters attack first by night
        p.put("expandOut",                  0,     0,      1,     1);   // catalysts and piglin portals fan out expandDist from home: more ground (the user)
        p.put("expandDist",                 24,    24,     24,    24);
        p.put("piglinMix",                  1,     1,      1,     1);   // piglins: brutes + magma cubes + headhunters (+ ghasts), from the arena
        p.put("pigMagma",                   0.2,   0.2,    0.2,   0.2);
        p.put("pigHead",                    0.18,  0.18,   0.18,  0.18);
        p.put("pigGhast",                   0.3,   0.3,    0.3,   0.3);
        p.put("monsterCounter",             1,     1,      1,     1);   // monsters: wraith core + zombies/skeletons, countered to the enemy's army (the user's rules)
        p.put("monsterOre",                 0.2,   0.2,    0.2,   0.2);   // monsters: miners share once the dungeon stands (wraiths, creepers cost ore)
        p.put("darkPath",                   0,     0,      1,     1);   // monsters: catalysts along the way to allies and the enemy (the user)
        p.put("darkPathMax",                8,     8,      8,     8);
        p.put("monsterMix",                 0,     0,      0,     0);   // monsters: zombies tank, skeletons damage, creepers strike (shares below)
        p.put("mixZombie",               0.45,  0.45,   0.45,  0.45);  // share of army population: zombies / husks in front
        p.put("mixSkeleton",             0.35,  0.35,   0.35,  0.35);  // skeletons / strays behind them
        p.put("mixCreeper",              0.20,  0.20,   0.20,  0.20);  // creepers (dungeon): the strongest blow
        p.put("huskWorkers",                18,    18,     18,    18);
        p.put("catalystHouse",              0,     0,      0,     0);   // monsters: the first houses are sculk catalysts toward the enemy
        p.put("catalystMax",                2,     2,      2,     2);
        p.put("catalystDist",               28,    28,     28,    28);
        p.put("nightSunSafeShare",          0.25,  0.25,   0.25,  0.25); // a force with at most this share burning ignores the clock
        p.put("nightFightTicks",            1800,  1800,   1800,  1800); // night kept for the fight itself
        p.put("nightDawnMargin",            400,   400,    400,   400);  // start home this much before the walk back would meet the dawn
        p.put("siegeSpread",                0,     0,      0,     1);   // enemy army gone: up to 8 groups of 2+ raze at once
        p.put("adaptWitches",               0,     0,      0,     0);   // from minute 12: an enemy army 65%+ melee brings the witches in
        p.put("boomPunish",                 0,     0,      0,     1);   // no enemy army at all from minute 10: attack with boomPunishPop
        p.put("boomPunishPop",              15,    15,     15,    15);
        p.put("ledger",                     0,     0,      0,     0);   // army fund: a share of everything gathered only soldiers may spend
        p.put("ledgerFrom",                 5,     5,      5,     5);   // ...from this minute
        p.put("ledgerShare1",               0.35,  0.35,   0.35,  0.35); // ...this share to minute 10
        p.put("ledgerShare2",               0.55,  0.55,   0.55,  0.55); // ...this share after
        p.put("ledgerWoodCap",              200,   200,    200,   200);
        p.put("ledgerOreCap",               100,   100,    100,   100);
        p.put("ledgerShareThreat",          0.9,   0.9,    0.9,   0.9); // ...rush or enemy army ahead
        p.put("styleResponse",              0,     0,      0,     0);   // read the enemy style (rush/standard/boom/tech) and answer it
        p.put("villagerShelter",            0,     0,      0,     0);   // enemy soldiers at the town centre: nearby villagers garrison it
        p.put("lateAllIn",                  0,     0,      0,     0);   // from lateAllInMinute: attack with an army at least 90% of theirs
        p.put("lateAllInMinute",            24,    24,     24,    24);
        p.put("campRush",                   0,     0,      0,     1);   // monsters camped at home killing villagers: barracks and 3 soldiers first
        p.put("huntFar",                    0,     0,      0,     0);   // game-rich maps: hunt out to huntFarBox with up to huntFarHunters
        p.put("huntFarBox",                 110,   110,    110,   110);
        p.put("huntFarHunters",             4,     4,      4,     4);
        p.put("mapParams",                  0,     0,      0,     0);   // apply tuning/map_params.json for the map being played
        p.put("leanDryTicks",               3000,  3000,   3000,  3000);
        p.put("leanOpening",                0,     0,      0,     0);   // no hunters without a catch, no far forage, no early houses or shrine while starved
        p.put("fieldsScale",                1,     1,      1,     1.101);   // the opening's field curve times this
        p.put("villagersFirst",             0,     0,      0,     1);   // villagers before soldiers until the cap unless the enemy is ahead by more than villagersFirstGap soldiers
        p.put("villagersFirstGap",          2,     2,      2,     4);
        p.put("threatPriorScale",           1,     1,      1,     1);   // how much of the by-the-clock enemy army guess counts (1 = all, 0 = only what is seen)
        p.put("farmNudge",                  0,     0,      0,     0);   // a farmer standing with ripe crops left is sent to the nearest one
        p.put("farmRoles",                  0,     0,      0,     0);   // fields only for villagers that can still become farmers
        p.put("leafFirst",                  0,     0,      0,     0);   // woodcutters go for leaves (60 wood a minute) before logs (37.5)
        p.put("leafRange",                  24,    24,     24,    24);
        p.put("harass",                     0,     0,      0,     0);   // minutes 8-14: 2-3 soldiers raid the enemy woodcutters
        p.put("strategy",                   0,     0,      0,     -1);   // 0 farms (A), 1 golems (B), 2 witches and tech (C), -1 chosen by Strategy
        p.put("golemOre",                   0.45,  0.45,   0.45,  0.45); // golem route: share of the gatherers on ore
        p.put("golemSites",                 2,     2,      2,     2);   // golem route: golems being built at once
        p.put("golemOreBlocks",             99999, 99999,  99999, 99999);  // auto route: this much ore near home...
        p.put("golemWoodBelow",             150,   150,    150,   150); // ...and little game or wood: golems
        p.put("golemUntil",                 14,    14,     14,    14);  // golem route: minute it hands over to vindicators
        p.put("fixedHunters",               0,     0,      0,     0);   // the same villagers hunt all game (at most "hunters")
        p.put("stockpileCooldown",          0,     0,      0,     1);   // no new stockpile within 10 blocks of one cancelled in the last 3 min
        p.put("heroCreep",                  0,     0,      0,     1);   // an idle hero (and one soldier) hunts weak monster camps for experience
        p.put("shrineMid",                  0,     0,      0,     0);   // the shrine at priority 92 from minute shrineMinute
        p.put("shrineMinute",               9,     9,      9,     9);
        p.put("shrineEarlyMinute",          0,     0,      0,     3);   // early shrine not before this minute...
        p.put("shrineEarlyFields",          0,     0,      0,     2);   // ...nor before this many fields
        p.put("shrineEarly",                0,     0,      0,     1);   // the hero shrine right after the first house
        p.put("huntReach",                  999,   999,    999,   999); // a villager hunts only game this close to it
        p.put("safePath",                   0,     0,      0,     1);   // no resource whose walk passes monsters or a death spot
        p.put("fleeRaiders",                0,     0,      0,     1);   // villagers run from enemy soldiers too, and a raided work spot is avoided
        p.put("clearForWood",               0,     0,      0,     1);   // woods behind monsters: soldiers clear them (the opening ends for it)
        p.put("rallyGuard",                 0,     0,      0,     1);   // an idle army waits by the villagers out in the field, not at home
        p.put("vilCap",                     99,    99,     99,    39);  // even expert: villagers stop here
        p.put("rushMinute",                 0,     0,      0,     7);   // before this minute...
        p.put("rushSoldiers",               99,    99,     99,    2);   // ...this many enemy soldiers (more than ours) end the opening
        p.put("openingBreakMin",            3,     3,      3,     3);   // ...and the enemy has at least this many soldiers
        p.put("openingBreak",               0,     0,      0,     1.5);   // end the opening once a seen enemy army is this many soldiers ahead (0 = never)
        p.put("smithFrom",                  99,    99,     99,    11);   // even expert: blacksmith (and miners for it) from this minute
        p.put("smithMiners",                3,     3,      3,     3);
        p.put("woodDropFrom",               8,     8,      8,     8);   // ...from this minute on
        p.put("woodDropSlack",              6,     6,      6,     8);   // ...longer than dropDistance plus this
        p.put("rebalanceSlack",             4,     3,      2,     1);    // how far worker split may drift before reassigning
        p.put("farmWorkersShare",           0.45,  0.45,   0.42,  0.40); // food share of workers in mid game
        p.put("econV2",                     0,     0,      0,     1);    // the rewritten economy: opening to the user's curve (BuildOrder), workers by need
        p.put("openingMinutes",             0,     0,      0,     11);
        p.put("openingOreFloor",            0,     0,      0,     100);  // opening: one miner only while ore is under this
        p.put("econPhase2",                 0,     0,      0,     0);    // after the opening: workers by need (fields, miners for armour, rest wood)
        p.put("econWorkers",                0,     0,      0,     34);   // ...and the villager count the army phase stops at
        p.put("phase2Ore",                  0,     0,      0,     0.15); // ...share of gatherers mining once a blacksmith stands
        p.put("composition",                0,     0,      0,     1);    // army shares from the arena's unit matrix (Composition)
        p.put("compTemp",                   0.15,  0.15,   0.15,  0.15); // softmax temperature: lower = more of the single best unit
        p.put("compTech",                   0.10,  0.10,   0.10,  0.10); // score discount of a unit whose building we lack
        p.put("compMinShare",               0.08,  0.08,   0.08,  0.08); // shares below this are dropped
        p.put("compTechGap",                0.15,  0.15,   0.15,  0.15); // build a tech building when its unit beats ours by this
        p.put("spendBalance",               0,     0,      0,     0);    // workers follow the spending: food piling while wood is dry -> to wood
        p.put("spendBalanceHigh",           400,   400,    400,   400);
        p.put("spendBalanceLow",            150,   150,    150,   150);
        p.put("spendBalanceMax",            6,     6,      6,     6);
        p.put("buildWoodReserve",           40,    40,     40,    59);   // wood the builder leaves for replanting (Production.WOOD_RESERVE)
        p.put("stallFarm",                  0,     0,      0,     1);    // no field, no food, wood in the bank for 30 s: a field before anything else
        p.put("stockpileEager",             0,     0,      0,     1);    // stockpiles at the woods early and urgently (a friend: the AI builds too few)
        p.put("stockpileEagerDist",         12,    12,     12,    12);   // ...when a woodcutter's spot is further than this from every drop site
        p.put("stockpileEagerWorkers",      3,     3,      3,     3);    // ...urgent once this many villagers cut wood
        p.put("stockpileEagerFarms",        0,     0,      0,     10);    // ...only once this many fields stand (the opening wood goes to fields first)
        p.put("farmRing",                   0,     0,      0,     1);    // fields right around the town centre and stockpiles; other buildings further out
        p.put("farmRingMax",                16,    16,     16,    16);   // ...search radius for a field around a drop site
        p.put("farmRingKeep",               18,    18,     18,    18);   // ...houses, barracks etc. no nearer to the town centre than this
        p.put("woodDesperate",              0,     0,      0,     1);    // no wood for 4 min, none banked: woods near monsters and up hills are fine
        p.put("resourceClear",              0,     0,      0,     1);    // army + hero clear camps on the woods/ore further out while nothing threatens
        p.put("resourceClearReach",         75,    75,     75,    75);   // ...this far from home
        p.put("openingLead",                0,     0,      0,     0.5);  // minutes the opening runs ahead of the user's curve
        p.put("foodCapToFarms",             0,     0,      0,     0);    // even opening: food workers capped at fields + hunters + 1, rest on wood
        p.put("foodCapMinutes",             0,     0,      0,     12);
        p.put("openFood",                   0.5,   0.5,    0.5,   0.5);  // food share of the first 8 villagers (the rest on wood)
        p.put("openFood2",                  0.45,  0.45,   0.45,  0.45); // ...of villagers 8-13
        p.put("oreFromMinute",              5,     5,      5,     5);    // even game: no early miners before this minute (golem / upgrade ore after it)
        p.put("earlyOreFloor",              9999,  9999,   9999,  9999); // ...while ore is at least this (9999 = rule off)
        p.put("oreShare",                   0.15,  0.15,   0.15,  0.173);
        p.put("houseBuffer",                2,     4,      6,     9);    // free supply kept ahead of population
        p.put("maxParallelHouses",          1,     1,      2,     4);
        p.put("buildersPerBuilding",        1,     2,      2,     1);
        p.put("extraCapitols",              0,     0,      0,     2);
        p.put("spendingEscrow",             0,     0.5,    0.5,   0.5);    // save for the top building priority instead of spending on units
        // --- production / tech ---
        p.put("barracksPerWorkers",         99,    16,     14,    9);   // one extra barracks per this many workers
        p.put("maxBarracks",                1,     2,      2,     4);
        p.put("maxTechBuildings",           0,     0,      1,     5);
        p.put("researchLevel",              0,     1,      2,     3);    // 0 none, 1 cheap, 2 most, 3 all useful
        p.put("counterComposition",         0,     0.3,    0.7,   1.0);  // how strongly unit choice reacts to enemy army
        p.put("resourceFitWeight",          0,     0.2,    0.4,   0.7);  // prefer units made of the resource that is piling up
        p.put("golemShare",                 0,     0.1,    0.2,   0.4);
        p.put("casterShare",                0,     0,      0.1,   0.15);
        p.put("buildTowers",                0,     0,      0,     0);   // no towers (the user: do not build towers)
        p.put("towers",                     0,     0,      0,     0);    // planned garrisoned watchtowers - off (the user: no towers)
        p.put("bridges",                    0,     0,      0,     1);    // bridge water/lava that blocks the army
        p.put("bridgeMinWater",             6,     6,      6,     6);    // narrower water is swum, not bridged
        p.put("turtleWorkers",              99,    99,     18,    45);   // villager cap while outnumbered (1vN)
        p.put("maxArmyPop",                 15,    21,     30,    999);  // standing army size cap (like AoE2 tiers)
        p.put("rushWhenOutnumbered",        0,     0,      0,     1);    // 1vN: pick enemies off one by one early
        p.put("rushStartSeconds",           9999,  9999,   9999,  360);
        p.put("rushMinPop",                 99,    99,     99,    42);
        p.put("rushHomeGuard",              0,     0,      0,     0.35); // share of army that stays home during 1vN raids
        p.put("useMarket",                  0,     0,      1,     1);    // trade surplus for the starving resource
        p.put("heroes",                     0,     0,      0,     1);    // shrine of prosperity + its free heroes
        p.put("upgrades",                   0,     0,      0,     1);    // library enchants + blacksmith chestplates
        // --- military ---
        p.put("firstAttackSeconds",         1020,  900,    780,   0);    // earliest attack (0 = whenever winning)
        p.put("attackMinArmyPop",           8,     12,     18,    26);
        p.put("attackAdvantage",            0.0,   0.9,    1.15,  1.35); // own/enemy strength needed to commit (0 = ignore)
        p.put("retreatRatio",               0.0,   0.35,   0.55,  0.5);  // retreat below this local strength ratio (0 = never)
        p.put("regroupDistance",            0,     22,     16,    12);   // pull stragglers back before engaging (0 = never)
        p.put("defendRadius",               28,    34,     36,    38);
        p.put("monsterDefence",             0,     0,      1,     1);    // wild and map monsters in the base count as a raid: the army defends against them
        p.put("monsterBell",                0,     0,      1,     1);    // ...and when the army is not enough the town bell calls the villagers up
        p.put("pullWorkersToDefend",        0,     0,      0,     0);    // town bell: OFF - RON 1.5.1b and 1.5.1c duplicate villagers on call-to-arms, the AI must not exploit it
        p.put("focusFire",                  0,     0,      1,     1);
        p.put("spreadFire",                 0,     0,      1,     1);   // focus fire without overkill: enough damage on a target already -> the next one
        p.put("returnFire",                 0,     0,      1,     1);   // hit on the way: strike back at the attacker in reach (the user)
        p.put("noStage",                    0,     0,      1,     1);   // no gathering spot before an attack (the user)
        p.put("unstick",                    0,     0,      1,     1);   // a stuck soldier or hero is led round by our own route (the user)
        p.put("avoidResources",             1,     1,      1,     1);   // no building on top of logs, ore, mushrooms, hay... (the user)
        p.put("unitSkills",                 0,     0,      1,     1);   // soldiers use their own skills: shields, clouds, fear, firewall, webs, mounts (the user)
        p.put("militiaCall",                0,     0,      1,     1);   // villagers attacked run to the town centre and take up arms (the user)
        p.put("kiting",                     0,     0,      0,     1);
        p.put("retreatWounded",             0,     0,      0,     1);
        p.put("targetWeakest",              0,     0,      1,     1);    // in FFA / 1vN pick the most profitable enemy, not the nearest
        p.put("raidWorkers",                0,     0,      0,     1);
        p.put("scout",                      0,     1,      1,     1);
        p.put("attackWaveCooldown",         240,   180,    120,   45);   // seconds between committed attacks
        // --- playing styles (sparring profiles in tuning/profiles; 0 = off) ---
        p.put("styleBarracksAtWorkers",     0,     0,      0,     0);    // first barracks at this many villagers, top priority; a second six later
        p.put("teamV2",                     0,     0,      0,     1);    // allied experts attack as one: shared target, assembly point, going in together
        p.put("teamJoinPop",                0,     0,      0,     10);   // army pop needed to join a team plan already running
        p.put("armyV2",                     0,     0,      0,     0);    // attack and retreat by fight forecast (CombatSim) instead of strength ratios
        p.put("v2Leave",                    0,     0,      0,     1);    // armyV2 also decides when to leave (0 = only retreats by forecast)
        p.put("v2Retreat",                  0,     0,      0,     1);    // armyV2 also decides retreats (0 = old retreat rule)
        p.put("v2MinPop",                   0,     0,      0,     16);   // no forecast attack below this army population
        p.put("v2LeaveLeft",                0,     0,      0,     0.4);  // leave when the forecast win keeps this share of the army
        p.put("v2RetreatLeft",              0,     0,      0,     0.25); // retreat when the forecast leaves less than this
        p.put("v2GrowthPerMinute",          0,     0,      0,     1.2);  // enemy units assumed per barracks per minute since we last saw their army
        p.put("v2UnseenPerMinute",          0,     0,      0,     2.0);  // enemy units assumed per minute after 6:30 when never seen
        p.put("v2EnemyScale",               0,     0,      0,     1.3);  // enemy hit points x this in the forecast (it came out optimistic)
        p.put("v2MilitiaShare",             0,     0,      0,     0.5);  // share of the defender's villagers counted as militia
        p.put("v2DefenceUnits",             0,     0,      0,     2);    // extra defenders assumed at an enemy base (militia, garrison)
        p.put("styleAttackAt",              0,     0,      0,     0);    // from this second on: attack with what there is, no strength check
        p.put("styleAttackMinPop",          8,     8,      8,     8);    // ...once the army is at least this big
        p.put("armyKeepHome",               0,     0.1,    0.15,  0.0);  // fraction of army left as home guard
        return p;
    }
}
