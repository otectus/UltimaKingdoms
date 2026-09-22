package dev.otectus.mcaquests.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable evidence that one accepted quest instance reached MCA: Quests' completed state.
 *
 * <p>Instances returned by {@link McaQuestsApi#readCompletionReceipts} have crossed the player-data
 * durability fence: the receipt, completion history, removed active quest, inventory and other player
 * state were present in the same on-disk player NBT snapshot. This does not make mutations to a
 * villager or another mod's saved data part of that player-file transaction.
 */
public record QuestCompletionReceipt(UUID providerEpoch, UUID receiptId, UUID playerId,
                                     ResourceLocation questId, long completionRevision,
                                     Outcome outcome, long completedGameTime, long acceptedGameTime,
                                     UUID giverId, ResourceLocation acceptedDimension,
                                     Optional<Integer> acceptedVillageId,
                                     Optional<KingdomBinding> kingdomBinding,
                                     Optional<CivicBuildingBinding> civicBuildingBinding) {

    public QuestCompletionReceipt {
        Objects.requireNonNull(providerEpoch, "providerEpoch");
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(giverId, "giverId");
        Objects.requireNonNull(acceptedDimension, "acceptedDimension");
        acceptedVillageId = Objects.requireNonNull(acceptedVillageId, "acceptedVillageId");
        kingdomBinding = Objects.requireNonNull(kingdomBinding, "kingdomBinding");
        civicBuildingBinding = Objects.requireNonNull(civicBuildingBinding, "civicBuildingBinding");
        if (completionRevision <= 0L || completedGameTime < 0L || acceptedGameTime < 0L
                || acceptedVillageId.filter(value -> value < 0).isPresent()) {
            throw new IllegalArgumentException("invalid quest completion receipt number");
        }
    }

    public enum Outcome {
        COMPLETED
    }

    /** Frozen sovereign settlement context captured when the quest was accepted. */
    public record KingdomBinding(UUID settlementId, ResourceLocation kingdomId,
                                 long settlementRevision, ResourceLocation dimension,
                                 Optional<ResourceLocation> localDimension,
                                 Optional<Integer> localVillageId) {
        public KingdomBinding {
            Objects.requireNonNull(settlementId, "settlementId");
            Objects.requireNonNull(kingdomId, "kingdomId");
            Objects.requireNonNull(dimension, "dimension");
            localDimension = Objects.requireNonNull(localDimension, "localDimension");
            localVillageId = Objects.requireNonNull(localVillageId, "localVillageId");
            if (settlementRevision < 0L || localVillageId.filter(value -> value < 0).isPresent()
                    || localDimension.isPresent() != localVillageId.isPresent()) {
                throw new IllegalArgumentException("invalid frozen kingdom binding");
            }
        }
    }

    /** Frozen civic-building context captured when the quest was accepted. */
    public record CivicBuildingBinding(UUID bindingId, UUID settlementId,
                                       ResourceLocation dimension, int villageId, int buildingId,
                                       String family, String typeAtBinding) {
        public CivicBuildingBinding {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(settlementId, "settlementId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(typeAtBinding, "typeAtBinding");
            if (villageId < 0 || buildingId < 0 || family.isBlank() || typeAtBinding.isBlank()) {
                throw new IllegalArgumentException("invalid frozen civic-building binding");
            }
        }
    }
}
