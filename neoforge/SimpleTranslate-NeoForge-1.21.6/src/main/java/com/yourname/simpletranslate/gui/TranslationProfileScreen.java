package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.TranslationProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/** A deliberately small per-server/save prompt editor with explicit disk writes. */
public final class TranslationProfileScreen extends BaseSimpleTranslateScreen {
    private static final int PANEL_WIDTH = 420;
    private static final int INPUT_TOP = 76;

    private final Screen parent;
    private MultiLineEditBox descriptionInput;
    private Component status = Component.empty();

    public TranslationProfileScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.translation_profile"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        String profile = TranslationProfileManager.current();

        int panelWidth = Math.max(220, Math.min(PANEL_WIDTH, this.width - 32));
        int left = this.width / 2 - panelWidth / 2;

        int inputHeight = Math.max(64, this.height - INPUT_TOP - 68);
        this.descriptionInput = new MultiLineEditBox.Builder()
                .setX(left)
                .setY(INPUT_TOP)
                .build(this.font, panelWidth, inputHeight,
                        Component.translatable("screen.simple_translate.translation_profile.context"));
        this.descriptionInput.setCharacterLimit(2_000);
        this.descriptionInput.setValue(profile);
        withTooltip(this.descriptionInput, "screen.simple_translate.translation_profile.context.tooltip");
        this.addRenderableWidget(this.descriptionInput);

        int gap = 6;
        int buttonWidth = (panelWidth - gap * 2) / 3;
        int buttonY = this.height - 28;
        Button saveButton = Button.builder(Component.translatable("screen.simple_translate.save"),
                        button -> saveProfile())
                .bounds(left, buttonY, buttonWidth, 20)
                .build();
        withTooltip(saveButton, "screen.simple_translate.translation_profile.save.tooltip");
        this.addRenderableWidget(saveButton);

        Button resetButton = Button.builder(
                        Component.translatable("screen.simple_translate.translation_profile.reset"),
                        button -> resetProfile())
                .bounds(left + buttonWidth + gap, buttonY, buttonWidth, 20)
                .build();
        withTooltip(resetButton, "screen.simple_translate.translation_profile.reset.tooltip");
        this.addRenderableWidget(resetButton);

        Button backButton = Button.builder(Component.translatable("screen.simple_translate.back"),
                        button -> onClose())
                .bounds(left + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20)
                .build();
        withTooltip(backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(backButton);
    }

    private void saveProfile() {
        TranslationProfileManager.saveCurrent(this.descriptionInput.getValue());
        SimpleTranslateMod.onTranslationProfileChanged();
        String saved = TranslationProfileManager.current();
        if (!saved.equals(this.descriptionInput.getValue())) {
            this.descriptionInput.setValue(saved);
        }
        this.status = Component.translatable("screen.simple_translate.translation_profile.saved");
    }

    private void resetProfile() {
        TranslationProfileManager.resetCurrent();
        SimpleTranslateMod.onTranslationProfileChanged();
        this.descriptionInput.setValue("");
        this.rebuildWidgets();
        this.status = Component.translatable("screen.simple_translate.translation_profile.reset_done");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, scopeLabel(), this.width / 2, 34, 0xFFAAAAAA);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.simple_translate.translation_profile.description"),
                this.width / 2, 49, 0xFFDDDDDD);

        int panelWidth = Math.max(220, Math.min(PANEL_WIDTH, this.width - 32));
        int left = this.width / 2 - panelWidth / 2;
        graphics.drawString(this.font,
                Component.translatable("screen.simple_translate.translation_profile.context"),
                left, INPUT_TOP - 13, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.descriptionInput != null && this.descriptionInput.getValue().isBlank()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.simple_translate.translation_profile.context.hint"),
                    left + 5, INPUT_TOP + 5, 0xFF777777);
        }
        if (this.status != null && !this.status.getString().isBlank()) {
            graphics.drawCenteredString(this.font, this.status, this.width / 2, this.height - 42, 0xFF88FF88);
        }
    }

    private static Component scopeLabel() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            String name = server.name == null || server.name.isBlank() ? server.ip : server.name;
            return Component.translatable("screen.simple_translate.translation_profile.scope.server", name);
        }
        if (minecraft.getSingleplayerServer() != null) {
            return Component.translatable("screen.simple_translate.translation_profile.scope.world",
                    minecraft.getSingleplayerServer().getWorldData().getLevelName());
        }
        return Component.translatable("screen.simple_translate.translation_profile.scope.global");
    }

    @Override
    public void onClose() {
        // Treat Back/Esc as a normal settings close: players commonly type a
        // profile and leave the screen without noticing the separate Save
        // button. Persist only when the normalized scope profile changed.
        if (this.descriptionInput != null) {
            String current = TranslationProfileManager.current();
            String description = this.descriptionInput.getValue();
            if (!current.equals(description.trim())) {
                TranslationProfileManager.saveCurrent(description);
                SimpleTranslateMod.onTranslationProfileChanged();
            }
        }
        Minecraft.getInstance().setScreen(this.parent);
    }
}
