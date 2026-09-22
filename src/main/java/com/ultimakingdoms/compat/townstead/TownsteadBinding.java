package com.ultimakingdoms.compat.townstead;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.townstead.TownsteadCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection manifest for Townstead's public 0.7.x API and two missing public capabilities. */
final class TownsteadBinding {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "com.aetherianartificer.townstead.api.TownsteadAPI";
    private static final String[] MCA_ROOTS = {
            "forge.net.conczin.mca", "forge.net.mca", "net.conczin.mca", "net.mca"
    };

    private final Set<TownsteadCapability> capabilities = ConcurrentHashMap.newKeySet();
    private final Map<TownsteadCapability, String> diagnostics = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, Method>> accessorCache = new ConcurrentHashMap<>();
    private final Set<TownsteadCapability> reportedFailures = ConcurrentHashMap.newKeySet();

    private Method entity;
    private Method calendar;
    private Method buildingAt;
    private Method origin;
    private Method gene;
    private Method managerGet;
    private Method managerGetOrEmpty;
    private Method villageGetBuildings;
    private Method spiritGet;
    private Constructor<?> reactionContext;
    private Object reactionContextSource;
    private Method reactionFire;

    private TownsteadBinding(boolean installed, boolean internalFallback) {
        if (!installed) {
            for (TownsteadCapability capability : TownsteadCapability.values()) {
                diagnostics.put(capability, "Townstead is not installed or integration is disabled");
            }
            return;
        }
        resolvePublicApi();
        resolveBuildingList();
        if (internalFallback) {
            resolveSpirit();
            resolveReaction();
        } else {
            diagnostics.put(TownsteadCapability.READ_SPIRIT, "internal fallback disabled by config");
            diagnostics.put(TownsteadCapability.DISPATCH_REACTION, "internal fallback disabled by config");
        }
    }

    static TownsteadBinding resolve(boolean installed, boolean internalFallback) {
        return new TownsteadBinding(installed, internalFallback);
    }

    Set<TownsteadCapability> capabilities() {
        return Set.copyOf(capabilities);
    }

    Map<TownsteadCapability, String> diagnostics() {
        EnumMap<TownsteadCapability, String> copy = new EnumMap<>(TownsteadCapability.class);
        for (TownsteadCapability capability : TownsteadCapability.values()) {
            copy.put(capability, diagnostics.getOrDefault(capability, "unavailable"));
        }
        return Map.copyOf(copy);
    }

    Optional<Object> entity(Entity value) {
        return invoke(TownsteadCapability.READ_VILLAGER, entity, null, value);
    }

    Optional<Object> calendar(MinecraftServer server) {
        return invoke(TownsteadCapability.READ_CALENDAR, calendar, null, server);
    }

    Optional<Object> buildingAt(ServerLevel level, BlockPos pos) {
        return invoke(TownsteadCapability.READ_BUILDING, buildingAt, null, level, pos);
    }

    Optional<Object> origin(ResourceLocation id) {
        return invoke(TownsteadCapability.READ_ORIGIN, origin, null, id);
    }

    Optional<Object> gene(ResourceLocation id) {
        return invoke(TownsteadCapability.READ_GENE, gene, null, id);
    }

    List<Object> buildings(ServerLevel level, int villageId) {
        if (!capabilities.contains(TownsteadCapability.READ_BUILDINGS)) return List.of();
        try {
            Object manager = managerGet.invoke(null, level);
            Object optional = managerGetOrEmpty.invoke(manager, villageId);
            if (!(optional instanceof Optional<?> village) || village.isEmpty()) return List.of();
            Object values = villageGetBuildings.invoke(village.get());
            if (!(values instanceof Map<?, ?> map)) return List.of();
            return List.copyOf(map.values());
        } catch (Throwable throwable) {
            disable(TownsteadCapability.READ_BUILDINGS, "runtime MCA building query failed", throwable);
            return List.of();
        }
    }

    Optional<Object> spirit(ServerLevel level, int villageId) {
        return invoke(TownsteadCapability.READ_SPIRIT, spiritGet, null, level, villageId);
    }

    boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> player,
                         ResourceLocation reactionId, Set<String> tags) {
        if (!capabilities.contains(TownsteadCapability.DISPATCH_REACTION)) return false;
        try {
            Object context = reactionContext.newInstance(reactionContextSource, player.orElse(null),
                    villager.blockPosition(), Set.copyOf(tags), 0);
            return Boolean.TRUE.equals(reactionFire.invoke(null, level, villager, reactionId, context));
        } catch (Throwable throwable) {
            disable(TownsteadCapability.DISPATCH_REACTION, "runtime reaction dispatch failed", throwable);
            return false;
        }
    }

    Optional<Object> call(TownsteadCapability capability, Object target, String accessor) {
        if (target == null || !capabilities.contains(capability)) return Optional.empty();
        try {
            Method method = accessorCache.computeIfAbsent(target.getClass(), TownsteadBinding::accessors).get(accessor);
            if (method == null) {
                disable(capability, target.getClass().getName() + "#" + accessor + " missing", null);
                return Optional.empty();
            }
            return Optional.ofNullable(method.invoke(target));
        } catch (Throwable throwable) {
            disable(capability, target.getClass().getName() + "#" + accessor + " failed", throwable);
            return Optional.empty();
        }
    }

    private void resolvePublicApi() {
        Class<?> api = type(API);
        if (api == null) {
            for (TownsteadCapability capability : TownsteadCapability.values()) {
                diagnostics.put(capability, "missing public API class " + API);
            }
            return;
        }
        entity = method(api, "entity", Entity.class);
        calendar = method(api, "calendar", MinecraftServer.class);
        buildingAt = method(api, "buildingAt", ServerLevel.class, BlockPos.class);
        origin = method(api, "origin", ResourceLocation.class);
        gene = method(api, "gene", ResourceLocation.class);
        bindPublic(TownsteadCapability.READ_VILLAGER, entity,
                "com.aetherianartificer.townstead.api.TownsteadVillagerSnapshot",
                "uuid", "name", "entityType", "rootId", "lifeStage", "biologicalAgeDays",
                "apparentAgeYears", "immortal", "ageless", "senior", "personalityId",
                "professionId", "professionLevel", "professionXp", "fertility",
                "carriedVariants", "expressedAlleles", "heritage");
        bindNestedPublic(TownsteadCapability.READ_NEEDS, entity,
                "com.aetherianartificer.townstead.api.TownsteadVillagerSnapshot", "needs",
                "com.aetherianartificer.townstead.api.TownsteadNeedsSnapshot",
                "hunger", "saturation", "hungerExhaustion", "thirst", "quenched",
                "thirstExhaustion", "fatigue", "collapsed", "gated");
        bindNestedPublic(TownsteadCapability.READ_SCHEDULE, entity,
                "com.aetherianartificer.townstead.api.TownsteadVillagerSnapshot", "schedule",
                "com.aetherianartificer.townstead.api.TownsteadScheduleSnapshot",
                "mode", "templateId", "customShifts", "nonDefaultCustomShifts", "currentTickHour",
                "currentDisplayHour", "currentShiftOrdinal", "currentActivity", "plannedActivity",
                "currentTemplateId", "shifts", "weekDayTemplates");
        bindPublic(TownsteadCapability.READ_CALENDAR, calendar,
                "com.aetherianartificer.townstead.api.TownsteadCalendarSnapshot",
                "profileId", "worldDay", "epochYearOffset", "timeMode", "year", "month", "day",
                "dayOfYear", "dayOfWeek", "season");
        bindPublic(TownsteadCapability.READ_BUILDING, buildingAt,
                "com.aetherianartificer.townstead.api.TownsteadBuildingSnapshot",
                "id", "villageId", "type", "size", "centerX", "centerY", "centerZ",
                "minX", "minY", "minZ", "maxX", "maxY", "maxZ");
        bindPublic(TownsteadCapability.READ_ORIGIN, origin,
                "com.aetherianartificer.townstead.api.TownsteadRootSnapshot",
                "id", "displayName", "species", "ancestry", "lineage", "effectiveSpecies",
                "defaultGenes", "lifeStages");
        bindPublic(TownsteadCapability.READ_GENE, gene,
                "com.aetherianartificer.townstead.api.TownsteadGeneSnapshot",
                "id", "displayName", "description", "category", "dominance", "locus", "weight",
                "displayMode", "variants");
    }

    private void resolveBuildingList() {
        for (String root : MCA_ROOTS) {
            Class<?> manager = type(root + ".server.world.data.VillageManager");
            Class<?> village = type(root + ".server.world.data.Village");
            Class<?> building = type(root + ".server.world.data.Building");
            if (manager == null || village == null || building == null) continue;
            managerGet = method(manager, "get", ServerLevel.class);
            managerGetOrEmpty = method(manager, "getOrEmpty", int.class);
            villageGetBuildings = method(village, "getBuildings");
            if (managerGet != null && managerGetOrEmpty != null && villageGetBuildings != null
                    && hasAccessors(building, "getId", "getType", "getSize", "getCenter", "getPos0", "getPos1")) {
                enable(TownsteadCapability.READ_BUILDINGS, "MCA direct loaded-village query (" + root + ")");
                return;
            }
        }
        diagnostics.put(TownsteadCapability.READ_BUILDINGS, "no supported MCA loaded-village query bound");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void resolveReaction() {
        try {
            Class<?> context = type("com.aetherianartificer.townstead.reaction.ReactionContext");
            Class<?> source = type("com.aetherianartificer.townstead.reaction.ReactionContext$TriggerSource");
            Class<?> dispatcher = type("com.aetherianartificer.townstead.reaction.ReactionDispatcher");
            if (context == null || source == null || dispatcher == null) throw new ReflectiveOperationException();
            reactionContext = context.getConstructor(source, Player.class, BlockPos.class, Set.class, int.class);
            reactionContextSource = Enum.valueOf((Class<? extends Enum>) source.asSubclass(Enum.class), "CONTEXT");
            reactionFire = dispatcher.getMethod("fire", ServerLevel.class, LivingEntity.class,
                    ResourceLocation.class, context);
            enable(TownsteadCapability.DISPATCH_REACTION, "Townstead ReactionDispatcher");
        } catch (Throwable throwable) {
            diagnostics.put(TownsteadCapability.DISPATCH_REACTION, "Townstead reaction surface unavailable");
        }
    }

    private void resolveSpirit() {
        Class<?> cache = type("com.aetherianartificer.townstead.spirit.VillageSpiritCache");
        if (cache != null) spiritGet = method(cache, "get", ServerLevel.class, int.class);
        if (spiritGet != null) enable(TownsteadCapability.READ_SPIRIT, "Townstead VillageSpiritCache");
        else diagnostics.put(TownsteadCapability.READ_SPIRIT, "Townstead spirit cache unavailable");
    }

    private void bindPublic(TownsteadCapability capability, Method entry, String snapshotName,
                            String... accessors) {
        Class<?> snapshot = type(snapshotName);
        if (entry != null && snapshot != null && hasAccessors(snapshot, accessors)) {
            enable(capability, "Townstead public API");
        } else {
            diagnostics.put(capability, "Townstead public API member unavailable");
        }
    }

    private void bindNestedPublic(TownsteadCapability capability, Method entry, String ownerName,
                                  String ownerAccessor, String snapshotName, String... accessors) {
        Class<?> owner = type(ownerName);
        Class<?> snapshot = type(snapshotName);
        if (entry != null && owner != null && method(owner, ownerAccessor) != null
                && snapshot != null && hasAccessors(snapshot, accessors)) {
            enable(capability, "Townstead public API");
        } else {
            diagnostics.put(capability, "Townstead public API member unavailable");
        }
    }

    private void enable(TownsteadCapability capability, String source) {
        capabilities.add(capability);
        diagnostics.put(capability, source);
    }

    private void disable(TownsteadCapability capability, String reason, Throwable throwable) {
        capabilities.remove(capability);
        diagnostics.put(capability, reason);
        if (reportedFailures.add(capability)) {
            if (throwable == null) LOGGER.error("[Ultima Kingdoms] Townstead {} disabled: {}", capability, reason);
            else LOGGER.error("[Ultima Kingdoms] Townstead {} disabled: {}", capability, reason, throwable);
        }
    }

    private Optional<Object> invoke(TownsteadCapability capability, Method method, Object target, Object... args) {
        if (!capabilities.contains(capability) || method == null) return Optional.empty();
        try {
            return Optional.ofNullable(method.invoke(target, args));
        } catch (Throwable throwable) {
            disable(capability, "runtime public API call failed", throwable);
            return Optional.empty();
        }
    }

    private static boolean hasAccessors(Class<?> type, String... names) {
        for (String name : names) if (method(type, name) == null) return false;
        return true;
    }

    private static Map<String, Method> accessors(Class<?> type) {
        Map<String, Method> result = new ConcurrentHashMap<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0) result.putIfAbsent(method.getName(), method);
        }
        return result;
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, false, TownsteadBinding.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) return null;
        try {
            return owner.getMethod(name, parameters);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
