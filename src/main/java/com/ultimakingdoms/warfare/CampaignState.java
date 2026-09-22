package com.ultimakingdoms.warfare;

import java.util.*;

/** Political decisions, provider intents and frozen civilian contracts. Native mechanics stay native. */
public final class CampaignState {
    public static final int CAPACITY = 4096;
    public enum Goal { DEFEND, RELIEF_ACCESS, WITHDRAWAL, AUTONOMY, TRANSFER }
    public enum Phase { NOTICE, ACTIVE, OCCUPIED, RESOLVED, EXPIRED, SUSPENDED }
    public enum Relation { NEUTRAL, ALLY, ENEMY }
    public enum TreatyPhase { PROPOSED, SIGNED, PENDING, ACTIVE, BREACHED, EXPIRED, REJECTED }
    public enum Sovereignty { RECOGNIZED, AUTONOMOUS, INDEPENDENT }
    public enum ContractKind { RELIEF, MEDIATION, AUTONOMY, ESCORT, RECONNAISSANCE, DEFENSE }
    public enum ContractPhase { OFFERED, ACCEPTED, COMPLETED, CANCELLED }
    public record Campaign(UUID id, UUID settlement, UUID claim, String attacker, String defender,
                           String kingdom, UUID commander, Goal goal, long noticeUntil, long expires,
                           Phase phase, long observedControlSequence, Relation before, String reason) {
        public Campaign {
            Objects.requireNonNull(id); Objects.requireNonNull(settlement); Objects.requireNonNull(claim);
            token(attacker); token(defender); token(kingdom); Objects.requireNonNull(commander); Objects.requireNonNull(goal);
            Objects.requireNonNull(phase); Objects.requireNonNull(before); text(reason, 512);
            if (attacker.equals(defender) || noticeUntil < 0 || expires <= noticeUntil || observedControlSequence < 1)
                throw new IllegalArgumentException("Invalid campaign");
        }
        public Campaign phase(Phase next, String detail) { return new Campaign(id, settlement, claim, attacker, defender, kingdom, commander,
                goal, noticeUntil, expires, next, observedControlSequence, before, detail); }
    }
    public record Accord(UUID id, UUID settlement, String first, String second, String firstKingdom, String secondKingdom,
                         Relation desired, Relation firstBefore, Relation secondBefore, Map<String, UUID> signatures,
                         TreatyPhase phase, Sovereignty sovereignty, String recognizedKingdom, long expires,
                         long controlSequence, UUID claim, String controller, String reason) {
        public Accord {
            Objects.requireNonNull(id); Objects.requireNonNull(settlement); token(first); token(second);
            token(firstKingdom); token(secondKingdom); Objects.requireNonNull(desired); Objects.requireNonNull(firstBefore);
            Objects.requireNonNull(secondBefore); signatures = Map.copyOf(signatures); Objects.requireNonNull(phase);
            Objects.requireNonNull(sovereignty); Objects.requireNonNull(claim); token(controller); token(recognizedKingdom); text(reason, 512);
            if (first.equals(second) || signatures.size() > 2 || signatures.keySet().stream().anyMatch(s -> !s.equals(first) && !s.equals(second))
                    || new HashSet<>(signatures.values()).size() != signatures.size()
                    || desired == Relation.ENEMY
                    || (!recognizedKingdom.equals(firstKingdom) && !recognizedKingdom.equals(secondKingdom))
                    || (!controller.equals(first) && !controller.equals(second))
                    || ((phase == TreatyPhase.SIGNED || phase == TreatyPhase.PENDING || phase == TreatyPhase.ACTIVE) && signatures.size() != 2)
                    || expires < 0 || controlSequence < 1) throw new IllegalArgumentException("Invalid accord");
        }
        public Accord phase(TreatyPhase next, String detail) { return new Accord(id, settlement, first, second, firstKingdom, secondKingdom,
                desired, firstBefore, secondBefore, signatures, next, sovereignty, recognizedKingdom, expires, controlSequence, claim, controller, detail); }
        public Accord sign(String side, UUID player) {
            var signed = new HashMap<>(signatures); signed.put(side, player);
            return new Accord(id, settlement, first, second, firstKingdom, secondKingdom, desired, firstBefore, secondBefore,
                    signed, signed.size() == 2 ? TreatyPhase.SIGNED : TreatyPhase.PROPOSED, sovereignty, recognizedKingdom, expires, controlSequence, claim, controller, reason);
        }
    }
    public record Decision(UUID settlement, UUID accord, Sovereignty status, String kingdom, long at, long controlSequence) {
        public Decision { Objects.requireNonNull(settlement); Objects.requireNonNull(accord); Objects.requireNonNull(status); token(kingdom);
            if (at < 0 || controlSequence < 1) throw new IllegalArgumentException("Invalid sovereign decision"); }
    }
    public record Contract(UUID id, UUID player, UUID giver, UUID institution, UUID settlement, String quest,
                           ContractKind kind, ContractPhase phase, long controlSequence, long institutionRevision,
                           String buildingFingerprint, long expires, UUID instance, UUID completionEpoch, UUID completionReceipt,
                           UUID route, long milestone) {
        public Contract {
            Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(giver); Objects.requireNonNull(institution);
            Objects.requireNonNull(settlement); token(quest); Objects.requireNonNull(kind); Objects.requireNonNull(phase);
            text(buildingFingerprint, 1024);
            if (controlSequence < 1 || institutionRevision < 0 || expires < 0 || milestone < 0) throw new IllegalArgumentException("Invalid civilian contract");
            if (phase == ContractPhase.ACCEPTED && instance == null || phase == ContractPhase.COMPLETED && (instance == null || completionEpoch == null || completionReceipt == null))
                throw new IllegalArgumentException("Incomplete contract evidence");
        }
        public Contract accepted(UUID nativeInstance) { return new Contract(id, player, giver, institution, settlement, quest, kind, ContractPhase.ACCEPTED,
                controlSequence, institutionRevision, buildingFingerprint, expires, nativeInstance, null, null, route, milestone); }
        public Contract complete(UUID epoch, UUID receipt) { return new Contract(id, player, giver, institution, settlement, quest, kind, ContractPhase.COMPLETED,
                controlSequence, institutionRevision, buildingFingerprint, expires, instance, epoch, receipt, route, milestone); }
    }
    public record Notice(UUID id, UUID settlement, String action, UUID actor, long at, String detail) {
        public Notice { Objects.requireNonNull(id); Objects.requireNonNull(settlement); token(action); Objects.requireNonNull(actor);
            if (at < 0) throw new IllegalArgumentException("Invalid notice"); text(detail, 512); }
    }
    public record Receipt(UUID player, String fingerprint, String result) {
        public Receipt { Objects.requireNonNull(player); text(fingerprint, 64); text(result, 1024); }
    }
    public long revision;
    public Map<UUID, Campaign> campaigns = new LinkedHashMap<>();
    public Map<UUID, Accord> accords = new LinkedHashMap<>();
    public Map<UUID, Decision> decisions = new LinkedHashMap<>();
    public Map<UUID, Contract> contracts = new LinkedHashMap<>();
    public Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    public List<Notice> history = new ArrayList<>();
    public void validate() {
        if (revision < 0 || campaigns == null || accords == null || decisions == null || contracts == null || receipts == null || history == null
                || campaigns.size() > CAPACITY || accords.size() > CAPACITY || decisions.size() > CAPACITY || contracts.size() > CAPACITY
                || receipts.size() > CAPACITY * 4 || history.size() > CAPACITY) throw new IllegalArgumentException("Campaign capacity exceeded");
        campaigns.forEach((id, c) -> { if (c == null || !id.equals(c.id())) throw new IllegalArgumentException("Campaign identity mismatch"); });
        accords.forEach((id, a) -> { if (a == null || !id.equals(a.id())) throw new IllegalArgumentException("Accord identity mismatch"); });
        decisions.forEach((id, d) -> {
            var accord = d == null ? null : accords.get(d.accord());
            if (d == null || !id.equals(d.settlement()) || accord == null || !accord.settlement().equals(id)
                    || !accord.recognizedKingdom().equals(d.kingdom()) || accord.sovereignty() != d.status()
                    || accord.signatures().size() != 2 || accord.controlSequence() != d.controlSequence()
                    || (accord.phase() != TreatyPhase.ACTIVE && accord.phase() != TreatyPhase.BREACHED && accord.phase() != TreatyPhase.EXPIRED))
                throw new IllegalArgumentException("Unbacked sovereignty");
        });
        contracts.forEach((id, c) -> { if (c == null || !id.equals(c.id())) throw new IllegalArgumentException("Contract identity mismatch"); });
        if (receipts.containsKey(null) || receipts.containsValue(null) || history.contains(null)) throw new IllegalArgumentException("Invalid history");
    }
    static void token(String s) { text(s, 128); if (s.isBlank()) throw new IllegalArgumentException("Empty identifier"); }
    static void text(String s, int max) { if (s == null || s.length() > max || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid text"); }
}
