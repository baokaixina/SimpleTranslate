package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.api.TokenUsage;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import com.yourname.simpletranslate.translation.TranslationEngine;
import net.minecraft.client.gui.GuiScreen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Session-only API usage monitor matching the baseline's diagnostics surface. */
final class TokenMonitorScreen extends ScrollableSettingsScreen {
    private static final int MAX_RECENT = 60;

    TokenMonitorScreen(GuiScreen parent, TranslationEngine engine) {
        super(parent, engine, "screen.simple_translate.token_monitor", "screen.simple_translate.main.usage");
    }

    @Override
    protected void buildContent() {
        addContentTextButton(100, 0, stateLabel("screen.simple_translate.token_monitor.enable",
                engine != null && engine.isTokenMonitorEnabled()), "screen.simple_translate.token_monitor.enable.tooltip");
        addContentButton(101, 26, "screen.simple_translate.token_monitor.clear", "screen.simple_translate.token_monitor.clear.tooltip");
        int entries = engine != null && engine.isTokenMonitorEnabled()
                ? Math.min(MAX_RECENT, TokenUsageMonitor.snapshot().size()) : 0;
        setContentHeight(126 + entries * 34);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        TokenUsageMonitor.Totals totals = TokenUsageMonitor.totals();
        drawContentText(tr("screen.simple_translate.token_monitor.totals"), 56, 0xAAAAAA);
        drawContentText(tr("screen.simple_translate.token_monitor.requests", format(totals.getRequestCount())), 69, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.token_monitor.prompt_tokens", format(totals.getPromptTokens())), 81, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.token_monitor.completion_tokens", format(totals.getCompletionTokens())), 93, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.token_monitor.total_tokens", format(totals.getTotalTokens())), 105, 0xFFFFFF);
        drawContentText(tr("screen.simple_translate.token_monitor.avg_time", totals.getAverageElapsedMs()), 117, 0xFFFFFF);
        if (engine == null || !engine.isTokenMonitorEnabled()) {
            drawContentText(tr("screen.simple_translate.token_monitor.disabled"), 139, 0xFFCC66);
            return;
        }
        List<TokenUsage> usage = TokenUsageMonitor.snapshot();
        if (usage.isEmpty()) {
            drawContentText(tr("screen.simple_translate.token_monitor.no_data"), 139, 0xAAAAAA);
            return;
        }
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        int y = 139;
        for (int index = usage.size() - 1, rendered = 0; index >= 0 && rendered < MAX_RECENT; index--, rendered++) {
            TokenUsage entry = usage.get(index);
            drawContentText(clock.format(new Date(entry.getTimestampMillis())) + "  " + trim(entry.getSurface(), 26), y, 0x77DD77);
            drawContentText(entry.getModel() + "  P:"+entry.getPromptTokens()+" C:"+entry.getCompletionTokens()
                    +" T:"+entry.getTotalTokens()+" "+entry.getElapsedMs()+"ms", y + 12, 0xDDDDDD);
            y += 34;
        }
    }

    @Override
    protected boolean onContentButton(int id) {
        if (id == 100 && engine != null) {
            engine.setTokenMonitorEnabled(!engine.isTokenMonitorEnabled());
            return true;
        }
        if (id == 101) {
            TokenUsageMonitor.clear();
            return true;
        }
        return false;
    }

    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value == null ? "?" : value;
        return value.substring(0, Math.max(1, maximum - 3)) + "...";
    }
}
