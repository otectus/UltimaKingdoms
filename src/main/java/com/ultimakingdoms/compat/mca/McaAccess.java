package com.ultimakingdoms.compat.mca;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.api.SettlementCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cached, loader-relocated access to the exact MCA 7.6.26 surface used by the bridge. */
final class McaAccess {
    static final String EXACT_VERSION = "7.6.26+1.20.1";
    static final ResourceLocation SOURCE = new ResourceLocation("ultima_kingdoms", "mca");
    static final String EXTERNAL_REF = "mca";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "forge.net.mca";
    private static final Resolved RESOLVED = resolve();
    private static final AtomicBoolean ENABLED = new AtomicBoolean(RESOLVED.missing().isEmpty());
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private McaAccess() {
    }

    static boolean available() {
        return ENABLED.get();
    }

    static List<String> missing() {
        return RESOLVED.missing();
    }

    static boolean isVillager(Entity entity) {
        return available() && RESOLVED.villager() != null && RESOLVED.villager().isInstance(entity);
    }

    static Optional<Object> homeVillage(Entity entity) {
        if (!isVillager(entity)) {
            return Optional.empty();
        }
        try {
            Object residency = RESOLVED.getResidency().invoke(entity);
            Object result = RESOLVED.getHomeVillage().invoke(residency);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return fail("Residency#getHomeVillage returned a non-Optional value", null);
        } catch (Throwable throwable) {
            return fail("MCA home-village lookup failed", throwable);
        }
    }

    static Optional<Object> nearestVillage(ServerLevel level, BlockPos center, int margin) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Object manager = RESOLVED.managerGet().invoke(level);
            Object result = RESOLVED.managerFindNearest().invoke(manager, center, margin);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return fail("VillageManager#findNearestVillage returned a non-Optional value", null);
        } catch (Throwable throwable) {
            return fail("MCA nearest-village lookup failed", throwable);
        }
    }

    static List<Object> villages(ServerLevel level) {
        if (!available()) {
            return List.of();
        }
        try {
            Object manager = RESOLVED.managerGet().invoke(level);
            if (!(manager instanceof Iterable<?> iterable)) {
                fail("VillageManager is no longer Iterable", null);
                return List.of();
            }
            List<Object> villages = new ArrayList<>();
            iterable.forEach(villages::add);
            return List.copyOf(villages);
        } catch (Throwable throwable) {
            fail("MCA village iteration failed", throwable);
            return List.of();
        }
    }

    static Optional<SettlementCandidate> candidate(ServerLevel level, Object village) {
        if (!available() || village == null || !RESOLVED.village().isInstance(village)) {
            return Optional.empty();
        }
        try {
            if (!((boolean) RESOLVED.villageIsVillage().invoke(village))) {
                return Optional.empty();
            }
            int id = (int) RESOLVED.villageGetId().invoke(village);
            Vec3i center = (Vec3i) RESOLVED.villageGetCenter().invoke(village);
            BoundingBox box = (BoundingBox) RESOLVED.villageGetBox().invoke(village);
            if (center == null || box == null || box.minX() > box.maxX() || box.minZ() > box.maxZ()) {
                return Optional.empty();
            }
            BlockPos anchor = new BlockPos(center.getX(), center.getY(), center.getZ());
            SettlementBounds bounds = new SettlementBounds(box.minX(), box.minZ(), box.maxX(), box.maxZ());
            int radius = Math.max(
                    Math.max(Math.abs(anchor.getX() - bounds.minX()), Math.abs(bounds.maxX() - anchor.getX())),
                    Math.max(Math.abs(anchor.getZ() - bounds.minZ()), Math.abs(bounds.maxZ() - anchor.getZ())));
            String external = level.dimension().location() + "#" + id;
            return Optional.of(new SettlementCandidate(
                    level.dimension(), anchor, radius, bounds, SOURCE, external, DetectionSource.EXTERNAL,
                    Optional.empty(), Optional.empty(), Map.of(EXTERNAL_REF, external), Optional.empty()));
        } catch (Throwable throwable) {
            return fail("MCA village conversion failed", throwable);
        }
    }

    private static Resolved resolve() {
        List<String> missing = new ArrayList<>();
        Class<?> villager = type(missing, ROOT + ".entity.VillagerEntityMCA");
        Class<?> residency = type(missing, ROOT + ".entity.ai.Residency");
        Class<?> village = type(missing, ROOT + ".server.world.data.Village");
        Class<?> manager = type(missing, ROOT + ".server.world.data.VillageManager");
        return new Resolved(
                villager,
                village,
                method(missing, villager, "getResidency"),
                method(missing, residency, "getHomeVillage"),
                method(missing, village, "getId"),
                method(missing, village, "getCenter"),
                method(missing, village, "getBox"),
                method(missing, village, "isVillage"),
                method(missing, manager, "get", ServerLevel.class),
                method(missing, manager, "findNearestVillage", BlockPos.class, int.class),
                List.copyOf(missing));
    }

    private static Class<?> type(List<String> missing, String name) {
        try {
            return Class.forName(name, false, McaAccess.class.getClassLoader());
        } catch (Throwable throwable) {
            missing.add("class " + name);
            return null;
        }
    }

    private static MethodHandle method(List<String> missing, Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        try {
            Method method = owner.getMethod(name, parameters);
            return MethodHandles.publicLookup().unreflect(method);
        } catch (Throwable throwable) {
            missing.add(owner.getName() + "#" + name);
            return null;
        }
    }

    private static <T> Optional<T> fail(String message, Throwable throwable) {
        ENABLED.set(false);
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            if (throwable == null) {
                LOGGER.error("[Ultima Kingdoms] {}; disabling MCA integration", message);
            } else {
                LOGGER.error("[Ultima Kingdoms] {}; disabling MCA integration", message, throwable);
            }
        }
        return Optional.empty();
    }

    private record Resolved(
            Class<?> villager,
            Class<?> village,
            MethodHandle getResidency,
            MethodHandle getHomeVillage,
            MethodHandle villageGetId,
            MethodHandle villageGetCenter,
            MethodHandle villageGetBox,
            MethodHandle villageIsVillage,
            MethodHandle managerGet,
            MethodHandle managerFindNearest,
            List<String> missing
    ) {
    }
}
