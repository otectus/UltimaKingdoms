package dev.otectus.mcaquests.quest.reward;

import dev.otectus.mcaquests.state.PendingItemRewards;
import dev.otectus.mcaquests.state.QuestCapabilities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.RangedWrapper;
import java.util.function.Function;

/** Bounded delivery for the mod's plain item and currency rewards, with a persistent overflow ledger. */
public final class ItemRewardDelivery {
    static final int STACKS_PER_PASS = 16;
    private ItemRewardDelivery() { }

    public static void grant(ServerPlayer player, Item item, int count) {
        if (count <= 0) { return; }
        var data = QuestCapabilities.get(player).orElseThrow(() ->
                new IllegalStateException("Cannot retain item reward without player quest data"));
        int stackSize = Math.max(1, new ItemStack(item).getMaxStackSize());
        int stacks = player.isAlive() && !player.isRemoved()
                ? data.pendingItems().takeStackBudget(player.serverLevel().getGameTime(),
                        (int) Math.min(STACKS_PER_PASS, ((long) count + stackSize - 1) / stackSize), STACKS_PER_PASS) : 0;
        int immediate = player.isAlive() && !player.isRemoved()
                ? (int) Math.min(count, (long) stackSize * stacks) : 0;
        int deferred = count - immediate;
        if (deferred > 0) {
            data.pendingItems().add(BuiltInRegistries.ITEM.getKey(item), deferred);
            player.sendSystemMessage(Component.translatable("mcaquests.reward.items_pending",
                    deferred, item.getDescription()));
        }
        while (immediate > 0) {
            int amount = Math.min(stackSize, immediate);
            ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(item, amount));
            immediate -= amount;
        }
    }

    /**
     * The same bounded delivery for a stack that carries NBT (an enchanted reward, a loot roll), which
     * cannot be reduced to an id and a count and so rides the stack ledger instead.
     */
    public static void grant(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) { return; }
        var data = QuestCapabilities.get(player).orElseThrow(() ->
                new IllegalStateException("Cannot retain item reward without player quest data"));
        ItemStack remainder = stack.copy();
        if (player.isAlive() && !player.isRemoved()
                && data.pendingItems().takeStackBudget(player.serverLevel().getGameTime(), 1, STACKS_PER_PASS) > 0) {
            player.getInventory().add(remainder);
        }
        if (!remainder.isEmpty()) {
            data.pendingItems().addStack(remainder);
            player.sendSystemMessage(Component.translatable("mcaquests.reward.items_pending",
                    remainder.getCount(), remainder.getHoverName()));
        }
    }

    /** True only when the whole fixed reward fits in the player-file-backed main inventory. */
    public static boolean canFitExactly(ServerPlayer player, Item item, int count) {
        if (count <= 0 || !player.isAlive() || player.isRemoved()) return false;
        return canFitExactly(mainInventory(player), item, count);
    }

    /**
     * Inserts the whole fixed reward without ever dropping an entity or using the deferred ledger.
     * Institutional completion preflights this before consuming delivery goods and checks it again here.
     */
    public static boolean grantExactly(ServerPlayer player, Item item, int count) {
        if (!canFitExactly(player, item, count)) return false;
        try {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                    mainInventory(player), new ItemStack(item, count), false);
            if (!remainder.isEmpty()) return false;
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    static boolean canFitExactly(IItemHandler inventory, Item item, int count) {
        return count > 0 && ItemHandlerHelper.insertItemStacked(
                inventory, new ItemStack(item, count), true).isEmpty();
    }

    private static IItemHandler mainInventory(ServerPlayer player) {
        return new RangedWrapper(new InvWrapper(player.getInventory()),
                0, player.getInventory().items.size());
    }

    /** Pays only into available inventory slots; a full inventory cannot create a stream of entities. */
    public static void flush(ServerPlayer player) {
        if (!player.isAlive() || player.isRemoved()) { return; }
        var data = QuestCapabilities.get(player).orElse(null);
        if (data == null || data.pendingItems().isEmpty()) { return; }
        IItemHandler inventory = mainInventory(player);
        if (flush(data.pendingItems(), inventory, id -> BuiltInRegistries.ITEM.containsKey(id)
                ? BuiltInRegistries.ITEM.get(id) : null, player.serverLevel().getGameTime())) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
    }

    static boolean flush(PendingItemRewards pending, IItemHandler inventory,
                         Function<ResourceLocation, Item> resolve, long tick) {
        int budget = pending.takeStackBudget(tick, STACKS_PER_PASS, STACKS_PER_PASS);
        if (budget <= 0) { return false; }
        boolean changed = false;
        // NBT-bearing stacks first: they were retained whole and cannot be rebuilt from an id.
        if (pending.hasStacks()) {
            for (ItemStack stack : pending.drainStacks()) {
                if (budget <= 0) { pending.addStack(stack); continue; }
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
                if (remainder.getCount() < stack.getCount()) { changed = true; }
                budget--;
                if (!remainder.isEmpty()) { pending.addStack(remainder); }
            }
            if (budget <= 0) { return changed; }
        }
        for (var entry : pending.nextBatch(STACKS_PER_PASS).entrySet()) {
            ResourceLocation id = entry.getKey();
            Item item = resolve.apply(id);
            if (item == null) { continue; }
            ItemStack sample = new ItemStack(item);
            if (sample.isEmpty()) { continue; }
            long remaining = entry.getValue();
            while (remaining > 0 && budget > 0) {
                int offered = (int) Math.min(remaining, Math.max(1, sample.getMaxStackSize()));
                ItemStack stack = new ItemStack(item, offered);
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, stack, false);
                int delivered = offered - remainder.getCount();
                if (delivered <= 0) { break; }
                pending.delivered(id, delivered);
                remaining -= delivered;
                budget--;
                changed = true;
            }
            if (budget == 0) { break; }
        }
        return changed;
    }
}
