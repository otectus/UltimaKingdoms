package com.ultimakingdoms.compat.mca;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.CivicEvidenceProvider;
import com.ultimakingdoms.api.CivicIdentityHint;
import com.ultimakingdoms.api.CivicIdentitySource;
import com.ultimakingdoms.api.CivicIdentityView;
import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementDetector;
import com.ultimakingdoms.api.SettlementView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Capability-probed, read-only integration for supported MCA Reborn 7.x package layouts. */
public final class McaCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private McaCompat() {
    }

    public record CommunityChoice(com.ultimakingdoms.api.McaCommunityRef reference,String label) {}
    public static List<CommunityChoice> operatorCommunities(net.minecraft.server.level.ServerPlayer viewer) {
        var server=viewer.getServer();if(!server.isSameThread()||viewer.hasDisconnected()||!viewer.hasPermissions(2))throw new IllegalArgumentException("Operator access required.");
        if(!ModList.get().isLoaded("mca"))return List.of();var result=new ArrayList<CommunityChoice>();
        var kingdoms=com.ultimakingdoms.api.UltimaKingdomsApi.get(server);
        for(var level:server.getAllLevels())for(var village:McaAccess.villages(level))McaAccess.candidate(level,village).ifPresent(candidate->{
            var reference=com.ultimakingdoms.api.McaCommunityRef.parse(candidate.externalRefs().get("mca"));reference.ifPresent(ref->result.add(new CommunityChoice(ref,kingdoms.getSettlementForMcaVillage(ref.dimension(),ref.villageId()).map(SettlementView::displayName).orElse("Native community at "+candidate.anchor().toShortString()))));
        });return List.copyOf(result);
    }

    public static Registration attach(MinecraftServer server, KingdomsService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        Optional<String> installed = ModList.get().getModContainerById("mca")
                .map(container -> container.getModInfo().getVersion().toString());
        if (installed.isEmpty()) {
            return () -> { };
        }
        List<Registration> registrations = new ArrayList<>();
        if (McaAccess.detectorAvailable()) {
            registrations.add(service.registerSettlementDetector(McaAccess.SOURCE, new McaDetector(server)));
        } else {
            LOGGER.error("[Ultima Kingdoms] MCA {} settlement detection capability unavailable: {}",
                    installed.get(), McaAccess.detectorMissing());
        }
        try {
            if (McaAccess.evidenceAvailable()) {
                registrations.add(service.registerCivicEvidenceProvider(
                        McaAccess.SOURCE, new McaEvidence(server, service)));
            } else {
                LOGGER.error("[Ultima Kingdoms] MCA {} civic evidence capability unavailable: {}",
                        installed.get(), McaAccess.evidenceMissing());
            }
        } catch (RuntimeException exception) {
            closeAll(registrations);
            throw exception;
        }
        if (registrations.isEmpty()) {
            return () -> { };
        }
        LOGGER.info("[Ultima Kingdoms] Attached MCA {} integration through package root {} (detection={}, civicEvidence={})",
                installed.get(), McaAccess.root(), McaAccess.detectorAvailable(), McaAccess.evidenceAvailable());
        return combined(registrations);
    }

    private static Registration combined(List<Registration> registrations) {
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                closeAll(registrations);
            }
        };
    }

    private static void closeAll(List<Registration> registrations) {
        RuntimeException failure = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private record McaDetector(MinecraftServer server) implements SettlementDetector {
        @Override
        public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
            if (level.getServer() != server || !McaAccess.detectorAvailable()) {
                return Stream.empty();
            }
            if (radiusChunks == 0) {
                return McaAccess.nearestVillage(level, center, 16)
                        .flatMap(village -> McaAccess.candidate(level, village))
                        .stream();
            }
            int radius = Math.multiplyExact(radiusChunks, 16);
            long minX = (long) center.getX() - radius;
            long maxX = (long) center.getX() + radius;
            long minZ = (long) center.getZ() - radius;
            long maxZ = (long) center.getZ() + radius;
            List<SettlementCandidate> candidates = new ArrayList<>();
            for (Object village : McaAccess.villages(level)) {
                McaAccess.candidate(level, village)
                        .filter(candidate -> candidate.bounds().maxX() >= minX && candidate.bounds().minX() <= maxX
                                && candidate.bounds().maxZ() >= minZ && candidate.bounds().minZ() <= maxZ)
                        .ifPresent(candidates::add);
            }
            return candidates.stream();
        }
    }

    private record McaEvidence(MinecraftServer server, KingdomsService service) implements CivicEvidenceProvider {
        @Override
        public Optional<CivicIdentityHint> observe(Entity entity) {
            if (entity.getServer() != server || !(entity.level() instanceof ServerLevel level)
                    || !McaAccess.isVillager(entity)) {
                return Optional.empty();
            }

            Optional<CivicIdentityView> current = service.getCivicIdentity(entity);
            Optional<Object> home = McaAccess.homeVillage(entity);
            if (home.isPresent()) {
                Optional<SettlementView> settlement = McaAccess.candidate(level, home.get())
                        .map(candidate -> service.registerCandidate(level, candidate));
                if (settlement.isEmpty()) {
                    return Optional.empty();
                }
                UUID settlementId = settlement.get().id();
                if (current.flatMap(CivicIdentityView::residenceSettlement).filter(settlementId::equals).isPresent()) {
                    return Optional.empty();
                }
                Optional<UUID> origin = current.flatMap(CivicIdentityView::originSettlement);
                if (current.isEmpty()) {
                    origin = Optional.of(settlementId);
                }
                return Optional.of(new CivicIdentityHint(origin, Optional.of(settlementId), CivicIdentitySource.MCA));
            }

            // MCA baby items spawn a child before Residency has necessarily found a home. Use only an
            // already-recognized settlement containing the newborn; never create one or choose a nearest village.
            if (current.isEmpty() && entity instanceof AgeableMob mob && mob.isBaby()) {
                return service.getSettlementAt(level, entity.blockPosition())
                        .map(SettlementView::id)
                        .map(id -> new CivicIdentityHint(Optional.of(id), Optional.of(id), CivicIdentitySource.MCA));
            }
            return Optional.empty();
        }
    }
}
