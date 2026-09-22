package com.ultimakingdoms.evolution;

import java.util.*;

/** Enumerated voluntary obligations. Hierarchy never grants ownership of people or native claims. */
public final class ProtectionState {
    public enum Duty { CIVIC_AID, DEFENSE_ASSISTANCE, MEDIATION }
    public enum Phase { PROPOSED, ACTIVE, EXIT_NOTICE, TERMINATED, EXPIRED }
    public record Pact(UUID id, String protector, String subordinate, UUID beneficiary, Set<Duty> duties,
                       Map<String, UUID> signatures, long created, long expires, long noticeTicks,
                       long exitAt, long revision, Phase phase, String terms) {
        public Pact {
            Objects.requireNonNull(id); EvolutionState.text(protector, 128); EvolutionState.text(subordinate, 128);
            Objects.requireNonNull(beneficiary); duties = Set.copyOf(duties); signatures = Map.copyOf(signatures);
            Objects.requireNonNull(phase); EvolutionState.text(terms, 512);
            if (protector.equals(subordinate) || duties.isEmpty() || signatures.size() > 2
                    || !Set.of(protector, subordinate).containsAll(signatures.keySet())
                    || new HashSet<>(signatures.values()).size() != signatures.size()
                    || created < 0 || expires <= created || noticeTicks < 1200 || noticeTicks > expires - created
                    || exitAt < 0 || revision < 1 || (phase == Phase.ACTIVE || phase == Phase.EXIT_NOTICE) && signatures.size() != 2
                    || phase == Phase.EXIT_NOTICE && exitAt == 0) throw new IllegalArgumentException("Invalid protectorate terms");
        }
        public boolean effective(long now) { return now < expires && (phase == Phase.ACTIVE || phase == Phase.EXIT_NOTICE && now < exitAt); }
        public Pact sign(String kingdom, UUID player, long expected, long now) {
            if (phase != Phase.PROPOSED || revision != expected || now >= expires || signatures.containsKey(kingdom))
                throw new IllegalArgumentException("Pact changed or already signed");
            var next = new LinkedHashMap<>(signatures); next.put(kingdom, player);
            return new Pact(id, protector, subordinate, beneficiary, duties, next, created, expires, noticeTicks, 0,
                    revision + 1, next.size() == 2 ? Phase.ACTIVE : Phase.PROPOSED, terms);
        }
        public Pact exit(long expected, long now) {
            if (revision != expected || !effective(now) || phase == Phase.EXIT_NOTICE) throw new IllegalArgumentException("Pact changed or inactive");
            return new Pact(id, protector, subordinate, beneficiary, duties, signatures, created, expires, noticeTicks,
                    Math.min(expires, Math.addExact(now, noticeTicks)), revision + 1, Phase.EXIT_NOTICE, terms);
        }
        public Pact refresh(long now) {
            Phase next = now >= expires ? Phase.EXPIRED : phase == Phase.EXIT_NOTICE && now >= exitAt ? Phase.TERMINATED : phase;
            return next == phase || phase == Phase.TERMINATED || phase == Phase.EXPIRED ? this
                    : new Pact(id, protector, subordinate, beneficiary, duties, signatures, created, expires, noticeTicks, exitAt, revision + 1, next, terms);
        }
    }
    public enum Status { OPEN, SATISFIED, REFUSED, EXPIRED }
    public record Obligation(UUID id, UUID pact, Duty duty, UUID beneficiary, UUID requestedBy,
                             long created, long deadline, long revision, Status status,
                             UUID performer, String receipt, String explanation) {
        public Obligation {
            Objects.requireNonNull(id); Objects.requireNonNull(pact); Objects.requireNonNull(duty); Objects.requireNonNull(beneficiary);
            Objects.requireNonNull(requestedBy); Objects.requireNonNull(status); EvolutionState.text(receipt, 256); EvolutionState.text(explanation, 512);
            if (created < 0 || deadline <= created || revision < 1 || status == Status.SATISFIED && (performer == null || receipt.isBlank()))
                throw new IllegalArgumentException("Invalid obligation");
        }
        public Obligation finish(Status next, UUID player, String proof, String reason, long expected) {
            if (revision != expected || status != Status.OPEN || next == Status.OPEN) throw new IllegalArgumentException("Obligation changed");
            return new Obligation(id, pact, duty, beneficiary, requestedBy, created, deadline, revision + 1, next, player, proof, reason);
        }
    }
    public long revision;
    public long clock;
    public Map<UUID, Pact> pacts = new LinkedHashMap<>();
    public Map<UUID, Obligation> obligations = new LinkedHashMap<>();
    public Set<String> receipts = new LinkedHashSet<>();
    /** Reject all active cycles and multiple overlords, including effects of activating a proposal. */
    public void hierarchy(long now) {
        var parents = new HashMap<String, String>();
        for (var p : pacts.values()) if (p.effective(now) && parents.putIfAbsent(p.subordinate(), p.protector()) != null)
            throw new IllegalArgumentException("A government already has an active protector");
        for (String child : parents.keySet()) {
            var seen = new HashSet<String>(); String current = child; int depth = 0;
            while (parents.containsKey(current)) {
                if (!seen.add(current) || ++depth > 4) throw new IllegalArgumentException("Protectorate cycle or maximum depth exceeded");
                current = parents.get(current);
            }
        }
    }
    public void validate() {
        if (revision < 0 || clock < 0 || pacts == null || obligations == null || receipts == null
                || pacts.size() > 1024 || obligations.size() > 8192 || receipts.size() > 8192) throw new IllegalArgumentException("Protectorate capacity exceeded");
        pacts.forEach((id, p) -> { if (p == null || !id.equals(p.id())) throw new IllegalArgumentException("Pact identity mismatch"); });
        obligations.forEach((id, o) -> {
            if (o == null || !id.equals(o.id()) || !pacts.containsKey(o.pact()) || !pacts.get(o.pact()).duties().contains(o.duty())
                    || !pacts.get(o.pact()).beneficiary().equals(o.beneficiary())) throw new IllegalArgumentException("Unbacked obligation");
        });
        receipts.forEach(r -> EvolutionState.text(r, 256)); hierarchy(clock);
    }
}
