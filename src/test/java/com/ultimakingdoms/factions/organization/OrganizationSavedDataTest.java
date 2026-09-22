package com.ultimakingdoms.factions.organization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationSavedDataTest {
    @TempDir
    Path temporary;

    @Test
    void membershipAndEvidenceSurviveSaveReload() {
        UUID player = UUID.randomUUID();
        UUID effect = UUID.randomUUID();
        ResourceLocation organization = new ResourceLocation("test", "guild");
        OrganizationSavedData data = new OrganizationSavedData();
        OrganizationSavedData.Records records = data.transaction();
        records.revision = 2L;
        OrganizationSavedData.MembershipRecord membership = new OrganizationSavedData.MembershipRecord(player, organization);
        membership.active = true;
        membership.everJoined = true;
        membership.standing = 25L;
        membership.deedCount = 1;
        membership.joinedAt = 40L;
        membership.revision = 1L;
        records.memberships.put(OrganizationSavedData.key(player, organization), membership);
        OrganizationSavedData.EvidenceRecord evidence = new OrganizationSavedData.EvidenceRecord();
        evidence.effectId = effect;
        evidence.sourceReceiptId = UUID.randomUUID();
        evidence.player = player;
        evidence.organization = organization.toString();
        evidence.questId = "test:quest";
        evidence.requestedCredit = 25;
        evidence.appliedCredit = 25;
        evidence.gameTime = 45L;
        evidence.revision = 2L;
        evidence.fingerprint = "a".repeat(64);
        records.receipts.put(effect, evidence);
        data.commit(records);

        OrganizationSavedData reloaded = OrganizationSavedData.load(data.save(new CompoundTag()));
        assertTrue(reloaded.writable());
        assertEquals(25L, reloaded.records().memberships.get(
                OrganizationSavedData.key(player, organization)).standing);
        assertEquals("test:quest", reloaded.records().receipts.get(effect).questId);
    }

    @Test
    void futureSchemaIsPreservedExactlyAndReadOnly() {
        CompoundTag future = new CompoundTag();
        future.putInt("Schema", 2);
        future.putString("FutureField", "untouched");
        OrganizationSavedData data = OrganizationSavedData.load(future);
        assertFalse(data.writable());
        assertEquals(future, data.save(new CompoundTag()));
    }

    @Test
    void corruptCurrentPayloadIsPreservedExactlyAndReadOnly() {
        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("Schema", 1);
        corrupt.putString("Records", "{not-json");
        corrupt.putLong("OperatorMarker", 91L);
        OrganizationSavedData data = OrganizationSavedData.load(corrupt);
        assertFalse(data.writable());
        assertEquals(corrupt, data.save(new CompoundTag()));
    }

    @Test
    void perEffectIdsReplayDeterministicallyAndPartitionOrganizations() {
        UUID player = UUID.randomUUID();
        UUID sourceReceipt = UUID.randomUUID();
        ResourceLocation first = new ResourceLocation("test", "first");
        ResourceLocation second = new ResourceLocation("test", "second");
        UUID a = OrganizationServiceImpl.effectId(player, first, sourceReceipt, "test:quest");
        UUID replay = OrganizationServiceImpl.effectId(player, first, sourceReceipt, "test:quest");
        UUID otherEffect = OrganizationServiceImpl.effectId(player, second, sourceReceipt, "test:quest");
        assertEquals(a, replay);
        assertNotEquals(a, otherEffect);
    }

    @Test
    void replayClassificationSurvivesRestartAndRejectsChangedPayload() {
        UUID player = UUID.randomUUID();
        UUID sourceReceipt = UUID.randomUUID();
        ResourceLocation organization = new ResourceLocation("test", "guild");
        String quest = "test:quest";
        UUID effect = OrganizationServiceImpl.effectId(player, organization, sourceReceipt, quest);
        String fingerprint = OrganizationServiceImpl.fingerprint(player, organization, sourceReceipt,
                quest, 20, java.util.Optional.empty());
        OrganizationSavedData data = new OrganizationSavedData();
        OrganizationSavedData.Records records = data.transaction();
        records.revision = 1L;
        OrganizationSavedData.EvidenceRecord evidence = new OrganizationSavedData.EvidenceRecord();
        evidence.effectId = effect;
        evidence.sourceReceiptId = sourceReceipt;
        evidence.player = player;
        evidence.organization = organization.toString();
        evidence.questId = quest;
        evidence.requestedCredit = 20;
        evidence.appliedCredit = 20;
        evidence.gameTime = 1L;
        evidence.revision = 1L;
        evidence.fingerprint = fingerprint;
        records.receipts.put(effect, evidence);
        data.commit(records);

        OrganizationSavedData reloaded = OrganizationSavedData.load(data.save(new CompoundTag()));
        assertEquals(OrganizationEvidenceLedger.Status.MATCH,
                OrganizationEvidenceLedger.replay(reloaded.records(), effect, fingerprint).status());
        String changed = OrganizationServiceImpl.fingerprint(player, organization, sourceReceipt,
                quest, 21, java.util.Optional.empty());
        assertEquals(OrganizationEvidenceLedger.Status.CONFLICT,
                OrganizationEvidenceLedger.replay(reloaded.records(), effect, changed).status());
        assertEquals(effect, OrganizationEvidenceLedger.sourceReceipt(reloaded.records(), player,
                organization, sourceReceipt).effectId);
    }

    @Test
    void failedDurableCommitDoesNotPublishCandidateRecords() throws IOException {
        OrganizationSavedData data = new OrganizationSavedData();
        OrganizationSavedData.Records candidate = data.transaction();
        candidate.revision = 1L;
        Path blockingFile = temporary.resolve("not-a-directory");
        Files.writeString(blockingFile, "block child creation");

        assertFalse(data.commitDurably(candidate, blockingFile.resolve("organizations.dat").toFile()));
        assertEquals(0L, data.records().revision);
        assertTrue(data.records().memberships.isEmpty());
        assertTrue(data.records().receipts.isEmpty());
    }

    @Test
    void lifecycleFrozenDefinitionAndTombstoneSurviveRestart() {
        OrganizationSavedData data=new OrganizationSavedData();var records=data.transaction();records.revision=3;
        var lifecycle=lifecycle("test:founded","test:template",UUID.randomUUID());lifecycle.state="DISSOLVED";lifecycle.revision=3;
        records.lifecycles.put(lifecycle.id,lifecycle);data.commit(records);
        var loaded=OrganizationSavedData.load(data.save(new CompoundTag()));
        assertTrue(loaded.writable());assertEquals("DISSOLVED",loaded.records().lifecycles.get("test:founded").state);
        assertEquals("test:quest",loaded.records().lifecycles.get("test:founded").definition.deeds.get(0).quest);
    }

    @Test
    void redirectsRejectCyclesAndResolveStableHistoricalIds() {
        OrganizationSavedData data=new OrganizationSavedData();var records=data.transaction();records.revision=1;
        records.redirects.put("test:old","test:new");
        assertEquals("test:new",OrganizationServiceImpl.resolveId(records,"test:old"));
        records.redirects.put("test:new","test:old");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->data.commit(records));
    }

    @Test
    void mergeHonorsOptOutAndNeverCopiesSourceStanding() {
        OrganizationSavedData.Records records=new OrganizationSavedData.Records();records.revision=9;
        UUID moving=UUID.randomUUID(),leaving=UUID.randomUUID();
        var sourceMoving=new OrganizationSavedData.MembershipRecord(moving,new ResourceLocation("test:source"));sourceMoving.active=true;sourceMoving.everJoined=true;sourceMoving.standing=400;
        var sourceLeaving=new OrganizationSavedData.MembershipRecord(leaving,new ResourceLocation("test:source"));sourceLeaving.active=true;sourceLeaving.everJoined=true;sourceLeaving.standing=300;
        var targetPrior=new OrganizationSavedData.MembershipRecord(moving,new ResourceLocation("test:target"));targetPrior.everJoined=true;targetPrior.standing=7;
        records.memberships.put(OrganizationSavedData.key(moving,"test:source"),sourceMoving);records.memberships.put(OrganizationSavedData.key(leaving,"test:source"),sourceLeaving);
        records.memberships.put(OrganizationSavedData.key(moving,"test:target"),targetPrior);
        var merge=new OrganizationSavedData.MergeRecord();merge.source="test:source";merge.target="test:target";merge.optOuts.add(leaving);
        OrganizationServiceImpl.applyMembershipMerge(records,merge,100);
        assertFalse(sourceMoving.active);assertFalse(sourceLeaving.active);assertTrue(targetPrior.active);assertEquals(7,targetPrior.standing);
        assertFalse(records.memberships.containsKey(OrganizationSavedData.key(leaving,"test:target")));
    }

    @Test
    void revisionFenceAndLifecycleAuthorityAreExplicit() {
        var records=new OrganizationSavedData.Records();records.revision=4;
        assertTrue(OrganizationServiceImpl.revisionMatches(records,4));assertFalse(OrganizationServiceImpl.revisionMatches(records,3));
        UUID founder=UUID.randomUUID(),stranger=UUID.randomUUID();var lifecycle=lifecycle("test:founded","test:template",founder);
        assertTrue(OrganizationServiceImpl.lifecycleAuthorized(founder,lifecycle,(k,p)->false));
        assertFalse(OrganizationServiceImpl.lifecycleAuthorized(stranger,lifecycle,(k,p)->false));
        assertTrue(OrganizationServiceImpl.lifecycleAuthorized(stranger,lifecycle,(k,p)->p==com.ultimakingdoms.api.politics.Politics.Permission.RATIFY));
    }

    private static OrganizationSavedData.LifecycleRecord lifecycle(String id,String template,UUID founder) {
        var result=new OrganizationSavedData.LifecycleRecord();result.id=id;result.template=template;result.sponsorKingdom="test:realm";
        result.displayName="Founded Guild";result.state="ACTIVE";result.founder=founder;result.createdAt=1;result.revision=1;
        var definition=new OrganizationSavedData.DefinitionRecord();definition.kind="guild";definition.nameKey="organization.test";definition.descriptionKey="organization.test.description";
        var rank=new OrganizationSavedData.RankRecord();rank.id="test:member";rank.permissions=Set.of("test:service");definition.ranks.add(rank);
        var deed=new OrganizationSavedData.DeedRecord();deed.quest="test:quest";deed.credit=10;definition.deeds.add(deed);result.definition=definition;return result;
    }
}
