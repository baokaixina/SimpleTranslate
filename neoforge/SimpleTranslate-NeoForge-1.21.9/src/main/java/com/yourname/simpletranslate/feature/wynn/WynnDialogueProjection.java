package com.yourname.simpletranslate.feature.wynn;

import com.yourname.simpletranslate.SimpleTranslateMod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact semantic projection for Wynncraft's 26.1 dialogue actionbar grammar.
 * Only text carried by the documented dialogue text fonts becomes model input;
 * frame/style/portrait/fade/progress fonts and every positioning glyph remain
 * exclusively in the original local render stream.
 */
public final class WynnDialogueProjection {
    private static final String FONT_PREFIX = "minecraft:hud/dialogue/text/";
    private static final Pattern BODY_FONT = Pattern.compile(
            "minecraft:hud/dialogue/text/[^/]+/body_([0-4])");
    private static final Pattern CHOICE_FONT = Pattern.compile(
            "minecraft:hud/dialogue/text/[^/]+/choice_([0-3])");
    private static final Pattern CHOICE_CHROME_FONT = Pattern.compile(
            "minecraft:hud/dialogue/style/[^/]+/choice");

    private final Component sourceActionbar;
    private final List<GlyphEvent> events;
    private final EventSequence sourceSequence;
    private final List<SemanticSlot> contentSlots;
    private final List<SemanticSlot> optionSlots;
    private final List<Component> contentComponents;
    private final List<Component> optionComponents;
    private final String contentFingerprint;
    private final String optionsFingerprint;
    private final String sessionKey;
    private final OptionVisibility optionVisibility;

    private WynnDialogueProjection(Component sourceActionbar,
                                   List<GlyphEvent> events,
                                   List<SemanticSlot> contentSlots,
                                   List<SemanticSlot> optionSlots,
                                   String sessionKey,
                                   OptionVisibility optionVisibility) {
        this.sourceActionbar = sourceActionbar;
        this.events = List.copyOf(events);
        this.sourceSequence = new EventSequence(this.events);
        this.contentSlots = List.copyOf(contentSlots);
        this.optionSlots = List.copyOf(optionSlots);
        this.contentComponents = requestComponents(this.contentSlots);
        this.optionComponents = requestComponents(this.optionSlots);
        this.contentFingerprint = fingerprint("content", this.contentSlots);
        this.optionsFingerprint = fingerprint("options", this.optionSlots);
        this.sessionKey = sessionKey == null ? "" : sessionKey;
        this.optionVisibility = optionVisibility == null
                ? OptionVisibility.UNKNOWN : optionVisibility;
    }

    @Nullable
    public static WynnDialogueProjection project(@Nullable Component source) {
        return source == null ? null : projectInternal(source);
    }

    /**
     * Recognizes the exact dialogue text family even while a typewriter frame
     * is too incomplete for a safe semantic projection. Callers use this to
     * keep raw PUA dialogue out of the generic actionbar translator.
     */
    public static boolean hasKnownDialogueTextStructure(@Nullable Component source) {
        if (source == null) {
            return false;
        }
        boolean[] found = new boolean[1];
        try {
            source.getVisualOrderText().accept((sourceIndex, style, codePoint) -> {
                if (DialogueLine.fromFont(WynncraftProfile.fontId(style)) != null) {
                    found[0] = true;
                    return false;
                }
                return true;
            });
        } catch (Throwable ignored) {
            return false;
        }
        return found[0];
    }

    @Nullable
    private static WynnDialogueProjection projectInternal(Component source) {
        List<GlyphEvent> events = snapshot(source);
        if (events.isEmpty()) {
            return null;
        }

        Map<DialogueLine, List<GlyphEvent>> byLine = new EnumMap<>(DialogueLine.class);
        boolean exactDialogueFont = false;
        boolean choiceChromeVisible = false;
        Set<String> portraitFonts = new LinkedHashSet<>();
        for (GlyphEvent event : events) {
            DialogueLine line = DialogueLine.fromFont(event.fontId());
            if (line != null) {
                exactDialogueFont = true;
                byLine.computeIfAbsent(line, ignored -> new ArrayList<>()).add(event);
            } else if (event.fontId().contains("hud/dialogue/portrait")) {
                // The portrait glyph itself can animate. Using that code point
                // as part of the dialogue session makes an otherwise stable
                // sentence look like a new session every frame and causes a
                // late translation to be discarded. The font family is the
                // stable identity; the visible NPC name is preferred below.
                portraitFonts.add(event.fontId());
            }
            if (CHOICE_CHROME_FONT.matcher(event.fontId()).matches()) {
                choiceChromeVisible = true;
            }
        }
        if (!exactDialogueFont) {
            return null;
        }

        List<LineRegion> nameRegions = regions(byLine, DialogueLine.NAMEPLATE);
        if (nameRegions == null) {
            return null;
        }
        List<LineRegion> bodyRegions = new ArrayList<>();
        for (DialogueLine line : DialogueLine.bodyLines()) {
            List<LineRegion> parsed = regions(byLine, line);
            if (parsed == null) {
                return null;
            }
            bodyRegions.addAll(parsed);
        }
        List<LineRegion> controlRegions = regions(byLine, DialogueLine.CONTROL);
        if (controlRegions == null) {
            return null;
        }

        List<SemanticSlot> content = new ArrayList<>(3);
        addLogicalSlot(content, SemanticKind.NAME, nameRegions, false);
        addLogicalSlot(content, SemanticKind.BODY, bodyRegions, true);
        addLogicalSlot(content, SemanticKind.CONTROL, controlRegions, false);

        List<SemanticSlot> options = new ArrayList<>(4);
        for (DialogueLine line : DialogueLine.choiceLines()) {
            List<LineRegion> parsed = regions(byLine, line);
            if (parsed == null) {
                return null;
            }
            addLogicalSlot(options, SemanticKind.OPTION, parsed, false);
        }

        // A frame may be emitted before the first typewriter character. It is
        // recognized but not requestable until at least one natural-language
        // slot exists; this prevents empty-prefix network traffic.
        if (content.isEmpty() && options.isEmpty()) {
            return null;
        }
        String name = slotText(content, SemanticKind.NAME);
        String session = !name.isBlank() ? name : String.join(",", portraitFonts);
        OptionVisibility optionVisibility = classifyOptionVisibility(
                options, slotText(content, SemanticKind.CONTROL), choiceChromeVisible);
        return new WynnDialogueProjection(source, events, content, options, session,
                optionVisibility);
    }

    private static OptionVisibility classifyOptionVisibility(List<SemanticSlot> options,
                                                              String controlText,
                                                              boolean choiceChromeVisible) {
        if (options == null || options.isEmpty()) {
            return OptionVisibility.NOT_PRESENT;
        }
        // Wynn only emits hud/dialogue/style/<theme>/choice while its choice
        // rows are actually presented. This is the strongest language-neutral
        // visibility signal in the 26.1.1 resource-pack grammar.
        if (choiceChromeVisible) {
            return OptionVisibility.VISIBLE;
        }
        String control = controlText == null ? ""
                : controlText.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        // Choice components can already be present while the typewriter/body
        // phase is active. They are requestable for pretranslation, but their
        // untouched source glyphs must remain responsible for being hidden.
        if (control.contains("to continue")) {
            return OptionVisibility.PRELOADED_HIDDEN;
        }
        return OptionVisibility.UNKNOWN;
    }

    private static List<GlyphEvent> snapshot(Component source) {
        List<GlyphEvent> result = new ArrayList<>();
        try {
            source.getVisualOrderText().accept((sourceIndex, style, codePoint) -> {
                Style safe = style == null ? Style.EMPTY : style;
                result.add(new GlyphEvent(result.size(), sourceIndex, safe,
                        WynncraftProfile.fontId(safe), codePoint));
                return true;
            });
            return List.copyOf(result);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    @Nullable
    private static List<LineRegion> regions(Map<DialogueLine, List<GlyphEvent>> byLine,
                                            DialogueLine line) {
        List<GlyphEvent> lineEvents = byLine.get(line);
        if (lineEvents == null || lineEvents.isEmpty()) {
            return List.of();
        }
        String proseFont = dominantProseFont(lineEvents, line);
        if (proseFont == null || proseFont.isBlank()) {
            return List.of();
        }
        if (hasAmbiguousForeignNaturalText(lineEvents, proseFont)) {
            // Two independently sentence-like fonts on one physical row are
            // not enough evidence to decide which one is an icon alphabet.
            // Keep the complete source frame instead of producing mixed prose.
            return null;
        }
        List<LineRegion> result = new ArrayList<>();
        List<SemanticGlyph> semanticGlyphs = new ArrayList<>();
        List<Integer> visualBefore = new ArrayList<>();
        List<Integer> pendingVisual = new ArrayList<>();
        Style effectiveStyle = null;
        Style eventStyle = null;
        boolean awaitingLegacyCode = false;
        for (int eventIndex = 0; eventIndex < lineEvents.size(); eventIndex++) {
            GlyphEvent event = lineEvents.get(eventIndex);
            int codePoint = event.codePoint();
            if (effectiveStyle == null || !Objects.equals(eventStyle, event.style())) {
                // Component Style transitions are authoritative. Inline legacy
                // state is then applied locally until another explicit Style
                // run begins. A translated line has one deterministic Style;
                // differing visible states are rejected below instead of being
                // guessed across reordered target-language words.
                effectiveStyle = event.style();
                eventStyle = event.style();
            }
            if (awaitingLegacyCode) {
                awaitingLegacyCode = false;
                ChatFormatting formatting = legacyFormatting(codePoint);
                if (formatting == null) {
                    return null;
                }
                effectiveStyle = formatting == ChatFormatting.RESET
                        ? Style.EMPTY : effectiveStyle.applyLegacyFormat(formatting);
                continue;
            }
            if (Objects.equals(event.fontId(), proseFont) && codePoint == '\u00A7') {
                awaitingLegacyCode = true;
                continue;
            }
            boolean proseGlyph = Objects.equals(event.fontId(), proseFont)
                    && isSemanticCodePoint(codePoint) && !isSymbol(codePoint);
            if (proseGlyph) {
                // Whitespace emitted while the typewriter is paused immediately
                // after an icon is not a new semantic fragment. Keep the visual
                // pending until a real following glyph arrives; if the frame ends
                // here it becomes a trailing local token instead of invalidating
                // the complete dialogue projection.
                boolean meaningful = Character.isLetterOrDigit(codePoint)
                        || (!Character.isWhitespace(codePoint) && !isPunctuation(codePoint));
                if (!pendingVisual.isEmpty() && meaningful) {
                    if (containsNaturalLanguageGlyphs(semanticGlyphs)) {
                        appendRegion(result, line, semanticGlyphs, visualBefore,
                                true, List.of());
                        semanticGlyphs.clear();
                        visualBefore = new ArrayList<>(pendingVisual);
                    } else {
                        visualBefore.addAll(pendingVisual);
                    }
                    pendingVisual.clear();
                }
                if (pendingVisual.isEmpty()) {
                    semanticGlyphs.add(new SemanticGlyph(codePoint, event.ordinal(), effectiveStyle));
                }
                continue;
            }

            boolean proseBefore = containsNaturalLanguageGlyphs(semanticGlyphs);
            boolean proseAfter = hasNaturalLanguageAfter(
                    lineEvents, eventIndex + 1, proseFont);
            if (isMovableVisualCandidate(event, proseFont, proseBefore, proseAfter,
                    !pendingVisual.isEmpty())) {
                pendingVisual.add(event.ordinal());
            }
        }
        if (awaitingLegacyCode) {
            return null;
        }
        appendRegion(result, line, semanticGlyphs, visualBefore,
                false, pendingVisual);
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    /** Converts one uninterrupted natural-language fragment into a safe model/overlay slot. */
    private static void appendRegion(List<LineRegion> target, DialogueLine line,
                                     List<SemanticGlyph> semanticGlyphs,
                                     List<Integer> visualBeforeOrdinals,
                                     boolean inlineIconAfter,
                                     List<Integer> visualAfterOrdinals) {
        if (semanticGlyphs == null || semanticGlyphs.isEmpty()) {
            return;
        }
        List<SemanticGlyph> glyphs = new ArrayList<>(semanticGlyphs);
        trimSemanticEdges(glyphs);
        if (glyphs.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        List<Integer> ordinals = new ArrayList<>(glyphs.size());
        List<StyledRun> styledRuns = new ArrayList<>();
        StringBuilder runText = new StringBuilder();
        Style runStyle = null;
        List<Integer> runOrdinals = new ArrayList<>();
        for (SemanticGlyph glyph : glyphs) {
            text.appendCodePoint(glyph.codePoint());
            ordinals.add(glyph.ordinal());
            if (runStyle == null || sameSemanticVisualStyle(runStyle, glyph.style())) {
                if (runStyle == null) {
                    runStyle = glyph.style();
                }
            } else {
                styledRuns.add(new StyledRun(runText.toString(), runStyle, List.copyOf(runOrdinals)));
                runText.setLength(0);
                runOrdinals.clear();
                runStyle = glyph.style();
            }
            runText.appendCodePoint(glyph.codePoint());
            runOrdinals.add(glyph.ordinal());
        }
        if (!runText.isEmpty()) {
            styledRuns.add(new StyledRun(runText.toString(),
                    runStyle == null ? Style.EMPTY : runStyle, List.copyOf(runOrdinals)));
        }
        if (!containsNaturalLanguage(text)) {
            return;
        }
        int start = ordinals.getFirst();
        int end = ordinals.getLast() + 1;
        List<Integer> before = visualBeforeOrdinals == null
                ? List.of() : List.copyOf(visualBeforeOrdinals);
        List<Integer> after = visualAfterOrdinals == null
                ? List.of() : List.copyOf(visualAfterOrdinals);
        target.add(new LineRegion(line, !before.isEmpty(), inlineIconAfter || !after.isEmpty(), text.toString(),
                styledRuns.getFirst().sourceStyle(), start, end, List.copyOf(ordinals),
                List.copyOf(styledRuns), componentForRuns(styledRuns), before, after));
    }

    private static void trimSemanticEdges(List<SemanticGlyph> glyphs) {
        while (!glyphs.isEmpty() && Character.isWhitespace(glyphs.getFirst().codePoint())) {
            glyphs.removeFirst();
        }
        while (!glyphs.isEmpty() && Character.isWhitespace(glyphs.getLast().codePoint())) {
            glyphs.removeLast();
        }
    }

    private static void addLogicalSlot(List<SemanticSlot> target, SemanticKind kind,
                                       List<LineRegion> regions, boolean joinAsParagraph) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        List<LineRegion> sorted = regions.stream()
                .sorted(Comparator.comparingInt((LineRegion region) -> region.line().lineIndex)
                        .thenComparingInt(LineRegion::startOrdinal))
                .toList();

        // A BODY is one spoken paragraph. Wynn may place a local service icon
        // between two words on a physical row, but that icon is presentation,
        // not a semantic sentence boundary. Keeping every body region in one
        // request prevents translations such as "你可以在 / 物品鉴定师" where
        // the predicate is lost because neither fragment has enough context.
        // The individual regions remain attached to the slot so the renderer
        // can mask all source prose while leaving the icon itself untouched.
        if (kind == SemanticKind.BODY) {
            addBodyLogicalSlot(target, sorted);
            return;
        }

        List<LineRegion> group = new ArrayList<>();
        for (LineRegion region : sorted) {
            // An inline dialogue icon can split the first physical body row into a
            // narrow suffix followed by several normal-width rows. Keep that
            // suffix in its own semantic slot: otherwise the renderer takes
            // its small width as the wrap width for the entire paragraph and
            // rejects an otherwise valid translation for using too many rows.
            if (!region.inlineIconBefore() && !group.isEmpty()
                    && group.getFirst().inlineIconBefore()) {
                addLogicalSlotGroup(target, kind, group, joinAsParagraph);
                group.clear();
            }
            if (region.inlineIconBefore() && !group.isEmpty()) {
                addLogicalSlotGroup(target, kind, group, joinAsParagraph);
                group.clear();
            }
            group.add(region);
        }
        addLogicalSlotGroup(target, kind, group, joinAsParagraph);
    }

    /**
     * Builds one ordinary spoken BODY paragraph. The source anchors remain
     * local solely to identify prose glyphs that may be hidden after a complete
     * overlay validates; they no longer force the provider or renderer to split
     * Chinese at English style, icon, or line-wrap boundaries.
     */
    private static void addBodyLogicalSlot(List<SemanticSlot> target,
                                           List<LineRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        StringBuilder paragraph = new StringBuilder();
        List<BodyAnchor> anchors = new ArrayList<>();
        List<StyledRun> appearanceRuns = new ArrayList<>();
        for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
            LineRegion region = regions.get(regionIndex);
            if (!paragraph.isEmpty()) {
                // A physical Wynn row wrap is presentation, not a sentence
                // boundary. Normalize it into one ordinary word boundary.
                paragraph.append(' ');
                if (!appearanceRuns.isEmpty()) {
                    StyledRun last = appearanceRuns.removeLast();
                    appearanceRuns.add(new StyledRun(last.text() + ' ', last.sourceStyle(),
                            last.maskOrdinals()));
                }
            }
            for (int runIndex = 0; runIndex < region.styledRuns().size(); runIndex++) {
                StyledRun run = region.styledRuns().get(runIndex);
                String runText = run.text();
                if (runText == null || runText.isEmpty()) {
                    continue;
                }
                paragraph.append(runText);
                anchors.add(new BodyAnchor(regionIndex, runIndex, region.line(),
                        run.sourceStyle(), run.maskOrdinals()));
                if (!appearanceRuns.isEmpty() && WynnSemanticStyle.sameBodyOverlayAppearance(
                        appearanceRuns.getLast().sourceStyle(), run.sourceStyle())) {
                    StyledRun last = appearanceRuns.removeLast();
                    appearanceRuns.add(new StyledRun(last.text() + runText, last.sourceStyle(),
                            last.maskOrdinals()));
                } else {
                    appearanceRuns.add(new StyledRun(runText, run.sourceStyle(), List.of()));
                }
            }
        }
        if (!containsNaturalLanguage(paragraph) || anchors.isEmpty()
                || !hasOverlaySafeBodyStyles(anchors)) {
            // A run the overlay cannot reproduce (obfuscated glyphs or a font
            // outside the row's dialogue carrier family) keeps the complete
            // paragraph source-only. Differing colours between runs are fine:
            // each translated span is mapped back to its own source appearance.
            return;
        }
        Style sourceStyle = anchors.getFirst().sourceStyle();
        Component request = bodyRequestComponent(paragraph.toString(), appearanceRuns);
        target.add(new SemanticSlot(target.size(), SemanticKind.BODY, paragraph.toString(),
                sourceStyle, List.copyOf(regions), request, List.copyOf(anchors)));
    }

    /**
     * A single-appearance BODY keeps the historical one-literal request shape
     * (and its cache identity). A multi-appearance BODY exposes every source
     * run's style so the model can preserve the highlight structure; response
     * colours are never trusted, only mapped back to source appearances.
     */
    private static Component bodyRequestComponent(String paragraph, List<StyledRun> appearanceRuns) {
        if (appearanceRuns.size() <= 1) {
            Style style = appearanceRuns.isEmpty() ? Style.EMPTY
                    : appearanceRuns.getFirst().sourceStyle();
            return Component.literal(paragraph).withStyle(WynnSemanticStyle.forRequest(style));
        }
        MutableComponent request = Component.empty();
        for (StyledRun run : appearanceRuns) {
            request.append(Component.literal(run.text()).withStyle(
                    WynnSemanticStyle.forRequest(run.sourceStyle())));
        }
        return request;
    }

    /**
     * A multi-style BODY is translatable only when its runs share one dialogue
     * font family and uniform decorations, with no obfuscated glyphs and no
     * Wynn shader-marker colour such as #00EB34 (a movement instruction, not
     * a display colour). Text colour and shadow may vary freely between runs;
     * each translated span is mapped back to its own source appearance when
     * the overlay is built.
     */
    private static boolean hasOverlaySafeBodyStyles(List<BodyAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return false;
        }
        Style expected = anchors.getFirst().sourceStyle();
        String expectedFamily = bodyCarrierFamily(anchors.getFirst().line(), expected);
        if (!WynnSemanticStyle.isOverlaySafe(expected) || expectedFamily == null
                || WynnSemanticStyle.isShaderMarkerColour(expected)) {
            return false;
        }
        for (BodyAnchor anchor : anchors) {
            if (anchor == null || anchor.maskOrdinals().isEmpty()
                    || !Objects.equals(expectedFamily,
                    bodyCarrierFamily(anchor.line(), anchor.sourceStyle()))
                    || !WynnSemanticStyle.isOverlaySafe(anchor.sourceStyle())
                    || !WynnSemanticStyle.sameDecorations(expected, anchor.sourceStyle())
                    || WynnSemanticStyle.isShaderMarkerColour(anchor.sourceStyle())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every semantic BODY anchor must still be carried by one source dialogue
     * family and the exact font variant that identified its physical row. A
     * visible-style comparison alone cannot safely distinguish a Wynn row
     * carrier from a second, unrelated custom alphabet with coincident colours.
     */
    @Nullable
    private static String bodyCarrierFamily(@Nullable DialogueLine line, @Nullable Style style) {
        if (line == null || !line.isBody() || style == null) {
            return null;
        }
        String fontId = WynncraftProfile.fontId(style);
        if (line != DialogueLine.fromFont(fontId)) {
            return null;
        }
        int bodySeparator = fontId.lastIndexOf("/body_");
        if (bodySeparator <= FONT_PREFIX.length()) {
            return null;
        }
        return fontId.substring(FONT_PREFIX.length(), bodySeparator);
    }

    private static void addLogicalSlotGroup(List<SemanticSlot> target, SemanticKind kind,
                                            List<LineRegion> regions, boolean joinAsParagraph) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        List<StyledRun> logicalRuns = new ArrayList<>();
        for (LineRegion region : regions) {
            if (!text.isEmpty() && joinAsParagraph) {
                text.append(' ');
                Style separatorStyle = logicalRuns.isEmpty()
                        ? region.sourceStyle() : logicalRuns.getLast().sourceStyle();
                appendStyledRun(logicalRuns, " ", separatorStyle, List.of());
            }
            text.append(region.sourceText());
            for (StyledRun run : region.styledRuns()) {
                appendStyledRun(logicalRuns, run.text(), run.sourceStyle(), run.maskOrdinals());
            }
        }
        if (!containsNaturalLanguage(text)) {
            return;
        }
        Component request = kind == SemanticKind.BODY
                ? Component.literal(text.toString()).withStyle(
                WynnSemanticStyle.forRequest(regions.getFirst().sourceStyle()))
                : componentForRuns(logicalRuns);
        target.add(new SemanticSlot(target.size(), kind, text.toString(),
                regions.getFirst().sourceStyle(), List.copyOf(regions), request, List.of()));
    }

    private static void appendStyledRun(List<StyledRun> target, String text, Style style,
                                        List<Integer> maskOrdinals) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Style safeStyle = style == null ? Style.EMPTY : style;
        List<Integer> safeOrdinals = maskOrdinals == null ? List.of() : maskOrdinals;
        if (!target.isEmpty()
                && sameSemanticVisualStyle(target.getLast().sourceStyle(), safeStyle)) {
            StyledRun previous = target.removeLast();
            List<Integer> mergedOrdinals = new ArrayList<>(previous.maskOrdinals());
            mergedOrdinals.addAll(safeOrdinals);
            target.add(new StyledRun(previous.text() + text, previous.sourceStyle(), mergedOrdinals));
            return;
        }
        target.add(new StyledRun(text, safeStyle, safeOrdinals));
    }

    private static String slotText(List<SemanticSlot> slots, SemanticKind kind) {
        for (SemanticSlot slot : slots) {
            if (slot.kind() == kind) {
                return slot.sourceText();
            }
        }
        return "";
    }

    private static List<Component> requestComponents(List<SemanticSlot> slots) {
        List<Component> result = new ArrayList<>(slots.size());
        for (SemanticSlot slot : slots) {
            result.add(slot.requestComponent());
        }
        return List.copyOf(result);
    }

    private static Component componentForRuns(List<StyledRun> runs) {
        MutableComponent result = Component.empty();
        for (StyledRun run : runs) {
            result.append(Component.literal(run.text()).withStyle(
                    WynnSemanticStyle.forRequest(run.sourceStyle())));
        }
        return result;
    }

    private static String fingerprint(String prefix, List<SemanticSlot> slots) {
        StringBuilder value = new StringBuilder("wynn.dialogue.semantic.paragraph.v5/").append(prefix);
        for (SemanticSlot slot : slots) {
            String sourceText = slot.sourceText();
            String requestText = slot.requestComponent().getString();
            value.append('|').append(slot.kind())
                    .append(':').append(sourceText.length()).append(':').append(sourceText)
                    .append(':').append(requestText.length()).append(':').append(requestText);
            for (BodyAnchor anchor : slot.bodyAnchors()) {
                LineRegion region = slot.regions().get(anchor.regionIndex());
                value.append('@').append(anchor.regionIndex())
                        .append('/').append(anchor.runIndex())
                        .append('/').append(anchor.maskOrdinals().size())
                        .append('/').append(region.visualBeforeOrdinals().size())
                        .append('/').append(region.visualAfterOrdinals().size());
                appendVisualStyleFingerprint(value, anchor.sourceStyle());
            }
        }
        return value.toString();
    }

    private static void appendVisualStyleFingerprint(StringBuilder target, Style style) {
        target.append('/').append(WynnSemanticStyle.visualFingerprint(style));
    }

    /**
     * Component metadata such as insertion/hover/click events is not rendered
     * in an actionbar. Wynn can attach that metadata to a dynamic player-name
     * component inside an otherwise single body line, so Style.equals would
     * reject the complete dialogue even though every visible glyph uses the
     * same dialogue style. Treat an absent colour as the effective HUD white
     * and compare only properties that can alter the visible text quad.
     */
    private static boolean sameSemanticVisualStyle(Style first, Style second) {
        return WynnSemanticStyle.sameStableStyle(first, second);
    }

    private static boolean isSemanticCodePoint(int codePoint) {
        return codePoint != '\u00A7' && !isProtectedWynnGlyph(codePoint) && !isControl(codePoint)
                && Character.getType(codePoint) != Character.FORMAT;
    }

    /**
     * Chooses the natural-language carrier structurally instead of enumerating
     * Wynn resource-pack families. Dialogue prose normally contains spaces and
     * several text leaves, while an icon/keybind font is a compact foreign run.
     * A future language family therefore becomes prose automatically, and every
     * other font on that physical row remains an opaque local visual token.
     */
    @Nullable
    private static String dominantProseFont(List<GlyphEvent> events, DialogueLine line) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        if (line == DialogueLine.NAMEPLATE) {
            return FONT_PREFIX + "nameplate";
        }
        if (line == DialogueLine.CONTROL) {
            return FONT_PREFIX + "control";
        }
        Map<String, Integer> scores = new java.util.LinkedHashMap<>();
        String previousFont = null;
        boolean runHasLetter = false;
        for (int index = 0; index <= events.size(); index++) {
            GlyphEvent event = index < events.size() ? events.get(index) : null;
            String font = event == null ? null : event.fontId();
            if (!Objects.equals(previousFont, font)) {
                if (previousFont != null && runHasLetter) {
                    scores.merge(previousFont, 10, Integer::sum);
                }
                previousFont = font;
                runHasLetter = false;
            }
            if (event == null || !isSemanticCodePoint(event.codePoint())
                    || isSymbol(event.codePoint())) {
                continue;
            }
            int codePoint = event.codePoint();
            int score = Character.isLetterOrDigit(codePoint) ? 1
                    : Character.isWhitespace(codePoint) ? 20 : 1;
            scores.merge(font, score, Integer::sum);
            runHasLetter |= Character.isLetter(codePoint);
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return best;
    }

    private static boolean isMovableVisualCandidate(GlyphEvent event, String proseFont,
                                                     boolean proseBefore, boolean proseAfter,
                                                     boolean visualClusterOpen) {
        if (event == null || Character.isWhitespace(event.codePoint())) {
            return false;
        }
        if (!Objects.equals(event.fontId(), proseFont)) {
            // A foreign font in a verified dialogue row is presentation data,
            // regardless of whether the resource pack maps it to PUA, emoji,
            // ASCII, a variation selector, or a future Unicode symbol.
            int type = Character.getType(event.codePoint());
            return (type != Character.FORMAT && !isControl(event.codePoint()))
                    || visualClusterOpen;
        }
        // Ordinary Unicode symbols are visual even when a pack keeps them in
        // the prose font. A following FORMAT/variation code stays in that same
        // atomic cluster. Same-font PUA positioning glyphs at row edges remain
        // fixed unless they sit strictly between two prose islands.
        if (isSymbol(event.codePoint())) {
            return proseBefore || proseAfter || visualClusterOpen;
        }
        if (Character.getType(event.codePoint()) == Character.FORMAT
                || isControl(event.codePoint())) {
            return visualClusterOpen;
        }
        return isProtectedWynnGlyph(event.codePoint())
                && (visualClusterOpen || (proseBefore && proseAfter));
    }

    private static boolean hasAmbiguousForeignNaturalText(List<GlyphEvent> events,
                                                          String proseFont) {
        Map<String, Integer> letters = new java.util.HashMap<>();
        Map<String, Integer> whitespace = new java.util.HashMap<>();
        for (GlyphEvent event : events) {
            if (event == null || Objects.equals(event.fontId(), proseFont)) continue;
            if (Character.isLetter(event.codePoint())) {
                letters.merge(event.fontId(), 1, Integer::sum);
            } else if (Character.isWhitespace(event.codePoint())) {
                whitespace.merge(event.fontId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : letters.entrySet()) {
            if (entry.getValue() >= 2 && whitespace.getOrDefault(entry.getKey(), 0) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNaturalLanguageGlyphs(List<SemanticGlyph> glyphs) {
        if (glyphs == null) {
            return false;
        }
        for (SemanticGlyph glyph : glyphs) {
            if (glyph != null && Character.isLetter(glyph.codePoint())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNaturalLanguageAfter(List<GlyphEvent> events, int startIndex,
                                                   String proseFont) {
        if (events == null) {
            return false;
        }
        for (int index = Math.max(0, startIndex); index < events.size(); index++) {
            GlyphEvent candidate = events.get(index);
            if (candidate != null
                    && Objects.equals(candidate.fontId(), proseFont)
                    && isSemanticCodePoint(candidate.codePoint())
                    && !isSymbol(candidate.codePoint())
                    && Character.isLetter(candidate.codePoint())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSymbol(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean containsNaturalLanguage(CharSequence text) {
        for (int index = 0; index < text.length(); ) {
            int codePoint = Character.codePointAt(text, index);
            if (Character.isLetter(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    static boolean isSafeTranslation(@Nullable String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (!isSemanticCodePoint(codePoint) || isSymbol(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /** BODY overlays may contain only ordinary prose that the local CJK font can measure. */
    static boolean isSafeBodyOverlayText(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (!isOverlayMaskableCodePoint(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /** Source glyphs eligible for a Wynn BODY mask; PUA and visual controls stay local. */
    static boolean isOverlayMaskableCodePoint(int codePoint) {
        return isSemanticCodePoint(codePoint) && !isSymbol(codePoint);
    }

    static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    /**
     * Wynn's 26.1 dialogue pack also stores positioning/advance glyphs in the
     * otherwise-unassigned supplementary C/D ranges (for example U+CFFC9 and
     * U+D0018 around a nameplate). They are resource-pack control glyphs, not
     * natural-language text, even though Unicode does not classify them as
     * private use. This predicate is deliberately confined to the exact Wynn
     * dialogue projection rather than changing generic text detection.
     */
    private static boolean isProtectedWynnGlyph(int codePoint) {
        return isPrivateUse(codePoint)
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF);
    }

    private static boolean isControl(int codePoint) {
        return (codePoint >= 0 && codePoint < 0x20) || codePoint == 0x7F;
    }

    @Nullable
    private static ChatFormatting legacyFormatting(int codePoint) {
        if (codePoint > Character.MAX_VALUE) {
            return null;
        }
        char value = Character.toLowerCase((char) codePoint);
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'k' && value <= 'o') || value == 'r')) {
            return null;
        }
        return ChatFormatting.getByCode(value);
    }

    public Component sourceActionbar() {
        return sourceActionbar;
    }

    public FormattedCharSequence sourceSequence() {
        return sourceSequence;
    }

    public List<Component> contentComponents() {
        return contentComponents;
    }

    public List<Component> optionComponents() {
        return optionComponents;
    }

    public List<SemanticSlot> contentSlots() {
        return contentSlots;
    }

    public List<SemanticSlot> optionSlots() {
        return optionSlots;
    }

    public String contentFingerprint() {
        return contentFingerprint;
    }

    public String optionsFingerprint() {
        return optionsFingerprint;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public OptionVisibility optionVisibility() {
        return optionVisibility;
    }

    public boolean terminalBodyPunctuation() {
        String body = slotText(contentSlots, SemanticKind.BODY).stripTrailing();
        if (body.isEmpty()) {
            return false;
        }
        int last = body.codePointBefore(body.length());
        return switch (last) {
            case '.', '!', '?', 0x2026, 0x3002, 0xFF01, 0xFF1F -> true;
            default -> false;
        };
    }

    public boolean isSemanticPrefixOf(WynnDialogueProjection newer) {
        if (newer == null || !Objects.equals(sessionKey, newer.sessionKey)) {
            return false;
        }
        String currentBody = slotText(contentSlots, SemanticKind.BODY);
        String newerBody = slotText(newer.contentSlots, SemanticKind.BODY);
        return !currentBody.isEmpty() && newerBody.startsWith(currentBody);
    }

    /** Exact render-stream equality used instead of serializing the Component tree again. */
    public boolean hasSameLayout(@Nullable WynnDialogueProjection other) {
        return other != null
                && optionVisibility == other.optionVisibility
                && events.equals(other.events);
    }

    @Nullable
    public WynnDialogueRenderPlan bindTranslations(@Nullable List<Component> translatedContent,
                                                    @Nullable List<Component> translatedOptions) {
        if (translatedContent == null || translatedContent.size() != contentSlots.size()) {
            SimpleTranslateMod.getLogger().debug(
                    "Wynn dialogue bind rejected contentSlots={} translatedSlots={}",
                    contentSlots.size(), translatedContent == null ? -1 : translatedContent.size());
            return null;
        }
        if (translatedOptions == null || translatedOptions.size() != optionSlots.size()) {
            SimpleTranslateMod.getLogger().debug(
                    "Wynn dialogue bind rejected optionSlots={} translatedSlots={}",
                    optionSlots.size(), translatedOptions == null ? -1 : translatedOptions.size());
            return null;
        }
        List<WynnDialogueRenderPlan.TranslatedSlot> bound = new ArrayList<>(
                translatedContent.size() + translatedOptions.size());
        if (!bindGroup(contentSlots, translatedContent, bound, true)
                || !bindGroup(optionSlots, translatedOptions, bound,
                optionVisibility == OptionVisibility.VISIBLE)) {
            return null;
        }
        // A fully unchanged response has nothing to render. Keep vanilla's
        // original actionbar instead of installing an empty marker plan.
        if (bound.isEmpty()) {
            return null;
        }
        return new WynnDialogueRenderPlan(sourceActionbar, events, List.copyOf(bound));
    }

    private boolean bindGroup(List<SemanticSlot> slots,
                              List<Component> translations,
                              List<WynnDialogueRenderPlan.TranslatedSlot> target,
                              boolean sourceVisible) {
        for (int index = 0; index < slots.size(); index++) {
            SemanticSlot slot = slots.get(index);
            Component translated = translations.get(index);
            BoundBody boundBody = slot.kind() == SemanticKind.BODY
                    ? bindComponentBody(slot, translated) : null;
            String comparableText = boundBody == null
                    ? (translated == null ? null : translated.getString())
                    : boundBody.comparableText();
            String renderedText = boundBody == null ? comparableText : boundBody.renderedText();
            if (slot.kind() == SemanticKind.BODY && boundBody == null) {
                // A BODY source stream is indivisible: an invalid model result,
                // stale cache entry or unsafe glyph ledger must keep only this
                // paragraph local. NAME/CONTROL slots from the same count-safe
                // response may still be rendered if their own verification holds.
                SimpleTranslateMod.getLogger().debug(
                        "Wynn dialogue BODY binding rejected; keeping source paragraph");
                continue;
            }
            if (!isSafeTranslation(renderedText)) return false;
            if (slot.sourceText().equals(comparableText)) {
                // Unchanged semantic slots stay on Wynn's original glyph
                // stream. This is expected for proper names such as Aledar and
                // for options the model intentionally leaves untranslated. Do
                // not mask or redraw them, but keep binding every changed slot
                // from the same count-safe Component JSON response.
                continue;
            }
            Component overlay = boundBody == null
                    ? bindStyledOverlay(slot, translated) : boundBody.overlay();
            if (overlay == null || !Objects.equals(renderedText, overlay.getString())) {
                return false;
            }
            target.add(new WynnDialogueRenderPlan.TranslatedSlot(slot, overlay, sourceVisible,
                    boundBody == null ? List.of() : boundBody.maskOrdinals(),
                    boundBody == null ? List.of() : boundBody.dockedIcons()));
        }
        return true;
    }

    /**
     * BODY has one semantic source paragraph and one translated paragraph. The
     * complete source anchor set remains local exclusively for masking verified
     * prose; it is never reconstructed from provider text. Response colours are
     * never trusted either: every translated span is mapped back to a matching
     * source appearance, and a span matching nothing keeps the source BODY.
     */
    @Nullable
    private BoundBody bindComponentBody(SemanticSlot slot, @Nullable Component translated) {
        if (slot == null || translated == null || slot.bodyAnchors().isEmpty()) {
            return null;
        }
        List<StyledLeaf> leaves = bodyStyledLeaves(translated);
        if (leaves == null || leaves.isEmpty()) {
            SimpleTranslateMod.getLogger().debug(
                    "Wynn dialogue BODY paragraph rejected non-literal Component response");
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (StyledLeaf leaf : leaves) {
            value.append(leaf.text());
        }
        if (!isSafeBodyOverlayText(value.toString())
                || !hasOverlaySafeBodyStyles(slot.bodyAnchors())) {
            SimpleTranslateMod.getLogger().debug("Wynn dialogue BODY paragraph rejected unsafe result");
            return null;
        }
        Component overlay = buildBodyOverlay(slot, leaves, value.toString());
        if (overlay == null || !Objects.equals(value.toString(), overlay.getString())) {
            SimpleTranslateMod.getLogger().debug(
                    "Wynn dialogue BODY paragraph rejected unmappable appearance");
            return null;
        }
        java.util.BitSet seenOrdinals = new java.util.BitSet();
        List<Integer> maskOrdinals = new ArrayList<>();
        int previousOrdinal = -1;
        for (BodyAnchor anchor : slot.bodyAnchors()) {
            if (anchor.regionIndex() < 0 || anchor.regionIndex() >= slot.regions().size()) {
                return null;
            }
            LineRegion region = slot.regions().get(anchor.regionIndex());
            if (anchor.line() != region.line()
                    || bodyCarrierFamily(anchor.line(), anchor.sourceStyle()) == null
                    || anchor.runIndex() < 0 || anchor.runIndex() >= region.styledRuns().size()) {
                return null;
            }
            StyledRun sourceRun = region.styledRuns().get(anchor.runIndex());
            if (anchor.maskOrdinals().isEmpty()
                    || !anchor.maskOrdinals().equals(sourceRun.maskOrdinals())
                    || !WynnSemanticStyle.sameBodyOverlayAppearance(
                    anchor.sourceStyle(), sourceRun.sourceStyle())) {
                return null;
            }
            for (int ordinal : anchor.maskOrdinals()) {
                if (ordinal < region.startOrdinal() || ordinal >= region.endOrdinal()
                        || ordinal < 0 || ordinal >= events.size()
                        || ordinal <= previousOrdinal || seenOrdinals.get(ordinal)
                        || region.visualBeforeOrdinals().contains(ordinal)
                        || region.visualAfterOrdinals().contains(ordinal)
                        || !isOverlayMaskableCodePoint(events.get(ordinal).codePoint())) {
                    return null;
                }
                seenOrdinals.set(ordinal);
                maskOrdinals.add(ordinal);
                previousOrdinal = ordinal;
            }
        }
        if (maskOrdinals.isEmpty()) {
            return null;
        }
        return new BoundBody(value.toString(), overlay, List.copyOf(maskOrdinals),
                computeDockedIcons(slot, leaves, overlay));
    }

    /**
     * An inline service icon keeps its source replay position only while the
     * response hides which translated span follows it. When the run after the
     * icon has a source appearance that never occurs before the icon and the
     * response preserved it, the icon docks into the translated flow
     * immediately before the first span with that appearance (Wynn bullets a
     * service name with its icon), so a shorter translated prefix no longer
     * strands the icon at the English x coordinate. Anything less certain
     * stays on the untouched source replay stream.
     */
    private List<DockedIcon> computeDockedIcons(SemanticSlot slot, List<StyledLeaf> leaves,
                                                Component overlay) {
        if (leaves.size() <= 1) {
            return List.of();
        }
        List<Integer> iconOrdinals = new ArrayList<>();
        for (LineRegion region : slot.regions()) {
            iconOrdinals.addAll(region.visualBeforeOrdinals());
            iconOrdinals.addAll(region.visualAfterOrdinals());
        }
        if (iconOrdinals.isEmpty()) {
            return List.of();
        }
        List<WynnDialogueRenderPlan.OverlaySpan> spans =
                WynnDialogueRenderPlan.normalizeOverlay(overlay).spans();
        List<DockedIcon> result = new ArrayList<>();
        for (int ordinal : iconOrdinals) {
            if (ordinal < 0 || ordinal >= events.size()) {
                continue;
            }
            BodyAnchor afterAnchor = firstAnchorAfter(slot, ordinal);
            if (afterAnchor == null || !appearanceAbsentBeforeOrdinal(slot, afterAnchor, ordinal)) {
                continue;
            }
            int charIndex = firstSpanStartWithAppearance(spans, afterAnchor.sourceStyle());
            if (charIndex < 0) {
                continue;
            }
            result.add(new DockedIcon(ordinal, charIndex));
        }
        return List.copyOf(result);
    }

    @Nullable
    private static BodyAnchor firstAnchorAfter(SemanticSlot slot, int ordinal) {
        for (BodyAnchor anchor : slot.bodyAnchors()) {
            if (!anchor.maskOrdinals().isEmpty() && anchor.maskOrdinals().getFirst() > ordinal) {
                return anchor;
            }
        }
        return null;
    }

    /**
     * The appearance of the run directly after the icon must not occur in any
     * run before the icon: otherwise the first matching translated span could
     * belong to the pre-icon text and the icon would dock too early. Repeats
     * of that appearance after the icon are safe — Wynn wraps one keyword
     * across physical rows as several same-appearance runs, and the first
     * matching translated span is still the keyword's start.
     */
    private static boolean appearanceAbsentBeforeOrdinal(SemanticSlot slot, BodyAnchor afterAnchor,
                                                         int ordinal) {
        for (BodyAnchor candidate : slot.bodyAnchors()) {
            if (!candidate.maskOrdinals().isEmpty()
                    && candidate.maskOrdinals().getLast() < ordinal
                    && WynnSemanticStyle.sameBodyOverlayAppearance(
                    candidate.sourceStyle(), afterAnchor.sourceStyle())) {
                return false;
            }
        }
        return true;
    }

    private static int firstSpanStartWithAppearance(List<WynnDialogueRenderPlan.OverlaySpan> spans,
                                                    Style appearance) {
        int offset = 0;
        for (WynnDialogueRenderPlan.OverlaySpan span : spans) {
            if (WynnSemanticStyle.sameBodyOverlayAppearance(span.style(), appearance)) {
                return offset;
            }
            offset += span.text().length();
        }
        return -1;
    }

    /** Ordered literal leaves of a BODY response with inherited styles resolved. */
    @Nullable
    private static List<StyledLeaf> bodyStyledLeaves(Component component) {
        List<StyledLeaf> leaves = new ArrayList<>();
        if (!collectBodyLeaves(component, Style.EMPTY, leaves) || leaves.isEmpty()) {
            return null;
        }
        return List.copyOf(leaves);
    }

    private static boolean collectBodyLeaves(Component component, Style inherited,
                                             List<StyledLeaf> target) {
        try {
            Style effective = component.getStyle().applyTo(inherited);
            if (component.getContents() instanceof PlainTextContents.LiteralContents literal) {
                if (!literal.text().isEmpty()) {
                    target.add(new StyledLeaf(literal.text(), effective));
                }
            } else if (!(component.getContents() instanceof PlainTextContents)) {
                // Translatable/score/selector contents have no stable literal
                // text for a mask-counted overlay; reject the complete BODY.
                return false;
            }
            for (Component sibling : component.getSiblings()) {
                if (!collectBodyLeaves(sibling, effective, target)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Maps every translated span back to a source BODY appearance. The model
     * may merge, split or reorder highlighted spans, but it may not invent a
     * colour: a span whose appearance matches no source run rejects the BODY
     * and keeps the source paragraph. A single flattened span is accepted in
     * the dominant source appearance (the one covering most source text).
     */
    @Nullable
    private static Component buildBodyOverlay(SemanticSlot slot, List<StyledLeaf> leaves,
                                              String fullText) {
        if (leaves.size() == 1) {
            return Component.literal(fullText).withStyle(
                    WynnSemanticStyle.forOverlay(dominantBodyAppearance(slot)));
        }
        MutableComponent overlay = Component.empty();
        for (StyledLeaf leaf : leaves) {
            Style matched = matchSourceAppearance(slot, leaf.style());
            if (matched == null) {
                return null;
            }
            overlay.append(Component.literal(leaf.text()).withStyle(
                    WynnSemanticStyle.forOverlay(matched)));
        }
        return overlay;
    }

    private static Style dominantBodyAppearance(SemanticSlot slot) {
        Style best = slot.sourceStyle();
        int bestCoverage = -1;
        List<BodyAnchor> anchors = slot.bodyAnchors();
        for (BodyAnchor candidateAnchor : anchors) {
            Style candidate = candidateAnchor.sourceStyle();
            int coverage = 0;
            for (BodyAnchor anchor : anchors) {
                if (WynnSemanticStyle.sameBodyOverlayAppearance(candidate, anchor.sourceStyle())) {
                    coverage += anchor.maskOrdinals().size();
                }
            }
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                best = candidate;
            }
        }
        return best;
    }

    @Nullable
    private static Style matchSourceAppearance(SemanticSlot slot, Style responseStyle) {
        for (BodyAnchor anchor : slot.bodyAnchors()) {
            if (WynnSemanticStyle.sameBodyOverlayAppearance(anchor.sourceStyle(), responseStyle)) {
                return anchor.sourceStyle();
            }
        }
        return null;
    }

    /** One translated BODY span with its fully inherited response style. */
    private record StyledLeaf(String text, Style style) {
        private StyledLeaf {
            text = text == null ? "" : text;
            style = style == null ? Style.EMPTY : style;
        }
    }

    private static List<String> componentTextLeaves(Component component) {
        if (component == null) {
            return List.of();
        }
        try {
            // BODY now has a single paragraph leaf. Non-BODY source trees may
            // still carry ordered local children, so their root sibling order
            // remains the authoritative leaf shape.
            if (!component.getSiblings().isEmpty()) {
                return component.getSiblings().stream()
                        .map(child -> child == null ? "" : child.getString())
                        .toList();
            }
            return List.of(component.getString());
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    @Nullable
    private static Component bindStyledOverlay(SemanticSlot slot, Component translated) {
        if (slot == null || translated == null) {
            return null;
        }
        try {
            List<String> values = componentTextLeaves(translated);
            List<Component> sourceLeaves = slot.requestComponent().getSiblings().isEmpty()
                    ? List.of(slot.requestComponent()) : slot.requestComponent().getSiblings();
            if (values.size() != sourceLeaves.size()) return null;
            MutableComponent overlay = Component.empty();
            for (int index = 0; index < values.size(); index++) {
                overlay.append(Component.literal(values.get(index)).withStyle(
                        WynnSemanticStyle.forOverlay(sourceLeaves.get(index).getStyle())));
            }
            return overlay;
        } catch (Throwable ignored) {
            return null;
        }
    }

    List<GlyphEvent> events() {
        return events;
    }

    public enum SemanticKind {
        NAME,
        BODY,
        CONTROL,
        OPTION
    }

    public enum OptionVisibility {
        NOT_PRESENT,
        PRELOADED_HIDDEN,
        VISIBLE,
        UNKNOWN
    }

    public record SemanticSlot(int index, SemanticKind kind, String sourceText,
                               Style sourceStyle, List<LineRegion> regions,
                               Component requestComponent, List<BodyAnchor> bodyAnchors) {
        public SemanticSlot {
            kind = Objects.requireNonNull(kind, "kind");
            sourceText = sourceText == null ? "" : sourceText;
            sourceStyle = sourceStyle == null ? Style.EMPTY : sourceStyle;
            regions = regions == null ? List.of() : List.copyOf(regions);
            requestComponent = requestComponent == null
                    ? Component.literal(sourceText).withStyle(
                    WynnSemanticStyle.forRequest(sourceStyle))
                    : requestComponent;
            bodyAnchors = bodyAnchors == null ? List.of() : List.copyOf(bodyAnchors);
        }

        public SemanticSlot(int index, SemanticKind kind, String sourceText,
                            Style sourceStyle, List<LineRegion> regions,
                            Component requestComponent) {
            this(index, kind, sourceText, sourceStyle, regions, requestComponent, List.of());
        }
    }

    public record BodyAnchor(int regionIndex, int runIndex, DialogueLine line,
                             Style sourceStyle, List<Integer> maskOrdinals) {
        public BodyAnchor {
            line = Objects.requireNonNull(line, "line");
            sourceStyle = sourceStyle == null ? Style.EMPTY : sourceStyle;
            maskOrdinals = maskOrdinals == null ? List.of() : List.copyOf(maskOrdinals);
        }
    }

    /** A validated single BODY paragraph plus all local source prose ordinals. */
    private record BoundBody(String comparableText, Component overlay,
                             List<Integer> maskOrdinals, List<DockedIcon> dockedIcons) {
        private BoundBody {
            overlay = overlay == null ? Component.empty() : overlay;
            maskOrdinals = maskOrdinals == null ? List.of() : List.copyOf(maskOrdinals);
            dockedIcons = dockedIcons == null ? List.of() : List.copyOf(dockedIcons);
        }

        private String renderedText() {
            return overlay.getString();
        }
    }

    /**
     * An inline icon docked into the translated BODY flow: the source glyph at
     * {@code ordinal} is drawn immediately before {@code charIndex} of the
     * normalized translated paragraph instead of at its source x coordinate.
     */
    public record DockedIcon(int ordinal, int charIndex) {
    }

    public record LineRegion(DialogueLine line, boolean inlineIconBefore, boolean inlineIconAfter,
                             String sourceText, Style sourceStyle,
                             int startOrdinal, int endOrdinal, List<Integer> maskOrdinals,
                             List<StyledRun> styledRuns, Component requestComponent,
                             List<Integer> visualBeforeOrdinals,
                             List<Integer> visualAfterOrdinals) {
        public LineRegion {
            line = Objects.requireNonNull(line, "line");
            sourceText = sourceText == null ? "" : sourceText;
            sourceStyle = sourceStyle == null ? Style.EMPTY : sourceStyle;
            maskOrdinals = maskOrdinals == null ? List.of() : List.copyOf(maskOrdinals);
            styledRuns = styledRuns == null ? List.of() : List.copyOf(styledRuns);
            requestComponent = requestComponent == null
                    ? Component.literal(sourceText).withStyle(
                    WynnSemanticStyle.forRequest(sourceStyle))
                    : requestComponent;
            visualBeforeOrdinals = visualBeforeOrdinals == null
                    ? List.of() : List.copyOf(visualBeforeOrdinals);
            visualAfterOrdinals = visualAfterOrdinals == null
                    ? List.of() : List.copyOf(visualAfterOrdinals);
        }
    }

    public record StyledRun(String text, Style sourceStyle, List<Integer> maskOrdinals) {
        public StyledRun {
            text = text == null ? "" : text;
            sourceStyle = sourceStyle == null ? Style.EMPTY : sourceStyle;
            maskOrdinals = maskOrdinals == null ? List.of() : List.copyOf(maskOrdinals);
        }
    }

    private record SemanticGlyph(int codePoint, int ordinal, Style style) {
        private SemanticGlyph {
            style = style == null ? Style.EMPTY : style;
        }
    }

    public enum DialogueLine {
        NAMEPLATE(-1),
        BODY_0(0), BODY_1(1), BODY_2(2), BODY_3(3), BODY_4(4),
        CHOICE_0(0), CHOICE_1(1), CHOICE_2(2), CHOICE_3(3),
        CONTROL(-1);

        private final int lineIndex;

        DialogueLine(int lineIndex) {
            this.lineIndex = lineIndex;
        }

        @Nullable
        static DialogueLine fromFont(String fontId) {
            if (fontId == null || !fontId.startsWith(FONT_PREFIX)) {
                return null;
            }
            if ((FONT_PREFIX + "nameplate").equals(fontId)) {
                return NAMEPLATE;
            }
            if ((FONT_PREFIX + "control").equals(fontId)) {
                return CONTROL;
            }
            Matcher body = BODY_FONT.matcher(fontId);
            if (body.matches()) {
                return bodyLines().get(Integer.parseInt(body.group(1)));
            }
            Matcher choice = CHOICE_FONT.matcher(fontId);
            if (choice.matches()) {
                return choiceLines().get(Integer.parseInt(choice.group(1)));
            }
            return null;
        }

        private static List<DialogueLine> bodyLines() {
            return List.of(BODY_0, BODY_1, BODY_2, BODY_3, BODY_4);
        }

        private boolean isBody() {
            return this == BODY_0 || this == BODY_1 || this == BODY_2
                    || this == BODY_3 || this == BODY_4;
        }

        int bodyRowIndex() {
            return isBody() ? lineIndex : -1;
        }

        private static List<DialogueLine> choiceLines() {
            return List.of(CHOICE_0, CHOICE_1, CHOICE_2, CHOICE_3);
        }
    }

    record GlyphEvent(int ordinal, int sourceIndex, Style style, String fontId, int codePoint) {
        GlyphEvent {
            style = style == null ? Style.EMPTY : style;
            fontId = fontId == null ? "" : fontId.toLowerCase(Locale.ROOT);
        }
    }

    static final class EventSequence implements FormattedCharSequence {
        private final List<GlyphEvent> events;

        EventSequence(List<GlyphEvent> events) {
            this.events = List.copyOf(events);
        }

        int size() {
            return events.size();
        }

        GlyphEvent eventAt(int ordinal) {
            return events.get(ordinal);
        }

        EventSequence slice(int fromInclusive, int toExclusive) {
            if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > events.size()) {
                return new EventSequence(List.of());
            }
            List<GlyphEvent> part = new ArrayList<>(toExclusive - fromInclusive);
            for (int index = fromInclusive; index < toExclusive; index++) {
                part.add(events.get(index));
            }
            return new EventSequence(part);
        }

        @Override
        public boolean accept(FormattedCharSink output) {
            if (output == null) {
                return false;
            }
            for (GlyphEvent event : events) {
                if (!output.accept(event.sourceIndex(), event.style(), event.codePoint())) {
                    return false;
                }
            }
            return true;
        }
    }
}
