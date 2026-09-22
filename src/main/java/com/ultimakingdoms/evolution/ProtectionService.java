package com.ultimakingdoms.evolution;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.contracts.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import static com.ultimakingdoms.evolution.ProtectionState.*;

/** Consent-based political hierarchy and native-service obligations with peaceful exit. */
public final class ProtectionService {
    public record PactView(UUID id,String protector,String subordinate,UUID beneficiary,Set<Duty> duties,Phase phase,
                           long revision,long expires,long noticeTicks,long exitAt,String terms,boolean settlementVisible) {}
    public record ObligationView(UUID id,UUID pact,Duty duty,UUID beneficiary,Status status,long revision,long deadline,String explanation) {}
    private final MinecraftServer server;
    private final ProtectionSavedData data;
    public ProtectionService(MinecraftServer server) { this.server = server; data = ProtectionSavedData.get(server); }
    public long revision() { if(!server.isSameThread())throw new IllegalStateException("Protection requires server thread"); return data.snapshot().revision; }
    public List<PactView> pacts(ServerPlayer viewer) { actor(viewer); var s=data.snapshot(); return s.pacts.values().stream().filter(p->visible(viewer,p))
            .sorted(Comparator.comparingLong(Pact::created).reversed()).map(p->new PactView(p.id(),p.protector(),p.subordinate(),p.beneficiary(),p.duties(),p.phase(),p.revision(),p.expires(),p.noticeTicks(),p.exitAt(),p.terms(),SettlementKnowledge.get(server).visible(viewer,p.beneficiary()))).toList(); }
    public List<ObligationView> obligations(ServerPlayer viewer) { actor(viewer); var visible=pacts(viewer).stream().filter(PactView::settlementVisible).map(PactView::id).collect(java.util.stream.Collectors.toSet());
        return data.snapshot().obligations.values().stream().filter(o->visible.contains(o.pact())).sorted(Comparator.comparingLong(Obligation::created).reversed())
                .map(o->new ObligationView(o.id(),o.pact(),o.duty(),o.beneficiary(),o.status(),o.revision(),o.deadline(),o.explanation())).toList(); }
    private boolean visible(ServerPlayer viewer,Pact p) { return p.signatures().size()==2||politics().authorized(viewer,p.protector(),Politics.Permission.RATIFY)||politics().authorized(viewer,p.subordinate(),Politics.Permission.RATIFY); }
    private PoliticalService politics() { return UltimaPoliticsApi.get(server); }
    private long now(ProtectionState s) { return Math.max(s.clock, server.overworld().getGameTime()); }
    private void actor(ServerPlayer player) {
        if (!server.isSameThread() || player.getServer() != server || player.hasDisconnected()) throw new IllegalArgumentException("Connected server player required");
    }
    private void authority(ServerPlayer player, String kingdom, Politics.Permission permission) {
        actor(player); if (!politics().authorized(player, kingdom, permission)) throw new IllegalArgumentException("Current " + permission + " authority required for " + kingdom);
    }
    private void commit(ProtectionState state) {
        state.clock = now(state); state.revision++;
        if (!data.commit(server, state)) throw new IllegalArgumentException("Protection save unavailable; action refused");
    }
    private Pact pact(ProtectionState s, UUID id) {
        var pact = s.pacts.get(id); if (pact == null) throw new IllegalArgumentException("Pact unavailable"); return pact;
    }
    private void provider() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("mcaquests"))
            throw new IllegalArgumentException("Native civilian contract provider must be enabled; obligations remain suspended");
    }
    public String propose(ServerPlayer player, String protector, String subordinate, UUID beneficiary,
                          Set<Duty> duties, long duration, long notice, String terms) {
        authority(player, protector, Politics.Permission.PROPOSE); provider();
        if (duration < 24000 || duration > 24_000_000) throw new IllegalArgumentException("Duration must be 24000..24000000 game ticks");
        var government = politics().government(subordinate).orElseThrow(() -> new IllegalArgumentException("Subordinate government unavailable"));
        if (government.state() != Politics.State.ACTIVE) throw new IllegalArgumentException("Subordinate government is not active");
        var settlement = UltimaKingdomsApi.get(server).getSettlement(beneficiary).orElseThrow(() -> new IllegalArgumentException("Beneficiary settlement unavailable"));
        if (!Set.of(protector, subordinate).contains(settlement.kingdomId().toString())
                || !SettlementKnowledge.get(server).visible(player, beneficiary)) throw new IllegalArgumentException("Known beneficiary in one signing kingdom required");
        var s = data.snapshot(); long now = now(s);
        if (s.pacts.values().stream().anyMatch(p -> p.subordinate().equals(subordinate) && (p.effective(now) || p.phase() == Phase.PROPOSED && p.expires() > now)))
            throw new IllegalArgumentException("Subordinate already has a protector or pending proposal");
        var p = new Pact(UUID.randomUUID(), protector, subordinate, beneficiary, duties, Map.of(), now, now + duration, notice, 0, 1, Phase.PROPOSED, terms);
        s.pacts.put(p.id(), p); commit(s); return "Protectorate proposal " + p.id() + "; both governments must sign frozen terms. Revision 1.";
    }
    public String sign(ServerPlayer player, UUID id, String kingdom, long revision) {
        authority(player, kingdom, Politics.Permission.RATIFY); provider(); var s = data.snapshot(); var p = pact(s, id);
        var signed = p.sign(kingdom, player.getUUID(), revision, now(s));
        for (var signature : signed.signatures().entrySet()) {
            var actor = server.getPlayerList().getPlayer(signature.getValue());
            if (actor == null || !politics().authorized(actor, signature.getKey(), Politics.Permission.RATIFY))
                throw new IllegalArgumentException("Both signing authorities must be online and still hold ratification authority");
        }
        s.pacts.put(id, signed); s.hierarchy(now(s)); commit(s);
        return signed.phase() == Phase.ACTIVE ? "Protectorate active. Only the enumerated voluntary duties apply; offices, laws and native ownership are retained." : "Signature recorded; awaiting the other government.";
    }
    public String exit(ServerPlayer player, UUID id, String kingdom, long revision) {
        authority(player, kingdom, Politics.Permission.RATIFY); var s = data.snapshot(); var p = pact(s, id);
        if (!Set.of(p.protector(), p.subordinate()).contains(kingdom)) throw new IllegalArgumentException("Signing government required");
        var next = p.exit(revision, now(s)); s.pacts.put(id, next); commit(s);
        return "Peaceful exit notice recorded; obligations end at game tick " + next.exitAt() + ". No assets or troops are confiscated.";
    }
    public String request(ServerPlayer player, UUID id, Duty duty, long revision) {
        var s = data.snapshot(); var p = pact(s, id); authority(player, p.protector(), Politics.Permission.REVIEW); provider();
        if (revision != p.revision() || !p.effective(now(s)) || p.phase() == Phase.EXIT_NOTICE) throw new IllegalArgumentException("Pact changed, inactive or exiting");
        if (!p.duties().contains(duty)) throw new IllegalArgumentException("Out-of-scope levy refused: this pact does not create that obligation");
        long deadlineNow = now(s);
        s.obligations.replaceAll((key, value) -> value.status() == Status.OPEN && value.deadline() <= deadlineNow
                ? value.finish(Status.EXPIRED, null, "", "Voluntary service deadline elapsed", value.revision()) : value);
        if (s.obligations.values().stream().anyMatch(o -> o.pact().equals(id) && o.duty() == duty && o.status() == Status.OPEN))
            throw new IllegalArgumentException("This duty already has an open request");
        long now = now(s); var obligation = new Obligation(UUID.randomUUID(), id, duty, p.beneficiary(), player.getUUID(), now,
                Math.min(p.expires(), now + 72000), 1, Status.OPEN, null, "", "Voluntary " + duty + " under protectorate " + id);
        s.obligations.put(obligation.id(), obligation); commit(s); return "Obligation " + obligation.id() + " revision 1. Complete a matching native contract or explicitly refuse.";
    }
    public String fulfill(ServerPlayer player, UUID id, long revision) {
        actor(player); provider(); var s = data.snapshot(); var o = s.obligations.get(id);
        if (o == null) throw new IllegalArgumentException("Obligation unavailable"); var p = pact(s, o.pact());
        if (!p.effective(now(s)) || now(s) >= o.deadline()) throw new IllegalArgumentException("Obligation no longer active");
        if (!SettlementKnowledge.get(server).visible(player, o.beneficiary())) throw new IllegalArgumentException("Visit the beneficiary first");
        var kind = EvolutionContracts.kind(o.duty());
        var proof = CivilianContractService.scopedProof(server, player.getUUID(), o.beneficiary(), kind, o.id())
                .filter(r -> r.completedAt() >= o.created() && r.completedAt() < o.deadline())
                .orElseThrow(() -> new IllegalArgumentException("Matching native service completion within the obligation period required"));
        String key = proof.providerEpoch() + ":" + proof.receipt();
        if (!s.receipts.add(key)) throw new IllegalArgumentException("Receipt already satisfies an obligation");
        s.obligations.put(id, o.finish(Status.SATISFIED, player.getUUID(), key, "Native " + kind + " service verified", revision)); commit(s);
        return "Obligation satisfied from native completion receipt. Native payment remains with the quest provider.";
    }
    public String refuse(ServerPlayer player, UUID id, long revision, String reason) {
        var s = data.snapshot(); var o = s.obligations.get(id); if (o == null) throw new IllegalArgumentException("Obligation unavailable");
        var p = pact(s, o.pact()); authority(player, p.subordinate(), Politics.Permission.REVIEW);
        if (reason.isBlank()) throw new IllegalArgumentException("Explain the refusal");
        s.obligations.put(id, o.finish(Status.REFUSED, player.getUUID(), "", reason, revision)); commit(s);
        return "Refusal recorded. This may support negotiation; it does not declare war or transfer property.";
    }
    public List<String> page(ServerPlayer viewer, int offset) {
        actor(viewer); if (offset < 0 || offset > 1024 || offset % 10 != 0) throw new IllegalArgumentException("Invalid page");
        var s = data.snapshot(); var lines = new ArrayList<String>();
        lines.add("Protectorates: enumerated voluntary obligations, maximum hierarchy depth 4; revision " + s.revision);
        s.pacts.values().stream().filter(p -> p.signatures().size() == 2 || politics().authorized(viewer, p.protector(), Politics.Permission.RATIFY)
                        || politics().authorized(viewer, p.subordinate(), Politics.Permission.RATIFY)).sorted(Comparator.comparing(Pact::id)).skip(offset).limit(10)
                .forEach(p -> lines.add(p.id() + " | " + p.protector() + " → " + p.subordinate() + " | " + p.refresh(now(s)).phase() + " | " + p.duties() + " | revision " + p.revision()));
        return List.copyOf(lines);
    }
    public List<String> inspect(ServerPlayer viewer, UUID id) {
        actor(viewer); var s = data.snapshot(); var p = pact(s, id);
        if (p.signatures().size() < 2 && !politics().authorized(viewer, p.protector(), Politics.Permission.RATIFY)
                && !politics().authorized(viewer, p.subordinate(), Politics.Permission.RATIFY)) throw new IllegalArgumentException("Proposal unavailable");
        var lines = new ArrayList<String>(); lines.add(p.terms()); lines.add("Duties: " + p.duties() + "; revision " + p.revision() + "; notice " + p.noticeTicks() + " ticks");
        if (SettlementKnowledge.get(server).visible(viewer, p.beneficiary())) {
            lines.add("Beneficiary settlement " + p.beneficiary());
            s.obligations.values().stream().filter(o -> o.pact().equals(id)).limit(16).forEach(o -> lines.add(o.id() + " | " + o.duty() + " | " + (o.status() == Status.OPEN && o.deadline() <= now(s) ? Status.EXPIRED : o.status()) + " | revision " + o.revision() + " | deadline " + o.deadline()));
        }
        return List.copyOf(lines);
    }
    /** Bounded factual demand for the director; private refusals are not public narrative evidence. */
    public List<Obligation> openDemand(UUID settlement) {
        var s = data.snapshot(); return s.obligations.values().stream().filter(o -> o.beneficiary().equals(settlement) && o.status() == Status.OPEN
                && o.deadline() > now(s) && s.pacts.get(o.pact()).effective(now(s))).limit(8).toList();
    }
    public List<Obligation> disputes(UUID settlement) {
        var s = data.snapshot(); return s.obligations.values().stream().filter(o -> o.beneficiary().equals(settlement)
                && o.status() == Status.REFUSED && s.pacts.get(o.pact()).effective(now(s))).limit(8).toList();
    }
}
