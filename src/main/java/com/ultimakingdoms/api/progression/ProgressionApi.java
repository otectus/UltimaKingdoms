package com.ultimakingdoms.api.progression;

import net.minecraft.server.level.ServerPlayer;
import java.util.function.BiFunction;

/** Private read predicates for trusted server integrations. Never serialize another player's answers. */
public final class ProgressionApi {
    private static BiFunction<ServerPlayer,ProgressionPredicate,ProgressionResult> reader=(p,q)->
            new ProgressionResult(ProgressionResult.Status.UNAVAILABLE,false,"progression.not_ready");
    private ProgressionApi() { }
    public static ProgressionResult evaluate(ServerPlayer player,ProgressionPredicate predicate) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Progression predicates require server thread");
        return reader.apply(player,predicate);
    }
    public static void register(BiFunction<ServerPlayer,ProgressionPredicate,ProgressionResult> provider) {
        reader=java.util.Objects.requireNonNull(provider);
    }
}
