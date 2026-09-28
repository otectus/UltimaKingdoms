package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Chat notices to the other parties of a proposal, so a counterpart learns that something awaits them without polling menus. */
public final class Notify {
    private static Component line(String text){return ActionRegistry.tr("notice.ultima_kingdoms.party","[Kingdoms] %s Open Kingdom Tasks (%s) to respond.",text,Component.keybind("key.ultima_kingdoms.actions"));}
    /** Tells one connected player; offline players learn from the next digest or menu. */
    public static void player(MinecraftServer server,UUID id,UUID except,String text){
        if(id==null||id.equals(except))return;var player=server.getPlayerList().getPlayer(id);if(player!=null)player.sendSystemMessage(line(text));
    }
    public static void players(MinecraftServer server,Collection<UUID> ids,UUID except,String text){
        if(ids==null)return;var seen=new HashSet<UUID>();for(UUID id:ids)if(seen.add(id))player(server,id,except,text);
    }
    /** Tells the office and mandate holders of a kingdom's government. */
    public static void government(MinecraftServer server,String kingdom,UUID except,String text){
        if(kingdom==null||kingdom.isBlank())return;
        try{var government=UltimaPoliticsApi.get(server).government(kingdom).orElse(null);if(government==null)return;
            var ids=new LinkedHashSet<UUID>(government.mandates().keySet());
            government.offices().values().stream().map(Politics.Office::holder).filter(p->p.kind()==Politics.Kind.PLAYER).map(Politics.Person::id).forEach(ids::add);
            players(server,ids,except,text);
        }catch(RuntimeException ignored){}
    }
    private Notify(){}
}
