package com.yourname.simpletranslate.feature.tooltip;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.ComponentVisualProjection;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.util.text.ITextComponent;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Controls whether a tooltip cache miss may start a model request. */
public final class TooltipTranslationTriggerState {
    private static final long SHORTCUT_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(750L);
    private static final long HOVER_DWELL_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);
    // A resource-pack GUI may briefly stall below four FPS. Keep the same
    // semantic hover alive across those frames instead of resetting the dwell
    // timer forever whenever one frame takes slightly over 250 ms.
    private static final long HOVER_CONTINUITY_NANOS = TimeUnit.SECONDS.toNanos(2L);
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
        return hasEnabledShortcutMode(TooltipTranslationController.RenderContext.ITEM)
                || hasEnabledShortcutMode(TooltipTranslationController.RenderContext.CHAT_OVERLAY);
    }

    public static boolean hasEnabledShortcutMode(TooltipTranslationController.RenderContext context) {
        if (!ModConfig.GLOBAL_ENABLED.get() || context == null) {
            return false;
        }
        return switch (context) {
            case ITEM -> ModConfig.TOOLTIP_ITEM_ENABLED.get()
                    && ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get()
                    == ModConfig.TooltipTriggerMode.SHORTCUT;
            case CHAT_OVERLAY -> ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()
                    && ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get()
                    == ModConfig.TooltipTriggerMode.SHORTCUT;
            case BOOK -> false;
        };
    }

    public static boolean allowRequest(TooltipTranslationController.RenderContext context,
                                       List<ITextComponent> components) {
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
            boolean allowed = switch (context) {
                case ITEM -> ITEM_HOVER_INTENT.allow(signature, nowNanos);
                case CHAT_OVERLAY -> CHAT_HOVER_INTENT.allow(signature, nowNanos);
                case BOOK -> true;
            };
            if (context == TooltipTranslationController.RenderContext.ITEM) {
                ITEM_HOVER_INTENT.logState(signature, allowed, nowNanos);
            }
            return allowed;
        }
        if (shortcutRequestExpiresAt == 0L || nowNanos > shortcutRequestExpiresAt) {
            shortcutRequestExpiresAt = 0L;
            return false;
        }
        shortcutRequestExpiresAt = 0L;
        if (context == TooltipTranslationController.RenderContext.ITEM) {
            SimpleTranslateMod.getLogger().debug(
                    "Tooltip shortcut trigger consumed signature={}", shortSignature(signature));
        }
        return true;
    }

    static boolean allowHoverRequestAtForTest(String signature, long nowNanos) {
        return ITEM_HOVER_INTENT.allow(signature, nowNanos);
    }

    public static void clearItemHoverIntent() {
        ITEM_HOVER_INTENT.clear();
    }

    static void clearHoverIntent() {
        clearItemHoverIntent();
        CHAT_HOVER_INTENT.clear();
    }

    private static String signature(List<ITextComponent> components) {
        if (components == null || components.isEmpty()) {
            return "";
        }
        // Use the exact same semantic projection as the request/cache path.
        // Raw custom-font ASCII glyphs can animate while getString() still looks
        // like language, so a plain-text signature is not stable enough.
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                components, ModConfig.TARGET_LANGUAGE.get());
        if (projection != null && projection.hasSlots()) {
            String semanticJson = projection.semanticJson();
            if (semanticJson != null && !semanticJson.isBlank()) {
                return semanticJson;
            }
        }
        return JsonPassthroughPipeline.semanticPromptSourceShape(components);
    }

    private static String shortSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return "empty";
        }
        return Integer.toUnsignedString(signature.hashCode(), 36);
    }

    private static final class HoverIntent {
        private String signature = "";
        private long firstSeenNanos;
        private long lastSeenNanos;
        private String lastLoggedSignature = "";
        private boolean lastLoggedAllowed;
        private boolean hasLoggedState;

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

        private long stableMillis(long nowNanos) {
            if (firstSeenNanos == 0L || nowNanos < firstSeenNanos) {
                return 0L;
            }
            return TimeUnit.NANOSECONDS.toMillis(nowNanos - firstSeenNanos);
        }

        private void logState(String currentSignature, boolean allowed, long nowNanos) {
            if (hasLoggedState && allowed == lastLoggedAllowed
                    && java.util.Objects.equals(currentSignature, lastLoggedSignature)) {
                return;
            }
            SimpleTranslateMod.getLogger().debug(
                    "Tooltip hover trigger state={} signature={} stableMillis={}",
                    allowed ? "stable" : "waiting", shortSignature(currentSignature),
                    stableMillis(nowNanos));
            lastLoggedSignature = currentSignature;
            lastLoggedAllowed = allowed;
            hasLoggedState = true;
        }

        private void clear() {
            this.signature = "";
            this.firstSeenNanos = 0L;
            this.lastSeenNanos = 0L;
            this.lastLoggedSignature = "";
            this.hasLoggedState = false;
        }
    }
}
