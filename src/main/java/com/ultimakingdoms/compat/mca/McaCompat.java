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

/** Exact-version, read-only integration for MCA Reborn 7.6.26 on Forge 1.20.1. */
public final class McaCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private McaCompat() {
    }

    public static Registration attach(MinecraftServer server, KingdomsService service) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(service, "service");
        Optional<String> installed = ModList.get().getModContainerById("mca")
                .map(container -> container.getModInfo().getVersion().toString());
        if (installed.isEmpty()) {
            return () -> { };
        }
        if (!McaAccess.EXACT_VERSION.equals(installed.get())) {
            LOGGER.error("[Ultima Kingdoms] MCA {} is installed, but this adapter targets exactly {}; integration disabled",
                    installed.get(), McaAccess.EXACT_VERSION);
            return () -> { };
        }
        if (!McaAccess.available()) {
            LOGGER.error("[Ultima Kingdoms] MCA {} integration surface did not resolve: {}",
                    installed.get(), McaAccess.missing());
            return () -> { };
        }

        McaDetector detector = new McaDetector(server);
        McaEvidence evidence = new McaEvidence(server, service);
        Registration detectorRegistration = service.registerSettlementDetector(McaAccess.SOURCE, detector);
        try {
            Registration evidenceRegistration = service.registerCivicEvidenceProvider(McaAccess.SOURCE, evidence);
            LOGGER.info("[Ultima Kingdoms] Attached MCA {} civic integration", installed.get());
            return combined(evidenceRegistration, detectorRegistration);
        } catch (RuntimeException exception) {
            detectorRegistration.close();
            throw exception;
        }
    }

    private static Registration combined(Registration first, Registration second) {
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                first.close();
                second.close();
            }
        };
    }

    private record McaDetector(MinecraftServer server) implements SettlementDetector {
        @Override
        public Stream<SettlementCandidate> detect(ServerLevel level, BlockPos center, int radiusChunks) {
            if (level.getServer() != server || !McaAccess.available()) {
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
