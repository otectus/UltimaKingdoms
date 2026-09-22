package com.ultimakingdoms.worldcontext;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.worldcontext.WorldContextApi;
import com.ultimakingdoms.warfare.WarfareConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber(modid=UltimaKingdomsApi.MOD_ID)
public final class WorldContextEvents {
    private static final Map<net.minecraft.server.MinecraftServer,WorldContextService> SERVICES=new WeakHashMap<>();
    private static final Map<UUID,DamageWindow> DAMAGE=new HashMap<>();
    private record Hit(int damage,long at) { }
    private static final class DamageWindow { final Map<UUID,Hit> players=new HashMap<>(); long touched; }
    private WorldContextEvents(){ }

    @SubscribeEvent public static void reload(AddReloadListenerEvent event){event.addListener(new WorldContextDefinitions());}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){
        if(!WarfareConfig.ENABLED.get()||!WarfareConfig.WORLD_CONTEXT.get())return;
        var service=new WorldContextService(event.getServer());SERVICES.put(event.getServer(),service);WorldContextApi.attach(event.getServer(),service);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){SERVICES.remove(event.getServer());WorldContextApi.detach(event.getServer());DAMAGE.clear();}

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event){
        if(!WarfareConfig.ENABLED.get()||!WarfareConfig.WORLD_CONTEXT.get()||event.phase!=TickEvent.Phase.END||!(event.player instanceof ServerPlayer player)||player.tickCount%20!=0)return;
        WorldContextService service=SERVICES.get(player.getServer());if(service==null)return;
        service.discoverAuthoredNearby(player);service.progress(player);
        if(player.tickCount%40==0)discoverStructures(player,service);
        if(player.tickCount%100==0)WorldContextAtlasBridge.publish(player,service);
        if(player.tickCount%200==0){long now=player.level().getGameTime();DAMAGE.entrySet().removeIf(e->now-e.getValue().touched>12000||DAMAGE.size()>4096);}
    }

    private static void discoverStructures(ServerPlayer player,WorldContextService service){
        var definitions=WorldContextDefinitions.get().structures();if(definitions.isEmpty())return;
        var registry=player.serverLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var chunk=player.serverLevel().getChunkAt(player.blockPosition());
        for(var entry:chunk.getAllStarts().entrySet()){
            ResourceLocation id=registry.getKey(entry.getKey());String role=definitions.get(id);var start=entry.getValue();
            if(role==null||start==null||!start.isValid())continue;var box=start.getBoundingBox();BlockPos playerPos=player.blockPosition();
            int dx=playerPos.getX()<box.minX()?box.minX()-playerPos.getX():Math.max(0,playerPos.getX()-box.maxX());
            int dz=playerPos.getZ()<box.minZ()?box.minZ()-playerPos.getZ():Math.max(0,playerPos.getZ()-box.maxZ());
            if(dx>48||dz>48)continue;service.observeStructure(player,id,new BlockPos((box.minX()+box.maxX())/2,(box.minY()+box.maxY())/2,(box.minZ()+box.maxZ())/2),role);
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public static void hurt(LivingHurtEvent event){
        if(!WarfareConfig.ENABLED.get()||!WarfareConfig.WORLD_CONTEXT.get()||event.isCanceled()||event.getAmount()<=0||event.getEntity().level().isClientSide())return;
        Entity attacker=event.getSource().getEntity();if(!(attacker instanceof ServerPlayer player))return;
        ResourceLocation type=BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());var rule=WorldContextDefinitions.get().encounters().get(type);if(rule==null)return;
        if(DAMAGE.size()>=4096&&!DAMAGE.containsKey(event.getEntity().getUUID()))return;
        long now=event.getEntity().level().getGameTime();DamageWindow window=DAMAGE.computeIfAbsent(event.getEntity().getUUID(),ignored->new DamageWindow());window.touched=now;
        if(window.players.size()>=64&&!window.players.containsKey(player.getUUID()))return;
        Hit old=window.players.get(player.getUUID());int amount=Math.max(1,(int)Math.ceil(event.getAmount()));window.players.put(player.getUUID(),new Hit((int)Math.min(Integer.MAX_VALUE,(long)(old==null?0:old.damage())+amount),now));
    }

    @SubscribeEvent(priority=EventPriority.LOWEST) public static void death(LivingDeathEvent event){
        if(!WarfareConfig.ENABLED.get()||!WarfareConfig.WORLD_CONTEXT.get()||event.isCanceled()||event.getEntity().level().isClientSide()||!(event.getEntity() instanceof Mob mob))return;
        ResourceLocation type=BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());var rule=WorldContextDefinitions.get().encounters().get(type);if(rule==null)return;
        if(excluded(mob))return;DamageWindow window=DAMAGE.remove(mob.getUUID());if(window==null)return;long now=mob.level().getGameTime();
        Map<ServerPlayer,Integer> contributors=new HashMap<>();for(var hit:window.players.entrySet())if(now-hit.getValue().at()<=rule.contributionWindow()){
            ServerPlayer player=mob.getServer().getPlayerList().getPlayer(hit.getKey());if(player!=null)contributors.put(player,hit.getValue().damage());}
        WorldContextService service=SERVICES.get(mob.getServer());if(service!=null)service.recordEncounter(mob,contributors,rule);
    }
    static boolean excluded(Mob mob){
        if(mob instanceof TamableAnimal tame&&tame.isTame())return true;
        if(mob instanceof OwnableEntity ownable&&ownable.getOwnerUUID()!=null)return true;
        MobSpawnType type=mob.getSpawnType();return type==null||switch(type){
            case NATURAL,CHUNK_GENERATION,STRUCTURE,PATROL,EVENT -> false;
            default -> true;
        };
    }
}
