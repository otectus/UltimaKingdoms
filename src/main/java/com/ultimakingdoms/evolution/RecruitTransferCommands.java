package com.ultimakingdoms.evolution;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.List;

public final class RecruitTransferCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("ultima-transfer");
        root.then(Commands.literal("reconciliation_preview").requires(s->s.hasPermission(2)).then(com.ultimakingdoms.interaction.NamedTargets.argument("transfer","reconciliation_transfer")
                .executes(c->run(c,()->new RecruitTransferService(c.getSource().getServer()).reconciliationPreview(c.getSource().getPlayerOrException(),com.ultimakingdoms.interaction.NamedTargets.uuid(c,"transfer","reconciliation_transfer"))))));
        root.then(Commands.literal("reconcile").requires(s->s.hasPermission(2)).then(com.ultimakingdoms.interaction.NamedTargets.argument("transfer","reconciliation_transfer")
                .then(Commands.argument("revision",LongArgumentType.longArg(1)).then(Commands.argument("fingerprint",StringArgumentType.word())
                .then(Commands.argument("reason",StringArgumentType.greedyString()).executes(c->run(c,()->new RecruitTransferService(c.getSource().getServer()).reconcile(c.getSource().getPlayerOrException(),
                        com.ultimakingdoms.interaction.NamedTargets.uuid(c,"transfer","reconciliation_transfer"),LongArgumentType.getLong(c,"revision"),StringArgumentType.getString(c,"fingerprint"),StringArgumentType.getString(c,"reason")))))))));
        var proposal = Commands.argument("family", BoolArgumentType.bool()).executes(c -> run(c, () -> new RecruitTransferService(c.getSource().getServer()).propose(
                c.getSource().getPlayerOrException(), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"unit","native_recruit"), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"recipient","player"), com.ultimakingdoms.interaction.NamedTargets.uuid(c,"group","native_group"), BoolArgumentType.getBool(c, "family"))));
        root.then(Commands.literal("propose").then(com.ultimakingdoms.interaction.NamedTargets.argument("unit","native_recruit").then(com.ultimakingdoms.interaction.NamedTargets.argument("recipient","player").then(com.ultimakingdoms.interaction.NamedTargets.argument("group","native_group").then(proposal)))));
        for (String verb : List.of("consent", "cancel", "apply")) {
            var revision = Commands.argument("revision", LongArgumentType.longArg(1)).executes(c -> run(c, () -> {
                var s = new RecruitTransferService(c.getSource().getServer()); var p = c.getSource().getPlayerOrException(); var id = com.ultimakingdoms.interaction.NamedTargets.uuid(c,"transfer","transfer"); var r = LongArgumentType.getLong(c, "revision");
                return switch (verb) { case "consent" -> s.consent(p, id, r); case "cancel" -> s.cancel(p, id, r); default -> s.apply(p, id, r); };
            }));
            root.then(Commands.literal(verb).then(com.ultimakingdoms.interaction.NamedTargets.argument("transfer","transfer").then(revision)));
        }
        for (String verb : List.of("inspect", "confirm", "restore")) root.then(Commands.literal(verb).then(com.ultimakingdoms.interaction.NamedTargets.argument("transfer","transfer").executes(c -> run(c, () -> {
            var s = new RecruitTransferService(c.getSource().getServer()); var p = c.getSource().getPlayerOrException(); var id = com.ultimakingdoms.interaction.NamedTargets.uuid(c,"transfer","transfer");
            if (verb.equals("inspect")) { s.inspect(p, id).forEach(line -> p.sendSystemMessage(Component.literal(line))); return "Private transfer inspection complete."; }
            return verb.equals("confirm") ? s.confirm(p, id) : s.restore(p, id);
        }))));
        event.getDispatcher().register(root);
    }
    private interface Work { String run() throws com.mojang.brigadier.exceptions.CommandSyntaxException; }
    private static int run(CommandContext<CommandSourceStack> c, Work work) {
        try { var result = work.run(); c.getSource().sendSuccess(() -> Component.literal(result), false); return 1; }
        catch (IllegalArgumentException | IllegalStateException | com.mojang.brigadier.exceptions.CommandSyntaxException failure) { c.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
    }
    private RecruitTransferCommands() { }
}
