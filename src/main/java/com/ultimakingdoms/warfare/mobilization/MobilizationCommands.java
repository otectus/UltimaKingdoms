package com.ultimakingdoms.warfare.mobilization;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=UltimaKingdomsApi.MOD_ID)
public final class MobilizationCommands {
    private MobilizationCommands(){ }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("ultima-mobilization");var muster=Commands.literal("muster");
        muster.then(Commands.literal("nearby").then(Commands.argument("doctrine",StringArgumentType.word()).executes(c->{
            var actor=c.getSource().getPlayerOrException();var lines=MobilizationEvents.get(actor.getServer()).musterNearby(actor,doctrine(c));
            lines.forEach(v->c.getSource().sendSuccess(()->Component.literal(v),false));return lines.size();})));
        muster.then(com.ultimakingdoms.interaction.NamedTargets.argument("unit","native_recruit").then(Commands.argument("doctrine",StringArgumentType.word()).executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).muster(actor,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"unit","native_recruit"),doctrine(c));
            c.getSource().sendSuccess(()->Component.literal(result),false);return 1;})));
        root.then(muster);
        root.then(Commands.literal("dismiss").then(com.ultimakingdoms.interaction.NamedTargets.argument("lease","deployment").executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).dismiss(actor,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"lease","deployment"));
            c.getSource().sendSuccess(()->Component.literal(result),false);return 1;})));
        root.then(Commands.literal("recover").then(com.ultimakingdoms.interaction.NamedTargets.argument("lease","deployment").executes(c->{
            var actor=c.getSource().getPlayerOrException();String result=MobilizationEvents.get(actor.getServer()).recover(actor,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"lease","deployment"));
            c.getSource().sendSuccess(()->Component.literal(result),false);return 1;})));
        root.then(Commands.literal("status").executes(c->{var actor=c.getSource().getPlayerOrException();var lines=MobilizationEvents.get(actor.getServer()).status(actor);
            lines.forEach(v->c.getSource().sendSuccess(()->Component.literal(v),false));return lines.size();}));
        event.getDispatcher().register(root);
    }
    private static MobilizationDoctrine doctrine(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context){
        try{return MobilizationDoctrine.valueOf(StringArgumentType.getString(context,"doctrine").toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Doctrine must be defense, escort or scout.");}}
}
