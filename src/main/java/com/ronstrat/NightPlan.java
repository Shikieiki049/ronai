package com.ronstrat;

import com.solegendary.reignofnether.time.NightUtils;
import com.solegendary.reignofnether.time.TimeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Set;

/**
 * The monsters' day and night (parameter nightPlan). In RON (TimeUtils, NightUtils) a day is 24000 ticks:
 * day from 500 to 12500, night the other half - ten minutes each. In daylight zombies, skeletons, strays,
 * drowned, wraiths and both monster heroes (necromancer, wretched wraith) burn unless they stand within a
 * night source's range: a mausoleum (40 blocks), a sculk catalyst (30 + 0.3 per sculk block, at most 50),
 * a stronghold (60) - anyone's - or near a unit with night warping; a blood moon is night everywhere.
 * Husks, spiders, creepers, wardens and slimes do not burn.
 *
 * What the army does with it: attack only when the night left covers the walk there, the fight and the walk
 * back - or with an army that does not burn; start home when the dawn would catch it on the way; by day the
 * burning units and the heroes wait inside the night at home. Our own rules from the mechanics, measured in
 * self-play ("night" games).
 */
final class NightPlan {
    private static final Set<String> BURNS = Set.of("ZombieUnit", "SkeletonUnit", "StrayUnit", "DrownedUnit", "WraithUnit",
            "NecromancerUnit", "WretchedWraithUnit", "PhantomSummon", "ZoglinUnit", "ZombiePiglinUnit");

    private final Bot bot;

    NightPlan(Bot bot) { this.bot = bot; }

    /** this bot plays by the clock: a faction with night sources, the parameter on, and a clock that turns */
    boolean on() {
        if (bot.plan.nightSources.isEmpty() || !bot.params.b("nightPlan")) return false;
        return bot.level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    long time() { return TimeUtils.normaliseTime(bot.level.getDayTime()); }

    boolean day() { return TimeUtils.isDay(bot.level); }

    /** ticks of night left (0 by day) */
    long nightLeft() {
        long t = time();
        if (day()) return 0;
        return t > TimeUtils.DUSK ? 24000 - t + TimeUtils.DAWN : TimeUtils.DAWN - t;
    }

    /** ticks until night falls (0 at night) */
    long untilDusk() { return day() ? TimeUtils.DUSK - time() : 0; }

    static boolean burns(LivingEntity e) { return BURNS.contains(e.getClass().getSimpleName()); }

    /** share of the force's population that burns in daylight */
    static double burnShare(Collection<? extends Mob> force) {
        double all = 0, burn = 0;
        for (Mob m : force) {
            double pv = Math.max(1, UnitEval.popValue(m));
            all += pv;
            if (burns(m)) burn += pv;
        }
        return all <= 0 ? 0 : burn / all;
    }

    /** safe from the sun at this spot right now */
    boolean safe(Vec3 pos) { return !day() || NightUtils.isInRangeOfNightSource(pos, false); }

    /** ticks for the force to walk this far (zombies and skeletons: about 4.5 blocks a second) */
    static double walkTicks(double blocks) { return blocks / 4.5 * 20; }

    /**
     * May the army set out against a base this far away? At night: the night left covers the way there, the
     * fight (nightFightTicks) and the way back. By day: only a force that mostly does not burn.
     */
    boolean attackWindow(Collection<? extends Mob> force, double distance) {
        // the user: by day the monsters' army stays in the dark at home and defends - it does not go out,
        // not even husks that do not burn
        if (day()) return false;
        double burn = burnShare(force);
        if (burn <= bot.params.get("nightSunSafeShare")) return true;
        return nightLeft() >= 2 * walkTicks(distance) + bot.params.get("nightFightTicks");
    }

    /** the army out on an attack has to start home now: the dawn would catch the burning part on the way */
    boolean mustReturn(Collection<? extends Mob> force, BlockPos home) {
        if (force.isEmpty()) return false;
        double x = 0, z = 0;
        for (Mob m : force) { x += m.getX(); z += m.getZ(); }
        x /= force.size(); z /= force.size();
        double back = Math.sqrt((x - home.getX()) * (x - home.getX()) + (z - home.getZ()) * (z - home.getZ()));
        // by day: back into the dark, whatever the army is made of
        if (day()) return !NightUtils.isInRangeOfNightSource(new Vec3(x, home.getY(), z), false);
        if (burnShare(force) <= bot.params.get("nightSunSafeShare")) return false;
        return nightLeft() < walkTicks(back) + bot.params.get("nightDawnMargin");
    }
}
