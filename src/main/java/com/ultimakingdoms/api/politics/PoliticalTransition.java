package com.ultimakingdoms.api.politics;

import java.util.*;

/** Typed constitutional transition contracts. Ballot identities are never exposed by read views. */
public final class PoliticalTransition {
    private PoliticalTransition() { }
    public enum ElectionState { OPEN, GRACE, RESOLVED, EXPIRED, CANCELLED }
    public record Rule(boolean elections, boolean regency, long electionTicks, long graceTicks, long regencyTicks,
                       int maxCandidates, Set<Politics.Permission> regentPermissions) {
        public Rule {
            regentPermissions = Set.copyOf(regentPermissions);
            if (electionTicks < 1200 || electionTicks > 2_419_200 || graceTicks < 200 || graceTicks > 172_800
                    || regencyTicks < 1200 || regencyTicks > 2_419_200 || maxCandidates < 2 || maxCandidates > 16
                    || regentPermissions.size() > 8 || regentPermissions.contains(Politics.Permission.DELEGATE))
                throw new IllegalArgumentException("Invalid constitutional transition limits");
        }
    }
    public record Election(UUID id, String kingdom, Set<UUID> candidates, Set<UUID> electorate, Map<UUID, UUID> ballots,
                           ElectionState state, long openedAt, long deadline, long graceUntil, long revision, UUID winner) {
        public Election {
            Objects.requireNonNull(id); kingdom = Politics.bounded(kingdom, 128); candidates = Set.copyOf(candidates);
            electorate = Set.copyOf(electorate); ballots = Map.copyOf(ballots); Objects.requireNonNull(state);
            if (candidates.size() < 2 || candidates.size() > 16 || electorate.isEmpty() || electorate.size() > 128
                    || !electorate.containsAll(ballots.keySet()) || !candidates.containsAll(ballots.values())
                    || openedAt < 0 || deadline <= openedAt || graceUntil <= deadline || revision < 0
                    || state == ElectionState.RESOLVED && (winner == null || !candidates.contains(winner))
                    || state != ElectionState.RESOLVED && winner != null)
                throw new IllegalArgumentException("Invalid election");
        }
    }
    public record ElectionView(UUID id, String kingdom, Set<UUID> candidates, ElectionState state, int electorateSize,
                               int ballotsCast, boolean viewerEligible, boolean viewerVoted, long deadline,
                               long graceUntil, long revision, Optional<UUID> winner) {
        public ElectionView { candidates = Set.copyOf(candidates); winner = Objects.requireNonNull(winner); }
    }
    public record Regency(UUID id, String kingdom, Politics.Person regent, Set<Politics.Permission> permissions,
                          Politics.Person preservedSuccessor, long appointedAt, long expiresAt, boolean active, long revision) {
        public Regency {
            Objects.requireNonNull(id); kingdom = Politics.bounded(kingdom, 128); Objects.requireNonNull(regent);
            permissions = Set.copyOf(permissions);
            if (regent.kind() != Politics.Kind.PLAYER || permissions.contains(Politics.Permission.DELEGATE)
                    || appointedAt < 0 || expiresAt <= appointedAt || revision < 0) throw new IllegalArgumentException("Invalid regency");
        }
    }
}
