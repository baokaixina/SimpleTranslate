package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.Arrays;

/**
 * Common 1.12.2 settings-screen contract.
 *
 * <p>The Fabric baseline uses a small common screen class so every settings
 * page has the same non-pausing navigation, translated labels, and delayed
 * help.  This is the equivalent implementation for the exact 1.12.2
 * {@link GuiScreen} API.</p>
 */
public abstract class BaseSimpleTranslateScreen extends GuiScreen {
    protected final GuiScreen parent;
    protected final TranslationEngine engine;

    protected BaseSimpleTranslateScreen(GuiScreen parent, TranslationEngine engine) {
        this.parent = parent;
        this.engine = engine;
    }

    protected static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }

    protected static String stateLabel(String key, boolean enabled) {
        return tr(key) + ": " + tr(enabled ? "options.on" : "options.off");
    }

    protected final void returnToParent() {
        if (this.mc != null) {
            this.mc.displayGuiScreen(this.parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Draw one delayed tooltip after all controls have rendered. */
    protected final void drawDelayedTooltip(int mouseX, int mouseY) {
        for (GuiButton button : this.buttonList) {
            if (button instanceof HintButton) {
                HintButton hint = (HintButton) button;
                String tooltip = hint.getVisibleTooltip();
                if (tooltip != null && !tooltip.isEmpty()) {
                    drawHoveringText(Arrays.asList(tooltip.split("\\n")), mouseX, mouseY);
                    return;
                }
            }
        }
    }
}
