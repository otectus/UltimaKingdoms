package com.ultimakingdoms.factions.organization;

import com.ultimakingdoms.api.factions.organization.OrganizationExplanation;
import com.ultimakingdoms.api.factions.organization.OrganizationRankView;
import com.ultimakingdoms.api.factions.organization.OrganizationServiceRule;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class OrganizationPolicy {
    private OrganizationPolicy() {
    }

    static OrganizationExplanation explain(OrganizationDefinition definition,
                                           OrganizationSavedData.MembershipRecord member,
                                           ResourceLocation permission, long revision) {
        ResourceLocation organization = definition.id();
        Optional<OrganizationServiceRule> foundRule = definition.service(permission);
        if (foundRule.isEmpty()) {
            return result(organization, permission, OrganizationExplanation.Decision.UNAVAILABLE,
                    Optional.empty(), List.of("organization.service_unavailable"), revision);
        }
        Optional<OrganizationRankView> effectiveRank = member == null
                ? Optional.empty() : definition.rank(member.standing);
        if ((member == null || !member.active) && foundRule.get().neutralAlternative()) {
            OrganizationServiceRule rule = foundRule.get();
            long standing = member == null ? 0 : member.standing;
            int deeds = member == null ? 0 : member.deedCount;
            List<String> reasons = new ArrayList<>();
            if (standing < rule.neutralMinimumStanding()) reasons.add("organization.neutral_standing_required:" + rule.neutralMinimumStanding());
            if (deeds < rule.neutralRequiredDeeds()) reasons.add("organization.neutral_deeds_required:" + rule.neutralRequiredDeeds());
            boolean allowed = reasons.isEmpty();
            if (allowed) reasons.add("organization.neutral_requirements_satisfied");
            return result(organization, permission, allowed ? OrganizationExplanation.Decision.ALLOW : OrganizationExplanation.Decision.DENY,
                    Optional.empty(), reasons, revision);
        }
        if (member == null || !member.active) {
            return result(organization, permission, OrganizationExplanation.Decision.DENY,
                    effectiveRank.map(OrganizationRankView::id),
                    List.of("organization.active_membership_required"), revision);
        }
        OrganizationServiceRule rule = foundRule.get();
        List<String> reasons = new ArrayList<>();
        if (effectiveRank.isEmpty() || definition.rankIndex(effectiveRank.get().id())
                < definition.rankIndex(rule.minimumRank())) {
            reasons.add("organization.minimum_rank_required:" + rule.minimumRank());
        }
        if (member.standing < rule.minimumStanding()) {
            reasons.add("organization.minimum_standing_required:" + rule.minimumStanding());
        }
        if (member.deedCount < rule.requiredDeeds()) {
            reasons.add("organization.minimum_deeds_required:" + rule.requiredDeeds());
        }
        if (effectiveRank.isEmpty() || !effectiveRank.get().permissions().contains(permission)) {
            reasons.add("organization.rank_permission_missing:" + permission);
        }
        if (reasons.isEmpty()) reasons.add("organization.requirements_satisfied");
        return result(organization, permission,
                reasons.size() == 1 && reasons.get(0).equals("organization.requirements_satisfied")
                        ? OrganizationExplanation.Decision.ALLOW : OrganizationExplanation.Decision.DENY,
                effectiveRank.map(OrganizationRankView::id), reasons, revision);
    }

    private static OrganizationExplanation result(ResourceLocation organization, ResourceLocation permission,
                                                  OrganizationExplanation.Decision decision,
                                                  Optional<ResourceLocation> rank, List<String> reasons,
                                                  long revision) {
        return new OrganizationExplanation(organization, permission, decision, rank, reasons, revision);
    }
}
