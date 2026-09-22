package com.ultimakingdoms.warfare.contracts;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.politics.InstitutionView;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.civic.CivicRuntime;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.warfare.WarfareConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.*;

/** Owner side of R3 civilian native contracts. MCA Quests owns objectives, payment and item delivery. */
public final class CivilianContractService {
    public static final String TOKEN_PREFIX = "r3:";
    private static final long OFFER_LIFETIME = 1_200L;

    public record Result(boolean success, String reason, Optional<String> binding) {
        static Result deny(String reason) { return new Result(false, reason, Optional.empty()); }
        static Result ok(String binding) { return new Result(true, "warfare.contract_terms", Optional.of(binding)); }
    }

    public record ServiceProof(UUID contract, UUID providerEpoch, UUID receipt, UUID player, UUID giver,
                               UUID institution, UUID settlement, CivilianContractKind kind,
                               String organization, String recognizedKingdom, String nativeController,
                               String autonomy, long controlSequence, long completedAt) { }

    public enum CompletionResult { APPLIED, REPLAYED, NOT_OURS, INVALID, SAVE_FAILED }

    private CivilianContractService() { }

    /** Creates and saves immutable terms before asking the native provider to render them. */
    public static Result offer(ServerPlayer player, Entity giver, CivilianContractKind kind) {
        return offerScoped(player, giver, kind, null);
    }
    /** R4 scopes identify an existing scenario or obligation, never an arbitrary repeatable reward key. */
    public static Result offerScoped(ServerPlayer player, Entity giver, CivilianContractKind kind, UUID scope) {
        requireActor(player); Objects.requireNonNull(giver); Objects.requireNonNull(kind);
        MinecraftServer server = player.getServer();
        if (kind.scoped() != (scope != null)) return Result.deny("evolution.contract_scope_required");
        if (scope == null && (!WarfareConfig.ENABLED.get() || !WarfareConfig.CONTRACTS.get()))
            return Result.deny("warfare.contracts_disabled");
        CivilianContractData data = CivilianContractData.get(server);
        if (!data.writable()) return Result.deny("warfare.contract_ledger_read_only");

        var contact = CivicRuntime.get(server).speakerContext(player, giver);
        if (contact.isEmpty() || !contact.get().servicesAvailable())
            return Result.deny("warfare.recognized_contact_required");
        var settlement = UltimaKingdomsApi.get(server).getResidence(giver).orElse(null);
        if (settlement == null) return Result.deny("warfare.settlement_unavailable");
        if (scope != null && !com.ultimakingdoms.evolution.EvolutionContracts.available(player, scope, settlement.id(), kind))
            return Result.deny("evolution.contract_scope_unavailable");
        var politics = UltimaPoliticsApi.get(server);
        InstitutionView institution = institutionAt(player, settlement.id());
        if (institution == null || !institution.operational())
            return Result.deny("warfare.institution_unavailable");
        var provider = WarfareApi.get(server).orElse(null);
        if (scope == null && provider == null) return Result.deny("warfare.control_provider_unavailable");
        WarfareApi.Control control = scope != null ? new WarfareApi.Control(settlement.id(), settlement.kingdomId().toString(), settlement.kingdomId().toString(),
                "civilian", "RECOGNIZED", false, 0, "available") : provider.control(settlement.id()).orElse(null);
        if (!available(control)) return Result.deny("warfare.control_unavailable");

        // Relief is deliberately independent of guild membership and private legal suspicion.
        // The warfare provider may enforce only the public essential-aid policy here.
        if (kind == CivilianContractKind.RELIEF) {
            if (!provider.reliefEligible(player, settlement.id())) return Result.deny("warfare.relief_unavailable");
        } else if (requiresSafeConduct(kind) && !provider.safeConduct(player, settlement.id())) {
            return Result.deny("warfare.safe_conduct_required");
        }
        long now = server.overworld().getGameTime();
        var existing = data.active(player.getUUID(), settlement.id(), kind, scope, now).orElse(null);
        if (existing != null) {
            if (existing.status() == CivilianContractData.Status.OFFERED
                    && existing.giver().equals(giver.getUUID())) {
                String binding = binding(existing.id());
                return QuestCompletionBridge.openInstitutionalCommissions(player, giver, Set.of(kind.quest()), binding)
                        ? Result.ok(binding) : Result.deny("warfare.quest_provider_unavailable");
            }
            return Result.deny("warfare.contract_already_served");
        }

        UUID id = UUID.randomUUID();
        var contract = new CivilianContractData.Contract(id, player.getUUID(), giver.getUUID(), institution.id(),
                settlement.id(), contact.get().organization(), kind.id(), kind.quest().toString(),
                control.recognizedKingdom(), control.nativeController(), control.autonomy(), control.sequence(),
                politics.revision(), institution.revision(), now, now + OFFER_LIFETIME,
                CivilianContractData.Status.OFFERED, null, null, scope);
        if (!data.put(server, contract)) return Result.deny("warfare.contract_save_failed");
        String binding = binding(id);
        if (!QuestCompletionBridge.openInstitutionalCommissions(player, giver, Set.of(kind.quest()), binding))
            return Result.deny("warfare.quest_provider_unavailable");
        return Result.ok(binding);
    }

    /** Route from InstitutionalCommissionApi.Handler before the legacy UUID handler. */
    public static String validate(ServerPlayer player, Entity giver, ResourceLocation quest,
                                  String binding, boolean completing) {
        requireActor(player);
        UUID id = parse(binding).orElse(null);
        if (id == null) return "warfare.contract_binding_invalid";
        MinecraftServer server = player.getServer();
        var data = CivilianContractData.get(server);
        var contract = data.get(id).orElse(null);
        if (!data.writable() || contract != null && contract.scope() == null && (!WarfareConfig.ENABLED.get() || !WarfareConfig.CONTRACTS.get()))
            return "warfare.contract_unavailable";
        if (contract == null || giver == null || quest == null || !contract.player().equals(player.getUUID())
                || !contract.giver().equals(giver.getUUID()) || !contract.quest().equals(quest.toString()))
            return "warfare.contract_binding_invalid";
        if (giver.level() != player.level() || !giver.isAlive() || giver.distanceToSqr(player) > 144.0D
                || !player.hasLineOfSight(giver)) return "warfare.contract_giver_unavailable";
        if (completing && contract.status() != CivilianContractData.Status.ACCEPTED
                || !completing && contract.status() != CivilianContractData.Status.OFFERED)
            return "warfare.contract_binding_invalid";
        if (!completing && server.overworld().getGameTime() >= contract.offerExpires())
            return "warfare.contract_offer_expired";
        if (!completing && contract.scope() != null && !com.ultimakingdoms.evolution.EvolutionContracts.available(player, contract.scope(), contract.settlement(), CivilianContractKind.parse(contract.kind())))
            return "evolution.contract_scope_unavailable";

        var provider = WarfareApi.get(server).orElse(null);
        if (contract.scope() == null && provider == null) return "warfare.control_provider_unavailable";
        var control = contract.scope() != null ? new WarfareApi.Control(contract.settlement(), contract.recognizedKingdom(), contract.recognizedKingdom(),
                contract.nativeController(), contract.autonomy(), false, 0, "available") : provider.control(contract.settlement()).orElse(null);
        if (!available(control)) return "warfare.control_unavailable";
        var place = UltimaPoliticsApi.get(server).institution(contract.institution()).orElse(null);
        if (place == null || !place.operational() || !place.settlement().equals(contract.settlement()))
            return "warfare.institution_unavailable";
        var residence = UltimaKingdomsApi.get(server).getResidence(giver);
        if (residence.isEmpty() || !residence.get().id().equals(contract.settlement()))
            return "warfare.contract_giver_unavailable";
        var contact = CivicRuntime.get(server).speakerContext(player, giver);
        if (contact.isEmpty() || !contact.get().servicesAvailable()
                || !contact.get().organization().equals(contract.organization()))
            return "warfare.recognized_contact_required";

        if (!completing) {
            // Native acceptance is the debit fence. A stale offer is rejected before any objective can consume items.
            if (UltimaPoliticsApi.get(server).revision() != contract.politicalRevision()
                    || place.revision() != contract.institutionRevision()
                    || control.sequence() != contract.controlSequence()
                    || !control.recognizedKingdom().equals(contract.recognizedKingdom())
                    || !control.nativeController().equals(contract.nativeController())
                    || !control.autonomy().equals(contract.autonomy()))
                return "warfare.contract_terms_changed";
            CivilianContractKind kind = CivilianContractKind.parse(contract.kind());
            if (kind == CivilianContractKind.RELIEF) {
                if (!provider.reliefEligible(player, contract.settlement())) return "warfare.relief_unavailable";
            } else if (requiresSafeConduct(kind) && !provider.safeConduct(player, contract.settlement())) {
                return "warfare.safe_conduct_required";
            }
        }
        // Guild departure and later private suspicion are intentionally absent from completion validation.
        if (!QuestCompletionBridge.ensureInstitutionalRecipient(player))
            return "warfare.quest_provider_unavailable";
        return "";
    }

    public static boolean accepted(ServerPlayer player, Entity giver, ResourceLocation quest,
                                   String binding, UUID instance) {
        requireActor(player);
        if (instance == null || !validate(player, giver, quest, binding, false).isEmpty()) return false;
        var data = CivilianContractData.get(player.getServer());
        var contract = data.get(parse(binding).orElseThrow()).orElse(null);
        return contract != null && data.put(player.getServer(), contract.accepted(instance));
    }

    public static boolean cancelled(ServerPlayer player, ResourceLocation quest, String binding, UUID instance) {
        requireActor(player);
        UUID id = parse(binding).orElse(null);
        if (id == null || quest == null || instance == null) return false;
        var data = CivilianContractData.get(player.getServer());
        var contract = data.get(id).orElse(null);
        if (contract == null) return true;
        if (contract.status() == CivilianContractData.Status.CANCELLED) return true;
        if (contract.status() != CivilianContractData.Status.ACCEPTED
                || !contract.player().equals(player.getUUID()) || !contract.quest().equals(quest.toString())
                || !instance.equals(contract.instance())) return false;
        return data.put(player.getServer(), contract.cancelled());
    }

    /** Called from the existing durable receipt transaction before provider acknowledgment. */
    public static CompletionResult recordCompletion(MinecraftServer server, UUID epoch, UUID receipt, UUID player) {
        requireServer(server); Objects.requireNonNull(epoch); Objects.requireNonNull(receipt); Objects.requireNonNull(player);
        var effect = QuestCompletionBridge.committedInstitutionalEffect(server, epoch, receipt, player).orElse(null);
        if (effect == null || !handles(effect.binding())) return CompletionResult.NOT_OURS;
        var data = CivilianContractData.get(server);
        UUID id = parse(effect.binding()).orElse(null);
        var contract = id == null ? null : data.get(id).orElse(null);
        if (contract == null || !contract.player().equals(player) || !contract.giver().equals(effect.giver())
                || !contract.quest().equals(effect.quest()) || !receipt.equals(contract.instance()))
            return CompletionResult.INVALID;
        if (contract.status() == CivilianContractData.Status.COMPLETED)
            return contract.completion().providerEpoch().equals(epoch)
                    && contract.completion().receipt().equals(receipt) ? CompletionResult.REPLAYED : CompletionResult.INVALID;
        if (contract.status() != CivilianContractData.Status.ACCEPTED) return CompletionResult.INVALID;
        return data.put(server, contract.completed(epoch, receipt, server.overworld().getGameTime()))
                ? CompletionResult.APPLIED : CompletionResult.SAVE_FAILED;
    }

    /** Durable, receipt-backed proof for campaign/diplomacy reads. It grants no effect by itself. */
    public static Optional<ServiceProof> proof(MinecraftServer server, UUID player, UUID settlement,
                                               CivilianContractKind kind) {
        requireServer(server);
        return CivilianContractData.get(server).completed(player, settlement, kind).map(CivilianContractService::proof);
    }
    public static Optional<ServiceProof> scopedProof(MinecraftServer server, UUID player, UUID settlement, CivilianContractKind kind, UUID scope) {
        requireServer(server);
        return CivilianContractData.get(server).completed(player, settlement, kind, scope).map(CivilianContractService::proof);
    }
    public static boolean hasUnsettledOrganization(MinecraftServer server, String organization) {
        requireServer(server); return CivilianContractData.get(server).hasUnsettledOrganization(organization);
    }

    public static boolean hasService(MinecraftServer server, UUID player, UUID settlement,
                                     CivilianContractKind kind) {
        return proof(server, player, settlement, kind).isPresent();
    }

    public static boolean handles(String binding) { return parse(binding).isPresent(); }
    public static String binding(UUID id) { return TOKEN_PREFIX + Objects.requireNonNull(id); }

    private static Optional<UUID> parse(String value) {
        if (value == null || !value.startsWith(TOKEN_PREFIX)) return Optional.empty();
        try {
            String raw = value.substring(TOKEN_PREFIX.length()); UUID id = UUID.fromString(raw);
            return raw.equals(id.toString()) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException failure) { return Optional.empty(); }
    }

    private static ServiceProof proof(CivilianContractData.Contract c) {
        var done = c.completion();
        return new ServiceProof(c.id(), done.providerEpoch(), done.receipt(), c.player(), c.giver(), c.institution(),
                c.settlement(), CivilianContractKind.parse(c.kind()), c.organization(), c.recognizedKingdom(),
                c.nativeController(), c.autonomy(), c.controlSequence(), done.completedAt());
    }

    private static InstitutionView institutionAt(ServerPlayer player, UUID settlement) {
        var politics = UltimaPoliticsApi.get(player.getServer());
        for (int offset = 0; offset <= 4096; offset += 32) {
            var page = politics.knownInstitutions(player, offset, 32);
            var match = page.stream().filter(i -> i.settlement().equals(settlement)).sorted(Comparator.comparing(InstitutionView::id)).findFirst();
            if (match.isPresent()) return match.get();
            if (page.size() < 32) break;
        }
        return null;
    }

    private static boolean available(WarfareApi.Control control) {
        return control != null && "available".equals(control.availability());
    }

    private static boolean requiresSafeConduct(CivilianContractKind kind) {
        return kind == CivilianContractKind.ESCORT;
    }

    private static void requireActor(ServerPlayer player) {
        if (player == null || player.getServer() == null || !player.getServer().isSameThread())
            throw new IllegalStateException("Civilian contracts require server thread");
    }

    private static void requireServer(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Civilian contracts require server thread");
    }
}
