package dev.otectus.mcacrime.api.jurisdiction;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/** Supplies immutable political/control evidence; MCA: Crime alone assigns its legal meaning. */
@FunctionalInterface
public interface JurisdictionPolicyProvider {
    Optional<JurisdictionSnapshot> resolve(MinecraftServer server, CrimeCommunityKey localCommunity,
                                           @Nullable UUID subject);

    /** Spatial jurisdiction for non-MCA responders such as a native allied soldier. */
    default Optional<CrimeCommunityKey> jurisdiction(MinecraftServer server, LivingEntity responder) {
        return Optional.empty();
    }
}
