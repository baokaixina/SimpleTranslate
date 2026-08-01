package com.yourname.simpletranslate.forge;

import com.yourname.simpletranslate.gui.SimpleTranslateScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Set;

/** Forge 1.12.2 mod-list configuration entry point. */
public final class ForgeConfigGuiFactory implements IModGuiFactory {
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Forge-1.12.2");

    @Override
    public void initialize(Minecraft minecraftInstance) {
        LOGGER.info("Registered Simple Translate Forge configuration GUI factory.");
    }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        return new SimpleTranslateScreen(parentScreen, com.yourname.simpletranslate.SimpleTranslateForge1122.getEngine());
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }
}
