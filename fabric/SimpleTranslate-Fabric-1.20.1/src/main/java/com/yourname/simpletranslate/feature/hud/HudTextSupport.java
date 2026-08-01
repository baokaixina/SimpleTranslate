package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.ComponentVisualProjection;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure HUD text templating and signature helpers, independent of the GUI mixin. */
public final class HudTextSupport {
    private static final Pattern COORDINATE_VALUE_PATTERN = Pattern.compile(
            "\\[\\s*[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*,\\s*[+-]?\\d+(?:[.,:]\\d+)*){1,2}\\s*\\]");
    private static final Pattern DYNAMIC_VALUE_PATTERN = Pattern.compile(
            "\\(?[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*/\\s*[+-]?\\d+(?:[.,:]\\d+)*)?%?\\)?");
    /** Existing actionbar/template markers are local migration data, never model input. */
    private static final Pattern LOCAL_LAYOUT_TOKEN_PATTERN =
            Pattern.compile("\\u27E6[^\\u27E6\\u27E7]*\\u27E7");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("⟦(1\\d{3})⟧");
    private static final int MAX_ACTIONBAR_PLACEHOLDERS = 1000;
    private static final int NO_ACTIONBAR_VARIABLE = -1;

    private HudTextSupport() {
    }

    public static ActionbarTemplate actionbarTemplate(@Nullable Component original) {
        if (original == null) {
            return new ActionbarTemplate(Component.empty(), List.of(), List.of());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(original, segments, Style.EMPTY, false);
        MutableComponent normalized = Component.empty();
        List<ActionbarVariable> variables = new ArrayList<>();
        List<ActionbarLeaf> leaves = new ArrayList<>();
        if (segments.isEmpty()) {
            appendTemplateText(normalized, original.getString(), Style.EMPTY, variables, leaves);
        } else {
            for (TextSegmentInfo segment : segments) {
                if (segment == null || segment.text == null || segment.text.isEmpty()) {
                    continue;
                }
                appendTemplateText(normalized, segment.text,
                        segment.style == null ? Style.EMPTY : segment.style, variables, leaves);
            }
        }
        return new ActionbarTemplate(normalized, List.copyOf(variables), List.copyOf(leaves));
    }

    @Nullable
    public static Component restoreActionbarVariables(Component translatedTemplate, ActionbarTemplate template) {
        if (translatedTemplate == null) {
            return null;
        }
        List<ActionbarVariable> variables = template == null ? List.of() : template.variables();
        if (variables.isEmpty()) {
            return translatedTemplate;
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(translatedTemplate, segments, Style.EMPTY, false);
        MutableComponent restored = Component.empty();
        int restoredVariables = 0;
        for (TextSegmentInfo segment : segments) {
            if (segment == null || segment.text == null || segment.text.isEmpty()) {
                continue;
            }
            Style style = segment.style == null ? Style.EMPTY : segment.style;
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(segment.text);
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) {
                    restored.append(Component.literal(segment.text.substring(cursor, matcher.start())).withStyle(style));
                }
                ActionbarVariable variable = variableAt(variables, matcher.group(1));
                if (variable == null) {
                    restored.append(Component.literal(matcher.group()).withStyle(style));
                } else {
                    restored.append(Component.literal(variable.value()).withStyle(variable.style()));
                    restoredVariables++;
                }
                cursor = matcher.end();
            }
            if (cursor < segment.text.length()) {
                restored.append(Component.literal(segment.text.substring(cursor)).withStyle(style));
            }
        }
        return restoredVariables == variables.size() ? restored : null;
    }

    public static String componentStyleSignature(@Nullable Component component) {
        if (component == null) {
            return "";
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        if (segments.isEmpty()) {
            return styleSignature(component.getStyle());
        }
        StringBuilder signature = new StringBuilder();
        for (TextSegmentInfo segment : segments) {
            if (segment == null) {
                continue;
            }
            signature.append(cleanText(segment.text)).append('@')
                    .append(styleSignature(segment.style == null ? Style.EMPTY : segment.style))
                    .append('\u0002');
        }
        return signature.toString();
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static String cleanText(String text) {
        return text == null ? "" : WHITESPACE.matcher(text.replace('\r', ' ').replace('\n', ' ').trim()).replaceAll(" ");
    }

    public static boolean isTechnicalText(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        boolean hasDigit = text.codePoints().anyMatch(Character::isDigit);
        StringBuilder letters = new StringBuilder();
        for (int index = 0; index < text.length(); ) {
            int protectedEnd = protectedActionbarTokenEnd(text, index);
            if (protectedEnd > index) {
                letters.append(' ');
                index = protectedEnd;
                continue;
            }
            int cp = text.codePointAt(index);
            letters.append(Character.isLetter(cp) ? new String(Character.toChars(cp)) : " ");
            index += Character.charCount(cp);
        }
        String value = letters.toString().trim();
        if (value.isEmpty()) {
            return true;
        }
        String[] tokens = value.split("\\s+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int length = token.codePointCount(0, token.length());
            boolean shortUppercase = length <= 3
                    && token.equals(token.toUpperCase(Locale.ROOT));
            boolean shortUnitBesideValue = hasDigit && length <= 2;
            if (!shortUppercase && !shortUnitBesideValue) {
                return false;
            }
        }
        return true;
    }

    private static void appendTemplateText(MutableComponent target, String text, Style style,
            List<ActionbarVariable> variables, List<ActionbarLeaf> leaves) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        Style effectiveStyle = style == null ? Style.EMPTY : style;
        int cursor = 0;
        while (cursor < text.length()) {
            int protectedEnd = protectedActionbarTokenEnd(text, cursor);
            if (protectedEnd > cursor) {
                appendProtectedToken(target, text.substring(cursor, protectedEnd), effectiveStyle, variables, leaves);
                cursor = protectedEnd;
                continue;
            }

            int next = cursor + Character.charCount(text.codePointAt(cursor));
            while (next < text.length() && protectedActionbarTokenEnd(text, next) <= next) {
                next += Character.charCount(text.codePointAt(next));
            }
            String literal = text.substring(cursor, next);
            target.append(Component.literal(literal).withStyle(effectiveStyle));
            leaves.add(new ActionbarLeaf(literal, literal, effectiveStyle, NO_ACTIONBAR_VARIABLE));
            cursor = next;
        }
    }

    private static int protectedActionbarTokenEnd(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return index;
        }
        for (Pattern pattern : List.of(LOCAL_LAYOUT_TOKEN_PATTERN,
                COORDINATE_VALUE_PATTERN, DYNAMIC_VALUE_PATTERN)) {
            Matcher matcher = pattern.matcher(text);
            matcher.region(index, text.length());
            if (matcher.lookingAt()) {
                return matcher.end();
            }
        }

        int codePoint = text.codePointAt(index);
        if (codePoint == '§' && index + 1 < text.length()) {
            int valueIndex = index + 1;
            return valueIndex + Character.charCount(text.codePointAt(valueIndex));
        }
        if (ComponentVisualProjection.isOpaqueCodepoint(codePoint)) {
            return index + Character.charCount(codePoint);
        }
        if (isBracketBoundary(codePoint)) {
            return index + Character.charCount(codePoint);
        }
        if (Character.isWhitespace(codePoint)) {
            int cursor = index;
            while (cursor < text.length()) {
                int current = text.codePointAt(cursor);
                if (!Character.isWhitespace(current)) {
                    break;
                }
                cursor += Character.charCount(current);
            }
            if (cursor < text.length() && isActionbarSeparator(text.codePointAt(cursor))) {
                return consumeActionbarSeparatorRun(text, index);
            }
            return index;
        }
        if (isActionbarSeparator(codePoint)) {
            return consumeActionbarSeparatorRun(text, index);
        }
        return index;
    }

    private static int consumeActionbarSeparatorRun(String text, int index) {
        int cursor = index;
        while (cursor < text.length()) {
            int codePoint = text.codePointAt(cursor);
            if (!Character.isWhitespace(codePoint) && !isActionbarSeparator(codePoint)) {
                break;
            }
            cursor += Character.charCount(codePoint);
        }
        return cursor;
    }

    private static boolean isBracketBoundary(int codePoint) {
        return codePoint == '[' || codePoint == ']' || codePoint == '(' || codePoint == ')'
                || codePoint == '{' || codePoint == '}';
    }

    private static boolean isActionbarSeparator(int codePoint) {
        if (ComponentVisualProjection.isOpaqueCodepoint(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static void appendProtectedToken(MutableComponent target, String value, Style style,
            List<ActionbarVariable> variables, List<ActionbarLeaf> leaves) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (variables.size() >= MAX_ACTIONBAR_PLACEHOLDERS) {
            target.append(Component.literal(value).withStyle(style));
            leaves.add(new ActionbarLeaf(value, value, style, NO_ACTIONBAR_VARIABLE));
            return;
        }
        int variableIndex = variables.size();
        String placeholder = DynamicTextTemplate.marker(1000 + variableIndex);
        variables.add(new ActionbarVariable(value, style));
        target.append(Component.literal(placeholder).withStyle(style));
        leaves.add(new ActionbarLeaf(placeholder, value, style, variableIndex));
    }

    @Nullable
    private static ActionbarVariable variableAt(List<ActionbarVariable> variables, String indexText) {
        try {
            int index = Integer.parseInt(indexText) - 1000;
            return index < 0 || index >= variables.size() ? null : variables.get(index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String styleSignature(Style style) {
        Style effective = style == null ? Style.EMPTY : style;
        String color = effective.getColor() == null ? "" : Integer.toString(effective.getColor().getValue());
        return "c=" + color
                + ";b=" + effective.isBold()
                + ";i=" + effective.isItalic()
                + ";u=" + effective.isUnderlined()
                + ";s=" + effective.isStrikethrough()
                + ";o=" + effective.isObfuscated();
    }

    public record ActionbarTemplate(Component component, List<ActionbarVariable> variables,
                                    List<ActionbarLeaf> leaves) {
        public ActionbarTemplate {
            variables = variables == null ? List.of() : List.copyOf(variables);
            leaves = leaves == null ? List.of() : List.copyOf(leaves);
        }

        public ActionbarTemplate(Component component, List<ActionbarVariable> variables) {
            this(component, variables, List.of());
        }
    }

    public record ActionbarVariable(String value, Style style) {
    }

    /**
     * One literal leaf emitted into the actionbar translation template, in render order.
     * A non-negative {@code variableIndex} points at {@link ActionbarTemplate#variables()}.
     */
    public record ActionbarLeaf(String templateText, String sourceText, Style style, int variableIndex) {
        public ActionbarLeaf {
            templateText = templateText == null ? "" : templateText;
            sourceText = sourceText == null ? "" : sourceText;
            style = style == null ? Style.EMPTY : style;
        }

        public boolean isVariable() {
            return variableIndex >= 0;
        }
    }
}
