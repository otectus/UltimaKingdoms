package com.ultimakingdoms.api.civic;

import java.util.List;
import java.util.UUID;

/** Private interaction context, not a public-chat broadcast. No remote locations or hidden sympathies. */
public record CivicContactContext(UUID npc, String organization, String nameKey, String role,
                                  boolean servicesAvailable, boolean introductionQualified,
                                  boolean commissionQualified, List<String> reasons, List<String> commissionReasons,
                                  long stateRevision, long policyRevision) {
    public CivicContactContext { reasons=List.copyOf(reasons); commissionReasons=List.copyOf(commissionReasons); }
}
