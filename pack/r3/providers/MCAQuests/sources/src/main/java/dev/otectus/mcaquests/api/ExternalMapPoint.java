package dev.otectus.mcaquests.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** One already-authorized map point supplied by a server integration. */
public record ExternalMapPoint(String key, ResourceKey<Level> dimension, BlockPos position, String label,
                               Kind kind, boolean approximate, boolean lastKnown) {
    public enum Kind { SITE, ROUTE }
    public ExternalMapPoint {
        if(key==null||key.isBlank()||key.length()>160||label==null||label.length()>128||dimension==null||position==null||kind==null)
            throw new IllegalArgumentException("invalid external map point");
        position=position.immutable();
    }
    public static ResourceKey<Level> dimension(String value){
        ResourceLocation id=ResourceLocation.tryParse(value);if(id==null)throw new IllegalArgumentException("invalid dimension");
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,id);
    }
}
