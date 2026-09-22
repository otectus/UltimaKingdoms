package com.ultimakingdoms.evolution;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.Locale;

public final class EvolutionCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("ultima-evolution");
        root.then(Commands.literal("family_introduction").executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).familyIntroduction(c.getSource().getPlayerOrException()))));
        root.then(Commands.literal("list").executes(c -> page(c, 0)).then(Commands.argument("offset", IntegerArgumentType.integer(0, 4096)).executes(c -> page(c, IntegerArgumentType.getInteger(c, "offset")))));
        root.then(Commands.literal("inspect").then(com.ultimakingdoms.interaction.NamedTargets.argument("scenario","scenario").executes(c -> run(c, () -> {
            var p = c.getSource().getPlayerOrException(); EvolutionRuntime.get(p.getServer()).inspect(p, com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario")).forEach(s -> p.sendSystemMessage(Component.literal(s))); return "Scenario inspected.";
        }))));
        root.then(Commands.literal("configure").requires(s -> s.hasPermission(2)).then(Commands.argument("enabled", BoolArgumentType.bool())
                .then(Commands.argument("drama", BoolArgumentType.bool()).executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).configure(c.getSource().getPlayerOrException(), BoolArgumentType.getBool(c, "enabled"), BoolArgumentType.getBool(c, "drama")))))));
        root.then(Commands.literal("region").requires(s -> s.hasPermission(2)).then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement")
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).region(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"), BoolArgumentType.getBool(c, "enabled")))))));
        root.then(Commands.literal("digest").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).subscribe(c.getSource().getPlayerOrException(), BoolArgumentType.getBool(c, "enabled"))))));
        for (String verb : new String[]{"contribute", "resolve"}) {
            var branch = Commands.literal(verb);
            for (var outcome : EvolutionState.Outcome.values()) {
                if (verb.equals("contribute") && outcome == EvolutionState.Outcome.DECLINE) continue;
                var revision = Commands.argument("revision", LongArgumentType.longArg(1));
                if (verb.equals("contribute")) revision.executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).contribute(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario"), LongArgumentType.getLong(c, "revision"), outcome)));
                else {
                    revision.executes(c -> resolve(c, outcome, ""));
                    revision.then(Commands.argument("counterpart", StringArgumentType.word()).executes(c -> resolve(c, outcome, StringArgumentType.getString(c, "counterpart"))));
                }
                branch.then(Commands.literal(outcome.name().toLowerCase(Locale.ROOT)).then(com.ultimakingdoms.interaction.NamedTargets.argument("scenario","scenario").then(revision)));
            }
            root.then(branch);
        }
        root.then(Commands.literal("withdraw").then(com.ultimakingdoms.interaction.NamedTargets.argument("scenario","scenario").then(Commands.argument("revision", LongArgumentType.longArg(1)).executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).withdraw(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario"), LongArgumentType.getLong(c, "revision")))))));
        root.then(Commands.literal("retry").then(com.ultimakingdoms.interaction.NamedTargets.argument("scenario","scenario").executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).retry(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario"))))));
        root.then(Commands.literal("release_rejected").then(com.ultimakingdoms.interaction.NamedTargets.argument("scenario","scenario").executes(c -> run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).releaseRejected(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario"))))));
        root.then(Commands.literal("contract").then(com.ultimakingdoms.interaction.NamedTargets.argument("scope","scope").then(com.ultimakingdoms.interaction.NamedTargets.argument("giver","npc")
                .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> run(c, () -> EvolutionContracts.offer(c.getSource().getPlayerOrException(),
                        com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scope","scope"), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"giver","npc"),
                        com.ultimakingdoms.warfare.contracts.CivilianContractKind.parse("evolving_" + StringArgumentType.getString(c, "kind")))))))));
        event.getDispatcher().register(root);
    }
    private static int resolve(CommandContext<CommandSourceStack> c, EvolutionState.Outcome outcome, String counterpart) {
        return run(c, () -> EvolutionRuntime.get(c.getSource().getServer()).resolve(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"scenario","scenario"), LongArgumentType.getLong(c, "revision"), outcome, counterpart));
    }
    private static int page(CommandContext<CommandSourceStack> c, int offset) {
        return run(c, () -> { var p = c.getSource().getPlayerOrException(); EvolutionRuntime.get(p.getServer()).page(p, offset).forEach(s -> p.sendSystemMessage(Component.literal(s))); return "Use /ultima-evolution inspect <scenario> for causes and choices."; });
    }
    private interface Work { String run() throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private static int run(CommandContext<CommandSourceStack> c, Work work) {
        try { String result = work.run(); c.getSource().sendSuccess(() -> Component.literal(result), false); return 1; }
        catch (IllegalArgumentException | IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException failure) { c.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
    }
    private EvolutionCommands() { }
}
