package com.ronstrat;

import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A quick forecast of a fight between two groups: half-second steps on a line, melee units have to
 * walk into reach, at most three melee units hit the same target, ranged units shoot as soon as the
 * nearest enemy is in range. Answers "who wins and with how much left", which a single strength
 * number cannot (ranged against melee, armour, a few strong units against many weak ones).
 *
 * Forecasts made at the start of real fights are compared with what happened (Probe) and written
 * into the self-play result line as simChecks.
 */
public final class CombatSim {
    private CombatSim() {}

    public static final double STEP = 0.5;
    public static final double MAX_SECONDS = 120;
    private static final int MELEE_PER_TARGET = 3;

    /** one fighter in the forecast */
    public static final class U {
        double hp, hp0, armour, dps, range, speed, pos;
        boolean fixed; // a tower: shoots, cannot be killed, does not count as a survivor
        int hitBy;

        U copy() {
            U u = new U();
            u.hp = hp; u.hp0 = hp0; u.armour = armour; u.dps = dps; u.range = range; u.speed = speed; u.fixed = fixed;
            return u;
        }
    }

    public static final class Result {
        public boolean win;
        /** share of the starting hit points still alive on each side, 0..1 */
        public double ownLeft, enemyLeft;
        public double seconds;

        @Override public String toString() {
            return String.format("%s own %.0f%% enemy %.0f%% in %.0fs", win ? "win" : "loss", 100 * ownLeft, 100 * enemyLeft, seconds);
        }
    }

    public static U of(LivingEntity e) {
        U u = new U();
        u.hp = u.hp0 = Math.max(1, e.getHealth());
        u.armour = e instanceof Unit un ? Math.min(0.8, Math.max(0, un.getUnitPhysicalArmorPercentage())) : 0;
        u.dps = UnitEval.dps(e);
        if (UnitEval.isWorker(e)) u.dps *= 0.5;
        u.range = Math.max(1.5, UnitEval.range(e));
        double a = e.getAttribute(Attributes.MOVEMENT_SPEED) == null ? 0.25 : e.getAttributeValue(Attributes.MOVEMENT_SPEED);
        u.speed = Math.max(1.5, Math.min(8, a * a * 58));
        return u;
    }

    public static U tower() {
        U u = new U();
        u.hp = u.hp0 = 1;
        u.dps = 6;
        u.range = 18;
        u.fixed = true;
        return u;
    }

    public static List<U> of(Collection<? extends LivingEntity> units) {
        List<U> out = new ArrayList<>();
        for (LivingEntity e : units) if (e.isAlive()) out.add(of(e));
        return out;
    }

    /** own units attack the enemy group; enemyTowers shoot for the enemy; the groups start gap blocks apart */
    public static Result run(Collection<? extends LivingEntity> own, Collection<? extends LivingEntity> enemy, int enemyTowers, double gap) {
        List<U> b = of(enemy);
        for (int i = 0; i < enemyTowers; i++) b.add(tower());
        return run(of(own), b, gap);
    }

    public static Result run(List<U> a, List<U> b, double gap) {
        for (U u : a) u.pos = 0;
        for (U u : b) u.pos = gap;
        double hpA0 = hp(a), hpB0 = hp(b);
        Result r = new Result();
        double t = 0;
        while (t < MAX_SECONDS && mobile(a) > 0 && mobile(b) > 0) {
            double[] dmgToB = step(a, b, +1);
            double[] dmgToA = step(b, a, -1);
            apply(b, dmgToB);
            apply(a, dmgToA);
            t += STEP;
        }
        r.seconds = t;
        r.ownLeft = hpA0 <= 0 ? 0 : hp(a) / hpA0;
        r.enemyLeft = hpB0 <= 0 ? 0 : hp(b) / hpB0;
        r.win = mobile(b) == 0 ? mobile(a) > 0 : mobile(a) > 0 && r.ownLeft > r.enemyLeft + 0.2;
        return r;
    }

    private static double hp(List<U> us) {
        double s = 0;
        for (U u : us) if (!u.fixed && u.hp > 0) s += u.hp / (1 - u.armour);
        return s;
    }

    private static int mobile(List<U> us) {
        int n = 0;
        for (U u : us) if (!u.fixed && u.hp > 0) n++;
        return n;
    }

    /** every living unit of from picks a target in to, walks or hits; returns the damage dealt to each unit of to */
    private static double[] step(List<U> from, List<U> to, int dir) {
        double[] dmg = new double[to.size()];
        for (U t : to) t.hitBy = 0;
        for (U u : from) {
            if (u.hp <= 0) continue;
            boolean melee = u.range <= 5;
            int best = -1;
            double bestD = 1e9;
            for (int i = 0; i < to.size(); i++) {
                U t = to.get(i);
                if (t.hp <= 0 || t.fixed) continue;
                if (melee && t.hitBy >= MELEE_PER_TARGET) continue;
                // nearest first; among equally near ones the weakest (focus fire)
                double d = Math.abs(t.pos - u.pos) + 0.001 * (t.hp - dmg[i]);
                if (dmg[i] >= t.hp) d += 50; // already dead this step: do not waste the hit
                if (d < bestD) { bestD = d; best = i; }
            }
            if (best < 0) continue;
            U t = to.get(best);
            double dist = Math.abs(t.pos - u.pos);
            if (dist <= u.range) {
                dmg[best] += u.dps * STEP * (1 - t.armour);
                if (melee) t.hitBy++;
            } else if (!u.fixed) {
                u.pos += dir * Math.min(u.speed * STEP, dist - u.range + 0.1);
            }
        }
        return dmg;
    }

    private static void apply(List<U> us, double[] dmg) {
        for (int i = 0; i < dmg.length; i++) {
            U u = us.get(i);
            if (!u.fixed) u.hp -= dmg[i];
        }
    }

    // ---- checking forecasts against real fights ----

    private static final List<String> CHECKS = new ArrayList<>();

    public static synchronized List<String> checks() { return new ArrayList<>(CHECKS); }
    public static synchronized void resetChecks() { CHECKS.clear(); }

    /** a real fight being watched: the forecast made at first contact and the units that were there */
    public static final class Probe {
        private final List<LivingEntity> own, enemy;
        private final double own0, enemy0;
        private final Result forecast;
        private final long start;
        private final int towers;
        long lastContact;

        public Probe(Collection<? extends LivingEntity> own, Collection<? extends LivingEntity> enemy, int towers, double gap, long now) {
            this.own = new ArrayList<>(own);
            this.enemy = new ArrayList<>(enemy);
            this.own0 = real(this.own);
            this.enemy0 = real(this.enemy);
            this.forecast = run(own, enemy, towers, gap);
            this.towers = towers;
            this.start = this.lastContact = now;
        }

        private static double real(List<LivingEntity> us) {
            double s = 0;
            for (LivingEntity e : us) if (e.isAlive() && !e.isRemoved()) s += UnitEval.effectiveHp(e);
            return s;
        }

        /** true when the fight is over (one side gone, no contact for 8 s, or 2 minutes); records the comparison */
        public boolean finished(long now, String how) {
            double o = real(own), e = real(enemy);
            boolean over = how != null || o <= 0 || e <= 0 || now - lastContact > 160 || now - start > 2400;
            if (!over) return false;
            String line = String.format(java.util.Locale.ROOT, "%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%s",
                    start / 20, own.size(), enemy.size(), towers, forecast.ownLeft, forecast.enemyLeft,
                    own0 <= 0 ? 0 : o / own0, enemy0 <= 0 ? 0 : e / enemy0, (now - start) / 20, how == null ? "end" : how);
            synchronized (CombatSim.class) { if (CHECKS.size() < 400) CHECKS.add(line); }
            return true;
        }
    }
}
