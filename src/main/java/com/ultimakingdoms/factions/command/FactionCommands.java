package com.ultimakingdoms.factions.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.ultimakingdoms.api.factions.FactionChangeCause;
import com.ultimakingdoms.api.factions.FactionMigrationReport;
import com.ultimakingdoms.api.factions.FactionStandingRequest;
import com.ultimakingdoms.api.factions.UltimaFactionsApi;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.factions.config.FactionConfig;
import com.ultimakingdoms.factions.FactionServiceImpl;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;
import java.util.UUID;

public final class FactionCommands {
    private static final ResourceLocation ADMIN_SOURCE=new ResourceLocation("ultima_kingdoms","faction_command");
    private FactionCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var faction = Commands.literal("faction").requires(source -> source.hasPermission(2));
        faction.then(Commands.literal("diagnostics").executes(context -> {
            var service = UltimaFactionsApi.get(context.getSource().getServer());
            context.getSource().sendSuccess(() -> Component.literal("Faction revision=" + service.revision()
                    + " syncMode=" + FactionConfig.SYNC_MODE.get()
                    + (service instanceof FactionServiceImpl implementation
                    ? " " + implementation.diagnosticSummary() : "")), false);
            return 1;
        }));
        faction.then(Commands.literal("migration")
                .then(Commands.literal("dry_run").executes(context -> migration(context.getSource(), false)))
                .then(Commands.literal("import").executes(context -> migration(context.getSource(), true))));
        faction.then(Commands.literal("get").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("kingdom", ResourceLocationArgument.id()).executes(context -> {
                    var player = EntityArgument.getPlayer(context, "player");
                    var kingdom = ResourceLocationArgument.getId(context, "kingdom");
                    var standing = UltimaFactionsApi.get(context.getSource().getServer())
                            .getStanding(player.getUUID(), kingdom);
                    context.getSource().sendSuccess(() -> Component.literal(standing
                            .map(value -> kingdom + " score=" + value.score() + " tier=" + value.tierId()
                                    + " revision=" + value.revision())
                            .orElse(kingdom + " score=0 tier=neutral (no record)")), false);
                    return 1;
                }))));
        faction.then(Commands.literal("add").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("kingdom", ResourceLocationArgument.id())
                        .then(Commands.argument("delta", IntegerArgumentType.integer(-2000, 2000))
                                .executes(context -> {
                                    var player = EntityArgument.getPlayer(context, "player");
                                    var kingdom = ResourceLocationArgument.getId(context, "kingdom");
                                    int delta = IntegerArgumentType.getInteger(context, "delta");
                                    var result = UltimaFactionsApi.get(context.getSource().getServer()).apply(
                                            new FactionStandingRequest(player.getUUID(), kingdom, delta,
                                                    ADMIN_SOURCE, FactionChangeCause.ADMIN, UUID.randomUUID(),
                                                    0L, Optional.empty(), Optional.of("Manual faction command"), false));
                                    context.getSource().sendSuccess(() -> Component.literal("Faction " + kingdom
                                            + " applied=" + result.appliedDelta() + " score="
                                            + result.standing().score() + " status=" + result.status()), true);
                                    return result.applied() ? 1 : 0;
                                })))));
        var localDelta = Commands.argument("delta", IntegerArgumentType.integer(-100, 100))
                .executes(context -> {
                    var player = EntityArgument.getPlayer(context, "player");
                    var dimension = ResourceLocationArgument.getId(context, "dimension");
                    int village = IntegerArgumentType.getInteger(context, "village");
                    int delta = IntegerArgumentType.getInteger(context, "delta");
                    var result = UltimaFactionsApi.get(context.getSource().getServer()).deliverLocalEffect(
                            player.getUUID(), new McaCommunityRef(dimension, village), delta,
                            UUID.randomUUID(), 0L, "Explicit faction command effect");
                    context.getSource().sendSuccess(() -> Component.literal("Local standing effect " + result
                            + " for " + dimension + "#" + village), true);
                    return result == com.ultimakingdoms.api.factions.LocalStandingEffectResult.APPLIED ? 1 : 0;
                });
        var village = Commands.argument("village", IntegerArgumentType.integer(0)).then(localDelta);
        var dimension = Commands.argument("dimension", ResourceLocationArgument.id()).then(village);
        var player = Commands.argument("player", EntityArgument.player()).then(dimension);
        faction.then(Commands.literal("local_effect").then(player));
        var pendingKingdom = Commands.argument("kingdom", ResourceLocationArgument.id()).executes(context -> {
            var service = UltimaFactionsApi.get(context.getSource().getServer());
            if (!(service instanceof FactionServiceImpl implementation)) return 0;
            UUID eventId = com.ultimakingdoms.interaction.NamedTargets.uuid(context,"event","standing_event");
            ResourceLocation kingdom = ResourceLocationArgument.getId(context, "kingdom");
            var result = implementation.resolvePending(eventId, kingdom);
            if (result.isEmpty()) {
                context.getSource().sendFailure(Component.literal("No resolvable UNMAPPED event " + eventId));
                return 0;
            }
            context.getSource().sendSuccess(() -> Component.literal("Resolved " + eventId + " to " + kingdom
                    + " status=" + result.get().status() + " applied=" + result.get().appliedDelta()), true);
            return 1;
        });
        faction.then(Commands.literal("pending_map")
                .then(com.ultimakingdoms.interaction.NamedTargets.argument("event","standing_event").then(pendingKingdom)));
        event.getDispatcher().register(Commands.literal("ultima").then(faction));
    }

    private static int migration(net.minecraft.commands.CommandSourceStack source, boolean apply) {
        var service = UltimaFactionsApi.get(source.getServer());
        FactionMigrationReport before = service.previewLegacyMigration();
        FactionMigrationReport report = apply && before.importable()
                ? service.importLegacyMigration() : before;
        source.sendSuccess(() -> Component.literal("Faction migration sourceAvailable=" + report.sourceAvailable()
                + " alreadyImported=" + report.alreadyImported() + " inputs=" + report.inputCount()
                + " groups=" + report.entries().size() + " unmapped=" + report.unmapped().size()
                + " importable=" + before.importable()), false);
        for (FactionMigrationReport.Entry entry : report.entries()) {
            source.sendSuccess(() -> Component.literal("  player=" + entry.playerId() + " kingdom="
                    + entry.kingdomId() + " scores=" + entry.localScores() + " baseline=" + entry.baseline()
                    + (entry.conflict() ? " CONFLICT existing=" + entry.existingScore() : "")), false);
        }
        for (FactionMigrationReport.Unmapped entry : report.unmapped()) {
            source.sendFailure(Component.literal("  UNMAPPED player=" + entry.playerId() + " community="
                    + entry.community() + " score=" + entry.localScore() + " reason=" + entry.reason()));
        }
        if (apply && !before.importable()) {
            source.sendFailure(Component.literal("Faction migration refused; resolve unavailable source, mappings, conflicts, or existing marker"));
            return 0;
        }
        if (apply) source.sendSuccess(() -> Component.literal("Faction baselines imported without synthetic incidents"), true);
        return report.unmapped().isEmpty() ? 1 : 0;
    }
}
