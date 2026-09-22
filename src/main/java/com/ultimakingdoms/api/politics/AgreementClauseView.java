package com.ultimakingdoms.api.politics;

import java.util.Optional;
import java.util.UUID;

/** Server integration query; contains neither private proposals nor settlement coordinates. */
public record AgreementClauseView(Politics.Clause clause, Status status, Optional<UUID> agreement,
                                 long politicalRevision, long expiresAt, String reason) {
    public enum Status { ACTIVE, UNAVAILABLE, NOT_AGREED }
    public boolean operational() { return status == Status.ACTIVE; }
}
