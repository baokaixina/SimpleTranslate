package com.yourname.simpletranslate.mixin;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class SimpleTranslateMixinPlugin implements IMixinConfigPlugin {
    private static final String FTB_LIBRARY_MOD_ID = "ftblibrary";
    private static final String WYNNTILS_MOD_ID = "wynntils";

    @Override
    public void onLoad(String mixinPackage) {
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
        if (mixinClassName.endsWith("WynntilsOverlayManagerMixin")) {
            return isModLoaded(WYNNTILS_MOD_ID);
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        try {
            LoadingModList modList = LoadingModList.get();
            if (modList == null) {
                return false;
            }
            ModFileInfo modFile = modList.getModFileById(modId);
            return modFile != null;
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
