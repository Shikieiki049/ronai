package com.ronstrat;

import com.solegendary.reignofnether.unit.UnitAction;
import com.solegendary.reignofnether.unit.UnitServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

/**
 * Every order goes through RON's own server-side action queue, i.e. exactly the path a human
 * player's clicks take. No teleporting, no direct goal manipulation, no free resources.
 */
public final class Orders {
    private Orders() {}

    public static int[] ids(Collection<? extends Entity> es) {
        int[] r = new int[es.size()];
        int i = 0;
        for (Entity e : es) r[i++] = e.getId();
        return r;
    }

    /** unit orders given per owner and action since the last report (verbose: how many path requests we cause) */
    static final java.util.Map<String, int[]> issued = new java.util.concurrent.ConcurrentHashMap<>();

    static String report(String owner) {
        int[] c = issued.remove(owner);
        if (c == null) return "";
        StringBuilder b = new StringBuilder();
        UnitAction[] all = UnitAction.values();
        for (int i = 0; i < c.length; i++) if (c[i] > 0) b.append(' ').append(all[i].name().toLowerCase()).append('=').append(c[i]);
        return b.toString();
    }

    private static void act(String owner, UnitAction a, int targetId, int[] ids, BlockPos pos, BlockPos building) {
        if (ids.length == 0) return;
        issued.computeIfAbsent(owner, k -> new int[UnitAction.values().length])[a.ordinal()] += ids.length;
        UnitServerEvents.addActionItem(owner, a, targetId, ids, pos, building == null ? BlockPos.ZERO : building);
    }

    /** a unit ability (potions, hero skills, autocast switches): the same command a player's click sends */
    public static void ability(String owner, UnitAction a, int targetId, Collection<? extends Entity> units, BlockPos pos) {
        act(owner, a, targetId, ids(units), pos, null);
    }

    public static void move(String owner, Collection<? extends Entity> units, BlockPos pos) {
        act(owner, UnitAction.MOVE, -1, ids(units), pos, null);
    }

    public static void attackMove(String owner, Collection<? extends Entity> units, BlockPos pos) {
        act(owner, UnitAction.ATTACK_MOVE, -1, ids(units), pos, null);
    }

    public static void attack(String owner, Collection<? extends Entity> units, LivingEntity target) {
        act(owner, UnitAction.ATTACK, target.getId(), ids(units), target.blockPosition(), null);
    }

    public static void attackBuilding(String owner, Collection<? extends Entity> units, BlockPos buildingPos) {
        act(owner, UnitAction.ATTACK_BUILDING, -1, ids(units), buildingPos, null);
    }

    /** Workers: MOVE onto a resource block sets the gather type and target, like a right-click. */
    public static void gather(String owner, Collection<? extends Entity> workers, BlockPos resourceBlock) {
        act(owner, UnitAction.MOVE, -1, ids(workers), resourceBlock, null);
    }

    public static void farm(String owner, Collection<? extends Entity> workers, BlockPos farmPos) {
        act(owner, UnitAction.FARM, -1, ids(workers), farmPos, null);
    }

    public static void buildRepair(String owner, Collection<? extends Entity> workers, BlockPos buildingPos) {
        act(owner, UnitAction.BUILD_REPAIR, -1, ids(workers), buildingPos, null);
    }

    public static void stop(String owner, Collection<? extends Entity> units) {
        act(owner, UnitAction.STOP, -1, ids(units), BlockPos.ZERO, null);
    }

    public static void returnResources(String owner, Collection<? extends Entity> workers) {
        act(owner, UnitAction.RETURN_RESOURCES_TO_CLOSEST, -1, ids(workers), BlockPos.ZERO, null);
    }

    public static void garrison(String owner, Collection<? extends Entity> units, BlockPos buildingPos) {
        act(owner, UnitAction.GARRISON, -1, ids(units), buildingPos, null);
    }

    public static void ungarrison(String owner, Collection<? extends Entity> units) {
        for (Entity e : units) act(owner, UnitAction.UNGARRISON, -1, new int[] { e.getId() }, e.blockPosition(), null);
    }

    /** a building's own ability, e.g. the town centre's call to arms bell */
    public static void buildingAbility(String owner, UnitAction action, BlockPos buildingPos) {
        UnitServerEvents.addActionItem(owner, action, -1, new int[0], buildingPos, buildingPos);
    }

    public static void raw(String owner, UnitAction action, Collection<? extends Entity> units, BlockPos pos, BlockPos building) {
        act(owner, action, -1, ids(units), pos, building);
    }
}
