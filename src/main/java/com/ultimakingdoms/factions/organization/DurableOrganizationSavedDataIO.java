package com.ultimakingdoms.factions.organization;

import net.minecraft.nbt.CompoundTag;
import java.io.File;
import java.io.IOException;

final class DurableOrganizationSavedDataIO {
    private DurableOrganizationSavedDataIO() { }
    static void write(File destination, CompoundTag data) throws IOException {
        com.ultimakingdoms.persistence.AtomicSavedDataWriter.write(destination, data);
    }
}
