package com.ultimakingdoms.factions;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.factions.EffectiveStanding;
import com.ultimakingdoms.api.factions.FactionStandingMirror;
import com.ultimakingdoms.api.factions.FactionMigrationReport;
import com.ultimakingdoms.api.factions.FactionStandingRequest;
import com.ultimakingdoms.api.factions.FactionStandingResult;
import com.ultimakingdoms.api.factions.FactionStandingSnapshot;
import com.ultimakingdoms.api.factions.StandingScope;
import com.ultimakingdoms.api.factions.LocalStandingEffectResult;
import com.ultimakingdoms.api.factions.UltimaFactionsService;
import com.ultimakingdoms.api.factions.event.FactionStandingChangedEvent;
import com.ultimakingdoms.factions.config.FactionConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FactionServiceImpl implements UltimaFactionsService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int API_VERSION = 1;
    private static final int IGNORED_RECEIPT_CAPACITY = 256;
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final FactionSavedData data;
    private final List<FactionStandingMirror> mirrors = new CopyOnWriteArrayList<>();
    private LocalStandingProvider localStanding = LocalStandingProvider.UNAVAILABLE;
    private LegacyStandingProvider legacyStanding = LegacyStandingProvider.UNAVAILABLE;
    private LocalEffectProvider localEffects = LocalEffectProvider.UNAVAILABLE;
    private static final String LEGACY_MIGRATION = "mcareputation:baseline-v1";

    public FactionServiceImpl(MinecraftServer server, KingdomsService kingdoms) {
        this.server = Objects.requireNonNull(server, "server");
        this.kingdoms = Objects.requireNonNull(kingdoms, "kingdoms");
        this.data = FactionSavedData.get(server);
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public Optional<FactionStandingSnapshot> getStanding(UUID playerId, ResourceLocation kingdomId) {
        requireServerThread();
        return data.standing(Objects.requireNonNull(playerId, "playerId"),
                Objects.requireNonNull(kingdomId, "kingdomId")).map(FactionStandingRecord::snapshot);
    }

    @Override
    public OptionalInt getLocalStanding(UUID playerId, McaCommunityRef community) {
        requireServerThread();
        return localStanding.score(Objects.requireNonNull(playerId, "playerId"),
                Objects.requireNonNull(community, "community"));
    }

    @Override
    public FactionStandingResult apply(FactionStandingRequest request) {
        return applyInternal(request, null, null, -1L, false);
    }

    @Override
    public boolean flushStandingChanges() {
        flushDurable();
        return !data.isDirty();
    }

    void pruneReceipts(long now, long retention) {
        requireServerThread();
        data.pruneReceipts(now, retention);
    }

    public FactionStandingResult applyFromOutbox(FactionStandingRequest request, String consumer,
                                                  UUID epoch, long sequence) {
        return applyInternal(request, Objects.requireNonNull(consumer,"consumer"),
                Objects.requireNonNull(epoch,"epoch"), sequence, false);
    }

    private FactionStandingResult applyInternal(FactionStandingRequest request, String consumer,
                                                UUID epoch, long sequence, boolean heldCustody) {
        requireServerThread();
        Objects.requireNonNull(request, "request");
        FactionStandingRecord current = data.standing(request.playerId(), request.kingdomId())
                .orElseGet(() -> new FactionStandingRecord(request.playerId(), request.kingdomId()));
        if (!data.writable()) {
            return new FactionStandingResult(FactionStandingResult.Status.READ_ONLY,
                    request.delta(), 0, current.snapshot());
        }
        Optional<SyncReceipt> replay = data.receipt(request.correlationId());
        if (replay.isPresent()) {
            if (consumer != null) data.advanceCursor(consumer, epoch, sequence);
            FactionStandingSnapshot standing = data.standing(request.playerId(), request.kingdomId())
                    .map(FactionStandingRecord::snapshot).orElse(current.snapshot());
            return new FactionStandingResult(FactionStandingResult.Status.REPLAYED,
                    request.delta(), 0, standing);
        }
        if (!heldCustody && request.sourceRevision() > 0
                && request.sourceRevision() <= data.checkpoint(request.source())) {
            if (consumer != null) data.advanceCursor(consumer, epoch, sequence);
            return new FactionStandingResult(FactionStandingResult.Status.STALE_SOURCE_REVISION,
                    request.delta(), 0, current.snapshot());
        }

        FactionStandingRecord record = data.getOrCreate(request.playerId(), request.kingdomId());
        int applied = request.delta() == 0 ? 0 : record.apply(request.delta(), data.nextRevision());
        data.record(SyncReceipt.from(request, record.revision, server.overworld().getGameTime()));
        if (consumer != null) data.advanceCursor(consumer, epoch, sequence);
        FactionStandingResult result = new FactionStandingResult(FactionStandingResult.Status.APPLIED,
                request.delta(), applied, record.snapshot());
        if (applied != 0) publish(result, request);
        return result;
    }

    public boolean takePendingCustody(String consumer, UUID epoch, long sequence, UUID eventId,
                                      String status, java.util.Map<String,String> payload,
                                      boolean advanceCursor) {
        requireServerThread();
        boolean accepted=data.takeCustody(new PendingReputationChange(epoch,sequence,eventId,status,payload),
                FactionConfig.MISSING_MAPPING_CAPACITY.get());
        if(accepted&&advanceCursor)data.advanceCursor(consumer,epoch,sequence);
        return accepted;
    }

    public boolean consumeIgnored(String consumer, UUID epoch, long sequence, UUID eventId,
                                  java.util.Map<String,String> payload, boolean advanceCursor) {
        requireServerThread();
        String reason = payload == null ? "unspecified" : payload.getOrDefault("reason", "unspecified");
        return data.consumeIgnored(new FactionSavedData.IgnoredSourceReceipt(epoch, sequence, eventId,
                        reason, server.overworld().getGameTime()), IGNORED_RECEIPT_CAPACITY,
                Objects.requireNonNull(consumer, "consumer"), advanceCursor);
    }

    public void recordShadow(UUID eventId, UUID player, ResourceLocation kingdom,
                             int projectedDelta, long sourceSequence, String outcome) {
        requireServerThread();
        data.recordShadow(new FactionSavedData.ShadowProjection(eventId,player,kingdom,
                projectedDelta,sourceSequence,outcome));
    }

    public Optional<FactionStandingResult> resolvePending(UUID eventId, ResourceLocation kingdomId) {
        requireServerThread();
        Optional<PendingReputationChange> found = data.pending(Objects.requireNonNull(eventId, "eventId"));
        if (found.isEmpty() || !"UNMAPPED".equals(found.get().status())) return Optional.empty();
        Map<String,String> payload = found.get().payload();
        try {
            UUID player = UUID.fromString(payload.get("player"));
            int delta = Integer.parseInt(payload.get("projectedDelta"));
            FactionStandingRequest request = new FactionStandingRequest(player,
                    Objects.requireNonNull(kingdomId, "kingdomId"), delta,
                    new ResourceLocation("ultima_kingdoms", "mca_reputation_sync"),
                    com.ultimakingdoms.api.factions.FactionChangeCause.LOCAL_REPUTATION,
                    eventId, found.get().sequence(), Optional.empty(),
                    Optional.of("Operator-resolved historical MCA mapping"), false);
            FactionStandingResult result = applyInternal(request, null, null, -1L, true);
            if (result.applied() || result.status() == FactionStandingResult.Status.REPLAYED) {
                data.resolvePending(eventId);
            }
            return Optional.of(result);
        } catch (RuntimeException malformed) {
            LOGGER.error("Pending reputation change {} has malformed frozen payload", eventId, malformed);
            return Optional.empty();
        }
    }

    public record PendingMappingView(UUID id,long sequence,Map<String,String> payload) { public PendingMappingView { payload=Map.copyOf(payload); } }
    public java.util.List<PendingMappingView> pendingMappings(net.minecraft.server.level.ServerPlayer viewer) {
        requireServerThread();if(viewer.getServer()!=server||viewer.hasDisconnected()||!viewer.hasPermissions(2))throw new IllegalArgumentException("Operator access required.");
        return data.pending().stream().filter(v->"UNMAPPED".equals(v.status())).sorted(java.util.Comparator.comparingLong(PendingReputationChange::sequence)).map(v->new PendingMappingView(v.eventId(),v.sequence(),v.payload())).toList();
    }

    public String diagnosticSummary() {
        requireServerThread();
        long unmapped = data.pending().stream().filter(value -> "UNMAPPED".equals(value.status())).count();
        long failed = data.pending().stream().filter(value -> "FAILED".equals(value.status())).count();
        return "pending=" + data.pending().size() + " unmapped=" + unmapped
                + " failed=" + failed + " ignored_receipts=" + data.ignoredReceiptCount()
                + " ignored_consumed=" + data.ignoredConsumed() + " shadow=" + data.shadow().size();
    }

    public Optional<SourceCursorState> durableSourceCursor(String consumer) {
        requireServerThread();
        FactionSavedData.SourceCursor cursor=data.durableCursor(consumer);
        return cursor==null?Optional.empty():Optional.of(new SourceCursorState(cursor.epoch(),cursor.through()));
    }

    public boolean initializeSourceCursor(String consumer, UUID epoch, long through) {
        requireServerThread();
        if (!data.writable()) return false;
        if (data.cursor(consumer) == null) data.advanceCursor(consumer, epoch, through);
        FactionSavedData.SourceCursor current = data.cursor(consumer);
        FactionSavedData.SourceCursor durable = data.durableCursor(consumer);
        if (!Objects.equals(current, durable)) {
            flushDurable();
            durable = data.durableCursor(consumer);
        }
        return durable != null;
    }

    public void onDurableSave(Runnable listener) {
        data.onDurableSave(listener);
    }

    public void flushDurable() {
        requireServerThread();
        if (data.isDirty()) {
            data.save(server.getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(FactionSavedData.DATA_NAME + ".dat").toFile());
        }
    }

    public record SourceCursorState(UUID epoch,long through) {}

    @Override
    public EffectiveStanding effectiveStanding(UUID playerId, ResourceLocation kingdomId, OptionalInt localScore) {
        requireServerThread();
        Objects.requireNonNull(localScore, "localScore");
        int faction = getStanding(playerId, kingdomId).map(FactionStandingSnapshot::score).orElse(0);
        int maximum = FactionConfig.FACTION_OVERLAY_MAX.get();
        int modifier = Math.max(-maximum, Math.min(maximum, faction));
        int local = localScore.orElse(0);
        int effective = localScore.isPresent()
                ? (int) Math.max(-1_000L, Math.min(1_000L, (long) local + modifier)) : 0;
        return new EffectiveStanding(localScore.isPresent(), local, faction, modifier, effective);
    }

    @Override
    public boolean matches(StandingScope scope, UUID playerId, ResourceLocation kingdomId,
                           OptionalInt localScore, int minimum, int maximum) {
        requireServerThread();
        if (minimum > maximum) throw new IllegalArgumentException("minimum exceeds maximum");
        EffectiveStanding standing = effectiveStanding(playerId, kingdomId, localScore);
        boolean local = standing.localAvailable() && within(standing.localScore(), minimum, maximum);
        boolean faction = within(standing.factionScore(), minimum, maximum);
        return switch (Objects.requireNonNull(scope, "scope")) {
            case LOCAL -> local;
            case FACTION -> faction;
            case EFFECTIVE -> standing.localAvailable() && within(standing.effectiveScore(), minimum, maximum);
            case EITHER -> local || faction;
            case BOTH -> local && faction;
        };
    }

    @Override
    public FactionMigrationReport previewLegacyMigration() {
        requireServerThread();
        return migrationReport();
    }

    @Override
    public FactionMigrationReport importLegacyMigration() {
        requireServerThread();
        FactionMigrationReport report = migrationReport();
        if (!report.importable()) return report;
        for (FactionMigrationReport.Entry entry : report.entries()) {
            FactionStandingRecord record = data.getOrCreate(entry.playerId(), entry.kingdomId());
            record.importBaseline(entry.baseline(), data.nextRevision());
        }
        data.markMigration(LEGACY_MIGRATION);
        return migrationReport();
    }

    @Override
    public LocalStandingEffectResult deliverLocalEffect(UUID playerId, McaCommunityRef community, int delta,
                                                         UUID correlationId, long sourceRevision,
                                                         String description) {
        requireServerThread();
        if (FactionConfig.SYNC_MODE.get() != FactionConfig.SyncMode.BIDIRECTIONAL_SEMANTIC) {
            return LocalStandingEffectResult.MODE_DISABLED;
        }
        return localEffects.deliver(Objects.requireNonNull(playerId, "playerId"),
                Objects.requireNonNull(community, "community"), delta,
                Objects.requireNonNull(correlationId, "correlationId"), sourceRevision, description);
    }

    @Override
    public long revision() {
        requireServerThread();
        return data.revision();
    }

    @Override
    public Registration registerMirror(FactionStandingMirror mirror) {
        requireServerThread();
        mirrors.add(Objects.requireNonNull(mirror, "mirror"));
        return () -> mirrors.remove(mirror);
    }

    public KingdomsService kingdoms() {
        return kingdoms;
    }

    FactionSavedData data() {
        return data;
    }

    public void installLocalStandingProvider(LocalStandingProvider provider) {
        localStanding = provider == null ? LocalStandingProvider.UNAVAILABLE : provider;
    }

    public void installLegacyStandingProvider(LegacyStandingProvider provider) {
        legacyStanding = provider == null ? LegacyStandingProvider.UNAVAILABLE : provider;
    }

    public void installLocalEffectProvider(LocalEffectProvider provider) {
        localEffects = provider == null ? LocalEffectProvider.UNAVAILABLE : provider;
    }

    private FactionMigrationReport migrationReport() {
        Optional<List<LegacyStandingProvider.LegacyStanding>> source = legacyStanding.standings();
        if (source.isEmpty()) return new FactionMigrationReport(false, data.hasMigration(LEGACY_MIGRATION),
                0, List.of(), List.of());
        Map<MigrationKey, List<LegacyStandingProvider.LegacyStanding>> grouped = new LinkedHashMap<>();
        List<FactionMigrationReport.Unmapped> unmapped = new ArrayList<>();
        for (LegacyStandingProvider.LegacyStanding standing : source.get()) {
            var settlement = kingdoms.getSettlementForMcaVillage(
                    standing.community().dimension(), standing.community().villageId());
            if (settlement.isEmpty()) {
                unmapped.add(new FactionMigrationReport.Unmapped(standing.playerId(), standing.community(),
                        standing.score(), "no_settlement_mapping"));
                continue;
            }
            ResourceLocation kingdom = settlement.get().kingdomId();
            if (kingdoms.getKingdom(kingdom).filter(com.ultimakingdoms.api.KingdomView::defined).isEmpty()) {
                unmapped.add(new FactionMigrationReport.Unmapped(standing.playerId(), standing.community(),
                        standing.score(), "missing_kingdom_definition"));
                continue;
            }
            grouped.computeIfAbsent(new MigrationKey(standing.playerId(), kingdom), ignored -> new ArrayList<>())
                    .add(standing);
        }
        List<FactionMigrationReport.Entry> entries = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(value -> {
                    List<LegacyStandingProvider.LegacyStanding> inputs = value.getValue().stream()
                            .sorted(Comparator.comparing(input -> input.community().toString())).toList();
                    Optional<FactionStandingRecord> existing = data.standing(value.getKey().player, value.getKey().kingdom);
                    return new FactionMigrationReport.Entry(value.getKey().player, value.getKey().kingdom,
                            inputs.stream().map(LegacyStandingProvider.LegacyStanding::community).toList(),
                            inputs.stream().map(LegacyStandingProvider.LegacyStanding::score).toList(),
                            aggregate(inputs.stream().map(LegacyStandingProvider.LegacyStanding::score).toList()),
                            existing.isPresent(), existing.map(record -> record.score).orElse(0));
                }).toList();
        unmapped.sort(Comparator.comparing((FactionMigrationReport.Unmapped value) -> value.playerId().toString())
                .thenComparing(value -> value.community().toString()));
        return new FactionMigrationReport(true, data.hasMigration(LEGACY_MIGRATION), source.get().size(),
                entries, unmapped);
    }

    static int aggregate(List<Integer> scores) {
        return aggregate(scores, FactionConfig.LEGACY_AGGREGATION.get());
    }

    static int aggregate(List<Integer> scores, FactionConfig.LegacyAggregation aggregation) {
        if (scores.isEmpty()) return 0;
        List<Integer> sorted = scores.stream().sorted().toList();
        return switch (aggregation) {
            case MEAN -> Math.round((float) scores.stream().mapToInt(Integer::intValue).average().orElse(0D));
            case MEDIAN -> sorted.size() % 2 == 1 ? sorted.get(sorted.size() / 2)
                    : Math.round(((long) sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2F);
            case MAX -> sorted.get(sorted.size() - 1);
            case MIN -> sorted.get(0);
            case SUM -> (int) Math.max(-1_000L, Math.min(1_000L,
                    scores.stream().mapToLong(Integer::longValue).sum()));
        };
    }

    private record MigrationKey(UUID player, ResourceLocation kingdom) implements Comparable<MigrationKey> {
        @Override public int compareTo(MigrationKey other) {
            int playerOrder = player.toString().compareTo(other.player.toString());
            return playerOrder != 0 ? playerOrder : kingdom.toString().compareTo(other.kingdom.toString());
        }
    }

    private void publish(FactionStandingResult result, FactionStandingRequest request) {
        for (FactionStandingMirror mirror : mirrors) {
            try { mirror.standingChanged(result, request); }
            catch (RuntimeException exception) { LOGGER.error("Faction standing mirror failed", exception); }
        }
        try { MinecraftForge.EVENT_BUS.post(new FactionStandingChangedEvent(result, request)); }
        catch (RuntimeException exception) { LOGGER.error("Faction standing event listener failed", exception); }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Ultima Factions API must be called on the server thread");
    }

    private static boolean within(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
