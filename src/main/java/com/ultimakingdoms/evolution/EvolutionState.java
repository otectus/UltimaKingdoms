package com.ultimakingdoms.evolution;

import java.util.*;

/** Saved choices and receipts, independent of loaded entities and wall-clock time. */
public final class EvolutionState {
    public static final int CAPACITY = 4096;
    public enum Trigger { INSTITUTION, INTERREGNUM, OCCUPATION, DEMAND, FAMILY, GRIEVANCE }
    public enum Outcome { AID, MEDIATE, INTRODUCE, NEGOTIATE, SUCCEED, DECLINE }
    public enum Phase { OPEN, RESOLVING, RESOLVED, DECLINED, EXPIRED }
    public record Template(int schema, String title, Trigger trigger, List<Outcome> outcomes,
                           long duration, long cooldown, int contributions, boolean dramatic) {
        public Template {
            text(title, 128); Objects.requireNonNull(trigger); outcomes = List.copyOf(outcomes);
            if (schema != 1 || duration < 1200 || duration > 24_000_000 || cooldown < 1200
                    || cooldown > 24_000_000 || contributions < 1 || contributions > 64
                    || outcomes.size() < 2 || outcomes.size() > 6 || new HashSet<>(outcomes).size() != outcomes.size()
                    || !outcomes.contains(Outcome.DECLINE)) throw new IllegalArgumentException("Invalid scenario template");
        }
    }
    public record Evidence(String provider, String receipt, String kind, long at, String detail) {
        public Evidence { text(provider, 128); text(receipt, 256); text(kind, 64); text(detail, 512);
            if (at < 0) throw new IllegalArgumentException("Invalid evidence time"); }
        public String key() { return provider + ":" + receipt; }
    }
    public record Contribution(UUID player, Outcome outcome, Evidence evidence) {
        public Contribution { Objects.requireNonNull(player); Objects.requireNonNull(outcome); Objects.requireNonNull(evidence); }
    }
    public record Intent(UUID actor, Outcome outcome, UUID request, long politicalRevision, String counterpart) {
        public Intent { Objects.requireNonNull(actor); Objects.requireNonNull(outcome); Objects.requireNonNull(request);
            text(counterpart, 128); if (politicalRevision < 0) throw new IllegalArgumentException("Invalid intent"); }
    }
    public record Scenario(UUID id, String templateId, Template terms, UUID settlement, UUID institution,
                           String kingdom, Evidence cause, UUID audience, long created, long deadline,
                           long revision, Phase phase, Map<UUID, Contribution> contributions,
                           Intent intent, String result) {
        public Scenario {
            Objects.requireNonNull(id); text(templateId, 128); Objects.requireNonNull(terms);
            Objects.requireNonNull(settlement); text(kingdom, 128); Objects.requireNonNull(cause);
            Objects.requireNonNull(phase); contributions = Map.copyOf(contributions); text(result, 512);
            if (created < 0 || deadline <= created || revision < 1 || contributions.size() > 64
                    || (phase == Phase.RESOLVING || phase == Phase.RESOLVED) && intent == null)
                throw new IllegalArgumentException("Invalid scenario state");
            contributions.forEach((key, c) -> {
                if (!key.equals(c.player()) || !terms.outcomes().contains(c.outcome()) || c.outcome() == Outcome.DECLINE)
                    throw new IllegalArgumentException("Invalid contribution");
            });
            if (intent != null && !terms.outcomes().contains(intent.outcome())) throw new IllegalArgumentException("Invalid outcome");
        }
        public boolean pending() { return phase == Phase.OPEN || phase == Phase.RESOLVING; }
        public Scenario contribute(Contribution contribution, long expected, long now) {
            editable(expected, now);
            var copy = new LinkedHashMap<>(contributions);
            if (copy.containsKey(contribution.player())) throw new IllegalArgumentException("Withdraw your previous choice first");
            copy.put(contribution.player(), contribution);
            return changed(Phase.OPEN, copy, null, "");
        }
        public Scenario withdraw(UUID player, long expected, long now) {
            editable(expected, now); var copy = new LinkedHashMap<>(contributions);
            if (copy.remove(player) == null) throw new IllegalArgumentException("No contribution to withdraw");
            return changed(Phase.OPEN, copy, null, "");
        }
        public Scenario reserve(Intent next, long expected, long now) {
            editable(expected, now);
            if (next.outcome() != Outcome.DECLINE && contributions.values().stream()
                    .filter(c -> c.outcome() == next.outcome()).count() < terms.contributions())
                throw new IllegalArgumentException("More verified contributions are required");
            return changed(Phase.RESOLVING, contributions, next, "Awaiting owner acknowledgment");
        }
        public Scenario finish(String receipt) {
            if (phase != Phase.RESOLVING) throw new IllegalArgumentException("No pending resolution");
            return changed(intent.outcome() == Outcome.DECLINE ? Phase.DECLINED : Phase.RESOLVED, contributions, intent, receipt);
        }
        public Scenario reopen() {
            if (phase != Phase.RESOLVING) throw new IllegalArgumentException("No pending resolution");
            return changed(Phase.OPEN, contributions, null, "Rejected owner request released for a fresh decision");
        }
        public Scenario expire(long now) {
            return phase == Phase.OPEN && now >= deadline ? changed(Phase.EXPIRED, contributions, null, "Opportunity expired peacefully") : this;
        }
        public Scenario pause(long elapsed) {
            if (phase != Phase.OPEN || elapsed <= 0) return this;
            return new Scenario(id, templateId, terms, settlement, institution, kingdom, cause, audience, created,
                    Math.addExact(deadline, elapsed), Math.addExact(revision, 1), phase, contributions, intent, result);
        }
        private void editable(long expected, long now) {
            if (phase != Phase.OPEN || revision != expected || now >= deadline)
                throw new IllegalArgumentException("Scenario changed or deadline elapsed; refresh first");
        }
        private Scenario changed(Phase next, Map<UUID, Contribution> choices, Intent selected, String receipt) {
            return new Scenario(id, templateId, terms, settlement, institution, kingdom, cause, audience, created,
                    deadline, Math.addExact(revision, 1), next, choices, selected, receipt);
        }
    }
    public long revision;
    public long clock;
    public boolean enabled;
    public boolean drama;
    public long nextEvaluation;
    public long lastEvaluation;
    public int cursor;
    public Set<UUID> eligible = new LinkedHashSet<>();
    public Set<UUID> activeRegions = new LinkedHashSet<>();
    public Map<UUID, Scenario> scenarios = new LinkedHashMap<>();
    public Map<String, Long> cooldowns = new LinkedHashMap<>();
    public Set<String> consumed = new LinkedHashSet<>();
    public Map<UUID, Long> digest = new LinkedHashMap<>();
    public Set<UUID> subscriptions = new LinkedHashSet<>();
    public void validate() {
        if (revision < 0 || clock < 0 || nextEvaluation < 0 || lastEvaluation < 0 || cursor < 0 || eligible == null || activeRegions == null || scenarios == null
                || cooldowns == null || consumed == null || digest == null || subscriptions == null
                || eligible.size() > 256 || activeRegions.size() > 256 || scenarios.size() > CAPACITY || cooldowns.size() > CAPACITY
                || consumed.size() > CAPACITY * 16 || digest.size() > CAPACITY || subscriptions.size() > CAPACITY)
            throw new IllegalArgumentException("Evolution save limits exceeded");
        scenarios.forEach((id, value) -> { if (value == null || !id.equals(value.id())) throw new IllegalArgumentException("Scenario identity mismatch"); });
        cooldowns.forEach((key, value) -> { text(key, 256); if (value == null || value < 0) throw new IllegalArgumentException("Invalid cooldown"); });
        consumed.forEach(s -> text(s, 512));
        digest.forEach((id, time) -> { Objects.requireNonNull(id); if (time == null || time < 0) throw new IllegalArgumentException("Invalid digest cursor"); });
        eligible.forEach(Objects::requireNonNull); activeRegions.forEach(Objects::requireNonNull); subscriptions.forEach(Objects::requireNonNull);
    }
    static void text(String value, int max) {
        if (value == null || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid scenario text");
    }
}
