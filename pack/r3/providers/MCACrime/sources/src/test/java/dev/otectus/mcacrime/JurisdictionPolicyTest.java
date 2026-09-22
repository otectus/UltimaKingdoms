package dev.otectus.mcacrime;

import dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicies;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionSnapshot;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JurisdictionPolicyTest {
    private static final CrimeCommunityKey LOCAL = new CrimeCommunityKey(
            new ResourceLocation("minecraft:overworld"), 7);

    @Test void occupiedCivilianLawAndLocalEvidenceRemainAuthoritative() {
        var evidence = Optional.of(snapshot(true, false, JurisdictionSnapshot.Cooperation.LOCAL_ONLY));
        assertTrue(JurisdictionPolicies.civilianLawContinues(evidence));
        assertFalse(JurisdictionPolicies.allowsSharedReports(evidence));
        assertTrue(JurisdictionPolicies.allowsStandaloneWanted(evidence));
    }

    @Test void safeConductSuppressesOnlyStandaloneRemoteWantedEnforcement() {
        var evidence = Optional.of(snapshot(true, true, JurisdictionSnapshot.Cooperation.LOCAL_ONLY));
        assertFalse(JurisdictionPolicies.allowsStandaloneWanted(evidence));
        assertTrue(JurisdictionPolicies.civilianLawContinues(evidence));
        assertThrows(IllegalArgumentException.class, () -> new JurisdictionSnapshot(LOCAL, "ultima:local",
                "ultima:madera", "recruits:red", "ultima:civil", JurisdictionSnapshot.CivilLaw.CONTINUES,
                JurisdictionSnapshot.Cooperation.LOCAL_ONLY, true, false, false, true, 4,
                JurisdictionSnapshot.Availability.AVAILABLE, "Invalid immunity"));
    }

    @Test void unavailableEvidencePreservesExistingCrimeRules() {
        var unavailable = Optional.of(new JurisdictionSnapshot(LOCAL, "ultima:local", "ultima:madera",
                "recruits:red", "ultima:civil", JurisdictionSnapshot.CivilLaw.UNKNOWN,
                JurisdictionSnapshot.Cooperation.LOCAL_ONLY, true, true, true, true, 4,
                JurisdictionSnapshot.Availability.SUSPENDED, "Provider absent"));
        assertTrue(JurisdictionPolicies.allowsSharedReports(unavailable));
        assertTrue(JurisdictionPolicies.allowsStandaloneWanted(unavailable));
        assertTrue(JurisdictionPolicies.civilianLawContinues(unavailable));
    }

    @Test void guardSeamUsesJurisdictionEvidenceWithoutMutatingHeatOrCases() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/otectus/mcacrime/justice/JusticeService.java"));
        int method = source.indexOf("public static LegalDecision forGuard");
        int resolve = source.indexOf("JurisdictionPolicies.resolve", method);
        int evaluate = source.indexOf("return evaluate", method);
        assertTrue(method >= 0 && resolve > method && evaluate > resolve);
        String body = source.substring(method, source.indexOf("public static LegalDecision evaluate", method));
        assertFalse(body.contains("setHeat"));
        assertFalse(body.contains("resolveCase"));
        assertTrue(body.contains("allowsStandaloneWanted"));

        String api = Files.readString(Path.of("src/main/java/dev/otectus/mcacrime/api/McaCrimeApi.java"));
        int local = api.indexOf("public static boolean mayEnforce");
        String localBody = api.substring(local, api.indexOf("// ------------------------------------------------------------------ snapshots", local));
        assertTrue(local >= 0 && localBody.contains("JurisdictionPolicies.jurisdictionOf"));
        assertTrue(localBody.contains("JusticeService.evaluate"));
        assertTrue(localBody.contains("LegalTarget.isMaskedPursuit"));
        assertFalse(localBody.contains("OutlawResolver.resolve"), "global outlaw projection is not local evidence");
        assertFalse(localBody.contains("setHeat"));
    }

    private static JurisdictionSnapshot snapshot(boolean safe, boolean immunity,
                                                  JurisdictionSnapshot.Cooperation cooperation) {
        return new JurisdictionSnapshot(LOCAL, "ultima:local", "ultima:madera", "recruits:red",
                "ultima:civil", JurisdictionSnapshot.CivilLaw.CONTINUES, cooperation, true, false,
                safe, immunity, 4, JurisdictionSnapshot.Availability.AVAILABLE,
                "Civil law continues under occupation");
    }
}
