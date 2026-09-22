package com.ultimakingdoms.compat.townstead;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.townstead.BoundCivicBuilding;
import com.ultimakingdoms.api.townstead.BuildingRecoveryPolicy;
import com.ultimakingdoms.api.townstead.BuildingRecoveryResult;
import com.ultimakingdoms.api.townstead.BuildingRecoveryStatus;
import com.ultimakingdoms.api.townstead.TownsteadBuildingView;
import com.ultimakingdoms.api.townstead.TownsteadCalendarView;
import com.ultimakingdoms.api.townstead.TownsteadCapability;
import com.ultimakingdoms.api.townstead.TownsteadGeneVariantView;
import com.ultimakingdoms.api.townstead.TownsteadGeneView;
import com.ultimakingdoms.api.townstead.TownsteadLifeStageView;
import com.ultimakingdoms.api.townstead.TownsteadNeedsView;
import com.ultimakingdoms.api.townstead.TownsteadOriginView;
import com.ultimakingdoms.api.townstead.TownsteadScheduleView;
import com.ultimakingdoms.api.townstead.TownsteadService;
import com.ultimakingdoms.api.townstead.TownsteadSpiritView;
import com.ultimakingdoms.api.townstead.TownsteadVillagerView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class TownsteadServiceImpl implements TownsteadService {
    private static final TownsteadScheduleView EMPTY_SCHEDULE = new TownsteadScheduleView(
            "", "", false, false, 0, 0, 0, "", "", "", List.of(), List.of());
    private static final TownsteadNeedsView EMPTY_NEEDS =
            new TownsteadNeedsView(0, 0, 0, 0, 0, 0, 0, false, false);

    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final TownsteadBinding binding;
    private final AtomicLong revision = new AtomicLong();

    TownsteadServiceImpl(MinecraftServer server, KingdomsService kingdoms, TownsteadBinding binding) {
        this.server = Objects.requireNonNull(server, "server");
        this.kingdoms = Objects.requireNonNull(kingdoms, "kingdoms");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override public int apiVersion() { return 1; }
    @Override public Set<TownsteadCapability> capabilities() { return binding.capabilities(); }
    @Override public Map<TownsteadCapability, String> diagnostics() { return binding.diagnostics(); }

    @Override
    public Optional<TownsteadVillagerView> villager(Entity entity) {
        if (entity.getServer() != server) return Optional.empty();
        return binding.entity(entity).flatMap(this::villagerView);
    }

    @Override
    public Optional<TownsteadCalendarView> calendar() {
        return binding.calendar(server).flatMap(this::calendarView);
    }

    @Override
    public Optional<TownsteadBuildingView> buildingAt(ServerLevel level, BlockPos pos) {
        if (level.getServer() != server) return Optional.empty();
        return binding.buildingAt(level, pos).flatMap(snapshot -> publicBuilding(level, snapshot));
    }

    @Override
    public List<TownsteadBuildingView> buildings(ServerLevel level, UUID settlementId) {
        if (level.getServer() != server) return List.of();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty() || !settlement.get().dimension().equals(level.dimension())) return List.of();
        Optional<McaCommunityRef> community = Optional.ofNullable(
                        settlement.get().externalRefs().get(McaCommunityRef.EXTERNAL_REF_NAMESPACE))
                .flatMap(McaCommunityRef::parse)
                .filter(ref -> ref.dimension().equals(level.dimension().location()));
        if (community.isEmpty()) return List.of();
        return binding.buildings(level, community.get().villageId()).stream()
                .map(raw -> internalBuilding(level, settlement.get(), community.get().villageId(), raw))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(TownsteadBuildingView::buildingId))
                .toList();
    }

    @Override
    public Optional<TownsteadOriginView> origin(ResourceLocation id) {
        return binding.origin(id).flatMap(this::originView);
    }

    @Override
    public Optional<TownsteadGeneView> gene(ResourceLocation id) {
        return binding.gene(id).flatMap(this::geneView);
    }

    @Override
    public Optional<TownsteadSpiritView> spirit(ServerLevel level, UUID settlementId) {
        if (level.getServer() != server) return Optional.empty();
        Optional<SettlementView> settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty() || !settlement.get().dimension().equals(level.dimension())) return Optional.empty();
        Optional<McaCommunityRef> community = Optional.ofNullable(
                        settlement.get().externalRefs().get(McaCommunityRef.EXTERNAL_REF_NAMESPACE))
                .flatMap(McaCommunityRef::parse)
                .filter(ref -> ref.dimension().equals(level.dimension().location()));
        return community.flatMap(ref -> binding.spirit(level, ref.villageId())
                .flatMap(entry -> spiritView(ref.villageId(), entry)));
    }

    @Override
    public BuildingRecoveryResult recover(ServerLevel level, BoundCivicBuilding bound,
                                           BuildingRecoveryPolicy policy) {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(policy, "policy");
        if (level.getServer() != server || !level.dimension().location().equals(bound.dimension())) {
            return result(BuildingRecoveryStatus.WAITING, bound, null, "bound dimension is not loaded here");
        }
        Optional<SettlementView> settlement = kingdoms.getSettlement(bound.settlementId());
        if (settlement.isEmpty()) {
            return result(BuildingRecoveryStatus.FAILED, bound, null, "settlement no longer exists");
        }
        List<TownsteadBuildingView> buildings = buildings(level, settlement.get().id());
        Optional<TownsteadBuildingView> exact = buildings.stream()
                .filter(building -> building.buildingId() == bound.buildingId())
                .filter(building -> building.family().equals(bound.family()))
                .findFirst();
        if (exact.isPresent()) {
            return result(BuildingRecoveryStatus.FOUND, bound, exact.get(), "building resolved");
        }
        if (!capabilities().contains(TownsteadCapability.READ_BUILDINGS)) {
            return result(missingBuildingStatus(policy, false), bound, null,
                    "loaded-village building capability is unavailable");
        }
        return switch (policy) {
            case WAIT -> result(missingBuildingStatus(policy, true), bound, null, "bound building is absent");
            case FAIL_WITH_REASON -> result(missingBuildingStatus(policy, true), bound, null,
                    "bound building is absent or changed family");
            case REBIND_SAME_FAMILY -> sameFamilyCandidate(buildings, bound.family())
                    .map(building -> result(BuildingRecoveryStatus.REBOUND,
                            BoundCivicBuilding.from(building), building,
                            "rebound to lowest building id in the same family"))
                    .orElseGet(() -> result(BuildingRecoveryStatus.WAITING, bound, null,
                            "no loaded building in the required family"));
        };
    }

    static BuildingRecoveryStatus missingBuildingStatus(BuildingRecoveryPolicy policy, boolean readable) {
        return readable && policy == BuildingRecoveryPolicy.FAIL_WITH_REASON
                ? BuildingRecoveryStatus.FAILED : BuildingRecoveryStatus.WAITING;
    }

    @Override
    public boolean fireReaction(ServerLevel level, LivingEntity villager, Optional<Player> playerCause,
                                ResourceLocation reactionId, Set<String> contextTags) {
        if (!TownsteadIntegrationConfig.ENABLE_REACTIONS.get()
                || level.getServer() != server || villager.level() != level) return false;
        return binding.fireReaction(level, villager, playerCause, reactionId, contextTags);
    }

    @Override public long revision() { return revision.get(); }

    void reconciledChange() { revision.incrementAndGet(); }

    private Optional<TownsteadVillagerView> villagerView(Object snapshot) {
        UUID id = uuid(value(TownsteadCapability.READ_VILLAGER, snapshot, "uuid"));
        ResourceLocation entityType = resource(value(TownsteadCapability.READ_VILLAGER, snapshot, "entityType"));
        if (id == null || entityType == null) return Optional.empty();
        TownsteadScheduleView schedule = binding.call(TownsteadCapability.READ_SCHEDULE, snapshot, "schedule")
                .flatMap(this::scheduleView).orElse(EMPTY_SCHEDULE);
        TownsteadNeedsView needs = binding.call(TownsteadCapability.READ_NEEDS, snapshot, "needs")
                .flatMap(this::needsView).orElse(EMPTY_NEEDS);
        return Optional.of(new TownsteadVillagerView(
                id, string(value(TownsteadCapability.READ_VILLAGER, snapshot, "name")), entityType,
                optionalResource(value(TownsteadCapability.READ_VILLAGER, snapshot, "rootId")),
                string(value(TownsteadCapability.READ_VILLAGER, snapshot, "lifeStage")),
                number(value(TownsteadCapability.READ_VILLAGER, snapshot, "biologicalAgeDays")).longValue(),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "apparentAgeYears")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "immortal")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "ageless")),
                bool(value(TownsteadCapability.READ_VILLAGER, snapshot, "senior")),
                string(value(TownsteadCapability.READ_VILLAGER, snapshot, "personalityId")),
                optionalResource(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionId")),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionLevel")),
                integer(value(TownsteadCapability.READ_VILLAGER, snapshot, "professionXp")),
                number(value(TownsteadCapability.READ_VILLAGER, snapshot, "fertility")).floatValue(),
                schedule, needs,
                stringMap(value(TownsteadCapability.READ_VILLAGER, snapshot, "carriedVariants")),
                stringList(value(TownsteadCapability.READ_VILLAGER, snapshot, "expressedAlleles")),
                resourceFloatMap(value(TownsteadCapability.READ_VILLAGER, snapshot, "heritage"))));
    }

    private Optional<TownsteadScheduleView> scheduleView(Object value) {
        return Optional.of(new TownsteadScheduleView(
                string(value(TownsteadCapability.READ_SCHEDULE, value, "mode")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "templateId")),
                bool(value(TownsteadCapability.READ_SCHEDULE, value, "customShifts")),
                bool(value(TownsteadCapability.READ_SCHEDULE, value, "nonDefaultCustomShifts")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentTickHour")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentDisplayHour")),
                integer(value(TownsteadCapability.READ_SCHEDULE, value, "currentShiftOrdinal")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "currentActivity")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "plannedActivity")),
                string(value(TownsteadCapability.READ_SCHEDULE, value, "currentTemplateId")),
                intList(value(TownsteadCapability.READ_SCHEDULE, value, "shifts")),
                stringList(value(TownsteadCapability.READ_SCHEDULE, value, "weekDayTemplates"))));
    }

    private Optional<TownsteadNeedsView> needsView(Object value) {
        return Optional.of(new TownsteadNeedsView(
                integer(value(TownsteadCapability.READ_NEEDS, value, "hunger")),
                number(value(TownsteadCapability.READ_NEEDS, value, "saturation")).floatValue(),
                number(value(TownsteadCapability.READ_NEEDS, value, "hungerExhaustion")).floatValue(),
                integer(value(TownsteadCapability.READ_NEEDS, value, "thirst")),
                integer(value(TownsteadCapability.READ_NEEDS, value, "quenched")),
                number(value(TownsteadCapability.READ_NEEDS, value, "thirstExhaustion")).floatValue(),
                integer(value(TownsteadCapability.READ_NEEDS, value, "fatigue")),
                bool(value(TownsteadCapability.READ_NEEDS, value, "collapsed")),
                bool(value(TownsteadCapability.READ_NEEDS, value, "gated"))));
    }

    private Optional<TownsteadCalendarView> calendarView(Object value) {
        return Optional.of(new TownsteadCalendarView(
                string(value(TownsteadCapability.READ_CALENDAR, value, "profileId")),
                number(value(TownsteadCapability.READ_CALENDAR, value, "worldDay")).longValue(),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "epochYearOffset")),
                string(value(TownsteadCapability.READ_CALENDAR, value, "timeMode")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "year")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "month")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "day")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "dayOfYear")),
                integer(value(TownsteadCapability.READ_CALENDAR, value, "dayOfWeek")),
                string(value(TownsteadCapability.READ_CALENDAR, value, "season"))));
    }

    private Optional<TownsteadBuildingView> publicBuilding(ServerLevel level, Object value) {
        try {
            int villageId = integer(value(TownsteadCapability.READ_BUILDING, value, "villageId"));
            Optional<SettlementView> settlement = kingdoms.getSettlementForMcaVillage(
                    level.dimension().location(), villageId);
            return settlement.map(found -> building(level, found, villageId, value,
                    TownsteadCapability.READ_BUILDING, false));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<TownsteadBuildingView> internalBuilding(ServerLevel level, SettlementView settlement,
                                                             int villageId, Object value) {
        try {
            return Optional.of(building(level, settlement, villageId, value, TownsteadCapability.READ_BUILDINGS, true));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private TownsteadBuildingView building(ServerLevel level, SettlementView settlement, int villageId,
                                            Object value, TownsteadCapability capability, boolean beanAccessors) {
        String prefix = beanAccessors ? "get" : "";
        int id = integer(value(capability, value, prefix + (beanAccessors ? "Id" : "id")));
        String type = string(value(capability, value, prefix + (beanAccessors ? "Type" : "type")));
        int size = integer(value(capability, value, prefix + (beanAccessors ? "Size" : "size")));
        int cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ;
        if (beanAccessors) {
            BlockPos center = (BlockPos) value(capability, value, "getCenter");
            BlockPos p0 = (BlockPos) value(capability, value, "getPos0");
            BlockPos p1 = (BlockPos) value(capability, value, "getPos1");
            if (center == null || p0 == null || p1 == null) throw new IllegalArgumentException("missing building bounds");
            cx = center.getX(); cy = center.getY(); cz = center.getZ();
            minX = Math.min(p0.getX(), p1.getX()); minY = Math.min(p0.getY(), p1.getY()); minZ = Math.min(p0.getZ(), p1.getZ());
            maxX = Math.max(p0.getX(), p1.getX()); maxY = Math.max(p0.getY(), p1.getY()); maxZ = Math.max(p0.getZ(), p1.getZ());
        } else {
            cx = integer(value(capability, value, "centerX")); cy = integer(value(capability, value, "centerY"));
            cz = integer(value(capability, value, "centerZ")); minX = integer(value(capability, value, "minX"));
            minY = integer(value(capability, value, "minY")); minZ = integer(value(capability, value, "minZ"));
            maxX = integer(value(capability, value, "maxX")); maxY = integer(value(capability, value, "maxY"));
            maxZ = integer(value(capability, value, "maxZ"));
        }
        String family = TownsteadBuildingView.familyOf(type);
        UUID bindingId = TownsteadBuildingView.bindingId(level.dimension().location(), settlement.id(), villageId, id);
        return new TownsteadBuildingView(level.dimension().location(), settlement.id(), bindingId,
                villageId, id, type, family, TownsteadBuildingView.levelOf(type), size,
                cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private Optional<TownsteadOriginView> originView(Object value) {
        ResourceLocation id = resource(value(TownsteadCapability.READ_ORIGIN, value, "id"));
        if (id == null) return Optional.empty();
        List<TownsteadLifeStageView> stages = objectList(value(TownsteadCapability.READ_ORIGIN, value, "lifeStages"))
                .stream().map(this::lifeStage).toList();
        return Optional.of(new TownsteadOriginView(id,
                string(value(TownsteadCapability.READ_ORIGIN, value, "displayName")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "species")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "ancestry")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "lineage")),
                optionalResource(value(TownsteadCapability.READ_ORIGIN, value, "effectiveSpecies")),
                resourceList(value(TownsteadCapability.READ_ORIGIN, value, "defaultGenes")), stages));
    }

    private TownsteadLifeStageView lifeStage(Object value) {
        return new TownsteadLifeStageView(
                string(callRaw(value, "id")), string(callRaw(value, "label")), integer(callRaw(value, "days")),
                number(callRaw(value, "scale")).floatValue(), string(callRaw(value, "presentsAs")),
                number(callRaw(value, "narrativeStart")).floatValue(),
                number(callRaw(value, "narrativeEnd")).floatValue());
    }

    private Optional<TownsteadGeneView> geneView(Object value) {
        ResourceLocation id = resource(value(TownsteadCapability.READ_GENE, value, "id"));
        if (id == null) return Optional.empty();
        List<TownsteadGeneVariantView> variants = objectList(value(TownsteadCapability.READ_GENE, value, "variants"))
                .stream().map(this::geneVariant).toList();
        return Optional.of(new TownsteadGeneView(id,
                string(value(TownsteadCapability.READ_GENE, value, "displayName")),
                string(value(TownsteadCapability.READ_GENE, value, "description")),
                string(value(TownsteadCapability.READ_GENE, value, "category")),
                string(value(TownsteadCapability.READ_GENE, value, "dominance")),
                optionalResource(value(TownsteadCapability.READ_GENE, value, "locus")),
                integer(value(TownsteadCapability.READ_GENE, value, "weight")),
                string(value(TownsteadCapability.READ_GENE, value, "displayMode")), variants));
    }

    private TownsteadGeneVariantView geneVariant(Object value) {
        return new TownsteadGeneVariantView(string(callRaw(value, "id")), string(callRaw(value, "displayName")),
                integer(callRaw(value, "weight")), string(callRaw(value, "type")));
    }

    private Optional<TownsteadSpiritView> spiritView(int villageId, Object entry) {
        Object totals = value(TownsteadCapability.READ_SPIRIT, entry, "totals");
        Object readout = value(TownsteadCapability.READ_SPIRIT, entry, "readout");
        if (totals == null || readout == null) return Optional.empty();
        Object classification = value(TownsteadCapability.READ_SPIRIT, readout, "classification");
        return Optional.of(new TownsteadSpiritView(villageId,
                integerMap(value(TownsteadCapability.READ_SPIRIT, totals, "perSpirit")),
                integer(value(TownsteadCapability.READ_SPIRIT, totals, "total")),
                integer(value(TownsteadCapability.READ_SPIRIT, totals, "contributingBuildings")),
                integer(value(TownsteadCapability.READ_SPIRIT, readout, "tierIndex")),
                classification == null ? "" : classification.toString().toLowerCase(java.util.Locale.ROOT),
                optionalString(value(TownsteadCapability.READ_SPIRIT, readout, "primarySpiritId")),
                optionalString(value(TownsteadCapability.READ_SPIRIT, readout, "secondarySpiritId"))));
    }

    private Object value(TownsteadCapability capability, Object target, String accessor) {
        return binding.call(capability, target, accessor).orElse(null);
    }

    private Object callRaw(Object target, String accessor) {
        try { return target.getClass().getMethod(accessor).invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    private static BuildingRecoveryResult result(BuildingRecoveryStatus status, BoundCivicBuilding binding,
                                                 TownsteadBuildingView building, String reason) {
        return new BuildingRecoveryResult(status, Optional.ofNullable(binding), Optional.ofNullable(building), reason);
    }

    static Optional<TownsteadBuildingView> sameFamilyCandidate(List<TownsteadBuildingView> buildings,
                                                                String family) {
        Objects.requireNonNull(buildings, "buildings");
        Objects.requireNonNull(family, "family");
        return buildings.stream()
                .filter(building -> building.family().equals(family))
                .min(Comparator.comparingInt(TownsteadBuildingView::buildingId));
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value) { return value instanceof Boolean b && b; }
    private static Number number(Object value) { return value instanceof Number n ? n : 0; }
    private static int integer(Object value) { return number(value).intValue(); }
    private static UUID uuid(Object value) { try { return UUID.fromString(string(value)); } catch (Exception ignored) { return null; } }
    private static ResourceLocation resource(Object value) { return ResourceLocation.tryParse(string(value)); }
    private static Optional<ResourceLocation> optionalResource(Object value) {
        String raw = string(value); return raw.isBlank() ? Optional.empty() : Optional.ofNullable(ResourceLocation.tryParse(raw));
    }
    private static Optional<String> optionalString(Object value) {
        return value == null || string(value).isBlank() ? Optional.empty() : Optional.of(string(value));
    }
    private static List<Object> objectList(Object value) { return value instanceof List<?> list ? new ArrayList<>(list) : List.of(); }
    private static List<String> stringList(Object value) { return objectList(value).stream().map(String::valueOf).toList(); }
    private static List<Integer> intList(Object value) { return objectList(value).stream().map(TownsteadServiceImpl::integer).toList(); }
    private static List<ResourceLocation> resourceList(Object value) { return objectList(value).stream().map(TownsteadServiceImpl::resource).filter(Objects::nonNull).toList(); }
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>(); map.forEach((key, item) -> out.put(String.valueOf(key), String.valueOf(item))); return Map.copyOf(out);
    }
    private static Map<ResourceLocation, Float> resourceFloatMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<ResourceLocation, Float> out = new LinkedHashMap<>();
        map.forEach((key, item) -> { ResourceLocation id = ResourceLocation.tryParse(String.valueOf(key)); if (id != null && item instanceof Number n) out.put(id, n.floatValue()); });
        return Map.copyOf(out);
    }
    private static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>(); map.forEach((key, item) -> out.put(String.valueOf(key), integer(item))); return Map.copyOf(out);
    }
}
