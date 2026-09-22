package com.ultimakingdoms.politics;

import com.ultimakingdoms.api.politics.PoliticalFact;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.api.politics.PoliticalTransition.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoliticalSavedDataTest {
    @TempDir Path temporary;
    @Test void legacyTermsStayCeremonialAndNewClausesSurvivePersistence() {
        var legacy = PoliticalSavedData.JSON.toJsonTree(definition("agreement")).getAsJsonObject();
        legacy.remove("clauses");
        assertTrue(PoliticalSavedData.JSON.fromJson(legacy, Definition.class).clauses().isEmpty());
        var terms = new Definition(1,"agreement","hospitality",List.of(),Set.of(),List.of(),Set.of(),
                720000,2,true,"",Set.of(Clause.HOSPITALITY));
        var data = new PoliticalSavedData(); var state = data.transaction();
        UUID id = UUID.randomUUID();
        state.agreements.put(id,new Agreement(id,"test:first","test:second","test:hospitality",terms,"Visit",
                "a".repeat(64),Map.of("test:first",UUID.randomUUID(),"test:second",UUID.randomUUID()),AgreementState.ACTIVE,0,720000,0));
        data.commit(state);
        var loaded = PoliticalSavedData.load(data.save(new CompoundTag()));
        assertTrue(loaded.writable());
        assertEquals(Set.of(Clause.HOSPITALITY),loaded.records().agreements.get(id).terms().clauses());
        assertThrows(UnsupportedOperationException.class,()->terms.clauses().clear());
    }
    @Test void unknownClausesAndNonAgreementEffectsFailClosed() {
        var json = PoliticalSavedData.JSON.toJsonTree(definition("agreement")).getAsJsonObject();
        json.add("clauses",com.google.gson.JsonParser.parseString("[\"HOSPITLITY\"]"));
        assertThrows(RuntimeException.class,()->PoliticalSavedData.JSON.fromJson(json,Definition.class));
        assertThrows(IllegalArgumentException.class,()->new Definition(1,"honor","title",List.of(),Set.of(),
                List.of(),Set.of(),1,2,false,"",Set.of(Clause.HOSPITALITY)));
    }
    static Definition definition(String kind) { return new Definition(1,kind,"test.title",List.of(),Set.of(Permission.APPOINT),List.of(),Set.of(),720000,2,true,""); }
    @Test void governmentTermsReceiptsAndHistorySurviveRestart() {
        PoliticalSavedData data = new PoliticalSavedData(); var state = data.transaction();
        UUID player = UUID.randomUUID(), settlement = UUID.randomUUID(), request = UUID.randomUUID();
        Person person = new Person(player,Kind.PLAYER);
        state.governments.put("test:realm", new Government("test:realm",settlement,"test:charter",definition("government"),State.ACTIVE,
                Map.of("test:leader",new Office("test:leader",definition("office"),person,null,player,10)),Map.of(player,Set.of(Permission.RATIFY)),null,1));
        state.revision = 1;
        state.receipts.put(request,new Receipt(player,"fingerprint",new Result(true,1,"ok","test:realm")));
        state.journal.add(new Notice(request,"test:realm",Action.BOOTSTRAP,player,"test:realm",10));
        state.facts.add(new PoliticalFact(request,request,1,10,"test:realm",PoliticalFact.Type.GOVERNMENT_FOUNDED,
                player,"test:realm",Set.of("test:realm"),Set.of(player),PoliticalFact.Visibility.PUBLIC,
                PoliticalFact.Correction.CURRENT,null,"Government founded"));
        data.commit(state);
        CompoundTag saved = data.save(new CompoundTag()); PoliticalSavedData loaded = PoliticalSavedData.load(saved);
        assertTrue(loaded.writable()); assertEquals(saved,loaded.save(new CompoundTag()));
        assertEquals(settlement,loaded.records().governments.get("test:realm").capital());
        assertEquals(player,loaded.records().receipts.get(request).actor());
        assertEquals(request,loaded.records().facts.get(0).sourceReceiptId());
        assertThrows(UnsupportedOperationException.class, () -> loaded.records().facts.get(0).affectedPlayers().clear());
        assertThrows(UnsupportedOperationException.class, () -> loaded.records().governments.get("test:realm").mandates().clear());
    }
    @Test void malformedAndFuturePayloadsRemainReadOnlyWithoutLosingUnknownFields() {
        for (CompoundTag bad : List.of(future(),malformed())) {
            PoliticalSavedData loaded = PoliticalSavedData.load(bad);
            assertFalse(loaded.writable()); loaded.setDirty(); assertFalse(loaded.isDirty());
            assertEquals(bad,loaded.save(new CompoundTag()));
            assertThrows(IllegalStateException.class,loaded::transaction);
        }
    }
    @Test void failedTransactionCannotMutatePublishedState() {
        PoliticalSavedData data = new PoliticalSavedData(); var next = data.transaction(); next.revision = -1;
        assertThrows(IllegalArgumentException.class, () -> data.commit(next));
        assertEquals(0,data.records().revision); assertFalse(data.isDirty());
    }
    @Test void failedDurableCommitDoesNotPublishPoliticalCandidate() throws IOException {
        PoliticalSavedData data = new PoliticalSavedData(); var next = data.transaction(); next.revision = 1;
        Path blocking = temporary.resolve("not-a-directory"); Files.writeString(blocking,"block child creation");
        assertFalse(data.commitDurably(next,blocking.resolve("politics.dat").toFile()));
        assertEquals(0,data.records().revision); assertTrue(data.records().facts.isEmpty());
    }
    @Test void schemaOneWithoutTypedFactsLoadsAsEmptyHistory() {
        PoliticalSavedData data = new PoliticalSavedData(); CompoundTag tag = data.save(new CompoundTag());
        var json = com.google.gson.JsonParser.parseString(tag.getString("Records")).getAsJsonObject();
        json.remove("facts"); tag.putString("Records",json.toString());
        PoliticalSavedData loaded = PoliticalSavedData.load(tag);
        assertTrue(loaded.writable(),loaded.diagnostic()); assertTrue(loaded.records().facts.isEmpty());
    }
    @Test void rejectedTextAndTermsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new Request(UUID.randomUUID(),0,Action.PETITION,"test:realm","","",null,"","x".repeat(513),"",0,0,0));
        assertThrows(IllegalArgumentException.class, () -> new Definition(2,"government","title",List.of(),Set.of(),List.of(),Set.of(),1,2,false,""));
    }
    @Test void explicitTransitionRuleElectionBallotsAndRegencySurviveRestart() {
        var data=new PoliticalSavedData();var state=data.transaction();UUID leader=UUID.randomUUID(),candidateA=UUID.randomUUID(),candidateB=UUID.randomUUID(),settlement=UUID.randomUUID();
        Person leaderPerson=new Person(leader,Kind.PLAYER),successor=new Person(candidateA,Kind.PLAYER);
        state.governments.put("test:realm",new Government("test:realm",settlement,"test:charter",definition("government"),State.ACTIVE,
                Map.of("ultima_kingdoms:leader",new Office("ultima_kingdoms:leader",definition("office"),leaderPerson,null,leader,1)),Map.of(),successor,1));
        Rule rule=new Rule(true,true,2400,400,3600,4,Set.of(Permission.APPOINT,Permission.SEAT));state.transitionRules.put("test:realm",rule);
        UUID electionId=UUID.randomUUID();state.elections.put(electionId,new Election(electionId,"test:realm",Set.of(candidateA,candidateB),Set.of(leader,candidateA),
                Map.of(leader,candidateA),ElectionState.OPEN,10,2410,2810,1,null));
        state.regencies.put("test:realm",new Regency(UUID.randomUUID(),"test:realm",new Person(candidateB,Kind.PLAYER),rule.regentPermissions(),successor,10,3610,true,1));state.revision=1;data.commit(state);
        var loaded=PoliticalSavedData.load(data.save(new CompoundTag()));assertTrue(loaded.writable());assertEquals(rule,loaded.records().transitionRules.get("test:realm"));
        assertEquals(candidateA,loaded.records().elections.get(electionId).ballots().get(leader));assertEquals(successor,loaded.records().regencies.get("test:realm").preservedSuccessor());
    }
    @Test void deterministicWinnerRequiresUniquePositiveVoteAndTieStaysNeutral() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),v1=UUID.randomUUID(),v2=UUID.randomUUID(),id=UUID.randomUUID();
        Election unique=new Election(id,"test:realm",Set.of(a,b),Set.of(v1,v2),Map.of(v1,a,v2,a),ElectionState.OPEN,0,10,20,0,null);
        assertEquals(Optional.of(a),GovernmentService.uniqueWinner(unique));
        Election tied=new Election(id,"test:realm",Set.of(a,b),Set.of(v1,v2),Map.of(v1,a,v2,b),ElectionState.GRACE,0,10,20,0,null);
        assertTrue(GovernmentService.uniqueWinner(tied).isEmpty());
    }
    @Test void electionPastGraceExpiresEvenWithAUniqueWinner() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),voter=UUID.randomUUID(),id=UUID.randomUUID();
        Election election=new Election(id,"test:realm",Set.of(a,b),Set.of(voter),Map.of(voter,a),ElectionState.GRACE,0,10,20,0,null);
        assertEquals(ElectionState.RESOLVED,GovernmentService.closureState(election,19));
        assertEquals(ElectionState.EXPIRED,GovernmentService.closureState(election,20));
    }
    @Test void expiredRegentIsNotEligibleForANewElection() {
        Rule rule=new Rule(true,true,2400,400,3600,4,Set.of(Permission.APPOINT));
        Regency regency=new Regency(UUID.randomUUID(),"test:realm",new Person(UUID.randomUUID(),Kind.PLAYER),rule.regentPermissions(),null,10,20,true,1);
        assertTrue(GovernmentService.unexpired(regency,19));
        assertFalse(GovernmentService.unexpired(regency,20));
    }
    @Test void schemaOneWithoutTransitionFieldsMigratesToExplicitOptOut() {
        PoliticalSavedData data=new PoliticalSavedData();CompoundTag tag=data.save(new CompoundTag());var json=com.google.gson.JsonParser.parseString(tag.getString("Records")).getAsJsonObject();
        json.remove("transitionRules");json.remove("elections");json.remove("regencies");tag.putString("Records",json.toString());PoliticalSavedData loaded=PoliticalSavedData.load(tag);
        assertTrue(loaded.writable(),loaded.diagnostic());assertTrue(loaded.records().transitionRules.isEmpty());assertTrue(loaded.records().elections.isEmpty());assertTrue(loaded.records().regencies.isEmpty());
    }
    @Test void partialTransitionPayloadIsPreservedReadOnly() {
        PoliticalSavedData data=new PoliticalSavedData();CompoundTag tag=data.save(new CompoundTag());var json=com.google.gson.JsonParser.parseString(tag.getString("Records")).getAsJsonObject();
        json.remove("elections");tag.putString("Records",json.toString());PoliticalSavedData loaded=PoliticalSavedData.load(tag);
        assertFalse(loaded.writable());assertEquals(tag,loaded.save(new CompoundTag()));assertTrue(loaded.diagnostic().contains("Incomplete constitutional transitions"));
    }
    private static CompoundTag future() { CompoundTag tag = new CompoundTag();tag.putInt("Schema",99);tag.putString("Unknown","preserve");return tag; }
    private static CompoundTag malformed() { CompoundTag tag = new CompoundTag();tag.putInt("Schema",1);tag.putString("Records","{broken");return tag; }
}
