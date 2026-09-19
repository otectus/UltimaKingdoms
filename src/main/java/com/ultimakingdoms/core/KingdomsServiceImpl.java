package com.ultimakingdoms.core;

import com.ultimakingdoms.api.AssignmentSource;
import com.ultimakingdoms.api.ChangeReason;
import com.ultimakingdoms.api.CivicEvidenceProvider;
import com.ultimakingdoms.api.CivicIdentityHint;
import com.ultimakingdoms.api.CivicIdentitySource;
import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.DetectionSource;
import com.ultimakingdoms.api.GeneratedName;
import com.ultimakingdoms.api.KingdomResolution;
import com.ultimakingdoms.api.KingdomResolver;
import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementDetector;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.event.CivicIdentityChangedEvent;
import com.ultimakingdoms.api.event.KingdomDefinitionsReloadedEvent;
import com.ultimakingdoms.api.event.ResidentJoinedEvent;
import com.ultimakingdoms.api.event.ResidentLeftEvent;
import com.ultimakingdoms.api.event.SettlementCreatedEvent;
import com.ultimakingdoms.api.event.SettlementDiscoveredEvent;
import com.ultimakingdoms.api.event.SettlementKingdomChangedEvent;
import com.ultimakingdoms.api.event.SettlementRenamedEvent;
import com.ultimakingdoms.citizen.CivicIdentityStore;
import com.ultimakingdoms.config.UltimaKingdomsConfig;
import com.ultimakingdoms.data.DefinitionRegistry;
import com.ultimakingdoms.naming.VillageNameGenerator;
import com.ultimakingdoms.settlement.PoiClusterDetector;
import com.ultimakingdoms.settlement.SpatialSettlementIndex;
import com.ultimakingdoms.settlement.VillageStructureDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class KingdomsServiceImpl implements KingdomsService {
    private static final ResourceLocation BUILTIN_POI = new ResourceLocation(UltimaKingdomsApi.MOD_ID, "poi_cluster");
    private static final ResourceLocation BUILTIN_STRUCTURE = new ResourceLocation(UltimaKingdomsApi.MOD_ID, "village_structure");
    private static final ResourceLocation MANUAL_SOURCE = new ResourceLocation(UltimaKingdomsApi.MOD_ID, "manual");
    private static final ResourceLocation UNKNOWN_BIOME = new ResourceLocation("minecraft", "the_void");
    private static final long OBSERVATION_WRITE_INTERVAL = 1_200L;

    private final MinecraftServer server;
    private final DefinitionRegistry definitions;
    private final SettlementSavedData data;
    private final SpatialSettlementIndex spatialIndex = new SpatialSettlementIndex();
    private final CivicIdentityStore civicStore = new CivicIdentityStore();
    private final Map<ResourceLocation, SettlementDetector> detectors = new LinkedHashMap<>();
    private final Map<ResourceLocation, KingdomResolver> resolvers = new LinkedHashMap<>();
    private final Map<ResourceLocation, CivicEvidenceProvider> evidenceProviders = new LinkedHashMap<>();
    private final Deque<QueuedChunk> chunkQueue = new ArrayDeque<>();
    private final Set<QueuedChunk> queuedChunks = new LinkedHashSet<>();

    public KingdomsServiceImpl(MinecraftServer server, DefinitionRegistry definitions) {
        this.server = Objects.requireNonNull(server, "server");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.data = SettlementSavedData.get(server);
        spatialIndex.rebuild(data.records().stream().map(SettlementRecord::snapshot).toList());
        detectors.put(BUILTIN_STRUCTURE, new VillageStructureDetector());
        detectors.put(BUILTIN_POI, new PoiClusterDetector(
                UltimaKingdomsConfig.POI_SEARCH_RADIUS.get(),
                UltimaKingdomsConfig.POI_MINIMUM_COUNT.get(),
                UltimaKingdomsConfig.DEFAULT_SETTLEMENT_RADIUS.get()
        ));
    }

    @Override
    public Optional<KingdomView> getKingdom(ResourceLocation id) {
        requireServerThread();
        ResourceLocation checked = Objects.requireNonNull(id, "id");
        Optional<KingdomView> defined = definitions.snapshot().kingdom(checked);
        if (defined.isPresent()) return defined;
        return data.records().stream().anyMatch(record -> record.kingdomId.equals(checked))
                ? Optional.of(new MissingKingdomView(checked)) : Optional.empty();
    }

    @Override
    public Collection<KingdomView> getKingdoms() {
        requireServerThread();
        Map<ResourceLocation, KingdomView> kingdoms = new LinkedHashMap<>();
        definitions.snapshot().kingdoms().forEach(kingdom -> kingdoms.put(kingdom.id(), kingdom));
        data.records().stream().map(record -> record.kingdomId).distinct()
                .forEach(id -> kingdoms.putIfAbsent(id, new MissingKingdomView(id)));
        return List.copyOf(kingdoms.values());
    }

    @Override
    public Optional<SettlementView> getSettlement(UUID id) {
        requireServerThread();
        return snapshot(id).map(value -> (SettlementView) value);
    }

    @Override
    public Optional<SettlementView> getSettlementAt(ServerLevel level, BlockPos pos) {
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        return spatialIndex.at(level.dimension(), pos).stream()
                .map(data::get)
                .flatMap(Optional::stream)
                .filter(record -> record.dimension.equals(level.dimension()) && record.bounds.contains(pos))
                .min(Comparator.comparingInt(record -> distanceSquared(record.anchor, pos)))
                .map(SettlementRecord::snapshot);
    }

    @Override
    public Collection<SettlementView> getSettlements(ResourceLocation kingdomId) {
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        return data.records().stream()
                .filter(record -> record.kingdomId.equals(kingdomId))
                .map(record -> (SettlementView) record.snapshot())
                .sorted(settlementOrder())
                .toList();
    }

    @Override
    public List<SettlementView> getSettlementPage(Optional<ResourceLocation> kingdomId, int offset, int limit) {
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        if (offset < 0) throw new IllegalArgumentException("Settlement page offset must be non-negative");
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Settlement page limit must be from 1 to 64");
        return data.records().stream()
                .filter(record -> kingdomId.isEmpty() || record.kingdomId.equals(kingdomId.get()))
                .map(record -> (SettlementView) record.snapshot())
                .sorted(settlementOrder())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<SettlementView> findSettlement(String query) {
        requireServerThread();
        String value = Objects.requireNonNull(query, "query").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Settlement query must not be blank");
        try {
            Optional<SettlementRecord> byUuid = data.get(UUID.fromString(value));
            if (byUuid.isPresent()) return Optional.of(byUuid.get().snapshot());
        } catch (IllegalArgumentException ignored) {
            // Continue with slug and human-readable lookup.
        }

        ResourceLocation parsedSlug = ResourceLocation.tryParse(value.contains(":")
                ? value : UltimaKingdomsApi.MOD_ID + ":" + value.toLowerCase(Locale.ROOT));
        List<SettlementRecord> matches = data.records().stream()
                .filter(record -> (parsedSlug != null
                        && (record.slug.equals(parsedSlug) || record.retiredSlugs.contains(parsedSlug)))
                        || VillageNameGenerator.normalize(record.displayName)
                                .equals(VillageNameGenerator.normalize(value))
                        || record.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias)
                                .equals(VillageNameGenerator.normalize(value))))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Settlement name is ambiguous: " + value);
        }
        return matches.stream().findFirst().map(SettlementRecord::snapshot);
    }

    @Override
    public Optional<SettlementView> getResidence(Entity entity) {
        requireServerThread();
        return identity(entity).flatMap(CivicIdentitySnapshot::residenceSettlement).flatMap(this::snapshot)
                .map(value -> (SettlementView) value);
    }

    @Override
    public Optional<CivicIdentityView> getCivicIdentity(Entity entity) {
        requireServerThread();
        return identity(entity).map(value -> (CivicIdentityView) value);
    }

    @Override
    public Map<String, String> getContext(Entity entity) {
        requireServerThread();
        Optional<CivicIdentitySnapshot> identity = identity(entity);
        if (identity.isEmpty()) return Map.of();
        Map<String, String> context = new LinkedHashMap<>();
        identity.get().residenceSettlement().flatMap(this::snapshot).ifPresent(settlement -> {
            context.put("ultima.kingdom", displayId(settlement.kingdomId()));
            context.put("ultima.kingdom_id", settlement.kingdomId().toString());
            context.put("ultima.village", settlement.displayName());
            context.put("ultima.village_id", settlement.id().toString());
            context.put("ultima.residence_kingdom", displayId(settlement.kingdomId()));
            context.put("ultima.residence_kingdom_id", settlement.kingdomId().toString());
            context.put("ultima.residence_village", settlement.displayName());
            context.put("ultima.residence_village_id", settlement.id().toString());
        });
        identity.get().originKingdom().ifPresent(kingdom -> {
            context.put("ultima.origin_kingdom", displayId(kingdom));
            context.put("ultima.origin_kingdom_id", kingdom.toString());
        });
        identity.get().originSettlement().flatMap(this::snapshot).ifPresent(settlement -> {
            context.put("ultima.origin_village", settlement.displayName());
            context.put("ultima.origin_village_id", settlement.id().toString());
        });
        return Map.copyOf(context);
    }

    @Override
    public long revision() {
        requireServerThread();
        return data.revision() + definitions.snapshot().revision();
    }

    @Override
    public SettlementView registerCandidate(ServerLevel level, SettlementCandidate candidate) {
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(candidate, "candidate");
        if (!level.dimension().equals(candidate.dimension())) {
            throw new IllegalArgumentException("Candidate dimension does not match the supplied level");
        }
        Optional<SettlementRecord> matched = match(candidate);
        if (matched.isPresent()) {
            observe(matched.get(), candidate, level.getGameTime());
            return matched.get().snapshot();
        }

        KingdomResolution resolution = resolve(level, candidate);
        if (definitions.snapshot().kingdom(resolution.kingdomId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + resolution.kingdomId());
        }
        GeneratedName name = candidate.proposedName()
                .map(this::proposedName)
                .orElseGet(() -> definitions.snapshot().generateName(level.getSeed(), candidate,
                        resolution.kingdomId(), this::normalizedNameTaken, this::slugTaken));
        if (normalizedNameTaken(name.normalizedKey()) || slugTaken(name.slug())) {
            throw new IllegalArgumentException("Generated settlement name is not unique: " + name.displayName());
        }

        SettlementRecord record = new SettlementRecord();
        record.id = UUID.randomUUID();
        record.dimension = candidate.dimension();
        record.anchor = candidate.anchor();
        record.radius = candidate.radius();
        record.bounds = candidate.bounds();
        record.kingdomId = resolution.kingdomId();
        record.displayName = name.displayName();
        record.slug = name.slug();
        record.biomeAtCreation = resolution.decisiveBiome().orElseGet(() -> biomeAt(level, candidate.anchor()));
        record.styleId = candidate.styleId().orElse(null);
        record.assignmentSource = resolution.source();
        record.detectionSource = candidate.detectionSource();
        record.createdGameTime = level.getGameTime();
        record.lastObservedGameTime = level.getGameTime();
        candidate.externalRefs().forEach(record::addExternalRef);
        record.assignmentTrace.addAll(resolution.trace());
        record.sourceId = candidate.sourceId();
        record.sourceKey = candidate.sourceKey();
        record.addDetectorIdentity(candidate.sourceId(), candidate.sourceKey());
        if (candidate.detectionSource() == DetectionSource.STRUCTURE) {
            record.addStrongStructureIdentity(candidate.sourceId(), candidate.sourceKey());
        }
        data.add(record);
        SettlementSnapshot snapshot = record.snapshot();
        spatialIndex.add(snapshot);
        MinecraftForge.EVENT_BUS.post(new SettlementCreatedEvent(snapshot));
        MinecraftForge.EVENT_BUS.post(new SettlementDiscoveredEvent(snapshot, candidate.sourceId()));
        return snapshot;
    }

    @Override
    public SettlementView rename(UUID settlementId, String newName) {
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.nameLocked) throw new IllegalArgumentException("Settlement name is locked: " + record.displayName);
        String clean = requireName(newName);
        String normalized = VillageNameGenerator.normalize(clean);
        if (data.records().stream().anyMatch(other -> other != record
                && (VillageNameGenerator.normalize(other.displayName).equals(normalized)
                || other.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias).equals(normalized))))) {
            throw new IllegalArgumentException("Settlement name is already in use: " + clean);
        }
        if (record.displayName.equals(clean)) return record.snapshot();
        String oldName = record.displayName;
        addAlias(record, oldName);
        record.displayName = clean;
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        MinecraftForge.EVENT_BUS.post(new SettlementRenamedEvent(snapshot, oldName, ChangeReason.API));
        return snapshot;
    }

    @Override
    public SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId) {
        return setKingdom(settlementId, kingdomId, ChangeReason.API);
    }

    @Override
    public SettlementView reclassify(UUID settlementId) {
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.kingdomLocked) throw new IllegalArgumentException("Settlement kingdom is locked: " + record.displayName);
        ServerLevel level = server.getLevel(record.dimension);
        if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable: " + record.dimension.location());
        SettlementCandidate candidate = asCandidate(record);
        KingdomResolution resolution = resolve(level, candidate);
        if (definitions.snapshot().kingdom(resolution.kingdomId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + resolution.kingdomId());
        }
        ResourceLocation oldKingdom = record.kingdomId;
        record.kingdomId = resolution.kingdomId();
        record.assignmentSource = resolution.source();
        record.assignmentTrace.clear();
        record.assignmentTrace.addAll(resolution.trace());
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        if (!oldKingdom.equals(record.kingdomId)) {
            MinecraftForge.EVENT_BUS.post(new SettlementKingdomChangedEvent(snapshot, oldKingdom,
                    record.kingdomId, ChangeReason.RECLASSIFICATION));
        }
        return snapshot;
    }

    @Override
    public SettlementView setLocks(UUID settlementId, boolean nameLocked, boolean kingdomLocked) {
        requireServerThread();
        SettlementRecord record = requireSettlement(settlementId);
        if (record.nameLocked == nameLocked && record.kingdomLocked == kingdomLocked) return record.snapshot();
        record.nameLocked = nameLocked;
        record.kingdomLocked = kingdomLocked;
        data.changed(record);
        return record.snapshot();
    }

    @Override
    public SettlementView merge(UUID sourceId, UUID targetId) {
        requireServerThread();
        UUID resolvedSource = data.resolveId(Objects.requireNonNull(sourceId, "sourceId"));
        UUID resolvedTarget = data.resolveId(Objects.requireNonNull(targetId, "targetId"));
        if (resolvedSource.equals(resolvedTarget)) throw new IllegalArgumentException("Settlements already resolve to the same record");
        SettlementRecord source = requireSettlement(resolvedSource);
        SettlementRecord target = requireSettlement(resolvedTarget);
        if (!source.dimension.equals(target.dimension)) {
            throw new IllegalArgumentException("Cannot merge settlements in different dimensions");
        }
        addAlias(target, source.displayName);
        source.aliases.forEach(alias -> addAlias(target, alias));
        target.retiredSlugs.add(source.slug);
        target.retiredSlugs.addAll(source.retiredSlugs);
        target.addDetectorIdentity(source.sourceId, source.sourceKey);
        if (source.detectionSource == DetectionSource.STRUCTURE) {
            target.addStrongStructureIdentity(source.sourceId, source.sourceKey);
        }
        source.detectorIdentities.forEach((detector, keys) ->
                keys.forEach(key -> target.addDetectorIdentity(detector, key)));
        source.strongStructureIdentities.forEach((detector, keys) ->
                keys.forEach(key -> target.addStrongStructureIdentity(detector, key)));
        source.externalRefs.forEach(target::addExternalRef);
        source.externalRefValues.forEach((namespace, values) ->
                values.forEach(value -> target.addExternalRef(namespace, value)));
        target.lastObservedGameTime = Math.max(target.lastObservedGameTime, source.lastObservedGameTime);
        spatialIndex.remove(source.snapshot());
        data.changed(target);
        data.redirect(source.id, target.id);
        return target.snapshot();
    }

    @Override
    public Collection<SettlementView> discover(ServerLevel level, BlockPos center, int radiusChunks) {
        requireServerThread();
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(center, "center");
        if (radiusChunks < 0 || radiusChunks > 32) {
            throw new IllegalArgumentException("Discovery radius must be from 0 to 32 chunks");
        }
        List<SettlementView> found = new ArrayList<>();
        detectors.forEach((owner, detector) -> detector.detect(level, center.immutable(), radiusChunks)
                .limit(256)
                .forEach(candidate -> found.add(registerCandidate(level, candidate))));
        return List.copyOf(found);
    }

    @Override
    public CivicIdentityView setOrigin(Entity entity, UUID settlementId) {
        requireEntity(entity);
        SettlementSnapshot settlement = snapshot(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + settlementId));
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(Optional.of(settlement.id()),
                Optional.of(settlement.kingdomId()), old.residenceSettlement(), old.residenceKingdom(),
                CivicIdentitySource.COMMAND, old.lastResidenceChange());
        civicStore.write(entity, updated);
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
        return updated;
    }

    @Override
    public CivicIdentityView setResidence(Entity entity, UUID settlementId) {
        requireEntity(entity);
        SettlementSnapshot settlement = snapshot(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + settlementId));
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        if (old.residenceSettlement().filter(settlement.id()::equals).isPresent()) return old;
        Optional<UUID> originSettlement = old.originSettlement().isPresent()
                ? old.originSettlement() : Optional.of(settlement.id());
        Optional<ResourceLocation> originKingdom = old.originKingdom().isPresent()
                ? old.originKingdom() : Optional.of(settlement.kingdomId());
        long gameTime = entity.level().getGameTime();
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(originSettlement, originKingdom,
                Optional.of(settlement.id()), Optional.of(settlement.kingdomId()), CivicIdentitySource.COMMAND,
                gameTime);
        civicStore.write(entity, updated);
        old.residenceSettlement().flatMap(this::snapshot).ifPresent(previous ->
                MinecraftForge.EVENT_BUS.post(new ResidentLeftEvent(entity.getUUID(), previous, updated)));
        MinecraftForge.EVENT_BUS.post(new ResidentJoinedEvent(entity.getUUID(), settlement, updated));
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
        return updated;
    }

    @Override
    public Registration registerSettlementDetector(ResourceLocation owner, SettlementDetector detector) {
        requireServerThread();
        return register(detectors, owner, detector, "settlement detector");
    }

    @Override
    public Registration registerKingdomResolver(ResourceLocation owner, KingdomResolver resolver) {
        requireServerThread();
        return register(resolvers, owner, resolver, "kingdom resolver");
    }

    @Override
    public Registration registerCivicEvidenceProvider(ResourceLocation owner, CivicEvidenceProvider provider) {
        requireServerThread();
        return register(evidenceProviders, owner, provider, "civic evidence provider");
    }

    public void enqueueChunk(ServerLevel level, ChunkPos chunkPos) {
        QueuedChunk queued = new QueuedChunk(level.dimension(), chunkPos.toLong());
        synchronized (chunkQueue) {
            if (queuedChunks.add(queued)) chunkQueue.addLast(queued);
        }
    }

    public void tickDiscovery() {
        requireServerThread();
        int budget = UltimaKingdomsConfig.CHUNK_SCANS_PER_TICK.get();
        for (int i = 0; i < budget; i++) {
            QueuedChunk queued;
            synchronized (chunkQueue) {
                queued = chunkQueue.pollFirst();
                if (queued != null) queuedChunks.remove(queued);
            }
            if (queued == null) return;
            ServerLevel level = server.getLevel(queued.dimension());
            int x = ChunkPos.getX(queued.chunk());
            int z = ChunkPos.getZ(queued.chunk());
            if (level == null || !level.hasChunk(x, z)) continue;
            BlockPos center = new BlockPos((x << 4) + 8, level.getSeaLevel(), (z << 4) + 8);
            discover(level, center, 0);
        }
    }

    public void observeEntity(Entity entity) {
        requireEntity(entity);
        for (CivicEvidenceProvider provider : List.copyOf(evidenceProviders.values())) {
            Optional<CivicIdentityHint> hint = provider.observe(entity);
            if (hint.isPresent()) {
                applyHint(entity, hint.get());
                return;
            }
        }
        if (identity(entity).isPresent()) return;
        getSettlementAt((ServerLevel) entity.level(), entity.blockPosition())
                .map(SettlementView::id)
                .ifPresent(id -> setResidence(entity, id));
    }

    public void copyIdentity(Entity source, Entity target) {
        requireServerThread();
        civicStore.copy(source, target);
    }

    public void definitionsReloaded() {
        requireServerThread();
        MinecraftForge.EVENT_BUS.post(new KingdomDefinitionsReloadedEvent(revision()));
    }

    private SettlementView setKingdom(UUID settlementId, ResourceLocation kingdomId, ChangeReason reason) {
        requireServerThread();
        Objects.requireNonNull(kingdomId, "kingdomId");
        if (definitions.snapshot().kingdom(kingdomId).isEmpty()) {
            throw new IllegalArgumentException("Unknown kingdom: " + kingdomId);
        }
        SettlementRecord record = requireSettlement(settlementId);
        if (record.kingdomLocked) throw new IllegalArgumentException("Settlement kingdom is locked: " + record.displayName);
        if (record.kingdomId.equals(kingdomId)) return record.snapshot();
        ResourceLocation oldKingdom = record.kingdomId;
        record.kingdomId = kingdomId;
        record.assignmentSource = AssignmentSource.MANUAL;
        record.assignmentTrace.clear();
        record.assignmentTrace.add("Kingdom assigned through " + reason.name().toLowerCase(Locale.ROOT));
        data.changed(record);
        SettlementSnapshot snapshot = record.snapshot();
        MinecraftForge.EVENT_BUS.post(new SettlementKingdomChangedEvent(snapshot, oldKingdom, kingdomId, reason));
        return snapshot;
    }

    private void applyHint(Entity entity, CivicIdentityHint hint) {
        CivicIdentitySnapshot old = identity(entity).orElse(CivicIdentitySnapshot.empty());
        Optional<SettlementSnapshot> hintedOrigin = hint.originSettlement().flatMap(this::snapshot);
        Optional<SettlementSnapshot> hintedResidence = hint.residenceSettlement().flatMap(this::snapshot);
        if (hint.originSettlement().isPresent() && hintedOrigin.isEmpty()) return;
        if (hint.residenceSettlement().isPresent() && hintedResidence.isEmpty()) return;
        Optional<UUID> originSettlement = old.originSettlement().isPresent()
                ? old.originSettlement() : hintedOrigin.map(SettlementSnapshot::id);
        Optional<ResourceLocation> originKingdom = old.originKingdom().isPresent()
                ? old.originKingdom() : hintedOrigin.map(SettlementSnapshot::kingdomId);
        Optional<UUID> residenceSettlement = hintedResidence.isPresent()
                ? hintedResidence.map(SettlementSnapshot::id) : old.residenceSettlement();
        Optional<ResourceLocation> residenceKingdom = residenceSettlement.flatMap(this::snapshot)
                .map(SettlementSnapshot::kingdomId);
        boolean residenceChanged = !residenceSettlement.equals(old.residenceSettlement());
        CivicIdentitySnapshot updated = new CivicIdentitySnapshot(
                originSettlement, originKingdom, residenceSettlement, residenceKingdom,
                hint.source(), residenceChanged ? entity.level().getGameTime() : old.lastResidenceChange());
        if (updated.equals(old)) return;
        civicStore.write(entity, updated);
        if (residenceChanged) {
            old.residenceSettlement().flatMap(this::snapshot).ifPresent(previous ->
                    MinecraftForge.EVENT_BUS.post(new ResidentLeftEvent(entity.getUUID(), previous, updated)));
            hintedResidence.ifPresent(current ->
                    MinecraftForge.EVENT_BUS.post(new ResidentJoinedEvent(entity.getUUID(), current, updated)));
        }
        MinecraftForge.EVENT_BUS.post(new CivicIdentityChangedEvent(entity.getUUID(), old, updated));
    }

    private Optional<CivicIdentitySnapshot> identity(Entity entity) {
        requireEntity(entity);
        Optional<CivicIdentitySnapshot> loaded = civicStore.read(entity, this::snapshot);
        if (loaded.isEmpty()) return Optional.empty();
        CivicIdentitySnapshot current = loaded.get();
        Optional<UUID> origin = current.originSettlement().map(data::resolveId);
        Optional<UUID> residence = current.residenceSettlement().map(data::resolveId);
        Optional<ResourceLocation> residenceKingdom = residence.flatMap(this::snapshot).map(SettlementSnapshot::kingdomId);
        if (!origin.equals(current.originSettlement()) || !residence.equals(current.residenceSettlement())
                || !residenceKingdom.equals(current.residenceKingdom())) {
            current = new CivicIdentitySnapshot(origin, current.originKingdom(), residence, residenceKingdom,
                    current.source(), current.lastResidenceChange());
            civicStore.write(entity, current);
        }
        return Optional.of(current);
    }

    private KingdomResolution resolve(ServerLevel level, SettlementCandidate candidate) {
        if (candidate.explicitKingdom().isPresent()) {
            ResourceLocation kingdom = candidate.explicitKingdom().get();
            AssignmentSource source = candidate.detectionSource() == DetectionSource.MANUAL
                    ? AssignmentSource.MANUAL : AssignmentSource.ADDON;
            return new KingdomResolution(kingdom, source, Optional.of(biomeAt(level, candidate.anchor())),
                    Integer.MAX_VALUE, 1.0, List.of("Explicit kingdom supplied by " + candidate.sourceId()));
        }
        for (KingdomResolver resolver : List.copyOf(resolvers.values())) {
            Optional<KingdomResolution> resolved = resolver.resolve(level, candidate);
            if (resolved.isPresent()) return resolved.get();
        }
        return definitions.snapshot().resolve(level, candidate);
    }

    private Optional<SettlementRecord> match(SettlementCandidate candidate) {
        for (SettlementRecord record : data.records()) {
            if (!record.dimension.equals(candidate.dimension())) continue;
            if (record.hasDetectorIdentity(candidate.sourceId(), candidate.sourceKey())) {
                return Optional.of(record);
            }
            if (candidate.externalRefs().entrySet().stream()
                    .anyMatch(entry -> record.hasExternalRef(entry.getKey(), entry.getValue()))) {
                return Optional.of(record);
            }
        }
        return data.records().stream()
                .filter(record -> record.dimension.equals(candidate.dimension()))
                .filter(record -> !differentStrongStructures(record, candidate))
                .filter(record -> substantiallyOverlaps(record.bounds, candidate.bounds())
                        || distanceSquared(record.anchor, candidate.anchor()) <= square(UltimaKingdomsConfig.CANDIDATE_MERGE_DISTANCE.get()))
                .min(Comparator.comparingInt(record -> distanceSquared(record.anchor, candidate.anchor())));
    }

    private void observe(SettlementRecord record, SettlementCandidate candidate, long gameTime) {
        boolean changed = false;
        if (!record.hasDetectorIdentity(candidate.sourceId(), candidate.sourceKey())) {
            record.addDetectorIdentity(candidate.sourceId(), candidate.sourceKey());
            changed = true;
        }
        if (candidate.detectionSource() == DetectionSource.STRUCTURE) {
            int before = record.strongStructureIdentities.getOrDefault(candidate.sourceId(), Set.of()).size();
            record.addStrongStructureIdentity(candidate.sourceId(), candidate.sourceKey());
            changed |= record.strongStructureIdentities.get(candidate.sourceId()).size() != before;
        }
        for (Map.Entry<String, String> entry : candidate.externalRefs().entrySet()) {
            changed |= record.addExternalRef(entry.getKey(), entry.getValue());
        }
        if (detectionRank(candidate.detectionSource()) > detectionRank(record.detectionSource)) {
            SettlementSnapshot oldSnapshot = record.snapshot();
            spatialIndex.remove(oldSnapshot);
            record.anchor = candidate.anchor();
            record.radius = candidate.radius();
            record.bounds = candidate.bounds();
            record.detectionSource = candidate.detectionSource();
            record.sourceId = candidate.sourceId();
            record.sourceKey = candidate.sourceKey();
            spatialIndex.add(record.snapshot());
            changed = true;
        }
        if (record.styleId == null && candidate.styleId().isPresent()) {
            record.styleId = candidate.styleId().get();
            changed = true;
        }
        if (gameTime - record.lastObservedGameTime >= OBSERVATION_WRITE_INTERVAL) {
            record.lastObservedGameTime = gameTime;
            changed = true;
        }
        if (changed) data.changed(record);
    }

    private GeneratedName proposedName(String requestedName) {
        String display = requireName(requestedName);
        String normalized = VillageNameGenerator.normalize(display);
        if (normalizedNameTaken(normalized)) throw new IllegalArgumentException("Settlement name is already in use: " + display);
        String base = slugPath(display);
        ResourceLocation slug = new ResourceLocation(UltimaKingdomsApi.MOD_ID, base);
        int suffix = 2;
        while (slugTaken(slug)) slug = new ResourceLocation(UltimaKingdomsApi.MOD_ID, base + "_" + suffix++);
        return new GeneratedName(display, normalized, slug, MANUAL_SOURCE, Optional.empty());
    }

    private boolean normalizedNameTaken(String value) {
        String normalized = VillageNameGenerator.normalize(value);
        return data.records().stream().anyMatch(record -> VillageNameGenerator.normalize(record.displayName).equals(normalized)
                || record.aliases.stream().anyMatch(alias -> VillageNameGenerator.normalize(alias).equals(normalized)));
    }

    private boolean slugTaken(ResourceLocation slug) {
        return data.records().stream().anyMatch(record -> record.slug.equals(slug) || record.retiredSlugs.contains(slug));
    }

    private Optional<SettlementSnapshot> snapshot(UUID id) {
        return data.get(Objects.requireNonNull(id, "id")).map(SettlementRecord::snapshot);
    }

    private SettlementRecord requireSettlement(UUID id) {
        return data.get(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement: " + id));
    }

    private void requireEntity(Entity entity) {
        requireServerThread();
        Objects.requireNonNull(entity, "entity");
        if (entity.getServer() != server || entity.level().isClientSide()) {
            throw new IllegalArgumentException("Entity is not attached to this server");
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Ultima Kingdoms API must be called on the server thread");
        }
    }

    private SettlementCandidate asCandidate(SettlementRecord record) {
        return new SettlementCandidate(record.dimension, record.anchor, record.radius, record.bounds,
                record.sourceId, record.sourceKey, record.detectionSource, Optional.ofNullable(record.styleId),
                Optional.empty(), record.externalRefs, Optional.of(record.displayName));
    }

    private static ResourceLocation biomeAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location()).orElse(UNKNOWN_BIOME);
    }

    private static boolean differentStrongStructures(SettlementRecord record, SettlementCandidate candidate) {
        return candidate.detectionSource() == DetectionSource.STRUCTURE
                && record.hasStrongStructureIdentities();
    }

    private static boolean substantiallyOverlaps(com.ultimakingdoms.api.SettlementBounds first,
                                                 com.ultimakingdoms.api.SettlementBounds second) {
        int minX = Math.max(first.minX(), second.minX());
        int minZ = Math.max(first.minZ(), second.minZ());
        int maxX = Math.min(first.maxX(), second.maxX());
        int maxZ = Math.min(first.maxZ(), second.maxZ());
        if (minX > maxX || minZ > maxZ) return false;
        long intersection = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        long firstArea = (long) (first.maxX() - first.minX() + 1) * (first.maxZ() - first.minZ() + 1);
        long secondArea = (long) (second.maxX() - second.minX() + 1) * (second.maxZ() - second.minZ() + 1);
        return intersection >= Math.min(firstArea, secondArea) * 0.65;
    }

    private static int detectionRank(DetectionSource source) {
        return switch (source) {
            case EXTERNAL -> 5;
            case STRUCTURE -> 4;
            case MANUAL -> 3;
            case ADDON -> 2;
            case POI -> 1;
        };
    }

    private static int distanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        long result = dx * dx + dz * dz;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int square(int value) {
        long result = (long) value * value;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static String requireName(String name) {
        String result = Objects.requireNonNull(name, "name").strip();
        if (result.isEmpty() || result.length() > 80) {
            throw new IllegalArgumentException("Settlement name must contain from 1 to 80 characters");
        }
        return result;
    }

    private static String slugPath(String value) {
        String slug = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "settlement" : slug;
    }

    private static String displayId(ResourceLocation id) {
        String[] words = id.getPath().split("[_/.-]+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static void addAlias(SettlementRecord record, String alias) {
        if (!alias.isBlank() && !record.aliases.contains(alias)) record.aliases.add(alias);
    }

    private static Comparator<SettlementView> settlementOrder() {
        return Comparator.comparing((SettlementView settlement) -> settlement.slug().toString())
                .thenComparing(SettlementView::id);
    }

    private <T> Registration register(Map<ResourceLocation, T> registrations, ResourceLocation owner,
                                      T value, String kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(value, kind);
        if (registrations.putIfAbsent(owner, value) != null) {
            throw new IllegalArgumentException("A " + kind + " is already registered for " + owner);
        }
        return () -> {
            requireServerThread();
            registrations.remove(owner, value);
        };
    }

    private record QueuedChunk(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                               long chunk) {
    }
}
