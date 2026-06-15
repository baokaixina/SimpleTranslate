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

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-direct-fixtures-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateDirectFixtureChecks.java"

    $javaSource = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.util.ChatTranslationRuntime;
import com.yourname.simpletranslate.util.ComponentSegmentHelper;
import com.yourname.simpletranslate.util.DirectFormattedTranslationPipeline;
import com.yourname.simpletranslate.util.DirectStatusTerms;
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
import java.util.Map;
import java.util.Set;

public final class SimpleTranslateDirectFixtureChecks {
    public static void main(String[] args) throws Exception {
        tryBootstrapMinecraft();
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SimpleTranslateDirectFixtureChecks <fixtures.json>");
        }
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        assertEquals("direct-formatted-fixtures-v1", root.get("version").getAsString(), "fixture file version");

        checkRingTooltip();
        checkAcknowledgementTooltip();
        checkSignFixedLayout();
        checkSignFixedKeepsNonEnglishLines();
        checkSignMixedRunStyles();
        checkSignRejectsMovedProtectedToken();
        checkSignGroupByIdDocument();
        checkAutoSignSingleDocument();
        checkManualSignSpatialDistributionDocument();
        checkSignModeEpochIsolation();
        checkSharedSignComponentCacheSerialization();
        checkTipSplitPlainContext();
        checkMovableManaCost();
        checkScoreboardDirectKey();
        checkHudTitleGroupDirect();
        checkLoadedStatusConsistency();
        checkFullwidthSystemStatusDetection();
        checkKoreanPlainFallbackAndResidualValidation();
        checkRejectsChangedStyle();
        checkRejectsMissingRun();
        checkAcceptsResidualSourceLanguage();
        checkChatSystemAbbreviationMessage();
        System.out.println("SimpleTranslate direct formatted fixtures passed");
    }

    private static void tryBootstrapMinecraft() throws Exception {
        if (FMLLoader.getCurrentOrNull() == null) {
            Constructor<FMLLoader> constructor = FMLLoader.class.getDeclaredConstructor(
                    ClassLoader.class, String[].class, Dist.class, boolean.class, Path.class);
            constructor.setAccessible(true);
            constructor.newInstance(SimpleTranslateDirectFixtureChecks.class.getClassLoader(),
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

    private static void checkRingTooltip() {
        Component source = Component.empty()
                .append(Component.literal("Ring").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)))
                .append(Component.literal(" Amulet Slot").withStyle(ChatFormatting.GRAY));

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        assertContains(document, "mode=\"line-text\"", "item tooltip uses line-text mode");
        if (document.contains("<g id=\"")) {
            throw new AssertionError("item tooltip line-text document must not expose styled group ids");
        }
        String translated = document.replace(">Ring Amulet Slot<", ">\u6212\u6307\u62a4\u7b26\u69fd<");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "ring tooltip restore");
        assertEquals("\u6212\u6307\u62a4\u7b26\u69fd", restored.get(0).getString(), "ring tooltip text");

        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals("\u6212\u6307\u62a4\u7b26\u69fd", segments.get(0).text, "ring translated line text");
        assertEquals(ChatFormatting.AQUA.getColor(), segments.get(0).style.getColor().getValue(), "ring line color");
        assertEquals(Boolean.TRUE, segments.get(0).style.isBold(), "ring line bold");
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
        String translated = document
                .replace(">Acknowledgement Of Excellence<", ">\u5353\u8d8a\u8bc1\u660e<")
                .replace(">A certificate stating one's achievement, acknowledged by a few organizations around the world.<",
                        ">\u4e00\u4efd\u8bc1\u660e\u6301\u6709\u8005\u6210\u5c31\u5e76\u83b7\u5f97\u4e16\u754c\u5404\u5730\u5c11\u6570\u7ec4\u7ec7\u8ba4\u53ef\u7684\u8bc1\u4e66\u3002<")
                .replace(">Used for Special Trades.<", ">\u7528\u4e8e\u7279\u6b8a\u4ea4\u6613\u3002<");

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

    private static void checkSignFixedLayout() {
        List<Component> source = List.of(
                Component.literal("Start Game").withStyle(ChatFormatting.WHITE),
                Component.literal("From Here").withStyle(ChatFormatting.WHITE),
                Component.literal("[Click to TP]").withStyle(ChatFormatting.GREEN),
                Component.empty()
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, "sign.lines.direct", "sign-lines", true);
        String translated = document
                .replace(">Start Game<", ">\u5f00\u59cb\u6e38\u620f<")
                .replace(">From Here<", ">\u4ece\u8fd9\u91cc\u5f00\u59cb<")
                .replace(">[Click to TP]<", ">[\u70b9\u51fb\u4f20\u9001]<");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, "sign.lines.direct", "sign-lines", true, translated);
        assertNotNull(restored, "sign restore");
        assertEquals(4, restored.size(), "sign must keep four line slots");
        assertEquals("\u5f00\u59cb\u6e38\u620f", restored.get(0).getString(), "sign line 0");
        assertEquals("\u4ece\u8fd9\u91cc\u5f00\u59cb", restored.get(1).getString(), "sign line 1");
        assertEquals("[\u70b9\u51fb\u4f20\u9001]", restored.get(2).getString(), "sign line 2");
        assertEquals(ChatFormatting.GREEN.getColor(), firstStyle(restored.get(2)).getColor().getValue(), "green click line");
    }

    private static void checkSignFixedKeepsNonEnglishLines() {
        List<Component> source = List.of(
                Component.literal("Start Game").withStyle(ChatFormatting.WHITE),
                Component.literal("?").withStyle(ChatFormatting.GRAY),
                Component.empty(),
                Component.literal("[Click]").withStyle(ChatFormatting.GREEN)
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, "sign.lines.direct", "sign-lines", true);
        String translated = document
                .replace(">Start Game<", ">\u5f00\u59cb\u6e38\u620f<")
                .replace(">?</run>", ">\u7591\u95ee</run>")
                .replace(">[Click]<", ">[\u70b9\u51fb]<");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, "sign.lines.direct", "sign-lines", true, translated);
        assertNotNull(restored, "sign fixed non-English restore");
        assertEquals("\u5f00\u59cb\u6e38\u620f", restored.get(0).getString(), "translated English line");
        assertEquals("?", restored.get(1).getString(), "non-English punctuation line must stay original");
        assertEquals("", restored.get(2).getString(), "empty sign line stays empty");
        assertEquals("[\u70b9\u51fb]", restored.get(3).getString(), "click line translated");
        assertEquals(ChatFormatting.GRAY.getColor(), firstStyle(restored.get(1)).getColor().getValue(), "question color");
        assertEquals(ChatFormatting.GREEN.getColor(), firstStyle(restored.get(3)).getColor().getValue(), "click color");
    }

    private static void checkSignMixedRunStyles() {
        List<Component> source = List.of(
                Component.empty()
                        .append(Component.literal("\u203b ").withStyle(ChatFormatting.RED))
                        .append(Component.literal("HP ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[+]").withStyle(ChatFormatting.GREEN)),
                Component.empty()
                        .append(Component.literal("\u203b ").withStyle(ChatFormatting.RED))
                        .append(Component.literal("Armor ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[+]").withStyle(ChatFormatting.GREEN)),
                Component.empty()
                        .append(Component.literal("\u203b ").withStyle(ChatFormatting.RED))
                        .append(Component.literal("Atk ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[-]").withStyle(ChatFormatting.GRAY)),
                Component.empty()
                        .append(Component.literal("\u203b ").withStyle(ChatFormatting.RED))
                        .append(Component.literal("Speed ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[+]").withStyle(ChatFormatting.GREEN))
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, "sign.lines.direct", "sign-lines", true);
        String translated = document
                .replace(">HP <", ">\u751f\u547d\u503c <")
                .replace(">Armor <", ">\u62a4\u7532\u503c <")
                .replace(">Atk <", ">\u653b\u51fb\u529b <")
                .replace(">Speed <", ">\u901f\u5ea6 <");

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, "sign.lines.direct", "sign-lines", true, translated);
        assertNotNull(restored, "mixed sign restore");
        assertEquals(4, restored.size(), "mixed sign keeps four line slots");

        List<TextSegmentInfo> hp = segments(restored.get(0));
        assertEquals(ChatFormatting.RED.getColor(), findSegment(hp, "\u203b ").style.getColor().getValue(),
                "sign bullet remains red");
        assertEquals(ChatFormatting.GRAY.getColor(), findSegment(hp, "\u751f\u547d\u503c ").style.getColor().getValue(),
                "sign translated HP keeps original word color");
        assertEquals(ChatFormatting.GREEN.getColor(), findSegment(hp, "[+]").style.getColor().getValue(),
                "sign plus remains green");

        List<TextSegmentInfo> atk = segments(restored.get(2));
        assertEquals(ChatFormatting.GRAY.getColor(), findSegment(atk, "\u653b\u51fb\u529b ").style.getColor().getValue(),
                "sign translated Atk keeps original word color");
        assertEquals(ChatFormatting.GRAY.getColor(), findSegment(atk, "[-]").style.getColor().getValue(),
                "sign minus remains gray");
    }

    private static void checkTipSplitPlainContext() {
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
        assertContains(request,
                "[ Tip ]: Most Enemies cannot attack [Fortified] Objectives. Only Siege Enemies can do so.",
                "split tip full plain context");
        assertContains(request, "Item tooltip semantic block", "split tip item context block");
        assertContains(request, "line 0 [title]: [ Tip ]: Most Enemies cannot attack [Fortified] Objectives. Only Siege Enemies can do so.",
                "split tip item context should keep numbered source line");

        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        String translated = document.replace(
                ">[ Tip ]: Most Enemies cannot attack [Fortified] Objectives. Only Siege Enemies can do so.<",
                ">[\u63d0\u793a]\uff1a\u5927\u591a\u6570\u654c\u4eba\u65e0\u6cd5\u653b\u51fb[\u52a0\u56fa]\u76ee\u6807\u3002\u53ea\u6709\u653b\u57ce\u654c\u4eba\u624d\u80fd\u505a\u5230\u3002<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, translated);
        assertNotNull(restored, "split tip restore");
        assertEquals("[\u63d0\u793a]\uff1a\u5927\u591a\u6570\u654c\u4eba\u65e0\u6cd5\u653b\u51fb[\u52a0\u56fa]\u76ee\u6807\u3002\u53ea\u6709\u653b\u57ce\u654c\u4eba\u624d\u80fd\u505a\u5230\u3002",
                restored.get(0).getString(), "split tip translated text");
        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.GOLD.getColor(), segments.get(0).style.getColor().getValue(), "split tip keeps stable line style");
    }

    private static void checkSignRejectsMovedProtectedToken() {
        List<Component> source = List.of(
                Component.literal("1. This map is").withStyle(ChatFormatting.WHITE),
                Component.literal("made for version").withStyle(ChatFormatting.WHITE),
                Component.literal("1.20.1").withStyle(ChatFormatting.WHITE),
                Component.literal("/trigger Settings").withStyle(ChatFormatting.GOLD)
        );
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                source, "sign.lines.direct", "sign-lines", true);

        String movedToken = document
                .replace(">1. This map is<", ">1. 这张地图适用于 1.20.1<")
                .replace(">made for version<", ">版本制作<")
                .replace(">1.20.1<", ">版本号<")
                .replace(">/trigger Settings<", ">/trigger 设置<");
        List<Component> rejected = DirectFormattedTranslationPipeline.restoreForTest(
                source, "sign.lines.direct", "sign-lines", true, movedToken);
        if (rejected != null) {
            throw new AssertionError("sign protected token moved across lines must be rejected");
        }

        String valid = document
                .replace(">1. This map is<", ">1. 这张地图<")
                .replace(">made for version<", ">是为版本<")
                .replace(">1.20.1<", ">1.20.1<")
                .replace(">/trigger Settings<", ">/trigger 设置<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                source, "sign.lines.direct", "sign-lines", true, valid);
        assertNotNull(restored, "sign protected tokens restore when kept on their own lines");
        assertEquals("1. 这张地图", restored.get(0).getString(), "numbered sign line keeps number");
        assertEquals("1.20.1", restored.get(2).getString(), "version token stays on original line");
        assertEquals("/trigger 设置", restored.get(3).getString(), "command token stays on original line");
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
        String document = extractStDoc(SignDirectDocument.requestPayloadForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context"));
        assertContains(document, "<sign id=\"" + idA + "\"", "sign A id serialized");
        assertContains(document, "<sign id=\"" + idB + "\"", "sign B id serialized");

        String valid = document
                .replace(">Start Game<", ">\u5f00\u59cb\u6e38\u620f<")
                .replace(">From Here<", ">\u4ece\u8fd9\u91cc\u5f00\u59cb<")
                .replace(">[Click to TP]<", ">[\u70b9\u51fb\u4f20\u9001]<")
                .replace(">1. This map is<", ">1. \u8fd9\u5f20\u5730\u56fe<")
                .replace(">made for version<", ">\u662f\u4e3a\u7248\u672c<")
                .replace(">/trigger Settings<", ">/trigger \u8bbe\u7f6e<");
        String reordered = swapSignBlocks(valid, idA, idB);
        SignDirectDocument.RestoreResult restored = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", reordered);
        assertEquals(Boolean.TRUE, restored.success(), "reordered sign ids restore reason=" + restored.failureReason());
        Map<String, Component[]> byId = restored.componentsBySignId();
        assertEquals("\u5f00\u59cb\u6e38\u620f", byId.get(idA)[0].getString(), "sign A writeback by id");
        assertEquals("[\u70b9\u51fb\u4f20\u9001]", byId.get(idA)[2].getString(), "sign A line 3 by id");
        assertEquals("1.20.1", byId.get(idB)[2].getString(), "sign B version stays on sign B");
        assertEquals("/trigger \u8bbe\u7f6e", byId.get(idB)[3].getString(), "sign B command stays on sign B");

        String residualSourceText = valid.replace(">1. \u8fd9\u5f20\u5730\u56fe<", ">1. This map is<");
        SignDirectDocument.RestoreResult leftover = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", residualSourceText);
        assertEquals(Boolean.TRUE, leftover.success(), "single untranslated sign run should not reject whole batch");

        String duplicateId = valid.replace("<sign id=\"" + idB + "\"", "<sign id=\"" + idA + "\"");
        if (SignDirectDocument.restoreForTest(entries, "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id", "shared sign context", duplicateId).success()) {
            throw new AssertionError("duplicate sign id must be rejected");
        }

        String missingSign = removeSignBlock(valid, idB);
        if (SignDirectDocument.restoreForTest(entries, "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id", "shared sign context", missingSign).success()) {
            throw new AssertionError("missing sign id must be rejected");
        }

        String missingLine = valid.replaceAll("(?s)<line i=\"2\"[^>]*><run id=\"s1_l2_r0\".*?</run></line>", "");
        if (SignDirectDocument.restoreForTest(entries, "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id", "shared sign context", missingLine).success()) {
            throw new AssertionError("missing line must be rejected");
        }

        String movedToken = valid
                .replace(">1. \u8fd9\u5f20\u5730\u56fe<", ">1. \u8fd9\u5f20\u5730\u56fe /trigger<")
                .replace(">/trigger \u8bbe\u7f6e<", ">\u8bbe\u7f6e<");
        SignDirectDocument.RestoreResult movedWithinSign = SignDirectDocument.restoreForTest(
                entries, "sign.manual.group.by_id.direct", "sign-manual-group-by-id", "shared sign context", movedToken);
        assertEquals(Boolean.TRUE, movedWithinSign.success(),
                "protected token may move within the same sign reason=" + movedWithinSign.failureReason());

        String movedAcrossSigns = valid
                .replace(">\u5f00\u59cb\u6e38\u620f<", ">\u5f00\u59cb\u6e38\u620f /trigger<")
                .replace(">/trigger \u8bbe\u7f6e<", ">\u8bbe\u7f6e<");
        if (SignDirectDocument.restoreForTest(entries, "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id", "shared sign context", movedAcrossSigns).success()) {
            throw new AssertionError("protected token moved across signs must be rejected");
        }
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
        assertContains(auto.requestPayload(), "sign.auto.single.direct", "auto sign uses automatic surface");
        assertContains(auto.requestPayload(), "sign-auto-single-by-id", "auto sign uses automatic role");
        assertContains(auto.requestPayload(), "Treat the four visible lines below as one complete sign message",
                "auto sign request includes whole-sign semantic context");
        assertContains(auto.requestPayload(), "<sign id=\"" + signId + "\"", "auto sign serializes one stable sign id");
        assertContains(auto.requestPayload(), "<line i=\"0\">This shop</line><line i=\"1\">sells rare</line><line i=\"2\">items today</line><line i=\"3\">[Click to Buy]</line>",
                "auto sign plain context keeps all four lines together");
        if (auto.cacheKey().equals(TranslationCacheKeys.key("sign.manual.group.by_id.direct",
                auto.sourceText(), auto.context(), auto.layoutSignature(), auto.styleSignature()))) {
            throw new AssertionError("automatic sign cache key must not collide with manual sign surface");
        }

        String translated = extractStDoc(auto.requestPayload())
                .replace(">This shop<", ">\u672c\u5546\u5e97<")
                .replace(">sells rare<", ">\u4eca\u65e5\u51fa\u552e<")
                .replace(">items today<", ">\u7a00\u6709\u7269\u54c1<")
                .replace(">[Click to Buy]<", ">[\u70b9\u51fb\u8d2d\u4e70]<");
        SignDirectDocument.RestoreResult restored = SignDirectDocument.restoreForTest(entries,
                "sign.auto.single.direct",
                "sign-auto-single-by-id",
                "Automatic sign mode translates only this one sign. Treat the four visible lines below as one complete sign message.",
                translated);
        assertEquals(Boolean.TRUE, restored.success(), "auto sign document restores reason=" + restored.failureReason());
        Component[] lines = restored.componentsBySignId().get(signId);
        assertEquals(4, lines.length, "auto sign restore keeps four lines");
        assertEquals("\u672c\u5546\u5e97", lines[0].getString(), "auto sign line 1 restored");
        assertEquals("\u4eca\u65e5\u51fa\u552e", lines[1].getString(), "auto sign line 2 restored with block context");
        assertEquals("\u7a00\u6709\u7269\u54c1", lines[2].getString(), "auto sign line 3 restored with block context");
        assertEquals("[\u70b9\u51fb\u8d2d\u4e70]", lines[3].getString(), "auto sign line 4 restored");
    }

    private static void checkManualSignSpatialDistributionDocument() {
        String idA = "minecraft:overworld:10,64,1:front:keep";
        String idB = "minecraft:overworld:11,64,1:front:places";
        List<SignDirectDocument.Entry> entries = List.of(
                new SignDirectDocument.Entry(idA, "stateKeep", List.of(
                        Component.literal("6. keepInventory").withStyle(ChatFormatting.WHITE),
                        Component.literal("is on by default.").withStyle(ChatFormatting.WHITE),
                        Component.literal("If you turn it off,").withStyle(ChatFormatting.WHITE),
                        Component.literal("there are some").withStyle(ChatFormatting.WHITE)
                ), new String[] { "6. keepInventory", "is on by default.", "If you turn it off,", "there are some" }, 1L, true),
                new SignDirectDocument.Entry(idB, "statePlaces", List.of(
                        Component.literal("places where you").withStyle(ChatFormatting.WHITE),
                        Component.literal("cannot get your").withStyle(ChatFormatting.WHITE),
                        Component.literal("items back.").withStyle(ChatFormatting.WHITE),
                        Component.empty()
                ), new String[] { "places where you", "cannot get your", "items back.", "" }, 2L, true)
        );
        String document = extractStDoc(SignDirectDocument.requestPayloadForTest(
                entries,
                "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id",
                "Spatial panel reading order: sign 1 then sign 2. The two signs are one semantic block."));
        String translated = document
                .replace(">6. keepInventory<", ">6. keepInventory<")
                .replace(">is on by default.<", ">\u9ed8\u8ba4\u5f00\u542f\u3002<")
                .replace(">If you turn it off,<", ">\u5982\u679c\u4f60\u5c06\u5176\u5173\u95ed\uff0c<")
                .replace(">there are some<", ">\u6709\u4e9b<")
                .replace(">places where you<", ">\u5730\u65b9\u4f60\u5c06<")
                .replace(">cannot get your<", ">\u65e0\u6cd5\u53d6\u56de<")
                .replace(">items back.<", ">\u7269\u54c1\u3002<");

        SignDirectDocument.RestoreResult restored = SignDirectDocument.restoreForTest(
                entries,
                "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id",
                "Spatial panel reading order: sign 1 then sign 2. The two signs are one semantic block.",
                translated);
        assertEquals(Boolean.TRUE, restored.success(), "manual sign spatial distribution restore");
        Map<String, Component[]> byId = restored.componentsBySignId();
        assertEquals("6. keepInventory", byId.get(idA)[0].getString(), "manual sign keeps numbered gamerule anchor on original sign");
        assertEquals("\u6709\u4e9b", byId.get(idA)[3].getString(), "manual sign distributes first continuation to first sign");
        assertEquals("\u5730\u65b9\u4f60\u5c06", byId.get(idB)[0].getString(), "manual sign distributes next continuation to second sign");
        assertEquals("\u7269\u54c1\u3002", byId.get(idB)[2].getString(), "manual sign writes final phrase to matching sign id");
    }

    private static void checkScoreboardDirectKey() {
        String tooltipKey = TranslationCacheKeys.key(TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                "Score Leaderboard", "line 0 [title]: Score Leaderboard", "title", "green");
        String scoreboardKey = TranslationCacheKeys.key("scoreboard.component.direct", "Score Leaderboard", "", "title", "white");
        if (tooltipKey.equals(scoreboardKey)) {
            throw new AssertionError("different surfaces must not share direct cache keys");
        }
assertContains(tooltipKey, "direct:v16-lineunit:tooltip.item_context.direct:", "tooltip key protocol");
assertContains(scoreboardKey, "direct:v16-lineunit:scoreboard.component.direct:", "scoreboard key protocol");
        assertContains(tooltipKey, ":lang=", "tooltip key language isolation");
        assertEquals("tooltip", TranslationCacheKeys.laneFromKey(tooltipKey), "tooltip lane");
        assertEquals("scoreboard", TranslationCacheKeys.laneFromKey(scoreboardKey), "scoreboard lane");
    }

    private static void checkHudTitleGroupDirect() {
        Component title = Component.literal("Network Reconnected").withStyle(ChatFormatting.GOLD);
        Component subtitle = Component.literal("Return to the control room").withStyle(ChatFormatting.YELLOW);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(title, subtitle), "hud.title_group.component.direct", "title-subtitle", false);
        assertContains(document, "surface=\"hud.title_group.component.direct\"", "title/subtitle grouped surface");
        assertContains(document, "Network Reconnected", "title source included");
        assertContains(document, "Return to the control room", "subtitle source included");

        String translated = document
                .replace(">Network Reconnected<", ">\u7f51\u7edc\u5df2\u91cd\u8fde<")
                .replace(">Return to the control room<", ">\u8fd4\u56de\u63a7\u5236\u5ba4<");
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
        String noisyTranslated = noisyDocument.replace(">I LOVE YOU BABY\u2727<", ">\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727<");
        List<Component> noisyRestored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(naturalTitle, noisySubtitle), "hud.title_group.component.direct", "title-subtitle", false, noisyTranslated);
        assertNotNull(noisyRestored, "legacy HUD formatting noise should not reject grouped title restore");
        assertEquals("\u6211\u7231\u4f60\uff0c\u5b9d\u8d1d\u2727", noisyRestored.get(0).getString(), "natural title translated with noisy subtitle");
        assertEquals(noisySubtitle.getString(), noisyRestored.get(1).getString(), "legacy HUD noise left unchanged");
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
        assertContains(request, "<st-glossary>", "loaded status glossary");
        assertContains(request, "source=\"Loaded\" target=\"\u5df2\u52a0\u8f7d\"", "loaded status glossary target");

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
            throw new AssertionError("fixture 9 fullwidth avSYS text should be translatable");
        }
        Component source = Component.literal(sourceText).withStyle(ChatFormatting.WHITE);
        String request = DirectFormattedTranslationPipeline.requestPayloadForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(request, "<st-normalized-plain-context>", "fixture 9 normalized context");
        assertContains(request, "ATTEMPTING NETWORK RECONNECTION", "fixture 9 normalized text");
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false);
        assertContains(document, "<g id=\"", "fixture 9 editable group emitted");
        String translated = document.replace(sourceText, "\uff3bavSYS\uff3d//\u6b63\u5728\u5c1d\u8bd5\u91cd\u65b0\u8fde\u63a5\u7f51\u7edc...//");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), ChatTranslationRuntime.CHAT_SYSTEM_SURFACE, "chat-system", false, translated);
        assertNotNull(restored, "fixture 9 restore");
        assertContains(restored.get(0).getString(), "\u6b63\u5728\u5c1d\u8bd5\u91cd\u65b0\u8fde\u63a5\u7f51\u7edc", "fixture 9 translated text");
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
        assertContains(document, "mode=\"line-text\"", "mana item tooltip uses line-text mode");
        if (document.contains("<g id=\"")) {
            throw new AssertionError("mana item tooltip must not expose movable styled groups");
        }

        String replacement = "\u6301\u7eed\u6bcf 0.4 \u79d2\u6d88\u8017 15 \u70b9\u6cd5\u529b\uff0c\u76f4\u5230\u4f60\u7684\u6cd5\u529b\u4e0d\u8db3";
        int lineStart = document.indexOf("<line i=\"0\"");
        int bodyStart = document.indexOf(">", lineStart) + 1;
        int bodyEnd = document.indexOf("</line>", bodyStart);
        String reordered = document.substring(0, bodyStart) + replacement + document.substring(bodyEnd);

        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, reordered);
        assertNotNull(restored, "movable mana restore");
        assertEquals("\u6301\u7eed\u6bcf 0.4 \u79d2\u6d88\u8017 15 \u70b9\u6cd5\u529b\uff0c\u76f4\u5230\u4f60\u7684\u6cd5\u529b\u4e0d\u8db3",
                restored.get(0).getString(), "movable mana text");
        List<TextSegmentInfo> segments = segments(restored.get(0));
        assertEquals(ChatFormatting.WHITE.getColor(), firstStyle(restored.get(0)).getColor().getValue(), "line-text mana keeps stable base style");
    }

    private static void checkRejectsChangedStyle() {
        Component source = Component.literal("Ring").withStyle(ChatFormatting.AQUA);
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "sign.lines.direct", "sign-lines", true);
        String changedStyle = document.replace("color=aqua", "color=gold").replace(">Ring<", ">\u6212\u6307<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "sign.lines.direct", "sign-lines", true, changedStyle);
        assertNotNull(restored, "changed model style attribute should still restore from original template");
        assertEquals("\u6212\u6307", restored.get(0).getString(), "style-attribute typo keeps translated text");
        assertEquals(ChatFormatting.AQUA.getColor(), firstStyle(restored.get(0)).getColor().getValue(),
                "returned style attribute is ignored; original style is used");
    }

    private static void checkRejectsMissingRun() {
        Component source = Component.empty()
                .append(Component.literal("Ring").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" Slot").withStyle(ChatFormatting.GRAY));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false);
        String missingRun = document.replace("</line>", "<g id=\"r0_0\">\u6212\u6307</g></line>");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_SURFACE,
                TooltipTranslationHelper.ITEM_TOOLTIP_CONTEXT_ROLE, false, missingRun);
        if (restored != null) {
            throw new AssertionError("nested line-text tag must be rejected");
        }
    }

    private static void checkAcceptsResidualSourceLanguage() {
        Component source = Component.literal("Plain English Sentence")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true));
        String document = DirectFormattedTranslationPipeline.serializeForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false);
        List<Component> residual = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false, document);
        assertNotNull(residual, "residual source language restore");
        assertEquals("Plain English Sentence", residual.get(0).getString(),
                "residual source language is accepted");

        String translated = document.replace(">Plain English Sentence<", ">\u666e\u901a\u82f1\u6587\u53e5\u5b50<");
        List<Component> restored = DirectFormattedTranslationPipeline.restoreForTest(
                List.of(source), "hud.actionbar.component.direct", "actionbar", false, translated);
        assertNotNull(restored, "translated plain English restore");
        assertEquals("\u666e\u901a\u82f1\u6587\u53e5\u5b50", restored.get(0).getString(),
                "translated plain English");
        assertEquals(ChatFormatting.GRAY.getColor(), firstStyle(restored.get(0)).getColor().getValue(),
                "translated plain English color");
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
        assertContains(document, "Abbreviations (Hover on their names):", "system chat heading is present in direct document");
        assertContains(document, "Hover on skill names to view information.", "system chat footer is present in direct document");

        String translated = document
                .replace(">Abbreviations (Hover on their names): <", ">\u7f29\u5199\uff08\u60ac\u505c\u540d\u79f0\u67e5\u770b\uff09\uff1a<")
                .replace(">\n[Root]<", ">\n[\u7f20\u7ed5]<")
                .replace(">[Burn]<", ">[\u71c3\u70e7]<")
                .replace(">\nHover on skill names to view information.<", ">\n\u5c06\u9f20\u6807\u60ac\u505c\u5728\u6280\u80fd\u540d\u79f0\u4e0a\u4ee5\u67e5\u770b\u4fe1\u606f\u3002<");
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

    private static String extractStDoc(String text) {
        int start = text.indexOf("<st-doc");
        int end = text.lastIndexOf("</st-doc>");
        if (start < 0 || end < start) {
            throw new AssertionError("missing st-doc in " + text);
        }
        return text.substring(start, end + "</st-doc>".length());
    }

    private static String swapSignBlocks(String document, String firstId, String secondId) {
        String first = signBlock(document, firstId);
        String second = signBlock(document, secondId);
        int start = document.indexOf(first);
        int end = document.indexOf(second) + second.length();
        return document.substring(0, start) + second + first + document.substring(end);
    }

    private static String removeSignBlock(String document, String signId) {
        return document.replace(signBlock(document, signId), "");
    }

    private static String signBlock(String document, String signId) {
        String needle = "<sign id=\"" + signId + "\"";
        int start = document.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("missing sign block " + signId + " in " + document);
        }
        int end = document.indexOf("</sign>", start);
        if (end < start) {
            throw new AssertionError("missing sign end " + signId + " in " + document);
        }
        return document.substring(start, end + "</sign>".length());
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
    $minecraftVersion = (Select-String -Path (Join-Path $ProjectDir "gradle.properties") -Pattern '^minecraft_version=(.+)$').Matches.Groups[1].Value
    if (-not $minecraftVersion) {
        throw "Could not read minecraft_version from gradle.properties"
    }
    function Find-LoomMinecraftJar([string]$Root, [string]$Version) {
        if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-Path -LiteralPath $Root)) {
            return $null
        }
        return Get-ChildItem -Path $Root -Recurse -Filter "*.jar" |
            Where-Object { $_.Name -like "minecraft-merged*" -and $_.Name -like "*$Version*" -and $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
    function Find-MappedOfficialJar([string]$Root, [string]$Version) {
        if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-Path -LiteralPath $Root)) {
            return $null
        }
        return Get-ChildItem -Path $Root -Recurse -Filter "*mapped_official*$Version*.jar" |
            Where-Object { $_.Name -like "*$Version*" -and $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
    function Find-NeoFormMappedJar([string]$Root, [string]$Version) {
        $neoFormRoot = Join-Path $Root "build\neoForm"
        if (-not (Test-Path -LiteralPath $neoFormRoot)) {
            return $null
        }
        $classesDir = Get-ChildItem -Path $neoFormRoot -Recurse -Directory -Filter "classes" |
            Where-Object { $_.FullName -like "*$Version*" -and $_.FullName -like "*\steps\recompile\classes" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($classesDir) {
            return $classesDir
        }
        foreach ($step in @("packRecomp", "applyOfficialMappings", "patchUserDev", "rename", "applyForgesAccessTransformer")) {
            $jar = Get-ChildItem -Path $neoFormRoot -Recurse -Filter "output.jar" |
                Where-Object { $_.FullName -like "*$Version*" -and $_.FullName -like "*\steps\$step\*" } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($jar) {
                return $jar
            }
        }
        return $null
    }
    $projectLeaf = Split-Path -Leaf ((Resolve-Path $ProjectDir).Path)
    $isForgeLikeProject = $projectLeaf -like "*Forge*"
    $minecraftJar = $null
    if (-not $isForgeLikeProject) {
        $minecraftJar = Find-LoomMinecraftJar (Join-Path $ProjectDir ".gradle\loom-cache\minecraftMaven\net\minecraft") $minecraftVersion
        if (-not $minecraftJar) {
            $minecraftJar = Find-LoomMinecraftJar "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft" $minecraftVersion
        }
    }
    if (-not $minecraftJar) {
        $minecraftJar = Find-MappedOfficialJar (Join-Path $ProjectDir ".gradle") $minecraftVersion
    }
    if (-not $minecraftJar) {
        $minecraftJar = Find-MappedOfficialJar (Join-Path "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge" "forge") $minecraftVersion
    }
    if (-not $minecraftJar) {
        $minecraftJar = Find-MappedOfficialJar (Join-Path "$env:USERPROFILE\.gradle\caches\forge_gradle\minecraft_repo\net\minecraftforge" "forge") $minecraftVersion
    }
    if (-not $minecraftJar) {
        $minecraftJar = Find-NeoFormMappedJar $ProjectDir $minecraftVersion
    }
    if (-not $minecraftJar) {
        throw "Could not find the remapped Minecraft/Forge $minecraftVersion jar"
    }
    $dependencyRoots = @(
        "com.mojang\brigadier",
        "com.mojang\datafixerupper",
        "com.google.guava\guava",
        "com.google.code.gson\gson",
        "it.unimi.dsi\fastutil",
        "org.joml\joml",
        "org.apache.commons\commons-lang3",
        "org.slf4j\slf4j-api",
        "com.mojang\logging",
        "com.mojang\authlib"
    )
    # The fixture harness only needs compiled mod classes, the remapped Minecraft jar,
    # Gson, and a small set of Minecraft API transitive jars. Pulling the full Fabric
    # dependency graph makes Windows classpaths too long and brittle, especially under
    # non-ASCII workspace paths.
    function Find-CachedJar([string]$relativeModulePath, [string]$namePattern) {
        $modulePath = Join-Path "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" $relativeModulePath
        if (-not (Test-Path $modulePath)) {
            return $null
        }
        return Get-ChildItem -Path $modulePath -Recurse -Filter $namePattern |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
    $localLibraryRoots = @()
    $projectLeaf = Split-Path -Leaf ((Resolve-Path $ProjectDir).Path)
    $testClientRoot = Join-Path "D:\mc" (-join ([char[]](0x6A21, 0x7EC4, 0x6D4B, 0x8BD5)))
    if ($projectLeaf -like "*NeoForge*") {
        $localLibraryRoots += Join-Path $testClientRoot "neoforge\$minecraftVersion\libraries"
    } elseif ($projectLeaf -like "*Forge*") {
        $localLibraryRoots += Join-Path $testClientRoot "forge\${minecraftVersion}forge\libraries"
    }
    function Test-MinecraftVersionAtLeast([string]$Version, [int]$Major, [int]$Minor, [int]$Patch) {
        $parts = $Version -split '\.'
        $current = @(
            if ($parts.Length -gt 0) { [int]$parts[0] } else { 0 }
            if ($parts.Length -gt 1) { [int]$parts[1] } else { 0 }
            if ($parts.Length -gt 2) { [int]$parts[2] } else { 0 }
        )
        $target = @($Major, $Minor, $Patch)
        for ($i = 0; $i -lt 3; $i++) {
            if ($current[$i] -gt $target[$i]) { return $true }
            if ($current[$i] -lt $target[$i]) { return $false }
        }
        return $true
    }
    $includeLocalRuntimeJars = $isForgeLikeProject -and (Test-MinecraftVersionAtLeast $minecraftVersion 1 20 4)
    function Find-LocalClientJar([string]$group, [string]$artifact, [string]$namePattern) {
        $relativeGroup = $group -replace '\.', '\'
        foreach ($root in $localLibraryRoots) {
            $modulePath = Join-Path (Join-Path $root $relativeGroup) $artifact
            if (Test-Path $modulePath) {
                $jar = Get-ChildItem -Path $modulePath -Recurse -Filter $namePattern |
                    Where-Object { $_.Name -notmatch "sources|javadoc" } |
                    Sort-Object Name -Descending |
                    Select-Object -First 1
                if ($jar) {
                    return $jar
                }
            }
        }
        return $null
    }

    function Read-MinecraftDependencyOutput {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            foreach ($configuration in @("minecraftLibraries", "minecraftLibrary")) {
                $output = & .\gradlew.bat -q dependencies --configuration $configuration --no-daemon 2>$null
                if ($LASTEXITCODE -eq 0 -and $output) {
                    return $output
                }
            }
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        return @()
    }
    $minecraftDependencyOutput = Read-MinecraftDependencyOutput
    function Find-DeclaredCachedJar([string]$group, [string]$artifact, [string]$namePattern) {
        $localClientJar = Find-LocalClientJar $group $artifact $namePattern
        if ($localClientJar) {
            return $localClientJar
        }
        $escapedGroup = [regex]::Escape($group)
        $escapedArtifact = [regex]::Escape($artifact)
        foreach ($line in $minecraftDependencyOutput) {
            if ($line -match "$escapedGroup`:$escapedArtifact`:([^:\s]+)") {
                $version = $Matches[1]
                $relativePath = Join-Path (Join-Path $group $artifact) $version
                return Find-CachedJar $relativePath $namePattern
            }
        }
        return Find-CachedJar (Join-Path $group $artifact) $namePattern
    }

    $dependencyJars = @()
    $brigadierJar = Find-DeclaredCachedJar "com.mojang" "brigadier" "brigadier-*.jar"
    if ($brigadierJar) {
        $dependencyJars += $brigadierJar.FullName
    }
    $dataFixerJar = Find-DeclaredCachedJar "com.mojang" "datafixerupper" "datafixerupper-*.jar"
    if ($dataFixerJar) {
        $dependencyJars += $dataFixerJar.FullName
    }
    $fastutilJar = Find-DeclaredCachedJar "it.unimi.dsi" "fastutil" "fastutil-*.jar"
    if ($fastutilJar) {
        $dependencyJars += $fastutilJar.FullName
    }
    $guavaJar = Find-DeclaredCachedJar "com.google.guava" "guava" "guava-*.jar"
    if ($guavaJar) {
        $dependencyJars += $guavaJar.FullName
    }
    $failureAccessJar = Find-DeclaredCachedJar "com.google.guava" "failureaccess" "failureaccess-*.jar"
    if ($failureAccessJar) {
        $dependencyJars += $failureAccessJar.FullName
    }
    foreach ($nettyModule in @("netty-common", "netty-buffer", "netty-codec", "netty-handler")) {
        $nettyJar = Find-DeclaredCachedJar "io.netty" $nettyModule "$nettyModule-*.jar"
        if ($nettyJar) {
            $dependencyJars += $nettyJar.FullName
        }
    }
    $jomlJar = Find-DeclaredCachedJar "org.joml" "joml" "joml-*.jar"
    if ($jomlJar) {
        $dependencyJars += $jomlJar.FullName
    }
    $commonsLangJar = Find-DeclaredCachedJar "org.apache.commons" "commons-lang3" "commons-lang3-*.jar"
    if ($commonsLangJar) {
        $dependencyJars += $commonsLangJar.FullName
    }
    $authlibJar = Find-DeclaredCachedJar "com.mojang" "authlib" "authlib-*.jar"
    if ($authlibJar) {
        $dependencyJars += $authlibJar.FullName
    }
    $bridgeJar = Find-DeclaredCachedJar "com.mojang" "bridge" "bridge-*.jar"
    if ($bridgeJar) {
        $dependencyJars += $bridgeJar.FullName
    }
    $javaBridgeJar = Find-DeclaredCachedJar "com.mojang" "javabridge" "javabridge-*.jar"
    if ($javaBridgeJar) {
        $dependencyJars += $javaBridgeJar.FullName
    }
    $mojangLoggingJar = Find-DeclaredCachedJar "com.mojang" "logging" "logging-*.jar"
    if ($mojangLoggingJar) {
        $dependencyJars += $mojangLoggingJar.FullName
    }
    $slf4jApiJar = Find-DeclaredCachedJar "org.slf4j" "slf4j-api" "slf4j-api-*.jar"
    if ($slf4jApiJar) {
        $dependencyJars += $slf4jApiJar.FullName
    }
    $fabricApiRoot = Join-Path $ProjectDir ".gradle\loom-cache\remapped_mods\remapped\net\fabricmc\fabric-api"
    if (Test-Path $fabricApiRoot) {
        $dependencyJars += Get-ChildItem -Path $fabricApiRoot -Recurse -Filter "*.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            ForEach-Object { $_.FullName }
    }
    foreach ($localLibraryRoot in $localLibraryRoots) {
        foreach ($lightLoaderModuleRoot in @("net\minecraftforge\eventbus", "net\minecraftforge\fmlloader", "net\minecraftforge\fmlcore", "net\minecraftforge\forgespi", "net\minecraftforge\unsafe", "org\apache\logging\log4j", "cpw\mods")) {
            $lightLoaderPath = Join-Path $localLibraryRoot $lightLoaderModuleRoot
            if (Test-Path $lightLoaderPath) {
                $dependencyJars += Get-ChildItem -Path $lightLoaderPath -Recurse -Filter "*.jar" |
                    Where-Object { $_.Name -notmatch "sources|javadoc" } |
                    ForEach-Object { $_.FullName }
            }
        }
    }
    if ($includeLocalRuntimeJars) {
        foreach ($localLibraryRoot in $localLibraryRoots) {
            foreach ($loaderModuleRoot in @("net\minecraftforge", "net\neoforged")) {
                $loaderPath = Join-Path $localLibraryRoot $loaderModuleRoot
                if (Test-Path $loaderPath) {
                    $dependencyJars += Get-ChildItem -Path $loaderPath -Recurse -Filter "*.jar" |
                        Where-Object { $_.Name -notmatch "sources|javadoc" } |
                        ForEach-Object { $_.FullName }
                }
            }
        }
        $localClientJars = @()
        foreach ($localLibraryRoot in $localLibraryRoots) {
            if (Test-Path $localLibraryRoot) {
                $localClientJars += Get-ChildItem -Path $localLibraryRoot -Recurse -Filter "*.jar" |
                    Where-Object { $_.Name -notmatch "sources|javadoc" } |
                    Sort-Object FullName -Descending |
                    ForEach-Object { $_.FullName }
            }
        }
        $dependencyJars = @($localClientJars + $dependencyJars | Select-Object -Unique)
    }
    $dependencyJars = @($dependencyJars |
            Where-Object {
                $_ -notmatch "\\com\\google\\code\\gson\\gson\\" -and
                $_ -notmatch "\\net\\neoforged\\installertools\\" -and
                $_ -notmatch "\\org\\ow2\\asm\\asm\\9\.6\\"
            } |
            Select-Object -Unique)
    $classpath = @($classes, $resources, $gsonJar.FullName, $minecraftJar.FullName) + $dependencyJars -join ";"

    $argClasspath = $classpath -replace '\\', '/'
    $argTempDir = $tempDir -replace '\\', '/'
    $argSourceFile = $sourceFile -replace '\\', '/'
    $argFixturePath = $FixturePath -replace '\\', '/'
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
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

    $javaClasspath = "$classpath;$tempDir"
    & java -cp $javaClasspath SimpleTranslateDirectFixtureChecks $FixturePath
    if ($LASTEXITCODE -ne 0) {
        throw "translation fixture checks failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
