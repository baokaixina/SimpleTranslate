package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic SimpleTranslate settings menu.
 *
 * The visual structure follows the original centered, sectioned list while all
 * current settings remain reachable through the maintained detail screens.
 */
public class SimpleTranslateScreen extends BaseSimpleTranslateScreen {
    private static final int VIEWPORT_TOP = 34;
    private static final int BOTTOM_BAR_HEIGHT = 35;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 26;

    private final Screen parent;
    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final List<Integer> widgetBaseY = new ArrayList<>();
    private final List<SectionLabel> sectionLabels = new ArrayList<>();

    private double scrollOffset;
    private int contentHeight;
    private Button backButton;

    public SimpleTranslateScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.scrollOffset = 0;
        this.scrollableWidgets.clear();
        this.widgetBaseY.clear();
        this.sectionLabels.clear();

        int centerX = this.width / 2;
        int buttonWidth = Math.max(180, Math.min(300, this.width - 40));
        int y = 48;

        y = addSection(y, "screen.simple_translate.settings.section.general");
        CycleButton<Boolean> globalButton = CycleButton.onOffBuilder(ModConfig.GLOBAL_ENABLED.get())
                .create(centerX - buttonWidth / 2, y, buttonWidth, BUTTON_HEIGHT,
                        Component.translatable("screen.simple_translate.settings.global_enabled"),
                        (button, enabled) -> {
                            ModConfig.GLOBAL_ENABLED.set(enabled);
                            SimpleTranslateMod.onGlobalTranslationSettingChanged(enabled);
                            ModConfig.save();
                        });
        withTooltip(globalButton, "screen.simple_translate.settings.global_enabled.tooltip");
        addScrollable(globalButton, y);
        y += SPACING + 10;

        y = addSection(y, "screen.simple_translate.section.api");
        y = addPageButton(y, buttonWidth, "screen.simple_translate.model_settings",
                "screen.simple_translate.model_settings.tooltip", () -> new ModelSettingsScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.language_settings",
                "screen.simple_translate.language_settings.tooltip", () -> new LanguageSettingsScreen(this));
        y += 10;

        y = addSection(y, "screen.simple_translate.section.features");
        y = addPageButton(y, buttonWidth, "screen.simple_translate.chat_translation",
                "screen.simple_translate.chat_translation.tooltip", () -> new ChatTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.book_translation",
                "screen.simple_translate.book_translation.tooltip", () -> new BookTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.item_tooltip_translation",
                "screen.simple_translate.item_tooltip_translation.tooltip", () -> new ItemTooltipScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.tooltip_glow.feature",
                "screen.simple_translate.tooltip_glow.feature.tooltip", () -> new TooltipGlowSettingsScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.hud_translation",
                "screen.simple_translate.hud_translation.tooltip", () -> new HudTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.sign_translation",
                "screen.simple_translate.sign_translation.tooltip", () -> new SignTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.advancement_translation",
                "screen.simple_translate.advancement_translation.tooltip",
                () -> new AdvancementTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.entity_translation",
                "screen.simple_translate.entity_translation.tooltip", () -> new EntityNameTranslationScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.text_display_translation",
                "screen.simple_translate.text_display_translation.tooltip",
                () -> new TextDisplayTranslationScreen(this));
        y += 10;

        y = addSection(y, "screen.simple_translate.section.management");
        y = addPageButton(y, buttonWidth, "screen.simple_translate.term_manager",
                "screen.simple_translate.term_manager.tooltip", () -> new TermManagerScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.blacklist_manager",
                "screen.simple_translate.blacklist_manager.tooltip", () -> new BlacklistManagerScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.cache_manager",
                "screen.simple_translate.cache_manager.tooltip", () -> new CacheManagerScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.hold_original",
                "screen.simple_translate.hold_original.tooltip", () -> new HoldOriginalScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.settings.section.advanced_api",
                "screen.simple_translate.settings.request_scheduler.tooltip",
                () -> new RequestSchedulingScreen(this));
        y = addPageButton(y, buttonWidth, "screen.simple_translate.token_monitor",
                "screen.simple_translate.token_monitor.tooltip",
                () -> new TokenMonitorScreen(this));

        this.contentHeight = y + 12;
        this.backButton = Button.builder(Component.translatable("screen.simple_translate.back"), button -> onClose())
                .bounds(centerX - buttonWidth / 2, this.height - 25, buttonWidth, BUTTON_HEIGHT)
                .build();
        withTooltip(this.backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(this.backButton);
        repositionWidgets();
    }

    private int addSection(int y, String key) {
        this.sectionLabels.add(new SectionLabel(y, key));
        return y + 18;
    }

    private int addPageButton(int y, int width, String labelKey, String tooltipKey, ScreenFactory factory) {
        Button button = Button.builder(Component.translatable(labelKey),
                        ignored -> Minecraft.getInstance().setScreen(factory.create()))
                .bounds(this.width / 2 - width / 2, y, width, BUTTON_HEIGHT)
                .build();
        withTooltip(button, tooltipKey);
        addScrollable(button, y);
        return y + SPACING;
    }

    private void addScrollable(AbstractWidget widget, int baseY) {
        this.scrollableWidgets.add(widget);
        this.widgetBaseY.add(baseY);
        this.addRenderableWidget(widget);
    }

    private void repositionWidgets() {
        int bottom = contentBottom();
        for (int i = 0; i < this.scrollableWidgets.size(); i++) {
            AbstractWidget widget = this.scrollableWidgets.get(i);
            int y = this.widgetBaseY.get(i) - (int) this.scrollOffset;
            widget.setY(y);
            boolean visible = y >= VIEWPORT_TOP && y + widget.getHeight() <= bottom;
            widget.visible = visible;
            widget.active = visible;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        boolean ready = SimpleTranslateMod.getTranslationManager() != null
                && SimpleTranslateMod.getTranslationManager().isReady();
        graphics.drawString(this.font,
                Component.translatable(ready
                        ? "screen.simple_translate.status.ready"
                        : "screen.simple_translate.status.not_configured"),
                Math.min(this.width - 72, this.width / 2 + 112), 12,
                ready ? 0x55FF55 : 0xFF5555);

        String recentError = com.yourname.simpletranslate.transport.TranslationRequestQueue.getRecentErrorStatus();
        if (recentError != null) {
            int errorX = Math.min(this.width - 72, this.width / 2 + 112);
            int errorY = 24;
            graphics.drawString(this.font,
                    Component.translatable("screen.simple_translate.status.api_error"),
                    errorX, errorY, 0xFFAA00);
        }

        graphics.enableScissor(0, VIEWPORT_TOP, this.width, contentBottom());
        int labelX = Math.max(8, this.width / 2 - Math.min(150, (this.width - 40) / 2));
        for (SectionLabel label : this.sectionLabels) {
            int y = label.baseY - (int) this.scrollOffset;
            if (y >= VIEWPORT_TOP - 10 && y < contentBottom()) {
                graphics.drawString(this.font, Component.translatable(label.key), labelX, y + 4, 0xAAAAAA);
            }
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            drawScrollBar(graphics);
        }
        drawBottomBar(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBottomBar(GuiGraphics graphics) {
        int width = Math.max(180, Math.min(300, this.width - 40));
        int left = this.width / 2 - width / 2 - 8;
        int right = this.width / 2 + width / 2 + 8;
        graphics.fill(left, contentBottom(), right, this.height - 2, 0xAA101010);
        graphics.fill(left, contentBottom(), right, contentBottom() + 1, 0x55FFFFFF);
    }

    private void drawScrollBar(GuiGraphics graphics) {
        int width = Math.max(180, Math.min(300, this.width - 40));
        int x = Math.min(this.width - 6, this.width / 2 + width / 2 + 8);
        int height = contentBottom() - VIEWPORT_TOP;
        graphics.fill(x, VIEWPORT_TOP, x + 4, contentBottom(), 0x33FFFFFF);
        int handleHeight = Math.max(20, height * height / Math.max(height, this.contentHeight));
        int handleY = VIEWPORT_TOP + (int) ((height - handleHeight) * (this.scrollOffset / maxScroll()));
        graphics.fill(x, handleY, x + 4, handleY + handleHeight, 0xAAFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0 && mouseY < contentBottom()) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset - delta * 24));
            repositionWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int contentBottom() {
        return this.height - BOTTOM_BAR_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - (contentBottom() - VIEWPORT_TOP));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record SectionLabel(int baseY, String key) {
    }

    @FunctionalInterface
    private interface ScreenFactory {
        Screen create();
    }
}
