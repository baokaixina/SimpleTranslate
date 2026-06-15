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
        Sort-Object @{ Expression = {
            try { [version]$_.Directory.Parent.Name } catch { [version]"0.0.0" }
        }; Descending = $true }, LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $gsonJar) {
        throw "Could not find gson jar in Gradle cache"
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-logic-checks-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateLogicChecks.java"

    $javaSource = @'
import com.google.gson.JsonObject;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.core.TranslationDocument;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.translation.DeepSeekTranslationService;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.translation.VisionOcrTranslationService;
import com.yourname.simpletranslate.network.SharedCacheEntry;
import com.yourname.simpletranslate.network.SharedCacheStore;
import com.yourname.simpletranslate.util.DirectSurfaceTranslator;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectStatusTerms;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.ModelOutputSanitizer;
import com.yourname.simpletranslate.util.NumberTemplate;
import com.yourname.simpletranslate.util.SignTranslationHelper;
import com.yourname.simpletranslate.util.TextSegmentInfo;
import com.yourname.simpletranslate.util.TooltipTranslationHelper;
import com.yourname.simpletranslate.util.TranslationCacheKeys;
import com.yourname.simpletranslate.util.TranslationTextDetector;
import com.yourname.simpletranslate.util.OcrManager;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SimpleTranslateLogicChecks {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        checkModelOutputSanitizer();
        checkStreamedTranslationParsing();
        checkApiUrlValidation();
        checkDirectCacheKeyProtocol();
        checkEditableTranslationCache();
        checkTooltipCompatibleCacheLookup();
        checkCacheShareImportExport();
        checkServerSharedCache();
        checkOcrConfigPayloadsAndCache();
        checkDirectFormattedRoundTrip();
        checkHudTitleGroupDirect();
        checkTextDisplayDirectStyleSlots();
        checkFrozenNumberPlaceholders();
        checkHudHistoryDefaultsAndContext();
        checkHudHistoryDedupeLimitAndContext();
        checkTranslatedTooltipMarker();
        checkDirectPlainContextPayload();
        checkItemTooltipContextPayload();
        checkUnicodeTextDetection();
        checkDirectFormattedKeepsLocalizedHotkeys();
        checkDirectStatusTermConsistency();
        checkDirectAcceptsResidualSourceLanguage();
        checkKoreanPlainFallbackAcceptsLlmResult();
        checkChatSystemMessagePrefixDetection();
        checkChatContextConfigAndPayload();
        checkChatLineTextFallbackAndStyledPrefix();
        checkTranslateComponentFixedLayout();
        checkTranslatableArgStyles();
        checkStyleSignatureFontAndEvents();
        checkSignModeEpoch();
        checkSharedSignComponentSerialization();
        checkModelNormalization();
        checkApiFormatAndParallelConfig();
        checkConfigMigrationAndUnknownKeyPreservation();
        System.out.println("SimpleTranslate logic checks passed");
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

        String responsesJson = """
                {"output":[{"content":[{"type":"output_text","text":"0|\u4f60\u597d"}]}]}
                """;
        assertEquals("0|\u4f60\u597d", parseResponse.invoke(service, responsesJson),
                "OpenAI Responses non-stream output_text should be parsed");

        try {
            parseResponse.invoke(service, "<!doctype html><html><head><title>New API</title></head><body>Dashboard</body></html>");
            throw new AssertionError("HTML dashboard responses must not be accepted as model output");
        } catch (java.lang.reflect.InvocationTargetException e) {
            String message = e.getCause() == null ? "" : e.getCause().getMessage();
            assertContains(message, "HTML page", "HTML response failure explains wrong endpoint");
            assertContains(message, "New API", "HTML response failure keeps compact page title");
        }
    }

    private static void checkApiUrlValidation() {
        assertEquals("", ModConfig.validateApiUrl("https://api.example-provider.local/v1"),
                "ordinary HTTPS API URLs should pass validation");
        assertContains(ModConfig.validateApiUrl("https://abc.example.com/v1"), "example placeholder",
                "documentation placeholder hosts must be rejected");
        assertContains(ModConfig.validateApiUrl("comeu.ai/v1"), "http://",
                "API URLs without a scheme must be rejected");
        assertContains(ModConfig.validateApiUrl(""), "not configured",
                "blank API URLs must be rejected");
    }

    private static void checkDirectCacheKeyProtocol() {
        String raw = "Righteous Brandishing";
        String tooltip = TranslationCacheKeys.key("tooltip.item.direct", raw);
        String chat = TranslationCacheKeys.key("chat.message.direct", raw);
        if (tooltip.equals(chat)) {
            throw new AssertionError("surface-specific cache keys should not collide");
        }
        assertContains(tooltip, "direct:v18-mintag:tooltip.item.direct:", "tooltip direct cache prefix");
        assertContains(chat, "direct:v18-mintag:chat.message.direct:", "chat direct cache prefix");
        if (!TranslationCacheKeys.isCurrentProtocolKey("direct:v18-mintag:tooltip.item.direct:deadbeef")) {
            throw new AssertionError("v18 cache protocol must be treated as current");
        }
        if (TranslationCacheKeys.isCurrentProtocolKey("direct:v17-format:tooltip.item.direct:deadbeef")) {
            throw new AssertionError("legacy v17 cache protocol must not be treated as current");
        }
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
        String document = "stw1\n0|<1>\u4e3b\u6b66\u5668\uff1a</1><2>\u53f3\u952e</2>";
        cache.put(key, document, source, TranslationCache.displayTextFromValue(document));
        TranslationCache.CacheViewEntry entry = cache.getEntry(key).orElseThrow();
        assertEquals(source, entry.sourceText(), "cache entry stores readable source text");
        assertEquals("\u4e3b\u6b66\u5668\uff1a\u53f3\u952e", entry.translationText(), "cache entry stores readable translated text");

        assertEquals(null, cache.updateEditableTranslationText(key, "\u4e3b\u6b66\u5668\uff1a\u9f20\u6807\u53f3\u952e").orElse(null),
                "editable cache update should accept wire payloads");
        String updated = cache.get(key).orElseThrow();
        assertContains(updated, "0|*\u4e3b\u6b66\u5668\uff1a\u9f20\u6807\u53f3\u952e",
                "edited wire line is stored trusted so numeric guards never revert player edits");
        assertEquals(Boolean.TRUE, cache.getEntry(key).orElseThrow().editedByPlayer(), "cache edit marks entry as player edited");
        assertEquals("\u4e3b\u6b66\u5668\uff1a\u9f20\u6807\u53f3\u952e",
                cache.getEntry(key).orElseThrow().translationText(), "edited wire payload keeps readable text");

        String multiKey = TranslationCacheKeys.key("hud.history.caption_batch.direct", "a\nb", "", "line", "style");
        String multiDocument = "stw1\n0|old one\n1|old two";
        cache.put(multiKey, multiDocument, "a\nb", TranslationCache.displayTextFromValue(multiDocument));
        assertEquals("unsupported-format", cache.updateEditableTranslationText(multiKey, "\u53ea\u6709\u4e00\u884c").orElse(null),
                "multi-line cache edit must reject wrong line count");
        assertEquals(null, cache.updateEditableTranslationText(multiKey, "\u7b2c\u4e00\u884c\n\u7b2c\u4e8c\u884c").orElse(null),
                "multi-line cache edit accepts matching line count");
        assertEquals("\u7b2c\u4e00\u884c\n\u7b2c\u4e8c\u884c", cache.getEntry(multiKey).orElseThrow().translationText(),
                "multi-line cache edit updates readable translation text");
    }

    private static void checkTooltipCompatibleCacheLookup() throws Exception {
        Path tempDir = Files.createTempDirectory("simpletranslate-cache-compatible");
        TranslationCache cache = new TranslationCache(tempDir.resolve("translations.json"));
        cache.load();

        List<Component> currentTooltip = List.of(
                Component.literal("Skill Disenchanter").withStyle(ChatFormatting.AQUA),
                Component.literal("It is important to be able").withStyle(ChatFormatting.LIGHT_PURPLE));
        String source = "Skill Disenchanter\nIt is important to be able";
        String surface = TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE;
        String role = TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE;
        String oldKey = TranslationCacheKeys.key(surface, source, "old tooltip context", "old-slot", "old-style");
        String currentContext = TooltipTranslationHelper.buildItemTooltipContext(currentTooltip);
        TranslationDocument currentDocument = TranslationDocument.fromComponents(
                currentTooltip, surface, role, false, currentContext);
        String currentKey = currentDocument.cacheKey();
        String payload = "stw1\n0|\u6280\u80fd\u795b\u9b54\u4e66\n1|\u80fd\u591f\u6539\u53d8\u6280\u80fd\u5f88\u91cd\u8981";
        cache.put(oldKey, payload, source, TranslationCache.displayTextFromValue(payload));
        cache.put(TranslationCacheKeys.key(TooltipTranslationHelper.HOVER_OVERLAY_SURFACE, source,
                        currentContext, "old-slot", "old-style"),
                "stw1\n0|\u9519\u8bef surface\n1|\u4e0d\u5e94\u547d\u4e2d", source, "\u9519\u8bef surface");

        if (cache.get(currentKey).isPresent()) {
            throw new AssertionError("exact tooltip key should be absent before compatible lookup");
        }
        List<String> candidates = cache.getCompatibleBySource(currentKey);
        assertEquals(1, candidates.size(), "compatible tooltip lookup should find same-source cache despite style key drift");
        TranslationDocument.RestoreOutcome outcome = currentDocument.restore(candidates.get(0));
        assertNotNull(outcome, "compatible tooltip candidate must restore against the current tooltip document");
        if (outcome.isPartial()) {
            throw new AssertionError("compatible tooltip candidate must fully restore before it can be used");
        }
        assertEquals("\u6280\u80fd\u795b\u9b54\u4e66", outcome.components().get(0).getString(),
                "compatible tooltip restore uses cached translation");
        assertEquals(ChatFormatting.AQUA.getColor(), firstStyle(outcome.components().get(0)).getColor().getValue(),
                "compatible tooltip restore keeps current render style");

        Field cacheField = SimpleTranslateMod.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        Object previousCache = cacheField.get(null);
        cacheField.set(null, cache);
        try {
            DirectFormattedTranslationPipeline.ComponentListResult result =
                    DirectSurfaceTranslator.getCachedComponents(
                            currentTooltip, surface, role, false, currentContext);
            if (!result.translated || result.components == currentTooltip) {
                throw new AssertionError("direct tooltip cache restore should use compatible same-source entries");
            }
            assertEquals("\u6280\u80fd\u795b\u9b54\u4e66", result.components.get(0).getString(),
                    "direct compatible tooltip cache restore text");
            if (cache.get(currentKey).isEmpty()) {
                throw new AssertionError("compatible tooltip cache restore should heal the exact current key");
            }

            List<Component> partialTooltip = List.of(
                    Component.literal("Skill Disenchanter").withStyle(ChatFormatting.AQUA),
                    Component.literal("It is important to be able").withStyle(ChatFormatting.LIGHT_PURPLE),
                    Component.literal("in MAINHAND,").withStyle(ChatFormatting.GOLD),
                    Component.literal("Use a Smithing Table to").withStyle(ChatFormatting.GOLD));
            String partialContext = TooltipTranslationHelper.buildItemTooltipContext(partialTooltip);
            TranslationDocument partialDocument = TranslationDocument.fromComponents(
                    partialTooltip, surface, role, false, partialContext);
            String partialPayload = "stw1\n0|\u6280\u80fd\u795b\u9b54\u4e66\n1|\u80fd\u591f\u66f4\u6362\u5df2\u62e5\u6709\u7684\n2|in MAINHAND,\n3|Use a Smithing Table to";
            cache.put(partialDocument.cacheKey(), partialPayload,
                    "Skill Disenchanter\nIt is important to be able\nin MAINHAND,\nUse a Smithing Table to",
                    TranslationCache.displayTextFromValue(partialPayload));
            DirectFormattedTranslationPipeline.ComponentListResult partialResult =
                    DirectSurfaceTranslator.getCachedComponents(
                            partialTooltip, surface, role, false, partialContext);
            if (!partialResult.translated || partialResult.components == partialTooltip) {
                throw new AssertionError("partial item tooltip cache should still render translated lines");
            }
            assertEquals("\u6280\u80fd\u795b\u9b54\u4e66", partialResult.components.get(0).getString(),
                    "partial tooltip cache keeps translated title");
            assertEquals("\u653e\u5728\u4e3b\u624b,", partialResult.components.get(2).getString(),
                    "partial tooltip cache applies fixed MAINHAND fallback");
            assertEquals("\u4f7f\u7528\u953b\u9020\u53f0\u6765", partialResult.components.get(3).getString(),
                    "partial tooltip cache applies fixed Smithing Table fallback");
            if (cache.get(partialDocument.cacheKey()).isEmpty()) {
                throw new AssertionError("partial item tooltip cache must not be deleted after render restore");
            }
        } finally {
            cacheField.set(null, previousCache);
        }
    }

    private static void checkCacheShareImportExport() throws Exception {
        Path tempDir = Files.createTempDirectory("simpletranslate-cache-share");
        String key = TranslationCacheKeys.key("chat.message.direct", "Hello there", "", "slot", "style");
        TranslationCache source = new TranslationCache(tempDir.resolve("source").resolve("translations.json"));
        source.load();
        source.put(key, "\u4f60\u597d", "Hello there", "\u4f60\u597d");
        assertEquals(null, source.updateEditableTranslationText(key, "\u4f60\u597d\u73a9\u5bb6").orElse(null),
                "raw cache edit should be shareable");

        Path shareDir = tempDir.resolve("legacy_share");
        Files.createDirectories(shareDir);
        Files.writeString(shareDir.resolve("chat.json"),
                "{" + jsonString(key) + ":" + jsonString("\u4f60\u597d\u73a9\u5bb6") + "}");

        Path archiveFile = tempDir.resolve("SimpleTranslateCache-Greveholm-20260607-000000.zip");
        TranslationCache.CacheShareExportResult archiveExport = source.exportShareArchive(
                archiveFile,
                new TranslationCache.CacheShareMetadata("local", "Greveholm"),
                null);
        assertEquals(1, archiveExport.entries(), "share archive exports current entries");
        assertEquals(1, archiveExport.lanes(), "share archive exports current lanes");
        if (!Files.exists(archiveFile)) {
            throw new AssertionError("share archive should write a zip file");
        }
        if (!archiveFile.equals(archiveExport.archiveFile())) {
            throw new AssertionError("share archive result should expose the written zip path");
        }
        if (Files.exists(tempDir.resolve("archive_cache_export.json"))) {
            throw new AssertionError("share archive export should not create extra legacy files when not requested");
        }

        TranslationCache archiveImported = new TranslationCache(tempDir.resolve("archive_target").resolve("translations.json"));
        archiveImported.load();
        TranslationCache.CacheImportResult archiveImportResult =
                archiveImported.importFromShareSources(List.of(archiveFile), "Greveholm");
        assertEquals(1, archiveImportResult.imported(), "matching save-name archive should import");
        assertEquals(0, archiveImportResult.skippedWorldMismatch(), "matching archive should not report world mismatch");
        TranslationCache.CacheViewEntry archiveImportedEntry = archiveImported.getEntry(key).orElseThrow();
        assertEquals("Hello there", archiveImportedEntry.sourceText(), "zip archive import preserves readable source text");
        assertEquals("\u4f60\u597d\u73a9\u5bb6", archiveImportedEntry.translationText(),
                "zip archive import preserves readable translated text");
        assertEquals(Boolean.TRUE, archiveImportedEntry.editedByPlayer(),
                "zip archive import preserves player edit marker");
        if (Files.exists(tempDir.resolve("cache"))) {
            throw new AssertionError("zip archive import must read in memory instead of extracting its cache folder");
        }

        TranslationCache archiveConflict = new TranslationCache(tempDir.resolve("archive_conflict").resolve("translations.json"));
        archiveConflict.load();
        archiveConflict.put(key, "\u672c\u5730\u8bd1\u6587", "Hello there", "\u672c\u5730\u8bd1\u6587");
        TranslationCache.CacheImportResult archiveConflictResult =
                archiveConflict.importFromShareSources(List.of(archiveFile), "Greveholm");
        assertEquals(0, archiveConflictResult.imported(), "zip archive import must only add missing entries");
        assertEquals(1, archiveConflictResult.skippedExisting(), "zip archive import should report existing entries");
        assertEquals("\u672c\u5730\u8bd1\u6587", archiveConflict.getEntry(key).orElseThrow().translationText(),
                "zip archive import must not overwrite local cache entries");

        TranslationCache archiveMismatch = new TranslationCache(tempDir.resolve("archive_mismatch").resolve("translations.json"));
        archiveMismatch.load();
        TranslationCache.CacheImportResult archiveMismatchResult =
                archiveMismatch.importFromShareSources(List.of(archiveFile), "Other Save");
        assertEquals(0, archiveMismatchResult.imported(), "wrong save-name archive should not import");
        assertEquals(1, archiveMismatchResult.skippedWorldMismatch(), "wrong save-name archive should report mismatch");
        if (archiveMismatch.getEntry(key).isPresent()) {
            throw new AssertionError("wrong save-name archive must not write cache entries");
        }

        TranslationCache imported = new TranslationCache(tempDir.resolve("target").resolve("translations.json"));
        imported.load();
        TranslationCache.CacheImportResult importResult = imported.importFromShareSources(List.of(shareDir));
        assertEquals(1, importResult.sourceCount(), "share import should count the source directory");
        assertEquals(1, importResult.imported(), "share import should add one entry");
        TranslationCache.CacheViewEntry importedEntry = imported.getEntry(key).orElseThrow();
        assertEquals("\u4f60\u597d\u73a9\u5bb6", importedEntry.translationText(),
                "legacy folder import stores readable translated text");

        TranslationCache conflict = new TranslationCache(tempDir.resolve("conflict").resolve("translations.json"));
        conflict.load();
        conflict.put(key, "\u672c\u5730\u8bd1\u6587", "Hello there", "\u672c\u5730\u8bd1\u6587");
        TranslationCache.CacheImportResult conflictResult = conflict.importFromShareSources(List.of(shareDir));
        assertEquals(0, conflictResult.imported(), "share import must not overwrite local entries");
        assertEquals(1, conflictResult.skippedExisting(), "share import should report existing conflicts");
        assertEquals("\u672c\u5730\u8bd1\u6587", conflict.getEntry(key).orElseThrow().translationText(),
                "local cache wins on conflict");

        Path nestedShare = tempDir.resolve("cache_import").resolve("cache").resolve("foreign_world");
        Files.createDirectories(nestedShare);
        Files.writeString(nestedShare.resolve("chat.json"),
                "{" + jsonString(key) + ":" + jsonString("\u4f60\u597d\u73a9\u5bb6") + "}");
        TranslationCache nestedTarget = new TranslationCache(tempDir.resolve("nested_target").resolve("translations.json"));
        nestedTarget.load();
        TranslationCache.CacheImportResult nestedResult = nestedTarget.importFromShareSources(
                List.of(tempDir.resolve("cache_import")));
        assertEquals(1, nestedResult.imported(), "nested cache/worldId package should import into active cache");
        assertEquals("\u4f60\u597d\u73a9\u5bb6", nestedTarget.getEntry(key).orElseThrow().translationText(),
                "nested foreign worldId cache should become current active cache data");

        String flatKey = TranslationCacheKeys.key("hud.actionbar.component.direct", "Mana", "", "slot", "style");
        Path flatImport = tempDir.resolve("cache_import.json");
        Files.writeString(flatImport, "{\"" + flatKey + "\":\"\u6cd5\u529b\"}");
        TranslationCache flatTarget = new TranslationCache(tempDir.resolve("flat_target").resolve("translations.json"));
        flatTarget.load();
        TranslationCache.CacheImportResult flatResult = flatTarget.importFromFile(flatImport, true);
        assertEquals(1, flatResult.imported(), "legacy flat cache_import.json should still import");
        assertEquals("\u6cd5\u529b", flatTarget.getEntry(flatKey).orElseThrow().translationText(),
                "legacy flat import stores readable translated text");

        Path mixedDir = tempDir.resolve("mixed");
        Files.createDirectories(mixedDir);
        Files.writeString(mixedDir.resolve("bad.json"), "{bad");
        Files.writeString(mixedDir.resolve("invalid.json"),
                "{\"legacy:key\":\"old\",\"" + TranslationCacheKeys.key("chat.message.direct", "Blank") + "\":\"\"}");
        TranslationCache.CacheImportResult invalidResult = flatTarget.importFromShareSources(List.of(mixedDir));
        if (invalidResult.failedFiles() < 1) {
            throw new AssertionError("bad cache json should be reported as a failed file");
        }
        if (invalidResult.skippedInvalid() < 2) {
            throw new AssertionError("old protocol and blank cache entries should be skipped as invalid");
        }

        Path configLike = tempDir.resolve("config");
        Files.createDirectories(configLike.resolve("cache_share"));
        Files.writeString(configLike.resolve("cache_share").resolve("chat.json"), Files.readString(shareDir.resolve("chat.json")));
        Files.writeString(configLike.resolve("cache_export.json"), "{}");
        assertEquals(2, TranslationCache.discoverImportSources(configLike).size(),
                "cache manager should discover share directories and legacy flat exports");

        Path configZip = tempDir.resolve("config_zip");
        Files.createDirectories(configZip.resolve("cache_share"));
        Files.write(configZip.resolve("cache_share").resolve("SimpleTranslateCache-Greveholm.zip"),
                Files.readAllBytes(archiveFile));
        assertEquals(1, TranslationCache.discoverImportSources(configZip).size(),
                "cache manager should discover zipped share packages");
    }

    private static void checkServerSharedCache() throws Exception {
        assertEquals(Boolean.FALSE, ModConfig.CACHE_SERVER_SHARE_ENABLED.get(),
                "server shared cache should default to disabled");

        Path tempDir = Files.createTempDirectory("simpletranslate-shared-server");
        String key = TranslationCacheKeys.key("chat.message.direct", "Shared hello", "", "slot", "style");
        SharedCacheEntry shared = new SharedCacheEntry(
                key,
                "\u5171\u4eab\u4f60\u597d",
                "Shared hello",
                "\u5171\u4eab\u4f60\u597d",
                "chat.message.direct",
                100L,
                false,
                0L);
        if (!shared.isShareable()) {
            throw new AssertionError("valid shared cache entry should be shareable");
        }
        if (new SharedCacheEntry("legacy:key", "bad", "", "", "", 0L, false, 0L).isShareable()) {
            throw new AssertionError("old protocol shared cache entry should be rejected");
        }

        SharedCacheStore serverStore = new SharedCacheStore();
        Path storeFile = tempDir.resolve("simple_translate_shared_cache.json");
        serverStore.load(storeFile);
        assertEquals(1, serverStore.putMissing(List.of(shared)).size(),
                "server store should add a missing shared cache entry");
        assertEquals(0, serverStore.putMissing(List.of(new SharedCacheEntry(
                key,
                "\u88ab\u8986\u76d6",
                "Shared hello",
                "\u88ab\u8986\u76d6",
                "chat.message.direct",
                200L,
                false,
                0L))).size(), "server store must not overwrite existing shared cache entries");
        serverStore.saveIfDue(Long.MAX_VALUE);
        if (!Files.exists(storeFile)) {
            throw new AssertionError("server shared cache should persist as one world-root file");
        }

        SharedCacheStore reloadedStore = new SharedCacheStore();
        reloadedStore.load(storeFile);
        assertEquals(1, reloadedStore.allEntries().size(), "server shared cache should reload saved entries");
        assertEquals("\u5171\u4eab\u4f60\u597d", reloadedStore.allEntries().get(0).translation(),
                "server shared cache reload should keep the original accepted translation");

        TranslationCache localCache = new TranslationCache(tempDir.resolve("client").resolve("translations.json"));
        localCache.load();
        assertEquals(true, localCache.putSharedIfAbsent(shared.key(), shared.translation(), shared.sourceText(),
                shared.translationText(), shared.editedByPlayer(), shared.createdAt(), shared.editedAt()),
                "client should import a missing server shared cache entry");
        assertEquals("\u5171\u4eab\u4f60\u597d", localCache.getEntry(key).orElseThrow().translationText(),
                "client shared cache import should store readable translated text");
        assertEquals(true, localCache.getEntry(key).orElseThrow().sharedImported(),
                "client shared cache import must mark remote entries so snapshots do not upload them back");
        assertEquals(false, localCache.putSharedIfAbsent(shared.key(), "\u672c\u4e0d\u5e94\u8986\u76d6",
                shared.sourceText(), "\u672c\u4e0d\u5e94\u8986\u76d6", false, 300L, 0L),
                "client shared cache import must not overwrite existing local entries");
        assertEquals("\u5171\u4eab\u4f60\u597d", localCache.getEntry(key).orElseThrow().translationText(),
                "local cache wins over later server shared cache entries");

        String localKey = TranslationCacheKeys.key("chat.message.direct", "Local hello", "", "slot", "style");
        localCache.put(localKey, "\u672c\u5730\u4f60\u597d", "Local hello", "\u672c\u5730\u4f60\u597d");
        assertEquals(false, localCache.getEntry(localKey).orElseThrow().sharedImported(),
                "local cache entries must remain eligible for player-to-player snapshot sharing");
    }

    private static void checkOcrConfigPayloadsAndCache() throws Exception {
        assertEquals(Boolean.FALSE, ModConfig.OCR_ENABLED.get(), "OCR should default to disabled");
        assertEquals(Boolean.TRUE, ModConfig.OCR_USE_TRANSLATION_MODEL.get(),
                "OCR should default to reusing the normal translation model");
        assertEquals(ModConfig.ApiFormat.OPENAI_RESPONSES, ModConfig.OCR_API_FORMAT.get(),
                "OCR should default to OpenAI Responses vision format");
        assertEquals("https://api.openai.com/v1/responses", ModConfig.OCR_API_URL.get(),
                "OCR default API URL");
        assertEquals(420, ModConfig.OCR_REGION_WIDTH.get(), "OCR default region width");
        assertEquals(180, ModConfig.OCR_REGION_HEIGHT.get(), "OCR default region height");
        assertEquals(-1, ModConfig.OCR_REGION_X.get(), "OCR default region x should mean centered");
        assertEquals(-1, ModConfig.OCR_REGION_Y.get(), "OCR default region y should mean centered");

        int oldWidth = ModConfig.OCR_REGION_WIDTH.get();
        int oldHeight = ModConfig.OCR_REGION_HEIGHT.get();
        int oldX = ModConfig.OCR_REGION_X.get();
        int oldY = ModConfig.OCR_REGION_Y.get();
        try {
            ModConfig.OCR_REGION_WIDTH.set(12);
            ModConfig.OCR_REGION_HEIGHT.set(2000);
            ModConfig.OCR_REGION_X.set(-9);
            ModConfig.OCR_REGION_Y.set(99999);
            assertEquals(80, ModConfig.OCR_REGION_WIDTH.get(), "OCR region width should clamp low");
            assertEquals(900, ModConfig.OCR_REGION_HEIGHT.get(), "OCR region height should clamp high");
            assertEquals(-1, ModConfig.OCR_REGION_X.get(), "OCR region x should keep -1 as only negative sentinel");
            assertEquals(9000, ModConfig.OCR_REGION_Y.get(), "OCR region y should clamp high");
        } finally {
            ModConfig.OCR_REGION_WIDTH.set(oldWidth);
            ModConfig.OCR_REGION_HEIGHT.set(oldHeight);
            ModConfig.OCR_REGION_X.set(oldX);
            ModConfig.OCR_REGION_Y.set(oldY);
        }

        String base64 = "abc123";
        String prompt = "Read visible text and translate it.";
        JsonObject responses = VisionOcrTranslationService.buildRequestBodyForTest(
                ModConfig.ApiFormat.OPENAI_RESPONSES, "gpt-4.1-mini", prompt, base64);
        assertContains(responses.toString(), "\"input_image\"", "OpenAI Responses OCR body should use input_image");
        assertContains(responses.toString(), "data:image/png;base64,abc123",
                "OpenAI Responses OCR body should carry a base64 data URL");

        JsonObject chat = VisionOcrTranslationService.buildRequestBodyForTest(
                ModConfig.ApiFormat.OPENAI_CHAT_COMPAT, "gpt-4o-mini", prompt, base64);
        assertContains(chat.toString(), "\"image_url\"", "OpenAI chat-compatible OCR body should use image_url");
        assertContains(chat.toString(), "data:image/png;base64,abc123",
                "OpenAI chat-compatible OCR body should carry a base64 data URL");

        JsonObject anthropic = VisionOcrTranslationService.buildRequestBodyForTest(
                ModConfig.ApiFormat.ANTHROPIC_MESSAGES, "claude-3-5-haiku-latest", prompt, base64);
        assertContains(anthropic.toString(), "\"media_type\":\"image/png\"",
                "Anthropic OCR body should identify PNG input");
        assertContains(anthropic.toString(), "\"data\":\"abc123\"",
                "Anthropic OCR body should carry inline base64 data");

        JsonObject gemini = VisionOcrTranslationService.buildRequestBodyForTest(
                ModConfig.ApiFormat.GEMINI_GENERATE_CONTENT, "gemini-1.5-flash", prompt, base64);
        assertContains(gemini.toString(), "\"inlineData\"",
                "Gemini OCR body should use inlineData image input");
        assertContains(gemini.toString(), "\"mimeType\":\"image/png\"",
                "Gemini OCR body should identify PNG input");

        OcrTranslationService.OcrResult parsed = VisionOcrTranslationService.parseOcrResultForTest(
                "```json\n{\"sourceText\":\"Hello\",\"translationText\":\"Ni hao\"}\n```");
        assertEquals(true, parsed.success(), "OCR JSON response should parse as success");
        assertEquals("Hello", parsed.sourceText(), "OCR parsed source text");
        assertEquals("Ni hao", parsed.translationText(), "OCR parsed translation text");
        assertEquals(false, parsed.hasPositionedRegions(), "legacy OCR JSON should remain a non-positioned fallback");

        OcrTranslationService.OcrResult positioned = VisionOcrTranslationService.parseOcrResultForTest(
                "{\"sourceText\":\"Start Game\",\"translationText\":\"Begin\","
                        + "\"regions\":[{\"sourceText\":\"Start Game\",\"translationText\":\"Begin\","
                        + "\"x\":0.25,\"y\":0.4,\"width\":0.5,\"height\":0.2}]}");
        assertEquals(true, positioned.hasPositionedRegions(), "OCR should parse positioned translation regions");
        assertEquals(1, positioned.regions().size(), "OCR positioned region count");
        assertEquals(250, positioned.regions().get(0).x(), "OCR fractional x should normalize to 0..1000");
        assertEquals(400, positioned.regions().get(0).y(), "OCR fractional y should normalize to 0..1000");
        assertEquals(500, positioned.regions().get(0).width(), "OCR fractional width should normalize to 0..1000");
        assertEquals(200, positioned.regions().get(0).height(), "OCR fractional height should normalize to 0..1000");

        OcrTranslationService.OcrResult fallback = VisionOcrTranslationService.parseOcrResultForTest("Plain translated text");
        assertEquals(true, fallback.success(), "OCR non-JSON response should fall back to translated text");
        assertEquals("", fallback.sourceText(), "OCR non-JSON response should not invent source text");
        assertEquals("Plain translated text", fallback.translationText(), "OCR non-JSON fallback translation text");

        OcrTranslationService.OcrResult failed = VisionOcrTranslationService.parseOcrResultForTest(
                "\u0000simpletranslate-ocr-error:HTTP 401: Unauthorized");
        assertEquals(false, failed.success(), "OCR internal API errors should surface as failures");
        assertContains(failed.errorMessage(), "HTTP 401", "OCR failure should preserve compact API status");

        String oldTranslationKey = ModConfig.DEEPSEEK_API_KEY.get();
        String oldOcrKey = ModConfig.OCR_API_KEY.get();
        ModConfig.ApiFormat oldTranslationFormat = ModConfig.API_FORMAT.get();
        ModConfig.ApiFormat oldOcrFormat = ModConfig.OCR_API_FORMAT.get();
        boolean oldReuse = ModConfig.OCR_USE_TRANSLATION_MODEL.get();
        try {
            ModConfig.OCR_USE_TRANSLATION_MODEL.set(true);
            ModConfig.API_FORMAT.set(ModConfig.ApiFormat.DEEPSEEK_CHAT);
            ModConfig.DEEPSEEK_API_KEY.set("text-only-key");
            ModConfig.OCR_API_FORMAT.set(ModConfig.ApiFormat.OPENAI_RESPONSES);
            ModConfig.OCR_API_KEY.set("vision-key");
            assertContains(OcrManager.activeProfileDescription(), ModConfig.ApiFormat.OPENAI_RESPONSES.getDisplayName(),
                    "OCR should fall back to dedicated vision model when reused translation model is text-only");
        } finally {
            ModConfig.DEEPSEEK_API_KEY.set(oldTranslationKey);
            ModConfig.OCR_API_KEY.set(oldOcrKey);
            ModConfig.API_FORMAT.set(oldTranslationFormat);
            ModConfig.OCR_API_FORMAT.set(oldOcrFormat);
            ModConfig.OCR_USE_TRANSLATION_MODEL.set(oldReuse);
        }

        String cacheValue = parsed.toCacheValue();
        if (cacheValue.contains("base64") || cacheValue.contains("input_image") || cacheValue.contains(base64)) {
            throw new AssertionError("OCR cache value must not store images or request payloads: " + cacheValue);
        }
        assertEquals("Ni hao", TranslationCache.displayTextFromValue(cacheValue),
                "cache manager should display OCR translation text instead of internal JSON");
        OcrTranslationService.OcrResult cached = OcrTranslationService.OcrResult.fromCacheValue(cacheValue);
        assertEquals("Hello", cached.sourceText(), "OCR cache should restore source text");
        assertEquals("Ni hao", cached.translationText(), "OCR cache should restore translation text");

        String positionedCacheValue = positioned.toCacheValue();
        assertContains(positionedCacheValue, "\"version\":\"ocr-cache-v2\"",
                "positioned OCR cache should use the layout-aware version");
        OcrTranslationService.OcrResult positionedCached =
                OcrTranslationService.OcrResult.fromCacheValue(positionedCacheValue);
        assertEquals(true, positionedCached.hasPositionedRegions(),
                "OCR cache should restore positioned translation regions");
        assertEquals("Begin", positionedCached.regions().get(0).translationText(),
                "OCR cache should restore positioned translation text");

        OcrTranslationService.OcrResult legacyCached = OcrTranslationService.OcrResult.fromCacheValue(
                "{\"version\":\"ocr-cache-v1\",\"sourceText\":\"Old\",\"translationText\":\"Legacy\"}");
        assertEquals("Legacy", legacyCached.translationText(), "OCR cache v1 should remain readable");
        assertEquals(false, legacyCached.hasPositionedRegions(), "OCR cache v1 should use paragraph fallback");

        String ocrKey = OcrManager.cacheKey("deadbeef");
        assertContains(ocrKey, "direct:v18-mintag:ocr.region.vision:", "OCR cache key should use its own surface");
        assertEquals("ocr", TranslationCacheKeys.laneFromKey(ocrKey), "OCR cache key should use ocr lane");
        assertEquals("ocr", TranslationCacheKeys.requestLaneFromSurface("ocr.region.vision"),
                "OCR requests should use the interactive ocr queue lane");
        SharedCacheEntry ocrShared = new SharedCacheEntry(
                ocrKey,
                cacheValue,
                "Hello",
                "Ni hao",
                OcrTranslationService.SURFACE,
                100L,
                false,
                0L);
        if (ocrShared.isShareable()) {
            throw new AssertionError("OCR cache entries must stay local and must not enter server shared cache");
        }
    }

    private static void checkDirectFormattedRoundTrip() {
        Component source = Component.empty()
                .append(Component.literal("Ring").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(" Amulet Slot").withStyle(ChatFormatting.GRAY));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false);
        assertContains(document, "[TEXT lines=1]", "wire document header");
        assertContains(document, "0|<1>Ring</1><2> Amulet Slot</2>",
                "multi-style line carries numbered tags around each styled run");

        String translated = document
                .replace("<1>Ring</1>", "<1>\u6212\u6307</1>")
                .replace("<2> Amulet Slot</2>", "<2>\u62a4\u7b26\u69fd</2>");
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

        // Reordered tags must follow the translated word order, not the source order.
        String reordered = "0|<2>\u62a4\u7b26\u69fd</2><1>\u6212\u6307</1>";
        List<Component> reorderedRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false, reordered);
        assertNotNull(reorderedRestored, "tag reordering restores");
        assertEquals("\u62a4\u7b26\u69fd\u6212\u6307", reorderedRestored.get(0).getString(), "reordered visible text");
        List<TextSegmentInfo> reorderedSegments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(reorderedRestored.get(0), reorderedSegments, Style.EMPTY, true);
        assertEquals(ChatFormatting.GRAY.getColor(), reorderedSegments.get(0).style.getColor().getValue(),
                "reordered first segment keeps its original run color");
        assertEquals(ChatFormatting.AQUA.getColor(), reorderedSegments.get(1).style.getColor().getValue(),
                "reordered second segment keeps its original run color");

        // Lost tags degrade to approximate styling instead of rejecting the line.
        String untagged = "0|\u6212\u6307\u62a4\u7b26\u69fd";
        List<Component> degraded = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false, untagged);
        assertNotNull(degraded, "tag loss must degrade gracefully instead of rejecting the document");
        assertEquals("\u6212\u6307\u62a4\u7b26\u69fd", degraded.get(0).getString(), "degraded line still shows translation");
    }

    private static void checkHudTitleGroupDirect() {
        Component title = Component.literal("Mission Started").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("Defend the South Gate").withStyle(ChatFormatting.YELLOW);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false);
        assertContains(document, "[TEXT lines=2]", "title/subtitle grouped wire header");
        assertContains(document, "0|Mission Started", "title line serialized");
        assertContains(document, "1|Defend the South Gate", "subtitle line serialized");

        String translated = document
                .replace("0|Mission Started", "0|\u4efb\u52a1\u5f00\u59cb")
                .replace("1|Defend the South Gate", "1|\u9632\u5b88\u5357\u95e8");
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
        String noisyTranslated = noisyDocument.replace("0|I LOVE YOU BABY\u2727", "0|\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727");
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
        assertContains(document, "0|<1>Primary Fire: </1><2>\u53f3\u952e</2>",
                "text display line keeps separate numbered style tags");

        String translated = document.replace("<1>Primary Fire: </1>", "<1>\u4e3b\u6b66\u5668\uff1a</1>");
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

        // A dropped non-editable tag is re-appended locally so hotkeys are never lost.
        String missingSlot = translated.replace("<2>\u53f3\u952e</2>", "");
        List<Component> missingRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "text_display.component.direct", "text-display", false, missingSlot);
        assertNotNull(missingRestored, "text display degrades gracefully when the model drops a tag");
        assertContains(missingRestored.get(0).getString(), "\u53f3\u952e",
                "dropped non-editable hotkey slot is restored from the original runs");
    }

    private static void checkFrozenNumberPlaceholders() {
        Component source = Component.literal("Time: 12:34").withStyle(ChatFormatting.GOLD);
        NumberTemplate template = NumberTemplate.capture(source);
        assertEquals("Time: @@0@@", template.normalizedText(), "number template normalized text");
        assertEquals("Time: @@0@@", template.normalized().getString(), "number template normalized component");
        assertEquals("\u65f6\u95f4\uff1a12:34", template.restoreText("\u65f6\u95f4\uff1a@@0@@"),
                "number template restores current value");
        if (template.canRestoreText("\u65f6\u95f4\uff1a@@0@@ @@0@@")) {
            throw new AssertionError("number template must reject duplicated placeholders");
        }
        if (template.canRestoreText("\u65f6\u95f4\uff1a@@1@@")) {
            throw new AssertionError("number template must reject out-of-range placeholders");
        }
        if (template.restoreText("\u65f6\u95f4\uff1a12:34") != null) {
            throw new AssertionError("number template must reject missing placeholders");
        }

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(template.normalized()), "scoreboard.component.direct", "scoreboard-line", false);
        String good = document.replace("Time: @@0@@", "\u65f6\u95f4\uff1a@@0@@");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(template.normalized()), "scoreboard.component.direct", "scoreboard-line", false, good);
        assertNotNull(restored, "direct pipeline should accept exact frozen numeric placeholder");
        assertEquals("\u65f6\u95f4\uff1a@@0@@", restored.get(0).getString(),
                "direct pipeline keeps numeric placeholder for caller restore");

        // The unified NumberGuard keeps the original line when digits change.
        String missing = good.replace("@@0@@", "12:34");
        if (DirectFormattedTranslationPipeline.restoreForTest(
                List.of(template.normalized()), "scoreboard.component.direct", "scoreboard-line", false, missing) != null) {
            throw new AssertionError("number guard must reject changed digit content");
        }
        String duplicated = good.replace("@@0@@", "@@0@@ @@0@@");
        if (DirectFormattedTranslationPipeline.restoreForTest(
                List.of(template.normalized()), "scoreboard.component.direct", "scoreboard-line", false, duplicated) != null) {
            throw new AssertionError("number guard must reject duplicated numeric tokens");
        }
        String extra = good.replace("@@0@@", "@@0@@ @@1@@");
        if (DirectFormattedTranslationPipeline.restoreForTest(
                List.of(template.normalized()), "scoreboard.component.direct", "scoreboard-line", false, extra) != null) {
            throw new AssertionError("number guard must reject invented numeric tokens");
        }

        // Literal numbers stay literal on every other surface; drops keep the original line.
        Component numeric = Component.literal("Produces 1 Emerald every 10 Seconds").withStyle(ChatFormatting.GOLD);
        String numericDoc = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(numeric), "tooltip.item_context.direct", "tooltip-block-context", false);
        assertContains(numericDoc, "0|Produces 1 Emerald every 10 Seconds",
                "numbers travel as literal digits without placeholders");
        List<Component> okNumbers = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(numeric), "tooltip.item_context.direct", "tooltip-block-context", false,
                "0|\u6bcf 10 \u79d2\u4ea7\u51fa 1 \u9897\u7eff\u5b9d\u77f3");
        assertNotNull(okNumbers, "matching numeric multiset is accepted");
        assertEquals("\u6bcf 10 \u79d2\u4ea7\u51fa 1 \u9897\u7eff\u5b9d\u77f3", okNumbers.get(0).getString(),
                "translated numeric line keeps original values");
        if (DirectFormattedTranslationPipeline.restoreForTest(
                List.of(numeric), "tooltip.item_context.direct", "tooltip-block-context", false,
                "0|\u6bcf 1 \u79d2\u4ea7\u51fa 1 \u9897\u7eff\u5b9d\u77f3") != null) {
            throw new AssertionError("changed numeric values must fall back to the original line");
        }
    }

    private static void checkHudHistoryDefaultsAndContext() {
        assertEquals(Boolean.FALSE, ModConfig.HUD_TITLE_CONTEXT_ENABLED.get(), "title history context default off");
        assertEquals(1500, ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.get(), "caption batch interval default");
        assertEquals(4500, ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.get(), "caption collect window default");
        ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(10);
        ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(10);
        assertEquals(500L, HudTranslationHistory.batchIntervalMs(), "caption batch interval clamps low values");
        assertEquals(500L, HudTranslationHistory.collectWindowMs(), "caption collect window clamps low values");
        ModConfig.HUD_CAPTION_BATCH_INTERVAL_MS.set(1500);
        ModConfig.HUD_CAPTION_COLLECT_WINDOW_MS.set(4500);

        Component title = Component.literal("Chapter Two").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("Into the Woods").withStyle(ChatFormatting.YELLOW);
        String noContextPayload = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(title, subtitle), HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false, "");
        assertContains(noContextPayload, "[TEXT lines=2]", "caption batch wire header");
        if (noContextPayload.contains("<1>")) {
            throw new AssertionError("single-style caption lines should not carry style tags");
        }
        List<Component> plainFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(title, subtitle), HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false,
                "\u7b2c\u4e8c\u7ae0\n\u8fdb\u5165\u68ee\u6797");
        assertNotNull(plainFallback, "caption batch should recover same-count plain text response");
        assertEquals("\u7b2c\u4e8c\u7ae0", plainFallback.get(0).getString(), "caption plain fallback title");
        assertEquals("\u8fdb\u5165\u68ee\u6797", plainFallback.get(1).getString(), "caption plain fallback subtitle");
        String context = "Recent HUD caption history before the current batch\n1. [标题]\n   original: Old subtitle\n   translated: \u65e7\u5b57\u5e55";
        String contextPayload = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(title, subtitle), HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false, context);
        if (noContextPayload.contains("[CONTEXT]")) {
            throw new AssertionError("caption batch payload without context should not include a context section");
        }
        assertContains(contextPayload, "[CONTEXT]", "caption batch payload should include the context section when enabled");
        assertContains(contextPayload, "\u65e7\u5b57\u5e55", "caption batch payload should carry history context text");
        String sourceText = title.getString() + "\n" + subtitle.getString();
        String noContextKey = TranslationCacheKeys.key(HudTranslationHistory.BATCH_SURFACE, sourceText,
                "", "layout", "style");
        String contextKey = TranslationCacheKeys.key(HudTranslationHistory.BATCH_SURFACE, sourceText,
                context, "layout", "style");
        if (noContextKey.equals(contextKey)) {
            throw new AssertionError("caption batch context must isolate direct cache keys");
        }
    }

    private static void checkHudHistoryDedupeLimitAndContext() {
        HudTranslationHistory.clear();
        HudTranslationHistory.recordCaption(
                HudTranslationHistory.CaptionType.ACTIONBAR,
                "actionbar-1",
                "stable-radio-noise",
                Component.literal("[radio noise]"),
                Component.literal("[radio noise]"));
        HudTranslationHistory.recordCaption(
                HudTranslationHistory.CaptionType.ACTIONBAR,
                "actionbar-1",
                "stable-radio-noise-with-different-ctx",
                Component.literal("[radio noise]"),
                Component.literal("[radio noise]"));
        assertEquals(1, HudTranslationHistory.entriesSnapshot().size(),
                "same actionbar history key should create only one history item");
        assertEquals(1, HudTranslationHistory.pendingCountForTest(),
                "same actionbar source should create one pending request");
        long firstAllowedAt = HudTranslationHistory.nextBatchAllowedAtForTest();
        if (HudTranslationHistory.prepareBatchForTest(firstAllowedAt - 1) != null) {
            throw new AssertionError("caption batch must wait for the 1.5s throttle");
        }
        if (HudTranslationHistory.prepareBatchForTest(firstAllowedAt) != null) {
            throw new AssertionError("caption batch must collect nearby subtitle fragments before starting");
        }
        HudTranslationHistory.BatchSnapshot firstBatch = HudTranslationHistory.prepareBatchForTest(
                firstAllowedAt + HudTranslationHistory.collectWindowMs());
        assertNotNull(firstBatch, "caption batch starts after the collect window");
        assertEquals(1, firstBatch.historyKeys().size(), "one repeated actionbar source enters one batch");
        assertEquals(0, HudTranslationHistory.pendingCountForTest(), "in-flight caption is no longer pending");
        assertEquals(1, HudTranslationHistory.inFlightCountForTest(), "caption batch marks one item in-flight");
        if (HudTranslationHistory.prepareBatchForTest(firstAllowedAt + 10) != null) {
            throw new AssertionError("in-flight caption batch must not be started twice");
        }
        HudTranslationHistory.completeBatchForTest(firstBatch.batchId(), List.of(Component.literal("[无线电噪声]")));
        assertEquals(HudTranslationHistory.Status.DONE, HudTranslationHistory.entriesSnapshot().get(0).status(),
                "completed caption batch marks item done");
        assertEquals("[无线电噪声]", HudTranslationHistory.entriesSnapshot().get(0).translatedText(),
                "caption batch stores translated text");
        assertEquals(0, HudTranslationHistory.drainReadyChatEntriesForTest().size(),
                "caption chat history is disabled by default");
        ModConfig.HUD_HISTORY_CHAT_ENABLED.set(true);
        List<HudTranslationHistory.Entry> readyChatEntries = HudTranslationHistory.drainReadyChatEntriesForTest();
        assertEquals(1, readyChatEntries.size(), "caption chat history publishes completed translations when enabled");
        assertEquals("[无线电噪声]", readyChatEntries.get(0).translatedText(),
                "caption chat history publishes translated text");
        assertEquals(0, HudTranslationHistory.drainReadyChatEntriesForTest().size(),
                "caption chat history should not publish the same entry twice");
        ModConfig.HUD_HISTORY_CHAT_ENABLED.set(false);
        HudTranslationHistory.clear();

        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.ACTIONBAR,
                "actionbar-1", "source-1", Component.literal("First caption"), Component.literal("First caption"));
        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.ACTIONBAR,
                "actionbar-2", "source-2", Component.literal("Second caption"), Component.literal("Second caption"));
        HudTranslationHistory.completeCaptionForTest("actionbar-2", Component.literal("\u7b2c\u4e8c\u6761"));
        HudTranslationHistory.completeCaptionForTest("actionbar-1", Component.literal("\u7b2c\u4e00\u6761"));
        assertEquals("\u7b2c\u4e00\u6761", HudTranslationHistory.entriesSnapshot().get(0).translatedText(),
                "HUD history keeps source order when translations finish out of order");
        assertEquals("\u7b2c\u4e8c\u6761", HudTranslationHistory.entriesSnapshot().get(1).translatedText(),
                "later HUD source remains after earlier source");
        HudTranslationHistory.clear();

        ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(true);
        for (int i = 0; i < 13; i++) {
            HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.TITLE,
                    "previous-title-" + i,
                    "previous-source-" + i,
                    Component.literal("Previous original " + i),
                    Component.literal("Previous original " + i));
            HudTranslationHistory.completeCaptionForTest("previous-title-" + i,
                    Component.literal("\u65e7\u5b57\u5e55 " + i));
        }
        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.SUBTITLE,
                "current-subtitle",
                "current-subtitle-source",
                Component.literal("Current subtitle"),
                Component.literal("Current subtitle"));
        long contextAllowedAt = HudTranslationHistory.nextBatchAllowedAtForTest();
        HudTranslationHistory.BatchSnapshot contextBatch = HudTranslationHistory.prepareBatchForTest(
                contextAllowedAt + HudTranslationHistory.collectWindowMs());
        assertNotNull(contextBatch, "pending subtitle starts a caption batch");
        assertContains(contextBatch.context(), "Previous original 12", "caption context includes latest prior source");
        assertContains(contextBatch.context(), "\u65e7\u5b57\u5e55 12", "caption context includes latest prior translation");
        if (contextBatch.context().contains("Previous original 0")) {
            throw new AssertionError("caption context should keep only recent 12 prior entries");
        }
        if (contextBatch.context().contains("Current subtitle")) {
            throw new AssertionError("caption context must not include the current batch");
        }
        HudTranslationHistory.completeBatchForTest(contextBatch.batchId(), List.of(Component.literal("\u5f53\u524d\u5b57\u5e55")));
        HudTranslationHistory.clear();
        ModConfig.HUD_TITLE_CONTEXT_ENABLED.set(false);

        for (int i = 0; i < HudTranslationHistory.MAX_BATCH_CAPTIONS + 3; i++) {
            HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.ACTIONBAR,
                    "batch-actionbar-" + i,
                    "batch-source-" + i,
                    Component.literal("Batch line " + i),
                    Component.literal("Batch line " + i));
        }
        long batchAllowedAt = HudTranslationHistory.nextBatchAllowedAtForTest();
        HudTranslationHistory.BatchSnapshot largeBatch = HudTranslationHistory.prepareBatchForTest(batchAllowedAt);
        assertNotNull(largeBatch, "large caption batch starts");
        assertEquals(HudTranslationHistory.MAX_BATCH_CAPTIONS, largeBatch.historyKeys().size(),
                "caption batch sends at most 12 entries");
        assertEquals(3, HudTranslationHistory.pendingCountForTest(), "extra captions wait for the next batch");
        HudTranslationHistory.completeBatchForTest(largeBatch.batchId(), largeBatch.components());
        long secondAllowedAt = HudTranslationHistory.nextBatchAllowedAtForTest();
        if (HudTranslationHistory.prepareBatchForTest(secondAllowedAt - 1) != null) {
            throw new AssertionError("second caption batch must also obey throttle");
        }
        HudTranslationHistory.BatchSnapshot secondBatch = HudTranslationHistory.prepareBatchForTest(
                secondAllowedAt + HudTranslationHistory.collectWindowMs());
        assertNotNull(secondBatch, "second caption batch starts after throttle");
        assertEquals(3, secondBatch.historyKeys().size(), "remaining captions enter second batch");
        HudTranslationHistory.completeBatchForTest(secondBatch.batchId(), secondBatch.components());
        HudTranslationHistory.clear();

        int historyTotal = HudTranslationHistory.MAX_HISTORY_ENTRIES + 2;
        for (int i = 0; i < historyTotal; i++) {
            String key = "title-" + i;
            HudTranslationHistory.recordCaption(
                    HudTranslationHistory.CaptionType.TITLE,
                    key,
                    "title-source-" + i,
                    Component.literal("Original " + i),
                    Component.literal("Original " + i));
            HudTranslationHistory.completeCaptionForTest(key, Component.literal("\u6807\u9898 " + i));
        }
        assertEquals(HudTranslationHistory.MAX_HISTORY_ENTRIES, HudTranslationHistory.entriesSnapshot().size(),
                "history keeps configured max entries");
        int latestHistoryIndex = historyTotal - 1;
        int oldestKeptHistoryIndex = historyTotal - HudTranslationHistory.MAX_HISTORY_ENTRIES;
        assertEquals("\u6807\u9898 " + oldestKeptHistoryIndex,
                HudTranslationHistory.entriesSnapshot().get(0).translatedText(), "history entries keep source order");
        assertEquals("\u6807\u9898 " + latestHistoryIndex,
                HudTranslationHistory.entriesSnapshot().get(HudTranslationHistory.entriesSnapshot().size() - 1).translatedText(),
                "latest history entry remains last in source order");

        HudTranslationHistory.recordCaption(
                HudTranslationHistory.CaptionType.TITLE,
                "title-" + latestHistoryIndex,
                "title-source-duplicate",
                Component.literal("Original " + latestHistoryIndex),
                Component.literal("Original " + latestHistoryIndex));
        assertEquals(HudTranslationHistory.MAX_HISTORY_ENTRIES, HudTranslationHistory.entriesSnapshot().size(),
                "duplicate title history entry should not grow list");
        HudTranslationHistory.clear();
        assertEquals(0, HudTranslationHistory.entriesSnapshot().size(), "history clear removes entries");
        assertEquals(0, HudTranslationHistory.pendingCountForTest(), "cleared history has no pending captions");
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
        assertContains(request, "[CONTEXT]", "direct request should include explicit context when provided");
        assertContains(request, "Surrounding sign/book context should be visible to the model.",
                "direct context content should be sent to the model");
        assertContains(request, "[TEXT lines=1]", "direct request should include the wire document");
        if (request.indexOf("[CONTEXT]") > request.indexOf("[TEXT lines=1]")) {
            throw new AssertionError("context should precede the wire document");
        }
        // The whole cross-run sentence travels exactly once, as one tagged line.
        assertContains(request, "<1>[ Tip ]: Most Enemies cannot attack </1>",
                "tagged line keeps the leading styled run");
        assertContains(request, "<4>Siege Enemies</4>", "tagged line keeps the styled siege run");
        int firstBody = request.indexOf("Most Enemies cannot attack");
        int lastBody = request.lastIndexOf("Most Enemies cannot attack");
        if (firstBody != lastBody) {
            throw new AssertionError("source text must be sent only once (no duplicated plain context)");
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
                .replace("0|Armulet Amulet Slot", "0|\u81c2\u73af\u62a4\u7b26\u69fd")
                .replace("1|Place a Armulet Amulet here to receive its When Worn bonuses and activate its Ability.",
                        "1|\u5728\u6b64\u653e\u7f6e\u81c2\u73af\u62a4\u7b26\uff0c\u4ee5\u83b7\u5f97\u5176\u7a7f\u6234\u65f6\u7684\u52a0\u6210\u5e76\u6fc0\u6d3b\u5176\u80fd\u529b\u3002");
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
        assertContains(request, "[GLOSSARY]", "status glossary section");
        assertContains(request, "Loaded -> \u5df2\u52a0\u8f7d", "loaded glossary term");

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

        String translated = document.replace("0|Plain English Sentence", "0|Plain English Sentence bonus");
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
        assertContains(key, "direct:v18-mintag:chat.system.direct:", "system chat uses isolated direct cache surface");
        assertEquals("chat", TranslationCacheKeys.laneFromKey(key), "system chat direct lane");
    }

    private static void checkChatContextConfigAndPayload() throws Exception {
        assertEquals(6, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get(), "chat context message count default");
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(-5);
        assertEquals(0, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get(), "chat context count clamps low");
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(25);
        assertEquals(20, ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.get(), "chat context count clamps high");
        ModConfig.CHAT_CONTEXT_MESSAGE_COUNT.set(6);
        assertEquals(1500, ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.get(), "chat context batch interval default");
        ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.set(100);
        assertEquals(500, ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.get(), "chat context batch interval clamps low");
        ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.set(20000);
        assertEquals(10000, ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.get(), "chat context batch interval clamps high");
        ModConfig.CHAT_CONTEXT_BATCH_INTERVAL_MS.set(1500);
        assertEquals(4500, ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.get(), "chat context collect window default");
        ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.set(100);
        assertEquals(500, ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.get(), "chat context collect window clamps low");
        ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.set(60000);
        assertEquals(30000, ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.get(), "chat context collect window clamps high");
        ModConfig.CHAT_CONTEXT_COLLECT_WINDOW_MS.set(4500);

        List<String> context = List.of(
                "<Guide> The caves are unsafe.",
                "<Player> What should I bring?",
                "<Guide> Take a lantern.");
        String payload = ChatTranslationRuntime.contextText(context, 2);
        assertContains(payload, "oldest to newest", "chat context payload order instruction");
        assertContains(payload, "1. [previous] <Guide> The caves are unsafe.", "chat context includes first previous line");
        assertContains(payload, "3. [target] <Guide> Take a lantern.", "chat context marks target line");
        String keyWithoutContext = TranslationCacheKeys.key(ChatTranslationRuntime.CHAT_CONTEXT_SURFACE,
                context.get(2), "", "chat-context:v2:target=0:count=1", "chat-context");
        String keyWithContext = TranslationCacheKeys.key(ChatTranslationRuntime.CHAT_CONTEXT_SURFACE,
                context.get(2), payload, "chat-context:v2:target=2:count=3", "chat-context");
        if (keyWithoutContext.equals(keyWithContext)) {
            throw new AssertionError("chat context payload and target index must isolate direct cache keys");
        }

        List<Component> batch = List.of(
                Component.literal("<Guide> The caves are unsafe."),
                Component.literal("<Player> What should I bring?"),
                Component.literal("<Guide> Take a lantern."));
        String batchContext = "Recent chat history before the current batch.\n"
                + "1. chat\n"
                + "   original: <Guide> Watch the dark corners.\n"
                + "   translated: [pending]";
        String batchPayload = DirectFormattedTranslationPipeline.requestPayloadForTest(
                batch, ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false, batchContext);
        assertContains(batchPayload, "[CONTEXT]", "chat context batch payload includes prior history context");
        assertContains(batchPayload, "[TEXT lines=3]", "chat context batch sends all pending chat lines in one document");
        assertContains(batchPayload, "0|<Guide> The caves are unsafe.", "chat context batch includes first pending line");
        assertContains(batchPayload, "2|<Guide> Take a lantern.", "chat context batch includes last pending line");
        List<Component> restoredBatch = DirectFormattedTranslationPipeline.restoreForTest(
                batch, ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false,
                "0|\u6d1e\u7a74\u5f88\u5371\u9669\u3002\n"
                        + "1|\u6211\u8be5\u5e26\u4ec0\u4e48\uff1f\n"
                        + "2|\u5e26\u4e00\u76cf\u706f\u3002");
        assertNotNull(restoredBatch, "chat context batch must restore a same-count multi-line response");
        assertEquals(3, restoredBatch.size(), "chat context batch restored line count");
        assertEquals("\u5e26\u4e00\u76cf\u706f\u3002", restoredBatch.get(2).getString(),
                "chat context batch preserves response order");

        try {
            Path tempDir = Files.createTempDirectory("simpletranslate-chat-cache");
            TranslationCache cache = new TranslationCache(tempDir.resolve("translations.json"));
            setStaticField(SimpleTranslateMod.class, "translationCache", cache);
            Component source = Component.literal("\u226b [Siege] \u2192 \u2714 Loaded").withStyle(ChatFormatting.GRAY);
            Component translated = Component.literal("\u226b [\u653b\u57ce] \u2192 \u2714 \u5df2\u52a0\u8f7d").withStyle(ChatFormatting.GRAY);
            boolean stored = DirectFormattedTranslationPipeline.cacheRestoredTranslation(
                    source, translated, ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE, "chat-context-batch", false,
                    batchContext);
            if (!stored) {
                throw new AssertionError("chat context batch should store per-message cache with its real context");
            }
            DirectFormattedTranslationPipeline.ComponentListResult emptyContextCached =
                    DirectSurfaceTranslator.getCachedComponents(
                            List.of(source), ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE,
                            "chat-context-batch", false, "");
            if (emptyContextCached == null || !emptyContextCached.translated
                    || emptyContextCached.components == null || emptyContextCached.components.isEmpty()) {
                throw new AssertionError("chat context batch should reuse same-source cache when context is empty after rejoining");
            }
            assertEquals("\u226b [\u653b\u57ce] \u2192 \u2714 \u5df2\u52a0\u8f7d",
                    emptyContextCached.components.get(0).getString(),
                    "chat context batch empty-context compatible cache lookup should restore text");
            DirectFormattedTranslationPipeline.ComponentListResult cached =
                    DirectSurfaceTranslator.getCachedComponents(
                            List.of(source), ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE,
                            "chat-context-batch", false, batchContext);
            assertNotNull(cached, "chat context batch real-context cache lookup");
            if (!cached.translated || cached.components == null || cached.components.isEmpty()) {
                throw new AssertionError("chat context batch real-context cache lookup should translate");
            }
            assertEquals("\u226b [\u653b\u57ce] \u2192 \u2714 \u5df2\u52a0\u8f7d", cached.components.get(0).getString(),
                    "chat context batch cached real-context message text");
        } finally {
            setStaticField(SimpleTranslateMod.class, "translationCache", null);
        }
    }

    private static void checkChatLineTextFallbackAndStyledPrefix() {
        Component source = Component.literal("<baokaixin> [Quest] The Ember Key is inactive.")
                .withStyle(ChatFormatting.GRAY);
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_CONTEXT_SURFACE, "chat-text", false,
                ChatTranslationRuntime.contextText(List.of(
                        "<baokaixin> [Hint] Find the old forge.",
                        "<baokaixin> [Quest] The Ember Key is inactive."), 1));
        assertContains(request, "[TEXT lines=1]", "single chat line wire header");
        if (request.contains("<1>")) {
            throw new AssertionError("single-style chat line must not carry style tags");
        }

        // A bare plain-text answer without the i| prefix still recovers for one line.
        String malformed = "[\u4efb\u52a1] \u4f59\u70ec\u94a5\u5319\u672a\u6fc0\u6d3b\u3002";
        List<Component> recovered = DirectFormattedTranslationPipeline.restoreWithPlainFallbackForTest(
                List.of(source), ChatTranslationRuntime.CHAT_CONTEXT_SURFACE, "chat-text", false, malformed);
        assertNotNull(recovered, "chat context should recover an unnumbered single-line model response");
        assertEquals("[\u4efb\u52a1] \u4f59\u70ec\u94a5\u5319\u672a\u6fc0\u6d3b\u3002",
                recovered.get(0).getString(), "chat context malformed fallback text");

        Component styledChat = Component.empty()
                .append(Component.literal("<baokaixin> ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("[Quest] The Ember Key is inactive.").withStyle(ChatFormatting.WHITE));
        String styledRequest = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(styledChat), ChatTranslationRuntime.CHAT_CONTEXT_SURFACE, "chat-text", false,
                ChatTranslationRuntime.contextText(List.of(
                        "<baokaixin> [Hint] Find the old forge.",
                        "<baokaixin> [Quest] The Ember Key is inactive."), 1));
        assertContains(styledRequest, "<1><baokaixin> </1>", "multi-run chat line tags the styled prefix");
        assertContains(styledRequest, "<2>[Quest] The Ember Key is inactive.</2>",
                "multi-run chat line tags the styled body");

        String styledDocument = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(styledChat), ChatTranslationRuntime.CHAT_CONTEXT_SURFACE, "chat-text", false);
        String styledTranslated = styledDocument.replace("<2>[Quest] The Ember Key is inactive.</2>",
                "<2>[\u4efb\u52a1] \u4f59\u70ec\u94a5\u5319\u672a\u6fc0\u6d3b\u3002</2>");
        Component applied = ChatTranslationRuntime.applyFullTranslation(styledChat, styledTranslated);
        assertNotNull(applied, "chat context full-line translation should keep styled player prefix instead of dropping the message");
        assertEquals("<baokaixin> [\u4efb\u52a1] \u4f59\u70ec\u94a5\u5319\u672a\u6fc0\u6d3b\u3002",
                applied.getString(), "chat styled prefix application");
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(applied, segments, Style.EMPTY, true);
        if (segments.size() < 2) {
            throw new AssertionError("styled chat prefix application should keep prefix and body segments");
        }
        assertEquals(ChatFormatting.DARK_GRAY.getColor(), segments.get(0).style.getColor().getValue(),
                "styled chat prefix color survives context application");
        assertEquals(ChatFormatting.WHITE.getColor(), segments.get(1).style.getColor().getValue(),
                "styled chat body color survives context application");

        Component iconSystemLine = Component.empty()
                .append(Component.literal("\u2716 ").withStyle(ChatFormatting.RED))
                .append(Component.literal("These special weapons have unique traits and effects of their own.")
                        .withStyle(ChatFormatting.WHITE));
        List<Component> iconFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(iconSystemLine), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false,
                "\u8fd9\u4e9b\u7279\u6b8a\u6b66\u5668\u62e5\u6709\u5404\u81ea\u72ec\u7279\u7684\u7279\u6027\u548c\u6548\u679c\u3002");
        assertNotNull(iconFallback, "system chat plain fallback should recover one editable body with icon prefix");
        assertEquals("\u2716 \u8fd9\u4e9b\u7279\u6b8a\u6b66\u5668\u62e5\u6709\u5404\u81ea\u72ec\u7279\u7684\u7279\u6027\u548c\u6548\u679c\u3002",
                iconFallback.get(0).getString(), "system chat plain fallback keeps icon prefix");
    }

    private static void checkTranslateComponentFixedLayout() {
        Component source = Component.literal("Boss Name").withStyle(ChatFormatting.GOLD);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "entity.name.direct", "entity-name",
                DirectSurfaceTranslator.isFixedLayoutSurface("entity.name.direct"));
        assertContains(document, "[TEXT lines=1]", "entity name wire header");
        assertContains(document, "0|Boss Name", "single-style entity line travels untagged");
        if (document.contains("<1>")) {
            throw new AssertionError("single-style entity line must not carry style tags");
        }
    }

    private static void checkTranslatableArgStyles() {
        Component source = Component.translatable(
                "%s",
                Component.literal("HP").withStyle(ChatFormatting.RED));
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(source, segments, Style.EMPTY, true);
        boolean foundRedHp = false;
        for (TextSegmentInfo segment : segments) {
            if ("HP".equals(segment.text) && segment.style != null && segment.style.getColor() != null
                    && segment.style.getColor().equals(TextColor.fromLegacyFormat(ChatFormatting.RED))) {
                foundRedHp = true;
                break;
            }
        }
        if (!foundRedHp) {
            throw new AssertionError("translatable args must preserve styled literal segments");
        }

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false);
        String translated = document.replace("0|HP", "0|\u751f\u547d");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false, translated);
        if (restored == null || restored.isEmpty()) {
            throw new AssertionError("translatable arg styled document should restore");
        }
        List<TextSegmentInfo> restoredSegments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(restored.get(0), restoredSegments, Style.EMPTY, true);
        boolean foundRedLife = false;
        for (TextSegmentInfo segment : restoredSegments) {
            if ("\u751f\u547d".equals(segment.text) && segment.style != null && segment.style.getColor() != null
                    && segment.style.getColor().equals(TextColor.fromLegacyFormat(ChatFormatting.RED))) {
                foundRedLife = true;
                break;
            }
        }
        if (!foundRedLife) {
            throw new AssertionError("translatable arg styled segment should survive direct restore");
        }
    }

    private static void checkStyleSignatureFontAndEvents() {
        Component source = Component.empty()
                .append(Component.literal("A").withStyle(Style.EMPTY.withFont(new net.minecraft.resources.ResourceLocation("minecraft", "default"))))
                .append(Component.literal("B").withStyle(Style.EMPTY.withFont(new net.minecraft.resources.ResourceLocation("minecraft", "alt"))));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "tooltip.item.direct", "tooltip", false);
        assertContains(document, "<1>A</1>", "different fonts must keep separate styled tags");
        assertContains(document, "<2>B</2>", "different fonts must keep separate styled tags");

        Component clickSource = Component.empty()
                .append(Component.literal("LinkA").withStyle(Style.EMPTY.withClickEvent(
                        new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, "https://a.example"))))
                .append(Component.literal("LinkB").withStyle(Style.EMPTY.withClickEvent(
                        new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, "https://b.example"))));
        String clickDocument = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(clickSource), "tooltip.item.direct", "tooltip", false);
        assertContains(clickDocument, "<1>LinkA</1>", "different click payloads must not merge runs");
        assertContains(clickDocument, "<2>LinkB</2>", "different click payloads must not merge runs");
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
        assertContains(request, "[CONTEXT]", "item tooltip request should include semantic block context");
        assertContains(request, "line 1 [lore]: A certificate stating one's achievement",
                "item tooltip request should expose lore context");
        assertContains(request, "[TEXT lines=4]", "item tooltip wire header keeps original line slots");
        if (request.contains("<1>")) {
            throw new AssertionError("single-style tooltip lines must not carry style tags");
        }

        String sourceText = String.join("\n",
                source.get(0).getString(), source.get(1).getString(), source.get(2).getString(), source.get(3).getString());
        String legacyKey = TranslationCacheKeys.key("tooltip.item.direct", sourceText, context, "layout", "style");
        String contextKey = TranslationCacheKeys.key(
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE, sourceText, context, "layout", "style");
        if (legacyKey.equals(contextKey)) {
            throw new AssertionError("item context tooltip cache key must not collide with legacy tooltip.item.direct");
        }
        assertContains(contextKey, "direct:v18-mintag:tooltip.item_context.direct:",
                "item context cache key should include the new surface");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        String translated = document
                .replace("0|Acknowledgement Of Excellence", "0|\u5353\u8d8a\u8bc1\u660e")
                .replace("1|A certificate stating one's achievement, acknowledged by a few organizations around the world.",
                        "1|\u4e00\u4efd\u8bc1\u660e\u6301\u6709\u8005\u6210\u5c31\u5e76\u83b7\u5f97\u4e16\u754c\u5404\u5730\u5c11\u6570\u7ec4\u7ec7\u8ba4\u53ef\u7684\u8bc1\u4e66\u3002")
                .replace("2|Used for Special Trades.", "2|\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002")
                .replace("3|Press SHIFT for details.", "3|\u6309 SHIFT \u67e5\u770b\u8be6\u60c5\u3002");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        if (restored == null || restored.size() != source.size()) {
            throw new AssertionError("item context tooltip restore must preserve the original line count");
        }
        assertEquals("\u5353\u8d8a\u8bc1\u660e", restored.get(0).getString(), "item context title restore");
        assertEquals("\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002", restored.get(2).getString(), "item context usage restore");
        assertContains(restored.get(3).getString(), "SHIFT", "item context hotkey should preserve key token");

        List<Component> numericTooltip = List.of(
                Component.literal("Crystallisation").withStyle(ChatFormatting.AQUA),
                Component.literal("This Pylon produces 1 Emerald every 10 Seconds, and give it to a random player.")
                        .withStyle(ChatFormatting.GOLD)
        );
        String numericRequest = DirectFormattedTranslationPipeline.requestPayloadForTest(
                numericTooltip,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE,
                false,
                TooltipTranslationHelper.buildItemTooltipContext(numericTooltip));
        assertContains(numericRequest, "1 Emerald every 10 Seconds",
                "item tooltip numeric values travel as literal digits");
        String numericDocument = DirectFormattedTranslationPipeline.serializeForTest(
                numericTooltip, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(numericDocument, "This Pylon produces 1 Emerald every 10 Seconds",
                "item tooltip wire document keeps literal numbers");
        String numericTranslated = numericDocument
                .replace("0|Crystallisation", "0|\u7ed3\u6676\u5316")
                .replace("1|This Pylon produces 1 Emerald every 10 Seconds, and give it to a random player.",
                        "1|\u6b64\u80fd\u91cf\u5854\u6bcf 10 \u79d2\u4ea7\u751f 1 \u9897\u7eff\u5b9d\u77f3\uff0c\u5e76\u4ea4\u7ed9\u4e00\u540d\u968f\u673a\u73a9\u5bb6\u3002");
        List<Component> numericRestored = DirectFormattedTranslationPipeline.restoreForTest(
                numericTooltip, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, numericTranslated);
        assertNotNull(numericRestored, "item tooltip numeric restore");
        assertContains(numericRestored.get(1).getString(), "\u6bcf 10 \u79d2\u4ea7\u751f 1 \u9897\u7eff\u5b9d\u77f3",
                "item tooltip numeric restore keeps original values in translated order");
        // A line whose digits changed falls back to the original English line.
        List<Component> numericBad = DirectFormattedTranslationPipeline.restoreForTest(
                numericTooltip, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false,
                numericTranslated.replace("\u6bcf 10 \u79d2", "\u6bcf 1 \u79d2"));
        assertNotNull(numericBad, "title still translates when the numeric line is guarded");
        assertEquals("This Pylon produces 1 Emerald every 10 Seconds, and give it to a random player.",
                numericBad.get(1).getString(),
                "number guard keeps the original line instead of showing wrong values");
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
        assertContains(request, "0|" + fullwidth, "fullwidth line travels on the wire");
        assertContains(request, "[NORMALIZED]", "fullwidth request should include the normalized hint section");
        assertContains(request, "ATTEMPTING NETWORK RECONNECTION", "normalized hint should expose readable Latin text");
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
        String originalStylePrompt = ModConfig.TRANSLATION_STYLE_PROMPT.get();
        try {
            ModConfig.TRANSLATION_STYLE_PROMPT.set("short and poetic");
            String styledPair = TranslationTextDetector.languagePairKey();
            if (defaultPair.equals(styledPair) || !styledPair.contains("short and poetic")) {
                throw new AssertionError("style prompt should change translation cache language/style key");
            }
        } finally {
            ModConfig.TRANSLATION_STYLE_PROMPT.set(originalStylePrompt);
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

    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
            throw new AssertionError(label + ": expected a non-null value");
        }
    }

    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
'@

    [System.IO.File]::WriteAllText($sourceFile, $javaSource, [System.Text.UTF8Encoding]::new($false))

    $classes = Join-Path $ProjectDir "build\classes\java\main"
    $resources = Join-Path $ProjectDir "build\resources\main"
    $classpathFile = Join-Path $tempDir "compile-classpath.txt"
    $initScript = Join-Path $tempDir "print-classpath.init.gradle"
    $classpathFileForGradle = $classpathFile.Replace("\", "/").Replace("'", "\'")
    $initSource = @"
allprojects {
    tasks.register("simpleTranslatePrintCompileClasspath") {
        doLast {
            def sourceSets = project.extensions.findByName("sourceSets")
            if (sourceSets != null) {
                new File('$classpathFileForGradle').text = sourceSets.main.compileClasspath.asPath
            }
        }
    }
}
"@
    [System.IO.File]::WriteAllText($initScript, $initSource, [System.Text.UTF8Encoding]::new($false))
    & .\gradlew.bat -q --no-daemon -I $initScript simpleTranslatePrintCompileClasspath
    if ($LASTEXITCODE -ne 0) {
        throw "classpath export failed with exit code $LASTEXITCODE"
    }
    $gradleClasspath = (Get-Content -Raw -Path $classpathFile).Trim()
    $minecraftVersion = (Get-Content -Path (Join-Path $ProjectDir "gradle.properties") |
        Where-Object { $_ -match "^minecraft_version=(.+)$" } |
        ForEach-Object { $Matches[1].Trim() } |
        Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($minecraftVersion)) {
        throw "Could not read minecraft_version from gradle.properties"
    }
    $minecraftJarEntries = @()
    $loomMinecraftRoot = Join-Path $ProjectDir ".gradle\loom-cache\minecraftMaven\net\minecraft"
    if (Test-Path $loomMinecraftRoot) {
        $minecraftJar = Get-ChildItem -Path $loomMinecraftRoot -Recurse -Filter "*$minecraftVersion*.jar" |
            Where-Object { $_.Name -like "minecraft-merged*" -and $_.Name -notmatch "sources|javadoc" } |
            Select-Object -First 1
        if (-not $minecraftJar) {
            throw "Could not find the remapped Minecraft $minecraftVersion jar"
        }
        $minecraftJarEntries = @($minecraftJar.FullName)
    }
    $remappedModJars = @()
    $remappedRoot = Join-Path $ProjectDir ".gradle\loom-cache\remapped_mods\remapped"
    if (Test-Path $remappedRoot) {
        $remappedModJars = Get-ChildItem -Path $remappedRoot -Recurse -Filter "*.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            ForEach-Object { $_.FullName }
    }
    $classpath = @($classes, $resources) + $minecraftJarEntries + $remappedModJars + @($gradleClasspath) -join ";"

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $argClasspath = $classpath -replace '\\', '/'
    $argTempDir = $tempDir -replace '\\', '/'
    $argSourceFile = $sourceFile -replace '\\', '/'
    $javacArgs = Join-Path $tempDir "javac.args"
    $javacArgLines = @(
        "-encoding"
        "UTF-8"
        "-cp"
        "`"$argClasspath`""
        "-d"
        "`"$argTempDir`""
        "`"$argSourceFile`""
    )
    [System.IO.File]::WriteAllLines($javacArgs, $javacArgLines, $utf8NoBom)
    & javac "@$javacArgs"
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    $shortClasspathDir = Join-Path $tempDir "cp"
    New-Item -ItemType Directory -Path $shortClasspathDir -Force | Out-Null
    $jarIndex = 0
    foreach ($entry in $classpath.Split(";")) {
        if ([string]::IsNullOrWhiteSpace($entry) -or -not $entry.EndsWith(".jar")) {
            continue
        }
        if (-not (Test-Path -LiteralPath $entry)) {
            continue
        }
        $targetJar = Join-Path $shortClasspathDir ("lib{0:D4}.jar" -f $jarIndex)
        Copy-Item -LiteralPath $entry -Destination $targetJar -Force
        $jarIndex++
    }
    $shortClasspath = @($classes, $resources, (Join-Path $shortClasspathDir "*"), $tempDir) -join ";"
    & java -cp $shortClasspath SimpleTranslateLogicChecks
    if ($LASTEXITCODE -ne 0) {
        throw "logic checks failed with exit code $LASTEXITCODE"
    }

    function Assert-FileContains {
        param([string]$Path, [string]$Needle, [string]$Label)
        $content = Get-Content -Raw -Encoding UTF8 -Path $Path
        if (-not $content.Contains($Needle)) {
            throw "${Label}: expected $Path to contain <$Needle>"
        }
    }

    function Assert-FileNotContains {
        param([string]$Path, [string]$Needle, [string]$Label)
        $content = Get-Content -Raw -Encoding UTF8 -Path $Path
        if ($content.Contains($Needle)) {
            throw "${Label}: expected $Path not to contain <$Needle>"
        }
    }

    function Assert-FileNotRegex {
        param([string]$Path, [string]$Pattern, [string]$Label)
        $content = Get-Content -Raw -Encoding UTF8 -Path $Path
        if ([regex]::IsMatch($content, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)) {
            throw "${Label}: expected $Path not to match <$Pattern>"
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
    $queueFile = Join-Path $src "translation\TranslationRequestQueue.java"
    $directFile = Join-Path $src "util\DirectFormattedTranslationPipeline.java"
    $modFile = Join-Path $src "SimpleTranslateMod.java"
    $cacheFile = Join-Path $src "cache\TranslationCache.java"
    $cacheManagerScreenFile = Join-Path $src "gui\CacheManagerScreen.java"
    $cacheEditScreenFile = Join-Path $src "gui\CacheEditScreen.java"
    $sharedClientFile = Join-Path $src "network\SharedCacheClient.java"
    $sharedServerFile = Join-Path $src "network\SharedCacheServer.java"
    $sharedStoreFile = Join-Path $src "network\SharedCacheStore.java"
    $sharedPayloadFile = Join-Path $src "network\SharedCachePayload.java"
    $resourceDir = Join-Path $ProjectDir "src\main\resources"
    $mixinConfigFile = Join-Path $resourceDir "simple_translate.mixins.json"
    $modsTomlFile = Join-Path $resourceDir "META-INF\mods.toml"
    $enLangFile = Join-Path $resourceDir "assets\simple_translate\lang\en_us.json"
    $zhLangFile = Join-Path $resourceDir "assets\simple_translate\lang\zh_cn.json"
    $textDisplayMixin = Join-Path $src "mixin\TextDisplayMixin.java"
    $textDisplayScreen = Join-Path $src "gui\TextDisplayTranslationScreen.java"

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
    Assert-FileContains $modFile "cache_settings.json" "cache share settings must be stored per world/server cache scope"
    Assert-FileContains $modFile "isCacheServerShareEnabled()" "shared-cache client must read the active cache scope setting"
    Assert-FileContains $modsTomlFile 'clientSideOnly=false' "Forge metadata must allow the shared-cache server bridge to load"
    Assert-FileContains $modsTomlFile 'side="BOTH"' "Forge metadata dependencies must not be client-only"
    Assert-FileContains $modFile "SharedCacheServer.register()" "Forge entrypoint must register the shared cache server bridge"
    Assert-FileContains $sharedPayloadFile "cache_sync/v1" "shared cache networking must use a stable v1 payload id"
    Assert-FileContains $sharedPayloadFile "MAX_ENTRIES_PER_PACKET = 64" "shared cache packets must be bounded"
    Assert-FileContains $sharedServerFile "simple_translate_shared_cache.json" "server shared cache must persist as one world-root file"
    Assert-FileContains $sharedServerFile "putMissing" "server shared cache must only add missing cache entries"
    Assert-FileContains $sharedClientFile "remoteImporting" "client shared cache import must not upload remote entries again"
    Assert-FileContains $sharedClientFile "enqueueLocalSnapshot" "client shared cache must upload existing local cache when sharing starts"
    Assert-FileContains $sharedClientFile "entry.sharedImported()" "client shared cache snapshots must skip entries imported from remote peers"
    Assert-FileContains $sharedClientFile "lastSnapshotQueued" "cache manager must be able to show shared-cache snapshot status"
    Assert-FileContains $sharedClientFile "uploadedEntries" "cache manager must be able to show sent shared-cache entries"
    Assert-FileContains $sharedClientFile "receivedEntries" "cache manager must be able to show received shared-cache entries"
    Assert-FileContains $sharedClientFile "putSharedIfAbsent" "client shared cache import must only add local missing entries"
    Assert-FileContains $sharedClientFile "SimpleTranslateMod.isCacheServerShareEnabled()" "client shared cache must use active world/server settings"
    Assert-FileNotContains $sharedClientFile "CACHE_SERVER_SHARE_ENABLED" "client shared cache must not use the old global share toggle"
    Assert-FileContains $sharedStoreFile "putMissing" "shared cache store must preserve existing entries"
    Assert-FileContains $cacheFile "updateEditableTranslationText" "translation cache must support editable in-game cache updates"
    Assert-FileContains $cacheFile "putSharedIfAbsent" "translation cache must support server shared cache import without overwrite"
    Assert-FileContains $cacheFile "SharedCacheClient.enqueueLocalEntry" "new local cache entries must enqueue for server sharing"
    Assert-FileContains $cacheFile "sharedImported" "translation cache must remember remote shared-cache origin to avoid sync loops"
    Assert-FileNotContains $cacheFile "deleteLocal" "player-facing cache deletion path must not return"
    Assert-FileNotContains $cacheFile "_deleted_shared_keys.json" "player-deleted shared cache tombstones must not remain"
    Assert-FileContains $cacheFile "sourceText" "translation cache entries must store readable source text"
    Assert-FileContains $cacheFile "translationText" "translation cache entries must store readable translated text"
    Assert-FileContains $cacheFile "removeFixedTextSegments" "cache editing must preserve fixed direct-format runs"
    Assert-FileContains $cacheFile "exportShareArchive" "translation cache must export save-named zip share archives"
    Assert-FileNotContains $cacheFile "exportSharePackage" "translation cache must not keep the old folder-export path"
    Assert-FileNotContains $cacheFile "clearDirectoryContents" "translation cache must not clear or recreate share folders"
    Assert-FileContains $cacheFile "CacheShareMetadata" "cache share archives must record current world/server metadata"
    Assert-FileContains $cacheFile "discoverImportSources" "translation cache must discover player-dropped import sources"
    Assert-FileContains $cacheFile "importFromShareSources" "translation cache must merge share packages into the active cache"
    Assert-FileContains $cacheFile "skippedExisting" "translation cache share import must keep local entries on conflict"
    Assert-FileContains $cacheFile "skippedWorldMismatch" "cache share import must reject archives for a different save/server"
    Assert-FileContains $cacheManagerScreenFile "new CacheEditScreen" "cache manager must open the cache edit screen"
    Assert-FileContains $cacheManagerScreenFile "screen.simple_translate.cache.edit.tooltip" "cache edit action must have delayed tooltip text"
    Assert-FileContains $cacheManagerScreenFile "new EditBox" "cache manager must expose a search field"
    Assert-FileContains $cacheManagerScreenFile "screen.simple_translate.cache.search.tooltip" "cache search field must have delayed tooltip text"
    Assert-FileContains $cacheManagerScreenFile "exportShareArchive" "cache manager export must create a save-named zip share archive"
    Assert-FileContains $cacheManagerScreenFile "safeFilePart" "cache manager export file name must include a safe world/server name"
    Assert-FileNotContains $cacheManagerScreenFile 'resolve("cache_share")' "cache manager export must not create an extra cache_share folder"
    Assert-FileNotContains $cacheManagerScreenFile '"cache_export.json"' "cache manager export must not create an extra legacy export file"
    Assert-FileNotContains $cacheManagerScreenFile "clearDirectoryContents" "cache manager export must not clear or recreate share folders"
    Assert-FileContains $cacheManagerScreenFile "TinyFileDialogs.tinyfd_openFileDialog" "cache manager import must open a zip/json file picker"
    Assert-FileContains $cacheManagerScreenFile "TinyFileDialogs.tinyfd_selectFolderDialog" "cache manager import must open a folder picker"
    Assert-FileContains $cacheManagerScreenFile "cache.importFromShareSources" "cache manager import must use the active-cache share importer"
    Assert-FileContains $cacheManagerScreenFile "screen.simple_translate.cache.import.done" "cache manager import must show in-game import statistics"
    Assert-FileContains $cacheManagerScreenFile "serverShareStatus" "cache manager must show shared-cache connection and transfer status"
    Assert-FileContains $cacheManagerScreenFile "SimpleTranslateMod.setCacheServerShareEnabled(enabled)" "cache manager share toggle must write the active world/server setting"
    Assert-FileNotContains $cacheManagerScreenFile "deleteSelectedEntry" "cache manager must not expose cache delete action"
    Assert-FileNotContains $cacheManagerScreenFile "clearSelectedLane" "cache manager must not expose category clear action"
    Assert-FileNotContains $cacheManagerScreenFile "clearCache()" "cache manager must not expose full clear action"
    Assert-FileNotContains $cacheManagerScreenFile "screen.simple_translate.cache.delete.tooltip" "cache manager delete tooltip must not remain"
    Assert-FileNotContains $cacheManagerScreenFile "screen.simple_translate.cache.clear.tooltip" "cache manager clear tooltip must not remain"
    Assert-FileContains $cacheManagerScreenFile "LIST_TOP = 104" "cache manager top controls/status rows must not overlap the cache list"
    Assert-FileContains $cacheManagerScreenFile "Cache list is added first" "cache manager list must render behind search/share controls"
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
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.server_share.status.connected" "Chinese lang must include server shared-cache connected status"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.server_share.status.connected" "English lang must include server shared-cache connected status"
    Assert-FileNotContains $zhLangFile "screen.simple_translate.cache.delete.done" "Chinese cache delete status must not remain"
    Assert-FileNotContains $enLangFile "screen.simple_translate.cache.delete.done" "English cache delete status must not remain"
    Assert-FileNotContains $zhLangFile "screen.simple_translate.cache.clear.done" "Chinese cache clear status must not remain"
    Assert-FileNotContains $enLangFile "screen.simple_translate.cache.clear.done" "English cache clear status must not remain"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.import.world_mismatch" "Chinese lang must include cache world mismatch status"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.import.world_mismatch" "English lang must include cache world mismatch status"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.import.folder_dialog" "Chinese lang must include folder fallback dialog text"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.import.folder_dialog" "English lang must include folder fallback dialog text"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.import.available" "Chinese lang must include cache share import status"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.import.available" "English lang must include cache share import status"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.import.dialog" "Chinese lang must include folder picker title"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.import.dialog" "English lang must include folder picker title"
    Assert-FileContains $zhLangFile "screen.simple_translate.cache.export.done" "Chinese lang must include cache share export result"
    Assert-FileContains $enLangFile "screen.simple_translate.cache.export.done" "English lang must include cache share export result"
    Assert-FileContains $serviceFile "translateFormattedDocument" "formatted document service entry"
    Assert-FileContains $managerFile "translateFormattedDocument(String document)" "formatted document manager entry"
    Assert-FileContains $deepSeekFile "RequestMode.DIRECT_FORMATTED" "DeepSeek direct formatted request mode"
    $promptsFile = Join-Path $src "translation\TranslationPrompts.java"
    $coreDir = Join-Path $src "core"
    Assert-FileContains $promptsFile "reorder tags freely" "direct prompt allows style tags to move with translated words"
    Assert-FileContains $promptsFile "[GLOSSARY]" "direct prompt supports deterministic status glossary"
    Assert-FileContains $promptsFile "directUserSections" "style/term hints live in the user payload to keep the system prompt prefix-cacheable"
    Assert-FileContains (Join-Path $coreDir "WireCodec.java") 'PAYLOAD_MARKER = "stw1"' "wire codec declares the canonical payload marker"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") "WireCodec.textBlock" "documents serialize numbered wire lines"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") "DirectStatusTerms.plainGlossary" "documents emit the plain status glossary"
    Assert-FileContains (Join-Path $coreDir "StyleRestorer.java") "NumberGuard.linePasses" "style restorer enforces the unified numeric guard"
    Assert-FileContains (Join-Path $coreDir "StyleRestorer.java") "restoreAnchoredLine" "style restorer degrades lost tags to anchored styling"
    Assert-FileContains (Join-Path $coreDir "RecoveryPolicy.java") "MAX_CONSECUTIVE_REJECTIONS" "negative cache freezes persistently rejected requests"
    Assert-FileContains $directFile "RecoveryPolicy.shouldAttempt" "direct pipeline consults the negative cache before requesting"
    Assert-FileContains $directFile "maybeUpgradePartialTranslation" "partially-translated cached payloads must heal via bounded background retries"
    Assert-FileContains $directFile "MAX_UPGRADE_ATTEMPTS" "partial cache upgrades must be bounded per session"
    Assert-FileContains $directFile "outcome.isPartial()" "partial direct translations must not be served from cache or persisted"
    Assert-FileContains $directFile "RecoveryPolicy.recordRejected" "direct pipeline records rejected responses"
    Assert-FileContains $directFile "reason=validation-failed" "direct pipeline logs validation rejection reason"
    Assert-FileContains $directFile "sourceSummary()" "direct pipeline logs source summary for rejected translations"
    Assert-FileContains $directFile "requestPayload()" "direct pipeline sends context payload to the model"
    Assert-FileContains $directFile "SimpleTranslateMod.getRuntimeRevision()" "direct async requests must capture world/runtime revision"
    Assert-FileContains $directFile "SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)" "direct async responses must ignore stale world/runtime revisions"
    Assert-FileNotContains $directFile "repairPayload" "direct malformed-output recovery must not add a hidden second API request"
    Assert-FileContains (Join-Path $src "util\TranslationCacheKeys.java") 'PROTOCOL = "direct:v18-mintag"' "direct cache protocol namespace"
    Assert-FileContains (Join-Path $src "util\TranslationCacheKeys.java") "requestLaneFromSurface" "request queue lanes derive from one shared surface mapping"
    Assert-FileContains (Join-Path $src "util\TranslationTextDetector.java") "customStylePromptKey()" "cache language key must include custom style prompt guidance"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "TRANSLATION_STYLE_PROMPT" "translation style prompt must have a semantic config alias"
    $hudScreenFile = Join-Path $src "gui\HudTranslationScreen.java"
    $titleScreenFile = Join-Path $src "gui\TitleTranslationScreen.java"
    $languageScreenFile = Join-Path $src "gui\LanguageSettingsScreen.java"
    $ocrScreenFile = Join-Path $src "gui\OcrTranslationScreen.java"
    $ocrOverlayFile = Join-Path $src "gui\OcrOverlayScreen.java"
    $keyBindingsFile = Join-Path $src "keybind\ModKeyBindings.java"
    $baseScreenFile = Join-Path $src "gui\BaseSimpleTranslateScreen.java"
    $scrollableScreenFile = Join-Path $src "gui\ScrollableSettingsScreen.java"
    $mainScreenFile = Join-Path $src "gui\SimpleTranslateScreen.java"
    Assert-FileContains $languageScreenFile "TRANSLATION_STYLE_PROMPT" "language settings screen must own the translation style prompt"
    Assert-FileContains $languageScreenFile "screen.simple_translate.translation_style" "language settings screen must expose translation style UI text"
    Assert-FileContains $languageScreenFile "SimpleTranslateMod.onStyleSettingsChanged()" "translation style prompt changes must reset runtime state"
    Assert-FileContains $baseScreenFile "setTooltipDelay" "settings tooltips must use a delayed hover tooltip"
    Assert-FileContains $baseScreenFile "Duration.ofMillis(700)" "settings tooltip delay must require a deliberate hover"
    Assert-FileContains $scrollableScreenFile "BOTTOM_BAR_HEIGHT" "scrollable settings screens must reserve a fixed bottom action bar"
    Assert-FileContains $scrollableScreenFile "getContentBottom()" "scrollable entries must be clipped above the bottom action bar"
    Assert-FileContains $scrollableScreenFile "screen.simple_translate.cancel" "scrollable child settings must use a cancel button matching the main screen"
    $removedDescriptionCall = "add" + "Description("
    Assert-FileNotContains $scrollableScreenFile $removedDescriptionCall "scrollable settings must not draw background hint text"
    $removedEnglishBlocks = [string][char]34 + " " + "blocks" + [string][char]34
    $drawStringCall = "drawString(this.font, " + [string][char]34
    $drawCenteredStringCall = "drawCenteredString(this.font, " + [string][char]34
    $chineseSentencePunctuation = [string][char]0x3002
    foreach ($guiFile in Get-ChildItem -Path (Join-Path $src "gui") -Filter "*.java") {
        $guiContent = Get-Content -Raw -Path $guiFile.FullName
        if ($guiContent -match ('add' + 'Description\(')) {
            throw "Settings screens must not draw background hint text: $($guiFile.Name)"
        }
        if (($guiContent.Contains($drawStringCall) -or $guiContent.Contains($drawCenteredStringCall)) `
                -and $guiContent.Contains($chineseSentencePunctuation)) {
            throw "Settings hint sentences must move to delayed tooltips: $($guiFile.Name)"
        }
        if ($guiContent.Contains($removedEnglishBlocks)) {
            throw "Chinese settings UI must not show English distance units: $($guiFile.Name)"
        }
    }
    Assert-FileContains $mainScreenFile "new TitleTranslationScreen(this)" "main settings screen must expose a separate title settings category"
    Assert-FileContains $mainScreenFile "screen.simple_translate.title_translation" "main settings screen must label the title settings category"
    Assert-FileContains $titleScreenFile "HUD_TITLE_ENABLED" "title settings screen must own title toggle"
    Assert-FileContains $titleScreenFile "HUD_ACTIONBAR_ENABLED" "title settings screen must own actionbar toggle"
    Assert-FileContains $titleScreenFile "HUD_TITLE_CONTEXT_ENABLED" "title settings screen must own title context toggle"
    Assert-FileContains $titleScreenFile "HUD_HISTORY_CHAT_ENABLED" "title settings screen must own caption chat-history toggle"
    Assert-FileNotContains $titleScreenFile "HUD_HISTORY_SHOW_ORIGINAL" "title settings screen must not keep removed caption original button toggle"
    Assert-FileContains $titleScreenFile "history_chat" "title settings screen must expose chat-history UI text"
    Assert-FileNotContains $titleScreenFile "history_show_original" "title settings screen must not expose removed original-button UI text"
    Assert-FileNotContains $titleScreenFile "STYLE_PROMPT" "title settings screen must not keep translation style prompt"
    Assert-FileNotContains $titleScreenFile "translation_style" "title settings screen must not expose translation style UI text"
    Assert-FileNotContains $titleScreenFile "stylePromptInput" "title settings screen must not keep translation style input"
    Assert-FileNotContains $titleScreenFile "adjustOverlayButton" "title settings screen must not keep removed caption window adjustment button"
    Assert-FileNotContains $titleScreenFile "adjustingHistoryPreview" "title settings screen must not keep removed caption window preview state"
    Assert-FileNotContains $titleScreenFile "renderPositionPreview" "title settings screen must not render removed caption window preview"
    Assert-FileNotContains $titleScreenFile "scrollWindow" "title settings screen must not keep removed caption window scrolling"
    Assert-FileContains $titleScreenFile "screen.simple_translate.title.section.title" "title settings section headers must be localized"
    Assert-FileNotContains $ocrScreenFile "modelInputsEnabled = !this.currentUseTranslationModel" "OCR model inputs must remain editable while normal-model reuse is enabled"
    Assert-FileNotContains $ocrScreenFile "&& modelInputsEnabled" "OCR model inputs must not be disabled by the reuse-normal-model toggle"
    Assert-FileContains $ocrScreenFile ".verifyAccess(this.currentUseTranslationModel" "OCR settings must test the currently selected reuse or dedicated vision profile"
    Assert-FileContains $ocrScreenFile "screen.simple_translate.ocr.check.tooltip" "OCR API test action must have a delayed tooltip"
    Assert-FileContains $ocrOverlayFile "this.regionX + 2, this.regionY + 2" "OCR result panel must fill the selected region from the top"
    Assert-FileNotContains $ocrOverlayFile "this.regionX + this.regionWidth + 8" "OCR result panel must not render outside the OCR region on the right"
    Assert-FileNotContains $ocrOverlayFile "this.regionX - PANEL_WIDTH - 8" "OCR result panel must not render outside the OCR region on the left"
    Assert-FileNotContains $ocrOverlayFile "PANEL_WIDTH" "OCR result panel must size from the OCR region instead of a fixed external width"
    Assert-FileContains $ocrOverlayFile "RESULT_MASK_COLOR = 0xA6000000" "OCR results must have a translucent black readability mask"
    Assert-FileContains $ocrOverlayFile 'Component.literal("\u3000\u3000" + paragraph)' "OCR translations must render with paragraph indentation"
    Assert-FileNotContains $ocrOverlayFile "screen.simple_translate.ocr.drag_hint" "OCR drag instructions must move out of the overlay"
    Assert-FileNotContains $ocrOverlayFile "screen.simple_translate.ocr.idle" "OCR overlay must stay visually empty before recognition"
    Assert-FileNotContains $ocrOverlayFile "recognizeButton" "OCR overlay must not keep an in-window recognize button"
    Assert-FileNotContains $ocrOverlayFile "closeButton" "OCR overlay must not keep an in-window close button"
    Assert-FileNotContains $ocrOverlayFile "drawButton" "OCR overlay must not keep button rendering in the selected region"
    Assert-FileNotContains $ocrOverlayFile "screen.simple_translate.ocr.source" "OCR overlay should display only the translated text, not a source/translation block"
    Assert-FileContains $ocrOverlayFile "requestRecognitionShortcut()" "OCR overlay must support keyboard-triggered recognition"
    Assert-FileContains $ocrOverlayFile "if (ModKeyBindings.matchesOcrToggleKey(keyCode, scanCode))" "OCR overlay must consume the open-window key without starting recognition"
    Assert-FileContains $ocrOverlayFile "if (!this.enterHeld)" "holding Enter must not submit repeated OCR requests"
    Assert-FileContains $ocrOverlayFile "this.enterHeld = false" "releasing Enter must re-arm the next manual OCR request"
    Assert-FileContains $ocrOverlayFile "GLFW_KEY_ENTER" "OCR overlay must use Enter as the recognition shortcut"
    Assert-FileContains $ocrOverlayFile "Screenshot.takeScreenshot(target)" "OCR screenshot capture must use a remapped direct call in production jars"
    Assert-FileNotContains $ocrOverlayFile "getMethod(`"takeScreenshot`"" "OCR screenshot capture must not rely on non-remapped reflection method names"
    Assert-FileContains $ocrOverlayFile "image.getPixelRGBA(x, y)" "OCR pixel reads must use remapped direct NativeImage calls"
    Assert-FileContains $ocrOverlayFile "image.setPixelRGBA(x, y, color)" "OCR pixel writes must use remapped direct NativeImage calls"
    Assert-FileContains $ocrOverlayFile "image.asByteArray()" "OCR PNG encoding must use remapped direct NativeImage calls"
    Assert-FileContains $ocrOverlayFile "paragraphLines" "OCR overlay text must wrap as normal paragraphs"
    Assert-FileContains $ocrOverlayFile "drawPositionedTranslations" "OCR overlay must render translated regions at their matching source positions"
    Assert-FileContains $ocrOverlayFile "mapRegionToCaptureArea" "OCR positioned regions must map against the exact captured area"
    Assert-FileContains $ocrOverlayFile "(region.x() + region.width()) * captureArea.width / 1000.0F" "OCR positioned width must preserve the source bounding box"
    Assert-FileContains $ocrOverlayFile "(region.y() + region.height()) * captureArea.height / 1000.0F" "OCR positioned height must preserve the source bounding box"
    Assert-FileNotContains $ocrOverlayFile "Math.max(24" "OCR positioned regions must not be widened beyond the source bounding box"
    Assert-FileContains $ocrOverlayFile "fitPositionedText" "OCR positioned translations must dynamically fit their source boxes"
    Assert-FileContains $ocrOverlayFile "MIN_POSITIONED_TEXT_SCALE" "OCR positioned translations must have a bounded minimum scale"
    Assert-FileContains $ocrOverlayFile "MAX_POSITIONED_TEXT_SCALE = 4.00F" "OCR positioned translations must be able to grow to match enlarged source text"
    Assert-FileContains $ocrOverlayFile "height / (float) Math.max(1, this.font.lineHeight)" "OCR positioned font size must derive from source box height"
    Assert-FileContains $ocrOverlayFile "width / (float) textWidth" "OCR positioned font size must remain constrained by source box width"
    Assert-FileContains $ocrOverlayFile "POSITIONED_SOURCE_MASK_COLOR" "OCR positioned translations must cover the matching source glyphs"
    Assert-FileContains $ocrOverlayFile "Math.max(0, (contentWidth - scaledTextWidth) / 2)" "OCR positioned translations must center over their source lines"
    Assert-FileNotContains $ocrOverlayFile "POSITIONED_TEXT_SCALE_STEP" "OCR positioned font sizing must not shrink iteratively to tiny multi-line text"
    Assert-FileContains $ocrOverlayFile "graphics.enableScissor(sourceBox.x, sourceBox.y" "each OCR translation must be clipped to its own source box"
    Assert-FileContains $ocrOverlayFile "graphics.pose().scale(layout.scale" "OCR positioned translations must scale inside their source boxes"
    Assert-FileContains $ocrOverlayFile "graphics.enableScissor(panel.x, panel.y" "OCR result text must be clipped to the selected frame"
    Assert-FileContains $zhLangFile "screen.simple_translate.ocr.check.tooltip" "Chinese lang must include the OCR API test tooltip"
    Assert-FileContains $enLangFile "OCR vision protocol is available" "English lang must include OCR protocol test success text"
    Assert-FileContains $enLangFile "Press the OCR key to open or close the frame" "OCR launch tooltip must explain the open/close toggle"
    Assert-FileContains $enLangFile "press Enter to capture and call the model" "OCR launch tooltip must explain the only token-consuming action"
    Assert-FileContains (Join-Path $src "translation\VisionOcrTranslationService.java") "ModConfig.validateApiUrl(apiUrl)" "dedicated OCR endpoints must reject placeholder or malformed URLs"
    Assert-FileContains (Join-Path $src "translation\VisionOcrTranslationService.java") "OCR endpoint returned an HTML page" "OCR API detection must reject dashboard HTML responses"
    Assert-FileContains (Join-Path $src "translation\VisionOcrTranslationService.java") '"regions":[{"sourceText"' "OCR prompt must request positioned source/translation regions"
    Assert-FileContains (Join-Path $src "translation\VisionOcrTranslationService.java") "tight bounding rectangle of the source glyph pixels" "OCR prompt must request strict source glyph bounds"
    Assert-FileContains (Join-Path $src "translation\OcrTranslationService.java") '"ocr-cache-v2"' "OCR cache must preserve positioned translation regions"
    $mainScreenContent = Get-Content -Raw -Path $mainScreenFile
    $modelSettingsIndex = $mainScreenContent.IndexOf('Component.translatable("screen.simple_translate.model_settings")')
    $ocrSettingsIndex = $mainScreenContent.IndexOf('Component.translatable("screen.simple_translate.ocr_settings")')
    $languageSettingsIndex = $mainScreenContent.IndexOf('Component.translatable("screen.simple_translate.language_settings")')
    if ($modelSettingsIndex -lt 0 -or $ocrSettingsIndex -le $modelSettingsIndex -or $languageSettingsIndex -le $ocrSettingsIndex) {
        throw "Main settings must place Screen OCR Translation directly below Model Settings"
    }
    Assert-FileNotContains $keyBindingsFile "requestRecognitionShortcut()" "the OCR open-window key must never start recognition"
    Assert-FileContains $keyBindingsFile "((OcrOverlayScreen) minecraft.screen).onClose();" "pressing the OCR key again must close the existing OCR frame"
    Assert-FileNotContains $keyBindingsFile "minecraft.setScreen(null);" "OCR key must not close the OCR overlay instead of recognizing"
    Assert-FileContains $scrollableScreenFile "renderWidgetsWithFixedBottomActions" "secondary scrollable screens must render save/cancel above scrolling content"
    Assert-FileContains $scrollableScreenFile "renderAboveScrollableContentBeforeBottomActions" "secondary scrollable screens must provide a layer below fixed buttons for previews"
    Assert-FileContains $scrollableScreenFile "this.saveButton.visible = false" "secondary save button must be hidden during normal widget pass"
    Assert-FileContains $scrollableScreenFile "this.saveButton.render" "secondary save button must be drawn after the bottom bar"
    Assert-FileContains $scrollableScreenFile "mouseY >= getContentBottom()" "secondary bottom action area must receive clicks before scrolling content"
    Assert-FileContains $zhLangFile "screen.simple_translate.title.section.history" "Chinese lang must include title history section label"
    Assert-FileContains $enLangFile "screen.simple_translate.title.section.history" "English lang must include title history section label"
    Assert-FileContains $zhLangFile "screen.simple_translate.translation_style" "Chinese lang must include translation style label"
    Assert-FileContains $enLangFile "screen.simple_translate.translation_style" "English lang must include translation style label"
    $oldTitleStyleKey = "screen.simple_translate.title." + "style_prompt"
    Assert-FileNotContains $zhLangFile $oldTitleStyleKey "old title style label must be removed from Chinese lang"
    Assert-FileNotContains $enLangFile $oldTitleStyleKey "old title style label must be removed from English lang"
    Assert-FileNotContains $hudScreenFile "HUD_TITLE_ENABLED" "HUD settings screen must not keep title toggle"
    Assert-FileNotContains $hudScreenFile "HUD_ACTIONBAR_ENABLED" "HUD settings screen must not keep actionbar toggle"
    Assert-FileNotContains $hudScreenFile "HUD_TITLE_CONTEXT_ENABLED" "HUD settings screen must not keep title context toggle"
    Assert-FileNotContains $hudScreenFile "HUD_HISTORY_CHAT_ENABLED" "HUD settings screen must not keep title history chat toggle"
    Assert-FileNotContains $hudScreenFile "STYLE_PROMPT" "HUD settings screen must not keep translation style prompt"
    Assert-FileNotContains $hudScreenFile "renderPositionPreview" "HUD settings screen must not show the removed title-history preview"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "TranslationRequestQueue.submit" "normal DeepSeek translation requests must use the global queue"
    Assert-FileContains $queueFile "Executors.newFixedThreadPool" "translation queue must support multiple surface lanes"
    Assert-FileContains $queueFile "laneForSurface" "translation queue must isolate work by surface lane"
    Assert-FileContains (Join-Path $src "util\TranslationCacheKeys.java") "hud_title" "title/subtitle HUD translations must use a dedicated lane"
    Assert-FileContains (Join-Path $src "util\TranslationCacheKeys.java") "hud_actionbar" "actionbar HUD translations must use a dedicated lane"
    Assert-FileContains $queueFile "TITLE_URGENT(480, true)" "title/subtitle HUD translations must use an urgent protected priority"
    Assert-FileContains $queueFile "ACTIONBAR_URGENT(360, true)" "actionbar HUD translations must use an isolated urgent priority"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "Priority.TITLE_URGENT" "formatted title requests must be scheduled with urgent title priority"
    Assert-FileContains (Join-Path $src "translation\DeepSeekTranslationService.java") "Priority.ACTIONBAR_URGENT" "formatted actionbar requests must be scheduled with actionbar priority"
    Assert-FileContains $queueFile "globalInFlight < maxParallelRequests()" "translation queue must enforce the configured global parallel limit"
    Assert-FileContains $queueFile "TASKS_BY_LANE_KEY" "translation queue must coalesce duplicate work inside a lane"
    Assert-FileContains $queueFile "runningTasks" "translation queue must cancel in-flight work by surface prefix"
    Assert-FileNotRegex $queueFile 'getLogger\(\)\.info\(\s*"Translation queue (?:start|finish|cleared)' "translation queue must not log routine lifecycle events at INFO"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "api.maxParallelRequests" "config must expose max parallel request count"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "enum ApiFormat" "config must expose selectable API formats"
    Assert-FileContains $deepSeekFile "EndpointKind.RESPONSES" "request layer must support OpenAI Responses format"
    Assert-FileContains $deepSeekFile "EndpointKind.ANTHROPIC_MESSAGES" "request layer must support Anthropic Messages format"
    Assert-FileContains $deepSeekFile "EndpointKind.GEMINI_GENERATE_CONTENT" "request layer must support Gemini generateContent format"
    Assert-FileContains $deepSeekFile "model.probe.direct" "model settings check must use the real direct translation protocol"
    Assert-FileContains $deepSeekFile "isValidModelAccessProbeResponse" "model settings check must validate restored line protocol"
    Assert-FileContains $deepSeekFile "if (isValidModelAccessProbeResponse(translated))" "model settings check success must be gated by direct protocol validation"
    Assert-FileContains $deepSeekFile "parseResponse(body)" "model settings check must parse the model response body, not only HTTP status"
    Assert-FileNotContains $deepSeekFile 'Failed to parse non-stream response: {}' "invalid API responses must not dump full bodies into logs"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") "isExampleHost" "API URL validation must reject documentation placeholder domains"
    Assert-FileContains $deepSeekFile "REPORTED_INVALID_API_URLS.add" "invalid configured API URLs must not spam one warning per translation"
    $zhTranslationProtocolAvailable = -join ([char[]](0x7ffb, 0x8bd1, 0x534f, 0x8bae, 0x53ef, 0x7528))
    Assert-FileContains $zhLangFile $zhTranslationProtocolAvailable "Chinese model check status should say translation protocol, not only API availability"
    Assert-FileContains $enLangFile "Translation protocol is available" "English model check status should say translation protocol, not only API availability"
    Assert-FileContains (Join-Path $src "SimpleTranslateMod.java") "TranslationRequestQueue.clear()" "runtime reset must clear queued translation work"
    Assert-FileNotContains $directFile "containsUntranslatedNaturalEnglish" "direct pipeline must not reject LLM output for residual English"
    Assert-FileNotContains $directFile "english-leftover line=" "direct pipeline must not reject residual source language in editable runs"
    Assert-FileNotContains $directFile "containsUntranslatedNaturalText" "old direct pipeline residual-source rejection helper must not return"
    Assert-FileContains $managerFile "containsBlacklistedEntry(translated)" "translated output blacklist must still be enforced"
    Assert-FileNotContains (Join-Path $src "mixin\BossHealthOverlayMixin.java") "SemanticBlockTranslationHelper" "bossbar must not use the old semantic compatibility path"
    if (Test-Path -LiteralPath $textDisplayMixin) {
        Assert-FileNotContains $textDisplayMixin "SemanticBlockTranslationHelper" "text display must not use the old semantic compatibility path"
    } else {
        Assert-FileNotContains $mixinConfigFile '"TextDisplayMixin"' "Minecraft 1.19.x must not enable unsupported text display mixins"
    }
    Assert-FileNotContains (Join-Path $src "mixin\ChatComponentMixin.java") "SemanticBlockTranslationHelper" "chat must not keep an unused semantic helper import"
    $signFile = Join-Path $src "util\SignTranslationHelper.java"
    $signDirectFile = Join-Path $src "util\SignDirectDocument.java"
    $signSelectionFile = Join-Path $src "util\SignContextSelectionManager.java"
    $signSelectionHighlighterFile = Join-Path $src "util\SignSelectionHighlighter.java"
    Assert-FileContains $signSelectionFile "SELECTIONS.remove(key);" "manual sign submitted selections must be cleared after completion or failure"
    Assert-FileNotContains $signSelectionFile "FAILED" "manual sign failed selections must not remain stuck in renderable selection state"
    Assert-FileNotContains $signSelectionHighlighterFile "case FAILED" "manual sign failed selection boxes must not remain rendered"
    Assert-FileContains $signFile "translateFormattedDocument(document.requestPayload(), document.surface())" "sign helper uses direct formatted document pipeline"
    Assert-FileNotRegex $signFile 'getLogger\(\)\.info\(\s*"[^"]*sign by-id direct skipped' "sign helper must not log pending/cooldown skips at INFO"
    Assert-FileContains $signFile "sign.manual.group.by_id.direct" "manual sign selection must submit one coordinate-id group document"
    Assert-FileContains $signFile "SignDirectDocument.fromEntries" "manual sign group must use the sign-id direct document"
    Assert-FileContains $signFile "sign.auto.single.direct" "automatic sign translation must translate only the current sign"
    Assert-FileContains $signFile "sign-auto-single-by-id" "automatic sign translation must use a single sign-id document"
    Assert-FileContains $signFile "buildAutoSignDocument" "automatic sign translation must build a whole-sign document"
    Assert-FileContains $signFile "Treat the four visible lines below as one complete sign message" "automatic sign prompt must make the four lines one semantic block"
    Assert-FileContains $signFile "applySignDocument(List.of(context)" "automatic sign completion must restore the full four-line sign document"
    Assert-FileNotContains $signFile "DirectSurfaceTranslator.translateComponentsAsync" "automatic sign translation must not use isolated component-list requests"
    Assert-FileContains $signFile "sign.auto.single.direct" "automatic sign mode must keep its dedicated surface"
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
    Assert-FileContains $signDirectFile "fromComponents(flat, surface, role, true, fullContext)" "sign documents ride the shared minimal-echo wire protocol"
    Assert-FileContains $signDirectFile "signIndex * 4 + lineIndex" "sign identity is positional so model reordering cannot corrupt writeback"
    Assert-FileContains $signDirectFile "sign-token-missing" "sign validation must reject missing protected anchors"
    Assert-FileContains $signDirectFile "sign-token-duplicate" "sign validation must reject duplicated protected anchors"
    Assert-FileContains $signDirectFile "sign-token-moved-from-other-sign" "sign validation must allow same-sign token movement but reject cross-sign token movement"
    Assert-FileContains $signDirectFile "signTokenViolation" "sign validation must validate protected tokens at sign scope"
    Assert-FileContains $signDirectFile "originalLines(sign)" "violated signs degrade to their original lines instead of rejecting the whole group"
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
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") "DirectSurfaceTranslator.translateTextAsync" "chat helper uses direct formatted pipeline"
    $chatMixin = Join-Path $src "mixin\ChatComponentMixin.java"
    $chatDir = Join-Path $src "chat"
    $chatController = Join-Path $chatDir "ChatTranslationController.java"
    $chatAuto = Join-Path $chatDir "ChatAutoTranslator.java"
    $chatButton = Join-Path $chatDir "ChatButtonController.java"
    $chatStore = Join-Path $chatDir "ChatMessageStore.java"
    $hudPresenter = Join-Path $chatDir "HudHistoryChatPresenter.java"
    Assert-FileContains $chatMixin 'ChatTranslationController' "chat mixin must stay thin and delegate to the chat controller"
    Assert-FileNotContains $chatMixin 'handleAutoMode' "AUTO-mode logic must live outside the mixin"
    Assert-FileNotContains $chatMixin 'createMessageWithButton' "BUTTON-mode logic must live outside the mixin"
    $chatBatchFile = Join-Path $chatDir "ChatContextBatchTranslator.java"
    Assert-FileContains $chatAuto 'ChatContextBatchTranslator.enqueueVisibleUntranslated' "chat context AUTO mode must scan visible untranslated chat into a delayed batch"
    Assert-FileNotContains $chatAuto 'tryApplyCachedContextTranslation' "chat context AUTO mode must not use the legacy per-target context cache"
    Assert-FileContains $chatBatchFile 'buildBatchContextBefore' "chat context batches must include prior translated history entries"
    Assert-FileContains $chatBatchFile 'enqueueVisibleUntranslated' "chat context batch must collect all visible untranslated chat lines"
    Assert-FileContains $chatBatchFile 'simpleTranslateAllMessages' "chat context batch must scan the chat box instead of only the latest addMessage line"
    Assert-FileContains $chatBatchFile 'trackController' "chat context batch must remember active controllers for tick rescan"
    Assert-FileContains $chatBatchFile 'scanVisibleUntranslatedControllers' "chat context batch must rescan visible chat so early messages are not skipped"
    Assert-FileContains $chatAuto 'tryApplyCachedAutoMessage' "chat AUTO mode must try the unified full-message cache before waiting for a batch"
    Assert-FileContains $chatBatchFile 'tryApplyCachedAutoMessage(identity, content, plainText, pendingKey)' "chat context batch scan must apply already-cached full-message translations immediately"
    Assert-FileContains $chatBatchFile 'cacheRestoredTranslation' "chat context batch completion must also store per-line full-message cache aliases for instant reload and context-toggle reuse"
    Assert-FileContains $chatAuto 'ChatTranslationRuntime.translateAutoMessage' "context-disabled AUTO chat must translate the whole displayed message through the unified chat batch surface"
    Assert-FileNotContains $chatAuto 'translateSegmentsAndApply' "context-disabled AUTO chat must not split one message into styled segment requests"
    Assert-FileNotContains $chatAuto 'ChatTranslationRuntime.translateBatch' "context-disabled AUTO chat must not call the legacy segment batch translator"
    Assert-FileNotContains $chatAuto 'buildCachedSegmentTranslation' "AUTO chat cache lookup must not depend on the legacy segment surface"
    Assert-FileNotContains (Join-Path $src "util\ChatTranslationRuntime.java") 'CHAT_SEGMENT_SURFACE' "chat runtime must not keep the removed segment-surface AUTO/BUTTON chain"
    Assert-FileNotContains (Join-Path $src "util\ChatTranslationRuntime.java") 'chat.message.segment.direct' "chat runtime must not keep the removed segment direct surface"
    Assert-FileNotContains (Join-Path $src "util\ChatTranslationRuntime.java") 'buildCachedSegmentTranslation' "chat runtime must not keep the removed segment cache helper"
    Assert-FileContains $chatBatchFile 'existing.status == Status.FAILED' "chat context failed entries must stay in cooldown instead of being recreated by new chat"
    Assert-FileContains $chatBatchFile 'fallbackExhaustedEntriesLocked' "chat context batch failures must fall back instead of retrying indefinitely"
    Assert-FileContains $chatBatchFile 'nextSequence' "chat context batch must record arrival order like HUD caption history"
    Assert-FileNotContains $chatBatchFile 'selectConsecutiveBatch' "chat context batch must not require consecutive chat indices"
    Assert-FileContains $chatController 'CHAT_CONTEXT_MESSAGE_COUNT' "chat context collection must read the player-configured previous-message count"
    Assert-FileContains $chatAuto 'scheduleRetry' "failed displayed chat lines must register a bounded automatic retry"
    Assert-FileContains $chatAuto 'MAX_AUTO_RETRIES' "chat auto retry must be bounded"
    Assert-FileNotContains $chatButton 'CHAT_CONTEXT_ENABLED' "button chat translation must never use the chat context toggle"
    Assert-FileNotContains $chatButton 'translateWithContext' "button chat translation must not call context translation"
    Assert-FileNotContains $chatButton 'buildCachedContextTranslation' "button chat translation must not read context cache"
    Assert-FileContains $chatButton 'startDirectMessageTranslation(messageId, data)' "button chat messages must translate the clicked line directly without context"
    Assert-FileContains $chatButton 'ChatTranslationRuntime.translateAutoMessage' "button chat must use the same full-message direct surface as automatic chat"
    Assert-FileContains $chatButton 'buildCachedAutoMessageTranslation' "button chat cache lookup must share automatic chat cache entries"
    Assert-FileNotContains $chatButton 'startSystemDirectTranslation' "button chat must not keep the old system-only direct branch"
    Assert-FileNotContains $chatButton 'startSegmentTranslation' "button chat must not keep the old styled segment branch"
    Assert-FileNotContains $chatButton 'ChatTranslationRuntime.translateBatch' "button chat must not split a clicked message into segment requests"
    Assert-FileContains $chatController 'onChatModeChanged' "chat mode switches must reset pending/processed state"
    $chatReplacer = Join-Path $chatDir "ChatMessageReplacer.java"
    Assert-FileContains $chatReplacer 'messageMatchesIdentity' "chat replacer must match messages by addedTime when component references go stale"
    Assert-FileContains $chatReplacer 'identity.originalText.equals(currentText)' "chat replacer must use original text to disambiguate same-tick system messages"
    Assert-FileContains $chatStore 'messageKey' "chat pending/translated keys must be per-message not plain-text global"
    Assert-FileContains $chatController 'getOriginalContextText' "chat button context must normalize displayed button messages before matching"
    Assert-FileContains $chatController 'return data.originalPlainText();' "chat button context must use the saved original text instead of translated/button-suffix text"
    Assert-FileContains $chatController 'ChatContextHelper.stripChatButtonSuffix(content.getString())' "chat context fallback must strip button suffixes from displayed messages"
    Assert-FileContains $chatMixin 'HudHistoryChatBridge' "chat mixin must keep the safe HUD-history chat bridge interface"
    Assert-FileContains $chatMixin 'simple_translate$upsertHudHistoryCaption' "chat mixin must forward completed HUD captions to the presenter"
    Assert-FileContains $hudPresenter 'HUD_HISTORY_CLICK_PREFIX' "HUD history chat buttons must use a separate click prefix"
    Assert-FileContains $hudPresenter 'isHudHistoryChatMessage' "HUD history messages must be identifiable"
    Assert-FileContains $hudPresenter 'MAX_HUD_HISTORY_CHAT_MESSAGES = 40' "HUD history chat messages must be capped to avoid full-screen chat background spam"
    Assert-FileContains $chatController 'hudHistoryPresenter.isHudHistoryChatMessage(message)' "HUD history chat messages must skip normal chat translation"
    Assert-FileNotContains $hudPresenter 'getChat().addMessage' "HUD history must not publish through vanilla addMessage"
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") 'CHAT_CONTEXT_SURFACE = "chat.context.direct"' "chat context must use direct context surface"
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") 'Chat history is ordered oldest to newest' "chat context payload must tell the model the chronological order"
    Assert-FileContains (Join-Path $src "util\ChatTranslationRuntime.java") '[target]' "chat context payload must mark the target line"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") 'CHAT_CONTEXT_MESSAGE_COUNT' "config must expose player-customizable chat context count"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") 'CHAT_CONTEXT_BATCH_INTERVAL_MS' "config must expose chat context batch interval"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") 'CHAT_CONTEXT_COLLECT_WINDOW_MS' "config must expose chat context collect window"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") 'chat.context.batch' "chat context batch surfaces emit a passage-coherence note"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") 'chat.context' "chat context surfaces emit a reference-only note for the model"
    Assert-FileContains $chatBatchFile 'BATCH_SURFACE = ChatTranslationRuntime.CHAT_CONTEXT_BATCH_SURFACE' "chat context batch must use the dedicated batch surface"
    Assert-FileContains $chatBatchFile 'BACKLOG_FORCE_FLUSH_COUNT' "chat context batch must flush early when the pending queue grows"
    Assert-FileContains $chatBatchFile 'TickEvent.ClientTickEvent' "chat context batch must tick on Forge client end tick"
    $chatScreenFile = Join-Path $src "gui\ChatTranslationScreen.java"
    Assert-FileContains $chatScreenFile 'extends ScrollableSettingsScreen' "chat settings must use the shared scroll/fixed bottom action screen"
    Assert-FileContains $chatScreenFile 'CHAT_CONTEXT_MESSAGE_COUNT' "chat settings screen must expose the custom context count"
    Assert-FileContains $chatScreenFile 'CHAT_CONTEXT_BATCH_INTERVAL_MS' "chat settings screen must expose batch interval input"
    Assert-FileContains $chatScreenFile 'CHAT_CONTEXT_COLLECT_WINDOW_MS' "chat settings screen must expose collect window input"
    Assert-FileContains $chatScreenFile 'chat.context_count.tooltip' "chat context count setting must have a hover tooltip"
    $zhExtraHistoryLabel = -join ([char[]](0x989D,0x5916,0x53C2,0x8003,0x4E0A,0x6587,0x6761,0x6570))
    $zhCollectWindowDistinction = -join ([char[]](0x6D88,0x606F,0x6536,0x96C6,0x7B49,0x5F85,0x65F6,0x95F4,0x53EA,0x51B3,0x5B9A,0x54EA,0x4E9B,0x8FDE,0x7EED,0x65B0,0x6D88,0x606F,0x8FDB,0x5165,0x540C,0x4E00,0x6279))
    $zhOldContextCountLabel = -join ([char[]](0x4E0A,0x4E0B,0x6587,0x6D88,0x606F,0x6570))
    Assert-FileContains $zhLangFile $zhExtraHistoryLabel "Chinese chat context count label must clarify it is extra prior history"
    Assert-FileContains $zhLangFile $zhCollectWindowDistinction "Chinese chat context tooltip must distinguish collect window from extra history"
    Assert-FileNotContains $zhLangFile $zhOldContextCountLabel "Chinese chat context count label must not use the old ambiguous wording"
    Assert-FileContains $enLangFile 'Extra Reference History' "English chat context count label must clarify it is extra prior history"
    Assert-FileContains $enLangFile 'The collect window decides which new consecutive messages enter the same batch' "English chat context tooltip must distinguish collect window from extra history"
    Assert-FileNotContains $enLangFile 'Context Message Count' "English chat context count label must not use the old ambiguous wording"
    Assert-FileContains $chatScreenFile 'currentMode == ModConfig.TranslationMode.AUTO' "chat settings context controls must be gated to AUTO mode"
    Assert-FileContains $chatScreenFile 'contextEnabledToggle.setValue(false)' "chat settings must turn context off when switching to BUTTON mode"
    Assert-FileContains $chatScreenFile 'savedContextEnabled = currentMode == ModConfig.TranslationMode.AUTO && contextEnabled' "chat settings must not persist context in BUTTON mode"
    Assert-FileNotRegex $directFile 'getLogger\(\)\.info\(\s*"Direct formatted translation (?:queued|recovered|skipped|ignored|failed|rejected)' "direct formatted pipeline must not log per-request lifecycle at INFO"
    Assert-FileNotRegex $directFile 'getLogger\(\)\.info\(\s*"Direct formatted batch completed' "direct formatted batch completion must not be INFO spam"
    Assert-FileContains $directFile 'cacheKeySummary()' "direct formatted failure logs should use compact cache-key summaries"
    Assert-FileContains (Join-Path $src "gui\BookTranslationScreen.java") 'extends ScrollableSettingsScreen' "book settings must use the shared scroll/fixed bottom action screen"
    Assert-FileContains (Join-Path $src "gui\ItemTooltipScreen.java") 'extends ScrollableSettingsScreen' "item tooltip settings must use the shared scroll/fixed bottom action screen"
    Assert-FileContains (Join-Path $src "gui\SignTranslationScreen.java") 'extends ScrollableSettingsScreen' "sign settings must use the shared scroll/fixed bottom action screen"
    Assert-FileContains (Join-Path $src "gui\AdvancementTranslationScreen.java") 'extends ScrollableSettingsScreen' "advancement settings must use the shared scroll/fixed bottom action screen"
    Assert-FileContains (Join-Path $src "gui\EntityNameTranslationScreen.java") 'extends ScrollableSettingsScreen' "entity settings must use the shared scroll/fixed bottom action screen"
    if (Test-Path -LiteralPath $textDisplayScreen) {
        Assert-FileContains $textDisplayScreen 'extends ScrollableSettingsScreen' "text display settings must use the shared scroll/fixed bottom action screen"
    }
    Assert-FileContains (Join-Path $src "gui\ScrollableSettingsScreen.java") 'screen.simple_translate.save.tooltip' "shared save action must have a hover tooltip"
    Assert-FileContains (Join-Path $src "gui\ScrollableSettingsScreen.java") 'drawBottomBar' "shared settings screen must keep bottom action mask above scroll content"
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
    Assert-FileContains $containerTooltipMixin "graphics.renderTooltip" "container item tooltip mixin must render cached translated tooltips directly"
    Assert-FileContains $containerTooltipMixin "ci.cancel()" "container item tooltip mixin must cancel vanilla rendering after rendering translated tooltip"
    Assert-FileContains $containerTooltipMixin "TooltipTranslationController.translateForRender" "container item tooltip mixin must use the shared tooltip controller"
    Assert-FileContains $containerTooltipMixin "TooltipTranslationController.isRenderingTranslated" "container tooltip mixin must respect the shared render guard"
    Assert-FileContains $containerTooltipMixin "TooltipTranslationHelper.isMarkedTranslatedTooltip" "container item tooltip mixin must avoid reprocessing already translated tooltips"
    Assert-FileNotContains $containerTooltipMixin 'method = "render", at = @At("TAIL")' "container item tooltip mixin must not prefetch every visible inventory slot on screen open"
    Assert-FileNotContains $containerTooltipMixin "prefetchComponentsBatch" "container item tooltip translation must be hover-triggered, not inventory-wide prefetch"
    Assert-FileNotContains $containerTooltipMixin "prefetchSlotsPerPass" "container item tooltip mixin must not keep slot-scanning prefetch state"
    Assert-FileNotContains $containerTooltipMixin "nextTooltipPrefetchSlot" "container item tooltip mixin must not rotate through backpack slots for translation"
    $hoverTooltipMixin = Join-Path $src "mixin\HoverTooltipMixin.java"
    $hoverTooltipText = Get-Content -LiteralPath $hoverTooltipMixin -Raw
    $newTooltipListDescriptor = 'renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V'
    $legacyTooltipListDescriptor = 'renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V'
    if (-not $hoverTooltipText.Contains($newTooltipListDescriptor) -and -not $hoverTooltipText.Contains($legacyTooltipListDescriptor)) {
        throw "materialized tooltip list render path must queue cache misses: expected $hoverTooltipMixin to contain <$newTooltipListDescriptor> or <$legacyTooltipListDescriptor>"
    }
    Assert-FileContains $hoverTooltipMixin "simple_translate`$onRenderComponentTooltip" "component tooltip render path must queue cache misses"
    Assert-FileContains $hoverTooltipMixin 'TooltipTranslationController.resolveRenderContext' "chat overlay tooltips must use the shared render context"
    Assert-FileContains $hoverTooltipMixin 'TooltipTranslationController.shouldTranslateRenderedTooltip' "materialized tooltip render paths must honor chat/item gates"
    Assert-FileContains (Join-Path $src "util\TooltipTranslationHelper.java") 'HOVER_OVERLAY_SURFACE = "hover.overlay.direct"' "chat overlay tooltips must use the overlay batch surface"
    Assert-FileContains (Join-Path $src "util\TooltipTranslationHelper.java") 'scheduleAsyncRefresh' "tooltip render must refresh automatically after async translation completes"
    Assert-FileContains (Join-Path $src "util\DirectFormattedTranslationPipeline.java") 'isLenientPartialSurface' "tooltip/hover surfaces must accept partial successful restores"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") 'hover.overlay' "overlay tooltip surface must carry a passage-coherence note"
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
    Assert-FileContains $titleOverlayMixin "true," "actionbar dynamic placeholders must be protected by fixed direct layout"
    Assert-FileContains $titleOverlayMixin "meaningfulWords <= 2" "actionbar technical HUD filter must allow multi-label RPG action bars"
    Assert-FileContains $promptsFile "@@0@@" "prompt must keep numeric placeholder counters stable"
    Assert-FileContains (Join-Path $coreDir "TranslationDocument.java") "hud.history.caption_batch" "HUD history batches carry a passage-coherence note"
    Assert-FileContains $titleOverlayMixin 'simple_translate$captionBatchMode' "title/actionbar must branch between caption batch and immediate fallback"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshTitleGroupFromCaptionBatch' "context-enabled title path must use caption batch history"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshOverlayFromCaptionBatch' "context-enabled actionbar path must use caption batch history"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshTitleGroupImmediate' "context-disabled title path must keep immediate direct fallback"
    Assert-FileContains $titleOverlayMixin 'simple_translate$refreshOverlayImmediate' "context-disabled actionbar path must keep immediate direct fallback"
    Assert-FileContains $titleOverlayMixin 'HudTranslationHistory.tickTranslator' "caption batch mode must tick the throttled history translator"
    Assert-FileContains $titleOverlayMixin 'HudTranslationHistory.recordCaption' "caption batch mode must record source captions instead of direct request spam"
    Assert-FileContains $titleOverlayMixin 'groupKey.equals(this.simple_translate$titleGroupKey' "title/subtitle async restore must guard against stale local title keys"
    Assert-FileContains $titleOverlayMixin 'currentKey.equals(this.simple_translate$overlayKey' "actionbar async restore must guard against stale actionbar template keys"
    Assert-FileContains $titleOverlayMixin 'simple_translate$nextHudHistoryKey' "HUD history must allocate per-source history keys"
    Assert-FileContains $titleOverlayMixin 'simple_translate$pendingActionbarHistoryKeys' "actionbar async history requests must be deduplicated by key"
    Assert-FileContains $titleOverlayMixin 'private Set<String> simple_translate$pendingTitleHistoryKeys()' "title pending keys must be lazily initialized for mixin target instances"
    Assert-FileContains $titleOverlayMixin 'private Set<String> simple_translate$pendingActionbarHistoryKeys()' "actionbar pending keys must be lazily initialized for mixin target instances"
    $hudHistoryFile = Join-Path $src "util\HudTranslationHistory.java"
    Assert-FileContains $hudHistoryFile 'BATCH_SURFACE = "hud.history.caption_batch.direct"' "HUD history must use the caption batch surface"
    Assert-FileContains $hudHistoryFile 'BATCH_ROLE = "hud-caption-history-batch"' "HUD history must use the caption batch role"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") 'HUD_CAPTION_BATCH_INTERVAL_MS' "config must expose custom HUD caption LLM request interval"
    Assert-FileContains (Join-Path $src "config\ModConfig.java") 'HUD_CAPTION_COLLECT_WINDOW_MS' "config must expose custom HUD caption collection window"
    Assert-FileContains $titleScreenFile 'caption_batch_interval' "title settings screen must expose caption LLM request interval input"
    Assert-FileContains $titleScreenFile 'caption_collect_window' "title settings screen must expose caption collection window input"
    Assert-FileContains $hudHistoryFile 'batchIntervalMs()' "HUD history batch scheduler must read the configured request interval"
    Assert-FileContains $hudHistoryFile 'collectWindowMs()' "HUD history must read the configured subtitle collection window"
    Assert-FileContains $hudHistoryFile 'MAX_BATCH_CAPTIONS = 12' "HUD history batch scheduler must cap each batch at 12 captions"
    Assert-FileNotContains $hudHistoryFile 'HUD_HISTORY_SHOW_ORIGINAL' "HUD history must not keep removed original-button config"
    Assert-FileContains $hudHistoryFile 'HUD_HISTORY_CHAT_ENABLED' "HUD history must honor the chat-history display toggle"
    Assert-FileContains $hudHistoryFile 'publishReadyChatMessages' "HUD history must publish completed captions to the safe chat bridge"
    Assert-FileContains $hudHistoryFile 'HudHistoryChatBridge.publish' "HUD history must use the bridge instead of vanilla addMessage"
    Assert-FileNotContains $hudHistoryFile 'CHAT_HISTORY_MARKER' "HUD history must not inject marked messages into vanilla chat"
    Assert-FileNotContains $hudHistoryFile 'CHAT_HISTORY_CLICK_PREFIX' "HUD history must not keep removed chat click prefix"
    Assert-FileNotContains $hudHistoryFile 'toggleHistoryChatMessage' "HUD history must not keep removed local chat toggle method"
    Assert-FileNotContains $hudHistoryFile 'buildHistoryToggleButton' "HUD history must not keep removed chat toggle button builder"
    Assert-FileNotContains $hudHistoryFile 'historyChatClickValueForTest' "HUD history must not keep removed chat-button test hook"
    Assert-FileNotContains $hudHistoryFile 'original_prefix' "HUD history must not append old inline original-prefix text"
    Assert-FileNotContains $hudHistoryFile 'publishChatMessage' "HUD history must not keep the old vanilla chat publisher"
    Assert-FileNotContains $hudHistoryFile 'getChat().addMessage' "HUD history must not write to the chat component"
    Assert-FileNotContains $hudHistoryFile 'ChatMessageState' "HUD history must not keep removed chat message state"
    Assert-FileContains $hudHistoryFile 'if (!ModConfig.HUD_TITLE_CONTEXT_ENABLED.get())' "HUD history must omit prior context when title context is disabled"
    Assert-FileNotContains $hudHistoryFile 'GuiGraphics' "HUD history must not render its own removed caption window"
    Assert-FileNotContains $hudHistoryFile 'FormattedCharSequence' "HUD history must not keep removed caption window line rendering"
    Assert-FileNotContains $hudHistoryFile 'windowScrollLines' "HUD history must not keep removed caption window scroll state"
    Assert-FileNotContains $hudHistoryFile 'scrollWindow' "HUD history must not keep removed caption window scroll methods"
    Assert-FileNotContains $hudHistoryFile 'drawPanel' "HUD history must not keep removed caption window panel drawing"
    Assert-FileNotContains $hudHistoryFile 'drawScrollbar' "HUD history must not keep removed caption window scrollbar drawing"
    Assert-FileContains $hudHistoryFile 'Status.IN_FLIGHT' "HUD history must track in-flight captions"
    Assert-FileContains $hudHistoryFile 'buildBatchContextBefore' "HUD history must build prior-caption context for batches"
    Assert-FileContains $hudHistoryFile 'entry.sequence >= firstBatchSequence' "HUD history context must exclude the current batch"
    Assert-FileNotContains $hudHistoryFile 'recordTitleSource' "HUD history must not keep old title source record path"
    Assert-FileNotContains $hudHistoryFile 'updateTitleSourceTranslation' "HUD history must not keep old title immediate update path"
    Assert-FileNotContains $hudHistoryFile 'recordActionbarSource' "HUD history must not keep old actionbar source record path"
    Assert-FileNotContains $hudHistoryFile 'updateActionbarSourceTranslation' "HUD history must not keep old actionbar immediate update path"
    Assert-FileNotContains $titleOverlayMixin 'private final Set<String> simple_translate$pendingTitleHistoryKeys' "title pending keys must not rely on a mixin field initializer"
    Assert-FileNotContains $titleOverlayMixin 'private final Set<String> simple_translate$pendingActionbarHistoryKeys' "actionbar pending keys must not rely on a mixin field initializer"
    Assert-FileNotContains $titleOverlayMixin 'recordTitleSource' "TitleOverlayMixin must not call old title history record path"
    Assert-FileNotContains $titleOverlayMixin 'updateTitleSourceTranslation' "TitleOverlayMixin must not call old title history update path"
    Assert-FileNotContains $titleOverlayMixin 'recordActionbarSource' "TitleOverlayMixin must not call old actionbar source record path"
    Assert-FileNotContains $titleOverlayMixin 'updateActionbarSourceTranslation' "TitleOverlayMixin must not call old actionbar history update path"
    Assert-FileNotContains $titleOverlayMixin "SemanticBlockTranslationHelper" "title/actionbar must not use the old semantic compatibility path"
    Assert-FileNotContains $titleOverlayMixin "hud.title.component.semantic" "title must not use the old independent semantic surface"
    Assert-FileNotContains $titleOverlayMixin "hud.subtitle.component.semantic" "subtitle must not use the old independent semantic surface"
    Assert-FileNotContains $titleOverlayMixin "hud.actionbar.component.semantic" "actionbar must not use the old semantic surface"
    Assert-FileNotContains $titleOverlayMixin "translateRaw(" "title/actionbar must not use raw translation"
    Assert-FileContains (Join-Path $coreDir "WireCodec.java") 'unnumbered.size() == expectedLines' "unnumbered plain responses recover positionally when line counts match"
    Assert-FileContains (Join-Path $coreDir "StyleRestorer.java") 'Re-append dropped non-editable runs' "dropped non-editable tags are restored locally so symbols never vanish"
    $keybindFile = Join-Path $src "keybind\ModKeyBindings.java"
    $removedHudHistoryKey = 'open_' + 'hud_history'
    $removedHudHistoryScreen = 'HudHistory' + 'OverlayScreen'
    $removedHudHistoryRender = 'render' + 'InteractiveOverlay'
    $removedHudHistoryScroll = 'scroll' + 'Interactive'
    $removedHudHistoryScrollState = 'interactive' + 'Scroll'
    $removedJKey = 'GLFW_KEY_' + 'J'
    Assert-FileNotContains $keybindFile 'history_scroll_older' "removed caption window scroll-up keybinding must not remain"
    Assert-FileNotContains $keybindFile 'history_scroll_newer' "removed caption window scroll-down keybinding must not remain"
    Assert-FileNotContains $keybindFile 'HudTranslationHistory.scrollWindow' "removed caption window keybindings must not call scroll methods"
    Assert-FileNotContains $zhLangFile 'key.simple_translate.history_scroll_older' "Chinese lang must remove caption window scroll-up keybinding"
    Assert-FileNotContains $zhLangFile 'key.simple_translate.history_scroll_newer' "Chinese lang must remove caption window scroll-down keybinding"
    Assert-FileNotContains $enLangFile 'key.simple_translate.history_scroll_older' "English lang must remove caption window scroll-up keybinding"
    Assert-FileNotContains $enLangFile 'key.simple_translate.history_scroll_newer' "English lang must remove caption window scroll-down keybinding"
    Assert-FileContains $zhLangFile 'screen.simple_translate.hud.history_chat' "Chinese lang must include chat-history setting"
    Assert-FileContains $enLangFile 'screen.simple_translate.hud.history_chat' "English lang must include chat-history setting"
    Assert-FileNotContains $zhLangFile 'screen.simple_translate.hud.history_show_original' "Chinese lang must remove removed original-button setting"
    Assert-FileNotContains $enLangFile 'screen.simple_translate.hud.history_show_original' "English lang must remove removed original-button setting"
    Assert-FileNotContains $zhLangFile 'chat.simple_translate.hud_history' "Chinese lang must remove removed HUD-history chat keys"
    Assert-FileNotContains $enLangFile 'chat.simple_translate.hud_history' "English lang must remove removed HUD-history chat keys"
    Assert-FileNotContains $zhLangFile 'chat.simple_translate.hud_history.original_prefix' "Chinese lang must remove old inline original prefix"
    Assert-FileNotContains $enLangFile 'chat.simple_translate.hud_history.original_prefix' "English lang must remove old inline original prefix"
    Assert-FileNotContains $keybindFile $removedHudHistoryKey "HUD history J/open keybinding must not remain"
    Assert-FileNotContains $keybindFile $removedHudHistoryScreen "HUD history mouse-unlocking overlay screen must not remain wired"
    Assert-FileNotContains $keybindFile $removedJKey "HUD history must not reserve J as a caption-history key"
    Assert-FileNotContains $hudHistoryFile $removedHudHistoryRender "HUD history mouse-unlocking render path must be removed"
    Assert-FileNotContains $hudHistoryFile $removedHudHistoryScroll "HUD history mouse-unlocking scroll path must be removed"
    Assert-FileNotContains $hudHistoryFile $removedHudHistoryScrollState "HUD history mouse-unlocking scroll state must be removed"
    Assert-FileNotContains $enLangFile $removedHudHistoryKey "HUD history key language entry must be removed"
    Assert-FileNotContains $zhLangFile $removedHudHistoryKey "HUD history key language entry must be removed"
    if (Test-Path (Join-Path $src ("gui\" + $removedHudHistoryScreen + ".java"))) {
        throw "HUD history mouse-unlocking overlay screen file must be deleted"
    }
    $bossbarMixin = Join-Path $src "mixin\BossHealthOverlayMixin.java"
    Assert-FileContains $bossbarMixin "DirectSurfaceTranslator.translateComponent" "bossbar must use the direct formatted surface translator"
    Assert-FileContains $bossbarMixin "bossbar.component.direct" "bossbar must use the direct surface key"
    Assert-FileNotContains $bossbarMixin "SemanticBlockTranslationHelper" "bossbar must not use the old semantic compatibility path"
    Assert-FileNotContains $bossbarMixin "bossbar.component.semantic" "bossbar must not use the old semantic surface"
    if (Test-Path -LiteralPath $textDisplayMixin) {
        Assert-FileContains $textDisplayMixin "DirectSurfaceTranslator.translateComponent" "text display must use the direct formatted surface translator"
        Assert-FileContains $textDisplayMixin "text_display.component.direct" "text display must use the direct surface key"
        Assert-FileNotContains $textDisplayMixin "SemanticBlockTranslationHelper" "text display must not use the old semantic compatibility path"
        Assert-FileNotContains $textDisplayMixin "text_display.component.semantic" "text display must not use the old semantic surface"
    } else {
        Assert-FileNotContains $mixinConfigFile '"TextDisplayMixin"' "Minecraft 1.19.x must not register unsupported text display rendering"
    }
    Assert-FileNotContains (Join-Path $src "util\ScoreboardTranslationHelper.java") "translateRawPreserveMarkers" "scoreboard must not use raw marker fallback"
    Assert-FileNotContains $deepSeekFile "Thread.sleep" "DeepSeek request workers must not block while retrying"
} finally {
    Pop-Location
}
