package com.ultimakingdoms.factions.organization;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.api.factions.organization.OrganizationLifecycleResult;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Authenticated user surface for durable organization lifecycle transactions. */
public final class OrganizationLifecycleCommands {
    private OrganizationLifecycleCommands() { }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var lifecycle=Commands.literal("lifecycle");
        lifecycle.then(Commands.literal("status").then(Commands.argument("organization",ResourceLocationArgument.id()).executes(c->{
            var service=OrganizationApi.get(c.getSource().getServer());var id=ResourceLocationArgument.getId(c,"organization");
            var resolved=service.resolve(id);var view=service.lifecycle(id);
            c.getSource().sendSuccess(()->Component.literal(id+" -> "+resolved.map(Object::toString).orElse("unavailable")
                    +view.map(value->" | "+value.state()+" | "+value.displayName()+" | revision "+value.revision()).orElse("")),false);return resolved.isPresent()?1:0;
        })));
        lifecycle.then(Commands.literal("found").then(Commands.argument("organization",ResourceLocationArgument.id())
                .then(Commands.argument("template",ResourceLocationArgument.id()).then(Commands.argument("kingdom",StringArgumentType.word())
                .then(Commands.argument("revision",LongArgumentType.longArg(0)).then(Commands.argument("name",StringArgumentType.greedyString()).executes(c->{
                    var player=c.getSource().getPlayerOrException();return show(c.getSource(),OrganizationApi.get(player.getServer()).found(player,
                            ResourceLocationArgument.getId(c,"organization"),ResourceLocationArgument.getId(c,"template"),
                            StringArgumentType.getString(c,"kingdom"),StringArgumentType.getString(c,"name"),LongArgumentType.getLong(c,"revision")));
                })))))));
        lifecycle.then(Commands.literal("merge").then(Commands.literal("propose").then(Commands.argument("source",ResourceLocationArgument.id())
                .then(Commands.argument("target",ResourceLocationArgument.id()).then(Commands.argument("revision",LongArgumentType.longArg(0)).executes(c->{
                    var player=c.getSource().getPlayerOrException();return show(c.getSource(),OrganizationApi.get(player.getServer()).proposeMerge(player,
                            ResourceLocationArgument.getId(c,"source"),ResourceLocationArgument.getId(c,"target"),LongArgumentType.getLong(c,"revision")));
                })))))
                .then(mergeAction("consent"))
                .then(mergeAction("opt_out"))
                .then(mergeAction("finalize"))
                .then(Commands.literal("novate").then(com.ultimakingdoms.interaction.NamedTargets.argument("merge","merge").then(com.ultimakingdoms.interaction.NamedTargets.argument("obligation","merge_obligation")
                        .then(Commands.argument("revision",LongArgumentType.longArg(0)).executes(c->{
                            var player=c.getSource().getPlayerOrException();return show(c.getSource(),OrganizationApi.get(player.getServer()).novateMergeObligation(player,
                                    com.ultimakingdoms.interaction.NamedTargets.uuid(c,"merge","merge"),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"obligation","merge_obligation"),LongArgumentType.getLong(c,"revision")));
                        }))))));
        lifecycle.then(Commands.literal("dissolve").then(Commands.argument("organization",ResourceLocationArgument.id())
                .then(Commands.argument("revision",LongArgumentType.longArg(0)).executes(c->{
                    var player=c.getSource().getPlayerOrException();return show(c.getSource(),OrganizationApi.get(player.getServer()).dissolve(player,
                            ResourceLocationArgument.getId(c,"organization"),LongArgumentType.getLong(c,"revision")));
                }))));
        event.getDispatcher().register(Commands.literal("ultima").then(Commands.literal("guild").then(lifecycle)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> mergeAction(String action) {
        return Commands.literal(action).then(com.ultimakingdoms.interaction.NamedTargets.argument("merge","merge").then(Commands.argument("revision",LongArgumentType.longArg(0)).executes(c->{
            var player=c.getSource().getPlayerOrException();var service=OrganizationApi.get(player.getServer());var id=com.ultimakingdoms.interaction.NamedTargets.uuid(c,"merge","merge");long revision=LongArgumentType.getLong(c,"revision");
            return show(c.getSource(),switch(action){case "consent"->service.consentMerge(player,id,revision);case "opt_out"->service.optOutMerge(player,id,revision);default->service.finalizeMerge(player,id,revision);});
        })));
    }

    private static int show(net.minecraft.commands.CommandSourceStack source, OrganizationLifecycleResult result) {
        source.sendSuccess(()->Component.literal(result.status()+": "+result.reason()+" | revision "+result.revision()),false);
        result.merge().ifPresent(value->source.sendSuccess(()->Component.literal("Merge "+value.id()+" "+value.source()+" -> "+value.target()+" | "+value.state()+" | opt-outs "+value.memberOptOuts().size()),false));
        result.obligations().forEach(value->source.sendSuccess(()->Component.literal("Blocking commission "+value.id()+" | player "+value.player()+" | quest "+value.quest()),false));
        return result.status()==OrganizationLifecycleResult.Status.APPLIED||result.status()==OrganizationLifecycleResult.Status.PENDING_CONSENT?1:0;
    }
}
