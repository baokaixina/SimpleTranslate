package com.yourname.simpletranslate.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal-echo wire format ("stw1") shared by every direct translation surface.
 *
 * <p>The model receives numbered lines ({@code i|text}) where multi-style lines
 * carry lightweight paired tags ({@code <1>...</1>}). The model is asked to
 * return only the translated numbered lines; all styling is reattached locally
 * from the original component template. Parsing is deliberately tolerant:
 * losing a tag or a line degrades only that fragment instead of rejecting the
 * whole document.</p>
 */
public final class WireCodec {
    /** Version marker stored as the first line of cached payloads. */
    public static final String PAYLOAD_MARKER = "stw1";

    private static final Pattern LINE_PATTERN = Pattern.compile("^\\s*(\\d{1,4})\\s*[|｜]\\s?(.*)$");
    private static final Pattern TAG_PATTERN = Pattern.compile("<(\\d{1,2})>(.*?)</\\1>");
    private static final Pattern ANY_TAG_PATTERN = Pattern.compile("</?\\d{1,2}>");
    private static final Pattern ITEM_HEADER_PATTERN = Pattern.compile("(?m)^\\s*\\[ITEM\\s+(\\d{1,3})]\\s*$");
    private static final Pattern NOISE_LINE_PATTERN = Pattern.compile(
            "(?i)^\\s*\\[/?(?:TEXT|BATCH|ITEM|CONTEXT|NOTE|GLOSSARY|TERMS|STYLE|NORMALIZED)[^]]*]\\s*$");

    private WireCodec() {
    }

    /** A fragment of a parsed line: tag number (0 = untagged/base) plus its text. */
    public record Piece(int tag, String text) {
    }

    /** True when the payload is a canonical cached payload (starts with the marker). */
    public static boolean isCanonicalPayload(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return trimmed.equals(PAYLOAD_MARKER) || trimmed.startsWith(PAYLOAD_MARKER + "\n")
                || trimmed.startsWith(PAYLOAD_MARKER + "\r");
    }

    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    public static String unescape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (next == '\\') {
                    out.append('\\');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** True when the raw text already contains wire-style tags and must not be tagged again. */
    public static boolean containsTagLikeText(String text) {
        return text != null && ANY_TAG_PATTERN.matcher(text).find();
    }

    public static String openTag(int tag) {
        return "<" + tag + ">";
    }

    public static String closeTag(int tag) {
        return "</" + tag + ">";
    }

    public static String section(String name, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return "[" + name + "]\n" + body.trim() + "\n[/" + name + "]\n";
    }

    public static String textBlock(List<String> encodedLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("[TEXT lines=").append(encodedLines.size()).append("]\n");
        for (int i = 0; i < encodedLines.size(); i++) {
            builder.append(i).append('|').append(encodedLines.get(i) == null ? "" : encodedLines.get(i)).append('\n');
        }
        builder.append("[/TEXT]");
        return builder.toString();
    }

    /**
     * Parses a model response (or cached payload) into line-index keyed wire
     * content. Returns {@code null} only when nothing usable can be recovered.
     */
    @Nullable
    public static Map<Integer, String> parseResponse(String raw, int expectedLines) {
        if (raw == null || expectedLines <= 0) {
            return null;
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) {
            return null;
        }

        Map<Integer, String> numbered = new LinkedHashMap<>();
        List<String> unnumbered = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String candidate = line == null ? "" : line;
            String trimmed = candidate.trim();
            if (trimmed.equals(PAYLOAD_MARKER) || NOISE_LINE_PATTERN.matcher(trimmed).matches()
                    || trimmed.startsWith("```")) {
                continue;
            }
            Matcher matcher = LINE_PATTERN.matcher(candidate);
            if (matcher.matches()) {
                int index;
                try {
                    index = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (index >= 0 && index <= expectedLines) {
                    numbered.putIfAbsent(index, matcher.group(2));
                }
                continue;
            }
            if (!trimmed.isEmpty()) {
                unnumbered.add(candidate);
            }
        }
        if (!numbered.isEmpty()) {
            // Some models number lines from 1; shift back when that is unambiguous.
            boolean oneBased = !numbered.containsKey(0) && numbered.containsKey(expectedLines);
            Map<Integer, String> byIndex = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> entry : numbered.entrySet()) {
                int index = oneBased ? entry.getKey() - 1 : entry.getKey();
                if (index >= 0 && index < expectedLines) {
                    byIndex.putIfAbsent(index, entry.getValue());
                }
            }
            if (!byIndex.isEmpty()) {
                return byIndex;
            }
        }
        Map<Integer, String> byIndex = new LinkedHashMap<>();

        // No numbered lines at all: positional fallback.
        if (expectedLines == 1) {
            String joined = String.join("\\n", unnumbered).trim();
            if (joined.isEmpty()) {
                return null;
            }
            byIndex.put(0, joined);
            return byIndex;
        }
        if (unnumbered.size() == expectedLines) {
            for (int i = 0; i < unnumbered.size(); i++) {
                byIndex.put(i, unnumbered.get(i).trim());
            }
            return byIndex;
        }
        return null;
    }

    /** Splits one line's wire content into ordered tagged/untagged pieces. */
    public static List<Piece> splitPieces(String content) {
        List<Piece> pieces = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return pieces;
        }
        Matcher matcher = TAG_PATTERN.matcher(content);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                pieces.add(new Piece(0, content.substring(cursor, matcher.start())));
            }
            int tag;
            try {
                tag = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                tag = 0;
            }
            pieces.add(new Piece(tag, matcher.group(2)));
            cursor = matcher.end();
        }
        if (cursor < content.length()) {
            pieces.add(new Piece(0, content.substring(cursor)));
        }
        return pieces;
    }

    /** Strips any stray unpaired tag markers from degraded model output. */
    public static String stripTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return ANY_TAG_PATTERN.matcher(content).replaceAll("");
    }

    public static String batchPayload(List<String> itemPayloads) {
        StringBuilder builder = new StringBuilder();
        builder.append("[BATCH items=").append(itemPayloads.size()).append("]\n");
        for (int i = 0; i < itemPayloads.size(); i++) {
            builder.append("[ITEM ").append(i).append("]\n")
                    .append(itemPayloads.get(i)).append('\n')
                    .append("[/ITEM ").append(i).append("]\n");
        }
        builder.append("[/BATCH]");
        return builder.toString();
    }

    /** Splits a batched model response into per-item bodies keyed by item index. */
    public static Map<Integer, String> parseBatchResponse(String raw) {
        Map<Integer, String> items = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = ITEM_HEADER_PATTERN.matcher(text);
        int previousIndex = -1;
        int previousEnd = -1;
        while (matcher.find()) {
            if (previousIndex >= 0) {
                items.putIfAbsent(previousIndex, text.substring(previousEnd, matcher.start()));
            }
            try {
                previousIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                previousIndex = -1;
            }
            previousEnd = matcher.end();
        }
        if (previousIndex >= 0) {
            items.putIfAbsent(previousIndex, text.substring(previousEnd));
        }
        return items;
    }
}
