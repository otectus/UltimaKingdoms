package dev.otectus.mcaquests.api;

import com.mojang.serialization.Codec;
import dev.otectus.mcaquests.event.QuestEventHandlers;
import dev.otectus.mcaquests.quest.condition.ConditionTypes;
import dev.otectus.mcaquests.quest.condition.QuestCondition;
import dev.otectus.mcaquests.quest.condition.QuestConditionType;
import dev.otectus.mcaquests.quest.objective.ObjectiveTypes;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.quest.objective.QuestObjectiveType;
import dev.otectus.mcaquests.quest.reward.QuestReward;
import dev.otectus.mcaquests.quest.reward.QuestRewardType;
import dev.otectus.mcaquests.quest.reward.RewardTypes;
import dev.otectus.mcaquests.state.CompletionReceiptDurability;
import dev.otectus.mcaquests.state.PlayerQuestData;
import dev.otectus.mcaquests.state.QuestCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public registration API for MCA: Quests add-ons (spec section 28).
 *
 * <p>Register your own objective / reward / condition types during your mod's setup
 * (e.g. {@code FMLCommonSetupEvent.enqueueWork}) using your own namespace for {@code id}; the JSON
 * {@code "type"} field then dispatches to your {@link Codec}. To react to quest progress, subscribe
 * to the events in {@code dev.otectus.mcaquests.api.event} on the Forge event bus.
 */
public final class McaQuestsApi {

    private static final ResourceLocation INSTITUTIONAL_COMPLETION_CONSUMER =
            new ResourceLocation("ultima_kingdoms", "regional_civic_network");

    private McaQuestsApi() {
    }

    public static <T extends QuestObjective> QuestObjectiveType<T> registerObjective(ResourceLocation id, Codec<T> codec) {
        return ObjectiveTypes.register(id, codec);
    }

    public static <T extends QuestReward> QuestRewardType<T> registerReward(ResourceLocation id, Codec<T> codec) {
        return RewardTypes.register(id, codec);
    }

    public static <T extends QuestCondition> QuestConditionType<T> registerCondition(ResourceLocation id, Codec<T> codec) {
        return ConditionTypes.register(id, codec);
    }

    /**
     * Signals that {@code player} genuinely held a conversation with {@code villager}, advancing every
     * matching {@code talk_to_profession} objective (quest and project) by one.
     *
     * <p>For MCA: Conversations, which knows about real dialogue that MCA: Quests' own empty-hand
     * interaction hook cannot see. Safe to call for a conversation the interaction hook also observed:
     * credit is deduped by villager UUID, so the same villager never counts twice for one objective.
     * Server-side only.
     */
    public static void notifyVillagerConversation(ServerPlayer player, Entity villager) {
        QuestEventHandlers.creditConversation(player, villager);
    }

    /**
     * Returns up to {@code limit} oldest durable completion receipts this loaded player has not yet
     * delivered to {@code consumerId}. Reads never scan offline player files. A consumer must durably
     * accept a receipt under {@link QuestCompletionReceipt#receiptId()} before acknowledging it here.
     *
     * <p>The call opportunistically retries the provider's player-file durability fence for a completion
     * whose first save failed. This call also subscribes the consumer for future completions; receipts
     * freeze the consumers active at completion, so a later installation cannot claim older quest work.
     * Polling renews a short lease. If every consumer stops polling, normal quest completion stops
     * producing receipts and cannot be blocked by an unused outbox. Limits are 1..64. Call on the
     * logical server thread.
     */
    public static List<QuestCompletionReceipt> readCompletionReceipts(ServerPlayer player,
                                                                      ResourceLocation consumerId,
                                                                      int limit) {
        if (player == null || consumerId == null) throw new NullPointerException("player and consumerId");
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be between 1 and 64");
        return QuestCapabilities.get(player).map(data -> {
            CompletionReceiptDurability.flushPending(player, data);
            return data.readCompletionReceipts(consumerId, limit, player.serverLevel().getGameTime());
        }).orElseGet(List::of);
    }

    /**
     * Durably acknowledges one receipt after {@code consumerId} has durably accepted it. The exact
     * provider epoch and receipt id prevent an acknowledgement from crossing a restored/replaced source.
     * Returns false for an unknown receipt, an epoch mismatch, an unavailable capability, or when the
     * acknowledgement could not be confirmed in the on-disk player NBT. Retrying is safe.
     * Fully acknowledged receipts may be retired early under capacity pressure; a bounded 512-entry
     * tombstone window keeps those acknowledgement retries idempotent until their original retention
     * deadline (oldest tombstones are reclaimed first if that separate bound is reached).
     */
    public static boolean acknowledgeCompletionReceipt(ServerPlayer player, ResourceLocation consumerId,
                                                        UUID providerEpoch, UUID receiptId) {
        if (player == null || consumerId == null || providerEpoch == null || receiptId == null) {
            throw new NullPointerException("completion receipt acknowledgement argument");
        }
        return QuestCapabilities.get(player).map(data -> {
            boolean alreadyAcknowledged = data.completionReceiptWasAcknowledged(consumerId, receiptId);
            if (!data.acknowledgeCompletionReceipt(consumerId, providerEpoch, receiptId)) return false;
            if (alreadyAcknowledged) return true;
            CompletionReceiptDurability.ResourceLocationAck key =
                    new CompletionReceiptDurability.ResourceLocationAck(consumerId, providerEpoch, receiptId);
            if (CompletionReceiptDurability.flushAcknowledgement(player, key)) return true;
            data.rollbackCompletionReceiptAcknowledgement(consumerId, receiptId, false);
            return false;
        }).orElse(false);
    }

    /**
     * Human-readable provider health for diagnostics: {@code ready}, {@code pending_save}, {@code full},
     * {@code corrupt}, {@code future_schema:N}, or {@code unavailable}. This call also retries a pending
     * save fence and never scans offline data.
     */
    public static String completionReceiptStatus(ServerPlayer player) {
        if (player == null) throw new NullPointerException("player");
        return QuestCapabilities.get(player).map(data -> {
            CompletionReceiptDurability.flushPending(player, data);
            return data.completionReceiptStatus();
        }).orElse("unavailable");
    }

    /**
     * Renews the one receipt lease required by institutional completion without invoking a player save.
     * Ultima calls this synchronously from its validation boundary so the native completion preflight
     * can prove the intended consumer is in the frozen receipt cohort. A pending save, full/corrupt
     * outbox, unavailable capability, or consumer-capacity failure returns false.
     */
    public static boolean renewInstitutionalCompletionConsumer(ServerPlayer player) {
        if (player == null) throw new NullPointerException("player");
        return QuestCapabilities.get(player).map(data -> {
            long now = player.serverLevel().getGameTime();
            try {
                data.readCompletionReceipts(INSTITUTIONAL_COMPLETION_CONSUMER, 8, now);
                return "ready".equals(data.completionReceiptStatus())
                        && data.hasActiveCompletionReceiptConsumer(INSTITUTIONAL_COMPLETION_CONSUMER, now);
            } catch (RuntimeException failure) {
                return false;
            }
        }).orElse(false);
    }

    /**
     * Opens MCA: Quests' native offer UI for a trusted server-side commission service, drawing only from
     * {@code allowedQuestIds}. The set may contain at most 32 existing template ids. This does not accept a
     * quest or bypass its giver, conditions, chain, cooldown, capacity, target, or acceptance revalidation.
     * Returns false when the caller is off-thread or the player cannot currently interact with the giver.
     */
    public static boolean openCommissionMenu(ServerPlayer player, Entity giver,
                                             Set<ResourceLocation> allowedQuestIds) {
        if (player == null || giver == null || allowedQuestIds == null) {
            throw new NullPointerException("commission menu argument");
        }
        if (allowedQuestIds.isEmpty() || allowedQuestIds.size() > 32) {
            throw new IllegalArgumentException("allowedQuestIds must contain between 1 and 32 ids");
        }
        Set<ResourceLocation> scope = Set.copyOf(allowedQuestIds);
        return dev.otectus.mcaquests.quest.QuestManager.openCommissionMenu(player, giver, scope);
    }

    /**
     * Opens the native UI for paid institutional work owned by one opaque Ultima contract UUID.
     * Institutional definitions are excluded from ordinary menus and fail closed if Ultima's validation
     * or durable acceptance callback is absent. The binding is persisted through completion receipts.
     */
    public static boolean openInstitutionalCommissionMenu(ServerPlayer player, Entity giver,
                                                          Set<ResourceLocation> allowedQuestIds,
                                                          String binding) {
        if (player == null || giver == null || allowedQuestIds == null || binding == null) {
            throw new NullPointerException("institutional commission menu argument");
        }
        if (allowedQuestIds.isEmpty() || allowedQuestIds.size() > 32) {
            throw new IllegalArgumentException("allowedQuestIds must contain between 1 and 32 ids");
        }
        if (!dev.otectus.mcaquests.quest.InstitutionalCommissionBridge.validBinding(binding)) {
            throw new IllegalArgumentException("binding must be one canonical UUID");
        }
        return dev.otectus.mcaquests.quest.QuestManager.openInstitutionalCommissionMenu(
                player, giver, Set.copyOf(allowedQuestIds), binding);
    }
}
