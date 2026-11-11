package com.ronstrat;

import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.List;

/** A bot-only match on a generated arena: used by the self-play harness and by /ronstrat arena. */
public final class Match {
    public final MatchSpec spec;
    public final List<List<Agent>> teams = new ArrayList<>();
    public final long seed;
    public BlockPos centre;
    public long startTick;

    /** start positions of a real map (RON start blocks); null = generate an arena */
    public List<BlockPos> fixedSeats;

    /**
     * Use the start positions saved in a RON map. The lone side of a 1vN takes a seat at random
     * (by seed), so no single start position decides the statistics.
     */
    public void useMapSeats(ServerLevel level) {
        List<BlockPos> all = new ArrayList<>();
        for (var sp : com.solegendary.reignofnether.startpos.StartPosServerEvents.startPoses)
            if (sp.enabled) all.add(sp.pos); // the start block itself, like a human's start
        if (all.size() < spec.players())
            throw new IllegalStateException("map has " + all.size() + " start positions, match needs " + spec.players());
        // java.util.Random gives neighbouring small seeds the same first draws (seeds 101..105 all dealt
        // the same seats), so the seed is scrambled first
        long mixed = (seed ^ (seed >>> 33)) * 0xff51afd7ed558ccdL;
        mixed = (mixed ^ (mixed >>> 33)) * 0xc4ceb9fe1a85ec53L;
        java.util.Collections.shuffle(all, new java.util.Random(mixed ^ (mixed >>> 33)));
        fixedSeats = new ArrayList<>(all.subList(0, spec.players()));
        // a headless server has no player cameras loading chunks: keep the whole playable map loaded,
        // otherwise armies freeze at the edge of the loaded area (a real game never has that problem)
        var border = level.getWorldBorder();
        int x0 = (int) Math.floor(border.getMinX()) >> 4, x1 = (int) Math.floor(border.getMaxX()) >> 4;
        int z0 = (int) Math.floor(border.getMinZ()) >> 4, z1 = (int) Math.floor(border.getMaxZ()) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) <= 4096) {
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) level.setChunkForced(cx, cz, true);
        } else {
            for (BlockPos p : fixedSeats)
                for (int dx = -10; dx <= 10; dx++)
                    for (int dz = -10; dz <= 10; dz++) level.setChunkForced((p.getX() >> 4) + dx, (p.getZ() >> 4) + dz, true);
        }
    }

    public Match(MatchSpec spec, long seed) {
        this.spec = spec;
        this.seed = seed;
    }

    public void start(ServerLevel level, BlockPos centre) {
        this.centre = centre;
        int n = spec.players();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setDayTime(1000);
        if (spec.fog) com.solegendary.reignofnether.fogofwar.FogOfWarServerEvents.setEnabled(true);
        List<BlockPos> seats = fixedSeats != null ? fixedSeats : Arena.build(level, centre, n, seed);
        int seat = 0;
        int idx = 0;
        List<int[]> handicaps = new ArrayList<>(); // {team, index in team, levels}
        for (List<MatchSpec.Player> team : spec.teams) {
            List<Agent> bots = new ArrayList<>();
            for (MatchSpec.Player pl : team) {
                Difficulty d = pl.difficulty();
                // RON player names: letters, digits, '-' only. T1-expert-1 stays as it always was.
                String name = (pl.brain().startsWith("ron") ? "Bot-" : "") + "T" + (teams.size() + 1) + "-" + d.key() + (pl.brain().isEmpty() ? "" : "-" + pl.brain()) + "-" + (++idx);
                bots.add(spec.readied
                        ? BotManager.startReadied(level, name, d, seats.get(seat++), spec.ffa ? 100 + idx : teams.size() + 1, pl.brain(), pl.profile())
                        : BotManager.start(level, name, d, seats.get(seat++), pl.brain(), pl.profile()));
                if (pl.handicap() > 0) handicaps.add(new int[] { teams.size(), bots.size() - 1, pl.handicap() });
            }
            teams.add(bots);
        }
        // per-player handicap levels, the same bonus the lobby setting gives
        for (int[] h : handicaps) {
            Agent a = teams.get(h[0]).get(h[1]);
            BotManager.applyHandicap(level, a.name(), a.startPos(), h[2]);
        }
        if (!spec.ffa) {
            for (List<Agent> t : teams)
                for (int i = 0; i < t.size(); i++)
                    for (int j = i + 1; j < t.size(); j++) BotManager.ally(t.get(i).name(), t.get(j).name());
            // 1vN: the lone player gets the handicap (2 villagers and +0.5x start resources per extra enemy)
            if (spec.handicap && teams.size() == 2) {
                for (int s = 0; s < 2; s++) {
                    List<Agent> lone = teams.get(s), other = teams.get(1 - s);
                    if (lone.size() == 1 && other.size() > 1)
                        BotManager.applyHandicap(level, lone.get(0).name(), lone.get(0).startPos(), other.size() - 1);
                }
            }
        }
        startTick = PlayerServerEvents.rtsGameTicks;
    }

    public List<Integer> aliveTeams() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            for (Agent b : teams.get(i)) if (BotManager.isRtsName(b.name())) { out.add(i); break; }
        }
        return out;
    }

    /** match is decided when one team (or, in FFA, one player) is left */
    public boolean decided() {
        if (spec.ffa) {
            int alive = 0;
            for (List<Agent> t : teams) for (Agent b : t) if (BotManager.isRtsName(b.name())) alive++;
            return alive <= 1;
        }
        return aliveTeams().size() <= 1;
    }

    /**
     * The team that has clearly won although the loser is not eliminated yet: every other team has no
     * army and the leader out-scores all of them together by the given ratio. -1 if none.
     */
    public int dominantTeam(double ratio) {
        List<Integer> alive = aliveTeams();
        if (alive.size() < 2) return -1;
        int best = -1;
        double bestS = -1, rest = 0;
        for (int i : alive) {
            double s = teamScore(i);
            rest += s;
            if (s > bestS) { bestS = s; best = i; }
        }
        rest -= bestS;
        if (bestS < ratio * Math.max(1, rest)) return -1;
        for (int i : alive) {
            if (i == best) continue;
            for (Agent b : teams.get(i)) if (BotManager.isRtsName(b.name()) && b.stat("army") > 0) return -1;
            // a big team is naturally "5x" a single player; it must actually be collapsing
            if (teamScore(i) > 0.4 * peak[i]) return -1;
        }
        return best;
    }

    /**
     * Early verdict for two equal teams: the team that has at least ratio times the other's units
     * (villagers and soldiers) and no smaller army, with five soldiers or more. -1 if none.
     * Back-tested on 56 mirror games: 2:1 held for 90 s was never overturned.
     */
    public int popLeader(double ratio) {
        if (spec.ffa || teams.size() != 2 || teams.get(0).size() != teams.get(1).size()) return -1;
        java.util.Map<String, Integer> side = new java.util.HashMap<>();
        for (int i = 0; i < 2; i++) for (Agent b : teams.get(i)) side.put(b.name(), i);
        int[] pop = new int[2], army = new int[2];
        for (var e : new ArrayList<>(com.solegendary.reignofnether.unit.UnitServerEvents.getAllUnits())) {
            if (!(e instanceof com.solegendary.reignofnether.unit.interfaces.Unit u) || !e.isAlive()) continue;
            Integer s = side.get(u.getOwnerName());
            if (s == null) continue;
            pop[s]++;
            if (!(e instanceof com.solegendary.reignofnether.unit.interfaces.WorkerUnit) && !u.isScout()
                    && !(e instanceof com.solegendary.reignofnether.unit.units.villagers.MilitiaUnit)) army[s]++;
        }
        for (int s = 0; s < 2; s++)
            if (pop[s] >= ratio * Math.max(1, pop[1 - s]) && army[s] >= army[1 - s] && army[s] >= 5) return s;
        return -1;
    }

    private double[] peak = new double[0];

    /** call regularly so collapse can be judged against each team's own best */
    public void trackPeaks() {
        if (peak.length != teams.size()) peak = new double[teams.size()];
        for (int i = 0; i < teams.size(); i++) peak[i] = Math.max(peak[i], teamScore(i));
    }

    /** used on timeout: total score of a team (army and economy still standing) */
    public double teamScore(int i) {
        double s = 0;
        for (Agent b : teams.get(i)) {
            if (!BotManager.isRtsName(b.name())) continue;
            s += b.stat("pop") + 3 * b.stat("buildings");
        }
        return s;
    }
}
