package com.ultimakingdoms.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicSavedDataWriter {
    private AtomicSavedDataWriter() {
    }

    public static void write(File destination, CompoundTag data) throws IOException {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        File temporary = File.createTempFile(destination.getName(), ".tmp", parent);
        boolean moved = false;
        try {
            CompoundTag root = new CompoundTag();
            root.put("data", data);
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root, temporary);
            try (FileChannel channel = FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Refuse the transaction when the filesystem cannot provide an atomic replacement.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            if (parent != null) {
                try (FileChannel directory = FileChannel.open(parent.toPath(), StandardOpenOption.READ)) {
                    directory.force(true);
                } catch (IOException ignored) {
                    // The replacement is complete; some platforms cannot force directory handles.
                }
            }
        } finally {
            if (!moved) Files.deleteIfExists(temporary.toPath());
        }
    }
}
