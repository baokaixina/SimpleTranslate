package com.yourname.simpletranslate.feature.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits an outgoing slash command into the parts a player wrote and the parts
 * the server has to parse. Only the former reach the translator, so
 * {@code /tellraw @s "你好"} keeps its command name, selector and JSON
 * punctuation while just the quoted message is replaced.
 */
public final class OutgoingCommandText {

    /** Vanilla caps both chat messages and command payloads at 256 characters. */
    public static final int MAX_MESSAGE_LENGTH = 256;

    /** Vanilla commands whose tail is free-form prose, mapped to the argument count in front of it. */
    private static final Map<String, Integer> FREE_TEXT_COMMANDS = buildFreeTextCommands();

    /** Highest argument count a user-configured chat command may skip. */
    private static final int MAX_LEADING_ARGUMENTS = 4;

    /** Object keys whose value is an identifier rather than something to translate. */
    private static final Set<String> NON_TEXT_KEYS = Set.of(
            "translate", "fallback", "font", "color", "id", "keybind", "selector",
            "nbt", "storage", "block", "entity", "objective", "action", "type",
            "target", "source", "sound", "uuid", "tag", "team", "criteria");

    /** Namespaced ids and dotted translation keys such as {@code minecraft:stone}. */
    private static final Pattern IDENTIFIER_LIKE = Pattern.compile(
            "[a-z0-9_]+(?:[.:/][a-z0-9_./:+-]+)+");

    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    /** Guards against pathological nesting in hand-written NBT. */
    private static final int MAX_NESTING = 3;

    private OutgoingCommandText() {
    }

    /** A run of the command that may be swapped for a translation. */
    public record Segment(int start, int end, String text, char quote, boolean nested) {
        public boolean quoted() {
            return quote != 0;
        }
    }

    public static boolean isCommand(String message) {
        return message != null && message.startsWith("/");
    }

    /**
     * Parses a user-supplied chat-command list such as {@code "pc gc party:1"}
     * into command name to leading-argument count. Server chat channels are not
     * discoverable from the client, so the player names their own.
     */
    public static Map<String, Integer> parseChatCommands(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> parsed = new HashMap<>();
        for (String token : raw.split("[\\s,;]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String name = token;
            int leadingArguments = 0;
            int colon = token.lastIndexOf(':');
            if (colon > 0) {
                try {
                    leadingArguments = Integer.parseInt(token.substring(colon + 1));
                    leadingArguments = Math.max(0, Math.min(MAX_LEADING_ARGUMENTS, leadingArguments));
                    name = token.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // The colon belongs to a namespaced command name, not a count.
                }
            }
            name = normalizeCommandName(name);
            if (!name.isEmpty()) {
                parsed.put(name, leadingArguments);
            }
        }
        return parsed;
    }

    public static List<Segment> findTranslatableSegments(String command) {
        return findTranslatableSegments(command, Collections.emptyMap());
    }

    /**
     * Finds every translatable run of {@code command}, in ascending order and
     * without overlaps. An empty list means the command carries nothing a
     * translator should touch. {@code chatCommands} comes from the player's
     * settings and wins over the built-in table.
     */
    public static List<Segment> findTranslatableSegments(
            String command, Map<String, Integer> chatCommands) {
        if (!isCommand(command)) {
            return Collections.emptyList();
        }
        int nameEnd = 1;
        while (nameEnd < command.length() && isCommandNameChar(command.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == 1) {
            return Collections.emptyList();
        }
        String name = normalizeCommandName(command.substring(1, nameEnd));

        Integer leadingArguments = chatCommands.get(name);
        if (leadingArguments == null) {
            leadingArguments = FREE_TEXT_COMMANDS.get(name);
        }
        if (leadingArguments != null) {
            return freeTextSegment(command, nameEnd, leadingArguments);
        }

        List<Segment> segments = new ArrayList<>();
        scanLiterals(command, nameEnd, command.length(), segments, 0);
        return segments;
    }

    /** The text handed to language detection, mirroring what will be translated. */
    public static String joinForDetection(List<Segment> segments) {
        StringBuilder joined = new StringBuilder();
        for (Segment segment : segments) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(segment.text());
        }
        return joined.toString();
    }

    public static List<String> texts(List<Segment> segments) {
        List<String> texts = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            texts.add(segment.text());
        }
        return texts;
    }

    /**
     * Rebuilds {@code command} with each segment replaced by its translation.
     * Returns {@code null} when the inputs do not line up.
     */
    public static String rebuild(String command, List<Segment> segments, List<String> translations) {
        if (command == null || segments == null || translations == null
                || segments.size() != translations.size() || segments.isEmpty()) {
            return null;
        }
        StringBuilder rebuilt = new StringBuilder(command.length() + 32);
        int cursor = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            String translation = translations.get(i);
            if (translation == null || translation.isBlank()
                    || segment.start() < cursor || segment.end() > command.length()) {
                return null;
            }
            rebuilt.append(command, cursor, segment.start());
            rebuilt.append(encode(translation, segment));
            cursor = segment.end();
        }
        rebuilt.append(command, cursor, command.length());
        return rebuilt.toString();
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private static List<Segment> freeTextSegment(String command, int nameEnd, int leadingArguments) {
        int cursor = nameEnd;
        for (int i = 0; i < leadingArguments; i++) {
            cursor = skipSpaces(command, cursor);
            if (cursor >= command.length()) {
                return Collections.emptyList();
            }
            cursor = skipArgument(command, cursor);
        }
        cursor = skipSpaces(command, cursor);
        int end = command.length();
        while (end > cursor && Character.isWhitespace(command.charAt(end - 1))) {
            end--;
        }
        if (end <= cursor) {
            return Collections.emptyList();
        }
        String body = command.substring(cursor, end);
        if (!hasLetter(body)) {
            return Collections.emptyList();
        }
        return List.of(new Segment(cursor, end, body, (char) 0, false));
    }

    private static void scanLiterals(String source, int from, int to, List<Segment> out, int depth) {
        String pendingKey = null;
        int cursor = from;
        while (cursor < to) {
            char c = source.charAt(cursor);
            if (c == '"' || c == '\'') {
                int contentStart = cursor + 1;
                int contentEnd = findClosingQuote(source, contentStart, to, c);
                if (contentEnd < 0) {
                    return;
                }
                String raw = source.substring(contentStart, contentEnd);
                String value = unescape(raw, c);
                if (isKeyPosition(source, contentEnd + 1, to)) {
                    pendingKey = value.toLowerCase(Locale.ROOT);
                } else {
                    boolean escaped = !raw.equals(value);
                    if (!escaped && depth < MAX_NESTING && looksStructured(value)) {
                        scanLiterals(source, contentStart, contentEnd, out, depth + 1);
                    } else if (isTranslatableLiteral(value, pendingKey)) {
                        out.add(new Segment(contentStart, contentEnd, value, c, depth > 0));
                    }
                    pendingKey = null;
                }
                cursor = contentEnd + 1;
                continue;
            }
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ',') {
                pendingKey = null;
                cursor++;
                continue;
            }
            if (isWordChar(c)) {
                int wordEnd = cursor;
                while (wordEnd < to && isWordChar(source.charAt(wordEnd))) {
                    wordEnd++;
                }
                pendingKey = isKeyPosition(source, wordEnd, to)
                        ? source.substring(cursor, wordEnd).toLowerCase(Locale.ROOT)
                        : null;
                cursor = wordEnd;
                continue;
            }
            cursor++;
        }
    }

    /** True when the next non-space character marks this literal as an object key. */
    private static boolean isKeyPosition(String source, int after, int to) {
        int cursor = skipSpaces(source, after, to);
        if (cursor >= to) {
            return false;
        }
        char c = source.charAt(cursor);
        return c == ':' || c == '=';
    }

    private static int findClosingQuote(String source, int from, int to, char quote) {
        for (int i = from; i < to; i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String raw, char quote) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder plain = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                plain.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> plain.append('\n');
                case 't' -> plain.append('\t');
                case 'r' -> plain.append('\r');
                default -> plain.append(next);
            }
        }
        return plain.toString();
    }

    private static String encode(String translation, Segment segment) {
        StringBuilder encoded = new StringBuilder(translation.length() + 8);
        for (int i = 0; i < translation.length(); i++) {
            char c = translation.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                encoded.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            if (!segment.quoted()) {
                encoded.append(c);
                continue;
            }
            if (c == '\\' || c == segment.quote()) {
                // A nested literal lives inside another quoted string, where a
                // second level of escapes would not survive the round trip.
                if (segment.nested()) {
                    continue;
                }
                encoded.append('\\');
            }
            encoded.append(c);
        }
        return encoded.toString();
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /** The tail of /say and friends is prose by definition, so it only has to contain words. */
    private static boolean hasLetter(String value) {
        return value != null && HAS_LETTER.matcher(value).find();
    }

    /**
     * A quoted literal only counts as prose when nothing marks it as machine
     * input: no identifier shape, no selector, no url, no key that is known to
     * carry an id.
     */
    private static boolean isTranslatableLiteral(String value, String key) {
        if (!hasLetter(value)) {
            return false;
        }
        String trimmed = value.trim();
        if (key != null && NON_TEXT_KEYS.contains(key)) {
            return false;
        }
        if (trimmed.charAt(0) == '@' || trimmed.contains("://")) {
            return false;
        }
        return !IDENTIFIER_LIKE.matcher(trimmed).matches();
    }

    private static boolean looksStructured(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    // ------------------------------------------------------------------
    // Small parsing helpers
    // ------------------------------------------------------------------

    /** Command names may be namespaced, so the colon belongs to the name. */
    private static boolean isCommandNameChar(char c) {
        return isWordChar(c) || c == ':';
    }

    /** Inside the body a colon separates a key from its value and must end the word. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static int skipSpaces(String source, int from) {
        return skipSpaces(source, from, source.length());
    }

    private static int skipSpaces(String source, int from, int to) {
        int cursor = from;
        while (cursor < to && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    /** Consumes one whitespace-delimited argument, keeping bracketed selectors intact. */
    private static int skipArgument(String source, int from) {
        int cursor = from;
        int brackets = 0;
        char quote = 0;
        while (cursor < source.length()) {
            char c = source.charAt(cursor);
            if (quote != 0) {
                if (c == '\\') {
                    cursor++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                brackets++;
            } else if (c == ']' || c == '}') {
                brackets = Math.max(0, brackets - 1);
            } else if (brackets == 0 && Character.isWhitespace(c)) {
                return cursor;
            }
            cursor++;
        }
        return cursor;
    }

    /** Strips a leading slash and any namespace so "/minecraft:say" matches "say". */
    private static String normalizeCommandName(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespace = normalized.lastIndexOf(':');
        return namespace >= 0 ? normalized.substring(namespace + 1) : normalized;
    }

    private static Map<String, Integer> buildFreeTextCommands() {
        Map<String, Integer> commands = new HashMap<>();
        commands.put("say", 0);
        commands.put("me", 0);
        commands.put("teammsg", 0);
        commands.put("tm", 0);
        commands.put("msg", 1);
        commands.put("tell", 1);
        commands.put("w", 1);
        commands.put("whisper", 1);
        return Collections.unmodifiableMap(commands);
    }
}
