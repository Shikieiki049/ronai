package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingBlock;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.BuildingUtils;
import com.solegendary.reignofnether.building.BuildingValidators;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.building.buildings.shared.AbstractBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds bridges where water or lava cuts the army off from its target (or makes it swim a long
 * way). A crossing is found with a route search that may cross liquids; then oak bridge segments
 * are laid one after another from the near bank until the far bank is reached. Each segment must
 * rest partly on land or on the previous segment (RON: 20-95% of its bottom over liquid), so the
 * candidate spots are tested with RON's own placement validator.
 */
public final class Bridger {
    private final Bot bot;
    private BlockPos bank, far;
    private double ux, uz;
    private int length, segments, liquidKind;
    private BuildingPlacement current;
    private long currentSince, nextRequest, crossingSince;
    private final Set<Long> failed = new HashSet<>();
    public int built;
    private final Set<BlockPos> refused = new HashSet<>();
    private long retryAt;

    Bridger(Bot bot) { this.bot = bot; }

    private boolean enabled() { return bot.params.b("bridges"); }

    public boolean busy() { return bank != null; }

    private static long key(BlockPos p) { return ((long) (p.getX() >> 3) << 32) ^ (p.getZ() >> 3); }

    /**
     * The army cannot get from `from` to `goal` (or only by a long swim): look for a liquid crossing
     * on the way and start bridging it.
     */
    public void request(BlockPos from, BlockPos goal) {
        if (!enabled() || busy() || bot.now < nextRequest) return;
        nextRequest = bot.now + 600;
        List<BlockPos> r = Terrain.bridgeRoute(bot.level, from, goal);
        if (r == null || !Terrain.reaches(r, goal)) {
            // the height-map search can miss an islet (shore cliffs, a target block up a tree): try the
            // straight line from the nearest shore instead
            if (straight(from, goal)) return;
            bot.log("bridge: no crossing found to " + goal.toShortString() + (r == null ? " (no route)" : " (route ends at " + r.get(r.size() - 1).toShortString() + ")"));
            return;
        }
        int s = -1, e = -1, kind = 0;
        for (int i = 1; i < r.size() - 1; i++) {
            int l = Terrain.liquidAt(bot.level, r.get(i));
            if (l != 0 && s < 0) { s = i; kind = l; }
            if (s >= 0) {
                if (l == 0) { e = i - 1; break; }
                if (l == 2) kind = 2;
            }
        }
        if (s < 1 || e < s) {
            if (straight(from, goal)) return;
            bot.log("bridge: route to " + goal.toShortString() + " is dry (s=" + s + " e=" + e + ")");
            return;
        }
        int len = e - s + 1;
        // a short swim is no obstacle; lava of any width is
        if (kind == 1 && len < bot.params.i("bridgeMinWater")) { bot.log("water crossing of " + len + " at " + r.get(s).toShortString() + ": swimming"); return; }
        BlockPos b = r.get(s - 1), f = r.get(Math.min(r.size() - 1, e + 1));
        if (failed.contains(key(b))) return;
        double dx = f.getX() - b.getX(), dz = f.getZ() - b.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 2 || d > 60) { bot.log("bridge: crossing of " + (int) d + " blocks at " + b.toShortString() + " not bridged"); return; }
        bank = b; far = f; ux = dx / d; uz = dz / d; length = (int) Math.ceil(d);
        liquidKind = kind; segments = 0; current = null; crossingSince = bot.now; refused.clear();
        bot.log("bridge needed over " + (kind == 2 ? "lava" : "water") + " from " + b.toShortString() + " to " + f.toShortString() + " (" + length + " blocks)");
    }

    /**
     * Straight-line crossing toward goal: walk from `from` to the goal one block at a time and bridge the
     * last stretch of water before it (the shore of an islet), if it is short enough.
     */
    private boolean straight(BlockPos from, BlockPos goal) {
        double dx = goal.getX() - from.getX(), dz = goal.getZ() - from.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 4) return false;
        int n = (int) d;
        int s = -1, e = -1;
        for (int t = 1; t <= n; t++) {
            int x = (int) Math.round(from.getX() + dx * t / d), z = (int) Math.round(from.getZ() + dz * t / d);
            BlockPos c = new BlockPos(x, bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            int l = Terrain.liquidAt(bot.level, c);
            if (l == 2) return false;
            if (l == 1 && (s < 0 || e >= 0)) { s = t; e = -1; }
            else if (l == 0 && s >= 0 && e < 0) e = t;
        }
        if (s < 2 || e < 0) return false;
        int len = e - s;
        if (len < bot.params.i("bridgeMinWater") || len > 60) return false;
        int bx = (int) Math.round(from.getX() + dx * (s - 1) / d), bz = (int) Math.round(from.getZ() + dz * (s - 1) / d);
        int fx = (int) Math.round(from.getX() + dx * e / d), fz = (int) Math.round(from.getZ() + dz * e / d);
        var hm = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        BlockPos b = new BlockPos(bx, bot.level.getHeight(hm, bx, bz), bz), f = new BlockPos(fx, bot.level.getHeight(hm, fx, fz), fz);
        if (failed.contains(key(b))) return false;
        double ddx = f.getX() - b.getX(), ddz = f.getZ() - b.getZ(), dd = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dd < 2) return false;
        bank = b; far = f; ux = ddx / dd; uz = ddz / dd; length = (int) Math.ceil(dd);
        liquidKind = 1; segments = 0; current = null; crossingSince = bot.now; refused.clear();
        bot.log("bridge needed over water (straight) from " + b.toShortString() + " to " + f.toShortString() + " (" + length + " blocks)");
        return true;
    }

    private BlockPos lineCell(int t) {
        int x = (int) Math.round(bank.getX() + ux * t), z = (int) Math.round(bank.getZ() + uz * t);
        int y = bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** first step along the crossing that is still liquid, or -1 if the whole line is dry now */
    private int frontier() {
        for (int t = 1; t < length; t++) if (Terrain.liquidAt(bot.level, lineCell(t)) != 0) return t;
        return -1;
    }

    private void give(String why) {
        bot.log("bridge " + why + " (" + segments + " segments)");
        if (!why.startsWith("done")) failed.add(key(bank));
        bank = null; current = null;
    }

    void think(Intel in) {
        if (!busy()) return;
        if (current != null) {
            if (current.isDestroyedServerside) { give("segment destroyed: giving up"); return; }
            if (!current.isBuilt) {
                if (bot.now - currentSince > 3000) { give("segment never finished: giving up"); }
                return;
            }
            current = null;
            built++;
        }
        int t0 = frontier();
        if (t0 < 0) { give("done"); Terrain.clearCache(); return; }
        if (segments >= 12 || bot.now - crossingSince > 12000) { give("too long: giving up"); return; }
        if (in.wood < 100 || bot.now < retryAt) return;
        place(in, t0);
    }

    private ArrayList<BuildingBlock> relative(boolean diag) {
        return ((AbstractBridge) Buildings.OAK_BRIDGE).getRelativeBlockData(bot.level, diag);
    }

    private void place(Intel in, int t0) {
        // liquid cells still to cover on the crossing line
        List<BlockPos> todo = new ArrayList<>();
        for (int t = t0; t < length; t++) {
            BlockPos c = lineCell(t);
            if (Terrain.liquidAt(bot.level, c) != 0) todo.add(c);
        }
        boolean diagonalLine = Math.abs(ux) > 0.38 && Math.abs(uz) > 0.38;
        BlockPos bestO = null; Rotation bestR = null; boolean bestD = false; int bestScore = 0;
        Rotation[] rots = { Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90 };
        for (boolean diag : new boolean[] { diagonalLine, !diagonalLine }) {
            ArrayList<BuildingBlock> rel = relative(diag);
            for (Rotation rot : rots) {
                for (int t = Math.max(0, t0 - 9); t <= Math.min(length, t0 + 3); t++) {
                    BlockPos c = lineCell(t);
                    for (int lat = -3; lat <= 3; lat++) {
                        for (int dy = -2; dy <= 0; dy++) {
                            BlockPos o = new BlockPos((int) Math.round(c.getX() - uz * lat), c.getY() + dy, (int) Math.round(c.getZ() + ux * lat));
                            if (refused.contains(o)) continue;
                            int score = coverage(rel, o, rot, todo);
                            if (score <= bestScore) continue;
                            if (!BuildingValidators.isPlacementValid(bot.level, Buildings.OAK_BRIDGE, o, bot.name, rot, diag, false, true)) continue;
                            bestScore = score; bestO = o; bestR = rot; bestD = diag;
                        }
                    }
                }
            }
        }
        if (bestO == null) { give("no valid spot at step " + t0 + ": giving up"); return; }
        List<Mob> builders = bot.economy.borrow(in, bestO, 2);
        if (builders.isEmpty()) return;
        BuildingPlacement p = BuildingServerEvents.placeBuilding(Buildings.OAK_BRIDGE, bestO, bestR, bot.name,
                Orders.ids(builders), false, bestD, false, true);
        if (p == null) {
            // refused (wood spent elsewhere this tick, a unit standing in the way...): try another spot soon
            for (Mob m : builders) bot.economy.setJob(m, Economy.Job.IDLE);
            refused.add(bestO);
            if (refused.size() >= 5) give("placement refused 5 times: giving up");
            else retryAt = bot.now + 100;
            return;
        }
        current = p; currentSince = bot.now; segments++;
        bot.log("bridge segment " + segments + " at " + bestO.toShortString() + " covers " + bestScore + " of " + todo.size());
    }

    /** how many of the liquid line cells this placement's blocks would stand on */
    private int coverage(ArrayList<BuildingBlock> rel, BlockPos o, Rotation rot, List<BlockPos> todo) {
        ArrayList<BuildingBlock> abs = BuildingUtils.getAbsoluteBlockData(rel, bot.level, o, rot);
        Set<Long> cols = new HashSet<>();
        for (BuildingBlock bb : abs) { BlockPos p = bb.getBlockPos(); cols.add(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL)); }
        int n = 0;
        for (BlockPos c : todo) if (cols.contains(((long) c.getX() << 32) ^ (c.getZ() & 0xffffffffL))) n++;
        return n;
    }
}
