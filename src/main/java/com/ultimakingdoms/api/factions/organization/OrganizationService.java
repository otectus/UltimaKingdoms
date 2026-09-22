package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationService {
    int apiVersion();

    List<OrganizationDefinitionView> definitions();

    Optional<OrganizationDefinitionView> definition(ResourceLocation organizationId);

    /** Returns only the authenticated player's own organizational state. */
    OrganizationPlayerSnapshot ownSnapshot(ServerPlayer player);

    OrganizationExplanation explainOwn(ServerPlayer player, ResourceLocation organizationId,
                                       ResourceLocation servicePermission);

    OrganizationOperationResult join(ServerPlayer actor, ResourceLocation organizationId);

    OrganizationOperationResult leave(ServerPlayer actor, ResourceLocation organizationId);

    /**
     * Trusted server integration entry point. The service derives a per-effect id from the supplied
     * receipt and rejects malformed, oversized, conflicting, or off-thread submissions.
     */
    OrganizationDeedResult recordDeed(UUID playerId, ResourceLocation organizationId, UUID receiptId,
                                      String questId, int credit, Optional<UUID> settlementId);

    /** Bounded private history; old providers may return no entries. */
    default List<OrganizationHistoryEntry> ownHistory(ServerPlayer player,int limit) { return List.of(); }

    /** Resolves a historical ID through bounded, durable redirects. */
    default Optional<ResourceLocation> resolve(ResourceLocation organizationId) { return Optional.empty(); }
    /** Installed charter templates only; dynamic organizations are excluded. */
    default List<OrganizationDefinitionView> foundingTemplates() { return definitions(); }
    /** Public durable lifecycle records, including tombstones and redirects. */
    default List<OrganizationLifecycleView> lifecycles(ServerPlayer viewer) { return List.of(); }
    /** Proposals visible to a party, an active source member, or a current charter authority. */
    default List<OrganizationMergeView> merges(ServerPlayer viewer) { return List.of(); }
    /** Accepted source commissions visible only to a current novation authority after both parties consent. */
    default List<OrganizationObligation> mergeObligations(ServerPlayer viewer,UUID mergeId) { return List.of(); }
    default Optional<OrganizationLifecycleView> lifecycle(ResourceLocation organizationId) { return Optional.empty(); }
    default Optional<OrganizationMergeView> merge(UUID mergeId) { return Optional.empty(); }
    default List<OrganizationObligation> obligations(ResourceLocation organizationId) { return List.of(); }
    default OrganizationLifecycleResult found(ServerPlayer actor,ResourceLocation organizationId,ResourceLocation templateId,
                                              String sponsorKingdom,String displayName,long expectedRevision) {
        return unsupported();
    }
    default OrganizationLifecycleResult proposeMerge(ServerPlayer actor,ResourceLocation source,ResourceLocation target,long expectedRevision) { return unsupported(); }
    default OrganizationLifecycleResult consentMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) { return unsupported(); }
    default OrganizationLifecycleResult optOutMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) { return unsupported(); }
    default OrganizationLifecycleResult finalizeMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) { return unsupported(); }
    default OrganizationLifecycleResult novateMergeObligation(ServerPlayer actor,UUID mergeId,UUID obligationId,long expectedRevision) { return unsupported(); }
    default OrganizationLifecycleResult dissolve(ServerPlayer actor,ResourceLocation organizationId,long expectedRevision) { return unsupported(); }

    private OrganizationLifecycleResult unsupported() {
        return new OrganizationLifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,revision(),"organization.lifecycle_unavailable",
                Optional.empty(),Optional.empty(),List.of());
    }

    long revision();
}
