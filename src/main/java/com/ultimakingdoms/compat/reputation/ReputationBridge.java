package com.ultimakingdoms.compat.reputation;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.factions.FactionServiceImpl;
import com.ultimakingdoms.factions.LegacyStandingProvider;
import com.ultimakingdoms.api.factions.LocalStandingEffectResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Optional MCA Reputation boundary. Durable synchronization is enabled only when its outbox API exists. */
public final class ReputationBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ReputationBridge() {
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms,
                                      FactionServiceImpl factions) {
        if (!ModList.get().isLoaded("mcareputation")) return () -> { };
        try {
            ReadAccess access = ReadAccess.resolve().bind(server);
            factions.installLocalStandingProvider(access::score);
            factions.installLegacyStandingProvider(access::baselines);
            factions.installLocalEffectProvider(access::deliverLocalEffect);
            LOGGER.info("[Ultima Factions] MCA Reputation local-standing reads available");
            Registration durable = ReputationOutboxBridge.attach(server, kingdoms, factions, access.apiClass);
            return () -> {
                durable.close();
                factions.installLocalStandingProvider(null);
                factions.installLegacyStandingProvider(null);
                factions.installLocalEffectProvider(null);
            };
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("[Ultima Factions] MCA Reputation API unavailable; local standing remains unresolved", exception);
            return () -> { };
        }
    }

    static final class ReadAccess {
        private final Class<?> apiClass;
        private final Constructor<?> communityConstructor;
        private final Method getScore;
        private final Method standingBaselines;
        private final Method deliverStandingEffect;
        private final MinecraftServer server;

        private ReadAccess(Class<?> apiClass, Constructor<?> communityConstructor, Method getScore,
                           Method standingBaselines, Method deliverStandingEffect, MinecraftServer server) {
            this.apiClass=apiClass;this.communityConstructor=communityConstructor;this.getScore=getScore;
            this.standingBaselines=standingBaselines;this.deliverStandingEffect=deliverStandingEffect;this.server=server;
        }

        static ReadAccess resolve() throws ReflectiveOperationException {
            Class<?> api=Class.forName("dev.otectus.mcareputation.api.McaReputationApi");
            Class<?> community=Class.forName("dev.otectus.mcareputation.community.CommunityKey");
            Constructor<?> constructor=community.getConstructor(net.minecraft.resources.ResourceLocation.class,int.class);
            Method score=api.getMethod("getScore",MinecraftServer.class,UUID.class,community);
            Method baselines;
            try { baselines=api.getMethod("standingBaselines",MinecraftServer.class); }
            catch (NoSuchMethodException ignored) { baselines=null; }
            Method effect;
            try { effect=api.getMethod("deliverStandingEffect",MinecraftServer.class,UUID.class,community,
                    int.class,ResourceLocation.class,String.class,long.class,String.class); }
            catch (NoSuchMethodException ignored) { effect=null; }
            return new ReadAccess(api,constructor,score,baselines,effect,null);
        }

        ReadAccess bind(MinecraftServer server) {
            return new ReadAccess(apiClass,communityConstructor,getScore,standingBaselines,deliverStandingEffect,server);
        }

        OptionalInt score(UUID player, McaCommunityRef community) {
            if(server==null)return OptionalInt.empty();
            try {
                Object key=communityConstructor.newInstance(community.dimension(),community.villageId());
                Object value=getScore.invoke(null,server,player,key);
                return value instanceof OptionalInt result?result:OptionalInt.empty();
            } catch(ReflectiveOperationException|RuntimeException exception){
                LOGGER.debug("MCA Reputation local-standing query failed",exception);return OptionalInt.empty();
            }
        }

        Optional<List<LegacyStandingProvider.LegacyStanding>> baselines() {
            if (server == null || standingBaselines == null) return Optional.empty();
            try {
                Object answer = standingBaselines.invoke(null, server);
                if (!(answer instanceof List<?> values)) return Optional.empty();
                List<LegacyStandingProvider.LegacyStanding> result = new ArrayList<>();
                for (Object value : values) {
                    Object community = value.getClass().getMethod("community").invoke(value);
                    ResourceLocation dimension = (ResourceLocation) community.getClass()
                            .getMethod("dimension").invoke(community);
                    int villageId = ((Number) community.getClass().getMethod("villageId").invoke(community)).intValue();
                    result.add(new LegacyStandingProvider.LegacyStanding(
                            (UUID) value.getClass().getMethod("player").invoke(value),
                            new McaCommunityRef(dimension, villageId),
                            ((Number) value.getClass().getMethod("score").invoke(value)).intValue(),
                            ((Number) value.getClass().getMethod("revision").invoke(value)).longValue()));
                }
                return Optional.of(List.copyOf(result));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.error("MCA Reputation baseline enumeration failed", exception);
                return Optional.empty();
            }
        }

        LocalStandingEffectResult deliverLocalEffect(UUID player, McaCommunityRef community, int delta,
                                                      UUID correlation, long revision, String description) {
            if (server == null || deliverStandingEffect == null) return LocalStandingEffectResult.UNAVAILABLE;
            try {
                Object key = communityConstructor.newInstance(community.dimension(), community.villageId());
                Object outcome = deliverStandingEffect.invoke(null, server, player, key, delta,
                        new ResourceLocation("ultima_kingdoms", "mca_reputation_sync"),
                        correlation.toString(), revision, description);
                String result = String.valueOf(outcome.getClass().getMethod("outcome").invoke(outcome));
                return switch (result) {
                    case "APPLIED" -> LocalStandingEffectResult.APPLIED;
                    case "DUPLICATE" -> LocalStandingEffectResult.DUPLICATE;
                    case "ACCEPTED_NO_PUBLIC_INCIDENT" -> LocalStandingEffectResult.ACCEPTED_NO_CHANGE;
                    default -> LocalStandingEffectResult.REFUSED;
                };
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.error("MCA Reputation semantic effect delivery failed", exception);
                return LocalStandingEffectResult.REFUSED;
            }
        }
    }
}
