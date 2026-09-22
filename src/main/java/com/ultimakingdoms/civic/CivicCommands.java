package com.ultimakingdoms.civic;

import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.compat.recruits.RecruitsObservation;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public final class CivicCommands {
    private CivicCommands() { }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        registerServices(event);
        event.getDispatcher().register(Commands.literal("ultima")
            .then(Commands.literal("guild")
                .executes(c -> {
                    var player=c.getSource().getPlayerOrException(); var service=OrganizationApi.get(player.getServer());
                    var own=service.ownSnapshot(player);
                    c.getSource().sendSuccess(() -> Component.literal("Guilds: " + service.definitions().size() + ". Citizenship and military allegiance remain separate."),false);
                    for(var definition:service.definitions()) {
                        var membership=own.memberships().stream().filter(m -> m.organizationId().equals(definition.id())).findFirst();
                        c.getSource().sendSuccess(() -> Component.literal(definition.id()+": "+membership.map(m -> m.status()+", standing "+m.standing()+", deeds "+m.deedCount()).orElse("unaffiliated")),false);
                    }
                    return 1;
                })
                .then(Commands.literal("join").then(Commands.argument("organization",ResourceLocationArgument.id())
                    .suggests((c,b) -> SharedSuggestionProvider.suggestResource(OrganizationApi.get(c.getSource().getServer()).definitions().stream().map(d -> d.id()),b))
                    .executes(c -> {
                        var result=OrganizationApi.get(c.getSource().getServer()).join(c.getSource().getPlayerOrException(),ResourceLocationArgument.getId(c,"organization"));
                        c.getSource().sendSuccess(() -> CivicText.reason(result.reason()),false); return result.applied()?1:0;
                    })))
                .then(Commands.literal("leave").then(Commands.argument("organization",ResourceLocationArgument.id()).executes(c -> {
                    var result=OrganizationApi.get(c.getSource().getServer()).leave(c.getSource().getPlayerOrException(),ResourceLocationArgument.getId(c,"organization"));
                    c.getSource().sendSuccess(() -> CivicText.reason(result.reason()),false); return result.applied()?1:0;
                })))
                .then(Commands.literal("explain").then(Commands.argument("organization",ResourceLocationArgument.id())
                    .then(Commands.argument("permission",ResourceLocationArgument.id()).executes(c -> {
                        var result=OrganizationApi.get(c.getSource().getServer()).explainOwn(c.getSource().getPlayerOrException(),ResourceLocationArgument.getId(c,"organization"),ResourceLocationArgument.getId(c,"permission"));
                        var explanation=Component.literal(result.decision()+": ");
                        result.reasons().forEach(reason -> explanation.append(CivicText.reason(reason)).append(" "));
                        c.getSource().sendSuccess(() -> explanation,false); return 1;
                    })))))
            .then(Commands.literal("military").then(Commands.literal("here").executes(c -> {
                var view=RecruitsObservation.here(c.getSource().getPlayerOrException());
                c.getSource().sendSuccess(() -> Component.literal("Recruits: "+view.status()+" "+view.version()+
                        (view.claimId().isEmpty()?"":" | owner "+view.ownerId()+" | siege "+view.underSiege())),false); return 1;
            })))
            .then(Commands.literal("integrations").requires(s -> s.hasPermission(2)).executes(c -> {
                for(String id:new String[]{"mca","mcaquests","mcareputation","mcacrime","townstead","recruits","dotcoinmod","runicskills","runic_gods","runic_races"}) {
                    var mod=ModList.get().getModContainerById(id);
                    c.getSource().sendSuccess(() -> Component.literal(id+": "+mod.map(m -> "installed "+m.getModInfo().getVersion()).orElse("absent")),false);
                }
                c.getSource().sendSuccess(() -> Component.literal("Civic network: "+CivicRuntime.get(c.getSource().getServer()).diagnostic()),false);
                var knowledge=com.ultimakingdoms.knowledge.SettlementKnowledge.get(c.getSource().getServer());
                c.getSource().sendSuccess(() -> Component.literal(knowledge.writable()?"Discovery storage: available":knowledge.diagnostic()),false);
                c.getSource().sendSuccess(() -> Component.literal("Installation is not an API guarantee. Guild quest credit requires the durable completion API. Recruits access is observation only, audited for 1.15.2."),false); return 1;
            })));
    }
    private static int result(net.minecraft.commands.CommandSourceStack source,CivicService.Result result) {
        source.sendSuccess(()->CivicText.reason(result.reason()),false);
        result.settlement().flatMap(com.ultimakingdoms.api.UltimaKingdomsApi.get(source.getServer())::getSettlement)
                .ifPresent(s->source.sendSuccess(()->Component.translatable("civic.introduction_destination",s.displayName(),s.anchor().toShortString()),false));
        return result.success()?1:0;
    }
    private static void registerServices(RegisterCommandsEvent event) {
        var guild=Commands.literal("guild");
        guild.then(Commands.literal("qualify").then(Commands.argument("kind",com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"skill_level","deity","race","lore_collected"},b))
                .then(Commands.argument("subject",com.mojang.brigadier.arguments.StringArgumentType.string())
                .then(Commands.argument("minimum",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,1000000)).executes(c->{
                    var p=c.getSource().getPlayerOrException();
                    try {
                        var kind=com.ultimakingdoms.api.progression.ProgressionPredicate.Kind.valueOf(com.mojang.brigadier.arguments.StringArgumentType.getString(c,"kind").toUpperCase(java.util.Locale.ROOT));
                        var q=new com.ultimakingdoms.api.progression.ProgressionPredicate(kind,com.mojang.brigadier.arguments.StringArgumentType.getString(c,"subject"),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"minimum"));
                        var answer=com.ultimakingdoms.api.progression.ProgressionApi.evaluate(p,q);
                        c.getSource().sendSuccess(()->Component.literal(answer.status()+": "+(answer.matches()?"qualified":"not qualified")+" ("+answer.reason()+")"),false);
                        return answer.matches()?1:0;
                    } catch(IllegalArgumentException failure) { c.getSource().sendFailure(Component.literal("Unknown qualification kind or invalid predicate."));return 0; }
                })))));

        for(String action:new String[]{"introduction","commissions","hospitality","workshop"}) guild.then(Commands.literal(action)
                .then(Commands.argument("organization",ResourceLocationArgument.id()).executes(c->{
                    var p=c.getSource().getPlayerOrException();var service=CivicRuntime.get(p.getServer());
                    var npc=service.nearbyContact(p,ResourceLocationArgument.getId(c,"organization"));
                    return result(c.getSource(),npc.isEmpty()?CivicService.Result.deny("civic.contact_unavailable"):
                            action.equals("introduction")?service.introduction(p,npc.get()):action.equals("hospitality")?service.hospitality(p,npc.get()):action.equals("workshop")?service.workshop(p,npc.get()):service.commissions(p,npc.get()));
                })));
        guild.then(Commands.literal("chapters").executes(c->{
            var p=c.getSource().getPlayerOrException();
            for(var chapter:CivicRuntime.get(p.getServer()).knownChapters(p,0))c.getSource().sendSuccess(()->Component.literal(
                    chapter.id()+" | "+chapter.organization()+" | "+chapter.settlementName()+" | "+chapter.status()+" | contacts "+chapter.contacts()),false);
            return 1;
        }));
        guild.then(Commands.literal("institutions").executes(c->{
            var p=c.getSource().getPlayerOrException();
            for(var institution:com.ultimakingdoms.api.politics.UltimaPoliticsApi.get(p.getServer()).knownInstitutions(p,0,32))
                c.getSource().sendSuccess(()->Component.literal(institution.id()+" | "+institution.type()+" | "+institution.status()),false);
            return 1;
        }));
        guild.then(Commands.literal("charter").then(Commands.argument("organization",ResourceLocationArgument.id())
                .then(com.ultimakingdoms.interaction.NamedTargets.argument("institution","institution").executes(c->{
                    var p=c.getSource().getPlayerOrException();return result(c.getSource(),CivicRuntime.get(p.getServer()).charter(p,
                            ResourceLocationArgument.getId(c,"organization"),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"institution","institution")));
                }))));
        guild.then(Commands.literal("appoint").then(com.ultimakingdoms.interaction.NamedTargets.argument("chapter","chapter")
                .then(Commands.argument("npc",net.minecraft.commands.arguments.EntityArgument.entity()).executes(c->{
                    var p=c.getSource().getPlayerOrException();return result(c.getSource(),CivicRuntime.get(p.getServer()).appoint(p,
                            com.ultimakingdoms.interaction.NamedTargets.uuid(c,"chapter","chapter"),net.minecraft.commands.arguments.EntityArgument.getEntity(c,"npc")));
                }))));
        guild.then(Commands.literal("dismiss").then(com.ultimakingdoms.interaction.NamedTargets.argument("npc","contact").executes(c->{
            var p=c.getSource().getPlayerOrException();return result(c.getSource(),CivicRuntime.get(p.getServer()).dismiss(p,
                    com.ultimakingdoms.interaction.NamedTargets.uuid(c,"npc","contact")));
        })));
        event.getDispatcher().register(Commands.literal("ultima").then(guild));
    }

}
