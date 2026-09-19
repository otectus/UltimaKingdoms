package com.ultimakingdoms.test;

import com.ultimakingdoms.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.*;
import java.util.*;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class McaGameTests {
    private static void check(boolean ok,String msg){if(!ok)throw new net.minecraft.gametest.framework.GameTestAssertException(msg);}
    private static Object invoke(Object obj,String name,Class<?>[] types,Object...args)throws Exception{return obj.getClass().getMethod(name,types).invoke(obj,args);}
    private static void field(Object obj,String name,Object value)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value);}
    @SuppressWarnings("unchecked")
    private static Object village(ServerLevel level,int id,BlockPos pos)throws Exception {
        Class<?> vc=Class.forName("forge.net.mca.server.world.data.Village");
        Object v=vc.getConstructor(int.class,ServerLevel.class).newInstance(id,level);
        Object box=Class.forName("forge.net.mca.util.BlockBoxExtended").getConstructor(int.class,int.class,int.class,int.class,int.class,int.class)
                .newInstance(pos.getX()-8,pos.getY()-2,pos.getZ()-8,pos.getX()+8,pos.getY()+5,pos.getZ()+8);
        field(v,"box",box);invoke(v,"setAutoScan",new Class[]{boolean.class},false);
        Map<Integer,Object> buildings=(Map<Integer,Object>)invoke(v,"getBuildings",new Class[]{});
        for(int i=0;i<8;i++)buildings.put(i,Class.forName("forge.net.mca.server.world.data.Building").getConstructor(BlockPos.class).newInstance(pos.offset(i,0,0)));
        Class<?> mc=Class.forName("forge.net.mca.server.world.data.VillageManager");
        Object manager=mc.getMethod("get",ServerLevel.class).invoke(null,level);
        Field villages=mc.getDeclaredField("villages");villages.setAccessible(true);((Map<Integer,Object>)villages.get(manager)).put(id,v);
        check((boolean)invoke(v,"isVillage",new Class[]{}),"Fixture not recognized by exact MCA release");return v;
    }
    private static void home(Entity e,int id)throws Exception {
        Class<?> residency=Class.forName("forge.net.mca.entity.ai.Residency");Field f=residency.getDeclaredField("VILLAGE");f.setAccessible(true);
        Method setter=Arrays.stream(e.getClass().getMethods()).filter(m->m.getName().equals("setTrackedValue")&&m.getParameterCount()==2).findFirst().orElseThrow();
        setter.invoke(e,f.get(null),id);
    }
    @GameTest(template="empty",timeoutTicks=650)
    public static void exactMcaHomeLifecycle(GameTestHelper h)throws Exception {
        if(!ModList.get().isLoaded("mca")){System.out.println("SKIP exact MCA lifecycle: standalone runtime");h.succeed();return;}
        check(ModList.get().getModContainerById("mca").orElseThrow().getModInfo().getVersion().toString().equals("7.6.26+1.20.1"),"Wrong MCA release");
        runScenario(h.getLevel(),h.absolutePos(new BlockPos(0,2,0)),h::runAfterDelay,h::succeed);
    }
    public static void runScenario(ServerLevel level,BlockPos a,java.util.function.BiConsumer<Long,Runnable> schedule,Runnable success)throws Exception {
        var api=UltimaKingdomsApi.get(level.getServer());BlockPos b=a.offset(160,0,0);
        level.getChunkAt(a);level.getChunkAt(b);level.setChunkForced(a.getX()>>4,a.getZ()>>4,true);level.setChunkForced(b.getX()>>4,b.getZ()>>4,true);village(level,90001,a);village(level,90002,b);
        var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca:male_villager"));check(type!=null,"MCA villager registry id missing");
        Entity e=type.create(level);check(e instanceof AgeableMob,"Not real MCA ageable entity");((Mob)e).setNoAi(true);e.setNoGravity(true);e.moveTo(a.getX(),a.getY(),a.getZ());home(e,90001);level.addFreshEntity(e);
        var initial=api.getCivicIdentity(e).orElseThrow();UUID initialResidence=initial.residenceSettlement().orElseThrow();
        check(api.getResidence(e).orElseThrow().externalRefs().get("mca").endsWith("#90001"),"Native MCA home did not assign residence");
        Entity baby=type.create(level);((AgeableMob)baby).setAge(-24000);((Mob)baby).setNoAi(true);baby.moveTo(a.getX(),a.getY(),a.getZ());level.addFreshEntity(baby);
        check(api.getCivicIdentity(baby).orElseThrow().originSettlement().orElseThrow().equals(initialResidence),"MCA newborn origin was not recorded");baby.discard();
        CompoundTag nativeBefore=new CompoundTag();e.saveWithoutId(nativeBefore);
        api.rename(initialResidence,"MCA Renamed "+initialResidence);api.setKingdom(initialResidence,new ResourceLocation("ultima_kingdoms:lunari"));
        CompoundTag nativeAfter=new CompoundTag();e.saveWithoutId(nativeAfter);
        nativeBefore.remove("ForgeData");nativeAfter.remove("ForgeData");check(nativeBefore.equals(nativeAfter),"Ultima mutation changed MCA native data");
        invoke(e,"setName",new Class[]{String.class},"MCA Test Renamed");check(api.getResidence(e).orElseThrow().id().equals(initialResidence),"Personal name changed civic identity");
        e.moveTo(b.getX(),b.getY(),b.getZ());
        schedule.accept(220L,()->{
            check(api.getResidence(e).orElseThrow().id().equals(initialResidence),"Visiting another MCA village rewrote home");
            try{home(e,90002);}catch(Exception ex){throw new RuntimeException(ex);}
        });
        schedule.accept(450L,()->{
            var migrated=api.getCivicIdentity(e).orElseThrow();
            check(!migrated.residenceSettlement().orElseThrow().equals(initialResidence),"Production periodic observation failed home migration");
            check(migrated.originSettlement().equals(initial.originSettlement())&&migrated.originKingdom().equals(initial.originKingdom()),"Migration rewrote origin history");
            CompoundTag saved=new CompoundTag();e.saveWithoutId(saved);Entity reloaded=type.create(level);reloaded.load(saved);
            check(api.getCivicIdentity(reloaded).orElseThrow().equals(migrated),"MCA NBT reload lost civic identity");
            e.discard();level.setChunkForced(a.getX()>>4,a.getZ()>>4,false);level.setChunkForced(b.getX()>>4,b.getZ()>>4,false);success.run();
        });
    }
}
