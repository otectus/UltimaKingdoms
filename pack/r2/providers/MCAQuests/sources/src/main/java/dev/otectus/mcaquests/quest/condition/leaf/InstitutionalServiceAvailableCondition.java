package dev.otectus.mcaquests.quest.condition.leaf;

import com.mojang.serialization.Codec;
import dev.otectus.mcaquests.quest.InstitutionalCommissionBridge;
import dev.otectus.mcaquests.quest.condition.ConditionTypes;
import dev.otectus.mcaquests.quest.condition.QuestCondition;
import dev.otectus.mcaquests.quest.condition.QuestConditionType;
import dev.otectus.mcaquests.quest.condition.QuestContext;
import net.minecraft.network.chat.Component;

/**
 * Datapack tripwire and offer gate for Ultima-owned institutional commissions.
 *
 * <p>The condition type did not exist before the bounded institutional protocol. Older MCA: Quests
 * builds therefore reject a definition that names it instead of ignoring the newer
 * {@code institutional_commission} metadata and exposing paid work as an ordinary quest. On this
 * provider it is true only when all methods in Ultima's exact reflection-only ABI are present.
 */
public final class InstitutionalServiceAvailableCondition implements QuestCondition {

    public static final Codec<InstitutionalServiceAvailableCondition> CODEC =
            Codec.unit(InstitutionalServiceAvailableCondition::new);

    @Override
    public QuestConditionType<?> type() {
        return ConditionTypes.INSTITUTIONAL_SERVICE_AVAILABLE;
    }

    @Override
    public boolean test(QuestContext context) {
        return InstitutionalCommissionBridge.serviceAvailable();
    }

    @Override
    public Component describe() {
        return Component.literal("Institutional commission service available");
    }
}
