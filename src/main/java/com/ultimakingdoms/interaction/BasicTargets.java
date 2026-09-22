package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.world.entity.*;
import java.util.*;
import static com.ultimakingdoms.interaction.ActionRegistry.*;

public final class BasicTargets {
    public static void init(){
        targets("kingdom",c->UltimaKingdomsApi.get(c.server()).getKingdoms().stream().map(k->new Choice(k.id().toString(),PlayerWords.text(k.translationKey()))).toList());
        targets("settlement",c->{var api=UltimaKingdomsApi.get(c.server());var known=SettlementKnowledge.get(c.server());var out=new ArrayList<Choice>();
            for(int offset=0;offset<=1_000_000;offset+=64){var page=known.page(c.player,api,Optional.empty(),offset,64);for(var s:page)out.add(new Choice(s.id().toString(),s.displayName(),s.dimension().location()+" · "+s.anchor().toShortString(),Map.of("revision",Long.toString(s.revision()),"kingdom",s.kingdomId().toString())));if(page.size()<64)break;}return out;});
        targets("player",c->c.server().getPlayerList().getPlayers().stream().map(p->new Choice(p.getUUID().toString(),p.getGameProfile().getName())).toList());
        targets("person",c->{var result=new ArrayList<>(c.choices("player"));result.addAll(c.choices("npc"));return result;});
        targets("npc",c->c.player.serverLevel().getEntities(c.player,c.player.getBoundingBox().inflate(32),e->e instanceof LivingEntity&&!(e instanceof net.minecraft.world.entity.player.Player)&&e.isAlive()&&c.player.hasLineOfSight(e)).stream().map(e->new Choice(e.getUUID().toString(),e.getDisplayName().getString(),"Nearby and visible")).toList());
        targets("organization",c->OrganizationApi.get(c.server()).definitions().stream().map(d->new Choice(d.id().toString(),PlayerWords.text(d.nameKey()))).toList());
    }
    public static Entity entity(Context c,String key){UUID id=c.uuid(key);var player=c.server().getPlayerList().getPlayer(id);if(player!=null)return player;var entity=c.player.serverLevel().getEntity(id);if(entity==null||entity.distanceToSqr(c.player)>32*32||!c.player.hasLineOfSight(entity))throw new IllegalArgumentException("That person is no longer nearby and visible.");return entity;}
    public static String settlement(Context c,UUID id){return UltimaKingdomsApi.get(c.server()).getSettlement(id).filter(s->SettlementKnowledge.get(c.server()).visible(c.player,id)).map(SettlementView::displayName).orElse("Unknown settlement");}
    public static String player(Context c,UUID id){var player=c.server().getPlayerList().getPlayer(id);return player==null?(c.server().getProfileCache()==null?java.util.Optional.<com.mojang.authlib.GameProfile>empty():c.server().getProfileCache().get(id)).map(com.mojang.authlib.GameProfile::getName).orElse("Player (offline)"):player.getGameProfile().getName();}
    private BasicTargets(){}
}
