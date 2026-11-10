package com.ronstrat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A symmetric test map: every start gets the same woodlines, ore, hunt and berries, rotated around
 * the centre, plus a contested middle. Used for self-play evaluation and for watching bots play.
 */
public final class Arena {
    private Arena() {}

    /** what the last generated arena contained, for the result log */
    public static int trees, oreBlocks, berries, animals;

    public static String summary(int players) {
        return String.format("radius=%d trees=%d ore=%d berries=%d animals=%d (per player: trees=%d ore=%d)",
                radiusFor(players), trees, oreBlocks, berries, animals, trees / Math.max(1, players), oreBlocks / Math.max(1, players));
    }

    public static int radiusFor(int players) {
        return Math.max(60, 14 * players);
    }

    public static List<BlockPos> build(ServerLevel level, BlockPos centre, int players, long seed) {
        int r = radiusFor(players);
        int half = r + 52;
        trees = 0; oreBlocks = 0; berries = 0; animals = 0;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.getX(), centre.getZ());
        Random rnd = new Random(seed);

        for (int cx = (centre.getX() - half) >> 4; cx <= (centre.getX() + half) >> 4; cx++)
            for (int cz = (centre.getZ() - half) >> 4; cz <= (centre.getZ() + half) >> 4; cz++)
                level.setChunkForced(cx, cz, true);

        // flatten
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = centre.getX() - half; x <= centre.getX() + half; x++) {
            for (int z = centre.getZ() - half; z <= centre.getZ() + half; z++) {
                for (int yy = y - 4; yy < y - 1; yy++) level.setBlock(m.set(x, yy, z), dirt, 2);
                level.setBlock(m.set(x, y - 1, z), grass, 2);
                for (int yy = y; yy < y + 24; yy++) {
                    if (!level.getBlockState(m.set(x, yy, z)).isAir()) level.setBlock(m, air, 2);
                }
            }
        }
        // clear any loose entities (items, old animals)
        level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(centre).inflate(half, 30, half),
                e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());

        List<BlockPos> starts = new ArrayList<>();
        double rot = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < players; i++) {
            double a = rot + 2 * Math.PI * i / players;
            double ux = -Math.cos(a), uz = -Math.sin(a);   // toward the centre
            double vx = -uz, vz = ux;                       // to the side
            BlockPos s = new BlockPos(centre.getX() + (int) Math.round(Math.cos(a) * r), y, centre.getZ() + (int) Math.round(Math.sin(a) * r));
            starts.add(s);
            Random lr = new Random(seed * 31 + 7); // same layout for every player
            // a real forest behind the base (RON maps are mostly forest) and a smaller grove to one side
            forest(level, s, -ux, -uz, vx, vz, 17, 40, 30, lr);
            forest(level, s, vx, vz, ux, uz, 18, 20, 15, lr);
            forest(level, s, -vx, -vz, ux, uz, 24, 14, 12, lr);
            // ore: a main patch and a smaller one
            ore(level, at(s, -vx * 15 + ux * 2, -vz * 15 + uz * 2), 3, 2, lr);
            ore(level, at(s, -vx * 12 - ux * 12, -vz * 12 - uz * 12), 2, 2, lr);
            // berries
            for (int k = 0; k < 6; k++) {
                BlockPos b = at(s, ux * 11 + vx * (k - 3) * 1.5, uz * 11 + vz * (k - 3) * 1.5);
                berries++;
                level.setBlock(b, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3), 2);
            }
            // hunt
            for (int k = 0; k < 8; k++) {
                BlockPos b = at(s, ux * 9 - vx * 8 + (k % 3), uz * 9 - vz * 8 + (k / 3));
                EntityType<? extends Mob> t = k % 2 == 0 ? EntityType.COW : EntityType.PIG;
                Mob animal = t.create(level);
                if (animal != null) {
                    animal.moveTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 0, 0);
                    animal.finalizeSpawn(level, level.getCurrentDifficultyAt(b), MobSpawnType.SPAWNER, null, null);
                    level.addFreshEntity(animal);
                    animals++;
                }
            }
        }
        // contested middle
        Random cr = new Random(seed * 17 + 3);
        ore(level, centre, 4, 3, cr);
        for (int i = 0; i < players; i++) {
            double a = rot + 2 * Math.PI * (i + 0.5) / players;
            BlockPos p = new BlockPos(centre.getX() + (int) (Math.cos(a) * r * 0.55), y, centre.getZ() + (int) (Math.sin(a) * r * 0.55));
            forest(level, p, Math.cos(a), Math.sin(a), -Math.sin(a), Math.cos(a), -6, 14, 12, cr);
        }
        return starts;
    }

    /** a block of trees, `width` blocks across and `depth` blocks deep, starting `dist` blocks out */
    private static void forest(ServerLevel level, BlockPos s, double fx, double fz, double sx, double sz,
                               int dist, int width, int depth, Random r) {
        for (int d = 0; d < depth; d += 3) {
            for (int w = -width / 2; w <= width / 2; w += 3) {
                double jitterA = r.nextInt(2), jitterD = r.nextInt(2);
                BlockPos base = at(s, fx * (dist + d + jitterD) + sx * (w + jitterA), fz * (dist + d + jitterD) + sz * (w + jitterA));
                tree(level, base, 5 + r.nextInt(2));
            }
        }
    }

    private static BlockPos at(BlockPos s, double dx, double dz) {
        return new BlockPos(s.getX() + (int) Math.round(dx), s.getY(), s.getZ() + (int) Math.round(dz));
    }

    private static void woodline(ServerLevel level, BlockPos s, double fx, double fz, double sx, double sz,
                                 int dist, int trees, int width, Random r) {
        for (int i = 0; i < trees; i++) {
            double along = (i - trees / 2.0) * 3.0;
            double depth = dist + (i % 2) * 3 + r.nextInt(2);
            BlockPos base = at(s, fx * depth + sx * along, fz * depth + sz * along);
            tree(level, base, 4 + r.nextInt(2));
        }
    }

    private static void tree(ServerLevel level, BlockPos base, int h) {
        trees++;
        BlockState log = Blocks.OAK_LOG.defaultBlockState(), leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        for (int dy = h - 2; dy <= h + 1; dy++) {
            int rr = dy >= h ? 1 : 2;
            for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
                if (Math.abs(dx) == rr && Math.abs(dz) == rr && rr == 2) continue;
                level.setBlock(base.offset(dx, dy, dz), leaves, 2);
            }
        }
        for (int dy = 0; dy < h; dy++) level.setBlock(base.above(dy), log, 2);
    }

    private static void ore(ServerLevel level, BlockPos c, int rad, int height, Random r) {
        for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
            if (dx * dx + dz * dz > rad * rad + 1) continue;
            for (int dy = 0; dy < height; dy++) {
                BlockState s = (dx + dz + dy) % 3 == 0 ? Blocks.COAL_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
                level.setBlock(c.offset(dx, dy, dz), s, 2);
                oreBlocks++;
            }
        }
    }

    public static void unforce(ServerLevel level, BlockPos centre, int players) {
        int half = radiusFor(players) + 52;
        for (int cx = (centre.getX() - half) >> 4; cx <= (centre.getX() + half) >> 4; cx++)
            for (int cz = (centre.getZ() - half) >> 4; cz <= (centre.getZ() + half) >> 4; cz++)
                level.setChunkForced(cx, cz, false);
    }

    static ChunkPos chunk(BlockPos p) { return new ChunkPos(p); }
}
