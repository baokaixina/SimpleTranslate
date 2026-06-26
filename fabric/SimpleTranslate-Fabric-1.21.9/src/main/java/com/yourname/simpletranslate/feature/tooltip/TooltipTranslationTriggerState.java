package com.yourname.simpletranslate.feature.tooltip;
import com.yourname.simpletranslate.core.ComponentRenderSafety;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Controls whether a tooltip cache miss may start a model request. */
public final class TooltipTranslationTriggerState {
    private static final long SHORTCUT_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(750L);
    private static final long HOVER_DWELL_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);
    private static final long HOVER_CONTINUITY_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static long shortcutRequestExpiresAt;
    private static final HoverIntent ITEM_HOVER_INTENT = new HoverIntent();
    private static final HoverIntent CHAT_HOVER_INTENT = new HoverIntent();

    private TooltipTranslationTriggerState() {
    }

    public static void armShortcutRequest() {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            shortcutRequestExpiresAt = 0L;
            return;
        }
        clearHoverIntent();
        shortcutRequestExpiresAt = System.nanoTime() + SHORTCUT_WINDOW_NANOS;
    }

    public static void clearShortcutRequest() {
        shortcutRequestExpiresAt = 0L;
        clearHoverIntent();
    }

    public static boolean hasEnabledShortcutMode() {
        return ModConfig.GLOBAL_ENABLED.get() && ((ModConfig.TOOLTIP_ITEM_ENABLED.get()
                && ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT)
                || (ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()
                && ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT));
    }

    public static boolean allowRequest(TooltipTranslationController.RenderContext context,
                                       List<Component> components) {
        return allowRequestAt(context, signature(components), System.nanoTime());
    }

    static boolean allowRequestAt(TooltipTranslationController.RenderContext context,
                                  String signature,
                                  long nowNanos) {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            shortcutRequestExpiresAt = 0L;
            clearHoverIntent();
            return false;
        }
        ModConfig.TooltipTriggerMode mode = switch (context) {
            case ITEM -> ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get();
            case CHAT_OVERLAY -> ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get();
            case BOOK -> ModConfig.TooltipTriggerMode.HOVER;
        };
        if (mode == ModConfig.TooltipTriggerMode.HOVER) {
            return switch (context) {
                case ITEM -> ITEM_HOVER_INTENT.allow(signature, nowNanos);
                case CHAT_OVERLAY -> CHAT_HOVER_INTENT.allow(signature, nowNanos);
                case BOOK -> true;
            };
        }
        if (shortcutRequestExpiresAt == 0L || nowNanos > shortcutRequestExpiresAt) {
            shortcutRequestExpiresAt = 0L;
            return false;
        }
        shortcutRequestExpiresAt = 0L;
        return true;
    }

    static void clearHoverIntent() {
        ITEM_HOVER_INTENT.clear();
        CHAT_HOVER_INTENT.clear();
    }

    private static String signature(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        StringBuilder signature = new StringBuilder();
        for (Component component : components) {
            signature.append('\u001e');
            if (component != null) {
                signature.append(ComponentRenderSafety.sanitize(component).getString());
            }
        }
        return signature.toString();
    }

    private static final class HoverIntent {
        private String signature = "";
        private long firstSeenNanos;
        private long lastSeenNanos;

        private boolean allow(String nextSignature, long nowNanos) {
            if (nextSignature == null || nextSignature.isBlank()) {
                clear();
                return false;
            }
            boolean changed = !nextSignature.equals(this.signature);
            boolean interrupted = this.lastSeenNanos > 0L
                    && nowNanos - this.lastSeenNanos > HOVER_CONTINUITY_NANOS;
            if (changed || interrupted || this.firstSeenNanos == 0L) {
                this.signature = nextSignature;
                this.firstSeenNanos = nowNanos;
            }
            this.lastSeenNanos = nowNanos;
            return nowNanos - this.firstSeenNanos >= HOVER_DWELL_NANOS;
        }

        private void clear() {
            this.signature = "";
            this.firstSeenNanos = 0L;
            this.lastSeenNanos = 0L;
        }
    }
}
