package com.ultimakingdoms.evolution.drama;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.UUID;

/** Explicit player surface. No command grants authority that the native providers reject. */
public final class DramaCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("ultima-drama");
        root.then(Commands.literal("list").executes(c -> page(c, 0)).then(Commands.argument("offset", IntegerArgumentType.integer(0, 4096)).executes(c -> page(c, IntegerArgumentType.getInteger(c, "offset")))));
        root.then(Commands.literal("inspect").then(com.ultimakingdoms.interaction.NamedTargets.argument("drama","drama").executes(c -> run(c, () -> {
            var player = c.getSource().getPlayerOrException(); DramaRuntime.get(player.getServer()).inspect(player, com.ultimakingdoms.interaction.NamedTargets.uuid(c,"drama","drama")).forEach(s -> player.sendSystemMessage(Component.literal(s))); return "Drama inspected.";
        }))));
        var proposal = com.ultimakingdoms.interaction.NamedTargets.argument("request","request").then(Commands.argument("revision", LongArgumentType.longArg(0))
                .then(Commands.argument("template", StringArgumentType.word()).then(com.ultimakingdoms.interaction.NamedTargets.argument("settlement","settlement")
                        .executes(c -> propose(c, "")).then(Commands.argument("subject", StringArgumentType.word()).executes(c -> propose(c, StringArgumentType.getString(c, "subject")))))));
        root.then(Commands.literal("propose").then(proposal));
        root.then(action("consent", (service, player, id, revision) -> service.consent(player, id, revision)));
        root.then(action("execute", (service, player, id, revision) -> service.execute(player, id, revision)));
        root.then(action("negotiate", (service, player, id, revision) -> service.negotiate(player, id, revision)));
        root.then(action("exit", (service, player, id, revision) -> service.exit(player, id, revision)));
        root.then(action("recover", (service, player, id, revision) -> service.recover(player, id, revision)));
        event.getDispatcher().register(root);
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> action(String name, Action action) {
        return Commands.literal(name).then(com.ultimakingdoms.interaction.NamedTargets.argument("drama","drama").then(Commands.argument("revision", LongArgumentType.longArg(1))
                .executes(c -> run(c, () -> { var player = c.getSource().getPlayerOrException(); return action.run(DramaRuntime.get(player.getServer()), player,
                        com.ultimakingdoms.interaction.NamedTargets.uuid(c,"drama","drama"), LongArgumentType.getLong(c, "revision")); }))));
    }
    private static int propose(CommandContext<CommandSourceStack> c, String subject) {
        return run(c, () -> { var player = c.getSource().getPlayerOrException(); return DramaRuntime.get(player.getServer()).propose(player,
                com.ultimakingdoms.interaction.NamedTargets.uuid(c,"request","request"), LongArgumentType.getLong(c, "revision"), StringArgumentType.getString(c, "template"),
                com.ultimakingdoms.interaction.NamedTargets.uuid(c,"settlement","settlement"), subject); });
    }
    private static int page(CommandContext<CommandSourceStack> c, int offset) {
        return run(c, () -> { var player = c.getSource().getPlayerOrException(); DramaRuntime.get(player.getServer()).page(player, offset).forEach(s -> player.sendSystemMessage(Component.literal(s))); return "Use /ultima-drama inspect <id> for frozen terms and evidence."; });
    }
    private interface Work { String run() throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private interface Action { String run(DramaService service, net.minecraft.server.level.ServerPlayer player, UUID id, long revision); }
    private static int run(CommandContext<CommandSourceStack> c, Work work) {
        try { String result = work.run(); c.getSource().sendSuccess(() -> Component.literal(result), false); return 1; }
        catch (IllegalArgumentException | IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException failure) { c.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
    }
    private DramaCommands() { }
}
