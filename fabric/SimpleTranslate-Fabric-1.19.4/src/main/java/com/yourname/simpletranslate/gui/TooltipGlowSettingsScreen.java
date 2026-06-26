package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Function;

/** Player-configurable pending-translation glow with a live preview. */
public class TooltipGlowSettingsScreen extends ScrollableSettingsScreen {
    public TooltipGlowSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.tooltip_glow.feature"), parent);
        this.contentWidth = 300;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(Component.translatable("screen.simple_translate.tooltip_glow.section").getString());

        CycleButton<Boolean> enabled = CycleButton.onOffBuilder(ModConfig.TOOLTIP_GLOW_ENABLED.get())
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.tooltip_glow.enabled"),
                        (button, value) -> {
                            ModConfig.TOOLTIP_GLOW_ENABLED.set(value);
                            applyLiveSettings();
                        });
        withTooltip(enabled, "screen.simple_translate.tooltip_glow.enabled.tooltip");
        addEntry(enabled);

        addSlider(ModConfig.TOOLTIP_GLOW_LINE_WIDTH, 1, 6, 1,
                value -> text("screen.simple_translate.tooltip_glow.pixels", value),
                "screen.simple_translate.tooltip_glow.line_width.tooltip");
        addSlider(ModConfig.TOOLTIP_GLOW_SPREAD, 0, 12, 1,
                value -> text("screen.simple_translate.tooltip_glow.pixels", value),
                "screen.simple_translate.tooltip_glow.spread.tooltip");
        addSlider(ModConfig.TOOLTIP_GLOW_CYCLE_MS, 2000, 24000, 250,
                value -> text("screen.simple_translate.tooltip_glow.seconds_decimal",
                        String.format(Locale.ROOT, "%.2f", value / 1000.0D)),
                "screen.simple_translate.tooltip_glow.speed.tooltip");
        addSlider(ModConfig.TOOLTIP_GLOW_OPACITY, 20, 255, 1,
                value -> text("screen.simple_translate.tooltip_glow.percent",
                        Math.round(value * 100.0F / 255.0F)),
                "screen.simple_translate.tooltip_glow.opacity.tooltip");

        CycleButton<ModConfig.TooltipGlowTheme> theme = CycleButton
                .<ModConfig.TooltipGlowTheme>builder(value -> Component.translatable(
                        "screen.simple_translate.tooltip_glow.theme." + value.name().toLowerCase(Locale.ROOT)))
                .withValues(ModConfig.TooltipGlowTheme.values())
                .withInitialValue(ModConfig.TOOLTIP_GLOW_THEME.get())
                .create(0, 0, this.contentWidth, 20,
                        Component.translatable("screen.simple_translate.tooltip_glow.theme"),
                        (button, value) -> {
                            ModConfig.TOOLTIP_GLOW_THEME.set(value);
                            applyLiveSettings();
                        });
        withTooltip(theme, "screen.simple_translate.tooltip_glow.theme.tooltip");
        addEntry(theme);

        GlowPreviewWidget preview = new GlowPreviewWidget();
        withTooltip(preview, "screen.simple_translate.tooltip_glow.preview.tooltip");
        addEntry(preview);
    }

    private void addSlider(ModConfig.IntValue config, int min, int max, int step,
                           Function<Integer, String> label, String tooltipKey) {
        IntSliderWidget slider = new IntSliderWidget(config, min, max, step, label);
        withTooltip(slider, tooltipKey);
        addEntry(slider);
    }

    @Override
    protected void saveSettings() {
        // Slider and cycle callbacks write directly to ModConfig.
    }

    private String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private final class GlowPreviewWidget extends AbstractWidget {
        private GlowPreviewWidget() {
            super(0, 0, TooltipGlowSettingsScreen.this.contentWidth, 76,
                    Component.translatable("screen.simple_translate.tooltip_glow.preview"));
        }

        @Override
        public void renderWidget(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            GuiGraphics graphics = new GuiGraphics(poseStack);
            int left = getX() + 10;
            int top = getY() + 9;
            int right = getX() + getWidth() - 10;
            int bottom = getY() + getHeight() - 9;
            graphics.fill(left, top, right, bottom, 0xEE100010);
            TooltipTranslationGlowRenderer.renderPreview(graphics, left, top, right, bottom);
            graphics.drawString(TooltipGlowSettingsScreen.this.font,
                    Component.translatable("screen.simple_translate.tooltip_glow.preview.title"),
                    left + 8, top + 8, 0x72D9D0);
            graphics.drawString(TooltipGlowSettingsScreen.this.font,
                    Component.translatable("screen.simple_translate.tooltip_glow.preview.pending"),
                    left + 8, top + 24, 0xE0E0E0);
            graphics.drawString(TooltipGlowSettingsScreen.this.font,
                    Component.translatable("screen.simple_translate.tooltip_glow.preview.hint"),
                    left + 8, top + 38, 0x999999);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class IntSliderWidget extends AbstractSliderButton {
        private final ModConfig.IntValue config;
        private final int min;
        private final int max;
        private final int step;
        private final Function<Integer, String> label;

        private IntSliderWidget(ModConfig.IntValue config, int min, int max, int step,
                                Function<Integer, String> label) {
            super(0, 0, TooltipGlowSettingsScreen.this.contentWidth, 20, Component.empty(),
                    normalize(config.get(), min, max));
            this.config = config;
            this.min = min;
            this.max = max;
            this.step = Math.max(1, step);
            this.label = label;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(this.label.apply(currentValue())));
        }

        @Override
        protected void applyValue() {
            this.config.set(currentValue());
            updateMessage();
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            applyLiveSettings();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (handled) {
                applyLiveSettings();
            }
            return handled;
        }

        private int currentValue() {
            int raw = this.min + (int) Math.round(this.value * (this.max - this.min));
            int stepped = this.min + Math.round((raw - this.min) / (float) this.step) * this.step;
            return Math.max(this.min, Math.min(this.max, stepped));
        }

        private static double normalize(int value, int min, int max) {
            return max <= min ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, (value - min) / (double) (max - min)));
        }
    }
}
