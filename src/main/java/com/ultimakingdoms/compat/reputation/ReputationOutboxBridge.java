package com.ultimakingdoms.compat.reputation;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.*;
import com.ultimakingdoms.factions.FactionServiceImpl;
import com.ultimakingdoms.factions.config.FactionConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.lang.reflect.*;
import java.util.*;

/** Reflection adapter for MCA Reputation's optional durable source journal. */
final class ReputationOutboxBridge {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final ResourceLocation CONSUMER=new ResourceLocation("ultima_kingdoms","factions");
    private static final ResourceLocation SOURCE=new ResourceLocation("ultima_kingdoms","mca_reputation_sync");
    private ReputationOutboxBridge() {}

    static Registration attach(MinecraftServer server, KingdomsService kingdoms,
                               FactionServiceImpl factions, Class<?> apiClass) {
        try {
            Runtime runtime=new Runtime(server,kingdoms,factions,apiClass);
            if(!runtime.register())return ()->{};
            MinecraftForge.EVENT_BUS.register(runtime);factions.onDurableSave(runtime::ackDurableCursor);
            LOGGER.info("[Ultima Factions] durable MCA Reputation outbox enabled");
            return ()->{MinecraftForge.EVENT_BUS.unregister(runtime);runtime.close();};
        } catch(ReflectiveOperationException|LinkageError exception) {
            LOGGER.warn("[Ultima Factions] MCA Reputation lacks the durable standing outbox; live faction synchronization is unavailable",exception);
            return ()->{};
        }
    }

    private static final class Runtime {
        final MinecraftServer server;final KingdomsService kingdoms;final FactionServiceImpl factions;
        final Class<?> captureResult;final Method register,unregister,poll,ack,flush;final Object consumer;
        UUID epoch;long shadowAfter;FactionConfig.SyncMode lastMode;

        Runtime(MinecraftServer server,KingdomsService kingdoms,FactionServiceImpl factions,Class<?> api)throws ReflectiveOperationException{
            this.server=server;this.kingdoms=kingdoms;this.factions=factions;
            Class<?> consumerType=Class.forName("dev.otectus.mcareputation.api.StandingConsumer");
            captureResult=Class.forName("dev.otectus.mcareputation.api.CaptureResult");
            register=api.getMethod("registerStandingConsumer",MinecraftServer.class,consumerType);
            unregister=api.getMethod("unregisterStandingConsumer",MinecraftServer.class,ResourceLocation.class);
            poll=api.getMethod("pollStandingChanges",MinecraftServer.class,ResourceLocation.class,UUID.class,long.class,int.class);
            ack=api.getMethod("ackStandingChanges",MinecraftServer.class,ResourceLocation.class,UUID.class,long.class);
            flush=api.getMethod("flushStandingChanges",MinecraftServer.class);
            consumer=Proxy.newProxyInstance(consumerType.getClassLoader(),new Class<?>[]{consumerType},(proxy,method,args)->switch(method.getName()){
                case "id"->CONSUMER;case "capture"->capture(args[0]);case "toString"->"Ultima Factions standing consumer";
                case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];
                default->throw new UnsupportedOperationException(method.toString());});
        }

        boolean register(){boolean sourceRegistered=false;try{
            Object answer=register.invoke(null,server,consumer);
            if(!(answer instanceof Optional<?> optional)||optional.isEmpty())return false;
            sourceRegistered=true;
            if(!Boolean.TRUE.equals(flush.invoke(null,server))){
                LOGGER.error("Could not durably register the Ultima Factions reputation consumer; integration remains disabled");
                rollbackRegistration();return false;
            }
            Object value=optional.get();epoch=(UUID)call(value,"epoch");long start=number(call(value,"startAfter"));
            if (!factions.initializeSourceCursor(CONSUMER.toString(),epoch,start)) {
                LOGGER.error("Could not durably initialize the Ultima Factions reputation cursor; integration remains disabled");
                rollbackRegistration();return false;
            }
            Optional<FactionServiceImpl.SourceCursorState> durable=factions.durableSourceCursor(CONSUMER.toString());
            if(durable.isEmpty()||!durable.get().epoch().equals(epoch)){
                LOGGER.error("Durable reputation cursor does not match the registered source epoch; integration remains disabled");
                rollbackRegistration();return false;
            }
            shadowAfter=durable.get().through();lastMode=FactionConfig.SYNC_MODE.get();
            return true;
        }catch(ReflectiveOperationException exception){
            LOGGER.error("Could not register durable reputation consumer",exception);
            if(sourceRegistered)rollbackRegistration();return false;
        }}

        void rollbackRegistration(){try{
            unregister.invoke(null,server,CONSUMER);
            if(!Boolean.TRUE.equals(flush.invoke(null,server)))
                LOGGER.error("Could not durably roll back failed reputation consumer registration");
        }catch(ReflectiveOperationException exception){LOGGER.error("Could not roll back failed reputation consumer registration",exception);}
            epoch=null;}

        Object capture(Object envelope)throws ReflectiveOperationException{
            Map<String,String> payload=raw(envelope);String cause=String.valueOf(call(envelope,"cause"));
            ResourceLocation source=(ResourceLocation)call(envelope,"source");int delta=(int)number(call(envelope,"delta"));
            payload.put("mode",FactionConfig.SYNC_MODE.get().name());payload.put("policyVersion","1");
            if(source.equals(SOURCE))return result("ignored",payload,"reverse_projection");
            if(!eligible(cause))return result("ignored",payload,"cause_filtered");
            int projected=project(delta,FactionConfig.LOCAL_DELTA_CONTRIBUTION.get());payload.put("projectedDelta",Integer.toString(projected));
            if(projected==0)return result("ignored",payload,"zero_projection");
            Object community=call(envelope,"community");ResourceLocation dimension=(ResourceLocation)call(community,"dimension");int village=(int)number(call(community,"villageId"));
            Optional<SettlementView> settlement=kingdoms.getSettlementForMcaVillage(dimension,village);
            if(settlement.isEmpty())return result("unmapped",payload,"no_settlement_mapping");
            if(kingdoms.getKingdom(settlement.get().kingdomId()).filter(KingdomView::defined).isEmpty())return result("unmapped",payload,"missing_kingdom_definition");
            payload.put("settlementId",settlement.get().id().toString());payload.put("kingdomIdAtEvent",settlement.get().kingdomId().toString());
            return captureResult.getMethod("ready",Map.class).invoke(null,Map.copyOf(payload));
        }

        Object result(String factory,Map<String,String> payload,String reason)throws ReflectiveOperationException{
            payload.put("reason",reason);return captureResult.getMethod(factory,Map.class).invoke(null,Map.copyOf(payload));
        }

        @SubscribeEvent public void tick(TickEvent.ServerTickEvent event){
            if(event.phase!=TickEvent.Phase.END||event.getServer()!=server||epoch==null||FactionConfig.SYNC_MODE.get()==FactionConfig.SyncMode.OFF)return;
            try{if(Boolean.TRUE.equals(flush.invoke(null,server)))pollOnce();}
            catch(ReflectiveOperationException|RuntimeException exception){LOGGER.error("Durable reputation poll failed",exception);}
        }

        void pollOnce()throws ReflectiveOperationException{
            Optional<FactionServiceImpl.SourceCursorState> cursor=factions.durableSourceCursor(CONSUMER.toString());
            if(cursor.isEmpty())return;if(!cursor.get().epoch().equals(epoch)){LOGGER.error("Durable reputation epoch mismatch (GAP); operator migration required");return;}
            FactionConfig.SyncMode mode=FactionConfig.SYNC_MODE.get();boolean shadowMode=mode==FactionConfig.SyncMode.SHADOW;
            shadowAfter=shadowCursor(lastMode,mode,cursor.get().through(),shadowAfter);
            lastMode=mode;
            long after=shadowMode?shadowAfter:cursor.get().through();
            Object batch=poll.invoke(null,server,CONSUMER,epoch,after,64);String status=String.valueOf(call(batch,"status"));
            if(!"READY".equals(status)){if(!"UNREGISTERED".equals(status))LOGGER.error("Durable reputation outbox status {}",status);return;}
            Object raw=call(batch,"deliveries");if(!(raw instanceof List<?> deliveries))return;
            for(Object delivery:deliveries){if(!process(delivery))break;if(shadowMode)shadowAfter=number(call(call(delivery,"envelope"),"sequence"));}
            if(!deliveries.isEmpty())factions.flushDurable();
        }

        boolean process(Object delivery)throws ReflectiveOperationException{
            Object envelope=call(delivery,"envelope"),capture=call(delivery,"capture");String status=String.valueOf(call(capture,"status"));
            @SuppressWarnings("unchecked") Map<String,String> payload=new LinkedHashMap<>((Map<String,String>)call(capture,"payload"));payload.putAll(raw(envelope));
            UUID event=(UUID)call(envelope,"eventId"),eventEpoch=(UUID)call(envelope,"epoch"),player=(UUID)call(envelope,"player");long sequence=number(call(envelope,"sequence"));
            if(FactionConfig.SYNC_MODE.get()==FactionConfig.SyncMode.SHADOW){
                if("READY".equals(status)){ResourceLocation kingdom=ResourceLocation.tryParse(payload.get("kingdomIdAtEvent"));if(kingdom==null)return false;factions.recordShadow(event,player,kingdom,Integer.parseInt(payload.get("projectedDelta")),sequence,"predicted");return true;}
                if("IGNORED".equals(status))return factions.consumeIgnored(CONSUMER.toString(),eventEpoch,sequence,event,payload,false);
                return factions.takePendingCustody(CONSUMER.toString(),eventEpoch,sequence,event,status,payload,false);
            }
            if("FAILED".equals(status)){factions.takePendingCustody(CONSUMER.toString(),eventEpoch,sequence,event,status,payload,false);return false;}
            if("IGNORED".equals(status))return factions.consumeIgnored(CONSUMER.toString(),eventEpoch,sequence,event,payload,true);
            if("UNMAPPED".equals(status))return factions.takePendingCustody(CONSUMER.toString(),eventEpoch,sequence,event,status,payload,true);
            if(!"READY".equals(status))return false;
            ResourceLocation kingdom=ResourceLocation.tryParse(payload.get("kingdomIdAtEvent"));UUID settlement=parseUuid(payload.get("settlementId"));
            if(kingdom==null||settlement==null){factions.takePendingCustody(CONSUMER.toString(),eventEpoch,sequence,event,"FAILED",payload,false);return false;}
            factions.applyFromOutbox(new FactionStandingRequest(player,kingdom,Integer.parseInt(payload.get("projectedDelta")),SOURCE,
                    FactionChangeCause.LOCAL_REPUTATION,event,sequence,Optional.of(settlement),Optional.of("MCA local reputation contribution"),(Boolean)call(envelope,"quiet")),CONSUMER.toString(),eventEpoch,sequence);
            return true;
        }

        void ackDurableCursor(){try{Optional<FactionServiceImpl.SourceCursorState> cursor=factions.durableSourceCursor(CONSUMER.toString());if(cursor.isPresent())ack.invoke(null,server,CONSUMER,cursor.get().epoch(),cursor.get().through());}catch(ReflectiveOperationException exception){LOGGER.error("Could not acknowledge durable reputation cursor",exception);}}
        void close(){try{unregister.invoke(null,server,CONSUMER);}catch(ReflectiveOperationException exception){LOGGER.warn("Could not unregister durable reputation consumer",exception);}}

        Map<String,String> raw(Object envelope)throws ReflectiveOperationException{
            Map<String,String> p=new LinkedHashMap<>();Object c=call(envelope,"community");p.put("player",String.valueOf(call(envelope,"player")));p.put("dimension",String.valueOf(call(c,"dimension")));p.put("villageId",String.valueOf(call(c,"villageId")));p.put("communityRevision",String.valueOf(call(envelope,"communityRevision")));p.put("oldScore",String.valueOf(call(envelope,"oldScore")));p.put("newScore",String.valueOf(call(envelope,"newScore")));p.put("cause",String.valueOf(call(envelope,"cause")));p.put("source",String.valueOf(call(envelope,"source")));p.put("eventId",String.valueOf(call(envelope,"eventId")));return p;
        }
        boolean eligible(String cause){return switch(cause){case "DEED","RESOLUTION","SUPERSEDE"->true;case "DECAY"->FactionConfig.PROPAGATE_DECAY.get();case "ADMIN"->FactionConfig.PROPAGATE_ADMIN.get();default->false;};}
        static Object call(Object target,String name)throws ReflectiveOperationException{return target.getClass().getMethod(name).invoke(target);}
        static long number(Object value){return ((Number)value).longValue();}
        static UUID parseUuid(String value){try{return value==null?null:UUID.fromString(value);}catch(IllegalArgumentException ignored){return null;}}
    }

    static int project(int delta,double factor){if(delta==0||factor==0)return 0;long magnitude=(long)Math.ceil(Math.abs((double)delta)*factor);long signed=delta<0?-magnitude:magnitude;return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,signed));}
    static long shadowCursor(FactionConfig.SyncMode previous,FactionConfig.SyncMode current,
                             long durableThrough,long existingShadow){
        return current==FactionConfig.SyncMode.SHADOW&&previous!=FactionConfig.SyncMode.SHADOW
                ? durableThrough:existingShadow;
    }
}
