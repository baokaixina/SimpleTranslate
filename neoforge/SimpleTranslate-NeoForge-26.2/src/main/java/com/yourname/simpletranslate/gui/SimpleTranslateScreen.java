package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Main settings menu grouped by player-facing purpose. */
public class SimpleTranslateScreen extends BaseSimpleTranslateScreen {
    private static final int VIEWPORT_TOP = 45;
    private static final int BOTTOM_BAR_HEIGHT = 35;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 26;
    private static final Map<Section, Boolean> EXPANDED = new EnumMap<>(Section.class);

    static {
        for (Section section : Section.values()) {
            EXPANDED.put(section, section == Section.ACCESS);
        }
    }

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
        double restoreScroll = this.scrollOffset;
        super.init();
        this.scrollableWidgets.clear();
        this.widgetBaseY.clear();
        this.sectionLabels.clear();

        int buttonWidth = Math.max(200, Math.min(300, this.width - 40));
        int y = VIEWPORT_TOP + 3;

        y = addSectionHeader(y, buttonWidth, Section.GENERAL);
        if (isExpanded(Section.GENERAL)) {
            CycleButton<Boolean> globalButton = CycleButton.onOffBuilder(ModConfig.GLOBAL_ENABLED.get())
                    .create(this.width / 2 - buttonWidth / 2, y, buttonWidth, BUTTON_HEIGHT,
                            Component.translatable("screen.simple_translate.settings.global_enabled"),
                            (button, enabled) -> {
                                ModConfig.GLOBAL_ENABLED.set(enabled);
                                SimpleTranslateMod.onGlobalTranslationSettingChanged(enabled);
                                ModConfig.save();
                            });
            withTooltip(globalButton, "screen.simple_translate.settings.global_enabled.tooltip");
            addScrollable(globalButton, y);
            y += SPACING;
        }
        y += 8;

        y = addSectionHeader(y, buttonWidth, Section.ACCESS);
        if (isExpanded(Section.ACCESS)) {
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.translation_api",
                    "screen.simple_translate.model_settings.tooltip", () -> new ModelSettingsScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.language",
                    "screen.simple_translate.language_settings.tooltip", () -> new LanguageSettingsScreen(this));
        }
        y += 8;

        y = addSectionHeader(y, buttonWidth, Section.SCOPE);
        if (isExpanded(Section.SCOPE)) {
            y = addSubgroup(y, "screen.simple_translate.main.group.chat_reading");
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.chat",
                    "screen.simple_translate.chat_translation.tooltip", () -> new ChatTranslationScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.book",
                    "screen.simple_translate.book_translation.tooltip", () -> new BookTranslationScreen(this));

            y = addSubgroup(y, "screen.simple_translate.main.group.items_menus");
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.item_tooltip",
                    "screen.simple_translate.item_tooltip_translation.tooltip", () -> new ItemTooltipScreen(this),
                    shortcutHint(net.minecraft.ChatFormatting.GRAY,
                            com.yourname.simpletranslate.keybind.ShortcutAction.TRANSLATE_TOOLTIP));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.gui",
                    "screen.simple_translate.gui_translation.tooltip", () -> new GuiTranslationScreen(this),
                    shortcutHint(net.minecraft.ChatFormatting.GRAY,
                            com.yourname.simpletranslate.keybind.ShortcutAction.TRANSLATE_GUI));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.advancement",
                    "screen.simple_translate.advancement_translation.tooltip",
                    () -> new AdvancementTranslationScreen(this));

            y = addSubgroup(y, "screen.simple_translate.main.group.screen");
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.hud",
                    "screen.simple_translate.hud_translation.tooltip", () -> new HudTranslationScreen(this));

            y = addSubgroup(y, "screen.simple_translate.main.group.world_text");
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.sign",
                    "screen.simple_translate.sign_translation.tooltip", () -> new SignTranslationScreen(this),
                    shortcutHint(net.minecraft.ChatFormatting.GRAY,
                            com.yourname.simpletranslate.keybind.ShortcutAction.SIGN_SELECT,
                            com.yourname.simpletranslate.keybind.ShortcutAction.SIGN_SUBMIT));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.entity",
                    "screen.simple_translate.entity_translation.tooltip", () -> new EntityNameTranslationScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.text_display",
                    "screen.simple_translate.text_display_translation.tooltip",
                    () -> new TextDisplayTranslationScreen(this));
        }
        y += 8;

        y = addSectionHeader(y, buttonWidth, Section.PROMPTS);
        if (isExpanded(Section.PROMPTS)) {
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.reference_prompt",
                    "screen.simple_translate.translation_profile.tooltip", () -> new TranslationProfileScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.terms",
                    "screen.simple_translate.term_manager.tooltip", () -> new TermManagerScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.blacklist",
                    "screen.simple_translate.blacklist_manager.tooltip", () -> new BlacklistManagerScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.history",
                    "screen.simple_translate.text_context.tooltip", () -> new TextContextSettingsScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.cache",
                    "screen.simple_translate.cache_manager.tooltip", () -> new CacheManagerScreen(this));
        }
        y += 8;

        y = addSectionHeader(y, buttonWidth, Section.OPERATION);
        if (isExpanded(Section.OPERATION)) {
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.shortcuts",
                    "screen.simple_translate.shortcuts.tooltip", () -> new ShortcutSettingsScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.glow",
                    "screen.simple_translate.tooltip_glow.feature.tooltip", () -> new TooltipGlowSettingsScreen(this));
        }
        y += 8;

        y = addSectionHeader(y, buttonWidth, Section.ADVANCED);
        if (isExpanded(Section.ADVANCED)) {
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.speed",
                    "screen.simple_translate.translation_speed.tooltip", () -> new TranslationSpeedScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.display_compatibility",
                    "screen.simple_translate.display_compatibility.tooltip",
                    () -> new DisplayCompatibilityScreen(this));
            y = addPageButton(y, buttonWidth, "screen.simple_translate.main.usage",
                    "screen.simple_translate.token_monitor.tooltip", () -> new TokenMonitorScreen(this));
        }

        this.contentHeight = y + 12;
        this.scrollOffset = Math.max(0, Math.min(maxScroll(), restoreScroll));
        this.backButton = new HoverHighlightButton(this.width / 2 - buttonWidth / 2, this.height - 25,
                buttonWidth, BUTTON_HEIGHT, Component.translatable("screen.simple_translate.back"),
                button -> onClose());
        withTooltip(this.backButton, "screen.simple_translate.back.tooltip");
        this.addRenderableWidget(this.backButton);
        repositionWidgets();
    }

    private int addSectionHeader(int y, int width, Section section) {
        SectionHeaderWidget header = new SectionHeaderWidget(section, width);
        addScrollable(header, y);
        return y + 22;
    }

    private int addSubgroup(int y, String key) {
        this.sectionLabels.add(new SectionLabel(y, key));
        return y + 16;
    }

    private int addPageButton(int y, int width, String labelKey, String tooltipKey, ScreenFactory factory) {
        return addPageButton(y, width, labelKey, tooltipKey, factory, null);
    }

    private int addPageButton(int y, int width, String labelKey, String tooltipKey,
                              ScreenFactory factory, Component shortcutSuffix) {
        Button button = new HoverHighlightButton(this.width / 2 - width / 2, y, width, BUTTON_HEIGHT,
                Component.translatable(labelKey), ignored -> Minecraft.getInstance().gui.setScreen(factory.create()));
        if (shortcutSuffix == null) {
            withTooltip(button, tooltipKey);
        } else {
            withTooltip(button, Component.translatable(tooltipKey)
                    .append(Component.literal("\n")).append(shortcutSuffix));
        }
        addScrollable(button, y);
        return y + SPACING;
    }

    private static Component shortcutHint(net.minecraft.ChatFormatting formatting,
                                          com.yourname.simpletranslate.keybind.ShortcutAction... actions) {
        MutableComponent hint = Component.translatable("screen.simple_translate.shortcuts.hint_prefix")
                .withStyle(formatting);
        for (int index = 0; index < actions.length; index++) {
            if (index > 0) {
                hint.append(Component.literal("/").withStyle(formatting));
            }
            hint.append(actions[index].chord().displayName());
        }
        return hint;
    }

    private void addScrollable(AbstractWidget widget, int baseY) {
        this.scrollableWidgets.add(widget);
        this.widgetBaseY.add(baseY);
        this.addRenderableWidget(widget);
    }

    private void toggleSection(Section section) {
        EXPANDED.put(section, !isExpanded(section));
        this.rebuildWidgets();
    }

    private static boolean isExpanded(Section section) {
        return EXPANDED.getOrDefault(section, true);
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("screen.simple_translate.main.subtitle"),
                this.width / 2, 24, 0xFFAAAAAA);

        boolean ready = SimpleTranslateMod.getTranslationManager() != null
                && SimpleTranslateMod.getTranslationManager().isReady();
        Component statusText = Component.translatable(ready
                ? "screen.simple_translate.status.ready"
                : "screen.simple_translate.status.not_configured");
        graphics.text(this.font, statusText,
                Math.max(4, this.width - this.font.width(statusText) - 8), 8,
                ready ? 0xFF55FF55 : 0xFFFF5555);

        String recentError = com.yourname.simpletranslate.transport.TranslationRequestQueue.getRecentErrorStatus();
        if (recentError != null) {
            Component error = Component.translatable("screen.simple_translate.status.api_error");
            graphics.text(this.font, error,
                    Math.max(4, this.width - this.font.width(error) - 8), 21, 0xFFFFAA00);
        }

        graphics.enableScissor(0, VIEWPORT_TOP, this.width, contentBottom());
        for (SectionLabel label : this.sectionLabels) {
            int y = label.baseY - (int) this.scrollOffset;
            if (y >= VIEWPORT_TOP - 10 && y < contentBottom()) {
                graphics.centeredText(this.font, Component.translatable(label.key),
                        this.width / 2, y + 3, 0xFF888888);
            }
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            drawScrollBar(graphics);
        }
        drawBottomBar(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBottomBar(GuiGraphicsExtractor graphics) {
        int width = Math.max(200, Math.min(300, this.width - 40));
        int left = this.width / 2 - width / 2 - 8;
        int right = this.width / 2 + width / 2 + 8;
        graphics.fill(left, contentBottom(), right, this.height - 2, 0xAA101010);
        graphics.fill(left, contentBottom(), right, contentBottom() + 1, 0x55FFFFFF);
    }

    private void drawScrollBar(GuiGraphicsExtractor graphics) {
        int width = Math.max(200, Math.min(300, this.width - 40));
        int x = Math.min(this.width - 6, this.width / 2 + width / 2 + 8);
        int height = contentBottom() - VIEWPORT_TOP;
        graphics.fill(x, VIEWPORT_TOP, x + 4, contentBottom(), 0x33FFFFFF);
        int handleHeight = Math.max(20, height * height / Math.max(height, this.contentHeight));
        int handleY = VIEWPORT_TOP + (int) ((height - handleHeight) * (this.scrollOffset / maxScroll()));
        graphics.fill(x, handleY, x + 4, handleY + handleHeight, 0xAAFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && mouseY < contentBottom()) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset - scrollY * 24));
            repositionWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int contentBottom() {
        return this.height - BOTTOM_BAR_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - (contentBottom() - VIEWPORT_TOP));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class SectionHeaderWidget extends AbstractWidget {
        private final Section section;

        private SectionHeaderWidget(Section section, int width) {
            super(SimpleTranslateScreen.this.width / 2 - width / 2, 0, width, 18,
                    Component.translatable(section.labelKey));
            this.section = section;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (isHoveredOrFocused()) {
                graphics.fill(getX(), getY(), getRight(), getBottom(), 0x33111111);
            }
            Component arrow = Component.literal(isExpanded(this.section) ? "\u25be " : "\u25b8 ");
            Component line = arrow.copy().append(getMessage());
            graphics.text(SimpleTranslateScreen.this.font, line,
                    getX() + 4, getY() + 5, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFE0E0E0, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            SimpleTranslateScreen.this.toggleSection(this.section);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private enum Section {
        GENERAL("screen.simple_translate.main.section.general"),
        ACCESS("screen.simple_translate.main.section.access"),
        SCOPE("screen.simple_translate.main.section.scope"),
        PROMPTS("screen.simple_translate.main.section.prompts"),
        OPERATION("screen.simple_translate.main.section.operation"),
        ADVANCED("screen.simple_translate.main.section.advanced");

        private final String labelKey;

        Section(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private record SectionLabel(int baseY, String key) {
    }

    @FunctionalInterface
    private interface ScreenFactory {
        Screen create();
    }
}
