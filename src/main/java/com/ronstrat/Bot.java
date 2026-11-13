package com.ronstrat;

import java.util.ArrayList;
import java.util.List;

import com.solegendary.reignofnether.faction.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/** One computer player: the managers plus the shared clock. */
public final class Bot implements Agent {
    public final String name;
    public final Difficulty difficulty;
    public final BotParams params;
    /** parameter profile ("" = the difficulty's defaults) */
    public final String profile;
    public final FactionPlan plan;
    public final BlockPos startPos;
    public final ServerLevel level;
    public final Intel intel = new Intel(this);
    /** the opening route Strategy chose (-1: not yet) */
    int chosenRoute = -1;
    /** the enemy fields mostly melee: witches were brought in (Strategy, parameter adaptWitches) */
    boolean adaptedWitches;
    /** the opponent's style as Strategy read it (-1 unknown) */
    int enemyStyle = -1;
    long styleCheckedAt = -1000;
    long meleeCheckedAt = -1000;
    public final Economy economy = new Economy(this);
    public final Builder builder = new Builder(this);
    public final Production production = new Production(this);
    public final Army army = new Army(this);
    public final Trader trader = new Trader(this);
    public final Bridger bridger = new Bridger(this);
    final Upgrades upgrades = new Upgrades(this);
    final Casters casters = new Casters(this);
    final Ledger ledger = new Ledger(this);
    final Random random;
    public long now;
    private long nextThink, nextMicro;
    /** decision log: on by default in real games (so a match can be looked at afterwards), test runs choose */
    public boolean verbose = Boolean.getBoolean("ronstrat.verbose") || !AutoTest.enabled();
    public int peakWorkers, peakArmy, deaths, hazardDeaths;

    public Bot(String name, Difficulty d, Faction faction, BlockPos startPos, ServerLevel level, long seed, String profile) {
        this.name = name;
        this.difficulty = d;
        this.profile = profile == null ? "" : profile;
        this.params = BotParams.of(d, this.profile);
        this.plan = FactionPlan.forFaction(faction);
        this.startPos = startPos;
        this.level = level;
        this.random = new Random(seed ^ name.hashCode());
        // stagger bots so they do not all think on the same tick
        this.nextThink = Math.floorMod(name.hashCode(), 10);
    }

    /**
     * Facing three or more live enemies (1vN): play the defensive boom AoE2 players use against
     * several opponents - fewer villagers, army and towers first, fight every wave at home.
     */
    public boolean outnumbered() {
        if (difficulty.ordinal() < Difficulty.HARD.ordinal()) return false;
        // outnumbered = facing a team much bigger than ours. A free-for-all (everyone on their own) is
        // not: there every enemy is also busy with the others.
        List<String> foes = new ArrayList<>();
        for (Intel.EnemyMemory m : intel.enemies.values()) if (m.alive) foes.add(m.name);
        if (foes.size() < 3) return false;
        int biggest = 0;
        for (String a : foes) {
            int team = 0;
            for (String b : foes) if (a.equals(b) || com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(a, b)) team++;
            biggest = Math.max(biggest, team);
        }
        int own = 1;
        for (String al : intel.allies) if (!al.equals(name)) own++;
        return biggest >= 3 && biggest > own;
    }

    /** the lower tiers miss things now and then, the way a busy human does */
    boolean attentive() {
        return random.nextDouble() < params.get("attentionChance");
    }

    // ------------------------------------------------------------------ Agent

    /** package suffix of this brain: "" for the current one; tools/freeze.ps1 sets it in a frozen copy */
    static final String BRAIN = "";

    /** the players run by this same brain (a frozen older brain only teams up with its own kind) */
    static List<Bot> peers() {
        List<Bot> out = new ArrayList<>();
        for (Agent a : BotManager.bots()) if (a instanceof Bot b) out.add(b);
        return out;
    }

    /** a building that found no place to stand (Builder) - a map analysis measure */
    int placeFails;

    @Override public String name() { return name; }
    @Override public Difficulty difficulty() { return difficulty; }
    @Override public BlockPos startPos() { return startPos; }
    @Override public String brain() { return BRAIN; }
    @Override public String profile() { return profile; }
    @Override public boolean verbose() { return verbose; }
    @Override public void setVerbose(boolean v) { verbose = v; }
    @Override public BlockPos home() { return intel.home(); }
    @Override public List<net.minecraft.world.entity.Mob> workers() { return intel.workers; }

    @Override
    public double stat(String key) {
        switch (key) {
            case "peakWorkers": return peakWorkers;
            case "peakArmy": return peakArmy;
            case "attacks": return army.attacksLaunched;
            case "retreats": return army.retreats;
            case "buildings": return intel.own.size();
            case "pop": return intel.pop;
            case "army": return intel.army.size();
            case "workers": return intel.workers.size();
            case "deaths": return deaths;
            case "placeFails": return placeFails;
            default: return 0;
        }
    }

    @Override
    public String info(String key) {
        switch (key) {
            case "mode": return String.valueOf(army.mode);
            case "target": return String.valueOf(army.target);
            default: return "";
        }
    }

    /** a unit of ours died: remember what killed it and where (finds map hazards and lost fights) */
    @Override
    public void onUnitDied(net.minecraft.world.entity.LivingEntity dead, net.minecraft.world.damagesource.DamageSource src, net.minecraft.world.entity.Entity killer) {
        boolean byUnit = killer instanceof com.solegendary.reignofnether.unit.interfaces.Unit;
        String killerOwner = byUnit ? ((com.solegendary.reignofnether.unit.interfaces.Unit) killer).getOwnerName() : null;
        String by = killer == null ? "-" : (byUnit ? killerOwner + ":" : "") + net.minecraft.world.entity.EntityType.getKey(killer.getType()).getPath();
        deaths++;
        if (UnitEval.isWorker(dead) && !byUnit) hazardDeaths++;
        if (byUnit && (killerOwner == null || killerOwner.isEmpty())) {
            hazardDeaths++;
            intel.markDanger(dead.blockPosition());
            if (UnitEval.isWorker(dead)) economy.evacuate(dead.blockPosition());
            else army.lostToCamp(dead.blockPosition());
        }
        // a villager shot at its work spot by an enemy raid: that spot is avoided for a while (not at the
        // town centre itself - that would stop every job at home)
        else if (byUnit && UnitEval.isWorker(dead) && params.b("fleeRaiders") && dead.blockPosition().distSqr(intel.home()) > 25 * 25)
            intel.markDanger(dead.blockPosition());
        String msg = "death " + net.minecraft.world.entity.EntityType.getKey(dead.getType()).getPath() + " at " + dead.blockPosition().toShortString()
                + " cause=" + src.getMsgId() + " by=" + by;
        if (byUnit && killerOwner != null && !killerOwner.isEmpty() && !UnitEval.isWorker(dead)) army.lostInAttack(dead.blockPosition());
        if (byUnit) log(msg); else RonStrategist.LOG.info("[{}] {}", name, msg);
    }

    @Override public void onKill() { army.killedInAttack(); }

    @Override
    public void tick(long gameTick) {
        now = gameTick;
        // real games: a status line every minute next to the decisions (the test harness prints its own)
        if (verbose && !AutoTest.enabled() && now % 1200 == 0)
            RonStrategist.LOG.info("[{}|{}] status t={}s {}", name, difficulty.key(),
                    com.solegendary.reignofnether.player.PlayerServerEvents.rtsGameTicks / 20, status());
        if (now >= nextThink) {
            nextThink = now + params.i("thinkTicks");
            intel.refresh(now);
            ledger.think(intel);
            economy.think(intel);
            builder.think(intel);
            bridger.think(intel);
            production.think(intel);
            army.think(intel);
            trader.think(intel);
            upgrades.think(intel);
            casters.think(intel);
            peakWorkers = Math.max(peakWorkers, intel.workers.size());
            peakArmy = Math.max(peakArmy, intel.army.size());
        }
        int mt = params.i("microTicks");
        if (mt > 0 && now >= nextMicro && army.mode != Army.Mode.GATHER) {
            nextMicro = now + mt;
            intel.refresh(now);
            army.micro(intel);
        }
    }

    boolean useStockpilesNow() { return params.b("useStockpiles"); }

    private long lastChat = -100000;

    /** human teammates hear what we are about to do (chat), so they can come along */
    void tellAllies(String msg, boolean urgent) {
        if (!urgent && now - lastChat < 600) return;
        try {
            var players = level.getServer().getPlayerList().getPlayers();
            boolean any = false;
            for (var pl : players) {
                String n = pl.getName().getString();
                if (BotManager.get(n) != null || !com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(name, n)) continue;
                pl.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b<" + name + ">§r " + msg));
                any = true;
            }
            if (any) { lastChat = now; log("chat: " + msg); }
        } catch (Throwable ignored) { }
    }

    void log(String s) {
        if (verbose) RonStrategist.LOG.info("[{}|{}] {}", name, difficulty.key(), s);
    }

    public String status() {
        Intel in = intel;
        var c = economy.counts;
        var rp = com.solegendary.reignofnether.player.PlayerServerEvents.getRTSPlayer(name);
        String harvested = "";
        if (rp != null) {
            var s = rp.scores.scoreList;
            harvested = String.format(" got F%d W%d O%d", s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.FOOD_HARVESTED, 0),
                    s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.WOOD_HARVESTED, 0),
                    s.getOrDefault(com.solegendary.reignofnether.player.RTSPlayerScoresEnum.ORES_HARVESTED, 0));
        }
        return String.format("%s [%s] workers=%d(F%d W%d O%d B%d I%d) army=%d(g%d m%d s%d) pop=%d/%d res F%d W%d O%d%s farms=%d/%d mode=%s target=%s",
                name, difficulty.zh, in.workers.size(), c.get(Economy.Job.FOOD), c.get(Economy.Job.WOOD), c.get(Economy.Job.ORE),
                c.get(Economy.Job.BUILD), c.get(Economy.Job.IDLE), in.army.size(), in.garrisoned.size(), in.militia.size(), in.scouts.size(), in.pop, in.popCap, in.food, in.wood, in.ore,
                harvested, in.built(plan.farm), economy.farmsWanted, army.mode, army.target) + " animals=" + economy.animalsNearHome + " deaths=" + deaths + "/" + hazardDeaths + " unreach=" + economy.unreachableCount + String.format(" threat=%.0f own=%.0f", army.threatPower(in), army.ownPower(in)) + production.tcIdleText() + (production.idleReason.isEmpty() ? "" : " prodIdle=" + production.idleReason);
    }

    /** per-worker dump for debugging the economy */
    public String workerDump() {
        StringBuilder sb = new StringBuilder();
        for (var w : intel.workers) {
            var wu = (com.solegendary.reignofnether.unit.interfaces.WorkerUnit) w;
            var g = wu.getGatherResourceGoal();
            sb.append(String.format("%n    #%d job=%s res=%s tgt=%s farm=%s build=%s idle=%s items=%d nav=%s pos=%s",
                    w.getId(), economy.job(w), g == null ? "-" : g.getTargetResourceName(),
                    g == null || g.getGatherTarget() == null ? "-" : g.getGatherTarget().toShortString(),
                    g != null && g.getTargetFarm() != null, wu.getBuildRepairGoal().getBuildingTarget() != null,
                    com.solegendary.reignofnether.unit.interfaces.WorkerUnit.isIdle(wu),
                    ((com.solegendary.reignofnether.unit.interfaces.Unit) w).getItems().size(),
                    w.getNavigation().isInProgress(), w.blockPosition().toShortString()));
        }
        return sb.toString();
    }
}
