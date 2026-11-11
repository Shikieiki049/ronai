package com.ronstrat;

import com.solegendary.reignofnether.ability.EnchantAbility;
import com.solegendary.reignofnether.ability.EquipAbility;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.Buildings;
import com.solegendary.reignofnether.building.buildings.villagers.Blacksmith;
import com.solegendary.reignofnether.building.buildings.villagers.Library;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit upgrades from the villager buildings: the library enchants weapons (sharpness for
 * vindicators, quick charge for pillagers, vigor for evokers) and the blacksmith fits chestplates
 * (leather, chainmail once upgraded). Both buildings do it by themselves with autocast to units
 * within 12 blocks; the bot switches autocast on when it has spare resources and walks units that
 * lack an upgrade past the building while the army is gathering.
 */
final class Upgrades {
    private final Bot bot;
    private final Map<Integer, Long> visiting = new HashMap<>(), visited = new HashMap<>();
    private final Map<Integer, BlockPos> visitTarget = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    /** different units that could not get served at a building: three and nobody is sent there for a while */
    private final Map<Long, Integer> buildingFails = new HashMap<>();
    private long nextLog;
    private int enchants, equips;
    private static List<EnchantAbility> wantedEnchants;
    private static EquipAbility leather, chainmail;
    private static boolean reflectFailed;
    private boolean costsLogged, wasRich;
    private int diagLogs, libLogs, visitLogs, failLogs;

    Upgrades(Bot bot) { this.bot = bot; }

    private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean init() {
        if (wantedEnchants != null) return true;
        if (reflectFailed) return false;
        try {
            List<EnchantAbility> l = new ArrayList<>();
            l.add((EnchantAbility) staticField(Library.class, "ENCHANT_MAIMING"));      // no upgrade needed
            l.add((EnchantAbility) staticField(Library.class, "ENCHANT_QUICK_CHARGE")); // no upgrade needed
            l.add((EnchantAbility) staticField(Library.class, "ENCHANT_SHARPNESS"));    // grand library
            l.add((EnchantAbility) staticField(Library.class, "ENCHANT_VIGOR"));        // grand library
            leather = (EquipAbility) staticField(Blacksmith.class, "EQUIP_LEATHER_CHESTPLATE_INSTANCE");
            chainmail = (EquipAbility) staticField(Blacksmith.class, "EQUIP_CHAINMAIL_CHESTPLATE");
            wantedEnchants = l;
            return true;
        } catch (Throwable t) {
            reflectFailed = true;
            RonStrategist.LOG.warn("RON Strategist: unit upgrades unavailable: {}", t.toString());
            return false;
        }
    }

    /** spare resources: upgrades are a late luxury and must not starve production or buildings */
    private boolean rich(Intel in) {
        // cheap (30-60 ore each) but ore is the scarce one late: on above 100, off only below 60
        // at the population limit nothing else can use the money: buy them as soon as it is there
        int bar = wasRich ? 30 : bot.economy.popMaxed(in) ? 60 : 100;
        return in.wood >= bar && in.ore >= bar;
    }

    private boolean wasArmourRich;

    /** a chestplate costs 30 ore and nothing else: it must not wait for wood as well */
    private boolean armourRich(Intel in) {
        boolean r = in.ore >= (wasArmourRich ? 30 : bot.economy.popMaxed(in) ? 40 : 80);
        wasArmourRich = r;
        return r;
    }

    /** sharpness and vigor need the grand library; before it vindicators get maiming instead */
    private static List<EnchantAbility> enchantsFor(BuildingPlacement b) {
        boolean grand = false;
        try { grand = b.getBuilding().getUpgradeLevel(b) > 0; } catch (Throwable ignored) { }
        return grand ? List.of(wantedEnchants.get(2), wantedEnchants.get(1), wantedEnchants.get(3)) : List.of(wantedEnchants.get(0), wantedEnchants.get(1));
    }

    private EquipAbility armourFor(BuildingPlacement b) {
        try {
            if (b.getBuilding().getUpgradeLevel(b) > 0) return chainmail;
        } catch (Throwable ignored) { }
        return leather;
    }

    void think(Intel in) {
        if (!bot.params.b("upgrades") || !init()) return;
        if (!costsLogged) { costsLogged = true; StringBuilder sb = new StringBuilder("upgrade costs:"); for (EnchantAbility e : wantedEnchants) sb.append(" ").append(e.getClass().getSimpleName()).append("=").append(e.cost.food).append("/").append(e.cost.wood).append("/").append(e.cost.ore); sb.append(" leather=").append(leather.cost.food).append("/").append(leather.cost.wood).append("/").append(leather.cost.ore).append(" chain=").append(chainmail.cost.food).append("/").append(chainmail.cost.wood).append("/").append(chainmail.cost.ore); bot.log(sb.toString()); }
        boolean rich = rich(in), armRich = armourRich(in);
        if (rich != wasRich) { wasRich = rich; if (bot.verbose) bot.log("upgrades autocast " + (rich ? "on" : "off")); }
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt) continue;
            try {
                if (b.getBuilding() == Buildings.LIBRARY) {
                    for (EnchantAbility e : wantedEnchants)
                        if (Library.isAutoCasting(b, e) != (rich && enchantsFor(b).contains(e))) Library.toggleAutoCast(b, e);
                } else if (b.getBuilding() == Buildings.BLACKSMITH) {
                    EquipAbility want = armRich ? armourFor(b) : null;
                    if (b.getDataStorage().getData(Blacksmith.AUTO_CAST_EQUIP) != want)
                        b.getDataStorage().setData(Blacksmith.AUTO_CAST_EQUIP, want);
                }
            } catch (Throwable t) {
                if (bot.now > nextLog) { bot.log("upgrade autocast failed: " + t); nextLog = bot.now + 6000; }
            }
        }
        countDone(in);
        enchantDiag(in);
    }

    private int enchantDiags;

    /** why a vindicator / pillager gets no enchant: one line per check, a few times a game */
    private void enchantDiag(Intel in) {
        if (!bot.verbose || enchantDiags >= 6 || bot.now % 2400 > 40) return;
        BuildingPlacement lib = null;
        for (BuildingPlacement b : in.own) if (b.isBuilt && b.getBuilding() == Buildings.LIBRARY) { lib = b; break; }
        if (lib == null) return;
        enchantDiags++;
        StringBuilder sb = new StringBuilder("enchant diag lib@" + lib.centrePos.toShortString() + " lvl=" + lib.getUpgradeLevel() + ":");
        int shown = 0;
        for (Mob u : in.army) {
            String t = u.getType().toShortString();
            if (!t.startsWith("vindicator") && !t.startsWith("pillager")) continue;
            if (shown++ >= 2) break;
            sb.append(" [").append(t).append(" hand=").append(u.getMainHandItem().getItem());
            for (EnchantAbility e : wantedEnchants)
                sb.append(" ").append(e.getClass().getSimpleName().replace("Enchant", "")).append("=")
                        .append(e.isCorrectUnitAndEquipment(u) ? "C" : "c").append(e.hasSameEnchant(u) ? "S" : "s")
                        .append(e.canAfford(lib) ? "A" : "a").append(e.isOffCooldown(lib) ? "O" : "o")
                        .append(Library.isAutoCasting(lib, e) ? "+" : "-");
            sb.append("]");
        }
        bot.log(sb.toString());
    }

    private void countDone(Intel in) {
        if (!bot.verbose || bot.now < nextLog) return;
        nextLog = bot.now + 1200;
        int en = 0, eq = 0, n = 0;
        for (Mob u : in.army) {
            boolean e = false, a = false, any = false;
            for (EnchantAbility ab : wantedEnchants) if (ab.isCorrectUnitAndEquipment(u)) { any = true; if (ab.hasSameEnchant(u)) e = true; }
            if (leather.isCorrectUnit(u) && !leather.isWindcaller(u)) { any = true; if (leather.hasItemInSlot(u)) a = true; }
            if (!any) continue;
            n++;
            if (e) en++;
            if (a) eq++;
        }
        if (n > 0 && (en != enchants || eq != equips)) bot.log("upgrades: " + en + "/" + n + " enchanted, " + eq + "/" + n + " armoured");
        enchants = en;
        equips = eq;
    }

    /** a unit still missing something one of our buildings can give it, and that building */
    private BlockPos needs(Intel in, Mob u) {
        List<BuildingPlacement> own = new ArrayList<>(in.own);
        own.sort(java.util.Comparator.comparingDouble(b -> u.blockPosition().distSqr(b.centrePos)));
        for (BuildingPlacement b : own) {
            if (!b.isBuilt) continue;
            if (failedAt.containsKey(u.getId() + "@" + b.centrePos.asLong())) continue;
            if (failedAt.containsKey("all@" + b.centrePos.asLong())) continue;
            if (b.getBuilding() == Buildings.LIBRARY && wasRich) {
                for (EnchantAbility e : enchantsFor(b))
                    if (e.isCorrectUnitAndEquipment(u) && !e.hasSameEnchant(u) && e.canAfford(b)) return b.centrePos;
            } else if (b.getBuilding() == Buildings.BLACKSMITH && wasArmourRich) {
                EquipAbility a = armourFor(b);
                if (a.isCorrectUnit(u) && !a.isWindcaller(u) && !a.hasSameItem(u) && a.canAfford(b)) return b.centrePos;
            }
        }
        return null;
    }

    /**
     * Called while the army gathers at the rally: sends a few units that lack an upgrade to the
     * building that gives it. @return the units on such a visit (the rally leaves them alone)
     */
    Set<Integer> visits(Intel in) {
        Set<Integer> out = new HashSet<>();
        if (!bot.params.b("upgrades") || !init() || !(wasRich || wasArmourRich)) { visiting.clear(); return out; }
        for (Mob u : in.army) {
            Long t = visiting.get(u.getId());
            if (t == null || bot.now - t < 580 || bot.now - t > 600 || diagLogs >= 12 || !bot.verbose) continue;
            BlockPos at = needs(in, u);
            if (at != null) { diagLogs++; bot.log("upgrade visit failed: " + u.getType().toShortString() + " d=" + (int) Math.sqrt(u.blockPosition().distSqr(at)) + " dy=" + (u.getBlockY() - at.getY()) + " idle=" + ((com.solegendary.reignofnether.unit.interfaces.Unit) u).isIdle() + " unit=" + u.blockPosition().toShortString() + " centre=" + at.toShortString() + " spot=" + approach(in, at, u).toShortString() + " door=" + doorOf(in, at)); }
        }
        for (Mob u : in.army) {
            Long t = visiting.get(u.getId());
            BlockPos tgt = visitTarget.get(u.getId());
            if (t != null && tgt != null && bot.now - t > 600 && tgt.equals(needs(in, u))) { failedAt.put(u.getId() + "@" + tgt.asLong(), bot.now); if (buildingFails.merge(tgt.asLong(), 1, Integer::sum) >= 3) { buildingFails.remove(tgt.asLong()); failedAt.put("all@" + tgt.asLong(), bot.now); } visitTarget.remove(u.getId()); if (bot.verbose && failLogs++ < 10) bot.log("upgrade: " + u.getType().toShortString() + " #" + u.getId() + " gave up on " + doorOf(in, tgt) + ", next=" + doorOf(in, needs(in, u))); }
        }
        failedAt.values().removeIf(t -> bot.now - t > 6000);
        visiting.entrySet().removeIf(e -> bot.now - e.getValue() > 600);
        visited.values().removeIf(t -> bot.now - t > 600);
        for (Mob u : in.army) {
            if (visiting.size() >= 6) break;
            if (u.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(in.home())) > 60 * 60) continue;
            int id = u.getId();
            if (visiting.containsKey(id) || visited.containsKey(id)) continue;
            BlockPos at = needs(in, u);
            if (at == null) continue;
            visiting.put(id, bot.now);
            visited.put(id, bot.now);
            visitTarget.put(id, at);
            BlockPos spot = approach(in, at, u);
            Orders.move(bot.name, List.of(u), spot);
            if (bot.verbose && visitLogs++ < 60) bot.log("upgrade visit " + u.getType().toShortString() + " from " + u.blockPosition().toShortString() + " to " + doorOf(in, at) + " spot " + spot.toShortString());
        }
        for (Mob u : in.army) {
            if (!visiting.containsKey(u.getId())) continue;
            if (needs(in, u) == null) { visiting.remove(u.getId()); continue; } // done: back to the rally
            applyInRange(in, u);
            out.add(u.getId());
        }
        return out;
    }

    /**
     * Cast it ourselves once the unit is close: the buildings' own autocast measures 12 blocks from
     * the building's centre in 3D, which units standing outside a big building on lower ground miss.
     */
    private void applyInRange(Intel in, Mob u) {
        for (BuildingPlacement b : in.own) {
            if (!b.isBuilt || u.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(new BlockPos(b.centrePos.getX(), b.minCorner.getY(), b.centrePos.getZ()))) >= 144) continue;
            try {
                if (b.getBuilding() == Buildings.LIBRARY && wasRich) {
                    for (EnchantAbility e : enchantsFor(b))
                        if (e.isCorrectUnitAndEquipment(u) && !e.hasSameEnchant(u) && e.canAfford(b) && e.isOffCooldown(b)) { e.use(bot.level, b, u); if (bot.verbose && libLogs++ < 10) bot.log("enchant " + e.getClass().getSimpleName() + " on " + u.getType().toShortString() + " d=" + (int) Math.sqrt(u.blockPosition().distSqr(b.centrePos)) + " now=" + e.hasSameEnchant(u)); return; }
                } else if (b.getBuilding() == Buildings.BLACKSMITH) {
                    EquipAbility a = armourFor(b);
                    if (a.isCorrectUnit(u) && !a.isWindcaller(u) && !a.hasSameItem(u) && a.canAfford(b) && a.isOffCooldown(b)) { a.use(bot.level, b, u); return; }
                }
            } catch (Throwable t) {
                if (bot.now > nextLog) { bot.log("upgrade use failed: " + t); nextLog = bot.now + 6000; }
            }
        }
    }

    /**
     * A standing spot beside the building on the unit's side: the centre is inside the building and
     * no path ends there, so units sent to it stop wherever their path gave up (30 blocks out).
     * Autocast reaches 12 blocks from the centre.
     */
    private static String doorOf(Intel in, BlockPos centre) {
        for (BuildingPlacement b : in.own) if (b.centrePos.equals(centre)) return door(b).toShortString() + " " + b.getBuilding().getClass().getSimpleName();
        return "?";
    }

    /** where the building lets its units out: always on walkable ground right beside it */
    private static BlockPos door(BuildingPlacement b) {
        try {
            if (b instanceof com.solegendary.reignofnether.building.buildings.placements.ProductionPlacement pp) {
                BlockPos p = pp.getDefaultOutdoorSpawnPoint();
                if (p != null) return p;
            }
        } catch (Throwable ignored) { }
        return b.centrePos;
    }

    /**
     * A standing spot just outside the building's footprint, within autocast reach (12 blocks from
     * the centre): never inside another building or on a farm, nearest to the unit.
     */
    private BlockPos approach(Intel in, BlockPos centre, Mob u) {
        BuildingPlacement bp = null;
        for (BuildingPlacement b : in.own) if (b.centrePos.equals(centre)) { bp = b; break; }
        if (bp == null) return centre;
        int x0 = Math.min(bp.minCorner.getX(), bp.maxCorner.getX()) - 1, x1 = Math.max(bp.minCorner.getX(), bp.maxCorner.getX()) + 1;
        int z0 = Math.min(bp.minCorner.getZ(), bp.maxCorner.getZ()) - 1, z1 = Math.max(bp.minCorner.getZ(), bp.maxCorner.getZ()) + 1;
        int cx = centre.getX(), cz = centre.getZ();
        List<BlockPos> cand = new ArrayList<>();
        cand.add(new BlockPos(Math.max(x0, Math.min(x1, u.getBlockX())), 0, Math.max(z0, Math.min(z1, u.getBlockZ()))));
        int[] xs = { x0, cx, x1 }, zs = { z0, cz, z1 };
        for (int x : xs) for (int z : zs) if (x != cx || z != cz) cand.add(new BlockPos(x, 0, z));
        BlockPos best = null;
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos c : cand) {
            int x = c.getX(), z = c.getZ();
            double dx = x - cx, dz = z - cz, len = Math.sqrt(dx * dx + dz * dz);
            if (len > 9) { x = cx + (int) Math.round(dx / len * 9); z = cz + (int) Math.round(dz / len * 9); }
            BlockPos p = bot.level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, centre.getY(), z));
            if (Math.abs(p.getY() - bp.minCorner.getY()) > 4) continue;
            try {
                if (com.solegendary.reignofnether.building.BuildingUtils.isPosInsideAnyBuilding(false, p)
                        || com.solegendary.reignofnether.building.BuildingUtils.isPosInsideAnyBuilding(false, p.below())
                        || com.solegendary.reignofnether.building.BuildingUtils.isPosInsideFarm(false, p.below())) continue;
            } catch (Throwable ignored) { }
            if (!bot.level.getFluidState(p.below()).isEmpty()) continue;
            ok.add(p);
        }
        // nearest first, but one the unit can actually walk to: a smith on a rise had its nearest side 3
        // blocks up a bank, and soldiers stood below it for good (12 failed visits, 2 of 21 armoured)
        ok.sort(java.util.Comparator.comparingDouble(q -> u.blockPosition().distSqr(q)));
        int checks = 0;
        for (BlockPos q : ok) {
            if (best == null) best = q;
            if (checks++ >= 4) break;
            List<BlockPos> r = Terrain.route(bot.level, u.blockPosition(), q, bot.now);
            if (Terrain.reaches(r, q)) return q;
        }
        return best != null ? best : centre;
    }
}
