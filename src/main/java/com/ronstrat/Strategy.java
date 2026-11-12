package com.ronstrat;

import com.solegendary.reignofnether.resources.ResourceName;
import net.minecraft.core.BlockPos;

/**
 * The opening route of an even expert game, and the in-game decision between routes (parameter strategy):
 *  A farms   - the user's own build: fields early, barracks from minute 7, vindicators
 *  B golems  - a share of the villagers on ore from the start, the blacksmith early, golem smithing,
 *              then iron golems built by villagers (no food) until the fields carry the army
 *  C witches - the witch hut right after the first barracks, healing potions, witches with the army
 * strategy -1 lets the bot choose once its town centre stands: the route table (route-vs-route
 * self-play, tuning/strategy_table.json) first, else what is near home - plenty of ore and little game
 * or wood favours golems.
 */
final class Strategy {
    private Strategy() {}

    static final int FARMS = 0, GOLEMS = 1, WITCHES = 2;

    static int route(Bot bot) {
        int s = (int) bot.params.get("strategy");
        if (s >= 0) return s;
        if (bot.chosenRoute < 0) {
            // decided once the town centre stands (before that: farms, not yet fixed)
            if (bot.intel == null || bot.intel.capitol == null) return FARMS;
            bot.chosenRoute = choose(bot);
        }
        return bot.chosenRoute;
    }

    private static boolean even(Bot bot) { return bot.difficulty == Difficulty.EXPERT && !bot.outnumbered(); }

    static boolean golems(Bot bot) {
        return even(bot) && route(bot) == GOLEMS && BuildOrder.minutes() < bot.params.get("golemUntil");
    }

    static boolean witches(Bot bot) {
        return bot.difficulty == Difficulty.EXPERT && (bot.params.b("witchEarly") || (even(bot) && route(bot) == WITCHES) || meleeHeavyFoe(bot));
    }

    /**
     * In-game switch (parameter adaptWitches): from minute 12, an enemy army of 8+ soldiers that is mostly
     * melee brings the witches in - lingering harming potions hit packed melee hardest. Once seen, it stays.
     */
    private static boolean meleeHeavyFoe(Bot bot) {
        if (!bot.params.b("adaptWitches") || BuildOrder.minutes() < 12) return false;
        if (bot.adaptedWitches) return true;
        if (bot.now - bot.meleeCheckedAt < 200) return false;
        bot.meleeCheckedAt = bot.now;
        int melee = 0, all = 0;
        for (net.minecraft.world.entity.LivingEntity e : bot.intel.visibleEnemies) {
            if (UnitEval.isWorker(e)) continue;
            all++;
            if (!UnitEval.isRanged(e)) melee++;
        }
        if (all >= 8 && melee >= 0.65 * all) {
            bot.adaptedWitches = true;
            bot.log("enemy army " + melee + "/" + all + " melee: witches join the army");
        }
        return bot.adaptedWitches;
    }

    /** once, when the town centre stands: the route table first, then what lies around home */
    private static int choose(Bot bot) {
        Intel in = bot.intel;
        if (in == null || in.capitol == null) return FARMS;
        String map = mapName();
        Integer t = table().get(norm(map));
        if (t != null) { bot.log("route " + t + " from the route table (" + map + ")"); return t; }
        BlockPos h = in.home();
        int ore = count(bot, h, ResourceName.ORE), wood = count(bot, h, ResourceName.WOOD);
        int game = bot.economy.animalsNearHome;
        int r = ore >= bot.params.get("golemOreBlocks") && (game <= 3 || wood < bot.params.get("golemWoodBelow")) ? GOLEMS : FARMS;
        bot.log("route " + r + ": ore blocks " + ore + ", wood blocks " + wood + ", game " + game + " near home");
        return r;
    }

    /** resource blocks of a kind within 40 blocks of home (the index visits each candidate once) */
    private static int count(Bot bot, BlockPos h, ResourceName type) {
        int[] n = { 0 };
        com.solegendary.reignofnether.resources.ResourceIndex.get(bot.level).findClosest(bot.level, h, 40, type, p -> { n[0]++; return false; });
        return n[0];
    }

    /** the map's name: the test harness passes the folder, a real game has RON's map info ("Infinity Isle") */
    static String mapName() {
        String m = System.getProperty("ronstrat.map", "");
        if (!m.isEmpty()) return m;
        try {
            Class<?> c = Class.forName("com.solegendary.reignofnether.rtsmap.RTSMapInfoServerEvents");
            Object info = c.getField("rtsMapInfo").get(null);
            if (info != null) return String.valueOf(info.getClass().getMethod("getName").invoke(info));
        } catch (Throwable ignored) { }
        return "";
    }

    /** "Infinity Isle", "Infinity_Isle" and "infinity-isle" are the same map */
    static String norm(String s) { return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", ""); }

    private static java.util.Map<String, Integer> table;

    /** tuning/strategy_table.json: { "Mirs_Landing": 0, ... } from route-vs-route self-play */
    private static synchronized java.util.Map<String, Integer> table() {
        if (table != null) return table;
        table = new java.util.HashMap<>();
        try {
            String pd = System.getProperty("ronstrat.profiles");
            java.nio.file.Path f = pd != null ? java.nio.file.Path.of(pd).getParent().resolve("strategy_table.json")
                    : net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("ronstrat/strategy_table.json");
            String text = null;
            if (java.nio.file.Files.exists(f)) text = java.nio.file.Files.readString(f);
            else try (var r = Strategy.class.getResourceAsStream("/ronstrat/strategy_table.json")) { // shipped in the jar
                if (r != null) text = new String(r.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (text != null) {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                for (var e : o.entrySet()) table.put(norm(e.getKey()), e.getValue().getAsInt());
            }
        } catch (Exception ignored) { }
        return table;
    }
}
