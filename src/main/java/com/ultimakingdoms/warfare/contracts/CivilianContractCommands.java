package com.ultimakingdoms.warfare.contracts;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;

/** Opens authored MCA Quests contracts from a recognized, nearby civilian giver. */
@Mod.EventBusSubscriber(modid = UltimaKingdomsApi.MOD_ID)
public final class CivilianContractCommands {
    private CivilianContractCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ultima-contract")
                .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                Arrays.stream(CivilianContractKind.values()).filter(k -> !k.scoped()).map(CivilianContractKind::id), builder))
                        .then(com.ultimakingdoms.interaction.NamedTargets.argument("giver","npc").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            CivilianContractKind kind;
                            try {
                                kind = CivilianContractKind.parse(StringArgumentType.getString(context, "kind"));
                            } catch (IllegalArgumentException invalidKind) {
                                source.sendFailure(Component.translatable("warfare.contract_kind_invalid"));
                                return 0;
                            }
                            var giver = player.serverLevel().getEntity(com.ultimakingdoms.interaction.NamedTargets.uuid(context,"giver","npc"));
                            if (giver == null) {
                                source.sendFailure(Component.translatable("warfare.contract_giver_unavailable"));
                                return 0;
                            }
                            var result = CivilianContractService.offer(player, giver, kind);
                            if (!result.success()) {
                                source.sendFailure(Component.translatable(result.reason()));
                                return 0;
                            }
                            source.sendSuccess(() -> Component.translatable(result.reason(),
                                    Component.translatable("warfare.contract_kind." + kind.id()), giver.getDisplayName()), false);
                            return 1;
                        }))));
    }
}
