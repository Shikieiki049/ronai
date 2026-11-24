package com.ronstrat;

import com.solegendary.reignofnether.ability.Ability;
import com.solegendary.reignofnether.ability.HeroAbility;
import com.solegendary.reignofnether.unit.UnitAction;
import com.solegendary.reignofnether.unit.interfaces.HeroUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Units with spells (a player's advice: witches are the villagers' only healing and their harming
 * potions hit hard; heroes level up, so their skills should be learnt and used).
 *  - witches: autocast lingering harming potions on enemies; a healing potion on a badly hurt soldier
 *    nearby when one is off cooldown (parameter witchMicro)
 *  - heroes: spend skill points as they come (the one-rank ultimate as soon as allowed, the others
 *    spread evenly), and in a fight use every learnt active skill that is off cooldown and paid for in
 *    mana (parameter heroSkills)
 */
final class Casters {
    private final Bot bot;
    private final Set<Integer> autocastSet = new HashSet<>();
    private final Map<Integer, Long> lastCast = new HashMap<>();
    private long nextRankLog;

    Casters(Bot bot) { this.bot = bot; }

    void think(Intel in) {
        if (bot.difficulty != Difficulty.EXPERT) return;
        boolean witches = bot.params.b("witchMicro"), heroes = bot.params.b("heroSkills");
        if (!witches && !heroes) return;
        autocastSet.removeIf(id -> in.army.stream().noneMatch(m -> m.getId() == id));
        if (bot.params.b("unitSkills")) try { unitSkills(in); } catch (Throwable t) { if (bot.now > nextRankLog) { bot.log("unit skill error: " + t); nextRankLog = bot.now + 6000; } }
        for (Mob u : in.army) {
            try {
                if (witches && isWitch(u)) witch(in, u);
                if (heroes && u instanceof HeroUnit h) hero(in, u, h);
            } catch (Throwable t) {
                if (bot.now > nextRankLog) { bot.log("caster error: " + t); nextRankLog = bot.now + 6000; }
            }
        }
    }

    // ------------------------------------------------------------------ the soldiers' own skills (the user)

    private final Map<Integer, Long> rangedHitAt = new HashMap<>();
    private final Map<Integer, Long> skillAt = new HashMap<>();
    private long lastMountCheck = -1000;

    /** our soldiers within r of p, not counting one */
    private static int friends(Intel in, net.minecraft.world.phys.Vec3 p, double r, Mob not) {
        int n = 0;
        for (Mob m : in.army) if (m != not && m.position().distanceToSqr(p) <= r * r) n++;
        for (Mob m : in.workers) if (m.position().distanceToSqr(p) <= r * r) n++;
        return n;
    }

    private void cast(Mob u, Ability a, LivingEntity target, BlockPos at) {
        Orders.ability(bot.name, a.action, target == null ? -1 : target.getId(), List.of(u), at);
        skillAt.put(u.getId(), bot.now);
    }

    /**
     * Brutes raise their shields under arrows (66% less ranged damage, half the speed) and lower them again
     * 6 s after the last arrow; wither skeletons let off their cloud in a crowd of enemies with few friends
     * close; wraiths fear the strongest enemy fighter on one of ours; blazes put their firewall and spiders
     * their webs where the enemies stand thick and none of ours do; skeletons mount idle spiders and
     * headhunters idle hoglins (spider jockeys, hoglin riders).
     */
    private void unitSkills(Intel in) {
        for (Mob u : in.army) {
            if (!(u instanceof Unit un) || Orders.isCasting(u.getId())) continue;
            LivingEntity by = u.getLastHurtByMob();
            if (by != null && UnitEval.isRanged(by) && u.tickCount - u.getLastHurtByMobTimestamp() < 10) rangedHitAt.put(u.getId(), bot.now);
            if (u instanceof com.solegendary.reignofnether.unit.units.piglins.BruteUnit br) {
                Ability sh = ability(un, UnitAction.TOGGLE_SHIELD_RAISE);
                if (sh == null) continue;
                boolean underFire = bot.now - rangedHitAt.getOrDefault(u.getId(), -100000L) < 120;
                if (underFire != br.isHoldingUpShield() && bot.now - skillAt.getOrDefault(u.getId(), -1000L) > 20) cast(u, sh, null, u.blockPosition());
                continue;
            }
            // wraiths: RON's own autocast puts the fear on what the wraith attacks. Our ordered cast replaced
            // its attack-move, and after the fear it stood (M11_us: -7.1%, early losses on Cabana)
            if (u instanceof com.solegendary.reignofnether.unit.units.monsters.WraithUnit) {
                Ability fear = ability(un, UnitAction.FEAR);
                if (fear != null && fear.autocastEnableAction != null && !fear.isAutocasting(un) && autocastSet.add(u.getId()))
                    Orders.ability(bot.name, fear.autocastEnableAction, -1, List.of(u), u.blockPosition());
                continue;
            }
            if (bot.now - skillAt.getOrDefault(u.getId(), -1000L) < 40) continue;
            List<LivingEntity> foes = enemiesNear(in, u, 16);
            if (foes.isEmpty()) continue;
            for (Ability a : un.getAbilities().get()) {
                if (!a.isOffCooldown(un)) continue;
                if (a.action == UnitAction.WITHER_CLOUD) {
                    int close = 0;
                    for (LivingEntity e : foes) if (e.distanceToSqr(u) < 4 * 4) close++;
                    if (close >= 3 && friends(in, u.position(), 4, u) <= 1) { cast(u, a, null, u.blockPosition()); break; }
                } else if (a.action == UnitAction.FEAR) {
                    LivingEntity best = null;
                    for (LivingEntity e : foes) {
                        if (e instanceof com.solegendary.reignofnether.unit.interfaces.HeroUnit || UnitEval.isWorker(e) || e.distanceToSqr(u) > (a.range + 1) * (a.range + 1)) continue;
                        if (friends(in, e.position(), 3, u) == 0) continue; // only one that is on one of ours
                        if (best == null || UnitEval.power(e) > UnitEval.power(best)) best = e;
                    }
                    if (best != null) { cast(u, a, best, best.blockPosition()); break; }
                } else if (a.action == UnitAction.SHOOT_FIREWALL || a.action == UnitAction.SPIN_WEBS) {
                    LivingEntity best = null;
                    int bestN = 1;
                    for (LivingEntity e : foes) {
                        if (e.distanceToSqr(u) > (a.range + 1) * (a.range + 1)) continue;
                        int n = 0;
                        for (LivingEntity o : foes) if (o.distanceToSqr(e) < 3.5 * 3.5) n++;
                        if (n > bestN && friends(in, e.position(), 4, u) == 0) { bestN = n; best = e; }
                    }
                    if (best != null) { cast(u, a, best, best.blockPosition()); break; }
                }
            }
        }
        // mounts, out of a fight: a skeleton onto an idle spider, a headhunter onto an idle hoglin
        if (bot.now - lastMountCheck < 100) return;
        lastMountCheck = bot.now;
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Mob u : in.army) {
            if (!(u instanceof Unit un) || u.isPassenger() || !in.enemiesNear(u.position(), 14).isEmpty()) continue;
            UnitAction act = null;
            Class<?> steed = null;
            if (u instanceof com.solegendary.reignofnether.unit.units.monsters.SkeletonUnit || u.getClass().getSimpleName().equals("StrayUnit")) { act = UnitAction.MOUNT_SPIDER; steed = com.solegendary.reignofnether.unit.units.monsters.SpiderUnit.class; }
            else if (u instanceof com.solegendary.reignofnether.unit.units.piglins.HeadhunterUnit) { act = UnitAction.MOUNT_HOGLIN; steed = com.solegendary.reignofnether.unit.units.piglins.HoglinUnit.class; }
            if (act == null || ability(un, act) == null) continue;
            Mob mount = null;
            for (Mob o : in.army) {
                if (!steed.isInstance(o) || o.isVehicle() || taken.contains(o.getId()) || o.distanceToSqr(u) > 24 * 24) continue;
                if (mount == null || o.distanceToSqr(u) < mount.distanceToSqr(u)) mount = o;
            }
            if (mount == null) continue;
            taken.add(mount.getId());
            Orders.ability(bot.name, act, mount.getId(), List.of(u), mount.blockPosition());
        }
    }

    private static boolean isWitch(Mob u) { return u.getClass().getSimpleName().equals("WitchUnit"); }

    private static Ability ability(Unit u, UnitAction action) {
        for (Ability a : u.getAbilities().get()) if (a.action == action) return a;
        return null;
    }

    private List<LivingEntity> enemiesNear(Intel in, Mob u, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : in.visibleEnemies) if (!UnitEval.isWorker(e) && e.distanceToSqr(u) < r * r) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ witches

    private void witch(Intel in, Mob w) {
        Unit u = (Unit) w;
        // harming potions by themselves on whatever comes in range
        if (autocastSet.add(w.getId())) {
            Ability harm = ability(u, UnitAction.THROW_LINGERING_HARMING_POTION);
            if (harm != null && !harm.isAutocasting(u)) {
                Orders.ability(bot.name, UnitAction.THROW_LINGERING_HARMING_POTION_AUTOCAST_ENABLE, -1, List.of(w), w.blockPosition());
                if (bot.verbose) bot.log("witch #" + w.getId() + ": harming potions on autocast");
            }
        }
        // a healing potion for the worst-hurt soldier within throwing range
        Ability heal = ability(u, UnitAction.THROW_HEALING_POTION);
        if (!com.solegendary.reignofnether.research.ResearchServerEvents.playerHasResearch(bot.name,
                com.solegendary.reignofnether.building.production.ProductionItems.RESEARCH_HEALING_POTIONS)) return;
        if (heal == null || !heal.isOffCooldown(u) || bot.now - lastCast.getOrDefault(w.getId(), -1000L) < 60) return;
        LivingEntity worst = null;
        double r = Math.max(6, heal.range);
        for (Mob a : in.army) {
            if (a == w || a.distanceToSqr(w) > r * r) continue;
            double f = a.getHealth() / a.getMaxHealth();
            if (f < 0.5 && (worst == null || f < worst.getHealth() / worst.getMaxHealth())) worst = a;
        }
        if (worst != null) {
            Orders.ability(bot.name, UnitAction.THROW_HEALING_POTION, worst.getId(), List.of(w), worst.blockPosition());
            bot.army.witchHeals++;
            lastCast.put(w.getId(), bot.now);
            if (bot.verbose) bot.log("witch heals " + worst.getType().toShortString() + " at " + (int) (100 * worst.getHealth() / worst.getMaxHealth()) + "% health");
        }
    }

    // ------------------------------------------------------------------ heroes

    private long nextHeroLog;

    private void hero(Intel in, Mob m, HeroUnit h) {
        rankUp(h);
        if (bot.verbose && bot.now >= nextHeroLog) {
            nextHeroLog = bot.now + 1200;
            bot.log("hero status: " + m.getType().toShortString() + " level " + h.getHeroLevel() + " exp " + h.getExperience()
                    + " points " + h.getSkillPoints() + " hp " + (int) m.getHealth() + "/" + (int) m.getMaxHealth() + " at " + m.blockPosition().toShortString());
        }
        // a cast under way: done once the skill is on cooldown (it went off) - then the army may order it again
        HeroAbility pend = pending.get(m.getId());
        if (pend != null) {
            if (!pend.isOffCooldown(h) || !Orders.isCasting(m.getId())) {
                boolean went = !pend.isOffCooldown(h);
                if (went) castsDone++; else castsLost++;
                if (bot.verbose) bot.log("hero " + m.getType().toShortString() + " " + pend.getClass().getSimpleName() + (went ? " went off" : " did not go off (3 s)")
                        + " - " + castsDone + " cast, " + castsLost + " lost");
                pending.remove(m.getId()); Orders.release(m.getId());
            }
            else return;
        }
        List<LivingEntity> foes = enemiesNear(in, m, 14);
        for (LivingEntity n : in.neutrals) if (n.distanceToSqr(m) < 14 * 14) foes.add(n);
        if (foes.isEmpty() || bot.now - lastCast.getOrDefault(m.getId(), -1000L) < 20) return;
        LivingEntity near = null;
        for (LivingEntity e : foes) if (near == null || e.distanceToSqr(m) < near.distanceToSqr(m)) near = e;
        for (HeroAbility a : h.getHeroAbilities()) {
            if (a.getRank(h) <= 0 || !a.isOffCooldown(h) || h.getMana() < a.manaCost) continue;
            String n = a.getClass().getSimpleName();
            if (n.contains("Passive")) continue;
            // the big one (a one-rank ultimate) only for a real fight
            if (a.maxRank <= 1 && foes.size() < 4 && m.getHealth() > 0.5 * m.getMaxHealth()) continue;
            // out of reach: not now (walking in under fire got it turned round or killed); a point skill aims
            // at the thick of the enemies, not just the nearest one
            if (a.range > 0 && near.distanceToSqr(m) > (a.range + 1) * (a.range + 1)) continue;
            BlockPos aim = a.canTargetEntities ? near.blockPosition() : thickest(foes, near);
            Orders.cast(bot.name, a.action, a.canTargetEntities ? near.getId() : -1, m, aim, 3000);
            pending.put(m.getId(), a);
            lastCast.put(m.getId(), bot.now);
            if (bot.verbose) bot.log("hero " + m.getType().toShortString() + " casts " + n + " (" + foes.size() + " foes)");
            break;
        }
    }

    private final Map<Integer, HeroAbility> pending = new HashMap<>();
    int castsDone, castsLost;

    /** the enemy with the most others within 4 blocks (where an area skill does most) */
    private static BlockPos thickest(List<LivingEntity> foes, LivingEntity fallback) {
        LivingEntity best = fallback;
        int bestN = -1;
        for (LivingEntity e : foes) {
            int n = 0;
            for (LivingEntity o : foes) if (o.distanceToSqr(e) <= 16) n++;
            if (n > bestN) { bestN = n; best = e; }
        }
        return best.blockPosition();
    }

    /** spend skill points: the one-rank ultimate first when the level allows, then the lowest-ranked skill */
    private void rankUp(HeroUnit h) {
        int guard = 0;
        while (h.getSkillPoints() > 0 && guard++ < 10) {
            HeroAbility pick = null;
            // the royal guard: Battle Rage first - its health regeneration is the only way it heals without
            // witches (the user); a guard without it stood at 5-28 of 125 health for the rest of the game
            // ...and the necromancer Raise Dead first: its two zombies are the escort it levels with (the user)
            for (HeroAbility a : h.getHeroAbilities()) {
                String cn = a.getClass().getSimpleName();
                if ((cn.equals("BattleRagePassive") || cn.equals("RaiseDead")) && a.getRank(h) == 0
                        && h.getHeroLevel() >= a.getLevelRequirement(h)) pick = a;
            }
            if (pick != null) {
                if (!pick.rankUp(h)) break;
                if (bot.verbose) bot.log("hero learns " + pick.getClass().getSimpleName() + " first");
                continue;
            }
            for (HeroAbility a : h.getHeroAbilities()) {
                if (a.getRank(h) >= a.maxRank || h.getHeroLevel() < a.getLevelRequirement(h)) continue;
                if (pick == null) { pick = a; continue; }
                boolean ultA = a.maxRank <= 1, ultP = pick.maxRank <= 1;
                if (ultA && !ultP) { pick = a; continue; }
                if (ultA == ultP && a.getRank(h) < pick.getRank(h)) pick = a;
            }
            if (pick == null || !pick.rankUp(h)) break;
            if (bot.verbose) bot.log("hero learns " + pick.getClass().getSimpleName() + " rank " + pick.getRank(h) + " (level " + h.getHeroLevel() + ")");
        }
    }
}
