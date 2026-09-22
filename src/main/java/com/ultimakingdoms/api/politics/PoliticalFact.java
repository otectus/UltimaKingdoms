package com.ultimakingdoms.api.politics;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, viewer-filtered account of one durably committed political fact. */
public record PoliticalFact(UUID id, UUID sourceReceiptId, long revision, long gameTime, String kingdom,
                            Type type, UUID actor, String recordId,
                            Set<String> affectedKingdoms, Set<UUID> affectedPlayers,
                            Visibility visibility, Correction correction,
                            UUID correctedBy, String summary) {
    public enum Type {
        GOVERNMENT_FOUNDED, CAPITAL_MOVED, OFFICE_APPOINTED, OFFICE_REMOVED,
        MANDATE_GRANTED, MANDATE_REVOKED, AGREEMENT_PROPOSED, AGREEMENT_SIGNED,
        AGREEMENT_DECLINED, AGREEMENT_WITHDRAWN, AGREEMENT_TERMINATED,
        PETITION_SUBMITTED, PETITION_REVIEWED, PETITION_APPROVED, PETITION_REJECTED, PETITION_WITHDRAWN,
        INSTITUTION_RECOGNIZED, INSTITUTION_REVALIDATED, INSTITUTION_SUSPENDED,
        HONOR_GRANTED, HONOR_REVOKED, LEADER_ABDICATED, SUCCESSOR_NAMED,
        SUCCESSION_CONFIRMED, HOUSE_UPDATED, AGREEMENT_EXPIRED, PETITION_EXPIRED,
        NPC_OFFICE_VACATED, TRANSITION_RULE_ADOPTED, ELECTION_OPENED, BALLOT_CAST,
        ELECTION_GRACE, ELECTION_RESOLVED, ELECTION_EXPIRED, REGENCY_APPOINTED, REGENCY_ENDED, REGENCY_EXPIRED
    }

    public enum Visibility { PUBLIC, PARTIES, PRIVATE }
    public enum Correction { CURRENT, SUPERSEDED, EXPIRED }

    public PoliticalFact {
        Objects.requireNonNull(id);
        Objects.requireNonNull(sourceReceiptId);
        if (revision < 0 || gameTime < 0) throw new IllegalArgumentException("Invalid political fact sequence");
        kingdom = Politics.bounded(kingdom, 128);
        Objects.requireNonNull(type);
        recordId = Politics.bounded(recordId, 128);
        affectedKingdoms = Set.copyOf(new LinkedHashSet<>(affectedKingdoms));
        affectedPlayers = Set.copyOf(new LinkedHashSet<>(affectedPlayers));
        if (affectedKingdoms.size() > 8 || affectedPlayers.size() > 16)
            throw new IllegalArgumentException("Political fact attribution is too large");
        affectedKingdoms.forEach(value -> Politics.bounded(value, 128));
        Objects.requireNonNull(visibility);
        Objects.requireNonNull(correction);
        summary = Politics.bounded(summary, 256);
    }
}
