package com.ultimakingdoms.api.factions;

import com.ultimakingdoms.api.McaCommunityRef;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable dry-run result for the explicit MCA-local to faction baseline migration. */
public record FactionMigrationReport(boolean sourceAvailable, boolean alreadyImported, int inputCount,
                                     List<Entry> entries, List<Unmapped> unmapped) {
    public FactionMigrationReport {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        unmapped = List.copyOf(Objects.requireNonNull(unmapped, "unmapped"));
    }

    public boolean importable() {
        return sourceAvailable && !alreadyImported && unmapped.isEmpty()
                && entries.stream().noneMatch(Entry::conflict);
    }

    public record Entry(UUID playerId, ResourceLocation kingdomId, List<McaCommunityRef> communities,
                        List<Integer> localScores, int baseline, boolean conflict, int existingScore) {
        public Entry {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(kingdomId, "kingdomId");
            communities = List.copyOf(Objects.requireNonNull(communities, "communities"));
            localScores = List.copyOf(Objects.requireNonNull(localScores, "localScores"));
            if (communities.size() != localScores.size()) {
                throw new IllegalArgumentException("communities and scores must have the same size");
            }
        }
    }

    public record Unmapped(UUID playerId, McaCommunityRef community, int localScore, String reason) {
        public Unmapped {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(community, "community");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
