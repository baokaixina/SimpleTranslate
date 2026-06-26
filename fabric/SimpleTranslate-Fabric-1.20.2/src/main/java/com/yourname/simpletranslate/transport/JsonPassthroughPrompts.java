package com.yourname.simpletranslate.transport;

import com.yourname.simpletranslate.core.TranslationTextDetector;

import java.util.List;
import java.util.Locale;

/**
 * System prompt for the JSON-passthrough component translation mode.
 *
 * <p>The model receives an array of Minecraft Component JSON objects and
 * returns the same array with only the natural-language {@code text} fields
 * translated. All style attributes ({@code color}, {@code bold},
 * {@code clickEvent}, {@code insertion}, {@code font},
 * {@code extra} structure, array length) are preserved verbatim. This
 * eliminates the {@code <n>} tag protocol entirely — the model sees the
 * visible structure and simply swaps text content. Hidden hover events are
 * restored locally instead of sent to the model.</p>
 */
public final class JsonPassthroughPrompts {

    private JsonPassthroughPrompts() {
    }

    public static String buildSystemPrompt(String sourceLanguage, String targetLanguage,
                                           List<com.yourname.simpletranslate.api.TranslationRequest.Term> termHints) {
        return buildSystemPrompt(sourceLanguage, targetLanguage, termHints, "");
    }

    public static String buildSystemPrompt(String sourceLanguage, String targetLanguage,
                                           List<com.yourname.simpletranslate.api.TranslationRequest.Term> termHints,
                                           String surface) {
        String sourceCode = TranslationTextDetector.canonicalLanguageCode(sourceLanguage);
        String sourceClause = "auto".equals(sourceCode)
                ? "Auto-detect the source language and translate"
                : "Translate from " + TranslationTextDetector.displayLanguageName(sourceLanguage);
        String target = TranslationTextDetector.displayLanguageName(targetLanguage);
        String surfaceValue = surface == null ? "" : surface.toLowerCase(Locale.ROOT);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional Minecraft game localizer. ")
                .append(sourceClause).append(" to ").append(target).append(".\n");
        prompt.append("The user message contains a JSON array of Minecraft Component objects");
        if (surfaceValue.startsWith("tooltip.item_context")) {
            prompt.append(" from one item tooltip");
        } else if (surfaceValue.startsWith("hover.context")) {
            prompt.append(" from one chat hover tooltip");
        } else if (surfaceValue.startsWith("sign.manual")) {
            prompt.append(" from manually selected Minecraft signs");
        } else if (surfaceValue.startsWith("sign.auto")) {
            prompt.append(" from one Minecraft sign");
        } else if (surfaceValue.startsWith("chat.")) {
            prompt.append(" from chat messages");
        }
        prompt.append(". ")
                .append("Each component is a JSON object with a \"text\" field and optional style fields ")
                .append("(\"color\", \"bold\", \"italic\", \"underlined\", \"strikethrough\", \"obfuscated\", ")
                .append("\"clickEvent\", \"insertion\", \"font\") and an \"extra\" array ")
                .append("for child fragments. Translate every natural-language \"text\" field into the ")
                .append("target language. Return ONLY the same JSON array with translated text — ")
                .append("no markdown, no explanation, no headers.\n");
        prompt.append("CRITICAL STRUCTURAL RULES:\n");
        prompt.append("- Preserve the exact JSON array length: one input element → one output element.\n");
        prompt.append("- Preserve every style field name and value verbatim (color, bold, clickEvent, etc.). ")
                .append("Never add, remove, rename, or translate style fields.\n");
        prompt.append("- Preserve the \"extra\" array structure: same number of children, same nesting order. ")
                .append("Only translate the \"text\" string inside each child.\n");
        prompt.append("- Never merge, split, reorder, or drop components in the \"extra\" array.\n");
        prompt.append("- Keep the JSON valid: proper quotes, commas, brackets. Return a parseable JSON array.\n");
        prompt.append("TEXT TRANSLATION RULES:\n");
        prompt.append("- Translate natural-language words and phrases in every \"text\" field.\n");
        prompt.append("- Keep player names, /commands, @selectors, coordinates, placeholders, ")
                .append("format codes (%s, {0}), ⟦Ni⟧ numeric markers, and pure-punctuation strings unchanged.\n");
        prompt.append("- If a \"text\" field is empty (\"\"), keep it empty.\n");
        prompt.append("- If a \"text\" field has no natural language (only symbols/numbers), keep it unchanged.\n");
        prompt.append("- For game content titles, item names, skill names, and invented Latin words, ")
                .append("create a localized name or natural transliteration; do not copy the Latin unchanged. ")
                .append("Only keep real player names unchanged.\n");
        if (surfaceValue.startsWith("tooltip.item_context")) {
            prompt.append("- This is an item tooltip: translate the title, lore, mechanic phrases, equipment labels, ")
                    .append("attribute names, and sentence fragments coherently across all array entries.\n");
        } else if (surfaceValue.startsWith("hover.context")) {
            prompt.append("- This is a chat hover tooltip: understand the whole tooltip before translating titles, ")
                    .append("skill descriptions, lore, and mechanic lines. Keep commands and numeric values unchanged.\n");
        } else if (surfaceValue.startsWith("chat.")) {
            prompt.append("- For consecutive chat/menu lines, understand the whole array as one server message block ")
                    .append("when possible, but still return one translated component per input component.\n");
        }
        if (isChineseTarget(targetLanguage)) {
            prompt.append("Examples:\n");
            prompt.append("Input: [{\"text\":\"\",\"extra\":[{\"text\":\"Steve\",\"color\":\"red\"},")
                    .append("{\"text\":\" found a \"},{\"text\":\"Diamond Sword\",\"color\":\"yellow\"}]}]\n");
            prompt.append("Output: [{\"text\":\"\",\"extra\":[{\"text\":\"Steve\",\"color\":\"red\"},")
                    .append("{\"text\":\"找到了一把\"},{\"text\":\"钻石剑\",\"color\":\"yellow\"}]}]\n");
            prompt.append("Input: [{\"text\":\"Enemies will come from three directions:\"}]\n");
            prompt.append("Output: [{\"text\":\"敌人将从三个方向进攻：\"}]\n");
        }
        if (termHints != null && !termHints.isEmpty()) {
            prompt.append("Apply these term translations:\n");
            for (com.yourname.simpletranslate.api.TranslationRequest.Term term : termHints) {
                if (term != null && term.source() != null && term.target() != null) {
                    prompt.append("- \"").append(term.source()).append("\" -> \"")
                            .append(term.target()).append("\"\n");
                }
            }
        }
        return prompt.toString().trim();
    }

    private static boolean isChineseTarget(String targetLanguage) {
        String code = TranslationTextDetector.canonicalLanguageCode(targetLanguage);
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("zh");
    }
}
