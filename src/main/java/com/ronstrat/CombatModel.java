package com.ronstrat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * v13: the combat model fitted on the arena (tools/fit_combat.py, tuning/combat_model.json copied into the jar).
 * A unit is worth exp(lv[type] + counters against the enemy's mix + day + upgrades); a side's value is the sum.
 * Held out it called 91% of fights right (correlation 0.91) where UnitEval's sqrt(dps x hp) - which put the
 * evoker and the witch at nothing (no basic attack) and the vindicator below the brute - and CombatSim did far
 * worse. Parameter combatModel: on, UnitEval.power is this value (scaled to the old numbers, so the thresholds
 * keep their meaning) and the attack and retreat checks add the counters.
 */
public final class CombatModel {
    private CombatModel() {}

    /** set per bot before it thinks (like CombatSim.v2): the decisions of this bot use the model */
    public static boolean on;
    /** UnitEval.power by the model too (off: only the retreat and the attack veto use it) */
    public static boolean modelPower;
    /** model value x SCALE ~ UnitEval's old power (a vindicator: 0.9 x 15.6 = 14) */
    static final double SCALE = 15.6;

    private static Map<String, Double> p;
    private static int rank;
    private static boolean loaded, failed;

    private static synchronized boolean load() {
        if (loaded) return !failed;
        loaded = true;
        try (var r = CombatModel.class.getResourceAsStream("/ronstrat/combat_model.json")) {
            if (r == null) { failed = true; return false; }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(r, StandardCharsets.UTF_8)).getAsJsonObject();
            rank = o.get("rank").getAsInt();
            p = new HashMap<>();
            for (var e : o.getAsJsonObject("params").entrySet()) p.put(e.getKey(), e.getValue().getAsDouble());
            return true;
        } catch (Exception e) {
            RonStrategist.LOG.warn("RON Strategist: combat model not loaded: {}", e.toString());
            failed = true;
            return false;
        }
    }

    public static boolean ready() { return load(); }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("VindicatorUnit", "vindicator"), Map.entry("PillagerUnit", "pillager"), Map.entry("IronGolemUnit", "golem"),
            Map.entry("WitchUnit", "witch"), Map.entry("EvokerUnit", "evoker"), Map.entry("RavagerUnit", "ravager"),
            Map.entry("WindcallerUnit", "windcaller"), Map.entry("MilitiaUnit", "militia"), Map.entry("RoyalGuardUnit", "guard"),
            Map.entry("ZombieUnit", "zombie"), Map.entry("HuskUnit", "husk"), Map.entry("DrownedUnit", "zombie"),
            Map.entry("SkeletonUnit", "skeleton"), Map.entry("StrayUnit", "skeleton"), Map.entry("BoggedUnit", "skeleton"),
            Map.entry("CreeperUnit", "creeper"), Map.entry("SpiderUnit", "spider"), Map.entry("PoisonSpiderUnit", "spider"),
            Map.entry("WraithUnit", "wraith"), Map.entry("SlimeUnit", "slime"),
            Map.entry("BruteUnit", "brute"), Map.entry("HeadhunterUnit", "headhunter"), Map.entry("MarauderUnit", "marauder"),
            Map.entry("HoglinUnit", "hoglin"), Map.entry("BlazeUnit", "blaze"), Map.entry("WitherSkeletonUnit", "witherskeleton"),
            Map.entry("MagmaCubeUnit", "magmacube"), Map.entry("GhastUnit", "ghast"));

    static String type(LivingEntity e) {
        String t = TYPES.get(e.getClass().getSimpleName());
        return t != null && p != null && p.containsKey("lv:" + t) ? t : null;
    }

    private static double cost(LivingEntity e) {
        if (e instanceof Unit u) { var c = u.getCost(); return Math.max(1, c.food + c.wood + c.ore); }
        return 100;
    }

    /** upgrades this unit carries, in the model's option names */
    private static double options(LivingEntity e, String t) {
        double z = 0;
        ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.CHAINMAIL_CHESTPLATE)) z += p.getOrDefault("opt:chain", 0.0);
        else if (chest.is(Items.LEATHER_CHESTPLATE)) z += p.getOrDefault("opt:leather", 0.0);
        ItemStack hand = e.getMainHandItem();
        if (!hand.isEmpty()) for (Enchantment en : hand.getAllEnchantments().keySet()) {
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT.getKey(en));
            String k = id.contains("sharpness") ? "sharpness" : id.contains("multishot") ? "multishot" : id.contains("quick_charge") ? "quickcharge"
                    : id.contains("piercing") ? "piercing" : id.contains("maiming") ? "maiming" : id.contains("vigor") ? "vigor" : null;
            if (k != null) z += p.getOrDefault("opt:" + k, 0.0);
        }
        return z;
    }

    /** the model's value of one unit without the counters (day and upgrades in), or -1 when it has no type */
    static double unitValue(LivingEntity e) {
        if (!load() || !e.isAlive()) return -1;
        String t = type(e);
        if (t == null) return -1;
        double z = p.get("lv:" + t) + options(e, t);
        if (NightPlan.burns(e) && e.level().isDay() && !com.solegendary.reignofnether.time.NightUtils.isInRangeOfNightSource(e.position(), false))
            z += p.getOrDefault("day:" + t, 0.0);
        return Math.exp(z);
    }

    /** UnitEval.power when the model is on: the model's value in the old units; heroes and the rest keep the old */
    static double power(LivingEntity e, double old) {
        double v = unitValue(e);
        return v < 0 ? old : v * SCALE;
    }

    /** log of side X's value against side Y's mix (the counters in) */
    static double sideLog(Collection<? extends LivingEntity> x, Collection<? extends LivingEntity> y) {
        Map<String, Double> share = new HashMap<>();
        double tot = 0;
        for (LivingEntity e : y) { String t = type(e); if (t == null || UnitEval.isWorker(e)) continue; double c = cost(e); share.merge(t, c, Double::sum); tot += c; }
        double[] b = new double[rank];
        if (tot > 0) for (var s : share.entrySet()) for (int r = 0; r < rank; r++) b[r] += p.getOrDefault("b:" + s.getKey() + ":" + r, 0.0) * s.getValue() / tot;
        double sum = 0;
        for (LivingEntity e : x) {
            if (UnitEval.isWorker(e) || !e.isAlive()) continue;
            String t = type(e);
            double v = unitValue(e);
            if (v < 0) { sum += UnitEval.power(e) / SCALE; continue; }
            double g = 0;
            for (int r = 0; r < rank; r++) g += p.getOrDefault("a:" + t + ":" + r, 0.0) * b[r];
            sum += v * Math.exp(g);
        }
        return Math.log(Math.max(1e-6, sum));
    }

    /** X against Y as a strength ratio on UnitEval's scale (its square law): >1 = X stronger */
    static double ratio(Collection<? extends LivingEntity> x, Collection<? extends LivingEntity> y) {
        if (!load()) return UnitEval.strength(x) / Math.max(1, UnitEval.strength(y));
        if (y.isEmpty()) return 99;
        return Math.exp(2 * (sideLog(x, y) - sideLog(y, x)));
    }

    /** resources a unit of the type costs (RON 1.5.1e configs) */
    static final Map<String, Double> COST = Map.ofEntries(
            Map.entry("vindicator", 160.0), Map.entry("pillager", 190.0), Map.entry("golem", 300.0), Map.entry("witch", 270.0),
            Map.entry("evoker", 260.0), Map.entry("ravager", 600.0), Map.entry("windcaller", 260.0), Map.entry("zombie", 75.0),
            Map.entry("husk", 75.0), Map.entry("skeleton", 95.0), Map.entry("creeper", 160.0), Map.entry("spider", 120.0),
            Map.entry("wraith", 140.0), Map.entry("slime", 120.0), Map.entry("brute", 120.0), Map.entry("headhunter", 150.0),
            Map.entry("hoglin", 210.0), Map.entry("blaze", 180.0), Map.entry("witherskeleton", 325.0), Map.entry("magmacube", 120.0),
            Map.entry("ghast", 450.0), Map.entry("marauder", 325.0));

    /** log of what 100 resources of the type are worth against these enemies (counters in), for the army mix */
    static double perCostLog(String type, Collection<? extends LivingEntity> enemies) {
        if (!load() || !p.containsKey("lv:" + type)) return Double.NaN;
        Map<String, Double> share = new HashMap<>();
        double tot = 0;
        for (LivingEntity e : enemies) { String t = type(e); if (t == null || UnitEval.isWorker(e)) continue; double c = cost(e); share.merge(t, c, Double::sum); tot += c; }
        double g = 0;
        if (tot > 0) for (int r = 0; r < rank; r++) {
            double b = 0;
            for (var sh : share.entrySet()) b += p.getOrDefault("b:" + sh.getKey() + ":" + r, 0.0) * sh.getValue() / tot;
            g += p.getOrDefault("a:" + type + ":" + r, 0.0) * b;
        }
        return p.get("lv:" + type) + g + Math.log(100.0 / COST.getOrDefault(type, 160.0));
    }

    /** the forecast of X against Y: -1 (Y wins clean) .. +1 (X wins clean) */
    static double forecast(Collection<? extends LivingEntity> x, Collection<? extends LivingEntity> y) {
        if (!load()) return 0;
        return Math.tanh(p.getOrDefault("alpha", 1.0) * (sideLog(x, y) - sideLog(y, x)));
    }
}
