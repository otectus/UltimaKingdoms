package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.model.CivicContractView;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.civic.InstitutionalWorkshopPolicy;
import dev.otectus.mcacrime.civic.ServiceContract;
import dev.otectus.mcacrime.detect.CrimeCommunityResolver;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Private, authenticated policy reads for optional institutional services.
 *
 * <p>This facade deliberately has no arbitrary subject-id overload. The subject is the online
 * {@link ServerPlayer} making the request, the giver must be the same nearby loaded entity supplied by
 * the interaction, and the community is resolved from that giver at call time. That makes the result
 * suitable for a server-side workshop transaction without turning the crime ledger into a directory
 * of other players' cases.
 */
public final class InstitutionalServiceApi {

    /** Maximum number of the requester's completed local restitution contracts returned at once. */
    public static final int MAX_RESTITUTION = 8;
    /** A normal institutional service may proceed. */
    public static final String ALLOWED = "allowed";
    /** A public local case currently suspends the service. */
    public static final String REFUSED = "refused";
    /** Crime cannot authoritatively answer at this time. */
    public static final String UNAVAILABLE = "unavailable";

    private static final double MAX_INTERACTION_DISTANCE_SQUARED = 64.0D;

    private InstitutionalServiceApi() {
    }

    /**
     * The current workshop decision for this player and giver.
     *
     * <p>{@code fingerprint} binds the local public case ids and resolution revisions, policy flags,
     * community, and the returned completed restitution rows. A caller must compare it with a fresh
     * result before committing a paid commission. {@code restitution} belongs only to the requesting
     * player, is limited to the giver's current community, contains completed Crime-owned contracts,
     * and is an immutable list of at most {@link #MAX_RESTITUTION} rows.
     */
    public record WorkshopAccess(String status, String reason, String fingerprint,
                                 List<CivicContractView> restitution) {

        public WorkshopAccess {
            status = ALLOWED.equals(status) || REFUSED.equals(status) || UNAVAILABLE.equals(status)
                    ? status : UNAVAILABLE;
            reason = bounded(reason, 96);
            fingerprint = bounded(fingerprint, 64);
            List<CivicContractView> bounded = new ArrayList<>(MAX_RESTITUTION);
            if (restitution != null) {
                for (CivicContractView contract : restitution) {
                    if (contract != null && bounded.size() < MAX_RESTITUTION) {
                        bounded.add(contract);
                    }
                }
            }
            restitution = List.copyOf(bounded);
        }

        private static String bounded(String value, int limit) {
            if (value == null) {
                return "";
            }
            return value.length() <= limit ? value : value.substring(0, limit);
        }
    }

    /**
     * Checks whether a paid recognized-workshop service may proceed right now.
     *
     * <p>This is separate from ordinary {@link McaCrimeApi#serviceRefusal} policy. It never gates food
     * or shelter, does not consult personal victim memory or global standing, and fails unavailable
     * instead of allowing a paid transaction when Crime cannot establish the answer. Any unresolved
     * or escaped case that is public knowledge in the giver's current community refuses the workshop;
     * resolving all such cases restores access immediately.
     */
    public static WorkshopAccess workshop(ServerPlayer player, Entity giver) {
        if (player == null || giver == null) {
            return unavailable("invalid_request");
        }
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()
                || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return unavailable("requester_unavailable");
        }
        if (!(player.level() instanceof ServerLevel level) || giver.level() != level
                || giver.isRemoved() || !giver.isAlive()
                || level.getEntity(giver.getUUID()) != giver
                || player.distanceToSqr(giver) > MAX_INTERACTION_DISTANCE_SQUARED) {
            return unavailable("giver_unavailable");
        }

        try {
            boolean townsteadEnabled = McaCrimeConfig.COMMON.townsteadEnabled.get();
            boolean serviceRestrictions = McaCrimeConfig.COMMON.townsteadServiceRestrictions.get();
            boolean observationsEnabled = McaCrimeConfig.COMMON.enableObservations.get();
            double confidence = McaCrimeConfig.COMMON.reportConfidenceThreshold.get();
            InstitutionalWorkshopPolicy.Flags flags = new InstitutionalWorkshopPolicy.Flags(
                    townsteadEnabled, serviceRestrictions, observationsEnabled, confidence);

            if (!townsteadEnabled || !serviceRestrictions) {
                return from(InstitutionalWorkshopPolicy.decide(flags, null, List.of(), Set.of(),
                        List.of()));
            }
            CrimeCommunityKey community = CrimeCommunityResolver.resolve(giver, level).orElse(null);
            if (community == null) {
                return from(InstitutionalWorkshopPolicy.decide(flags, community, List.of(), Set.of(),
                        List.of()));
            }

            CrimeWorldData data = CrimeWorldData.get(server);
            if (data == null) {
                return unavailable("crime_data_unavailable");
            }
            if (data.isLoadFailed()) {
                return unavailable("crime_data_load_failed");
            }
            if (data.isReadOnlyFutureData()) {
                return unavailable("crime_data_read_only");
            }

            UUID requester = player.getUUID();
            List<CrimeRecordView> cases = data.recordsForOffender(requester).stream()
                    .map(record -> record.view())
                    .toList();
            Set<UUID> acceptedReports = new HashSet<>();
            data.reportsAgainst(requester).stream()
                    .filter(report -> report.supportsArrest(confidence))
                    .forEach(report -> acceptedReports.add(report.incidentId()));

            List<CivicContractView> restitution = completedLocalRestitution(data, requester, community);
            return from(InstitutionalWorkshopPolicy.decide(flags, community, cases, acceptedReports,
                    restitution));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime - institutional workshop query failed; unavailable", t);
            return unavailable("provider_error");
        }
    }

    private static List<CivicContractView> completedLocalRestitution(CrimeWorldData data, UUID requester,
                                                                     CrimeCommunityKey community) {
        List<ServiceContract> completed = data.serviceContractsFor(requester).stream()
                .filter(ServiceContract::offenderIsPlayer)
                .filter(contract -> community.equals(contract.community()))
                .filter(contract -> contract.state() == ServiceContract.State.COMPLETED)
                .sorted(Comparator.comparingLong(ServiceContract::resolvedAt).reversed()
                        .thenComparing(contract -> contract.contractId().toString()))
                .limit(MAX_RESTITUTION)
                .toList();
        List<CivicContractView> views = new ArrayList<>(completed.size());
        for (ServiceContract contract : completed) {
            views.add(new CivicContractView(contract.contractId(), contract.caseId(),
                    contract.offender(), contract.community().asString(), contract.task().id(),
                    contract.requiredUnits(), contract.completedUnits(), contract.deadline(),
                    contract.state().id()));
        }
        return List.copyOf(views);
    }

    private static WorkshopAccess from(InstitutionalWorkshopPolicy.Decision decision) {
        return new WorkshopAccess(decision.status(), decision.reason(), decision.fingerprint(),
                decision.restitution());
    }

    private static WorkshopAccess unavailable(String reason) {
        return new WorkshopAccess(UNAVAILABLE, reason, digest("institutional-workshop-v1\n" + reason),
                List.of());
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }
}
