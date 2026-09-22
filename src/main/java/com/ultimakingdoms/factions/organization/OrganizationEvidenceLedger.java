package com.ultimakingdoms.factions.organization;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

final class OrganizationEvidenceLedger {
    private OrganizationEvidenceLedger() {
    }

    static Replay replay(OrganizationSavedData.Records records, UUID effectId, String fingerprint) {
        OrganizationSavedData.EvidenceRecord stored = records.receipts.get(effectId);
        if (stored == null) return new Replay(Status.NEW, null);
        return new Replay(stored.fingerprint.equals(fingerprint) ? Status.MATCH : Status.CONFLICT, stored);
    }

    static boolean alreadyCredited(OrganizationSavedData.Records records, UUID player,
                                   ResourceLocation organization, String questId) {
        return records.receipts.values().stream().anyMatch(value -> player.equals(value.player)
                && organization.toString().equals(value.organization) && questId.equals(value.questId));
    }

    static OrganizationSavedData.EvidenceRecord sourceReceipt(OrganizationSavedData.Records records,
                                                              UUID player, ResourceLocation organization,
                                                              UUID sourceReceiptId) {
        return records.receipts.values().stream().filter(value -> player.equals(value.player)
                && organization.toString().equals(value.organization)
                && sourceReceiptId.equals(value.sourceReceiptId)).findFirst().orElse(null);
    }

    enum Status {
        NEW,
        MATCH,
        CONFLICT
    }

    record Replay(Status status, OrganizationSavedData.EvidenceRecord stored) {
    }
}
