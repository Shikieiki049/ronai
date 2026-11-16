package com.ronstrat;

import com.solegendary.reignofnether.unit.goals.MoveToTargetBlockGoal;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Units that cannot find their way (the user: a hero or a soldier behind a wall, a fence, a ledge or a tangled
 * path stands there for good). Every second each soldier and hero is checked: it has somewhere to go (a move
 * target, or an enemy to reach) more than 5 blocks away but has not moved 1.5 blocks in 5 seconds. Then our
 * own surface route (Terrain.route: the height map, steps of one block, fences and walls, water at a price)
 * leads it there by hand, a waypoint about 10 blocks ahead at a time, until it is there, an enemy is close
 * (it fights first), or 40 seconds have passed. Parameter unstick.
 */
final class Unstick {
    private final Bot bot;
    private final Map<Integer, double[]> last = new HashMap<>();          // id -> x, z, tick of the last real move
    private final Map<Integer, Guide> guided = new HashMap<>();
    private long nextCheck;
    int rescued;

    private record Guide(BlockPos goal, List<BlockPos> route, long since) {}

    Unstick(Bot bot) { this.bot = bot; }

    void think(Intel in) {
        if (!bot.params.b("unstick") || bot.now < nextCheck) return;
        nextCheck = bot.now + 20;
        List<Mob> units = new ArrayList<>(in.army);
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (Mob u : units) {
            alive.add(u.getId());
            if (Orders.isCasting(u.getId())) continue;
            BlockPos goal = goalOf(u);
            Guide g = guided.get(u.getId());
            // being led: next waypoint, or let go
            if (g != null) {
                boolean enemyNear = !in.enemiesNear(u.position(), 10).isEmpty();
                if (enemyNear || bot.now - g.since() > 800 || u.blockPosition().distSqr(g.goal()) < 6 * 6) {
                    guided.remove(u.getId());
                    if (!enemyNear && u.blockPosition().distSqr(g.goal()) >= 6 * 6) continue;
                    // arrived / an enemy: back to what it was doing - attack-move to the goal fights on the way
                    Orders.attackMove(bot.name, List.of(u), g.goal());
                    continue;
                }
                BlockPos wp = Terrain.ahead(g.route(), u.position(), 10);
                Orders.move(bot.name, List.of(u), wp);
                Orders.protect(u.getId(), 1200);
                last.put(u.getId(), new double[] { u.getX(), u.getZ(), bot.now });
                continue;
            }
            double[] l = last.get(u.getId());
            if (l == null || Math.hypot(u.getX() - l[0], u.getZ() - l[1]) > 1.5) { last.put(u.getId(), new double[] { u.getX(), u.getZ(), bot.now }); continue; }
            if (goal == null || u.blockPosition().distSqr(goal) < 5 * 5) { l[2] = bot.now; continue; }
            if (bot.now - l[2] < 100) continue;
            // stuck for 5 s with somewhere to go: lead it round
            List<BlockPos> r = Terrain.route(bot.level, u.blockPosition(), goal, bot.now);
            l[2] = bot.now;
            if (r == null || r.size() < 2) continue;
            guided.put(u.getId(), new Guide(goal, r, bot.now));
            rescued++;
            BlockPos wp = Terrain.ahead(r, u.position(), 10);
            Orders.move(bot.name, List.of(u), wp);
            Orders.protect(u.getId(), 1200);
            if (bot.verbose) bot.log("unstick: " + u.getType().toShortString() + " at " + u.blockPosition().toShortString() + " stuck on the way to "
                    + goal.toShortString() + " - led round (" + r.size() + " steps), " + rescued + " so far");
        }
        last.keySet().retainAll(alive);
        guided.keySet().retainAll(alive);
    }

    /** where the unit is trying to get: its move target, else the enemy it attacks */
    private static BlockPos goalOf(Mob u) {
        if (!(u instanceof Unit un)) return null;
        MoveToTargetBlockGoal mg = un.getMoveGoal();
        if (mg != null && mg.getMoveTarget() != null) return mg.getMoveTarget();
        LivingEntity t = un.getTargetGoal() == null ? null : un.getTargetGoal().getTarget();
        // a target already in reach (an archer shooting from where it stands) is not being stuck
        if (t != null && t.isAlive() && t.distanceTo(u) > UnitEval.range(u) + 2) return t.blockPosition();
        return null;
    }
}
