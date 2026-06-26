package com.yourname.simpletranslate.feature.hud;

import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.core.ComponentSegmentHelper;
import com.yourname.simpletranslate.core.TextSegmentInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure HUD text templating and signature helpers, independent of the GUI mixin. */
public final class HudTextSupport {
    private static final Pattern DYNAMIC_VALUE_PATTERN =
            Pattern.compile("\\(?[+-]?\\d+(?:\\.\\d+)?(?:/[+-]?\\d+(?:\\.\\d+)?)*%?\\)?");
    private static final String TECHNICAL_TOKEN_BODY =
            "(?:NE|NW|SE|SW|N|S|E|W|HP|MP|XP|ATK|DEF|SPD|DPS|DOT|HPS|LVL|LV)";
    private static final Pattern BRACKETED_TECHNICAL_TOKEN_PATTERN =
            Pattern.compile("\\[" + TECHNICAL_TOKEN_BODY + "]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TECHNICAL_TOKEN_PATTERN =
            Pattern.compile(TECHNICAL_TOKEN_BODY, Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("⟦(1\\d{3})⟧");
    private static final int MAX_ACTIONBAR_PLACEHOLDERS = 1000;

    private HudTextSupport() {
    }

    public static ActionbarTemplate actionbarTemplate(@Nullable Component original) {
        if (original == null) {
            return new ActionbarTemplate(Component.empty(), List.of());
        }
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(original, segments, Style.EMPTY, false);
        MutableComponent normalized = Component.empty();
        List<ActionbarVariable> variables = new ArrayList<>();
        if (segments.isEmpty()) {
            appendTemplateText(normalized, original.getString(), Style.EMPTY, variables);
        } else {
            for (TextSegmentInfo segment : segments) {
                if (segment == null || segment.text == null || segment.text.isEmpty()) {
                    continue;
                }
                appendTemplateText(normalized, segment.text,
                        segment.style == null ? Style.EMPTY : segment.style, variables);
            }
        }
        return new ActionbarTemplate(normalized, List.copyOf(variables));
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

    public static String cleanText(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
    }

    public static boolean isTechnicalText(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String withoutSymbols = text.replaceAll("[\\p{Punct}\\s\\u00A7§※✥✦✧❤♥☼☽☾◆◇■□▶▷◀◁|/\\\\]+", "");
        if (withoutSymbols.isEmpty()) {
            return true;
        }
        String words = withoutSymbols.replaceAll("[0-9]+", "");
        if (words.matches("(?i)^(N|S|E|W|NE|NW|SE|SW|HP|MP|XP|ATK|DEF|SPD|DPS|DOT|HPS|LV|LVL)+$")) {
            return true;
        }
        String[] tokens = text.replaceAll("[^A-Za-z0-9/]+", " ").trim().split("\\s+");
        int meaningfulWords = 0;
        int technicalWords = 0;
        for (String token : tokens) {
            if (token == null || token.isBlank() || token.matches("[0-9/]+")) {
                continue;
            }
            meaningfulWords++;
            if (token.matches("(?i)N|S|E|W|NE|NW|SE|SW|HP|MP|XP|ATK|DEF|SPD|DPS|DOT|HPS|LV|LVL")) {
                technicalWords++;
            }
        }
        return meaningfulWords > 0 && technicalWords >= meaningfulWords && meaningfulWords <= 2;
    }

    private static void appendTemplateText(MutableComponent target, String text, Style style,
            List<ActionbarVariable> variables) {
        if (target == null || text == null || text.isEmpty()) {
            return;
        }
        Style effectiveStyle = style == null ? Style.EMPTY : style;
        int cursor = 0;
        while (cursor < text.length()) {
            int protectedEnd = protectedActionbarTokenEnd(text, cursor);
            if (protectedEnd > cursor) {
                appendProtectedToken(target, text.substring(cursor, protectedEnd), effectiveStyle, variables);
                cursor = protectedEnd;
                continue;
            }

            int next = cursor + Character.charCount(text.codePointAt(cursor));
            while (next < text.length() && protectedActionbarTokenEnd(text, next) <= next) {
                next += Character.charCount(text.codePointAt(next));
            }
            target.append(Component.literal(text.substring(cursor, next)).withStyle(effectiveStyle));
            cursor = next;
        }
    }

    private static int protectedActionbarTokenEnd(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return index;
        }
        Matcher matcher = DYNAMIC_VALUE_PATTERN.matcher(text);
        matcher.region(index, text.length());
        if (matcher.lookingAt()) {
            return matcher.end();
        }

        int bracketedTechnicalEnd = matchedTechnicalTokenEnd(
                BRACKETED_TECHNICAL_TOKEN_PATTERN, text, index, false);
        if (bracketedTechnicalEnd > index) {
            return bracketedTechnicalEnd;
        }
        int technicalEnd = matchedTechnicalTokenEnd(TECHNICAL_TOKEN_PATTERN, text, index, true);
        if (technicalEnd > index) {
            return technicalEnd;
        }

        int codePoint = text.codePointAt(index);
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

    private static int matchedTechnicalTokenEnd(Pattern pattern, String text, int index, boolean requireBoundary) {
        if (requireBoundary && index > 0 && Character.isLetterOrDigit(text.codePointBefore(index))) {
            return index;
        }
        Matcher matcher = pattern.matcher(text);
        matcher.region(index, text.length());
        if (!matcher.lookingAt()) {
            return index;
        }
        int end = matcher.end();
        if (requireBoundary && end < text.length() && Character.isLetterOrDigit(text.codePointAt(end))) {
            return index;
        }
        return end;
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
        return switch (codePoint) {
            case '|', '/', '\\', ':', ';', ',', '\uFF0C', '\u3001', '\uFF1A', '\uFF1B',
                    '\u203B', '\u2725', '\u2726', '\u2727', '\u2764', '\u2665',
                    '\u263C', '\u263D', '\u263E', '\u25C6', '\u25C7', '\u25A0',
                    '\u25A1', '\u25B6', '\u25B7', '\u25C0', '\u25C1', '\u2022',
                    '\u00B7', '-', '\u2013', '\u2014', '+' -> true;
            default -> false;
        };
    }

    private static void appendProtectedToken(MutableComponent target, String value, Style style,
            List<ActionbarVariable> variables) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (variables.size() >= MAX_ACTIONBAR_PLACEHOLDERS) {
            target.append(Component.literal(value).withStyle(style));
            return;
        }
        String placeholder = DynamicTextTemplate.marker(1000 + variables.size());
        variables.add(new ActionbarVariable(value, style));
        target.append(Component.literal(placeholder).withStyle(style));
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

    public record ActionbarTemplate(Component component, List<ActionbarVariable> variables) {
    }

    public record ActionbarVariable(String value, Style style) {
    }
}
