package com.ultimakingdoms.compat.quests.receipts;

import com.google.gson.Gson;
import com.ultimakingdoms.persistence.AtomicSavedDataWriter;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;

/** Freezes recipient policy before any effect, so a datapack reload cannot alter an in-flight receipt. */
final class QuestReceiptData extends SavedData {
    static final String NAME="ultima_kingdoms_quest_receipts";
    private static final Gson JSON=new Gson();
    record Effect(String organization,int credit,boolean authoredContact) { }
    record Intent(UUID epoch,UUID receipt,UUID player,String quest,UUID giver,UUID settlement,
                  String dimension,Integer village,List<Effect> effects,boolean delivered,String institutionalBinding) {
        Intent(UUID epoch,UUID receipt,UUID player,String quest,UUID giver,UUID settlement,String dimension,Integer village,List<Effect> effects,boolean delivered) {
            this(epoch,receipt,player,quest,giver,settlement,dimension,village,effects,delivered,"");
        }
        Intent { effects=List.copyOf(effects);institutionalBinding=institutionalBinding==null?"":institutionalBinding; }
        Intent completed(){return new Intent(epoch,receipt,player,quest,giver,settlement,dimension,village,effects,true,institutionalBinding);}
        String key(){return player+"|"+epoch+"|"+receipt;}
        UUID effectReceipt(){return UUID.nameUUIDFromBytes((epoch+"|"+receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        void validate(){
            if(!institutionalBinding.isEmpty()) {
                String token=institutionalBinding.startsWith("r3:")?institutionalBinding.substring(3):institutionalBinding;
                if(!UUID.fromString(token).toString().equals(token))throw new IllegalArgumentException("Noncanonical institutional binding");
            }
            Objects.requireNonNull(epoch);Objects.requireNonNull(receipt);Objects.requireNonNull(player);Objects.requireNonNull(giver);
            if(quest==null||quest.length()>128||ResourceLocation.tryParse(quest)==null||dimension==null||ResourceLocation.tryParse(dimension)==null||village!=null&&village<0||effects.size()>128)
                throw new IllegalArgumentException("Invalid completion intent");
            Set<String> organizations=new HashSet<>();for(var e:effects)if(e==null||e.organization==null||ResourceLocation.tryParse(e.organization)==null||e.credit<1||e.credit>10000||!organizations.add(e.organization))throw new IllegalArgumentException("Invalid completion effect");
        }
    }
    private Map<String,Intent> intents=new LinkedHashMap<>();
    private CompoundTag preserved;
    static QuestReceiptData get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(QuestReceiptData::load,QuestReceiptData::new,NAME);}
    static QuestReceiptData load(CompoundTag tag){
        var data=new QuestReceiptData();try {
            if(!tag.contains("Schema",Tag.TAG_INT)||tag.getInt("Schema")!=1||!tag.contains("Payload",Tag.TAG_STRING))throw new IllegalArgumentException("Receipt schema unavailable");
            Intent[] entries=JSON.fromJson(tag.getString("Payload"),Intent[].class);
            if(entries==null||entries.length>16384)throw new IllegalArgumentException("Receipt capacity exceeded");
            for(var intent:entries){intent.validate();if(data.intents.putIfAbsent(intent.key(),intent)!=null)throw new IllegalArgumentException("Duplicate receipt intent");}
        }catch(RuntimeException failure){data.intents.clear();data.preserved=tag.copy();com.mojang.logging.LogUtils.getLogger().error("Quest effect ledger is read-only; preserving payload",failure);}return data;
    }
    boolean writable(){return preserved==null;}
    Optional<Intent> get(String key){return Optional.ofNullable(intents.get(key));}
    Collection<Intent> forPlayer(UUID player){return intents.values().stream().filter(i->i.player().equals(player)).limit(8).toList();}
    boolean put(MinecraftServer server,Intent intent){intent.validate();if(!writable()||!intents.containsKey(intent.key())&&intents.size()>=16384)return false;var next=new LinkedHashMap<>(intents);next.put(intent.key(),intent);return commit(server,next);}
    boolean remove(MinecraftServer server,String key){var next=new LinkedHashMap<>(intents);next.remove(key);return commit(server,next);}
    private boolean commit(MinecraftServer server,Map<String,Intent> next){if(!writable())return false;try{AtomicSavedDataWriter.write(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME+".dat").toFile(),encode(next));intents=next;setDirty(false);return true;}catch(java.io.IOException failure){com.mojang.logging.LogUtils.getLogger().error("Quest effect intent could not be saved",failure);return false;}}
    private static CompoundTag encode(Map<String,Intent> intents){var tag=new CompoundTag();tag.putInt("Schema",1);tag.putString("Payload",JSON.toJson(intents.values()));return tag;}
    @Override public boolean isDirty(){return writable()&&super.isDirty();}
    @Override public CompoundTag save(CompoundTag tag){return writable()?encode(intents):preserved.copy();}
}
