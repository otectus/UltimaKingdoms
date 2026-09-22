package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.InstitutionalServiceApi;
import dev.otectus.mcacrime.api.model.CivicContractView;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.api.model.CrimeRecordView;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure public-knowledge policy behind the authenticated institutional workshop API. */
public final class InstitutionalWorkshopPolicy {

    private InstitutionalWorkshopPolicy() {
    }

    /** Every configuration fact that can change this policy's meaning. */
    public record Flags(boolean townsteadEnabled, boolean serviceRestrictions,
                        boolean observationsEnabled, double reportConfidence) {
    }

    /** Immutable decision assembled without reading a server or mutable store. */
    public record Decision(String status, String reason, String fingerprint,
                           List<CivicContractView> restitution) {
        public Decision {
            restitution = restitution == null ? List.of() : List.copyOf(restitution.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(InstitutionalServiceApi.MAX_RESTITUTION)
                    .toList());
        }
    }

    /**
     * Evaluates only cases this community is allowed to know under the existing public-view rule.
     * The report set must already have been restricted to accepted reports at {@code reportConfidence}.
     */
    public static Decision decide(Flags flags, @Nullable CrimeCommunityKey community,
                                  List<CrimeRecordView> cases, Set<UUID> acceptedReports,
                                  List<CivicContractView> restitution) {
        if (flags == null) {
            return unavailable("policy_unavailable", "no_flags");
        }
        if (!flags.townsteadEnabled() || !flags.serviceRestrictions()) {
            return unavailable("service_disabled", fingerprintSeed(flags, community));
        }
        if (community == null) {
            return unavailable("community_unavailable", fingerprintSeed(flags, null));
        }
        if (cases == null || acceptedReports == null) {
            return unavailable("evidence_unavailable", fingerprintSeed(flags, community));
        }

        List<CrimeRecordView> relevant = new ArrayList<>();
        for (CrimeRecordView view : cases) {
            if (CrimePublicView.isPublic(view, community, flags.observationsEnabled(),
                    acceptedReports::contains)) {
                relevant.add(view);
            }
        }
        relevant.sort(Comparator.comparing(view -> view.id().toString()));

        boolean refused = relevant.stream().anyMatch(CrimeRecordView::actionable);
        List<CivicContractView> boundedRestitution = restitution == null ? List.of()
                : restitution.stream().filter(java.util.Objects::nonNull)
                        .limit(InstitutionalServiceApi.MAX_RESTITUTION).toList();
        String fingerprint = fingerprint(flags, community, relevant, boundedRestitution);
        return new Decision(refused ? InstitutionalServiceApi.REFUSED : InstitutionalServiceApi.ALLOWED,
                refused ? "public_local_case_unresolved" : "clear", fingerprint, boundedRestitution);
    }

    private static Decision unavailable(String reason, String seed) {
        return new Decision(InstitutionalServiceApi.UNAVAILABLE, reason, sha256(seed + "\n" + reason),
                List.of());
    }

    private static String fingerprint(Flags flags, CrimeCommunityKey community,
                                      List<CrimeRecordView> relevant,
                                      List<CivicContractView> restitution) {
        StringBuilder value = new StringBuilder(fingerprintSeed(flags, community));
        for (CrimeRecordView view : relevant) {
            value.append("case=").append(view.id()).append(':')
                    .append(view.resolutionRevision()).append(':')
                    .append(view.resolution().name()).append('\n');
        }
        for (CivicContractView contract : restitution) {
            value.append("restitution=").append(contract.contractId()).append(':')
                    .append(contract.caseId()).append(':').append(contract.state()).append('\n');
        }
        return sha256(value.toString());
    }

    private static String fingerprintSeed(Flags flags, @Nullable CrimeCommunityKey community) {
        return "institutional-workshop-v1\n"
                + "townstead=" + flags.townsteadEnabled() + '\n'
                + "restrictions=" + flags.serviceRestrictions() + '\n'
                + "observations=" + flags.observationsEnabled() + '\n'
                + "confidence=" + Double.toHexString(flags.reportConfidence()) + '\n'
                + "community=" + (community == null ? "" : community.asString()) + '\n';
    }

    private static String sha256(String value) {
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
