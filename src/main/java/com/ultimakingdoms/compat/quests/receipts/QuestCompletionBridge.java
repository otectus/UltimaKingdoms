package com.ultimakingdoms.compat.quests.receipts;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.civic.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Optional public API binding. No completed-event inference and no provider state writes except public acknowledgment. */
public final class QuestCompletionBridge {
    private static final ResourceLocation CONSUMER=new ResourceLocation("ultima_kingdoms:regional_civic_network");
    private static final Map<UUID,String> HEALTH=new HashMap<>();
    private static final Set<UUID> FAILURES=new HashSet<>();
    private static final Set<UUID> WAKE=new LinkedHashSet<>();
    private static Binding binding;
    private static String capability="not_probed";
    private static int cursor;
    private record Binding(Method read,Method ack,Method status,Method commission) { }
    private QuestCompletionBridge(){ }
    @SuppressWarnings("unchecked") public static void register() {
        MinecraftForge.EVENT_BUS.register(QuestCompletionBridge.class);
        if(!ModList.get().isLoaded("mcaquests")){capability="absent";return;}
        try {
            Class<?> api=Class.forName("dev.otectus.mcaquests.api.McaQuestsApi");
            binding=new Binding(api.getMethod("readCompletionReceipts",ServerPlayer.class,ResourceLocation.class,int.class),
                    api.getMethod("acknowledgeCompletionReceipt",ServerPlayer.class,ResourceLocation.class,UUID.class,UUID.class),
                    api.getMethod("completionReceiptStatus",ServerPlayer.class),api.getMethod("openCommissionMenu",ServerPlayer.class,Entity.class,Set.class));
            Class<? extends Event> event=(Class<? extends Event>)Class.forName("dev.otectus.mcaquests.api.event.QuestCompletionReceiptReadyEvent");
            Method player=event.getMethod("getPlayer");
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL,false,(Class<Event>)event,e->{try{ServerPlayer p=(ServerPlayer)player.invoke(e);if(WAKE.size()<4096)WAKE.add(p.getUUID());}catch(ReflectiveOperationException ignored){}});
            capability="available";
        }catch(ReflectiveOperationException|LinkageError failure){binding=null;capability="unsupported_completion_api";com.mojang.logging.LogUtils.getLogger().warn("Guild completion credit unavailable: provider completion API missing",failure);}
    }
    public static String status(ServerPlayer player){if(!CivicConfig.ENABLED.get())return "disabled";return HEALTH.getOrDefault(player.getUUID(),capability);}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player&&WAKE.size()<4096)WAKE.add(player.getUUID());}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){HEALTH.remove(event.getEntity().getUUID());FAILURES.remove(event.getEntity().getUUID());WAKE.remove(event.getEntity().getUUID());}
    @SubscribeEvent public static void stop(ServerStoppedEvent event){HEALTH.clear();FAILURES.clear();WAKE.clear();cursor=0;}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||binding==null||!CivicConfig.ENABLED.get())return;
        var players=event.getServer().getPlayerList().getPlayers();if(players.isEmpty())return;
        Set<ServerPlayer> work=new LinkedHashSet<>();
        for(var id:new ArrayList<>(WAKE)){if(work.size()>=4)break;WAKE.remove(id);var player=event.getServer().getPlayerList().getPlayer(id);if(player!=null)work.add(player);}
        if(event.getServer().getTickCount()%20==0)for(int i=0;i<Math.min(32,players.size());i++)work.add(players.get(Math.floorMod(cursor++,players.size())));
        work.forEach(QuestCompletionBridge::consume);
    }
    public static void consume(ServerPlayer player){
        if(!player.getServer().isSameThread())throw new IllegalStateException("Receipt consumer requires server thread");
        if(binding==null||!CivicConfig.ENABLED.get())return;
        var ledger=QuestReceiptData.get(player.getServer());if(!ledger.writable()){HEALTH.put(player.getUUID(),"effect_ledger_read_only");return;}
        try {
            HEALTH.put(player.getUUID(),String.valueOf(binding.status.invoke(null,player)));
            for(var intent:ledger.forPlayer(player.getUUID()))if(intent.delivered()&&civilianCompletionCommitted(player,intent)
                    &&(boolean)binding.ack.invoke(null,player,CONSUMER,intent.epoch(),intent.receipt()))ledger.remove(player.getServer(),intent.key());
            var receipts=(List<?>)binding.read.invoke(null,player,CONSUMER,8);
            for(Object receipt:receipts) {
                UUID owner=(UUID)value(receipt,"playerId");if(!owner.equals(player.getUUID())||!value(receipt,"outcome").toString().equals("COMPLETED"))throw new IllegalStateException("Provider returned wrong subject/outcome");
                UUID epoch=(UUID)value(receipt,"providerEpoch"),id=(UUID)value(receipt,"receiptId");String key=owner+"|"+epoch+"|"+id;
                QuestReceiptData.Intent intent=ledger.get(key).orElse(null);
                if(intent==null){
                    ResourceLocation quest=(ResourceLocation)value(receipt,"questId");List<QuestReceiptData.Effect> effects=new ArrayList<>();
                    var service=OrganizationApi.get(player.getServer());
                    for(var definition:service.definitions())for(var deed:definition.deeds())if(deed.questId().equals(quest))effects.add(new QuestReceiptData.Effect(definition.id().toString(),deed.credit(),CivicDefinitions.INSTANCE.get(definition.id()).map(r->r.sponsorQuests().contains(quest)).orElse(false)));
                    ResourceLocation dimension=(ResourceLocation)value(receipt,"acceptedDimension");Integer village=(Integer)((Optional<?>)value(receipt,"acceptedVillageId")).orElse(null);
                    UUID settlement=null;Optional<?> frozen=(Optional<?>)value(receipt,"kingdomBinding");
                    if(frozen.isPresent())settlement=(UUID)value(frozen.get(),"settlementId");
                    if(settlement==null&&village!=null)settlement=UltimaKingdomsApi.get(player.getServer()).getSettlementForMcaVillage(dimension,village).map(s->s.id()).orElse(null);
                    String institutional="";
                    try { institutional=(String)value(receipt,"institutionalBinding"); } catch(NoSuchMethodException legacy) { }
                    intent=new QuestReceiptData.Intent(epoch,id,owner,quest.toString(),(UUID)value(receipt,"giverId"),settlement,dimension.toString(),village,effects,false,institutional);
                    if(!ledger.put(player.getServer(),intent)){HEALTH.put(owner,"intent_save_failed_or_full");continue;}
                }
                if(!deliver(player,intent)){continue;}
                if(!intent.delivered()&&!ledger.put(player.getServer(),intent.completed())){HEALTH.put(owner,"delivery_marker_save_failed");continue;}
                if((boolean)binding.ack.invoke(null,player,CONSUMER,epoch,id))ledger.remove(player.getServer(),key);
                else HEALTH.put(owner,"provider_ack_pending");
            }
            FAILURES.remove(player.getUUID());
        }catch(ReflectiveOperationException|LinkageError|RuntimeException failure){HEALTH.put(player.getUUID(),"receipt_delivery_failed");if(FAILURES.add(player.getUUID()))com.mojang.logging.LogUtils.getLogger().error("Civic receipt delivery will retry for {}",player.getUUID(),failure);}
    }
    private static boolean deliver(ServerPlayer player,QuestReceiptData.Intent intent){
        if(!intent.institutionalBinding().isEmpty()) {
            if(com.ultimakingdoms.warfare.contracts.CivilianContractService.handles(intent.institutionalBinding())) {
                if(!civilianCompletionCommitted(player,intent))return false;
            } else if(!intent.delivered()) {
                var politics=com.ultimakingdoms.api.politics.UltimaPoliticsApi.get(player.getServer());
                if(!(politics instanceof com.ultimakingdoms.politics.GovernmentService government)
                        ||!government.recordCommissionHonor(player,intent.epoch(),intent.receipt())) {
                    HEALTH.put(player.getUUID(),"institutional_honor_pending");return false;
                }
            }
        }
        if(intent.delivered())return true;
        var service=OrganizationApi.get(player.getServer());
        for(var effect:intent.effects()) {
            ResourceLocation organization=new ResourceLocation(effect.organization());
            var result=com.ultimakingdoms.factions.organization.OrganizationRuntime.applyCommittedQuestEffect(player.getServer(),intent.epoch(),intent.receipt(),player.getUUID(),organization);
            if(!(result.applied()||result.status()==OrganizationDeedResult.Status.REPLAYED||result.reason().equals("organization.quest_already_credited"))){HEALTH.put(player.getUUID(),result.reason());return false;}
            UUID home=intent.settlement();
            if(home==null&&intent.village()!=null)home=UltimaKingdomsApi.get(player.getServer()).getSettlementForMcaVillage(new ResourceLocation(intent.dimension()),intent.village()).map(s->s.id()).orElse(null);
            boolean sponsor=effect.authoredContact();
            if(sponsor&&!CivicRuntime.applyCommittedContact(player.getServer(),intent.epoch(),intent.receipt(),player.getUUID(),organization)){HEALTH.put(player.getUUID(),"contact_save_pending");return false;}
            if(result.applied())player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("civic.service_recorded",effect.credit(),net.minecraft.network.chat.Component.translatable(service.definition(organization).map(OrganizationDefinitionView::nameKey).orElse(organization.toString()))));
        }
        return true;
    }
    /** Provider acknowledgment is unreachable until the R3 owner proof is durably applied or replayed. */
    private static boolean civilianCompletionCommitted(ServerPlayer player,QuestReceiptData.Intent intent) {
        if(!com.ultimakingdoms.warfare.contracts.CivilianContractService.handles(intent.institutionalBinding()))return true;
        var result=com.ultimakingdoms.warfare.contracts.CivilianContractService.recordCompletion(
                player.getServer(),intent.epoch(),intent.receipt(),player.getUUID());
        if(result==com.ultimakingdoms.warfare.contracts.CivilianContractService.CompletionResult.APPLIED
                ||result==com.ultimakingdoms.warfare.contracts.CivilianContractService.CompletionResult.REPLAYED)return true;
        HEALTH.put(player.getUUID(),result==com.ultimakingdoms.warfare.contracts.CivilianContractService.CompletionResult.SAVE_FAILED
                ?"civilian_service_save_pending":"civilian_service_receipt_invalid");return false;
    }
    public record CommittedContact(UUID giver,UUID receipt,UUID settlement,String dimension,Integer village) { }
    public static Optional<CommittedContact> committedContact(net.minecraft.server.MinecraftServer server,UUID epoch,UUID receipt,UUID player,ResourceLocation organization) {
        if(!server.isSameThread())throw new IllegalStateException("Intent reads require server thread");
        var ledger=QuestReceiptData.get(server);if(!ledger.writable())return Optional.empty();
        return ledger.get(player+"|"+epoch+"|"+receipt).filter(intent->intent.effects().stream()
                .anyMatch(effect->effect.organization().equals(organization.toString())&&effect.authoredContact()))
                .map(intent->new CommittedContact(intent.giver(),intent.effectReceipt(),intent.settlement(),intent.dimension(),intent.village()));
    }
    public record CommittedEffect(UUID effectReceipt,String quest,int credit,Optional<UUID> settlement) { }
    /** Internal proof lookup, never a client-controlled award definition. */
    public static Optional<CommittedEffect> committedEffect(net.minecraft.server.MinecraftServer server,UUID epoch,UUID receipt,UUID player,ResourceLocation organization) {
        if(!server.isSameThread())throw new IllegalStateException("Intent reads require server thread");
        var ledger=QuestReceiptData.get(server);if(!ledger.writable())return Optional.empty();
        return ledger.get(player+"|"+epoch+"|"+receipt).flatMap(intent->intent.effects().stream()
                .filter(effect->effect.organization().equals(organization.toString())).findFirst()
                .map(effect->new CommittedEffect(intent.effectReceipt(),intent.quest(),effect.credit(),Optional.ofNullable(intent.settlement()))));
    }
    public static boolean openCommissions(ServerPlayer player,Entity giver,Set<ResourceLocation> quests){
        if(binding==null||quests.isEmpty())return false;
        try{return (boolean)binding.commission.invoke(null,player,giver,quests);}catch(ReflectiveOperationException|RuntimeException failure){HEALTH.put(player.getUUID(),"commission_provider_failed");return false;}
    }
    public static boolean openInstitutionalCommissions(ServerPlayer player,Entity giver,Set<ResourceLocation> quests,String token) {
        if(!ensureInstitutionalRecipient(player))return false;
        try {
            return (boolean)Class.forName("dev.otectus.mcaquests.api.McaQuestsApi").getMethod("openInstitutionalCommissionMenu",
                    ServerPlayer.class,Entity.class,Set.class,String.class).invoke(null,player,giver,quests,token);
        } catch(ReflectiveOperationException|LinkageError|RuntimeException failure) { HEALTH.put(player.getUUID(),"institutional_provider_unavailable");return false; }
    }
    /** Synchronous commit-time renewal, including completion immediately after login/restart. */
    public static boolean ensureInstitutionalRecipient(ServerPlayer player) {
        if(!player.getServer().isSameThread())throw new IllegalStateException("Receipt subscription requires server thread");
        if(binding==null||!CivicConfig.ENABLED.get())return false;
        try {
            // Unlike receipt polling this capability never saves playerdata during a provider transaction.
            return (boolean)Class.forName("dev.otectus.mcaquests.api.McaQuestsApi")
                    .getMethod("renewInstitutionalCompletionConsumer",ServerPlayer.class).invoke(null,player);
        } catch(ReflectiveOperationException|LinkageError|RuntimeException failure) { return false; }
    }
    public record InstitutionalEffect(String binding,String quest,UUID giver) { }
    public static Optional<InstitutionalEffect> committedInstitutionalEffect(net.minecraft.server.MinecraftServer server,UUID epoch,UUID receipt,UUID player) {
        if(!server.isSameThread())throw new IllegalStateException("Intent reads require server thread");
        var ledger=QuestReceiptData.get(server);if(!ledger.writable())return Optional.empty();
        return ledger.get(player+"|"+epoch+"|"+receipt).filter(i->!i.institutionalBinding().isEmpty())
                .map(i->new InstitutionalEffect(i.institutionalBinding(),i.quest(),i.giver()));
    }
    private static Object value(Object record,String accessor)throws ReflectiveOperationException{return record.getClass().getMethod(accessor).invoke(record);}
}
