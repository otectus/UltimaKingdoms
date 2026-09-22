package com.ultimakingdoms.api.civic;

import java.util.Optional;
import java.util.UUID;

/** Private actor-only response. Destination exists only after durable discovery. */
public record CivicActionResult(boolean success, String reason, Optional<UUID> settlement) {
    public CivicActionResult { settlement=java.util.Objects.requireNonNull(settlement); }
}
