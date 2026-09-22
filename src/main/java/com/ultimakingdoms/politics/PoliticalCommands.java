package com.ultimakingdoms.politics;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.UUID;
import java.util.*;

public final class PoliticalCommands {
    private PoliticalCommands() { }
    private static String selector(String name){return switch(name){case "kingdom","counterpart"->"kingdom";case "settlement"->"settlement";case "profile"->"government_definition";case "office"->"office_definition";case "type"->"agreement_definition";case "honor"->"honor_definition";case "agreement"->"agreement";case "petition"->"petition";case "record"->"political_record";case "permission"->"political_permission";default->"";};}
    private static RequiredArgumentBuilder<CommandSourceStack, String> word(String name) {String kind=selector(name);return kind.isEmpty()?Commands.argument(name, com.ultimakingdoms.command.SettlementSelectorArgument.settlement()):com.ultimakingdoms.interaction.NamedTargets.argument(name,kind); }
    private static RequiredArgumentBuilder<CommandSourceStack, String> text(String name) { return Commands.argument(name, StringArgumentType.greedyString()); }
    private static String arg(CommandContext<CommandSourceStack> c, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {String kind=selector(name);return kind.isEmpty()?StringArgumentType.getString(c,name):com.ultimakingdoms.interaction.NamedTargets.value(c,name,kind); }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("politics");
        root.then(Commands.literal("diagnose").requires(source -> source.hasPermission(2)).then(word("kingdom").executes(c -> inspect(c,"overview"))));
        root.then(Commands.literal("inspect").then(word("kingdom").executes(c -> inspect(c, "overview")).then(word("tab").executes(c -> inspect(c, arg(c,"tab"))))));
        root.then(Commands.literal("bootstrap").requires(s -> s.hasPermission(2)).then(word("kingdom").then(word("settlement").then(word("profile")
                .then(Commands.argument("leader", EntityArgument.player()).executes(c -> run(c, Action.BOOTSTRAP, arg(c,"profile"), arg(c,"settlement"),
                        new Person(EntityArgument.getPlayer(c,"leader").getUUID(), Kind.PLAYER), "", "", "")))))));
        root.then(Commands.literal("seat").then(word("kingdom").then(word("settlement").executes(c -> run(c,Action.SEAT,"",arg(c,"settlement"),null,"","","")))));
        root.then(Commands.literal("appoint").then(word("kingdom").then(word("office").then(Commands.argument("person",EntityArgument.entity())
                .executes(c -> run(c,Action.APPOINT,arg(c,"office"),"",person(EntityArgument.getEntity(c,"person")),"","",""))
                .then(word("settlement").executes(c -> run(c,Action.APPOINT,arg(c,"office"),arg(c,"settlement"),person(EntityArgument.getEntity(c,"person")),"","","")))))));
        root.then(Commands.literal("remove").then(word("kingdom").then(word("office")
                .executes(c -> run(c,Action.REMOVE_OFFICE,arg(c,"office"),"",null,"","",""))
                .then(word("settlement").executes(c -> run(c,Action.REMOVE_OFFICE,arg(c,"office"),arg(c,"settlement"),null,"","",""))))));
        root.then(Commands.literal("delegate").then(word("kingdom").then(Commands.argument("person",EntityArgument.player()).then(word("permission")
                .executes(c -> run(c,Action.DELEGATE,arg(c,"permission"),"",person(EntityArgument.getPlayer(c,"person")),"","",""))))));
        root.then(Commands.literal("revoke").then(word("kingdom").then(Commands.argument("person",EntityArgument.player())
                .executes(c -> run(c,Action.REVOKE,"","",person(EntityArgument.getPlayer(c,"person")),"","","")))));
        root.then(Commands.literal("propose").then(word("kingdom").then(word("counterpart").then(word("type").then(text("terms")
                .executes(c -> run(c,Action.PROPOSE,arg(c,"type"),"",null,arg(c,"counterpart"),arg(c,"terms"),"")))))));
        root.then(Commands.literal("sign").then(word("kingdom").then(word("agreement").executes(c -> run(c,Action.SIGN,"",arg(c,"agreement"),null,"","","" )).then(word("hash")
                .executes(c -> run(c,Action.SIGN,"",arg(c,"agreement"),null,"","",arg(c,"hash")))))));
        for (Action action : new Action[]{Action.DECLINE,Action.WITHDRAW,Action.TERMINATE,Action.REVALIDATE,Action.SUSPEND_RECOGNITION,Action.REVOKE_HONOR})
            root.then(Commands.literal(action.name().toLowerCase(java.util.Locale.ROOT)).then(word("kingdom").then(word("record")
                    .executes(c -> run(c,action,"",arg(c,"record"),null,"","","")))));
        for (Action action : new Action[]{Action.REVIEW,Action.APPROVE,Action.REJECT})
            root.then(Commands.literal(action.name().toLowerCase(java.util.Locale.ROOT)).then(word("kingdom").then(word("petition").then(text("reason")
                    .executes(c -> run(c,action,"",arg(c,"petition"),null,"",arg(c,"reason"),""))))));
        root.then(Commands.literal("honor").then(word("kingdom").then(word("honor").then(Commands.argument("person",EntityArgument.entity()).then(text("reason")
                .executes(c -> run(c,Action.HONOR,arg(c,"honor"),"",person(EntityArgument.getEntity(c,"person")),"",arg(c,"reason"),"")))))));
        root.then(Commands.literal("name_successor").then(word("kingdom").then(Commands.argument("person",EntityArgument.entity())
                .executes(c -> run(c,Action.NAME_SUCCESSOR,"","",person(EntityArgument.getEntity(c,"person")),"","","")))));
        for (Action action : new Action[]{Action.ABDICATE,Action.SUCCEED}) root.then(Commands.literal(action.name().toLowerCase(java.util.Locale.ROOT))
                .then(word("kingdom").executes(c -> run(c,action,"","",null,"","",""))));
        root.then(Commands.literal("transition_rule").then(word("kingdom").then(Commands.argument("elections",BoolArgumentType.bool())
                .then(Commands.argument("regency",BoolArgumentType.bool()).then(Commands.argument("election_ticks",LongArgumentType.longArg(1200,2419200))
                .then(Commands.argument("grace_ticks",LongArgumentType.longArg(200,172800)).then(Commands.argument("regency_ticks",LongArgumentType.longArg(1200,2419200))
                .then(text("permissions").executes(PoliticalCommands::transitionRule)))))))));
        root.then(Commands.literal("election_open").then(word("kingdom").then(Commands.argument("first",EntityArgument.player()).then(Commands.argument("second",EntityArgument.player())
                .executes(PoliticalCommands::openElection)))));
        root.then(Commands.literal("election_vote").then(com.ultimakingdoms.interaction.NamedTargets.argument("election","election").then(Commands.argument("candidate",EntityArgument.player())
                .executes(PoliticalCommands::castBallot))));
        root.then(Commands.literal("election_close").then(com.ultimakingdoms.interaction.NamedTargets.argument("election","election")
                .executes(c->transition(c,(service,player)->service.closeElection(player,UUID.randomUUID(),service.revision(),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"election","election"))))));
        root.then(Commands.literal("election_inspect").then(com.ultimakingdoms.interaction.NamedTargets.argument("election","election").executes(c->{var player=c.getSource().getPlayerOrException();var view=UltimaPoliticsApi.get(player.getServer()).election(player,com.ultimakingdoms.interaction.NamedTargets.uuid(c,"election","election"));
            if(view.isEmpty()){c.getSource().sendFailure(Component.literal("Election unavailable"));return 0;}c.getSource().sendSuccess(()->Component.literal(view.get().toString()),false);return 1;})));
        root.then(Commands.literal("appoint_regent").then(word("kingdom").then(Commands.argument("person",EntityArgument.player())
                .executes(PoliticalCommands::appointRegent))));
        root.then(Commands.literal("end_regency").then(word("kingdom").executes(c->transition(c,(service,player)->service.endRegency(player,UUID.randomUUID(),service.revision(),arg(c,"kingdom"))))));
        event.getDispatcher().register(Commands.literal("ultima").then(root));
    }
    private static int transitionRule(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Set<Permission> permissions=new LinkedHashSet<>();for(String value:arg(c,"permissions").split(","))if(!value.isBlank())permissions.add(Permission.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        var rule=new PoliticalTransition.Rule(BoolArgumentType.getBool(c,"elections"),BoolArgumentType.getBool(c,"regency"),LongArgumentType.getLong(c,"election_ticks"),LongArgumentType.getLong(c,"grace_ticks"),LongArgumentType.getLong(c,"regency_ticks"),8,permissions);
        return transition(c,(service,player)->service.adoptTransitionRule(player,UUID.randomUUID(),service.revision(),arg(c,"kingdom"),rule));
    }
    private static int openElection(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID first=EntityArgument.getPlayer(c,"first").getUUID(),second=EntityArgument.getPlayer(c,"second").getUUID();String kingdom=arg(c,"kingdom");
        return transition(c,(service,player)->service.openElection(player,UUID.randomUUID(),service.revision(),kingdom,List.of(first,second)));
    }
    private static int castBallot(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID election=com.ultimakingdoms.interaction.NamedTargets.uuid(c,"election","election"),candidate=EntityArgument.getPlayer(c,"candidate").getUUID();
        return transition(c,(service,player)->service.castBallot(player,UUID.randomUUID(),service.revision(),election,candidate));
    }
    private static int appointRegent(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID regent=EntityArgument.getPlayer(c,"person").getUUID();String kingdom=arg(c,"kingdom");
        return transition(c,(service,player)->service.appointRegent(player,UUID.randomUUID(),service.revision(),kingdom,regent));
    }
    private interface TransitionWork { Result run(PoliticalService service,net.minecraft.server.level.ServerPlayer player) throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private static int transition(CommandContext<CommandSourceStack> c,TransitionWork work) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try{var player=c.getSource().getPlayerOrException();Result result=work.run(UltimaPoliticsApi.get(player.getServer()),player);if(result.success())c.getSource().sendSuccess(()->Component.literal(result.message()+": "+result.recordId()),false);else c.getSource().sendFailure(Component.literal(result.message()));return result.success()?1:0;}
        catch(IllegalArgumentException|IllegalStateException failure){c.getSource().sendFailure(Component.literal(failure.getMessage()));return 0;}
    }
    private static Person person(Entity entity) { return new Person(entity.getUUID(), entity instanceof Player ? Kind.PLAYER : Kind.NPC); }
    private static int run(CommandContext<CommandSourceStack> c, Action action, String definition, String target, Person person, String counterpart, String text, String hash)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = c.getSource().getPlayerOrException(); var service = UltimaPoliticsApi.get(player.getServer());
        if(action==Action.SIGN&&hash.isEmpty()){
            String kingdom=arg(c,"kingdom");for(int offset=0;offset<=100000;offset+=20){var page=service.page(player,UUID.randomUUID(),kingdom,"agreements",offset);var row=page.rows().stream().filter(r->r.id().equals(target)).findFirst();if(row.isPresent()){hash=row.get().termsHash();break;}if(!page.hasMore())break;}
        }
        Request request = new Request(UUID.randomUUID(), service.revision(), action, arg(c,"kingdom"), definition, target, person, counterpart, text, hash, 0,0,0);
        Result result = service.execute(player, request);
        if (result.success()) c.getSource().sendSuccess(() -> Component.literal(result.message() + ": " + result.recordId()), false);
        else c.getSource().sendFailure(Component.literal(result.message()));
        return result.success() ? 1 : 0;
    }
    private static int inspect(CommandContext<CommandSourceStack> c, String tab) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        Page page = UltimaPoliticsApi.get(player.getServer()).page(player,UUID.randomUUID(),arg(c,"kingdom"),tab,0);
        if (!page.diagnostic().isEmpty()) c.getSource().sendFailure(Component.literal(page.diagnostic()));
        for (Row row : page.rows()) c.getSource().sendSuccess(() -> Component.literal(row.id()+" "+row.title()+": "+row.detail()+(row.termsHash().isEmpty()?"":" [terms hash: "+row.termsHash()+"]")),false);
        return 1;
    }
}
