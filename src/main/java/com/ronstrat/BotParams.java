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
        p.put("boomWorkers",                99,    99,     20,    24);   // after this many workers the army gets food first
        p.put("armyPerWorker",              0,     0.5,    0.6,   0.8);  // ...until army pop reaches this many per worker
        p.put("hunters",                    0,     2,      3,     4);
        p.put("useStockpiles",              0,     1,      1,     1);
        p.put("dropDistance",               30,    24,     20,    16);   // build a stockpile when gathering further than this
        p.put("rebalanceSlack",             4,     3,      2,     1);    // how far worker split may drift before reassigning
        p.put("farmWorkersShare",           0.45,  0.45,   0.42,  0.40); // food share of workers in mid game
        p.put("econV2",                     0,     0,      0,     1);    // the rewritten economy: opening to the user's curve (BuildOrder), workers by need
        p.put("openingMinutes",             0,     0,      0,     10);
        p.put("openingOreFloor",            0,     0,      0,     100);  // opening: one miner only while ore is under this
        p.put("econPhase2",                 0,     0,      0,     0);    // after the opening: workers by need (fields, miners for armour, rest wood)
        p.put("econWorkers",                0,     0,      0,     34);   // ...and the villager count the army phase stops at
        p.put("phase2Ore",                  0,     0,      0,     0.15); // ...share of gatherers mining once a blacksmith stands
        p.put("openingLead",                0,     0,      0,     0.5);  // minutes the opening runs ahead of the user's curve
        p.put("foodCapToFarms",             0,     0,      0,     0);    // even opening: food workers capped at fields + hunters + 1, rest on wood
        p.put("foodCapMinutes",             0,     0,      0,     12);
        p.put("openFood",                   0.5,   0.5,    0.5,   0.5);  // food share of the first 8 villagers (the rest on wood)
        p.put("openFood2",                  0.45,  0.45,   0.45,  0.45); // ...of villagers 8-13
        p.put("oreFromMinute",              5,     5,      5,     5);    // even game: no early miners before this minute (golem / upgrade ore after it)
        p.put("earlyOreFloor",              9999,  9999,   9999,  9999); // ...while ore is at least this (9999 = rule off)
        p.put("oreShare",                   0.15,  0.15,   0.15,  0.15);
        p.put("houseBuffer",                2,     4,      6,     9);    // free supply kept ahead of population
        p.put("maxParallelHouses",          1,     1,      2,     3);
        p.put("buildersPerBuilding",        1,     2,      2,     2);
        p.put("extraCapitols",              0,     0,      0,     2);
        p.put("spendingEscrow",             0,     0.5,    0.5,   0.5);    // save for the top building priority instead of spending on units
        // --- production / tech ---
        p.put("barracksPerWorkers",         99,    16,     14,    10);   // one extra barracks per this many workers
        p.put("maxBarracks",                1,     2,      2,     4);
        p.put("maxTechBuildings",           0,     0,      1,     5);
        p.put("researchLevel",              0,     1,      2,     3);    // 0 none, 1 cheap, 2 most, 3 all useful
        p.put("counterComposition",         0,     0.3,    0.7,   1.0);  // how strongly unit choice reacts to enemy army
        p.put("resourceFitWeight",          0,     0.2,    0.4,   0.7);  // prefer units made of the resource that is piling up
        p.put("golemShare",                 0,     0.1,    0.2,   0.4);
        p.put("casterShare",                0,     0,      0.1,   0.15);
        p.put("buildTowers",                0,     0,      1,     1);
        p.put("towers",                     0,     0,      0,     3);    // planned garrisoned watchtowers
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
