package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Baseline-style, sectioned root settings menu for Forge 1.12.2. */
public final class SimpleTranslateScreen extends ScrollableSettingsScreen {
    private static final int ROW_GAP = 26;
    private static final Map<Section, Boolean> EXPANDED = new EnumMap<Section, Boolean>(Section.class);

    static {
        for (Section section : Section.values()) EXPANDED.put(section, Boolean.valueOf(section == Section.ACCESS));
    }

    private final List<SubgroupLabel> subgroupLabels = new ArrayList<SubgroupLabel>();

    public SimpleTranslateScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.settings", "screen.simple_translate.main.subtitle");
    }

    @Override
    protected String bottomActionKey() {
        return parent == null ? "gui.done" : "screen.simple_translate.back";
    }

    @Override
    protected void buildContent() {
        subgroupLabels.clear();
        int y = 0;
        y = addHeader(y, Section.GENERAL);
        if (expanded(Section.GENERAL)) {
            addContentTextButton(100, y, stateLabel("screen.simple_translate.settings.global_enabled",
                    ModConfig.GLOBAL_ENABLED.get()), "screen.simple_translate.settings.global_enabled.tooltip");
            y += ROW_GAP;
        }
        y += 8;

        y = addHeader(y, Section.ACCESS);
        if (expanded(Section.ACCESS)) {
            y = addPage(y, 201, "screen.simple_translate.main.translation_api", "screen.simple_translate.model_settings.tooltip");
            y = addPage(y, 202, "screen.simple_translate.main.language", "screen.simple_translate.language_settings.tooltip");
        }
        y += 8;

        y = addHeader(y, Section.SCOPE);
        if (expanded(Section.SCOPE)) {
            y = addSubgroup(y, "screen.simple_translate.main.group.chat_reading");
            y = addPage(y, 203, "screen.simple_translate.main.chat", "screen.simple_translate.chat_translation.tooltip");
            y = addPage(y, 204, "screen.simple_translate.main.book", "screen.simple_translate.book_translation.tooltip");
            y = addSubgroup(y, "screen.simple_translate.main.group.items_menus");
            y = addPage(y, 205, "screen.simple_translate.main.item_tooltip", "screen.simple_translate.item_tooltip_translation.tooltip");
            y = addPage(y, 206, "screen.simple_translate.main.gui", "screen.simple_translate.gui_translation.tooltip");
            y = addPage(y, 207, "screen.simple_translate.main.advancement", "screen.simple_translate.advancement_translation.tooltip");
            y = addSubgroup(y, "screen.simple_translate.main.group.screen");
            y = addPage(y, 208, "screen.simple_translate.main.hud", "screen.simple_translate.hud_translation.tooltip");
            y = addSubgroup(y, "screen.simple_translate.main.group.world_text");
            y = addPage(y, 209, "screen.simple_translate.main.sign", "screen.simple_translate.sign_translation.tooltip");
            y = addPage(y, 210, "screen.simple_translate.main.entity", "screen.simple_translate.entity_translation.tooltip");
        }
        y += 8;

        y = addHeader(y, Section.PROMPTS);
        if (expanded(Section.PROMPTS)) {
            y = addPage(y, 211, "screen.simple_translate.main.reference_prompt", "screen.simple_translate.translation_profile.tooltip");
            y = addPage(y, 212, "screen.simple_translate.main.terms", "screen.simple_translate.term_manager.tooltip");
            y = addPage(y, 213, "screen.simple_translate.main.blacklist", "screen.simple_translate.blacklist_manager.tooltip");
            y = addPage(y, 214, "screen.simple_translate.main.history", "screen.simple_translate.text_context.tooltip");
            y = addPage(y, 215, "screen.simple_translate.main.cache", "screen.simple_translate.cache_manager.tooltip");
        }
        y += 8;

        y = addHeader(y, Section.OPERATION);
        if (expanded(Section.OPERATION)) {
            y = addPage(y, 216, "screen.simple_translate.main.shortcuts", "screen.simple_translate.shortcuts.tooltip");
            y = addPage(y, 217, "screen.simple_translate.main.glow", "screen.simple_translate.tooltip_glow.feature.tooltip");
        }
        y += 8;

        y = addHeader(y, Section.ADVANCED);
        if (expanded(Section.ADVANCED)) {
            y = addPage(y, 219, "screen.simple_translate.main.speed", "screen.simple_translate.translation_speed.tooltip");
            y = addPage(y, 220, "screen.simple_translate.main.usage", "screen.simple_translate.token_monitor.tooltip");
        }
        setContentHeight(y + 10);
    }

    private int addHeader(int y, Section section) {
        addContentWidget(new SectionHeaderButton(20 + section.ordinal(), contentLeft, 0, contentWidth, section), y);
        return y + 22;
    }

    private int addPage(int y, int id, String label, String tooltip) {
        addContentButton(id, y, label, tooltip);
        return y + ROW_GAP;
    }

    private int addSubgroup(int y, String key) {
        subgroupLabels.add(new SubgroupLabel(y, key));
        return y + 16;
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        boolean configured = engine != null && engine.isConfigured();
        String status = tr(configured ? "screen.simple_translate.status.ready"
                : "screen.simple_translate.status.not_configured");
        drawString(fontRenderer, status, Math.max(4, width - fontRenderer.getStringWidth(status) - 8), 8,
                configured ? 0x55FF55 : 0xFF5555);
        String recentError = engine == null ? null : engine.getRecentRequestErrorStatus();
        if (recentError != null) {
            String error = tr("screen.simple_translate.status.api_error");
            drawString(fontRenderer, error,
                    Math.max(4, width - fontRenderer.getStringWidth(error) - 8), 21, 0xFFAA00);
        }
        for (SubgroupLabel label : subgroupLabels) {
            int screenY = 46 + label.baseY - scrollOffset;
            if (screenY >= 46 && screenY + fontRenderer.FONT_HEIGHT <= viewportBottom) {
                drawCenteredString(fontRenderer, tr(label.key), width / 2, screenY + 3, 0x888888);
            }
        }
    }

    @Override
    protected boolean onContentButton(int id) {
        if (id >= 20 && id < 20 + Section.values().length) {
            Section section = Section.values()[id - 20];
            EXPANDED.put(section, Boolean.valueOf(!expanded(section)));
            return true;
        }
        if (id == 100 && engine != null) {
            boolean enabled = !ModConfig.GLOBAL_ENABLED.get();
            ModConfig.GLOBAL_ENABLED.set(enabled);
            ModConfig.save();
            SimpleTranslateForge1122.onGlobalTranslationSettingChanged(enabled);
            return true;
        }
        GuiScreen next = createPage(id);
        if (next != null) {
            Minecraft.getMinecraft().displayGuiScreen(next);
        }
        return false;
    }

    private GuiScreen createPage(int id) {
        switch (id) {
            case 201: return new ServiceSettingsScreen(this, engine);
            case 202: return new LanguageSettingsScreen(this, engine);
            case 203: return new ChatTranslationScreen(this, engine);
            case 204: return new BookTranslationScreen(this, engine);
            case 205: return new ItemTooltipScreen(this, engine);
            case 206: return new GuiTranslationScreen(this, engine);
            case 207: return new AdvancementTranslationScreen(this, engine);
            case 208: return new HudTranslationScreen(this, engine);
            case 209: return new SignTranslationScreen(this, engine);
            case 210: return new EntityNameTranslationScreen(this, engine);
            case 211: return new TranslationProfileScreen(this, engine);
            case 212: return new TermManagerScreen(this, engine);
            case 213: return new BlacklistManagerScreen(this, engine);
            case 214: return new TextContextSettingsScreen(this, engine);
            case 215: return new CacheManagerScreen(this, engine);
            case 216: return new ShortcutSettingsScreen(this, engine);
            case 217: return new TooltipGlowSettingsScreen(this, engine);
            case 219: return new TranslationSpeedScreen(this, engine);
            case 220: return new TokenMonitorScreen(this, engine);
            default: return null;
        }
    }

    private static boolean expanded(Section section) {
        Boolean result = EXPANDED.get(section);
        return result == null || result.booleanValue();
    }

    private enum Section {
        GENERAL("screen.simple_translate.main.section.general"),
        ACCESS("screen.simple_translate.main.section.access"),
        SCOPE("screen.simple_translate.main.section.scope"),
        PROMPTS("screen.simple_translate.main.section.prompts"),
        OPERATION("screen.simple_translate.main.section.operation"),
        ADVANCED("screen.simple_translate.main.section.advanced");

        private final String key;

        Section(String key) {
            this.key = key;
        }
    }

    private static final class SubgroupLabel {
        private final int baseY;
        private final String key;

        private SubgroupLabel(int baseY, String key) {
            this.baseY = baseY;
            this.key = key;
        }
    }

    /** High-version-style section header: plain arrow text, not another setting button. */
    private final class SectionHeaderButton extends GuiButton {
        private final Section section;

        private SectionHeaderButton(int id, int x, int y, int buttonWidth, Section section) {
            super(id, x, y, buttonWidth, 18, tr(section.key));
            this.section = section;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            boolean hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            if (hovered) drawRect(x, y, x + width, y + height, 0x33111111);
            String arrow = expanded(section) ? "\u25be " : "\u25b8 ";
            drawString(fontRenderer, arrow + displayString, x + 4, y + 5, hovered ? 0xFFFFFF : 0xE0E0E0);
        }
    }
}
