package com.ronstrat;

import com.google.gson.JsonObject;
import com.solegendary.reignofnether.ability.EnchantAbility;
import com.solegendary.reignofnether.ability.EquipAbility;
import com.solegendary.reignofnether.building.buildings.villagers.Blacksmith;
import com.solegendary.reignofnether.building.production.ProductionItem;
import com.solegendary.reignofnether.building.production.ProductionItems;
import com.solegendary.reignofnether.registrars.EntityRegistrar;
import com.solegendary.reignofnether.research.ResearchServerEvents;
import com.solegendary.reignofnether.unit.UnitAction;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The arena (-Dronstrat.arena=file): armies fight on a flat platform, no economy. One fight per line of
 * the file:   name | side A | side B      a side = units and options, e.g.
 *     vindicator*12                          twelve vindicators
 *     pillager*8+vindicator*4 leather axes   mixed, leather armour, the vindicator axes research
 * units: vindicator pillager golem witch evoker ravager windcaller guard militia
 * options: leather chain (armour on every unit that can wear it), axes crossbows vexes windcallers lingering golemsmith (research),
 *          sharpness maiming quickcharge multishot vigor (enchantments on the units that take them)
 * Each fight ends when a side is dead or after 90 seconds; the result (share of hit points left on each
 * side) goes to -Dronstrat.out as one JSON line. Needs a match of two idle players: "easy@idle vs easy@idle".
 */
final class ArenaTest {
    private ArenaTest() {}

    private static List<String> lines;
    private static int index = -1;
    private static long phaseAt;
    private static int phase; // 0 idle, 1 spawned (waiting to order), 2 fighting
    private static final List<Mob> sideA = new ArrayList<>(), sideB = new ArrayList<>();
    private static double hpA0, hpB0;
    private static BlockPos centre;
    private static String ownerA, ownerB;
    private static final int Y = 200, HALF_X = 34, HALF_Z = 18;

    static boolean enabled() { return System.getProperty("ronstrat.arena") != null; }

    /** called every server tick once the match of two idle players has started; true when all fights are done */
    static boolean tick(ServerLevel level, Match match, long ticks) {
        try {
            if (lines == null) {
                lines = new ArrayList<>();
                for (String l : Files.readAllLines(Path.of(System.getProperty("ronstrat.arena")), StandardCharsets.UTF_8))
                    if (!l.isBlank() && !l.startsWith("#")) lines.add(l.trim());
                ownerA = match.teams.get(0).get(0).name();
                ownerB = match.teams.get(1).get(0).name();
                var border = level.getWorldBorder();
                centre = new BlockPos((int) border.getCenterX(), Y, (int) border.getCenterZ());
                for (int x = -HALF_X; x <= HALF_X; x++)
                    for (int z = -HALF_Z; z <= HALF_Z; z++) {
                        level.setBlock(centre.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                        for (int y = 0; y < 4; y++) level.setBlock(centre.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                phase = 0;
                phaseAt = ticks + 40;
                RonStrategist.LOG.info("ARENA {} fights, platform at {}", lines.size(), centre.toShortString());
                return false;
            }
            if (phase == 0) {
                if (ticks < phaseAt) return false;
                index++;
                if (index >= lines.size()) return true;
                String[] f = lines.get(index).split("\\|");
                sideA.clear(); sideB.clear();
                spawn(level, f[1].trim(), ownerA, -1, sideA);
                spawn(level, f[2].trim(), ownerB, +1, sideB);
                hpA0 = hp(sideA); hpB0 = hp(sideB);
                phase = 1; phaseAt = ticks + 20;
                return false;
            }
            if (phase == 1) {
                if (ticks < phaseAt) return false;
                Orders.attackMove(ownerA, sideA, centre.offset(10, 0, 0));
                Orders.attackMove(ownerB, sideB, centre.offset(-10, 0, 0));
                autocast(ownerA, sideA); autocast(ownerB, sideB);
                phase = 2; phaseAt = ticks;
                return false;
            }
            if (ticks % 10 != 0) return false;
            int aliveA = alive(sideA), aliveB = alive(sideB);
            long t = ticks - phaseAt;
            // armies that lost each other (a unit pushed off, a kiter gone far): order again every 10 s
            if (t > 0 && t % 200 == 0) {
                Orders.attackMove(ownerA, living(sideA), centre);
                Orders.attackMove(ownerB, living(sideB), centre);
            }
            if (aliveA > 0 && aliveB > 0 && t < 1800) return false;
            String[] f = lines.get(index).split("\\|");
            JsonObject o = new JsonObject();
            o.addProperty("arena", f[0].trim());
            o.addProperty("a", f[1].trim());
            o.addProperty("b", f[2].trim());
            double la = hpA0 <= 0 ? 0 : hp(sideA) / hpA0, lb = hpB0 <= 0 ? 0 : hp(sideB) / hpB0;
            o.addProperty("aLeft", Math.round(la * 1000) / 1000.0);
            o.addProperty("bLeft", Math.round(lb * 1000) / 1000.0);
            o.addProperty("aAlive", aliveA);
            o.addProperty("bAlive", aliveB);
            o.addProperty("aUnits", sideA.size());
            o.addProperty("bUnits", sideB.size());
            o.addProperty("seconds", t / 20);
            o.addProperty("winner", aliveA > 0 && aliveB == 0 ? "a" : aliveB > 0 && aliveA == 0 ? "b" : "draw");
            String out = System.getProperty("ronstrat.out");
            if (out != null) Files.writeString(Path.of(out), o + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            for (Mob m : sideA) m.discard();
            for (Mob m : sideB) m.discard();
            // what the fight left behind (vexes, potion clouds)
            for (Entity e : level.getAllEntities()) if (e != null && e.getY() > Y - 20 && !(e instanceof net.minecraft.world.entity.player.Player)) e.discard();
            ResearchServerEvents.removeAllResearchFor(ownerA);
            ResearchServerEvents.removeAllResearchFor(ownerB);
            phase = 0; phaseAt = ticks + 30;
            return false;
        } catch (Exception e) {
            RonStrategist.LOG.error("ARENA failed at fight " + index, e);
            return true;
        }
    }

    private static void autocast(String owner, List<Mob> ms) {
        List<Mob> witches = new ArrayList<>(), evokers = new ArrayList<>();
        for (Mob m : ms) {
            if (m.getType() == EntityRegistrar.WITCH_UNIT.get()) witches.add(m);
            if (m.getType() == EntityRegistrar.EVOKER_UNIT.get()) evokers.add(m);
        }
        if (!witches.isEmpty()) Orders.ability(owner, UnitAction.THROW_LINGERING_HARMING_POTION_AUTOCAST_ENABLE, -1, witches, centre);
        if (!evokers.isEmpty() && ResearchServerEvents.playerHasResearch(owner, ProductionItems.RESEARCH_EVOKER_VEXES))
            Orders.ability(owner, UnitAction.CAST_SUMMON_VEXES_AUTOCAST_ENABLE, -1, evokers, centre);
    }

    private static List<Mob> living(List<Mob> ms) { List<Mob> l = new ArrayList<>(); for (Mob m : ms) if (m.isAlive()) l.add(m); return l; }
    private static int alive(List<Mob> ms) { int n = 0; for (Mob m : ms) if (m.isAlive()) n++; return n; }
    private static double hp(List<Mob> ms) { double s = 0; for (Mob m : ms) if (m.isAlive()) s += m.getHealth(); return s; }

    private static EntityType<? extends Mob> type(String name) {
        return switch (name) {
            case "vindicator" -> EntityRegistrar.VINDICATOR_UNIT.get();
            case "pillager" -> EntityRegistrar.PILLAGER_UNIT.get();
            case "golem" -> EntityRegistrar.IRON_GOLEM_UNIT.get();
            case "witch" -> EntityRegistrar.WITCH_UNIT.get();
            case "evoker" -> EntityRegistrar.EVOKER_UNIT.get();
            case "ravager" -> EntityRegistrar.RAVAGER_UNIT.get();
            case "windcaller" -> EntityRegistrar.WINDCALLER_UNIT.get();
            case "guard" -> EntityRegistrar.ROYAL_GUARD_UNIT.get();
            case "militia" -> EntityRegistrar.MILITIA_UNIT.get();
            default -> throw new IllegalArgumentException("arena: unknown unit " + name);
        };
    }

    private static ProductionItem research(String opt) {
        return switch (opt) {
            case "axes" -> ProductionItems.RESEARCH_VINDICATOR_AXES;
            case "crossbows" -> ProductionItems.RESEARCH_PILLAGER_CROSSBOWS;
            case "vexes" -> ProductionItems.RESEARCH_EVOKER_VEXES;
            case "windcallers" -> ProductionItems.RESEARCH_UPGRADED_WINDCALLERS;
            case "lingering" -> ProductionItems.RESEARCH_LINGERING_POTIONS;
            case "golemsmith" -> ProductionItems.RESEARCH_GOLEM_SMITHING;
            default -> null;
        };
    }

    private static final java.lang.reflect.Method DO_ENCHANT;
    static {
        try { DO_ENCHANT = EnchantAbility.class.getDeclaredMethod("doEnchant", LivingEntity.class); DO_ENCHANT.setAccessible(true); }
        catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
    }

    private static EquipAbility equip(String field) throws Exception {
        var f = Blacksmith.class.getDeclaredField(field);
        f.setAccessible(true);
        return (EquipAbility) f.get(null);
    }

    /** side: "vindicator*12+pillager*4 leather axes"; dir -1 = the west half, +1 = the east half */
    private static void spawn(ServerLevel level, String side, String owner, int dir, List<Mob> into) throws Exception {
        String[] parts = side.split("\\s+");
        EquipAbility armour = null;
        List<EnchantAbility> enchants = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            ProductionItem r = research(parts[i]);
            if (r != null) ResearchServerEvents.addResearch(owner, r); // before the units exist: they read it when created
            else if (parts[i].equals("leather")) armour = equip("EQUIP_LEATHER_CHESTPLATE_INSTANCE");
            else if (parts[i].equals("chain")) armour = equip("EQUIP_CHAINMAIL_CHESTPLATE");
            else if (parts[i].equals("sharpness")) enchants.add(new com.solegendary.reignofnether.ability.abilities.EnchantSharpness());
            else if (parts[i].equals("maiming")) enchants.add(new com.solegendary.reignofnether.ability.abilities.EnchantMaiming());
            else if (parts[i].equals("quickcharge")) enchants.add(new com.solegendary.reignofnether.ability.abilities.EnchantQuickCharge());
            else if (parts[i].equals("multishot")) enchants.add(new com.solegendary.reignofnether.ability.abilities.EnchantMultishot());
            else if (parts[i].equals("vigor")) enchants.add(new com.solegendary.reignofnether.ability.abilities.EnchantVigor());
            else throw new IllegalArgumentException("arena: unknown option " + parts[i]);
        }
        int n = 0;
        for (String g : parts[0].split("\\+")) {
            String[] u = g.split("\\*");
            int count = u.length > 1 ? Integer.parseInt(u[1]) : 1;
            for (int i = 0; i < count; i++, n++) {
                // rows of six, two blocks apart, 16 blocks from the centre line
                BlockPos at = centre.offset(dir * (16 + 2 * (n / 6)), 0, -5 + 2 * (n % 6));
                Entity e = UnitServerEvents.spawnMob(type(u[0]), level, at, owner);
                if (!(e instanceof Mob m)) continue;
                m.teleportTo(at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5);
                if (m instanceof Unit u2) u2.setupEquipmentAndUpgradesServer();
                if (armour != null && armour.isCorrectUnit((LivingEntity) m)) m.setItemSlot(armour.equipmentSlot, new ItemStack(armour.item));
                for (EnchantAbility en : enchants) if (en.isCorrectUnitAndEquipment(m)) DO_ENCHANT.invoke(en, m);
                into.add(m);
            }
        }
    }
}
