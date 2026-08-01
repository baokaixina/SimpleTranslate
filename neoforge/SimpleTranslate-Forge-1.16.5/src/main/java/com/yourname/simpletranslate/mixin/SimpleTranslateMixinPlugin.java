package com.yourname.simpletranslate.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class SimpleTranslateMixinPlugin implements IMixinConfigPlugin {
    private static final String FTB_LIBRARY_MOD_ID = "ftblibrary";

    @Override
    public void onLoad(String mixinPackage) {
        // Forge 1.16.5 predates JarJar, so MixinExtras is shaded into this jar
        // and must be bootstrapped manually before any @WrapOperation mixin
        // applies (runtime evidence 2026-07-27: without this, mixin apply dies
        // with ClassMetadataNotFoundException for mixinextras Operation).
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("FtbScreenWrapperTranslationMixin")) {
            return isModLoaded(FTB_LIBRARY_MOD_ID);
        }
        if (mixinClassName.endsWith("FtbTextFieldTranslationMixin")) {
            return isModLoaded(FTB_LIBRARY_MOD_ID);
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        try {
            LoadingModList loadingModList = LoadingModList.get();
            return loadingModList != null && loadingModList.getModFileById(modId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
