package com.ultimakingdoms.api.progression;

/** Server-only, actor-private evidence; unavailable is distinct from a failed qualification. */
public record ProgressionResult(Status status,boolean matches,String reason) {
    public enum Status { AVAILABLE, ABSENT, UNSUPPORTED, UNAVAILABLE }
    public ProgressionResult { if(status!=Status.AVAILABLE&&matches)throw new IllegalArgumentException("Unavailable evidence cannot qualify"); }
}
