package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entity name translation settings screen.
 */
public class EntityNameTranslationScreen extends ScrollableSettingsScreen {

    private boolean entityNameEnabled;
    private int entityNameRadius;

    public EntityNameTranslationScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.entity_translation"), parent);
        this.contentWidth = 240;
        this.entrySpacing = 26;
        this.entityNameEnabled = ModConfig.CONTENT_ENTITY_NAME_ENABLED.get();
        this.entityNameRadius = ModConfig.CONTENT_ENTITY_NAME_RADIUS.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.entity.section").getString());

        CycleButton<Boolean> entityButton = CycleButton.onOffBuilder(entityNameEnabled)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.entity.enabled"),
                        (button, value) -> entityNameEnabled = value);
        withTooltip(entityButton, "screen.simple_translate.entity.enabled.tooltip");
        addEntry(entityButton);

        CycleButton<Integer> radiusButton = CycleButton.<Integer>builder(
                        value -> Component.translatable("screen.simple_translate.radius.blocks", value))
                .withValues(4, 8, 12, 16, 20, 24, 32, 48, 64)
                .withInitialValue(entityNameRadius)
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.entity.radius"),
                        (button, value) -> entityNameRadius = value);
        withTooltip(radiusButton, "screen.simple_translate.entity.radius.tooltip");
        addEntry(radiusButton);
    }

    @Override
    protected void saveSettings() {
        ModConfig.CONTENT_ENTITY_NAME_ENABLED.set(entityNameEnabled);
        ModConfig.CONTENT_ENTITY_NAME_RADIUS.set(entityNameRadius);
    }
}
