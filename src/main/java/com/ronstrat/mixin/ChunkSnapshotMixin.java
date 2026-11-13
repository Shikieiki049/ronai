package com.ronstrat.mixin;

import com.solegendary.reignofnether.unit.pathfinding.ChunkSnapshot;
import com.solegendary.reignofnether.unit.pathfinding.WalkabilityGridChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Speed only, same answers: RON's path search looks up the grid chunk of every cell it visits in a hash
 * map (31% of a self-play server's CPU in a profile). Neighbouring cells nearly always share the chunk,
 * so the last chunk found is kept. The snapshot's map never changes after capture, so the cache cannot
 * go stale.
 */
@Mixin(value = ChunkSnapshot.class, remap = false)
public abstract class ChunkSnapshotMixin {
    @Shadow @Final private Long2ObjectMap<WalkabilityGridChunk> chunks;

    @Unique private long ronstrat$lastKey = Long.MIN_VALUE;
    @Unique private WalkabilityGridChunk ronstrat$lastChunk;

    /**
     * @author ronstrat
     * @reason a one-entry cache in front of the chunk map lookup (a snapshot is captured, searched and
     * dropped on one thread - PathfinderWorkerPool, RtsPathfinder - so plain fields are enough)
     */
    @Overwrite
    private WalkabilityGridChunk chunkAt(int wx, int wz) {
        long key = (long) (wx >> 4) & 4294967295L | ((long) (wz >> 4) & 4294967295L) << 32;
        if (key == ronstrat$lastKey) return ronstrat$lastChunk;
        WalkabilityGridChunk c = chunks.get(key);
        ronstrat$lastChunk = c;
        ronstrat$lastKey = key;
        return c;
    }
}
