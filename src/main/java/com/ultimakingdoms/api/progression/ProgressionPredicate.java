package com.ultimakingdoms.api.progression;

import java.util.Objects;

/** Read-only qualifications. None grants faction rank or changes provider progression. */
public record ProgressionPredicate(Kind kind,String subject,int minimum) {
    public enum Kind { SKILL_LEVEL, DEITY, RACE, LORE_COLLECTED }
    public ProgressionPredicate {
        Objects.requireNonNull(kind);
        if(subject==null||subject.isBlank()||subject.length()>128||minimum<0||minimum>1_000_000)
            throw new IllegalArgumentException("Invalid progression predicate");
    }
}
