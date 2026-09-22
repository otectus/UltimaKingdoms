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

/** Cached, loader-relocated access to the supported MCA 7.x village and residency capabilities. */
final class McaAccess {
    static final ResourceLocation SOURCE = new ResourceLocation("ultima_kingdoms", "mca");
    static final String EXTERNAL_REF = "mca";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] CANDIDATE_ROOTS = {
            "forge.net.conczin.mca",
            "forge.net.mca",
            "net.conczin.mca",
            "net.mca"
    };
    private static final Resolved RESOLVED = resolve();
    private static final AtomicBoolean DETECTOR_ENABLED = new AtomicBoolean(RESOLVED.detectorMissing().isEmpty());
    private static final AtomicBoolean EVIDENCE_ENABLED = new AtomicBoolean(RESOLVED.evidenceMissing().isEmpty());
    private static final AtomicBoolean DETECTOR_FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean EVIDENCE_FAILURE_REPORTED = new AtomicBoolean();

    private McaAccess() {
    }

    static boolean detectorAvailable() {
        return DETECTOR_ENABLED.get();
    }

    static boolean evidenceAvailable() {
        return EVIDENCE_ENABLED.get();
    }

    static String root() {
        return RESOLVED.root();
    }

    static List<String> detectorMissing() {
        return RESOLVED.detectorMissing();
    }

    static List<String> evidenceMissing() {
        return RESOLVED.evidenceMissing();
    }

    static boolean isVillager(Entity entity) {
        return evidenceAvailable() && RESOLVED.villager() != null && RESOLVED.villager().isInstance(entity);
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
            return failEvidence("Residency#getHomeVillage returned a non-Optional value", null);
        } catch (Throwable throwable) {
            return failEvidence("MCA home-village lookup failed", throwable);
        }
    }

    static Optional<Object> nearestVillage(ServerLevel level, BlockPos center, int margin) {
        if (!detectorAvailable()) {
            return Optional.empty();
        }
        try {
            Object manager = RESOLVED.managerGet().invoke(level);
            Object result = RESOLVED.managerFindNearest().invoke(manager, center, margin);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return failDetector("VillageManager#findNearestVillage returned a non-Optional value", null);
        } catch (Throwable throwable) {
            return failDetector("MCA nearest-village lookup failed", throwable);
        }
    }

    static List<Object> villages(ServerLevel level) {
        if (!detectorAvailable()) {
            return List.of();
        }
        try {
            Object manager = RESOLVED.managerGet().invoke(level);
            if (!(manager instanceof Iterable<?> iterable)) {
                failDetector("VillageManager is no longer Iterable", null);
                return List.of();
            }
            List<Object> villages = new ArrayList<>();
            iterable.forEach(villages::add);
            return List.copyOf(villages);
        } catch (Throwable throwable) {
            failDetector("MCA village iteration failed", throwable);
            return List.of();
        }
    }

    static Optional<SettlementCandidate> candidate(ServerLevel level, Object village) {
        if ((!detectorAvailable() && !evidenceAvailable())
                || village == null || !RESOLVED.village().isInstance(village)) {
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
            return failShared("MCA village conversion failed", throwable);
        }
    }

    private static Resolved resolve() {
        String root = null;
        Class<?> villager = null;
        for (String candidate : CANDIDATE_ROOTS) {
            villager = type(candidate + ".entity.VillagerEntityMCA");
            if (villager != null) {
                root = candidate;
                break;
            }
        }
        if (root == null) {
            List<String> absent = List.of("class <known MCA root>.entity.VillagerEntityMCA");
            return new Resolved("unresolved", null, null, null, null, null, null, null, null, null, null,
                    absent, absent);
        }

        List<String> sharedMissing = new ArrayList<>();
        Class<?> village = requiredType(sharedMissing, root + ".server.world.data.Village");
        MethodHandle villageGetId = method(sharedMissing, village, "getId");
        MethodHandle villageGetCenter = method(sharedMissing, village, "getCenter");
        MethodHandle villageGetBox = method(sharedMissing, village, "getBox");
        MethodHandle villageIsVillage = method(sharedMissing, village, "isVillage");

        List<String> detectorMissing = new ArrayList<>(sharedMissing);
        Class<?> manager = requiredType(detectorMissing, root + ".server.world.data.VillageManager");
        MethodHandle managerGet = method(detectorMissing, manager, "get", ServerLevel.class);
        MethodHandle managerFindNearest = method(detectorMissing, manager, "findNearestVillage", BlockPos.class, int.class);

        List<String> evidenceMissing = new ArrayList<>(sharedMissing);
        Class<?> residency = requiredType(evidenceMissing, root + ".entity.ai.Residency");
        MethodHandle getResidency = method(evidenceMissing, villager, "getResidency");
        MethodHandle getHomeVillage = method(evidenceMissing, residency, "getHomeVillage");
        return new Resolved(
                root,
                villager,
                village,
                getResidency,
                getHomeVillage,
                villageGetId,
                villageGetCenter,
                villageGetBox,
                villageIsVillage,
                managerGet,
                managerFindNearest,
                List.copyOf(detectorMissing),
                List.copyOf(evidenceMissing));
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, McaAccess.class.getClassLoader());
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Class<?> requiredType(List<String> missing, String name) {
        Class<?> type = type(name);
        if (type == null) missing.add("class " + name);
        return type;
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

    private static <T> Optional<T> failDetector(String message, Throwable throwable) {
        DETECTOR_ENABLED.set(false);
        reportFailure(DETECTOR_FAILURE_REPORTED, "MCA settlement detection", message, throwable);
        return Optional.empty();
    }

    private static <T> Optional<T> failEvidence(String message, Throwable throwable) {
        EVIDENCE_ENABLED.set(false);
        reportFailure(EVIDENCE_FAILURE_REPORTED, "MCA civic evidence", message, throwable);
        return Optional.empty();
    }

    private static <T> Optional<T> failShared(String message, Throwable throwable) {
        DETECTOR_ENABLED.set(false);
        EVIDENCE_ENABLED.set(false);
        reportFailure(DETECTOR_FAILURE_REPORTED, "MCA settlement detection", message, throwable);
        reportFailure(EVIDENCE_FAILURE_REPORTED, "MCA civic evidence", message, throwable);
        return Optional.empty();
    }

    private static void reportFailure(AtomicBoolean reported, String capability, String message, Throwable throwable) {
        if (reported.compareAndSet(false, true)) {
            if (throwable == null) {
                LOGGER.error("[Ultima Kingdoms] {}; disabling {}", message, capability);
            } else {
                LOGGER.error("[Ultima Kingdoms] {}; disabling {}", message, capability, throwable);
            }
        }
    }

    private record Resolved(
            String root,
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
            List<String> detectorMissing,
            List<String> evidenceMissing
    ) {
    }
}
