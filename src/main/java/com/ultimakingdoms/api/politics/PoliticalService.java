package com.ultimakingdoms.api.politics;

import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Server-thread only. Every query is viewer-authorized; no private state in civic context. */
public interface PoliticalService {
    Politics.Result execute(ServerPlayer actor, Politics.Request request);
    Politics.Page page(ServerPlayer viewer, UUID requestId, String kingdom, String tab, int offset);
    /** Durable, newest-first history. Implementations must filter every entry for the viewer. */
    default java.util.List<PoliticalFact> history(ServerPlayer viewer, int offset, int limit) { return java.util.List.of(); }
    /** Trusted server state read. This is not a player discovery or location grant. */
    default java.util.Optional<Politics.Government> government(String kingdom) { return java.util.Optional.empty(); }
    /** Actor-owned durable request recovery; mismatched actors receive no receipt. */
    default java.util.Optional<Politics.Result> receipt(ServerPlayer actor, UUID requestId) { return java.util.Optional.empty(); }
    default Politics.Result adoptTransitionRule(ServerPlayer actor,UUID requestId,long expectedRevision,String kingdom,PoliticalTransition.Rule rule) { return unavailable(); }
    default Politics.Result openElection(ServerPlayer actor,UUID requestId,long expectedRevision,String kingdom,java.util.List<UUID> candidates) { return unavailable(); }
    default Politics.Result castBallot(ServerPlayer actor,UUID requestId,long expectedRevision,UUID election,UUID candidate) { return unavailable(); }
    default Politics.Result closeElection(ServerPlayer actor,UUID requestId,long expectedRevision,UUID election) { return unavailable(); }
    default Politics.Result appointRegent(ServerPlayer actor,UUID requestId,long expectedRevision,String kingdom,UUID regent) { return unavailable(); }
    default Politics.Result endRegency(ServerPlayer actor,UUID requestId,long expectedRevision,String kingdom) { return unavailable(); }
    default java.util.Optional<PoliticalTransition.ElectionView> election(ServerPlayer viewer,UUID election) { return java.util.Optional.empty(); }
    /** Bounded, viewer-authorized election browser; never returns individual ballots. */
    default java.util.List<PoliticalTransition.ElectionView> elections(ServerPlayer viewer,int offset,int limit) { return java.util.List.of(); }
    default java.util.Optional<PoliticalTransition.Rule> transitionRule(String kingdom) { return java.util.Optional.empty(); }
    default java.util.Optional<PoliticalTransition.Regency> regency(String kingdom) { return java.util.Optional.empty(); }
    private Politics.Result unavailable() { return new Politics.Result(false,revision(),"Constitutional transitions unavailable",""); }
    /** Server integration read; no packet should expose its location without viewer authorization. */
    default java.util.Optional<InstitutionView> institution(UUID institutionId) { return java.util.Optional.empty(); }
    /** Revalidates the existing government/office authority; never grants it from guild rank. */
    default boolean canRecognize(ServerPlayer actor, UUID settlement) { return false; }
    /** Revalidates a live kingdom-wide mandate; integrations cannot infer authority from military membership. */
    default boolean authorized(ServerPlayer actor, String kingdom, Politics.Permission permission) { return false; }
    /** Trusted server policy query; never a discovery grant or a location response. */
    default boolean isCapital(UUID settlement) { return false; }
    default java.util.List<InstitutionView> knownInstitutions(ServerPlayer viewer, int offset, int limit) { return java.util.List.of(); }
    default AgreementClauseView clause(String firstKingdom, String secondKingdom, Politics.Clause clause) {
        return new AgreementClauseView(clause, AgreementClauseView.Status.UNAVAILABLE,
                java.util.Optional.empty(), revision(), 0, "civic.agreement_unavailable");
    }
    long revision();
}
