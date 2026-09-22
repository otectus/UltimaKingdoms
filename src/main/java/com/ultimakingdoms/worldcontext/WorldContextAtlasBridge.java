package com.ultimakingdoms.worldcontext;

import com.ultimakingdoms.api.worldcontext.WorldContextApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.*;

/** Optional reflection-only publication into MCAQuests' existing atlas pipeline. */
final class WorldContextAtlasBridge {
    private static Method publish; private static boolean probed;
    private WorldContextAtlasBridge(){ }
    static void publish(ServerPlayer player,WorldContextService service){
        if(!ModList.get().isLoaded("mcaquests"))return;
        try{
            if(!probed){probed=true;Class<?> api=Class.forName("dev.otectus.mcaquests.api.McaQuestsApi",false,WorldContextAtlasBridge.class.getClassLoader());
                publish=api.getMethod("publishExternalMapPoints",ServerPlayer.class,String.class,List.class);}
            if(publish==null)return;List<Map<String,Object>> encoded=new ArrayList<>();
            for(WorldContextApi.MapPoint point:service.mapPoints(player,96)){
                Map<String,Object> value=new LinkedHashMap<>();value.put("key",point.key());value.put("dimension",point.dimension().toString());
                value.put("x",point.position().getX());value.put("y",point.position().getY());value.put("z",point.position().getZ());
                value.put("label",point.label());value.put("kind",point.kind());value.put("approximate",point.approximate());value.put("lastKnown",point.lastKnown());encoded.add(value);
            }
            publish.invoke(null,player,"ultima_kingdoms",encoded);
        }catch(ReflectiveOperationException|LinkageError failure){publish=null;
            com.mojang.logging.LogUtils.getLogger().debug("MCAQuests external atlas feed unavailable",failure);}
    }
}
