package com.ultimakingdoms.test;

import com.ultimakingdoms.UltimaKingdoms;
import com.ultimakingdoms.api.*;
import com.ultimakingdoms.core.KingdomsServiceImpl;
import com.ultimakingdoms.citizen.CivicIdentityStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.*;
import java.util.stream.Stream;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class KingdomGameTests {
    private static ResourceLocation id(String s) { return new ResourceLocation("ultima_kingdoms",s); }
    private static KingdomsService service(GameTestHelper h) { return UltimaKingdomsApi.get(h.getLevel().getServer()); }
    private static SettlementCandidate candidate(GameTestHelper h, String key, int dx) {
        var pos=h.absolutePos(new BlockPos(dx,2,2));
        return SettlementCandidate.structure(h.getLevel().dimension(),pos,4,id("test"),key+pos,null);
    }
    private static void check(boolean ok,String message) { if(!ok)throw new net.minecraft.gametest.framework.GameTestAssertException(message); }

    @GameTest(template="empty")
    public static void fiveDefaultClassifications(GameTestHelper h) {
        String[][] cases={{"plains","serenum"},{"snowy_plains","lunari"},{"savanna","madera"},{"desert","anemosia"},{"taiga","yew"}};
        var level=h.getLevel(); var server=level.getServer();
        for(int i=0;i<cases.length;i++) {
            var pos=h.absolutePos(new BlockPos(i*16,2,0)); level.getChunkAt(pos);
            String coords=pos.getX()+" "+pos.getY()+" "+pos.getZ();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level),
                    "fillbiome "+(pos.getX()-8)+" "+(pos.getY()-4)+" "+(pos.getZ()-8)+" "+(pos.getX()+8)+" "+(pos.getY()+4)+" "+(pos.getZ()+8)+" minecraft:"+cases[i][0]);
            var c=SettlementCandidate.structure(level.dimension(),pos,0,id("classification"),"biome-"+i,null);
            var result=UltimaKingdoms.DEFINITIONS.snapshot().resolve(level,c);
            check(result.kingdomId().equals(id(cases[i][1])), cases[i][0]+" resolved to "+result.kingdomId());
        }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void duplicateDiscoveryAndNearbyStructures(GameTestHelper h) {
        var api=service(h);var one=candidate(h,"one",0);var two=candidate(h,"two",8);
        var a=api.registerCandidate(h.getLevel(),one);var b=api.registerCandidate(h.getLevel(),two);
        check(!a.id().equals(b.id()),"Distinct nearby structure starts merged");
        check(api.registerCandidate(h.getLevel(),one).id().equals(a.id()),"Repeated discovery duplicated record");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void renameReassignMergeAndCitizenHistory(GameTestHelper h) {
        var api=service(h); var a=api.registerCandidate(h.getLevel(),candidate(h,"history",0));
        var b=api.registerCandidate(h.getLevel(),candidate(h,"target",32));
        Villager v=EntityType.VILLAGER.create(h.getLevel()); check(v!=null,"Villager creation failed");
        api.setOrigin(v,a.id());api.setResidence(v,a.id());
        var renamed=api.rename(a.id(),"Test Haven "+a.id());
        var reassigned=api.setKingdom(a.id(),id("lunari"));
        check(renamed.id().equals(a.id()) && reassigned.id().equals(a.id()),"Mutation changed UUID");
        check(reassigned.displayName().equals(renamed.displayName()),"Reassignment changed name");
        var civic=api.getCivicIdentity(v).orElseThrow();
        check(civic.originKingdom().orElseThrow().equals(a.kingdomId()),"Historical origin rewritten");
        check(civic.residenceKingdom().orElseThrow().equals(id("lunari")),"Residence did not resolve live kingdom");
        api.merge(a.id(),b.id());
        check(api.getSettlement(a.id()).orElseThrow().id().equals(b.id()),"Merge redirect missing");
        check(api.getResidence(v).orElseThrow().id().equals(b.id()),"Citizen failed to follow redirect");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void addonDiscoveryAndProviderLifetime(GameTestHelper h) {
        var api=service(h);var c=candidate(h,"addon",0); final int[] calls={0};
        Registration registration=api.registerSettlementDetector(id("test_detector"),(level,pos,radius)->{calls[0]++;return Stream.of(c);});
        var found=api.discover(h.getLevel(),c.anchor(),0);
        check(found.stream().anyMatch(s->s.anchor().equals(c.anchor())),"Addon discovery failed");
        int count=calls[0]; registration.close();registration.close();api.discover(h.getLevel(),c.anchor(),0);
        check(calls[0]==count,"Closed detector still called"); h.succeed();
    }
    @GameTest(template="empty")
    public static void civicPersistenceConversionAndShortVisit(GameTestHelper h) {
        var api=service(h);var a=api.registerCandidate(h.getLevel(),candidate(h,"home",0));
        var b=api.registerCandidate(h.getLevel(),candidate(h,"visit",32));
        Villager v=EntityType.VILLAGER.create(h.getLevel());Villager loaded=EntityType.VILLAGER.create(h.getLevel());
        api.setResidence(v,a.id());var original=api.getCivicIdentity(v).orElseThrow();
        v.moveTo(b.anchor().getX(),b.anchor().getY(),b.anchor().getZ());
        ((KingdomsServiceImpl)api).observeEntity(v);
        check(api.getResidence(v).orElseThrow().id().equals(a.id()),"Brief visit overwrote home");
        CompoundTag saved=new CompoundTag();v.saveWithoutId(saved);loaded.load(saved);
        check(api.getCivicIdentity(loaded).orElseThrow().equals(original),"Entity reload lost civic identity");
        Villager converted=EntityType.VILLAGER.create(h.getLevel());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.entity.living.LivingConversionEvent.Post(v,converted));
        check(api.getCivicIdentity(converted).orElseThrow().equals(original),"Conversion lost civic identity");
        api.setResidence(converted,b.id());
        check(api.getCivicIdentity(converted).orElseThrow().originSettlement().equals(original.originSettlement()),"Home migration overwrote origin");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void undefinedKingdomDoesNotMutateSave(GameTestHelper h) {
        var api=service(h);var a=api.registerCandidate(h.getLevel(),candidate(h,"missing",0));
        try {api.setKingdom(a.id(),id("missing_definition"));throw new net.minecraft.gametest.framework.GameTestAssertException("Unknown kingdom accepted");}
        catch(IllegalArgumentException expected) { }
        check(api.getSettlement(a.id()).orElseThrow().equals(a),"Rejected mutation altered settlement");h.succeed();
    }
    @GameTest(template="empty")
    public static void nonOperatorReadOnlyCommands(GameTestHelper h) throws Exception {
        var server=h.getLevel().getServer();var dispatcher=server.getCommands().getDispatcher();
        var source=server.createCommandSourceStack().withLevel(h.getLevel()).withPermission(0);
        check(dispatcher.execute("ultima kingdom list",source)==5,"Nonoperator cannot read kingdom list");
        try {dispatcher.execute("ultima village create 32 Unauthorized",source);throw new net.minecraft.gametest.framework.GameTestAssertException("Nonoperator mutation accepted");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
        h.succeed();
    }
    @GameTest(template="empty")
    public static void historicalAliasesAndMergeRediscovery(GameTestHelper h) {
        var api=service(h);var ca=candidate(h,"merge-source",0);var cb=candidate(h,"merge-target",32);
        var a=api.registerCandidate(h.getLevel(),ca);var b=api.registerCandidate(h.getLevel(),cb);
        String historical="Historical Test "+a.id();api.rename(a.id(),historical);api.rename(a.id(),"New Test "+a.id());
        check(api.findSettlement(historical.toUpperCase(java.util.Locale.ROOT)).orElseThrow().id().equals(a.id()),"Normalized historical alias missing");
        try {api.rename(b.id(),historical);throw new net.minecraft.gametest.framework.GameTestAssertException("Historical alias reused");}
        catch(IllegalArgumentException expected) { }
        api.merge(a.id(),b.id());
        check(api.registerCandidate(h.getLevel(),ca).id().equals(b.id()),"Source rediscovery recreated merged structure");
        check(api.registerCandidate(h.getLevel(),cb).id().equals(b.id()),"Target rediscovery changed merged identity");
        check(api.findSettlement(a.slug().toString()).orElseThrow().id().equals(b.id()),"Old machine slug lost on merge");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void loadedPoiClusterDiscoveredLazily(GameTestHelper h) {
        var level=h.getLevel();var center=h.absolutePos(new BlockPos(500,2,0)).atY(level.getSeaLevel());level.getChunkAt(center);level.setChunkForced(center.getX()>>4,center.getZ()>>4,true);
        level.setBlockAndUpdate(center.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(center,net.minecraft.world.level.block.Blocks.BELL.defaultBlockState());
        for(int z=1;z<=3;z++){
            var foot=center.offset(2,0,z*3);var head=foot.east();
            level.setBlockAndUpdate(foot.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(head.below(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            var state=net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING,net.minecraft.core.Direction.EAST);
            level.setBlock(foot,state.setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.FOOT),2);
            level.setBlock(head,state.setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD),2);
        }
        h.runAfterDelay(5,()->{
            ((KingdomsServiceImpl)service(h)).enqueueChunk(level,new net.minecraft.world.level.ChunkPos(center));
        });
        h.succeedWhen(()->{
            var found=service(h).getSettlementAt(level,center).orElseThrow(()->new net.minecraft.gametest.framework.GameTestAssertException("Loaded POIs not lazily recognized"));
            check(found.detectionSource()==DetectionSource.POI,"Expected actual POI detector");
            var id=found.id();service(h).discover(level,center,0);
            check(service(h).getSettlementAt(level,center).orElseThrow().id().equals(id),"Repeated POI scan duplicated settlement");level.setChunkForced(center.getX()>>4,center.getZ()>>4,false);
        });
    }

    @GameTest(template="empty",timeoutTicks=600)
    public static void loadedStructureStartsDiscoveredLazily(GameTestHelper h) {
        var level=h.getLevel();var base=h.absolutePos(new BlockPos(800,2,0));
        var structure=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .get(new ResourceLocation("minecraft:village_plains"));
        for(int n=0;n<2;n++){
            var pos=base.offset(n*16,0,0);var cp=new net.minecraft.world.level.ChunkPos(pos);var chunk=level.getChunkAt(pos);level.setChunkForced(cp.x,cp.z,true);
            var piece=new net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces.MineShaftRoom(0,
                    net.minecraft.util.RandomSource.create(n),pos.getX(),pos.getZ(),net.minecraft.world.level.levelgen.structure.structures.MineshaftStructure.Type.NORMAL);
            var start=new net.minecraft.world.level.levelgen.structure.StructureStart(structure,cp,0,
                    new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
            chunk.setStartForStructure(structure,start);
            ((KingdomsServiceImpl)service(h)).enqueueChunk(level,cp);
        }
        h.succeedWhen(()->{
            var records=service(h).getSettlementPage(Optional.empty(),0,64).stream()
                    .filter(v->v.detectionSource()==DetectionSource.STRUCTURE&&v.externalRefs().getOrDefault("minecraft:structure","").contains("village_plains"))
                    .filter(v->Math.abs(v.anchor().getX()-base.getX())<50&&Math.abs(v.anchor().getZ()-base.getZ())<50).toList();
            check(records.size()==2,"Actual chunk structure-start detector did not preserve two nearby starts: "+records.size());
        });
    }

    @GameTest(template="empty")
    public static void ledgerPacketRoundTripAndUntrustedBounds(GameTestHelper h) {
        var settlement=service(h).registerCandidate(h.getLevel(),candidate(h,"packet",0));
        var kingdom=service(h).getKingdom(settlement.kingdomId()).orElseThrow();
        var packet=new com.ultimakingdoms.network.LedgerPagePacket(42,service(h).revision(),Optional.empty(),0,false,0,
                List.of(com.ultimakingdoms.presentation.SettlementSummary.from(settlement)),List.of(com.ultimakingdoms.presentation.KingdomSummary.from(kingdom)));
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {packet.encode(buf);check(packet.equals(com.ultimakingdoms.network.LedgerPagePacket.decode(buf)),"Ledger packet roundtrip changed data");}
        finally{buf.release();}
        var invalid=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            invalid.writeVarLong(1);invalid.writeVarLong(1);invalid.writeBoolean(false);invalid.writeVarInt(0);invalid.writeBoolean(false);invalid.writeVarInt(0);invalid.writeVarInt(Integer.MAX_VALUE);
            try{com.ultimakingdoms.network.LedgerPagePacket.decode(invalid);throw new net.minecraft.gametest.framework.GameTestAssertException("Unbounded packet count accepted");}
            catch(IllegalArgumentException expected){}
        } finally{invalid.release();} h.succeed();
    }

    public static class EventProbe {
        int created,renamed,reassigned;
        @net.minecraftforge.eventbus.api.SubscribeEvent public void created(com.ultimakingdoms.api.event.SettlementCreatedEvent event){created++;}
        @net.minecraftforge.eventbus.api.SubscribeEvent public void renamed(com.ultimakingdoms.api.event.SettlementRenamedEvent event){renamed++;}
        @net.minecraftforge.eventbus.api.SubscribeEvent public void reassigned(com.ultimakingdoms.api.event.SettlementKingdomChangedEvent event){reassigned++;}
    }
    @GameTest(template="empty") public static void lifecycleEventsOnlyForChanges(GameTestHelper h){
        var probe=new EventProbe();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(probe);
        try {
            var api=service(h);var c=candidate(h,"events",0);var a=api.registerCandidate(h.getLevel(),c);api.registerCandidate(h.getLevel(),c);
            api.rename(a.id(),"Event Test "+a.id());api.rename(a.id(),"Event Test "+a.id());
            var target=a.kingdomId().equals(id("lunari"))?id("serenum"):id("lunari");api.setKingdom(a.id(),target);api.setKingdom(a.id(),target);
            check(probe.created==1&&probe.renamed==1&&probe.reassigned==1,"Lifecycle event duplicated or absent "+probe.created+"/"+probe.renamed+"/"+probe.reassigned);
        }finally{net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(probe);}h.succeed();
    }

    @GameTest(template="empty") public static void externalPromotionRetainsStrongStructureSeparation(GameTestHelper h){
        var api=service(h);var ca=candidate(h,"promoted-structure",0);var first=api.registerCandidate(h.getLevel(),ca);
        var external=SettlementCandidate.external(h.getLevel().dimension(),ca.anchor(),ca.radius(),ca.bounds(),id("external_test"),"native1",null,Map.of("native","one"),null);
        check(api.registerCandidate(h.getLevel(),external).id().equals(first.id()),"External evidence failed to match same village");
        var second=api.registerCandidate(h.getLevel(),candidate(h,"other-strong",8));
        check(!second.id().equals(first.id()),"External promotion erased strong structure separation");h.succeed();
    }
    @GameTest(template="empty") public static void invalidAddonReclassificationIsAtomic(GameTestHelper h){
        var api=service(h);var first=api.registerCandidate(h.getLevel(),candidate(h,"invalid-addon",0));
        try(var registration=api.registerKingdomResolver(id("invalid_test"),(level,c)->Optional.of(new KingdomResolution(id("missing"),AssignmentSource.ADDON,Optional.empty(),1,1,List.of("invalid"))))){
            try{api.reclassify(first.id());throw new net.minecraft.gametest.framework.GameTestAssertException("Undefined addon kingdom accepted");}
            catch(IllegalArgumentException expected){}
            check(api.getSettlement(first.id()).orElseThrow().equals(first),"Invalid reclassification mutated persisted record");
        }h.succeed();
    }

    @GameTest(template="empty") public static void commandResourceIdsAndSlugSuggestionsExecute(GameTestHelper h)throws Exception {
        runCommandScenario(h.getLevel(),h.absolutePos(new BlockPos(0,2,0)));h.succeed();
    }
    public static void runCommandScenario(net.minecraft.server.level.ServerLevel level,BlockPos position)throws Exception {
        var api=UltimaKingdomsApi.get(level.getServer());var first=api.registerCandidate(level,
                SettlementCandidate.structure(level.dimension(),position,4,id("command_test"),"command-resource-id",null));
        var dispatcher=level.getServer().getCommands().getDispatcher();var source=level.getServer().createCommandSourceStack().withLevel(level);
        check(dispatcher.execute("ultima kingdom info ultima_kingdoms:serenum",source)==1,"Unquoted kingdom resource ID failed");
        check(dispatcher.execute("ultima village info "+first.slug(),source)==1,"Unquoted settlement slug failed");
        var target=first.kingdomId().equals(id("lunari"))?id("serenum"):id("lunari");
        check(dispatcher.execute("ultima village setkingdom "+first.slug()+" "+target,source)==1,"Suggested raw IDs failed mutation");
        check(api.getSettlement(first.id()).orElseThrow().kingdomId().equals(target),"Command reported success without assigning kingdom");
        String renamed="Quoted Command "+first.id();api.rename(first.id(),renamed);
        check(dispatcher.execute("ultima village info \""+renamed+"\"",source)==1,"Quoted display name failed");
        check(dispatcher.execute("ultima village info \""+first.displayName()+"\"",source)==1,"Quoted historical alias failed");
        String input="ultima village setkingdom "+first.slug()+" ultima_";
        var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(input,source)).join().getList();
        check(!suggestions.isEmpty(),"Kingdom suggestions missing");
        for(var suggestion:suggestions){var parsed=dispatcher.parse(suggestion.apply(input),source);check(!parsed.getReader().canRead()&&parsed.getExceptions().isEmpty(),"Suggested command is not parseable: "+suggestion.apply(input));}
    }

}
