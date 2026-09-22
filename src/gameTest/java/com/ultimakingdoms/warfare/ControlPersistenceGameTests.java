package com.ultimakingdoms.warfare;

import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("ultima_kingdoms")
@PrefixGameTestTemplate(false)
public final class ControlPersistenceGameTests {
    @GameTest(template = "empty")
    public static void malformedAndFuturePayloadsArePreserved(GameTestHelper helper) {
        for (String payload : new String[]{"{}", "null", "{\"revision\":-1,\"mappings\":{},\"bindings\":{}}"}) {
            var tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putString("Payload", payload);
            var loaded = ControlSavedData.load(tag);
            if (loaded.writable() || loaded.isDirty() || !loaded.save(new CompoundTag()).equals(tag))
                throw new GameTestAssertException("Malformed control data was overwritten");
        }
        var future = new CompoundTag(); future.putInt("Schema", 99); future.putString("Payload", "future data");
        var loaded = ControlSavedData.load(future);
        if (loaded.writable() || !loaded.save(new CompoundTag()).equals(future)) throw new GameTestAssertException("Future data lost");
        var valid = new ControlSavedData().save(new CompoundTag());
        if (!ControlSavedData.load(valid).writable()) throw new GameTestAssertException("Current empty schema failed roundtrip");
        helper.succeed();
    }
}
