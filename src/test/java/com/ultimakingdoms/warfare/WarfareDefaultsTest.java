package com.ultimakingdoms.warfare;

import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarfareDefaultsTest {
    @Test void everyR3BooleanDefaultsEnabledIncludingFutureSwitches() throws Exception {
        int checked=0;
        for(var field:WarfareConfig.class.getDeclaredFields())if(field.getType()==ForgeConfigSpec.BooleanValue.class){
            var value=(ForgeConfigSpec.BooleanValue)field.get(null);
            assertEquals(Boolean.TRUE,value.getDefault(),field.getName());checked++;
        }
        assertTrue(checked>=7);
    }
}
