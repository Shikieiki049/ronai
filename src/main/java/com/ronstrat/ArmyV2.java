package com.ronstrat;

import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingServerEvents;
import com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The expert's attack decisions by fight forecast (parameter armyV2) instead of strength ratios:
 * what the enemy probably has (seen units still alive, plus what their barracks made since we
 * looked), whether our army beats that with enough left, and whether a fight in progress is lost.
 */
final class ArmyV2 {
    private final Bot bot;
    private long lastLog = -10000;
    CombatSim.Result lastForecast;

    ArmyV2(Bot bot) { this.bot = bot; }

    /** the enemy army as the forecast sees it; atHome adds what defends a base (militia, garrisons) */
    List<CombatSim.U> estimate(Intel in, Intel.EnemyMemory m, boolean atHome) {
        m.knownStrength(bot.now); // drops dead and stale sightings
        List<CombatSim.U> out = new ArrayList<>();
        for (LivingEntity e : m.seenUnits.values()) if (!UnitEval.isWorker(e)) out.add(CombatSim.of(e));
        CombatSim.U typical = typical(out, in.army);
        double extra;
        if (m.lastSeenArmy < 0) {
            // never seen: what an opponent usually fields by this minute
            extra = Math.max(0, Math.min(30, (bot.now / 1200.0 - 6.5) * bot.params.get("v2UnseenPerMinute")));
        } else {
            double mins = Math.min(5, Math.max(0, bot.now - m.lastSeenArmy) / 1200.0);
            extra = mins * bot.params.get("v2GrowthPerMinute") * Math.max(1, Math.min(4, barracks(m)));
        }
        if (atHome) extra += bot.params.get("v2DefenceUnits");
        for (int i = 0; i < (int) Math.round(extra); i++) out.add(typical.copy());
        double scale = bot.params.get("v2EnemyScale");
        if (scale > 0) for (CombatSim.U u : out) { u.hp *= scale; u.hp0 *= scale; }
        if (atHome) {
            // the defender rings the bell: a share of the villagers fight as militia; towers shoot
            int villagers = 0;
            for (LivingEntity e : in.visibleEnemies)
                if (UnitEval.isWorker(e) && e instanceof com.solegendary.reignofnether.unit.interfaces.Unit u && m.name.equals(u.getOwnerName())) villagers++;
            int militia = (int) Math.round(villagers * bot.params.get("v2MilitiaShare"));
            for (int i = 0; i < militia; i++) {
                CombatSim.U v = new CombatSim.U();
                v.hp = v.hp0 = 25; v.dps = 2.5; v.range = 2; v.speed = 4;
                out.add(v);
            }
            int towers = 0;
            for (BuildingPlacement b : BuildingServerEvents.getBuildings())
                if (m.name.equals(b.ownerName) && b.isBuilt && b.getBuilding() == bot.plan.tower) towers++;
            for (int i = 0; i < towers; i++) out.add(CombatSim.tower());
        }
        return out;
    }

    private static CombatSim.U typical(List<CombatSim.U> seen, Collection<? extends LivingEntity> own) {
        List<CombatSim.U> src = seen;
        if (src.isEmpty()) src = CombatSim.of(own);
        CombatSim.U t = new CombatSim.U();
        if (src.isEmpty()) {
            t.hp = t.hp0 = 45; t.dps = 4; t.range = 2; t.speed = 4;
            return t;
        }
        for (CombatSim.U u : src) {
            t.hp0 += u.hp0; t.armour += u.armour; t.dps += u.dps; t.speed += u.speed;
        }
        int n = src.size();
        t.hp0 /= n; t.armour /= n; t.dps /= n; t.speed /= n;
        t.hp = t.hp0;
        t.range = 2;
        return t;
    }

    private int barracks(Intel.EnemyMemory m) {
        int n = 0;
        for (BuildingPlacement b : BuildingServerEvents.getBuildings()) {
            if (!m.name.equals(b.ownerName) || !b.isBuilt || b.isCapitol || !(b instanceof ProductionPlacement)) continue;
            if (m.buildings.containsKey(b.originPos)) n++;
        }
        return n;
    }

    /** would this force beat what the enemy probably has at home, with enough left to go on and destroy the base? */
    boolean worthLeaving(Intel in, Intel.EnemyMemory m, List<Mob> force, List<Bot> allies) {
        List<LivingEntity> own = new ArrayList<>(force);
        for (Bot o : allies) own.addAll(o.intel.army);
        CombatSim.Result r = CombatSim.run(CombatSim.of(own), estimate(in, m, true), 12);
        lastForecast = r;
        boolean go = r.win && r.ownLeft >= bot.params.get("v2LeaveLeft");
        if (go || bot.now - lastLog > 600) {
            lastLog = bot.now;
            bot.log("sim: " + (go ? "leave" : "wait") + " " + own.size() + " vs " + m.name + " est " + estimate(in, m, true).size() + ": " + r);
        }
        return go;
    }

    /**
     * A fight in progress is lost: the forecast leaves us under a quarter while they keep a good part,
     * the rest of the army would not turn it, and we are fast enough to get away (otherwise fighting on
     * does more damage than dying on the run).
     */
    boolean lost(List<? extends LivingEntity> localFriends, List<? extends LivingEntity> localEnemies, List<? extends LivingEntity> everyone) {
        if (localFriends.isEmpty() || localEnemies.isEmpty()) return false;
        CombatSim.Result r = CombatSim.run(localFriends, localEnemies, 0, 4);
        lastForecast = r;
        if (r.win || r.ownLeft >= bot.params.get("v2RetreatLeft") || r.enemyLeft < 0.3) return false;
        if (everyone.size() > localFriends.size()) {
            CombatSim.Result all = CombatSim.run(everyone, localEnemies, 0, 10);
            if (all.win && all.ownLeft >= 0.3) return false;
        }
        double own = 0, en = 0;
        int enN = 0;
        for (LivingEntity e : localFriends) own += CombatSim.of(e).speed;
        for (LivingEntity e : localEnemies) if (!UnitEval.isWorker(e)) { en += CombatSim.of(e).speed; enN++; }
        own /= localFriends.size();
        if (enN > 0 && own < 0.9 * en / enN) return false; // cannot outrun them
        bot.log("sim: fight lost " + localFriends.size() + " vs " + localEnemies.size() + ": " + r);
        return true;
    }
}
