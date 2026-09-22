package com.ultimakingdoms.acceptance;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.evolution.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;
import static com.ultimakingdoms.acceptance.IntegrationScenario.*;

@Mod.EventBusSubscriber(modid="ultima_kingdoms_acceptance")
public final class R4MultiplayerFixture {
    private static UUID scenario;
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        if(!Boolean.getBoolean("ultima.acceptance.r4Multiplayer"))return;
        event.getDispatcher().register(Commands.literal("r4-fixture").requires(s->s.hasPermission(4))
            .then(Commands.literal("setup").executes(c->{try{
                var server=c.getSource().getServer();var level=server.overworld();
                var one=Objects.requireNonNull(server.getPlayerList().getPlayerByName("PoliticalLeader"));
                var two=Objects.requireNonNull(server.getPlayerList().getPlayerByName("PoliticalOther"));
                server.getPlayerList().op(one.getGameProfile());
                for(var p:List.of(one,two)){p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.teleportTo(level,1,64,1,0,0);}
                String kingdom="ultima_kingdoms:serenum";
                var seat=MilitaryScenario.settlement(UltimaKingdomsApi.get(server),level,new BlockPos(0,64,0),new ResourceLocation(kingdom),"Multiplayer Assembly");
                var politics=UltimaPoliticsApi.get(server);
                for(var p:List.of(one,two))SettlementKnowledge.get(server).discover(p.getUUID(),seat.id());
                success(politics.execute(one,new Politics.Request(UUID.randomUUID(),politics.revision(),Politics.Action.BOOTSTRAP,kingdom,kingdom+"_charter",seat.id().toString(),new Politics.Person(one.getUUID(),Politics.Kind.PLAYER),"","","",0,64,0)));
                var other=MilitaryScenario.settlement(UltimaKingdomsApi.get(server),level,new BlockPos(160,64,0),new ResourceLocation("ultima_kingdoms:lunari"),"Neighbor Assembly");
                success(politics.execute(one,new Politics.Request(UUID.randomUUID(),politics.revision(),Politics.Action.BOOTSTRAP,"ultima_kingdoms:lunari","ultima_kingdoms:lunari_charter",other.id().toString(),new Politics.Person(one.getUUID(),Politics.Kind.PLAYER),"","","",0,64,0)));
                success(politics.execute(one,new Politics.Request(UUID.randomUUID(),politics.revision(),Politics.Action.NAME_SUCCESSOR,kingdom,"","",new Politics.Person(two.getUUID(),Politics.Kind.PLAYER),"","","",0,0,0)));
                UUID petition=UUID.randomUUID();
                success(politics.execute(one,new Politics.Request(petition,politics.revision(),Politics.Action.PETITION,kingdom,"ultima_kingdoms:introduction_petition",seat.id().toString(),null,"ultima_kingdoms:lunari","Private R4 concern","",0,0,0)));
                success(politics.execute(one,new Politics.Request(UUID.randomUUID(),politics.revision(),Politics.Action.ABDICATE,kingdom,"","",null,"","","",0,0,0)));
                var evolution=EvolutionRuntime.get(server);evolution.configure(one,true,false);evolution.region(one,seat.id(),true);evolution.tick();
                String row=evolution.page(two,0).stream().filter(s->s.contains("named successor")).findFirst().orElseThrow();scenario=UUID.fromString(row.substring(0,36));
                evolution.contribute(two,scenario,1,EvolutionState.Outcome.SUCCEED);
                // The former ruler should no longer receive operator privacy privileges during the race.
                server.getPlayerList().deop(one.getGameProfile());
                Files.writeString(Path.of("../SCENARIO"),scenario.toString());Files.writeString(Path.of("../PETITION"),petition.toString());
                System.out.println("R4_MULTIPLAYER_READY");return 1;
            }catch(Exception failure){throw new RuntimeException(failure);}}))
            .then(Commands.literal("audit").executes(c->{
                var server=c.getSource().getServer();var two=server.getPlayerList().getPlayerByName("PoliticalOther");
                var lines=EvolutionRuntime.get(server).inspect(two,scenario);String first=lines.get(0);
                check(first.contains("RESOLVED")||first.contains("DECLINED"),"one race outcome committed");
                var government=UltimaPoliticsApi.get(server).government("ultima_kingdoms:serenum").orElseThrow();
                check(first.contains("RESOLVED")?government.offices().get("ultima_kingdoms:leader").holder().id().equals(two.getUUID()):government.state()==Politics.State.INTERREGNUM,"government agrees with committed race result");
                check(first.contains("revision 4"),"losing command cannot commit a second terminal outcome");
                System.out.println("R4_MULTIPLAYER_AUDIT_PASS");return 1;
            })));
    }
    private static void success(Politics.Result result){check(result.success(),result.message());}
}
