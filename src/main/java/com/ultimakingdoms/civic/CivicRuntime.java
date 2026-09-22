package com.ultimakingdoms.civic;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.civic.CivicNetworkApi;
import net.minecraft.server.MinecraftServer;
import java.util.*;

public final class CivicRuntime {
    private static final Map<MinecraftServer,CivicService> SERVICES=new WeakHashMap<>();
    private CivicRuntime() { }
    public static Registration attach(MinecraftServer server,KingdomsService kingdoms) {
        if(!server.isSameThread())throw new IllegalStateException("Civic runtime requires server thread");
        var service=new CivicService(server,kingdoms);
        if(SERVICES.putIfAbsent(server,service)!=null)throw new IllegalStateException("Civic runtime already attached");
        try {
            com.ultimakingdoms.api.civic.InstitutionalCommissionApi.attach(server,service);
            CivicNetworkApi.attach(server,service::speakerContext);
            CivicNetworkApi.attachActions(server,(player,speaker,introduction)->{
                var result=introduction?service.introduction(player,speaker):service.commissions(player,speaker);
                result.settlement().flatMap(kingdoms::getSettlement).ifPresent(s->player.sendSystemMessage(
                        net.minecraft.network.chat.Component.translatable("civic.introduction_destination",s.displayName(),s.anchor().toShortString())));
                return new com.ultimakingdoms.api.civic.CivicActionResult(result.success(),result.reason(),result.settlement());
            });
        } catch(RuntimeException failure) { CivicNetworkApi.detach(server);com.ultimakingdoms.api.civic.InstitutionalCommissionApi.detach(server);SERVICES.remove(server);throw failure; }
        return ()->{CivicNetworkApi.detach(server);com.ultimakingdoms.api.civic.InstitutionalCommissionApi.detach(server);SERVICES.remove(server);};
    }
    /** Internal replay, bound to a durable authored-contact decision rather than caller-supplied role data. */
    public static boolean applyCommittedContact(MinecraftServer server,UUID epoch,UUID receipt,UUID player,net.minecraft.resources.ResourceLocation organization) {
        var proof=com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.committedContact(server,epoch,receipt,player,organization)
                .orElseThrow(()->new IllegalArgumentException("No durable contact authorization"));
        return get(server).recordCommittedContact(proof.giver(),organization,proof.settlement(),proof.dimension(),proof.village(),proof.receipt());
    }
    public static CivicService get(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("Civic runtime requires server thread");
        var value=SERVICES.get(server);if(value==null)throw new IllegalStateException("Civic runtime unavailable");return value;
    }
}
