package com.ronstrat;

import com.solegendary.reignofnether.ability.EnchantAbility;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.building.BuildingUtils;
import com.solegendary.reignofnether.resources.ResourceName;
import com.solegendary.reignofnether.resources.ResourceSource;
import com.solegendary.reignofnether.resources.ResourceSources;
import com.solegendary.reignofnether.unit.goals.GatherResourcesGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

/**
 * The few RON calls that differ between RON builds (1.5.1c, which the mod is built against, and
 * 1.5.0b-2, the build with RON's own computer players that self-play uses as a sparring partner).
 * Each looks the method up once and falls back to an equivalent when it is missing.
 */
final class RonCompat {
    private RonCompat() {}

    private static Method canGather, isAutoCasting, toggleAutoCast, hasSameEnchant, syncAll;
    private static java.lang.reflect.Field blockCondition;
    private static boolean looked;

    private static synchronized void look() {
        if (looked) return;
        looked = true;
        canGather = find(GatherResourcesGoal.class, "canGather", BlockPos.class, ResourceName.class);
        if (canGather == null) {
            try {
                blockCondition = GatherResourcesGoal.class.getDeclaredField("BLOCK_CONDITION");
                blockCondition.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        hasSameEnchant = find(EnchantAbility.class, "hasSameEnchant", LivingEntity.class);
        try {
            Class<?> lib = Class.forName("com.solegendary.reignofnether.building.buildings.villagers.Library");
            isAutoCasting = find(lib, "isAutoCasting", BuildingPlacement.class, EnchantAbility.class);
            toggleAutoCast = find(lib, "toggleAutoCast", BuildingPlacement.class, EnchantAbility.class);
        } catch (ClassNotFoundException ignored) {}
        try {
            syncAll = find(Class.forName("com.solegendary.reignofnether.startpos.StartPosServerEvents"), "syncAll");
        } catch (ClassNotFoundException ignored) {}
    }

    private static Method find(Class<?> c, String name, Class<?>... args) {
        try {
            Method m = c.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static boolean canGather(GatherResourcesGoal g, Level level, BlockPos bp, ResourceName type) {
        look();
        if (canGather != null) {
            try { return (boolean) canGather.invoke(g, bp, type); } catch (ReflectiveOperationException e) { return false; }
        }
        // older RON: the goal's own test, run for the asked resource type
        if (blockCondition != null) {
            ResourceName old = g.data.targetResourceName;
            try {
                g.data.targetResourceName = type;
                @SuppressWarnings("unchecked") java.util.function.Predicate<BlockPos> test = (java.util.function.Predicate<BlockPos>) blockCondition.get(g);
                return test.test(bp);
            } catch (ReflectiveOperationException e) {
                return false;
            } finally {
                g.data.targetResourceName = old;
            }
        }
        // last resort: the same test without the goal's own memory of unreachable blocks
        if (!level.getWorldBorder().isWithinBounds(bp)) return false;
        ResourceSource src = ResourceSources.getFromBlockPos(bp, level);
        if (src == null || src.resourceName != type) return false;
        BlockState bs = level.getBlockState(bp);
        if (!src.blockStateTest.test(bs)) return false;
        if (type != ResourceName.FOOD && BuildingUtils.isPosInsideAnyBuilding(level.isClientSide(), bp)) return false;
        for (BlockPos adj : java.util.List.of(bp.above(), bp.north(), bp.south(), bp.east(), bp.west()))
            if (!level.getBlockState(adj).isSolid()) return true;
        return false;
    }

    static boolean hasSameEnchant(EnchantAbility e, LivingEntity u) {
        look();
        if (hasSameEnchant == null) return false;
        try { return (boolean) hasSameEnchant.invoke(e, u); } catch (ReflectiveOperationException ex) { return false; }
    }

    static boolean isAutoCasting(BuildingPlacement b, EnchantAbility e) {
        look();
        if (isAutoCasting == null) return false;
        try { return (boolean) isAutoCasting.invoke(null, b, e); } catch (ReflectiveOperationException ex) { return false; }
    }

    static void toggleAutoCast(BuildingPlacement b, EnchantAbility e) {
        look();
        if (toggleAutoCast == null) return;
        try { toggleAutoCast.invoke(null, b, e); } catch (ReflectiveOperationException ignored) {}
    }

    static void syncStartPositions() {
        look();
        if (syncAll == null) return;
        try { syncAll.invoke(null); } catch (ReflectiveOperationException ignored) {}
    }
}
