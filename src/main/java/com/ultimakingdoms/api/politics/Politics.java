package com.ultimakingdoms.api.politics;

import java.util.*;

/** Immutable, optional-provider-free political contracts. IDs are namespaced resource strings. */
public final class Politics {
    private Politics() { }
    public enum Permission { APPOINT, SEAT, PROPOSE, RATIFY, REVIEW, HONOR, RECOGNIZE, DELEGATE }
    public enum Kind { PLAYER, NPC }
    public enum Action { BOOTSTRAP, SEAT, APPOINT, REMOVE_OFFICE, DELEGATE, REVOKE,
        PROPOSE, SIGN, DECLINE, WITHDRAW, TERMINATE, PETITION, REVIEW, APPROVE, REJECT,
        RECOGNIZE, REVALIDATE, SUSPEND_RECOGNITION, HONOR, REVOKE_HONOR, ABDICATE, NAME_SUCCESSOR, SUCCEED, HOUSE,
        ADOPT_TRANSITION_RULE, OPEN_ELECTION, CAST_BALLOT, CLOSE_ELECTION, APPOINT_REGENT, END_REGENCY }
    public enum State { ACTIVE, INTERREGNUM, DORMANT }
    public enum AgreementState { PROPOSED, ACTIVE, DECLINED, WITHDRAWN, EXPIRED, TERMINATED }
    /** Explicit, frozen civilian effects. Legacy agreements acquire no new effects on upgrade. */
    public enum Clause { HOSPITALITY, INTRODUCTIONS, WORKSHOP_ACCESS, CIVIC_AID, INTELLIGENCE_EXCHANGE }
    public enum PetitionState { SUBMITTED, UNDER_REVIEW, APPROVED, DECLINED, WITHDRAWN, EXPIRED }
    public record Person(UUID id, Kind kind) {
        public Person { Objects.requireNonNull(id); Objects.requireNonNull(kind); }
    }
    /** All fields are bounded on construction; unused optional identifiers are empty strings. */
    public record Request(UUID requestId, long expectedRevision, Action action, String kingdom,
                          String definition, String target, Person person, String counterpart,
                          String text, String termsHash, int x, int y, int z) {
        public Request {
            Objects.requireNonNull(requestId); Objects.requireNonNull(action);
            kingdom = bounded(kingdom, 128); definition = bounded(definition, 128);
            target = bounded(target, 128); counterpart = bounded(counterpart, 128);
            text = bounded(text, 512); termsHash = bounded(termsHash, 64);
            if (expectedRevision < 0) throw new IllegalArgumentException("Invalid revision");
        }
    }
    public record Result(boolean success, long revision, String message, String recordId) { }
    public record Definition(int schema, String kind, String title, List<String> offices,
                             Set<Permission> permissions, List<String> requires, Set<String> buildingTypes,
                             long durationTicks, int maxOffices, boolean independentApproval,
                             String commissionPool, Set<Clause> clauses) {
        /** Retains the original API constructor and the ceremonial meaning of existing callers. */
        public Definition(int schema, String kind, String title, List<String> offices,
                          Set<Permission> permissions, List<String> requires, Set<String> buildingTypes,
                          long durationTicks, int maxOffices, boolean independentApproval, String commissionPool) {
            this(schema, kind, title, offices, permissions, requires, buildingTypes, durationTicks,
                    maxOffices, independentApproval, commissionPool, Set.of());
        }
        public Definition {
            if (schema != 1 || durationTicks < 0 || durationTicks > 2_400_000_000L || maxOffices < 1 || maxOffices > 16)
                throw new IllegalArgumentException("Invalid political definition limits");
            kind = bounded(kind, 40); title = bounded(title, 128);
            offices = List.copyOf(offices); permissions = Set.copyOf(permissions);
            requires = List.copyOf(requires); buildingTypes = Set.copyOf(buildingTypes);
            commissionPool = bounded(commissionPool, 128);
            clauses = clauses == null ? Set.of() : Set.copyOf(clauses);
            if (!clauses.isEmpty() && !kind.equals("agreement"))
                throw new IllegalArgumentException("Only agreements may declare operational clauses");
            if (offices.size() > 32 || requires.size() > 16 || buildingTypes.size() > 64)
                throw new IllegalArgumentException("Political definition too large");
        }
    }
    public record Office(String definitionId, Definition terms, Person holder, UUID settlement,
                         UUID appointedBy, long appointedAt) { }
    public record Government(String kingdom, UUID capital, String profileId, Definition constitution,
                             State state, Map<String, Office> offices, Map<UUID, Set<Permission>> mandates,
                             Person successor, long revision) {
        public Government {
            Objects.requireNonNull(kingdom); Objects.requireNonNull(capital);
            Objects.requireNonNull(constitution); Objects.requireNonNull(state);
            offices = Map.copyOf(offices);
            Map<UUID, Set<Permission>> copy = new LinkedHashMap<>();
            mandates.forEach((id, permissions) -> copy.put(id, Set.copyOf(permissions)));
            mandates = Map.copyOf(copy);
        }
    }
    public record Agreement(UUID id, String proposer, String recipient, String definitionId, Definition terms,
                            String explanation, String termsHash, Map<String, UUID> signatures,
                            AgreementState state, long proposedAt, long expiresAt, long revision) {
        public Agreement {
            Objects.requireNonNull(id); Objects.requireNonNull(terms); Objects.requireNonNull(state);
            if (proposer.equals(recipient)) throw new IllegalArgumentException("Agreement needs two kingdoms");
            explanation = bounded(explanation, 512); signatures = Map.copyOf(signatures);
        }
        public boolean involves(String kingdom) { return proposer.equals(kingdom) || recipient.equals(kingdom); }
        public String pairKey() { return proposer.compareTo(recipient) < 0 ? proposer + "|" + recipient : recipient + "|" + proposer; }
    }
    public record Building(String provider, String dimension, int villageId, int buildingId,
                           UUID settlement, String type, int x, int y, int z) { }
    public record Petition(UUID id, UUID requester, String kingdom, String definitionId, Definition terms,
                           UUID settlement, Person nominee, Building building, String counterpart,
                           String explanation, PetitionState state, String decision, UUID decidedBy,
                           long submittedAt, long expiresAt, long revision) {
        public Petition {
            Objects.requireNonNull(id); Objects.requireNonNull(requester); Objects.requireNonNull(terms);
            Objects.requireNonNull(state); explanation = bounded(explanation, 512); decision = bounded(decision, 512);
        }
    }
    public record Recognition(UUID id, String kingdom, String definitionId, Definition terms,
                              Building building, UUID awardedBy, boolean active, long revision) { }
    public record Honor(UUID id, String kingdom, String definitionId, Definition terms, Person recipient,
                        UUID awardedBy, String evidence, boolean discretionary, boolean active, long revision) { }
    public record House(String kingdom, String name, String motto, Set<Person> members) {
        public House { name = bounded(name, 64); motto = bounded(motto, 256); members = Set.copyOf(members); }
    }
    public record Notice(UUID id, String kingdom, Action action, UUID actor, String recordId, long gameTime) { }
    public record Receipt(UUID actor, String fingerprint, Result result) { }
    public record Row(String id, String title, String detail, String termsHash) {
        public Row(String id, String title, String detail) { this(id, title, detail, ""); }
    }
    public record Choice(String id, String kind, String title) { }
    public record Page(UUID requestId, long revision, String kingdom, String tab, int offset,
                       boolean hasMore, List<Row> rows, List<Choice> definitions, Map<Action, String> actionDenials, String diagnostic) {
        public Page { rows = List.copyOf(rows); definitions = List.copyOf(definitions); actionDenials = Map.copyOf(actionDenials); }
    }
    public static String bounded(String value, int limit) {
        Objects.requireNonNull(value);
        if (value.length() > limit || value.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Text exceeds limit");
        return value;
    }
}
