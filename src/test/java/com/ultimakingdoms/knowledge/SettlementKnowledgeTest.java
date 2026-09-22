package com.ultimakingdoms.knowledge;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SettlementKnowledgeTest {
    private static CompoundTag empty() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", 1); tag.putBoolean("Initialized", true); tag.putLong("Revision", 1);
        tag.put("Public", new ListTag()); tag.put("Players", new ListTag());
        return tag;
    }

    @Test void privateDiscoverySurvivesRestartWithoutLeakingToAnotherPlayer() {
        var data = SettlementKnowledge.load(empty());
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), village = UUID.randomUUID();
        assertTrue(data.discover(alice, village));
        assertFalse(data.discover(alice, village));
        var loaded = SettlementKnowledge.load(data.save(new CompoundTag()));
        assertTrue(loaded.knows(alice, village));
        assertFalse(loaded.knows(bob, village));
        assertEquals(2, loaded.revision());
    }

    @Test void legacyPublicSettlementsAndMergedDiscoveriesRetainVisibility() {
        UUID old = UUID.randomUUID(), replacement = UUID.randomUUID(), alice = UUID.randomUUID();
        CompoundTag tag = empty(), id = new CompoundTag(); id.putUUID("Id", old);
        ListTag published = new ListTag(); published.add(id); tag.put("Public", published);
        var data = SettlementKnowledge.load(tag);
        assertTrue(data.knows(alice, old));
        data.merge(old, replacement);
        assertFalse(data.knows(alice, old));
        assertTrue(data.knows(alice, replacement));
        UUID privateOld = UUID.randomUUID(), privateNew = UUID.randomUUID();
        data.discover(alice, privateOld); data.merge(privateOld, privateNew);
        assertTrue(data.knows(alice, privateNew));
        assertFalse(data.knows(UUID.randomUUID(), privateNew));
    }

    @Test void malformedAndFutureDataStayReadOnlyAndUnchanged() {
        CompoundTag future = empty(); future.putInt("Schema", 99); future.putString("Extra", "preserve");
        assertPreserved(future);
        CompoundTag malformed = empty(); ListTag wrong = new ListTag(); wrong.add(StringTag.valueOf("bad"));
        malformed.put("Public", wrong); assertPreserved(malformed);
        CompoundTag duplicate = empty(); ListTag ids = new ListTag(); CompoundTag id = new CompoundTag();
        id.putUUID("Id", UUID.randomUUID()); ids.add(id); ids.add(id.copy()); duplicate.put("Public", ids);
        assertPreserved(duplicate);
    }

    @Test void failedSaveStaysDirtyAndSuccessfulSaveRestoresKnowledge(@org.junit.jupiter.api.io.TempDir java.nio.file.Path path) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        var data=SettlementKnowledge.load(empty()); UUID player=UUID.randomUUID(), village=UUID.randomUUID();
        data.discover(player,village);
        java.nio.file.Path blocker=path.resolve("blocker"); java.nio.file.Files.writeString(blocker,"not a directory");
        data.save(blocker.resolve("knowledge.dat").toFile()); assertTrue(data.isDirty());
        java.io.File destination=path.resolve("knowledge.dat").toFile(); data.save(destination); assertFalse(data.isDirty());
        var loaded=SettlementKnowledge.load(NbtIo.readCompressed(destination).getCompound("data"));
        assertTrue(loaded.knows(player,village));
    }

    private void assertPreserved(CompoundTag input) {
        var data = SettlementKnowledge.load(input);
        assertFalse(data.writable());
        assertFalse(data.discover(UUID.randomUUID(), UUID.randomUUID()));
        data.setDirty(); assertFalse(data.isDirty());
        assertEquals(input, data.save(new CompoundTag()));
    }
}
