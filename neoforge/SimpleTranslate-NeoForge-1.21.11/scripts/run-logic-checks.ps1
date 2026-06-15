param(
    [string]$ProjectDir = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"

Push-Location $ProjectDir
try {
    & .\gradlew.bat compileJava --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "compileJava failed with exit code $LASTEXITCODE"
    }

    $gsonJar = Get-ChildItem -Path "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" |
        ForEach-Object {
            $version = [version]"0.0.0"
            if ($_.FullName -match "\\gson\\([^\\]+)\\") {
                try { $version = [version]$Matches[1] } catch { $version = [version]"0.0.0" }
            }
            [pscustomobject]@{ File = $_; Version = $version; LastWriteTime = $_.LastWriteTime }
        } |
        Sort-Object Version, LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty File
    if (-not $gsonJar) {
        throw "Could not find gson jar in Gradle cache"
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-logic-checks-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateLogicChecks.java"

    $javaSource = @'
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.translation.DeepSeekTranslationService;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectStatusTerms;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import com.yourname.simpletranslate.util.TextSegmentInfo;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import com.yourname.simpletranslate.util.TranslationCacheKeys;
import com.yourname.simpletranslate.util.TranslationTextDetector;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.Bootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class SimpleTranslateLogicChecks {
    public static void main(String[] args) throws Exception {
        tryBootstrapMinecraft();
        checkModelOutputSanitizer();
        checkStreamedTranslationParsing();
        checkDirectCacheKeyProtocol();
        checkEditableTranslationCache();
        checkDirectFormattedRoundTrip();
        checkHudTitleGroupDirect();
        checkTextDisplayDirectStyleSlots();
        checkTranslatedTooltipMarker();
        checkDirectPlainContextPayload();
        checkItemTooltipContextPayload();
        checkUnicodeTextDetection();
        checkDirectFormattedKeepsLocalizedHotkeys();
        checkDirectStatusTermConsistency();
        checkDirectAcceptsResidualSourceLanguage();
        checkKoreanPlainFallbackAcceptsLlmResult();
        checkChatSystemMessagePrefixDetection();
        checkSignModeEpoch();
        checkSharedSignComponentSerialization();
        checkModelNormalization();
        checkApiFormatAndParallelConfig();
        checkConfigMigrationAndUnknownKeyPreservation();
        System.out.println("SimpleTranslate logic checks passed");
    }

    private static void tryBootstrapMinecraft() throws Exception {
        if (FMLLoader.getCurrentOrNull() == null) {
            Constructor<FMLLoader> constructor = FMLLoader.class.getDeclaredConstructor(
                    ClassLoader.class, String[].class, Dist.class, boolean.class, Path.class);
            constructor.setAccessible(true);
            constructor.newInstance(SimpleTranslateLogicChecks.class.getClassLoader(),
                    new String[0], Dist.CLIENT, false, Path.of(".").toAbsolutePath());
        }
        LoadingModList loadingModList = LoadingModList.of(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        Field loadingModListField = FMLLoader.class.getDeclaredField("loadingModList");
        loadingModListField.setAccessible(true);
        loadingModListField.set(FMLLoader.getCurrent(), loadingModList);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static void checkModelOutputSanitizer() {
        assertEquals("\u94fe\u9501\u95ea\u7535",
                ModelOutputSanitizer.sanitize("<think>reasoning</think>\n\u8bd1\u6587\uff1a\u94fe\u9501\u95ea\u7535"),
                "thinking blocks and output labels should be removed");
        assertEquals("\u6700\u5927\u76ee\u6807\u6570\uff1a3",
                ModelOutputSanitizer.sanitize("```text\n\u6700\u5927\u76ee\u6807\u6570\uff1a3\n```"),
                "fenced code wrappers should be removed");
    }

    private static void checkStreamedTranslationParsing() throws Exception {
        DeepSeekTranslationService service = new DeepSeekTranslationService();
        Method parseResponse = DeepSeekTranslationService.class.getDeclaredMethod("parseResponse", String.class);
        parseResponse.setAccessible(true);
        String response = """
                data: {"choices":[{"delta":{"content":"\u5f00\u59cb"}}]}

                data: {"choices":[{"delta":{"content":"\u6e38\u620f"}}]}

                data: [DONE]
                """;
        assertEquals("\u5f00\u59cb\u6e38\u620f", parseResponse.invoke(service, response),
                "streaming response chunks should be assembled");
    }

    private static void checkDirectCacheKeyProtocol() {
        String raw = "Righteous Brandishing";
        String tooltip = TranslationCacheKeys.key("tooltip.item.direct", raw);
        String chat = TranslationCacheKeys.key("chat.message.direct", raw);
        if (tooltip.equals(chat)) {
            throw new AssertionError("surface-specific cache keys should not collide");
        }
        assertContains(tooltip, "direct:v16-lineunit:tooltip.item.direct:", "tooltip direct cache prefix");
        assertContains(chat, "direct:v16-lineunit:chat.message.direct:", "chat direct cache prefix");
        assertContains(tooltip, ":lang=", "tooltip direct cache language isolation");
        assertEquals("tooltip", TranslationCacheKeys.laneFromKey(tooltip), "tooltip lane");
        assertEquals("chat", TranslationCacheKeys.laneFromKey(chat), "chat lane");
        if (tooltip.contains(raw)) {
            throw new AssertionError("cache key should hash source text instead of storing raw tooltip text");
        }
    }

    private static void checkEditableTranslationCache() throws Exception {
        Path tempDir = Files.createTempDirectory("simpletranslate-cache-edit");
        TranslationCache cache = new TranslationCache(tempDir.resolve("translations.json"));
        cache.load();

        String source = "Primary Fire: \u53f3\u952e";
        String surface = "text_display.component.direct";
        String key = TranslationCacheKeys.key(surface, source, "", "slots", "styles");
        String document = "<st-doc v=\"direct-v4\" surface=\"text_display.component.direct\" role=\"text-display\" mode=\"free\">"
                + "<line id=\"0\" base=\"\"><g id=\"r0_0\">\u4e3b\u6b66\u5668\uff1a</g><g id=\"r0_1\">\u53f3\u952e</g></line>"
                + "</st-doc>";
        cache.put(key, document, source, TranslationCache.displayTextFromValue(document));
        TranslationCache.CacheViewEntry entry = cache.getEntry(key).orElseThrow();
        assertEquals(source, entry.sourceText(), "cache entry stores readable source text");
        assertEquals("\u4e3b\u6b66\u5668\uff1a\u53f3\u952e", entry.translationText(), "cache entry stores readable translated text");

        assertEquals(null, cache.updateEditableTranslationText(key, "\u4e3b\u6b66\u5668\uff1a\u9f20\u6807\u53f3\u952e").orElse(null),
                "editable cache update should accept same-line direct documents");
        String updated = cache.get(key).orElseThrow();
        assertContains(updated, "<g id=\"r0_0\">\u4e3b\u6b66\u5668\uff1a</g>", "first style slot remains anchored");
        assertContains(updated, "<g id=\"r0_1\">\u9f20\u6807\u53f3\u952e</g>", "second style slot receives edited remainder");
        assertEquals(Boolean.TRUE, cache.getEntry(key).orElseThrow().editedByPlayer(), "cache edit marks entry as player edited");

        String fixedKey = TranslationCacheKeys.key(surface, "Level 10", "", "fixed", "styles");
        String fixedDocument = "<st-doc v=\"direct-v4\" surface=\"text_display.component.direct\" role=\"text-display\" mode=\"free\">"
                + "<line id=\"0\" base=\"\"><run id=\"r0_0\" editable=\"false\">10</run><g id=\"r0_1\"> Health</g></line>"
                + "</st-doc>";
        cache.put(fixedKey, fixedDocument, "Level 10", TranslationCache.displayTextFromValue(fixedDocument));
        assertEquals(null, cache.updateEditableTranslationText(fixedKey, "10 \u751f\u547d\u503c").orElse(null),
                "editable cache update should preserve fixed runs");
        assertContains(cache.get(fixedKey).orElseThrow(), "<run id=\"r0_0\" editable=\"false\">10</run>",
                "fixed run remains unchanged after edit");
        assertContains(cache.get(fixedKey).orElseThrow(), "<g id=\"r0_1\">\u751f\u547d\u503c</g>",
                "editable run should not duplicate fixed text");

        String multiKey = TranslationCacheKeys.key("hud.history.caption_batch.direct", "a\nb", "", "line", "style");
        String multiDocument = "<st-doc v=\"direct-v4\" surface=\"hud.history.caption_batch.direct\" role=\"hud-caption-history-batch\" mode=\"line-text\">"
                + "<line id=\"0\" base=\"\">old one</line><line id=\"1\" base=\"\">old two</line></st-doc>";
        cache.put(multiKey, multiDocument, "a\nb", TranslationCache.displayTextFromValue(multiDocument));
        assertEquals("unsupported-format", cache.updateEditableTranslationText(multiKey, "\u53ea\u6709\u4e00\u884c").orElse(null),
                "multi-line cache edit must reject wrong line count");
        assertEquals(null, cache.updateEditableTranslationText(multiKey, "\u7b2c\u4e00\u884c\n\u7b2c\u4e8c\u884c").orElse(null),
                "multi-line cache edit accepts matching line count");
        assertEquals("\u7b2c\u4e00\u884c\n\u7b2c\u4e8c\u884c", cache.getEntry(multiKey).orElseThrow().translationText(),
                "multi-line cache edit updates readable translation text");
    }

    private static void checkDirectFormattedRoundTrip() {
        Component source = Component.empty()
                .append(Component.literal("Ring").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(" Amulet Slot").withStyle(ChatFormatting.GRAY));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false);
        assertContains(document, "<st-doc", "direct document root");
        assertContains(document, "mode=\"free\"", "free direct document mode");
        assertContains(document, "<g id=\"", "free direct document uses movable styled groups");
        if (document.contains("<g id=\"r0_0\" editable=") || document.contains("<g id=\"r0_0\" style=")) {
            throw new AssertionError("free <g> tags should only carry compact ids");
        }
        assertContains(document, "<g id=\"r0_0\">Ring</g>",
                "tooltip free mode should keep first styled run when styles differ");
        assertContains(document, "<g id=\"r0_1\"> Amulet Slot</g>",
                "tooltip free mode should keep second styled run when styles differ");
        assertContains(document, "base=\"color=", "line base style is serialized");

        String translated = document
                .replace(">Ring<", ">\u6212\u6307<")
                .replace("> Amulet Slot<", ">\u62a4\u7b26\u69fd<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false, translated);
        if (restored == null || restored.size() != 1) {
            throw new AssertionError("direct document should restore one component");
        }
        assertEquals("\u6212\u6307\u62a4\u7b26\u69fd", restored.get(0).getString(), "translated visible text");
        List<TextSegmentInfo> restoredSegments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(restored.get(0), restoredSegments, Style.EMPTY, true);
        if (restoredSegments.isEmpty() || restoredSegments.get(0).style.getColor() == null) {
            throw new AssertionError("restored component should keep styled text segments");
        }
        assertEquals(ChatFormatting.AQUA.getColor(), restoredSegments.get(0).style.getColor().getValue(),
                "color style survives direct restore");
        assertEquals(Boolean.TRUE, restoredSegments.get(0).style.isBold(),
                "bold style survives direct restore");
        assertEquals(ChatFormatting.GRAY.getColor(), restoredSegments.get(1).style.getColor().getValue(),
                "second color style survives direct restore");

        String missingGroup = translated.replaceFirst("(?s)<g id=\"r0_0\">.*?</g>", "");
        List<Component> restoredMissing = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false, missingGroup);
        if (restoredMissing != null) {
            throw new AssertionError("missing styled editable free group should reject instead of losing color mapping");
        }
    }

    private static void checkHudTitleGroupDirect() {
        Component title = Component.literal("Mission Started").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("Defend the South Gate").withStyle(ChatFormatting.YELLOW);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false);
        assertContains(document, "surface=\"hud.title_group.component.direct\"", "title/subtitle group surface");
        assertContains(document, "role=\"title-subtitle\"", "title/subtitle group role");

        String translated = document
                .replace(">Mission Started<", ">\u4efb\u52a1\u5f00\u59cb<")
                .replace(">Defend the South Gate<", ">\u9632\u5b88\u5357\u95e8<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false, translated);
        if (restored == null || restored.size() != 2) {
            throw new AssertionError("title/subtitle direct group should restore two components");
        }
        assertEquals("\u4efb\u52a1\u5f00\u59cb", restored.get(0).getString(), "title translated in grouped request");
        assertEquals("\u9632\u5b88\u5357\u95e8", restored.get(1).getString(), "subtitle translated in grouped request");
        assertEquals(ChatFormatting.GOLD.getColor(), firstStyle(restored.get(0)).getColor().getValue(), "title color");
        assertEquals(ChatFormatting.YELLOW.getColor(), firstStyle(restored.get(1)).getColor().getValue(), "subtitle color");

        Component noisySubtitle = Component.literal("don~ 6onUeonnaonlbone9ona3onsconh donY5ono6onueonr aonPbono9onw3oneconr don~")
                .withStyle(ChatFormatting.DARK_PURPLE);
        String noisyDocument = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(Component.literal("I LOVE YOU BABY\u2727").withStyle(ChatFormatting.GOLD), noisySubtitle),
                "hud.title_group.component.direct", "title-subtitle", false);
        String noisyTranslated = noisyDocument.replace(">I LOVE YOU BABY\u2727<", ">\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727<");
        List<Component> noisyRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(Component.literal("I LOVE YOU BABY\u2727").withStyle(ChatFormatting.GOLD), noisySubtitle),
                "hud.title_group.component.direct", "title-subtitle", false, noisyTranslated);
        if (noisyRestored == null || noisyRestored.size() != 2) {
            throw new AssertionError("legacy-formatted HUD noise should not reject the title/subtitle group");
        }
        assertEquals("\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727", noisyRestored.get(0).getString(), "natural HUD title still translates");
        assertEquals(noisySubtitle.getString(), noisyRestored.get(1).getString(), "legacy HUD noise remains unchanged");
    }

    private static void checkTextDisplayDirectStyleSlots() {
        Component source = Component.empty()
                .append(Component.literal("Primary Fire: ").withStyle(ChatFormatting.RED))
                .append(Component.literal("\u53f3\u952e").withStyle(ChatFormatting.YELLOW));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "text_display.component.direct", "text-display", false);
        assertContains(document, "surface=\"text_display.component.direct\"", "text display direct surface");
        assertContains(document, "role=\"text-display\"", "text display direct role");
        assertContains(document, "mode=\"free\"", "text display should use style-slot free mode");
        assertContains(document, "<g id=\"r0_0\">Primary Fire: </g>", "text display label slot");
        assertContains(document, "<g id=\"r0_1\">\u53f3\u952e</g>", "text display hotkey slot");

        String translated = document.replace(">Primary Fire: <", ">\u4e3b\u6b66\u5668\uff1a<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "text_display.component.direct", "text-display", false, translated);
        assertNotNull(restored, "text display slot document should restore");
        assertEquals("\u4e3b\u6b66\u5668\uff1a\u53f3\u952e",
                restored.get(0).getString(), "text display slot restore text");
        List<TextSegmentInfo> restoredSegments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(restored.get(0), restoredSegments, Style.EMPTY, true);
        assertEquals(ChatFormatting.RED.getColor(), restoredSegments.get(0).style.getColor().getValue(),
                "text display translated label keeps source color");
        assertEquals(ChatFormatting.YELLOW.getColor(), restoredSegments.get(1).style.getColor().getValue(),
                "text display hotkey keeps source color");

        Component logo = Component.empty()
                .append(Component.literal("S").withStyle(ChatFormatting.RED))
                .append(Component.literal("w").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("e").withStyle(ChatFormatting.RED))
                .append(Component.literal("r").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("v").withStyle(ChatFormatting.RED))
                .append(Component.literal("e").withStyle(ChatFormatting.GOLD));
        String logoDocument = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(logo), "text_display.component.direct", "text-display", false);
        assertContains(logoDocument, "mode=\"free\"", "stylized text display title uses style slots");
        assertContains(logoDocument, "<g id=\"r0_0\">S</g>", "stylized title first letter slot");
        List<Component> logoRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(logo), "text_display.component.direct", "text-display", false, logoDocument);
        assertNotNull(logoRestored, "stylized title should restore unchanged from slot document");
        assertEquals("Swerve", logoRestored.get(0).getString(), "stylized title text");
        List<TextSegmentInfo> logoSegments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(logoRestored.get(0), logoSegments, Style.EMPTY, true);
        assertEquals(ChatFormatting.RED.getColor(), logoSegments.get(0).style.getColor().getValue(),
                "stylized title first color");
        assertEquals(ChatFormatting.GOLD.getColor(), logoSegments.get(1).style.getColor().getValue(),
                "stylized title second color");

        Component simple = Component.literal("Dash \"F\" to deal damage!")
                .withStyle(ChatFormatting.GOLD);
        List<Component> plainFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(simple), "text_display.component.direct", "text-display", false,
                "\u51b2\u523a \"F\" \u9020\u6210\u4f24\u5bb3\uff01");
        assertNotNull(plainFallback, "single-style text display should recover plain text model responses");
        assertEquals("\u51b2\u523a \"F\" \u9020\u6210\u4f24\u5bb3\uff01",
                plainFallback.get(0).getString(), "text display single-style plain fallback text");
        assertEquals(ChatFormatting.GOLD.getColor(), firstStyle(plainFallback.get(0)).getColor().getValue(),
                "text display single-style fallback keeps base color");

        String missingSlot = translated.replaceFirst("(?s)<g id=\"r0_0\">.*?</g>", "");
        List<Component> missingRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "text_display.component.direct", "text-display", false, missingSlot);
        if (missingRestored != null) {
            throw new AssertionError("text display should reject missing styled slot instead of losing color mapping");
        }
    }

    private static void checkTranslatedTooltipMarker() {
        Component translated = Component.empty()
                .append(Component.literal("\u6280\u80fd\u6d88\u8017: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("80 Mana").withStyle(ChatFormatting.AQUA));
        List<Component> translatedList = List.of(translated);
        TooltipTranslationHelper.markTranslatedTooltip(translatedList);
        if (!TooltipTranslationHelper.isMarkedTranslatedTooltip(translatedList)) {
            throw new AssertionError("translated tooltip list should be marked");
        }
        if (!TooltipTranslationHelper.isMarkedTranslatedTooltip(translated)) {
            throw new AssertionError("translated tooltip component should be marked");
        }
        if (!TooltipTranslationHelper.isMarkedTranslatedTooltip(List.of(translated))) {
            throw new AssertionError("copied translated tooltip list should still be recognized by component identity");
        }
        TooltipTranslationHelper.clearPendingCache();
        if (TooltipTranslationHelper.isMarkedTranslatedTooltip(translatedList)) {
            throw new AssertionError("tooltip translated markers should clear with pending state");
        }
    }

    private static void checkDirectPlainContextPayload() {
        Component source = Component.empty()
                .append(Component.literal("[ Tip ]: Most Enemies cannot attack ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("[Fortified]").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" Objectives. Only ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Siege Enemies").withStyle(ChatFormatting.RED))
                .append(Component.literal(" can do so.").withStyle(ChatFormatting.GOLD));
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), "tooltip.item.direct", "tip-line", false,
                "Surrounding sign/book context should be visible to the model.");
        assertContains(request, "<st-context>", "direct request should include explicit context when provided");
        assertContains(request, "Surrounding sign/book context should be visible to the model.",
                "direct context content should be sent to the model");
        assertContains(request, "<st-plain-context", "direct request should include plain context");
        assertContains(request,
                "[ Tip ]: Most Enemies cannot attack [Fortified] Objectives. Only Siege Enemies can do so.",
                "plain context should expose complete cross-run sentence");
        assertContains(request, "<st-doc", "direct request should still include formatted document");
        if (request.indexOf("<st-plain-context") > request.indexOf("<st-doc")) {
            throw new AssertionError("plain context should precede the formatted document");
        }
    }

    private static void checkDirectFormattedKeepsLocalizedHotkeys() {
        List<Component> source = List.of(
                Component.literal("Armulet Amulet Slot").withStyle(ChatFormatting.AQUA),
                Component.literal("Place a Armulet Amulet here to receive its When Worn bonuses and activate its Ability.").withStyle(ChatFormatting.GRAY),
                Component.literal("\u6309 SHIFT + \u7a7a\u683c \u6253\u5f00\u9ad8\u7ea7\u7f16\u8f91\u5668").withStyle(ChatFormatting.GRAY),
                Component.literal("\u6309 Delete \u6765\u5220\u9664\uff01").withStyle(ChatFormatting.GRAY)
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, "tooltip.item.direct", "tooltip", false);
        String translated = document
                .replace(">Armulet Amulet Slot<", ">\u81c2\u73af\u62a4\u7b26\u69fd<")
                .replace(">Place a Armulet Amulet here to receive its When Worn bonuses and activate its Ability.<",
                        ">\u5728\u6b64\u653e\u7f6e\u81c2\u73af\u62a4\u7b26\uff0c\u4ee5\u83b7\u5f97\u5176\u7a7f\u6234\u65f6\u7684\u52a0\u6210\u5e76\u6fc0\u6d3b\u5176\u80fd\u529b\u3002<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, "tooltip.item.direct", "tooltip", false, translated);
        if (restored == null || restored.size() != 4) {
            throw new AssertionError("localized NBTEditor hotkey lines with SHIFT/Delete should not reject the whole tooltip");
        }
        assertContains(restored.get(2).getString(), "SHIFT", "SHIFT key name should be preserved");
        assertContains(restored.get(3).getString(), "Delete", "Delete key name should be preserved");
    }

    private static void checkDirectStatusTermConsistency() {
        Component first = Component.empty()
                .append(Component.literal("\u226b [Animate Heroes]").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" \u2192 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\u2714 Loaded").withStyle(ChatFormatting.GREEN));
        Component second = Component.empty()
                .append(Component.literal("\u226b [Siege]").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" \u2192 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\u2714 Loaded").withStyle(ChatFormatting.GREEN));

        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(first, second), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, "<st-glossary>", "status glossary section");
        assertContains(request, "source=\"Loaded\" target=\"\u5df2\u52a0\u8f7d\"", "loaded glossary term");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(first, second), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        String translated = document
                .replace("\u226b [Animate Heroes]", "\u226b [\u52a8\u753b\u82f1\u96c4]")
                .replace("\u226b [Siege]", "\u226b [\u653b\u57ce]")
                .replaceFirst("\u2714 Loaded", "\u2714 \u5df2\u52a0\u8f7d")
                .replaceFirst("\u2714 Loaded", "\u2714 \u5df2\u88c5\u8f7d");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(first, second), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, translated);
        if (restored == null || restored.size() != 2) {
            throw new AssertionError("status term restore should succeed");
        }
        assertContains(restored.get(0).getString(), "\u2714 \u5df2\u52a0\u8f7d", "first loaded status");
        assertContains(restored.get(1).getString(), "\u2714 \u5df2\u52a0\u8f7d", "second loaded status normalized");
        if (restored.get(1).getString().contains("\u5df2\u88c5\u8f7d")) {
            throw new AssertionError("Loaded must not normalize to \u5df2\u88c5\u8f7d");
        }
        assertEquals("\u5df2\u52a0\u8f7d", DirectStatusTerms.fixedTermsForTest().get("Loaded"),
                "loaded deterministic term");
    }

    private static void checkDirectAcceptsResidualSourceLanguage() {
        Component source = Component.literal("Plain English Sentence")
                .withStyle(Style.EMPTY.withItalic(true));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false);
        List<Component> unchanged = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false, document);
        if (unchanged == null || unchanged.size() != 1) {
            throw new AssertionError("unchanged LLM output should be accepted when document structure is valid");
        }
        assertEquals("Plain English Sentence", unchanged.get(0).getString(),
                "unchanged English actionbar text remains a valid LLM result");

        String translated = document.replace(">Plain English Sentence<", ">Plain English Sentence bonus<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false, translated);
        if (restored == null || restored.size() != 1) {
            throw new AssertionError("LLM output with residual English should restore");
        }
        assertEquals("Plain English Sentence bonus", restored.get(0).getString(),
                "residual English actionbar text");
    }

    private static void checkKoreanPlainFallbackAcceptsLlmResult() {
        String korean = "\uc81c\uac00 \ub2f9\uc2e0\uc744 \uc704\ud574 \ucc3d\uc791\ud55c \uc2dc\uc785\ub2c8\ub2e4. \uc790\uc5f0\uacfc \uc774\ubcc4, \uc704\ub85c\ub97c \uc8fc\uc81c\ub85c \uc9e7\uac8c \uc368\ubcf4\uc558\uc2b5\ub2c8\ub2e4.";
        Component source = Component.literal(korean).withStyle(ChatFormatting.GRAY);
        if (!TranslationTextDetector.containsTranslatableText(korean)) {
            throw new AssertionError("Korean text should be detected as translatable");
        }
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, korean, "Korean direct request contains original text");

        List<Component> plainFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false,
                "\u8fd9\u662f\u4e00\u9996\u4ee5\u81ea\u7136\u3001\u79bb\u522b\u548c\u6170\u85c9\u4e3a\u4e3b\u9898\u7684\u77ed\u8bd7\u3002");
        if (plainFallback == null || plainFallback.size() != 1) {
            throw new AssertionError("single-run Korean chat should recover from a plain translated response");
        }
        assertEquals("\u8fd9\u662f\u4e00\u9996\u4ee5\u81ea\u7136\u3001\u79bb\u522b\u548c\u6170\u85c9\u4e3a\u4e3b\u9898\u7684\u77ed\u8bd7\u3002",
                plainFallback.get(0).getString(), "plain Korean fallback text");
        assertEquals(ChatFormatting.GRAY.getColor(), firstStyle(plainFallback.get(0)).getColor().getValue(),
                "plain Korean fallback keeps original style");

        List<Component> unchangedPlain = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, korean);
        if (unchangedPlain == null || unchangedPlain.size() != 1) {
            throw new AssertionError("plain fallback should accept unchanged Korean LLM output");
        }
        assertEquals(korean, unchangedPlain.get(0).getString(), "unchanged Korean plain fallback text");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        List<Component> unchangedStructured = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, document);
        if (unchangedStructured == null || unchangedStructured.size() != 1) {
            throw new AssertionError("structured direct restore should accept unchanged Korean LLM output");
        }
        assertEquals(korean, unchangedStructured.get(0).getString(), "unchanged Korean structured text");
    }

    private static void checkChatSystemMessagePrefixDetection() {
        Component system = Component.literal("Abbreviations (Hover on their names): \n[Root]\nHover on skill names to view information.");
        List<TextSegmentInfo> segments = new ArrayList<>();
        ChatTranslationRuntime.extractSegments(system, segments);
        String candidate = ChatTranslationRuntime.getTranslatableChatSegmentText(segments, 0, system.getString());
        assertEquals(system.getString(), candidate, "system chat colon heading should not be skipped as player prefix");

        Component player = Component.literal("<JekNJok> Hello world");
        segments.clear();
        ChatTranslationRuntime.extractSegments(player, segments);
        String playerBody = ChatTranslationRuntime.getTranslatableChatSegmentText(segments, 0, player.getString());
        assertEquals("Hello world", playerBody, "angle-bracket player prefix should still be protected");

        String key = TranslationCacheKeys.key(ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, system.getString());
        assertContains(key, "direct:v16-lineunit:chat.system.direct:", "system chat uses isolated direct cache surface");
        assertEquals("chat", TranslationCacheKeys.laneFromKey(key), "system chat direct lane");
    }

    private static void checkSignModeEpoch() {
        long before = SignTranslationHelper.signModeEpochForTest();
        SignTranslationHelper.bumpSignModeEpochForTest();
        long afterManual = SignTranslationHelper.signModeEpochForTest();
        if (afterManual <= before) {
            throw new AssertionError("sign mode epoch should advance when switching AUTO -> MANUAL");
        }
        SignTranslationHelper.bumpSignModeEpochForTest();
        long afterAuto = SignTranslationHelper.signModeEpochForTest();
        if (afterAuto <= afterManual) {
            throw new AssertionError("sign mode epoch should advance when switching MANUAL -> AUTO");
        }
    }

    private static void checkItemTooltipContextPayload() {
        List<Component> source = List.of(
                Component.literal("Acknowledgement Of Excellence").withStyle(ChatFormatting.GREEN),
                Component.literal("A certificate stating one's achievement, acknowledged by a few organizations around the world.")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(true)),
                Component.literal("Used for Special Trades.").withStyle(ChatFormatting.GOLD),
                Component.literal("Press SHIFT for details.").withStyle(ChatFormatting.GRAY)
        );
        String context = TooltipTranslationHelper.buildItemTooltipContext(source);
        assertContains(context, "Item tooltip semantic block", "item tooltip context header");
        assertContains(context, "line 0 [title]: Acknowledgement Of Excellence", "item title line role");
        assertContains(context, "line 1 [lore]: A certificate stating one's achievement", "item lore line role");
        assertContains(context, "line 2 [usage]: Used for Special Trades.", "item usage line role");
        assertContains(context, "line 3 [hotkey]: Press SHIFT for details.", "item hotkey line role");

        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                source,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE,
                false,
                context);
        assertContains(request, "<st-context>", "item tooltip request should include semantic block context");
        assertContains(request, "line 1 [lore]: A certificate stating one's achievement",
                "item tooltip request should expose lore context");
        assertContains(request, "surface=\"tooltip.item_context.direct\"",
                "item tooltip request must use the context surface");
        assertContains(request, "role=\"tooltip-block-context\"",
                "item tooltip request must use the context role");
        assertContains(request, "mode=\"line-text\"",
                "item tooltip request must use line-text mode instead of styled group ids");
        if (request.contains("<g id=\"")) {
            throw new AssertionError("item tooltip line-text request must not expose low-level styled group ids");
        }
        if (request.contains("surface=\"tooltip.item.direct\"")) {
            throw new AssertionError("item tooltip request must not use the legacy tooltip.item.direct surface");
        }

        String sourceText = String.join("\n",
                source.get(0).getString(), source.get(1).getString(), source.get(2).getString(), source.get(3).getString());
        String legacyKey = TranslationCacheKeys.key("tooltip.item.direct", sourceText, context, "layout", "style");
        String contextKey = TranslationCacheKeys.key(
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE, sourceText, context, "layout", "style");
        if (legacyKey.equals(contextKey)) {
            throw new AssertionError("item context tooltip cache key must not collide with legacy tooltip.item.direct");
        }
        assertContains(contextKey, "direct:v16-lineunit:tooltip.item_context.direct:",
                "item context cache key should include the new surface");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        String translated = document
                .replace(">Acknowledgement Of Excellence<", ">\u5353\u8d8a\u8bc1\u660e<")
                .replace(">A certificate stating one's achievement, acknowledged by a few organizations around the world.<",
                        ">\u4e00\u4efd\u8bc1\u660e\u6301\u6709\u8005\u6210\u5c31\u5e76\u83b7\u5f97\u4e16\u754c\u5404\u5730\u5c11\u6570\u7ec4\u7ec7\u8ba4\u53ef\u7684\u8bc1\u4e66\u3002<")
                .replace(">Used for Special Trades.<", ">\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002<")
                .replace(">Press SHIFT for details.<", ">\u6309 SHIFT \u67e5\u770b\u8be6\u60c5\u3002<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        if (restored == null || restored.size() != source.size()) {
            throw new AssertionError("item context tooltip restore must preserve the original line count");
        }
        assertEquals("\u5353\u8d8a\u8bc1\u660e", restored.get(0).getString(), "item context title restore");
        assertEquals("\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002", restored.get(2).getString(), "item context usage restore");
        assertContains(restored.get(3).getString(), "SHIFT", "item context hotkey should preserve key token");
    }

    private static void checkSharedSignComponentSerialization() {
        Component[] source = new Component[] {
                Component.literal("Start Game").withStyle(ChatFormatting.WHITE),
                Component.literal("From Here").withStyle(ChatFormatting.WHITE),
                Component.literal("[Click to TP]").withStyle(ChatFormatting.GREEN),
                Component.empty()
        };
        String serialized = SignTranslationHelper.serializeSharedSignComponentsForTest(source);
        if (serialized == null || !serialized.startsWith("sign-components-v1")) {
            throw new AssertionError("shared sign component cache should serialize four styled components");
        }
        Component[] restored = SignTranslationHelper.deserializeSharedSignComponentsForTest(serialized);
        if (restored == null || restored.length != 4) {
            throw new AssertionError("shared sign component cache should restore four components");
        }
        assertEquals("[Click to TP]", restored[2].getString(), "shared sign cache preserves click line text");
        assertEquals(ChatFormatting.GREEN.getColor(), restored[2].getStyle().getColor().getValue(),
                "shared sign cache preserves line color/style");
    }

    private static void checkUnicodeTextDetection() {
        String fullwidth = "\uff3b\uff41\uff56\uff33\uff39\uff33\uff3d\uff0f\uff0f\uff21\uff34\uff34\uff25\uff2d\uff30\uff34\uff29\uff2e\uff27 \uff2e\uff25\uff34\uff37\uff2f\uff32\uff2b \uff32\uff25\uff23\uff2f\uff2e\uff2e\uff25\uff23\uff34\uff29\uff2f\uff2e\uff0e\uff0e\uff0e\uff0f\uff0f";
        if (!TranslationTextDetector.containsTranslatableText(fullwidth)) {
            throw new AssertionError("fullwidth Latin status text should be detected as translatable");
        }
        Component source = Component.literal(fullwidth).withStyle(ChatFormatting.WHITE);
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, "<g id=\"r0_0\"", "fullwidth run should be emitted as an editable free-layout group");
        assertContains(request, "<st-normalized-plain-context>", "fullwidth request should include normalized context");
        assertContains(request, "ATTEMPTING NETWORK RECONNECTION", "normalized context should expose readable Latin text");
        if (TranslationTextDetector.containsTranslatableText("\u7eaf\u4e2d\u6587\u5df2\u7ecf\u7ffb\u8bd1")) {
            throw new AssertionError("pure Simplified Chinese should not be retranslated by default");
        }
        if (!TranslationTextDetector.containsTranslatableText("\u7eaf\u4e2d\u6587 with English")) {
            throw new AssertionError("mixed Chinese plus foreign text should still be translated");
        }
        for (String shortText : List.of("A", "OK", "HP", "Mana", "Slot Items")) {
            if (!TranslationTextDetector.containsTranslatableText(shortText)) {
                throw new AssertionError("short natural text should be sent to LLM: " + shortText);
            }
        }
        Component styledPlayerName = Component.literal("JekNJok").withStyle(ChatFormatting.GOLD);
        if (!TranslationTextDetector.containsTranslatableText(styledPlayerName.getString())) {
            throw new AssertionError("styled/player-like names should be eligible for LLM translation");
        }
        for (String foreign : List.of("\u3072\u3089\u304c\u306a", "\ud55c\uad6d\uc5b4", "\u0440\u0443\u0441\u0441\u043a\u0438\u0439", "\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629")) {
            if (!TranslationTextDetector.containsTranslatableText(foreign)) {
                throw new AssertionError("non-target language text should be sent to LLM: " + foreign);
            }
        }
        for (String nonText : List.of("", "   ", "12345", "!!!", "\u00a7a\u00a7l")) {
            if (TranslationTextDetector.containsTranslatableText(nonText)) {
                throw new AssertionError("empty, numeric, punctuation, and formatting-only text should not translate: " + nonText);
            }
        }
        String defaultPair = TranslationTextDetector.languagePairKey();
        if (!defaultPair.contains("auto") || !defaultPair.contains("zh_cn")) {
            throw new AssertionError("default language pair should be auto->zh_cn but was " + defaultPair);
        }
        String originalStylePrompt = ModConfig.HUD_STYLE_PROMPT.get();
        try {
            ModConfig.HUD_STYLE_PROMPT.set("short and poetic");
            String styledPair = TranslationTextDetector.languagePairKey();
            if (defaultPair.equals(styledPair) || !styledPair.contains("short and poetic")) {
                throw new AssertionError("style prompt should change translation cache language/style key");
            }
        } finally {
            ModConfig.HUD_STYLE_PROMPT.set(originalStylePrompt);
        }
    }

    private static void checkModelNormalization() {
        assertEquals("deepseek-v4-flash", ModConfig.normalizeDeepSeekModelId("flash"), "flash alias");
        assertEquals("deepseek-v4-flash", ModConfig.normalizeDeepSeekModelId("deepseek-v4-flsh"), "flsh typo alias");
        assertEquals("deepseek-v4-pro", ModConfig.normalizeDeepSeekModelId("pro"), "pro alias");
        assertEquals("deepseek-v4-flash", ModConfig.normalizeDeepSeekModelId("unknown-model"), "unknown model ids should fall back to DeepSeek V4 Flash");
    }

    private static void checkApiFormatAndParallelConfig() {
        ModConfig.API_FORMAT.set(ModConfig.ApiFormat.OPENAI_CHAT_COMPAT);
        assertEquals("gpt-4o-mini", ModConfig.normalizeModelId(""), "OpenAI-compatible blank model should use format default");
        assertEquals("doubao-lite-4k-character-240828", ModConfig.normalizeModelId("doubao-lite-4k-character-240828"),
                "OpenAI-compatible model ids should not be normalized to DeepSeek presets");

        ModConfig.API_MAX_PARALLEL_REQUESTS.set(99);
        assertEquals(8, ModConfig.API_MAX_PARALLEL_REQUESTS.get(), "max parallel requests should clamp high values");
        ModConfig.API_MAX_PARALLEL_REQUESTS.set(0);
        assertEquals(1, ModConfig.API_MAX_PARALLEL_REQUESTS.get(), "max parallel requests should clamp low values");

        ModConfig.API_MAX_PARALLEL_REQUESTS.set(5);
        ModConfig.API_FORMAT.set(ModConfig.ApiFormat.DEEPSEEK_CHAT);
    }

    private static void checkConfigMigrationAndUnknownKeyPreservation() throws Exception {
        Path tempDir = Files.createTempDirectory("simpletranslate-config-check");
        Path configFile = tempDir.resolve("simple_translate-client.json");
        Files.writeString(configFile, """
                {
                  "api.apiKey": "test-key",
                  "content.bookButtonOffsetX": 12,
                  "content.bookButtonOffsetY": 34,
                  "future.option": "keep-me"
                }
                """);

        ModConfig.init(tempDir);
        assertEquals(12, ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_X.get(), "legacy book x offset");
        assertEquals(34, ModConfig.CONTENT_BOOK_BOOKMARK_OFFSET_Y.get(), "legacy book y offset");

        ModConfig.DEEPSEEK_THINKING_ENABLED.set(true);
        ModConfig.save();

        String saved = Files.readString(configFile);
        assertContains(saved, "\"future.option\"", "unknown key should be preserved");
        assertContains(saved, "\"content.bookBookmarkOffsetX\": 12", "book x offset should be migrated");
        assertContains(saved, "\"content.bookBookmarkOffsetY\": 34", "book y offset should be migrated");
        assertContains(saved, "\"api.thinkingEnabled\": true", "thinking setting should persist");
    }

    private static void assertContains(String text, String expected, String label) {
        if (text == null || !text.contains(expected)) {
            throw new AssertionError(label + ": expected to find <" + expected + "> in <" + text + ">");
        }
    }

    private static Style firstStyle(Component component) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        if (segments.isEmpty()) {
            throw new AssertionError("component has no text segments: " + component);
        }
        return segments.get(0).style;
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
    private static void assertNotNull(Object value, String label) {
        if (value == null) {
            throw new AssertionError(label + ": expected non-null value");
        }
    }
}
'@

    [System.IO.File]::WriteAllText($sourceFile, $javaSource, [System.Text.UTF8Encoding]::new($false))

    $classes = Join-Path $ProjectDir "build\classes\java\main"
    $resources = Join-Path $ProjectDir "build\resources\main"
    $gradleProperties = Join-Path $ProjectDir "gradle.properties"
    $minecraftVersion = "1.20.1"
    if (Test-Path $gradleProperties) {
        $versionLine = Get-Content -Path $gradleProperties |
            Where-Object { $_ -match '^minecraft_version=' } |
            Select-Object -First 1
        if ($versionLine) {
            $minecraftVersion = ($versionLine -split '=', 2)[1].Trim()
        }
    }
    function Find-NeoGradleMappedJar([string]$Version) {
        $neoFormRoot = Join-Path $ProjectDir "build\neoForm"
        if (-not (Test-Path $neoFormRoot)) {
            return $null
        }
        $classesDir = Get-ChildItem -Path $neoFormRoot -Recurse -Directory -Filter "classes" |
            Where-Object { $_.FullName -match [regex]::Escape("neoFormJoined$Version-") -and $_.FullName -match "\\steps\\recompile\\classes$" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($classesDir) {
            return $classesDir
        }
        foreach ($step in @("packRecomp", "applyOfficialMappings", "patchUserDev", "rename", "applyForgesAccessTransformer")) {
            $jar = Get-ChildItem -Path $neoFormRoot -Recurse -Filter "output.jar" |
                Where-Object { $_.FullName -match [regex]::Escape("neoFormJoined$Version-") -and $_.FullName -match "\\steps\\$step\\output\.jar$" } |
                Sort-Object Length -Descending |
                Select-Object -First 1
            if ($jar) {
                return $jar
            }
        }
        return $null
    }

    function Get-LocalClientRoot([string]$Version) {
        $testRootName = -join ([char[]](0x6A21, 0x7EC4, 0x6D4B, 0x8BD5))
        return Join-Path (Join-Path (Join-Path "D:\mc" $testRootName) "neoforge\$Version") ""
    }

    function Find-LocalClientVersionJar([string]$Version) {
        $versionsRoot = Join-Path (Get-LocalClientRoot $Version) "versions"
        if (-not (Test-Path $versionsRoot)) {
            return $null
        }
        return Get-ChildItem -Path $versionsRoot -Recurse -Filter "$Version*.jar" |
            Where-Object { $_.FullName -notmatch "\\mods\\" -and $_.Name -notmatch "sources|javadoc" } |
            Sort-Object Length -Descending |
            Select-Object -First 1
    }

    $minecraftJar = Find-NeoGradleMappedJar $minecraftVersion
    if (-not $minecraftJar) {
        throw "Could not find the NeoGradle remapped Minecraft $minecraftVersion jar"
    }
    $clientVersionJar = Find-LocalClientVersionJar $minecraftVersion
    $clientExtraJar = Get-ChildItem -Path (Join-Path $ProjectDir "build\jars\extra") -Recurse -Filter "client-extra.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    $dependencyJars = @()
    $localLibrariesRoot = Join-Path (Get-LocalClientRoot $minecraftVersion) "libraries"
    if (Test-Path $localLibrariesRoot) {
        $dependencyJars += Get-ChildItem -Path $localLibrariesRoot -Recurse -Filter "*.jar" |
            Where-Object {
                $_.Name -notmatch "sources|javadoc" -and
                $_.FullName -notmatch "\\com\\google\\code\\gson\\gson\\" -and
                $_.FullName -notmatch "\\guava\\20\.0\\" -and
                $_.FullName -notmatch "\\gson\\2\.8\.9\\" -and
                $_.FullName -notmatch "\\logging\\1\.1\.1\\" -and
                $_.FullName -notmatch "\\net\\neoforged\\installertools\\" -and
                $_.FullName -notmatch "\\org\\ow2\\asm\\asm\\9\.6\\"
            } |
            ForEach-Object { $_.FullName }
    }

    $classpathEntries = @($classes, $resources, $gsonJar.FullName, $minecraftJar.FullName)
    if ($clientVersionJar) { $classpathEntries += $clientVersionJar.FullName }
    if ($clientExtraJar) { $classpathEntries += $clientExtraJar.FullName }
    $classpathEntries += $dependencyJars
    $classpath = ($classpathEntries |
            Where-Object { $_ -and (Test-Path $_) } |
            Select-Object -Unique) -join ";"

    & javac -encoding UTF-8 -cp $classpath -d $tempDir $sourceFile
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    & java -cp "$classpath;$tempDir" SimpleTranslateLogicChecks
    if ($LASTEXITCODE -ne 0) {
        throw "logic checks failed with exit code $LASTEXITCODE"
    }

    function Assert-FileContains {
        param([string]$Path, [string]$Needle, [string]$Label)
        $content = Get-Content -Raw -Path $Path
        if (-not $content.Contains($Needle)) {
            throw "${Label}: expected $Path to contain <$Needle>"
        }
    }

    function Assert-FileNotContains {
        param([string]$Path, [string]$Needle, [string]$Label)
        $content = Get-Content -Raw -Path $Path
        if ($content.Contains($Needle)) {
            throw "${Label}: expected $Path not to contain <$Needle>"
        }
    }

    function Assert-FileMissing {
        param([string]$Path, [string]$Label)
        if (Test-Path -LiteralPath $Path) {
            throw "${Label}: expected $Path to be absent"
        }
    }

    $src = Join-Path $ProjectDir "src\main\java\com\yourname\simpletranslate"
    $managerFile = Join-Path $src "translation\TranslationManager.java"
    $deepSeekFile = Join-Path $src "translation\DeepSeekTranslationService.java"
    $serviceFile = Join-Path $src "translation\TranslationService.java"
    $directFile = Join-Path $src "util\DirectFormattedTranslationPipeline.java"
    $modFile = Join-Path $src "SimpleTranslateMod.java"
    $cacheFile = Join-Path $src "cache\TranslationCache.java"
    $cacheManagerScreenFile = Join-Path $src "gui\CacheManagerScreen.java"
    $cacheEditScreenFile = Join-Path $src "gui\CacheEditScreen.java"
    $resourceDir = Join-Path $ProjectDir "src\main\resources"
    $enLangFile = Join-Path $resourceDir "assets\simple_translate\lang\en_us.json"
    $zhLangFile = Join-Path $resourceDir "assets\simple_translate\lang\zh_cn.json"

    Assert-FileMissing (Join-Path $src "util\ScoreboardDisplayEntry.java") "low-risk dead scoreboard display entry must not return"
    Assert-FileMissing (Join-Path $src "util\TextSegment.java") "low-risk dead TextSegment must not return"
    Assert-FileMissing (Join-Path $src "util\ChatTranslationHelper.java") "old chat helper must not return"
    Assert-FileMissing (Join-Path $src "util\ChatButtonHelper.java") "old chat button helper must not return"
    Assert-FileMissing (Join-Path $resourceDir "simple_translate.png") "unused root mod icon resource must not return"
    Assert-FileNotContains $enLangFile '"chat.simple_translate.translate":' "old chat button translate lang key must not return"
    Assert-FileNotContains $enLangFile '"chat.simple_translate.original":' "old chat button original lang key must not return"
    Assert-FileNotContains $enLangFile '"chat.simple_translate.translating":' "old chat button translating lang key must not return"
    Assert-FileNotContains $zhLangFile '"chat.simple_translate.translate":' "old chat button translate lang key must not return"
    Assert-FileNotContains $zhLangFile '"chat.simple_translate.original":' "old chat button original lang key must not return"
    Assert-FileNotContains $zhLangFile '"chat.simple_translate.translating":' "old chat button translating lang key must not return"

    Assert-FileContains $modFile 'resetTranslationRuntime("world-switch:' "world switch must reset runtime pending/cooldown state"
    Assert-FileContains $modFile 'resetTranslationRuntime("disconnect")' "disconnect must reset runtime pending/cooldown state"
    Assert-FileContains $modFile "getRuntimeRevision()" "mod must expose runtime revision for stale async response checks"
    Assert-FileContains $modFile "getWorldPath(LevelResource.ROOT)" "local world id must include the save path, not only the display name"
    Assert-FileContains $modFile "migrateLegacyWorldDataIfNeeded(worldId)" "path-based world ids should migrate legacy display-name cache data"
    Assert-FileContains $modFile "onTranslationCacheEdited()" "cache edits must expose a runtime refresh hook"
    Assert-FileContains $cacheFile "updateEditableTranslationText" "translation cache must support editable in-game cache updates"
    Assert-FileContains $cacheFile "sourceText" "translation cache entries must store readable source text"
    Assert-FileContains $cacheFile "translationText" "translation cache entries must store readable translated text"
    Assert-FileContains $cacheFile "removeFixedTextSegments" "cache editing must preserve fixed direct-format runs"
    Assert-FileContains $cacheManagerScreenFile "new CacheEditScreen" "cache manager must open the cache edit screen"
    Assert-FileContains $cacheManagerScreenFile "screen.simple_translate.cache.edit.tooltip" "cache edit action must have delayed tooltip text"
    Assert-FileContains $cacheManagerScreenFile "new EditBox" "cache manager must expose a search field"
    Assert-FileContains $cacheManagerScreenFile "screen.simple_translate.cache.search.tooltip" "cache search field must have delayed tooltip text"
    Assert-FileContains $cacheManagerScreenFile "ROW_HEIGHT = 24" "cache manager list rows must use a compact fixed row height"
    Assert-FileContains $cacheManagerScreenFile "bottom - top" "cache manager list constructor must pass viewport height instead of treating bottom as row height"
    Assert-FileContains $cacheManagerScreenFile "matches(String normalizedQuery)" "cache manager entries must support filtering"
    Assert-FileContains $cacheManagerScreenFile "fitText" "cache manager row text must be clipped to column width"
    Assert-FileContains $cacheEditScreenFile "MultiLineEditBox" "cache edit screen must use a multiline readable translation input"
    Assert-FileContains $cacheEditScreenFile "updateEditableTranslationText" "cache edit screen must save through the safe cache rewrite API"
    Assert-FileContains $cacheEditScreenFile "SimpleTranslateMod.onTranslationCacheEdited()" "cache edit save must refresh active runtime state"
    Assert-FileContains $cacheEditScreenFile "screen.simple_translate.cache.edit.input.tooltip" "cache edit input must have delayed tooltip text"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.edit.tooltip" "Chinese lang must include cache edit tooltip"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.edit.tooltip" "English lang must include cache edit tooltip"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.search.tooltip" "Chinese lang must include cache search tooltip"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.search.tooltip" "English lang must include cache search tooltip"
    Assert-FileContains $serviceFile "translateFormattedDocument" "formatted document service entry"
    Assert-FileContains $managerFile "translateFormattedDocument(String document)" "formatted document manager entry"
    Assert-FileContains $deepSeekFile "RequestMode.DIRECT_FORMATTED" "DeepSeek direct formatted request mode"
    Assert-FileContains $deepSeekFile 'mode=\"free\"' "direct formatted prompt supports movable free-layout groups"
    Assert-FileContains $deepSeekFile "you may move <g id" "direct formatted prompt allows style tokens to move"
    Assert-FileContains $deepSeekFile "<st-plain-context>" "direct prompt uses complete plain text context"
    Assert-FileContains $deepSeekFile "<st-glossary>" "direct prompt supports deterministic status glossary"
    Assert-FileContains $directFile '<st-doc v=\"direct-v4\"' "direct pipeline serializes reversible document root"
    Assert-FileContains $directFile '<g id=\"' "direct pipeline serializes free layout as movable style groups"
    Assert-FileContains $directFile '<st-plain-context surface=\"' "direct pipeline sends complete plain context before formatted document"
    Assert-FileNotContains $directFile '"text_display.component.direct".equals(surface)' "text display must not use line-text mode because it loses style-slot colors"
    Assert-FileContains $directFile 'surface.startsWith("text_display.")' "text display direct payload must recover plain text model responses"
    Assert-FileContains $directFile '|| (surface != null && surface.startsWith("text_display."))' "text display must require editable style slots to avoid color loss"
    Assert-FileContains $deepSeekFile 'surface=\"text_display.component.direct\"' "text display prompt must preserve style slots"
    Assert-FileContains $directFile "DirectStatusTerms.glossarySection" "direct pipeline emits status glossary"
    Assert-FileContains $directFile "DirectStatusTerms.apply" "direct pipeline normalizes deterministic status terms"
    Assert-FileContains $directFile "reason=validation-failed" "direct pipeline logs validation rejection reason"
    Assert-FileContains $directFile "sourceSummary()" "direct pipeline logs source summary for rejected translations"
    Assert-FileContains $directFile "requestPayload()" "direct pipeline sends context payload to the model"
    Assert-FileContains $directFile "restore(String translatedDocument)" "direct pipeline validates and restores translated documents"
    Assert-FileContains $directFile "TranslationCacheKeys.key(surface, sourceText, context, layoutSignature, styleSignature)" "direct cache key includes surface/layout/style/source"
    Assert-FileContains $directFile "SimpleTranslateMod.getRuntimeRevision()" "direct async requests must capture world/runtime revision"
    Assert-FileContains $directFile "SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)" "direct async responses must ignore stale world/runtime revisions"
    Assert-FileContains (Join-Path $src "util\TranslationCacheKeys.java") 'PROTOCOL = "direct:v16-lineunit"' "direct cache protocol namespace"
    Assert-FileContains (Join-Path $src "util\TranslationTextDetector.java") "customStylePromptKey()" "cache language key must include custom style prompt guidance"
    Assert-FileContains (Join-Path $src "gui\LanguageSettingsScreen.java") "SimpleTranslateMod.onStyleSettingsChanged()" "custom style prompt changes must reset runtime state"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "TranslationRequestQueue.submit" "normal DeepSeek translation requests must use the global queue"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "Executors.newFixedThreadPool" "translation queue must support multiple surface lanes"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "laneForSurface" "translation queue must isolate work by surface lane"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "hud_title" "title/subtitle HUD translations must use a dedicated lane"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "hud_actionbar" "actionbar HUD translations must use a dedicated lane"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "TITLE_URGENT(480, true)" "title/subtitle HUD translations must use an urgent protected priority"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "ACTIONBAR_URGENT(360, true)" "actionbar HUD translations must use an isolated urgent priority"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "Priority.TITLE_URGENT" "formatted title requests must be scheduled with urgent title priority"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "Priority.ACTIONBAR_URGENT" "formatted actionbar requests must be scheduled with actionbar priority"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "globalInFlight < maxParallelRequests()" "translation queue must enforce the configured global parallel limit"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "TASKS_BY_LANE_KEY" "translation queue must coalesce duplicate work inside a lane"
    Assert-FileContains (Join-Path $src "translation\TranslationRequestQueue.java") "cancelSurfacePrefix" "translation queue must cancel queued work by surface prefix"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "api.maxParallelRequests" "config must expose max parallel request count"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "enum ApiFormat" "config must expose selectable API formats"
    Assert-FileContains $deepSeekFile "EndpointKind.RESPONSES" "request layer must support OpenAI Responses format"
    Assert-FileContains $deepSeekFile "EndpointKind.ANTHROPIC_MESSAGES" "request layer must support Anthropic Messages format"
    Assert-FileContains $deepSeekFile "EndpointKind.GEMINI_GENERATE_CONTENT" "request layer must support Gemini generateContent format"
    Assert-FileContains (Join-Path $src "SimpleTranslateMod.java") "TranslationRequestQueue.clear()" "runtime reset must clear queued translation work"
    Assert-FileNotContains $directFile "containsUntranslatedNaturalEnglish" "direct pipeline must not reject LLM output for residual English"
    Assert-FileNotContains $directFile "english-leftover line=" "direct pipeline must not reject residual source language in editable runs"
    Assert-FileNotContains $directFile "containsUntranslatedNaturalText" "old direct pipeline residual-source rejection helper must not return"
    Assert-FileContains $managerFile "containsBlacklistedEntry(translated)" "translated output blacklist must still be enforced"
    Assert-FileNotContains (Join-Path $src "mixin\BossHealthOverlayMixin.java") "SemanticBlockTranslationHelper" "bossbar must not use the old semantic compatibility path"
    Assert-FileNotContains (Join-Path $src "mixin\TextDisplayMixin.java") "SemanticBlockTranslationHelper" "text display must not use the old semantic compatibility path"
    Assert-FileNotContains (Join-Path $src "mixin\ChatComponentMixin.java") "SemanticBlockTranslationHelper" "chat must not keep an unused semantic helper import"
    $signFile = Join-Path $src "util\SignTranslationHelper.java"
    $signDirectFile = Join-Path $src "util\SignDirectDocument.java"
    $signSelectionFile = Join-Path $src "util\SignContextSelectionManager.java"
    $signSelectionHighlighterFile = Join-Path $src "util\SignSelectionHighlighter.java"
    Assert-FileContains $signSelectionFile "SELECTIONS.remove(key);" "manual sign submitted selections must be cleared after completion or failure"
    Assert-FileNotContains $signSelectionFile "FAILED" "manual sign failed selections must not remain stuck in renderable selection state"
    Assert-FileNotContains $signSelectionHighlighterFile "case FAILED" "manual sign failed selection boxes must not remain rendered"
    Assert-FileContains $signFile "translateFormattedDocument(document.requestPayload())" "sign helper uses direct formatted document pipeline"
    Assert-FileContains $signFile "sign.manual.group.by_id.direct" "manual sign selection must submit one coordinate-id group document"
    Assert-FileContains $signFile "SignDirectDocument.fromEntries" "manual sign group must use the sign-id direct document"
    Assert-FileContains $signFile "sign.auto.single.direct" "automatic sign translation must translate only the current sign"
    Assert-FileContains $signFile "sign-auto-single-by-id" "automatic sign translation must use a single sign-id document"
    Assert-FileContains $signFile "buildAutoSignDocument" "automatic sign translation must build a whole-sign document"
    Assert-FileContains $signFile "Treat the four visible lines below as one complete sign message" "automatic sign prompt must make the four lines one semantic block"
    Assert-FileContains $signFile "applySignDocument(List.of(context)" "automatic sign completion must restore the full four-line sign document"
    Assert-FileNotContains $signFile "DirectSurfaceTranslator.translateComponentsAsync" "automatic sign translation must not use isolated component-list requests"
    Assert-FileContains $deepSeekFile 'surface=\"sign.auto.single.direct\"' "direct prompt must include automatic whole-sign rules"
    Assert-FileContains $signFile "Automatic sign mode translates only this one sign" "automatic sign prompt must not use nearby sign context"
    Assert-FileNotContains $signFile "Nearby context signs" "automatic sign prompt must not include nearby context signs"
    Assert-FileContains $signFile "buildSpatialPanelContext" "manual sign selection must build spatial panel context"
    Assert-FileContains $signFile "Spatial panel reading order" "manual sign prompt must expose spatial panel reading order"
    Assert-FileContains $signFile "panelKey()" "manual sign ordering must group signs into spatial panels"
    Assert-FileContains $signFile "directionFromRotation" "manual sign ordering must derive standing-sign facing"
    Assert-FileNotContains $signFile "sign.manual.sequence.direct" "manual sign selection must not use sequential fallback"
    Assert-FileNotContains $signFile "sign.manual.ordered.direct" "manual sign selection must not use old ordered line chunks"
    Assert-FileNotContains $signFile "requestManualOrderedTranslation" "manual sign selection must not use old ordered translation method"
    Assert-FileNotContains $signFile "requestManualSequentialFallback" "manual sign selection must not use ordered plain-text fallback"
    Assert-FileNotContains $signFile "requestMappingBatch" "automatic sign translation must not use old grouped mapping batch"
    Assert-FileContains $signFile "selectionIndex" "manual sign translation must preserve player selection order"
    Assert-FileContains $signFile "signId" "manual sign writeback must match coordinate sign ids"
    Assert-FileContains $signFile "createSignId" "sign helper must create stable coordinate ids"
    Assert-FileContains $signFile 'append("  line ").append(line + 1).append(": ")' "sign context must expose per-line boundaries to the model"
    Assert-FileContains $signFile "SIGN_COMPONENT_CACHE" "sign helper caches styled translated components"
    Assert-FileContains $signFile "SHARED_SIGN_COMPONENT_CACHE" "sign helper must keep successful per-sign translations across auto/manual mode switches"
    Assert-FileContains $signFile "createPersistentSignKey" "shared sign cache must use the stable per-sign persistent key"
    Assert-FileContains $signFile "serializeSignComponents" "shared sign cache must persist full styled Component arrays"
    Assert-FileContains $signFile "deserializeSignComponents" "shared sign cache must reload full styled Component arrays"
    Assert-FileContains $signFile "clearTransientState()" "sign mode changes must clear pending/transient state without clearing successful shared translations"
    Assert-FileContains $signFile "putSharedSignComponents(context" "automatic and manual sign completions must write shared per-sign cache"
    Assert-FileContains $signFile "CONTENT_SIGN_CONTEXT_MODE.get() == ModConfig.SignContextMode.MANUAL" "manual sign mode must not auto-request translations"
    Assert-FileContains $signFile "SIGN_MODE_EPOCH" "sign helper must isolate stale async sign responses by mode epoch"
    Assert-FileContains $signFile "handleSignSettingsChanged" "sign settings changes must bump sign mode epoch"
    Assert-FileContains $signFile 'TranslationRequestQueue.cancelSurfacePrefix("sign.auto")' "switching sign mode must cancel queued automatic sign work"
    Assert-FileContains $signFile 'TranslationRequestQueue.cancelSurfacePrefix("sign.manual")' "switching sign mode must cancel queued manual sign work"
    Assert-FileContains $signFile "isSignModeEpochCurrent(modeEpoch)" "sign async responses must check mode epoch before writing back"
    Assert-FileNotContains $signFile "SIGN_RESULT_CACHE" "sign helper must not read or write old string sign cache"
    Assert-FileNotContains $signFile "translateRawPreserveMarkers" "sign helper must not use old marker-preserving translation"
    Assert-FileNotContains $signFile "@@@S" "sign helper must not use old sign markers"
    Assert-FileContains $signDirectFile "mode=\"sign-group\"" "sign direct document must use sign-group mode"
    Assert-FileContains $signDirectFile "sign-duplicate-id" "sign direct validation must reject duplicate sign ids"
    Assert-FileContains $signDirectFile "sign-missing-id" "sign direct validation must reject missing sign ids"
    Assert-FileContains $signDirectFile "sign-unknown-id" "sign direct validation must reject wrong sign ids"
    Assert-FileContains $directFile "sign-token-missing" "sign direct fixed layout must reject missing protected anchors"
    Assert-FileContains $directFile "sign-token-moved" "sign direct fixed layout must reject protected anchors moved from another line"
    Assert-FileContains $signDirectFile "sign-token-moved-from-other-sign" "sign group validation must allow same-sign token movement but reject cross-sign token movement"
    Assert-FileContains $signDirectFile "signProtectedTokenCounts" "sign group validation must validate protected tokens at sign scope"
    Assert-FileContains (Join-Path $src "mixin\SignRendererMixin.java") "CONTENT_SIGN_RADIUS" "sign renderer mixin must use auto scan range as an automatic trigger gate"
    Assert-FileContains (Join-Path $src "mixin\SignRendererMixin.java") "allowAutoRequest" "sign renderer radius gate must only block new automatic requests"
    Assert-FileContains (Join-Path $src "mixin\SignRendererMixin.java") "getTranslatedLinesWithState(sign, front, mc.level, allowAutoRequest)" "sign renderer must still register cached sign translations outside auto scan range"
    Assert-FileContains (Join-Path $src "gui\SignTranslationScreen.java") "CONTENT_SIGN_RADIUS" "sign settings screen must expose automatic scan range"
    Assert-FileContains (Join-Path $src "gui\SignTranslationScreen.java") "handleSignSettingsChanged" "sign settings screen must bump epoch and clear sign state on settings changes"
    Assert-FileContains (Join-Path $src "mixin\SignTextMixin.java") "data.translatedComponents" "sign render mixin must render styled translated components directly"
    $advancementFile = Join-Path $src "util\AdvancementTranslationHelper.java"
    Assert-FileContains $advancementFile "translateComponentsAsync(List.of" "advancement title and description must be requested as one async document"
    Assert-FileContains $advancementFile "storeComponent(titleText, result.components.get(0), TITLE_SURFACE)" "advancement document completion must populate title cache"
    Assert-FileContains $advancementFile "storeComponent(descText, result.components.get(1), DESCRIPTION_SURFACE)" "advancement document completion must populate description cache"
    Assert-FileContains $advancementFile "pendingDocuments" "advancement helper must suppress fragmented title/description requests while document is pending"
    Assert-FileContains $advancementFile "pendingComponentKeys" "advancement pending suppression must be scoped to the current title/description text"
    Assert-FileNotContains $advancementFile "!pendingDocuments.isEmpty()" "advancement pending suppression must not block unrelated advancement titles globally"
    Assert-FileContains $advancementFile "getCachedTitleComponent" "advancement title FCS render paths must be able to read the whole-document title cache"
    Assert-FileContains $advancementFile "cache.put(key, translatedText)" "advancement document results must persist title/description strings for FCS readers"
    $advancementWidgetMixin = Join-Path $src "mixin\AdvancementWidgetMixin.java"
    $advancementWidgetContent = Get-Content -Raw -Path $advancementWidgetMixin
    if (-not ($advancementWidgetContent.Contains("AdvancementWidget;title:Lnet/minecraft/util/FormattedCharSequence;") -or
              $advancementWidgetContent.Contains("AdvancementWidget;titleLines:Ljava/util/List;"))) {
        throw "advancement widget must redirect the precomputed title FCS field/list"
    }
    if (-not ($advancementWidgetContent.Contains('simple_translate$getTitleForRender') -or
              $advancementWidgetContent.Contains('simple_translate$getTitleLinesForRender'))) {
        throw "advancement widget title must be rebuilt from the document cache like descriptions"
    }
    Assert-FileContains $advancementWidgetMixin "getCachedTitleComponent" "advancement widget title must read the whole-document title cache"
    $advancementToastMixin = Join-Path $src "mixin\AdvancementToastMixin.java"
    Assert-FileContains $advancementToastMixin 'simple_translate$getNextTranslatedTitleLine' "advancement toast FCS title lines must read cached whole-document title translations"
    Assert-FileContains $advancementToastMixin "getCachedTitleComponent" "advancement toast title must not rely on FCS fragment translation"
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") "DirectSurfaceTranslator.translateTextAsync" "chat helper uses direct formatted pipeline"
    $chatMixin = Join-Path $src "mixin\ChatComponentMixin.java"
    Assert-FileContains $chatMixin 'if (ModConfig.CHAT_CONTEXT_ENABLED.get())' "chat context mode must remain reachable after chat-body detection"
    Assert-FileNotContains $chatMixin 'if (ModConfig.CHAT_CONTEXT_ENABLED.get() && !simple_translate$hasChatBodyPrefix(plainText))' "chat context mode must not be guarded by the old unreachable pattern"
    Assert-FileContains $chatMixin 'simple_translate$handleAutoModeWithContext(message, plainText, manager)' "auto chat path must dispatch context translation before segment fallback"
    Assert-FileContains $chatMixin 'simple_translate$getOriginalContextText(content)' "chat button context must normalize displayed button messages before matching"
    Assert-FileContains $chatMixin 'ButtonMessageData data = simple_translate$buttonMessages.get(messageId);' "chat button context must recover the saved original message data"
    Assert-FileContains $chatMixin 'return data.originalPlainText();' "chat button context must use the saved original text instead of translated/button-suffix text"
    Assert-FileContains $chatMixin 'return simple_translate$stripChatButtonSuffix(content.getString());' "chat context fallback must strip button suffixes from displayed messages"
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") 'CHAT_CONTEXT_SURFACE = "chat.context.direct"' "chat context must use direct context surface"
    Assert-FileNotContains (Join-Path $src "util\TooltipTranslationHelper.java") "tooltip.item.segment" "tooltip helper must not read old segment cache"
    Assert-FileNotContains (Join-Path $src "util\TooltipTranslationHelper.java") "tooltip.item.paragraph" "tooltip helper must not read old paragraph cache"
    Assert-FileNotContains (Join-Path $src "util\TooltipTranslationHelper.java") "translateRawPreserveMarkers" "tooltip and hover must not use marker batches"
    Assert-FileContains (Join-Path $src "util\TooltipTranslationHelper.java") 'ITEM_TOOLTIP_CONTEXT_SURFACE = "tooltip.item_context.direct"' "item tooltip helper must use the new context surface"
    Assert-FileContains (Join-Path $src "util\TooltipTranslationHelper.java") "buildItemTooltipContext" "item tooltip helper must build whole-tooltip semantic context"
    Assert-FileNotContains (Join-Path $src "util\TooltipTranslationHelper.java") '"tooltip.item.direct"' "item tooltip helper must not request the legacy item surface"
    Assert-FileNotContains (Join-Path $src "mixin\ItemStackMixin.java") '"tooltip.item.direct"' "item stack mixin must not read legacy item tooltip cache"
    Assert-FileContains (Join-Path $src "mixin\ItemStackMixin.java") "TooltipTranslationHelper.getCachedComponentsBatch" "item stack mixin must read the new helper-owned cache path"
    $containerTooltipMixin = Join-Path $src "mixin\AbstractContainerScreenMixin.java"
    Assert-FileContains (Join-Path $ProjectDir "src\main\resources\simple_translate.mixins.json") '"AbstractContainerScreenMixin"' "container item tooltip mixin must be registered"
    Assert-FileContains $containerTooltipMixin 'method = "renderTooltip"' "container item tooltip mixin must hook the actual container tooltip render path"
    Assert-FileContains $containerTooltipMixin "getTooltipFromContainerItem" "container item tooltip mixin must reuse the screen-specific tooltip source"
    $containerTooltipContent = Get-Content -Raw -Path $containerTooltipMixin
    if (-not ($containerTooltipContent.Contains("graphics.renderTooltip") -or $containerTooltipContent.Contains("graphics.setTooltipForNextFrame"))) {
        throw "container item tooltip mixin must render cached translated tooltips directly: expected $containerTooltipMixin to contain <graphics.renderTooltip> or <graphics.setTooltipForNextFrame>"
    }
    Assert-FileContains $containerTooltipMixin "ci.cancel()" "container item tooltip mixin must cancel vanilla rendering after rendering translated tooltip"
    Assert-FileContains $containerTooltipMixin "TooltipTranslationHelper.translateComponentsBatch" "container item tooltip mixin must use the shared tooltip batch translator"
    Assert-FileContains $containerTooltipMixin "TooltipTranslationHelper.isMarkedTranslatedTooltip" "container item tooltip mixin must avoid reprocessing already translated tooltips"
    Assert-FileNotContains $containerTooltipMixin 'method = "render", at = @At("TAIL")' "container item tooltip mixin must not prefetch every visible inventory slot on screen open"
    Assert-FileNotContains $containerTooltipMixin "prefetchComponentsBatch" "container item tooltip translation must be hover-triggered, not inventory-wide prefetch"
    Assert-FileNotContains $containerTooltipMixin "prefetchSlotsPerPass" "container item tooltip mixin must not keep slot-scanning prefetch state"
    Assert-FileNotContains $containerTooltipMixin "nextTooltipPrefetchSlot" "container item tooltip mixin must not rotate through backpack slots for translation"
    $titleOverlayMixin = Join-Path $src "mixin\TitleOverlayMixin.java"
    Assert-FileContains $titleOverlayMixin "DirectSurfaceTranslator.translateComponents" "title/subtitle must translate together through the direct formatted pipeline"
    Assert-FileContains $titleOverlayMixin "hud.title_group.component.direct" "title/subtitle must use the grouped direct HUD surface"
    Assert-FileContains $titleOverlayMixin "hud.actionbar.component.direct" "actionbar must use its own direct HUD surface"
    $titleOverlaySource = Get-Content -Raw $titleOverlayMixin
    if ($titleOverlaySource -notmatch 'if \(token\.matches\("\[0-9/\]\+"\)\) \{\s*continue;\s*\}') {
        throw "actionbar technical HUD filter must not count numeric counters as technical words"
    }
    Assert-FileContains $titleOverlayMixin "ACTIONBAR_DYNAMIC_VALUE_PATTERN" "actionbar must normalize fast-changing numeric values before caching"
    Assert-FileContains $titleOverlayMixin 'simple_translate$buildActionbarTemplate' "actionbar must build a stable translation template"
    Assert-FileContains $titleOverlayMixin 'simple_translate$restoreActionbarVariables' "actionbar must restore current numeric values into cached translated labels"
    Assert-FileContains $titleOverlayMixin "List.of(actionbarTemplate.component())" "actionbar dynamic template must go through component-list translation"
    Assert-FileContains $titleOverlayMixin "true);" "actionbar dynamic placeholders must be protected by fixed direct layout"
    Assert-FileContains $titleOverlayMixin "meaningfulWords <= 2" "actionbar technical HUD filter must allow multi-label RPG action bars"
    Assert-FileContains $deepSeekFile 'surface=\"hud.actionbar.component.direct\"' "direct prompt must include actionbar placeholder rules"
    Assert-FileContains $deepSeekFile "@@0@@" "actionbar placeholder rules must keep numeric HUD counters stable"
    Assert-FileContains $titleOverlayMixin 'simple_translate$captionBatchMode' "title/actionbar must branch between caption batch and immediate fallback"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshTitleGroupFromCaptionBatch' "context-enabled title path must use caption batch history"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshOverlayFromCaptionBatch' "context-enabled actionbar path must use caption batch history"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshTitleGroupImmediate' "context-disabled title path must keep immediate direct fallback"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshOverlayImmediate' "context-disabled actionbar path must keep immediate direct fallback"
    Assert-FileContains $titleOverlayMixin 'HudTranslationHistory.tickTranslator' "caption batch mode must tick the throttled history translator"
    Assert-FileContains $titleOverlayMixin 'HudTranslationHistory.recordCaption' "caption batch mode must record source captions instead of direct request spam"
    Assert-FileContains $titleOverlayMixin 'groupKey.equals(this.simple_translate$titleGroupKey' "title/subtitle async restore must guard against stale local title keys"
    Assert-FileContains $titleOverlayMixin 'currentKey.equals(this.simple_translate$overlayKey' "actionbar async restore must guard against stale actionbar template keys"
    Assert-FileNotContains $titleOverlayMixin "SemanticBlockTranslationHelper" "title/actionbar must not use the old semantic compatibility path"
    Assert-FileNotContains $titleOverlayMixin "hud.title.component.semantic" "title must not use the old independent semantic surface"
    Assert-FileNotContains $titleOverlayMixin "hud.subtitle.component.semantic" "subtitle must not use the old independent semantic surface"
    Assert-FileNotContains $titleOverlayMixin "hud.actionbar.component.semantic" "actionbar must not use the old semantic surface"
    Assert-FileNotContains $titleOverlayMixin "translateRaw(" "title/actionbar must not use raw translation"
    $bossbarMixin = Join-Path $src "mixin\BossHealthOverlayMixin.java"
    Assert-FileContains $bossbarMixin "DirectSurfaceTranslator.translateComponent" "bossbar must use the direct formatted surface translator"
    Assert-FileContains $bossbarMixin "bossbar.component.direct" "bossbar must use the direct surface key"
    Assert-FileNotContains $bossbarMixin "SemanticBlockTranslationHelper" "bossbar must not use the old semantic compatibility path"
    Assert-FileNotContains $bossbarMixin "bossbar.component.semantic" "bossbar must not use the old semantic surface"
    $textDisplayMixin = Join-Path $src "mixin\TextDisplayMixin.java"
    Assert-FileContains $textDisplayMixin "DirectSurfaceTranslator.translateComponent" "text display must use the direct formatted surface translator"
    Assert-FileContains $textDisplayMixin "text_display.component.direct" "text display must use the direct surface key"
    Assert-FileNotContains $textDisplayMixin "SemanticBlockTranslationHelper" "text display must not use the old semantic compatibility path"
    Assert-FileNotContains $textDisplayMixin "text_display.component.semantic" "text display must not use the old semantic surface"
    Assert-FileNotContains (Join-Path $src "util\ScoreboardTranslationHelper.java") "translateRawPreserveMarkers" "scoreboard must not use raw marker fallback"
    Assert-FileNotContains $deepSeekFile "Thread.sleep" "DeepSeek request workers must not block while retrying"
} finally {
    Pop-Location
}

