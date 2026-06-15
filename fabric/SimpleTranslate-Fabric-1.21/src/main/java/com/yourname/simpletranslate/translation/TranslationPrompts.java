package com.yourname.simpletranslate.translation;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.TranslationTextDetector;

import java.util.List;
import java.util.Locale;

/**
 * Prompt builder for every request mode.
 *
 * <p>The DIRECT_FORMATTED system prompt is intentionally compact and
 * byte-stable per language pair: custom style instructions and term hints are
 * appended to the user payload instead, so providers with automatic prefix
 * caching (e.g. DeepSeek) can reuse the system prompt across requests.</p>
 */
public final class TranslationPrompts {

    public enum RequestMode {
        PLAIN_TEXT,
        PRESERVE_MARKERS,
        MAPPING_JSON,
        DIRECT_FORMATTED
    }

    private TranslationPrompts() {
    }

    public static String languageName(String code) {
        return TranslationTextDetector.displayLanguageName(code);
    }

    public static String buildSystemPrompt(String sourceLanguage, String targetLanguage,
                                           List<TranslationService.TermHint> termHints, RequestMode mode) {
        if (mode == RequestMode.DIRECT_FORMATTED) {
            return buildDirectSystemPrompt(sourceLanguage, targetLanguage);
        }
        return buildLegacySystemPrompt(sourceLanguage, targetLanguage, termHints, mode);
    }

    /** Compact, stable system prompt for the minimal-echo wire protocol. */
    private static String buildDirectSystemPrompt(String sourceLanguage, String targetLanguage) {
        String sourceCode = TranslationTextDetector.canonicalLanguageCode(sourceLanguage);
        String sourceClause = "auto".equals(sourceCode)
                ? "Auto-detect the source language and translate"
                : "Translate from " + languageName(sourceLanguage);
        String target = languageName(targetLanguage);
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional Minecraft game localizer. ")
                .append(sourceClause).append(" to ").append(target).append(".\n");
        prompt.append("Input blocks [CONTEXT], [NOTE], [GLOSSARY], [TERMS], [STYLE], [NORMALIZED] are reference only; never echo them.\n");
        prompt.append("The [TEXT] block has numbered lines \"i|content\". Reply with ONLY the translated lines, ")
                .append("one per line, formatted \"i|translation\", same numbers, same order, same count. ")
                .append("No headers, no explanations, no markdown, no bilingual echo.\n");
        prompt.append("Inline numbered tags like <1>...</1> mark styled fragments. Keep every tag pair, wrapped around ")
                .append("the words corresponding to its source content; reorder tags freely for natural word order; ")
                .append("never drop, invent, renumber or nest tags.\n");
        if (isChineseTarget(targetLanguage)) {
            prompt.append("Example: \"3|draining <1>15</1><2> Mana</2> every <3>0.4</3>s\" -> ")
                    .append("\"3|每 <3>0.4</3> 秒消耗 <1>15</1><2> 点法力</2>\".\n");
        } else {
            prompt.append("Example: tags move with their words, e.g. \"3|<1>15</1><2> Mana</2> drained\" may become ")
                    .append("\"3|drained <1>15</1><2> Mana</2>\" in the target word order.\n");
        }
        prompt.append("Copy digits, numbers, placeholders (%s, {0}, @@0@@), \u00a7-format codes, /commands, @selectors, ")
                .append("player names, coordinates and \\n sequences exactly; numbers must never change, swap or move between lines. ")
                .append("In idiomatic phrases like \"1 by 1\" or \"one at a time\", keep the source digits or repeat the same count with Arabic digits.\n");
        prompt.append("Translate every natural-language word: UI labels, status words, stats, and bracketed labels ")
                .append("(e.g. [Click to TP]) while keeping the bracket characters. Keep a line unchanged only when it has ")
                .append("no natural language or is already in the target language.\n");
        prompt.append("Lines are often hard-wrapped fragments of one sentence. Read all lines first, translate the ")
                .append("complete sentence, then distribute the translation naturally across the same line numbers. ")
                .append("Never skip a continuation line, never leave it in the source language, and never merge its ")
                .append("content into another line while leaving it empty.\n");
        prompt.append("Apply [GLOSSARY] and [TERMS] term mappings exactly. Follow [STYLE] for tone and wording only. ")
                .append("Use concise, fluent game-localization wording; stay faithful to source facts; never invent effects, ")
                .append("targets or durations.\n");
        prompt.append("If the input contains [ITEM k] blocks, output the line \"[ITEM k]\" followed by that item's ")
                .append("translated numbered lines, for every item, in order.");
        return prompt.toString();
    }

    private static boolean isChineseTarget(String targetLanguage) {
        String code = TranslationTextDetector.canonicalLanguageCode(targetLanguage);
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /** Builds the [TERMS]/[STYLE] user-payload suffix sections for direct requests. */
    public static String directUserSections(List<TranslationService.TermHint> termHints) {
        StringBuilder sections = new StringBuilder();
        String stylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        if (stylePrompt != null && !stylePrompt.isBlank()) {
            sections.append("[STYLE]\n").append(stylePrompt.trim()).append("\n[/STYLE]\n");
        }
        if (termHints != null && !termHints.isEmpty()) {
            sections.append("[TERMS]\n");
            for (TranslationService.TermHint hint : termHints) {
                if (hint == null || hint.original() == null || hint.translation() == null) {
                    continue;
                }
                sections.append(hint.original()).append(" -> ").append(hint.translation()).append('\n');
            }
            sections.append("[/TERMS]\n");
        }
        return sections.toString();
    }

    private static String buildLegacySystemPrompt(String sourceLanguage, String targetLanguage,
                                                  List<TranslationService.TermHint> termHints, RequestMode mode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional translator for Minecraft game content. ");
        String sourceCode = TranslationTextDetector.canonicalLanguageCode(sourceLanguage);
        if ("auto".equals(sourceCode)) {
            prompt.append("Auto-detect the source language and translate the following text to ");
        } else {
            prompt.append("Translate the following text from ").append(languageName(sourceLanguage)).append(" to ");
        }
        prompt.append(languageName(targetLanguage)).append(". ");
        prompt.append("Minecraft formatting, colors, styles, and events are restored by the client code. ");
        prompt.append("Only output the translated text without any explanation or additional content. ");
        prompt.append("Do not echo the source text or produce bilingual output unless the source itself must stay unchanged. ");
        prompt.append("For Minecraft UI and item tooltip text, translate every visible natural-language word or phrase into the target language; do not append, repeat, or keep the source beside the translation. ");
        prompt.append("Do not leave UI labels such as Abbreviations, Hover on their names, Objectives, Enemies, Mana, Damage, Ability, Used, or fullwidth Latin status text unchanged. ");
        prompt.append("Keep only player names, namespace IDs, commands, formatting codes, numbers, placeholders, and symbols unchanged when they are not natural-language text. ");
        prompt.append("Use fluent game-localization wording in the target language. Mechanics should be short, precise, and natural; lore should read naturally. ");
        prompt.append("Stay faithful to the source facts: do not invent lore, targets, triggers, ranges, or effects. ");
        prompt.append("Keep important item type nouns explicit in names: translate Charm and Amulet as 护符, Staff as 法杖, Blueprint as 蓝图, Ballista as 弩炮, Guardian/Warden as 守卫, and do not compress them away. ");
        prompt.append("Translate natural-language text inside existing brackets while preserving the bracket characters and count, for example [Root] -> [缠绕], [Burn] -> [燃烧], [Phase] -> [相位], [Click to TP] -> [点击传送], and [Right-Click] -> [右键]. ");
        prompt.append("In tooltip mechanics, a leading phrase like 'For 10s,' is a duration ('持续 10s'), not a range. Phrases like 'every 0.8s' are intervals. ");
        prompt.append("In game status text, 'without [Status]' means the target does not currently have that status; never translate it as 'will not trigger [Status]'. ");
        prompt.append("Do not add quotation marks, brackets, punctuation, or explanatory wording unless they already exist in the source text or are represented by a frozen placeholder. ");
        prompt.append("If the text contains player names or coordinates, keep them unchanged. ");
        prompt.append("Tokens like @@0@@ or @@1@@ are frozen numeric placeholders owned by the client; keep every one exactly once, unchanged, in the natural position for the target language. ");
        if (mode == RequestMode.PRESERVE_MARKERS) {
            prompt.append("Never modify control markers/tokens such as §§§, @@@F<number>@@@, @@@CTX<number>@@@, @@@TTS<number>@@@, @@@S<number>@@@, @@@S<signIndex>L<lineIndex>@@@, @@@S_END@@@, ");
            prompt.append("§§§PAGE§§§, §§§TITLE§§§, or bracketed marker tags. ");
            prompt.append("@@@F<number>@@@ markers are frozen style placeholders; keep every one exactly. ");
            prompt.append("Keep marker order/count and line breaks unchanged. ");
            prompt.append("When @@@TTS<number>@@@ markers are present, preserve every marker exactly, translate only the text after each marker until the next marker, and never merge, remove, reorder, or move text across those markers. ");
        }
        if (mode == RequestMode.MAPPING_JSON) {
            prompt.append("When the user prompt is a structured mapping document, output JSON only and do not wrap the answer in markdown or code fences. ");
            prompt.append("The JSON shape must be {\"version\":\"mapping-v1\",\"jobId\":\"...\",\"translations\":[{\"id\":\"...\",\"translation\":\"...\"}]}. ");
            prompt.append("Return one translation entry for every unit id from the input. Do not change ids or order. ");
            prompt.append("@@@F<number>@@@ markers are style placeholders owned by the client; every marker in a unit must appear exactly once in that unit's translation. ");
            prompt.append("For layoutMode=flexible-tooltip, each unit may be a semantic style block or an inline styled token. Translate each unit naturally as game localization, not word-by-word. You may change word order and line breaks, but every @@@F marker inside that same unit must appear exactly once. ");
            prompt.append("Do not translate a semantic block by following the source's visual line breaks; translate the block's meaning as a complete title, lore paragraph, operation hint, stat row, or mechanic sentence according to its role/tokenMask. ");
            prompt.append("For layoutMode=fixed-layout, preserve slot boundaries and line structure as instructed by the unit metadata. ");
        }

        String stylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        if (stylePrompt != null) {
            stylePrompt = stylePrompt.trim();
            if (!stylePrompt.isEmpty()) {
                prompt.append("\n\nFollow this custom translation style instruction strictly: ");
                prompt.append(stylePrompt);
                prompt.append(" ");
                prompt.append("Apply it to tone and wording, but never break formatting, control markers, or line structure.");
            }
        }

        if (termHints != null && !termHints.isEmpty()) {
            prompt.append("\n\nUse these term translations for consistency:\n");
            for (TranslationService.TermHint hint : termHints) {
                prompt.append("- \"").append(hint.original()).append("\" -> \"").append(hint.translation())
                        .append("\"\n");
            }
        }

        return prompt.toString();
    }
}
