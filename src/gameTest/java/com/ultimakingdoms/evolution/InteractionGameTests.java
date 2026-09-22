package com.ultimakingdoms.evolution;

import com.mojang.authlib.GameProfile;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.interaction.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.*;

import java.util.*;

import static com.ultimakingdoms.evolution.EvolutionState.*;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public final class InteractionGameTests {
    private static final UUID NONE=InteractionNetwork.NONE;

    @GameTest(template="empty",timeoutTicks=100)
    public static void registryAndCatalogueExposeNamedTasksWithoutEditableInternalIds(GameTestHelper helper){
        InteractionNetwork.init();var operator=player(helper,"RegistryOperator",true);var visitor=player(helper,"RegistryVisitor",false);
        Set<String> ids=new HashSet<>();for(var task:ActionRegistry.tasks(operator)){ids.add(task.id());for(var field:task.fields())if(field.kind()==ActionRegistry.Kind.TEXT||field.kind()==ActionRegistry.Kind.NUMBER){
            String words=(field.key()+" "+field.label()+" "+field.help()).toLowerCase(Locale.ROOT);
            check(!words.matches(".*(^|[^a-z])(uuid|revision|fingerprint)([^a-z]|$).*$"),"Editable internal field in "+task.id()+": "+field.key());
            check(!field.key().equals("id")&&!field.key().endsWith("_id"),"Editable id field in "+task.id());
        }}
        for(String expected:List.of("settlement.create","settlement.rename","politics.read_overview","civic.status","standing.diagnostics",
                "evolution.overview","protection.propose","transfer.propose","drama.propose","organization.found"))
            check(ids.contains(expected),"Registry omitted "+expected);
        check(ids.containsAll(Set.of("transfer.share_destination","transfer.revoke_destination")),"Explicit transfer destination consent tasks omitted");
        check(ActionRegistry.targetKinds().containsAll(Set.of("settlement","kingdom","player","person","npc","organization","scenario","pact","obligation","transfer","drama","merge","merge_obligation","native_recruit","native_group","own_native_group","shared_transfer_destination")),"Named target providers incomplete");

        var operatorList=list(operator,"Configure world evolution");var visitorList=list(visitor,"Configure world evolution");
        check(operatorList.options().stream().anyMatch(o->o.label().equals("Configure world evolution")),"Operator catalogue omitted administrative task");
        check(visitorList.options().isEmpty(),"Player catalogue exposed operator task");
        check(list(visitor,"known settlement").options().stream().anyMatch(o->o.label().equals("Read a known settlement")),"Player catalogue filter omitted read task");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void namedSettlementWizardRejectsSessionAttacksAndExecutesOnce(GameTestHelper helper){
        InteractionNetwork.init();var operator=player(helper,"WizardOperator",true);var stranger=player(helper,"WizardStranger",false);
        var radius=start(operator,"settlement.create");check(radius.mode().equals("text")&&radius.title().contains("Radius"),"Create wizard did not start at radius");
        check(call(stranger,radius,"NEXT","32").mode().equals("error"),"Cross-player session was accepted");
        check(request(operator,radius.session(),UUID.randomUUID(),"NEXT","32","",0).mode().equals("error"),"Stale state nonce was accepted");

        var name=call(operator,radius,"NEXT","32");check(name.mode().equals("text")&&name.title().contains("name"),"Create wizard did not request readable name");
        check(request(operator,name.session(),radius.state(),"NEXT","Replay","",0).mode().equals("error"),"Prior page nonce was replayed");
        var review=call(operator,name,"NEXT","Wizard Reach");check(review.mode().equals("review"),"Create wizard omitted review");
        check(!review.detail().toLowerCase(Locale.ROOT).matches(".*(uuid|fingerprint|revision [0-9]).*"),"Create review exposed editable implementation identifiers");

        var checked=request(operator,review.session(),review.state(),"CHECK","","",0);
        check(checked.mode().equals("review")&&checked.session().equals(review.session())&&checked.state().equals(review.state()),"CHECK did not retrieve the unchanged reviewed form");
        var applied=call(operator,review,"APPLY","");check(applied.mode().equals("result")&&applied.detail().contains("Registered Wizard Reach"),"Create action failed: "+applied.detail());
        var api=UltimaKingdomsApi.get(helper.getLevel().getServer());var settlement=api.getSettlementAt(helper.getLevel(),operator.blockPosition()).orElseThrow();
        check(settlement.displayName().equals("Wizard Reach"),"Wizard did not create named settlement");long revision=api.revision();
        var duplicate=request(operator,review.session(),review.state(),"APPLY","","",0);
        check(duplicate.mode().equals("result")&&duplicate.detail().equals(applied.detail()),"Duplicate APPLY did not return cached result");
        check(api.revision()==revision,"Duplicate APPLY repeated the mutation");

        SettlementKnowledge.get(helper.getLevel().getServer()).discover(operator.getUUID(),settlement.id());
        var choose=start(operator,"settlement.rename");var option=choose.options().stream().filter(o->o.label().equals("Wizard Reach")).findFirst().orElseThrow();
        var rename=call(operator,choose,"PICK",option.key());check(rename.mode().equals("text"),"Rename wizard omitted name field");
        var renameReview=call(operator,rename,"NEXT","Wizard Harbor");var renamed=call(operator,renameReview,"APPLY","");
        check(renamed.mode().equals("result")&&api.getSettlement(settlement.id()).orElseThrow().displayName().equals("Wizard Harbor"),"Named picker rename failed: "+renamed.detail());
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void permissionLossAndPrivateTargetsAreRechecked(GameTestHelper helper){
        InteractionNetwork.init();var mutable=new MutablePlayer(helper,"PermissionLoss",true);var form=start(mutable,"settlement.create");mutable.allowed=false;
        check(call(mutable,form,"NEXT","32").mode().equals("error"),"Permission loss did not invalidate operator form");

        var owner=player(helper,"PrivateOwner",false);var recipient=player(helper,"PrivateRecipient",false);var outsider=player(helper,"PrivateOutsider",false);
        var api=UltimaKingdomsApi.get(helper.getLevel().getServer());var settlement=api.registerCandidate(helper.getLevel(),SettlementCandidate.manual(
                helper.getLevel().dimension(),helper.absolutePos(new BlockPos(60004,2,60004)),4,new ResourceLocation("ultima_kingdoms","manual"),"interaction-private-"+UUID.randomUUID(),"Private Reach"));
        SettlementKnowledge.get(helper.getLevel().getServer()).adopt(helper.getLevel().getServer(),api);
        for(var p:List.of(owner,recipient,outsider))SettlementKnowledge.get(helper.getLevel().getServer()).discover(p.getUUID(),settlement.id());
        var saved=EvolutionSavedData.get(helper.getLevel().getServer());var state=saved.snapshot();long now=helper.getLevel().getGameTime();
        var terms=new Template(1,"Private audience",Trigger.FAMILY,List.of(Outcome.INTRODUCE,Outcome.DECLINE),72000,1200,1,false);UUID scenario=UUID.randomUUID();
        state.scenarios.put(scenario,new Scenario(scenario,"test:private",terms,settlement.id(),null,settlement.kingdomId().toString(),
                new Evidence("test","private-receipt","FAMILY",now,"Private relationship evidence"),owner.getUUID(),now,now+72000,1,Phase.OPEN,Map.of(),null,""));
        check(saved.commit(helper.getLevel().getServer(),state),"Private scenario fixture save failed");
        var transferData=RecruitTransferData.get(helper.getLevel().getServer());UUID transfer=UUID.randomUUID();
        check(transferData.put(helper.getLevel().getServer(),new RecruitTransferData.Transfer(transfer,UUID.randomUUID(),owner.getUUID(),recipient.getUUID(),UUID.randomUUID(),
                "0".repeat(64),false,now+1200,now+72000,1,RecruitTransferData.Phase.OFFERED,null,"Private bilateral proposal")),"Private transfer fixture save failed");

        var ownerContext=new ActionRegistry.Context(owner,Map.of(),Map.of());var recipientContext=new ActionRegistry.Context(recipient,Map.of(),Map.of());var outsiderContext=new ActionRegistry.Context(outsider,Map.of(),Map.of());
        check(ActionRegistry.choices("scenario",ownerContext).stream().anyMatch(c->c.value().equals(scenario.toString())),"Audience cannot select own private scenario");
        check(ActionRegistry.choices("scenario",outsiderContext).stream().noneMatch(c->c.value().equals(scenario.toString())),"Private scenario leaked to outsider");
        check(ActionRegistry.choices("transfer",ownerContext).stream().anyMatch(c->c.value().equals(transfer.toString())),"Owner cannot select private transfer");
        check(ActionRegistry.choices("transfer",recipientContext).stream().anyMatch(c->c.value().equals(transfer.toString())),"Recipient cannot select private transfer");
        check(ActionRegistry.choices("transfer",outsiderContext).stream().noneMatch(c->c.value().equals(transfer.toString())),"Private transfer leaked to outsider");
        var unsharedDestination=new ActionRegistry.Context(owner,Map.of("recipient",recipient.getUUID().toString()),Map.of());
        check(ActionRegistry.choices("native_group",unsharedDestination).isEmpty(),"Recipient native groups were enumerated without an explicit destination share");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void duplicateNamesStayStableAndRepliesRemainBounded(GameTestHelper helper){
        var first=new ActionRegistry.Choice("first","Harbor");var second=new ActionRegistry.Choice("second","Harbor");var third=new ActionRegistry.Choice("third","Harbor");
        var alias=NamedTargets.named(List.of(first,second,third)).get(1).label();
        check(NamedTargets.resolve(List.of(second,third),alias).value().equals("second"),"Removing another record retargeted a named command");
        var options=new ArrayList<InteractionNetwork.Option>();for(int i=0;i<20;i++)options.add(new InteractionNetwork.Option(""+i,"Terms", "<".repeat(1024),false));
        var reply=new InteractionNetwork.Reply(NONE,NONE,NONE,"choice","Terms","Choose",options,"",0,false,false,false);
        String encoded=InteractionNetwork.encodeReply(reply);check(encoded.length()<=60000&&encoded.contains("choice"),"Legal terms exceeded reply bound");
        var pathological=new InteractionNetwork.Reply(NONE,NONE,NONE,"review","Terms","\u0001".repeat(16000),List.of(),"",0,false,false,false);
        check(InteractionNetwork.encodeReply(pathological).length()<=60000,"Escaped provider text exceeded final envelope bound");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void nearbyPersonCanMoveWithoutInvalidatingNamedReview(GameTestHelper helper){
        InteractionNetwork.init();var operator=player(helper,"MovingPersonOperator",true);var pos=helper.absolutePos(new BlockPos(1,2,1));operator.setPos(pos.getX(),pos.getY(),pos.getZ());
        var villager=net.minecraft.world.entity.EntityType.VILLAGER.create(helper.getLevel());villager.setPos(pos.getX()+1,pos.getY(),pos.getZ());villager.setNoAi(true);villager.setCustomName(net.minecraft.network.chat.Component.literal("Moving Advisor"));helper.getLevel().addFreshEntity(villager);
        var form=start(operator,"citizen.inspect");var option=form.options().stream().filter(o->o.label().equals("Moving Advisor")).findFirst().orElseThrow();
        check(option.detail().contains("Current location"),"Nearby person picker omitted location context");
        var review=call(operator,form,"PICK",option.key());check(review.mode().equals("review"),"Named person selection did not reach review");
        villager.setPos(pos.getX()+2,pos.getY(),pos.getZ());var result=call(operator,review,"APPLY","");
        check(result.mode().equals("result")&&!result.detail().contains("Could not complete"),"Ordinary NPC movement invalidated named review: "+result.detail());villager.discard();helper.succeed();
    }

    private static InteractionNetwork.Reply start(FakePlayer player,String task){return request(player,NONE,NONE,"TASK",task,"",0);}
    private static InteractionNetwork.Reply list(FakePlayer player,String search){return request(player,NONE,NONE,"LIST","",search,0);}
    private static InteractionNetwork.Reply call(FakePlayer player,InteractionNetwork.Reply prior,String operation,String value){return request(player,prior.session(),prior.state(),operation,value,"",0);}
    private static InteractionNetwork.Reply request(FakePlayer player,UUID session,UUID state,String operation,String value,String search,int page){return InteractionNetwork.handle(player,new InteractionNetwork.Request(UUID.randomUUID(),session,state,operation,value,search,page));}
    private static FakePlayer player(GameTestHelper helper,String name,boolean operator){var player=new FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),name)){@Override public boolean hasPermissions(int level){return operator;}};var pos=helper.absolutePos(new BlockPos(50000,2,50000));player.setPos(pos.getX(),pos.getY(),pos.getZ());return player;}
    private static final class MutablePlayer extends FakePlayer {boolean allowed;MutablePlayer(GameTestHelper helper,String name,boolean allowed){super(helper.getLevel(),new GameProfile(UUID.randomUUID(),name));this.allowed=allowed;}@Override public boolean hasPermissions(int level){return allowed;}}
    private static void check(boolean value,String message){if(!value)throw new GameTestAssertException(message);}
    private InteractionGameTests(){}
}
