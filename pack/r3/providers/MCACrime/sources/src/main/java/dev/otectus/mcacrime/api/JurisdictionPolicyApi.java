package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicies;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicyProvider;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionSnapshot;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/** Focused additive extension facade for jurisdiction/control integrations. */
public final class JurisdictionPolicyApi {
    private JurisdictionPolicyApi() { }

    public static void register(MinecraftServer server, JurisdictionPolicyProvider provider) {
        JurisdictionPolicies.attach(server, provider);
    }

    public static void unregister(MinecraftServer server, JurisdictionPolicyProvider provider) {
        JurisdictionPolicies.detach(server, provider);
    }

    public static Optional<JurisdictionSnapshot> policy(MinecraftServer server,
                                                         CrimeCommunityKey community,
                                                         @Nullable UUID subject) {
        return JurisdictionPolicies.resolve(server, community, subject);
    }

    public static boolean mayEnforce(LivingEntity responder, LivingEntity target) {
        return McaCrimeApi.mayEnforce(responder, target);
    }
}
