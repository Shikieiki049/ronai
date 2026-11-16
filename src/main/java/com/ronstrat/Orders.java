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

    /**
     * Units in the middle of casting (a hero skill): until the time here (ms) no other order reaches them - the
     * army's micro re-ordered every soldier every 0.3 s and a hero walking into range or winding up a skill was
     * turned round again and again, so it kept trying and never cast (the user saw it).
     */
    static final java.util.Map<Integer, Long> casting = new java.util.concurrent.ConcurrentHashMap<>();

    static void protect(int id, long ms) { casting.put(id, System.currentTimeMillis() + ms); }
    static void release(int id) { casting.remove(id); }
    static boolean isCasting(int id) { Long u = casting.get(id); return u != null && u > System.currentTimeMillis(); }

    private static int[] withoutCasting(int[] ids) {
        if (casting.isEmpty()) return ids;
        long now = System.currentTimeMillis();
        casting.values().removeIf(u -> u <= now);
        int n = 0;
        for (int id : ids) if (!casting.containsKey(id)) n++;
        if (n == ids.length) return ids;
        int[] r = new int[n];
        int k = 0;
        for (int id : ids) if (!casting.containsKey(id)) r[k++] = id;
        return r;
    }

    // ---- order tracer (-Dronstrat.traceOrders=true): a unit sent one way and then back the other way within 3 s
    // is logged with both callers - to find the logic pair behind the army walking out and back (the user)
    static final boolean TRACE = Boolean.getBoolean("ronstrat.traceOrders");
    private record Last(BlockPos dest, long ms, String caller) {}
    private static final java.util.Map<Integer, Last> lastOrder = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<String, Integer> flips = new java.util.concurrent.ConcurrentHashMap<>();
    private static long lastFlipReport;

    private static void trace(String owner, UnitAction a, int[] ids, BlockPos pos) {
        if (a != UnitAction.MOVE && a != UnitAction.ATTACK_MOVE && a != UnitAction.ATTACK && a != UnitAction.ATTACK_BUILDING) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var level = server.overworld();
        String caller = StackWalker.getInstance().walk(st -> st.map(f -> f.getClassName().replace("com.ronstrat.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                .filter(x -> !x.startsWith("Orders.")).findFirst().orElse("?"));
        long now = System.currentTimeMillis();
        for (int id : ids) {
            Entity e = level.getEntity(id);
            if (e == null) continue;
            Last l = lastOrder.put(id, new Last(pos, now, a.name() + "@" + caller));
            if (l == null || now - l.ms() > 3000) continue;
            double ax = l.dest().getX() - e.getX(), az = l.dest().getZ() - e.getZ(), bx = pos.getX() - e.getX(), bz = pos.getZ() - e.getZ();
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            if (la < 4 || lb < 4) continue;
            if ((ax * bx + az * bz) / (la * lb) > -0.3) continue;
            String k = l.caller() + "  ->  " + a.name() + "@" + caller;
            flips.merge(k, 1, Integer::sum);
        }
        if (now - lastFlipReport > 30000 && !flips.isEmpty()) {
            lastFlipReport = now;
            StringBuilder sb = new StringBuilder("RON Strategist order flips (one way, then back within 3 s):");
            flips.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12)
                    .forEach(en -> sb.append(" | ").append(en.getValue()).append("x  ").append(en.getKey()));
            com.ronstrat.RonStrategist.LOG.info(sb.toString());
        }
    }

    private static void act(String owner, UnitAction a, int targetId, int[] ids, BlockPos pos, BlockPos building) {
        ids = withoutCasting(ids);
        if (ids.length == 0) return;
        if (TRACE) trace(owner, a, ids, pos);
        issued.computeIfAbsent(owner, k -> new int[UnitAction.values().length])[a.ordinal()] += ids.length;
        UnitServerEvents.addActionItem(owner, a, targetId, ids, pos, building == null ? BlockPos.ZERO : building);
    }

    /** a unit ability (potions, hero skills, autocast switches): the same command a player's click sends */
    public static void ability(String owner, UnitAction a, int targetId, Collection<? extends Entity> units, BlockPos pos) {
        act(owner, a, targetId, ids(units), pos, null);
    }

    /** a hero skill: the hero is left alone for up to ms while it walks into range and casts */
    public static void cast(String owner, UnitAction a, int targetId, Entity caster, BlockPos pos, long ms) {
        release(caster.getId());
        int[] id = { caster.getId() };
        issued.computeIfAbsent(owner, k -> new int[UnitAction.values().length])[a.ordinal()]++;
        UnitServerEvents.addActionItem(owner, a, targetId, id, pos, BlockPos.ZERO);
        protect(caster.getId(), ms);
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
