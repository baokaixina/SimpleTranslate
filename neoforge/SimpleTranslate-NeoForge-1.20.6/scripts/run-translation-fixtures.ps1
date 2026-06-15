param(
    [string]$ProjectDir = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$FixturePath = ""
)

$ErrorActionPreference = "Stop"

Push-Location $ProjectDir
try {
    if ([string]::IsNullOrWhiteSpace($FixturePath)) {
        $FixturePath = Join-Path $ProjectDir "scripts\translation-fixtures.json"
    }
    $FixturePath = (Resolve-Path $FixturePath).Path

    & .\gradlew.bat compileJava --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "compileJava failed with exit code $LASTEXITCODE"
    }

    $gsonJar = Get-ChildItem -Path "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $gsonJar) {
        throw "Could not find gson jar in Gradle cache"
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-direct-fixtures-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateDirectFixtureChecks.java"

    $javaSource = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.core.NumberGuard;
import com.yourname.simpletranslate.core.RecoveryPolicy;
import com.yourname.simpletranslate.core.WireCodec;
import com.yourname.simpletranslate.translation.OcrTranslationService;
import com.yourname.simpletranslate.translation.VisionOcrTranslationService;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectStatusTerms;
import com.yourname.simpletranslate.util.HudTranslationHistory;
import com.yourname.simpletranslate.util.SignDirectDocument;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SimpleTranslateDirectFixtureChecks {
    public static void main(String[] args) {
        try {
            runChecks(args);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void runChecks(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SimpleTranslateDirectFixtureChecks <fixtures.json>");
        }
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        assertEquals("direct-formatted-fixtures-v1", root.get("version").getAsString(), "fixture file version");

        checkRingTooltip();
        checkAcknowledgementTooltip();
        checkSignedIdPapersTooltipStyles();
        checkMovableManaCost();
        checkTagLossDegradation();
        checkOneBasedNumberingTolerance();
        checkPartialLineApplication();
        checkNumberGuardFallback();
        checkCrossLineNumberMovement();
        checkNegativeCachePolicy();
        checkBatchWireRoundTrip();
        checkSignGroupByIdDocument();
        checkAutoSignSingleDocument();
        checkSignModeEpochIsolation();
        checkSharedSignComponentCacheSerialization();
        checkItemTooltipContextPayload();
        checkScoreboardDirectKey();
        checkHudTitleGroupDirect();
        checkHudCaptionHistoryBatchDirect();
        checkLoadedStatusConsistency();
        checkFullwidthSystemStatusDetection();
        checkKoreanPlainFallbackAndResidualValidation();
        checkChatSystemAbbreviationMessage();
        checkOcrVisionJsonParsing();
        System.out.println("SimpleTranslate direct formatted fixtures passed");
    }

    private static void checkRingTooltip() {
        Component source = Component.empty()
                .append(Component.literal("Ring").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(" Amulet Slot").withStyle(ChatFormatting.GRAY));

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(document, "[TEXT lines=1]", "ring tooltip wire header");
        assertContains(document, "0|<1>Ring</1><2> Amulet Slot</2>", "ring styled runs are tagged");
        String translated = document
                .replace("<1>Ring</1>", "<1>\u6212\u6307</1>")
                .replace("<2> Amulet Slot</2>", "<2>\u62a4\u7b26\u69fd</2>");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "ring tooltip restore");
        assertEquals("\u6212\u6307\u62a4\u7b26\u69fd", restored.get(0).getString(), "ring tooltip text");

        List<TextSegmentInfo> segments = segments(restored.get(0));
        if (segments.size() < 2) {
            throw new AssertionError("ring tooltip restore must keep separate styled segments");
        }
        assertEquals("\u6212\u6307", segments.get(0).text, "ring translated prefix text");
        assertEquals(ChatFormatting.AQUA.getColor(), segments.get(0).style.getColor().getValue(), "ring prefix color");
        assertEquals(Boolean.TRUE, segments.get(0).style.isBold(), "ring prefix bold");
        assertEquals("\u62a4\u7b26\u69fd", segments.get(1).text.trim(), "ring translated suffix text");
        assertEquals(ChatFormatting.GRAY.getColor(), segments.get(1).style.getColor().getValue(), "ring suffix color");
    }

    private static void checkAcknowledgementTooltip() {
        List<Component> source = List.of(
                Component.literal("Acknowledgement Of Excellence").withStyle(ChatFormatting.GREEN),
                Component.literal("A certificate stating one's achievement, acknowledged by a few organizations around the world.")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(true)),
                Component.literal("Used for Special Trades.").withStyle(ChatFormatting.GOLD)
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(document, "[TEXT lines=3]", "acknowledgement wire header");
        String translated = document
                .replace("0|Acknowledgement Of Excellence", "0|\u5353\u8d8a\u8bc1\u660e")
                .replace("1|A certificate stating one's achievement, acknowledged by a few organizations around the world.",
                        "1|\u4e00\u4efd\u8bc1\u660e\u6301\u6709\u8005\u6210\u5c31\u5e76\u83b7\u5f97\u4e16\u754c\u5404\u5730\u5c11\u6570\u7ec4\u7ec7\u8ba4\u53ef\u7684\u8bc1\u4e66\u3002")
                .replace("2|Used for Special Trades.", "2|\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "acknowledgement restore");
        assertEquals("\u5353\u8d8a\u8bc1\u660e", restored.get(0).getString(), "title text");
        assertEquals("\u4e00\u4efd\u8bc1\u660e\u6301\u6709\u8005\u6210\u5c31\u5e76\u83b7\u5f97\u4e16\u754c\u5404\u5730\u5c11\u6570\u7ec4\u7ec7\u8ba4\u53ef\u7684\u8bc1\u4e66\u3002", restored.get(1).getString(), "lore text");
        assertEquals("\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002", restored.get(2).getString(), "usage text");
        assertEquals(ChatFormatting.GREEN.getColor(), firstStyle(restored.get(0)).getColor().getValue(), "title color");
        assertEquals(ChatFormatting.LIGHT_PURPLE.getColor(), firstStyle(restored.get(1)).getColor().getValue(), "lore color");
        assertEquals(Boolean.TRUE, firstStyle(restored.get(1)).isItalic(), "lore italic");
        assertEquals(ChatFormatting.GOLD.getColor(), firstStyle(restored.get(2)).getColor().getValue(), "usage color");
    }

    private static void checkSignedIdPapersTooltipStyles() {
        Component title = Component.literal("Signed ID Papers").withStyle(ChatFormatting.GREEN);
        Component name = Component.empty()
                .append(Component.literal("Name: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Milton, Luke").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withItalic(true)));
        Component age = Component.empty()
                .append(Component.literal("Age: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("[REDACTED]").withStyle(ChatFormatting.RED));
        Component lore = Component.literal("Some documents with some personal info, signed by you.")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true));

        List<Component> source = List.of(title, name, age, lore);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(document, "<1>Name: </1>", "signed papers name label tagged");
        assertContains(document, "<2>[REDACTED]</2>", "signed papers redacted token tagged");
        String translated = document
                .replace("0|Signed ID Papers", "0|\u5df2\u7b7e\u7f72\u7684\u8eab\u4efd\u6587\u4ef6")
                .replace("<1>Name: </1>", "<1>\u59d3\u540d\uff1a</1>")
                .replace("<1>Age: </1>", "<1>\u5e74\u9f84\uff1a</1>")
                .replace("<2>[REDACTED]</2>", "<2>[\u5df2\u7f16\u8f91]</2>")
                .replace("3|Some documents with some personal info, signed by you.",
                        "3|\u4e00\u4e9b\u5305\u542b\u4e2a\u4eba\u4fe1\u606f\u7684\u6587\u4ef6\uff0c\u7531\u4f60\u7b7e\u7f72\u3002");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "signed papers restore");
        assertEquals(source.size(), restored.size(), "signed papers line count");
        assertEquals("\u5df2\u7b7e\u7f72\u7684\u8eab\u4efd\u6587\u4ef6", restored.get(0).getString(), "signed papers title text");
        assertEquals("\u59d3\u540d\uff1aMilton, Luke", restored.get(1).getString(), "signed papers name text");
        assertEquals("\u5e74\u9f84\uff1a[\u5df2\u7f16\u8f91]", restored.get(2).getString(), "signed papers age text");
        List<TextSegmentInfo> nameSegments = segments(restored.get(1));
        assertEquals(Boolean.TRUE, nameSegments.get(nameSegments.size() - 1).style.isItalic(),
                "preserved proper-name segment keeps italic style");
        List<TextSegmentInfo> ageSegments = segments(restored.get(2));
        assertEquals(ChatFormatting.GREEN.getColor(), ageSegments.get(0).style.getColor().getValue(),
                "age label keeps green base style");
        assertEquals(ChatFormatting.RED.getColor(), ageSegments.get(ageSegments.size() - 1).style.getColor().getValue(),
                "translated redacted token keeps red style");
    }

    private static void checkMovableManaCost() {
        Component source = Component.empty()
                .append(Component.literal("while draining ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("15").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" Mana").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" every ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("0.4").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" until you don't have enough ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Mana").withStyle(ChatFormatting.AQUA));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(document, "<2>15</2>", "mana cost number stays literal in its tag");
        assertContains(document, "<5>0.4</5>", "interval number stays literal in its tag");

        // The model reorders tags to natural Chinese word order; numbers ride inside their tags.
        String reordered = "0|\u6bcf<5>0.4</5>\u79d2\u6d88\u8017<2>15</2><3> \u70b9\u6cd5\u529b</3>\uff0c"
                + "\u76f4\u5230\u4f60\u6ca1\u6709\u8db3\u591f\u7684<7>\u6cd5\u529b</7>";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, reordered);
        assertNotNull(restored, "mana cost reorder restore");
        String visible = restored.get(0).getString();
        assertContains(visible, "\u6bcf0.4\u79d2\u6d88\u801715", "mana cost numbers stay with their words");
        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.GREEN.getColor(), findSegment(segments, "0.4").style.getColor().getValue(),
                "interval number keeps green style after reorder");
        assertEquals(ChatFormatting.GREEN.getColor(), findSegment(segments, "15").style.getColor().getValue(),
                "mana cost number keeps green style after reorder");

        // Swapping digits between tags is caught by the per-line number guard...
        String swapped = "0|\u6bcf<5>15</5>\u79d2\u6d88\u8017<2>0.4</2><3> \u70b9\u6cd5\u529b</3><7>\u6cd5\u529b</7>";
        List<Component> swappedRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, swapped);
        if (swappedRestored != null) {
            // Non-editable number tags always restore their original source digits,
            // so even a "swap" cannot change the displayed values.
            String swappedVisible = swappedRestored.get(0).getString();
            assertContains(swappedVisible, "0.4", "original interval value survives");
            assertContains(swappedVisible, "15", "original cost value survives");
            if (swappedVisible.contains("\u6bcf15\u79d2")) {
                throw new AssertionError("swapped numbers must not present 15 as the interval: " + swappedVisible);
            }
        }
    }

    private static void checkTagLossDegradation() {
        Component source = Component.empty()
                .append(Component.literal("[ Tip ]: Most Enemies cannot attack ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("[Fortified]").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" Objectives.").withStyle(ChatFormatting.GOLD));
        // Model dropped every tag but still translated; line must survive with anchored styling.
        String untagged = "0|[\u63d0\u793a]\uff1a\u5927\u591a\u6570\u654c\u4eba\u65e0\u6cd5\u653b\u51fb[\u52a0\u56fa]\u76ee\u6807\u3002";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, untagged);
        assertNotNull(restored, "tag loss must not reject the translation");
        String visible = restored.get(0).getString();
        assertContains(visible, "[\u63d0\u793a]", "tag-loss line keeps translated text");
        assertContains(visible, "[\u52a0\u56fa]", "tag-loss line keeps translated bracket token");
        // Bracket-count anchor mapping keeps the second bracket yellow.
        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.YELLOW.getColor(), findSegment(segments, "[\u52a0\u56fa]").style.getColor().getValue(),
                "anchored bracket token keeps yellow style");
    }

    private static void checkOneBasedNumberingTolerance() {
        List<Component> source = List.of(
                Component.literal("First line of lore.").withStyle(ChatFormatting.GRAY),
                Component.literal("Second line of lore.").withStyle(ChatFormatting.GRAY));
        Map<Integer, String> shifted = WireCodec.parseResponse("1|\u7532\n2|\u4e59", 2);
        assertNotNull(shifted, "one-based numbering parses");
        assertEquals("\u7532", shifted.get(0), "one-based line 1 maps to index 0");
        assertEquals("\u4e59", shifted.get(1), "one-based line 2 maps to index 1");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, "1|\u7532\n2|\u4e59");
        assertNotNull(restored, "one-based response restores");
        assertEquals("\u7532", restored.get(0).getString(), "one-based restore first line");
        assertEquals("\u4e59", restored.get(1).getString(), "one-based restore second line");
    }

    private static void checkPartialLineApplication() {
        List<Component> source = List.of(
                Component.literal("First line of lore.").withStyle(ChatFormatting.GRAY),
                Component.literal("Second line of lore.").withStyle(ChatFormatting.GRAY));
        String response = "0|\u7b2c\u4e00\u884c\u4f20\u8bf4\u3002";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, response);
        assertNotNull(restored, "partial response applies by line number");
        assertEquals("\u7b2c\u4e00\u884c\u4f20\u8bf4\u3002", restored.get(0).getString(), "returned line is translated");
        assertEquals("Second line of lore.", restored.get(1).getString(), "missing line keeps the original text");
        assertEquals(ChatFormatting.GRAY.getColor(), firstStyle(restored.get(1)).getColor().getValue(),
                "missing line keeps the original style");
    }

    private static void checkNumberGuardFallback() {
        if (!NumberGuard.linePasses("every 10 Seconds", "\u6bcf 10 \u79d2")) {
            throw new AssertionError("matching numeric multiset must pass the guard");
        }
        if (NumberGuard.linePasses("every 10 Seconds", "\u6bcf 1 \u79d2")) {
            throw new AssertionError("changed digits must fail the guard");
        }
        if (NumberGuard.linePasses("no numbers here", "\u51fa\u73b0\u4e86 3 \u4e2a\u6570\u5b57")) {
            throw new AssertionError("invented digits must fail the guard");
        }
        if (!NumberGuard.linePasses("Else, you'll take 1 by 1.", "\u5426\u5219\uff0c\u6bcf\u6b21\u4e00\u4e2a\u4e00\u4e2a\u53d6\u3002")) {
            throw new AssertionError("Chinese numerals must satisfy the same numeric multiset as source digits");
        }
        if (NumberGuard.linePasses("Else, you'll take 1 by 1.", "\u5426\u5219\uff0c\u6bcf\u6b21 2 \u9897\u3002")) {
            throw new AssertionError("wrong Chinese translation digits must fail the guard");
        }

        List<Component> source = List.of(
                Component.literal("Crystallisation").withStyle(ChatFormatting.AQUA),
                Component.literal("This Pylon produces 1 Emerald every 10 Seconds.").withStyle(ChatFormatting.GOLD));
        String bad = "0|\u7ed3\u6676\u5316\n1|\u6b64\u80fd\u91cf\u5854\u6bcf 1 \u79d2\u4ea7\u51fa 1 \u9897\u7eff\u5b9d\u77f3\u3002";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, bad);
        assertNotNull(restored, "document with one guarded line still restores");
        assertEquals("\u7ed3\u6676\u5316", restored.get(0).getString(), "title line translated");
        assertEquals("This Pylon produces 1 Emerald every 10 Seconds.", restored.get(1).getString(),
                "wrong-number line falls back to the original English text");
    }

    private static void checkCrossLineNumberMovement() {
        // Wrapped sentences legitimately move numbers between adjacent lines.
        List<Component> source = List.of(
                Component.literal("Will seek the closest enemy").withStyle(ChatFormatting.GOLD),
                Component.literal("within 5 blocks.").withStyle(ChatFormatting.GOLD));
        String response = "0|\u5c06\u81ea\u52a8\u8ffd\u8e2a 5 \u683c\u5185\n1|\u6700\u8fd1\u7684\u654c\u4eba\u3002";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, response);
        assertNotNull(restored, "cross-line number movement restores");
        assertEquals("\u5c06\u81ea\u52a8\u8ffd\u8e2a 5 \u683c\u5185", restored.get(0).getString(),
                "number may move to the previous wrapped line");
        assertEquals("\u6700\u8fd1\u7684\u654c\u4eba\u3002", restored.get(1).getString(),
                "continuation line stays translated");

        // A document that loses or invents digits reverts the offending lines;
        // when every editable line is reverted, the whole response is rejected.
        String wrong = "0|\u5c06\u81ea\u52a8\u8ffd\u8e2a 50 \u683c\u5185\n1|\u6700\u8fd1\u7684\u654c\u4eba\u3002";
        if (DirectFormattedTranslationPipeline.restoreForTest(
                source, TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, wrong) != null) {
            throw new AssertionError("wrong-digit document with no surviving translation must be rejected");
        }
    }

    private static void checkNegativeCachePolicy() {
        RecoveryPolicy.clearAll();
        String key = "direct:v18-mintag:test.surface:deadbeef";
        for (int i = 0; i < 3; i++) {
            RecoveryPolicy.recordRejected(key);
            if (!RecoveryPolicy.shouldAttempt(key)) {
                throw new AssertionError("fewer than 4 rejections must not freeze the key (attempt " + i + ")");
            }
        }
        RecoveryPolicy.recordRejected(key);
        if (RecoveryPolicy.shouldAttempt(key)) {
            throw new AssertionError("4 consecutive rejections must freeze the key");
        }
        RecoveryPolicy.recordSuccess(key);
        if (!RecoveryPolicy.shouldAttempt(key)) {
            throw new AssertionError("success must clear the negative cache entry");
        }
        RecoveryPolicy.clearAll();
    }

    private static void checkBatchWireRoundTrip() {
        String batch = WireCodec.batchPayload(List.of("[TEXT lines=1]\n0|Hello\n[/TEXT]", "[TEXT lines=1]\n0|World\n[/TEXT]"));
        assertContains(batch, "[BATCH items=2]", "batch header");
        assertContains(batch, "[ITEM 0]", "batch first item header");
        assertContains(batch, "[ITEM 1]", "batch second item header");
        Map<Integer, String> parsed = WireCodec.parseBatchResponse(
                "[ITEM 0]\n0|\u4f60\u597d\n[/ITEM 0]\n[ITEM 1]\n0|\u4e16\u754c\n[/ITEM 1]");
        assertEquals(2, parsed.size(), "batch response item count");
        assertContains(parsed.get(0), "0|\u4f60\u597d", "batch first item body");
        assertContains(parsed.get(1), "0|\u4e16\u754c", "batch second item body");
    }

    private static void checkSignGroupByIdDocument() {
        String idA = "minecraft:overworld:1,64,1:front:aaaa";
        String idB = "minecraft:overworld:2,64,1:front:bbbb";
        List<SignDirectDocument.Entry> entries = List.of(
                new SignDirectDocument.Entry(idA, "stateA", List.of(
                        Component.literal("Start Game").withStyle(ChatFormatting.WHITE),
                        Component.literal("From Here").withStyle(ChatFormatting.WHITE),
                        Component.literal("[Click to TP]").withStyle(ChatFormatting.GREEN),
                        Component.empty()
                ), new String[] { "Start Game", "From Here", "[Click to TP]", "" }, 1L, true),
                new SignDirectDocument.Entry(idB, "stateB", List.of(
                        Component.literal("1. This map is").withStyle(ChatFormatting.WHITE),
                        Component.literal("made for version").withStyle(ChatFormatting.WHITE),
                        Component.literal("1.20.1").withStyle(ChatFormatting.WHITE),
                        Component.literal("/trigger Settings").withStyle(ChatFormatting.GOLD)
                ), new String[] { "1. This map is", "made for version", "1.20.1", "/trigger Settings" }, 2L, true)
        );
        String payload = SignDirectDocument.requestPayloadForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context");
        assertContains(payload, "[TEXT lines=8]", "two signs serialize eight positional lines");
        assertContains(payload, "0|Start Game", "sign A first line");
        assertContains(payload, "4|1. This map is", "sign B first line");
        assertContains(payload, "Each sign has exactly 4 short lines", "sign grouping note");

        String valid = "0|\u5f00\u59cb\u6e38\u620f\n1|\u4ece\u8fd9\u91cc\u5f00\u59cb\n2|[\u70b9\u51fb\u4f20\u9001]\n3|\n"
                + "4|1. \u8fd9\u5f20\u5730\u56fe\n5|\u662f\u4e3a\u7248\u672c\n6|1.20.1\n7|/trigger \u8bbe\u7f6e";
        SignDirectDocument.RestoreResult restored = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", valid);
        assertEquals(Boolean.TRUE, restored.success(), "sign group restore reason=" + restored.failureReason());
        Map<String, Component[]> byId = restored.componentsBySignId();
        assertEquals("\u5f00\u59cb\u6e38\u620f", byId.get(idA)[0].getString(), "sign A writeback by id");
        assertEquals("[\u70b9\u51fb\u4f20\u9001]", byId.get(idA)[2].getString(), "sign A line 3 by id");
        assertEquals(ChatFormatting.GREEN.getColor(), byId.get(idA)[2].getStyle().getColor() == null
                ? firstStyle(byId.get(idA)[2]).getColor().getValue()
                : byId.get(idA)[2].getStyle().getColor().getValue(), "sign A click line keeps green");
        assertEquals("1.20.1", byId.get(idB)[2].getString(), "sign B version stays on sign B");
        assertEquals("/trigger \u8bbe\u7f6e", byId.get(idB)[3].getString(), "sign B command stays on sign B");

        // A protected token moved across both signs violates both -> whole group rejected.
        String movedAcrossSigns = valid
                .replace("0|\u5f00\u59cb\u6e38\u620f", "0|\u5f00\u59cb\u6e38\u620f /trigger")
                .replace("7|/trigger \u8bbe\u7f6e", "7|\u8bbe\u7f6e");
        SignDirectDocument.RestoreResult moved = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", movedAcrossSigns);
        if (moved.success()) {
            throw new AssertionError("token movement that corrupts every translatable sign must be rejected");
        }

        // Only one sign violated: the healthy sign keeps its translation, the violated one degrades.
        String oneSignDropsToken = valid.replace("7|/trigger \u8bbe\u7f6e", "7|\u8bbe\u7f6e");
        SignDirectDocument.RestoreResult partial = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", oneSignDropsToken);
        assertEquals(Boolean.TRUE, partial.success(), "single violated sign degrades instead of rejecting the group");
        assertEquals("\u5f00\u59cb\u6e38\u620f", partial.componentsBySignId().get(idA)[0].getString(),
                "healthy sign keeps its translation");
        assertEquals("/trigger Settings", partial.componentsBySignId().get(idB)[3].getString(),
                "violated sign falls back to original lines");

        // Tokens may move within the same sign.
        String movedWithinSign = valid
                .replace("4|1. \u8fd9\u5f20\u5730\u56fe", "4|1. \u8fd9\u5f20\u5730\u56fe /trigger")
                .replace("7|/trigger \u8bbe\u7f6e", "7|\u8bbe\u7f6e");
        SignDirectDocument.RestoreResult within = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", movedWithinSign);
        assertEquals(Boolean.TRUE, within.success(),
                "protected token may move within the same sign reason=" + within.failureReason());
        assertContains(within.componentsBySignId().get(idB)[0].getString(), "/trigger",
                "token kept within the same sign");
    }

    private static void checkAutoSignSingleDocument() {
        String signId = "minecraft:overworld:4,64,8:front:auto";
        List<SignDirectDocument.Entry> entries = List.of(
                new SignDirectDocument.Entry(signId, "autoState", List.of(
                        Component.literal("This shop").withStyle(ChatFormatting.WHITE),
                        Component.literal("sells rare").withStyle(ChatFormatting.WHITE),
                        Component.literal("items today").withStyle(ChatFormatting.WHITE),
                        Component.literal("[Click to Buy]").withStyle(ChatFormatting.GREEN)
                ), new String[] { "This shop", "sells rare", "items today", "[Click to Buy]" }, 0L, true)
        );
        SignDirectDocument.Document auto = SignDirectDocument.fromEntries(entries,
                "sign.auto.single.direct",
                "sign-auto-single-by-id",
                "Automatic sign mode translates only this one sign. Treat the four visible lines below as one complete sign message.");
        assertContains(auto.requestPayload(), "Treat the four visible lines below as one complete sign message",
                "auto sign request includes whole-sign semantic context");
        assertContains(auto.requestPayload(), "[TEXT lines=4]", "auto sign serializes four lines");
        String manualKey = TranslationCacheKeys.key("sign.manual.group.by_id.direct",
                auto.sourceText(), auto.context(), auto.layoutSignature(), auto.styleSignature());
        if (auto.cacheKey().equals(manualKey)) {
            throw new AssertionError("automatic sign cache key must not collide with manual sign surface");
        }

        String translated = "0|\u672c\u5546\u5e97\n1|\u4eca\u65e5\u51fa\u552e\n2|\u7a00\u6709\u7269\u54c1\n3|[\u70b9\u51fb\u8d2d\u4e70]";
        SignDirectDocument.RestoreResult restored = auto.restore(translated);
        assertEquals(Boolean.TRUE, restored.success(), "auto sign document restores reason=" + restored.failureReason());
        Component[] lines = restored.componentsBySignId().get(signId);
        assertEquals(4, lines.length, "auto sign restore keeps four lines");
        assertEquals("\u672c\u5546\u5e97", lines[0].getString(), "auto sign line 1 restored");
        assertEquals("\u4eca\u65e5\u51fa\u552e", lines[1].getString(), "auto sign line 2 restored with block context");
        assertEquals("\u7a00\u6709\u7269\u54c1", lines[2].getString(), "auto sign line 3 restored with block context");
        assertEquals("[\u70b9\u51fb\u8d2d\u4e70]", lines[3].getString(), "auto sign line 4 restored");
    }

    private static void checkSignModeEpochIsolation() {
        long before = SignTranslationHelper.signModeEpochForTest();
        SignTranslationHelper.bumpSignModeEpochForTest();
        long manual = SignTranslationHelper.signModeEpochForTest();
        if (manual <= before) {
            throw new AssertionError("AUTO -> MANUAL must advance sign mode epoch so stale auto responses cannot write back");
        }
        SignTranslationHelper.bumpSignModeEpochForTest();
        long automatic = SignTranslationHelper.signModeEpochForTest();
        if (automatic <= manual) {
            throw new AssertionError("MANUAL -> AUTO must advance sign mode epoch so stale manual responses cannot write back");
        }
    }

    private static void checkSharedSignComponentCacheSerialization() {
        Component[] source = new Component[] {
                Component.literal("\u5f00\u59cb\u6e38\u620f").withStyle(ChatFormatting.WHITE),
                Component.literal("\u4ece\u8fd9\u91cc\u5f00\u59cb").withStyle(ChatFormatting.WHITE),
                Component.literal("[\u70b9\u51fb\u4f20\u9001]").withStyle(ChatFormatting.GREEN),
                Component.empty()
        };
        String serialized = SignTranslationHelper.serializeSharedSignComponentsForTest(source);
        assertNotNull(serialized, "shared sign component serialization");
        assertContains(serialized, "sign-components-v1", "shared sign component cache protocol");
        Component[] restored = SignTranslationHelper.deserializeSharedSignComponentsForTest(serialized);
        assertNotNull(restored, "shared sign component deserialization");
        assertEquals(4, restored.length, "shared sign component cache keeps four lines");
        assertEquals("[\u70b9\u51fb\u4f20\u9001]", restored[2].getString(), "shared sign component cache preserves translated line text");
        assertEquals(ChatFormatting.GREEN.getColor(), restored[2].getStyle().getColor().getValue(),
                "shared sign component cache preserves translated line style");
    }

    private static void checkItemTooltipContextPayload() {
        Component source = Component.empty()
                .append(Component.literal("[ Tip ]: Most Enemies cannot attack ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("[Fortified]").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" Objectives. Only ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Siege Enemies").withStyle(ChatFormatting.RED))
                .append(Component.literal(" can do so.").withStyle(ChatFormatting.GOLD));

        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false,
                TooltipTranslationHelper.buildItemTooltipContext(List.of(source)));
        assertContains(request, "[CONTEXT]", "item tooltip request context section");
        assertContains(request, "Item tooltip semantic block", "item tooltip context block");
        assertContains(request,
                "line 0 [title]: [ Tip ]: Most Enemies cannot attack [Fortified] Objectives. Only Siege Enemies can do so.",
                "item tooltip context keeps numbered source line");
        assertContains(request, "[NOTE]", "item tooltip note section");
        assertContains(request, "[TEXT lines=1]", "item tooltip wire document");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        String translated = document
                .replace("<1>[ Tip ]: Most Enemies cannot attack </1>", "<1>[\u63d0\u793a]\uff1a\u5927\u591a\u6570\u654c\u4eba\u65e0\u6cd5\u653b\u51fb </1>")
                .replace("<2>[Fortified]</2>", "<2>[\u52a0\u56fa]</2>")
                .replace("<3> Objectives. Only </3>", "<3> \u76ee\u6807\u3002\u53ea\u6709 </3>")
                .replace("<4>Siege Enemies</4>", "<4>\u653b\u57ce\u654c\u4eba</4>")
                .replace("<5> can do so.</5>", "<5> \u624d\u80fd\u505a\u5230\u3002</5>");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "split tip restore");
        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.YELLOW.getColor(), findSegment(segments, "[\u52a0\u56fa]").style.getColor().getValue(),
                "split tip fortified token keeps yellow");
        assertEquals(ChatFormatting.RED.getColor(), findSegment(segments, "\u653b\u57ce\u654c\u4eba").style.getColor().getValue(),
                "split tip siege enemies keeps red");
    }

    private static void checkScoreboardDirectKey() {
        String tooltipKey = TranslationCacheKeys.key(TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                "Score Leaderboard", "line 0 [title]: Score Leaderboard", "title", "green");
        String scoreboardKey = TranslationCacheKeys.key("scoreboard.component.direct", "Score Leaderboard", "", "title", "white");
        if (tooltipKey.equals(scoreboardKey)) {
            throw new AssertionError("different surfaces must not share direct cache keys");
        }
        assertContains(tooltipKey, "direct:v18-mintag:tooltip.item_context.direct:", "tooltip key protocol");
        assertContains(scoreboardKey, "direct:v18-mintag:scoreboard.component.direct:", "scoreboard key protocol");
        assertContains(tooltipKey, ":lang=", "tooltip key language isolation");
        assertEquals("tooltip", TranslationCacheKeys.laneFromKey(tooltipKey), "tooltip lane");
        assertEquals("scoreboard", TranslationCacheKeys.laneFromKey(scoreboardKey), "scoreboard lane");
        assertEquals("tooltip_hover", TranslationCacheKeys.requestLaneFromSurface("tooltip.item_context.direct"),
                "tooltip request lane");
        assertEquals("sign_manual", TranslationCacheKeys.requestLaneFromSurface("sign.manual.group.by_id.direct"),
                "manual sign request lane");
    }

    private static void checkHudTitleGroupDirect() {
        Component title = Component.literal("Network Reconnected").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("Return to the control room").withStyle(ChatFormatting.YELLOW);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false);
        assertContains(document, "0|Network Reconnected", "title source included");
        assertContains(document, "1|Return to the control room", "subtitle source included");

        String translated = document
                .replace("0|Network Reconnected", "0|\u7f51\u7edc\u5df2\u91cd\u8fde")
                .replace("1|Return to the control room", "1|\u8fd4\u56de\u63a7\u5236\u5ba4");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false, translated);
        assertNotNull(restored, "title/subtitle grouped restore");
        assertEquals(2, restored.size(), "title/subtitle grouped result count");
        assertEquals("\u7f51\u7edc\u5df2\u91cd\u8fde", restored.get(0).getString(), "title grouped translation");
        assertEquals("\u8fd4\u56de\u63a7\u5236\u5ba4", restored.get(1).getString(), "subtitle grouped translation");
        assertEquals(ChatFormatting.GOLD.getColor(), firstStyle(restored.get(0)).getColor().getValue(),
                "title grouped color");
        assertEquals(ChatFormatting.YELLOW.getColor(), firstStyle(restored.get(1)).getColor().getValue(),
                "subtitle grouped color");

        Component noisySubtitle = Component.literal("don~ 6onUeonnaonlbone9ona3onsconh donY5ono6onueonr aonPbono9onw3oneconr don~")
                .withStyle(ChatFormatting.DARK_PURPLE);
        Component naturalTitle = Component.literal("I LOVE YOU BABY\u2727").withStyle(ChatFormatting.GOLD);
        String noisyDocument = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(naturalTitle, noisySubtitle), "hud.title_group.component.direct", "title-subtitle", false);
        String noisyTranslated = noisyDocument.replace("0|I LOVE YOU BABY\u2727", "0|\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727");
        List<Component> noisyRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(naturalTitle, noisySubtitle), "hud.title_group.component.direct", "title-subtitle", false, noisyTranslated);
        assertNotNull(noisyRestored, "legacy HUD formatting noise should not reject grouped title restore");
        assertEquals("\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727", noisyRestored.get(0).getString(), "natural title translated with noisy subtitle");
        assertEquals(noisySubtitle.getString(), noisyRestored.get(1).getString(), "legacy HUD noise left unchanged");
    }

    private static void checkHudCaptionHistoryBatchDirect() {
        Component title = Component.literal("Radio: The agency wishes their most").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("sincere apologies for the secrecy.").withStyle(ChatFormatting.YELLOW);
        Component actionbar = Component.literal("Find the fuse box").withStyle(ChatFormatting.AQUA);
        List<Component> source = List.of(title, subtitle, actionbar);
        String context = "Recent HUD caption history before the current batch\n"
                + "1. [\u6807\u9898]\n"
                + "   original: The broadcast begins.\n"
                + "   translated: \u5e7f\u64ad\u5f00\u59cb\u3002";
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                source, HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false, context);
        assertContains(request, "[CONTEXT]", "HUD history batch context section");
        assertContains(request, "Recent HUD caption history before the current batch", "HUD history batch context");
        assertContains(request, "[NOTE]", "HUD history passage note");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false);
        assertContains(document, "[TEXT lines=3]", "HUD history caption batch line count");
        String translated = document
                .replace("0|Radio: The agency wishes their most", "0|\u7535\u53f0\uff1a\u673a\u6784\u81f4\u4ee5\u6700\u8bda\u631a\u7684")
                .replace("1|sincere apologies for the secrecy.", "1|\u4fdd\u5bc6\u6b49\u610f\u3002")
                .replace("2|Find the fuse box", "2|\u627e\u5230\u4fdd\u9669\u4e1d\u76d2");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false, translated);
        assertNotNull(restored, "HUD history caption batch restore");
        assertEquals(3, restored.size(), "HUD history caption batch line count");
        assertEquals("\u7535\u53f0\uff1a\u673a\u6784\u81f4\u4ee5\u6700\u8bda\u631a\u7684", restored.get(0).getString(), "HUD history batch title");
        assertEquals("\u4fdd\u5bc6\u6b49\u610f\u3002", restored.get(1).getString(), "HUD history batch subtitle");
        assertEquals("\u627e\u5230\u4fdd\u9669\u4e1d\u76d2", restored.get(2).getString(), "HUD history batch actionbar");
        assertEquals(ChatFormatting.GOLD.getColor(), firstStyle(restored.get(0)).getColor().getValue(),
                "HUD history batch title color");
        assertEquals(ChatFormatting.YELLOW.getColor(), firstStyle(restored.get(1)).getColor().getValue(),
                "HUD history batch subtitle color");
        assertEquals(ChatFormatting.AQUA.getColor(), firstStyle(restored.get(2)).getColor().getValue(),
                "HUD history batch actionbar color");
        List<Component> plainFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                source, HudTranslationHistory.BATCH_SURFACE, HudTranslationHistory.BATCH_ROLE, false,
                "\u7535\u53f0\uff1a\u673a\u6784\u81f4\u4ee5\u6700\u8bda\u631a\u7684\n\u4fdd\u5bc6\u6b49\u610f\u3002\n\u627e\u5230\u4fdd\u9669\u4e1d\u76d2");
        assertNotNull(plainFallback, "HUD history batch recovers plain same-count response");
        assertEquals("\u4fdd\u5bc6\u6b49\u610f\u3002", plainFallback.get(1).getString(), "HUD history plain fallback subtitle");

        HudTranslationHistory.clear();
        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.TITLE,
                "caption-title", "caption-title-source", title, title);
        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.SUBTITLE,
                "caption-subtitle", "caption-subtitle-source", subtitle, subtitle);
        HudTranslationHistory.recordCaption(HudTranslationHistory.CaptionType.ACTIONBAR,
                "caption-actionbar", "caption-actionbar-source", actionbar, actionbar);
        HudTranslationHistory.completeCaptionForTest("caption-actionbar", Component.literal("\u627e\u5230\u4fdd\u9669\u4e1d\u76d2"));
        HudTranslationHistory.completeCaptionForTest("caption-title", Component.literal("\u7535\u53f0\uff1a\u673a\u6784\u81f4\u4ee5\u6700\u8bda\u631a\u7684"));
        HudTranslationHistory.completeCaptionForTest("caption-subtitle", Component.literal("\u4fdd\u5bc6\u6b49\u610f\u3002"));
        List<HudTranslationHistory.Entry> entries = HudTranslationHistory.entriesSnapshot();
        assertEquals("[\u6807\u9898]", entries.get(0).type().label(), "HUD history title prefix");
        assertEquals("[\u526f\u6807\u9898]", entries.get(1).type().label(), "HUD history subtitle prefix");
        assertEquals("[\u52a8\u4f5c\u680f]", entries.get(2).type().label(), "HUD history actionbar prefix");
        assertEquals("\u7535\u53f0\uff1a\u673a\u6784\u81f4\u4ee5\u6700\u8bda\u631a\u7684", entries.get(0).translatedText(), "HUD history keeps source order after out-of-order completion");
        HudTranslationHistory.clear();
    }

    private static void checkLoadedStatusConsistency() {
        List<Component> source = List.of(
                Component.empty()
                        .append(Component.literal("\u226b [Animate Heroes]").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" \u2192 ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\u2714 Loaded").withStyle(ChatFormatting.GREEN)),
                Component.empty()
                        .append(Component.literal("\u226b [Siege]").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" \u2192 ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\u2714 Loaded").withStyle(ChatFormatting.GREEN)),
                Component.empty()
                        .append(Component.literal("\u226b [Enemies]").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(" \u2192 ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("\u2714 Loaded").withStyle(ChatFormatting.GREEN))
        );
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                source, ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, "[GLOSSARY]", "loaded status glossary");
        assertContains(request, "Loaded -> \u5df2\u52a0\u8f7d", "loaded status glossary target");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        String translated = document
                .replace("\u226b [Animate Heroes]", "\u226b [\u52a8\u753b\u82f1\u96c4]")
                .replace("\u226b [Siege]", "\u226b [\u653b\u57ce]")
                .replace("\u226b [Enemies]", "\u226b [\u654c\u4eba]")
                .replaceFirst("\u2714 Loaded", "\u2714 \u5df2\u52a0\u8f7d")
                .replaceFirst("\u2714 Loaded", "\u2714 \u5df2\u88c5\u8f7d")
                .replaceFirst("\u2714 Loaded", "\u2714 Loaded");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, translated);
        assertNotNull(restored, "loaded status restore");
        assertEquals(3, restored.size(), "loaded status line count");
        for (Component component : restored) {
            String visible = component.getString();
            assertContains(visible, "\u2714 \u5df2\u52a0\u8f7d", "loaded status translated consistently");
            if (visible.contains("\u5df2\u88c5\u8f7d") || visible.contains("Loaded")) {
                throw new AssertionError("Loaded status must not keep inconsistent wording: " + visible);
            }
            assertEquals(ChatFormatting.GREEN.getColor(),
                    findSegment(segments(component), "\u2714 \u5df2\u52a0\u8f7d").style.getColor().getValue(),
                    "loaded status keeps green style");
        }
        assertEquals("\u5df2\u52a0\u8f7d", DirectStatusTerms.fixedTermsForTest().get("Loaded"),
                "loaded deterministic term map");
    }

    private static void checkFullwidthSystemStatusDetection() {
        String sourceText = "\uff3b\uff41\uff56\uff33\uff39\uff33\uff3d\uff0f\uff0f\uff21\uff34\uff34\uff25\uff2d\uff30\uff34\uff29\uff2e\uff27 \uff2e\uff25\uff34\uff37\uff2f\uff32\uff2b \uff32\uff25\uff23\uff2f\uff2e\uff2e\uff25\uff23\uff34\uff29\uff2f\uff2e\uff0e\uff0e\uff0e\uff0f\uff0f";
        if (!TranslationTextDetector.containsTranslatableText(sourceText)) {
            throw new AssertionError("fullwidth avSYS text should be translatable");
        }
        Component source = Component.literal(sourceText).withStyle(ChatFormatting.WHITE);
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, "[NORMALIZED]", "fullwidth normalized hint section");
        assertContains(request, "ATTEMPTING NETWORK RECONNECTION", "fullwidth normalized text");
        String translated = "0|\uff3bavSYS\uff3d//\u6b63\u5728\u5c1d\u8bd5\u91cd\u65b0\u8fde\u63a5\u7f51\u7edc...//";
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, translated);
        assertNotNull(restored, "fullwidth restore");
        assertContains(restored.get(0).getString(), "\u6b63\u5728\u5c1d\u8bd5\u91cd\u65b0\u8fde\u63a5\u7f51\u7edc", "fullwidth translated text");
    }

    private static void checkKoreanPlainFallbackAndResidualValidation() {
        String sourceText = "\uc81c\uac00 \ub2f9\uc2e0\uc744 \uc704\ud574 \ucc3d\uc791\ud55c \uc2dc\uc785\ub2c8\ub2e4. \uc790\uc5f0\uacfc \uc774\ubcc4, \uc704\ub85c\ub97c \uc8fc\uc81c\ub85c \uc9e7\uac8c \uc368\ubcf4\uc558\uc2b5\ub2c8\ub2e4.";
        if (!TranslationTextDetector.containsTranslatableText(sourceText)) {
            throw new AssertionError("Korean fixture should be translatable");
        }
        Component source = Component.literal(sourceText).withStyle(ChatFormatting.GRAY);
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, sourceText, "Korean fixture direct request source");

        List<Component> plainFallback = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false,
                "\u8fd9\u662f\u4e00\u9996\u4ee5\u81ea\u7136\u3001\u79bb\u522b\u548c\u6170\u85c9\u4e3a\u4e3b\u9898\u7684\u77ed\u8bd7\u3002");
        assertNotNull(plainFallback, "Korean plain response fallback");
        assertEquals("\u8fd9\u662f\u4e00\u9996\u4ee5\u81ea\u7136\u3001\u79bb\u522b\u548c\u6170\u85c9\u4e3a\u4e3b\u9898\u7684\u77ed\u8bd7\u3002",
                plainFallback.get(0).getString(), "Korean fallback translated text");
        assertEquals(ChatFormatting.GRAY.getColor(), firstStyle(plainFallback.get(0)).getColor().getValue(),
                "Korean fallback keeps gray style");

        List<Component> residualPlain = DirectFormattedTranslationPipeline.restorePlainFallbackForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, sourceText);
        assertNotNull(residualPlain, "unchanged Korean plain response is accepted");
        assertEquals(sourceText, residualPlain.get(0).getString(), "unchanged Korean plain text");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        List<Component> residualStructured = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, document);
        assertNotNull(residualStructured, "unchanged Korean structured response is accepted");
        assertEquals(sourceText, residualStructured.get(0).getString(), "unchanged Korean structured text");
    }

    private static void checkChatSystemAbbreviationMessage() {
        Component source = Component.empty()
                .append(Component.literal("Abbreviations (Hover on their names): ").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true)))
                .append(Component.literal("\n[Root]").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" , ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Burn]").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\nHover on skill names to view information.").withStyle(ChatFormatting.GRAY));

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(document, "Abbreviations (Hover on their names):", "system chat heading present in wire document");
        assertContains(document, WireCodec.escape("\nHover on skill names to view information."),
                "embedded newlines are escaped on the wire");

        String translated = document
                .replace("<1>Abbreviations (Hover on their names): </1>",
                        "<1>\u7f29\u5199\uff08\u60ac\u505c\u540d\u79f0\u67e5\u770b\uff09\uff1a</1>")
                .replace("<2>" + WireCodec.escape("\n[Root]") + "</2>",
                        "<2>" + WireCodec.escape("\n[\u7f20\u7ed5]") + "</2>")
                .replace("<4>[Burn]</4>", "<4>[\u71c3\u70e7]</4>")
                .replace("<5>" + WireCodec.escape("\nHover on skill names to view information.") + "</5>",
                        "<5>" + WireCodec.escape("\n\u5c06\u9f20\u6807\u60ac\u505c\u5728\u6280\u80fd\u540d\u79f0\u4e0a\u4ee5\u67e5\u770b\u4fe1\u606f\u3002") + "</5>");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, translated);
        assertNotNull(restored, "system abbreviation chat restore");
        String visible = restored.get(0).getString();
        assertContains(visible, "\u7f29\u5199\uff08\u60ac\u505c\u540d\u79f0\u67e5\u770b\uff09\uff1a", "system heading translated");
        assertContains(visible, "[\u7f20\u7ed5]", "root tag translated");
        assertContains(visible, "[\u71c3\u70e7]", "burn tag translated");
        assertContains(visible, "\u5c06\u9f20\u6807\u60ac\u505c\u5728\u6280\u80fd\u540d\u79f0\u4e0a\u4ee5\u67e5\u770b\u4fe1\u606f\u3002", "system footer translated");

        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.GRAY.getColor(), findSegment(segments, "\u7f29\u5199\uff08\u60ac\u505c\u540d\u79f0\u67e5\u770b\uff09\uff1a").style.getColor().getValue(),
                "system heading keeps gray");
        assertEquals(Boolean.TRUE, findSegment(segments, "\u7f29\u5199\uff08\u60ac\u505c\u540d\u79f0\u67e5\u770b\uff09\uff1a").style.isItalic(),
                "system heading keeps italic");
        assertEquals(ChatFormatting.GREEN.getColor(), findSegment(segments, "\n[\u7f20\u7ed5]").style.getColor().getValue(),
                "root tag keeps green");
        assertEquals(ChatFormatting.GOLD.getColor(), findSegment(segments, "[\u71c3\u70e7]").style.getColor().getValue(),
                "burn tag keeps gold");
    }

    private static void checkOcrVisionJsonParsing() {
        OcrTranslationService.OcrResult parsed = VisionOcrTranslationService.parseOcrResultForTest(
                "{\"sourceText\":\"Open gate\",\"translationText\":\"\u6253\u5f00\u95e8\"}");
        assertEquals(true, parsed.success(), "OCR JSON fixture parses");
        assertEquals("Open gate", parsed.sourceText(), "OCR JSON fixture source text");
        assertEquals("\u6253\u5f00\u95e8", parsed.translationText(), "OCR JSON fixture translation text");

        OcrTranslationService.OcrResult fenced = VisionOcrTranslationService.parseOcrResultForTest(
                "```json\n{\"sourceText\":\"Dash\",\"translationText\":\"\u51b2\u523a\"}\n```");
        assertEquals("\u51b2\u523a", fenced.translationText(), "OCR fenced JSON fixture translation text");

        OcrTranslationService.OcrResult fallback = VisionOcrTranslationService.parseOcrResultForTest(
                "\u5c4f\u5e55\u4e0a\u7684\u6587\u5b57");
        assertEquals(true, fallback.success(), "OCR plain-text fallback parses");
        assertEquals("", fallback.sourceText(), "OCR plain-text fallback leaves source empty");
        assertEquals("\u5c4f\u5e55\u4e0a\u7684\u6587\u5b57", fallback.translationText(),
                "OCR plain-text fallback translation");
    }

    private static TextSegmentInfo findSegment(List<TextSegmentInfo> segments, String text) {
        for (TextSegmentInfo segment : segments) {
            if (segment.text != null && segment.text.equals(text)) {
                return segment;
            }
        }
        throw new AssertionError("missing segment text: " + text + " in " + segments);
    }

    private static Style firstStyle(Component component) {
        List<TextSegmentInfo> parts = segments(component);
        if (parts.isEmpty()) {
            throw new AssertionError("component has no text segment: " + component);
        }
        return parts.get(0).style;
    }

    private static List<TextSegmentInfo> segments(Component component) {
        List<TextSegmentInfo> segments = new ArrayList<>();
        ComponentSegmentHelper.extractSegments(component, segments, Style.EMPTY, true);
        return segments;
    }

    private static void assertContains(String text, String expected, String label) {
        if (text == null || !text.contains(expected)) {
            throw new AssertionError(label + ": expected <" + expected + "> in <" + text + ">");
        }
    }

    private static void assertNotNull(Object value, String label) {
        if (value == null) {
            throw new AssertionError(label + ": expected non-null value");
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
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
    $modDevArtifactsRoot = Join-Path $ProjectDir "build\moddev\artifacts"
    if (Test-Path $modDevArtifactsRoot) {
        $modDevMergedJar = Get-ChildItem -Path $modDevArtifactsRoot -Filter "*-merged.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            Select-Object -First 1
        if ((-not $minecraftJarEntries) -and $modDevMergedJar) {
            $minecraftJarEntries = @($modDevMergedJar.FullName)
        }
        $clientExtraJar = Get-ChildItem -Path $modDevArtifactsRoot -Filter "*client-extra*.jar" |
            Where-Object { $_.Name -notmatch "(-sources|-javadoc)\.jar$" } |
            Select-Object -First 1
        if ($clientExtraJar -and ($minecraftJarEntries -notcontains $clientExtraJar.FullName)) {
            $minecraftJarEntries += $clientExtraJar.FullName
        }
    }
    $remappedModJars = @()
    $remappedRoot = Join-Path $ProjectDir ".gradle\loom-cache\remapped_mods\remapped"
    if (Test-Path $remappedRoot) {
        $remappedModJars = Get-ChildItem -Path $remappedRoot -Recurse -Filter "*.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            ForEach-Object { $_.FullName }
    }
    $classpath = @($classes, $resources) + $minecraftJarEntries + $remappedModJars + @($gradleClasspath) -join ";"

    $argClasspath = $classpath -replace '\\', '/'
    $argTempDir = $tempDir -replace '\\', '/'
    $argSourceFile = $sourceFile -replace '\\', '/'
    $argFixturePath = $FixturePath -replace '\\', '/'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $javacArgs = Join-Path $tempDir "javac.args"
    $javacArgLines = @(
        "-encoding"
        "UTF-8"
        "-proc:none"
        "-cp"
        "`"$argClasspath`""
        "-d"
        "`"$argTempDir`""
        "`"$argSourceFile`""
    )
    [System.IO.File]::WriteAllLines($javacArgs, $javacArgLines, $utf8NoBom)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & javac "@$javacArgs"
    $javacExit = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($javacExit -ne 0) {
        throw "javac failed with exit code $javacExit"
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
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $fixtureOutput = & java -cp $shortClasspath SimpleTranslateDirectFixtureChecks $FixturePath 2>&1
    $fixtureExit = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($fixtureOutput) {
        $fixtureOutput | ForEach-Object { Write-Host $_ }
    }
    if ($fixtureExit -ne 0) {
        throw "translation fixture checks failed with exit code $fixtureExit"
    }
} finally {
    Pop-Location
}
