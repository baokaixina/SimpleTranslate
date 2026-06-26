package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.api.TokenUsage;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Token usage monitor screen. Shows session totals and a scrollable list of
 * recent API requests with per-request token breakdown.
 */
public class TokenMonitorScreen extends ScrollableSettingsScreen {

    private static final int RECENT_REQUEST_LIMIT = 60;

    public TokenMonitorScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.token_monitor"), parent);
        this.contentWidth = 330;
        this.entrySpacing = 10;
    }

    @Override
    protected void buildContent() {
        addSectionHeader(text("screen.simple_translate.token_monitor.enable"));
        CycleButton<Boolean> enableToggle = CycleButton.onOffBuilder(ModConfig.TOKEN_MONITOR_ENABLED.get())
                .create(0, 0, contentWidth, 20,
                        Component.translatable("screen.simple_translate.token_monitor.enable"),
                        (button, value) -> ModConfig.TOKEN_MONITOR_ENABLED.set(value));
        withTooltip(enableToggle, "screen.simple_translate.token_monitor.enable.tooltip");
        addEntry(enableToggle);

        addSectionHeader(text("screen.simple_translate.token_monitor.totals"));
        addEntry(new InfoCardWidget(
                Component.translatable("screen.simple_translate.token_monitor.totals"),
                buildTotalsLines(),
                0xFF62D5FF,
                contentWidth,
                92));

        addSectionHeader(text("screen.simple_translate.token_monitor.recent"));

        if (!ModConfig.TOKEN_MONITOR_ENABLED.get()) {
            addEntry(new InfoCardWidget(
                    Component.translatable("screen.simple_translate.token_monitor.disabled"),
                    List.of(text("screen.simple_translate.token_monitor.enable.tooltip")),
                    0xFFFFC857,
                    contentWidth,
                    58));
            return;
        }

        List<TokenUsage> snapshot = TokenUsageMonitor.snapshot();
        if (snapshot.isEmpty()) {
            addEntry(new InfoCardWidget(
                    Component.translatable("screen.simple_translate.token_monitor.no_data"),
                    List.of(""),
                    0xFF9E9E9E,
                    contentWidth,
                    46));
        } else {
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
            for (int i = snapshot.size() - 1; i >= 0 && i >= snapshot.size() - RECENT_REQUEST_LIMIT; i--) {
                TokenUsage usage = snapshot.get(i);
                String time = timeFormat.format(new Date(usage.timestampMillis()));
                String title = time + "  " + shortenSurface(usage.surface());
                List<String> lines = List.of(
                        usage.model(),
                        Component.translatable("screen.simple_translate.token_monitor.prompt_tokens",
                                String.format("%,d", usage.promptTokens())).getString()
                                + "   "
                                + Component.translatable("screen.simple_translate.token_monitor.completion_tokens",
                                String.format("%,d", usage.completionTokens())).getString(),
                        Component.translatable("screen.simple_translate.token_monitor.total_tokens",
                                String.format("%,d", usage.totalTokens())).getString()
                                + "   " + usage.elapsedMs() + "ms");
                addEntry(new InfoCardWidget(Component.literal(title), lines, 0xFF7CFFB2, contentWidth, 64));
            }
        }

        Button clearButton = Button.builder(
                        Component.translatable("screen.simple_translate.token_monitor.clear"),
                        button -> {
                            TokenUsageMonitor.clear();
                            this.rebuild();
                        })
                .bounds(0, 0, contentWidth, 20)
                .build();
        addEntry(clearButton);
    }

    private void rebuild() {
        this.init();
    }

    private List<String> buildTotalsLines() {
        TokenUsageMonitor.Totals totals = TokenUsageMonitor.totals();
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.simple_translate.token_monitor.requests", totals.requestCount()).getString());
        lines.add(Component.translatable("screen.simple_translate.token_monitor.prompt_tokens",
                String.format("%,d", totals.promptTokens())).getString());
        lines.add(Component.translatable("screen.simple_translate.token_monitor.completion_tokens",
                String.format("%,d", totals.completionTokens())).getString());
        lines.add(Component.translatable("screen.simple_translate.token_monitor.total_tokens",
                String.format("%,d", totals.totalTokens())).getString());
        lines.add(Component.translatable("screen.simple_translate.token_monitor.avg_time", totals.avgElapsedMs()).getString());
        return lines;
    }

    private static String shortenSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return "?";
        }
        if (surface.length() <= 30) {
            return surface;
        }
        return surface.substring(0, 27) + "...";
    }

    @Override
    protected void saveSettings() {
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }

    private static final class InfoCardWidget extends AbstractWidget {
        private final Component heading;
        private final List<String> lines;
        private final int accentColor;

        private InfoCardWidget(Component heading, List<String> lines, int accentColor, int width, int height) {
            super(0, 0, width, height, Component.empty());
            this.heading = heading == null ? Component.empty() : heading;
            this.lines = lines == null ? List.of() : List.copyOf(lines);
            this.accentColor = accentColor;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int left = getX();
            int top = getY();
            int right = left + getWidth();
            int bottom = top + getHeight();
            graphics.fill(left, top, right, bottom, 0xCC14171C);
            graphics.fill(left, top, left + 3, bottom, accentColor);
            graphics.fill(left + 3, top, right, top + 1, 0x44FFFFFF);

            int textLeft = left + 10;
            int textWidth = Math.max(20, getWidth() - 18);
            graphics.drawString(font, trim(font, heading.getString(), textWidth), textLeft, top + 7, 0xFFFFFFFF, false);
            int y = top + 22;
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (y + 9 > bottom - 5) {
                    break;
                }
                graphics.drawString(font, trim(font, line, textWidth), textLeft, y, 0xFFD6D9DE, false);
                y += 11;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, heading);
        }

        private static String trim(Font font, String text, int width) {
            if (text == null || text.isEmpty() || font.width(text) <= width) {
                return text == null ? "" : text;
            }
            return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
        }
    }
}
