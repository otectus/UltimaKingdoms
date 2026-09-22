package com.ultimakingdoms.evolution.drama;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.compat.recruits.RecruitsMilitary;
import com.ultimakingdoms.evolution.EvolutionRuntime;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.*;
import com.ultimakingdoms.warfare.CampaignState.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Opt-in orchestration over existing authority-checked campaign and organization operations. */
public final class DramaService {
    public record TemplateView(String id,String title,DramaState.Kind kind,String objective,Set<DramaState.Outcome> outcomes) {}
    public record DramaView(UUID id,String title,DramaState.Kind kind,UUID settlement,DramaState.Phase phase,long revision,long remainingTicks,
                            String objective,Set<DramaState.Outcome> outcomes,String subject,String sourceKingdom,String targetKingdom,String result,boolean participant,boolean canConsent) {}
    private final MinecraftServer server;
    private final DramaSavedData data;
    private final DramaDefinitions definitions;
    DramaService(MinecraftServer server, DramaDefinitions definitions) { this.server = server; this.definitions = definitions; this.data = DramaSavedData.get(server); }
    public long revision() { thread(); return data.snapshot().revision; }
    public long activeCount() { thread(); return data.snapshot().dramas.values().stream().filter(d -> !d.terminal()).count(); }
    public Optional<DramaState.Drama> drama(UUID id) { thread(); return Optional.ofNullable(data.snapshot().dramas.get(id)); }
    public List<TemplateView> templates() { thread(); return definitions.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e->new TemplateView(e.getKey(),e.getValue().title(),e.getValue().kind(),e.getValue().objective(),e.getValue().outcomes())).toList(); }
    public List<DramaView> dramas(ServerPlayer viewer) { viewer(viewer); return data.snapshot().dramas.values().stream().filter(d->participates(viewer,d)||eligibleConsent(viewer,d))
            .sorted(Comparator.comparingLong(DramaState.Drama::created).reversed()).map(d->new DramaView(d.id(),d.terms().title(),d.terms().kind(),d.settlement(),d.phase(),d.revision(),d.remainingTicks(),
                    d.terms().objective(),d.terms().outcomes(),d.subject(),d.sourceKingdom(),d.targetKingdom(),d.result(),participates(viewer,d),eligibleConsent(viewer,d))).toList(); }
    private boolean eligibleConsent(ServerPlayer viewer,DramaState.Drama drama) { if(drama.phase()!=DramaState.Phase.PROPOSED||viewer.getUUID().equals(drama.proposer()))return false;
        if(drama.terms().kind()==DramaState.Kind.SCHISM){var source=ResourceLocation.tryParse(drama.subject());return source!=null&&activeMember(viewer,source);}
        try{return RecruitsMilitary.commandedFaction(viewer).equals(drama.targetFaction());}catch(IllegalArgumentException failure){return false;} }

    public String propose(ServerPlayer actor, UUID request, long expectedRevision, String templateId, UUID settlement, String subject) {
        actor(actor); require(EvolutionRuntime.dramaEnabled(server), "Authored drama is not enabled for this world.");
        require(request != null && settlement != null, "A request and settlement are required.");
        var template = definitions.snapshot().get(templateId); require(template != null, "Unknown authored drama template.");
        require(SettlementKnowledge.get(server).visible(actor, settlement), "Settlement is not known to you.");
        var civic = UltimaKingdomsApi.get(server).getSettlement(settlement).orElseThrow(() -> new IllegalArgumentException("Civic settlement is unavailable."));
        String source, target, sourceKingdom, targetKingdom; var evidence = new ArrayList<DramaState.Evidence>();
        String dynamic = ""; subject = subject == null ? "" : subject;
        if (template.kind() == DramaState.Kind.SCHISM) {
            var original = ResourceLocation.tryParse(subject); require(original != null, "Schism requires an existing organization id.");
            var organizations = OrganizationApi.get(server); require(organizations.definition(original).isPresent(), "Schism requires a defined source organization.");
            var membership = organizations.ownSnapshot(actor).memberships().stream().filter(m -> m.organizationId().equals(original)
                    && m.status() == OrganizationMembershipSnapshot.Status.ACTIVE).findFirst();
            require(membership.isPresent(), "Schism participants must be active members of the source organization.");
            sourceKingdom = civic.kingdomId().toString(); targetKingdom = sourceKingdom;
            require(UltimaPoliticsApi.get(server).authorized(actor, sourceKingdom, Politics.Permission.RECOGNIZE), "Founding signatory lacks current charter recognition authority.");
            source = "schism_founder"; target = "schism_consenter";
            dynamic = "ultima_kingdoms:schism_" + request.toString().replace("-", "");
            evidence.add(new DramaState.Evidence("organization", "ACTIVE_MEMBERSHIP", original.toString(), membership.get().revision(), "Saved source organization membership and current charter authority confirmed"));
        } else {
            var controls = WarfareRuntime.get(server); var binding = controls.verifiedBinding(settlement)
                    .orElseThrow(() -> new IllegalArgumentException("Saved native control evidence is unavailable."));
            source = RecruitsMilitary.commandedFaction(actor);
            var sourceMap = controls.mapping(source).orElseThrow(() -> new IllegalArgumentException("Your native faction is not explicitly mapped."));
            target = binding.owner(); sourceKingdom = sourceMap.kingdom(); targetKingdom = sourceKingdom;
            var targetMap = controls.mapping(target).orElseThrow(() -> new IllegalArgumentException("The involved native faction is not explicitly mapped."));
            require(!source.equals(target), "The two participants must be distinct."); targetKingdom = targetMap.kingdom();
            require(Set.of(DramaState.Kind.CAMPAIGN, DramaState.Kind.REBELLION, DramaState.Kind.INVASION).contains(template.kind()), "This authored template has no installed authority adapter.");
            evidence.add(new DramaState.Evidence("warfare_control", binding.condition().name(), binding.claim().toString(), binding.sequence(),
                    "Saved control binding; civic settlement and buildings remain unchanged"));
            if (template.kind() == DramaState.Kind.REBELLION) {
                require(binding.condition() == ControlState.Condition.OCCUPIED, "Rebellion requires a current saved occupation grievance.");
                evidence.add(new DramaState.Evidence("warfare_control", "OCCUPATION_GRIEVANCE", settlement.toString(), binding.sequence(), "Occupation is evidence for the proposal, not authority to erase civic identity"));
            }
        }
        String fingerprint = templateId + "|" + settlement + "|" + subject;
        var current = data.snapshot();
        if (current.receipts.containsKey(request)) { require(current.receipts.get(request).equals(fingerprint), "Request id belongs to another proposal."); return "Drama " + request + " already recorded."; }
        require(current.revision == expectedRevision, "Drama state changed; refresh before proposing.");
        require(current.dramas.values().stream().filter(d -> !d.terminal()).count() + EvolutionRuntime.activeScenarioCount(server)
                < com.ultimakingdoms.evolution.EvolutionConfig.CONCURRENT.get(), "The shared world evolution concurrency budget is full.");
        require(current.dramas.size() < DramaState.CAPACITY, "Drama capacity reached.");
        require(current.dramas.values().stream().noneMatch(d -> d.settlement().equals(settlement) && !d.terminal()), "An authored drama already involves this settlement.");
        var participants = new LinkedHashMap<String, UUID>(); participants.put(source, actor.getUUID());
        var frozen = new DramaState.Drama(request, templateId, template, settlement, actor.getUUID(), source, target, sourceKingdom, targetKingdom,
                subject, dynamic, participants, Set.of(source), evidence, now(), template.durationTicks(), now(), 1, 0, DramaState.Phase.PROPOSED,
                DramaState.Operation.NONE, DramaState.operationId(request, "provider"), "Awaiting an independent participant's consent");
        current.dramas.put(request, frozen); current.receipts.put(request, fingerprint); save(current);
        return "Drama " + request + " proposed from frozen authored terms; the other participant must consent.";
    }

    public String consent(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.phase() == DramaState.Phase.PROPOSED, "Drama is not awaiting consent.");
        String side;
        if (drama.terms().kind() == DramaState.Kind.SCHISM) {
            var source = ResourceLocation.tryParse(drama.subject()); require(source != null && activeMember(actor, source), "Only another active source member may consent.");
            require(!actor.getUUID().equals(drama.proposer()), "Bilateral consent requires another player."); side = drama.targetFaction();
        } else {
            side = RecruitsMilitary.commandedFaction(actor); require(side.equals(drama.targetFaction()), "Only the involved opposing native leader may consent.");
        }
        current.dramas.put(id, drama.consent(side, actor.getUUID(), expectedRevision)); save(current);
        return "Consent recorded. The drama is ready; no native operation has been implied.";
    }

    public String execute(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.terms().outcomes().contains(drama.terms().kind() == DramaState.Kind.SCHISM ? DramaState.Outcome.FOUND_SCHISM : DramaState.Outcome.DECLARE), "Template does not authorize this outcome.");
        require(actor.getUUID().equals(drama.proposer()), "Only the proposing signatory may start the recorded operation.");
        if (drama.terms().kind() != DramaState.Kind.SCHISM)
            require(RecruitsMilitary.commands(actor, drama.sourceFaction()), "Proposer no longer commands the recorded native faction.");
        DramaState.Operation operation = drama.terms().kind() == DramaState.Kind.SCHISM ? DramaState.Operation.SCHISM_FOUNDING : DramaState.Operation.DECLARATION;
        current.dramas.put(id, drama.begin(operation, expectedRevision)); save(current); // durable fence before provider call
        return resume(actor, id);
    }

    public String negotiate(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id);
        require(drama.terms().outcomes().contains(DramaState.Outcome.NEGOTIATE), "Template does not authorize negotiation.");
        require(drama.terms().kind() != DramaState.Kind.SCHISM, "A schism exits peacefully by leaving or independently choosing membership.");
        require(RecruitsMilitary.commands(actor, drama.sourceFaction()), "Only the recorded native signatory may propose terms.");
        current.dramas.put(id, drama.begin(DramaState.Operation.ACCORD, expectedRevision)); save(current);
        return resume(actor, id);
    }

    public String exit(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); var current = data.snapshot(); var drama = requireDrama(current, id); require(participates(actor, drama), "Only a participant may exit.");
        require(drama.terms().outcomes().contains(DramaState.Outcome.EXIT), "Template does not authorize exit.");
        current.dramas.put(id, drama.exit(expectedRevision, "Participant chose a nonviolent exit; civic identity, buildings, assets, and standing were retained")); save(current);
        return "Drama exited without changing native control, civic identity, buildings, assets, or organization standing.";
    }

    public String recover(ServerPlayer actor, UUID id, long expectedRevision) {
        actor(actor); requireEnabled(); var current = data.snapshot(); var drama = requireDrama(current, id); require(participates(actor, drama), "Only a participant may recover this drama.");
        if (drama.phase() == DramaState.Phase.ACTIVE) {
            require(drama.terms().outcomes().contains(DramaState.Outcome.RECOVER), "Template does not authorize withdrawal recovery.");
            require(RecruitsMilitary.commands(actor, drama.sourceFaction()), "Only the recorded campaign commander may withdraw.");
            current.dramas.put(id, drama.begin(DramaState.Operation.WITHDRAWAL, expectedRevision)); save(current);
        } else {
            require(drama.revision() == expectedRevision && Set.of(DramaState.Phase.APPLYING, DramaState.Phase.SUSPENDED, DramaState.Phase.NEGOTIATING, DramaState.Phase.RECOVERING).contains(drama.phase()), "Nothing is pending recovery.");
            if (drama.operation() != DramaState.Operation.NONE) { current.dramas.put(id, drama.retry(expectedRevision)); save(current); }
        }
        return resume(actor, id);
    }

    private String resume(ServerPlayer actor, UUID id) {
        var current = data.snapshot(); var drama = requireDrama(current, id);
        try {
            if (drama.operation() == DramaState.Operation.SCHISM_FOUNDING || drama.phase() == DramaState.Phase.APPLYING && drama.terms().kind() == DramaState.Kind.SCHISM) {
                var organizations = OrganizationApi.get(server); var dynamic = new ResourceLocation(drama.dynamicOrganization());
                if (organizations.lifecycle(dynamic).isEmpty()) {
                    var result = organizations.found(actor, dynamic, new ResourceLocation(drama.terms().organizationTemplate()), drama.sourceKingdom(), drama.terms().organizationName(), organizations.revision());
                    require(result.status() == OrganizationLifecycleResult.Status.APPLIED, "Charter was not applied: " + result.reason());
                }
                current = data.snapshot(); drama = requireDrama(current, id);
                current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, "Schism charter founded; each participant retains standing and chooses membership independently")); save(current);
                return "Schism charter founded. No member was moved and no standing was copied.";
            }
            var campaigns = CampaignService.get(server);
            if (drama.operation() == DramaState.Operation.WITHDRAWAL || drama.phase() == DramaState.Phase.RECOVERING) {
                campaigns.withdraw(actor, drama.providerRequest());
                var confirmed = campaigns.campaign(drama.providerRequest()).orElseThrow(() -> new IllegalArgumentException("Campaign receipt unavailable after withdrawal."));
                require(confirmed.phase() == CampaignState.Phase.RESOLVED, "Native withdrawal remains pending.");
                current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, "Native campaign withdrawal confirmed")); save(current);
                return "Native withdrawal confirmed; civic identity and assets remain intact.";
            }
            if (drama.operation() == DramaState.Operation.ACCORD || drama.phase() == DramaState.Phase.NEGOTIATING) {
                UUID accordId = DramaState.operationId(id, "accord"); var accord = campaigns.accord(accordId).orElse(null);
                if (accord == null) {
                    var control = campaigns.control(drama.settlement()).orElseThrow(() -> new IllegalArgumentException("Current control unavailable for negotiation."));
                    campaigns.propose(actor, accordId, campaigns.revision(), drama.settlement(), drama.targetFaction(), Relation.NEUTRAL,
                            Sovereignty.RECOGNIZED, control.recognizedKingdom(), drama.terms().objective());
                    accord = campaigns.accord(accordId).orElseThrow(() -> new IllegalArgumentException("Negotiation receipt unavailable after proposal."));
                }
                if (accord.phase() != TreatyPhase.ACTIVE) {
                    if (drama.operation() == DramaState.Operation.ACCORD) {
                        current = data.snapshot(); drama = requireDrama(current, id);
                        current.dramas.put(id, drama.applied(DramaState.Phase.NEGOTIATING, "Native accord proposed; both leaders must ratify through campaign authority")); save(current);
                    }
                    return "Negotiation remains pending bilateral native ratification.";
                }
                current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(DramaState.Phase.RESOLVED, "Bilateral native accord confirmed")); save(current);
                return "Bilateral native accord confirmed.";
            }
            if (campaigns.campaign(drama.providerRequest()).isEmpty()) {
                campaigns.declare(actor, drama.providerRequest(), campaigns.revision(), drama.settlement(), drama.terms().goal(), drama.terms().objective());
            } else campaigns.applyDeclaration(actor, drama.providerRequest());
            var confirmed = campaigns.campaign(drama.providerRequest()).orElseThrow(() -> new IllegalArgumentException("Campaign receipt unavailable after provider operation."));
            DramaState.Phase next = confirmed.phase() == CampaignState.Phase.SUSPENDED ? DramaState.Phase.SUSPENDED
                    : confirmed.phase() == CampaignState.Phase.RESOLVED || confirmed.phase() == CampaignState.Phase.EXPIRED ? DramaState.Phase.RESOLVED : DramaState.Phase.ACTIVE;
            current = data.snapshot(); drama = requireDrama(current, id); current.dramas.put(id, drama.applied(next,
                    "Native campaign state confirmed as " + confirmed.phase() + "; battle and control remain native-owned")); save(current);
            return "Provider-confirmed campaign state: " + confirmed.phase() + ". No siege or transfer was invented.";
        } catch (RuntimeException failure) { return suspend(id, failure); }
    }

    private String suspend(UUID id, RuntimeException failure) {
        var current = data.snapshot(); var drama = requireDrama(current, id); String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (detail.length() > 800) detail = detail.substring(0, 800);
        current.dramas.put(id, drama.suspended("Provider operation pending recovery: " + detail));
        if (!saveIfPossible(current)) return "Provider failed and suspension could not be saved; inspect before retrying.";
        return "Provider operation was not confirmed. Drama is suspended for explicit recovery: " + detail;
    }

    public List<String> page(ServerPlayer viewer, int offset) {
        actor(viewer); if (offset < 0) throw new IllegalArgumentException("Offset must be nonnegative.");
        return data.snapshot().dramas.values().stream().filter(d -> participates(viewer, d)).sorted(Comparator.comparingLong(DramaState.Drama::created).reversed())
                .skip(offset).limit(20).map(d -> d.id() + " | " + d.terms().title() + " | " + d.phase() + " | rev " + d.revision() + " | online time " + d.remainingTicks()).toList();
    }
    public List<String> inspect(ServerPlayer viewer, UUID id) {
        actor(viewer); var drama = data.snapshot().dramas.get(id); if (drama == null || !participates(viewer, drama)) return List.of("Drama unavailable.");
        var lines = new ArrayList<String>(); lines.add(drama.id() + " | " + drama.terms().title() + " | " + drama.phase() + " | revision " + drama.revision());
        lines.add("Objective: " + drama.terms().objective()); lines.add("Remaining online time: " + drama.remainingTicks() + "; consents " + drama.consents().size() + "/2");
        drama.evidence().forEach(e -> lines.add("Evidence " + e.source() + "/" + e.kind() + " #" + e.revision() + ": " + e.detail())); lines.add("Status: " + drama.result()); return List.copyOf(lines);
    }

    void tick() {
        thread(); if (!EvolutionRuntime.dramaEnabled(server) || !data.writable()) return; var current = data.snapshot();
        if (current.dramas.isEmpty()) return; var open = current.dramas.values().stream().filter(d -> !d.terminal()).toList(); if (open.isEmpty()) return;
        boolean changed = false; long now = now(); int count = Math.min(8, open.size());
        for (int i = 0; i < count; i++) {
            var drama = open.get(Math.floorMod(current.cursor++, open.size())); boolean online = drama.participants().values().stream().allMatch(id -> server.getPlayerList().getPlayer(id) != null);
            var evaluated = drama.evaluate(now, online); if (!evaluated.equals(drama)) { current.dramas.put(drama.id(), evaluated); changed = true; }
        }
        if (changed) saveIfPossible(current);
    }
    private boolean activeMember(ServerPlayer player, ResourceLocation organization) {
        return OrganizationApi.get(server).ownSnapshot(player).memberships().stream().anyMatch(m -> m.organizationId().equals(organization) && m.status() == OrganizationMembershipSnapshot.Status.ACTIVE);
    }
    private boolean participates(ServerPlayer player, DramaState.Drama drama) { return drama.participants().containsValue(player.getUUID()); }
    private DramaState.Drama requireDrama(DramaState state, UUID id) { var drama = state.dramas.get(id); if (drama == null) throw new IllegalArgumentException("Drama unavailable."); return drama; }
    private void viewer(ServerPlayer actor) { thread(); require(actor != null && actor.getServer() == server && !actor.hasDisconnected(), "Connected server player required."); }
    private void actor(ServerPlayer actor) { viewer(actor); require(data.writable(), "Drama records are retained read-only."); }
    private void requireEnabled() { require(EvolutionRuntime.dramaEnabled(server), "Authored drama is not enabled for this world."); }
    private long now() { return server.overworld().getGameTime(); }
    private void save(DramaState next) { next.revision = Math.addExact(next.revision, 1); require(data.commit(server, next), "Drama transaction could not be saved."); }
    private boolean saveIfPossible(DramaState next) { next.revision = Math.addExact(next.revision, 1); return data.commit(server, next); }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("Drama requires server thread"); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
