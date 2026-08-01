package com.yourname.simpletranslate.feature.tooltip;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.core.ComponentVisualProjection;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import net.minecraft.util.text.ITextComponent;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Cache misses require either a stable hover or one consumed shortcut press. */
public final class TooltipRequestTriggerState {
    private static final long SHORTCUT_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(900L);
    private static final long HOVER_DWELL_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);
    private static final long HOVER_CONTINUITY_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long FAILURE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(6L);

    private static long shortcutRequestExpiresAt;
    private static final HoverIntent ITEM_HOVER = new HoverIntent();
    private static final HoverIntent CHAT_HOVER = new HoverIntent();
    private static final Set<String> PENDING = new HashSet<String>();
    private static final Map<String, Long> RETRY_AFTER = new HashMap<String, Long>();

    private TooltipRequestTriggerState() { }

    public static synchronized void armShortcutRequest() {
        if (!ModConfig.GLOBAL_ENABLED.get()) {
            shortcutRequestExpiresAt = 0L;
            return;
        }
        clearHoverIntent();
        shortcutRequestExpiresAt = System.nanoTime() + SHORTCUT_WINDOW_NANOS;
    }

    public static synchronized void clear() {
        shortcutRequestExpiresAt = 0L;
        clearHoverIntent();
        PENDING.clear();
        RETRY_AFTER.clear();
    }

    public static synchronized boolean allowRequest(Context context, List<ITextComponent> components) {
        if (!ModConfig.GLOBAL_ENABLED.get() || context == null) {
            clear();
            return false;
        }
        ModConfig.TooltipTriggerMode mode = context == Context.ITEM
                ? ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get()
                : ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get();
        long now = System.nanoTime();
        if (mode == ModConfig.TooltipTriggerMode.HOVER) {
            String signature = signature(components);
            return (context == Context.ITEM ? ITEM_HOVER : CHAT_HOVER).allow(signature, now);
        }
        if (shortcutRequestExpiresAt == 0L || now > shortcutRequestExpiresAt) {
            shortcutRequestExpiresAt = 0L;
            return false;
        }
        // One key press authorizes one semantic cache miss, not every tooltip
        // crossed during the remainder of the time window.
        shortcutRequestExpiresAt = 0L;
        return true;
    }

    public static synchronized String requestSignature(Context context, List<ITextComponent> components) {
        return (context == null ? "tooltip" : context.name()) + ':' + signature(components);
    }

    public static synchronized boolean beginRequest(String signature) {
        if (signature == null || signature.trim().isEmpty() || PENDING.contains(signature)) return false;
        long now = System.nanoTime();
        Long retryAt = RETRY_AFTER.get(signature);
        if (retryAt != null && now < retryAt.longValue()) return false;
        RETRY_AFTER.remove(signature);
        PENDING.add(signature);
        return true;
    }

    public static synchronized void finishRequest(String signature, boolean success) {
        if (signature == null) return;
        if (!PENDING.remove(signature)) return;
        if (success) RETRY_AFTER.remove(signature);
        else RETRY_AFTER.put(signature, Long.valueOf(System.nanoTime() + FAILURE_RETRY_NANOS));
    }

    public static synchronized boolean isPending(String signature) {
        return signature != null && PENDING.contains(signature);
    }

    private static String signature(List<ITextComponent> components) {
        if (components == null || components.isEmpty()) return "";
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                components, ModConfig.TARGET_LANGUAGE.get());
        if (projection != null && projection.hasSlots()) {
            String semantic = projection.semanticJson();
            if (semantic != null && !semantic.trim().isEmpty()) return semantic;
        }
        return JsonPassthroughPipeline.semanticPromptSourceShape(components);
    }

    private static void clearHoverIntent() {
        ITEM_HOVER.clear();
        CHAT_HOVER.clear();
    }

    public enum Context { ITEM, CHAT_HOVER }

    private static final class HoverIntent {
        private String signature = "";
        private long firstSeenNanos;
        private long lastSeenNanos;

        private boolean allow(String nextSignature, long nowNanos) {
            if (nextSignature == null || nextSignature.trim().isEmpty()) {
                clear();
                return false;
            }
            boolean interrupted = lastSeenNanos > 0L
                    && nowNanos - lastSeenNanos > HOVER_CONTINUITY_NANOS;
            if (!nextSignature.equals(signature) || interrupted || firstSeenNanos == 0L) {
                signature = nextSignature;
                firstSeenNanos = nowNanos;
            }
            lastSeenNanos = nowNanos;
            return nowNanos - firstSeenNanos >= HOVER_DWELL_NANOS;
        }

        private void clear() {
            signature = "";
            firstSeenNanos = 0L;
            lastSeenNanos = 0L;
        }
    }
}
