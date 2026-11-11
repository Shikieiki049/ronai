package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.unit.interfaces.AttackerUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Military. The structure follows the strong AoE2 scripts: defend the town first, mass at a rally
 * point, commit an attack only with a real strength advantage, pick the most profitable enemy
 * (in 1vN: defeat them one at a time, never fight two at once), fall back when the local fight is
 * lost, and micro (focus fire, kiting, saving wounded expensive units) on the higher tiers.
 */
public final class Army {
    public enum Mode { GATHER, ATTACK, DEFEND, RETREAT }

    private final Bot bot;
    public Mode mode = Mode.GATHER;
    public String target;
    private BlockPos targetPos;
    private long lastAttackEnd = -100000, retreatUntil, lastReissue, lastRegroup = -1000, lastDiag;
    public int timesRaided;
    private boolean underAttack;
    private BlockPos lastThreat;
    private final Map<Integer, Long> lastOrder = new HashMap<>();
    private final List<BlockPos> scoutPoints = new ArrayList<>();
    private int scoutIdx;
    /** likely enemy starts (mirror images of our own) */
    final List<BlockPos> guessed = new ArrayList<>();
    private Mob explorer;
    public int attacksLaunched, retreats;

    /** every bot picks its own timing, like different players would */
    private double firstAttackJitter = -1;

    Army(Bot bot) { this.bot = bot; }

    // ------------------------------------------------------------------ anchors used by the builder

    /** the side of the base away from the enemy (for buildings put up while under attack) */
    public BlockPos rearAnchor(Intel in) {
        BlockPos h = in.home(), f = frontAnchor(in);
        return new BlockPos(2 * h.getX() - f.getX(), h.getY(), 2 * h.getZ() - f.getZ());
    }

    public BlockPos frontAnchor(Intel in) {
        BlockPos h = in.home();
        BlockPos e = enemyDirectionPoint(in);
        if (e == null) return h;
        Vec3 d = Vec3.atCenterOf(e).subtract(Vec3.atCenterOf(h));
        double len = d.length();
        if (len < 1) return h;
        d = d.scale(8 / len);
        return BlockPos.containing(h.getX() + d.x, h.getY(), h.getZ() + d.z);
    }

    public BlockPos threatAnchor(Intel in) {
        if (lastThreat == null) return frontAnchor(in);
        BlockPos h = in.home();
        return new BlockPos((h.getX() * 2 + lastThreat.getX()) / 3, h.getY(), (h.getZ() * 2 + lastThreat.getZ()) / 3);
    }

    private BlockPos enemyDirectionPoint(Intel in) {
        double x = 0, z = 0; int n = 0;
        for (Intel.EnemyMemory m : in.enemies.values()) {
            BlockPos b = m.alive ? m.anyBuilding() : null;
            if (b != null) { x += b.getX(); z += b.getZ(); n++; }
        }
        if (n == 0) return null;
        return new BlockPos((int) (x / n), in.home().getY(), (int) (z / n));
    }

    private BlockPos rally(Intel in) {
        BlockPos h = in.home();
        BlockPos toward = targetPos != null ? targetPos : enemyDirectionPoint(in);
        if (toward == null) return h.offset(6, 0, 6);
        Vec3 d = Vec3.atCenterOf(toward).subtract(Vec3.atCenterOf(h));
        double len = d.length();
        if (len < 1) return h;
        d = d.scale(14 / len);
        return BlockPos.containing(h.getX() + d.x, h.getY(), h.getZ() + d.z);
    }

    // ------------------------------------------------------------------ macro

    private CombatSim.Probe probe;
    private ArmyV2 v2;
    private long lastLostCheck = -1000;
    private boolean v2on() {
        if (bot.difficulty != Difficulty.EXPERT || !bot.params.b("armyV2")) return false;
        if (v2 == null) v2 = new ArmyV2(bot);
        return true;
    }

    void think(Intel in) {
        BotParams p = bot.params;
        if (probe != null && probe.finished(bot.now, null)) probe = null;
        double defendR = p.get("defendRadius");
        if (mode != Mode.ATTACK) curForce = in.army;
        stopFriendlyFire(in);
        upgradeVisits = underAttackNear(in) ? java.util.Set.of() : bot.upgrades.visits(in);
        List<LivingEntity> threats = threats(in, defendR);
        boolean wasUnder = underAttack;
        underAttack = !threats.isEmpty();
        if (underAttack && !wasUnder) {
            timesRaided++;
            StringBuilder sb = new StringBuilder();
            for (LivingEntity t : threats) sb.append(((Unit) t).getOwnerName()).append(":").append(t.getType().toShortString()).append("@").append((int) Math.sqrt(t.distanceToSqr(Vec3.atCenterOf(in.home())))).append(" ");
            bot.log("raid: " + sb);
        }

        scout(in);
        manageTowers(in);
        if (underAttack) lastThreatTick = bot.now;
        else releaseMilitia(in);

        if (in.army.isEmpty() && in.militia.isEmpty()) {
            if (underAttack) {
                if (!ringBell(in, threats, 0)) evacuateWorkers(in, threats);
            }
            if (mode == Mode.ATTACK) endAttack();
            return;
        }

        if (underAttack) {
            if (defend(in, threats)) runAttack(in);
            return;
        }
        raidSquad.clear();
        if (mode == Mode.DEFEND) mode = Mode.GATHER;

        if (mode == Mode.RETREAT) {
            if (bot.now < retreatUntil) { gather(in); return; }
            mode = Mode.GATHER;
        }

        if (mode == Mode.ATTACK) {
            campSquad(in);
            runAttack(in);
            return;
        }
        campSquad.clear(); campSquadAt = null;

        // monster camps next to the base first: they kill villagers and block the resources around them
        if (mode == Mode.GATHER && clearNeutrals(in)) return;
        if (shouldAttack(in)) {
            mode = Mode.ATTACK;
            attacksLaunched++;
            trackedTarget = null; // fresh progress clock for this attack
            ledgePushes = 0;
            bot.log("attack -> " + target + " army=" + in.army.size() + " pop=" + in.pop);
            bot.tellAllies(joinedHuman != null ? "看到你在打 " + target + "，我带 " + in.army.size() + " 个兵过来帮忙！"
                    : "我要进攻 " + target + " 了，兵力 " + in.army.size() + "，一起上！", true);
            joinedHuman = null;
            lastReissue = -1000;
            stageUntil = bot.difficulty.ordinal() >= Difficulty.HARD.ordinal() ? bot.now + 800 : -1;
            stageStarted = bot.now;
            stagePos = null;
            runAttack(in);
            return;
        }
        gather(in);
    }

    /** attack staging: march to a point short of the target and wait for the army to close up */
    private long stageUntil = -1, stageStarted = -1;
    private BlockPos stagePos;

    /**
     * The other allied expert bots whose armies are attacking the same enemy right now. Allied bots
     * coming one after another get beaten one at a time; together they are one army.
     */
    List<Bot> partners() {
        List<Bot> out = new ArrayList<>();
        if (bot.difficulty != Difficulty.EXPERT || target == null) return out;
        for (Bot o : Bot.peers()) {
            if (o == bot || o.difficulty != Difficulty.EXPERT || o.army.mode != Mode.ATTACK || !target.equals(o.army.target)) continue;
            if (!allied(o)) continue; // free-for-all: everyone fights alone
            if (o.intel.army.isEmpty()) continue;
            out.add(o);
        }
        return out;
    }

    private boolean allied(Bot o) {
        try { return com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(bot.name, o.name); }
        catch (Throwable t) { return false; }
    }

    /** a partner's army still well behind ours on the way in: hold at the staging point for it */
    private boolean partnerLagging() {
        if (targetPos == null) return false;
        Vec3 t = Vec3.atCenterOf(targetPos);
        for (Bot o : partners()) {
            List<Mob> body = mainBody(o.intel.army);
            if (body.isEmpty()) continue;
            if (centroid(body).distanceTo(t) > 28 + 35) return true;
        }
        return false;
    }

    /**
     * Join an allied expert's attack the moment it starts, with whatever army is at home:
     * the two waves land together instead of one after the other.
     */
    private String joinedHuman;

    private boolean joinPartner(Intel in, double armyPop) {
        if (bot.difficulty != Difficulty.EXPERT || armyPop < 15 || bot.now - lastAttackEnd < 300) return false;
        // a human teammate fighting at an enemy base: go and help
        for (String h : in.allies) {
            if (BotManager.get(h) != null) continue;
            for (Intel.EnemyMemory em : in.enemies.values()) {
                if (!em.alive || em.buildings.isEmpty() || em.anyBuilding() == null) continue;
                int n = 0;
                for (LivingEntity e : com.solegendary.reignofnether.unit.UnitServerEvents.getAllUnits()) {
                    if (!(e instanceof Unit u) || !h.equals(u.getOwnerName()) || !e.isAlive() || UnitEval.isWorker(e) || UnitEval.isScout(e)) continue;
                    for (BlockPos b : em.buildings.keySet()) if (e.blockPosition().distSqr(b) < 40 * 40) { n++; break; }
                }
                if (n >= 5) {
                    target = em.name;
                    targetPos = em.anyBuilding();
                    joinedHuman = h;
                    bot.log("joint attack with " + h + " (human, " + n + " units at " + em.name + ")");
                    return true;
                }
            }
        }
        for (Bot o : Bot.peers()) {
            if (o == bot || o.difficulty != Difficulty.EXPERT || o.army.mode != Mode.ATTACK || o.army.target == null) continue;
            if (!allied(o)) continue; // only allies attack together
            Intel.EnemyMemory em = in.enemies.get(o.army.target);
            if (em == null || !em.alive || em.anyBuilding() == null) continue;
            if (o.intel.army.size() < 4) continue;
            target = o.army.target;
            targetPos = em.anyBuilding();
            bot.log("joint attack with " + o.name + " on " + target + " (theirs " + o.intel.army.size() + ")");
            bot.tellAllies("跟上 " + o.name + "，一起打 " + target + "！", true);
            return true;
        }
        return false;
    }

    private boolean staging(Intel in, List<Mob> force) {
        if (stageUntil < 0 || bot.now > stageUntil || targetPos == null || force.isEmpty()) { stageUntil = -1; return false; }
        if (stagePos == null) {
            Vec3 from = centroid(force), to = Vec3.atCenterOf(targetPos);
            Vec3 d = to.subtract(from);
            double len = d.length();
            if (len < 40) { stageUntil = -1; return false; } // already close: just go
            Vec3 p = to.subtract(d.scale(28 / len));
            stagePos = BlockPos.containing(p);
            Orders.move(bot.name, force, waypoint(from, stagePos));
            lastReissue = bot.now;
            return true;
        }
        int there = 0;
        for (Mob u : force) if (u.blockPosition().distSqr(stagePos) < 14 * 14) there++;
        boolean wait = partnerLagging() && bot.now - stageStarted < 1800;
        if (wait) stageUntil = Math.max(stageUntil, bot.now + 40);
        if (there >= force.size() * 0.75 && !wait) { stageUntil = -1; lastReissue = -1000; return false; }
        if (bot.now - lastReissue > 200) {
            List<Mob> late = new ArrayList<>();
            for (Mob u : force) if (u.blockPosition().distSqr(stagePos) >= 14 * 14 && ((Unit) u).isIdle()) late.add(u);
            if (!late.isEmpty()) Orders.move(bot.name, late, waypoint(centroid(late), stagePos));
            lastReissue = bot.now;
        }
        return true;
    }

    private List<LivingEntity> threats(Intel in, double r) {
        List<LivingEntity> out = new ArrayList<>();
        double r2 = r * r;
        for (LivingEntity e : in.visibleEnemies) {
            for (BuildingPlacement b : in.own) {
                double d = b.isCapitol ? r2 : r2 * 0.45;
                if (e.distanceToSqr(Vec3.atCenterOf(b.centrePos)) <= d) { out.add(e); break; }
            }
        }
        // enemy workers wandering by are not a raid unless they are building next to us
        out.removeIf(e -> UnitEval.isWorker(e) && e.distanceToSqr(Vec3.atCenterOf(in.home())) > 20 * 20);
        // a scout looking around is not a raid either; the army should not chase dogs around the base
        out.removeIf(UnitEval::isScout);
        // nor is one explorer walking past the outskirts: a raid is two or more fighters, or
        // anything right at the capitol
        int fighters = 0;
        for (LivingEntity e : out) if (!UnitEval.isWorker(e)) fighters++;
        if (fighters < 2) {
            Vec3 h = Vec3.atCenterOf(in.home());
            out.removeIf(e -> e.distanceToSqr(h) > 16 * 16);
        }
        // an intruder the army cannot hurt (inside a building, up a ledge) would keep the whole army
        // home forever
        // ...but a real raid (many fighters, or ones we are wearing down) is never ignored
        double hpSum = 0;
        for (LivingEntity e : out) hpSum += e.getHealth();
        if (hpSum < threatHpSample - 3) lastThreatProgress = bot.now;
        if (bot.now - lastThreatSample > 100 || hpSum < threatHpSample) { threatHpSample = hpSum; lastThreatSample = bot.now; }
        if (fighters >= 5 || bot.now - lastThreatProgress < 400) {
            for (LivingEntity e : out) threatWatch.remove(e.getId());
            return out;
        }
        out.removeIf(e -> untouchable(e, true));
        return out;
    }

    /** @return true when the attack goes on with the rest of the army (a squad handles the raid) */
    private boolean defend(Intel in, List<LivingEntity> threats) {
        BlockPos home = in.home();
        LivingEntity focus = threats.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(Vec3.atCenterOf(home)))).orElse(threats.get(0));
        lastThreat = focus.blockPosition();
        double threatS = UnitEval.strength(threats);

        List<Mob> responders = new ArrayList<>(in.army);
        boolean keepAttacking = false;
        boolean expert = bot.difficulty == Difficulty.EXPERT;
        if (mode == Mode.ATTACK && bot.difficulty.ordinal() >= Difficulty.HARD.ordinal()) {
            // keep pressure on if the raid is small and the home guard can handle it: sending the whole
            // army home for two scouts is how weak AIs lose games
            List<Mob> home2 = near(in.army, Vec3.atCenterOf(home), bot.params.get("defendRadius") + 16);
            if (UnitEval.strength(home2) > 1.5 * threatS) { responders = home2; keepAttacking = expert; }
            else if (expert && threatS * 3 < UnitEval.strength(in.army)) {
                // a small raid on a spread-out base: the units nearest to it go back, the attack goes on
                // (recalling everything for two vindicators at an outlying farm ping-pongs the army forever)
                List<Mob> sorted = new ArrayList<>(in.army);
                sorted.sort(Comparator.comparingDouble(m -> m.distanceToSqr(focus)));
                responders = new ArrayList<>();
                for (Mob m : sorted) {
                    if (responders.size() >= 2 && UnitEval.strength(responders) >= 2 * threatS) break;
                    responders.add(m);
                }
                keepAttacking = true;
                if (bot.now - lastSquadLog > 600) { bot.log("raid squad of " + responders.size() + " goes home, attack goes on"); lastSquadLog = bot.now; }
            }
            else endAttack();
        } else if (mode == Mode.ATTACK) {
            endAttack();
        }
        raidSquad.clear();
        if (keepAttacking) for (Mob m : responders) raidSquad.add(m.getId());
        else mode = Mode.DEFEND;
        // defend with everything at once: only part of a raid is usually in sight, and a defence
        // fed in piece by piece loses to the square law
        List<Mob> toOrder = new ArrayList<>();
        for (Mob m : responders) {
            if (m.distanceToSqr(focus) > 14 * 14 || ((Unit) m).isIdle()) toOrder.add(m);
        }
        // outnumbered at home: fight under the capitol and the towers (they shoot too) instead of
        // walking out to meet a bigger army in the open
        BlockPos meet = focus.blockPosition();
        if (bot.difficulty == Difficulty.EXPERT && in.capitol != null && in.capitol.isBuilt
                && UnitEval.strength(responders) < threatS * 1.2
                && focus.distanceToSqr(Vec3.atCenterOf(in.capitol.centrePos)) > 14 * 14) {
            Vec3 cap = Vec3.atCenterOf(in.capitol.centrePos);
            Vec3 d = focus.position().subtract(cap);
            d = new Vec3(d.x, 0, d.z);
            if (d.lengthSqr() > 1) meet = BlockPos.containing(cap.add(d.normalize().scale(7)));
            toOrder = new ArrayList<>();
            for (Mob m : responders) if (m.blockPosition().distSqr(meet) > 8 * 8 || ((Unit) m).isIdle()) toOrder.add(m);
        }
        if (!toOrder.isEmpty() && bot.now - lastDefendOrder > 20) {
            // units far away (caught in an enemy base when the raid came) walk home first: an
            // attack-move would keep them brawling with whatever stands next to them out there
            List<Mob> far = new ArrayList<>(), close = new ArrayList<>();
            for (Mob m : toOrder) (m.blockPosition().distSqr(meet) > 50 * 50 ? far : close).add(m);
            if (!far.isEmpty() && bot.now - lastFarHome > 100) {
                Orders.move(bot.name, far, waypoint(centroid(far), meet));
                lastFarHome = bot.now;
                if (++farHomeLogs % 10 == 1) bot.log("defend: " + far.size() + " units far away march home");
            }
            if (!close.isEmpty()) Orders.attackMove(bot.name, close, meet);
            lastDefendOrder = bot.now;
        }
        // militia called up by the bell fight alongside the army
        if (!in.militia.isEmpty() && bot.now - lastMilitiaOrder > 20) {
            Orders.attackMove(bot.name, in.militia, focus.blockPosition());
            lastMilitiaOrder = bot.now;
        }
        double defenders = UnitEval.strength(responders) + UnitEval.strength(in.garrisoned);
        if (!ringBell(in, threats, defenders) && (responders.isEmpty() || UnitEval.strength(responders) < threatS * 0.5))
            evacuateWorkers(in, threats);
        return keepAttacking;
    }

    /** units sent home against a small raid while the rest of the army keeps attacking */
    private final java.util.Set<Integer> raidSquad = new java.util.HashSet<>();
    private final java.util.Set<Integer> campSquad = new java.util.HashSet<>();
    private BlockPos campSquadAt;
    private long campSquadSince, campSquadOrder;

    /**
     * Attacking far away while monsters kill villagers at home: the units nearest home go back and
     * deal with them (a human teammate had to clear them for us), the rest keep attacking.
     */
    private void campSquad(Intel in) {
        if (bot.difficulty != Difficulty.EXPERT) return;
        if (campSquadAt != null) {
            final BlockPos at = campSquadAt;
            boolean left = false;
            for (LivingEntity e : in.neutrals) if (e.isAlive() && e.blockPosition().distSqr(at) < 18 * 18) { left = true; break; }
            List<Mob> sq = new ArrayList<>();
            for (Mob m : in.army) if (campSquad.contains(m.getId())) sq.add(m);
            if (!left || sq.isEmpty() || bot.now - campSquadSince > 2400) {
                bot.log("camp squad done at " + at.toShortString() + (left ? " (gave up)" : ""));
                campSquad.clear(); campSquadAt = null;
                return;
            }
            if (bot.now - campSquadOrder > 200) { Orders.attackMove(bot.name, sq, at); campSquadOrder = bot.now; }
            return;
        }
        BlockPos d = in.recentDeath(in.home(), 90, 600);
        if (d == null) return;
        List<LivingEntity> beasts = new ArrayList<>();
        for (LivingEntity e : in.neutrals) if (e.isAlive() && e.blockPosition().distSqr(d) < 18 * 18) beasts.add(e);
        if (beasts.isEmpty()) return;
        double need = UnitEval.strength(beasts) * 2.0;
        List<Mob> army = new ArrayList<>(in.army);
        Vec3 dv = Vec3.atCenterOf(d);
        army.sort(Comparator.comparingDouble(m -> m.distanceToSqr(dv)));
        List<Mob> sq = new ArrayList<>();
        for (Mob m : army) {
            if (sq.size() >= army.size() / 2) break;
            sq.add(m);
            if (UnitEval.strength(sq) >= need) break;
        }
        if (UnitEval.strength(sq) < need) return;
        for (Mob m : sq) campSquad.add(m.getId());
        campSquadAt = d; campSquadSince = bot.now; campSquadOrder = bot.now;
        Orders.attackMove(bot.name, sq, d);
        bot.log("camp squad: " + sq.size() + " units go home to clear monsters at " + d.toShortString() + " (" + beasts.size() + " beasts)");
    }
    private java.util.Set<Integer> upgradeVisits = java.util.Set.of();
    /** home itself is being raided: nobody wanders off to the library */
    private boolean underAttackNear(Intel in) { return underAttack && bot.now - lastThreatTick < 40; }
    /** game ticks since the last raid on the base */
    long sinceThreat() { return bot.now - lastThreatTick; }
    private long lastDefendOrder = -1000, lastSquadLog = -10000;
    private long bellRung = -100000, lastThreatTick = -100000, lastMilitiaOrder, lastRelease, lastFarHome = -1000;
    private int farHomeLogs;

    /**
     * The AoE2 town bell, RON style: the capitol's call to arms turns nearby villagers into militia.
     * Only for a real raid near home that the army cannot hold on its own.
     */
    private boolean ringBell(Intel in, List<LivingEntity> threats, double defenders) {
        if (!bot.params.b("pullWorkersToDefend") || in.capitol == null || !in.capitol.isBuilt) return false;
        int fighters = 0;
        double threatS = 0;
        List<LivingEntity> near = new ArrayList<>();
        Vec3 home = Vec3.atCenterOf(in.capitol.centrePos);
        for (LivingEntity t : threats) {
            if (UnitEval.isWorker(t)) continue;
            if (t.distanceToSqr(home) > 34 * 34) continue;
            fighters++;
            near.add(t);
        }
        if (fighters < 3) return false;
        threatS = UnitEval.strength(near);
        if (defenders > threatS * 1.3) return false;
        if (bot.now - bellRung < 1300) return !in.militia.isEmpty();
        bellRung = bot.now;
        Orders.buildingAbility(bot.name, com.solegendary.reignofnether.unit.UnitAction.CALL_TO_ARMS_BUILDING, in.capitol.centrePos);
        bot.log("town bell: " + fighters + " attackers at home");
        return true;
    }

    /** raid over: militia go back to their jobs */
    private void releaseMilitia(Intel in) {
        if (in.militia.isEmpty() || in.capitol == null) return;
        if (bot.now - lastThreatTick < 200 || bot.now - lastRelease < 100) return;
        lastRelease = bot.now;
        // walk them home first; the ability only reaches militia near the capitol
        List<Mob> far = new ArrayList<>();
        for (Mob m : in.militia) if (m.distanceToSqr(Vec3.atCenterOf(in.capitol.centrePos)) > 40 * 40) far.add(m);
        if (!far.isEmpty()) Orders.move(bot.name, far, in.capitol.centrePos);
        Orders.buildingAbility(bot.name, com.solegendary.reignofnether.unit.UnitAction.BACK_TO_WORK_BUILDING, in.capitol.centrePos);
    }

    private void evacuateWorkers(Intel in, List<LivingEntity> threats) {
        if (bot.difficulty.ordinal() < Difficulty.HARD.ordinal()) return;
        // only a real raid is worth stopping the economy for
        int fighters = 0;
        for (LivingEntity t : threats) if (!UnitEval.isWorker(t)) fighters++;
        if (fighters < 3) return;
        List<Mob> run = new ArrayList<>();
        for (Mob w : in.workers) {
            for (LivingEntity t : threats) {
                if (UnitEval.isWorker(t)) continue;
                if (w.distanceToSqr(t) < 11 * 11) { run.add(w); break; }
            }
        }
        if (run.isEmpty()) return;
        BlockPos safe = in.home();
        // flee to the far side of the capitol from the threat
        LivingEntity t = threats.get(0);
        Vec3 away = Vec3.atCenterOf(safe).subtract(t.position()).normalize().scale(6);
        Orders.move(bot.name, run, BlockPos.containing(safe.getX() + away.x, safe.getY(), safe.getZ() + away.z));
        // keep their job record: they go back to work by themselves once the raid is over
    }

    private double jitter() {
        if (firstAttackJitter < 0) firstAttackJitter = 0.7 + 0.6 * bot.random.nextDouble();
        return firstAttackJitter;
    }

    private boolean shouldAttack(Intel in) {
        BotParams p = bot.params;
        double secs = PlayerServerEvents.rtsGameTicks / 20.0;
        if (secs < p.get("firstAttackSeconds") * jitter()) return false;
        double armyPop = 0;
        for (Mob m : in.army) armyPop += UnitEval.popValue(m);
        if (joinPartner(in, armyPop)) return true;
        if (bot.now - lastAttackEnd < p.get("attackWaveCooldown") * 20) return false;
        boolean maxed = in.pop >= 140;
        // 1vN: hit the nearest opponent while the field is still empty, before the weak bots have
        // armies, then the next one - defeating them in detail instead of waiting for all of them
        if (bot.outnumbered() && p.b("rushWhenOutnumbered") && secs >= p.get("rushStartSeconds")) {
            if (armyPop < p.get("rushMinPop")) return false;
            String nearest = null;
            double best = 1e18;
            double ownS = UnitEval.strength(attackForce(in));
            for (Intel.EnemyMemory m : in.enemies.values()) {
                if (bot.now < enemyBlacklist.getOrDefault(m.name, -1L)) continue;
                BlockPos b = m.alive ? m.anyBuilding() : null;
                if (b == null) continue;
                // a rush only pays against a clearly weaker opponent: against equal armies it just
                // bleeds units and leaves home empty when the whole enemy team comes
                if (m.lastSeenArmy < 0 || ownS < 2.0 * decayedStrength(m)) continue; // unscouted = not soft
                double d = b.distSqr(in.home());
                // an enemy we have just seen with a real army is not the soft target
                if (m.knownStrength(bot.now) > UnitEval.strength(in.army) * 1.2) d *= 4;
                if (d < best) { best = d; nearest = m.name; }
            }
            if (nearest == null && !maxed) return false;
            if (nearest != null) {
            target = nearest;
            targetPos = in.enemies.get(nearest).anyBuilding();
            return true;
            }
            // maxed out with no soft target left: fall through to the ordinary attack
        }
        // even game, small but real edge: we have just seen their army and ours is clearly bigger -
        // hit now (raid their villagers) instead of waiting for the full attack size; a human with
        // fewer units punishes waiting by teching up
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && armyPop >= 30 && !(v2on() && p.b("v2Leave"))) {
            String t0 = chooseTarget(in);
            Intel.EnemyMemory m0 = t0 == null ? null : in.enemies.get(t0);
            if (m0 != null && bot.now - m0.lastSeenArmy < 20 * 90) {
                double own0 = UnitEval.strength(attackForce(in));
                if (own0 >= 1.8 * Math.max(1, m0.knownStrength(bot.now) + homeDefenceBonus(m0))) {
                    target = t0;
                    targetPos = m0.anyBuilding();
                    bot.log("edge attack: ours " + (int) own0 + " vs seen " + (int) m0.knownStrength(bot.now));
                    return true;
                }
            }
        }
        // a sparring style with a fixed attack time: go with what there is, like a human all-in
        if (p.get("styleAttackAt") > 0 && secs >= p.get("styleAttackAt") && armyPop >= p.get("styleAttackMinPop")) {
            String ts = chooseTarget(in);
            Intel.EnemyMemory ms = ts == null ? null : in.enemies.get(ts);
            if (ms != null && ms.anyBuilding() != null) {
                target = ts;
                targetPos = ms.anyBuilding();
                bot.log("style attack at " + (int) secs + "s with army pop " + (int) armyPop);
                return true;
            }
        }
        // forecast instead of ratios: go as soon as the army we have beats what they probably have
        if (v2on() && p.b("v2Leave") && !bot.outnumbered()) {
            if (armyPop < p.get("v2MinPop") && !maxed) return false;
            String tv = chooseTarget(in);
            Intel.EnemyMemory mv = tv == null ? null : in.enemies.get(tv);
            if (mv == null || mv.anyBuilding() == null) return false;
            String old = target;
            target = tv;
            boolean go = maxed || v2.worthLeaving(in, mv, attackForce(in), partners());
            if (!go) { target = old; return false; }
            targetPos = mv.anyBuilding();
            return true;
        }
        double minPop = p.get("attackMinArmyPop") * (bot.outnumbered() ? 1.8 : 1.0);
        // even game: go as one real wave (a human pushes back a dozen units with a few more from four barracks)
        if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered()) minPop = Math.max(minPop, 45);
        if (armyPop < minPop && !maxed) return false;
        String t = chooseTarget(in);
        if (t == null) {
            // nobody found yet: the lower tiers just march toward where the scout went; the expert waits for intel
            return false;
        }
        target = t;
        Intel.EnemyMemory m = in.enemies.get(t);
        targetPos = m.anyBuilding();
        double adv = p.get("attackAdvantage");
        if (adv <= 0 || maxed) return true;
        double own = UnitEval.strength(attackForce(in));
        double enemy = decayedStrength(m) + homeDefenceBonus(m);
        if (bot.now - m.lastSeenArmy > 20 * 120) {
            // no recent look at their army: assume it is as big as ours has been allowed to grow,
            // i.e. only go with a clearly larger force than the minimum (unknown = dangerous)
            if (armyPop < p.get("attackMinArmyPop") * 1.5 && !maxed) return false;
        }
        return own >= adv * enemy;
    }

    /**
     * Situational awareness for the even game: how big an army could hit us now, in "power" units
     * (sum of UnitEval.power). The biggest army we actually saw (still alive), or - when we have not
     * seen it - what an opponent typically fields by this minute (first soldiers ~minute 6-7, about
     * two more every minute after that), whichever is larger.
     */
    double threatPower(Intel in) {
        double seen = 0, per = 0;
        int perN = 0;
        for (Intel.EnemyMemory m : in.enemies.values()) {
            if (!m.alive) continue;
            seen = Math.max(seen, Math.sqrt(m.knownStrength(bot.now)));
            for (LivingEntity e : m.seenUnits.values()) { per += UnitEval.power(e); perN++; }
        }
        for (Mob a : in.army) { per += UnitEval.power(a); perN++; }
        double unit = perN > 0 ? per / perN : defaultUnitPower;
        double mins = bot.now / 1200.0;
        double prior = Math.max(0, Math.min(30, (mins - 6.5) * 2.0)) * unit;
        return Math.max(seen, prior);
    }
    private static final double defaultUnitPower = 8;

    double ownPower(Intel in) {
        double s = 0;
        for (Mob a : in.army) s += UnitEval.power(a);
        return s;
    }

    /** even game: the army we have would hold what can come - safe to keep booming */
    boolean safeToBoom(Intel in) { return ownPower(in) >= 0.8 * threatPower(in); }

    /**
     * Opening assessment: monsters camped next to the base (seen, or where villagers already died).
     * A human sees them at the start and clears them with the first few soldiers before booming;
     * waiting until they have eaten five villagers loses the game. 0 = none known.
     */
    double homeCampStrength(Intel in) {
        if (bot.difficulty != Difficulty.EXPERT || bot.outnumbered() || bot.now > 18000) return 0;
        Vec3 h = Vec3.atCenterOf(in.home());
        List<LivingEntity> camp = new ArrayList<>();
        for (LivingEntity n : in.neutrals) if (n.position().distanceToSqr(h) < 45 * 45 && !strongCamp(n.blockPosition())) camp.add(n);
        double s = camp.isEmpty() ? 0 : UnitEval.strength(camp);
        if (s == 0 && bot.hazardDeaths >= 2 && in.nearestDanger(in.home(), 45) != null) s = 1;
        return s;
    }

    private double decayedStrength(Intel.EnemyMemory m) {
        // what we know is alive, plus a slowly growing allowance for what they built since we looked
        double known = m.knownStrength(bot.now);
        double sinceMin = Math.max(0, bot.now - m.lastSeenArmy) / 1200.0;
        double growth = Math.sqrt(known) + 6 * Math.min(6, sinceMin);
        return Math.max(known, growth * growth);
    }

    /** towers and the capitol fight back; count them a little */
    private double homeDefenceBonus(Intel.EnemyMemory m) {
        int towers = 0;
        for (BlockPos o : m.buildings.keySet()) {
            for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
                if (b.originPos.equals(o) && b.getBuilding() == bot.plan.tower) towers++;
            }
        }
        double s = Math.sqrt(Math.max(0, m.armyStrength * 0.1)) + 12 * towers;
        return s * s;
    }

    /** how often the army gave up on each enemy: a base it cannot get into is left alone for longer each time */
    private final Map<String, Integer> enemyFails = new HashMap<>();

    private void giveUp(String name) {
        int f = enemyFails.merge(name, 1, Integer::sum);
        if (bot.difficulty != Difficulty.EXPERT) f = 1;
        long len = f <= 1 ? 6000 : f == 2 ? 24000 : 72000;
        enemyBlacklist.put(name, bot.now + len);
        if (f >= 2) bot.log("giving up on " + name + " for " + len / 1200 + " min (failed " + f + "x)");
    }

    String chooseTarget(Intel in) {
        String t = chooseTarget(in, false);
        // every enemy left is one we could not get at: try the one that failed least, rather than idle
        return t != null || bot.difficulty != Difficulty.EXPERT ? t : chooseTarget(in, true);
    }

    private String chooseTarget(Intel in, boolean fallback) {
        BlockPos h = in.home();
        String best = null;
        double bestScore = -1e18;
        double own = Math.max(1, UnitEval.strength(in.army));
        for (Intel.EnemyMemory m : in.enemies.values()) {
            if (!m.alive) continue;
            if (!fallback && bot.now < enemyBlacklist.getOrDefault(m.name, -1L)) continue;
            BlockPos b = m.anyBuilding();
            if (b == null) continue;
            double dist = Math.sqrt(b.distSqr(h));
            double score;
            if (bot.params.b("targetWeakest")) {
                double rel = Math.sqrt(decayedStrength(m) / own);
                score = -dist / 40.0 - 2.0 * rel - 0.002 * m.buildings.size();
                if (m.name.equals(target)) score += 0.6; // stick with a plan
                if (fallback) score -= 3.0 * enemyFails.getOrDefault(m.name, 0);
            } else {
                score = -dist;
            }
            if (score > bestScore) { bestScore = score; best = m.name; }
        }
        return best;
    }

    private List<Mob> attackForce(Intel in) {
        List<Mob> avail = in.army;
        if (!raidSquad.isEmpty() || !upgradeVisits.isEmpty() || !campSquad.isEmpty()) {
            avail = new ArrayList<>(in.army);
            avail.removeIf(m -> raidSquad.contains(m.getId()) || upgradeVisits.contains(m.getId()) || campSquad.contains(m.getId()));
            if (avail.isEmpty()) avail = in.army;
        }
        return attackForce(in, avail);
    }

    private List<Mob> attackForce(Intel in, List<Mob> army) {
        double keep = bot.params.get("armyKeepHome");
        // a home guard only matters while someone can still come for us
        boolean enemyArmyLeft = false;
        for (Intel.EnemyMemory em : in.enemies.values()) if (em.alive && em.knownStrength(bot.now) > 0) enemyArmyLeft = true;
        // and only while they actually come: passive opponents get the whole army thrown at them
        if (bot.outnumbered() && enemyArmyLeft && bot.now - lastThreatTick < 20 * 180) keep = Math.max(keep, bot.params.get("rushHomeGuard"));
        if (keep <= 0) return army;
        List<Mob> sorted = new ArrayList<>(army);
        Vec3 h = Vec3.atCenterOf(in.home());
        sorted.sort(Comparator.comparingDouble(m -> -m.distanceToSqr(h)));
        int n = (int) Math.ceil(sorted.size() * (1 - keep));
        return sorted.subList(0, Math.max(1, n));
    }

    private void endAttack() {
        mode = Mode.GATHER;
        lastAttackEnd = bot.now;
    }

    private void runAttack(Intel in) {
        BotParams p = bot.params;
        Intel.EnemyMemory m = target == null ? null : in.enemies.get(target);
        if (m == null || !m.alive || m.anyBuilding() == null) {
            String t = chooseTarget(in);
            if (t == null) { endAttack(); return; }
            target = t;
            m = in.enemies.get(t);
        }
        List<Mob> force = attackForce(in);
        if (targetPos == null) targetPos = m.anyBuilding();
        if (staging(in, force)) return;
        // judge the fight by the main body of the army: one straggler that wandered into the enemy
        // base must neither make the whole army retreat nor decide where the army is
        List<Mob> body = mainBody(force);
        Vec3 c = centroid(body);
        List<Bot> partners = partners();
        java.util.Set<String> partnerNames = new java.util.HashSet<>();
        for (Bot o : partners) partnerNames.add(o.name);
        List<LivingEntity> localEnemies = new ArrayList<>();
        for (LivingEntity e : in.visibleEnemies) {
            if (untouchable(e, false)) continue;
            if (e instanceof Unit eu && partnerNames.contains(eu.getOwnerName())) continue;
            for (Mob u : body) if (u.distanceToSqr(e) < 20 * 20) { localEnemies.add(e); break; }
        }
        List<Mob> localOwn = localEnemies.isEmpty() ? List.of() : near(force, centroid(localEnemies), 40);
        List<LivingEntity> localFriends = new ArrayList<>(localOwn);
        if (!localEnemies.isEmpty()) for (Bot o : partners) localFriends.addAll(near(o.intel.army, centroid(localEnemies), 40));

        // check the fight forecast against what happens (self-play only: the result line carries it)
        if (bot.difficulty == Difficulty.EXPERT) {
            List<LivingEntity> mil0 = new ArrayList<>();
            for (LivingEntity e : localEnemies) if (!UnitEval.isWorker(e)) mil0.add(e);
            if (probe == null && mil0.size() >= 3 && localFriends.size() >= 3)
                probe = new CombatSim.Probe(localFriends, mil0, 0, 12, bot.now);
            else if (probe != null && !mil0.isEmpty()) probe.lastContact = bot.now;
        }

        // lost the fight? fall back and rebuild instead of feeding units one by one
        double rr = p.get("retreatRatio");
        if (rr > 0 && !localEnemies.isEmpty() && !localOwn.isEmpty()) {
            double ratio = UnitEval.strength(localFriends) / Math.max(1, UnitEval.strength(localEnemies));
            // the expert commits: a clearly bigger army overall (with partners) does not turn round over one
            // bad skirmish (a player watched four attacks in a row break off at 0.4-0.5 and come to nothing)
            boolean committed = false;
            if (bot.difficulty == Difficulty.EXPERT) {
                rr = Math.min(rr, 0.4);
                double allOwn = UnitEval.strength(force);
                for (Bot o : partners) allOwn += UnitEval.strength(o.intel.army);
                committed = m != null && ratio >= 0.25 && allOwn >= 1.3 * Math.max(1, decayedStrength(m) + homeDefenceBonus(m));
            }
            boolean giveUp = ratio < rr && !committed;
            if (v2on() && p.b("v2Retreat")) {
                List<LivingEntity> everyone = new ArrayList<>(force);
                for (Bot o : partners) everyone.addAll(o.intel.army);
                giveUp = bot.now - lastLostCheck >= 40 && v2.lost(localFriends, localEnemies, everyone);
                if (bot.now - lastLostCheck >= 40) lastLostCheck = bot.now;
            }
            if (giveUp) {
                mode = Mode.RETREAT;
                retreats++;
                retreatUntil = bot.now + (v2on() && p.b("v2Retreat") ? 1200 : 500);
                lastAttackEnd = bot.now;
                if (probe != null && probe.finished(bot.now, "retreat")) probe = null;
                bot.log("retreat ratio=" + String.format("%.2f", ratio));
                bot.tellAllies("打不过 " + target + "，先撤回来补兵", true);
                Orders.move(bot.name, force, rally(in));
                return;
            }
        }
        // remember the enemy army we are fighting
        if (!localEnemies.isEmpty()) {
            List<LivingEntity> mil = new ArrayList<>();
            for (LivingEntity e : localEnemies) if (!UnitEval.isWorker(e)) mil.add(e);
            if (!mil.isEmpty()) {
                m.armyStrength = Math.max(m.armyStrength * 0.7, UnitEval.strength(mil));
                m.lastSeenArmy = bot.now;
            }
        }

        // regroup stragglers before contact
        double rg = p.get("regroupDistance");
        // units far from the main body (a second group at the front, fresh units at home) cannot path
        // to it in one go: a move order there just hangs. Bring them over in route steps instead, and
        // leave them out of the body's own orders.
        List<Mob> lost = new ArrayList<>();
        for (Mob u : force) if (u.position().distanceToSqr(c) > 56 * 56) lost.add(u);
        if (!lost.isEmpty() && bot.now - lastLostOrder > 160) {
            lastLostOrder = bot.now;
            Orders.move(bot.name, lost, waypoint(centroid(lost), anchor(body, c)));
        }
        if (!lost.isEmpty()) { force = new ArrayList<>(force); force.removeAll(lost); if (force.isEmpty()) return; }
        if (rg > 0 && localEnemies.isEmpty() && bot.now - lastRegroup > 160) {
            int far = 0;
            for (Mob u : force) if (u.position().distanceToSqr(c) > rg * rg) far++;
            if (far > force.size() * 0.3) {
                Orders.move(bot.name, force, anchor(body, c));
                lastRegroup = bot.now;
                lastReissue = bot.now;
                return;
            }
        }

        // pick the point to push: nearest enemy worker cluster (raids) or nearest known building
        BlockPos goal = null;
        if (p.b("raidWorkers")) {
            LivingEntity w = null;
            for (LivingEntity e : in.visibleEnemies) {
                if (!UnitEval.isWorker(e) || !target.equals(((Unit) e).getOwnerName())) continue;
                if (e.position().distanceToSqr(c) > 30 * 30) continue;
                if (w == null || e.position().distanceToSqr(c) < w.position().distanceToSqr(c)) w = e;
            }
            if (w != null && localEnemies.stream().filter(e -> !UnitEval.isWorker(e)).count() <= 1) goal = w.blockPosition();
        }
        BuildingPlacement building = nearestEnemyBuilding(m, c);
        if (goal == null && building != null) goal = building.centrePos;
        if (goal == null) {
            BlockPos b = m.anyBuilding();
            if (b == null) { endAttack(); return; }
            goal = b;
        }
        targetPos = goal;
        double enemyHp = 0;
        for (LivingEntity e : localEnemies) enemyHp += e.getHealth();
        if (bot.now - lastDiag > 600) {
            lastDiag = bot.now;
            long mil = localEnemies.stream().filter(e -> !UnitEval.isWorker(e)).count();
            Vec3 ec = localEnemies.isEmpty() ? c : centroid(localEnemies);
            bot.log(String.format("attackdiag force=%d body=%d c=%d,%d,%d goal=%s d=%d localEnemy=%d(mil %d hp %d at %d,%d,%d) localOwn=%d bld=%s",
                    force.size(), body.size(), (int) c.x, (int) c.y, (int) c.z, goal.toShortString(),
                    (int) Math.sqrt(c.distanceToSqr(Vec3.atCenterOf(goal))), localEnemies.size(), mil, (int) enemyHp,
                    (int) ec.x, (int) ec.y, (int) ec.z, localOwn.size(),
                    building == null ? "-" : building.getBuilding().getClass().getSimpleName() + "@" + building.getHealth()));
        }
        // leaving a base we could not take: attack-move would stop at every defender on the ledge
        // above again, so walk away with plain moves until the army is clear or meets enemies on its level
        if (bot.now < disengageUntil) {
            boolean level = false;
            for (LivingEntity e : localEnemies)
                if (Math.abs(e.getY() - c.y) < 3 && e.position().distanceToSqr(c) < 10 * 10) { level = true; break; }
            if (level) disengageUntil = 0;
            else {
                if (bot.now - lastReissue > 100) { Orders.move(bot.name, force, waypoint(c, goal)); lastReissue = bot.now; }
                return;
            }
        }
        // fighting up a ledge: the defenders stand a few blocks above and melee units stop at the
        // cliff foot (attack-move engages what it sees). Walk the melee up the ramp with a plain move
        // order; archers keep shooting from below.
        if (!localEnemies.isEmpty() && bot.now >= pushUntil) {
            Vec3 ec = centroid(localEnemies);
            if (ec.y - c.y >= 3) {
                List<Mob> melee = new ArrayList<>();
                for (Mob u : force) if (!UnitEval.isRanged(u)) melee.add(u);
                BlockPos up = waypoint(c, BlockPos.containing(ec));
                if (!melee.isEmpty() && ledgePushes < 15 && (up.getY() > c.y + 0.5 || Math.sqrt(Vec3.atCenterOf(up).distanceToSqr(c)) > 6)) {
                    ledgePushes++;
                    Orders.move(bot.name, melee, up);
                    pushUntil = bot.now + 100;
                    lastReissue = bot.now;
                    return;
                }
            }
        }
        boolean hurting = !localEnemies.isEmpty() && fightProgress(enemyHp);
        curForce = force;
        if (bot.difficulty == Difficulty.EXPERT && attrition(m, c, building)) { Orders.move(bot.name, force, rally(in)); return; }
        if (checkStuck(m, c, goal, building, hurting)) return;

        // detour: shot at from a ledge we cannot climb here, the units keep stopping to fight back
        // (attack-move). Walk the route round to the ramp with plain moves until near the goal.
        // the target stands up on a plateau (a walled mesa base): fighting at the cliff foot gets nowhere.
        // Find where the route reaches the top and walk the whole army up there first, plain moves only.
        if (bot.difficulty == Difficulty.EXPERT && goal.getY() - c.y >= 4) {
            if (rampTop == null || !goal.equals(rampGoal) || bot.now > rampUntil) {
                rampTop = null;
                if (bot.now >= rampRetry) {
                    rampRetry = bot.now + 400;
                    List<BlockPos> r = Terrain.route(bot.level, BlockPos.containing(c), goal, bot.now);
                    if (Terrain.reaches(r, goal)) {
                        for (BlockPos q : r) if (q.getY() >= goal.getY() - 2) { rampTop = q; break; }
                        if (rampTop != null) {
                            rampGoal = goal; rampUntil = bot.now + 1800;
                            bot.log("plateau: walking up to " + rampTop.toShortString() + " for " + goal.toShortString());
                        }
                    }
                }
            }
            if (rampTop != null && c.y < rampTop.getY() - 2) {
                if (bot.now - lastReissue > 80) { Orders.move(bot.name, force, waypoint(c, rampTop)); lastReissue = bot.now; }
                return;
            }
        }
        if (bot.now < detourUntil) {
            // archers hitting defenders on the ledge above must not call the walk round off
            boolean below = !localEnemies.isEmpty() && centroid(localEnemies).y - c.y >= 3;
            if (Math.sqrt(c.distanceToSqr(Vec3.atCenterOf(goal))) < 16 && !below || hurting && !below) detourUntil = 0;
            else {
                if (bot.now - lastReissue > 100) { Orders.move(bot.name, force, waypoint(c, goal)); lastReissue = bot.now; }
                return;
            }
        }

        // a stand-off: enemies in sight but nobody is taking damage (high ground, a wall, units
        // attack-moving to a spot they cannot reach). Order every unit onto a specific enemy instead.
        // Typical cause: the enemy stands on a ledge above the army and attack-move keeps stopping the
        // units at the cliff foot. Alternate: first a plain move into the base (the pathfinder then
        // takes the ramp instead of stopping to shoot), then direct attack orders on single enemies.
        if (bot.now < pushUntil) return;
        if (!localEnemies.isEmpty() && bot.now - lastFightProgress > 300 && bot.now - lastDirect > 160) {
            lastDirect = bot.now;
            // a stand-off far from the target is someone in the way (defenders of a base we pass on its
            // ledge): not worth fighting, just keep walking
            if (bot.difficulty == Difficulty.EXPERT && Math.sqrt(c.distanceToSqr(Vec3.atCenterOf(goal))) > 45) {
                disengageUntil = bot.now + 1000;
                noteDetour(c, goal);
                bot.log("stand-off on the way at " + BlockPos.containing(c).toShortString() + ": walking on to " + goal.toShortString());
                Orders.move(bot.name, force, waypoint(c, goal));
                lastReissue = bot.now;
                return;
            }
            if (standoffs >= 5) {
                standoffs = 0;
                detourUntil = bot.now + 1200;
                noteDetour(c, goal);
                bot.log("stand-off at " + BlockPos.containing(c).toShortString() + ": detour along the route to " + goal.toShortString());
                Orders.move(bot.name, force, waypoint(c, goal));
                lastReissue = bot.now;
                return;
            }
            if ((standoffs++ & 1) == 0) {
                BlockPos into = building != null ? building.centrePos : goal;
                Orders.move(bot.name, force, waypoint(c, into));
                pushUntil = bot.now + 140;
            } else {
                for (Mob u : force) {
                    LivingEntity best = null;
                    for (LivingEntity e : localEnemies)
                        if (best == null || u.distanceToSqr(e) < u.distanceToSqr(best)) best = e;
                    if (best != null) Orders.attack(bot.name, List.of(u), best);
                }
            }
            lastReissue = bot.now;
            return;
        }

        // reach the base with no defenders in sight: knock the buildings down
        // (or only a handful of defenders against a big army: a quarter of it deals with them, the
        // rest razes - otherwise thirty units stand around a stockpile chasing two militia for ages)
        List<LivingEntity> localMil = new ArrayList<>();
        for (LivingEntity e : localEnemies) if (!UnitEval.isWorker(e)) localMil.add(e);
        boolean fewDefenders = !localMil.isEmpty() && bot.difficulty == Difficulty.EXPERT && force.size() >= 8
                && UnitEval.strength(force) >= 4 * UnitEval.strength(localMil);
        if (building != null && (localMil.isEmpty() || fewDefenders)
                && c.distanceToSqr(Vec3.atCenterOf(building.centrePos)) < 18 * 18) {
            List<Mob> razers = force;
            if (fewDefenders) {
                Vec3 mc = centroid(localMil);
                List<Mob> byDist = new ArrayList<>(force);
                byDist.sort(Comparator.comparingDouble(u -> u.distanceToSqr(mc)));
                int nf = Math.max(2, force.size() / 4);
                List<Mob> fighters = byDist.subList(0, nf);
                razers = new ArrayList<>(byDist.subList(nf, byDist.size()));
                if (bot.now - lastReissue > 100) Orders.attackMove(bot.name, fighters, BlockPos.containing(mc));
            }
            List<Mob> idle = new ArrayList<>();
            for (Mob u : razers) if (((Unit) u).isIdle() || bot.now - lastReissue > 200) idle.add(u);
            if (!idle.isEmpty()) {
                // raze several buildings at once: split the idle units over the nearest few
                List<BuildingPlacement> near = nearestEnemyBuildings(m, c, Math.max(1, Math.min(4, idle.size() / 3)));
                for (int i = 0; i < near.size(); i++) {
                    List<Mob> group = new ArrayList<>();
                    for (int k = i; k < idle.size(); k += near.size()) group.add(idle.get(k));
                    Orders.attackBuilding(bot.name, group, edgeBlock(near.get(i), centroid(group)));
                }
                lastReissue = bot.now;
            }
            return;
        }
        List<Mob> toOrder = new ArrayList<>();
        boolean resync = bot.now - lastReissue > 200;
        for (Mob u : force) {
            if (resync || ((Unit) u).isIdle()) toOrder.add(u);
        }
        // marching but not moving (the route leads into a dead end): try RON's own pathfinding straight
        // to the goal for a while before checkStuck gives the target up
        if (marchAt == null || c.distanceToSqr(marchAt) > 4 * 4) { marchAt = c; marchSince = bot.now; }
        else if (localEnemies.isEmpty() && bot.now - marchSince > 300 && bot.now >= directUntil) {
            directUntil = bot.now + 400; marchSince = bot.now;
            int navs = 0, idles = 0;
            for (Mob u : force) { if (u.getNavigation().isInProgress()) navs++; if (((Unit) u).isIdle()) idles++; }
            BlockPos cb = BlockPos.containing(c);
            bot.log("army not moving at " + cb.toShortString() + ": heading straight for " + goal.toShortString()
                    + " [nav=" + navs + " idle=" + idles + "/" + force.size() + " ground=" + bot.level.getBlockState(cb.below()).getBlock().getDescriptionId()
                    + " feet=" + bot.level.getBlockState(cb).getBlock().getDescriptionId() + routeDiag(c, goal) + "]" + unitDiag(force));
            bot.bridger.request(BlockPos.containing(c), goal);
            if (bot.difficulty == Difficulty.EXPERT && navs > 0) noteDetour(c, goal);
            // units holding a move target but with no path at all: RON's move goal is wedged. Clear the
            // orders and walk each unit a short hop along the route from where it stands.
            int wedged = 0;
            for (Mob u : force) {
                var mg = ((Unit) u).getMoveGoal();
                if (mg != null && mg.getMoveTarget() != null && u.getNavigation().getPath() == null) wedged++;
            }
            List<BlockPos> route = wedged * 2 >= force.size() ? Terrain.route(bot.level, cb, goal, bot.now) : null;
            if (route != null && route.size() > 2) {
                Orders.stop(bot.name, force);
                // each unit hops along its own route: a split army's centroid lies between the halves, and
                // hops along the centroid's route sent the front half back 40 blocks (Mir's: 15 minutes of
                // ping-pong in the middle of the map, then a timeout with the enemy beaten)
                for (Mob u : force) {
                    List<BlockPos> own = u.position().distanceToSqr(c) > 12 * 12 ? Terrain.route(bot.level, u.blockPosition(), goal, bot.now) : null;
                    List<BlockPos> r = own != null && own.size() > 2 ? own : route;
                    Orders.move(bot.name, List.of(u), Terrain.ahead(r, u.position(), 8));
                }
                bot.log("army wedged (" + wedged + "/" + force.size() + "): short hops along the route");
                // the same goal again and again: that building cannot be reached from here, try others
                if (goal.equals(lastWedgedGoal)) wedgedOnGoal++; else { lastWedgedGoal = goal; wedgedOnGoal = 1; }
                if (wedgedOnGoal >= 4) {
                    for (BuildingPlacement b : BuildingServerEvents.getBuildings())
                        if (b.centrePos.equals(goal)) { buildingBlacklist.put(b.originPos, bot.now + 3600); bot.log("goal " + goal.toShortString() + " unreachable: blacklisted"); }
                    wedgedOnGoal = 0;
                }
                directUntil = bot.now; // back to the normal route march afterwards
                lastReissue = bot.now;
                return;
            }
            boolean ignoredNear = false;
            for (long[] w : enemyWatch.values())
                if (bot.now < w[2] && w.length > 5 && Math.abs(w[4] - c.x) + Math.abs(w[5] - c.z) < 28) { ignoredNear = true; break; }
            // locked onto something it cannot reach (a scout dog under a ledge): attack-move would keep the lock
            for (Mob u : force) if (u.getTarget() != null && !localEnemies.contains(u.getTarget())) { ignoredNear = true; break; }
            if (ignoredNear) Orders.move(bot.name, force, nearStep(c, goal, (directTries++ & 1) == 0 ? 0 : 1));
            else Orders.attackMove(bot.name, force, nearStep(c, goal, (directTries++ & 1) == 0 ? 0 : 1));
            lastReissue = bot.now;
            return;
        }
        if (bot.now < directUntil) toOrder.removeIf(u -> !((Unit) u).isIdle());
        // ignored (unreachable) enemies close by: attack-move would stop at each of them again
        boolean shotAt = false;
        for (long[] w : enemyWatch.values())
            if (bot.now < w[2] && w.length > 5 && Math.abs(w[4] - c.x) + Math.abs(w[5] - c.z) < 28) { shotAt = true; break; }
        if (shotAt && localEnemies.isEmpty() && !toOrder.isEmpty() && bot.now >= directUntil) {
            Orders.move(bot.name, force, waypoint(c, goal));
            lastReissue = bot.now;
            return;
        }
        if (!toOrder.isEmpty()) {
            Orders.attackMove(bot.name, toOrder, bot.now < directUntil ? nearStep(c, goal, (directTries & 1) == 0 ? 1 : 0) : waypoint(c, goal));
            lastReissue = bot.now;
        }
    }

    // ---- attack progress: an army that cannot reach its target (water, cliffs) must not stand there all game
    private final Map<String, Long> enemyBlacklist = new HashMap<>();
    private final Map<BlockPos, Long> buildingBlacklist = new HashMap<>();
    private BlockPos lastWedgedGoal;
    private int wedgedOnGoal;
    private long lastProgress = -1;
    private double bestDist = 1e9;
    private BlockPos trackedBuilding;
    private int trackedHp;
    private String trackedTarget, realTarget;
    private long lastRealProgress;

    /**
     * Where to gather the army: the spot of the unit standing nearest to its centre. The centre itself
     * can be inside a ruin wall or up on a ledge between two groups, where no path ends.
     */
    private BlockPos anchor(List<Mob> units, Vec3 c) {
        Mob best = null;
        for (Mob u : units) if (u.onGround() && (best == null || u.distanceToSqr(c) < best.distanceToSqr(c))) best = u;
        return best == null ? BlockPos.containing(c) : best.blockPosition();
    }

    private long disengageUntil;
    private BlockPos rampTop, rampGoal;
    private long rampUntil, rampRetry;
    private Vec3 detourFrom;
    private String detourTarget;
    private int detourFails;
    private long detourFromTick;

    /**
     * The army keeps failing at the same spot on its route: the height map lied there (an arch, a
     * ruin wall, slabs). Close the next climb on the route for a while so the search finds another way.
     */
    private void blockRouteAhead(Vec3 c, BlockPos goal, String why) {
        List<BlockPos> r = Terrain.route(bot.level, BlockPos.containing(c), goal, bot.now);
        if (r == null || r.size() < 3) return;
        int i0 = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < r.size(); i++) {
            double dd = Vec3.atBottomCenterOf(r.get(i)).distanceToSqr(c.x, r.get(i).getY(), c.z);
            if (dd < bd) { bd = dd; i0 = i; }
        }
        int pick = -1;
        for (int i = i0 + 1; i < Math.min(r.size(), i0 + 16); i++)
            if (r.get(i).getY() > r.get(i - 1).getY()) { pick = i; break; }
        if (pick < 0) pick = Math.min(r.size() - 1, i0 + 6);
        BlockPos p = r.get(pick);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) Terrain.block(p.getX() + dx, p.getZ() + dz, bot.now + 6000);
        Terrain.blockDone(bot.now);
        bot.log("route " + why + ": closing " + p.toShortString() + " on the way to " + goal.toShortString());
    }

    /** a detour/walk-on starts here; starting the next one at the same spot means the route does not work */
    private void noteDetour(Vec3 c, BlockPos goal) {
        if (bot.difficulty == Difficulty.EXPERT && detourFrom != null && detourFrom.distanceToSqr(c) < 6 * 6 && bot.now - detourFromTick < 4000)
        {
            blockRouteAhead(c, goal, "detour failed");
            // the routes into this base keep failing one after another: it cannot be reached from here
            if (!java.util.Objects.equals(target, detourTarget)) { detourTarget = target; detourFails = 0; }
            if (++detourFails >= 4 && target != null) {
                detourFails = 0;
                bot.log("no way into " + target + "'s base, switching target");
                giveUp(target);
                endAttack();
                lastAttackEnd = bot.now - 100000;
                disengageUntil = bot.now + 700;
            }
        }
        detourFrom = c;
        detourFromTick = bot.now;
    }
    private long lastFightProgress, lastDirect, lastHpSample, pushUntil, detourUntil, lastLostOrder;
    private int standoffs, skippedBuildings, ledgePushes;
    private Vec3 marchAt;
    private BlockPos marchGoal;
    private double marchBestD = 1e9;
    private long marchSince, directUntil;
    private int directTries;

    /**
     * A point at most 30 blocks from the army toward the goal (side = 0), or 30 blocks off to the side
     * (side = 1, alternating left and right), for RON's own pathfinder, which cannot plan 200 blocks.
     */
    private BlockPos nearStep(Vec3 c, BlockPos goal, int side) {
        double dx = goal.getX() + 0.5 - c.x, dz = goal.getZ() + 0.5 - c.z, d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        if (d <= 30 && side == 0) return goal;
        dx /= d; dz /= d;
        double px = dx, pz = dz;
        if (side == 1) { double s = ((directTries >> 1) & 1) == 0 ? 1 : -1; px = dx * 0.5 - dz * s; pz = dz * 0.5 + dx * s; }
        int x = (int) Math.floor(c.x + px * 30), z = (int) Math.floor(c.z + pz * 30);
        return new BlockPos(x, bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }
    private List<Mob> curForce;
    private final java.util.Set<BlockPos> rangedTried = new java.util.HashSet<>();
    private double sampledHp = 1e9;

    /** per enemy unit near the army: {since, hp*100 then, ignoredUntil} */
    private final Map<Integer, long[]> enemyWatch = new HashMap<>();

    /**
     * An enemy the army has been next to for a minute without taking any damage (inside a building, on
     * an unreachable ledge): fighting it pins the whole army, so leave it alone for a while.
     */
    private final Map<Integer, long[]> threatWatch = new HashMap<>();
    private double threatHpSample = 1e9;
    private long lastThreatSample, lastThreatProgress;

    /**
     * @param threat the enemy is an intruder at our buildings (called from threats()): while defending,
     *               all the time the army spends trying to reach it counts (up a cliff it may never get
     *               close). Otherwise only time spent right next to the army counts.
     */
    private boolean untouchable(LivingEntity e, boolean threat) {
        Map<Integer, long[]> watch = threat ? threatWatch : enemyWatch;
        if (watch.size() > 400) watch.values().removeIf(w -> bot.now - w[0] > 3000 && bot.now >= w[2]);
        long hp = (long) (e.getHealth() * 100);
        long[] w = watch.get(e.getId());
        if (w != null && bot.now < w[2]) return true;
        boolean close = curForce != null && !near(curForce, e.position(), 12).isEmpty();
        boolean counts = close || threat && mode == Mode.DEFEND;
        // a gap in watching (it left, we stopped defending) restarts the clock
        if (w == null || !counts || bot.now - w[3] > 40) { watch.put(e.getId(), new long[] { bot.now, hp, 0, bot.now, e.getBlockX(), e.getBlockZ() }); return false; }
        w[3] = bot.now;
        // a unit stuck on a ledge or inside a building stays put; one that moves around is fighting
        if (Math.abs(e.getBlockX() - w[4]) + Math.abs(e.getBlockZ() - w[5]) > 4) { w[0] = bot.now; w[4] = e.getBlockX(); w[5] = e.getBlockZ(); return false; }
        if (hp < w[1] - 50) { w[0] = bot.now; w[1] = hp; return false; }
        if (bot.now - w[0] > (close ? 1200 : 3600)) {
            w[2] = bot.now + 6000; w[0] = bot.now;
            bot.log("enemy " + e.getType().toShortString() + " at " + e.blockPosition().toShortString() + " cannot be hurt: ignoring it");
            return true;
        }
        return false;
    }

    /** the enemy units around the army are actually losing health */
    private boolean fightProgress(double enemyHp) {
        if (enemyHp < sampledHp - 3) lastFightProgress = bot.now;
        if (bot.now - lastHpSample > 100 || enemyHp < sampledHp) { sampledHp = enemyHp; lastHpSample = bot.now; }
        return bot.now - lastFightProgress < 300;
    }

    private boolean checkStuck(Intel.EnemyMemory m, Vec3 c, BlockPos goal, BuildingPlacement building, boolean fighting) {
        if (!m.name.equals(trackedTarget)) { trackedTarget = m.name; lastProgress = bot.now; bestDist = 1e9; trackedBuilding = null; skippedBuildings = 0; ledgePushes = 0; }
        double d = Math.sqrt(c.distanceToSqr(Vec3.atCenterOf(goal)));
        if (!m.name.equals(realTarget)) { realTarget = m.name; lastRealProgress = bot.now; }
        if (d < bestDist - 4) { bestDist = d; lastProgress = bot.now; lastRealProgress = bot.now; }
        // fighting counts as progress, but not forever: killing reinforcements at a cliff foot while
        // never getting closer or damaging anything is a stalemate
        if (fighting && bot.now - lastRealProgress < 6000) lastProgress = bot.now;
        if (building != null) {
            if (!building.originPos.equals(trackedBuilding)) { trackedBuilding = building.originPos; trackedHp = building.getHealth(); lastRealProgress = bot.now; }
            // real damage only: a building losing a point now and then (one archer plinking at it) is a
            // stalemate, not progress
            else if (building.getHealth() <= trackedHp - (bot.difficulty == Difficulty.EXPERT ? 8 : 1)) { trackedHp = building.getHealth(); lastProgress = bot.now; lastRealProgress = bot.now; skippedBuildings = 0; }
        }
        if (bot.now - lastProgress < 2400) return false;
        lastProgress = bot.now;
        if (d > 30 || building == null) {
            giveUp(m.name);
            bot.log("no progress against " + m.name + " (dist " + (int) d + "), switching target");
            endAttack();
            lastAttackEnd = bot.now - 100000; disengageUntil = bot.now + 700; // the army is fine, only the target is not: go again at once
            return true;
        }
        if (curForce != null && rangedTried.add(building.originPos)) {
            // melee cannot get at it (on a ledge, behind a wall): let the archers shoot it from below
            List<Mob> rangedUnits = new ArrayList<>();
            for (Mob u : curForce) if (UnitEval.isRanged(u)) rangedUnits.add(u);
            if (!rangedUnits.isEmpty()) {
                Orders.attackBuilding(bot.name, rangedUnits, edgeBlock(building, centroid(rangedUnits)));
                bot.log("ranged units on building at " + building.originPos.toShortString());
                return false;
            }
        }
        if (++skippedBuildings >= 2) {
            // skipping building after building of the same player: the base itself is the problem
            skippedBuildings = 0;
            giveUp(m.name);
            bot.log("no progress inside " + m.name + "'s base, switching target");
            endAttack();
            lastAttackEnd = bot.now - 100000; disengageUntil = bot.now + 700;
            return true;
        }
        buildingBlacklist.put(building.originPos, bot.now + 6000);
        bot.log("cannot damage building at " + building.originPos.toShortString() + ", skipping it");
        trackedBuilding = null;
        return false;
    }

    /**
     * The block of a building nearest to the attackers. Its centre can be out of reach (a farm's middle
     * behind its fence); its edge never is.
     */
    private BlockPos edgeBlock(BuildingPlacement b, Vec3 from) {
        // the point of the building's box nearest to the attackers, then the highest solid block there
        int x = (int) Math.max(b.minCorner.getX(), Math.min(b.maxCorner.getX(), Math.floor(from.x)));
        int z = (int) Math.max(b.minCorner.getZ(), Math.min(b.maxCorner.getZ(), Math.floor(from.z)));
        for (int y = b.maxCorner.getY(); y >= b.minCorner.getY(); y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!bot.level.getBlockState(p).isAir() && com.solegendary.reignofnether.building.BuildingUtils.isPosPartOfAnyBuilding(false, p, true, 0)) return p;
        }
        return b.centrePos;
    }

    private List<BuildingPlacement> nearestEnemyBuildings(Intel.EnemyMemory m, Vec3 from, int n) {
        List<BuildingPlacement> all = new ArrayList<>();
        for (BuildingPlacement b : BuildingServerEvents.getBuildings())
            if (m.name.equals(b.ownerName) && m.buildings.containsKey(b.originPos) && bot.now >= buildingBlacklist.getOrDefault(b.originPos, -1L)) all.add(b);
        all.sort(Comparator.comparingDouble(b -> Vec3.atCenterOf(b.centrePos).distanceToSqr(from)));
        return all.subList(0, Math.min(n, all.size()));
    }

    private BuildingPlacement nearestEnemyBuilding(Intel.EnemyMemory m, Vec3 from) {
        List<BuildingPlacement> all = new ArrayList<>();
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (!m.name.equals(b.ownerName) || !m.buildings.containsKey(b.originPos)) continue;
            if (bot.now < buildingBlacklist.getOrDefault(b.originPos, -1L)) continue;
            all.add(b);
        }
        if (all.isEmpty()) return null;
        all.sort(Comparator.comparingDouble(b -> Vec3.atCenterOf(b.centrePos).distanceToSqr(from)));
        // the nearest building the army can actually walk to (a plateau base may only be reachable
        // from one side; a farm behind a cliff is not worth standing under)
        BlockPos at = BlockPos.containing(from);
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            BuildingPlacement b = all.get(i);
            if (Vec3.atCenterOf(b.centrePos).distanceToSqr(from) < 12 * 12) return b;
            if (Terrain.reaches(Terrain.route(bot.level, at, b.centrePos, bot.now), b.centrePos)) return b;
        }
        return all.get(0);
    }

    private long lastNeutralOrder = -1000, clearBegan, clearCooldownUntil;
    private final Map<Long, int[]> campTries = new HashMap<>();
    private final java.util.Set<Long> hopelessCamps = new java.util.HashSet<>();
    private int inBaseBest = Integer.MAX_VALUE;
    private long inBaseSince, inBaseCooldownUntil;

    /** soldiers lost to monsters: x, z, tick. Two or more near one spot = a camp too strong for us */
    private final List<long[]> campLosses = new ArrayList<>();
    private final List<BlockPos> strongCamps = new ArrayList<>();
    /** army size when each strong camp beat us: worth another try with twice that many */
    private final Map<BlockPos, Integer> strongCampArmy = new HashMap<>();
    private int armyNow;
    private BlockPos remGoal;
    private long remSince;

    /** attack attrition: own soldiers lost {x,z,tick}, enemies killed by the army {tick} */
    private final List<long[]> attackLosses = new ArrayList<>();
    private final List<Long> attackKills = new ArrayList<>();
    private long lastAttrition;

    void lostInAttack(BlockPos p) {
        if (mode != Mode.ATTACK) return;
        attackLosses.add(new long[] { p.getX(), p.getZ(), bot.now });
    }

    void killedInAttack() {
        if (mode == Mode.ATTACK) attackKills.add(bot.now);
    }

    /** bleeding at one spot (archers shooting across water, a tower) while killing almost nothing */
    private boolean attrition(Intel.EnemyMemory m, Vec3 c, BuildingPlacement building) {
        attackLosses.removeIf(l -> bot.now - l[2] > 2400);
        attackKills.removeIf(t -> bot.now - t > 2400);
        if (bot.now - lastAttrition < 1200) return false;
        int lost = 0;
        for (long[] l : attackLosses) if (Math.abs(l[0] - c.x) + Math.abs(l[1] - c.z) < 50) lost++;
        if (lost < Math.max(7, armyNow / 3) || attackKills.size() * 3 > lost) return false;
        lastAttrition = bot.now;
        attackLosses.clear();
        attackKills.clear();
        if (building != null) buildingBlacklist.put(building.originPos, bot.now + 6000);
        giveUp(m.name);
        bot.log("attrition: lost " + lost + " at " + BlockPos.containing(c).toShortString() + ", switching target");
        mode = Mode.RETREAT;
        retreats++;
        retreatUntil = bot.now + 300;
        lastAttackEnd = bot.now;
        return true;
    }

    void lostToCamp(BlockPos p) {
        campLosses.removeIf(l -> bot.now - l[2] > 3600);
        campLosses.add(new long[] { p.getX(), p.getZ(), bot.now });
        int n = 0;
        for (long[] l : campLosses) if (Math.abs(l[0] - p.getX()) <= 16 && Math.abs(l[1] - p.getZ()) <= 16) n++;
        if (n >= 2 && !strongCamp(p)) {
            strongCamps.add(p);
            strongCampArmy.put(p, Math.max(5, armyNow));
            clearCooldownUntil = bot.now + 1200;
            lastNeutralOrder = -1000;
            bot.log("camp at " + p.toShortString() + " is too strong: pulling back and leaving it alone");
            if (!bot.intel.army.isEmpty()) Orders.move(bot.name, bot.intel.army, rally(bot.intel));
        }
    }

    /** a camp that beat our soldiers lies within r of p: villagers do not gather there until it is cleared */
    boolean nearStrongCamp(BlockPos p, double r) {
        for (BlockPos s : strongCamps) if (s.distSqr(p) < r * r) return true;
        return false;
    }

    private boolean strongCamp(BlockPos p) {
        // the expert comes back with twice the army that failed (a camp guarding the next woods must go)
        for (BlockPos s : strongCamps)
            if (s.distSqr(p) < 28 * 28 && !(bot.difficulty == Difficulty.EXPERT && armyNow >= 2 * strongCampArmy.getOrDefault(s, 99))) return true;
        return false;
    }

    private static long campKey(LivingEntity n) { return ((long) (n.getBlockX() >> 4) << 32) ^ (n.getBlockZ() >> 4); }

    /**
     * Neutral monster camps next to the base kill villagers (like AoE2 wolves): once the army is
     * clearly stronger than a camp near home, go and clear it.
     */
    private boolean clearNeutrals(Intel in) {
        if (bot.difficulty != Difficulty.EXPERT || in.army.isEmpty()) return false;
        armyNow = in.army.size();
        // monsters that wandered into the base: they kill villagers and freeze the whole economy
        // (every job near them is "dangerous") - kill them now, whatever else is going on
        List<LivingEntity> inBase = new ArrayList<>();
        for (LivingEntity n : in.neutrals) {
            for (BuildingPlacement b : in.own) {
                if (hopelessCamps.contains(campKey(n)) || strongCamp(n.blockPosition())) break;
                if (n.distanceToSqr(Vec3.atCenterOf(b.centrePos)) < (b.isCapitol ? 30 * 30 : 14 * 14)) { inBase.add(n); break; }
            }
        }
        if (!inBase.isEmpty() && bot.now >= inBaseCooldownUntil) {
            // no kill for half a minute: these cannot be reached either - leave them alone
            if (inBase.size() < inBaseBest || bot.now - inBaseSince > 2400) { inBaseBest = inBase.size(); inBaseSince = bot.now; }
            else if (bot.now - inBaseSince > 600) {
                for (LivingEntity n : inBase) hopelessCamps.add(campKey(n));
                bot.log("monsters in the base cannot be reached, ignoring them");
                inBaseCooldownUntil = bot.now + 2400;
                inBaseBest = Integer.MAX_VALUE;
                return false;
            }
            if (bot.now - lastNeutralOrder > 40) {
                lastNeutralOrder = bot.now;
                for (Mob u : in.army) {
                    LivingEntity t = null;
                    for (LivingEntity n : inBase) if (t == null || u.distanceToSqr(n) < u.distanceToSqr(t)) t = n;
                    Orders.attack(bot.name, List.of(u), t);
                }
                bot.log("monsters in the base: " + inBase.size());
            }
            return true;
        }
        // opening: a camp by the base is cleared by the first soldiers, as soon as they clearly outmatch it
        double homeCamp = homeCampStrength(in);
        boolean earlyClear = homeCamp > 0 && in.army.size() >= 3 && UnitEval.strength(in.army) >= 2.0 * homeCamp;
        if (in.army.size() < 6 && !earlyClear) return false;
        // 1vN: the opponents' first waves arrive together from minute 14 on. A small army off hunting
        // a camp then is caught away from home (and has already lost units to the camp).
        double secs = PlayerServerEvents.rtsGameTicks / 20.0;
        boolean waveTime = bot.outnumbered() && secs >= 780 && in.army.size() < 16;
        // a camp hunt may not eat the army's whole game: at most one minute, then two minutes off
        // monsters are killing villagers right now: protecting the economy beats the usual pause
        boolean killing = bot.difficulty == Difficulty.EXPERT && !waveTime && in.recentDeath(in.home(), 90, 1200) != null;
        if (bot.now < clearCooldownUntil && !(killing && bot.now >= clearCooldownUntil - 2000)) return false;
        if (bot.now - lastNeutralOrder > 300) clearBegan = bot.now;
        if (bot.now - clearBegan > 1200) { clearCooldownUntil = bot.now + 2400; return false; }
        Vec3 h = Vec3.atCenterOf(in.home());
        LivingEntity near = null;
        for (LivingEntity n : in.neutrals) {
            if (n.position().distanceToSqr(h) > (waveTime ? 28 * 28 : 45 * 45) || strongCamp(n.blockPosition())) continue;
            if (near == null || n.position().distanceToSqr(h) < near.position().distanceToSqr(h)) near = n;
        }
        BlockPos goal;
        String what;
        if (near != null) {
            List<LivingEntity> camp = new ArrayList<>();
            for (LivingEntity n : in.neutrals) if (n.distanceToSqr(near) < 16 * 16) camp.add(n);
            if (UnitEval.strength(in.army) < (earlyClear ? 2.0 : 3.0) * UnitEval.strength(camp)) return false;
            // a camp the army cannot get at (fenced in, on a pillar): stop trying after a minute
            long key = campKey(near);
            if (hopelessCamps.contains(key)) return false;
            int[] st = campTries.computeIfAbsent(key, k -> new int[] { camp.size(), (int) bot.now });
            if (camp.size() < st[0]) { st[0] = camp.size(); st[1] = (int) bot.now; }
            else if (bot.now - st[1] > 1200) { hopelessCamps.add(key); bot.log("camp at " + near.blockPosition().toShortString() + " cannot be cleared, ignoring it"); return false; }
            goal = near.blockPosition();
            what = camp.size() + " seen";
        } else {
            // a camp we remember (or where villagers died) but cannot see right now: go and look,
            // with a force big enough for a typical camp
            // the spot where villagers are being killed right now comes first, then the nearest danger
            BlockPos d = bot.difficulty == Difficulty.EXPERT ? in.recentDeath(in.home(), 90, 1200) : null;
            if (d == null) d = in.nearestDanger(in.home(), 70);
            // monsters sitting on the next woods / ore the economy needs (further out than the base)
            BlockPos rb = bot.economy.blockedByDanger;
            if (d == null && rb != null && bot.now - bot.economy.blockedAt < 2400) d = in.nearestDanger(rb, 30);
            if (d == null || in.army.size() < (bot.outnumbered() ? 8 : bot.hazardDeaths >= 4 && d.distSqr(in.home()) < 50 * 50 ? 3 : 5) || waveTime || strongCamp(d)) return false;
            // even game: a far camp is not worth the army being away when the enemy's first attack comes
            // (minute 10-11 on Mir's: the army chased camps 70 blocks out, lost units to them, and the
            // hard AI's eight vindicators walked into an empty base)
            if (bot.difficulty == Difficulty.EXPERT && !bot.outnumbered() && d.distSqr(in.home()) > 40 * 40
                    && (!safeToBoom(in) || in.army.size() < 8)) return false;
            Vec3 c = centroid(in.army);
            if (c.distanceToSqr(Vec3.atCenterOf(d)) < 8 * 8) { // nothing there any more
                in.forgetDanger(d, 18);
                final BlockPos gone = d;
                strongCamps.removeIf(sc -> sc.distSqr(gone) < 24 * 24);
                return false;
            }
            // the army cannot get there (cliff, water): forget that spot after 45 s so the next one gets a turn
            if (bot.difficulty == Difficulty.EXPERT) {
                if (remGoal == null || remGoal.distSqr(d) > 6 * 6) { remGoal = d; remSince = bot.now; }
                else if (bot.now - remSince > 900) { bot.log("cannot reach danger at " + d.toShortString() + ", dropping it"); in.forgetDanger(d, 10); remGoal = null; return false; }
                // monsters met on the way are fought, not walked past
                LivingEntity met = null;
                for (LivingEntity n : in.neutrals) if (n.position().distanceToSqr(c) < 18 * 18 && !strongCamp(n.blockPosition()) && (met == null || n.position().distanceToSqr(c) < met.position().distanceToSqr(c))) met = n;
                if (met != null) {
                    List<LivingEntity> camp = new ArrayList<>();
                    for (LivingEntity n : in.neutrals) if (n.distanceToSqr(met) < 16 * 16) camp.add(n);
                    if (UnitEval.strength(in.army) >= 2.0 * UnitEval.strength(camp)) near = met;
                }
            }
            goal = near != null ? near.blockPosition() : d;
            what = near != null ? "met on the way" : "remembered";
        }
        if (bot.now - lastNeutralOrder > 100) {
            lastNeutralOrder = bot.now;
            bot.log("clearing neutral camp at " + goal.toShortString() + " (" + what + ")");
            if (near != null) {
                // attack-move does not pick fights with unowned monsters: name the target for each unit
                List<LivingEntity> camp = new ArrayList<>();
                for (LivingEntity n : in.neutrals) if (n.distanceToSqr(near) < 20 * 20) camp.add(n);
                for (Mob u : in.army) {
                    LivingEntity t = null;
                    for (LivingEntity n : camp) if (t == null || u.distanceToSqr(n) < u.distanceToSqr(t)) t = n;
                    if (t != null) Orders.attack(bot.name, List.of(u), t);
                }
            } else {
                Orders.move(bot.name, in.army, waypoint(centroid(in.army), goal));
            }
        }
        return true;
    }

    private void gather(Intel in) {
        if (clearNeutrals(in)) return;
        BlockPos r = rally(in);
        java.util.Set<Integer> visiting = upgradeVisits;
        List<Mob> stray = new ArrayList<>();
        for (Mob u : in.army) {
            if (visiting.contains(u.getId())) continue;
            if (u.blockPosition().distSqr(r) > 10 * 10 && ((Unit) u).isIdle()) stray.add(u);
        }
        if (!stray.isEmpty()) Orders.move(bot.name, stray, r);
    }

    // ------------------------------------------------------------------ towers

    private final Map<Integer, Long> sentToTower = new HashMap<>();
    private long lastUngarrison = -1000;

    /** keep ranged units inside the towers: +10 range from height, the turtle half of 1vN play */
    private void manageTowers(Intel in) {
        if (bot.params.i("towers") <= 0) return;
        sentToTower.values().removeIf(t -> bot.now - t > 600);
        // towers are for holding off waves: at most a quarter of the ranged army (8 at most) sits in
        // them, and only while several enemies are alive or a raid came lately. A late army stuck at
        // the population cap needs those pillagers in the field (16 sat in towers in a timed-out game)
        boolean towerTime = bot.outnumbered() || bot.now - lastThreatTick < 2400;
        int towerCap = towerTime ? Math.min(8, (in.army.size() + in.garrisoned.size()) / 4) : 0;
        if (in.garrisoned.size() > towerCap && !underAttack && (in.pop >= in.popCap - 6 || towerCap == 0) && bot.now - lastUngarrison > 200) {
            List<Mob> out = new ArrayList<>(in.garrisoned.subList(0, in.garrisoned.size() - towerCap));
            Orders.ungarrison(bot.name, out);
            lastUngarrison = bot.now;
            if (bot.verbose) bot.log("towers: " + out.size() + " units leave the towers (cap " + towerCap + ")");
        }
        int towerRoom = towerCap - in.garrisoned.size();
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt) continue;
            var gba = b.getBuilding().getActiveAddon(com.solegendary.reignofnether.building.addon.GarrisonableBuildingAddon.class);
            if (gba == null) continue;
            int occ = com.solegendary.reignofnether.building.addon.GarrisonableBuildingAddon.getNumOccupants(b);
            int pending = 0;
            for (Map.Entry<Integer, Long> e : sentToTower.entrySet()) {
                var ent = bot.level.getEntity(e.getKey());
                if (ent instanceof Unit u && !u.isGarrisoned() && ent.blockPosition().distSqr(b.centrePos) < 30 * 30) pending++;
            }
            int need = Math.min(gba.getCapacity() - occ - pending, towerRoom - pending);
            if (need <= 0) continue;
            List<Mob> cand = new ArrayList<>();
            for (Mob m : in.army) {
                if (!UnitEval.isRanged(m) || sentToTower.containsKey(m.getId())) continue;
                if (!(m instanceof Unit u) || !u.canGarrison()) continue;
                if (mode == Mode.ATTACK && m.distanceToSqr(Vec3.atCenterOf(in.home())) > 40 * 40) continue;
                cand.add(m);
            }
            // keep at least half of the ranged units in the field army
            int ranged = 0;
            for (Mob m : in.army) if (UnitEval.isRanged(m)) ranged++;
            if (ranged < 4) continue;
            cand.sort(Comparator.comparingDouble(m -> m.distanceToSqr(Vec3.atCenterOf(b.centrePos))));
            List<Mob> go = cand.subList(0, Math.min(need, cand.size()));
            if (go.isEmpty()) continue;
            for (Mob m : go) sentToTower.put(m.getId(), bot.now);
            towerRoom -= go.size();
            Orders.garrison(bot.name, new ArrayList<>(go), b.centrePos);
            in.army.removeAll(go);
        }
    }

    // ------------------------------------------------------------------ scouting

    private void scout(Intel in) {
        boolean allFound = true;
        for (Intel.EnemyMemory m : in.enemies.values()) if (m.alive && m.anyBuilding() == null) allFound = false;
        Mob s = in.scouts.isEmpty() ? null : in.scouts.get(0);
        double secs = PlayerServerEvents.rtsGameTicks / 20.0;
        if (s == null && !allFound && secs > 200 && in.army.size() >= 2 && bot.params.b("scout")) {
            if (explorer == null || !explorer.isAlive()) explorer = in.army.get(in.army.size() - 1);
            s = explorer;
        } else if (s != null) {
            explorer = null;
        }
        if (s == null) return;
        if (scoutPoints.isEmpty()) buildScoutPoints(in.home());
        BlockPos wp = scoutPoints.get(scoutIdx % scoutPoints.size());
        if (s.blockPosition().distSqr(wp) < 8 * 8 || ((Unit) s).isIdle()) {
            scoutIdx++;
            wp = scoutPoints.get(scoutIdx % scoutPoints.size());
            Orders.move(bot.name, List.of(s), wp);
        }
    }

    private void buildScoutPoints(BlockPos h) {
        // where the others probably start: maps are laid out symmetrically, so the mirror images of
        // our own start through the middle of the map (a player knows the map; with fog on, circling
        // the home would never reach a base 200 blocks away)
        var wb = bot.level.getWorldBorder();
        double cx = wb.getCenterX(), cz = wb.getCenterZ();
        int maxR = 150;
        if (wb.getSize() < 2000) {
            maxR = (int) Math.max(150, wb.getSize() * 0.7);
            int[][] mir = { { (int) (2 * cx - h.getX()), (int) (2 * cz - h.getZ()) }, { (int) (2 * cx - h.getX()), h.getZ() }, { h.getX(), (int) (2 * cz - h.getZ()) } };
            for (int[] q : mir) {
                if (Math.abs(q[0] - h.getX()) + Math.abs(q[1] - h.getZ()) < 40) continue;
                scoutPoints.add(new BlockPos(q[0], h.getY(), q[1]));
            }
            guessed.addAll(scoutPoints);
        }
        for (int r = 30; r <= maxR; r += 30) {
            int n = Math.max(6, r / 10);
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n + r * 0.01;
                scoutPoints.add(new BlockPos(h.getX() + (int) (Math.cos(a) * r), h.getY(), h.getZ() + (int) (Math.sin(a) * r)));
            }
        }
    }

    // ------------------------------------------------------------------ micro

    void micro(Intel in) {
        BotParams p = bot.params;
        boolean focus = p.b("focusFire"), kite = p.b("kiting"), save = p.b("retreatWounded");
        if (!focus && !kite && !save) return;
        Vec3 home = Vec3.atCenterOf(in.home());
        for (Mob u : in.army) {
            if (bot.now - lastOrder.getOrDefault(u.getId(), -1000L) < 6) continue;
            List<LivingEntity> near = in.enemiesNear(u.position(), 14);
            if (near.isEmpty()) continue;
            double range = Math.max(2.5, UnitEval.range(u));
            boolean ranged = UnitEval.isRanged(u);

            LivingEntity nearestMelee = null;
            double nmd = 1e9;
            for (LivingEntity e : near) {
                if (UnitEval.isRanged(e) || UnitEval.isWorker(e)) continue;
                double d = e.distanceToSqr(u);
                if (d < nmd) { nmd = d; nearestMelee = e; }
            }

            if (save && u.getHealth() < u.getMaxHealth() * 0.25 && UnitEval.popValue(u) >= 3 && nearestMelee != null && nmd < 16) {
                Vec3 d = home.subtract(u.position()).normalize().scale(10);
                order(u, () -> Orders.move(bot.name, List.of(u), BlockPos.containing(u.position().add(d))));
                continue;
            }
            // kiting (hit and step back): a ranged unit a melee unit is about to reach steps away,
            // then turns and shoots again at once - the melee unit spends its time walking
            if (kite && ranged && nearestMelee != null && nmd < Math.max(3.5, range * 0.45) * Math.max(3.5, range * 0.45)) {
                BlockPos to = kiteSpot(u, nearestMelee, home);
                if (to != null) {
                    order(u, () -> Orders.move(bot.name, List.of(u), to));
                    reattackAt.put(u.getId(), bot.now + 7);
                    continue;
                }
            }
            Long ra = reattackAt.get(u.getId());
            if (ra != null && bot.now >= ra) {
                reattackAt.remove(u.getId());
                LivingEntity t = bestTarget(u, near, range, true);
                if (t != null && u instanceof AttackerUnit) {
                    order(u, () -> Orders.attack(bot.name, List.of(u), t));
                    continue;
                }
            }
            if (!focus) continue;
            LivingEntity cur = ((Unit) u).getTargetGoal().getTarget();
            LivingEntity best = null;
            double bestScore = 1e18;
            for (LivingEntity e : near) {
                double d = Math.sqrt(e.distanceToSqr(u));
                if (ranged ? d > range + 1.5 : d > 8) continue;
                // kill fast what hurts most: low effective hp, high damage, prefer ranged/casters
                double score = UnitEval.effectiveHp(e) / Math.max(0.5, UnitEval.dps(e));
                if (UnitEval.isRanged(e)) score *= 0.7;
                if (UnitEval.isWorker(e)) score *= 3;
                score += d * 0.3;
                if (score < bestScore) { bestScore = score; best = e; }
            }
            if (best == null || best == cur) continue;
            if (cur != null && cur.isAlive() && cur.getHealth() < cur.getMaxHealth() * 0.3) continue; // finish it
            if (!(u instanceof AttackerUnit)) continue;
            LivingEntity tgt = best;
            order(u, () -> Orders.attack(bot.name, List.of(u), tgt));
        }
    }

    private final Map<Integer, Long> reattackAt = new HashMap<>();

    /** where to step back to: away from the attacker, leaning toward home, on walkable ground */
    private BlockPos kiteSpot(Mob u, LivingEntity from, Vec3 home) {
        Vec3 away = u.position().subtract(from.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        Vec3 toHome = new Vec3(home.x - u.getX(), 0, home.z - u.getZ());
        Vec3 dir = away.normalize().scale(0.75).add(toHome.lengthSqr() > 1 ? toHome.normalize().scale(0.25) : Vec3.ZERO).normalize();
        int y0 = u.blockPosition().getY();
        for (double rot : new double[] { 0, 0.6, -0.6, 1.2, -1.2 }) {
            double c = Math.cos(rot), s = Math.sin(rot);
            Vec3 d = new Vec3(dir.x * c - dir.z * s, 0, dir.x * s + dir.z * c).scale(5);
            int x = (int) Math.floor(u.getX() + d.x), z = (int) Math.floor(u.getZ() + d.z);
            int y = bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - y0) <= 2) return new BlockPos(x, y, z);
        }
        return null;
    }

    private LivingEntity bestTarget(Mob u, List<LivingEntity> near, double range, boolean ranged) {
        LivingEntity best = null;
        double bestScore = 1e18;
        for (LivingEntity e : near) {
            double d = Math.sqrt(e.distanceToSqr(u));
            if (ranged ? d > range + 3 : d > 8) continue;
            double score = UnitEval.effectiveHp(e) / Math.max(0.5, UnitEval.dps(e));
            if (UnitEval.isRanged(e)) score *= 0.7;
            if (UnitEval.isWorker(e)) score *= 3;
            score += d * 0.3;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    private void order(Mob u, Runnable r) {
        lastOrder.put(u.getId(), bot.now);
        r.run();
    }

    // ------------------------------------------------------------------ util

    /** the densest cluster of the army: the unit with most neighbours within 15, and everyone within 25 of it */
    static List<Mob> mainBody(List<Mob> us) {
        if (us.size() <= 2) return us;
        Mob best = us.get(0);
        int bestN = -1;
        for (Mob a : us) {
            int n = 0;
            for (Mob b : us) if (a.distanceToSqr(b) < 15 * 15) n++;
            if (n > bestN) { bestN = n; best = a; }
        }
        List<Mob> out = near(us, best.position(), 25);
        return out.isEmpty() ? us : out;
    }

    /** Minecraft pathfinding gives up on long trips: march in legs of about 40 blocks */
    BlockPos waypoint(Vec3 from, BlockPos goal) {
        Vec3 g = Vec3.atCenterOf(goal);
        Vec3 d = g.subtract(from);
        double len = Math.sqrt(d.x * d.x + d.z * d.z);
        if (len <= 10) return goal;
        // Minecraft's pathfinder only searches near the unit: on maps with plateaus it stops at the
        // cliff foot. Steer along a long-range surface route (it finds the ramps) instead.
        List<BlockPos> route = Terrain.route(bot.level, BlockPos.containing(from), goal, bot.now);
        // water or lava on the way (or no way at all): let the bridger look for a crossing (throttled)
        if (route != null && mode == Mode.ATTACK) bot.bridger.request(BlockPos.containing(from), goal);
        if (route != null && route.size() > 2) return Terrain.ahead(route, from, 18);
        if (len <= 45 || com.solegendary.reignofnether.unit.UnitServerEvents.rtsPathfinding) return goal;
        Vec3 p = from.add(d.x * 40 / len, 0, d.z * 40 / len);
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        return new BlockPos(x, bot.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    private int friendlyFire;

    /**
     * RON's iron golem is a vanilla iron golem underneath: hit by an ally in the crush (an arrow, a
     * sweep) it turns on that ally and kills it. Calm down any unit that targets one of our own.
     */
    private void stopFriendlyFire(Intel in) {
        for (Mob u : in.army) {
            LivingEntity t = u.getTarget();
            Object tg = ((Unit) u).getTargetGoal() == null ? null : ((Unit) u).getTargetGoal().getTarget();
            boolean bad = t instanceof Unit tu && bot.name.equals(tu.getOwnerName())
                    || tg instanceof Unit gu && bot.name.equals(gu.getOwnerName());
            if (!bad) continue;
            u.setTarget(null);
            if (u instanceof net.minecraft.world.entity.NeutralMob nm) nm.stopBeingAngry();
            u.setLastHurtByMob(null);
            Orders.stop(bot.name, List.of(u));
            if (friendlyFire++ % 20 == 0) bot.log("friendly fire stopped (" + friendlyFire + "): " + u.getType().toShortString());
        }
    }

    private static String unitDiag(List<Mob> force) {
        StringBuilder sb = new StringBuilder(" units:");
        for (int i = 0; i < Math.min(4, force.size()); i++) {
            Mob u = force.get(i * force.size() / 4);
            com.solegendary.reignofnether.unit.goals.MoveToTargetBlockGoal mg = ((Unit) u).getMoveGoal();
            Object tg = ((Unit) u).getTargetGoal() == null ? null : ((Unit) u).getTargetGoal().getTarget();
            net.minecraft.world.level.pathfinder.Path path = u.getNavigation().getPath();
            sb.append(" {").append(u.blockPosition().toShortString())
              .append(" mt=").append(mg == null || mg.getMoveTarget() == null ? "-" : mg.getMoveTarget().toShortString())
              .append(" fin=").append(mg == null || mg.getFinalNodePos() == null ? "-" : mg.getFinalNodePos().toShortString())
              .append(" bo=").append(mg != null && mg.isInBackoff())
              .append(" path=").append(path == null ? "-" : (path.canReach() ? "ok" : "partial") + path.getNodeCount() + (path.isDone() ? "done" : ""))
              .append(" tgt=").append(tg instanceof net.minecraft.world.entity.Entity en ? en.getType().toShortString() : String.valueOf(tg))
              .append("}");
        }
        return sb.toString();
    }

    private String routeDiag(Vec3 c, BlockPos goal) {
        long t0 = System.nanoTime();
        List<BlockPos> r = Terrain.route(bot.level, BlockPos.containing(c), goal, bot.now);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (r == null) return " route=null " + ms + "ms";
        return " route=" + r.size() + " reaches=" + Terrain.reaches(r, goal) + " end=" + r.get(r.size() - 1).toShortString()
                + " wp=" + Terrain.ahead(r, c, 18).toShortString() + " " + ms + "ms";
    }

    static Vec3 centroid(List<? extends LivingEntity> us) {
        double x = 0, y = 0, z = 0;
        for (LivingEntity u : us) { x += u.getX(); y += u.getY(); z += u.getZ(); }
        int n = Math.max(1, us.size());
        return new Vec3(x / n, y / n, z / n);
    }

    static List<Mob> near(List<Mob> us, Vec3 c, double r) {
        List<Mob> out = new ArrayList<>();
        for (Mob u : us) if (u.position().distanceToSqr(c) <= r * r) out.add(u);
        return out;
    }
}
