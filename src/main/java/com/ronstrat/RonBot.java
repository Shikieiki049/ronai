package com.ronstrat;

import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.unit.interfaces.WorkerUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/**
 * A seat played by RON's own computer player (com.solegendary.reignofnether.ai, present in some RON
 * builds such as 1.5.0b-2) instead of by this mod. Self-play only: spec brain "ron" = RON's BRUTAL,
 * "ronhard" = HARD, "ronnormal", "roneasy". The seat's name must start with "Bot-" (RON's rule).
 * This agent itself does nothing but report numbers.
 */
public final class RonBot implements Agent {
    private final String name, brain;
    private final Difficulty difficulty;
    private final BlockPos startPos;
    private int peakWorkers, peakArmy, workersNow, armyNow;

    public RonBot(String name, Difficulty d, Faction faction, BlockPos pos, ServerLevel level, long seed, String brain) {
        this.name = name;
        this.difficulty = d;
        this.startPos = pos;
        this.brain = brain;
        String tier = switch (brain) {
            case "ronhard" -> "HARD";
            case "ronnormal" -> "NORMAL";
            case "roneasy" -> "EASY";
            default -> "BRUTAL";
        };
        try {
            Class<?> diff = Class.forName("com.solegendary.reignofnether.ai.BotDifficulty");
            Object dv = diff.getField(tier).get(null);
            Class.forName("com.solegendary.reignofnether.ai.BotServerEvents").getMethod("addBot", String.class, diff).invoke(null, name, dv);
            RonStrategist.LOG.info("RON Strategist: {} is played by RON's own bot ({})", name, tier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("this RON build has no built-in bots (com.solegendary.reignofnether.ai); run with the alternate RON server", e);
        }
    }

    @Override public String name() { return name; }
    @Override public Difficulty difficulty() { return difficulty; }
    @Override public BlockPos startPos() { return startPos; }
    @Override public String brain() { return brain; }
    @Override public String profile() { return ""; }
    @Override public void tick(long serverTick) {
        if (serverTick % 200 != 0) return;
        int w = 0, a = 0;
        for (LivingEntity e : UnitServerEvents.getAllUnits()) {
            if (!(e instanceof Unit u) || !e.isAlive() || !name.equals(u.getOwnerName())) continue;
            if (e instanceof WorkerUnit) w++; else if (!u.isScout()) a++;
        }
        workersNow = w; armyNow = a;
        peakWorkers = Math.max(peakWorkers, w);
        peakArmy = Math.max(peakArmy, a);
    }
    @Override public String status() { return name + " (RON's own bot)"; }
    @Override public String workerDump() { return ""; }
    @Override public boolean verbose() { return false; }
    @Override public void setVerbose(boolean v) {}
    @Override public void onUnitDied(LivingEntity dead, DamageSource src, Entity killer) {}
    @Override public void onKill() {}
    @Override public double stat(String key) {
        return switch (key) {
            case "peakWorkers" -> peakWorkers;
            case "peakArmy" -> peakArmy;
            case "army" -> armyNow;
            case "workers" -> workersNow;
            default -> 0;
        };
    }
    @Override public String info(String key) { return ""; }
    @Override public BlockPos home() { return startPos; }
    @Override public List<Mob> workers() { return new ArrayList<>(); }
}
