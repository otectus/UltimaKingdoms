package com.ultimakingdoms.evolution.drama;

import com.ultimakingdoms.warfare.CampaignState;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable authored drama state. It records authority intents, never inferred native victories. */
public final class DramaState {
    public static final int CAPACITY = 256, PARTICIPANT_LIMIT = 8, EVIDENCE_LIMIT = 16;
    public enum Kind { CAMPAIGN, REBELLION, SCHISM, INVASION, REGENCY }
    public enum Phase { PROPOSED, READY, APPLYING, ACTIVE, NEGOTIATING, RECOVERING, RESOLVED, EXITED, EXPIRED, SUSPENDED }
    public enum Outcome { DECLARE, NEGOTIATE, EXIT, RECOVER, FOUND_SCHISM, APPOINT_REGENT }
    public enum Operation { NONE, DECLARATION, ACCORD, WITHDRAWAL, SCHISM_FOUNDING, REGENCY }

    public record Template(String title, Kind kind, Set<Outcome> outcomes, CampaignState.Goal goal,
                           String objective, long durationTicks, int maxParticipants, int operationBudget,
                           boolean requiresOccupation, String organizationTemplate, String organizationName) {
        public Template {
            text(title, 128); Objects.requireNonNull(kind); outcomes = Set.copyOf(outcomes); text(objective, 512);
            if (durationTicks < 1200 || durationTicks > 2_419_200 || maxParticipants < 2 || maxParticipants > PARTICIPANT_LIMIT
                    || operationBudget < 1 || operationBudget > 16 || outcomes.isEmpty()
                    || outcomes.stream().noneMatch(Set.of(Outcome.EXIT, Outcome.NEGOTIATE)::contains))
                throw new IllegalArgumentException("Drama templates need bounded duration, participants, budget, and peaceful choices");
            if ((kind == Kind.CAMPAIGN || kind == Kind.REBELLION || kind == Kind.INVASION) && goal == null)
                throw new IllegalArgumentException("Military drama needs an authored campaign goal");
            if (kind == Kind.REBELLION && !requiresOccupation)
                throw new IllegalArgumentException("A rebellion template must require saved occupation evidence");
            if (kind == Kind.SCHISM) {
                if (organizationTemplate == null || organizationName == null) throw new IllegalArgumentException("Schism needs an organization charter");
                token(organizationTemplate); text(organizationName, 128);
            }
        }
    }

    public record Evidence(String source, String kind, String receipt, long revision, String detail) {
        public Evidence { token(source); token(kind); token(receipt); text(detail, 512); if (revision < 0) throw new IllegalArgumentException("Invalid evidence revision"); }
    }

    public record Drama(UUID id, String templateId, Template terms, UUID settlement, UUID proposer,
                        String sourceFaction, String targetFaction, String sourceKingdom, String targetKingdom,
                        String subject, String dynamicOrganization, Map<String, UUID> participants, Set<String> consents,
                        List<Evidence> evidence, long created, long remainingTicks, long lastEvaluation, long revision,
                        int operationCount, Phase phase, Operation operation, UUID providerRequest, String result) {
        public Drama {
            Objects.requireNonNull(id); token(templateId); Objects.requireNonNull(terms); Objects.requireNonNull(settlement); Objects.requireNonNull(proposer);
            token(sourceFaction); token(targetFaction); token(sourceKingdom); token(targetKingdom);
            subject = subject == null ? "" : subject; dynamicOrganization = dynamicOrganization == null ? "" : dynamicOrganization;
            text(subject, 256); text(dynamicOrganization, 128); participants = Map.copyOf(participants); consents = Set.copyOf(consents);
            evidence = List.copyOf(evidence); Objects.requireNonNull(phase); Objects.requireNonNull(operation); Objects.requireNonNull(providerRequest); text(result, 1024);
            if (created < 0 || remainingTicks < 0 || lastEvaluation < 0 || revision < 1 || operationCount < 0 || operationCount > terms.operationBudget() || participants.isEmpty()
                    || participants.size() > terms.maxParticipants() || evidence.size() > EVIDENCE_LIMIT
                    || !participants.containsValue(proposer) || !participants.keySet().containsAll(consents)
                    || new HashSet<>(participants.values()).size() != participants.size()) throw new IllegalArgumentException("Invalid drama");
        }
        public boolean terminal() { return phase == Phase.RESOLVED || phase == Phase.EXITED || phase == Phase.EXPIRED; }
        public boolean ready() { return consents.size() >= 2 && consents.contains(sourceFaction) && consents.contains(targetFaction); }
        public Drama consent(String side, UUID player, long expected) {
            editable(expected); if (!participants.getOrDefault(side, player).equals(player)) throw new IllegalArgumentException("A participant role cannot change signatory");
            var people = new LinkedHashMap<>(participants); people.putIfAbsent(side, player); var signed = new LinkedHashSet<>(consents); signed.add(side);
            return changed(signed.size() >= 2 ? Phase.READY : phase, Operation.NONE, providerRequest, result, people, signed, remainingTicks, lastEvaluation);
        }
        public Drama begin(Operation next, long expected) {
            editable(expected); if (phase != Phase.READY && phase != Phase.ACTIVE && phase != Phase.SUSPENDED && phase != Phase.NEGOTIATING)
                throw new IllegalArgumentException("Drama is not ready for this operation");
            if (operationCount >= terms.operationBudget()) throw new IllegalArgumentException("Authored operation budget exhausted");
            return new Drama(id, templateId, terms, settlement, proposer, sourceFaction, targetFaction, sourceKingdom, targetKingdom,
                    subject, dynamicOrganization, participants, consents, evidence, created, remainingTicks, lastEvaluation, Math.addExact(revision, 1),
                    operationCount + 1, next == Operation.WITHDRAWAL ? Phase.RECOVERING : next == Operation.ACCORD ? Phase.NEGOTIATING : Phase.APPLYING,
                    next, providerRequest, "Durable operation intent recorded");
        }
        public Drama retry(long expected) {
            editable(expected); if (operation == Operation.NONE || !Set.of(Phase.APPLYING, Phase.SUSPENDED, Phase.RECOVERING).contains(phase))
                throw new IllegalArgumentException("No provider operation is pending recovery");
            if (operationCount >= terms.operationBudget()) throw new IllegalArgumentException("Authored operation budget exhausted");
            return new Drama(id, templateId, terms, settlement, proposer, sourceFaction, targetFaction, sourceKingdom, targetKingdom,
                    subject, dynamicOrganization, participants, consents, evidence, created, remainingTicks, lastEvaluation, Math.addExact(revision, 1),
                    operationCount + 1, operation == Operation.WITHDRAWAL ? Phase.RECOVERING : operation == Operation.ACCORD ? Phase.NEGOTIATING : Phase.APPLYING,
                    operation, providerRequest, "Explicit bounded recovery attempt recorded");
        }
        public Drama applied(Phase next, String detail) {
            if (next != Phase.ACTIVE && next != Phase.RESOLVED && next != Phase.NEGOTIATING && next != Phase.SUSPENDED)
                throw new IllegalArgumentException("Invalid operation result");
            return changed(next, Operation.NONE, providerRequest, detail, participants, consents, remainingTicks, lastEvaluation);
        }
        public Drama suspended(String detail) {
            return changed(Phase.SUSPENDED, operation, providerRequest, detail, participants, consents, remainingTicks, lastEvaluation);
        }
        public Drama exit(long expected, String detail) { editable(expected); return changed(Phase.EXITED, Operation.NONE, providerRequest, detail, participants, consents, remainingTicks, lastEvaluation); }
        public Drama evaluate(long now, boolean allOnline) {
            if (terminal() || now <= lastEvaluation) return this;
            long left = allOnline ? Math.max(0, remainingTicks - Math.min(now - lastEvaluation, 1200)) : remainingTicks;
            return changed(left == 0 ? Phase.EXPIRED : phase, operation, providerRequest,
                    left == 0 ? "Authored opportunity expired without changing civic identity or assets" : result,
                    participants, consents, left, now);
        }
        private void editable(long expected) { if (terminal() || revision != expected) throw new IllegalArgumentException("Drama changed or is closed; refresh first"); }
        private Drama changed(Phase next, Operation op, UUID request, String detail, Map<String, UUID> people, Set<String> signed, long remaining, long evaluated) {
            return new Drama(id, templateId, terms, settlement, proposer, sourceFaction, targetFaction, sourceKingdom, targetKingdom,
                    subject, dynamicOrganization, people, signed, evidence, created, remaining, evaluated, Math.addExact(revision, 1), operationCount, next, op, request, detail);
        }
    }

    public long revision;
    public int cursor;
    public Map<UUID, Drama> dramas = new LinkedHashMap<>();
    public Map<UUID, String> receipts = new LinkedHashMap<>();
    public void validate() {
        if (revision < 0 || cursor < 0 || dramas == null || receipts == null || dramas.size() > CAPACITY || receipts.size() > CAPACITY * 4)
            throw new IllegalArgumentException("Drama capacity exceeded");
        dramas.forEach((id, drama) -> { if (drama == null || !id.equals(drama.id())) throw new IllegalArgumentException("Drama identity mismatch"); });
        receipts.forEach((id, value) -> { Objects.requireNonNull(id); text(value, 1024); });
    }
    public static UUID operationId(UUID drama, String operation) {
        return UUID.nameUUIDFromBytes(("ultima-drama|" + drama + "|" + operation).getBytes(StandardCharsets.UTF_8));
    }
    static void token(String value) { text(value, 128); if (value.isBlank()) throw new IllegalArgumentException("Empty drama identifier"); }
    static void text(String value, int max) { if (value == null || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid drama text"); }
}
