package com.ultimakingdoms.core;

import com.ultimakingdoms.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.lang.reflect.Method;
import java.util.UUID;
import static com.ultimakingdoms.test.TestAssertions.*;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public class SettlementPersistenceTest {
    private SettlementRecord record(String name) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.randomUUID(); r.dimension = Level.OVERWORLD; r.anchor = new BlockPos(3,64,8);
        r.radius=32; r.bounds=SettlementBounds.around(r.anchor,32);
        r.kingdomId=new ResourceLocation("ultima_kingdoms:serenum"); r.displayName=name;
        r.slug=new ResourceLocation("ultima_kingdoms:"+name.toLowerCase());
        r.biomeAtCreation=new ResourceLocation("minecraft:plains");
        r.assignmentSource=AssignmentSource.EXACT_BIOME; r.detectionSource=DetectionSource.STRUCTURE;
        r.sourceId=new ResourceLocation("test:village"); r.sourceKey=name; r.nameLocked=true;
        r.addExternalRef("mca", "minecraft:overworld#42");r.addExternalRef("mca", "minecraft:overworld#43");
        r.addStrongStructureIdentity(r.sourceId,r.sourceKey);r.addStrongStructureIdentity(r.sourceId,"start-a");r.addStrongStructureIdentity(r.sourceId,"start-b");
        r.retiredSlugs.add(new ResourceLocation("ultima_kingdoms:retired"));r.aliases.add("Old " + name);
        return r;
    }
    @GameTest(template="empty") public void nbtRoundTripPreservesIdentityAndReferences(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var original=record("Aldwick"); var loaded=SettlementRecord.load(original.save());
        assertEquals(original.snapshot(), loaded.snapshot());
        assertEquals(original.strongStructureIdentities,loaded.strongStructureIdentities);
        assertEquals(original.externalRefValues,loaded.externalRefValues);
        assertEquals(original.retiredSlugs,loaded.retiredSlugs);
    }
    @GameTest(template="empty") public void mergedIdentityRedirectSurvivesSerialization(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        var a=record("Aldwick"); var b=record("Greenford"); var data=new SettlementSavedData();
        data.add(a); data.add(b); data.redirect(a.id,b.id);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,data.save(new CompoundTag()));
        assertEquals(b.id,loaded.get(a.id).orElseThrow().id);
        assertEquals(1,loaded.records().size());
    }
    @GameTest(template="empty") public void futureRootSchemaRefusesInitializationAndStaysClean(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        CompoundTag future=new CompoundTag(); future.putInt("Schema",99);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,future);
        Method validate=SettlementSavedData.class.getDeclaredMethod("validateLoaded"); validate.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(loaded));
        assertInstanceOf(IllegalStateException.class,failure.getCause());
        assertFalse(loaded.isDirty());
    }
    @GameTest(template="empty") public void futureRecordSchemaIsNotSilentlyDowngraded(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var tag=record("Aldwick").save();tag.putInt("Schema",99);
        assertThrows(IllegalArgumentException.class,()->SettlementRecord.load(tag));
    }
    @GameTest(template="empty") public void futureNestedRecordProtectsWholeSave(GameTestHelper helper) throws Exception {
        helper.runAfterDelay(1, helper::succeed);
        var original=record("Aldwick"); var data=new SettlementSavedData(); data.add(original);
        var tag=data.save(new CompoundTag());tag.getList("Settlements",10).getCompound(0).putInt("Schema",99);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class);load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,tag);
        Method validate=SettlementSavedData.class.getDeclaredMethod("validateLoaded");validate.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->validate.invoke(loaded));
        assertFalse(loaded.isDirty());
    }

}
