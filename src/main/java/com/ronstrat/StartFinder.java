package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.resources.ResourceIndex;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a fair start position on a real map: dry, flat ground, wood and ore in reach,
 * and far enough from every other player.
 */
public final class StartFinder {
    private StartFinder() {}

    public static BlockPos find(ServerLevel level, BlockPos around, int minDist, int maxDist, List<BlockPos> taken) {
        List<BlockPos> others = new ArrayList<>(taken);
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) if (!b.ownerName.isEmpty()) others.add(b.centrePos);
        for (LivingEntity e : UnitServerEvents.getAllUnits()) others.add(e.blockPosition());
        BlockPos best = null;
        double bestScore = -1e9;
        for (int r = minDist; r <= maxDist; r += 12) {
            for (int i = 0; i < 24; i++) {
                double a = 2 * Math.PI * i / 24 + r * 0.013;
                int x = around.getX() + (int) (Math.cos(a) * r), z = around.getZ() + (int) (Math.sin(a) * r);
                if (!level.getWorldBorder().isWithinBounds(x, z)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                if (!level.getFluidState(p.below()).isEmpty() || level.getFluidState(p).is(Fluids.WATER)) continue;
                double nearest = 1e9;
                for (BlockPos o : others) nearest = Math.min(nearest, Math.sqrt(o.distSqr(p)));
                if (nearest < minDist * 0.8) continue;
                double score = flatness(level, x, z, y) * -3;
                score += ResourceIndex.get(level).findClosest(level, p, 22, ResourceName.WOOD, q -> true).isPresent() ? 30 : 0;
                score += ResourceIndex.get(level).findClosest(level, p, 26, ResourceName.ORE, q -> q.getY() >= y - 6).isPresent() ? 25 : 0;
                score -= Math.abs(r - (minDist + maxDist) / 2.0) * 0.1;
                if (score > bestScore) { bestScore = score; best = p; }
            }
        }
        return best;
    }

    private static int flatness(ServerLevel level, int x, int z, int y) {
        int dev = 0;
        for (int dx = -8; dx <= 8; dx += 4)
            for (int dz = -8; dz <= 8; dz += 4)
                dev += Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) - y);
        return dev;
    }
}
