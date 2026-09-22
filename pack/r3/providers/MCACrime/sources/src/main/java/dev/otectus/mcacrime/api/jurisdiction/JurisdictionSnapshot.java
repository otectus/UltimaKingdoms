package dev.otectus.mcacrime.api.jurisdiction;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;

import java.util.Objects;

/**
 * Frozen answer for one local community and optional traveler. It neither resolves cases nor changes
 * Heat; Crime combines it with its own evidence, mask, custody and player-protection rules.
 */
public record JurisdictionSnapshot(CrimeCommunityKey localCommunity, String jurisdictionId,
                                   String recognizedSovereign, String nativeController,
                                   String lawProfile, CivilLaw civilLaw, Cooperation cooperation,
                                   boolean occupied, boolean contested, boolean safeConduct,
                                   boolean remoteWantedImmunity, long sequence,
                                   Availability availability, String explanation) {
    public enum CivilLaw { CONTINUES, UNKNOWN }
    public enum Cooperation { CONFIGURED, LOCAL_ONLY }
    public enum Availability { AVAILABLE, SUSPENDED, UNAVAILABLE }

    public JurisdictionSnapshot {
        Objects.requireNonNull(localCommunity); Objects.requireNonNull(civilLaw);
        Objects.requireNonNull(cooperation); Objects.requireNonNull(availability);
        token(jurisdictionId, 128); token(recognizedSovereign, 128); token(nativeController, 128);
        token(lawProfile, 128); token(explanation, 512);
        if (sequence < 0) throw new IllegalArgumentException("Negative jurisdiction sequence");
        if (remoteWantedImmunity && !safeConduct)
            throw new IllegalArgumentException("Remote-wanted immunity requires safe conduct evidence");
    }

    private static void token(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid jurisdiction evidence");
    }
}
