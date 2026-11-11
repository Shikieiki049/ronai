package com.ronstrat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * One computer player as the rest of the mod sees it. The current brain is {@link Bot}; the self-play
 * harness can also load a frozen copy of an older brain (package com.ronstrat.&lt;brain&gt;) so that two
 * versions play each other in the same game.
 */
public interface Agent {
    String name();
    Difficulty difficulty();
    BlockPos startPos();
    /** "" = the current brain, otherwise the package suffix of a frozen one ("base") */
    String brain();
    /** "" = plain difficulty defaults, otherwise the name of a parameter profile */
    String profile();

    void tick(long serverTick);

    String status();
    String workerDump();
    boolean verbose();
    void setVerbose(boolean v);

    /** one of this player's units died */
    void onUnitDied(LivingEntity dead, DamageSource src, Entity killer);
    /** one of this player's units killed a unit of another player */
    void onKill();

    /** numbers for the harness: peakWorkers, peakArmy, attacks, retreats, buildings, pop, army, workers, deaths */
    double stat(String key);
    /** short texts for the harness: mode, target */
    String info(String key);
    BlockPos home();
    List<Mob> workers();
}
