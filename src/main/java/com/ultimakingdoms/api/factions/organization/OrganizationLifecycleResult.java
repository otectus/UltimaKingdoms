package com.ultimakingdoms.api.factions.organization;

import java.util.List;
import java.util.Optional;

public record OrganizationLifecycleResult(Status status, long revision, String reason,
                                          Optional<OrganizationLifecycleView> organization,
                                          Optional<OrganizationMergeView> merge,
                                          List<OrganizationObligation> obligations) {
    public enum Status { APPLIED, PENDING_CONSENT, OBLIGATIONS_BLOCKING, NO_CHANGE, STALE_REVISION,
        UNAUTHORIZED, UNKNOWN, CONFLICT, READ_ONLY, LIMIT_REACHED, DURABILITY_FAILED }
    public OrganizationLifecycleResult {
        organization=organization==null?Optional.empty():organization;merge=merge==null?Optional.empty():merge;
        obligations=List.copyOf(obligations);reason=reason==null?"":reason;
    }
}
