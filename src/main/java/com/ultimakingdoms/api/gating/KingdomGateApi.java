package com.ultimakingdoms.api.gating;

import com.google.gson.JsonParser;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import com.ultimakingdoms.api.McaCommunityRef;

/** Reflection-friendly server facade for optional quest and conversation integrations. */
public final class KingdomGateApi {
    private static final Map<MinecraftServer, Function<ResourceLocation, Optional<KingdomPredicate>>> NAMED_GATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private KingdomGateApi() {
    }

    public static KingdomPredicate parse(String json) {
        return KingdomPredicate.fromJson(JsonParser.parseString(Objects.requireNonNull(json, "json")));
    }

    public static boolean testJson(ServerPlayer player, Entity giver, String json,
                                   Optional<UUID> explicitSettlementId) {
        try {
            return test(player, giver, parse(json), explicitSettlementId);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public static boolean testNamed(ServerPlayer player, Entity giver, ResourceLocation gateId,
                                    Optional<UUID> explicitSettlementId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gateId, "gateId");
        Function<ResourceLocation, Optional<KingdomPredicate>> gates = NAMED_GATES.get(player.getServer());
        if (gates == null) return false;
        return gates.apply(gateId).map(predicate -> test(player, giver, predicate, explicitSettlementId)).orElse(false);
    }

    public static boolean test(ServerPlayer player, Entity giver, KingdomPredicate predicate,
                               Optional<UUID> explicitSettlementId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(predicate, "predicate");
        explicitSettlementId = Objects.requireNonNull(explicitSettlementId, "explicitSettlementId");
        if ((predicate.subject() == KingdomSubject.EXPLICIT_SETTLEMENT) != explicitSettlementId.isPresent()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return predicate.matches(Optional.empty());
        KingdomsService service;
        try {
            service = UltimaKingdomsApi.get(server);
        } catch (IllegalStateException unavailable) {
            return predicate.matches(Optional.empty());
        }
        return predicate.matches(DefaultKingdomContextResolver.INSTANCE.resolve(
                service, player, giver, predicate.subject(), explicitSettlementId));
    }

    /** Evaluates an inline gate against a frozen, previously persisted civic snapshot. */
    public static boolean testJsonAgainst(String json, KingdomContext context) {
        Objects.requireNonNull(context, "context");
        try {
            return parse(json).matches(Optional.of(context));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** Evaluates a server's named gate against a frozen, previously persisted civic snapshot. */
    public static boolean testNamedAgainst(ServerPlayer player, ResourceLocation gateId, KingdomContext context) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gateId, "gateId");
        Objects.requireNonNull(context, "context");
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        Function<ResourceLocation, Optional<KingdomPredicate>> gates = NAMED_GATES.get(server);
        if (gates == null) return false;
        return gates.apply(gateId).map(predicate -> predicate.matches(Optional.of(context))).orElse(false);
    }

    /** Reflection-friendly subject resolution for integrations which compose another server-owned predicate. */
    public static Optional<ResourceLocation> resolveKingdomId(
            ServerPlayer player,
            Entity giver,
            String serializedSubject,
            Optional<UUID> explicitSettlementId
    ) {
        return resolveSnapshot(player, giver, serializedSubject, explicitSettlementId)
                .map(KingdomContext::kingdomId);
    }

    /** Resolves the MCA community attached to a captured settlement, when one is indexed. */
    public static Optional<McaCommunityRef> resolveMcaCommunity(ServerPlayer player, UUID settlementId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(settlementId, "settlementId");
        MinecraftServer server = player.getServer();
        if (server == null) return Optional.empty();
        try {
            return UltimaKingdomsApi.get(server).getSettlement(settlementId)
                    .map(settlement -> settlement.externalRefs().get(McaCommunityRef.EXTERNAL_REF_NAMESPACE))
                    .flatMap(McaCommunityRef::parse);
        } catch (IllegalStateException unavailable) {
            return Optional.empty();
        }
    }

    /** Complete immutable acceptance snapshot for bound quest/dialogue lifecycle integrations. */
    public static Optional<KingdomContext> resolveSnapshot(
            ServerPlayer player,
            Entity giver,
            String serializedSubject,
            Optional<UUID> explicitSettlementId
    ) {
        Objects.requireNonNull(player, "player");
        Optional<KingdomSubject> subject = KingdomSubject.fromSerializedName(
                Objects.requireNonNull(serializedSubject, "serializedSubject"));
        if (subject.isEmpty()) return Optional.empty();
        explicitSettlementId = Objects.requireNonNull(explicitSettlementId, "explicitSettlementId");
        if ((subject.get() == KingdomSubject.EXPLICIT_SETTLEMENT) != explicitSettlementId.isPresent()) {
            return Optional.empty();
        }
        MinecraftServer server = player.getServer();
        if (server == null) return Optional.empty();
        KingdomsService service;
        try {
            service = UltimaKingdomsApi.get(server);
        } catch (IllegalStateException unavailable) {
            return Optional.empty();
        }
        return DefaultKingdomContextResolver.INSTANCE.resolve(
                service, player, giver, subject.get(), explicitSettlementId);
    }

    /** Internal lifecycle seam used by the server integration bootstrap. */
    public static Registration attachNamedGates(
            MinecraftServer server,
            Function<ResourceLocation, Optional<KingdomPredicate>> lookup
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(lookup, "lookup");
        Function<ResourceLocation, Optional<KingdomPredicate>> previous = NAMED_GATES.putIfAbsent(server, lookup);
        if (previous != null && previous != lookup) {
            throw new IllegalStateException("Kingdom gate registry is already attached to this server");
        }
        return () -> NAMED_GATES.remove(server, lookup);
    }
}
