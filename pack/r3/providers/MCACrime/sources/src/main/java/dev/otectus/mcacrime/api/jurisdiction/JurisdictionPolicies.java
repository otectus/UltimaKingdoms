package dev.otectus.mcacrime.api.jurisdiction;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.*;

/** Server-scoped provider registry and conservative Crime-owned interpretation. */
public final class JurisdictionPolicies {
    private static final Map<MinecraftServer, JurisdictionPolicyProvider> PROVIDERS = new WeakHashMap<>();

    private JurisdictionPolicies() { }

    public static void attach(MinecraftServer server, JurisdictionPolicyProvider provider) {
        requireThread(server);
        if (PROVIDERS.putIfAbsent(server, Objects.requireNonNull(provider)) != null)
            throw new IllegalStateException("Jurisdiction policy provider already attached");
    }

    public static void detach(MinecraftServer server, JurisdictionPolicyProvider provider) {
        requireThread(server); PROVIDERS.remove(server, provider);
    }

    public static Optional<JurisdictionSnapshot> resolve(MinecraftServer server, CrimeCommunityKey community,
                                                          @Nullable UUID subject) {
        if (server == null || community == null || !server.isSameThread()) return Optional.empty();
        JurisdictionPolicyProvider provider = PROVIDERS.get(server);
        if (provider == null) return Optional.empty();
        try {
            Optional<JurisdictionSnapshot> result = provider.resolve(server, community, subject);
            if (result == null || result.isEmpty() || !result.get().localCommunity().equals(community))
                return Optional.empty();
            return result;
        } catch (RuntimeException | LinkageError failure) {
            McaCrime.LOGGER.warn("MCA: Crime jurisdiction provider failed closed ({})",
                    failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** MCA guards use their home community; other responders require the installed spatial provider. */
    public static Optional<CrimeCommunityKey> jurisdictionOf(ServerLevel level, LivingEntity responder) {
        if (level == null || responder == null || responder.level() != level) return Optional.empty();
        CrimeCommunityKey nativeHome = dev.otectus.mcacrime.memory.ReportService.jurisdictionOf(level, responder);
        if (nativeHome != null) return Optional.of(nativeHome);
        JurisdictionPolicyProvider provider = PROVIDERS.get(level.getServer());
        if (provider == null) return Optional.empty();
        try {
            Optional<CrimeCommunityKey> result = provider.jurisdiction(level.getServer(), responder);
            return result == null ? Optional.empty() : result.filter(key -> key.dimension().equals(level.dimension().location()));
        } catch (RuntimeException | LinkageError failure) {
            McaCrime.LOGGER.warn("MCA: Crime jurisdiction location provider failed closed ({})",
                    failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Missing, suspended or conflicting evidence preserves Crime's normal local civilian law. */
    public static boolean allowsSharedReports(Optional<JurisdictionSnapshot> evidence) {
        return evidence.filter(e -> e.availability() == JurisdictionSnapshot.Availability.AVAILABLE)
                .map(e -> e.cooperation() == JurisdictionSnapshot.Cooperation.CONFIGURED).orElse(true);
    }

    /** Safe conduct is narrow: it suppresses standalone remote Wanted pursuit, never a local case. */
    public static boolean allowsStandaloneWanted(Optional<JurisdictionSnapshot> evidence) {
        return evidence.filter(e -> e.availability() == JurisdictionSnapshot.Availability.AVAILABLE)
                .map(e -> !(e.safeConduct() && e.remoteWantedImmunity())).orElse(true);
    }

    /** Occupation never disables local civilian law; unknown evidence also keeps it running. */
    public static boolean civilianLawContinues(Optional<JurisdictionSnapshot> evidence) {
        return true;
    }

    private static void requireThread(MinecraftServer server) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("Jurisdiction policy registry requires server thread");
    }
}
