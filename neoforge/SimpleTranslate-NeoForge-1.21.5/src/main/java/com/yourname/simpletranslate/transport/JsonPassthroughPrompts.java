package com.yourname.simpletranslate.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.config.TranslationProfileManager;
import com.yourname.simpletranslate.core.TranslationTextDetector;

import java.util.List;
import java.util.Locale;

/**
 * System prompt for the JSON-passthrough component translation mode.
 *
 * <p>The model receives an ordered top-level array of semantic Minecraft
 * Components. Opaque visuals, format controls, custom-font positioning glyphs,
 * dynamic values and hidden hover payloads stay local. The response therefore
 * needs only the same top-level count and parseable translated Components; the
 * client binds their visible text back to the untouched source structure.</p>
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
        return buildSystemPrompt(sourceLanguage, targetLanguage, termHints, surface, "");
    }

    public static String buildSystemPrompt(String sourceLanguage, String targetLanguage,
                                           List<com.yourname.simpletranslate.api.TranslationRequest.Term> termHints,
                                           String surface, String promptContext) {
        String sourceCode = TranslationTextDetector.canonicalLanguageCode(sourceLanguage);
        String sourceClause = "auto".equals(sourceCode)
                ? "Auto-detect the source language and translate"
                : "Translate from " + TranslationTextDetector.displayLanguageName(sourceLanguage);
        String target = TranslationTextDetector.displayLanguageName(targetLanguage);
        String surfaceValue = surface == null ? "" : surface.toLowerCase(Locale.ROOT);
        boolean wynnSemanticSurface = surfaceValue.contains(".wynn.")
                || surfaceValue.startsWith("wynn.");
        boolean wynnNpcNameplateSurface = surfaceValue.startsWith("text_display.wynn.npc_label.");
        boolean wynnDialogueContentSurface = surfaceValue.contains(".wynn.dialogue.content.");
        boolean wholeGuiFrame = surfaceValue.startsWith("gui.component.visible_frame.")
                || surfaceValue.startsWith("hud.visible_frame.");
        boolean itemTooltipFrame = wholeGuiFrame
                && callerContextContains(promptContext, "frame_context_kind=item_tooltip");
        boolean itemTooltipSurface = surfaceValue.startsWith("tooltip.item_context")
                || surfaceValue.startsWith("tooltip.visible.item.")
                || itemTooltipFrame;
        boolean structuralRetry = promptContext != null
                && promptContext.contains("\"component_structure_retry\":true");
        boolean partitionRecovery = promptContext != null
                && promptContext.contains("\"component_partition_recovery\":true");

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a professional Minecraft game localizer. ")
                .append(sourceClause).append(" to ").append(target).append(".\n");
        prompt.append("The user message contains an ordered JSON array of semantic Minecraft Component slots");
        if (itemTooltipSurface) {
            prompt.append(" from one item tooltip");
        } else if (wholeGuiFrame) {
            prompt.append(" from one visible GUI draw frame");
        } else if (surfaceValue.startsWith("hover.context")) {
            prompt.append(" from one chat hover tooltip");
        } else if (surfaceValue.startsWith("sign.manual")) {
            prompt.append(" from manually selected Minecraft signs");
        } else if (surfaceValue.startsWith("sign.auto")) {
            prompt.append(" from one Minecraft sign");
        } else if (surfaceValue.startsWith("chat.")) {
            prompt.append(" from chat messages");
        } else if (wynnNpcNameplateSurface) {
            prompt.append(" from one Wynncraft NPC, merchant, or service nameplate");
        }
        prompt.append(". ")
                .append("Each top-level element is one translatable slot in reading order. Read the whole array ")
                .append("as one coherent visible document before translating. Use neighboring slots to resolve ")
                .append("sentence fragments, articles, pronouns, menu labels, and terminology; translate every slot ")
                .append("into the target language, and keep each result at the ")
                .append("same top-level index. Return ONLY a JSON array of Minecraft Components — ")
                .append("no markdown, no explanation, no headers.\n");
        // Primacy position: the player's orders lead the rule sections; the same
        // section is repeated at the end of the prompt (recency position).
        prompt.append(TranslationProfileManager.promptSection());
        prompt.append("CRITICAL STRUCTURAL RULES:\n");
        prompt.append("- Preserve the exact top-level array length and order: one input slot → one output Component.\n");
        prompt.append("- Every output element must be a valid Minecraft Component containing the complete translation ")
                .append("of its corresponding slot. Never merge, split, reorder, or drop top-level slots.\n");
        prompt.append("- A visual line or tooltip row may be only part of a sentence. Do not translate slots as isolated ")
                .append("dictionary entries: carry grammar and meaning across adjacent slots, while still returning ")
                .append("exactly one output Component at each original index.\n");
        prompt.append("- These semantic slots normally use Minecraft's valid JSON string Component shorthand. Prefer ")
                .append("one JSON string per output slot; do not expand one string into multiple top-level elements.\n");
        prompt.append("- Do not invent icons, private-use characters, format controls, placeholders, custom fonts, ")
                .append("coordinates, or spacing glyphs. The client preserves all opaque visuals and dynamic values locally.\n");
        prompt.append("- Keep the JSON valid: proper quotes, commas, and brackets.\n");
        if (structuralRetry) {
            prompt.append("STRUCTURAL CORRECTION RETRY: the previous non-empty answer violated the Component JSON ")
                    .append("shape. Recount the input top-level elements before translating, then recount the output ")
                    .append("before finishing. Do not merge adjacent phrases into one element and do not split one ")
                    .append("element into multiple top-level elements. Return only the corrected JSON array.\n");
        }
        if (partitionRecovery) {
            prompt.append("COMPONENT PARTITION RECOVERY: the user array is one contiguous partition of a larger ")
                    .append("source document. Preserve this partition's exact top-level array length. If one translated ")
                    .append("slot needs multiple styled fragments, place them inside that slot's nested extra array; ")
                    .append("never create another top-level element. The complete source document remains available ")
                    .append("in context for terminology and sentence meaning.\n");
        }
        prompt.append("TEXT TRANSLATION RULES:\n");
        prompt.append("- Translate natural-language words and phrases in every \"text\" field.\n");
        prompt.append("- Keep player names, /commands, @selectors, and ordinary format placeholders (%s, {0}) unchanged.\n");
        prompt.append("- If a \"text\" field is empty (\"\"), keep it empty.\n");
        prompt.append("- If a \"text\" field has no natural language (only symbols/numbers), keep it unchanged.\n");
        prompt.append("- Unless the player's orders or the mandatory terminology below say otherwise, for game ")
                .append("content titles, item names, skill names, and invented Latin words, create a localized ")
                .append("name or natural transliteration; do not copy the Latin unchanged. Only keep real player ")
                .append("names unchanged.\n");
        prompt.append("- Some entries are sentence fragments separated by a classified live value, coordinate, icon, key glyph, or ")
                .append("other value that the client retains locally. The optional full source block shows those gaps. ")
                .append("Translate the surrounding entries so their unchanged-order concatenation with every retained ")
                .append("value is fluent in the target language. Never leave a dangling source-language article or ")
                .append("preposition such as 'the', 'a', 'an', 'of', or 'to' beside a retained value.\n");
        prompt.append("- In context metadata only, <number> marks a classified live number already owned by the client. ")
                .append("Never emit <number>, a copied digit, a spelled-out replacement value, or any new placeholder. ")
                .append("Translate only the requested words around that local gap; the client inserts the value once. ")
                .append("All ordinary numbers present in the JSON request are semantic sentence content: preserve each exactly once ")
                .append("and translate the grammar around it normally.\n");
        if (wynnSemanticSurface) {
            prompt.append("- This is a Wynncraft semantic-layout request. Every entry contains only a safe ")
                    .append("natural-language phrase; translate every phrase, including short menu labels. ")
                    .append("Do not invent icons, keybinds, arrows, spacing, private-use glyphs, or legacy § format codes; ")
                    .append("the client restores those outside this JSON array.\n");
            prompt.append("- Interpret capitalized professions, merchants, and service names from the surrounding ")
                    .append("Wynncraft sentence as in-world roles, never as programming identifiers or item IDs. ")
                    .append("For Chinese, \"Item Identifier\"/\"Item Identifiers\" means \"物品鉴定师\", not \"物品标识符\".\n");
        }
        if (wynnNpcNameplateSurface) {
            prompt.append("- This is a short Wynncraft NPC, merchant, or service title, not a program identifier or item ID. ")
                    .append("Localize it as a natural in-world role/title; keep only genuine player names unchanged. ")
                    .append("For example, translate \"Item Identifier\" as \"物品鉴定师\" in Chinese, never \"物品标识符\".\n");
        }
        if (wynnDialogueContentSurface) {
            prompt.append("- This Wynncraft dialogue BODY slot is one complete spoken paragraph. Physical source rows, "
                    + "style fragments, icons, keycaps, controls, and format glyphs are presentation owned by the client. "
                    + "Return one fluent paragraph translation in this slot; never split the sentence around source visual "
                    + "boundaries or invent replacements for client-owned visuals. CONTROL prose may be translated, but "
                    + "protected keycap glyphs remain client-owned.\n");
        }
        if (wholeGuiFrame) {
            prompt.append("- GUI frame entries are physical draw rows, labels, or style fragments, not guaranteed logical sentences. ")
                    .append("First reconstruct every complete clause from the full ordered source block and caller_context. ")
                    .append("A visual wrap must not change which trailing quantity, location, or condition applies to the preceding clause. ")
                    .append("Then distribute the translated wording across the same ordered slots without duplicating or omitting meaning.\n");
        }
        if (surfaceValue.startsWith("entity.")) {
            prompt.append("- This is an entity name. Keep genuine player/account names unchanged. Localize NPC names, ")
                    .append("merchant/service roles, creature titles and other server-authored entity names according to ")
                    .append("the active scope and player translation profile; never interpret a service title as a ")
                    .append("programming identifier.\n");
        }
        if (itemTooltipSurface) {
            prompt.append("- This is an item tooltip: translate the title, lore, mechanic phrases, equipment labels, ")
                    .append("attribute names, rarity/category badges, all-caps headings, control hints, remaining-count labels, ")
                    .append("and sentence fragments coherently across all array entries. Short words ")
                    .append("around an icon or colour boundary still belong to the surrounding sentence; move their ")
                    .append("meaning between same-order slots when needed so the concatenated target text is natural. ")
                    .append("For quest objectives, preserve logical scope: a trailing phrase such as 'in N games/matches' ")
                    .append("normally applies to the whole preceding condition, not merely the nearest verb; express that scope explicitly.\n");
        } else if (surfaceValue.startsWith("hover.context")) {
            prompt.append("- This is a chat hover tooltip: understand the whole tooltip before translating titles, ")
                    .append("skill descriptions, lore, and mechanic lines. Keep commands and numeric values unchanged.\n");
        } else if (surfaceValue.startsWith("chat.outgoing")) {
            prompt.append("- This is a player's outgoing chat message: translate the whole message into the target ")
                    .append("language before it is sent. If the input mixes languages, convert every natural-language ")
                    .append("fragment into the target language while keeping names, commands, and placeholders unchanged.\n");
        } else if (surfaceValue.startsWith("chat.")) {
            prompt.append("- For consecutive chat/menu lines, understand the whole array as one server message block ")
                    .append("when possible, but still return one translated component per input component.\n");
        }
        if (isChineseTarget(targetLanguage)) {
            prompt.append("Examples:\n");
            prompt.append("Input: [\"Steve found a \",\"Diamond Sword\"]\n");
            prompt.append("Output: [\"Steve 找到了一把\",\"钻石剑\"]\n");
            prompt.append("Input: [\"Enemies will come from three directions:\"]\n");
            prompt.append("Output: [\"敌人将从三个方向进攻：\"]\n");
            if (itemTooltipSurface) {
                prompt.append("Input: [\"This item's power has been sealed,\",\"an\",\"Item Identifier\",\"can unlock\",\"its potential.\"]\n");
                prompt.append("Output: [\"这件物品的力量已被封印，\",\"将它带给\",\"物品鉴定师\",\"即可解锁\",\"其潜力。\"]\n");
                prompt.append("For a quest shaped as surviving for a duration or winning across a game count, the trailing game-count scope applies to the whole condition, not just the nearest verb. Keep every ordinary quantity exactly once in the translated objective.\n");
                prompt.append("Translate badge text such as COMMON / WEEKLY QUEST, and phrase a leading retained count plus 'remaining' as a natural number-first availability label, not the literal word order '数量 + 剩余'.\n");
            }
        }
        if (termHints != null && !termHints.isEmpty()) {
            StringBuilder mandatory = new StringBuilder();
            StringBuilder keepOriginal = new StringBuilder();
            for (com.yourname.simpletranslate.api.TranslationRequest.Term term : termHints) {
                if (term == null || term.source() == null || term.target() == null
                        || term.source().isBlank() || term.target().isBlank()) {
                    continue;
                }
                if (term.source().equals(term.target())) {
                    keepOriginal.append("- \"").append(term.source()).append("\"\n");
                } else {
                    mandatory.append("- \"").append(term.source()).append("\" -> \"")
                            .append(term.target()).append("\"\n");
                }
            }
            if (mandatory.length() > 0) {
                prompt.append("MANDATORY TERMINOLOGY (overrides all other translation rules): render each source ")
                        .append("term EXACTLY as its target wherever it appears; never transliterate, localize ")
                        .append("or paraphrase it differently.\n").append(mandatory);
            }
            if (keepOriginal.length() > 0) {
                prompt.append("Keep these terms in their original form, unchanged: the player explicitly wants ")
                        .append("them left as-is, never translated or transliterated.\n").append(keepOriginal);
            }
        }
        TranslationPromptPolicy.appendSharedSections(prompt, promptContext);
        return prompt.toString().trim();
    }

    private static boolean isChineseTarget(String targetLanguage) {
        String code = TranslationTextDetector.canonicalLanguageCode(targetLanguage);
        return code != null && code.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static boolean callerContextContains(String promptContext, String marker) {
        if (promptContext == null || promptContext.isBlank() || marker == null || marker.isEmpty()) {
            return false;
        }
        if (promptContext.contains(marker)) {
            return true;
        }
        try {
            JsonElement parsed = JsonParser.parseString(promptContext);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject metadata = parsed.getAsJsonObject();
            JsonElement callerContext = metadata.get("caller_context");
            return callerContext != null
                    && callerContext.isJsonPrimitive()
                    && callerContext.getAsString().contains(marker);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
