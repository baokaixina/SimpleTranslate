package com.yourname.simpletranslate.bootstrap;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * MixinBooter 9.4 late loader for the optional FTB compat mixins. They target
 * mod classes (FTBLib GuiWrapper/TextField), which are not on the
 * LaunchClassLoader when {@link SimpleTranslateMixinLoader} runs at coremod
 * time — registering them from the early loader silently dropped them in
 * every previous run (verified live 2026-07-27: GuiWrapper was never
 * transformed). MixinBooter discovers this class inside the mod jar and
 * queues the config after mod jars join the classpath; the @Pseudo mixins
 * still no-op gracefully when FTB Library is absent.
 */
public final class SimpleTranslateLateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<String>(2);
        if (Loader.isModLoaded("ftblib")) {
            configs.add("simple_translate.late.ftb.mixins.json");
        }
        if (Loader.isModLoaded("tips")) {
            configs.add("simple_translate.late.tips.mixins.json");
        }
        return configs;
    }
}
