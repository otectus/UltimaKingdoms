package com.ultimakingdoms.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.*;

/** Apply audited native patches only to the exact supported provider. */
public final class RecruitsMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var mods = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
        return mods != null && mods.getMods().stream().anyMatch(m -> m.getModId().equals("recruits") && m.getVersion().toString().equals("1.15.2"));
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
