package com.ultimakingdoms.politics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.api.politics.PoliticalTransition.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.io.File;
import java.util.*;

/** Copy-on-write transactions. Future/malformed payloads remain untouched and read-only. */
public final class PoliticalSavedData extends SavedData {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final String NAME = "ultima_kingdoms_politics";
    static final int SCHEMA = 1;
    static final class Records {
        long revision;
        Map<String, Government> governments = new LinkedHashMap<>();
        Map<UUID, Agreement> agreements = new LinkedHashMap<>();
        Map<UUID, Petition> petitions = new LinkedHashMap<>();
        Map<UUID, Recognition> recognitions = new LinkedHashMap<>();
        Map<UUID, Honor> honors = new LinkedHashMap<>();
        Map<String, House> houses = new LinkedHashMap<>();
        Map<UUID, Receipt> receipts = new LinkedHashMap<>();
        List<Notice> journal = new ArrayList<>();
        List<com.ultimakingdoms.api.politics.PoliticalFact> facts = new ArrayList<>();
        Map<String, Rule> transitionRules = new LinkedHashMap<>();
        Map<UUID, Election> elections = new LinkedHashMap<>();
        Map<String, Regency> regencies = new LinkedHashMap<>();
        void validate() {
            if (revision < 0 || governments.size() > 128 || agreements.size() > 4096 || petitions.size() > 4096
                    || recognitions.size() > 4096 || honors.size() > 8192 || houses.size() > 128
                    || receipts.size() > 8192 || journal.size() > 32768 || facts.size() > 32768
                    || transitionRules.size() > 128 || elections.size() > 512 || regencies.size() > 128) throw new IllegalArgumentException("Political save limits exceeded");
            governments.forEach((key, value) -> {
                id(key); id(value.profileId());
                if (!key.equals(value.kingdom()) || value.revision() < 0 || value.revision() > revision || value.offices().size() > 64 || value.mandates().size() > 128)
                    throw new IllegalArgumentException("Invalid government");
                value.offices().forEach((officeKey, office) -> {
                    id(office.definitionId()); Objects.requireNonNull(office.terms()); Objects.requireNonNull(office.holder()); Objects.requireNonNull(office.appointedBy());
                    String expected = office.definitionId() + (office.settlement() == null ? "" : "@" + office.settlement());
                    if (!officeKey.equals(expected)) throw new IllegalArgumentException("Invalid office scope");
                });
            });
            agreements.forEach((key, value) -> {
                id(value.definitionId()); id(value.proposer()); id(value.recipient());
                if (!key.equals(value.id()) || value.termsHash() == null || !value.termsHash().matches("[0-9a-f]{64}")
                        || value.signatures().size() > 2 || !Set.of(value.proposer(), value.recipient()).containsAll(value.signatures().keySet())
                        || value.state() == AgreementState.ACTIVE && value.signatures().size() != 2)
                    throw new IllegalArgumentException("Invalid agreement identity/signatures");
            });
            petitions.forEach((key, value) -> { id(value.kingdom()); id(value.definitionId()); Objects.requireNonNull(value.settlement()); if (!key.equals(value.id())) throw new IllegalArgumentException("Invalid petition ID"); });
            recognitions.forEach((key, value) -> { id(value.kingdom()); id(value.definitionId()); Objects.requireNonNull(value.terms()); Objects.requireNonNull(value.building()); Objects.requireNonNull(value.awardedBy()); if (!key.equals(value.id())) throw new IllegalArgumentException("Invalid recognition ID"); });
            honors.forEach((key, value) -> { id(value.kingdom()); id(value.definitionId()); Objects.requireNonNull(value.terms()); Objects.requireNonNull(value.recipient()); Objects.requireNonNull(value.evidence()); if (!key.equals(value.id())) throw new IllegalArgumentException("Invalid honor ID"); });
            receipts.forEach((key, value) -> { Objects.requireNonNull(key); Objects.requireNonNull(value.actor()); Objects.requireNonNull(value.fingerprint()); Objects.requireNonNull(value.result()); });
            journal.forEach(value -> { Objects.requireNonNull(value.id()); id(value.kingdom()); Objects.requireNonNull(value.action()); Objects.requireNonNull(value.actor()); });
            Set<UUID> factIds = new HashSet<>();
            facts.forEach(value -> {
                Objects.requireNonNull(value); id(value.kingdom());
                if (value.revision() > revision || !factIds.add(value.id()) || !value.affectedKingdoms().contains(value.kingdom())
                        || value.correction() != com.ultimakingdoms.api.politics.PoliticalFact.Correction.CURRENT
                        || value.correctedBy() != null) throw new IllegalArgumentException("Invalid persisted political fact");
            });
            transitionRules.forEach((kingdom, rule) -> { id(kingdom); Objects.requireNonNull(rule); if (!governments.containsKey(kingdom)) throw new IllegalArgumentException("Transition rule without government"); });
            elections.forEach((key, election) -> { Objects.requireNonNull(election); id(election.kingdom()); Rule rule=transitionRules.get(election.kingdom());
                if (!key.equals(election.id()) || !governments.containsKey(election.kingdom()) || (election.state()==ElectionState.OPEN||election.state()==ElectionState.GRACE)
                        &&(rule==null||!rule.elections()||election.candidates().size()>rule.maxCandidates())) throw new IllegalArgumentException("Invalid election identity"); });
            regencies.forEach((kingdom, regency) -> { id(kingdom); Objects.requireNonNull(regency); Government government=governments.get(kingdom);Rule rule=transitionRules.get(kingdom);
                if (!kingdom.equals(regency.kingdom()) || government==null || regency.active()&&(rule==null||!rule.regency()||!rule.regentPermissions().containsAll(regency.permissions())
                        ||!Objects.equals(regency.preservedSuccessor(),government.successor()))) throw new IllegalArgumentException("Invalid regency identity"); });
        }
        private static void id(String id) {
            if (id == null || id.length() > 128 || net.minecraft.resources.ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("Malformed political resource ID");
        }
    }
    private Records records = new Records();
    private CompoundTag preserved;
    private String diagnostic = "";
    public static PoliticalSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PoliticalSavedData::load, PoliticalSavedData::new, NAME);
    }
    static PoliticalSavedData load(CompoundTag tag) {
        PoliticalSavedData data = new PoliticalSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != SCHEMA
                    || !tag.contains("Records", Tag.TAG_STRING)) throw new IllegalArgumentException("Unsupported political schema/payload");
            var json = com.google.gson.JsonParser.parseString(tag.getString("Records")).getAsJsonObject();
            boolean legacyHistory = !json.has("facts");
            boolean hasTransitionRules=json.has("transitionRules"),hasElections=json.has("elections"),hasRegencies=json.has("regencies");
            boolean legacyTransitions=!hasTransitionRules&&!hasElections&&!hasRegencies;
            if(!legacyTransitions&&!(hasTransitionRules&&hasElections&&hasRegencies))throw new IllegalArgumentException("Incomplete constitutional transitions");
            data.records = JSON.fromJson(json, Records.class);
            if (data.records == null) throw new IllegalArgumentException("Missing political records");
            // Schema 1 predates typed history. Its absence is the only repaired legacy field.
            if (data.records.facts == null) {
                if (!legacyHistory) throw new IllegalArgumentException("Malformed political facts");
                data.records.facts = new ArrayList<>();
            }
            if (legacyTransitions) {
                data.records.transitionRules = new LinkedHashMap<>(); data.records.elections = new LinkedHashMap<>(); data.records.regencies = new LinkedHashMap<>();
            } else if (data.records.transitionRules == null || data.records.elections == null || data.records.regencies == null)
                throw new IllegalArgumentException("Malformed constitutional transitions");
            data.records.validate();
        } catch (RuntimeException failure) {
            data.records = new Records(); data.preserved = tag.copy();
            data.diagnostic = "Political data is read-only: " + failure.getMessage();
        }
        return data;
    }
    boolean writable() { return preserved == null; }
    String diagnostic() { return diagnostic; }
    Records records() { return records; }
    Records transaction() {
        if (!writable()) throw new IllegalStateException(diagnostic);
        return JSON.fromJson(JSON.toJson(records), Records.class);
    }
    void commit(Records next) { next.validate(); records = next; setDirty(); }
    /** Durable political transaction fence used before acknowledging or publishing a result. */
    boolean commitDurably(MinecraftServer server, Records next) {
        return commitDurably(next, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(NAME+".dat").toFile());
    }
    boolean commitDurably(Records next, File destination) {
        if(!writable())return false;next.validate();
        try {
            var tag=new CompoundTag();tag.putInt("Schema",SCHEMA);tag.putString("Records",JSON.toJson(next));
            com.ultimakingdoms.persistence.AtomicSavedDataWriter.write(destination,tag);
            records=next;setDirty(false);return true;
        } catch(java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Political durable commit failed",failure);return false; }
    }
    @Override public boolean isDirty() { return writable() && super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) {
        if (!writable()) return preserved.copy();
        tag.putInt("Schema", SCHEMA); tag.putString("Records", JSON.toJson(records)); return tag;
    }
}
