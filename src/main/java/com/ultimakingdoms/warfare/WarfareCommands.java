package com.ultimakingdoms.warfare;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class WarfareCommands {
    private WarfareCommands() { }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("warfare");
        root.then(Commands.literal("here").executes(c -> run(c, () -> {
            var player = c.getSource().getPlayerOrException();
            WarfareRuntime.get(player.getServer()).here(player).forEach(line -> player.sendSystemMessage(Component.literal(line)));
            return "Campaigns and accords use /ultima warfare. Temporary orders use /ultima-mobilization; civilian work uses /ultima-contract.";
        })));
        root.then(Commands.literal("inspect").then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement").executes(c -> run(c, () -> {
            var player = c.getSource().getPlayerOrException();
            WarfareRuntime.get(player.getServer()).view(player, com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"))
                    .forEach(line -> player.sendSystemMessage(Component.literal(line)));
            return "Control history inspection complete.";
        }))));
        root.then(Commands.literal("map_here").requires(s -> s.hasPermission(2))
                .then(Commands.argument("kingdom", ResourceLocationArgument.id()).executes(c -> run(c, () ->
                        WarfareRuntime.get(c.getSource().getServer()).mapHere(c.getSource().getPlayerOrException(), ResourceLocationArgument.getId(c, "kingdom"))))));
        root.then(Commands.literal("bind_here").requires(s -> s.hasPermission(2))
                .then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement").executes(c -> run(c, () ->
                        WarfareRuntime.get(c.getSource().getServer()).bindHere(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"))))));
        root.then(Commands.literal("retire").requires(s -> s.hasPermission(2)).then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement")
                .then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).executes(c -> run(c, () ->
                        WarfareRuntime.get(c.getSource().getServer()).retire(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"),
                                com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "revision")))))));
        root.then(Commands.literal("campaigns").then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement").executes(c -> run(c, () -> {
            var player = c.getSource().getPlayerOrException();
            CampaignService.get(player.getServer()).page(player, com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement")).forEach(s -> player.sendSystemMessage(Component.literal(s)));
            return "Political campaign and settlement decisions.";
        }))));
        var declare = Commands.literal("declare");
        for (CampaignState.Goal goal : CampaignState.Goal.values())
            declare.then(Commands.literal(goal.name().toLowerCase(java.util.Locale.ROOT))
                    .then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement").then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                            .then(Commands.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> run(c, () ->
                                    CampaignService.get(c.getSource().getServer()).declare(c.getSource().getPlayerOrException(), java.util.UUID.randomUUID(),
                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(c,"revision"), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"), goal,
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(c,"reason"))))))));
        root.then(declare);
        root.then(Commands.literal("retry_campaign").then(com.ultimakingdoms.interaction.NamedTargets.argument("campaign","campaign").executes(c -> run(c, () ->
                CampaignService.get(c.getSource().getServer()).applyDeclaration(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"campaign","campaign"))))));
        root.then(Commands.literal("withdraw").then(com.ultimakingdoms.interaction.NamedTargets.argument("campaign","campaign").executes(c -> run(c, () ->
                CampaignService.get(c.getSource().getServer()).withdraw(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"campaign","campaign"))))));
        var accord = Commands.literal("accord");
        for (CampaignState.Sovereignty status : CampaignState.Sovereignty.values())
            accord.then(Commands.literal(status.name().toLowerCase(java.util.Locale.ROOT)).then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement")
                    .then(Commands.argument("counterpart", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("kingdom", ResourceLocationArgument.id()).then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                    .then(Commands.argument("terms", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> run(c, () ->
                                            CampaignService.get(c.getSource().getServer()).propose(c.getSource().getPlayerOrException(), java.util.UUID.randomUUID(),
                                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(c,"revision"), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c,"counterpart"), CampaignState.Relation.NEUTRAL,
                                                    status, ResourceLocationArgument.getId(c,"kingdom").toString(), com.mojang.brigadier.arguments.StringArgumentType.getString(c,"terms"))))))))));
        accord.then(Commands.literal("sign").then(com.ultimakingdoms.interaction.NamedTargets.argument("accord","accord")
                .then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).executes(c -> run(c, () ->
                        CampaignService.get(c.getSource().getServer()).sign(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"accord","accord"),
                                com.mojang.brigadier.arguments.LongArgumentType.getLong(c,"revision")))))));
        accord.then(Commands.literal("apply").then(com.ultimakingdoms.interaction.NamedTargets.argument("accord","accord").executes(c -> run(c, () ->
                CampaignService.get(c.getSource().getServer()).applyAccord(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"accord","accord"))))));
        accord.then(Commands.literal("reject").then(com.ultimakingdoms.interaction.NamedTargets.argument("accord","accord")
                .then(Commands.argument("revision", com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).executes(c -> run(c, () ->
                        CampaignService.get(c.getSource().getServer()).reject(c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"accord","accord"),
                                com.mojang.brigadier.arguments.LongArgumentType.getLong(c,"revision")))))));
        root.then(accord);
        event.getDispatcher().register(Commands.literal("ultima").then(root));
    }
    private interface Operation { String run() throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private static int run(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, Operation operation)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            String result = operation.run(); context.getSource().sendSuccess(() -> Component.literal(result), false); return 1;
        } catch (IllegalArgumentException failure) {
            context.getSource().sendFailure(Component.literal(failure.getMessage())); return 0;
        }
    }
}
