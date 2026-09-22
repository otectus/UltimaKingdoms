package com.ultimakingdoms.evolution;

import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.contracts.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.UUID;

/** Validates every repeatable native contract against a live, saved R4 request. */
public final class EvolutionContracts {
    public static CivilianContractKind kind(EvolutionState.Outcome outcome) {
        return switch (outcome) {
            case AID -> CivilianContractKind.EVOLVING_AID;
            case MEDIATE -> CivilianContractKind.EVOLVING_MEDIATION;
            case NEGOTIATE -> CivilianContractKind.EVOLVING_AUTONOMY;
            default -> throw new IllegalArgumentException("This outcome has no native service contract");
        };
    }
    public static CivilianContractKind kind(ProtectionState.Duty duty) {
        return switch (duty) {
            case CIVIC_AID -> CivilianContractKind.EVOLVING_AID;
            case DEFENSE_ASSISTANCE -> CivilianContractKind.EVOLVING_DEFENSE;
            case MEDIATION -> CivilianContractKind.EVOLVING_MEDIATION;
        };
    }
    public static boolean available(ServerPlayer player, UUID scope, UUID settlement, CivilianContractKind kind) {
        var server = player.getServer();
        if (server == null || !server.isSameThread() || !SettlementKnowledge.get(server).visible(player, settlement)) return false;
        var evolution = EvolutionSavedData.get(server);
        var state = evolution.snapshot(); var scenario = state.scenarios.get(scope);
        long now = Math.max(state.clock, server.overworld().getGameTime());
        if (scenario != null) return evolution.writable() && state.enabled && state.eligible.contains(settlement)
                && (!scenario.terms().dramatic() || state.drama) && scenario.settlement().equals(settlement)
                && scenario.phase() == EvolutionState.Phase.OPEN && scenario.deadline() > now
                && (scenario.audience() == null || scenario.audience().equals(player.getUUID()))
                && EvolutionDefinitions.INSTANCE.snapshot().containsKey(scenario.templateId())
                && scenario.terms().outcomes().stream().anyMatch(o -> switch (o) { case AID, MEDIATE, NEGOTIATE -> kind(o) == kind; default -> false; });
        var protection = ProtectionSavedData.get(server); var p = protection.snapshot(); var obligation = p.obligations.get(scope);
        if (obligation == null) return false; now = Math.max(p.clock, server.overworld().getGameTime());
        return protection.writable() && obligation.status() == ProtectionState.Status.OPEN && obligation.deadline() > now
                && obligation.beneficiary().equals(settlement) && kind(obligation.duty()) == kind && p.pacts.get(obligation.pact()).effective(now);
    }
    public static String offer(ServerPlayer player, UUID scope, UUID giverId, CivilianContractKind kind) {
        Entity giver = player.serverLevel().getEntity(giverId);
        if (giver == null || !giver.isAlive() || giver.distanceToSqr(player) > 144 || !player.hasLineOfSight(giver))
            throw new IllegalArgumentException("Use a nearby loaded institutional contact");
        var result = CivilianContractService.offerScoped(player, giver, kind, scope);
        return result.success() ? "Native service offer opened; its accepted terms and payment belong to MCA Quests." : result.reason();
    }
    private EvolutionContracts() { }
}
