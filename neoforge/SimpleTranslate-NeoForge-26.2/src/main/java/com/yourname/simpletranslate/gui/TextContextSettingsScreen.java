package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Scope-local translation-memory context settings shared by all Component surfaces. */
public final class TextContextSettingsScreen extends ScrollableSettingsScreen {
    private boolean enabled;
    private boolean allowShared;
    private boolean receivedChat;
    private boolean sentChat;
    private boolean itemTooltip;
    private boolean hoverTooltip;
    private boolean book;
    private boolean sign;
    private boolean hudCaptions;
    private boolean hudProgress;
    private boolean entityName;
    private boolean textDisplay;
    private String savedSignature;
    private Component status = Component.empty();

    public TextContextSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.text_context"), parent);
        this.contentWidth = 270;
        loadValues();
        this.savedSignature = signature();
    }

    private void loadValues() {
        enabled = ModConfig.API_TEXT_CONTEXT_ENABLED.get();
        allowShared = ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.get();
        receivedChat = ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.get();
        sentChat = ModConfig.API_TEXT_CONTEXT_SENT_CHAT.get();
        itemTooltip = ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.get();
        hoverTooltip = ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.get();
        book = ModConfig.API_TEXT_CONTEXT_BOOK.get();
        sign = ModConfig.API_TEXT_CONTEXT_SIGN.get();
        hudCaptions = ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.get();
        hudProgress = ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.get();
        entityName = ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.get();
        textDisplay = ModConfig.API_TEXT_CONTEXT_TEXT_DISPLAY.get();
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.text_context.privacy").getString());
        addDescription(Component.translatable("screen.simple_translate.text_context.privacy_notice").getString());
        addToggle("screen.simple_translate.text_context.enabled", enabled, value -> enabled = value);
        addToggle("screen.simple_translate.text_context.allow_shared", allowShared, value -> allowShared = value);

        addSectionHeader(Component.translatable("screen.simple_translate.text_context.presets").getString());
        Button allPreset = Button.builder(
                Component.translatable("screen.simple_translate.text_context.preset.all"), button -> {
            applyPreset(true, false, "screen.simple_translate.text_context.preset.all.applied");
        }).bounds(0, 0, contentWidth, 20).build();
        withTooltip(allPreset, "screen.simple_translate.text_context.preset.all.tooltip");
        addEntry(allPreset);
        Button uiPreset = Button.builder(
                Component.translatable("screen.simple_translate.text_context.preset.ui"), button -> {
            applyPreset(true, true, "screen.simple_translate.text_context.preset.ui.applied");
        }).bounds(0, 0, contentWidth, 20).build();
        withTooltip(uiPreset, "screen.simple_translate.text_context.preset.ui.tooltip");
        addEntry(uiPreset);
        Button clearPreset = Button.builder(
                Component.translatable("screen.simple_translate.text_context.preset.clear"), button -> {
            applyPreset(false, false, "screen.simple_translate.text_context.preset.clear.applied");
        }).bounds(0, 0, contentWidth, 20).build();
        withTooltip(clearPreset, "screen.simple_translate.text_context.preset.clear.tooltip");
        addEntry(clearPreset);

        addSectionHeader(Component.translatable("screen.simple_translate.text_context.sources").getString());
        addToggle("screen.simple_translate.text_context.received_chat", receivedChat, value -> receivedChat = value);
        addToggle("screen.simple_translate.text_context.sent_chat", sentChat, value -> sentChat = value);
        addToggle("screen.simple_translate.text_context.item_tooltip", itemTooltip, value -> itemTooltip = value);
        addToggle("screen.simple_translate.text_context.hover_tooltip", hoverTooltip, value -> hoverTooltip = value);
        addToggle("screen.simple_translate.text_context.book", book, value -> book = value);
        addToggle("screen.simple_translate.text_context.sign", sign, value -> sign = value);
        addToggle("screen.simple_translate.text_context.hud_captions", hudCaptions, value -> hudCaptions = value);
        addToggle("screen.simple_translate.text_context.hud_progress", hudProgress, value -> hudProgress = value);
        addToggle("screen.simple_translate.text_context.entity_name", entityName, value -> entityName = value);
        addToggle("screen.simple_translate.text_context.text_display", textDisplay, value -> textDisplay = value);
    }

    private void addToggle(String key, boolean initial, java.util.function.Consumer<Boolean> setter) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
                .create(0, 0, contentWidth, 20, Component.translatable(key),
                        (ignored, value) -> {
                            setter.accept(value);
                            status = Component.translatable("screen.simple_translate.text_context.saved");
                        });
        withTooltip(button, key + ".tooltip");
        addEntry(button);
    }

    private void setAll(boolean value) {
        receivedChat = value;
        sentChat = value;
        itemTooltip = value;
        hoverTooltip = value;
        book = value;
        sign = value;
        hudCaptions = value;
        hudProgress = value;
        entityName = value;
        textDisplay = value;
    }

    private void applyPreset(boolean value, boolean excludeChat, String statusKey) {
        enabled = value;
        setAll(value);
        if (excludeChat) {
            receivedChat = false;
            sentChat = false;
        }
        saveSettings();
        ModConfig.save();
        status = Component.translatable(statusKey);
        double previousScroll = scrollOffset;
        rebuildWidgets();
        scrollOffset = Math.max(0, Math.min(previousScroll, Math.max(0, contentHeight - viewportHeight)));
        repositionEntries();
    }

    @Override
    protected void saveSettings() {
        ModConfig.API_TEXT_CONTEXT_ENABLED.set(enabled);
        ModConfig.API_TEXT_CONTEXT_ALLOW_SHARED.set(allowShared);
        ModConfig.API_TEXT_CONTEXT_RECEIVED_CHAT.set(receivedChat);
        ModConfig.API_TEXT_CONTEXT_SENT_CHAT.set(sentChat);
        ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(itemTooltip);
        ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(hoverTooltip);
        ModConfig.API_TEXT_CONTEXT_BOOK.set(book);
        ModConfig.API_TEXT_CONTEXT_SIGN.set(sign);
        ModConfig.API_TEXT_CONTEXT_HUD_CAPTIONS.set(hudCaptions);
        ModConfig.API_TEXT_CONTEXT_HUD_PROGRESS.set(hudProgress);
        ModConfig.API_TEXT_CONTEXT_ENTITY_NAME.set(entityName);
        ModConfig.API_TEXT_CONTEXT_TEXT_DISPLAY.set(textDisplay);
        String current = signature();
        if (!current.equals(savedSignature)) {
            SimpleTranslateMod.onTextContextSettingsChanged();
            savedSignature = current;
        }
    }

    private String signature() {
        return enabled + ":" + allowShared + ":" + receivedChat + ":" + sentChat + ":"
                + itemTooltip + ":" + hoverTooltip + ":" + book + ":" + sign + ":"
                + hudCaptions + ":" + hudProgress + ":"
                + entityName + ":" + textDisplay;
    }

    @Override
    protected void renderAboveScrollableContentBeforeBottomActions(GuiGraphicsExtractor graphics,
                                                                   int mouseX,
                                                                   int mouseY,
                                                                   float partialTick) {
        if (status != null && !status.getString().isBlank()) {
            graphics.centeredText(this.font, status, this.width / 2, 29, 0xFF88FF88);
        }
    }
}
