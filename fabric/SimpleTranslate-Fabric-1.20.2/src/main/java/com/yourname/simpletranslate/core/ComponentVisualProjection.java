package com.yourname.simpletranslate.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker-free projection of a Component JSON array into model-visible semantic
 * leaves and client-owned visual runs.
 *
 * <p>Every translatable run becomes one top-level literal Component in
 * {@link #semanticRoot()}. Resource-pack glyphs, Unicode format/control
 * characters, symbols, dynamic values, coordinates and structurally explicit
 * identifiers never enter that array. Ordinary printable words and acronyms are
 * semantic by default: XP, HP, rank labels and future server vocabulary reach
 * the model without a word allow/deny list. Local atoms remain in the source
 * skeleton and are copied verbatim when a response is rebound. Font metadata is
 * likewise local, but a new font must never make a whole sentence untranslated.</p>
 *
 * <p>The response contract is deliberately small: it must be a top-level JSON
 * array with exactly {@link #slotCount()} entries and every entry must parse as
 * a Minecraft Component. A response entry's visible string is selected by its
 * top-level ordinal. Its nested children and styles are never inspected or
 * trusted, so the model does not have to preserve a nested leaf structure.
 * Source styles, fonts, events and nesting remain authoritative.</p>
 *
 * <p>Typical use:</p>
 * <pre>{@code
 * ComponentVisualProjection projection = ComponentVisualProjection.project(
 *         JsonParser.parseString(sourceJson), targetLanguage);
 * String requestJson = projection.semanticJson();
 * JsonArray rebuilt = projection.rebuildResponseJson(modelResponse);
 * }</pre>
 */
public final class ComponentVisualProjection {

    /** Migration-only recognition of existing local layout tokens; never generated here. */
    private static final Pattern LOCAL_LAYOUT_TOKEN = Pattern.compile(
            "\\u27E6[^\\u27E6\\u27E7]*\\u27E7");
    private static final Pattern COORDINATE_TOKEN = Pattern.compile(
            "\\[\\s*[+-]?\\d+(?:[.,:]\\d+)*(?:\\s*,\\s*[+-]?\\d+(?:[.,:]\\d+)*){1,2}\\s*\\]");
    private static final Pattern SELECTOR_TOKEN = Pattern.compile(
            "@[A-Za-z](?:\\[[^\\]\\r\\n]*\\])?");
    private static final Pattern COMMAND_TOKEN = Pattern.compile(
            "/[A-Za-z0-9_:-]+");
    private static final Pattern PLACEHOLDER_TOKEN = Pattern.compile(
            "(?:%\\d*\\$?[A-Za-z]|\\{\\d+}|\\$\\{[^}\\r\\n]+}|<[A-Za-z0-9_.:-]+>)");
    private static final Pattern ADDRESS_TOKEN = Pattern.compile(
            "(?i:(?:https?://|www\\.)[^\\s]+|(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:/[^\\s]*)?)");
    private static final Pattern NAMESPACED_OR_CODE_TOKEN = Pattern.compile(
            "(?:[a-z0-9_.-]+:[a-z0-9_./-]+|[A-Za-z][A-Za-z0-9.-]*(?:_[A-Za-z0-9_./:-]+)+)");
    /**
     * Active cache-migration detector for source text affected by the retired
     * pre-language-visible projection. It is never used to hide request text.
     */
    private static final Pattern PRE_LANGUAGE_VISIBLE_TOKEN = Pattern.compile(
            "(?:[A-Z][A-Z0-9]{1,8}\\+|(?i:ms|xp|hp|mp|sp|fps|tps|lv|lvl|x|d|h|m|s))");

    private final JsonArray sourceRoot;
    private final JsonArray semanticRoot;
    private final List<LeafPlan> leafPlans;
    private final List<SemanticSlot> slots;

    private ComponentVisualProjection(JsonArray sourceRoot, JsonArray semanticRoot,
                                      List<LeafPlan> leafPlans, List<SemanticSlot> slots) {
        this.sourceRoot = sourceRoot;
        this.semanticRoot = semanticRoot;
        this.leafPlans = List.copyOf(leafPlans);
        this.slots = List.copyOf(slots);
    }

    /** Builds a projection from a serialized top-level Component array. */
    @Nullable
    public static ComponentVisualProjection project(String sourceJson, String targetLanguage) {
        if (sourceJson == null || sourceJson.isBlank()) {
            return null;
        }
        try {
            return project(JsonParser.parseString(sourceJson), targetLanguage);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Selectively invalidates cached responses created while readable acronyms
     * were removed from the semantic request. Unaffected v6 entries keep their
     * exact historical key and remain reusable.
     */
    static boolean needsLanguageVisibleCacheRevision(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(sourceJson);
            if (!parsed.isJsonArray()) {
                return false;
            }
            List<LeafDescriptor> leaves = new ArrayList<>();
            collectRootLeaves(parsed.getAsJsonArray(), leaves, null);
            for (LeafDescriptor leaf : leaves) {
                String text = leaf.text();
                Matcher matcher = PRE_LANGUAGE_VISIBLE_TOKEN.matcher(text);
                while (matcher.find()) {
                    if (tokenBoundaryBefore(text, matcher.start())
                            && tokenBoundaryAfter(text, matcher.end())
                            && !isPossessiveSuffix(text, matcher.start(), matcher.end())) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    /**
     * Convenience entry point that serializes live Components without the old
     * number-marker/cache normalization step.
     */
    @Nullable
    public static ComponentVisualProjection projectComponents(List<Component> source,
                                                              String targetLanguage) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        try {
            JsonArray root = new JsonArray();
            for (Component component : source) {
                root.add(JsonParser.parseString(ComponentJsonCompat.toJson(
                        component == null ? Component.empty() : component)));
            }
            return project(root, targetLanguage);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Builds a projection from a parsed top-level Component array. */
    @Nullable
    public static ComponentVisualProjection project(JsonElement source, String targetLanguage) {
        if (source == null || !source.isJsonArray() || source.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonArray root = source.getAsJsonArray().deepCopy();
        if (!componentsParse(root)) {
            return null;
        }

        List<LeafDescriptor> leaves = new ArrayList<>();
        collectRootLeaves(root, leaves, null);
        List<LeafPlan> plans = new ArrayList<>(leaves.size());
        List<SemanticSlot> slots = new ArrayList<>();
        JsonArray semantic = new JsonArray();
        for (int leafIndex = 0; leafIndex < leaves.size(); leafIndex++) {
            LeafDescriptor leaf = leaves.get(leafIndex);
            plans.add(projectLeaf(leaf, leafIndex, targetLanguage, slots, semantic,
                    isPostClientFontLayoutControl(leaves, leafIndex)));
        }
        return new ComponentVisualProjection(root, semantic, plans, slots);
    }

    public boolean hasSlots() {
        return !slots.isEmpty();
    }

    public int slotCount() {
        return slots.size();
    }

    /** Source metadata for diagnostics and semantic-memory indexing. */
    public List<SemanticSlot> slots() {
        return slots;
    }

    /**
     * Consecutive semantic-slot counts owned by each original top-level
     * Component. Recovery may split between these groups, never inside one;
     * colours, icon-adjacent grammar and nested style runs therefore remain a
     * single grammatical unit on every translation surface.
     */
    public List<Integer> atomicGroupSizes() {
        if (slots.isEmpty()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        int currentTopLevel = slots.get(0).sourceTopLevelIndex();
        int count = 0;
        for (SemanticSlot slot : slots) {
            if (slot.sourceTopLevelIndex() != currentTopLevel) {
                result.add(count);
                currentTopLevel = slot.sourceTopLevelIndex();
                count = 0;
            }
            count++;
        }
        result.add(count);
        return List.copyOf(result);
    }

    /** A defensive copy of the complete local source skeleton. */
    public JsonArray sourceRoot() {
        return sourceRoot.deepCopy();
    }

    /**
     * A defensive copy of the marker-free, semantic-only top-level Component
     * array that is safe to send through the Component JSON transport.
     */
    public JsonArray semanticRoot() {
        return semanticRoot.deepCopy();
    }

    public String semanticJson() {
        return semanticRoot.toString();
    }

    /** Convenience view for callers that already operate on Components. */
    public List<Component> semanticComponents() {
        List<Component> result = new ArrayList<>(semanticRoot.size());
        for (JsonElement element : semanticRoot) {
            result.add(ComponentJsonCompat.fromJson(element));
        }
        return List.copyOf(result);
    }

    /**
     * Reads slot text from a full tree previously rebuilt from this projection.
     * This is for semantic-memory indexing of accepted local cache entries, not
     * model-response acceptance. Opaque atoms are exact delimiters; any tree or
     * delimiter mismatch simply makes the cache entry ineligible for memory.
     */
    @Nullable
    public List<String> alignedTranslatedSlotTexts(JsonElement translatedRoot) {
        if (translatedRoot == null || !translatedRoot.isJsonArray()) {
            return null;
        }
        JsonArray translatedArray = translatedRoot.getAsJsonArray();
        if (translatedArray.size() != sourceRoot.size() || !componentsParse(translatedArray)) {
            return null;
        }
        List<LeafDescriptor> translatedLeaves = new ArrayList<>();
        collectRootLeaves(translatedArray, translatedLeaves, null);
        if (translatedLeaves.size() != leafPlans.size()) {
            return null;
        }
        List<String> result = new ArrayList<>(java.util.Collections.nCopies(slots.size(), null));
        for (int leafIndex = 0; leafIndex < leafPlans.size(); leafIndex++) {
            List<Atom> atoms = leafPlans.get(leafIndex).atoms();
            String translated = translatedLeaves.get(leafIndex).text();
            int cursor = 0;
            for (int atomIndex = 0; atomIndex < atoms.size(); atomIndex++) {
                Atom atom = atoms.get(atomIndex);
                if (atom.slotIndex() < 0) {
                    if (!translated.startsWith(atom.sourceText(), cursor)) {
                        return null;
                    }
                    cursor += atom.sourceText().length();
                    continue;
                }
                String nextOpaque = "";
                for (int next = atomIndex + 1; next < atoms.size(); next++) {
                    Atom candidate = atoms.get(next);
                    if (candidate.slotIndex() < 0 && !candidate.sourceText().isEmpty()) {
                        nextOpaque = candidate.sourceText();
                        break;
                    }
                }
                int end = nextOpaque.isEmpty() ? translated.length()
                        : translated.indexOf(nextOpaque, cursor);
                if (end < cursor) {
                    return null;
                }
                result.set(atom.slotIndex(), translated.substring(cursor, end));
                cursor = end;
            }
            if (cursor != translated.length()) {
                return null;
            }
        }
        return result.stream().anyMatch(java.util.Objects::isNull) ? null : List.copyOf(result);
    }

    /** Strict JSON entry point; prose wrappers/fences are intentionally not sanitized here. */
    @Nullable
    public JsonArray rebuildResponseJson(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        try {
            return rebuildResponse(JsonParser.parseString(responseJson));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Rebinds a parsed model response by top-level ordinal only. Nested response
     * layout and response styles are deliberately ignored.
     */
    @Nullable
    public JsonArray rebuildResponse(JsonElement response) {
        if (response == null || !response.isJsonArray()) {
            return null;
        }
        JsonArray array = response.getAsJsonArray();
        if (array.size() != slots.size()) {
            return null;
        }
        List<String> translated = new ArrayList<>(array.size());
        try {
            for (int index = 0; index < array.size(); index++) {
                JsonElement element = array.get(index);
                if (element == null || element.isJsonNull()) {
                    return null;
                }
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return null;
                }
                String value = component.getString();
                // An empty but parseable response still satisfies the wire
                // contract. Bind that one slot to its source text instead of
                // erasing prose or rejecting otherwise valid sibling slots.
                translated.add(value == null || value.isBlank()
                        ? slots.get(index).sourceText() : value);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return rebuildWithSlotTexts(translated);
    }

    /**
     * Rebinds already-parsed response Components. The caller is responsible for
     * having applied the same top-level Component parse/count contract.
     */
    @Nullable
    public JsonArray rebuildComponents(List<Component> translatedComponents) {
        if (translatedComponents == null || translatedComponents.size() != slots.size()) {
            return null;
        }
        List<String> values = new ArrayList<>(translatedComponents.size());
        for (int index = 0; index < translatedComponents.size(); index++) {
            Component component = translatedComponents.get(index);
            if (component == null) {
                return null;
            }
            String value = component.getString();
            values.add(value == null || value.isBlank()
                    ? slots.get(index).sourceText() : value);
        }
        return rebuildWithSlotTexts(values);
    }

    /** Rebinds parsed semantic Components and returns the full current source list. */
    @Nullable
    public List<Component> rebuildComponentList(List<Component> translatedComponents) {
        JsonArray rebuilt = rebuildComponents(translatedComponents);
        if (rebuilt == null) {
            return null;
        }
        List<Component> result = new ArrayList<>(rebuilt.size());
        try {
            for (JsonElement element : rebuilt) {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return null;
                }
                result.add(component);
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Parses a rebuilt full source tree back into its original top-level Component count. */
    @Nullable
    public List<Component> rebuildComponentList(JsonElement response) {
        JsonArray rebuilt = rebuildResponse(response);
        if (rebuilt == null) {
            return null;
        }
        List<Component> result = new ArrayList<>(rebuilt.size());
        try {
            for (JsonElement element : rebuilt) {
                Component component = ComponentJsonCompat.fromJson(element);
                if (component == null) {
                    return null;
                }
                result.add(component);
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private JsonArray rebuildWithSlotTexts(List<String> translated) {
        if (translated == null || translated.size() != slots.size()) {
            return null;
        }
        JsonArray copy = sourceRoot.deepCopy();
        List<LeafRef> refs = new ArrayList<>();
        collectRootLeaves(copy, null, refs);
        if (refs.size() != leafPlans.size()) {
            return null;
        }
        for (int leafIndex = 0; leafIndex < leafPlans.size(); leafIndex++) {
            StringBuilder text = new StringBuilder();
            for (Atom atom : leafPlans.get(leafIndex).atoms()) {
                text.append(atom.slotIndex() >= 0
                        ? translated.get(atom.slotIndex())
                        : atom.sourceText());
            }
            refs.get(leafIndex).set(text.toString());
        }
        return copy;
    }

    private static LeafPlan projectLeaf(LeafDescriptor leaf, int leafIndex,
                                        String targetLanguage,
                                        List<SemanticSlot> slots,
                                        JsonArray semantic,
                                        boolean localLayoutControl) {
        String text = leaf.text();
        List<Atom> atoms = new ArrayList<>();
        if (text.isEmpty()) {
            appendRaw(atoms, text);
            return new LeafPlan(atoms);
        }
        if (localLayoutControl || isCompactCustomFontGlyph(leaf)) {
            appendRaw(atoms, text);
            return new LeafPlan(atoms);
        }

        int plainStart = 0;
        int cursor = 0;
        while (cursor < text.length()) {
            int legacyEnd = legacyFormatEnd(text, cursor);
            int tokenEnd = opaqueTokenEnd(text, cursor);
            int repeatedSpaceEnd = repeatedSpaceEnd(text, cursor);
            int cp = text.codePointAt(cursor);
            int visualEnd = isOpaqueCodepoint(cp)
                    ? cursor + Character.charCount(cp) : cursor;
            int rawEnd = Math.max(Math.max(legacyEnd, tokenEnd),
                    Math.max(repeatedSpaceEnd, visualEnd));
            if (rawEnd > cursor) {
                appendSemanticCandidate(text, plainStart, cursor, leafIndex,
                        leaf.sourceTopLevelIndex(),
                        targetLanguage, atoms, slots, semantic);
                appendRaw(atoms, text.substring(cursor, rawEnd));
                cursor = rawEnd;
                plainStart = cursor;
                continue;
            }
            cursor += Character.charCount(cp);
        }
        appendSemanticCandidate(text, plainStart, text.length(), leafIndex,
                leaf.sourceTopLevelIndex(),
                targetLanguage, atoms, slots, semantic);
        return new LeafPlan(atoms);
    }

    /**
     * Some HUD packs emit U+00C0 immediately after a client-only font carrier
     * in their content-tracker templates. Their own scoreboard/text parsers
     * treat that codepoint as padding, not language. Scope the rule to a
     * standalone run directly following a client-only font marker so an
     * ordinary phrase such as "À la carte" remains translatable everywhere else.
     */
    private static boolean isPostClientFontLayoutControl(List<LeafDescriptor> leaves,
                                                         int leafIndex) {
        if (leaves == null || leafIndex <= 0 || leafIndex >= leaves.size()) {
            return false;
        }
        LeafDescriptor leaf = leaves.get(leafIndex);
        String text = leaf.text();
        if (text.isEmpty() || !text.codePoints().allMatch(codePoint -> codePoint == 0x00C0)) {
            return false;
        }
        for (int previousIndex = leafIndex - 1; previousIndex >= 0; previousIndex--) {
            LeafDescriptor previous = leaves.get(previousIndex);
            if (previous.sourceTopLevelIndex() != leaf.sourceTopLevelIndex()) {
                return false;
            }
            if (previous.text().isEmpty()) {
                continue;
            }
            return ComponentJsonCompat.isLocalFontMarker(previous.fontId());
        }
        return false;
    }

    /**
     * Resource packs can map ordinary ASCII codepoints to bitmap sprites. A
     * compact custom-font leaf can therefore be a visual atom even when Unicode
     * alone looks like English. Only a single codepoint is ambiguous enough to
     * keep local; two-letter words such as "an", "of" and "to" are ordinary
     * sentence grammar and must reach the model regardless of their font.
     */
    private static boolean isCompactCustomFontGlyph(LeafDescriptor leaf) {
        if (leaf != null && ComponentJsonCompat.isLocalFontMarker(leaf.fontId())) {
            // Atlas/player sprite fonts are client-only visual atoms. Their
            // serializable marker can carry an ordinary letter (for example a
            // quest icon), but that letter must never reach the model.
            return true;
        }
        if (leaf == null || leaf.fontId().isBlank()
                || "minecraft:default".equals(leaf.fontId())
                || "minecraft:uniform".equals(leaf.fontId())) {
            return false;
        }
        String stripped = leaf.text().strip();
        if (stripped.isEmpty()
                || stripped.codePointCount(0, stripped.length()) != 1) {
            return false;
        }
        int codePoint = stripped.codePointAt(0);
        // Letters in any script are language, even when a resource pack emits
        // one styled/custom-font Component per character. Actual icon glyphs
        // are represented by PUA/control/symbol codepoints and stay local via
        // the ordinary opaque-codepoint path.
        return !Character.isLetter(codePoint) && !Character.isDigit(codePoint);
    }

    private static void appendSemanticCandidate(String source, int start, int end,
                                                int leafIndex, int sourceTopLevelIndex,
                                                String targetLanguage,
                                                List<Atom> atoms,
                                                List<SemanticSlot> slots,
                                                JsonArray semantic) {
        if (start >= end) {
            return;
        }
        int coreStart = start;
        int coreEnd = end;
        while (coreStart < coreEnd) {
            int cp = source.codePointAt(coreStart);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                break;
            }
            coreStart += Character.charCount(cp);
        }
        while (coreEnd > coreStart) {
            int cp = source.codePointBefore(coreEnd);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                break;
            }
            coreEnd -= Character.charCount(cp);
        }
        String core = source.substring(coreStart, coreEnd);
        if (core.isEmpty()
                || !TranslationTextDetector.containsTranslatableText(core, 1, targetLanguage)) {
            appendRaw(atoms, source.substring(start, end));
            return;
        }
        appendRaw(atoms, source.substring(start, coreStart));
        int slotIndex = slots.size();
        slots.add(new SemanticSlot(slotIndex, leafIndex, sourceTopLevelIndex, core));
        // A JSON string is the canonical literal shorthand for a Minecraft
        // Component. Semantic slots carry no style (the local source skeleton
        // owns it), so the compact form reduces model split/merge mistakes.
        semantic.add(core);
        atoms.add(new Atom(core, slotIndex));
        appendRaw(atoms, source.substring(coreEnd, end));
    }

    private static void appendRaw(List<Atom> atoms, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!atoms.isEmpty() && atoms.get(atoms.size() - 1).slotIndex() < 0) {
            Atom previous = atoms.remove(atoms.size() - 1);
            atoms.add(new Atom(previous.sourceText() + text, -1));
        } else {
            atoms.add(new Atom(text, -1));
        }
    }

    private static int legacyFormatEnd(String text, int index) {
        if (text.charAt(index) != '\u00a7' || index + 1 >= text.length()) {
            return index;
        }
        return index + 1 + Character.charCount(text.codePointAt(index + 1));
    }

    private static int repeatedSpaceEnd(String text, int index) {
        if (text.charAt(index) != ' ') {
            return index;
        }
        int cursor = index;
        while (cursor < text.length() && text.charAt(cursor) == ' ') {
            cursor++;
        }
        return cursor - index > 1 ? cursor : index;
    }

    private static int opaqueTokenEnd(String text, int index) {
        int localLayoutEnd = matchToken(LOCAL_LAYOUT_TOKEN, text, index);
        if (localLayoutEnd > index) {
            return localLayoutEnd;
        }
        if (!tokenBoundaryBefore(text, index)) {
            return index;
        }
        int end = matchToken(COORDINATE_TOKEN, text, index);
        if (end < 0) end = matchToken(SELECTOR_TOKEN, text, index);
        if (end < 0) end = matchToken(PLACEHOLDER_TOKEN, text, index);
        if (end < 0) end = matchToken(ADDRESS_TOKEN, text, index);
        if (end < 0) end = matchToken(NAMESPACED_OR_CODE_TOKEN, text, index);
        if (end < 0) end = ComponentJsonNumberNormalizer.dynamicValueEnd(text, index);
        if (end < 0) end = matchToken(COMMAND_TOKEN, text, index);
        return end > index && tokenBoundaryAfter(text, end) ? end : index;
    }

    private static boolean isPossessiveSuffix(String text, int start, int end) {
        if (text == null || start <= 0 || end - start != 1
                || (text.charAt(start) != 's' && text.charAt(start) != 'S')) {
            return false;
        }
        char previous = text.charAt(start - 1);
        return previous == '\'' || previous == '\u2019';
    }

    private static int matchToken(Pattern pattern, String text, int index) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(index, text.length());
        return matcher.lookingAt() ? matcher.end() : -1;
    }

    private static boolean tokenBoundaryBefore(String text, int index) {
        if (index <= 0) {
            return true;
        }
        int cp = text.codePointBefore(index);
        return !Character.isLetterOrDigit(cp) && cp != '_';
    }

    private static boolean tokenBoundaryAfter(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        int cp = text.codePointAt(index);
        return !Character.isLetterOrDigit(cp) && cp != '_';
    }

    /**
     * Unicode-category based classification deliberately avoids an icon ID
     * allowlist: previously unseen resource-pack glyphs remain local too.
     */
    public static boolean isOpaqueCodepoint(int cp) {
        if (cp < 0x20 || cp == 0x7F || Character.getType(cp) == Character.FORMAT
                || isVariationSelector(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || type == Character.SURROGATE
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL
                || (Character.isWhitespace(cp) && cp != ' ')
                || (Character.isSpaceChar(cp) && cp != ' ');
    }

    private static boolean isVariationSelector(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static boolean componentsParse(JsonArray root) {
        try {
            for (JsonElement element : root) {
                if (element == null || element.isJsonNull()
                        || ComponentJsonCompat.fromJson(element) == null) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void collectRootLeaves(JsonArray root,
                                          @Nullable List<LeafDescriptor> leaves,
                                          @Nullable List<LeafRef> refs) {
        for (int index = 0; index < root.size(); index++) {
            JsonElement child = root.get(index);
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                if (leaves != null) {
                    leaves.add(new LeafDescriptor(child.getAsString(), "", index));
                }
                if (refs != null) {
                    refs.add(new ArrayLeafRef(root, index));
                }
            } else {
                collectLeaves(child, "", index, leaves, refs);
            }
        }
    }

    private static void collectLeaves(JsonElement element, String inheritedFont,
                                      int sourceTopLevelIndex,
                                      @Nullable List<LeafDescriptor> leaves,
                                      @Nullable List<LeafRef> refs) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement child = array.get(index);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    if (leaves != null) {
                        leaves.add(new LeafDescriptor(
                                child.getAsString(), inheritedFont, sourceTopLevelIndex));
                    }
                    if (refs != null) {
                        refs.add(new ArrayLeafRef(array, index));
                    }
                } else {
                    collectLeaves(child, inheritedFont, sourceTopLevelIndex, leaves, refs);
                }
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String font = effectiveFont(object, inheritedFont);
        String property = stringProperty(object, "text")
                ? "text"
                : stringProperty(object, "fallback")
                ? "fallback"
                : dynamicNaturalLanguageTranslateProperty(object);
        if (property != null) {
            if (leaves != null) {
                leaves.add(new LeafDescriptor(
                        object.get(property).getAsString(), font, sourceTopLevelIndex));
            }
            if (refs != null) {
                refs.add(new ObjectLeafRef(object, property));
            }
        }
        collectChild(object, "extra", font, sourceTopLevelIndex, leaves, refs);
        collectChild(object, "with", font, sourceTopLevelIndex, leaves, refs);
        collectChild(object, "separator", font, sourceTopLevelIndex, leaves, refs);
    }

    private static void collectChild(JsonObject object, String key, String font,
                                     int sourceTopLevelIndex,
                                     @Nullable List<LeafDescriptor> leaves,
                                     @Nullable List<LeafRef> refs) {
        if (object.has(key)) {
            collectLeaves(object.get(key), font, sourceTopLevelIndex, leaves, refs);
        }
    }

    private static String effectiveFont(JsonObject object, String inheritedFont) {
        if (stringProperty(object, "font")) {
            return object.get("font").getAsString();
        }
        return inheritedFont == null ? "" : inheritedFont;
    }

    private static boolean stringProperty(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString();
    }

    /**
     * Some mods use a TranslatableComponent as a styled message container and
     * place the visible prose directly in its key. If no active language table
     * owns that key, the key itself is the rendered text and must become a
     * semantic leaf. Conventional resource keys stay local even when a client
     * happens not to have their resource pack loaded yet.
     */
    @Nullable
    private static String dynamicNaturalLanguageTranslateProperty(JsonObject object) {
        if (!stringProperty(object, "translate")) {
            return null;
        }
        String key = object.get("translate").getAsString();
        if (key.isBlank() || Language.getInstance().has(key)
                || !looksLikeNaturalLanguageKey(key)) {
            return null;
        }
        return "translate";
    }

    static boolean looksLikeNaturalLanguageKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String stripped = key.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "").strip();
        if (stripped.isEmpty()) {
            return false;
        }
        // Resource identifiers are deliberately conservative. Natural prose
        // normally has whitespace or sentence punctuation; a lone title-case
        // word is also safe because ordinary language keys are dotted/lowercase.
        if (stripped.matches("(?i)[a-z0-9_-]+(?:[.:/][a-z0-9_.:/-]+)+")
                || stripped.matches("[A-Z0-9_+/-]{2,}")) {
            return false;
        }
        boolean proseShape = stripped.codePoints().anyMatch(Character::isWhitespace)
                || stripped.matches(".*[.!?,;:。！？；：].*")
                || stripped.matches("[A-Z][A-Za-z'-]{2,}")
                || (stripped.codePoints().filter(Character::isLetter).count() >= 2
                && stripped.codePoints().anyMatch(codePoint -> codePoint > 0x7F));
        // Target-language filtering belongs to projectLeaf(), which already
        // receives the actual targetLanguage. This classifier only decides
        // whether an absent translate key is prose rather than a resource ID.
        // Keeping it target-independent supports Chinese -> English, Japanese
        // -> Korean and every other configured direction.
        return proseShape && stripped.codePoints().anyMatch(Character::isLetter);
    }

    /** One model-visible semantic run in deterministic top-level request order. */
    public record SemanticSlot(int index, int sourceLeafIndex,
                               int sourceTopLevelIndex, String sourceText) {
        public SemanticSlot {
            sourceText = sourceText == null ? "" : sourceText;
        }
    }

    private record LeafDescriptor(String text, String fontId, int sourceTopLevelIndex) {
        private LeafDescriptor {
            text = text == null ? "" : text;
            fontId = fontId == null ? "" : fontId;
        }
    }

    private record LeafPlan(List<Atom> atoms) {
        private LeafPlan {
            atoms = List.copyOf(atoms == null ? List.of() : atoms);
        }
    }

    private record Atom(String sourceText, int slotIndex) {
        private Atom {
            sourceText = sourceText == null ? "" : sourceText;
        }
    }

    private interface LeafRef {
        void set(String value);
    }

    private record ObjectLeafRef(JsonObject object, String property) implements LeafRef {
        @Override
        public void set(String value) {
            object.addProperty(property, value);
        }
    }

    private record ArrayLeafRef(JsonArray array, int index) implements LeafRef {
        @Override
        public void set(String value) {
            array.set(index, new JsonPrimitive(value));
        }
    }
}
