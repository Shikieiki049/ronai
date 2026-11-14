package com.ronstrat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solegendary.reignofnether.building.Building;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The army composition planner (parameter composition): which share of the army each unit type should
 * be, from the arena's unit matrix (tools/arena.py -> tuning/unit_matrix.json, shipped in the jar as
 * /ronstrat/unit_matrix.json) and the enemy army seen.
 *
 * matrix[a][b] = result of an army of a against an army of b of the same cost, from -1 (a wiped out, b
 * untouched) to +1. Against an enemy mix q our unit a scores sum_b q_b * matrix[a][b]; the shares are a
 * softmax of the scores over the units we can train (temperature compTemp), the ones that need a building
 * we lack are discounted by compTech. Windcallers are only an enemy column (we do not train them). The enemy mix is remembered (seen units fade over three minutes);
 * before anything is seen, RON's own villager weights stand in (vindicator 3, pillager 3, the rest 1).
 */
final class Composition {
    static final String[] UNITS = { "vindicator", "pillager", "golem", "witch", "evoker", "ravager", "windcaller" };
    private static final FactionPlan.Role[] ROLE = { FactionPlan.Role.MELEE, FactionPlan.Role.RANGED, FactionPlan.Role.TANK,
            FactionPlan.Role.SUPPORT, FactionPlan.Role.CASTER, FactionPlan.Role.SIEGE, FactionPlan.Role.RANGED };
    private static final double[] PRIOR = { 3, 3, 1, 1, 1, 1, 0.5 };
    private static double[][] matrix;

    private final Bot bot;
    private final double[] seen = new double[UNITS.length]; // cost-weighted enemy army, fading
    private long lastFade;
    double[] lastShares = new double[UNITS.length];
    double[] lastScores = new double[UNITS.length];

    Composition(Bot bot) { this.bot = bot; }

    static int index(LivingEntity e) {
        EntityType<?> t = e.getType();
        if (t == EntityRegistrar.VINDICATOR_UNIT.get()) return 0;
        if (t == EntityRegistrar.PILLAGER_UNIT.get()) return 1;
        if (t == EntityRegistrar.IRON_GOLEM_UNIT.get()) return 2;
        if (t == EntityRegistrar.WITCH_UNIT.get()) return 3;
        if (t == EntityRegistrar.EVOKER_UNIT.get()) return 4;
        if (t == EntityRegistrar.RAVAGER_UNIT.get()) return 5;
        // windcallers: only an enemy type - flying (upgraded) ones are out of a vindicator's reach, pillagers shoot them down
        if (t == EntityRegistrar.WINDCALLER_UNIT.get()) return 6;
        // other factions: by fighting style, a melee unit counts as a vindicator, a ranged one as a pillager
        if (UnitEval.isWorker(e)) return -1;
        return UnitEval.isRanged(e) ? 1 : 0;
    }

    static Building producer(int i) {
        return switch (i) {
            case 0, 1 -> Buildings.BARRACKS;
            case 2 -> Buildings.BLACKSMITH;
            case 3 -> Buildings.WITCH_HUT;
            case 4, 6 -> Buildings.ARCANE_TOWER;
            default -> Buildings.CASTLE;
        };
    }

    private static final double[] COST = { 160, 190, 300, 270, 260, 600, 260 };

    /** the unit matrix; a flat zero matrix (no preference) when there is none */
    static synchronized double[][] matrix() {
        if (matrix != null) return matrix;
        matrix = new double[UNITS.length][UNITS.length];
        try {
            String pd = System.getProperty("ronstrat.profiles");
            Path f = pd != null ? Path.of(pd).getParent().resolve("unit_matrix.json")
                    : net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("ronstrat/unit_matrix.json");
            String text = null;
            if (Files.exists(f)) text = Files.readString(f);
            else try (var r = Composition.class.getResourceAsStream("/ronstrat/unit_matrix.json")) {
                if (r != null) text = new String(r.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (text != null) {
                JsonObject o = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("units");
                for (int a = 0; a < UNITS.length; a++) {
                    if (!o.has(UNITS[a])) continue;
                    JsonObject row = o.getAsJsonObject(UNITS[a]);
                    for (int b = 0; b < UNITS.length; b++) if (row.has(UNITS[b])) matrix[a][b] = row.get(UNITS[b]).getAsDouble();
                }
            }
        } catch (Exception e) { RonStrategist.LOG.warn("unit matrix not read: {}", e.toString()); }
        return matrix;
    }

    /** the enemy mix (shares summing to 1): what was seen, fading, else the prior */
    double[] enemyMix(Intel in) {
        if (bot.now - lastFade >= 200) { // every 10 s keep 95%: half-life about 2.3 minutes
            for (int i = 0; i < seen.length; i++) seen[i] *= 0.95;
            lastFade = bot.now;
        }
        double[] now = new double[UNITS.length];
        for (LivingEntity e : in.visibleEnemies) {
            int i = index(e);
            if (i >= 0) now[i] += COST[i];
        }
        // what is in sight now is at least what we know
        for (int i = 0; i < seen.length; i++) seen[i] = Math.max(seen[i], now[i]);
        double total = 0;
        for (double v : seen) total += v;
        double[] q = new double[UNITS.length];
        double priorW = Math.max(0, 1 - total / 1500.0); // the prior fades out as ~10 soldiers are seen
        double pt = 0;
        for (double v : PRIOR) pt += v;
        for (int i = 0; i < q.length; i++) q[i] = (total > 0 ? (1 - priorW) * seen[i] / total : 0) + (total > 0 ? priorW : 1) * PRIOR[i] / pt;
        return q;
    }

    /** army shares per role for Production; units we cannot train (no building, too few workers) get none */
    Map<FactionPlan.Role, Double> roleMix(Intel in) {
        BotParams p = bot.params;
        double[][] m = matrix();
        double[] q = enemyMix(in);
        double temp = Math.max(0.02, p.get("compTemp")), techCost = p.get("compTech");
        double[] w = new double[UNITS.length];
        double sum = 0;
        for (int a = 0; a < UNITS.length; a++) {
            double s = 0;
            for (int b = 0; b < UNITS.length; b++) s += q[b] * m[a][b];
            lastScores[a] = s;
            if (!allowed(a, in)) continue;
            if (in.built(producer(a)) == 0) s -= techCost;
            w[a] = Math.exp(s / temp);
            sum += w[a];
        }
        Map<FactionPlan.Role, Double> out = new EnumMap<>(FactionPlan.Role.class);
        for (int a = 0; a < UNITS.length; a++) {
            double share = sum > 0 ? w[a] / sum : (a == 0 ? 1 : 0);
            if (share < p.get("compMinShare")) share = 0;
            lastShares[a] = share;
            out.merge(ROLE[a], share, Double::sum);
        }
        return out;
    }

    /** a unit the planner may ask for: barracks units always, the others once the economy can carry the tech */
    private boolean allowed(int a, Intel in) {
        int workers = in.workers.size();
        return switch (a) {
            case 0, 1 -> true;
            case 2 -> workers >= 14;
            case 3 -> workers >= 18;
            case 4 -> workers >= 24;
            case 5 -> workers >= 32;
            default -> false; // windcallers: we never train them
        };
    }

    /** the tech building the planner wants and lacks (its unit outscores the best one we have by compTechGap), or null */
    Building techWanted(Intel in) {
        double bestHave = -9, bestMissing = -9;
        int missing = -1;
        for (int a = 0; a < UNITS.length; a++) {
            if (!allowed(a, in)) continue;
            if (in.built(producer(a)) > 0) bestHave = Math.max(bestHave, lastScores[a]);
            else if (lastScores[a] > bestMissing) { bestMissing = lastScores[a]; missing = a; }
        }
        if (missing < 0 || bestMissing < bestHave + bot.params.get("compTechGap")) return null;
        return producer(missing);
    }

    String describe() {
        Map<String, String> s = new LinkedHashMap<>();
        for (int a = 0; a < UNITS.length; a++) if (lastShares[a] > 0) s.put(UNITS[a], String.format("%.2f", lastShares[a]));
        return s.toString();
    }
}
