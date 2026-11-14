package com.ronstrat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/** A player that does nothing (brain "idle"): the owner of an army in the arena test. */
public final class IdleAgent implements Agent {
    private final String name;
    private final Difficulty difficulty;
    private final BlockPos startPos;

    public IdleAgent(String name, Difficulty d, BlockPos pos) { this.name = name; this.difficulty = d; this.startPos = pos; }

    @Override public String name() { return name; }
    @Override public Difficulty difficulty() { return difficulty; }
    @Override public BlockPos startPos() { return startPos; }
    @Override public String brain() { return "idle"; }
    @Override public String profile() { return ""; }
    @Override public void tick(long serverTick) { }
    @Override public String status() { return name + " [idle]"; }
    @Override public String workerDump() { return ""; }
    @Override public boolean verbose() { return false; }
    @Override public void setVerbose(boolean v) { }
    @Override public void onUnitDied(LivingEntity dead, DamageSource src, Entity killer) { }
    @Override public void onKill() { }
    @Override public double stat(String key) { return 0; }
    @Override public String info(String key) { return ""; }
    @Override public BlockPos home() { return startPos; }
    @Override public List<Mob> workers() { return List.of(); }
}
