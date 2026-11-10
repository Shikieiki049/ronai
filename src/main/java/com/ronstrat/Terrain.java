package com.ronstrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Long-range route finding on the surface height map. Minecraft's own pathfinder only searches a
 * small area around the unit, so on maps with plateaus and ramps (bases on high ground) it gives up
 * at the foot of the cliff. A human player clicks a waypoint at the ramp; this finds that ramp.
 * Moves: climb at most 1 block per step, drop at most 3, no water or lava.
 */
public final class Terrain {
    private Terrain() {}

    private static final int MAX_NODES = 250_000;
    private static final Map<Long, Cached> CACHE = new HashMap<>();

    private record Cached(List<BlockPos> route, long tick) {}

    /**
     * Cells the army failed to get through although the height map says they are walkable (arches,
     * ruins, slabs, building roofs): column key -> tick until which the route search avoids them.
     */
    private static final Map<Long, Long> BLOCKED = new HashMap<>();
    private static long nowTick;

    private static long col(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }

    public static void block(int x, int z, long until) {
        BLOCKED.put(col(x, z), until);
    }

    public static void blockDone(long now) {
        BLOCKED.values().removeIf(t -> t <= now);
        CACHE.clear();
    }

    private static int h(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** cells the route may cross only at extra cost (units swim, slowly) */
    private static final ThreadLocal<java.util.BitSet> WATER = ThreadLocal.withInitial(java.util.BitSet::new);
    private static final ThreadLocal<java.util.BitSet> LAVA = ThreadLocal.withInitial(java.util.BitSet::new);
    /** set while planning a bridge: lava cells become crossable (at a high price) */
    private static final ThreadLocal<Boolean> ALLOW_LAVA = ThreadLocal.withInitial(() -> false);

    /** fences, walls and gates stick out of the height map like a 1-block step, but no unit can jump them */
    private static boolean fenced(ServerLevel level, int x, int y, int z) {
        var s = level.getBlockState(new BlockPos(x, y - 1, z));
        return s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS) || s.is(net.minecraft.tags.BlockTags.FENCE_GATES);
    }

    private static int fluid(ServerLevel level, int x, int y, int z) {
        var f = level.getFluidState(new BlockPos(x, y - 1, z));
        return f.is(FluidTags.LAVA) ? 2 : f.is(FluidTags.WATER) ? 1 : 0;
    }

    /**
     * Surface route from one point to (near) another, as a list of surface positions every few
     * blocks; null if there is none.
     */
    public static List<BlockPos> route(ServerLevel level, BlockPos from, BlockPos to, long now) {
        long key = (((long) (from.getX() >> 3) & 0xFFFF) << 48) | (((long) (from.getZ() >> 3) & 0xFFFF) << 32)
                | (((long) (to.getX() >> 2) & 0xFFFF) << 16) | ((long) (to.getZ() >> 2) & 0xFFFF);
        nowTick = Math.max(nowTick, now);
        Cached c = CACHE.get(key);
        if (c != null && now - c.tick < 1200) return c.route;
        if (CACHE.size() > 2000) CACHE.clear();
        List<BlockPos> r = search(level, from, to);
        CACHE.put(key, new Cached(r, now));
        return r;
    }

    /**
     * Route for planning bridges: water and lava both crossable at a price. Not cached and not for
     * walking (it may lead straight through lava); call rarely.
     */
    public static List<BlockPos> bridgeRoute(ServerLevel level, BlockPos from, BlockPos to) {
        ALLOW_LAVA.set(true);
        try { return search(level, from, to); } finally { ALLOW_LAVA.set(false); }
    }

    /** 0 dry, 1 water, 2 lava under this surface cell */
    public static int liquidAt(ServerLevel level, BlockPos surface) {
        return fluid(level, surface.getX(), surface.getY(), surface.getZ());
    }

    /** the ground changed (a bridge was finished): forget old routes */
    public static void clearCache() { CACHE.clear(); }

    private static List<BlockPos> search(ServerLevel level, BlockPos from, BlockPos to) {
        var border = level.getWorldBorder();
        int minX = (int) Math.ceil(border.getMinX()) + 1, maxX = (int) Math.floor(border.getMaxX()) - 2;
        int minZ = (int) Math.ceil(border.getMinZ()) + 1, maxZ = (int) Math.floor(border.getMaxZ()) - 2;
        if ((long) (maxX - minX) * (maxZ - minZ) > 1_500_000L) {
            // huge world: restrict to a box around the trip
            int pad = 96;
            minX = Math.max(minX, Math.min(from.getX(), to.getX()) - pad);
            maxX = Math.min(maxX, Math.max(from.getX(), to.getX()) + pad);
            minZ = Math.max(minZ, Math.min(from.getZ(), to.getZ()) - pad);
            maxZ = Math.min(maxZ, Math.max(from.getZ(), to.getZ()) + pad);
        }
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        if (w <= 0 || d <= 0) return null;
        int sx = clamp(from.getX(), minX, maxX) - minX, sz = clamp(from.getZ(), minZ, maxZ) - minZ;
        int gx = to.getX(), gz = to.getZ(), gy = to.getY();

        int n = w * d;
        WATER.get().clear();
        LAVA.get().clear();
        int[] hs = new int[n];
        Arrays.fill(hs, Integer.MIN_VALUE);
        float[] g = new float[n];
        Arrays.fill(g, Float.MAX_VALUE);
        int[] parent = new int[n];
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        int start = sz * w + sx;
        g[start] = 0;
        parent[start] = -1;
        open.add(new long[]{0, start});
        int best = start;
        double bestH = Double.MAX_VALUE;
        int expanded = 0;
        int goalCell = -1;
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
        while (!open.isEmpty() && expanded < MAX_NODES) {
            long[] top = open.poll();
            int cur = (int) top[1];
            float cg = g[cur];
            if (top[0] > (long) ((cg + heur(cur, w, minX, minZ, gx, gz)) * 16) + 1) continue; // stale
            expanded++;
            int cx = cur % w, cz = cur / w;
            int ch = height(level, hs, cur, cx + minX, cz + minZ);
            if (ch == Integer.MAX_VALUE) ch = h(level, cx + minX, cz + minZ); // the start itself may be a blocked cell
            double hh = heur(cur, w, minX, minZ, gx, gz);
            if (hh < bestH && Math.abs(ch - gy) <= 4) { bestH = hh; best = cur; }
            if (hh <= 6 && Math.abs(ch - gy) <= 4) { goalCell = cur; break; }
            for (int k = 0; k < 8; k++) {
                int nx = cx + dx[k], nz = cz + dz[k];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                int ni = nz * w + nx;
                int nh = height(level, hs, ni, nx + minX, nz + minZ);
                if (nh == Integer.MAX_VALUE) continue;
                int dh = nh - ch;
                if (dh > 1 || dh < -3) continue;
                if (k >= 4) {
                    // diagonal: both side cells must be passable from here too
                    int a = cz * w + nx, b = nz * w + cx;
                    int ha = height(level, hs, a, nx + minX, cz + minZ), hb = height(level, hs, b, cx + minX, nz + minZ);
                    if (ha == Integer.MAX_VALUE || hb == Integer.MAX_VALUE || ha - ch > 1 || hb - ch > 1) continue;
                }
                float ng = cg + (k >= 4 ? 1.414f : 1f) + (dh != 0 ? 0.3f : 0f) + (WATER.get().get(ni) ? 4f : 0f) + (LAVA.get().get(ni) ? 25f : 0f);
                if (ng < g[ni]) {
                    g[ni] = ng;
                    parent[ni] = cur;
                    open.add(new long[]{(long) ((ng + heur(ni, w, minX, minZ, gx, gz)) * 16), ni});
                }
            }
        }
        int end = goalCell >= 0 ? goalCell : best;
        if (end == start) return null;
        List<BlockPos> rev = new ArrayList<>();
        for (int i = end; i >= 0; i = parent[i]) {
            rev.add(new BlockPos(i % w + minX, hs[i], i / w + minZ));
            if (i == start) break;
        }
        Collections.reverse(rev);
        return rev;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double heur(int i, int w, int minX, int minZ, int gx, int gz) {
        int x = i % w + minX, z = i / w + minZ;
        return Math.sqrt((double) (x - gx) * (x - gx) + (double) (z - gz) * (z - gz));
    }

    private static int height(ServerLevel level, int[] hs, int i, int x, int z) {
        int v = hs[i];
        if (v != Integer.MIN_VALUE) return v;
        int y = h(level, x, z);
        int fl = fluid(level, x, y, z);
        if (fl == 1) WATER.get().set(i);
        if (fl == 2 && ALLOW_LAVA.get()) LAVA.get().set(i);
        v = (fl == 2 && !ALLOW_LAVA.get()) || fenced(level, x, y, z) ? Integer.MAX_VALUE : y;
        if (!BLOCKED.isEmpty() && BLOCKED.getOrDefault(col(x, z), 0L) > nowTick) v = Integer.MAX_VALUE;
        hs[i] = v;
        return v;
    }

    /** the point about `ahead` blocks along the route from the position nearest to `at` */
    public static BlockPos ahead(List<BlockPos> route, net.minecraft.world.phys.Vec3 at, int ahead) {
        int nearest = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            BlockPos p = route.get(i);
            double dd = (p.getX() + 0.5 - at.x) * (p.getX() + 0.5 - at.x) + (p.getZ() + 0.5 - at.z) * (p.getZ() + 0.5 - at.z);
            if (dd < bd) { bd = dd; nearest = i; }
        }
        return route.get(Math.min(route.size() - 1, nearest + ahead));
    }

    /** whether the route actually ends near the goal (and not at the foot of a cliff) */
    public static boolean reaches(List<BlockPos> route, BlockPos goal) {
        if (route == null || route.isEmpty()) return false;
        BlockPos e = route.get(route.size() - 1);
        double dx = e.getX() - goal.getX(), dz = e.getZ() - goal.getZ();
        return dx * dx + dz * dz <= 7 * 7 && Math.abs(e.getY() - goal.getY()) <= 4;
    }
}
