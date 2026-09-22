package com.ultimakingdoms.acceptance;

import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.factions.organization.OrganizationRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Test-only fault boundary: persist intent, remove policy, then recover from owner proof. */
final class CivicReceiptFaultFixture {
    @SuppressWarnings({"unchecked","rawtypes"})
    static void deliverAcrossPolicyRemoval(ServerPlayer player,UUID settlement)throws Exception {
        var consumer=new ResourceLocation("ultima_kingdoms:regional_civic_network");
        var receipts=(List<?>)NativeQuestScenario.invoke("api.McaQuestsApi","readCompletionReceipts",player,consumer,8);
        check(receipts.size()==1,"native receipt is exposed only after player-file verification");
        var receipt=receipts.get(0);UUID epoch=(UUID)NativeQuestScenario.call(receipt,"providerEpoch"),id=(UUID)NativeQuestScenario.call(receipt,"receiptId");
        String base="com.ultimakingdoms.compat.quests.receipts.QuestReceiptData";
        var effectType=Class.forName(base+"$Effect");var effectConstructor=effectType.getDeclaredConstructors()[0];effectConstructor.setAccessible(true);
        Object effect=effectConstructor.newInstance("ultima_kingdoms:lamplighters",10,true);
        var intentType=Class.forName(base+"$Intent");var intentConstructor=intentType.getDeclaredConstructors()[0];intentConstructor.setAccessible(true);
        Object intent=intentConstructor.newInstance(epoch,id,player.getUUID(),NativeQuestScenario.call(receipt,"questId").toString(),NativeQuestScenario.call(receipt,"giverId"),settlement,
                NativeQuestScenario.call(receipt,"acceptedDimension").toString(),((Optional<?>)NativeQuestScenario.call(receipt,"acceptedVillageId")).orElse(null),List.of(effect),false);
        var ledgerType=Class.forName(base);var get=ledgerType.getDeclaredMethod("get",net.minecraft.server.MinecraftServer.class);get.setAccessible(true);Object ledger=get.invoke(null,player.getServer());
        var put=ledgerType.getDeclaredMethod("put",net.minecraft.server.MinecraftServer.class,intentType);put.setAccessible(true);
        check((boolean)put.invoke(ledger,player.getServer(),intent),"recipient policy intent persisted before injected definition removal");
        var field=OrganizationRuntime.class.getDeclaredField("DEFINITIONS");field.setAccessible(true);Object definitions=field.get(null);
        var activeField=definitions.getClass().getDeclaredField("active");activeField.setAccessible(true);Object snapshot=((AtomicReference<?>)activeField.get(definitions)).get();
        var accessor=snapshot.getClass().getDeclaredMethod("definitions");accessor.setAccessible(true);Object old=accessor.invoke(snapshot);
        var pendingField=definitions.getClass().getDeclaredField("pending");pendingField.setAccessible(true);var pending=(AtomicReference)pendingField.get(definitions);
        pending.set(Map.of());OrganizationRuntime.commitPending();
        try {
            QuestCompletionBridge.consume(player);
            var member=OrganizationApi.get(player.getServer()).ownSnapshot(player).memberships().get(0);
            check(member.standing()==10&&!member.definitionAvailable(),"frozen native effect survives definition removal without reinterpretation");
            boolean rejected=false;
            try {OrganizationRuntime.applyCommittedQuestEffect(player.getServer(),UUID.randomUUID(),UUID.randomUUID(),player.getUUID(),new ResourceLocation("ultima_kingdoms:lamplighters"));}
            catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"fabricated committed-effect identity cannot bypass deed policy");
        } finally {pending.set(old);OrganizationRuntime.commitPending();}
    }
}
