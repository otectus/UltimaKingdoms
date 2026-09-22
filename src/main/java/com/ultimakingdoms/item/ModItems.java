package com.ultimakingdoms.item;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, UltimaKingdomsApi.MOD_ID);

    public static final RegistryObject<Item> VILLAGE_LEDGER = ITEMS.register("village_ledger",
            () -> new VillageLedgerItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> BOOK_OF_KINGDOMS = ITEMS.register("book_of_kingdoms",
            () -> new BookOfKingdomsItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(ModItems::addCreativeTabContents);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(VILLAGE_LEDGER);
            event.accept(BOOK_OF_KINGDOMS);
        }
    }
}
