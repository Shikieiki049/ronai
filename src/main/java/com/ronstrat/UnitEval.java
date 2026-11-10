package com.ronstrat;

import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

/**
 * Combat value estimates. Fights in RON, like in AoE2, roughly follow Lanchester's square law:
 * a group's fighting strength grows with the square of its summed per-unit power, where power is
 * sqrt(dps * effective hp). This is what decides attack / hold / retreat.
 */
public final class UnitEval {
    private UnitEval() {}

    public static boolean isWorker(LivingEntity e) { return e instanceof WorkerUnit; }

    public static boolean isScout(LivingEntity e) { return e instanceof Unit u && u.isScout(); }

    public static double range(LivingEntity e) {
        return e instanceof AttackerUnit a ? a.getAttackRange() : 0;
    }

    public static boolean isRanged(LivingEntity e) { return range(e) > 5.0; }

    public static double dps(LivingEntity e) {
        if (e instanceof AttackerUnit a) {
            double d = a.getUnitAttackDamage() * Math.max(0.2, a.getAttacksPerSecond());
            return Math.max(0.5, d);
        }
        // spellcasters without a basic attack still matter
        return e instanceof Unit ? 3.0 : 0.0;
    }

    public static double effectiveHp(LivingEntity e) {
        double armour = 0;
        if (e instanceof Unit u) armour = Math.min(0.8, Math.max(0, u.getUnitPhysicalArmorPercentage()));
        return Math.max(1, e.getHealth()) / (1.0 - armour);
    }

    public static double power(LivingEntity e) {
        if (!e.isAlive()) return 0;
        double p = Math.sqrt(dps(e) * effectiveHp(e));
        if (isRanged(e)) p *= 1.2;
        if (isWorker(e)) p *= 0.35;
        return p;
    }

    public static double strength(Collection<? extends LivingEntity> units) {
        double s = 0;
        for (LivingEntity e : units) s += power(e);
        return s * s;
    }

    public static double popValue(LivingEntity e) {
        return e instanceof Unit u ? Math.max(1, u.getCost().population) : 1;
    }
}
