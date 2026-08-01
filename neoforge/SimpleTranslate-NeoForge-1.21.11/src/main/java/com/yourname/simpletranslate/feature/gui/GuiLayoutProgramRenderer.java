package com.yourname.simpletranslate.feature.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.ActiveFontManager;
import org.jetbrains.annotations.Nullable;
import com.yourname.simpletranslate.core.ProtectedTextRuns;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders resource-pack Components that use PUA/padding fonts as a small layout
 * program. Every run keeps its source advance and absolute anchor, so replacing
 * English with a different-width translation cannot move later rows or icons.
 */
public final class GuiLayoutProgramRenderer {
    private static final int MAX_DETECTION_CACHE = 64;
    private static final int MAX_IDENTITY_CACHE = 256;
    private static final float ADVANCE_EPSILON = 0.05F;
    private static final float MIN_READABLE_SCALE_X = 0.75F;
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static final Map<String, Boolean> DETECTION_CACHE =
            new LinkedHashMap<>(80, 0.75F, true);
    private static final IdentityHashMap<Component, IdentityDetection> IDENTITY_CACHE =
            new IdentityHashMap<>();

    private GuiLayoutProgramRenderer() {
    }

    public static boolean isReplaying() {
        return REPLAYING.get();
    }

    public static boolean isLayoutProgram(Component component) {
        if (component == null) {
            return false;
        }
        int structuralHash = component.hashCode();
        IdentityDetection identity = IDENTITY_CACHE.get(component);
        if (identity != null && identity.structuralHash() == structuralHash) {
            return identity.layoutProgram();
        }
        String json;
        try {
            json = JsonPassthroughPipeline.serializeComponents(List.of(component));
        } catch (RuntimeException ignored) {
            return false;
        }
        if (json == null) {
            return false;
        }
        synchronized (DETECTION_CACHE) {
            Boolean cached = DETECTION_CACHE.get(json);
            if (cached != null) {
                rememberIdentity(component, structuralHash, cached);
                return cached;
            }
        }
        boolean result;
        try {
            JsonElement root = JsonParser.parseString(json);
            result = JsonPassthroughPipeline.isLayoutCriticalHudTree(root);
        } catch (RuntimeException ignored) {
            result = false;
        }
        synchronized (DETECTION_CACHE) {
            DETECTION_CACHE.put(json, result);
            while (DETECTION_CACHE.size() > MAX_DETECTION_CACHE) {
                DETECTION_CACHE.remove(DETECTION_CACHE.keySet().iterator().next());
            }
        }
        rememberIdentity(component, structuralHash, result);
        return result;
    }

    private static void rememberIdentity(Component component, int structuralHash, boolean result) {
        if (IDENTITY_CACHE.size() >= MAX_IDENTITY_CACHE && !IDENTITY_CACHE.containsKey(component)) {
            IDENTITY_CACHE.clear();
        }
        IDENTITY_CACHE.put(component, new IdentityDetection(structuralHash, result));
    }

    private record RenderCacheKey(Component source, Component translated, Font font,
                                  long revision, int color, boolean shadow) {
    }

    private static volatile RenderCacheEntry lastRender;

    private record RenderCacheEntry(RenderCacheKey key, List<PositionedSpan> positioned) {
    }

    public static boolean renderText(GuiGraphics graphics, Font font,
                                     Component source, Component translated,
                                     float x, float y, int color, boolean shadow) {
        if (graphics == null || font == null || source == null || translated == null
                || source == translated) {
            return false;
        }
        RenderCacheKey cacheKey = new RenderCacheKey(source, translated, font,
                com.yourname.simpletranslate.core.ActiveFontManager.resourceRevision(), color, shadow);
        List<PositionedSpan> positioned;
        RenderCacheEntry cached = lastRender;
        if (cached != null && cached.key().equals(cacheKey)) {
            positioned = cached.positioned();
        } else {
            positioned = measurePositionedSpans(font, source, translated, color, shadow);
            if (positioned == null) {
                return false;
            }
            lastRender = new RenderCacheEntry(cacheKey, positioned);
        }
        for (PositionedSpan span : positioned) {
            if (span.rendered().getString().isEmpty()) {
                continue;
            }
            graphics.pose().pushMatrix();
            try {
                graphics.pose().translate(x + span.x(), y + span.yOffset());
                if (span.scaleX() != 1.0F) {
                    graphics.pose().scale(span.scaleX(), 1.0F);
                }
                REPLAYING.set(true);
                graphics.drawString(font, span.rendered(), 0, 0, color, shadow);
            } finally {
                REPLAYING.set(false);
                graphics.pose().popMatrix();
            }
        }
        return true;
    }

    public static boolean renderCenteredText(
            GuiGraphics graphics, Font font, Component source,
            Component translated, int centerX, int y, int color) {
        int sourceWidth = font.width(source);
        return renderText(graphics, font, source, translated,
                centerX - sourceWidth / 2.0F, y, color, true);
    }

    public static void clearLocalState() {
        REPLAYING.remove();
        IDENTITY_CACHE.clear();
        synchronized (DETECTION_CACHE) {
            DETECTION_CACHE.clear();
        }
    }

    /** Pure structural seam used by offline regression fixtures. */
    public static boolean hasCompatibleVisualRuns(Component source, Component translated) {
        List<Span> spans = compile(source, translated);
        return spans != null && !spans.isEmpty();
    }

    /** Pure metric guard shared with offline negative-padding regression tests. */
    public static boolean acceptsMeasuredAdvances(
            float sourceAdvance, float renderedAdvance, boolean protectedRun) {
        return Float.isFinite(sourceAdvance) && Float.isFinite(renderedAdvance)
                && (protectedRun || renderedAdvance >= 0.0F);
    }

    @Nullable
    private static List<PositionedSpan> measurePositionedSpans(Font font, Component source,
                                                               Component translated, int color,
                                                               boolean shadow) {
        List<Span> spans = compile(source, translated);
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        float sourceCursor = 0.0F;
        List<PositionedSpan> positioned = new ArrayList<>(spans.size());
        for (Span span : spans) {
            Component sourceRun = styled(span.sourceText(), span.style());
            float sourceAdvance = font.getSplitter().stringWidth(sourceRun);
            Component rendered = span.protectedRun()
                    ? sourceRun : styled(span.translatedText(), safeTextStyle(span.style()));
            float renderedAdvance = font.getSplitter().stringWidth(rendered);
            if (!acceptsMeasuredAdvances(
                    sourceAdvance, renderedAdvance, span.protectedRun())) {
                return null;
            }
            float scaleX = !span.protectedRun() && sourceAdvance > 0.0F
                    && renderedAdvance > sourceAdvance
                    ? Math.max(MIN_READABLE_SCALE_X, sourceAdvance / renderedAdvance)
                    : 1.0F;
            float yOffset = span.protectedRun() ? 0.0F
                    : sourceBaselineOffset(font, sourceRun, color, shadow);
            positioned.add(new PositionedSpan(rendered, sourceCursor, yOffset, scaleX));
            sourceCursor += sourceAdvance;
        }
        float wholeAdvance = font.getSplitter().stringWidth(source);
        if (!Float.isFinite(wholeAdvance)
                || Math.abs(wholeAdvance - sourceCursor) > ADVANCE_EPSILON) {
            return null;
        }
        return List.copyOf(positioned);
    }

    private static List<Span> compile(Component source, Component translated) {
        List<StyledRun> sourceRuns = flatten(source);
        List<StyledRun> translatedRuns = flatten(translated);
        if (sourceRuns.size() != translatedRuns.size()) {
            return null;
        }
        List<Span> spans = new ArrayList<>();
        for (int index = 0; index < sourceRuns.size(); index++) {
            StyledRun original = sourceRuns.get(index);
            StyledRun replacement = translatedRuns.get(index);
            List<ProtectedTextRuns.Run> originalParts =
                    ProtectedTextRuns.split(original.text());
            List<ProtectedTextRuns.Run> replacementParts =
                    ProtectedTextRuns.split(replacement.text());
            if (originalParts.size() != replacementParts.size()) {
                return null;
            }
            for (int part = 0; part < originalParts.size(); part++) {
                ProtectedTextRuns.Run originalPart = originalParts.get(part);
                ProtectedTextRuns.Run replacementPart = replacementParts.get(part);
                if (originalPart.protectedRun() != replacementPart.protectedRun()
                        || (originalPart.protectedRun()
                        && !originalPart.text().equals(replacementPart.text()))) {
                    return null;
                }
                spans.add(new Span(originalPart.text(), replacementPart.text(),
                        original.style(), originalPart.protectedRun()));
            }
        }
        return spans;
    }

    private static List<StyledRun> flatten(Component component) {
        List<StyledRun> result = new ArrayList<>();
        component.visit((style, text) -> {
            // Empty wrapper Components carry inheritance/grouping only and are
            // deliberately absent after visible-component materialization.
            // They must not make the source/translation visual-run counts differ.
            if (text != null && !text.isEmpty()) {
                result.add(new StyledRun(text,
                        style == null ? Style.EMPTY : style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    private static float sourceBaselineOffset(Font font, Component source,
                                              int color, boolean shadow) {
        try {
            int sourceTop = font.prepareText(source.getVisualOrderText(),
                    0.0F, 0.0F, color, shadow, false, 0).bounds().top();
            Component safeSource = styled(source.getString(), safeTextStyle(source.getStyle()));
            int safeTop = font.prepareText(safeSource.getVisualOrderText(),
                    0.0F, 0.0F, color, shadow, false, 0).bounds().top();
            return sourceTop - safeTop;
        } catch (RuntimeException ignored) {
            return 0.0F;
        }
    }

    private static Component styled(String text, Style style) {
        return Component.literal(text == null ? "" : text)
                .withStyle(style == null ? Style.EMPTY : style);
    }

    private static Style safeTextStyle(Style source) {
        return (source == null ? Style.EMPTY : source).withFont(
                new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT));
    }

    private record IdentityDetection(int structuralHash, boolean layoutProgram) {
    }

    private record StyledRun(String text, Style style) {
    }

    private record Span(String sourceText, String translatedText,
                        Style style, boolean protectedRun) {
    }

    private record PositionedSpan(Component rendered, float x,
                                  float yOffset, float scaleX) {
    }
}
