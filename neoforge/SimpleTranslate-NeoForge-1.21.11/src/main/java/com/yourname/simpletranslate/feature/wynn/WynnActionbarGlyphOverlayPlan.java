package com.yourname.simpletranslate.feature.wynn;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Direct, glyph-level Wynn selector actionbar translation.
 *
 * <p>Wynn's selector resource-pack font is a coordinate stream: private-use
 * glyphs and even ordinary spaces move the pen before later icons and labels
 * are drawn.  Replacing a Component therefore cannot be safe.  This plan
 * snapshots the <em>actual</em> visual-order sequence given to vanilla, hides
 * only the semantic English glyph pixels while preserving their original
 * advances, then overlays Chinese at full-prefix positions from that same
 * sequence. Wynn's resource pack also moves selector-font vertices in its
 * text shader (for example {@code center_left} is shifted by half a screen in
 * both axes). The source font carries that shader marker, but the default font
 * required for Chinese does not, so the plan mirrors the verified shader
 * anchor in the GUI pose. No raw PUA/icon glyph is manually replayed by this
 * class.</p>
 *
     * <p>The {@link #isCurrentGlyphMasked()} bridge is called from the
     * private {@code Font.PreparedTextBuilder} mixin. Rendering in
 * {@link GuiGraphics} is deferred, so a render-wide flag would be
 * lost before Font consumes the sequence.  {@link EventSequence} instead
 * scopes a thread-local marker around each individual sink callback.</p>
 */
public final class WynnActionbarGlyphOverlayPlan {
    private static final Pattern DYNAMIC_NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,:]\\d+)*%?");
    private static final float WIDTH_EPSILON = 0.001F;
    // Selector slots are single-line, fixed-anchor regions. Below this scale
    // Chinese glyphs become crowded enough to overlap visually, so the plan
    // atomically leaves the source glyph stream untouched instead.
    private static final float MIN_READABLE_SCALE_X = 0.75F;
    private static final ThreadLocal<CurrentGlyph> CURRENT_GLYPH =
            ThreadLocal.withInitial(CurrentGlyph::new);

    private WynnActionbarGlyphOverlayPlan() {
    }

    /**
     * Produces a plan projection only when the exact Wynn selector fonts,
     * private-use anchors, and known grammar match the live actionbar.
     */
    @Nullable
    public static Projection project(@Nullable Component source) {
        if (source == null || !WynncraftProfile.matchesActionbar(source)) {
            return null;
        }
        return projectInternal(source);
    }

    /** Structural alias retained for fixture and metadata callers. */
    @Nullable
    public static Projection projectStructure(@Nullable Component source) {
        return project(source);
    }

    @Nullable
    private static Projection projectInternal(Component source) {
        List<GlyphEvent> events = snapshot(source);
        return events.isEmpty() ? null : buildProjection(source, events);
    }

    @Nullable
    private static Projection buildProjection(Component source, List<GlyphEvent> events) {
        EventSequence sourceSequence = new EventSequence(events, new BitSet(events.size()));
        List<SemanticSlot> slots = collectSlots(events);
        if (slots.isEmpty()) {
            return null;
        }
        return new Projection(source, sourceSequence, slots);
    }

    private static List<GlyphEvent> snapshot(Component source) {
        List<GlyphEvent> events = new ArrayList<>();
        try {
            source.getVisualOrderText().accept((position, style, codePoint) -> {
                events.add(new GlyphEvent(events.size(), position,
                        style == null ? Style.EMPTY : style, codePoint));
                return true;
            });
            // 26.1.1's default Language visual sequence returns false after a
            // complete traversal (`FormattedText.visit(...).isPresent()`),
            // while Font intentionally ignores that terminal boolean.  The
            // captured callbacks are authoritative; discarding them based on
            // the return value would reject every ordinary actionbar.
            return List.copyOf(events);
        } catch (Throwable ignored) {
            // The source actionbar remains visible through vanilla if a future
            // language/resource-pack sequence cannot be inspected safely.
            return List.of();
        }
    }

    /**
     * Builds phrase slots from real visual-order atoms.  A single regular space
     * may join two compatible English atoms (for example "Left-Click to play").
     * All layout grammar is a hard boundary: PUA, controls, arrows, protected
     * fonts, style effects, repeated spaces and decorative letter grids remain
     * source-only.
     */
    private static List<SemanticSlot> collectSlots(List<GlyphEvent> events) {
        List<SemanticSlot> slots = new ArrayList<>();
        int cursor = 0;
        while (cursor < events.size()) {
            GlyphEvent first = events.get(cursor);
            if (!isNaturalAtom(events, cursor)) {
                cursor++;
                continue;
            }

            int start = cursor;
            int end = cursor + 1;
            Style style = first.style();
            ScreenAnchor screenAnchor = semanticScreenAnchor(style);
            // Only the two public selector text regions are free-form
            // natural language. Other anchor fonts carry status/debug labels
            // such as AS8 and decorative card data; translating those would
            // recreate the stray top/right text seen in earlier overlays.
            // TOP_MIDDLE is handled separately by the exact
            // CreateaCharacter matcher below.
            if (screenAnchor != ScreenAnchor.CENTER_LEFT
                    && screenAnchor != ScreenAnchor.BOTTOM_MIDDLE) {
                cursor++;
                continue;
            }
            StringBuilder text = new StringBuilder();
            text.appendCodePoint(first.codePoint());
            boolean containsLetter = isAsciiLetter(first.codePoint());

            while (end < events.size()) {
                GlyphEvent next = events.get(end);
                if (isNaturalAtom(events, end)
                        && sameSemanticStyle(style, next.style())
                        && isSourceRunContinuation(events.get(end - 1), next)) {
                    text.appendCodePoint(next.codePoint());
                    containsLetter |= isAsciiLetter(next.codePoint());
                    end++;
                    continue;
                }

                // Exactly one normal ASCII space may live inside a phrase. A
                // second space is a Wynn fixed gap and ends the semantic slot.
                if (isSinglePlainSpace(events, end, style)
                        && end + 1 < events.size()
                        && isNaturalAtom(events, end + 1)
                        && sameSemanticStyle(style, events.get(end + 1).style())
                        && isSourceRunContinuation(events.get(end - 1), events.get(end))
                        && isSourceRunContinuation(events.get(end), events.get(end + 1))) {
                    text.append(' ');
                    text.appendCodePoint(events.get(end + 1).codePoint());
                    containsLetter |= isAsciiLetter(events.get(end + 1).codePoint());
                    end += 2;
                    continue;
                }
                break;
            }

            // Do not send isolated punctuation/numbers to the model.  The
            // character-selection grammars all have English letters in every
            // useful phrase, while dynamic numeric values remain local.
            if (containsLetter && text.length() > 0
                    && (screenAnchor != ScreenAnchor.BOTTOM_MIDDLE
                    || isKnownBottomPrompt(text.toString()))) {
                slots.add(new SemanticSlot(slots.size(), text.toString(), style,
                        screenAnchor, start, end, contiguousOrdinals(start, end), false));
                cursor = end;
            } else {
                cursor = start + 1;
            }
        }
        SemanticSlot topMiddle = collectCreateCharacterSlot(events);
        if (topMiddle != null) {
            slots.add(topMiddle);
        }
        slots.sort(Comparator.comparingInt(SemanticSlot::startOrdinal));
        List<SemanticSlot> indexed = new ArrayList<>(slots.size());
        for (int index = 0; index < slots.size(); index++) {
            SemanticSlot slot = slots.get(index);
            indexed.add(new SemanticSlot(index, slot.sourceText(), slot.sourceStyle(),
                    slot.screenAnchor(), slot.startOrdinal(), slot.endOrdinal(),
                    slot.maskOrdinals(), slot.regrouped()));
        }
        return List.copyOf(indexed);
    }

    private static boolean isKnownBottomPrompt(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*-\\s*", "-")
                .replaceAll("\\s*/\\s*", "/");
        return normalized.equals("left-click to select")
                || normalized.equals("left-click to play")
                || normalized.equals("scroll up/down to browse")
                || normalized.equals("right-click to return")
                || normalized.equals("right-click to switch");
    }

    private static boolean isSourceRunContinuation(GlyphEvent previous, GlyphEvent next) {
        if (previous == null || next == null || previous.sourceIndex() < 0 || next.sourceIndex() < 0) {
            return false;
        }
        return next.sourceIndex() == previous.sourceIndex()
                + Character.charCount(previous.codePoint());
    }

    private static List<Integer> contiguousOrdinals(int start, int end) {
        List<Integer> ordinals = new ArrayList<>(Math.max(0, end - start));
        for (int ordinal = start; ordinal < end; ordinal++) {
            ordinals.add(ordinal);
        }
        return List.copyOf(ordinals);
    }

    /**
     * Wynn emits the selector heading as a positioned glyph grid rather than
     * one ordinary string.  This is the only interleaved grid we reconstruct:
     * every visible letter must belong to the top-middle selector font and the
     * exact letter stream must be {@code CreateaCharacter}.  Positioning PUA
     * and controls stay on the source stream and only the letter ordinals are
     * hidden when a complete translation is available.
     */
    @Nullable
    private static SemanticSlot collectCreateCharacterSlot(List<GlyphEvent> events) {
        StringBuilder letters = new StringBuilder();
        List<Integer> maskOrdinals = new ArrayList<>();
        Style sourceStyle = null;
        int start = -1;
        int end = -1;
        for (GlyphEvent event : events) {
            if (semanticScreenAnchor(event.style()) != ScreenAnchor.TOP_MIDDLE) {
                continue;
            }
            int codePoint = event.codePoint();
            if (isAsciiLetter(codePoint)) {
                if (sourceStyle == null) {
                    sourceStyle = event.style();
                    start = event.ordinal();
                }
                letters.appendCodePoint(codePoint);
                maskOrdinals.add(event.ordinal());
                end = event.ordinal() + 1;
                continue;
            }
            if (codePoint == ' ' || isPrivateUse(codePoint) || isControl(codePoint)
                    || codePoint == '\u00A7') {
                continue;
            }
            // Any other top-middle printable glyph makes the grid ambiguous.
            return null;
        }
        if (!"CreateaCharacter".contentEquals(letters) || sourceStyle == null
                || start < 0 || end <= start || maskOrdinals.size() != 16) {
            return null;
        }
        return new SemanticSlot(-1, "Create a Character", sourceStyle,
                ScreenAnchor.TOP_MIDDLE, start, end, List.copyOf(maskOrdinals), true);
    }

    private static boolean isNaturalAtom(List<GlyphEvent> events, int index) {
        if (index < 0 || index >= events.size()) {
            return false;
        }
        GlyphEvent event = events.get(index);
        int codePoint = event.codePoint();
        if (isPrivateUse(codePoint) || isControl(codePoint) || isArrow(codePoint)
                || WynncraftProfile.isProtectedFont(event.style())
                || semanticScreenAnchor(event.style()) == null
                || hasUnsafeVisualEffect(event.style()) || isDecorativeGridLetter(events, index)) {
            return false;
        }
        return isNaturalCodePoint(codePoint);
    }

    private static boolean isNaturalCodePoint(int codePoint) {
        // Digits are deliberately raw anchors rather than model input.  This
        // lets a ticking value such as "Combat Lv. 4" -> "Combat Lv. 5"
        // reuse the phrase translation while the current number and its exact
        // selector-font advance remain local to this frame.
        return isAsciiLetter(codePoint) || switch (codePoint) {
            case '-', '\'', '.', ',', ':', ';', '!', '?', '/', '+', '&', '(', ')' -> true;
            default -> false;
        };
    }

    private static boolean isAsciiLetter(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z');
    }

    private static boolean isSinglePlainSpace(List<GlyphEvent> events, int index, Style expectedStyle) {
        if (index < 0 || index >= events.size()) {
            return false;
        }
        GlyphEvent event = events.get(index);
        if (event.codePoint() != ' ' || !sameSemanticStyle(expectedStyle, event.style())
                || WynncraftProfile.isProtectedFont(event.style()) || hasUnsafeVisualEffect(event.style())) {
            return false;
        }
        return (index == 0 || events.get(index - 1).codePoint() != ' ')
                && (index + 1 >= events.size() || events.get(index + 1).codePoint() != ' ');
    }

    private static boolean sameSemanticStyle(Style first, Style second) {
        return WynnSemanticStyle.sameStableStyle(first, second);
    }

    private static boolean hasUnsafeVisualEffect(Style style) {
        Style safe = style == null ? Style.EMPTY : style;
        return safe.isObfuscated();
    }

    /**
     * A C-PUA-r-PUA-e style decorative grid is never reconstructed or guessed.
     * Real Wynn streams may place zero-width/control atoms beside each PUA, so
     * this scans across a bounded protected separator instead of assuming the
     * neighbouring letter is exactly two callbacks away.
     */
    private static boolean isDecorativeGridLetter(List<GlyphEvent> events, int index) {
        if (!isAsciiLetter(events.get(index).codePoint())) {
            return false;
        }
        return hasDecorativeLetterAcrossAnchor(events, index, -1)
                || hasDecorativeLetterAcrossAnchor(events, index, 1);
    }

    private static boolean hasDecorativeLetterAcrossAnchor(List<GlyphEvent> events,
                                                            int index, int direction) {
        boolean sawProtectedAnchor = false;
        int cursor = index + direction;
        int inspected = 0;
        while (cursor >= 0 && cursor < events.size() && inspected++ < 8) {
            int codePoint = events.get(cursor).codePoint();
            if (isPrivateUse(codePoint) || isControl(codePoint)) {
                sawProtectedAnchor = true;
                cursor += direction;
                continue;
            }
            if (codePoint == ' ' || codePoint == 0x200C) {
                cursor += direction;
                continue;
            }
            return sawProtectedAnchor && isAsciiLetter(codePoint)
                    && sameSemanticStyle(events.get(index).style(), events.get(cursor).style());
        }
        return false;
    }

    /** Maps the exact Wynn selector shader-font suffix to the shared 3x3 anchor grid. */
    @Nullable
    private static ScreenAnchor semanticScreenAnchor(@Nullable Style style) {
        String font = WynncraftProfile.fontId(style);
        if (!font.contains("hud/selector/") && !font.contains("/selector/")) {
            return null;
        }
        if (font.contains("/center_left")) {
            return ScreenAnchor.CENTER_LEFT;
        }
        if (font.contains("/center_middle")) {
            return ScreenAnchor.CENTER_MIDDLE;
        }
        if (font.contains("/center_right")) {
            return ScreenAnchor.CENTER_RIGHT;
        }
        if (font.contains("/top_left")) {
            return ScreenAnchor.TOP_LEFT;
        }
        if (font.contains("/top_middle")) {
            return ScreenAnchor.TOP_MIDDLE;
        }
        if (font.contains("/top_right")) {
            return ScreenAnchor.TOP_RIGHT;
        }
        if (font.contains("/bottom_left")) {
            return ScreenAnchor.BOTTOM_LEFT;
        }
        if (font.contains("/bottom_middle")) {
            return ScreenAnchor.BOTTOM_MIDDLE;
        }
        if (font.contains("/bottom_right")) {
            return ScreenAnchor.BOTTOM_RIGHT;
        }
        return null;
    }

    private static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xC0000 && codePoint <= 0xDFFFF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    private static boolean isControl(int codePoint) {
        return (codePoint >= 0 && codePoint < 0x20) || codePoint == 0x7F
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private static boolean isArrow(int codePoint) {
        return switch (codePoint) {
            case 0x2190, 0x2191, 0x2192, 0x2193, 0x2194, 0x21D0, 0x21D2,
                    0x00AB, 0x00BB, 0x25B6, 0x25C0 -> true;
            default -> false;
        };
    }

    private static boolean isSafeTranslation(@Nullable String text) {
        // Count and Component parsing are the shared response contract. Source
        // icons/controls never entered the request and remain in the local
        // event stream, so a second character allowlist here only creates
        // false whole-frame fallbacks.
        return text != null;
    }

    private static String cacheKey(List<SemanticSlot> slots) {
        StringBuilder key = new StringBuilder("wynn.glyph-overlay.v5");
        for (SemanticSlot slot : slots) {
            key.append('|').append(DYNAMIC_NUMBER_PATTERN.matcher(slot.sourceText()).replaceAll("⟦N⟧"))
                    .append('@').append(WynnSemanticStyle.visualFingerprint(slot.sourceStyle()))
                    .append(':').append(slot.screenAnchor());
        }
        return key.toString();
    }

    /**
     * Visible to logic tests and the mixin bridge without exposing the thread
     * local itself. Index arguments from Font are intentionally ignored: the
     * nest flag alone decides masking for the current accept callback.
     */
    public static boolean isCurrentGlyphMasked() {
        return CURRENT_GLYPH.get().masked;
    }

    /** Immutable actionbar source plus its request-ready semantic slots. */
    public static final class Projection {
        private final Component sourceActionbar;
        private final EventSequence sourceSequence;
        private final List<SemanticSlot> slots;
        private final List<Component> semanticComponents;
        private final String cacheKey;

        private Projection(Component sourceActionbar, EventSequence sourceSequence, List<SemanticSlot> slots) {
            this.sourceActionbar = sourceActionbar;
            this.sourceSequence = sourceSequence;
            this.slots = List.copyOf(slots);
            List<Component> request = new ArrayList<>(slots.size());
            for (SemanticSlot slot : slots) {
            request.add(Component.literal(slot.sourceText()).withStyle(
                    WynnSemanticStyle.forRequest(slot.sourceStyle())));
            }
            this.semanticComponents = List.copyOf(request);
            this.cacheKey = WynnActionbarGlyphOverlayPlan.cacheKey(this.slots);
        }

        public Component sourceActionbar() {
            return sourceActionbar;
        }

        /** A projection is usable only when the source snapshot and its slots exist. */
        public boolean valid() {
            return sourceActionbar != null && sourceSequence.size() > 0 && !slots.isEmpty();
        }

        /** Matches the existing HUD projection contract. */
        public boolean hasSlots() {
            return !slots.isEmpty();
        }

        /** This type is deliberately actionbar-only, never a tooltip projection. */
        public boolean isActionbar() {
            return true;
        }

        public FormattedCharSequence sourceSequence() {
            return sourceSequence;
        }

        public List<Component> semanticComponents() {
            return semanticComponents;
        }

        public List<SemanticSlot> slots() {
            return slots;
        }

        public String cacheKey() {
            return cacheKey;
        }

        @Nullable
        public Plan bindTranslations(@Nullable List<Component> translations) {
            if (translations == null || translations.size() != slots.size()) {
                return null;
            }
            BitSet mask = new BitSet(sourceSequence.size());
            List<TranslatedSlot> translatedSlots = new ArrayList<>(slots.size());
            for (int index = 0; index < slots.size(); index++) {
                SemanticSlot slot = slots.get(index);
                Component translatedComponent = translations.get(index);
                String translatedText = translatedComponent == null ? null : translatedComponent.getString();
                if (!isSafeTranslation(translatedText)) {
                    return null;
                }
                // An unchanged slot is a valid per-slot fallback (for example
                // a proper name). Leave that exact source range unmasked while
                // still installing translations for changed sibling slots.
                if (translatedText.equals(slot.sourceText())) {
                    continue;
                }
                for (int ordinal : slot.maskOrdinals()) {
                    if (ordinal >= 0 && ordinal < sourceSequence.size()
                            && sourceSequence.eventAt(ordinal).codePoint() != ' ') {
                        mask.set(ordinal);
                    }
                }
            Component overlay = Component.literal(translatedText).withStyle(
                    WynnSemanticStyle.forOverlay(slot.sourceStyle()));
                translatedSlots.add(new TranslatedSlot(slot, overlay));
            }
            if (translatedSlots.isEmpty()) {
                return null;
            }
            return new Plan(sourceActionbar, sourceSequence, new EventSequence(sourceSequence.events(), mask),
                    List.copyOf(translatedSlots));
        }
    }

    /** A natural-language source range in real visual-order event coordinates. */
    public record SemanticSlot(int index, String sourceText, Style sourceStyle,
                               ScreenAnchor screenAnchor, int startOrdinal, int endOrdinal,
                               List<Integer> maskOrdinals, boolean regrouped) {
        public SemanticSlot {
            sourceText = sourceText == null ? "" : sourceText;
            sourceStyle = sourceStyle == null ? Style.EMPTY : sourceStyle;
            screenAnchor = Objects.requireNonNull(screenAnchor, "screenAnchor");
            maskOrdinals = maskOrdinals == null ? List.of() : List.copyOf(maskOrdinals);
        }
    }

    /**
     * CPU equivalent of the Wynn text shader's clip-space {@code screenAnchor}
     * offsets. A clip offset of -1/+1 is half a GUI dimension; positive clip Y
     * points upward, hence the negative GUI-space Y factor.
     */
    public enum ScreenAnchor {
        TOP_LEFT(-0.5F, -1.0F),
        TOP_MIDDLE(0.0F, -1.0F),
        TOP_RIGHT(0.5F, -1.0F),
        CENTER_LEFT(-0.5F, -0.5F),
        CENTER_MIDDLE(0.0F, -0.5F),
        CENTER_RIGHT(0.5F, -0.5F),
        BOTTOM_LEFT(-0.5F, 0.0F),
        BOTTOM_MIDDLE(0.0F, 0.0F),
        BOTTOM_RIGHT(0.5F, 0.0F);

        private final float xFactor;
        private final float yFactor;

        ScreenAnchor(float xFactor, float yFactor) {
            this.xFactor = xFactor;
            this.yFactor = yFactor;
        }

        public float offsetX(int guiWidth) {
            return guiWidth * xFactor;
        }

        public float offsetY(int guiHeight) {
            return guiHeight * yFactor;
        }
    }

    /**
     * Client-thread draw plan.  If any exact-width invariant cannot be proven,
     * {@link #render} returns false and the Title mixin draws the untouched
     * source actionbar through vanilla.
     */
    public static final class Plan {
        private final Component sourceActionbar;
        private final EventSequence sourceSequence;
        private final EventSequence maskedSourceSequence;
        private final List<TranslatedSlot> translatedSlots;

        private Plan(Component sourceActionbar, EventSequence sourceSequence,
                     EventSequence maskedSourceSequence, List<TranslatedSlot> translatedSlots) {
            this.sourceActionbar = sourceActionbar;
            this.sourceSequence = sourceSequence;
            this.maskedSourceSequence = maskedSourceSequence;
            this.translatedSlots = translatedSlots;
        }

        public Component sourceActionbar() {
            return sourceActionbar;
        }

        public FormattedCharSequence sourceSequence() {
            return sourceSequence;
        }

        public FormattedCharSequence maskedSourceSequence() {
            return maskedSourceSequence;
        }

        @Nullable
        private Font cachedLayoutFont;
        private long cachedLayoutRevision = -1L;
        private Layout cachedLayout;

        public Layout resolveLayout(Font font) {
            if (font == null) {
                return null;
            }
            long revision = com.yourname.simpletranslate.core.ActiveFontManager.resourceRevision();
            if (font == this.cachedLayoutFont && revision == this.cachedLayoutRevision) {
                return this.cachedLayout;
            }
            Layout layout = resolveLayout(font.getSplitter()::stringWidth, sequence -> {
                Font.PreparedText prepared = font.prepareText(sequence, 0.0F, 0.0F,
                        0xFFFFFFFF, true, false, 0);
                ScreenRectangle bounds = prepared.bounds();
                return bounds == null ? null : new GlyphBounds(
                        bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
            });
            this.cachedLayoutFont = font;
            this.cachedLayoutRevision = revision;
            this.cachedLayout = layout;
            return layout;
        }

        /** Deterministic fixture seam using the active font's float advance function. */
        @Nullable
        public Layout resolveLayout(AdvanceMeasurer widths) {
            return resolveLayout(widths, null);
        }

        /**
         * Deterministic full-position seam. Production passes bounds from
         * {@link Font#prepareText(FormattedCharSequence, float, float, int, boolean, boolean, int)},
         * which exposes the real bitmap glyph ascent/top/left used by Wynn's
         * selector fonts. Fixtures may provide synthetic bounds directly.
         */
        @Nullable
        public Layout resolveLayout(AdvanceMeasurer widths, @Nullable GlyphBoundsMeasurer bounds) {
            if (widths == null) {
                return null;
            }
            float sourceWidth = widths.width(sourceSequence);
            float maskedWidth = widths.width(maskedSourceSequence);
            if (!finitePositiveOrZero(sourceWidth) || !approximatelyEqual(sourceWidth, maskedWidth)) {
                return null;
            }
            List<PositionedSlot> positions = new ArrayList<>(translatedSlots.size());
            for (TranslatedSlot translated : translatedSlots) {
                SemanticSlot slot = translated.source();
                float start = widths.width(sourceSequence.slice(0, slot.startOrdinal()));
                float end = widths.width(sourceSequence.slice(0, slot.endOrdinal()));
                float budget = end - start;
                FormattedCharSequence translatedSequence = translated.component().getVisualOrderText();
                float translatedWidth = widths.width(translatedSequence);
                if (!finite(start) || !finite(end) || !finitePositive(translatedWidth)) {
                    return null;
                }
                float drawX = start;
                float drawY = 0.0F;
                GlyphBounds sourceBounds = null;
                GlyphBounds targetBounds = null;
                if (bounds != null) {
                    sourceBounds = bounds.bounds(
                            sourceSequence.slice(slot.startOrdinal(), slot.endOrdinal()));
                    targetBounds = bounds.bounds(translatedSequence);
                    if (!validBounds(sourceBounds) || !validBounds(targetBounds)) {
                        return null;
                    }
                    if (slot.regrouped()) {
                        // Interleaved heading letters are positioned by PUA;
                        // their net advance is not a usable text-region width.
                        budget = sourceBounds.right() - sourceBounds.left();
                    }
                }
                if (!finitePositive(budget)) {
                    return null;
                }
                float scale = translatedWidth > budget ? budget / translatedWidth : 1.0F;
                if (!finitePositive(scale) || scale < MIN_READABLE_SCALE_X || scale > 1.0F) {
                    return null;
                }
                if (sourceBounds != null && targetBounds != null) {
                    // Align the visible target quad with the source quad. The
                    // horizontal bearing must be adjusted after scaling; y is
                    // unscaled and therefore uses the direct top difference.
                    drawX = start + sourceBounds.left() - targetBounds.left() * scale;
                    drawY = sourceBounds.top() - targetBounds.top();
                    if (!finite(drawX) || !finite(drawY)) {
                        return null;
                    }
                }
                positions.add(new PositionedSlot(slot, translated.component(), start, budget,
                        translatedWidth, scale, drawX, drawY));
            }
            return new Layout(sourceWidth, List.copyOf(positions));
        }

        public boolean render(GuiGraphics graphics, Font font,
                              int x, int y, int width, int color) {
            if (graphics == null || font == null || width != font.width(sourceActionbar)) {
                return false;
            }
            Layout layout = resolveLayout(font);
            if (layout == null) {
                return false;
            }
            float originalWidth = font.getSplitter().stringWidth(sourceActionbar.getVisualOrderText());
            if (!approximatelyEqual(layout.sourceWidth(), originalWidth)) {
                return false;
            }
            // Validate every built-in Wynn transform before masking any source
            // glyph. A late failure would leave an incomplete frame.
            for (PositionedSlot slot : layout.slots()) {
                ScreenAnchor anchor = slot.source().screenAnchor();
                if (!finite(anchor.offsetX(graphics.guiWidth()))
                        || !finite(anchor.offsetY(graphics.guiHeight()))
                        || !finitePositive(slot.scaleX())
                        || slot.scaleX() < MIN_READABLE_SCALE_X) {
                    return false;
                }
            }
            graphics.drawStringWithBackdrop(font, Component.empty(), x, y, width, color);
            graphics.drawString(font, maskedSourceSequence, x, y, color, true);
            for (PositionedSlot slot : layout.slots()) {
                ScreenAnchor anchor = slot.source().screenAnchor();
                graphics.pose().pushMatrix();
                try {
                    graphics.pose().translate(
                            x + slot.drawX() + anchor.offsetX(graphics.guiWidth()),
                            y + slot.drawY() + anchor.offsetY(graphics.guiHeight()));
                    if (slot.scaleX() != 1.0F) {
                        graphics.pose().scale(slot.scaleX(), 1.0F);
                    }
                    graphics.drawString(font, slot.component(), 0, 0, color, true);
                } finally {
                    graphics.pose().popMatrix();
                }
            }
            return true;
        }
    }

    @FunctionalInterface
    public interface AdvanceMeasurer {
        float width(FormattedCharSequence sequence);
    }

    @FunctionalInterface
    public interface GlyphBoundsMeasurer {
        @Nullable GlyphBounds bounds(FormattedCharSequence sequence);
    }

    /** Visible pixel bounds relative to the supplied text baseline. */
    public record GlyphBounds(float left, float top, float right, float bottom) {
    }

    /** Fully resolved Chinese overlay operation, exposed for fixtures. */
    public record PositionedSlot(SemanticSlot source, Component component, float x,
                                 float sourceWidth, float translatedWidth, float scaleX,
                                 float drawX, float drawY) {
    }

    /** Full float layout of the current glyph stream. */
    public record Layout(float sourceWidth, List<PositionedSlot> slots) {
    }

    private record TranslatedSlot(SemanticSlot source, Component component) {
    }

    private record GlyphEvent(int ordinal, int sourceIndex, Style style, int codePoint) {
    }

    /**
     * An immutable replay of the exact visual-order event stream.  It keeps
     * each original part-local source index, which is essential for vanilla's
     * bidi/component behavior, while using the ordinal only for our local mask.
     */
    private static final class EventSequence implements FormattedCharSequence {
        private final List<GlyphEvent> events;
        private final BitSet masked;

        private EventSequence(List<GlyphEvent> events, BitSet masked) {
            this.events = List.copyOf(events);
            this.masked = (BitSet) masked.clone();
        }

        private List<GlyphEvent> events() {
            return events;
        }

        private int size() {
            return events.size();
        }

        private GlyphEvent eventAt(int ordinal) {
            return events.get(ordinal);
        }

        private EventSequence slice(int fromInclusive, int toExclusive) {
            if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > events.size()) {
                return new EventSequence(List.of(), new BitSet());
            }
            List<GlyphEvent> part = new ArrayList<>(toExclusive - fromInclusive);
            BitSet partMask = new BitSet(toExclusive - fromInclusive);
            for (int index = fromInclusive; index < toExclusive; index++) {
                part.add(events.get(index));
                if (masked.get(index)) {
                    partMask.set(index - fromInclusive);
                }
            }
            return new EventSequence(part, partMask);
        }

        @Override
        public boolean accept(FormattedCharSink output) {
            if (output == null) {
                return false;
            }
            for (int ordinal = 0; ordinal < events.size(); ordinal++) {
                GlyphEvent event = events.get(ordinal);
                if (!acceptEvent(output, event, masked.get(ordinal))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean acceptEvent(FormattedCharSink output, GlyphEvent event, boolean masked) {
            if (!masked) {
                return output.accept(event.sourceIndex(), event.style(), event.codePoint());
            }
            CurrentGlyph current = CURRENT_GLYPH.get();
            boolean previousMasked = current.masked;
            current.masked = true;
            try {
                return output.accept(event.sourceIndex(), event.style(), event.codePoint());
            } finally {
                current.masked = previousMasked;
            }
        }
    }

    private static final class CurrentGlyph {
        private boolean masked;
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0.0F;
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }

    private static boolean finitePositiveOrZero(float value) {
        return Float.isFinite(value) && value >= 0.0F;
    }

    private static boolean approximatelyEqual(float first, float second) {
        return Float.isFinite(first) && Float.isFinite(second) && Math.abs(first - second) <= WIDTH_EPSILON;
    }

    private static boolean validBounds(@Nullable GlyphBounds bounds) {
        return bounds != null && finite(bounds.left()) && finite(bounds.top())
                && finite(bounds.right()) && finite(bounds.bottom())
                && bounds.right() > bounds.left() && bounds.bottom() > bounds.top();
    }
}
