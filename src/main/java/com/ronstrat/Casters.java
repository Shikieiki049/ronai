package com.ronstrat;

import com.solegendary.reignofnether.ability.Ability;
import com.solegendary.reignofnether.ability.HeroAbility;
import com.solegendary.reignofnether.unit.UnitAction;
import com.solegendary.reignofnether.unit.interfaces.HeroUnit;
import com.solegendary.reignofnether.unit.interfaces.Unit;
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
        for (Mob u : in.army) {
            try {
                if (witches && isWitch(u)) witch(in, u);
                if (heroes && u instanceof HeroUnit h) hero(in, u, h);
            } catch (Throwable t) {
                if (bot.now > nextRankLog) { bot.log("caster error: " + t); nextRankLog = bot.now + 6000; }
            }
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
        List<LivingEntity> foes = enemiesNear(in, m, 12);
        for (LivingEntity n : in.neutrals) if (n.distanceToSqr(m) < 12 * 12) foes.add(n);
        if (foes.isEmpty() || bot.now - lastCast.getOrDefault(m.getId(), -1000L) < 20) return;
        LivingEntity near = null;
        for (LivingEntity e : foes) if (near == null || e.distanceToSqr(m) < near.distanceToSqr(m)) near = e;
        for (HeroAbility a : h.getHeroAbilities()) {
            if (a.getRank(h) <= 0 || !a.isOffCooldown(h) || h.getMana() < a.manaCost) continue;
            String n = a.getClass().getSimpleName();
            if (n.contains("Passive")) continue;
            // the big one (a one-rank ultimate) only for a real fight
            if (a.maxRank <= 1 && foes.size() < 4 && m.getHealth() > 0.5 * m.getMaxHealth()) continue;
            if (a.canTargetEntities) {
                if (a.range > 0 && near.distanceToSqr(m) > (a.range + 1) * (a.range + 1)) continue;
                Orders.ability(bot.name, a.action, near.getId(), List.of(m), near.blockPosition());
            } else {
                Orders.ability(bot.name, a.action, -1, List.of(m), near.blockPosition());
            }
            lastCast.put(m.getId(), bot.now);
            if (bot.verbose) bot.log("hero " + m.getType().toShortString() + " casts " + n + " (" + foes.size() + " foes)");
            break;
        }
    }

    /** spend skill points: the one-rank ultimate first when the level allows, then the lowest-ranked skill */
    private void rankUp(HeroUnit h) {
        int guard = 0;
        while (h.getSkillPoints() > 0 && guard++ < 10) {
            HeroAbility pick = null;
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
