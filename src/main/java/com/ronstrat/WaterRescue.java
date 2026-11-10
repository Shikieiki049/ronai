package com.ronstrat;

import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;

/**
 * Units in deep water barely moved. In water a mob's push is 0.02 x its speed and RON units walk at
 * 0.25: with water's 0.8 drag that is about 0.08 blocks a second (Oceanborn: 10 blocks to the bank took
 * over two minutes) - the unit seems to float on the spot whatever it was ordered to do.
 * A unit swimming (in water, no ground underfoot) gets a short, invisible Dolphin's Grace: vanilla drag
 * 0.96 instead of 0.8, about 2 blocks a second, close to its walking pace. It wears off 4 s after the
 * unit is out. Shallow water (the pool in every wheat field) is left alone.
 * Measured (villagers dropped 5-12 blocks from a bank, 60 s): 21/24 ashore vs 12/24 without.
 * Tried and dropped: letting swimmers path through water (woodcutters crossed straits to wood they could
 * not bring back) and pushing stuck swimmers onto banks / re-pathing them (pulled armies off their
 * routes) - both cost Infinity_Isle every game. Applies to every unit, players' as well as bots'.
 * -Dronstrat.noWaterRescue turns it off (A/B tests).
 */
final class WaterRescue {
    private static long ticks;
    private static final boolean OFF = System.getProperty("ronstrat.noWaterRescue") != null;

    static void tick() {
        if (OFF || ++ticks % 5 != 0) return;
        for (LivingEntity e : new ArrayList<>(UnitServerEvents.getAllUnits())) {
            if (!(e instanceof Mob m) || !(e instanceof Unit) || !e.isAlive() || e.level().isClientSide) continue;
            boolean swimming = m.isInWater() && !m.onGround() && m.level().getFluidState(m.blockPosition().below()).is(FluidTags.WATER);
            if (!swimming) continue;
            MobEffectInstance g = m.getEffect(MobEffects.DOLPHINS_GRACE);
            if (g == null || g.getDuration() < 40) m.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 80, 0, false, false, false));
            // ordered somewhere but no path at all (a channel between fields, the target side a wall): a
            // push onto the nearest bank it can climb, nearest the target. Only then - no re-pathing and
            // nothing for units on a path or busy gathering (those cost Infinity_Isle every game)
            if (HOP && m.getNavigation().isDone() && !(e instanceof com.solegendary.reignofnether.unit.interfaces.WorkerUnit wk && wk.getGatherResourceGoal() != null && wk.getGatherResourceGoal().getGatherTarget() != null)) {
                var mg = ((Unit) e).getMoveGoal();
                net.minecraft.core.BlockPos mt = mg != null ? mg.getMoveTarget() : null;
                if (mt == null) continue;
                net.minecraft.core.BlockPos exit = climbableBank(m, mt);
                if (exit != null) {
                    double dx = exit.getX() + 0.5 - m.getX(), dz = exit.getZ() + 0.5 - m.getZ();
                    double d = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
                    m.setDeltaMovement(dx / d * 0.15, 0.42, dz / d * 0.15);
                }
            }
        }
    }
    private static final boolean HOP = System.getProperty("ronstrat.noWaterHop") == null;

    /** a dry block right next to the unit, top at most ~1 above the water, with room to stand on it */
    private static net.minecraft.core.BlockPos climbableBank(Mob m, net.minecraft.core.BlockPos target) {
        var lv = m.level();
        net.minecraft.core.BlockPos at = m.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    net.minecraft.core.BlockPos q = at.offset(dx, dy, dz);
                    if (!lv.getFluidState(q).isEmpty() || lv.getBlockState(q).getCollisionShape(lv, q).isEmpty()) continue;
                    double top = q.getY() + lv.getBlockState(q).getCollisionShape(lv, q).max(net.minecraft.core.Direction.Axis.Y);
                    if (top > m.getY() + 1.3) continue;
                    if (!lv.getBlockState(q.above()).getCollisionShape(lv, q.above()).isEmpty() || !lv.getBlockState(q.above(2)).getCollisionShape(lv, q.above(2)).isEmpty()) continue;
                    double d = q.distSqr(target);
                    if (d < bestD) { bestD = d; best = q; }
                }
            }
        return best;
    }
}
