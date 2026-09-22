package dev.otectus.mcaquests.api.event;

import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.util.Objects;

/**
 * Optional wake-up notification fired after a completion receipt crosses its player-file durability
 * fence. Consumers must still use the read/ack API as their replay source; this event is not an outbox.
 */
public final class QuestCompletionReceiptReadyEvent extends Event {
    private final ServerPlayer player;
    private final QuestCompletionReceipt receipt;

    public QuestCompletionReceiptReadyEvent(ServerPlayer player, QuestCompletionReceipt receipt) {
        this.player = Objects.requireNonNull(player, "player");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public QuestCompletionReceipt getReceipt() {
        return receipt;
    }
}
