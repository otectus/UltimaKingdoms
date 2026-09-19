package com.ultimakingdoms.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.KingdomView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class UltimaCommands {
    private static final ResourceLocation MANUAL_DETECTOR =
            new ResourceLocation(UltimaKingdomsApi.MOD_ID, "manual_command");
    private static final int DEFAULT_MANUAL_RADIUS = 64;

    private UltimaCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ultima")
                .then(Commands.literal("kingdom")
                        .then(Commands.literal("list").executes(UltimaCommands::kingdomList))
                        .then(Commands.literal("info")
                                .then(Commands.argument("kingdom", ResourceLocationArgument.id())
                                        .suggests(KINGDOM_SUGGESTIONS)
                                        .executes(UltimaCommands::kingdomInfo))))
                .then(Commands.literal("village")
                        .then(Commands.literal("here").executes(UltimaCommands::villageHere))
                        .then(Commands.literal("info")
                                .executes(context -> villageInfo(context, null))
                                .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .executes(context -> villageInfo(context,
                                                SettlementSelectorArgument.getSettlement(context, "village")))))
                        .then(Commands.literal("create").requires(source -> source.hasPermission(2))
                                .executes(context -> create(context, DEFAULT_MANUAL_RADIUS, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512))
                                        .executes(context -> create(context,
                                                IntegerArgumentType.getInteger(context, "radius"), null))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> create(context,
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("rename").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(UltimaCommands::rename))))
                        .then(Commands.literal("setkingdom").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().then(Commands.argument("kingdom", ResourceLocationArgument.id())
                                        .suggests(KINGDOM_SUGGESTIONS).executes(UltimaCommands::setKingdom))))
                        .then(Commands.literal("reclassify").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(UltimaCommands::reclassify)))
                        .then(Commands.literal("lock").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(context -> setLocks(context, true))))
                        .then(Commands.literal("unlock").requires(source -> source.hasPermission(2))
                                .then(settlementArgument().executes(context -> setLocks(context, false))))
                        .then(Commands.literal("discover").requires(source -> source.hasPermission(2))
                                .executes(context -> discover(context, 8))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 32))
                                        .executes(context -> discover(context,
                                                IntegerArgumentType.getInteger(context, "radiusChunks")))))
                        .then(Commands.literal("merge").requires(source -> source.hasPermission(2))
                                .then(Commands.argument("source", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .then(Commands.argument("target", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(UltimaCommands::merge))))
                        .then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                                .executes(context -> debug(context, null))
                                .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                        .suggests(SETTLEMENT_SUGGESTIONS)
                                        .executes(context -> debug(context,
                                                SettlementSelectorArgument.getSettlement(context, "village"))))))
                .then(Commands.literal("citizen").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("info")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .executes(UltimaCommands::citizenInfo)))
                        .then(Commands.literal("setorigin")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(context -> setCitizenSettlement(context, true)))))
                        .then(Commands.literal("setresidence")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .then(Commands.argument("village", SettlementSelectorArgument.settlement())
                                                .suggests(SETTLEMENT_SUGGESTIONS)
                                                .executes(context -> setCitizenSettlement(context, false))))))
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2))
                        .executes(UltimaCommands::reload)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> settlementArgument() {
        return Commands.argument("village", SettlementSelectorArgument.settlement()).suggests(SETTLEMENT_SUGGESTIONS);
    }

    private static int kingdomList(CommandContext<CommandSourceStack> context) {
        List<KingdomView> kingdoms = service(context).getKingdoms().stream()
                .sorted(Comparator.comparing(view -> view.id().toString())).toList();
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.kingdom.list",
                kingdoms.size()), false);
        kingdoms.forEach(kingdom -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.ultima_kingdoms.kingdom.entry", Component.translatable(kingdom.translationKey()),
                kingdom.id().toString()), false));
        return kingdoms.size();
    }

    private static int kingdomInfo(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "kingdom");
        Optional<KingdomView> result = service(context).getKingdom(id);
        if (result.isEmpty()) return failure(context, "command.ultima_kingdoms.error.kingdom_missing", id);
        KingdomView kingdom = result.get();
        long settlements = service(context).getSettlements(id).size();
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.kingdom.info",
                Component.translatable(kingdom.translationKey()), kingdom.id().toString(), settlements), false);
        return 1;
    }

    private static int villageHere(CommandContext<CommandSourceStack> context) {
        return villageAtSource(context).map(settlement -> sendSettlement(context, settlement))
                .orElseGet(() -> failure(context, "command.ultima_kingdoms.error.no_village_here"));
    }

    private static int villageInfo(CommandContext<CommandSourceStack> context, String query) {
        Optional<SettlementView> settlement = query == null ? villageAtSource(context) : find(context, query);
        return settlement.map(value -> sendSettlement(context, value))
                .orElseGet(() -> failure(context, query == null
                        ? "command.ultima_kingdoms.error.no_village_here"
                        : "command.ultima_kingdoms.error.village_missing", query == null ? "" : query));
    }

    private static int create(CommandContext<CommandSourceStack> context, int radius, String proposedName) {
        return mutate(context, service -> {
            BlockPos anchor = BlockPos.containing(context.getSource().getPosition());
            String sourceKey = "command:" + UUID.randomUUID();
            SettlementView settlement = service.registerCandidate(context.getSource().getLevel(),
                    SettlementCandidate.manual(context.getSource().getLevel().dimension(), anchor, radius,
                            MANUAL_DETECTOR, sourceKey, proposedName));
            context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.village.created",
                    settlement.displayName(), settlement.id().toString()), true);
            return 1;
        });
    }

    private static int rename(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            String name = StringArgumentType.getString(context, "name");
            SettlementView updated = service(context).rename(settlement.id(), name);
            context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.village.renamed",
                    updated.displayName()), true);
            return 1;
        });
    }

    private static int setKingdom(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            ResourceLocation kingdom = ResourceLocationArgument.getId(context, "kingdom");
            if (service(context).getKingdom(kingdom).isEmpty()) {
                return failure(context, "command.ultima_kingdoms.error.kingdom_missing", kingdom);
            }
            SettlementView updated = service(context).setKingdom(settlement.id(), kingdom);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.ultima_kingdoms.village.kingdom_changed", updated.displayName(), kingdom), true);
            return 1;
        });
    }

    private static int reclassify(CommandContext<CommandSourceStack> context) {
        return mutateSettlement(context, "village", settlement -> {
            SettlementView updated = service(context).reclassify(settlement.id());
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.ultima_kingdoms.village.reclassified", updated.displayName(), updated.kingdomId()), true);
            return 1;
        });
    }

    private static int setLocks(CommandContext<CommandSourceStack> context, boolean locked) {
        return mutateSettlement(context, "village", settlement -> {
            SettlementView updated = service(context).setLocks(settlement.id(), locked, locked);
            context.getSource().sendSuccess(() -> Component.translatable(locked
                    ? "command.ultima_kingdoms.village.locked" : "command.ultima_kingdoms.village.unlocked",
                    updated.displayName()), true);
            return 1;
        });
    }

    private static int discover(CommandContext<CommandSourceStack> context, int radiusChunks) {
        return mutate(context, service -> {
            BlockPos center = BlockPos.containing(context.getSource().getPosition());
            Collection<SettlementView> found = service.discover(context.getSource().getLevel(), center, radiusChunks);
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.ultima_kingdoms.village.discovered", found.size()), true);
            return found.size();
        });
    }

    private static int merge(CommandContext<CommandSourceStack> context) {
        Optional<SettlementView> source = find(context, SettlementSelectorArgument.getSettlement(context, "source"));
        Optional<SettlementView> target = find(context, SettlementSelectorArgument.getSettlement(context, "target"));
        if (source.isEmpty() || target.isEmpty()) {
            return failure(context, "command.ultima_kingdoms.error.village_missing",
                    source.isEmpty() ? SettlementSelectorArgument.getSettlement(context, "source")
                            : SettlementSelectorArgument.getSettlement(context, "target"));
        }
        return mutate(context, service -> {
            SettlementView merged = service.merge(source.get().id(), target.get().id());
            context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.village.merged",
                    source.get().displayName(), merged.displayName()), true);
            return 1;
        });
    }

    private static int debug(CommandContext<CommandSourceStack> context, String query) {
        Optional<SettlementView> settlement = query == null ? villageAtSource(context) : find(context, query);
        if (settlement.isEmpty()) return failure(context, query == null
                ? "command.ultima_kingdoms.error.no_village_here"
                : "command.ultima_kingdoms.error.village_missing", query == null ? "" : query);
        SettlementView view = settlement.get();
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.village.debug",
                view.displayName(), view.anchor().toShortString(), view.biomeAtCreation(), view.kingdomId(),
                view.assignmentSource().name(), view.detectionSource().name(), view.revision()), false);
        view.assignmentTrace().forEach(line -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.ultima_kingdoms.village.trace", line), false));
        return 1;
    }

    private static int citizenInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        Optional<CivicIdentityView> identity = service(context).getCivicIdentity(entity);
        if (identity.isEmpty()) return failure(context, "command.ultima_kingdoms.error.no_civic_identity",
                entity.getDisplayName());
        CivicIdentityView view = identity.get();
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.citizen.info",
                entity.getDisplayName(), optional(view.originSettlement()), optional(view.originKingdom()),
                optional(view.residenceSettlement()), optional(view.residenceKingdom()), view.source().name()), false);
        return 1;
    }

    private static int setCitizenSettlement(CommandContext<CommandSourceStack> context, boolean origin)
            throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        Optional<SettlementView> settlement = find(context, SettlementSelectorArgument.getSettlement(context, "village"));
        if (settlement.isEmpty()) return failure(context, "command.ultima_kingdoms.error.village_missing",
                SettlementSelectorArgument.getSettlement(context, "village"));
        return mutate(context, service -> {
            if (origin) service.setOrigin(entity, settlement.get().id());
            else service.setResidence(entity, settlement.get().id());
            context.getSource().sendSuccess(() -> Component.translatable(origin
                            ? "command.ultima_kingdoms.citizen.origin_set"
                            : "command.ultima_kingdoms.citizen.residence_set",
                    entity.getDisplayName(), settlement.get().displayName()), true);
            return 1;
        });
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((ignored, error) ->
                server.execute(() -> {
                    if (error == null) context.getSource().sendSuccess(() -> Component.translatable(
                            "command.ultima_kingdoms.reload.success"), true);
                    else context.getSource().sendFailure(Component.translatable(
                            "command.ultima_kingdoms.reload.failure", error.getMessage()));
                }));
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.reload.started"), false);
        return 1;
    }

    private static int sendSettlement(CommandContext<CommandSourceStack> context, SettlementView settlement) {
        context.getSource().sendSuccess(() -> Component.translatable("command.ultima_kingdoms.village.info",
                settlement.displayName(), settlement.slug(), settlement.id(), settlement.kingdomId(),
                settlement.dimension().location(), settlement.anchor().toShortString(), settlement.biomeAtCreation(),
                settlement.assignmentSource().name()), false);
        return 1;
    }

    private static Optional<SettlementView> villageAtSource(CommandContext<CommandSourceStack> context) {
        return service(context).getSettlementAt(context.getSource().getLevel(),
                BlockPos.containing(context.getSource().getPosition()));
    }

    private static Optional<SettlementView> find(CommandContext<CommandSourceStack> context, String query) {
        return service(context).findSettlement(query);
    }

    private static KingdomsService service(CommandContext<CommandSourceStack> context) {
        return UltimaKingdomsApi.get(context.getSource().getServer());
    }

    private static int mutateSettlement(CommandContext<CommandSourceStack> context, String argument,
                                        Function<SettlementView, Integer> operation) {
        Optional<SettlementView> settlement = find(context, SettlementSelectorArgument.getSettlement(context, argument));
        if (settlement.isEmpty()) return failure(context, "command.ultima_kingdoms.error.village_missing",
                SettlementSelectorArgument.getSettlement(context, argument));
        return mutate(context, ignored -> operation.apply(settlement.get()));
    }

    private static int mutate(CommandContext<CommandSourceStack> context, Function<KingdomsService, Integer> action) {
        try {
            return action.apply(service(context));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return failure(context, "command.ultima_kingdoms.error.operation", exception.getMessage());
        }
    }

    private static int failure(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(Component.translatable(key, args));
        return 0;
    }

    private static String optional(Optional<?> value) {
        return value.map(Object::toString).orElse("-");
    }

    private static final SuggestionProvider<CommandSourceStack> KINGDOM_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(service(context).getKingdoms().stream()
                    .map(view -> view.id().toString()), builder);

    private static final SuggestionProvider<CommandSourceStack> SETTLEMENT_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(service(context).getSettlementPage(Optional.empty(), 0, 64).stream()
                    .flatMap(view -> java.util.stream.Stream.of(view.slug().toString(), view.id().toString(),
                            StringArgumentType.escapeIfRequired(view.displayName()))), builder);
}
