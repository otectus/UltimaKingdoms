package com.ultimakingdoms.client;

import com.ultimakingdoms.item.BookOfKingdomsItem;
import net.minecraft.client.Minecraft;

/** Keeps screen construction out of the bootstrap class verified by DistExecutor. */
public final class BookGuideClient {
    private BookGuideClient() {}
    public static void init() {
        BookOfKingdomsItem.reader(() -> Minecraft.getInstance().setScreen(new KingdomGuideScreen(null)));
    }
}
