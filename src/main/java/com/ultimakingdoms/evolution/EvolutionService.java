package com.ultimakingdoms.evolution;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.contracts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ultimakingdoms.evolution.EvolutionState.*;

/** Bounded, opt-in opportunities. Provider results are consumed, never invented by the director. */
public final class EvolutionService {
    public record SettingsView(long revision, boolean enabled, boolean drama, boolean subscribed,
                               Set<UUID> eligibleRegions, Set<UUID> activeRegions, boolean writable) {}
    public record ScenarioView(UUID id, String title, Trigger trigger, UUID settlement, String kingdom,
                               Phase phase, long revision, long deadline, List<Outcome> outcomes,
                               int requiredContributions, Map<Outcome,Long> contributions,
                               Optional<Outcome> ownChoice, String cause, String result, boolean privateAudience) {}
    private final MinecraftServer server;
    private final EvolutionSavedData data;
    public EvolutionService(MinecraftServer server) { this.server = server; this.data = EvolutionSavedData.get(server); }
    public long revision() { if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread"); return data.snapshot().revision; }
    public SettingsView settings(ServerPlayer player) { actor(player); var s=data.snapshot(); return new SettingsView(s.revision,s.enabled,s.drama,
            s.subscriptions.contains(player.getUUID()),Set.copyOf(s.eligible),Set.copyOf(s.activeRegions),data.writable()); }
    public List<ScenarioView> scenarios(ServerPlayer player) { actor(player); var s=data.snapshot(); return s.scenarios.values().stream()
            .filter(e->visible(player,e)).sorted(Comparator.comparingLong(Scenario::created).reversed().thenComparing(Scenario::id)).map(e->view(player,e)).toList(); }
    public Optional<ScenarioView> scenario(ServerPlayer player,UUID id) { actor(player); var s=data.snapshot(); var e=s.scenarios.get(id);
        return e!=null&&visible(player,e)?Optional.of(view(player,e)):Optional.empty(); }
    private ScenarioView view(ServerPlayer player,Scenario e) { var counts=new EnumMap<Outcome,Long>(Outcome.class);
        for(var outcome:e.terms().outcomes()) counts.put(outcome,e.contributions().values().stream().filter(c->c.outcome()==outcome).count());
        var own=Optional.ofNullable(e.contributions().get(player.getUUID())).map(Contribution::outcome);
        return new ScenarioView(e.id(),e.terms().title(),e.terms().trigger(),e.settlement(),e.kingdom(),e.phase(),e.revision(),e.deadline(),
                e.terms().outcomes(),e.terms().contributions(),Map.copyOf(counts),own,e.cause().detail(),e.result(),e.audience()!=null); }
    private long now(EvolutionState s) { return Math.max(s.clock, server.overworld().getGameTime()); }
    private void actor(ServerPlayer p) {
        if (!server.isSameThread() || p.getServer() != server || p.hasDisconnected()) throw new IllegalArgumentException("Connected server player required");
    }
    private void commit(EvolutionState s) {
        s.clock = now(s); s.revision = Math.addExact(s.revision, 1);
        if (!data.commit(server, s)) throw new IllegalArgumentException("Scenario save unavailable; action refused");
    }
    public String configure(ServerPlayer player, boolean enabled, boolean drama) {
        actor(player); if (!player.hasPermissions(2)) throw new IllegalArgumentException("Operator permission required");
        var s = data.snapshot(); if (s.enabled != enabled) s.activeRegions.clear(); s.enabled = enabled; s.drama = drama; commit(s);
        return "World evolution " + (enabled ? "enabled" : "paused") + "; dramatic scenarios " + (drama ? "enabled" : "disabled") + ".";
    }
    public String region(ServerPlayer player, UUID settlement, boolean enabled) {
        actor(player); if (!player.hasPermissions(2)) throw new IllegalArgumentException("Operator permission required");
        if (UltimaKingdomsApi.get(server).getSettlement(settlement).isEmpty()) throw new IllegalArgumentException("Settlement unavailable");
        var s = data.snapshot(); if (enabled) s.eligible.add(settlement); else { s.eligible.remove(settlement); s.activeRegions.remove(settlement); } commit(s);
        return "Region " + (enabled ? "eligible" : "paused") + "; evaluation requires an online visitor.";
    }
    public String subscribe(ServerPlayer player, boolean enabled) {
        actor(player); var s = data.snapshot();
        if (enabled) { s.subscriptions.add(player.getUUID()); s.digest.putIfAbsent(player.getUUID(), now(s)); }
        else s.subscriptions.remove(player.getUUID());
        commit(s); return enabled ? "Personal political digest enabled." : "Personal political digest disabled.";
    }
    public String familyIntroduction(ServerPlayer player) {
        actor(player); var s = data.snapshot();
        var marriage = com.ultimakingdoms.compat.mca.McaFamilyEvidence.marriage(server, player.getUUID())
                .orElseThrow(() -> new IllegalArgumentException("A reciprocal native marriage record is required; unavailable family records create no assumptions"));
        net.minecraft.world.entity.Entity spouse = null;
        for (var level : server.getAllLevels()) { spouse = level.getEntity(marriage.second()); if (spouse != null) break; }
        if (spouse == null) throw new IllegalArgumentException("Visit while your spouse is loaded to verify their civic introduction; no chunk was loaded");
        var kingdoms = UltimaKingdomsApi.get(server);
        var own = kingdoms.getSettlementAt(player.serverLevel(), player.blockPosition()).orElseThrow(() -> new IllegalArgumentException("Visit an eligible settlement"));
        var other = kingdoms.getResidence(spouse).orElseThrow(() -> new IllegalArgumentException("Spouse has no verified civic residence"));
        String counterpart = other.kingdomId().toString();
        if (own.kingdomId().equals(other.kingdomId()) || !SettlementKnowledge.get(server).visible(player, other.id())
                || UltimaPoliticsApi.get(server).government(counterpart).isEmpty()) throw new IllegalArgumentException("A known counterpart government is required");
        if (!s.enabled || !s.eligible.contains(own.id())) throw new IllegalArgumentException("Enable evolution for this world and region first");
        String templateId = "ultima_kingdoms:family_introduction";
        var terms = EvolutionDefinitions.INSTANCE.snapshot().get(templateId); if (terms == null) throw new IllegalArgumentException("Family scenario definition unavailable");
        String key = player.getUUID() + ":family"; long now = now(s);
        if (s.cooldowns.getOrDefault(key, 0L) > now || activeCount(s) >= EvolutionConfig.CONCURRENT.get())
            throw new IllegalArgumentException("Family opportunity cooldown or world event budget reached");
        UUID id = UUID.randomUUID();
        var e = new Scenario(id, templateId, terms, own.id(), null, own.kingdomId().toString(),
                new Evidence("mca:family", marriage.second() + "|" + counterpart, "RECIPROCAL_MARRIAGE", now,
                        "Your native family relationship offers a private diplomatic introduction"), player.getUUID(), now, now + terms.duration(), 1, Phase.OPEN, Map.of(), null, "");
        s.scenarios.put(id, e); s.cooldowns.put(key, now + terms.cooldown()); commit(s);
        return "Private family introduction " + id + ". Preview the consequences before contributing; marriage grants no political ownership.";
    }
    private String familyCounterpart(ServerPlayer player, Scenario e) {
        String[] parts = e.cause().receipt().split("\\|", 2);
        if (!Objects.equals(e.audience(), player.getUUID()) || parts.length != 2
                || com.ultimakingdoms.compat.mca.McaFamilyEvidence.marriage(server, player.getUUID()).filter(m -> m.second().toString().equals(parts[0])).isEmpty())
            throw new IllegalArgumentException("Native family relationship changed or is unavailable; introduction paused");
        return parts[1];
    }
    private boolean visible(ServerPlayer player, Scenario e) {
        return (e.audience() == null || e.audience().equals(player.getUUID()))
                && SettlementKnowledge.get(server).visible(player, e.settlement());
    }
    private Scenario require(ServerPlayer player, EvolutionState s, UUID id) {
        var event = s.scenarios.get(id);
        if (event == null || !visible(player, event)) throw new IllegalArgumentException("Scenario unavailable");
        return event;
    }
    private void available(EvolutionState s, Scenario e) {
        if (!data.writable() || !s.enabled || !s.eligible.contains(e.settlement()) || e.terms().dramatic() && !s.drama)
            throw new IllegalArgumentException("This scenario is paused by world policy");
        if (!EvolutionDefinitions.INSTANCE.snapshot().containsKey(e.templateId())) throw new IllegalArgumentException("Scenario definition unavailable; saved terms retained");
        if (UltimaKingdomsApi.get(server).getSettlement(e.settlement()).filter(v -> v.kingdomId().toString().equals(e.kingdom())).isEmpty())
            throw new IllegalArgumentException("Settlement identity changed; historical scenario retained");
        if (e.institution() != null && UltimaPoliticsApi.get(server).institution(e.institution()).filter(InstitutionView::operational).isEmpty())
            throw new IllegalArgumentException("Institution unavailable; no unloaded building is presumed destroyed");
    }
    public List<String> page(ServerPlayer player, int offset) {
        actor(player); if (offset < 0 || offset > CAPACITY || offset % 10 != 0) throw new IllegalArgumentException("Invalid page");
        var s = data.snapshot(); var lines = new ArrayList<String>();
        lines.add("World evolution: " + (!data.writable() ? "read-only recovery required" : s.enabled ? "enabled" : "paused") + "; revision " + s.revision);
        s.scenarios.values().stream().filter(e -> visible(player, e)).sorted(Comparator.comparingLong(Scenario::created).reversed().thenComparing(Scenario::id))
                .skip(offset).limit(10).forEach(e -> lines.add(e.id() + " | " + e.terms().title() + " | " + e.phase() + " | revision " + e.revision()));
        return List.copyOf(lines);
    }
    public List<String> inspect(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        var lines = new ArrayList<String>();
        lines.add(e.terms().title() + " | " + e.phase() + " | revision " + e.revision());
        lines.add("Cause: " + e.cause().detail() + " (" + e.cause().provider() + ", game tick " + e.cause().at() + ")");
        lines.add("Choices: " + e.terms().outcomes() + "; required contributions " + e.terms().contributions() + "; deadline " + e.deadline());
        for (var choice : e.terms().outcomes()) lines.add(choice + ": " + e.contributions().values().stream().filter(c -> c.outcome() == choice).count() + " verified contributions");
        var own = e.contributions().get(player.getUUID()); if (own != null) lines.add("Your choice: " + own.outcome());
        try { available(s, e); } catch (IllegalArgumentException unavailable) { lines.add(unavailable.getMessage()); }
        if (!e.result().isBlank()) lines.add(e.result());
        lines.add("Contributing grants no automatic treaty, succession, payment or troop transfer. Decline preserves peaceful play.");
        return List.copyOf(lines);
    }
    /** Only receipt-backed service or current public authority qualifies; client-supplied proof is never trusted. */
    public String contribute(ServerPlayer player, UUID id, long revision, Outcome outcome) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id); available(s, e);
        if (!e.terms().outcomes().contains(outcome) || outcome == Outcome.DECLINE) throw new IllegalArgumentException("Outcome unavailable");
        Evidence proof;
        if (outcome == Outcome.AID || outcome == Outcome.MEDIATE || outcome == Outcome.NEGOTIATE) {
            var kind = EvolutionContracts.kind(outcome);
            var service = CivilianContractService.scopedProof(server, player.getUUID(), e.settlement(), kind, e.id())
                    .filter(p -> p.completedAt() >= e.created()).orElseThrow(() -> new IllegalArgumentException("Complete the matching native civilian contract after this opportunity began"));
            proof = new Evidence("mcaquests", service.providerEpoch() + ":" + service.receipt(), kind.name(), service.completedAt(), "Verified " + kind.name().toLowerCase(Locale.ROOT) + " service");
        } else if (outcome == Outcome.INTRODUCE && e.cause().provider().equals("mca:family")) {
            familyCounterpart(player, e);
            proof = new Evidence("mca:family", e.id() + ":" + player.getUUID(), "INTRODUCTION", now(s), "Reciprocal native family relationship revalidated");
        } else {
            var politics = UltimaPoliticsApi.get(server);
            var government = politics.government(e.kingdom()).orElseThrow(() -> new IllegalArgumentException("Government unavailable"));
            if (outcome == Outcome.SUCCEED) {
                if (government.state() != Politics.State.INTERREGNUM || government.successor() == null
                        || government.successor().kind() != Politics.Kind.PLAYER || !government.successor().id().equals(player.getUUID()))
                    throw new IllegalArgumentException("Only the named player successor can accept this opportunity");
            } else if (!politics.authorized(player, e.kingdom(), Politics.Permission.PROPOSE))
                throw new IllegalArgumentException("Current diplomatic mandate required");
            proof = new Evidence("ultima_kingdoms:government", e.id() + ":" + player.getUUID() + ":" + government.revision(),
                    outcome.name(), now(s), "Current government authority verified");
        }
        if (s.consumed.contains(proof.key())) throw new IllegalArgumentException("This service receipt already supports another choice");
        s.scenarios.put(id, e.contribute(new Contribution(player.getUUID(), outcome, proof), revision, now(s)));
        s.consumed.add(proof.key()); commit(s); return "Verified contribution recorded. No shared outcome has been committed yet.";
    }
    public String withdraw(ServerPlayer player, UUID id, long revision) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        s.scenarios.put(id, e.withdraw(player.getUUID(), revision, now(s))); commit(s);
        return "Choice withdrawn; its service receipt remains recorded to prevent reuse.";
    }
    public String resolve(ServerPlayer player, UUID id, long revision, Outcome outcome, String counterpart) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id); available(s, e);
        var politics = UltimaPoliticsApi.get(server);
        if (outcome == Outcome.INTRODUCE && e.cause().provider().equals("mca:family")) {
            String expected = familyCounterpart(player, e);
            if (!counterpart.equals(expected)) throw new IllegalArgumentException("Introduction counterpart is fixed by the verified family context: " + expected);
        }
        if (outcome != Outcome.SUCCEED && !Objects.equals(e.audience(), player.getUUID())
                && !politics.authorized(player, e.kingdom(), Politics.Permission.REVIEW))
            throw new IllegalArgumentException("Current government review authority required to resolve a shared opportunity");
        if (outcome == Outcome.SUCCEED && (!e.contributions().containsKey(player.getUUID())
                || e.contributions().get(player.getUUID()).outcome() != outcome)) throw new IllegalArgumentException("Named successor contribution required");
        if (outcome == Outcome.INTRODUCE && (politics.government(counterpart).isEmpty() || counterpart.equals(e.kingdom())))
            throw new IllegalArgumentException("Choose another constituted government");
        var intent = new Intent(player.getUUID(), outcome, UUID.randomUUID(), politics.revision(), counterpart);
        s.scenarios.put(id, e.reserve(intent, revision, now(s))); commit(s);
        return retry(player, id);
    }
    /** The same actor/request/frozen revision survives the provider/local-commit crash window. */
    public String retry(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = s.scenarios.get(id);
        if (e == null || e.phase() != Phase.RESOLVING || !e.intent().actor().equals(player.getUUID())) throw new IllegalArgumentException("Only the original actor may resume the pending resolution");
        var intent = e.intent(); String result;
        var receipt = UltimaPoliticsApi.get(server).receipt(player, intent.request());
        if (receipt.filter(Politics.Result::success).isPresent()) {
            result = "Government receipt " + intent.request() + "; record " + receipt.get().recordId();
            s.scenarios.put(id, e.finish(result)); commit(s); return result;
        }
        require(player, s, id); available(s, e);
        if (intent.outcome() == Outcome.INTRODUCE || intent.outcome() == Outcome.SUCCEED) {
            if (e.cause().provider().equals("mca:family") && UltimaPoliticsApi.get(server).receipt(player, intent.request()).isEmpty()) familyCounterpart(player, e);
            var action = intent.outcome() == Outcome.SUCCEED ? Politics.Action.SUCCEED : Politics.Action.PETITION;
            var request = new Politics.Request(intent.request(), intent.politicalRevision(), action, e.kingdom(),
                    action == Politics.Action.PETITION ? "ultima_kingdoms:introduction_petition" : "", e.settlement().toString(),
                    null, intent.counterpart(), "Scenario " + e.id() + ": " + e.terms().title(), "", 0, 0, 0);
            var response = UltimaPoliticsApi.get(server).execute(player, request);
            if (!response.success()) return "Resolution remains pending: " + response.message();
            result = "Government receipt " + intent.request() + "; record " + response.recordId();
        } else result = intent.outcome() == Outcome.DECLINE ? "Opportunity declined peacefully" : "Verified " + intent.outcome().name().toLowerCase(Locale.ROOT) + " service acknowledged; further political terms require consent";
        s.scenarios.put(id, e.finish(result)); commit(s); return result;
    }
    public String releaseRejected(ServerPlayer player, UUID id) {
        actor(player); var s = data.snapshot(); var e = require(player, s, id);
        if (e.phase() != Phase.RESOLVING || !e.intent().actor().equals(player.getUUID())) throw new IllegalArgumentException("Original pending actor required");
        if (UltimaPoliticsApi.get(server).government(e.kingdom()).isEmpty()) throw new IllegalArgumentException("Owner state unavailable; retain the pending request for recovery");
        if (UltimaPoliticsApi.get(server).receipt(player, e.intent().request()).isPresent()) throw new IllegalArgumentException("Owner already committed; retry acknowledgment instead");
        s.scenarios.put(id, e.reopen()); commit(s); return "Uncommitted outcome released; refresh before choosing again.";
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("Evolution requires server thread");
        if (!data.evaluationDue(server.overworld().getGameTime())) return;
        var s = data.snapshot();
        long now = now(s); s.nextEvaluation = Math.addExact(now, EvolutionConfig.INTERVAL.get());
        var players = server.getPlayerList().getPlayers().stream().sorted(Comparator.comparing(ServerPlayer::getUUID)).toList();
        var active = new LinkedHashMap<UUID, ServerPlayer>();
        int count = Math.min(players.size(), EvolutionConfig.BUDGET.get());
        for (int i = 0; i < count; i++) {
            var player = players.get(Math.floorMod(s.cursor++, players.size()));
            UltimaKingdomsApi.get(server).getSettlementAt(player.serverLevel(), player.blockPosition())
                    .filter(place -> s.eligible.contains(place.id())).ifPresent(place -> active.putIfAbsent(place.id(), player));
        }
        if (s.cursor > 1_000_000) s.cursor = 0;
        // Freeze the deadline across inactive sampling windows; returning to a region does not apply offline catch-up.
        s.scenarios.replaceAll((id, e) -> {
            if (!active.containsKey(e.settlement()) || !s.activeRegions.contains(e.settlement()))
                return e.pause(Math.max(0, now - Math.max(s.lastEvaluation, e.created())));
            return e.expire(now);
        });
        s.activeRegions = new LinkedHashSet<>(active.keySet()); s.lastEvaluation = now;
        for (var entry : active.entrySet()) generate(s, entry.getValue(), entry.getKey(), now);
        var deliveries = new ArrayList<ServerPlayer>();
        for (var player : players.stream().limit(EvolutionConfig.BUDGET.get()).toList()) {
            if (!s.subscriptions.contains(player.getUUID()) || now - s.digest.getOrDefault(player.getUUID(), now) < EvolutionConfig.DIGEST_INTERVAL.get()) continue;
            s.digest.put(player.getUUID(), now); deliveries.add(player);
        }
        commit(s); // Save cursors before notification: a restart cannot repeat a digest.
        for (var player : deliveries) {
            long open = s.scenarios.values().stream().filter(e -> e.pending() && visible(player, e)).count();
            if (open > 0) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(open + " known political opportunities. /ultima-evolution list"));
        }
    }
    private void generate(EvolutionState s, ServerPlayer viewer, UUID settlement, long now) {
        if (s.scenarios.size() >= CAPACITY || activeCount(s) >= EvolutionConfig.CONCURRENT.get()) return;
        var place = UltimaKingdomsApi.get(server).getSettlement(settlement).orElse(null); if (place == null) return;
        var politics = UltimaPoliticsApi.get(server); var government = politics.government(place.kingdomId().toString()).orElse(null);
        if (government == null) return;
        var institution = politics.knownInstitutions(viewer, 0, 32).stream().filter(v -> v.settlement().equals(settlement) && v.operational()).findFirst().orElse(null);
        for (var item : EvolutionDefinitions.INSTANCE.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            var terms = item.getValue(); if (terms.dramatic() && !s.drama) continue;
            String key = settlement + ":" + item.getKey();
            if (s.cooldowns.getOrDefault(key, 0L) > now || s.scenarios.values().stream().anyMatch(e -> e.pending() && e.settlement().equals(settlement) && e.templateId().equals(item.getKey()))) continue;
            Evidence cause = null;
            if (terms.trigger() == Trigger.INSTITUTION && institution != null && government.state() == Politics.State.ACTIVE)
                cause = new Evidence("ultima_kingdoms:institution", institution.id() + ":" + institution.revision(), "OPERATIONAL_INSTITUTION", now, "A recognized local institution is open to diplomatic introductions");
            if (terms.trigger() == Trigger.INTERREGNUM && government.state() == Politics.State.INTERREGNUM && government.successor() != null)
                cause = new Evidence("ultima_kingdoms:government", government.kingdom() + ":" + government.revision(), "NAMED_SUCCESSION", now, "Leadership is vacant with a legally named successor");
            if (terms.trigger() == Trigger.OCCUPATION && institution != null) {
                var control = WarfareApi.get(server).flatMap(p -> p.control(viewer, settlement)).orElse(null);
                boolean occupied = com.ultimakingdoms.warfare.WarfareRuntime.get(server).verifiedBinding(settlement)
                        .filter(b -> b.condition() == com.ultimakingdoms.warfare.ControlState.Condition.OCCUPIED).isPresent();
                if (control != null && "available".equals(control.availability()) && (control.contested() || occupied))
                    cause = new Evidence("ultima_kingdoms:control", settlement + ":" + control.sequence(), "CONTROL_TRANSITION", now, "Confirmed local control conditions support relief and autonomy talks");
            }
            if (terms.trigger() == Trigger.DEMAND && institution != null) {
                var demand = new ProtectionService(server).openDemand(settlement).stream().findFirst().orElse(null);
                if (demand != null) cause = new Evidence("ultima_kingdoms:protection", demand.id().toString(), "VOLUNTARY_DEMAND", demand.created(),
                        "A signed protectorate has requested voluntary " + demand.duty().name().toLowerCase(Locale.ROOT));
            }
            if (terms.trigger() == Trigger.GRIEVANCE && institution != null) {
                var dispute = new ProtectionService(server).disputes(settlement).stream().findFirst().orElse(null);
                if (dispute != null) cause = new Evidence("ultima_kingdoms:protection", dispute.id().toString(), "REFUSED_VOLUNTARY_OBLIGATION", dispute.created(),
                        "A signed voluntary obligation was refused; mediation or an alternative civilian contribution can reopen talks");
            }
            if (cause == null) continue;
            String causeKey = "cause:" + com.ultimakingdoms.politics.GovernmentService.hash(item.getKey() + ":" + cause.key());
            if (s.consumed.contains(causeKey)) continue;
            String seed = key + ":" + now; UUID id = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
            var event = new Scenario(id, item.getKey(), terms, settlement, terms.trigger() == Trigger.INTERREGNUM ? null : institution.id(),
                    place.kingdomId().toString(), cause, null, now, Math.addExact(now, terms.duration()), 1, Phase.OPEN, Map.of(), null, "");
            s.scenarios.put(id, event); s.consumed.add(causeKey); s.cooldowns.put(key, Math.addExact(now, terms.cooldown())); return;
        }
    }
    private long activeCount(EvolutionState s) {
        return s.scenarios.values().stream().filter(Scenario::pending).count() + com.ultimakingdoms.evolution.drama.DramaRuntime.get(server).activeCount();
    }
}
