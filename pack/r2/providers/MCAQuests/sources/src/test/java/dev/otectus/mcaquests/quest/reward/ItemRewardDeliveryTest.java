package dev.otectus.mcaquests.quest.reward;

import dev.otectus.mcaquests.state.PendingItemRewards;
import dev.otectus.mcaquests.support.TestBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemRewardDeliveryTest {
    static { TestBootstrap.ensureBootstrapped(); }
    private static final ResourceLocation ITEM = new ResourceLocation("minecraft", "emerald");

    @Test void fullInventoryRetainsEveryOwedItemWithoutCreativeDiscardFallback() {
        PendingItemRewards pending = new PendingItemRewards();
        pending.add(ITEM, 5000);
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.DIAMOND, 64));
        assertFalse(ItemRewardDelivery.flush(pending, new InvWrapper(inventory), id -> Items.EMERALD, 1));
        assertEquals(5000L, pending.snapshot().get(ITEM));
        inventory.setItem(0, new ItemStack(Items.EMERALD, 62));
        assertTrue(ItemRewardDelivery.flush(pending, new InvWrapper(inventory), id -> Items.EMERALD, 2));
        assertEquals(64, inventory.getItem(0).getCount());
        assertEquals(4998L, pending.snapshot().get(ITEM));
    }

    @Test void exactInstitutionalPaymentRequiresPlayerFileBackedCapacity() {
        SimpleContainer full = new SimpleContainer(new ItemStack(Items.DIAMOND, 64));
        assertFalse(ItemRewardDelivery.canFitExactly(new InvWrapper(full), Items.EMERALD, 6));

        SimpleContainer partial = new SimpleContainer(new ItemStack(Items.EMERALD, 58));
        assertTrue(ItemRewardDelivery.canFitExactly(new InvWrapper(partial), Items.EMERALD, 6));
        assertEquals(58, partial.getItem(0).getCount(), "capacity simulation must not mutate inventory");

        SimpleContainer empty = new SimpleContainer(1);
        assertTrue(ItemRewardDelivery.canFitExactly(new InvWrapper(empty), Items.EMERALD, 6));
    }

    @Test void eachTickCanInsertAtMostSixteenStacksAcrossCalls() {
        PendingItemRewards pending = new PendingItemRewards();
        pending.add(ITEM, 5000);
        SimpleContainer inventory = new SimpleContainer(40);
        assertTrue(ItemRewardDelivery.flush(pending, new InvWrapper(inventory), id -> Items.EMERALD, 1));
        assertEquals(5000L - 1024, pending.snapshot().get(ITEM));
        assertFalse(ItemRewardDelivery.flush(pending, new InvWrapper(inventory), id -> Items.EMERALD, 1));
        assertEquals(5000L - 1024, pending.snapshot().get(ITEM));
    }

    @Test void absentItemsDoNotLoseDebtOrStarveRestoredItems() {
        PendingItemRewards pending = new PendingItemRewards();
        for (int i = 0; i < 17; i++) {
            pending.add(new ResourceLocation("absent", "item_" + i), 64);
        }
        pending.add(ITEM, 64);
        SimpleContainer inventory = new SimpleContainer(1);
        assertFalse(ItemRewardDelivery.flush(pending, new InvWrapper(inventory),
                id -> id.equals(ITEM) ? Items.EMERALD : null, 1));
        assertTrue(ItemRewardDelivery.flush(pending, new InvWrapper(inventory),
                id -> id.equals(ITEM) ? Items.EMERALD : null, 2));
        assertEquals(17, pending.snapshot().size());
        assertFalse(pending.snapshot().containsKey(ITEM));
    }
}
