package com.ultimakingdoms.worldcontext;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.UuidArgument;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.worldcontext.WorldContextApi;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/** Player-facing route guide/ledger and tightly scoped operator authorship. */
@Mod.EventBusSubscriber(modid=UltimaKingdomsApi.MOD_ID)
public final class WorldContextCommands {
    private WorldContextCommands(){ }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("ultima-world")
                .then(Commands.literal("sites").executes(c->sites(c.getSource(),0))
                        .then(Commands.argument("page",IntegerArgumentType.integer(0,1562)).executes(c->sites(c.getSource(),IntegerArgumentType.getInteger(c,"page")))))
                .then(Commands.literal("routes").executes(c->routes(c.getSource(),0))
                        .then(Commands.argument("page",IntegerArgumentType.integer(0,1562)).executes(c->routes(c.getSource(),IntegerArgumentType.getInteger(c,"page")))))
                .then(Commands.literal("encounters").executes(c->encounters(c.getSource(),0))
                        .then(Commands.argument("page",IntegerArgumentType.integer(0,1562)).executes(c->encounters(c.getSource(),IntegerArgumentType.getInteger(c,"page")))))
                .then(Commands.literal("route").then(Commands.literal("start").then(com.ultimakingdoms.interaction.NamedTargets.argument("id","route")
                        .executes(c->{var player=c.getSource().getPlayerOrException();var result=api(player).beginRoute(player,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"id","route"));
                            c.getSource().sendSuccess(()->Component.literal("Route: "+result.reason()),false);return result.allowed()?1:0;}))))
                .then(Commands.literal("resource").then(Commands.literal("request").then(com.ultimakingdoms.interaction.NamedTargets.argument("site","site")
                        .then(Commands.argument("commodity",ResourceLocationArgument.id()).executes(c->{var player=c.getSource().getPlayerOrException();
                            var result=api(player).requestNeutralResourceAccess(player,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"site","site"),ResourceLocationArgument.getId(c,"commodity"));
                            c.getSource().sendSuccess(()->Component.literal("Resource access: "+result.reason()),false);return result.allowed()?1:0;})))))
                .then(Commands.literal("author").requires(s->s.hasPermission(2))
                        .then(Commands.literal("site").then(Commands.argument("role",StringArgumentType.word()).executes(c->{var player=c.getSource().getPlayerOrException();
                            var id=service(player).authorSite(player,StringArgumentType.getString(c,"role"));c.getSource().sendSuccess(()->Component.literal(id.map(v->"Authored site "+v+"; a player must discover it in person.").orElse("Site was not saved.")),false);return id.isPresent()?1:0;})))
                        .then(Commands.literal("route").then(com.ultimakingdoms.interaction.NamedTargets.argument("fromInstitution","institution").then(com.ultimakingdoms.interaction.NamedTargets.argument("toInstitution","institution").executes(c->{
                            var player=c.getSource().getPlayerOrException();var id=service(player).authorRoute(player,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"fromInstitution","institution"),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"toInstitution","institution"));
                            c.getSource().sendSuccess(()->Component.literal(id.map(v->"Authored checkpoint route "+v).orElse("Route denied: both operational institutions and nearby discovered sites are required.")),false);return id.isPresent()?1:0;}))))
                        .then(Commands.literal("resource").then(com.ultimakingdoms.interaction.NamedTargets.argument("site","site").then(com.ultimakingdoms.interaction.NamedTargets.argument("returnSite","site")
                                .then(Commands.argument("commodity",ResourceLocationArgument.id()).then(Commands.argument("excluded",ResourceLocationArgument.id()).executes(c->{var player=c.getSource().getPlayerOrException();
                                    boolean ok=service(player).authorNeutralResource(player,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"site","site"),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"returnSite","site"),
                                            Set.of(ResourceLocationArgument.getId(c,"commodity")),Set.of(ResourceLocationArgument.getId(c,"excluded")));
                                    c.getSource().sendSuccess(()->Component.literal(ok?"Neutral access saved with an independent return site.":"Neutral access denied; both sites must be discovered and the resource site must be outside a settlement."),false);return ok?1:0;}))))))));
    }
    private static int sites(net.minecraft.commands.CommandSourceStack source,int page)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var player=source.getPlayerOrException();var values=api(player).sites(player,page*16,16);source.sendSuccess(()->Component.literal("Known world sites ("+values.size()+")"),false);
        values.forEach(v->source.sendSuccess(()->Component.literal(v.id()+" "+v.role()+" "+v.dimension()+" "+v.position().toShortString()
                +(v.contested()?" [contested]":v.controller().isBlank()?"":" [controller "+v.controller()+"]")),false));return values.size();
    }
    private static int routes(net.minecraft.commands.CommandSourceStack source,int page)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var player=source.getPlayerOrException();var values=api(player).routes(player,page*16,16);source.sendSuccess(()->Component.literal("Known institution routes ("+values.size()+")"),false);
        values.forEach(v->source.sendSuccess(()->Component.literal(v.id()+" checkpoint "+(v.nextCheckpoint()+1)+"/"+v.checkpoints().size()+" "+v.status()),false));return values.size();
    }
    private static int encounters(net.minecraft.commands.CommandSourceStack source,int page)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var player=source.getPlayerOrException();var values=api(player).encounters(player,page*16,16);source.sendSuccess(()->Component.literal("Proven encounter contributions ("+values.size()+")"),false);
        values.forEach(v->source.sendSuccess(()->Component.literal(v.encounter()+" contribution "+v.contribution()+" repeat "+v.repeat()+" receipt "+v.receipt()),false));return values.size();
    }
    private static WorldContextApi.Provider api(net.minecraft.server.level.ServerPlayer player){return WorldContextApi.get(player.getServer()).orElseThrow(()->new IllegalStateException("World context unavailable"));}
    private static WorldContextService service(net.minecraft.server.level.ServerPlayer player){var provider=api(player);if(provider instanceof WorldContextService service)return service;throw new IllegalStateException("World context provider unavailable");}
}
