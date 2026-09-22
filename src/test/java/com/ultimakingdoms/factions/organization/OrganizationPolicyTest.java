package com.ultimakingdoms.factions.organization;

import com.ultimakingdoms.api.factions.organization.OrganizationDeedRule;
import com.ultimakingdoms.api.factions.organization.OrganizationExplanation;
import com.ultimakingdoms.api.factions.organization.OrganizationRankView;
import com.ultimakingdoms.api.factions.organization.OrganizationServiceRule;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationPolicyTest {
    private static final ResourceLocation ORGANIZATION = new ResourceLocation("test", "guild");
    private static final ResourceLocation MEMBER = new ResourceLocation("test", "member");
    private static final ResourceLocation SERVICE = new ResourceLocation("test", "service");

    @Test
    void activeMembershipStandingAndDeedsAreIndependentPermissionRequirements() {
        OrganizationDefinition definition = definition();
        OrganizationSavedData.MembershipRecord member = new OrganizationSavedData.MembershipRecord(
                UUID.randomUUID(), ORGANIZATION);
        member.standing = 100;
        member.deedCount = 2;

        OrganizationExplanation inactive = OrganizationPolicy.explain(definition, member, SERVICE, 4L);
        assertEquals(OrganizationExplanation.Decision.DENY, inactive.decision());
        assertEquals(List.of("organization.active_membership_required"), inactive.reasons());

        member.active = true;
        member.everJoined = true;
        OrganizationExplanation missingDeed = OrganizationPolicy.explain(definition, member, SERVICE, 5L);
        assertEquals(OrganizationExplanation.Decision.DENY, missingDeed.decision());
        assertTrue(missingDeed.reasons().contains("organization.minimum_deeds_required:3"));

        member.deedCount = 3;
        OrganizationExplanation allowed = OrganizationPolicy.explain(definition, member, SERVICE, 6L);
        assertEquals(OrganizationExplanation.Decision.ALLOW, allowed.decision());
        assertEquals(Optional.of(MEMBER), allowed.effectiveRank());
        assertEquals(List.of("organization.requirements_satisfied"), allowed.reasons());
    }

    @Test
    void neutralServiceRequiresItsOwnThresholdsAndNeverGrantsRank() {
        var base=definition();
        var definition=new OrganizationDefinition(base.id(),base.kind(),base.military(),base.nameKey(),base.descriptionKey(),
                base.exclusiveGroup(),base.conflicts(),base.ranks(),
                List.of(new OrganizationServiceRule(SERVICE,MEMBER,20L,3,100L,5)),base.deeds());
        var member=new OrganizationSavedData.MembershipRecord(UUID.randomUUID(),ORGANIZATION);
        member.standing=100;member.deedCount=4;
        assertEquals(OrganizationExplanation.Decision.DENY,OrganizationPolicy.explain(definition,member,SERVICE,1).decision());
        member.deedCount=5;
        var allowed=OrganizationPolicy.explain(definition,member,SERVICE,2);
        assertEquals(OrganizationExplanation.Decision.ALLOW,allowed.decision());
        assertTrue(allowed.effectiveRank().isEmpty());
        member.everJoined=true; // Resignation retains exactly the same independent route.
        assertEquals(OrganizationExplanation.Decision.ALLOW,OrganizationPolicy.explain(definition,member,SERVICE,3).decision());
        assertEquals(OrganizationExplanation.Decision.DENY,OrganizationPolicy.explain(definition,null,SERVICE,4).decision());
    }

    private static OrganizationDefinition definition() {
        return new OrganizationDefinition(ORGANIZATION, "guild", false, "organization.test.guild",
                "organization.test.guild.description", Optional.empty(), Set.of(),
                List.of(new OrganizationRankView(MEMBER, 0L, Set.of(SERVICE))),
                List.of(new OrganizationServiceRule(SERVICE, MEMBER, 20L, 3)),
                List.of(new OrganizationDeedRule(new ResourceLocation("test", "quest"), 20)));
    }
}
