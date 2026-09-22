package dev.otectus.mcaquests.state;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.api.QuestCompletionReceipt;
import dev.otectus.mcaquests.mixin.PlayerListAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Forces and verifies the player-file fence used by the public completion receipt API. */
public final class CompletionReceiptDurability {
    private CompletionReceiptDurability() {
    }

    /**
     * Forces vanilla's targeted save for this player, then exposes every pending receipt found in the
     * resulting player file. Vanilla catches player-data IO failures internally, so the explicit
     * reread and semantic verification are the success signal.
     */
    public static boolean flushPending(ServerPlayer player, PlayerQuestData data) {
        List<QuestCompletionReceipt> pending = data.pendingCompletionReceiptDurability();
        if (pending.isEmpty()) return true;
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) return false;
        savePlayer(server, player);
        PlayerQuestData disk = readPlayerQuestData(server, player);
        if (disk == null) return false;
        boolean all = true;
        for (QuestCompletionReceipt expected : pending) {
            if (containsCompletedSnapshot(disk, expected)) {
                data.markCompletionReceiptDurable(expected.receiptId());
            } else {
                all = false;
            }
        }
        if (!all) {
            McaQuests.LOGGER.error("[MCA: Quests] Player save did not contain the completed quest receipt "
                    + "and state for {}; receipt delivery remains fenced", player.getUUID());
        }
        return all;
    }

    /** Forces and verifies an acknowledgement. The caller rolls its in-memory mutation back on false. */
    public static boolean flushAcknowledgement(ServerPlayer player, ResourceLocationAck acknowledgement) {
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) return false;
        savePlayer(server, player);
        PlayerQuestData disk = readPlayerQuestData(server, player);
        return disk != null && disk.completionReceiptAcknowledged(acknowledgement.consumer(),
                acknowledgement.epoch(), acknowledgement.receiptId());
    }

    private static boolean containsCompletedSnapshot(PlayerQuestData disk, QuestCompletionReceipt expected) {
        if (!disk.completionReceiptDurable(expected.receiptId())
                || !disk.completionReceipt(expected.receiptId()).filter(expected::equals).isPresent()
                || disk.history().completionCountByGiver(expected.questId(), expected.giverId()) <= 0) {
            return false;
        }
        return disk.active().stream().noneMatch(active -> active.isInstance(expected.receiptId()));
    }

    private static void savePlayer(MinecraftServer server, ServerPlayer player) {
        ((PlayerListAccessor) server.getPlayerList()).mcaquests$savePlayer(player);
    }

    private static PlayerQuestData readPlayerQuestData(MinecraftServer server, ServerPlayer player) {
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
        try {
            CompoundTag root = NbtIo.readCompressed(file.toFile());
            if (!root.contains("ForgeCaps", Tag.TAG_COMPOUND)) return null;
            CompoundTag forgeCaps = root.getCompound("ForgeCaps");
            String key = QuestCapabilities.ID.toString();
            if (!forgeCaps.contains(key, Tag.TAG_COMPOUND)) return null;
            PlayerQuestData disk = new PlayerQuestData();
            disk.load(forgeCaps.getCompound(key));
            return disk;
        } catch (IOException | RuntimeException failure) {
            McaQuests.LOGGER.error("[MCA: Quests] Could not verify durable player quest data at {}", file, failure);
            return null;
        }
    }

    /** Immutable ack verification key, kept here so no mutable outbox state crosses the disk reread. */
    public record ResourceLocationAck(net.minecraft.resources.ResourceLocation consumer,
                                      java.util.UUID epoch, java.util.UUID receiptId) {
    }
}
