package com.ultimakingdoms.api.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OrganizationEvidenceReceipt(UUID effectId, UUID sourceReceiptId, String questId,
                                          ResourceLocation organizationId, int requestedCredit,
                                          int appliedCredit, Optional<UUID> settlementId,
                                          long recordedAt, long revision) {
    public OrganizationEvidenceReceipt {
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(sourceReceiptId, "sourceReceiptId");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(organizationId, "organizationId");
        settlementId = Objects.requireNonNull(settlementId, "settlementId");
    }
}
