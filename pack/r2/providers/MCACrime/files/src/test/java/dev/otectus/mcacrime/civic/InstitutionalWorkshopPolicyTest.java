package dev.otectus.mcacrime.civic;

import dev.otectus.mcacrime.api.InstitutionalServiceApi;
import dev.otectus.mcacrime.api.model.CivicContractView;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.ledger.Resolution;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstitutionalWorkshopPolicyTest {

    private static final CrimeCommunityKey LOCAL =
            new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 4);
    private static final CrimeCommunityKey REMOTE =
            new CrimeCommunityKey(new ResourceLocation("minecraft", "overworld"), 9);
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("workshop-player".getBytes());
    private static final InstitutionalWorkshopPolicy.Flags NO_REPORT_LAYER =
            new InstitutionalWorkshopPolicy.Flags(true, true, false, 0.75D);
    private static final InstitutionalWorkshopPolicy.Flags REPORT_LAYER =
            new InstitutionalWorkshopPolicy.Flags(true, true, true, 0.75D);

    @Test
    void onlyPublicCasesInTheGiversCommunitySuspendTheWorkshop() {
        CrimeRecordView local = caseOf("local", LOCAL, true, Resolution.UNRESOLVED, 0L);
        CrimeRecordView remote = caseOf("remote", REMOTE, true, Resolution.UNRESOLVED, 0L);
        CrimeRecordView masked = caseOf("masked", LOCAL, false, Resolution.UNRESOLVED, 0L);

        assertEquals(InstitutionalServiceApi.REFUSED,
                decide(NO_REPORT_LAYER, List.of(local)).status());
        assertEquals(InstitutionalServiceApi.ALLOWED,
                decide(NO_REPORT_LAYER, List.of(remote, masked)).status());
    }

    @Test
    void aWitnessedCaseWaitsForAnAcceptedReportWhenObservationsAreEnabled() {
        CrimeRecordView seen = caseOf("seen", LOCAL, true, Resolution.UNRESOLVED, 0L);
        assertEquals(InstitutionalServiceApi.ALLOWED,
                InstitutionalWorkshopPolicy.decide(REPORT_LAYER, LOCAL, List.of(seen), Set.of(),
                        List.of()).status());
        assertEquals(InstitutionalServiceApi.REFUSED,
                InstitutionalWorkshopPolicy.decide(REPORT_LAYER, LOCAL, List.of(seen), Set.of(seen.id()),
                        List.of()).status());
    }

    @Test
    void resolvingThePublicCaseRestoresAccessAndChangesTheFingerprint() {
        CrimeRecordView open = caseOf("repair", LOCAL, true, Resolution.ESCAPED, 2L);
        CrimeRecordView resolved = caseOf("repair", LOCAL, true, Resolution.SERVED, 3L);
        InstitutionalWorkshopPolicy.Decision before = decide(NO_REPORT_LAYER, List.of(open));
        InstitutionalWorkshopPolicy.Decision after = decide(NO_REPORT_LAYER, List.of(resolved));

        assertEquals(InstitutionalServiceApi.REFUSED, before.status());
        assertEquals(InstitutionalServiceApi.ALLOWED, after.status());
        assertNotEquals(before.fingerprint(), after.fingerprint());
    }

    @Test
    void unknownCommunityAndDisabledPolicyAreUnavailable() {
        InstitutionalWorkshopPolicy.Flags disabled =
                new InstitutionalWorkshopPolicy.Flags(false, true, false, 0.75D);
        assertEquals(InstitutionalServiceApi.UNAVAILABLE,
                InstitutionalWorkshopPolicy.decide(disabled, LOCAL, List.of(), Set.of(), List.of()).status());
        assertEquals("service_disabled",
                InstitutionalWorkshopPolicy.decide(disabled, LOCAL, List.of(), Set.of(), List.of()).reason());
        assertEquals(InstitutionalServiceApi.UNAVAILABLE,
                InstitutionalWorkshopPolicy.decide(NO_REPORT_LAYER, null, List.of(), Set.of(), List.of()).status());
    }

    @Test
    void remoteAndPrivateCasesCannotPerturbTheLocalFingerprint() {
        InstitutionalWorkshopPolicy.Decision empty = decide(NO_REPORT_LAYER, List.of());
        InstitutionalWorkshopPolicy.Decision hidden = decide(NO_REPORT_LAYER, List.of(
                caseOf("remote-revision", REMOTE, true, Resolution.ESCAPED, 47L),
                caseOf("private-revision", LOCAL, false, Resolution.ESCAPED, 91L)));
        assertEquals(empty.fingerprint(), hidden.fingerprint());
    }

    @Test
    void restitutionIsDefensivelyCopiedAndBounded() {
        List<CivicContractView> source = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            source.add(contract(index));
        }
        InstitutionalWorkshopPolicy.Decision decision =
                InstitutionalWorkshopPolicy.decide(NO_REPORT_LAYER, LOCAL, List.of(), Set.of(), source);
        source.clear();

        assertEquals(InstitutionalServiceApi.MAX_RESTITUTION, decision.restitution().size());
        assertThrows(UnsupportedOperationException.class,
                () -> decision.restitution().add(contract(99)));
    }

    private static InstitutionalWorkshopPolicy.Decision decide(InstitutionalWorkshopPolicy.Flags flags,
                                                                List<CrimeRecordView> cases) {
        return InstitutionalWorkshopPolicy.decide(flags, LOCAL, cases, Set.of(), List.of());
    }

    private static CrimeRecordView caseOf(String seed, CrimeCommunityKey community, boolean witnessed,
                                           Resolution resolution, long revision) {
        return new CrimeRecordView(UUID.nameUUIDFromBytes(seed.getBytes()), PLAYER, Optional.empty(),
                new ResourceLocation("mcacrime", "theft"), Optional.ofNullable(community),
                witnessed ? Set.of(UUID.nameUUIDFromBytes((seed + "-witness").getBytes())) : Set.of(),
                witnessed, 100L, 10L, -2L, 5L, 0L, resolution, revision, Optional.empty(), Map.of());
    }

    private static CivicContractView contract(int index) {
        return new CivicContractView(UUID.nameUUIDFromBytes(("contract-" + index).getBytes()),
                UUID.nameUUIDFromBytes(("case-" + index).getBytes()), PLAYER, LOCAL.asString(),
                "victim_amends", 2, 2, 1000L, "completed");
    }
}
