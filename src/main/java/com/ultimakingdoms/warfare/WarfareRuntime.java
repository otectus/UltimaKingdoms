package com.ultimakingdoms.warfare;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.event.SettlementMutationPreflightEvent;
import com.ultimakingdoms.compat.recruits.RecruitsObservation;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

/** Bounded reconciliation of explicit bindings. Native siege policy and native writes stay native. */
public final class WarfareRuntime {
    private static final Map<MinecraftServer, WarfareRuntime> RUNTIMES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final ControlSavedData data;
    private final com.ultimakingdoms.compat.recruits.RecruitsDurableClaims.Reader durableReader = new com.ultimakingdoms.compat.recruits.RecruitsDurableClaims.Reader();
    private final Map<UUID, String> availability = new HashMap<>();
    private UUID cursor;
    private WarfareRuntime(MinecraftServer server) {
        this.server = server; kingdoms = UltimaKingdomsApi.get(server); data = ControlSavedData.get(server);
    }
    public static WarfareRuntime get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Warfare requires server thread");
        return RUNTIMES.computeIfAbsent(server, WarfareRuntime::new);
    }
    public static void clear(MinecraftServer server) { RUNTIMES.remove(server); }
    public long revision() { return data.revision(); }
    public Optional<ControlState.Binding> binding(UUID settlement) { return data.binding(settlement); }
    public Optional<ControlState.Mapping> mapping(String nativeFaction) { return data.mapping(nativeFaction); }
    public List<ControlState.Binding> bindings() { return data.bindings(); }
    public boolean confirmed(UUID settlement) {
        return WarfareConfig.ENABLED.get() && data.writable() && availability.getOrDefault(settlement, "").startsWith("Native saved snapshot confirmed");
    }
    public Optional<ControlState.Binding> verifiedBinding(UUID settlement) {
        if (!server.isSameThread() || !WarfareConfig.ENABLED.get() || !data.writable()) return Optional.empty();
        var binding = binding(settlement).orElse(null); if (binding == null) return Optional.empty();
        var nativeView = RecruitsObservation.claim(server, binding.claim());
        var saved = durableReader.snapshot(server).claims().get(binding.claim());
        var site = RecruitsObservation.at(server, new net.minecraft.world.level.ChunkPos(binding.chunkX(), binding.chunkZ()));
        if (nativeView.status() != RecruitsObservation.Status.AVAILABLE || saved == null || !nativeView.ownerId().equals(binding.owner())
                || !saved.owner().equals(binding.owner()) || saved.siege() != nativeView.underSiege()
                || !site.claimId().equals(binding.claim().toString())
                || !saved.chunks().contains(net.minecraft.world.level.ChunkPos.asLong(binding.chunkX(), binding.chunkZ()))) return Optional.empty();
        return Optional.of(binding);
    }
    private void enabled() {
        if (!WarfareConfig.ENABLED.get()) throw new IllegalArgumentException("Political warfare observation is disabled.");
        if (!data.writable()) throw new IllegalArgumentException("Control records are read-only; operator recovery required.");
    }
    private void actor(ServerPlayer actor) {
        if (!server.isSameThread() || actor.getServer() != server) throw new IllegalArgumentException("Wrong server or thread.");
    }
    private static void operator(ServerPlayer actor) {
        if (!actor.hasPermissions(2)) throw new IllegalArgumentException("Only an operator can register military bindings.");
    }
    private void commit(ControlState next) {
        next.revision = Math.addExact(next.revision, 1);
        if (!data.commit(server, next)) throw new IllegalArgumentException("Control records could not be saved; action refused.");
    }
    /** Mapping labels refer to an existing native owner observed where the operator stands. */
    public String mapHere(ServerPlayer actor, net.minecraft.resources.ResourceLocation kingdom) {
        actor(actor);
        operator(actor); enabled();
        if (kingdoms.getKingdom(kingdom).filter(KingdomView::defined).isEmpty()) throw new IllegalArgumentException("Kingdom unavailable.");
        var view = RecruitsObservation.here(actor); requireClaim(view);
        var next = data.snapshot();
        var mapping = new ControlState.Mapping(view.ownerId(), kingdom.toString());
        var existing = next.mappings.get(view.ownerId());
        if (mapping.equals(existing)) return "Military mapping already registered.";
        if (existing != null) throw new IllegalArgumentException("Native faction already mapped; implicit remapping is refused.");
        if (next.mappings.size() >= ControlState.LIMIT) throw new IllegalArgumentException("Military mapping capacity reached.");
        next.mappings.put(view.ownerId(), mapping); commit(next);
        return "Registered military owner " + view.ownerId() + " for " + kingdom + ". Native membership is unchanged.";
    }
    public String bindHere(ServerPlayer actor, UUID settlementId) {
        actor(actor);
        operator(actor); enabled();
        var settlement = kingdoms.getSettlement(settlementId).orElseThrow(() -> new IllegalArgumentException("Settlement unavailable."));
        if (kingdoms.getKingdom(settlement.kingdomId()).filter(KingdomView::defined).isEmpty())
            throw new IllegalArgumentException("Settlement kingdom definition unavailable.");
        if (!settlement.dimension().equals(Level.OVERWORLD)
                || kingdoms.getSettlementAt(actor.serverLevel(), actor.blockPosition()).filter(s -> s.id().equals(settlementId)).isEmpty())
            throw new IllegalArgumentException("Stand inside the overworld settlement and its native claim to bind it.");
        var view = RecruitsObservation.here(actor); requireClaim(view);
        UUID claim = UUID.fromString(view.claimId());
        var durable = durableReader.snapshot(server);
        var saved = durable.claims().get(claim);
        if (!durable.available() || saved == null || !saved.owner().equals(view.ownerId()) || saved.siege() != view.underSiege()
                || !saved.chunks().contains(actor.chunkPosition().toLong()))
            throw new IllegalArgumentException("Native claim is awaiting a verified provider save; retry after the world saves.");
        var next = data.snapshot();
        if (!next.mappings.containsKey(view.ownerId())) throw new IllegalArgumentException("Map this native faction first.");
        var existing = next.bindings.get(settlementId);
        if (existing != null && existing.claim().equals(claim)) return "Settlement claim already bound.";
        if (existing != null || next.retired.containsKey(claim) || next.bindings.values().stream().anyMatch(b -> b.claim().equals(claim)))
            throw new IllegalArgumentException("Settlement or claim is already bound; implicit reassignment is refused.");
        if (next.bindings.size() + next.retired.size() >= ControlState.LIMIT) throw new IllegalArgumentException("Claim binding capacity reached.");
        var condition = view.underSiege() ? ControlState.Condition.UNDER_SIEGE
                : next.mappings.get(view.ownerId()).kingdom().equals(settlement.kingdomId().toString())
                ? ControlState.Condition.CONTROLLED : ControlState.Condition.OCCUPIED;
        var entry = new ControlState.Entry(1, server.overworld().getGameTime(), view.ownerId(), condition);
        next.bindings.put(settlementId, new ControlState.Binding(settlementId, claim, actor.chunkPosition().x,
                actor.chunkPosition().z, settlement.kingdomId().toString(), view.ownerId(), condition, 1, List.of(entry)));
        commit(next); availability.put(settlementId, "Observed now");
        return "Native claim bound. Recognized sovereignty and civic identity are preserved.";
    }
    private static void requireClaim(RecruitsObservation.View view) {
        if (view.status() != RecruitsObservation.Status.AVAILABLE || view.claimId().isEmpty())
            throw new IllegalArgumentException("Native claim unavailable: " + view.status());
    }
    /** Explicit operator opt-out, including provider removal recovery; native state is never modified. */
    public String retire(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); operator(actor);
        if (!data.writable()) throw new IllegalArgumentException("Control records are read-only; restore a supported backup.");
        var next = data.snapshot();
        if (next.revision != expectedRevision) throw new IllegalArgumentException("Control revision changed; inspect again before retiring.");
        var binding = next.bindings.remove(id);
        if (binding == null) throw new IllegalArgumentException("No active binding to retire.");
        next.retired.put(binding.claim(), binding.retire(server.overworld().getGameTime(), actor.getUUID()));
        commit(next); availability.remove(id);
        return "Control binding retired with its history preserved. Native control is unchanged; civic migration is now permitted.";
    }
    /** This endpoint discloses control only at the viewer's present settlement, or to operators. */
    public List<String> view(ServerPlayer viewer, UUID id) {
        actor(viewer);
        if (!SettlementKnowledge.get(server).visible(viewer, id)) return List.of("Control information unavailable.");
        if (!viewer.hasPermissions(2) && kingdoms.getSettlementAt(viewer.serverLevel(), viewer.blockPosition())
                .filter(s -> s.id().equals(id)).isEmpty()) return List.of("Visit this settlement to inspect its control history.");
        var state = data.snapshot();
        var binding = state.bindings.get(id);
        if (binding == null) binding = state.retired.values().stream().filter(b -> b.settlement().equals(id))
                .max(Comparator.comparingLong(b -> b.history().get(b.history().size() - 1).gameTime())).orElse(null);
        if (binding == null) return List.of("No explicit native claim binding.");
        var lines = new ArrayList<String>();
        if (viewer.hasPermissions(2)) lines.add("Control revision: " + state.revision + "; retire explicitly with /ultima warfare retire " + id + " " + state.revision);
        lines.add(!WarfareConfig.ENABLED.get() ? "Political warfare disabled; retained historical snapshot."
                : !data.writable() ? "Control records unavailable; read-only recovery required."
                : binding.condition() == ControlState.Condition.RETIRED ? "Retired binding; historical information only."
                : availability.getOrDefault(id, "Stale: awaiting native reconciliation after startup."));
        var political = CampaignService.get(server).control(viewer, id);
        lines.add("Recognized sovereign: " + political.map(c -> c.recognizedKingdom() + " | " + c.autonomy()).orElse(binding.sovereign()));
        lines.add("Observed military owner: " + binding.owner() + " | " + binding.condition());
        lines.add("Observation #" + binding.sequence() + ". Occupation does not change civic identity or civilian law.");
        binding.history().stream().skip(Math.max(0, binding.history().size() - 6)).forEach(e ->
                lines.add("Day " + e.gameTime() / 24000 + ": " + e.condition() + " | " + e.owner()));
        lines.add("Peace/autonomy: /ultima warfare campaigns " + id + ". Civilian contracts: /ultima-contract. Local civilian law remains in force.");
        return List.copyOf(lines);
    }
    public List<String> here(ServerPlayer viewer) {
        return kingdoms.getSettlementAt(viewer.serverLevel(), viewer.blockPosition())
                .map(s -> view(viewer, s.id())).orElse(List.of("No recognized settlement here."));
    }
    /** Public for operational checks; processes at most one configured batch, without visiting chunks. */
    public void reconcile() {
        if (!server.isSameThread()) throw new IllegalStateException("Control reconciliation requires server thread");
        if (!WarfareConfig.ENABLED.get() || !data.writable()) return;
        var next = data.snapshot();
        var ids = next.bindings.keySet().stream().sorted().toList();
        var batch = ids.stream().filter(id -> cursor == null || id.compareTo(cursor) > 0).limit(WarfareConfig.BUDGET.get()).toList();
        if (batch.isEmpty()) { cursor = null; return; }
        var durable = durableReader.snapshot(server);
        boolean changed = false;
        for (UUID id : batch) {
            var binding = next.bindings.get(id);
            var kingdom = net.minecraft.resources.ResourceLocation.tryParse(binding.sovereign());
            if (kingdom == null || kingdoms.getKingdom(kingdom).filter(KingdomView::defined).isEmpty()) {
                cursor = id; availability.put(id, "Suspended: recognized kingdom definition unavailable."); continue;
            }
            var observation = RecruitsObservation.claim(server, binding.claim());
            cursor = id;
            if (observation.status() != RecruitsObservation.Status.AVAILABLE) {
                availability.put(id, "Suspended: " + observation.status() + "; retained historical snapshot."); continue;
            }
            boolean exists = !observation.claimId().isEmpty();
            var mapping = next.mappings.get(observation.ownerId());
            if (mapping != null) {
                var mappedKingdom = net.minecraft.resources.ResourceLocation.tryParse(mapping.kingdom());
                if (mappedKingdom == null || kingdoms.getKingdom(mappedKingdom).filter(KingdomView::defined).isEmpty()) {
                    availability.put(id, "Suspended: controlling kingdom definition unavailable."); continue;
                }
            }
            if (exists) {
                var site = RecruitsObservation.at(server, new net.minecraft.world.level.ChunkPos(binding.chunkX(), binding.chunkZ()));
                if (site.status() != RecruitsObservation.Status.AVAILABLE || !site.claimId().equals(binding.claim().toString())) {
                    availability.put(id, "Suspended: registered site no longer belongs to this native claim."); continue;
                }
            }
            var saved = durable.claims().get(binding.claim());
            if (!durable.available() || exists && (saved == null || !saved.owner().equals(observation.ownerId())
                    || saved.siege() != observation.underSiege()) || !exists && saved != null) {
                availability.put(id, "Pending native save confirmation; retained historical snapshot."); continue;
            }
            if (exists && !saved.chunks().contains(net.minecraft.world.level.ChunkPos.asLong(binding.chunkX(), binding.chunkZ()))) {
                availability.put(id, "Suspended: registered site no longer belongs to this native claim."); continue;
            }
            var updated = binding.observe(exists ? observation.ownerId() : binding.owner(), observation.underSiege(), exists,
                    next.mappings, server.overworld().getGameTime());
            next.bindings.put(id, updated); changed |= updated != binding;
            availability.put(id, "Native saved snapshot confirmed at day " + server.overworld().getGameTime() / 24000 + ".");
        }
        if (changed && !data.commit(server, increment(next)))
            batch.forEach(id -> availability.put(id, "Suspended: observation save failed; showing previous history."));
    }
    private static ControlState increment(ControlState state) { state.revision = Math.addExact(state.revision, 1); return state; }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && WarfareConfig.ENABLED.get()
                && event.getServer().getTickCount() % WarfareConfig.INTERVAL.get() == 0) {
            get(event.getServer()).reconcile();
            CampaignService.get(event.getServer()).tick();
        }
    }
    @SubscribeEvent public static void preflight(SettlementMutationPreflightEvent event) {
        // Even when disabled, do not silently orphan a registered identity or change its civic meaning.
        var runtime = get(event.server());
        var state = runtime.data.snapshot();
        if (!runtime.data.writable() || state.bindings.containsKey(event.source().id())
                || event.mergeTarget().filter(s -> state.bindings.containsKey(s.id())).isPresent())
            event.reject("Registered military control requires explicit migration before settlement reassignment or merge.");
    }
}
