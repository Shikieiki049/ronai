package com.ronstrat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One joint attack of allied expert bots (parameter teamV2): one target, one assembly point short of
 * it, and one moment to go in. Armies that set off from homes at different distances meet at the
 * assembly point first and then land together, instead of arriving minutes apart and being beaten
 * one at a time.
 */
final class TeamPlan {
    private static final Map<String, TeamPlan> PLANS = new HashMap<>();

    final String team, target;
    final BlockPos targetPos, assembly;
    final long created;
    final Set<String> members = new LinkedHashSet<>();
    long firstThere = -1, released = -1;

    private TeamPlan(String team, String target, BlockPos targetPos, BlockPos assembly, long now) {
        this.team = team; this.target = target; this.targetPos = targetPos; this.assembly = assembly; this.created = now;
    }

    /** the allied expert bots of this brain, this one included, sorted by name: the key of the team */
    static List<Bot> mates(Bot bot) {
        List<Bot> out = new ArrayList<>();
        for (Bot o : Bot.peers()) {
            if (o.difficulty != Difficulty.EXPERT || !BotManager.isRtsName(o.name)) continue;
            if (o != bot && !allied(bot, o)) continue;
            out.add(o);
        }
        out.sort((a, b) -> a.name.compareTo(b.name));
        return out;
    }

    private static boolean allied(Bot a, Bot b) {
        try { return com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(a.name, b.name); }
        catch (Throwable t) { return false; }
    }

    static String key(List<Bot> mates) {
        StringBuilder sb = new StringBuilder();
        for (Bot b : mates) sb.append(b.name).append('|');
        return sb.toString();
    }

    /** the team's running plan, if any (dropped when nobody is attacking under it any more) */
    static synchronized TeamPlan current(Bot bot) {
        List<Bot> mates = mates(bot);
        if (mates.size() < 2) return null;
        TeamPlan p = PLANS.get(key(mates));
        if (p == null) return null;
        boolean anyone = false;
        for (Bot b : mates) if (p.members.contains(b.name) && b.army.inAttack() && p.target.equals(b.army.targetName())) anyone = true;
        if (!anyone && bot.now - p.created > 200) { PLANS.remove(p.team); return null; }
        if (bot.now - p.created > 20 * 600) { PLANS.remove(p.team); return null; } // ten minutes: start over
        return p;
    }

    /** open a plan on this target: the assembly point is short of it, on the side the team comes from */
    static synchronized TeamPlan open(Bot bot, String target, BlockPos targetPos) {
        List<Bot> mates = mates(bot);
        if (mates.size() < 2) return null;
        Vec3 from = Vec3.ZERO;
        int n = 0;
        for (Bot b : mates) { BlockPos h = b.intel.home(); if (h != null) { from = from.add(Vec3.atCenterOf(h)); n++; } }
        if (n == 0) return null;
        from = from.scale(1.0 / n);
        Vec3 to = Vec3.atCenterOf(targetPos);
        Vec3 d = to.subtract(from);
        double len = d.length();
        double back = Math.min(55, len * 0.5);
        BlockPos a = len < 1 ? targetPos : BlockPos.containing(to.subtract(d.scale(back / len)));
        a = com.solegendary.reignofnether.util.MiscUtil.getHighestNonAirBlock(bot.level, new BlockPos(a.getX(), 0, a.getZ())).above();
        TeamPlan p = new TeamPlan(key(mates), target, targetPos, a, bot.now);
        PLANS.put(p.team, p);
        bot.log("team plan: " + target + " assemble at " + a.toShortString() + " (" + mates.size() + " allies)");
        return p;
    }

    /**
     * Called by each member while its army walks in. Released (everyone goes) when the armies of all
     * members are at the assembly point, or 50 s after the first one got there, or 3 minutes after the plan
     * started (a member stuck at home must not hold the others forever).
     */
    synchronized boolean released(Bot bot) {
        if (released >= 0) return true;
        int there = 0, total = 0;
        for (Bot b : mates(bot)) {
            if (!members.contains(b.name) || !b.army.inAttack()) continue;
            total++;
            List<Mob> body = Army.mainBody(b.intel.army);
            if (!body.isEmpty() && Army.centroid(body).distanceTo(Vec3.atCenterOf(assembly)) < 22) there++;
        }
        if (members.size() < 2 && bot.now - created < 600) return false; // give the others a moment to join
        if (there > 0 && firstThere < 0) firstThere = bot.now;
        boolean all = total > 0 && there >= total;
        if (all || firstThere >= 0 && bot.now - firstThere > 1000 || bot.now - created > 3600) {
            released = bot.now;
            bot.log("team plan: go on " + target + " (" + there + "/" + total + " assembled)");
            bot.tellAllies("集合完毕，一起压上去打 " + target + "！", true);
            return true;
        }
        return false;
    }
}
