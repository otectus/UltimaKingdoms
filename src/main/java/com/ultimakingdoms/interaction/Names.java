package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;
import java.util.UUID;

/** Player-facing names and times for service results, so replies name people and places instead of identifiers. */
public final class Names {
    public static String settlement(MinecraftServer server,UUID id){return id==null?"an unknown settlement":UltimaKingdomsApi.get(server).getSettlement(id).map(SettlementView::displayName).orElse("an unknown settlement");}
    /** The settlement's name only when the viewer has discovered it; otherwise a neutral placeholder. */
    public static String settlement(MinecraftServer server,ServerPlayer viewer,UUID id){return id!=null&&SettlementKnowledge.get(server).visible(viewer,id)?settlement(server,id):"an undiscovered settlement";}
    public static String kingdom(MinecraftServer server,String id){if(id==null||id.isBlank())return "no kingdom";return UltimaKingdomsApi.get(server).getKingdoms().stream().filter(k->k.id().toString().equals(id)).findFirst().map(k->PlayerWords.text(k.translationKey())).orElse(id);}
    public static String player(MinecraftServer server,UUID id){if(id==null)return "nobody";var player=server.getPlayerList().getPlayer(id);if(player!=null)return player.getGameProfile().getName();var cache=server.getProfileCache();return cache==null?"an offline player":cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse("an offline player");}
    public static String entity(MinecraftServer server,UUID id){if(id==null)return "an unknown unit";for(var level:server.getAllLevels()){var entity=level.getEntity(id);if(entity!=null)return entity.getName().getString();}return "an unloaded unit";}
    /** A player by name when known, otherwise a loaded entity's name. */
    public static String person(MinecraftServer server,UUID id){var player=server.getPlayerList().getPlayer(id);if(player!=null)return player.getGameProfile().getName();for(var level:server.getAllLevels()){var entity=level.getEntity(id);if(entity!=null)return entity.getName().getString();}return player(server,id);}
    public static String words(Object value){return value instanceof Enum<?> e?ActionRegistry.words(e.name()):value==null?"":ActionRegistry.words(String.valueOf(value));}
    /** Approximate wall-clock reading of a tick count ("about 2 hours"). */
    public static String duration(long ticks){
        long seconds=Math.max(0,ticks)/20;
        if(seconds<60)return "under a minute";
        long minutes=seconds/60;if(minutes<60)return "about "+minutes+(minutes==1?" minute":" minutes");
        long hours=minutes/60;if(hours<48)return "about "+hours+(hours==1?" hour":" hours");
        long days=hours/24;return "about "+days+" days";
    }
    /** Time left until an overworld game time, or "expired". */
    public static String remaining(MinecraftServer server,long at){long left=at-server.overworld().getGameTime();return left<=0?"expired":duration(left);}
    /** "day 12" reading of a game time. */
    public static String day(long gameTime){return "day "+(gameTime/24000+1);}
    public static String lower(String value){return value.toLowerCase(Locale.ROOT);}
    private Names(){}
}
