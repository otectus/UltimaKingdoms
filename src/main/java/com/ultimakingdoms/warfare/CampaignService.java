package com.ultimakingdoms.warfare;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.compat.recruits.RecruitsMilitary;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.CampaignState.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Durable intent -> native acknowledgment -> political effect. Recovery never rewrites native divergence. */
public final class CampaignService implements WarfareApi.Provider {
    private static final Map<MinecraftServer, CampaignService> SERVICES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final CampaignSavedData data;
    private int campaignCursor, accordCursor;
    private CampaignService(MinecraftServer server) { this.server = server; data = CampaignSavedData.get(server); }
    public static CampaignService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaigns require server thread");
        return SERVICES.computeIfAbsent(server, CampaignService::new);
    }
    public static void attach(MinecraftServer server) { WarfareApi.attach(server, get(server)); }
    public static void clear(MinecraftServer server) { SERVICES.remove(server); RecruitsMilitary.clear(server); WarfareApi.detach(server); }
    private WarfareRuntime controls() { return WarfareRuntime.get(server); }
    private KingdomsService kingdoms() { return UltimaKingdomsApi.get(server); }
    private PoliticalService politics() { return UltimaPoliticsApi.get(server); }
    private long now() { return server.overworld().getGameTime(); }
    public long revision() { return data.snapshot().revision; }
    public Optional<CampaignState.Campaign> campaign(UUID id) {
        if (!server.isSameThread()) throw new IllegalStateException("Campaign reads require server thread");
        return Optional.ofNullable(data.snapshot().campaigns.get(id));
    }
    public Optional<CampaignState.Accord> accord(UUID id) {
        if (!server.isSameThread()) throw new IllegalStateException("Accord reads require server thread");
        return Optional.ofNullable(data.snapshot().accords.get(id));
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    private void actor(ServerPlayer actor) {
        require(server.isSameThread() && actor.getServer() == server && !actor.hasDisconnected(), "Connected server player required.");
        require(WarfareConfig.ENABLED.get() && WarfareConfig.MILITARY.get(), "Military actions disabled by this server.");
        require(data.writable(), "Campaign records are unavailable and retained read-only.");
    }
    private String authority(ServerPlayer actor, Politics.Permission permission) {
        actor(actor); String nativeId = RecruitsMilitary.commandedFaction(actor);
        var mapping = controls().mapping(nativeId).orElseThrow(() -> new IllegalArgumentException("Native faction is not explicitly mapped."));
        require(politics().authorized(actor, mapping.kingdom(), permission), "A live " + permission + " political mandate is also required.");
        return nativeId;
    }
    private void known(ServerPlayer player, UUID settlement) {
        require(SettlementKnowledge.get(server).visible(player, settlement), "Settlement is not known to you.");
    }
    private ControlState.Binding bound(UUID id) {
        return controls().verifiedBinding(id).orElseThrow(() -> new IllegalArgumentException("Current native control is awaiting verified persistence or recovery."));
    }
    private void save(CampaignState next) {
        next.revision = Math.addExact(next.revision, 1);
        require(data.commit(server, next), "Campaign transaction could not be saved.");
    }
    private void notice(CampaignState next, UUID settlement, String action, UUID actor, String detail) {
        next.history.add(new Notice(UUID.randomUUID(), settlement, action, actor, now(), detail));
        if (next.history.size() > CampaignState.CAPACITY) next.history.remove(0);
    }
    private Optional<String> replay(ServerPlayer actor, UUID request, String fingerprint) {
        var receipt = data.snapshot().receipts.get(request);
        if (receipt == null) return Optional.empty();
        require(receipt.player().equals(actor.getUUID()) && receipt.fingerprint().equals(fingerprint), "Request identifier already belongs to another action.");
        return Optional.of(receipt.result());
    }
    private void receipt(CampaignState next, ServerPlayer actor, UUID request, String fingerprint, String result) {
        require(next.receipts.size() < CampaignState.CAPACITY * 4, "Campaign receipt capacity reached.");
        next.receipts.put(request, new Receipt(actor.getUUID(), fingerprint, result));
    }
    public String declare(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, Goal goal, String reason) {
        actor(actor); CampaignState.text(reason, 512); require(!reason.isBlank(), "A public campaign objective is required.");
        String fingerprint = com.ultimakingdoms.politics.GovernmentService.hash("declare|" + expectedRevision + "|" + settlement + "|" + goal + "|" + reason);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String attacker = authority(actor, Politics.Permission.PROPOSE); known(actor, settlement);
        var binding = bound(settlement); String defender = binding.owner(); require(!attacker.equals(defender), "Your faction already controls this claim.");
        require(controls().mapping(defender).isPresent(), "Defending faction must be explicitly mapped.");
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement), "This server protects capitals from campaigns.");
        var next = data.snapshot(); require(next.revision == expectedRevision, "Campaign state changed; refresh before declaring.");
        require(next.campaigns.size() < CampaignState.CAPACITY, "Campaign capacity reached.");
        require(next.campaigns.values().stream().noneMatch(c -> c.settlement().equals(settlement) && c.expires() > now()
                && c.phase() != Phase.EXPIRED && c.phase() != Phase.RESOLVED), "A campaign is already open for this settlement.");
        var before = RecruitsMilitary.relation(server, attacker, defender);
        var campaign = new Campaign(request, settlement, binding.claim(), attacker, defender, controls().mapping(attacker).orElseThrow().kingdom(),
                actor.getUUID(), goal, now() + WarfareConfig.NOTICE.get(), now() + WarfareConfig.NOTICE.get() + WarfareConfig.CAMPAIGN_DURATION.get(),
                Phase.NOTICE, binding.sequence(), before, reason);
        next.campaigns.put(request, campaign); notice(next, settlement, "declaration_intent", actor.getUUID(), reason);
        receipt(next, actor, request, fingerprint, "Campaign " + request + " recorded; inspect its native application status."); save(next);
        applyDeclaration(actor, request);
        return "Campaign " + request + ": " + data.snapshot().campaigns.get(request).phase() + ". Native siege detection still governs the battle.";
    }
    public String applyDeclaration(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE);
        var next = data.snapshot(); var campaign = next.campaigns.get(campaignId); require(campaign != null, "Campaign unavailable.");
        require(campaign.commander().equals(actor.getUUID()) && campaign.attacker().equals(faction), "This is another commander's campaign.");
        require(now() < campaign.expires() && campaign.phase() != Phase.RESOLVED && campaign.phase() != Phase.EXPIRED, "Campaign closed.");
        var binding = bound(campaign.settlement()); require(binding.claim().equals(campaign.claim()) && binding.owner().equals(campaign.defender()), "Claim control changed.");
        boolean applied = RecruitsMilitary.apply(actor, campaign.attacker(), campaign.defender(), campaign.before(), Relation.ENEMY);
        next = data.snapshot();
        next.campaigns.put(campaignId, campaign.phase(applied ? now() < campaign.noticeUntil() ? Phase.NOTICE : Phase.ACTIVE : Phase.SUSPENDED,
                applied ? "Declaration confirmed by native save; objective " + campaign.goal() : "Native declaration vetoed or not yet saved; explicit retry required."));
        save(next); return next.campaigns.get(campaignId).reason();
    }
    public String propose(ServerPlayer actor, UUID request, long expectedRevision, UUID settlement, String counterpart,
                          Relation relation, Sovereignty status, String recognizedKingdom, String terms) {
        actor(actor); CampaignState.text(terms, 512); require(!terms.isBlank(), "Describe the settlement terms.");
        require(relation != Relation.ENEMY, "War uses an explicit campaign declaration.");
        String fingerprint = com.ultimakingdoms.politics.GovernmentService.hash("accord|" + expectedRevision + "|" + settlement + "|" + counterpart + "|" + relation + "|" + status + "|" + recognizedKingdom + "|" + terms);
        var prior = replay(actor, request, fingerprint); if (prior.isPresent()) return prior.get();
        String first = authority(actor, Politics.Permission.RATIFY); known(actor, settlement); var binding = bound(settlement);
        require(!first.equals(counterpart), "An accord needs two separate native factions.");
        var a = controls().mapping(first).orElseThrow(); var b = controls().mapping(counterpart).orElseThrow(() -> new IllegalArgumentException("Counterpart is not mapped."));
        require(RecruitsMilitary.faction(server, counterpart).isPresent(), "Counterpart native faction unavailable.");
        String currentSovereign = control(settlement).orElseThrow().recognizedKingdom();
        require((first.equals(binding.owner()) || counterpart.equals(binding.owner()))
                && (a.kingdom().equals(currentSovereign) || b.kingdom().equals(currentSovereign)), "Both current control and recognized sovereignty must be represented.");
        require(recognizedKingdom.equals(a.kingdom()) || recognizedKingdom.equals(b.kingdom()), "The recognized sovereign must be a signing government.");
        require(!WarfareConfig.PROTECT_CAPITALS.get() || !politics().isCapital(settlement)
                || status == Sovereignty.RECOGNIZED && recognizedKingdom.equals(currentSovereign), "Protected capital sovereignty cannot be changed.");
        var next = data.snapshot(); require(next.revision == expectedRevision, "Campaign state changed; refresh before proposing.");
        require(next.accords.size() < CampaignState.CAPACITY, "Accord capacity reached.");
        require(next.accords.values().stream().noneMatch(t -> t.settlement().equals(settlement)
                && t.phase() != TreatyPhase.EXPIRED && t.phase() != TreatyPhase.REJECTED && t.phase() != TreatyPhase.BREACHED && t.expires() > now()), "An accord already applies or is pending for this settlement.");
        var accord = new Accord(request, settlement, first, counterpart, a.kingdom(), b.kingdom(), relation,
                RecruitsMilitary.relation(server, first, counterpart), RecruitsMilitary.relation(server, counterpart, first), Map.of(first, actor.getUUID()),
                TreatyPhase.PROPOSED, status, recognizedKingdom, now() + WarfareConfig.CAMPAIGN_DURATION.get(), binding.sequence(), binding.claim(), binding.owner(), terms);
        next.accords.put(request, accord); notice(next, settlement, "accord_proposed", actor.getUUID(), terms);
        receipt(next, actor, request, fingerprint, "Accord " + request + " awaits the other native leader and political ratifier."); save(next);
        return next.receipts.get(request).result();
    }
    public String sign(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot();
        require(next.revision == expectedRevision, "Accord state changed; inspect before signing.");
        var accord = next.accords.get(accordId); require(accord != null && accord.phase() == TreatyPhase.PROPOSED && now() < accord.expires(), "Accord is not open for signature.");
        known(actor, accord.settlement()); require(side.equals(accord.first()) || side.equals(accord.second()), "You do not represent either signing faction.");
        accordControl(accord);
        require(!accord.signatures().containsKey(side), "This faction already signed.");
        accord = accord.sign(side, actor.getUUID()); next.accords.put(accordId, accord); save(next);
        return accord.phase() == TreatyPhase.SIGNED ? applyAccord(actor, accordId) : "Signature recorded.";
    }
    public String applyAccord(ServerPlayer requester, UUID accordId) {
        actor(requester); var next = data.snapshot(); var accord = next.accords.get(accordId);
        require(accord != null && (accord.phase() == TreatyPhase.SIGNED || accord.phase() == TreatyPhase.PENDING) && now() < accord.expires(), "Accord is not ready for application.");
        require(accord.signatures().containsValue(requester.getUUID()), "Only an accord signatory may request application.");
        accordControl(accord);
        ServerPlayer first = signatory(accord, accord.first(), accord.firstKingdom());
        ServerPlayer second = signatory(accord, accord.second(), accord.secondKingdom());
        var firstLive = RecruitsMilitary.relation(server, accord.first(), accord.second());
        var secondLive = RecruitsMilitary.relation(server, accord.second(), accord.first());
        require((firstLive == accord.firstBefore() || firstLive == accord.desired()) && (secondLive == accord.secondBefore() || secondLive == accord.desired()),
                "Native diplomacy diverged; negotiate fresh terms instead of overwriting it.");
        next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, "Native application pending; peace is not yet operational.")); save(next);
        boolean applied = RecruitsMilitary.apply(first, accord.first(), accord.second(), accord.firstBefore(), accord.desired())
                && RecruitsMilitary.apply(signatory(accord, accord.second(), accord.secondKingdom()), accord.second(), accord.first(), accord.secondBefore(), accord.desired());
        next = data.snapshot();
        if (!applied) {
            next.accords.put(accordId, accord.phase(TreatyPhase.PENDING, "Native application is incomplete or vetoed; inspect and explicitly retry.")); save(next);
            return "Accord pending; military peace is not operational.";
        }
        signatory(accord, accord.first(), accord.firstKingdom()); signatory(accord, accord.second(), accord.secondKingdom()); accordControl(accord);
        next.accords.put(accordId, accord.phase(TreatyPhase.ACTIVE, "Both native directions confirmed in the provider save."));
        next.decisions.put(accord.settlement(), new Decision(accord.settlement(), accord.id(), accord.sovereignty(), accord.recognizedKingdom(), now(), accord.controlSequence()));
        next.campaigns.replaceAll((id, c) -> c.settlement().equals(accord.settlement()) && c.phase() != Phase.EXPIRED
                ? c.phase(Phase.RESOLVED, "Settlement decision ratified by both governments.") : c);
        notice(next, accord.settlement(), "accord_operational", requester.getUUID(), accord.sovereignty() + " | " + accord.recognizedKingdom()); save(next);
        return "Accord operational. Recognized sovereignty is " + accord.sovereignty() + "; civic identity and native ownership are preserved.";
    }
    private void accordControl(Accord accord) {
        var current = bound(accord.settlement());
        require(current.claim().equals(accord.claim()) && current.owner().equals(accord.controller()), "Physical control changed; negotiate fresh settlement terms.");
    }
    public String withdraw(ServerPlayer actor, UUID campaignId) {
        String faction = authority(actor, Politics.Permission.PROPOSE); var next = data.snapshot(); var c = next.campaigns.get(campaignId);
        require(c != null && c.attacker().equals(faction) && c.commander().equals(actor.getUUID()), "Only this campaign's commander may withdraw.");
        require(c.phase() != Phase.RESOLVED, "Campaign already closed.");
        next.campaigns.put(campaignId, c.phase(Phase.SUSPENDED, "Withdrawal requested; native confirmation pending.")); save(next);
        boolean confirmed = RecruitsMilitary.apply(actor, c.attacker(), c.defender(), Relation.ENEMY, Relation.NEUTRAL);
        next = data.snapshot();
        next.campaigns.put(campaignId, c.phase(confirmed ? Phase.RESOLVED : Phase.SUSPENDED,
                confirmed ? "Own military declaration withdrawn; counterpart diplomacy remains native-owned." : "Native withdrawal pending; explicit retry required.")); save(next);
        return next.campaigns.get(campaignId).reason();
    }
    public String reject(ServerPlayer actor, UUID accordId, long expectedRevision) {
        String side = authority(actor, Politics.Permission.RATIFY); var next = data.snapshot(); var a = next.accords.get(accordId);
        require(next.revision == expectedRevision && a != null && (side.equals(a.first()) || side.equals(a.second())), "Accord changed or you lack signing authority.");
        require(a.phase() != TreatyPhase.ACTIVE, "An operational agreement must be replaced by a new negotiated decision or native diplomacy change.");
        next.accords.put(accordId, a.phase(TreatyPhase.REJECTED, "Negotiation rejected; any already applied native direction is retained for explicit reconciliation.")); save(next);
        return "Negotiation rejected. Native relations were not rewritten.";
    }
    private ServerPlayer signatory(Accord accord, String side, String kingdom) {
        var player = server.getPlayerList().getPlayer(accord.signatures().get(side));
        require(player != null && RecruitsMilitary.commands(player, side) && politics().authorized(player, kingdom, Politics.Permission.RATIFY),
                "Both native leaders must be online and retain their ratification mandates."); return player;
    }
    @Override public Optional<WarfareApi.Control> control(UUID settlement) {
        if (!server.isSameThread()) throw new IllegalStateException("Control query requires server thread");
        var binding = controls().binding(settlement); if (binding.isEmpty()) return Optional.empty();
        var value = binding.get(); var decision = data.snapshot().decisions.get(settlement);
        String civic = kingdoms().getSettlement(settlement).map(s -> s.kingdomId().toString()).orElse(value.sovereign());
        String recognized = decision == null ? value.sovereign() : decision.kingdom();
        String status = decision == null ? Sovereignty.RECOGNIZED.name() : decision.status().name();
        boolean siege = value.condition() == ControlState.Condition.UNDER_SIEGE;
        String availability = !data.writable() ? "read_only" : !WarfareConfig.ENABLED.get() ? "disabled"
                : controls().verifiedBinding(settlement).isPresent() ? "available" : "provider_pending";
        return Optional.of(new WarfareApi.Control(settlement, civic, recognized, value.owner(), status, siege, value.sequence(), availability));
    }
    @Override public Optional<WarfareApi.Control> control(ServerPlayer viewer, UUID settlement) {
        if (viewer.getServer() != server || !SettlementKnowledge.get(server).visible(viewer, settlement)) return Optional.empty();
        if (!viewer.hasPermissions(2) && kingdoms().getSettlementAt(viewer.serverLevel(), viewer.blockPosition()).filter(s -> s.id().equals(settlement)).isEmpty()) return Optional.empty();
        return control(settlement);
    }
    @Override public boolean reliefEligible(ServerPlayer player, UUID settlement) {
        return WarfareConfig.ENABLED.get() && WarfareConfig.CONTRACTS.get() && player.getServer() == server && data.writable()
                && SettlementKnowledge.get(server).visible(player, settlement) && control(settlement).filter(c -> c.availability().equals("available")).isPresent();
    }
    @Override public boolean safeConduct(ServerPlayer player, UUID settlement) {
        if (!reliefEligible(player, settlement)) return false;
        String faction = player.getTeam() == null ? "" : player.getTeam().getName();
        return data.snapshot().accords.values().stream().anyMatch(a -> a.settlement().equals(settlement) && operational(a)
                && (faction.isEmpty() || neutralTo(faction, a.first()) && neutralTo(faction, a.second())));
    }
    private boolean neutralTo(String visitor, String side) {
        if (visitor.equals(side)) return true;
        try {
            if (RecruitsMilitary.faction(server, visitor).isEmpty()) return true;
            return RecruitsMilitary.relation(server, visitor, side) != Relation.ENEMY
                    && RecruitsMilitary.relation(server, side, visitor) != Relation.ENEMY;
        } catch (IllegalArgumentException unavailable) { return false; }
    }
    private boolean operational(Accord accord) {
        if (accord.phase() != TreatyPhase.ACTIVE || now() >= accord.expires() || !WarfareConfig.MILITARY.get()) return false;
        try {
            return RecruitsMilitary.relation(server, accord.first(), accord.second()) == accord.desired()
                    && RecruitsMilitary.relation(server, accord.second(), accord.first()) == accord.desired()
                    && RecruitsMilitary.saved(server, accord.first(), accord.second(), accord.desired())
                    && RecruitsMilitary.saved(server, accord.second(), accord.first(), accord.desired());
        } catch (IllegalArgumentException unavailable) { return false; }
    }
    public boolean siegeAllowed(UUID claim, List<String> attackers) {
        var binding = controls().bindings().stream().filter(b -> b.claim().equals(claim)).findFirst();
        if (binding.isEmpty() || !WarfareConfig.ENABLED.get() || !WarfareConfig.MILITARY.get()) return true;
        if (!data.writable()) return false;
        var b = binding.get();
        if (WarfareConfig.PROTECT_CAPITALS.get() && politics().isCapital(b.settlement())) return false;
        if (WarfareConfig.REQUIRE_DEFENDER_ONLINE.get()) {
            var defender = RecruitsMilitary.faction(server, b.owner());
            if (defender.isEmpty() || server.getPlayerList().getPlayer(defender.get().leader()) == null) return false;
        }
        if (data.snapshot().accords.values().stream().anyMatch(a -> a.settlement().equals(b.settlement()) && operational(a))) return false;
        return !attackers.isEmpty() && attackers.stream().allMatch(attacker -> data.snapshot().campaigns.values().stream()
                .anyMatch(c -> c.claim().equals(claim) && c.attacker().equals(attacker) && c.defender().equals(b.owner())
                        && now() >= c.noticeUntil() && now() < c.expires() && (c.phase() == Phase.ACTIVE || c.phase() == Phase.NOTICE)
                        && RecruitsMilitary.relation(server, attacker, b.owner()) == Relation.ENEMY
                        && RecruitsMilitary.saved(server, attacker, b.owner(), Relation.ENEMY)));
    }
    public void tick() {
        if (!WarfareConfig.ENABLED.get() || !data.writable()) return;
        var next = data.snapshot(); boolean changed = false;
        var campaigns = new ArrayList<>(next.campaigns.entrySet());
        var accords = new ArrayList<>(next.accords.entrySet());
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), campaigns.size()); i++) {
            var entry = campaigns.get(Math.floorMod(campaignCursor++, campaigns.size())); var c = entry.getValue();
            if (c.phase() == Phase.RESOLVED || c.phase() == Phase.EXPIRED) continue;
            Phase phase = c.phase(); String reason = c.reason();
            if (now() >= c.expires()) { phase = Phase.EXPIRED; reason = "Campaign expired; native forces retain their native orders."; }
            else {
                var control = controls().binding(c.settlement());
                if (control.isPresent() && control.get().owner().equals(c.attacker()) && controls().verifiedBinding(c.settlement()).isPresent()) {
                    phase = Phase.OCCUPIED; reason = "Native control confirmed; occupation awaits a separate settlement decision.";
                } else if (phase == Phase.NOTICE && now() >= c.noticeUntil()) {
                    try { if (RecruitsMilitary.relation(server, c.attacker(), c.defender()) == Relation.ENEMY && RecruitsMilitary.saved(server, c.attacker(), c.defender(), Relation.ENEMY))
                        { phase = Phase.ACTIVE; reason = "Notice elapsed; native troop detection may begin a siege."; } }
                    catch (IllegalArgumentException unavailable) { phase = Phase.SUSPENDED; reason = "Native provider unavailable; campaign retained."; }
                }
            }
            if (phase != c.phase()) { next.campaigns.put(entry.getKey(), c.phase(phase, reason)); changed = true; }
        }
        for (int i = 0; i < Math.min(WarfareConfig.BUDGET.get(), accords.size()); i++) {
            var entry = accords.get(Math.floorMod(accordCursor++, accords.size()));
            var a = entry.getValue();
            if (a.phase() == TreatyPhase.ACTIVE) {
                try {
                    if (now() >= a.expires()) { next.accords.put(a.id(), a.phase(TreatyPhase.EXPIRED, "Agreement term elapsed; native relations are not rewritten.")); changed = true; }
                    else if (RecruitsMilitary.relation(server, a.first(), a.second()) != a.desired() || RecruitsMilitary.relation(server, a.second(), a.first()) != a.desired()) {
                        next.accords.put(a.id(), a.phase(TreatyPhase.BREACHED, "Native diplomacy changed; no automatic relation write-back.")); changed = true;
                    }
                } catch (IllegalArgumentException unavailable) { /* Native absence suspends operational queries without discarding terms. */ }
            }
        }
        if (changed) save(next);
    }
    private boolean viewable(ServerPlayer viewer,UUID settlement) {
        require(server.isSameThread()&&viewer.getServer()==server&&!viewer.hasDisconnected(),"Connected server player required.");
        return SettlementKnowledge.get(server).visible(viewer,settlement)&&(viewer.hasPermissions(2)||kingdoms().getSettlementAt(viewer.serverLevel(),viewer.blockPosition()).filter(s->s.id().equals(settlement)).isPresent());
    }
    public List<Campaign> campaigns(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,"Invalid campaign page.");return data.snapshot().campaigns.values().stream().filter(c->viewable(viewer,c.settlement())).sorted(Comparator.comparing(Campaign::id)).skip(offset).limit(limit).toList();
    }
    public List<Accord> accords(ServerPlayer viewer,int offset,int limit) {
        require(offset>=0&&offset<=4096&&limit>0&&limit<=64,"Invalid accord page.");return data.snapshot().accords.values().stream().filter(a->viewable(viewer,a.settlement())).sorted(Comparator.comparing(Accord::id)).skip(offset).limit(limit).toList();
    }
    public List<String> page(ServerPlayer viewer, UUID settlement) {
        require(viewer.getServer() == server, "Wrong server."); known(viewer, settlement);
        if (!viewer.hasPermissions(2) && kingdoms().getSettlementAt(viewer.serverLevel(), viewer.blockPosition()).filter(s -> s.id().equals(settlement)).isEmpty())
            return List.of("Visit this settlement to inspect its campaigns and negotiations.");
        var result = new ArrayList<String>(); result.add("Campaign revision: " + revision());
        control(viewer, settlement).ifPresent(c -> result.add("Sovereignty: " + c.autonomy() + " | " + c.recognizedKingdom() + " | " + c.availability()));
        var state = data.snapshot();
        state.campaigns.values().stream().filter(c -> c.settlement().equals(settlement)).skip(Math.max(0, state.campaigns.values().stream().filter(c -> c.settlement().equals(settlement)).count() - 6))
                .forEach(c -> result.add(c.id() + " | " + c.goal() + " | " + c.phase() + " | " + c.reason()));
        state.accords.values().stream().filter(a -> a.settlement().equals(settlement)).limit(6)
                .forEach(a -> result.add(a.id() + " | " + a.phase() + " | " + a.sovereignty() + " | " + a.reason()));
        return List.copyOf(result);
    }
}
