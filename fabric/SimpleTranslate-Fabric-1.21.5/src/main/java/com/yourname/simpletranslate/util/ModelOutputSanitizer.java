package com.yourname.simpletranslate.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModelOutputSanitizer {
  private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");
  private static final Pattern ANALYSIS_BLOCK_PATTERN = Pattern.compile("(?is)<analysis>.*?</analysis>");
  private static final Pattern FENCED_BLOCK_PATTERN = Pattern.compile("(?s)^```[a-zA-Z0-9_-]*\\s*(.*?)\\s*```$");
  private static final Pattern OUTPUT_PREFIX_PATTERN = Pattern.compile(
    "(?is)^(?:translation|translated\\s*text|output|answer|final\\s*answer|\\u6700\\u7ec8\\u7b54\\u6848|\\u7b54\\u6848|\\u8bd1\\u6587)\\s*[:\\uFF1A]\\s*"
  );

  private ModelOutputSanitizer() {
  }

  public static String sanitize(String text) {
    if (text == null) {
      return null;
    }

    String cleaned = text.replace("\r\n", "\n").trim();
    if (cleaned.isEmpty()) {
      return "";
    }

    cleaned = THINK_BLOCK_PATTERN.matcher(cleaned).replaceAll("").trim();
    cleaned = ANALYSIS_BLOCK_PATTERN.matcher(cleaned).replaceAll("").trim();

    if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
      Matcher fencedMatcher = FENCED_BLOCK_PATTERN.matcher(cleaned);
      if (fencedMatcher.matches()) {
        cleaned = fencedMatcher.group(1).trim();
      }
    }

    cleaned = stripLeadingOutputLabels(cleaned);
    return normalizeWhitespace(cleaned);
  }

  public static String sanitizeWithOriginal(String translated, String originalText) {
    String cleaned = sanitize(translated);
    if (cleaned == null || cleaned.isEmpty() || originalText == null || originalText.isEmpty()) {
      return cleaned;
    }

    String original = stripMinecraftFormatting(originalText).trim();
    if (original.isEmpty() || cleaned.equals(original)) {
      return cleaned;
    }

    String escapedOriginal = Pattern.quote(original);

    // Remove explicit wrappers that usually indicate model echoing the source.
    cleaned = cleaned.replaceAll("\\(\\s*" + escapedOriginal + "\\s*\\)", " ");
    cleaned = cleaned.replaceAll("（\\s*" + escapedOriginal + "\\s*）", " ");
    cleaned = cleaned.replaceAll("\\[\\s*" + escapedOriginal + "\\s*\\]", " ");
    cleaned = cleaned.replaceAll("【\\s*" + escapedOriginal + "\\s*】", " ");
    cleaned = cleaned.replaceAll("“\\s*" + escapedOriginal + "\\s*”", " ");
    cleaned = cleaned.replaceAll("\"\\s*" + escapedOriginal + "\\s*\"", " ");

    // Remove "original - translated" and "translated - original" forms.
    cleaned = cleaned.replaceAll("^\\s*" + escapedOriginal + "\\s*[:\\uFF1A\\-\\u2014|/]\\s*", "");
    cleaned = cleaned.replaceAll("\\s*[:\\uFF1A\\-\\u2014|/]\\s*" + escapedOriginal + "\\s*$", "");

    // If model mixed original inside translated sentence, remove exact source fragment
    // only when translated output clearly contains non-latin script.
    if (containsCjk(cleaned) && containsAsciiLetterOrDigit(original)) {
      String removed = cleaned.replace(original, " ");
      removed = normalizeWhitespace(removed);
      if (!removed.isEmpty() && !removed.equals(original)) {
        cleaned = removed;
      }
    }

    cleaned = normalizeWhitespace(cleaned);
    if (cleaned.isEmpty()) {
      return sanitize(translated);
    }

    return cleaned;
  }

  private static String stripLeadingOutputLabels(String text) {
    String current = text;
    while (true) {
      Matcher matcher = OUTPUT_PREFIX_PATTERN.matcher(current);
      if (!matcher.find()) {
        return current;
      }
      String next = current.substring(matcher.end()).trim();
      if (next.isEmpty()) {
        return current;
      }
      current = next;
    }
  }

  private static String normalizeWhitespace(String text) {
    if (text == null) {
      return null;
    }

    String cleaned = text.trim();
    cleaned = cleaned.replaceAll("[ \\t]{2,}", " ");
    cleaned = cleaned.replaceAll(" *\\n *", "\n");
    cleaned = cleaned.replaceAll("\\s+([,.;!?，。！？；：])", "$1");
    cleaned = cleaned.replaceAll("([\\(（\\[【])\\s+", "$1");
    cleaned = cleaned.replaceAll("\\s+([\\)）\\]】])", "$1");
    cleaned = cleaned.replaceAll("(?m)^[ \\t]+|[ \\t]+$", "");
    return cleaned.trim();
  }

  private static String stripMinecraftFormatting(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }

    StringBuilder result = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\u00a7' && i + 1 < text.length()) {
        i += 2;
        continue;
      }
      result.append(c);
      i++;
    }
    return result.toString();
  }

  private static boolean containsCjk(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= '\u4e00' && c <= '\u9fff' || c >= '\u3400' && c <= '\u4dbf' || c >= '\u3040' && c <= '\u30ff' || c >= '\uac00' && c <= '\ud7af') {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAsciiLetterOrDigit(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
        return true;
      }
    }
    return false;
  }
}
