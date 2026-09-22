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
        return record(name, name.equals("Greenford") ? 52 : 42);
    }
    private SettlementRecord record(String name, int villageId) {
        SettlementRecord r = new SettlementRecord();
        r.id = UUID.randomUUID(); r.dimension = Level.OVERWORLD; r.anchor = new BlockPos(3,64,8);
        r.radius=32; r.bounds=SettlementBounds.around(r.anchor,32);
        r.kingdomId=new ResourceLocation("ultima_kingdoms:serenum"); r.displayName=name;
        r.slug=new ResourceLocation("ultima_kingdoms:"+name.toLowerCase());
        r.biomeAtCreation=new ResourceLocation("minecraft:plains");
        r.assignmentSource=AssignmentSource.EXACT_BIOME; r.detectionSource=DetectionSource.STRUCTURE;
        r.sourceId=new ResourceLocation("test:village"); r.sourceKey=name; r.nameLocked=true;
        r.addExternalRef("mca", "minecraft:overworld#"+villageId);
        r.addExternalRef("mca", "minecraft:overworld#"+(villageId+1));
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
        data.add(a); data.add(b); data.validateMergeExternalRefs(a,b);
        a.externalRefValues.forEach((namespace,values)->values.forEach(value->b.addExternalRef(namespace,value)));
        b.aliases.add(a.displayName);b.aliases.addAll(a.aliases);
        data.redirect(a.id,b.id);data.changed(b);
        Method load=SettlementSavedData.class.getDeclaredMethod("load",CompoundTag.class); load.setAccessible(true);
        var loaded=(SettlementSavedData)load.invoke(null,data.save(new CompoundTag()));
        assertEquals(b.id,loaded.get(a.id).orElseThrow().id);
        assertEquals(b.id,loaded.getByExternalRef("mca","minecraft:overworld#42").orElseThrow().id);
        assertEquals(b.id,loaded.getByExternalRef("mca","minecraft:overworld#53").orElseThrow().id);
        assertTrue(loaded.get(b.id).orElseThrow().aliases.contains("Aldwick"));
        assertTrue(loaded.get(b.id).orElseThrow().aliases.contains("Old Aldwick"));
        assertEquals(1,loaded.records().size());
    }
    @GameTest(template="empty") public void duplicateExternalReferenceIsRejectedAtomically(GameTestHelper helper) {
        helper.runAfterDelay(1, helper::succeed);
        var a=record("Aldwick",42);var b=record("Greenford",42);var data=new SettlementSavedData();
        data.add(a);
        assertThrows(IllegalStateException.class,()->data.add(b));
        assertEquals(1,data.records().size());
        assertEquals(a.id,data.getByExternalRef("mca","minecraft:overworld#42").orElseThrow().id);
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
