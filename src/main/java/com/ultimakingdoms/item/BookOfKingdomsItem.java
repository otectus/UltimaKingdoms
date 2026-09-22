package com.ultimakingdoms.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import java.util.List;

/** The client installs the reader; the dedicated server never links a Screen class. */
public final class BookOfKingdomsItem extends Item {
    private static Runnable reader=()->{};
    public BookOfKingdomsItem(Properties properties){super(properties);}
    public static void reader(Runnable value){reader=java.util.Objects.requireNonNull(value);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        if(level.isClientSide())reader.run();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide());
    }
    @Override public void appendHoverText(ItemStack stack,Level level,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("item.ultima_kingdoms.book_of_kingdoms.tooltip"));
    }
}
