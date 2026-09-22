package com.ultimakingdoms.factions.organization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Independent schema-1 organization state. Unsupported or corrupt payloads are retained read-only. */
final class OrganizationSavedData extends SavedData {
    static final String DATA_NAME = "ultima_kingdoms_organizations";
    static final int SCHEMA = 1;
    static final int MAX_MEMBERSHIPS = 32_768;
    static final int MAX_RECEIPTS = 16_384;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    static final class Records {
        long revision;
        Map<String, MembershipRecord> memberships = new LinkedHashMap<>();
        Map<UUID, EvidenceRecord> receipts = new LinkedHashMap<>();
        java.util.List<HistoryRecord> history = new java.util.ArrayList<>();
        Map<String, LifecycleRecord> lifecycles = new LinkedHashMap<>();
        Map<String, String> redirects = new LinkedHashMap<>();
        Map<UUID, MergeRecord> merges = new LinkedHashMap<>();

        void validate() {
            if (revision < 0 || memberships == null || receipts == null || history == null || lifecycles == null
                    || redirects == null || merges == null || history.size()>8192 || lifecycles.size()>128
                    || redirects.size()>128 || merges.size()>512 || memberships.size() > MAX_MEMBERSHIPS || receipts.size() > MAX_RECEIPTS) {
                throw new IllegalArgumentException("organization save limits exceeded");
            }
            for(var entry:history) {
                if(entry==null||entry.id==null||entry.player==null||entry.revision<0||entry.revision>revision||entry.gameTime<0
                        ||!java.util.Set.of("joined","left","deed_recorded","founded","merge_proposed","merge_consented",
                        "merge_opted_out","merged","dissolved","obligation_novated").contains(entry.action))throw new IllegalArgumentException("Invalid organization history");
                resource(entry.organization);
            }
            memberships.forEach((key, value) -> {
                Objects.requireNonNull(value, "membership");
                resource(value.organization);
                if (!key.equals(key(value.player, value.organization)) || value.joinedAt < -1L || value.leftAt < -1L
                        || value.deedCount < 0 || value.revision < 0 || value.revision > revision) {
                    throw new IllegalArgumentException("invalid organization membership");
                }
                Objects.requireNonNull(value.player, "membership player");
                if (value.active && !value.everJoined) throw new IllegalArgumentException("active membership was never joined");
            });
            receipts.forEach((key, value) -> {
                Objects.requireNonNull(value, "receipt");
                resource(value.organization);
                Objects.requireNonNull(value.effectId, "effect id");
                Objects.requireNonNull(value.sourceReceiptId, "source receipt");
                Objects.requireNonNull(value.player, "receipt player");
                if (!key.equals(value.effectId) || value.questId == null || value.questId.length() > 128
                        || ResourceLocation.tryParse(value.questId) == null || value.gameTime < 0L
                        || value.revision < 0L || value.revision > revision || value.fingerprint == null
                        || !value.fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid organization evidence receipt");
                }
            });
            lifecycles.forEach((key,value)->{
                Objects.requireNonNull(value,"lifecycle");resource(key);resource(value.id);resource(value.template);
                resource(value.sponsorKingdom);Objects.requireNonNull(value.founder,"founder");Objects.requireNonNull(value.definition,"definition");
                if(!key.equals(value.id)||value.createdAt<0||value.revision<0||value.revision>revision
                        ||value.displayName==null||value.displayName.isBlank()||value.displayName.length()>64
                        ||!Set.of("ACTIVE","MERGED","DISSOLVED").contains(value.state))throw new IllegalArgumentException("invalid organization lifecycle");
                if(value.state.equals("MERGED")){resource(value.redirect);if(value.redirect.equals(value.id))throw new IllegalArgumentException("self redirect");}
                else if(value.redirect!=null)throw new IllegalArgumentException("unexpected organization redirect");
                value.definition.validate();
            });
            redirects.forEach((source,target)->{resource(source);resource(target);var lifecycle=lifecycles.get(source);
                if(source.equals(target)||lifecycle==null||!lifecycle.state.equals("MERGED")||!target.equals(lifecycle.redirect)||!lifecycles.containsKey(target))
                    throw new IllegalArgumentException("invalid lifecycle redirect");});
            for(String source:redirects.keySet())resolve(redirects,source);
            merges.forEach((key,value)->{
                Objects.requireNonNull(value,"merge");resource(value.source);resource(value.target);Objects.requireNonNull(value.proposer);
                if(!key.equals(value.id)||value.source.equals(value.target)||value.revision<0||value.revision>revision
                        ||value.optOuts==null||value.optOuts.size()>32768||!lifecycles.containsKey(value.source)||!lifecycles.containsKey(value.target)
                        ||!Set.of("PENDING","CONSENTED","APPLIED","CANCELLED").contains(value.state)
                        ||value.state.equals("PENDING")&&value.targetConsent||value.state.equals("CONSENTED")&&(!value.sourceConsent||!value.targetConsent)
                        ||value.state.equals("APPLIED")&&!redirects.containsKey(value.source))
                    throw new IllegalArgumentException("invalid organization merge");
            });
        }
    }

    static final class LifecycleRecord {
        String id,template,sponsorKingdom,displayName,state,redirect;
        UUID founder;
        long createdAt,revision;
        DefinitionRecord definition;
    }

    static final class MergeRecord {
        UUID id,proposer;
        String source,target,state;
        boolean sourceConsent,targetConsent;
        Set<UUID> optOuts=new LinkedHashSet<>();
        long revision;
    }

    static final class DefinitionRecord {
        String kind,nameKey,descriptionKey,exclusiveGroup;
        Set<String> conflicts=new LinkedHashSet<>();
        List<RankRecord> ranks=new ArrayList<>();
        List<ServiceRecord> services=new ArrayList<>();
        List<DeedRecord> deeds=new ArrayList<>();
        void validate(){
            if(kind==null||nameKey==null||descriptionKey==null||conflicts==null||ranks==null||services==null||deeds==null
                    ||!Set.of("guild","order","institution","civic_group").contains(kind)||nameKey.length()>128||descriptionKey.length()>160
                    ||ranks.isEmpty()||ranks.size()>32||services.size()>64||deeds.size()>128)throw new IllegalArgumentException("invalid frozen definition");
            if(exclusiveGroup!=null)resource(exclusiveGroup);conflicts.forEach(OrganizationSavedData::resource);
            ranks.forEach(RankRecord::validate);services.forEach(ServiceRecord::validate);deeds.forEach(DeedRecord::validate);
            Set<String> rankIds=new HashSet<>(),serviceIds=new HashSet<>(),questIds=new HashSet<>();long threshold=Long.MIN_VALUE;
            for(var rank:ranks){if(!rankIds.add(rank.id)||rank.standing<=threshold)throw new IllegalArgumentException("invalid frozen rank order");threshold=rank.standing;}
            for(var service:services)if(!serviceIds.add(service.permission)||!rankIds.contains(service.rank))throw new IllegalArgumentException("invalid frozen service reference");
            for(var deed:deeds)if(!questIds.add(deed.quest))throw new IllegalArgumentException("duplicate frozen deed");
        }
    }
    static final class RankRecord { String id;long standing;Set<String> permissions=new LinkedHashSet<>();void validate(){resource(id);if(permissions==null||permissions.size()>64)throw new IllegalArgumentException("invalid frozen rank");permissions.forEach(OrganizationSavedData::resource);} }
    static final class ServiceRecord { String permission,rank;long standing,neutralStanding;int deeds,neutralDeeds;void validate(){resource(permission);resource(rank);if(deeds<0||neutralDeeds< -1||(neutralStanding<0)!=(neutralDeeds<0))throw new IllegalArgumentException("invalid frozen service");} }
    static final class DeedRecord { String quest;int credit;void validate(){resource(quest);if(credit<1||credit>10000)throw new IllegalArgumentException("invalid frozen deed");} }

    static final class HistoryRecord {
        UUID id,player;
        String organization,action;
        long gameTime,revision;
    }

    static final class MembershipRecord {
        UUID player;
        String organization;
        boolean active;
        boolean everJoined;
        long standing;
        int deedCount;
        long joinedAt = -1L;
        long leftAt = -1L;
        long revision;

        MembershipRecord() {
        }

        MembershipRecord(UUID player, ResourceLocation organization) {
            this.player = player;
            this.organization = organization.toString();
        }
    }

    static final class EvidenceRecord {
        UUID effectId;
        UUID sourceReceiptId;
        UUID player;
        String organization;
        String questId;
        int requestedCredit;
        int appliedCredit;
        UUID settlement;
        long gameTime;
        long revision;
        String fingerprint;

        EvidenceRecord() {
        }
    }

    private Records records = new Records();
    private CompoundTag retainedRaw;
    private String diagnostic = "";

    static OrganizationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                OrganizationSavedData::load, OrganizationSavedData::new, DATA_NAME);
    }

    static OrganizationSavedData load(CompoundTag tag) {
        OrganizationSavedData data = new OrganizationSavedData();
        try {
            if (!tag.contains("Schema", Tag.TAG_INT) || tag.getInt("Schema") != SCHEMA
                    || !tag.contains("Records", Tag.TAG_STRING)) {
                throw new IllegalArgumentException("unsupported organization schema/payload");
            }
            var object=com.google.gson.JsonParser.parseString(tag.getString("Records")).getAsJsonObject();
            boolean legacy=!object.has("lifecycles")&&!object.has("redirects")&&!object.has("merges");
            Records parsed = JSON.fromJson(object, Records.class);
            if (parsed == null) throw new IllegalArgumentException("missing organization records");
            if(legacy){parsed.lifecycles=new LinkedHashMap<>();parsed.redirects=new LinkedHashMap<>();parsed.merges=new LinkedHashMap<>();}
            parsed.validate();
            data.records = parsed;
        } catch (RuntimeException failure) {
            data.records = new Records();
            data.retainedRaw = tag.copy();
            data.diagnostic = "Organization data is read-only: " + failure.getMessage();
            LOGGER.error(data.diagnostic);
        }
        return data;
    }

    boolean writable() {
        return retainedRaw == null;
    }

    String diagnostic() {
        return diagnostic;
    }

    Records records() {
        return records;
    }

    Records transaction() {
        if (!writable()) throw new IllegalStateException(diagnostic);
        return JSON.fromJson(JSON.toJson(records), Records.class);
    }

    void commit(Records next) {
        next.validate();
        records = next;
        setDirty();
    }

    boolean commitDurably(Records next, File destination) {
        if (!writable()) return false;
        next.validate();
        try {
            DurableOrganizationSavedDataIO.write(destination, saveRecords(next));
            records = next;
            setDirty(false);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not durably commit organization data {}", destination, exception);
            return false;
        }
    }

    boolean flush(File destination) {
        if (!writable()) return false;
        if (!isDirty()) return true;
        try {
            DurableOrganizationSavedDataIO.write(destination, save(new CompoundTag()));
            setDirty(false);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not durably save organization data {}", destination, exception);
            setDirty(true);
            return false;
        }
    }

    @Override
    public boolean isDirty() {
        return writable() && super.isDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (!writable()) return retainedRaw.copy();
        return saveRecords(records, tag);
    }

    private static CompoundTag saveRecords(Records records) {
        return saveRecords(records, new CompoundTag());
    }

    private static CompoundTag saveRecords(Records records, CompoundTag tag) {
        tag.putInt("Schema", SCHEMA);
        tag.putString("Records", JSON.toJson(records));
        return tag;
    }

    static String key(UUID player, String organization) {
        return player + "|" + organization;
    }

    static String key(UUID player, ResourceLocation organization) {
        return key(player, organization.toString());
    }

    private static void resource(String value) {
        if (value == null || value.length() > 128 || ResourceLocation.tryParse(value) == null) {
            throw new IllegalArgumentException("malformed organization resource id");
        }
    }
    private static String resolve(Map<String,String> redirects,String source) {
        Set<String> seen=new HashSet<>();String value=source;
        while(redirects.containsKey(value)){if(!seen.add(value)||seen.size()>128)throw new IllegalArgumentException("organization redirect cycle");value=redirects.get(value);}
        return value;
    }
}
