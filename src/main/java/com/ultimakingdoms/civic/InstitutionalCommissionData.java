package com.ultimakingdoms.civic;

import com.google.gson.Gson;
import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

/** Offers commit before display; accepted identities never recycle and cancellation releases ownership. */
final class InstitutionalCommissionData extends SavedData {
    static final String NAME="ultima_kingdoms_institutional_commissions";
    private static final Gson JSON=new Gson();
    record Contract(UUID id,UUID player,UUID giver,UUID institution,String organization,String kingdom,String quest,
                    long politicsRevision,long recognitionRevision,String buildingFingerprint,String legalFingerprint,
                    long offerExpires,UUID instance,String honor,Politics.Definition honorTerms) {
        Contract {
            Objects.requireNonNull(id);Objects.requireNonNull(player);Objects.requireNonNull(giver);Objects.requireNonNull(institution);
            for(String value:List.of(organization,kingdom,quest,honor))
                if(value.length()>128||net.minecraft.resources.ResourceLocation.tryParse(value)==null)throw new IllegalArgumentException("Invalid commission ID");
            if(politicsRevision<0||recognitionRevision<0||offerExpires<0||buildingFingerprint.length()>1024||legalFingerprint.length()>128
                    ||honorTerms==null||!honorTerms.kind().equals("honor"))throw new IllegalArgumentException("Invalid frozen commission");
        }
        Contract accepted(UUID value) { return new Contract(id,player,giver,institution,organization,kingdom,quest,politicsRevision,
                recognitionRevision,buildingFingerprint,legalFingerprint,offerExpires,value,honor,honorTerms); }
    }
    private Map<UUID,Contract> contracts=new LinkedHashMap<>();
    private CompoundTag preserved;
    static InstitutionalCommissionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(InstitutionalCommissionData::load,InstitutionalCommissionData::new,NAME);
    }
    static InstitutionalCommissionData load(CompoundTag tag) {
        var data=new InstitutionalCommissionData();
        try {
            if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1||!tag.contains("Contracts",Tag.TAG_STRING))throw new IllegalArgumentException("Unsupported commission schema");
            var values=JSON.fromJson(tag.getString("Contracts"),Contract[].class);
            if(values==null||values.length>16384)throw new IllegalArgumentException("Commission capacity");
            for(var value:values)if(value==null||data.contracts.putIfAbsent(value.id(),value)!=null)throw new IllegalArgumentException("Duplicate contract");
        } catch(RuntimeException failure) { data.contracts.clear();data.preserved=tag.copy(); }
        return data;
    }
    boolean writable() { return preserved==null; }
    Optional<Contract> get(UUID id) { return Optional.ofNullable(contracts.get(id)); }
    List<Contract> own(UUID player) { return contracts.values().stream().filter(c->c.player().equals(player)).toList(); }
    List<com.ultimakingdoms.api.factions.organization.OrganizationObligation> obligations(String organization) {
        if(!writable())return List.of();
        var id=net.minecraft.resources.ResourceLocation.tryParse(organization);if(id==null)return List.of();
        return contracts.values().stream().filter(c->c.instance()!=null&&c.organization().equals(organization))
                .sorted(Comparator.comparing(Contract::id)).map(c->new com.ultimakingdoms.api.factions.organization.OrganizationObligation(
                        c.id(),c.player(),c.instance(),id,new net.minecraft.resources.ResourceLocation(c.quest()))).toList();
    }
    boolean novate(MinecraftServer server,UUID id,String source,String target) {
        if(!writable()||source.equals(target)||net.minecraft.resources.ResourceLocation.tryParse(target)==null)return false;
        Contract current=contracts.get(id);if(current==null||current.instance()==null)return false;
        if(current.organization().equals(target))return true;
        if(!current.organization().equals(source))return false;
        Contract replacement=new Contract(current.id(),current.player(),current.giver(),current.institution(),target,current.kingdom(),current.quest(),
                current.politicsRevision(),current.recognitionRevision(),current.buildingFingerprint(),current.legalFingerprint(),current.offerExpires(),
                current.instance(),current.honor(),current.honorTerms());
        var next=new LinkedHashMap<>(contracts);next.put(id,replacement);return commit(server,next);
    }
    int reservedHonors(Set<UUID> awarded,long now) {
        if(!writable())return 8192; // Unknown promises cannot be discarded to make room for new awards.
        return (int)contracts.values().stream().filter(c->!awarded.contains(c.id())&&(c.instance()!=null||now<c.offerExpires())).count();
    }
    boolean remove(MinecraftServer server,UUID id) {
        if(!writable())return false;
        var next=new LinkedHashMap<>(contracts);next.remove(id);
        return commit(server,next);
    }
    boolean put(MinecraftServer server,Contract contract) {
        if(!writable())return false;
        var next=new LinkedHashMap<>(contracts);
        // Only never-accepted expired offers can retire. Accepted evidence remains until explicit cancellation.
        next.values().removeIf(c->c.instance()==null&&c.offerExpires()<server.overworld().getGameTime());
        if(!next.containsKey(contract.id())&&next.size()>=16384)return false;
        next.put(contract.id(),contract);
        return commit(server,next);
    }
    private boolean commit(MinecraftServer server,Map<UUID,Contract> next) {
        try {
            AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat").toFile(),encode(next));
            contracts=next;setDirty(false);return true;
        } catch(java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Institutional contract save failed",failure);return false; }
    }
    private static CompoundTag encode(Map<UUID,Contract> values) { var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Contracts",JSON.toJson(values.values()));return tag; }
    @Override public boolean isDirty() { return writable()&&super.isDirty(); }
    @Override public CompoundTag save(CompoundTag tag) { return writable()?encode(contracts):preserved.copy(); }
}
