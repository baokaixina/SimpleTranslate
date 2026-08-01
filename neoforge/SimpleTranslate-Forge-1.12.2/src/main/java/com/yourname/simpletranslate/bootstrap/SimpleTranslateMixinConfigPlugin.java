package com.yourname.simpletranslate.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Config plugin for the early (vanilla-target) mixin set. The optional FTB
 * compat mixins moved to loader-conditional late configs via
 * {@link SimpleTranslateLateMixinLoader}: this plugin's getMixins() runs at
 * coremod time, before FML adds mod jars to the LaunchClassLoader, so a
 * GuiWrapper.class resource probe here can never succeed and previously
 * dropped the FTB mixins in every production run.
 */
public final class SimpleTranslateMixinConfigPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

    @Override
    public List<String> getMixins() {
        return Collections.<String>emptyList();
    }
}
