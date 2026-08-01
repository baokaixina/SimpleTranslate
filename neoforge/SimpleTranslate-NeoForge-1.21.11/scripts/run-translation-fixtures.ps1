param(
    [string]$ProjectDir = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$FixturePath = ""
)

$ErrorActionPreference = "Stop"

# BEGIN SimpleTranslate project JDK pin
. (Join-Path $PSScriptRoot "resolve-java.ps1")
Use-SimpleTranslateProjectJava -ProjectDir $ProjectDir -Purpose Gradle | Out-Null
# END SimpleTranslate project JDK pin

# project JDK pin disabled for 1.21.11 manual JAVA_HOME




function Test-JavaHome([string]$JavaHome) {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $false
    }
    try {
        $java = [System.IO.Path]::Combine($JavaHome.Trim(), "bin", "java.exe")
        $javac = [System.IO.Path]::Combine($JavaHome.Trim(), "bin", "javac.exe")
        return (Test-Path -LiteralPath $java -PathType Leaf -ErrorAction Stop) -and
                (Test-Path -LiteralPath $javac -PathType Leaf -ErrorAction Stop)
    } catch {
        return $false
    }
}

function Get-JavaMajor([string]$JavaHome) {
    if (-not (Test-JavaHome $JavaHome)) {
        return 0
    }
    try {
        $java = [System.IO.Path]::Combine($JavaHome.Trim(), "bin", "java.exe")
        $versionText = [string]::Join("`n", @(& $java -version 2>&1))
        $match = [regex]::Match($versionText, '(?:version\s+"|openjdk\s+)(\d+)')
        if ($match.Success) {
            return [int]$match.Groups[1].Value
        }
        return 0
    } catch {
        return 0
    }
}

function Resolve-JavaHome([int]$MinimumMajor) {
    $configured = Use-SimpleTranslateProjectJava -ProjectDir $ProjectDir -Purpose Gradle
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        return $configured
    }
    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add($env:JAVA_HOME)
    }

    $roots = @(
        [System.IO.Path]::Combine($env:USERPROFILE, ".jdks"),
        [System.IO.Path]::Combine($env:USERPROFILE, ".gradle", "jdks"),
        [System.IO.Path]::Combine($env:ProgramFiles, "Java"),
        [System.IO.Path]::Combine($env:ProgramFiles, "Zulu"),
        [System.IO.Path]::Combine($env:ProgramFiles, "Eclipse Adoptium")
    )
    foreach ($root in $roots) {
        try {
            if (Test-Path -LiteralPath $root -PathType Container -ErrorAction Stop) {
                Get-ChildItem -LiteralPath $root -Directory -ErrorAction Stop |
                        Sort-Object Name -Descending |
                        ForEach-Object { $candidates.Add($_.FullName) }
            }
        } catch {
            # A stale environment path must not prevent discovery of installed JDKs.
        }
    }

    foreach ($candidate in $candidates) {
        $major = Get-JavaMajor $candidate
        if ($major -ge $MinimumMajor) {
            return $candidate
        }
    }
    return $null
}

$resolvedJavaHome = Resolve-JavaHome 21
if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
    throw "Java 21+ JDK not found. Set JAVA_HOME to a valid JDK or install one under %USERPROFILE%\\.jdks."
}
$env:JAVA_HOME = $resolvedJavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$JavaCommand = [System.IO.Path]::Combine($env:JAVA_HOME, "bin", "java.exe")
$JavacCommand = [System.IO.Path]::Combine($env:JAVA_HOME, "bin", "javac.exe")
$tempDir = $null

Push-Location $ProjectDir
try {
    if ([string]::IsNullOrWhiteSpace($FixturePath)) {
        $FixturePath = Join-Path $ProjectDir "scripts\translation-fixtures.json"
    }
    $FixturePath = (Resolve-Path $FixturePath).Path

& "$env:JAVA_HOME\bin\java.exe" "-Dorg.gradle.appname=gradlew" `
  -classpath "gradle/wrapper/gradle-wrapper.jar" `
  org.gradle.wrapper.GradleWrapperMain compileJava --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "compileJava failed with exit code $LASTEXITCODE"
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-json-fixtures-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateJsonFixtureChecks.java"

    $javaSource = @'
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.cache.ComponentJsonCacheEditor;
import com.yourname.simpletranslate.cache.LineTranslationMemory;
import com.yourname.simpletranslate.cache.TermDictionary;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.config.TranslationProfileManager;
import com.yourname.simpletranslate.core.ActiveFontManager;
import com.yourname.simpletranslate.core.ComponentJsonCompat;
import com.yourname.simpletranslate.core.ComponentJsonNumberNormalizer;
import com.yourname.simpletranslate.core.ProtectedTextRuns;
import com.yourname.simpletranslate.core.ComponentVisualProjection;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TextContextMemory;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.feature.chat.ChatAutoTranslationFilter;
import com.yourname.simpletranslate.feature.hud.ActionbarLayoutRenderer;
import com.yourname.simpletranslate.feature.hud.HudFeature;
import com.yourname.simpletranslate.feature.gui.GuiTranslationHelper;
import com.yourname.simpletranslate.feature.gui.GuiLayoutProgramRenderer;
import com.yourname.simpletranslate.feature.hud.HudTextSupport;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationHelper;
import com.yourname.simpletranslate.feature.sign.SignJsonDocument;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationController;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.feature.tooltip.TooltipSemanticResultStore;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationTriggerState;
import com.yourname.simpletranslate.keybind.ShortcutAction;
import com.yourname.simpletranslate.feature.wynn.WynnActionbarGlyphOverlayPlan;
import com.yourname.simpletranslate.feature.wynn.WynnDialogueProjection;
import com.yourname.simpletranslate.feature.wynn.WynnDialoguePendingEffect;
import com.yourname.simpletranslate.feature.wynn.WynnDialogueRenderPlan;
import com.yourname.simpletranslate.feature.wynn.WynncraftProfile;
import com.yourname.simpletranslate.mixin.TextDisplayMixin;
import com.yourname.simpletranslate.transport.JsonPassthroughPrompts;
import com.yourname.simpletranslate.transport.TranslationLane;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.transport.TranslationPromptPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SimpleTranslateJsonFixtureChecks {
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            if (args.length != 1) {
                throw new IllegalArgumentException("Usage: SimpleTranslateJsonFixtureChecks <fixtures.json>");
            }
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
            assertEquals("component-json-fixtures-v2", root.get("version").getAsString(), "fixture version");
            assertTrue(root.getAsJsonArray("fixtures").size() >= 20, "surface fixture inventory");
            List<String> fixtureSurfaces = new ArrayList<>();
            for (JsonElement fixture : root.getAsJsonArray("fixtures")) {
                fixtureSurfaces.add(fixture.getAsJsonObject().get("surface").getAsString());
            }
            assertTrue(fixtureSurfaces.contains("gui.component.visible_frame.v3")
                    && fixtureSurfaces.contains("hud.actionbar.wynn.dialogue.content.paragraph.v5"),
                    "fixture inventory tracks the shared GUI frame and Wynn paragraph dialogue cache generation");

            checkExternalWynnDialogueFixture();
            checkLooseComponentJsonAcceptance();
            checkAutoChatFilterClassification();
            checkCustomFontSanitizer();
            checkClientOnlyOverlayFontProjection();
            checkHudOverlayIncrementalActivation();
            checkHoverEventsFrozenForVisibleRequests();
            checkInvalidResponsesRejected();
            checkComponentVisualProjection();
            checkDynamicNaturalLanguageTranslateKey();
            checkJsonTextNodeEditing();
            checkSignPositionMapping();
            checkSignCacheAcceptance();
            checkCacheKeyMigrationShape();
            checkPromptPolicyMutations();
            checkCurrentTooltipContextToggles();
            checkDynamicMarkerCacheSafety();
            checkDynamicNumericTemplate();
            checkDynamicNumberUnitSuffix();
        checkScoreboardSemanticFrame();
        checkHudPlaceholderPreservation();
            checkWynncraftHudFontSplit();
            checkWynncraftLayoutCriticalHud();
            checkWynnStructuralOwnership();
            checkWynncraftActionbarLayoutRenderer();
            checkGuiLayoutMaterializedWrappers();
            checkWynnDirectSurfaces();
            checkWynnDialogueProjection();
            checkWirePayloadContextSeparation();
            checkUnifiedTooltipProductionProjection();
            checkTranslationLaneLeaseIsolation();
            checkStyledTooltipWrapping();
            checkProtectedTextRuns();
            checkComponentJsonCompactRetryAndHoverIdentity();
            checkAdaptiveComponentPartitionRecovery();
            checkLegacyTooltipSemanticCacheBridge();
            checkDecoratedWynnServiceLabel();
            checkWynnDialoguePendingTracker();
            System.out.println("SimpleTranslate component JSON fixtures passed");
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void checkAutoChatFilterClassification() {
        String avsys = "[ａｖＳＹＳ] ／／ＣＲＩＴＩＣＡＬ ＬＩＦＥ ＳＵＰＰＯＲＴ ＥＲＲＯＲ！／／";
        assertEquals("[avSYS] //CRITICAL LIFE SUPPORT ERROR!//",
                ChatAutoTranslationFilter.candidateBodyForTest(avsys),
                "AUTO chat classification normalizes fullwidth system text");
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate(avsys),
                "fullwidth avSYS prose reaches AUTO translation");
        assertTrue(!ChatAutoTranslationFilter.shouldAutoTranslate("<Ｐｌａｙｅｒ> ｇｇ"),
                "fullwidth short player chatter remains filtered");

        for (String systemLabel : List.of(
                "Master Mode: [?]: ✗",
                "Death Counter: [?]: ✗",
                "Give Transparent Armor: [?]: ✓",
                "Quest Progress: [3/10]")) {
            assertEquals(systemLabel,
                    ChatAutoTranslationFilter.candidateBodyForTest(systemLabel),
                    "multi-word colon label remains complete: " + systemLabel);
            assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate(systemLabel),
                    "multi-word colon label reaches AUTO translation: " + systemLabel);
        }

        assertEquals("Status: ✓",
                ChatAutoTranslationFilter.candidateBodyForTest("Status: ✓"),
                "ambiguous symbol-only colon value fails open as system text");
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate("Status: ✓"),
                "single-word status label with a symbol value reaches AUTO translation");
        assertEquals("gg", ChatAutoTranslationFilter.candidateBodyForTest("Player: gg"),
                "plain username prefix still isolates the chat body");
        assertTrue(!ChatAutoTranslationFilter.shouldAutoTranslate("Player: gg"),
                "ordinary short player chatter remains filtered");
        assertEquals("Please help at spawn.",
                ChatAutoTranslationFilter.candidateBodyForTest("Player: Please help at spawn."),
                "ordinary player prose still isolates the chat body");
        assertTrue(ChatAutoTranslationFilter.shouldAutoTranslate("Player: Please help at spawn."),
                "ordinary player prose remains eligible for AUTO translation");
    }


    private static void checkDynamicNaturalLanguageTranslateKey() {
        String warning = "\u00a76Distant Horizons: Low memory detected.\n"
                + "\u00a7fStuttering or low FPS may occur.";
        String escaped = warning.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
        ComponentVisualProjection projection = ComponentVisualProjection.project(
                "[{\"translate\":\"" + escaped + "\",\"color\":\"yellow\"}]",
                "zh_cn");
        assertNotNull(projection, "dynamic natural-language translate projection");
        assertTrue(projection.hasSlots(),
                "an absent translate key containing visible prose becomes semantic text");
        assertTrue(projection.semanticJson().contains("Distant Horizons")
                        && projection.semanticJson().contains("Low memory"),
                "multiline formatted mod warning reaches the Component JSON request");

        ComponentVisualProjection resourceKey = ComponentVisualProjection.project(
                "[{\"translate\":\"distanthorizons.warning.low_memory\"}]", "zh_cn");
        assertNotNull(resourceKey, "ordinary missing resource-key projection");
        assertTrue(!resourceKey.hasSlots(),
                "an ordinary resource identifier never becomes model-visible prose");

        ComponentVisualProjection chineseToEnglish = ComponentVisualProjection.project(
                "[{\"translate\":\"低内存警告。可能会出现卡顿。\"}]", "en");
        assertNotNull(chineseToEnglish,
                "non-Chinese target dynamic natural-language projection");
        assertTrue(chineseToEnglish.hasSlots()
                        && chineseToEnglish.semanticJson().contains("低内存警告"),
                "Chinese dynamic translate prose reaches an English-target request");
        ComponentVisualProjection compactChineseToEnglish = ComponentVisualProjection.project(
                "[{\"translate\":\"低内存警告\"}]", "en");
        assertNotNull(compactChineseToEnglish,
                "unpunctuated CJK dynamic translate projection");
        assertTrue(compactChineseToEnglish.hasSlots(),
                "unpunctuated CJK dynamic translate prose reaches the request");
    }

    private static void checkLooseComponentJsonAcceptance() {
        Component source = Component.literal("Advanced Active Skills").withStyle(ChatFormatting.YELLOW);
        List<Component> originals = List.of(source);
        String serialized = JsonPassthroughPipeline.serializeComponents(originals);
        assertEquals(1, JsonParser.parseString(serialized).getAsJsonArray().size(), "serialized array size");

        String changed = "[{\"text\":\"\u9ad8\u7ea7\",\"color\":\"red\",\"extra\":[{\"text\":\"\u4e3b\u52a8\u6280\u80fd\",\"underlined\":true}]}]";
        List<Component> restored = JsonPassthroughPipeline.deserializeComponents(changed, originals);
        assertNotNull(restored, "valid changed structure accepted");
        assertEquals("\u9ad8\u7ea7\u4e3b\u52a8\u6280\u80fd", restored.get(0).getString(), "translated visible text");
        assertEquals("red", restored.get(0).getStyle().getColor().serialize(), "model color change accepted");

        String fenced = "```json\n" + changed + "\n```";
        assertNull(JsonPassthroughPipeline.deserializeComponents(fenced, originals),
                "markdown fences are invalid Component JSON and must keep the source");

        String extraOnly = "[{\"extra\":[{\"text\":\"\u4e2d\u6587\",\"color\":\"gold\"}]}]";
        List<Component> normalized = JsonPassthroughPipeline.deserializeComponents(extraOnly, originals);
        assertNotNull(normalized, "extra-only component gains an empty text root");
        assertEquals("\u4e2d\u6587", normalized.get(0).getString(), "extra-only translated text retained");

        String nestedExtraOnly = "[{\"extra\":[{\"extra\":[{\"text\":\"\u5d4c\u5957\u4e2d\u6587\"}]}]}]";
        List<Component> nested = JsonPassthroughPipeline.deserializeComponents(nestedExtraOnly, originals);
        assertNotNull(nested, "nested extra-only components normalized recursively");
        assertEquals("\u5d4c\u5957\u4e2d\u6587", nested.get(0).getString(), "nested translated text retained");
    }

    private static void checkCurrentTooltipContextToggles() {
        boolean item = ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.get();
        boolean hover = ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.get();
        boolean book = ModConfig.API_TEXT_CONTEXT_BOOK.get();
        try {
            ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(false);
            ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(false);
            ModConfig.API_TEXT_CONTEXT_BOOK.set(false);
            assertTrue(!TextContextMemory.isSurfaceEnabled("tooltip.visible.item.component.v2"),
                    "current item-tooltip surface obeys its context switch");
            assertTrue(!TextContextMemory.isSurfaceEnabled("tooltip.visible.chat_hover.component.v2"),
                    "current chat-hover surface obeys its context switch");
            assertTrue(!TextContextMemory.isSurfaceEnabled("tooltip.visible.book_hover.component.v2"),
                    "current book-hover surface obeys its context switch");

            ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(true);
            ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(true);
            ModConfig.API_TEXT_CONTEXT_BOOK.set(true);
            assertTrue(TextContextMemory.isSurfaceEnabled("tooltip.visible.item.component.v2")
                            && TextContextMemory.isSurfaceEnabled("tooltip.visible.chat_hover.component.v2")
                            && TextContextMemory.isSurfaceEnabled("tooltip.visible.book_hover.component.v2"),
                    "current tooltip context switches enable their production surfaces");
        } finally {
            ModConfig.API_TEXT_CONTEXT_ITEM_TOOLTIP.set(item);
            ModConfig.API_TEXT_CONTEXT_HOVER_TOOLTIP.set(hover);
            ModConfig.API_TEXT_CONTEXT_BOOK.set(book);
        }
    }


    private static void checkCustomFontSanitizer() {
        Component source = Component.literal("Oak Wood Wand");
        List<Component> originals = List.of(source);
        String inheritedWynnFont = "[{\"text\":\"\",\"font\":\"minecraft:language/wynncraft\","
                + "\"extra\":[{\"text\":\"\u6a61\u6728\u6cd5\u6756\",\"color\":\"white\"}]}]";
        List<Component> inheritedSanitized = JsonPassthroughPipeline.deserializeComponents(inheritedWynnFont, originals);
        assertNotNull(inheritedSanitized, "inherited custom-font CJK response accepted");
        String inheritedJson = ComponentJsonCompat.toJson(inheritedSanitized.get(0));
        assertTrue(inheritedJson.contains("minecraft:default"),
                "CJK child overrides inherited resource-pack custom font");
        assertTrue(inheritedJson.contains("\u6a61\u6728\u6cd5\u6756"),
                "CJK child text remains visible after inherited font override");
    }

    private static void checkClientOnlyOverlayFontProjection() {
        FontDescription.AtlasSprite questIcon = new FontDescription.AtlasSprite(
                Identifier.fromNamespaceAndPath("minecraft", "items"),
                Identifier.fromNamespaceAndPath(
                        "minecraft", "wynn/gui/content_book/quest_active"));
        FontDescription.Resource wynnLanguage = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("minecraft", "language/wynncraft"));
        Component source = Component.empty()
                .append(Component.literal("A").withStyle(
                        Style.EMPTY.withFont(questIcon)))
                .append(Component.literal("\u00c0"))
                .append(Component.literal("Quest - Dearly Departed").withStyle(
                        Style.EMPTY.withFont(wynnLanguage)))
                .append(Component.literal("Talk to Farmer Cevalus at [-751, 70, -1666]"));

        String encoded = ComponentJsonCompat.toJson(source);
        assertTrue(encoded.contains("simple_translate:local_font/atlas_sprite/"),
                "client-only atlas font is represented by a local JSON marker");

        ComponentVisualProjection projection = ComponentVisualProjection.projectComponents(
                List.of(source), "zh_cn");
        assertNotNull(projection, "Wynntils mixed icon-and-prose overlay projection");
        assertTrue(projection.hasSlots(),
                "prose beside an atlas-sprite icon reaches the model");
        assertTrue(projection.semanticJson().contains("Dearly Departed")
                        && projection.semanticJson().contains("Farmer Cevalus"),
                "the complete visible quest title and task reach the request");
        assertTrue(!projection.semanticJson().contains(
                        "simple_translate:local_font/atlas_sprite/")
                        && !projection.semanticJson().contains("\u00c0")
                        && !projection.semanticComponents().stream()
                        .anyMatch(component -> "A".equals(component.getString())),
                "the local icon, its ordinary A carrier and adjacent Wynn padding stay model-invisible");

        List<Component> translatedSlots = new ArrayList<>();
        for (int index = 0; index < projection.slotCount(); index++) {
            translatedSlots.add(Component.literal("译文" + index));
        }
        JsonArray rebuilt = projection.rebuildComponents(translatedSlots);
        assertNotNull(rebuilt, "translated Wynn overlay source skeleton rebuilt");
        List<Component> restored = JsonPassthroughPipeline.deserializeComponents(
                rebuilt.toString(), List.of(source));
        assertNotNull(restored, "translated Wynn overlay parses through production pipeline");
        assertTrue(restored.get(0).getString().startsWith("A")
                        && restored.get(0).getString().contains("译文"),
                "the exact icon carrier remains while adjacent prose changes");

        boolean[] exactAtlasFontRestored = {false};
        restored.get(0).visit((style, text) -> {
            if (questIcon.equals(style.getFont())) {
                exactAtlasFontRestored[0] = true;
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        assertTrue(exactAtlasFontRestored[0],
                "the exact live AtlasSprite font object is restored after translation");
        assertTrue(ComponentJsonCompat.toJson(restored.get(0)).contains(
                        "simple_translate:local_font/atlas_sprite/"),
                "a restored client-only font remains safely serializable on later frames");
    }

    private static void checkHudOverlayIncrementalActivation() throws Exception {
        Method activation = GuiTranslationHelper.class.getDeclaredMethod(
                "shouldAutomaticallyRequest", boolean.class, boolean.class,
                boolean.class, ModConfig.GuiTranslationMode.class);
        activation.setAccessible(true);
        boolean oldHudFrameActive = ModConfig.CONTENT_HUD_FRAME_ACTIVE.get();
        try {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(false);
            assertTrue((boolean) activation.invoke(null, true, false, true,
                            ModConfig.GuiTranslationMode.SHORTCUT),
                    "a successful K HUD snapshot enables future overlay additions");
            assertTrue(!(boolean) activation.invoke(null, true, false, false,
                            ModConfig.GuiTranslationMode.SHORTCUT),
                    "HUD translation remains opt-in before K on a new installation");
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(true);
            assertTrue((boolean) activation.invoke(null, true, false, false,
                            ModConfig.GuiTranslationMode.SHORTCUT),
                    "persisted K HUD activation survives a world re-entry without an in-memory snapshot");
        } finally {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(oldHudFrameActive);
        }
        TranslationCache legacyHudCache = new TranslationCache(
                Files.createTempDirectory("simpletranslate-hud-activation")
                        .resolve("translations.json"));
        String legacyHudSource = "[{\"text\":\"Finish Quests: 1/3\"}]";
        String legacyHudKey = TranslationCacheKeys.componentJsonKey(
                "hud.visible_frame.component.v2", legacyHudSource, "fixture",
                "auto", "zh_cn");
        legacyHudCache.putComponentJson(
                legacyHudKey, "[{\"text\":\"完成任务：1/3\"}]",
                legacyHudSource, "Finish Quests: 1/3", "完成任务：1/3");
        assertTrue(legacyHudCache.hasSurface("hud.visible_frame.component.v2"),
                "startup HUD activation probe finds an accepted whole-frame cache without copying it");
        boolean activeBeforeMigration = ModConfig.CONTENT_HUD_FRAME_ACTIVE.get();
        try {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(false);
            GuiTranslationHelper.migrateLegacyHudFrameActivation(legacyHudCache);
            assertTrue(ModConfig.CONTENT_HUD_FRAME_ACTIVE.get(),
                    "an existing installation's accepted HUD cache migrates its prior K opt-in");
        } finally {
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(activeBeforeMigration);
        }
        assertTrue((boolean) activation.invoke(null, false, false, false,
                         ModConfig.GuiTranslationMode.AUTO),
                "ordinary GUI AUTO translation remains automatic");
        assertTrue((boolean) activation.invoke(null, false, false, true,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "one successful K snapshot authorizes newly scrolled GUI rows");
        assertTrue(!(boolean) activation.invoke(null, false, false, false,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "GUI shortcut mode remains opt-in before its first snapshot");
        assertTrue(!(boolean) activation.invoke(null, true, true, true,
                        ModConfig.GuiTranslationMode.AUTO),
                "detached tooltip and advancement captures do not compete with HUD requests");

        Method openScreenFrame = GuiTranslationHelper.class.getDeclaredMethod(
                "shouldOpenScreenFrame", boolean.class, boolean.class,
                ModConfig.GuiTranslationMode.class);
        openScreenFrame.setAccessible(true);
        assertTrue(!(boolean) openScreenFrame.invoke(null, false, false,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "idle shortcut GUI does not open a capture frame");
        assertTrue((boolean) openScreenFrame.invoke(null, true, false,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "manual K opens the current GUI frame");
        assertTrue((boolean) openScreenFrame.invoke(null, false, true,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "an accepted GUI snapshot keeps replay active");
        assertTrue((boolean) openScreenFrame.invoke(null, false, false,
                        ModConfig.GuiTranslationMode.AUTO),
                "GUI AUTO opens a capture frame");

        Method deferred = GuiTranslationHelper.class.getDeclaredMethod(
                "shouldIncludeDeferredElements", boolean.class, boolean.class,
                ModConfig.GuiTranslationMode.class);
        deferred.setAccessible(true);
        assertTrue((boolean) deferred.invoke(null, true, false,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "manual K includes the exact 1.21.11 deferred tooltip stage");
        assertTrue((boolean) deferred.invoke(null, false, true,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "an accepted screen snapshot keeps deferred tooltip replay active");
        assertTrue((boolean) deferred.invoke(null, false, false,
                        ModConfig.GuiTranslationMode.AUTO),
                "GUI AUTO includes generic deferred widget tooltips");
        assertTrue(!(boolean) deferred.invoke(null, false, false,
                        ModConfig.GuiTranslationMode.SHORTCUT),
                "an unarmed shortcut frame yields deferred item tooltips to their dedicated path");

        Method merge = GuiTranslationHelper.class.getDeclaredMethod(
                "mergeTranslations", Map.class, Map.class, int.class);
        merge.setAccessible(true);
        Map<String, Component> firstViewport = new java.util.LinkedHashMap<>();
        firstViewport.put("row-a", Component.literal("甲"));
        firstViewport.put("row-b", Component.literal("乙"));
        Map<String, Component> secondViewport = new java.util.LinkedHashMap<>();
        secondViewport.put("row-b", Component.literal("乙（更新）"));
        secondViewport.put("row-c", Component.literal("丙"));
        @SuppressWarnings("unchecked")
        Map<String, Component> merged = (Map<String, Component>) merge.invoke(
                null, firstViewport, secondViewport, 8);
        assertTrue(merged.size() == 3 && merged.containsKey("row-a")
                        && "乙".equals(merged.get("row-b").getString())
                        && merged.containsKey("row-c"),
                "late viewport results add missing rows without replacing an accepted translation");
        @SuppressWarnings("unchecked")
        Map<String, Component> bounded = (Map<String, Component>) merge.invoke(
                null, firstViewport, secondViewport, 2);
        assertTrue(!bounded.containsKey("row-a") && bounded.containsKey("row-b")
                        && bounded.containsKey("row-c"),
                "accumulated screen snapshots evict only the oldest rows at their safety bound");

        Method telemetry = GuiTranslationHelper.class.getDeclaredMethod(
                "isHudTelemetryText", String.class);
        telemetry.setAccessible(true);
        assertTrue((boolean) telemetry.invoke(null, "\uE100 -840 SW -1576 \uE104"),
                "custom-font coordinate telemetry is excluded from HUD translation context");
        assertTrue((boolean) telemetry.invoke(null, "N")
                        && (boolean) telemetry.invoke(null, "SW"),
                "standalone compass labels are treated as HUD telemetry");
        assertTrue(!(boolean) telemetry.invoke(null, "Slay Lv. 1+ Mobs: 10/50")
                        && !(boolean) telemetry.invoke(null, "Ragni")
                        && !(boolean) telemetry.invoke(null, "NEWS"),
                "natural-language objectives, place names, and ordinary words remain translatable");
        assertTrue(!ShortcutAction.TOGGLE_GLOBAL_TRANSLATION.defaultChord().isBound(),
                "the global translation shortcut is customizable and unbound by default");

        FontDescription animatedTooltipFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("mcc", "animated_tooltip"));
        Component tooltipFrameA = Component.empty()
                .append(Component.literal("\uE100").withStyle(
                        Style.EMPTY.withFont(animatedTooltipFont)))
                .append(Component.literal(" Daily Quest Progress: 0/5")
                        .withStyle(ChatFormatting.YELLOW));
        Component tooltipFrameB = Component.empty()
                .append(Component.literal("\uE101").withStyle(
                        Style.EMPTY.withFont(animatedTooltipFont)))
                .append(Component.literal(" Daily Quest Progress: 3/5")
                        .withStyle(ChatFormatting.YELLOW));
        String tooltipKeyA = GuiTranslationHelper.detachedFrameKey(
                "gui.item_tooltip", List.of(tooltipFrameA));
        String tooltipKeyB = GuiTranslationHelper.detachedFrameKey(
                "gui.item_tooltip", List.of(tooltipFrameB));
        assertEquals(tooltipKeyA, tooltipKeyB,
                "an async item translation remains attached while its icon and progress animate");
        assertTrue(!tooltipKeyA.equals(GuiTranslationHelper.detachedFrameKey(
                        "gui.item_tooltip", List.of(Component.literal("Health Regeneration")))),
                "different tooltip prose still owns a different detached frame");

        ComponentVisualProjection tooltipProjection = JsonPassthroughPipeline.projectLiveComponents(
                List.of(tooltipFrameA), "zh_cn");
        assertNotNull(tooltipProjection, "item frame exposes a reusable semantic projection");
        List<Component> translatedTooltip = tooltipProjection.rebuildComponentList(
                List.of(Component.literal("每日任务进度：")));
        assertNotNull(translatedTooltip, "translated item frame keeps its source skeleton");
        Method extractTooltipSlots = GuiTranslationHelper.class.getDeclaredMethod(
                "itemTooltipTranslatedSlots", ComponentVisualProjection.class, Component.class);
        extractTooltipSlots.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Component> tooltipSlots = (List<Component>) extractTooltipSlots.invoke(
                null, tooltipProjection, translatedTooltip.getFirst());
        assertNotNull(tooltipSlots, "accepted async item result retains semantic translated slots");
        Method rebindTooltip = GuiTranslationHelper.class.getDeclaredMethod(
                "rebuildItemTooltipSemanticTranslation",
                Component.class, Component.class, List.class);
        rebindTooltip.setAccessible(true);
        Component reboundTooltip = (Component) rebindTooltip.invoke(
                null, tooltipFrameB, tooltipFrameB, tooltipSlots);
        assertNotNull(reboundTooltip, "accepted item result rebinds during the same continuous hover");
        assertTrue(reboundTooltip.getString().contains("\uE101")
                        && reboundTooltip.getString().contains("3/5")
                        && reboundTooltip.getString().contains("每日任务进度")
                        && !reboundTooltip.getString().contains("0/5"),
                "tooltip rebind displays translated prose with the current icon and current progress");

        Field translationCacheField = com.yourname.simpletranslate.SimpleTranslateClientBootstrap.class
                .getDeclaredField("translationCache");
        translationCacheField.setAccessible(true);
        TranslationCache previousTranslationCache =
                (TranslationCache) translationCacheField.get(null);
        boolean oldCacheEnabled = ModConfig.CACHE_ENABLED.get();
        boolean oldTooltipGlobalEnabled = ModConfig.GLOBAL_ENABLED.get();
        try {
            ModConfig.CACHE_ENABLED.set(true);
            ModConfig.GLOBAL_ENABLED.set(true);
            TranslationCache immediateTooltipCache = new TranslationCache(
                    Files.createTempDirectory("simpletranslate-tooltip-first-frame")
                            .resolve("translations.json"));
            translationCacheField.set(null, immediateTooltipCache);
            GuiTranslationHelper.clearLocalState();

            assertTrue(GuiTranslationHelper.beginDetachedFrame(
                            tooltipKeyA, "Item tooltip", true),
                    "fixture opens an item frame to derive the production cache identity");
            GuiTranslationHelper.translateVisible(tooltipFrameA);
            Method activeFrame = GuiTranslationHelper.class.getDeclaredMethod("activeFrame");
            activeFrame.setAccessible(true);
            Object cacheProbeFrame = activeFrame.invoke(null);
            Field frameSourcesField = cacheProbeFrame.getClass().getDeclaredField("sources");
            frameSourcesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Component> frameSources =
                    (Map<String, Component>) frameSourcesField.get(cacheProbeFrame);
            List<Component> cacheSources = new ArrayList<>(frameSources.values());
            Method buildFrameContext = GuiTranslationHelper.class.getDeclaredMethod(
                    "buildFrameContext", cacheProbeFrame.getClass(), List.class);
            buildFrameContext.setAccessible(true);
            String itemFrameContext = (String) buildFrameContext.invoke(
                    null, cacheProbeFrame, cacheSources);
            GuiTranslationHelper.clearLocalState();

            JsonPassthroughPipeline.cacheResolvedComponents(
                    cacheSources, translatedTooltip,
                    "gui.component.visible_frame.item_tooltip.v1",
                    "gui-visible-frame", itemFrameContext,
                    com.yourname.simpletranslate.SimpleTranslateMod.getRuntimeRevision());
            Component tooltipFrameCurrent = Component.empty()
                    .append(Component.literal("\uE100").withStyle(
                            Style.EMPTY.withFont(animatedTooltipFont)))
                    .append(Component.literal(" Daily Quest Progress: 3/5")
                            .withStyle(ChatFormatting.YELLOW));
            assertTrue(GuiTranslationHelper.beginItemTooltipFrame(
                            tooltipKeyA, "Item tooltip", false, List.of(tooltipFrameCurrent)),
                    "a persistent item cache opens before the 350ms hover dwell permits requests");
            assertTrue(GuiTranslationHelper.hasFrameSnapshot(tooltipKeyA),
                    "the persistent item cache is promoted before the first tooltip draw");
            FormattedCharSequence immediateSequence = GuiTranslationHelper.translateFormattedSequence(
                    tooltipFrameCurrent.getVisualOrderText());
            Component immediateTooltip = GuiTranslationHelper.componentFromFormattedSequence(
                    immediateSequence);
            assertTrue(immediateTooltip.getString().contains("每日任务进度")
                            && immediateTooltip.getString().contains("3/5")
                            && !immediateTooltip.getString().contains("0/5"),
                    "the first cached hover draws translated prose with current dynamic values");
            assertTrue(!GuiTranslationHelper.isFrameTranslationPending(tooltipKeyA),
                    "a first-frame cache hit never starts another model request");
            GuiTranslationHelper.endDetachedFrame(null);

            GuiTranslationHelper.clearLocalState();
            assertTrue(GuiTranslationHelper.beginItemTooltipFrame(
                            tooltipKeyA, "Item tooltip", false, List.of(tooltipFrameCurrent)),
                    "the same persistent item cache rehydrates after the hover snapshot is gone");
            Component repeatedTooltip = GuiTranslationHelper.componentFromFormattedSequence(
                    GuiTranslationHelper.translateFormattedSequence(
                            tooltipFrameCurrent.getVisualOrderText()));
            assertTrue(repeatedTooltip.getString().contains("每日任务进度")
                            && repeatedTooltip.getString().contains("3/5"),
                    "every repeated hover draws the cached translation on its first frame");
            GuiTranslationHelper.endDetachedFrame(null);
        } finally {
            GuiTranslationHelper.clearLocalState();
            translationCacheField.set(null, previousTranslationCache);
            ModConfig.CACHE_ENABLED.set(oldCacheEnabled);
            ModConfig.GLOBAL_ENABLED.set(oldTooltipGlobalEnabled);
        }

        Method translateWynnOverlays = GuiTranslationHelper.class.getDeclaredMethod(
                "shouldTranslateWynnOverlays", boolean.class, boolean.class);
        translateWynnOverlays.setAccessible(true);
        assertTrue((boolean) translateWynnOverlays.invoke(null, true, true),
                "the independent Wynn overlay switch enables persistent translation");
        assertTrue(!(boolean) translateWynnOverlays.invoke(null, false, true),
                "the global translation switch still disables Wynn overlays");
        assertTrue(!(boolean) translateWynnOverlays.invoke(null, true, false),
                "the independent Wynn overlay switch disables its exact render surface");

        boolean oldWynnGlobalEnabled = ModConfig.GLOBAL_ENABLED.get();
        boolean oldWynnOverlayEnabled = ModConfig.HUD_WYNN_OVERLAY_ENABLED.get();
        boolean oldGuiTranslationEnabled = ModConfig.CONTENT_GUI_ENABLED.get();
        boolean oldWynnHudFrameActive = ModConfig.CONTENT_HUD_FRAME_ACTIVE.get();
        GuiTranslationHelper.clearLocalState();
        try {
            ModConfig.GLOBAL_ENABLED.set(true);
            ModConfig.HUD_WYNN_OVERLAY_ENABLED.set(true);
            ModConfig.CONTENT_GUI_ENABLED.set(false);
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(false);

            Method currentFrame = GuiTranslationHelper.class.getDeclaredMethod("activeFrame");
            currentFrame.setAccessible(true);
            Field suppressionDepthField = GuiTranslationHelper.class.getDeclaredField(
                    "CAPTURE_SUPPRESSION_DEPTH");
            suppressionDepthField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Integer> suppressionDepth =
                    (ThreadLocal<Integer>) suppressionDepthField.get(null);

            GuiTranslationHelper.beginWynnOverlayFrame();
            Object wynnFrame = currentFrame.invoke(null);
            assertNotNull(wynnFrame,
                    "enabled Wynn overlays open without GUI AUTO, HUD activation, or K");
            Field frameKeyField = wynnFrame.getClass().getDeclaredField("screenKey");
            frameKeyField.setAccessible(true);
            assertEquals("hud.wynn.overlay_frame.v1", frameKeyField.get(wynnFrame),
                    "Wynn overlays own a cache document distinct from the ordinary K/HUD frame");
            assertEquals(0, suppressionDepth.get(),
                    "a cold-start Wynn overlay frame needs no outer K capture state");

            Component questOverlay = Component.literal(
                    "Quest - King's Recruit\nContinue through the underpass");
            GuiTranslationHelper.translateVisible(questOverlay);
            Field frameSourcesField = wynnFrame.getClass().getDeclaredField("sources");
            frameSourcesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Component> wynnSources =
                    (Map<String, Component>) frameSourcesField.get(wynnFrame);
            assertTrue(!wynnSources.isEmpty(),
                    "Wynntils standard Component text enters the independent overlay document");
            Method frameContextKind = GuiTranslationHelper.class.getDeclaredMethod(
                    "frameContextKind", wynnFrame.getClass());
            frameContextKind.setAccessible(true);
            assertEquals("wynn_overlay", frameContextKind.invoke(null, wynnFrame),
                    "Wynn overlays receive their dedicated whole-document context kind");
            Method surfaceForFrame = GuiTranslationHelper.class.getDeclaredMethod(
                    "surfaceForFrame", wynnFrame.getClass());
            surfaceForFrame.setAccessible(true);
            assertEquals("hud.wynn.overlay_frame.component.v1",
                    surfaceForFrame.invoke(null, wynnFrame),
                    "Wynn overlays use a cache surface isolated from ordinary K/HUD translations");
            wynnSources.clear();
            GuiTranslationHelper.endWynnOverlayFrame();

            assertTrue(GuiTranslationHelper.beginDetachedFrame(
                            "fixture.outer.k", "Outer K frame", true),
                    "fixture opens an outer frame to prove Wynn ownership isolation");
            GuiTranslationHelper.beginCaptureSuppression();
            GuiTranslationHelper.beginWynnOverlayFrame();
            Object nestedWynnFrame = currentFrame.invoke(null);
            assertEquals("hud.wynn.overlay_frame.v1", frameKeyField.get(nestedWynnFrame),
                    "Wynn opens its own document instead of borrowing the active K frame");
            assertEquals(0, suppressionDepth.get(),
                    "the exact Wynntils window temporarily suspends vanilla scoreboard suppression");
            GuiTranslationHelper.endWynnOverlayFrame();
            assertEquals(1, suppressionDepth.get(),
                    "the outer dedicated-HUD suppression depth is restored exactly");
            assertEquals("fixture.outer.k",
                    frameKeyField.get(currentFrame.invoke(null)),
                    "ending the Wynn frame returns ownership to the untouched outer frame");
            GuiTranslationHelper.endCaptureSuppression();
            GuiTranslationHelper.endDetachedFrame(null);

            GuiTranslationHelper.clearLocalState();
            ModConfig.HUD_WYNN_OVERLAY_ENABLED.set(false);
            assertTrue(GuiTranslationHelper.beginDetachedFrame(
                            "fixture.outer.k.disabled-wynn", "Outer K frame", true),
                    "fixture reopens K ownership with Wynn translation disabled");
            Object outerFrame = currentFrame.invoke(null);
            Field outerSourcesField = outerFrame.getClass().getDeclaredField("sources");
            outerSourcesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Component> outerSources =
                    (Map<String, Component>) outerSourcesField.get(outerFrame);
            GuiTranslationHelper.beginWynnOverlayFrame();
            GuiTranslationHelper.translateVisible(Component.literal("Quest - A Hunter's Calling"));
            assertTrue(outerSources.isEmpty(),
                    "disabled Wynn overlays are not captured by an active K frame");
            GuiTranslationHelper.endWynnOverlayFrame();
            GuiTranslationHelper.translateVisible(Component.literal("Ordinary visible menu text"));
            assertTrue(!outerSources.isEmpty(),
                    "K resumes normally after the exact disabled-Wynn render window closes");
            outerSources.clear();
            GuiTranslationHelper.endDetachedFrame(null);
        } finally {
            GuiTranslationHelper.clearLocalState();
            ModConfig.GLOBAL_ENABLED.set(oldWynnGlobalEnabled);
            ModConfig.HUD_WYNN_OVERLAY_ENABLED.set(oldWynnOverlayEnabled);
            ModConfig.CONTENT_GUI_ENABLED.set(oldGuiTranslationEnabled);
            ModConfig.CONTENT_HUD_FRAME_ACTIVE.set(oldWynnHudFrameActive);
        }

        boolean oldGlobalEnabled = ModConfig.GLOBAL_ENABLED.get();
        GuiTranslationHelper.clearLocalState();
        try {
            ModConfig.GLOBAL_ENABLED.set(true);
            FormattedCharSequence maskedSequence =
                    FormattedCharSequence.forward("masked Wynn source", Style.EMPTY);
            assertTrue(GuiTranslationHelper.beginDetachedFrame(
                            "fixture.dedicated.owner", "Dedicated owner", true),
                    "fixture opens one active frame before testing K suppression");
            GuiTranslationHelper.beginCaptureSuppression();
            try {
                assertTrue(GuiTranslationHelper.translateFormattedSequence(maskedSequence)
                                == maskedSequence,
                        "dedicated Wynn glyph masks reach Font without K rebuilding the sequence");
            } finally {
                GuiTranslationHelper.endCaptureSuppression();
                GuiTranslationHelper.endDetachedFrame(null);
            }
        } finally {
            GuiTranslationHelper.clearLocalState();
            ModConfig.GLOBAL_ENABLED.set(oldGlobalEnabled);
        }

        assertTrue(!TooltipTranslationController.isItemTooltipSubmission(),
                "item-tooltip submission scope starts inactive");
        TooltipTranslationController.beginItemTooltipSubmission();
        TooltipTranslationController.beginItemTooltipSubmission();
        assertTrue(TooltipTranslationController.isItemTooltipSubmission(),
                "nested ItemStack/container paths share one dedicated tooltip scope");
        TooltipTranslationController.endItemTooltipSubmission();
        TooltipTranslationController.endItemTooltipSubmission();
        assertTrue(!TooltipTranslationController.isItemTooltipSubmission(),
                "item-tooltip submission scope balances after terminal scheduling");

    }

    private static void checkDynamicMarkerCacheSafety() throws Exception {
        Field cacheField = com.yourname.simpletranslate.SimpleTranslateClientBootstrap.class
                .getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        TranslationCache previousCache = (TranslationCache) cacheField.get(null);
        boolean previousCacheEnabled = ModConfig.CACHE_ENABLED.get();
        boolean previousGlobalEnabled = ModConfig.GLOBAL_ENABLED.get();
        Path cacheDir = Files.createTempDirectory("simpletranslate-dynamic-marker-cache");
        TranslationCache cache = new TranslationCache(cacheDir.resolve("translations.json"));
        try {
            cache.load();
            cacheField.set(null, cache);
            ModConfig.CACHE_ENABLED.set(true);
            ModConfig.GLOBAL_ENABLED.set(true);

            Component sourceSell = Component.empty()
                    .append(Component.literal("Shift Right-Click to sell ("))
                    .append(Component.literal("43"))
                    .append(Component.literal("-"))
                    .append(Component.literal("89"))
                    .append(Component.literal(")"));
            Component sourceItem = Component.empty()
                    .append(Component.literal("1 x "))
                    .append(Component.literal("Bob's Tear"));
            List<Component> source = List.of(sourceSell, sourceItem);

            Component translatedSell = Component.empty()
                    .append(Component.literal("Shift+右键 出售（"))
                    .append(Component.literal("43"))
                    .append(Component.literal("-"))
                    .append(Component.literal("89"))
                    .append(Component.literal(")"));
            Component translatedItem = Component.empty()
                    .append(Component.literal("1× "))
                    .append(Component.literal("鲍勃之泪"));
            List<Component> translated = List.of(translatedSell, translatedItem);

            String surface = "fixture.dynamic_marker.item_tooltip";
            String role = "fixture-dynamic-marker";
            String context = "exact Ingredient Pouch quantity shape";
            long revision = com.yourname.simpletranslate.SimpleTranslateMod.getRuntimeRevision();
            JsonPassthroughPipeline.cacheResolvedComponents(
                    source, translated, surface, role, context, revision);

            String sourceJson = JsonPassthroughPipeline.serializeComponents(source);
            Method buildCacheKey = JsonPassthroughPipeline.class.getDeclaredMethod(
                    "buildCacheKey", String.class, String.class, String.class,
                    String.class, String.class, String.class);
            buildCacheKey.setAccessible(true);
            String key = (String) buildCacheKey.invoke(null, surface, sourceJson, context, role,
                    ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
            String safeTemplate = cache.get(key).orElseThrow();
            assertTrue(safeTemplate.contains("⟦N0⟧") && safeTemplate.contains("⟦N1⟧")
                            && safeTemplate.contains("1× ") && !safeTemplate.contains("⟦N2⟧"),
                    "cache template retains only source-owned price markers and keeps translated quantities literal");
            assertTrue(ComponentJsonNumberNormalizer.hasSameDynamicMarkerDomain(
                            JsonParser.parseString(sourceJson), JsonParser.parseString(safeTemplate)),
                    "cache template marker multiset exactly matches the normalized source tree");

            var safeHit = JsonPassthroughPipeline.getCachedComponents(
                    source, surface, role, false, context);
            assertTrue(safeHit.handled && safeHit.translated
                            && safeHit.components.get(1).getString().equals("1× 鲍勃之泪")
                            && safeHit.components.stream().noneMatch(component ->
                            component.getString().contains("⟦N")),
                    "safe cache replay restores live prices without exposing an internal marker");

            String poisoned = safeTemplate.replace("1× ", "⟦N2⟧× ");
            cache.putComponentJson(key, poisoned, sourceJson,
                    "Shift Right-Click to sell (43-89)\n1 x Bob's Tear",
                    "Shift+右键 出售（43-89）\n1× 鲍勃之泪");
            var rejectedHit = JsonPassthroughPipeline.getCachedComponents(
                    source, surface, role, false, context);
            assertTrue(rejectedHit.handled && !rejectedHit.translated
                            && rejectedHit.components == source,
                    "an old cache entry with an unowned marker keeps the original frame");
            assertTrue(cache.get(key).isEmpty(),
                    "an old cache entry with an unowned marker is lazily removed after its first hit");
        } finally {
            cache.flush();
            cacheField.set(null, previousCache);
            ModConfig.CACHE_ENABLED.set(previousCacheEnabled);
            ModConfig.GLOBAL_ENABLED.set(previousGlobalEnabled);
            try (var paths = Files.walk(cacheDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkStyledTooltipWrapping() throws Exception {
        FontDescription wideFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("simple_translate", "fixture/wide"));
        Font font = fixtureFont(wideFont, 4.0F, 10.0F);
        Component source = Component.literal("AB")
                .withStyle(Style.EMPTY.withFont(wideFont).withBold(true));
        Method wrapper = TooltipTranslationHelper.class.getDeclaredMethod(
                "wrapStyledTooltipComponent", Component.class, int.class, Font.class);
        wrapper.setAccessible(true);

        List<Component> wrapped = (List<Component>) wrapper.invoke(null, source, 20, font);
        assertEquals(2, wrapped.size(),
                "custom-font bold advances split at the rendered width");
        assertEquals("AB", wrapped.get(0).getString() + wrapped.get(1).getString(),
                "styled tooltip wrapping preserves every source code point");
        assertFloatEquals(11.0F, font.getSplitter().stringWidth(wrapped.get(0)),
                "first wrapped glyph keeps custom-font and bold metrics");
        assertFloatEquals(11.0F, font.getSplitter().stringWidth(wrapped.get(1)),
                "second wrapped glyph keeps custom-font and bold metrics");
        String wrappedJson = ComponentJsonCompat.toJson(wrapped.get(0));
        assertTrue(wrappedJson.contains("simple_translate:fixture/wide")
                        && wrappedJson.contains("\"bold\":true"),
                "wrapped component retains the exact font and bold style");

        Font unsafeFont = fixtureFont(wideFont, 4.0F, Float.NaN);
        Component unsafe = Component.literal("A").withStyle(Style.EMPTY.withFont(wideFont));
        List<Component> unsafeWrapped = (List<Component>) wrapper.invoke(null, unsafe, 2, unsafeFont);
        assertTrue(unsafeWrapped.size() == 1 && unsafeWrapped.get(0) == unsafe,
                "non-finite custom-font metrics fail closed to the complete source component");

        Font negativeFont = fixtureFont(wideFont, 4.0F, -3.0F);
        List<Component> negativeWrapped = (List<Component>) wrapper.invoke(null, unsafe, 2, negativeFont);
        assertTrue(negativeWrapped.size() == 1 && negativeWrapped.get(0) == unsafe,
                "negative positioning advances are never moved onto independent tooltip lines");
    }

    private static Font fixtureFont(FontDescription wideFont,
                                    float defaultAdvance,
                                    float wideAdvance) {
        net.minecraft.client.gui.font.glyphs.BakedGlyph defaultGlyph = fixtureGlyph(defaultAdvance);
        net.minecraft.client.gui.font.glyphs.BakedGlyph wideGlyph = fixtureGlyph(wideAdvance);
        net.minecraft.client.gui.GlyphSource defaultSource = fixtureGlyphSource(defaultGlyph);
        net.minecraft.client.gui.GlyphSource wideSource = fixtureGlyphSource(wideGlyph);
        return new Font(new Font.Provider() {
            @Override
            public net.minecraft.client.gui.GlyphSource glyphs(FontDescription font) {
                return wideFont.equals(font) ? wideSource : defaultSource;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
                return null;
            }
        });
    }

    private static net.minecraft.client.gui.GlyphSource fixtureGlyphSource(
            net.minecraft.client.gui.font.glyphs.BakedGlyph glyph) {
        return fixtureGlyphSource(glyph, Map.of());
    }

    private static net.minecraft.client.gui.GlyphSource fixtureGlyphSource(
            net.minecraft.client.gui.font.glyphs.BakedGlyph glyph,
            Map<Integer, net.minecraft.client.gui.font.glyphs.BakedGlyph> overrides) {
        Map<Integer, net.minecraft.client.gui.font.glyphs.BakedGlyph> safeOverrides =
                overrides == null ? Map.of() : Map.copyOf(overrides);
        return new net.minecraft.client.gui.GlyphSource() {
            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getGlyph(int codePoint) {
                return safeOverrides.getOrDefault(codePoint, glyph);
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.BakedGlyph getRandomGlyph(
                    net.minecraft.util.RandomSource random, int targetWidth) {
                return glyph;
            }
        };
    }

    private static net.minecraft.client.gui.font.glyphs.BakedGlyph fixtureGlyph(float advance) {
        return fixtureGlyph(advance, 0.0F, 8.0F);
    }

    private static net.minecraft.client.gui.font.glyphs.BakedGlyph fixtureGlyph(
            float advance, float topOffset, float height) {
        return new net.minecraft.client.gui.font.glyphs.BakedGlyph() {
            private final com.mojang.blaze3d.font.GlyphInfo info =
                    com.mojang.blaze3d.font.GlyphInfo.simple(advance);

            @Override
            public com.mojang.blaze3d.font.GlyphInfo info() {
                return this.info;
            }

            @Override
            public net.minecraft.client.gui.font.TextRenderable.Styled createGlyph(
                    float x, float y, int color, int shadowColor, Style style,
                    float boldOffset, float shadowOffset) {
                float visibleWidth = Float.isFinite(advance) && advance > 0.0F ? advance : 1.0F;
                float left = x;
                float top = y + topOffset;
                float right = left + visibleWidth;
                float bottom = top + Math.max(1.0F, height);
                return (net.minecraft.client.gui.font.TextRenderable.Styled)
                        java.lang.reflect.Proxy.newProxyInstance(
                                net.minecraft.client.gui.font.TextRenderable.Styled.class.getClassLoader(),
                                new Class<?>[]{net.minecraft.client.gui.font.TextRenderable.Styled.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                    case "style" -> style == null ? Style.EMPTY : style;
                                    case "left", "activeLeft" -> left;
                                    case "top", "activeTop" -> top;
                                    case "right", "activeRight" -> right;
                                    case "bottom", "activeBottom" -> bottom;
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    case "toString" -> "FixtureStyledGlyph";
                                    default -> null;
                                });
            }
        };
    }

    private static net.minecraft.client.gui.font.glyphs.BakedGlyph fixtureInvisibleGlyph(
            float advance) {
        return new net.minecraft.client.gui.font.glyphs.BakedGlyph() {
            private final com.mojang.blaze3d.font.GlyphInfo info =
                    com.mojang.blaze3d.font.GlyphInfo.simple(advance);

            @Override
            public com.mojang.blaze3d.font.GlyphInfo info() {
                return this.info;
            }

            @Override
            public net.minecraft.client.gui.font.TextRenderable.Styled createGlyph(
                    float x, float y, int color, int shadowColor, Style style,
                    float boldOffset, float shadowOffset) {
                return null;
            }
        };
    }

    private static Font dialogueFixtureFont() {
        return dialogueFixtureFont(null);
    }

    private static Font dialogueFixtureFont(FontDescription nonFiniteFont) {
        FontDescription cjk = new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT);
        FontDescription body0 = fixtureFontDescription("hud/dialogue/text/wynncraft/body_0");
        FontDescription body1 = fixtureFontDescription("hud/dialogue/text/wynncraft/body_1");
        FontDescription body2 = fixtureFontDescription("hud/dialogue/text/wynncraft/body_2");
        FontDescription body3 = fixtureFontDescription("hud/dialogue/text/wynncraft/body_3");
        FontDescription body4 = fixtureFontDescription("hud/dialogue/text/wynncraft/body_4");
        FontDescription merchant = fixtureFontDescription("hud/dialogue/text/merchant/body_0");
        FontDescription reset1 = fixtureFontDescription("hud/dialogue/text/layout/reset_1");
        FontDescription reset2 = fixtureFontDescription("hud/dialogue/text/layout/reset_2");
        net.minecraft.client.gui.GlyphSource cjkSource =
                fixtureGlyphSource(fixtureGlyph(8.0F, 0.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource body0Source =
                fixtureGlyphSource(fixtureGlyph(6.0F, 0.0F, 8.0F), Map.of(
                        0xE100, fixtureInvisibleGlyph(-20.0F),
                        0xE101, fixtureInvisibleGlyph(-20.0F)));
        net.minecraft.client.gui.GlyphSource body1Source =
                fixtureGlyphSource(fixtureGlyph(6.0F, 10.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource body2Source =
                fixtureGlyphSource(fixtureGlyph(6.0F, 20.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource body3Source =
                fixtureGlyphSource(fixtureGlyph(6.0F, 30.0F, 8.0F), Map.of(
                        0xE102, fixtureInvisibleGlyph(-6.0F)));
        net.minecraft.client.gui.GlyphSource body4Source =
                fixtureGlyphSource(fixtureGlyph(6.0F, 40.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource merchantSource =
                fixtureGlyphSource(fixtureGlyph(12.0F, 0.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource reset1Source =
                fixtureGlyphSource(fixtureInvisibleGlyph(-60.0F));
        net.minecraft.client.gui.GlyphSource reset2Source =
                fixtureGlyphSource(fixtureInvisibleGlyph(-60.0F));
        net.minecraft.client.gui.GlyphSource defaultSource =
                fixtureGlyphSource(fixtureGlyph(6.0F, 0.0F, 8.0F));
        net.minecraft.client.gui.GlyphSource nonFiniteSource =
                fixtureGlyphSource(fixtureGlyph(Float.NaN, 0.0F, 8.0F));
        return new Font(new Font.Provider() {
            @Override
            public net.minecraft.client.gui.GlyphSource glyphs(FontDescription font) {
                if (nonFiniteFont != null && nonFiniteFont.equals(font)) return nonFiniteSource;
                if (cjk.equals(font)) return cjkSource;
                if (body0.equals(font)) return body0Source;
                if (body1.equals(font)) return body1Source;
                if (body2.equals(font)) return body2Source;
                if (body3.equals(font)) return body3Source;
                if (body4.equals(font)) return body4Source;
                if (merchant.equals(font)) return merchantSource;
                if (reset1.equals(font)) return reset1Source;
                if (reset2.equals(font)) return reset2Source;
                return defaultSource;
            }

            @Override
            public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() {
                return null;
            }
        });
    }

    private static FontDescription fixtureFontDescription(String path) {
        return new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("minecraft", path));
    }
    @SuppressWarnings("unchecked")
    private static void checkHoverEventsFrozenForVisibleRequests() throws Exception {
        Component source = Component.literal("Open Menu").withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Component.literal("Hidden Skill Details"))));
        String requestJson = JsonPassthroughPipeline.serializeComponents(List.of(source));
        assertTrue(!requestJson.contains("hoverEvent"), "ordinary request strips hidden hover event");
        assertTrue(!requestJson.contains("Hidden Skill Details"), "ordinary request strips hidden hover text");

        Method reattach = JsonPassthroughPipeline.class.getDeclaredMethod(
                "reattachOriginalHoverEvents", List.class, List.class);
        reattach.setAccessible(true);
        List<Component> restored = (List<Component>) reattach.invoke(
                null, List.of(Component.literal("\u6253\u5f00\u83dc\u5355")), List.of(source));
        String restoredJson = ComponentJsonCompat.toJson(restored.get(0));
        assertTrue(restoredJson.contains("hoverEvent") || restoredJson.contains("hover_event"),
                "translated visible component regains hover event");
        assertTrue(restoredJson.contains("Hidden Skill Details"), "reattached hover text stays original");
    }

    private static void checkInvalidResponsesRejected() {
        List<Component> originals = List.of(Component.literal("One"), Component.literal("Two"));
        assertNull(JsonPassthroughPipeline.deserializeComponents("[{\"text\":\"Uno\"}]", originals),
                "wrong top-level count rejected");
        assertNull(JsonPassthroughPipeline.deserializeComponents("not-json", originals),
                "non-json rejected");
        assertNull(JsonPassthroughPipeline.deserializeComponents("[null,null]", originals),
                "non-component entries rejected");
        assertNull(JsonPassthroughPipeline.deserializeComponents("[{},{}]", originals),
                "contentless objects without extra children remain invalid");
    }

    private static void checkComponentVisualProjection() {
        String supplementary = Character.toString(0xCFFC4);
        String opaqueCluster = "\uE123" + supplementary + "\uD83D\uDDB1\uFE0F\u200D";
        String sourceJson = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Cast your \",\"color\":\"white\"},"
                + "{\"text\":\"Arrow Bomb Spell\",\"font\":\"minecraft:custom/tutorial\",\"color\":\"aqua\"},"
                + "{\"text\":\" by clicking \"},"
                + "{\"text\":\"" + opaqueCluster + "\",\"font\":\"minecraft:keybind\"},"
                + "{\"text\":\" LEFT - RIGHT\"},"
                + "{\"text\":\" at [12, 53, -1584] §a⟦K999⟧\"}]}]";
        ComponentVisualProjection projection = ComponentVisualProjection.project(sourceJson, "zh_cn");
        assertNotNull(projection, "arbitrary visual Component projection");
        assertTrue(projection.hasSlots(), "arbitrary visual projection retains natural-language slots");
        List<String> sourceSlots = projection.slots().stream()
                .map(ComponentVisualProjection.SemanticSlot::sourceText).toList();
        assertTrue(sourceSlots.contains("Cast your")
                        && sourceSlots.contains("Arrow Bomb Spell")
                        && sourceSlots.contains("by clicking")
                        && sourceSlots.contains("LEFT - RIGHT"),
                "normal and custom-font tutorial prose remains model-visible in reading order");

        ComponentVisualProjection shortWordProjection = ComponentVisualProjection.project(
                "[{\"text\":\"\",\"extra\":["
                        + "{\"text\":\"This item's power has been sealed,\"},"
                        + "{\"text\":\"an \",\"font\":\"minecraft:language/wynncraft\"},"
                        + "{\"text\":\"\\uE003\",\"font\":\"minecraft:merchant\"},"
                        + "{\"text\":\" Item Identifier can unlock its potential.\"}]}]",
                "zh_cn");
        assertNotNull(shortWordProjection, "custom-font article projection");
        assertTrue(shortWordProjection.slots().stream().anyMatch(slot -> "an".equals(slot.sourceText())),
                "two-letter custom-font grammar is translated instead of mistaken for an icon");
        assertEquals(List.of(shortWordProjection.slotCount()), shortWordProjection.atomicGroupSizes(),
                "all styled/icon-adjacent fragments from one Component form one recovery atom");
        String semanticJson = projection.semanticJson();
        assertTrue(!semanticJson.contains("\uE123") && !semanticJson.contains(supplementary)
                        && !semanticJson.contains("\uD83D\uDDB1") && !semanticJson.contains("\u200D")
                        && !semanticJson.contains("[12, 53, -1584]")
                        && !semanticJson.contains("§a") && !semanticJson.contains("⟦K999⟧"),
                "unknown PUA, supplementary, emoji/FORMAT, coordinates, colours and local markers stay local");

        JsonArray response = new JsonArray();
        for (String sourceSlot : sourceSlots) {
            JsonObject translated = new JsonObject();
            translated.addProperty("text", switch (sourceSlot) {
                case "Cast your" -> "施放你的";
                case "Arrow Bomb Spell" -> "箭矢轰炸法术";
                case "by clicking" -> "，点击";
                case "LEFT - RIGHT" -> "左键 - 右键";
                default -> "译文";
            });
            response.add(translated);
        }
        JsonArray rebuilt = projection.rebuildResponse(response);
        assertNotNull(rebuilt, "arbitrary visual response binds by semantic ordinal");
        String visible = ComponentJsonCompat.fromJson(rebuilt.get(0)).getString();
        assertTrue(visible.contains("施放你的") && visible.contains("箭矢轰炸法术")
                        && visible.contains("，点击") && visible.contains("左键 - 右键"),
                "every tutorial phrase is translated despite unseen visual atoms");
        assertTrue(visible.contains(opaqueCluster)
                        && visible.indexOf(opaqueCluster) == visible.lastIndexOf(opaqueCluster),
                "the complete unknown visual cluster is restored exactly once at its source position");
        assertTrue(visible.contains("[12, 53, -1584]")
                        && visible.contains("§a⟦K999⟧"),
                "dynamic coordinates, legacy formatting and local tokens are restored verbatim");
        String rebuiltJson = rebuilt.toString();
        assertTrue(rebuiltJson.contains("minecraft:custom/tutorial")
                        && rebuiltJson.contains("\"color\":\"aqua\""),
                "source custom-font/style ownership remains in the local skeleton");

        JsonArray blankFirst = response.deepCopy();
        blankFirst.set(0, JsonParser.parseString("{\"text\":\"\"}"));
        JsonArray blankRebuilt = projection.rebuildResponse(blankFirst);
        assertNotNull(blankRebuilt, "blank but parseable slot response remains accepted");
        assertTrue(ComponentJsonCompat.fromJson(blankRebuilt.get(0)).getString().contains("Cast your"),
                "one blank slot falls back to its source without erasing translated siblings");
        assertNull(projection.rebuildResponse(JsonParser.parseString("[]")),
                "top-level semantic count mismatch is rejected");

        ComponentVisualProjection interleaved = ComponentVisualProjection.project(
                "[{\"text\":\"F\\uE000a\\uE001ction\",\"font\":\"minecraft:future/icons\"}]",
                "zh_cn");
        assertNotNull(interleaved, "PUA-interleaved word projection");
        assertTrue(interleaved.slotCount() >= 2 && !interleaved.semanticJson().contains("\\uE000")
                        && !interleaved.semanticJson().contains("\\uE001"),
                "PUA-interleaved prose is split into semantic neighbours instead of skipped");
        JsonArray interleavedResponse = new JsonArray();
        for (int index = 0; index < interleaved.slotCount(); index++) {
            JsonObject translated = new JsonObject();
            translated.addProperty("text", "译" + index);
            interleavedResponse.add(translated);
        }
        String interleavedVisible = ComponentJsonCompat.fromJson(
                interleaved.rebuildResponse(interleavedResponse).get(0)).getString();
        assertTrue(interleavedVisible.indexOf('\uE000') > 0
                        && interleavedVisible.indexOf('\uE001') > interleavedVisible.indexOf('\uE000'),
                "interleaved opaque atoms never move to the end of translated text");

        ComponentVisualProjection naturalLabels = ComponentVisualProjection.project(
                "[{\"text\":\"NEW\"},{\"text\":\"Splinter's Workshop\"},"
                        + "{\"text\":\"Rotates in 3h and grants XP\"}]", "zh_cn");
        assertNotNull(naturalLabels, "ordinary uppercase and possessive labels project");
        List<String> naturalSlots = naturalLabels.slots().stream()
                .map(ComponentVisualProjection.SemanticSlot::sourceText).toList();
        assertTrue(naturalSlots.contains("NEW")
                        && naturalSlots.contains("Splinter's Workshop"),
                "NEW and possessive s remain complete model-visible phrases");
        assertTrue(!naturalLabels.semanticJson().contains("3h")
                        && naturalLabels.semanticJson().contains("XP"),
                "classified live time stays local while ordinary game abbreviations reach the model");

        Component islandRewardsSource = Component.literal(
                "Complete Quests and earn Island XP towards your Daily Meter and Weekly Vault to earn Reward Crates.")
                .withStyle(ChatFormatting.YELLOW);
        ComponentVisualProjection islandRewards = ComponentVisualProjection.projectComponents(
                List.of(islandRewardsSource), "zh_cn");
        assertNotNull(islandRewards, "Island Rewards language-visible projection");
        assertEquals(1, islandRewards.slotCount(),
                "Island XP remains inside one complete grammatical request slot");
        assertEquals(islandRewardsSource.getString(), islandRewards.slots().getFirst().sourceText(),
                "the model sees the exact complete Island XP sentence");
        List<Component> islandRewardsTranslated = islandRewards.rebuildComponentList(
                JsonParser.parseString("[\"完成任务可获得岛屿 XP，推进每日进度和每周宝库，从而赢取奖励箱。\"]"));
        assertNotNull(islandRewardsTranslated, "Island Rewards translated response rebuild");
        String islandRewardsVisible = islandRewardsTranslated.getFirst().getString();
        assertTrue(islandRewardsVisible.contains("岛屿 XP")
                        && islandRewardsVisible.indexOf("XP") == islandRewardsVisible.lastIndexOf("XP")
                        && !islandRewardsVisible.contains("经验值，XP"),
                "a complete response contains the XP meaning exactly once without client reinsertion");
        assertEquals(ChatFormatting.YELLOW.getColor(),
                islandRewardsTranslated.getFirst().getStyle().getColor().getValue(),
                "language-visible translation still binds into the original text style");

        Component styledIslandXp = Component.empty()
                .append(Component.literal("Complete Quests and earn Island ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("XP").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" towards your Daily Meter.").withStyle(ChatFormatting.YELLOW));
        ComponentVisualProjection styledIslandProjection = ComponentVisualProjection.projectComponents(
                List.of(styledIslandXp), "zh_cn");
        assertNotNull(styledIslandProjection, "styled Island XP projection");
        assertTrue(styledIslandProjection.slots().stream()
                        .anyMatch(slot -> "XP".equals(slot.sourceText())),
                "a pure acronym in its own styled Component is still visible to the model");
        JsonArray styledIslandResponse = new JsonArray();
        for (ComponentVisualProjection.SemanticSlot slot : styledIslandProjection.slots()) {
            styledIslandResponse.add(switch (slot.sourceText()) {
                case "Complete Quests and earn Island" -> "完成任务可获得岛屿";
                case "XP" -> "XP";
                case "towards your Daily Meter." -> "，推进每日进度。";
                default -> slot.sourceText();
            });
        }
        List<Component> styledIslandTranslated = styledIslandProjection.rebuildComponentList(
                styledIslandResponse);
        assertNotNull(styledIslandTranslated, "styled Island XP response rebuild");
        String styledIslandVisible = styledIslandTranslated.getFirst().getString();
        assertTrue(styledIslandVisible.indexOf("XP") >= 0
                        && styledIslandVisible.indexOf("XP") == styledIslandVisible.lastIndexOf("XP"),
                "style-fragmented XP remains present exactly once after rebuild");
        String styledIslandJson = ComponentJsonCompat.toJson(styledIslandTranslated.getFirst());
        assertTrue(styledIslandJson.contains("\"text\":\"XP\"")
                        && styledIslandJson.contains("\"color\":\"aqua\""),
                "the source XP colour survives language-visible translation");

        ComponentVisualProjection futureAcronyms = ComponentVisualProjection.project(
                "[{\"text\":\"Island XP affects maximum HP, MP regeneration, FPS and LVL progression.\"}]",
                "zh_cn");
        assertNotNull(futureAcronyms, "future acronym projection");
        assertEquals("Island XP affects maximum HP, MP regeneration, FPS and LVL progression.",
                futureAcronyms.slots().getFirst().sourceText(),
                "ordinary printable acronyms are semantic by default without an acronym allowlist");
    }

    private static void checkJsonTextNodeEditing() {
        String json = "[{\"text\":\"Root\",\"extra\":[{\"text\":\"Child\",\"color\":\"gold\"}],"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"Hover\"}}}]";
        List<String> nodes = ComponentJsonCacheEditor.textNodes(json);
        assertEquals(List.of("Root", "Child", "Hover"), nodes, "text-node traversal");

        List<String> edited = List.of("\u6839", "\u5b50\u8282\u70b9", "Hover\\\\Path\nSecond");
        String editorText = ComponentJsonCacheEditor.encodeEditorText(edited);
        assertEquals(edited, ComponentJsonCacheEditor.decodeEditorText(editorText, 3), "editor escaping round trip");
        String replaced = ComponentJsonCacheEditor.replaceTextNodes(json, edited);
        assertNotNull(replaced, "node replacement");
        assertEquals(edited, ComponentJsonCacheEditor.textNodes(replaced), "replacement keeps node order");
        assertNull(ComponentJsonCacheEditor.replaceTextNodes(json, List.of("too few")), "node-count mismatch rejected");
    }

    private static void checkSignPositionMapping() {
        List<SignJsonDocument.Entry> entries = List.of(
                new SignJsonDocument.Entry("a", "state-a", lines("Start", "Game", "Now", "!"),
                        new String[] {"Start", "Game", "Now", "!"}, 0L, true),
                new SignJsonDocument.Entry("b", "state-b", lines("Choose", "Class", "Here", ""),
                        new String[] {"Choose", "Class", "Here", ""}, 1L, true));
        SignJsonDocument.Document document = SignJsonDocument.fromEntries(
                entries, "sign.manual.group.by_id.direct", "sign-test", "two signs");
        assertEquals(8, document.components().size(), "two signs flatten to eight components");

        List<Component> translated = lines("\u5f00\u59cb", "\u6e38\u620f", "\u73b0\u5728", "\uff01",
                "\u9009\u62e9", "\u804c\u4e1a", "\u8fd9\u91cc", "");
        SignJsonDocument.RestoreResult result = document.restoreComponents(translated);
        assertTrue(result.success(), "sign mapping success");
        assertEquals("\u5f00\u59cb", result.componentsBySignId().get("a")[0].getString(), "first sign position");
        assertEquals("\u9009\u62e9", result.componentsBySignId().get("b")[0].getString(), "second sign position");
        assertTrue(!document.restoreComponents(translated.subList(0, 7)).success(), "short sign array rejected");

        SignJsonDocument.Document compact = SignJsonDocument.fromCompactEntries(
                entries, "sign.manual.group.by_id.direct", "sign-test", "one panel");
        assertEquals(2, compact.components().size(), "compact manual panel uses one component per sign");
        assertEquals("Start Game Now !", compact.components().get(0).getString(),
                "compact source preserves complete sign wording");
        SignJsonDocument.RestoreResult compactResult = compact.restoreComponents(
                lines("\u5f00\u59cb\u6e38\u620f\uff01", "\u5728\u6b64\u9009\u62e9\u804c\u4e1a"));
        assertTrue(compactResult.success(), "compact sign mapping success");
        assertEquals("\u5f00\u59cb\u6e38\u620f\uff01",
                compactResult.componentsBySignId().get("a")[0].getString(),
                "compact first sign restored");
        assertEquals("", compactResult.componentsBySignId().get("a")[1].getString(),
                "compact sign leaves remaining rows for render-time reflow");
        assertEquals("\u5728\u6b64\u9009\u62e9\u804c\u4e1a",
                compactResult.componentsBySignId().get("b")[0].getString(),
                "compact second sign restored");
    }

    private static void checkSignCacheAcceptance() throws Exception {
        Method stable = SignTranslationHelper.class.getDeclaredMethod(
                "isStablePersistentSignCache", String[].class, Component[].class);
        stable.setAccessible(true);

        assertTrue((Boolean) stable.invoke(null,
                        (Object) new String[] {"Start Game", "", "", ""},
                        (Object) signComponents("Start Game", "", "", "")),
                "valid component JSON is not rejected by semantic heuristics");
        assertTrue((Boolean) stable.invoke(null,
                        (Object) new String[] {"Start Game", "", "", ""},
                        (Object) signComponents("\u5f00\u59cb\u6e38\u620f", "", "", "")),
                "translated sign accepted");
        assertTrue((Boolean) stable.invoke(null,
                        (Object) new String[] {"Short sign", "", "", ""},
                        (Object) signComponents("\u8fd9\u662f\u4e00\u6bb5\u975e\u5e38\u975e\u5e38\u975e\u5e38\u957f\u7684\u544a\u793a\u724c\u8bd1\u6587\uff0c\u4f1a\u88ab\u56db\u884c\u663e\u793a\u5bb9\u91cf\u6324\u6389\u540e\u7eed\u5185\u5bb9", "", "", "")),
                "long translation remains cacheable for render-time layout");
        assertTrue(!(Boolean) stable.invoke(null,
                        (Object) new String[] {"Start Game", "", "", ""},
                        (Object) new Component[] {Component.literal("\u5f00\u59cb")} ),
                "component count mismatch rejected");
        assertTrue(!(Boolean) stable.invoke(null,
                        (Object) new String[] {"Start Game", "", "", ""},
                        (Object) new Component[] {Component.literal("\u5f00\u59cb"), null, Component.empty(), Component.empty()}),
                "null component rejected");
    }

    private static void checkCacheKeyMigrationShape() throws Exception {
        String sourceJson = "[{\"text\":\"Hello\"}]";
        String current = TranslationCacheKeys.componentJsonKey("chat.context.direct", sourceJson);
        String contextual = TranslationCacheKeys.componentJsonKey(
                "chat.context.direct", sourceJson, "Earlier line establishes the speaker.");
        String legacy = TranslationCacheKeys.legacyComponentJsonKey("chat.context.direct", sourceJson);
        assertTrue(current.startsWith("stx2:chat.context.direct:"), "current key keeps original surface lane");
        assertTrue(current.contains(":fmt=component_json_v1:"), "current key uses the approved Component JSON format");
        String v4 = TranslationCacheKeys.componentJsonV4Key("chat.context.direct", sourceJson, "", "", "");
        assertTrue(v4.contains(":fmt=component_json_v4:"), "v4 key remains derivable for lazy migration");
        assertTrue(!v4.equals(current), "inactive v4 and current keys must differ");
        String v3 = TranslationCacheKeys.componentJsonV3Key("chat.context.direct", sourceJson, "", "", "");
        assertTrue(v3.contains(":fmt=component_json_v3:"), "v3 key remains derivable for lazy migration");
        assertTrue(!v3.equals(current), "inactive v3 and current keys must differ");
        String v2 = TranslationCacheKeys.componentJsonV2Key("chat.context.direct", sourceJson, "", "", "");
        assertTrue(v2.contains(":fmt=component_json_v2:"), "v2 key remains derivable for lazy migration");
        assertTrue(!v2.equals(current), "inactive v2 and current keys must differ");
        String v1 = TranslationCacheKeys.componentJsonV1Key("chat.context.direct", sourceJson, "", "", "");
        assertTrue(v1.contains(":fmt=component_json_v1:"), "language-aware current v1 key remains derivable");
        assertTrue(!contextual.equals(current), "contextual JSON requests use a distinct cache key");
        assertTrue(!contextual.contains(":ctx=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855:"),
                "non-empty context hash is retained");
        assertTrue(legacy.startsWith("stx2:json.chat.context.direct:"), "legacy JSON key remains discoverable");
        assertTrue(!legacy.contains(":fmt="), "legacy key shape remains byte-compatible");

        Method policyKey = JsonPassthroughPipeline.class.getDeclaredMethod(
                "buildCacheKey", String.class, String.class, String.class, String.class,
                String.class, String.class);
        policyKey.setAccessible(true);
        String npcRoleKey = (String) policyKey.invoke(null, "entity.name.direct", sourceJson, "",
                "npc-service-name", "en_us", "zh_cn");
        String playerRoleKey = (String) policyKey.invoke(null, "entity.name.direct", sourceJson, "",
                "player-name", "en_us", "zh_cn");
        assertTrue(!npcRoleKey.equals(playerRoleKey), "surface role participates in the semantic cache key");
        assertTrue(!npcRoleKey.equals(TranslationCacheKeys.componentJsonKey(
                        "entity.name.direct", sourceJson, "", "en_us", "zh_cn")),
                "prompt-policy revision prevents reuse of pre-policy current-format cache entries");
        String baseSemanticContext = "translation_role=game-text"
                + "\ntranslation_prompt_policy="
                + TranslationPromptPolicy.cacheFingerprint("tooltip.visible.item.component.v2")
                + "\ntranslation_wire=component_visual_projection_v5";
        String unaffectedSource = "[\"Ordinary tooltip text\"]";
        String unaffectedCurrent = (String) policyKey.invoke(null,
                "tooltip.visible.item.component.v2", unaffectedSource, "",
                "game-text", "en_us", "zh_cn");
        String unaffectedPreVisibility = TranslationCacheKeys.componentJsonKey(
                "tooltip.visible.item.component.v2", unaffectedSource, baseSemanticContext,
                "en_us", "zh_cn");
        assertEquals(unaffectedPreVisibility, unaffectedCurrent,
                "language-visible projection keeps unaffected v6 cache keys reusable");
        String acronymSource = "[\"Complete Quests and earn Island XP towards your Daily Meter.\"]";
        String acronymCurrent = (String) policyKey.invoke(null,
                "tooltip.visible.item.component.v2", acronymSource, "",
                "game-text", "en_us", "zh_cn");
        String acronymPreVisibility = TranslationCacheKeys.componentJsonKey(
                "tooltip.visible.item.component.v2", acronymSource, baseSemanticContext,
                "en_us", "zh_cn");
        assertTrue(!acronymPreVisibility.equals(acronymCurrent),
                "only sources affected by the retired hidden-acronym projection receive a new cache key");
        assertTrue(!TranslationPromptPolicy.legacyCacheCompatible(),
                "semantic prompt revision keeps legacy v1-v5 entries inactive");

        Path cacheFile = Files.createTempDirectory("simpletranslate-prompt-fingerprint")
                .resolve("translations.json");
        TranslationCache cache = new TranslationCache(cacheFile);
        cache.load();
        String fingerprint = TranslationPromptPolicy.cacheFingerprint("entity.name.direct");
        cache.putComponentJson(npcRoleKey, "[{\"text\":\"守卫\"}]", sourceJson,
                "Guard", "守卫", fingerprint);
        TranslationCache.CacheViewEntry stored = cache.getEntries().values().iterator().next();
        assertEquals(fingerprint, stored.promptFingerprint(),
                "automatic cache records persist the exact prompt-policy fingerprint");
    }

    private static void checkPromptPolicyMutations() throws Exception {
        String surface = "entity.name.direct";
        String oldProfile = TranslationProfileManager.current();
        String baseCachePolicy = TranslationPromptPolicy.cacheFingerprint(surface);
        String baseRuntimePolicy = TranslationPromptPolicy.runtimeFingerprint();
        try {
            TranslationProfileManager.saveCurrent("奇幻 MMORPG，职业名称采用世界观内称谓");
            String changedCachePolicy = TranslationPromptPolicy.cacheFingerprint(surface);
            String changedRuntimePolicy = TranslationPromptPolicy.runtimeFingerprint();
            assertTrue(!baseCachePolicy.equals(changedCachePolicy)
                            && !baseRuntimePolicy.equals(changedRuntimePolicy),
                    "translation profile description changes both persistent and runtime policy identity");
            String jsonPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                    "en", "zh_cn", List.of(), surface, "");
            assertTrue(jsonPrompt.contains("奇幻 MMORPG"),
                    "the unified Component JSON prompt receives the active scope orders");
            assertTrue(jsonPrompt.split("PLAYER'S HIGHEST-PRIORITY ORDERS", -1).length == 3,
                    "the player's orders appear exactly twice (primacy and recency positions)");
            assertTrue(!jsonPrompt.contains("PLAYER'S LOCALIZATION ORDERS"),
                    "the superseded orders banner is retired");
            assertTrue(jsonPrompt.contains("Unless the player's orders or the mandatory terminology"),
                    "the default name-localization rule defers to player orders and mandatory terminology");
        } finally {
            TranslationProfileManager.saveCurrent(oldProfile);
        }

        Field dictionaryField = com.yourname.simpletranslate.SimpleTranslateClientBootstrap.class
                .getDeclaredField("termDictionary");
        dictionaryField.setAccessible(true);
        Object oldDictionary = dictionaryField.get(null);
        TermDictionary dictionary = new TermDictionary(Files.createTempDirectory(
                "simpletranslate-term-policy").resolve("terms.json"));
        dictionary.load();
        try {
            dictionaryField.set(null, dictionary);
            String beforeTermPolicy = TranslationPromptPolicy.cacheFingerprint(surface);
            String beforeTermRuntime = TranslationPromptPolicy.runtimeFingerprint();
            dictionary.addTerm("Item Identifier", "物品鉴定师");
            assertTrue(!beforeTermPolicy.equals(TranslationPromptPolicy.cacheFingerprint(surface))
                            && !beforeTermRuntime.equals(TranslationPromptPolicy.runtimeFingerprint()),
                    "effective term-dictionary changes invalidate exact cache and automatic line-memory generations");
            assertTrue(dictionary.matchTermsInText("Visit the Item Identifier").stream()
                            .anyMatch(term -> "物品鉴定师".equals(term.target())),
                    "the policy-changing dictionary entry is also an active model term hint");
            dictionary.addTerm("Detlas", "Detlas");
            String termPrompt = JsonPassthroughPrompts.buildSystemPrompt("en", "zh_cn",
                    dictionary.matchTermsInText("Visit the Item Identifier in Detlas"), surface, "");
            assertTrue(termPrompt.contains("MANDATORY TERMINOLOGY")
                            && termPrompt.contains("- \"Item Identifier\" -> \"物品鉴定师\""),
                    "term mappings are presented as mandatory override orders");
            assertTrue(termPrompt.contains("Keep these terms in their original form, unchanged")
                            && termPrompt.contains("- \"Detlas\""),
                    "identity term mappings become explicit keep-unchanged orders");
        } finally {
            dictionaryField.set(null, oldDictionary);
        }
    }

    private static void checkDynamicNumericTemplate() {
        DynamicTextTemplate template = DynamicTextTemplate.capture(
                Component.literal("Wave 12/20").withStyle(ChatFormatting.AQUA));
        assertTrue(template.hasValues(), "numeric template captures values");
        assertEquals("Wave \u27e61000\u27e7", template.normalizedText(), "numeric markers");
        Component restored = template.restore(Component.literal(
                "\u6ce2\u6b21 \u27e61000\u27e7").withStyle(ChatFormatting.RED));
        assertNotNull(restored, "numeric markers restore");
        assertEquals("\u6ce2\u6b21 12/20", restored.getString(), "dynamic values restored");
        assertNull(template.restore(Component.literal(
                "\u6ce2\u6b21 \u27e61000\u27e7/\u27e61000\u27e7")), "duplicate marker rejected");

        DynamicTextTemplate scoreboard = DynamicTextTemplate.capture(Component.literal(
                "§7 and equip it at [-1301, 53, -1596]"));
        assertEquals("§7 and equip it at [⟦1000⟧, ⟦1001⟧, ⟦1002⟧]",
                scoreboard.normalizedText(), "legacy colour code is not converted into a dynamic marker");
        assertTrue(!scoreboard.normalizedText().contains("§⟦"),
                "dynamic scoreboard templates never split a legacy colour pair");

        DynamicTextTemplate fused = DynamicTextTemplate.capture(Component.literal("§7123"));
        assertEquals("§7⟦1000⟧", fused.normalizedText(),
                "digits immediately following a legacy colour code remain dynamic");

        DynamicTextTemplate proseQuantity = DynamicTextTemplate.capture(Component.literal(
                "Compare the 10 factions"));
        assertTrue(!proseQuantity.hasValues(),
                "the shared dynamic template does not extract an ordinary prose quantity");
        assertEquals("Compare the 10 factions", proseQuantity.normalizedText(),
                "ordinary prose remains one grammatical template run");
    }

    private static void checkDynamicNumberUnitSuffix() {
        List<String> values = new ArrayList<>();
        String masked = ComponentJsonNumberNormalizer.normalizeNumbers(
                "Charges are restored every 10m", values);
        assertEquals("Charges are restored every ⟦N0⟧m", masked,
                "unit suffix stays outside the dynamic marker so the model sees the duration grammar");
        assertEquals(List.of("10"), values,
                "only the numeric part is captured for restore");
        assertEquals("Charges are restored every 10m",
                ComponentJsonNumberNormalizer.restoreNumbers(masked, values),
                "marker plus literal suffix restores the original token exactly");

        List<String> tickingValues = new ArrayList<>();
        assertEquals(masked, ComponentJsonNumberNormalizer.normalizeNumbers(
                "Charges are restored every 12m", tickingValues),
                "ticking values share one cache key while the unit stays visible");
        assertEquals(List.of("12"), tickingValues, "the updated value is captured for restore");

        List<String> percentValues = new ArrayList<>();
        assertEquals("⟦N0⟧%", ComponentJsonNumberNormalizer.normalizeNumbers("50%", percentValues),
                "percent suffix stays literal");
        assertEquals(List.of("50"), percentValues, "percent value captured without the sign");

        List<String> ordinalValues = new ArrayList<>();
        assertEquals("⟦N0⟧rd", ComponentJsonNumberNormalizer.normalizeNumbers("3rd", ordinalValues),
                "ordinal suffix stays literal");
        assertEquals(List.of("3"), ordinalValues, "ordinal digits captured without the suffix");

        List<String> pingValues = new ArrayList<>();
        assertEquals("Ping: ⟦N0⟧ms",
                ComponentJsonNumberNormalizer.normalizeNumbers("Ping: 5ms", pingValues),
                "two-letter ms suffix is preferred over single-letter units");
        assertEquals(List.of("5"), pingValues, "ping value captured without the unit");

        List<String> fractionValues = new ArrayList<>();
        assertEquals("Durability: ⟦N0⟧",
                ComponentJsonNumberNormalizer.normalizeNumbers("Durability: 69/80", fractionValues),
                "suffix-less fractions keep the legacy whole-token masking");
        assertEquals(List.of("69/80"), fractionValues, "fraction value captured whole");

        List<String> coordinateValues = new ArrayList<>();
        String coordinates = ComponentJsonNumberNormalizer.normalizeNumbers(
                "at [12, 53, -1584]", coordinateValues);
        assertTrue(!coordinates.contains("12") && coordinates.contains("⟦N"),
                "coordinate masking is unaffected by the suffix split");
        assertEquals("at [12, 53, -1584]",
                ComponentJsonNumberNormalizer.restoreNumbers(coordinates, coordinateValues),
                "coordinates restore verbatim");

        assertEquals("Time left: <number>m",
                ComponentJsonNumberNormalizer.maskPromptDynamicNumbers("Time left: 10m"),
                "prompt masking keeps the same literal suffix shape");
    }

    private static void checkScoreboardSemanticFrame() throws Exception {
        List<Component> frame = List.of(
                Component.literal("§6§lplay.wynncraft.com"),
                Component.literal("À"),
                Component.empty(),
                Component.literal("§7§e§lTracked Quest:"),
                Component.empty(),
                Component.literal("§fKing's Recruit"),
                Component.empty(),
                Component.literal("§7Talk to the Guard at §f[-1298, 53,"),
                Component.empty(),
                Component.literal("§f-1584]§7"),
                Component.empty());

        Class<?> projectionClass = Class.forName(
                ScoreboardTranslationHelper.class.getName() + "$FrameProjection");
        Method project = projectionClass.getDeclaredMethod("project", List.class);
        project.setAccessible(true);
        Object projection = project.invoke(null, frame);
        assertNotNull(projection, "scoreboard frame projection");

        Method requestsMethod = projectionClass.getDeclaredMethod("requests");
        requestsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Component> requests = (List<Component>) requestsMethod.invoke(projection);
        assertEquals(3, requests.size(),
                "each translatable scoreboard row is one top-level Component slot");
        assertEquals(List.of("Tracked Quest:", "King's Recruit",
                        "Talk to the Guard at [-1298, 53,"),
                requests.stream().map(Component::getString)
                        .map(value -> value.replaceAll("§[0-9A-FK-ORa-fk-or]", ""))
                        .toList(),
                "scoreboard row ordinals retain the complete readable frame order");
        assertTrue(requests.stream().noneMatch(component ->
                        component.getString().contains("play.wynncraft.com")
                                || component.getString().contains("À")),
                "server address and resource-pack glyph stay local");

        Method contextMethod = projectionClass.getDeclaredMethod("context");
        contextMethod.setAccessible(true);
        String frameContext = (String) contextMethod.invoke(projection);
        assertTrue(frameContext.contains("Talk to the Guard at [-1298, 53,")
                        && frameContext.contains("-1584]"),
                "local-only continuation rows remain available as whole-frame prompt context");

        Method bind = projectionClass.getDeclaredMethod("bind", List.class);
        bind.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Component> mapped = (List<Component>) bind.invoke(projection, List.of(
                Component.literal("§7§e§l追踪任务："),
                Component.literal("§f国王的新兵"),
                Component.literal("§7在[-1298, 53,处与守卫交谈")));
        assertNotNull(mapped, "scoreboard exact-row translation binding");
        assertEquals(frame.size(), mapped.size(), "scoreboard binding preserves physical slot count");
        assertEquals(frame.get(0).getString(), mapped.get(0).getString(),
                "scoreboard server address remains byte-for-byte local");
        assertEquals(frame.get(1).getString(), mapped.get(1).getString(),
                "scoreboard resource glyph remains byte-for-byte local");
        assertEquals("§7§e§l追踪任务：", mapped.get(3).getString(),
                "scoreboard header returns to its original render index and formatting prefix");
        assertEquals("§f国王的新兵", mapped.get(5).getString(),
                "quest title uses its semantic context rather than the old action translation");
        assertEquals("§7在[-1298, 53,处与守卫交谈", mapped.get(7).getString(),
                "translated instruction returns to its exact physical row");
        assertEquals(frame.get(9).getString(), mapped.get(9).getString(),
                "local coordinate continuation stays byte-for-byte at its original row");
        assertNull(bind.invoke(projection, List.of(Component.literal("only one row"))),
                "scoreboard row-count mismatch is rejected without prose-position guessing");

        Method sameFrameKeys = ScoreboardTranslationHelper.class.getDeclaredMethod(
                "sameOrderedFrameKeys", List.class, List.class);
        sameFrameKeys.setAccessible(true);
        assertTrue((boolean) sameFrameKeys.invoke(null,
                        List.of("a", "b"), List.of("a", "b")),
                "identical ordered scoreboard keys reuse the previous frame");
        assertTrue(!(boolean) sameFrameKeys.invoke(null,
                        List.of("b", "a"), List.of("a", "b")),
                "scoreboard row reordering invalidates frame reuse");
        assertTrue(!(boolean) sameFrameKeys.invoke(null,
                        List.of("a"), List.of("a", "b")),
                "scoreboard row-count changes invalidate frame reuse");

        Component styledRow = Component.empty()
                .append(Component.literal("- ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Finish Quests: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("1/2").withStyle(ChatFormatting.WHITE));
        Object styledProjection = project.invoke(null, List.of(styledRow));
        assertNotNull(styledProjection, "styled scoreboard frame projection");
        @SuppressWarnings("unchecked")
        List<Component> styledRequests = (List<Component>) requestsMethod.invoke(styledProjection);
        assertEquals(1, styledRequests.size(), "styled scoreboard row remains one physical request");
        assertEquals(ComponentJsonCompat.toJson(styledRow), ComponentJsonCompat.toJson(styledRequests.get(0)),
                "scoreboard frame keeps the exact server Component tree before semantic projection");

        ComponentVisualProjection visual = ComponentVisualProjection.project(
                JsonPassthroughPipeline.serializeComponents(styledRequests), "zh_cn");
        assertNotNull(visual, "styled scoreboard visual projection");
        assertEquals(1, visual.slotCount(), "only scoreboard prose enters the model payload");
        JsonArray translatedSlot = new JsonArray();
        translatedSlot.add(JsonParser.parseString("{\"text\":\"完成任务：\"}"));
        JsonArray rebuiltStyled = visual.rebuildResponse(translatedSlot);
        assertNotNull(rebuiltStyled, "styled scoreboard response rebound");
        List<Component> finalizedStyled = JsonPassthroughPipeline.deserializeComponents(
                rebuiltStyled.toString(), styledRequests);
        assertNotNull(finalizedStyled, "styled scoreboard live values finalized");
        Component restoredStyled = finalizedStyled.getFirst();
        assertEquals("- 完成任务： 1/2", restoredStyled.getString(),
                "scoreboard opaque prefix and score remain in their exact positions");
        String restoredJson = ComponentJsonCompat.toJson(restoredStyled);
        assertTrue(restoredJson.contains("\"color\":\"green\"")
                        && restoredJson.contains("\"color\":\"gray\"")
                        && restoredJson.contains("\"color\":\"white\""),
                "scoreboard bullet, translated label and score retain their original colors");
    }

    private static void checkHudPlaceholderPreservation() {
        Component source = Component.literal("Left \u27e61000\u27e7 Click");
        String serialized = JsonPassthroughPipeline.serializeComponents(List.of(source));
        assertTrue(!serialized.contains("\u27e6\u27e6"), "HUD layout placeholders must not double-wrap");
        assertTrue(serialized.contains("\u27e61000\u27e7"), "HUD layout placeholder digits preserved");

        ComponentVisualProjection projection = ComponentVisualProjection.project(serialized, "zh_cn");
        assertNotNull(projection, "HUD placeholder visual projection");
        assertEquals(2, projection.slotCount(),
                "natural-language runs on both sides of a local placeholder become semantic slots");
        assertEquals(List.of("Left", "Click"),
                projection.slots().stream().map(ComponentVisualProjection.SemanticSlot::sourceText).toList(),
                "semantic slots contain only readable text");
        assertTrue(!projection.semanticJson().contains("\u27e61000\u27e7")
                        && !projection.semanticJson().contains("\u27e6\u27e6"),
                "local layout placeholders never enter the model payload");

        JsonArray response = new JsonArray();
        response.add(JsonParser.parseString("{\"text\":\"左侧\"}"));
        response.add(JsonParser.parseString("{\"text\":\"点击\"}"));
        JsonArray rebuilt = projection.rebuildResponse(response);
        assertNotNull(rebuilt, "HUD placeholder response rebound by top-level ordinal");
        Component restored = ComponentJsonCompat.fromJson(rebuilt.get(0));
        assertNotNull(restored, "rebuilt HUD component parses");
        assertEquals("左侧 \u27e61000\u27e7 点击", restored.getString(),
                "local placeholder and surrounding spaces are restored byte-for-byte");
        assertNull(projection.rebuildResponse(JsonParser.parseString("[{\"text\":\"仅一个槽\"}]")),
                "HUD semantic slot count mismatch is rejected");
    }

    private static void checkWynncraftHudFontSplit() {
        List<Component> originals = List.of(Component.literal("hud"));
        // Ordinary (non-layout) custom fonts may still remount CJK onto default.
        String ordinaryCustom = "[{\"text\":\"\u6a61\u6728\u6cd5\u6756\",\"font\":\"minecraft:language/wynncraft\"}]";
        List<Component> ordinaryRestored = JsonPassthroughPipeline.deserializeComponents(ordinaryCustom, originals);
        assertNotNull(ordinaryRestored, "ordinary custom-font CJK response accepted");
        String ordinaryJson = ComponentJsonCompat.toJson(ordinaryRestored.get(0));
        assertTrue(ordinaryJson.contains("minecraft:default"),
                "ordinary resource-pack custom font still remounts CJK onto default");

        // Layout-critical HUD fonts must NEVER remount visible CJK onto default.
        String mixedBottom = "[{\"text\":\"\u70b9\u51fb\u9009\u62e9          \uE002 \u5411\u4e0a\u6eda\u52a8\","
                + "\"font\":\"minecraft:hud/selector/default/bottom_middle\"}]";
        List<Component> mixedRestored = JsonPassthroughPipeline.deserializeComponents(mixedBottom, originals);
        assertNotNull(mixedRestored, "mixed CJK/PUA Wynncraft HUD response accepted");
        String mixedJson = ComponentJsonCompat.toJson(mixedRestored.get(0));
        assertTrue(!mixedJson.contains("minecraft:default"),
                "negative: layout HUD must not remount CJK onto default");
        assertTrue(mixedJson.contains("hud/selector/default/bottom_middle"),
                "layout HUD keeps Wynncraft positioning font for CJK and PUA");
        assertTrue(mixedJson.contains("\u70b9\u51fb\u9009\u62e9          "),
                "alignment spaces preserved in CJK slot");

        String pureCjk = "[{\"text\":\"\\u0001\u7528\u5206\u8eab\u8ff7\u60d1\u654c\u4eba\","
                + "\"font\":\"minecraft:hud/selector/default/center_left/1/description\"}]";
        List<Component> pureRestored = JsonPassthroughPipeline.deserializeComponents(pureCjk, originals);
        assertNotNull(pureRestored, "pure CJK on layout font accepted");
        String pureJson = ComponentJsonCompat.toJson(pureRestored.get(0));
        assertTrue(!pureJson.contains("minecraft:default"),
                "negative: pure CJK on layout font must stay on positioning font");
        assertTrue(pureJson.contains("hud/selector/default/center_left/1/description"),
                "description region font preserved");
        assertTrue(pureJson.contains("\u7528\u5206\u8eab\u8ff7\u60d1\u654c\u4eba"),
                "translated CJK text remains in tree");

        // Non-hud custom font that still embeds PUA positioning glyphs must not
        // remount its CJK sibling onto default either.
        String puaHeavy = "[{\"text\":\"\uE000 \u5de6\u952e\u70b9\u51fb\",\"font\":\"minecraft:wynn/actionbar\"}]";
        List<Component> puaRestored = JsonPassthroughPipeline.deserializeComponents(puaHeavy, originals);
        assertNotNull(puaRestored, "PUA-heavy custom font response accepted");
        String puaJson = ComponentJsonCompat.toJson(puaRestored.get(0));
        assertTrue(!puaJson.contains("minecraft:default"),
                "negative: PUA-heavy custom font must not remount CJK onto default");
        assertTrue(puaJson.contains("minecraft:wynn/actionbar"),
                "PUA-heavy custom font id preserved");
        assertTrue(puaJson.contains("\uE000") || puaJson.contains("\\uE000"),
                "positive: PUA positioning glyph kept on custom font");

        String singleKnownLayoutPua = "[{\"text\":\"\uE000 Click to select\","
                + "\"font\":\"minecraft:hud/selector/default/bottom_middle\"}]";
        assertTrue(JsonPassthroughPipeline.isLayoutCriticalHudTree(
                        JsonParser.parseString(singleKnownLayoutPua)),
                "one known selector font plus one PUA anchor is layout-critical");

        String singleGenericPua = "[{\"text\":\"\uE000 Click to select\","
                + "\"font\":\"minecraft:wynn/actionbar\"}]";
        assertTrue(!JsonPassthroughPipeline.isLayoutCriticalHudTree(
                        JsonParser.parseString(singleGenericPua)),
                "one generic custom font plus one PUA keeps the multi-font fallback threshold");
    }

    private static void checkWynncraftLayoutCriticalHud() {
        // Multi-region actionbar: center_left + bottom_middle + top_middle.
        // All PUA positioning nodes and their custom fonts must survive; CJK
        // must never be remounted onto minecraft:default.
        String sourceJson = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\uDB40\uDC24\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\\u0001\u00a7eBattle Monk\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\uDB3F\uDFA7\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\uDB3F\uDF2E\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uE000 Left\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61000\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"Click to select          \uE002 Scroll up\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61001\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"down to browse          \uE001 Right\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61002\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"Click to return\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uDB3F\uDF2D\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uDB40\uDC24\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\\u0001Swift combos\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\uDB3F\uDFA1\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\uDB3F\uDFD3\",\"font\":\"minecraft:hud/selector/default/top_middle\"},"
                + "{\"text\":\"\\u0001C\uE001r\uE001e\",\"font\":\"minecraft:hud/selector/default/top_middle\"},"
                + "{\"text\":\"\uDB3F\uDFD3\",\"font\":\"minecraft:hud/selector/default/top_middle\"}"
                + "]}]";
        assertTrue(JsonPassthroughPipeline.isLayoutCriticalHudTree(JsonParser.parseString(sourceJson)),
                "multi-region Wynncraft actionbar detected as layout-critical");
        assertTrue(!JsonPassthroughPipeline.shouldKeepLayoutCriticalHudOriginal(sourceJson, "zh_cn"),
                "layout-critical HUD translates in place by default (keep-original is opt-in)");

        // Simulate a translated tree that keeps every positioning font (both wire
        // modes rebuild into this skeleton shape). Sanitize must not remount CJK.
        String translatedJson = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\uDB40\uDC24\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\\u0001\u00a7e\u6218\u6597\u50e7\u4fa3\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\uDB3F\uDFA7\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                + "{\"text\":\"\uDB3F\uDF2E\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uE000 \u5de6\u952e\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61000\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u70b9\u51fb\u9009\u62e9          \uE002 \u5411\u4e0a\u6eda\u52a8\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61001\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u5411\u4e0b\u6d4f\u89c8          \uE001 \u53f3\u952e\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u27e61002\u27e7\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\u70b9\u51fb\u8fd4\u56de\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uDB3F\uDF2D\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\"\uDB40\uDC24\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\\u0001\u8fc5\u6377\u8fde\u51fb\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\uDB3F\uDFA1\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\uDB3F\uDFD3\",\"font\":\"minecraft:hud/selector/default/top_middle\"},"
                + "{\"text\":\"\\u0001C\uE001r\uE001e\",\"font\":\"minecraft:hud/selector/default/top_middle\"},"
                + "{\"text\":\"\uDB3F\uDFD3\",\"font\":\"minecraft:hud/selector/default/top_middle\"}"
                + "]}]";
        ComponentVisualProjection layoutProjection = ComponentVisualProjection.project(
                sourceJson, "zh_cn");
        assertNotNull(layoutProjection, "layout-critical tree has a current Component projection");
        List<String> alignedLayoutText = layoutProjection.alignedTranslatedSlotTexts(
                JsonParser.parseString(translatedJson));
        assertNotNull(alignedLayoutText,
                "translated layout tree aligns with the current source-owned visual skeleton");
        JsonArray layoutResponse = new JsonArray();
        alignedLayoutText.forEach(layoutResponse::add);
        JsonArray merged = layoutProjection.rebuildResponse(layoutResponse);
        assertNotNull(merged, "Component projection skeleton merge keeps leaf alignment");
        List<Component> originals = List.of(Component.literal("hud"));
        List<Component> restored = JsonPassthroughPipeline.deserializeComponents(merged.toString(), originals);
        assertNotNull(restored, "layout-critical translated tree accepted");
        String restoredJson = ComponentJsonCompat.toJson(restored.get(0));

        assertTrue(restoredJson.contains("minecraft:hud/selector/default/center_left/1/display_name"),
                "center_left display_name positioning font preserved");
        assertTrue(restoredJson.contains("minecraft:hud/selector/default/bottom_middle"),
                "bottom_middle positioning font preserved");
        assertTrue(restoredJson.contains("minecraft:hud/selector/default/top_middle"),
                "top_middle positioning font preserved");
        assertTrue(restoredJson.contains("minecraft:hud/selector/default/center_left/1/description"),
                "center_left description positioning font preserved");
        assertTrue(restoredJson.contains("\uDB40\uDC24") || restoredJson.contains("\\uDB40\\uDC24")
                || restoredJson.contains("\udb40\udc24"),
                "PUA positioning glyph preserved");
        assertTrue(restoredJson.contains("\u6218\u6597\u50e7\u4fa3"), "translated class name retained");
        assertTrue(restoredJson.contains("\u5de6\u952e"), "translated click hint retained");
        assertTrue(!restoredJson.contains("\"font\":\"minecraft:default\""),
                "negative: no visible text remounted onto minecraft:default");

        // Negative: the old v3 collapse pattern must be recognized as layout-broken.
        String collapsed = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\uE000\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                + "{\"text\":\" \u5de6\u952e\",\"font\":\"minecraft:default\"},"
                + "{\"text\":\"\uDB40\uDC24\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                + "{\"text\":\"\u8fc5\u6377\u8fde\u51fb\",\"font\":\"minecraft:default\"}"
                + "]}]";
        assertTrue(JsonPassthroughPipeline.isLayoutBrokenCustomFontTranslation(collapsed, sourceJson),
                "negative fixture: CJK-on-default beside layout fonts is layout-broken");
        assertTrue(!JsonPassthroughPipeline.isLayoutBrokenCustomFontTranslation(translatedJson, sourceJson),
                "CJK kept on layout fonts is not layout-broken");

        // In-place layout contract: same skeleton, fonts and styles verbatim,
        // only text content changed.
        assertTrue(JsonPassthroughPipeline.satisfiesInPlaceLayoutContract(translatedJson, sourceJson),
                "positive: in-place translated tree satisfies the layout contract");
        assertTrue(JsonPassthroughPipeline.satisfiesInPlaceLayoutContract(sourceJson, sourceJson),
                "positive: identity translation satisfies the layout contract");
        assertTrue(!JsonPassthroughPipeline.satisfiesInPlaceLayoutContract(collapsed, sourceJson),
                "negative: node-count/default-font collapse violates the layout contract");

        // Negative: wrapper node injected around a translated leaf (the old
        // splitMixedCustomFontText shape) must violate the contract.
        String wrapped = translatedJson.replace(
                "{\"text\":\"\uE000 \u5de6\u952e\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}",
                "{\"text\":\"\",\"font\":\"minecraft:hud/selector/default/bottom_middle\",\"extra\":["
                        + "{\"text\":\"\uE000 \",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                        + "{\"text\":\"\u5de6\u952e\",\"font\":\"minecraft:default\"}]}");
        assertTrue(!wrapped.equals(translatedJson), "wrapper fixture mutates the tree");
        assertTrue(!JsonPassthroughPipeline.satisfiesInPlaceLayoutContract(wrapped, sourceJson),
                "negative: wrapper/split siblings violate the layout contract");

        // Negative: font swapped on one node while structure is otherwise intact.
        String fontSwapped = translatedJson.replace(
                "{\"text\":\"\u70b9\u51fb\u8fd4\u56de\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}",
                "{\"text\":\"\u70b9\u51fb\u8fd4\u56de\",\"font\":\"minecraft:default\"}");
        assertTrue(!fontSwapped.equals(translatedJson), "font-swap fixture mutates the tree");
        assertTrue(!JsonPassthroughPipeline.satisfiesInPlaceLayoutContract(fontSwapped, sourceJson),
                "negative: remounting a node onto minecraft:default violates the layout contract");
    }

    private static void checkWynnStructuralOwnership() {
        String selectorFont = "minecraft:hud/selector/default/bottom_middle";
        Component recognized = Component.empty()
                .append(Component.literal("").withStyle(styleWithFont(selectorFont)))
                .append(Component.literal("Left-Click to Play").withStyle(styleWithFont(selectorFont)))
                .append(Component.literal("").withStyle(styleWithFont(selectorFont)))
                .append(Component.literal("Right-Click to Switch").withStyle(styleWithFont(selectorFont)));
        assertTrue(WynncraftProfile.matchesActionbar(recognized),
                "exact Wynn selector structure is owned without live server context");
        Component missingAnchor = Component.literal("Left-Click to Play Right-Click to Switch")
                .withStyle(styleWithFont(selectorFont));
        assertTrue(!WynncraftProfile.matchesActionbar(missingAnchor),
                "selector prose without private-use anchors is not claimed");
        Component unrelatedHudFont = Component.empty()
                .append(Component.literal("").withStyle(styleWithFont("minecraft:hud/custom")))
                .append(Component.literal("Left-Click to Play").withStyle(styleWithFont("minecraft:hud/custom")))
                .append(Component.literal("").withStyle(styleWithFont("minecraft:hud/custom")))
                .append(Component.literal("Right-Click to Switch").withStyle(styleWithFont("minecraft:hud/custom")));
        assertTrue(!WynncraftProfile.matchesActionbar(unrelatedHudFont),
                "generic minecraft:hud fonts cannot activate Wynn selector ownership");
        Component unrelatedPua = Component.literal(" Ordinary HUD text")
                .withStyle(styleWithFont(selectorFont));
        assertTrue(!WynncraftProfile.matchesActionbar(unrelatedPua),
                "unrelated PUA layout text is not claimed without verified grammar");
    }

    private static Style styleWithFont(String font) {
        int separator = font.indexOf(':');
        Identifier id = Identifier.fromNamespaceAndPath(
                separator > 0 ? font.substring(0, separator) : "minecraft",
                separator > 0 ? font.substring(separator + 1) : font);
        return Style.EMPTY.withFont(new FontDescription.Resource(id));
    }


    private static void checkWynncraftActionbarLayoutRenderer() {
        String font = "minecraft:hud/selector/default/bottom_middle";
        String sourceJson = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\uE000\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"Click to select\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"\uE002\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"Scroll up\",\"font\":\"" + font + "\"}]}]";
        JsonArray sourceTree = JsonParser.parseString(sourceJson).getAsJsonArray();
        Component original = ComponentJsonCompat.fromJson(sourceTree.get(0));
        HudTextSupport.ActionbarTemplate template = HudTextSupport.actionbarTemplate(original);
        assertEquals(4, template.leaves().size(), "actionbar template keeps every source leaf in order");
        ComponentVisualProjection projection = ComponentVisualProjection.projectComponents(
                List.of(template.component()), "zh_cn");
        assertNotNull(projection, "single-font PUA actionbar projection");
        assertEquals(2, projection.slotCount(), "single-font PUA actionbar has two semantic slots");
        assertEquals(List.of("Click to select", "Scroll up"),
                projection.slots().stream().map(ComponentVisualProjection.SemanticSlot::sourceText).toList(),
                "only readable actionbar text enters the model payload");
        assertTrue(!projection.semanticJson().contains("\u27e61000\u27e7")
                        && !projection.semanticJson().contains("\u27e61001\u27e7")
                        && !projection.semanticJson().contains(font),
                "actionbar variable markers and positioning fonts remain client-owned");

        JsonArray response = new JsonArray();
        response.add(JsonParser.parseString("{\"text\":\"\u70b9\u51fb\u9009\u62e9\"}"));
        response.add(JsonParser.parseString("{\"text\":\"\u5411\u4e0a\u6eda\u52a8\"}"));
        JsonArray jsonRebuilt = projection.rebuildResponse(response);
        assertNotNull(jsonRebuilt, "semantic actionbar reconstruction succeeds");
        String rebuiltJson = jsonRebuilt.toString();
        assertTrue(rebuiltJson.contains(font), "semantic projection retains the positioning font");
        assertTrue(rebuiltJson.contains("\u27e61000\u27e7")
                        && rebuiltJson.contains("\u27e61001\u27e7"),
                "semantic projection restores local variable markers into the template");
        assertTrue(rebuiltJson.contains("\u70b9\u51fb\u9009\u62e9") && rebuiltJson.contains("\u5411\u4e0a\u6eda\u52a8"),
                "semantic projection retains translated visible text");

        Component jsonTranslated = ComponentJsonCompat.fromJson(jsonRebuilt.get(0));
        Component restoredActionbar = HudTextSupport.restoreActionbarVariables(jsonTranslated, template);
        assertNotNull(restoredActionbar, "actionbar anchors restore after accepting the translated template");
        String restoredJson = ComponentJsonCompat.toJson(restoredActionbar);
        assertTrue((restoredJson.contains("\uE000") || restoredJson.contains("\\uE000"))
                        && (restoredJson.contains("\uE002") || restoredJson.contains("\\uE002")),
                "current PUA anchors are restored locally after translation");

        ActionbarLayoutRenderer.Plan jsonPlan =
                ActionbarLayoutRenderer.compile(original, template, jsonTranslated);
        assertNotNull(jsonPlan, "component-json actionbar plan is safe");
        assertWynncraftActionbarLayout(jsonPlan.layout(SimpleTranslateJsonFixtureChecks::wynnActionbarFixtureWidth),
                "component-json");
        assertNull(jsonPlan.layout(component -> "\u70b9\u51fb\u9009\u62e9".equals(component.getString())
                        ? 16.01F : wynnActionbarFixtureWidth(component)),
                "fixed-anchor actionbar rejects compression below the readable 0.75 scale floor");

        // The renderer must never accept the same anchors in a different order:
        // its caller must fall back to the original stream in this case.
        String reorderedAnchorJson = "[{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\uE002\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"\u70b9\u51fb\u9009\u62e9\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"\uE000\",\"font\":\"" + font + "\"},"
                + "{\"text\":\"\u5411\u4e0a\u6eda\u52a8\",\"font\":\"" + font + "\"}]}]";
        Component reordered = ComponentJsonCompat.fromJson(
                JsonParser.parseString(reorderedAnchorJson).getAsJsonArray().get(0));
        assertNull(ActionbarLayoutRenderer.compile(original, template, reordered),
                "reordered PUA anchors reject the translation and preserve the original actionbar");

        // A changed counter must share the translated template, then restore the
        // current value locally instead of issuing another request for the same wording.
        HudTextSupport.ActionbarTemplate wave12 = HudTextSupport.actionbarTemplate(Component.literal("Wave 12/20"));
        HudTextSupport.ActionbarTemplate wave13 = HudTextSupport.actionbarTemplate(Component.literal("Wave 13/20"));
        assertEquals(ComponentJsonCompat.toJson(wave12.component()), ComponentJsonCompat.toJson(wave13.component()),
                "dynamic actionbar values share one translation template");
        Component translatedWaveTemplate = Component.literal("\u6ce2\u6b21 \u27e61000\u27e7");
        Component restored12 = HudTextSupport.restoreActionbarVariables(translatedWaveTemplate, wave12);
        Component restored13 = HudTextSupport.restoreActionbarVariables(translatedWaveTemplate, wave13);
        assertNotNull(restored12, "first dynamic actionbar value restores");
        assertNotNull(restored13, "current dynamic actionbar value restores");
        assertEquals("\u6ce2\u6b21 12/20", restored12.getString(), "first dynamic value retained");
        assertEquals("\u6ce2\u6b21 13/20", restored13.getString(), "current dynamic value retained");
    }

    private static void checkGuiLayoutMaterializedWrappers() {
        String sourceJson = "{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE000\",\"font\":\"minecraft:padding\"},"
                + "{\"text\":\"Island News\",\"font\":\"mcc:hud_offset_8\"}]}]}";
        String translatedJson = "{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE000\",\"font\":\"minecraft:padding\"},"
                + "{\"text\":\"岛屿新闻\",\"font\":\"mcc:hud_offset_8\"}]}";
        Component source = ComponentJsonCompat.fromJson(JsonParser.parseString(sourceJson));
        Component translated = ComponentJsonCompat.fromJson(JsonParser.parseString(translatedJson));
        assertTrue(GuiLayoutProgramRenderer.hasCompatibleVisualRuns(source, translated),
                "GUI layout programs ignore structure-only empty wrappers after materialization");
        assertTrue(GuiLayoutProgramRenderer.acceptsMeasuredAdvances(-48.0F, -48.0F, true),
                "protected MCC padding glyphs retain legal negative advances");
        assertTrue(!GuiLayoutProgramRenderer.acceptsMeasuredAdvances(12.0F, -1.0F, false),
                "ordinary translated prose still rejects invalid negative advances");
    }

    /**
     * Real Wynn fixtures use the dedicated semantic projection instead of the
     * generic layout-tree rewriter.  This validates both model request modes
     * without opening a client: semantic components are the COMPONENT_JSON
     * array, and the unified Component projection sees the exact same entries.
     */
    private static void checkWynnDirectSurfaces() {
        checkWynnSelectorGlyphProjection();
    }

    private static void checkWynnSelectorGlyphProjection() {
        Component selector = componentFromJson(
                "{\"text\":\"\",\"extra\":["
                        + "{\"text\":\"\uE000 Left-Click to select          \uE002 Scroll up/down to browse          \uE001 Right-Click to return\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                        + "{\"text\":\"v2.2.1_4\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                        + "{\"text\":\"\u0001§eBattle Monk\",\"font\":\"minecraft:hud/selector/default/center_left/1/display_name\"},"
                        + "{\"text\":\"Swift combos\",\"font\":\"minecraft:hud/selector/default/center_left/1/description\"},"
                        + "{\"text\":\"AS8\",\"font\":\"minecraft:hud/selector/default/top_right\"},"
                        + "{\"text\":\"C\uE001r\uE001e\",\"font\":\"minecraft:hud/selector/default/top_middle\"}]}" );
        assertTrue(WynncraftProfile.matchesActionbar(selector),
                "E000/E002/E001 select-browse-return selector grammar is recognized");
        List<WynnVisualGlyph> selectorVisual = wynnVisualGlyphs(selector.getVisualOrderText());
        assertTrue(selectorVisual.stream().anyMatch(glyph -> glyph.codePoint() == 0xE000),
                "selector visual-order stream retains the first public PUA anchor: " + selectorVisual);
        assertTrue(selectorVisual.stream().anyMatch(glyph -> glyph.codePoint() == 'L'),
                "selector visual-order stream retains natural-language glyphs: " + selectorVisual);
        WynnActionbarGlyphOverlayPlan.Projection glyphProjection =
                WynnActionbarGlyphOverlayPlan.project(selector);
        assertNotNull(glyphProjection, "recognized selector projects an exact visual-order glyph stream");
        assertNotNull(WynnActionbarGlyphOverlayPlan.project(selector),
                "server-agnostic structural ownership accepts the public selector grammar for the direct glyph renderer");
        assertEquals(selector.getString(), glyphProjection.sourceActionbar().getString(),
                "direct glyph projection retains the complete original actionbar component");
        assertTrue(!glyphProjection.cacheKey().isBlank(), "direct glyph projection has a semantic cache key");
        assertTrue(glyphProjection.semanticComponents().stream()
                        .anyMatch(component -> component.getString().equals("Left-Click to select")),
                "a normal single-space selector phrase becomes one semantic request slot");
        assertTrue(glyphProjection.semanticComponents().stream()
                        .anyMatch(component -> component.getString().equals("Scroll up/down to browse")),
                "the browse phrase becomes one semantic request slot");
        assertTrue(glyphProjection.semanticComponents().stream()
                        .anyMatch(component -> component.getString().equals("Right-Click to return")),
                "the return phrase becomes one semantic request slot: "
                        + glyphProjection.semanticComponents().stream().map(Component::getString).toList());
        assertTrue(glyphProjection.semanticComponents().stream()
                        .anyMatch(component -> component.getString().equals("Battle Monk")),
                "center-left selector display names remain semantic request slots");
        assertTrue(glyphProjection.semanticComponents().stream()
                        .anyMatch(component -> component.getString().equals("Swift combos")),
                "center-left selector descriptions remain semantic request slots");
        assertTrue(glyphProjection.semanticComponents().stream()
                        .noneMatch(component -> component.getString().contains("AS8")
                                || component.getString().contains("v2.2.1_4")),
                "selector status/version labels remain on the original glyph stream");
        assertTrue(glyphProjection.slots().stream().noneMatch(slot ->
                        slot.sourceText().length() == 1
                                && Character.isLetter(slot.sourceText().charAt(0))),
                "top-middle decorative letter cells never become individual model slots");
        assertTrue(glyphProjection.slots().stream().allMatch(slot ->
                        slot.screenAnchor() == WynnActionbarGlyphOverlayPlan.ScreenAnchor.CENTER_LEFT
                                || slot.screenAnchor() == WynnActionbarGlyphOverlayPlan.ScreenAnchor.BOTTOM_MIDDLE),
                "only verified selector shader anchors enter direct translation");
        for (Component semantic : glyphProjection.semanticComponents()) {
            String text = semantic.getString();
            assertTrue(!text.contains("§") && !text.contains("\uE000") && !text.contains("\uE001")
                            && !text.contains("\uE002") && !text.contains("\u0001")
                            && !text.contains("C\uE001r\uE001e"),
                    "direct glyph request slots never include selector anchors or decorative grids");
        }
        String glyphRequest = JsonPassthroughPipeline.serializeComponents(glyphProjection.semanticComponents());
        JsonArray glyphRequestEntries = JsonParser.parseString(glyphRequest).getAsJsonArray();
        assertEquals(glyphProjection.semanticComponents().size(), glyphRequestEntries.size(),
                "direct glyph COMPONENT_JSON request preserves every natural-language phrase");
        List<Component> translated = translateWynnGlyphSlots(glyphProjection.semanticComponents());
        WynnActionbarGlyphOverlayPlan.Plan plan = glyphProjection.bindTranslations(translated);
        assertNotNull(plan, "direct glyph plan accepts safe Chinese phrase translations");
        assertEquals(selector.getString(), plan.sourceActionbar().getString(),
                "direct glyph plan keeps the original source component for vanilla centering");
        List<WynnVisualGlyph> originalVisual = wynnVisualGlyphs(selector.getVisualOrderText());
        assertEquals(originalVisual, wynnVisualGlyphs(plan.sourceSequence()),
                "source sequence retains exact source indices, styles, PUA and spaces");
        List<WynnMaskedGlyph> maskedVisual = wynnMaskedGlyphs(plan.maskedSourceSequence());
        assertEquals(originalVisual, maskedVisual.stream().map(WynnMaskedGlyph::glyph).toList(),
                "masked sequence never rewrites the original visual stream");
        assertTrue(maskedVisual.stream().anyMatch(glyph -> glyph.masked() && glyph.glyph().codePoint() == 'L'),
                "only semantic English glyph pixels are suppressed before Chinese overlay rendering");
        assertTrue(maskedVisual.stream().noneMatch(glyph -> glyph.masked() && isWynnPua(glyph.glyph().codePoint())),
                "PUA anchors and icons remain in the original vanilla glyph stream");
        assertTrue(!WynnActionbarGlyphOverlayPlan.isCurrentGlyphMasked(),
                "glyph masking clears after each visual-order callback");
        assertTrue(maskedVisual.stream().noneMatch(glyph -> glyph.masked() && glyph.glyph().codePoint() == ' '),
                "fixed and interior spacing keeps its original glyph advance");
        assertTrue(maskedVisual.stream().noneMatch(glyph -> glyph.masked()
                        && glyph.glyph().codePoint() == 'C'
                        && String.valueOf(glyph.glyph().style().getFont()).contains("top_middle")),
                "unknown PUA-interleaved decorative letter grids remain visible source glyphs");
        assertFloatEquals(wynnGlyphOverlayWidth(plan.sourceSequence()),
                wynnGlyphOverlayWidth(plan.maskedSourceSequence()),
                "glyph masking retains the exact original total advance");

        WynnActionbarGlyphOverlayPlan.Layout layout =
                plan.resolveLayout(SimpleTranslateJsonFixtureChecks::wynnGlyphOverlayWidth);
        assertNotNull(layout, "direct glyph overlay resolves from complete original prefixes");
        assertTrue(layout.slots().stream().allMatch(slot -> slot.scaleX() >= 0.75F),
                "every direct glyph overlay stays at or above the readable 0.75 scale floor");
        assertNull(plan.resolveLayout(sequence ->
                        "\u5de6\u952e\u70b9\u51fb\u4ee5\u9009\u62e9\u5f53\u524d\u804c\u4e1a\u89d2\u8272".equals(wynnGlyphText(sequence))
                                ? 27.0F : wynnGlyphOverlayWidth(sequence)),
                "direct glyph overlay rejects a translated slot that would compress below 0.75");
        WynnActionbarGlyphOverlayPlan.PositionedSlot leftClick =
                findWynnGlyphSlot(layout, "Left-Click to select");
        WynnActionbarGlyphOverlayPlan.PositionedSlot scroll =
                findWynnGlyphSlot(layout, "Scroll up/down to browse");
        WynnActionbarGlyphOverlayPlan.PositionedSlot rightClick =
                findWynnGlyphSlot(layout, "Right-Click to return");
        WynnActionbarGlyphOverlayPlan.PositionedSlot battleMonk =
                findWynnGlyphSlot(layout, "Battle Monk");
        WynnActionbarGlyphOverlayPlan.PositionedSlot swiftCombos =
                findWynnGlyphSlot(layout, "Swift combos");
        assertTrue(leftClick.scaleX() < 1.0F,
                "overwide Chinese overlay compresses inside its original source span");
        assertFloatEquals(21.0F, leftClick.x(),
                "left-click overlay starts after the real E000 source prefix");
        assertFloatEquals(121.0F, scroll.x(),
                "browse overlay starts after the original E002 reset prefix");
        assertFloatEquals(221.0F, rightClick.x(),
                "return overlay starts after the original E001 reset prefix");
        assertFloatEquals(1.0F, scroll.scaleX(),
                "short Chinese overlays are not stretched");
        assertEquals(WynnActionbarGlyphOverlayPlan.ScreenAnchor.BOTTOM_MIDDLE,
                leftClick.source().screenAnchor(),
                "bottom controls keep the shader's zero-offset bottom-middle anchor");
        assertEquals(WynnActionbarGlyphOverlayPlan.ScreenAnchor.CENTER_LEFT,
                battleMonk.source().screenAnchor(),
                "left display name records the Wynn center-left shader anchor");
        assertEquals(WynnActionbarGlyphOverlayPlan.ScreenAnchor.CENTER_LEFT,
                swiftCombos.source().screenAnchor(),
                "left description records the Wynn center-left shader anchor");
        assertEquals(new net.minecraft.network.chat.FontDescription.Resource(
                        ActiveFontManager.CJK_FALLBACK_FONT),
                battleMonk.component().getStyle().getFont(),
                "left selector translations use the mod-owned CJK font instead of the Wynn bitmap font");
        assertFloatEquals(-480.0F, battleMonk.source().screenAnchor().offsetX(960),
                "center-left CPU overlay mirrors the shader's negative half-screen X shift");
        assertFloatEquals(-270.0F, battleMonk.source().screenAnchor().offsetY(540),
                "center-left CPU overlay mirrors the shader's negative half-screen Y shift");
        assertFloatEquals(0.0F, leftClick.source().screenAnchor().offsetX(960),
                "bottom-middle overlay receives no synthetic horizontal shift");
        assertFloatEquals(0.0F, leftClick.source().screenAnchor().offsetY(540),
                "bottom-middle overlay receives no synthetic vertical shift");

        assertFloatEquals(leftClick.x(), leftClick.drawX(),
                "advance-only fixture seam keeps the historical baseline x");
        assertFloatEquals(0.0F, leftClick.drawY(),
                "advance-only fixture seam keeps the historical baseline y");
        WynnActionbarGlyphOverlayPlan.Layout visibleBoundsLayout = plan.resolveLayout(
                SimpleTranslateJsonFixtureChecks::wynnGlyphOverlayWidth,
                sequence -> {
                    String text = wynnGlyphText(sequence);
                    if ("Left-Click to select".equals(text)) {
                        return new WynnActionbarGlyphOverlayPlan.GlyphBounds(1.25F, -7.0F, 19.25F, 2.0F);
                    }
                    if ("\u5de6\u952e\u70b9\u51fb\u4ee5\u9009\u62e9\u5f53\u524d\u804c\u4e1a\u89d2\u8272".equals(text)) {
                        return new WynnActionbarGlyphOverlayPlan.GlyphBounds(-2.5F, -4.0F, 25.5F, 5.0F);
                    }
                    float measured = Math.max(1.0F, wynnGlyphOverlayWidth(sequence));
                    return new WynnActionbarGlyphOverlayPlan.GlyphBounds(0.0F, -8.0F, measured, 2.0F);
                });
        assertNotNull(visibleBoundsLayout,
                "direct glyph overlay accepts deterministic visible-pixel bounds");
        WynnActionbarGlyphOverlayPlan.PositionedSlot visibleLeftClick =
                findWynnGlyphSlot(visibleBoundsLayout, "Left-Click to select");
        assertFloatEquals(leftClick.x() + 1.25F - (-2.5F * leftClick.scaleX()),
                visibleLeftClick.drawX(),
                "scaled target left bearing aligns with the source visible left edge");
        assertFloatEquals(-3.0F, visibleLeftClick.drawY(),
                "target glyph top aligns vertically with the source visible glyph top");
        assertFloatEquals(
                leftClick.x() + 1.25F,
                visibleLeftClick.drawX() + (-2.5F * visibleLeftClick.scaleX()),
                "translated visible left edge lands exactly on the original visible left edge");
        assertNull(plan.resolveLayout(SimpleTranslateJsonFixtureChecks::wynnGlyphOverlayWidth,
                        sequence -> null),
                "missing glyph bounds fail closed to the untouched source actionbar");

        assertNull(glyphProjection.bindTranslations(List.of(Component.literal("\u7f3a\u5931"))),
                "slot count mismatch safely leaves the original selector untouched");
        List<Component> unsafe = new ArrayList<>(translated);
        unsafe.set(0, Component.literal("§e\u4e0d\u5b89\u5168"));
        assertNotNull(glyphProjection.bindTranslations(unsafe),
                "a parseable legacy-code response is accepted by the shared count/Component contract");
        unsafe.set(0, Component.literal("\u4e0d\u5b89\u5168\uE000"));
        assertNotNull(glyphProjection.bindTranslations(unsafe),
                "a parseable PUA response is not rejected by a second selector allowlist");
        unsafe.set(0, Component.literal("\u4e0d\u5b89\u5168" + Character.toString(0xCFFC4)));
        assertNotNull(glyphProjection.bindTranslations(unsafe),
                "a parseable supplementary response is not rejected by a second selector allowlist");
        unsafe.set(0, Component.literal("\u4e0d\u5b89\u5168\u200D"));
        assertNotNull(glyphProjection.bindTranslations(unsafe),
                "a parseable FORMAT response is not rejected by a second selector allowlist");
        List<Component> partiallyUnchanged = new ArrayList<>(translated);
        partiallyUnchanged.set(0, Component.literal(
                glyphProjection.semanticComponents().get(0).getString()));
        assertNotNull(glyphProjection.bindTranslations(partiallyUnchanged),
                "an unchanged semantic slot stays local without discarding changed sibling translations");

        Component playSwitch = componentFromJson(
                "{\"text\":\"\uE000 Left-Click to play                     \uE001 Right-Click to switch\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}");
        assertTrue(WynncraftProfile.matchesActionbar(playSwitch),
                "E000/E001 play-switch selector grammar is recognized");
        Component malformed = componentFromJson(
                "{\"text\":\"\uE002 Left-Click to select          \uE000 Scroll up/down to browse\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}");
        assertTrue(!WynncraftProfile.matchesActionbar(malformed),
                "missing/reordered selector anchors fail closed instead of entering Wynn rendering");
        assertNull(WynnActionbarGlyphOverlayPlan.project(malformed),
                "altered PUA source streams never create a live direct glyph overlay plan");

        Component dynamicFour = componentFromJson(
                "{\"text\":\"\uE000 Left-Click to play          \uE001 Right-Click to switch \u0001§eBattle Monk Combat Lv. 4\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}");
        Component dynamicFive = componentFromJson(
                "{\"text\":\"\uE000 Left-Click to play          \uE001 Right-Click to switch \u0001§eBattle Monk Combat Lv. 5\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"}");
        WynnActionbarGlyphOverlayPlan.Projection directFour =
                WynnActionbarGlyphOverlayPlan.project(dynamicFour);
        WynnActionbarGlyphOverlayPlan.Projection directFive =
                WynnActionbarGlyphOverlayPlan.project(dynamicFive);
        assertNotNull(directFour, "direct glyph dynamic selector four is recognized");
        assertNotNull(directFive, "direct glyph dynamic selector five is recognized");
        assertEquals(directFour.cacheKey(), directFive.cacheKey(),
                "dynamic values reuse one direct glyph semantic translation key");

        Component topHeading = componentFromJson(
                "{\"text\":\"\",\"extra\":["
                        + "{\"text\":\"\uE000 Left-Click to select          \uE002 Scroll up/down to browse          \uE001 Right-Click to return\",\"font\":\"minecraft:hud/selector/default/bottom_middle\"},"
                        + "{\"text\":\"C\uE010r\uE011e\uE012a\uE013t\uE014e\uE015a\uE016C\uE017h\uE018a\uE019r\uE01Aa\uE01Bc\uE01Ct\uE01De\uE01Er\",\"font\":\"minecraft:hud/selector/default/top_middle\"}]}");
        WynnActionbarGlyphOverlayPlan.Projection topProjection =
                WynnActionbarGlyphOverlayPlan.project(topHeading);
        assertNotNull(topProjection, "exact CreateaCharacter positioned glyph grid is recognized");
        assertTrue(topProjection.cacheKey().contains("wynn.glyph-overlay.v5"),
                "top-middle selector layout uses the v5 cache projection key");
        assertTrue(topProjection.semanticComponents().stream().anyMatch(component ->
                        component.getString().equals("Create a Character")),
                "positioned top heading is reconstructed as one semantic slot");
        WynnActionbarGlyphOverlayPlan.SemanticSlot createSlot = topProjection.slots().stream()
                .filter(slot -> slot.sourceText().equals("Create a Character"))
                .findFirst().orElseThrow();
        assertEquals(WynnActionbarGlyphOverlayPlan.ScreenAnchor.TOP_MIDDLE, createSlot.screenAnchor(),
                "Create a Character records the verified top-middle screen anchor");
        assertEquals(16, createSlot.maskOrdinals().size(),
                "only the sixteen source letter glyphs are masked; positioning PUA remain visible");
        WynnActionbarGlyphOverlayPlan.Plan topPlan = topProjection.bindTranslations(
                translateWynnGlyphSlots(topProjection.semanticComponents()));
        assertNotNull(topPlan, "top-middle heading binds a safe Chinese overlay");
        List<WynnMaskedGlyph> topMasked = wynnMaskedGlyphs(topPlan.maskedSourceSequence());
        assertTrue(topMasked.stream().filter(WynnMaskedGlyph::masked).count() >= 16,
                "the exact English heading glyphs are hidden after a complete translation");
        assertTrue(topMasked.stream().noneMatch(glyph -> glyph.masked()
                        && isWynnPua(glyph.glyph().codePoint())),
                "top-middle positioning glyphs retain their original advances");
    }

    private static void checkWirePayloadContextSeparation() throws Exception {
        String userArray = "[\"Auto Open\",\"Click to Toggle\"]";
        String caller = "menu context with a quote: \\\"Wynn\\\"";
        assertEquals(userArray, JsonPassthroughPipeline.buildUserPayload(userArray, caller),
                "caller context never changes the Component JSON user array");
        assertTrue(JsonParser.parseString(JsonPassthroughPipeline.buildUserPayload(userArray, caller)).isJsonArray(),
                "the user payload remains one parseable top-level Component array");
        String componentArray = "[{\"text\":\"Auto Open\"}]";
        assertEquals(componentArray, JsonPassthroughPipeline.buildUserPayload(componentArray, caller),
                "caller context never changes the COMPONENT_JSON user array");

        TextContextMemory.PromptMetadata metadata = TextContextMemory.buildPromptMetadata(
                caller, "tooltip.item_context.semantic_paragraph.v1", "item-tooltip",
                userArray, false);
        assertTrue(!metadata.json().isBlank(), "caller context becomes separate JSON prompt metadata");
        JsonObject metadataJson = JsonParser.parseString(metadata.json()).getAsJsonObject();
        assertEquals("simple_translate_prompt_context_v2", metadataJson.get("schema").getAsString(),
                "context metadata schema");
        assertEquals("item-tooltip", metadataJson.get("surface_role").getAsString(),
                "caller role is preserved as prompt metadata");
        assertEquals("tooltip.item_context.semantic_paragraph.v1",
                metadataJson.get("surface").getAsString(), "surface is preserved as prompt metadata");
        assertEquals(caller, metadataJson.get("caller_context").getAsString(),
                "caller context is JSON escaped and round-trips as data");
        String systemPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                "en_us", "zh_cn", List.of(),
                "tooltip.item_context.semantic_paragraph.v1", metadata.json());
        assertTrue(systemPrompt.contains("OPTIONAL LOCAL CONTEXT METADATA")
                        && systemPrompt.contains(metadata.json()),
                "smart context is attached to the system prompt only");
        String factionShape = JsonPassthroughPipeline.semanticPromptSourceShape(List.of(Component.literal(
                "Faction Leaderboard\n\nCompare the total Faction XP contributed towards each of the 10 factions.")));
        assertTrue(factionShape.contains("the 10 factions") && !factionShape.contains("the <number> factions"),
                "ordinary prose quantities remain inside the complete source sentence");
        ComponentVisualProjection factionProjection = ComponentVisualProjection.projectComponents(List.of(
                Component.literal("Faction Leaderboard\n\nCompare the total Faction XP contributed towards each of the 10 factions.")),
                "zh_cn");
        assertNotNull(factionProjection, "Faction leaderboard projection");
        String serializedFaction10 = JsonPassthroughPipeline.serializeComponents(List.of(Component.literal(
                "Faction Leaderboard\n\nCompare the total Faction XP contributed towards each of the 10 factions.")));
        String serializedFaction11 = JsonPassthroughPipeline.serializeComponents(List.of(Component.literal(
                "Faction Leaderboard\n\nCompare the total Faction XP contributed towards each of the 11 factions.")));
        assertTrue(!serializedFaction10.equals(serializedFaction11)
                        && serializedFaction10.contains("10")
                        && !serializedFaction10.contains("⟦N"),
                "different fixed quantities produce different semantic cache sources");
        ComponentVisualProjection normalizedFactionProjection = ComponentVisualProjection.project(
                serializedFaction10, "zh_cn");
        assertNotNull(normalizedFactionProjection, "normalized Faction leaderboard projection");
        assertEquals(factionProjection.semanticJson(), normalizedFactionProjection.semanticJson(),
                "live and normalized transport projections agree for fixed prose quantities");
        assertTrue(factionProjection.semanticJson().contains("the 10 factions"),
                "ordinary prose quantities remain model-visible for article and classifier reordering");
        JsonArray factionResponse = new JsonArray();
        for (ComponentVisualProjection.SemanticSlot slot : factionProjection.slots()) {
            factionResponse.add(slot.sourceText().contains("the 10 factions")
                    ? "比较阵营经验贡献，看看 10 个阵营中谁遥遥领先。"
                    : "译文");
        }
        JsonArray rebuiltFaction = factionProjection.rebuildResponse(factionResponse);
        assertNotNull(rebuiltFaction, "Faction leaderboard rebuilt response");
        String rebuiltFactionText = ComponentJsonCompat.fromJson(rebuiltFaction.get(0)).getString();
        assertTrue(rebuiltFactionText.indexOf("10") >= 0
                        && rebuiltFactionText.indexOf("10") == rebuiltFactionText.lastIndexOf("10"),
                "a semantic prose quantity is translated and emitted exactly once by the Component response");
        assertTrue(!rebuiltFactionText.contains("the 10"),
                "the English article is not stranded beside a client-owned number gap");
        JsonArray structurallyValidNumericResponse = new JsonArray();
        for (int slot = 0; slot < factionProjection.slotCount(); slot++) {
            structurallyValidNumericResponse.add(slot == 0 ? "译文99" : "译文");
        }
        assertNotNull(factionProjection.rebuildResponse(structurallyValidNumericResponse),
                "numeric model text remains accepted when count and Component parsing are valid");
        assertEquals("component_visual_projection_v7", TranslationPromptPolicy.SEMANTIC_REVISION,
                "the global prompt revision invalidates caches created under pre-override prompt wording");
        String guiTooltipContext = "frame_context_kind=item_tooltip\n"
                + "Continuous reading stream: Survive at least 4m or win in 8 games <visual-row> of Dynaball.";
        TextContextMemory.PromptMetadata guiTooltipMetadata = TextContextMemory.buildPromptMetadata(
                guiTooltipContext, "gui.component.visible_frame.v3", "gui-visible-frame",
                "[\"Survive at least or win in games\",\"of Dynaball.\"]", false);
        String maskedGuiTooltipContext = JsonParser.parseString(guiTooltipMetadata.json()).getAsJsonObject()
                .get("caller_context").getAsString();
        assertTrue(maskedGuiTooltipContext.contains("frame_context_kind=item_tooltip")
                        && maskedGuiTooltipContext.contains("4m")
                        && maskedGuiTooltipContext.contains("8 games")
                        && !maskedGuiTooltipContext.contains("<number>"),
                "fixed quest quantities remain available to the shared semantic context");
        String liveProgressShape = JsonPassthroughPipeline.semanticPromptSourceShape(List.of(
                Component.literal("Daily Quest Progress: 3/5")));
        assertTrue(liveProgressShape.contains("Daily Quest Progress: <number>")
                        && !liveProgressShape.contains("3/5"),
                "a structural progress ratio remains a client-owned live value");
        String fixedRequirementShape = JsonPassthroughPipeline.semanticPromptSourceShape(List.of(
                Component.literal("Requires 10 XP to unlock")));
        assertTrue(fixedRequirementShape.contains("Requires 10 XP to unlock")
                        && !fixedRequirementShape.contains("<number>"),
                "an XP requirement inside prose is not mistaken for live telemetry");
        String liveLevelShape = JsonPassthroughPipeline.semanticPromptSourceShape(List.of(
                Component.literal("Combat Level 82")));
        assertTrue(liveLevelShape.contains("Combat Level <number>")
                        && !liveLevelShape.contains("82"),
                "a terminal status value remains reusable across live updates");
        String guiTooltipPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                "en_us", "zh_cn", List.of(),
                "gui.component.visible_frame.v3", guiTooltipMetadata.json());
        assertTrue(guiTooltipPrompt.contains("from one item tooltip"),
                "detached K item frame marker activates item-tooltip prompt policy");
        assertTrue(guiTooltipPrompt.contains("physical draw rows"),
                "whole GUI prompt treats draw rows as non-semantic wraps");
        assertTrue(guiTooltipPrompt.contains("trailing phrase such as 'in N games/matches'"),
                "item quest prompt preserves trailing quantity scope");
        assertTrue(guiTooltipPrompt.contains("trailing game-count scope applies to the whole condition"),
                "item quest prompt carries the cross-wrap scope correction without copying live values");
        assertTrue(guiTooltipPrompt.contains("Keep every ordinary quantity exactly once")
                        && guiTooltipPrompt.contains("COMMON / WEEKLY QUEST")
                        && guiTooltipPrompt.contains("数量 + 剩余"),
                "Chinese item-frame guidance covers quest scope, badge headings, and ordinary quantities");
        assertTrue(guiTooltipPrompt.contains("Never emit <number>")
                        && guiTooltipPrompt.contains("Survive at least 4m")
                        && guiTooltipPrompt.contains("8 games")
                        && guiTooltipPrompt.contains("ordinary numbers present in the JSON request"),
                "the system prompt distinguishes live gaps from semantic quantities");
        assertTrue(JsonParser.parseString(guiTooltipMetadata.json()).getAsJsonObject()
                        .get("caller_context").getAsString().contains("frame_context_kind=item_tooltip"),
                "the detached frame kind survives JSON metadata escaping without changing the user array");
        String wynnDialoguePrompt = JsonPassthroughPrompts.buildSystemPrompt(
                "en_us", "zh_cn", List.of(),
                "hud.actionbar.wynn.dialogue.content.paragraph.v5", "");
        assertTrue(wynnDialoguePrompt.contains("one complete spoken paragraph")
                        && wynnDialoguePrompt.contains("one fluent paragraph translation")
                        && wynnDialoguePrompt.contains("protected keycap glyphs"),
                "the Component JSON Wynn prompt requests coherent BODY prose while retaining local keycaps");
        String entityJsonPrompt = JsonPassthroughPrompts.buildSystemPrompt(
                "en_us", "zh_cn", List.of(), "entity.name.direct", metadata.json());
        assertTrue(entityJsonPrompt.contains("Keep genuine player/account names unchanged")
                        && entityJsonPrompt.contains("surface_role")
                        && entityJsonPrompt.contains("programming identifier"),
                "the unified Component prompt carries role-aware entity policy without marker contracts");
        TextContextMemory.PromptMetadata componentRequestMetadata = TextContextMemory.buildPromptMetadata(
                "", "tooltip.item_context.semantic_paragraph.v1", userArray, true);
        long previousRevision = componentRequestMetadata.contextRevision();
        assertTrue(previousRevision >= 0L && TextContextMemory.isRevisionCurrent(previousRevision),
                "every Component JSON cache miss captures the independent context revision even when history is disabled");
        long changedRevision = TextContextMemory.settingsChanged();
        assertTrue(changedRevision > previousRevision && TextContextMemory.isRevisionCurrent(changedRevision),
                "context setting changes invalidate only context-bound requests");
        assertTrue(!TextContextMemory.isRevisionCurrent(previousRevision),
                "old Component requests are rejected before they can populate the stable cache");
        TextContextMemory.PromptMetadata entityRequestMetadata = TextContextMemory.buildPromptMetadata(
                "", "entity.name.direct", "entity-name", componentArray, true);
        assertTrue(entityRequestMetadata.contextRevision() == changedRevision,
                "all Component JSON surfaces capture the same smart-context revision");
    }

    private static void checkUnifiedTooltipProductionProjection() throws Exception {
        Style gray = Style.EMPTY.withColor(ChatFormatting.GRAY);
        String localIcon = Character.toString(0xE240);

        List<Component> interleavedGlyph = List.of(
                Component.literal("Translate left " + localIcon + " translate right").withStyle(gray));
        assertUnifiedTooltipReadyRoundTrip(interleavedGlyph, "interleaved visual tooltip",
                List.of(localIcon));

        List<Component> dailyMeter = List.of(
                Component.literal("Daily Meter").withStyle(ChatFormatting.YELLOW),
                Component.literal("Earn XP to level up this meter!").withStyle(gray),
                Component.literal("▏▏▏▏▏▏▏▏▏▏ 0%").withStyle(gray),
                Component.literal("Progress: 0/500 XP").withStyle(gray),
                Component.literal("Resets in: 9h 16m").withStyle(ChatFormatting.RED));
        assertUnifiedTooltipReadyRoundTrip(dailyMeter, "Daily Meter",
                List.of("▏▏▏", "0/500", "9h"));

        List<Component> weeklyQuest = List.of(
                Component.literal("Weekly Quest"),
                Component.empty()
                        .append(Component.literal(localIcon).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" Complete 8 games").withStyle(ChatFormatting.YELLOW)),
                Component.literal("▏▏▏▏▏▏▏▏▏▏ 0%").withStyle(gray),
                Component.literal("Progress: 0/8").withStyle(gray),
                Component.literal("Expires in: 4d 4h 24m").withStyle(ChatFormatting.RED));
        assertUnifiedTooltipReadyRoundTrip(weeklyQuest, "Weekly Quest",
                List.of(localIcon, "▏▏▏", "0/8", "4d"));
    }

    private static void assertUnifiedTooltipReadyRoundTrip(
            List<Component> source, String label, List<String> retainedLocalAtoms) throws Exception {
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(source, "zh_cn");
        assertNotNull(projection, label + " unified production projection exists");
        assertTrue(projection.hasSlots(), label + " unified production projection has semantic slots");

        List<Component> translatedSlots = new ArrayList<>();
        for (int index = 0; index < projection.slotCount(); index++) {
            translatedSlots.add(Component.literal("译文" + index));
        }
        List<Component> restoredFullTree = projection.rebuildComponentList(translatedSlots);
        assertNotNull(restoredFullTree, label + " shared projection restores the full Component tree");

        Method extractReady = TooltipTranslationHelper.class.getDeclaredMethod(
                "translatedSemanticComponents", ComponentVisualProjection.class, List.class);
        extractReady.setAccessible(true);
        List<Component> ready = (List<Component>) extractReady.invoke(
                null, projection, restoredFullTree);
        assertNotNull(ready, label + " async full-tree result extracts semantic READY Components");
        assertEquals(projection.slotCount(), ready.size(),
                label + " READY result keeps the exact semantic slot count");

        Method readyKeyMethod = TooltipTranslationHelper.class.getDeclaredMethod(
                "semanticPendingKey", String.class, ComponentVisualProjection.class);
        readyKeyMethod.setAccessible(true);
        String readyKey = (String) readyKeyMethod.invoke(
                null, "tooltip.visible.item.component.v2", projection);
        TooltipSemanticResultStore.put(readyKey, ready);
        List<Component> currentReady = TooltipSemanticResultStore.get(readyKey);
        assertNotNull(currentReady, label + " semantic result enters the production READY handoff");
        ComponentVisualProjection currentProjection = JsonPassthroughPipeline.projectLiveComponents(
                source, "zh_cn");
        assertNotNull(currentProjection, label + " current render projection exists");
        List<Component> rebound = currentProjection.rebuildComponentList(currentReady);
        assertNotNull(rebound, label + " READY result rebinds through the current source skeleton");
        assertEquals(source.size(), rebound.size(),
                label + " current top-level Component structure remains intact before render wrapping");

        String reboundText = rebound.stream().map(Component::getString)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(reboundText.contains("译文0"),
                label + " READY rebound contains translated semantic text");
        for (String atom : retainedLocalAtoms) {
            assertTrue(reboundText.contains(atom),
                    label + " READY rebound preserves current local atom: " + atom);
        }
        TooltipSemanticResultStore.remove(readyKey);
    }

    private static void checkTranslationLaneLeaseIsolation() {
        TranslationLane lane = TranslationLanes.get("fixture-lease-isolation");
        lane.clear();
        String key = "same-component-request";
        TranslationLane.Lease oldLease = lane.begin(key, 60_000L);
        assertNotNull(oldLease, "first request obtains a translation-lane lease");
        assertTrue(lane.isPending(key), "first request is pending");

        lane.clear();
        TranslationLane.Lease currentLease = lane.begin(key, 60_000L);
        assertNotNull(currentLease, "same key may start after runtime state clear");
        assertTrue(lane.isPending(key), "new generation request is pending");

        lane.finish(oldLease);
        assertTrue(lane.isPending(key),
                "late success from an old generation cannot release the new request");
        lane.fail(oldLease, 60_000L);
        assertTrue(lane.isPending(key),
                "late failure from an old generation cannot release the new request");
        assertTrue(!lane.isThrottled(key),
                "late failure from an old generation cannot throttle the new request");

        lane.finish(currentLease);
        assertTrue(!lane.isPending(key), "current lease releases its own request");
        TranslationLane.Lease failedLease = lane.begin(key, 60_000L);
        assertNotNull(failedLease, "request can restart after successful completion");
        lane.fail(failedLease, 60_000L);
        assertTrue(!lane.isPending(key), "current failed lease releases its own request");
        assertTrue(lane.isThrottled(key), "current failed lease installs the normal cooldown");
        lane.clear();
    }

    private static void checkLegacyTooltipSemanticCacheBridge() throws Exception {
        String icon = Character.toString(0xE240);
        List<Component> source = List.of(
                Component.literal("Daily Quest").withStyle(ChatFormatting.GREEN),
                Component.empty()
                        .append(Component.literal(icon).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" Complete 8 games").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Progress: 0/8").withStyle(ChatFormatting.GRAY));
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(source, "zh_cn");
        assertNotNull(projection, "legacy tooltip cache bridge has a current projection");
        assertTrue(projection.hasSlots(), "legacy tooltip cache bridge has semantic slots");

        List<Component> legacySemantic = new ArrayList<>();
        for (int index = 0; index < projection.slotCount(); index++) {
            legacySemantic.add(Component.literal("旧缓存译文" + index));
        }
        String sourceText = projection.semanticComponents().stream()
                .map(Component::getString)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + '\n' + right);
        String translationText = legacySemantic.stream()
                .map(Component::getString)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + '\n' + right);
        String legacyPayload = JsonPassthroughPipeline.serializeComponents(legacySemantic);

        Path cacheDir = Files.createTempDirectory("simpletranslate-tooltip-legacy-bridge");
        try {
            TranslationCache cache = new TranslationCache(cacheDir.resolve("translations.json"));
            String oldKey = TranslationCacheKeys.componentJsonKey(
                    "tooltip.item_context.semantic_paragraph.v2",
                    projection.semanticJson(), "legacy 2.1.13 context",
                    ModConfig.SOURCE_LANGUAGE.get(), ModConfig.TARGET_LANGUAGE.get());
            cache.putComponentJson(oldKey, legacyPayload, projection.semanticJson(),
                    sourceText, translationText);

            Method bridge = TooltipTranslationHelper.class.getDeclaredMethod(
                    "tryLegacySemanticCacheCandidate", TranslationCache.class, List.class,
                    ComponentVisualProjection.class, String.class, String.class, String.class);
            bridge.setAccessible(true);
            String readyKey = "fixture-legacy-ready";
            List<Component> rebuilt = (List<Component>) bridge.invoke(
                    null, cache, source, projection,
                "tooltip.visible.item.component.v2", readyKey, "");
            assertNotNull(rebuilt, "2.1.13 semantic cache candidate is accepted by the current tooltip path");
            String rebuiltText = rebuilt.stream().map(Component::getString)
                    .reduce("", (left, right) -> left + '\n' + right);
            assertTrue(rebuiltText.contains("旧缓存译文0"),
                    "legacy semantic cache text is rebound into the current Component skeleton");
            assertTrue(rebuiltText.contains(icon) && rebuiltText.contains("0/8"),
                    "legacy semantic cache rebound preserves current icon and dynamic progress locally");
            List<Component> ready = TooltipSemanticResultStore.get(readyKey);
            assertNotNull(ready, "legacy semantic cache hit seeds the current READY handoff");
            assertEquals(projection.slotCount(), ready.size(),
                    "legacy semantic cache READY result keeps the exact current slot count");
            TooltipSemanticResultStore.remove(readyKey);
        } finally {
            Files.deleteIfExists(cacheDir.resolve("translations.json"));
            Files.deleteIfExists(cacheDir);
        }
    }

    private static void checkWynnDialogueProjection() throws Exception {
        // Captured from a real 26.1 Wynn Therck frame. The resource pack uses
        // U+Cxxxx/U+Dxxxx positioning glyphs around the visible dialogue text;
        // those code points are unassigned by Unicode rather than standard PUA.
        Component capturedTherck = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uDAFF\\uDFD7\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uE001 to continue\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uDAFF\\uDFD6\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uDAFF\\uDFA4\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Go on\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\u27E61001\\u27E7\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"I don't have time for you. My brother\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDF3B\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"keeps sending me people all the time.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uDAFF\\uDFB8\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDFC9\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Therck\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"\\uDB00\\uDC18\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/nameplate\"}]}"
        );
        WynnDialogueProjection captured = WynnDialogueProjection.project(capturedTherck);
        assertNotNull(captured,
                "captured Therck frame ignores supplementary Wynn positioning glyphs");
        assertEquals(3, captured.contentComponents().size(),
                "captured Therck frame exposes name, joined body, and control slots");
        assertEquals("Therck", captured.contentComponents().get(0).getString(),
                "supplementary nameplate anchors never enter the NPC name slot");
        assertTrue(captured.contentComponents().stream().noneMatch(component ->
                        component.getString().codePoints().anyMatch(codePoint ->
                                codePoint >= 0xC0000 && codePoint <= 0xDFFFF)),
                "supplementary Wynn positioning glyphs never enter dialogue requests");

        // Wynn builds a player-name placeholder as a separate Component run.
        // Its insertion metadata is not visible in the actionbar. The null and
        // explicit-white runs differ by colour alone, so the BODY stays
        // translatable and each translated span maps back to its own source
        // appearance. This fixture also reproduces the reported Aledar frame
        // whose successful cached translation deliberately leaves the NPC
        // proper name unchanged.
        Component playerNameDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE520\",\"font\":\"minecraft:hud/dialogue/portrait/aledar\"},"
                + "{\"text\":\"Aledar\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Well, nothing to do but keep moving! Tasim, \",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\"baokaixin\",\"color\":\"white\",\"insertion\":\"baokaixin\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\",\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\"I'll race you to the gate!\",\"font\":\"minecraft:hud/dialogue/text/common/body_1\"},"
                + "{\"text\":\"\\uE001 to continue\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/control\"}]}"
        );
        WynnDialogueProjection playerNameProjected =
                WynnDialogueProjection.project(playerNameDialogue);
        assertNotNull(playerNameProjected,
                "common-family Aledar dialogue with dynamic player-name metadata is recognized");
        assertEquals(3, playerNameProjected.contentComponents().size(),
                "colour-only BODY differences stay translatable beside the name and control slots");
        WynnDialogueProjection.SemanticSlot playerNameBody =
                playerNameProjected.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .findFirst().orElseThrow();
        assertEquals(4, playerNameBody.bodyAnchors().size(),
                "null and explicit-white runs keep their own source anchors for masking");
        WynnDialogueRenderPlan aledarCachedPlan = playerNameProjected.bindTranslations(
                List.of(Component.literal("Aledar"),
                        componentBodyTranslation(playerNameProjected,
                                "好吧，只能继续走了！塔西姆，", "baokaixin", "，", "我跟你比赛到大门！"),
                        Component.literal("继续")), List.of());
        assertNotNull(aledarCachedPlan,
                "colour-only Aledar BODY binds beside the control translation");
        assertEquals(2, aledarCachedPlan.translatedSlots().size(),
                "unchanged name stays source-owned while body and control overlay");
        assertTrue(aledarCachedPlan.translatedSlots().stream().noneMatch(slot ->
                        slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME),
                "the unchanged proper name remains in Wynn's original stream");

        // Exact 2.1.14 production regression: the Component JSON request and
        // cache both contained this complete Chinese reply, but the old render
        // gate wrapped 阿莱达尔 against the narrow English Aledar glyph span
        // and discarded the whole frame because the translated name used more rows.
        Component helmetDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Aledar\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Yes, a helmet! Will you let us pass now?\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"to continue\",\"font\":\"minecraft:hud/dialogue/text/control\"}]}");
        WynnDialogueProjection helmetProjection =
                WynnDialogueProjection.project(helmetDialogue);
        assertNotNull(helmetProjection, "reported Aledar helmet dialogue is recognized");
        WynnDialogueRenderPlan helmetPlan = helmetProjection.bindTranslations(
                List.of(Component.literal("阿莱达尔"),
                        componentBodyTranslation(helmetProjection,
                                "是的，一顶头盔！现在你能让我们过去了吗？"),
                        Component.literal("继续")), List.of());
        assertNotNull(helmetPlan, "reported cached Aledar Chinese reply binds to a render plan");
        assertEquals(List.of(
                        WynnDialogueProjection.SemanticKind.NAME,
                        WynnDialogueProjection.SemanticKind.BODY,
                        WynnDialogueProjection.SemanticKind.CONTROL),
                helmetPlan.translatedSlots().stream()
                        .map(slot -> slot.source().kind()).toList(),
                "name, complete body and control prompt all survive binding");
        assertEquals("阿莱达尔", helmetPlan.translatedSlots().getFirst().component().getString(),
                "the cached translated NPC name reaches the render plan unchanged");
        assertFloatEquals(17.0F,
                WynnDialogueRenderPlan.centeredDrawX(20.0F, 24.0F, -1.0F, 32.0F),
                "an overwide native-size CJK name is centered instead of wrapped or scaled");

        Component animatedPortraitFrame = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE521\",\"font\":\"minecraft:hud/dialogue/portrait/aledar\"},"
                + "{\"text\":\"Aledar\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Well, nothing to do but keep moving! Tasim, baokaixin,\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\"I'll race you to the gate!\",\"font\":\"minecraft:hud/dialogue/text/common/body_1\"},"
                + "{\"text\":\"\\uE001 to continue\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/control\"}]}"
        );
        WynnDialogueProjection animatedPortraitProjected =
                WynnDialogueProjection.project(animatedPortraitFrame);
        assertNotNull(animatedPortraitProjected, "animated portrait frame remains projectable");
        assertEquals(playerNameProjected.sessionKey(), animatedPortraitProjected.sessionKey(),
                "animated portrait glyphs do not reset the semantic dialogue session");
        assertTrue(playerNameProjected.hasSameLayout(
                        WynnDialogueProjection.project(playerNameDialogue)),
                "an identical captured glyph stream reuses the same dialogue layout identity");
        assertTrue(!playerNameProjected.hasSameLayout(animatedPortraitProjected),
                "portrait or positioning glyph changes invalidate cached dialogue geometry without resetting semantics");

        for (String family : List.of("common", "wynncraft", "high_gavellian", "wynnic",
                "old_fruman", "merchant")) {
            Component familyLine = componentFromJson("{\"text\":\"\",\"extra\":["
                    + "{\"text\":\"Speaker\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                    + "{\"text\":\"Translate this line\",\"font\":\"minecraft:hud/dialogue/text/"
                    + family + "/body_0\"}]}");
            WynnDialogueProjection familyProjection =
                    WynnDialogueProjection.project(familyLine);
            assertNotNull(familyProjection, family + " dialogue family is recognized");
            assertEquals("Translate this line", familyProjection.contentSlots().stream()
                            .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                            .findFirst().orElseThrow().sourceText(),
                    family + " body text reaches the semantic request");
        }

        // Real regression from the reported Tasim frame: the final English
        // source row is much shorter than the first. It must not become the
        // width budget that horizontally crushes the second Chinese row.
        Component tasimShortTail = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Tasim\",\"shadow_color\":1073741824,"
                + "\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Hopefully we won't have to use\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\"these just yet.\",\"font\":\"minecraft:hud/dialogue/text/common/body_1\"},"
                + "{\"text\":\"to continue\",\"shadow_color\":536870912,"
                + "\"font\":\"minecraft:hud/dialogue/text/control\"}]}");
        WynnDialogueProjection tasimProjection =
                WynnDialogueProjection.project(tasimShortTail);
        assertNotNull(tasimProjection, "reported Tasim short-tail frame is recognized");
        WynnDialogueRenderPlan tasimPlan = tasimProjection.bindTranslations(
                List.of(Component.literal("塔西姆"),
                        componentBodyTranslation(tasimProjection, "希望我们暂时还不用",
                                "用到这些。"),
                        Component.literal("继续")), List.of());
        assertNotNull(tasimPlan, "reported Tasim translation binds without data loss");
        FontDescription tasimOverlayFont =
                new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT);
        assertTrue(tasimPlan.translatedSlots().stream().allMatch(slot ->
                        tasimOverlayFont.equals(slot.component().getStyle().getFont())
                                || (!slot.component().getSiblings().isEmpty()
                                && slot.component().getSiblings().stream().allMatch(part ->
                                tasimOverlayFont.equals(part.getStyle().getFont())))),
                "reported Tasim frame renders every translated glyph through the mod-owned CJK font");
        WynnDialogueRenderPlan.TranslatedSlot tasimBody = tasimPlan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertTrue(!tasimBody.component().getString().isBlank(),
                "plain multi-fragment Wynn prose binds as one continuous BODY component");

        // Exact 2026-07-15 Tasim regression: the valid Component JSON reply
        // preserves three source children for count/style alignment. Those
        // children are wire structure, not three render anchors; retaining them
        // produced overlapping Chinese near "这个地方 / 到处都是".
        Component tasimFragmentedSentence = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Tasim\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"That guard wasn't kidding. This place is\","
                + "\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" crawling\",\"shadow_color\":16777215,"
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\" with the corrupt.\","
                + "\"shadow_color\":536870912,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\"to continue\",\"font\":\"minecraft:hud/dialogue/text/control\"}]}");
        WynnDialogueProjection tasimFragmentedProjection =
                WynnDialogueProjection.project(tasimFragmentedSentence);
        assertNotNull(tasimFragmentedProjection,
                "reported three-row Tasim sentence is recognized from exact Wynn fonts");
        assertEquals(3, tasimFragmentedProjection.contentComponents().size(),
                "per-row shadow differences stay translatable beside name and control slots");
        WynnDialogueRenderPlan tasimFragmentedPlan = tasimFragmentedProjection.bindTranslations(
                List.of(Component.literal("塔西姆"),
                        componentBodyTranslation(tasimFragmentedProjection,
                                "那个守卫没开玩笑。这个地方", "到处都是", "腐败的家伙。"),
                        Component.literal("继续")), List.of());
        assertNotNull(tasimFragmentedPlan,
                "shadow-varied Tasim BODY binds beside name and control translations");
        assertEquals(3, tasimFragmentedPlan.translatedSlots().size(),
                "name, shadow-varied body and control all overlay");
        assertTrue(tasimFragmentedPlan.translatedSlots().stream().anyMatch(slot ->
                        slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY),
                "the shadow-varied BODY overlays with each span mapped to its source appearance");

        Component decoratedWarning = componentFromJson("{\"text\":\"Warning \",\"color\":\"red\","
                + "\"bold\":true,\"italic\":true,\"underlined\":true,\"strikethrough\":true,"
                + "\"shadow_color\":1073741824,"
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}").copy()
                .withStyle(style -> style
                        .withInsertion("unsafe-local-insertion")
                        .withClickEvent(new ClickEvent.SuggestCommand("/unsafe-local-command"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("unsafe hidden hover"))));
        Component colouredBody = Component.empty()
                .append(decoratedWarning)
                .append(componentFromJson(
                "{\"text\":\"detail\",\"color\":\"white\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}"));
        WynnDialogueProjection colouredBodyProjection = WynnDialogueProjection.project(colouredBody);
        assertNull(colouredBodyProjection,
                "a multi-style BODY with no independent slot remains entirely source-owned");
        assertTrue(ComponentJsonCompat.toJson(colouredBody).contains("unsafe-local-insertion"),
                "source-local interaction metadata remains intact while no BODY overlay is created");

        // Exact live 2026-07-16 Tasim frame. Wynn marks the current word with
        // #00EB34; its shader interprets G=235/B=52 as movementItalic(-64, 1).
        // Copying that source style onto CJK sheared "到处都是" back over
        // "这个地方" even though the renderer emitted only one BODY draw.
        Component liveMovementTasim = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Tasim\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"That guard wasn't kidding. This place is \","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"crawling\",\"color\":\"#00EB34\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\" with the corrupt.\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"}]}" );
        WynnDialogueProjection liveMovementProjection =
                WynnDialogueProjection.project(liveMovementTasim);
        assertNotNull(liveMovementProjection,
                "live Tasim movement-marker frame is recognized");
        assertTrue(liveMovementProjection.contentSlots().stream().noneMatch(slot ->
                        slot.kind() == WynnDialogueProjection.SemanticKind.BODY),
                "a #00EB34 movement-marked prose run cannot be assigned one CJK BODY style");

        Component markerOnFirstWord = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Moving \",\"color\":\"#00eb34\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"marker\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}");
        Component markerOnSecondWord = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Moving \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"marker\",\"color\":\"#00eb34\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}");
        WynnDialogueProjection markerFirstProjection =
                WynnDialogueProjection.project(markerOnFirstWord);
        WynnDialogueProjection markerSecondProjection =
                WynnDialogueProjection.project(markerOnSecondWord);
        assertNull(markerFirstProjection,
                "a multi-style marker-only BODY with no independent slot remains source-owned");
        assertNull(markerSecondProjection,
                "moving a multi-style marker cannot create a translatable BODY slot");

        // Layout-level Tasim regression. Hidden row-reset glyphs keep their
        // original advances, while all translated BODY children flow exactly
        // once through mod-owned CJK rows.
        Component fixedRowTasim = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"First line\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE100\",\"font\":\"minecraft:hud/dialogue/text/layout/reset_1\"},"
                + "{\"text\":\"Second row\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uE101\",\"font\":\"minecraft:hud/dialogue/text/layout/reset_2\"},"
                + "{\"text\":\"Third\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"}]}" );
        WynnDialogueProjection fixedRowTasimProjection =
                WynnDialogueProjection.project(fixedRowTasim);
        assertNotNull(fixedRowTasimProjection,
                "hidden row-reset glyphs preserve a complete three-row BODY projection");
        String fixedRowTasimText = "那个守卫没开玩笑。这个地方 到处都是 腐败的家伙。";
        WynnDialogueRenderPlan fixedRowTasimPlan = fixedRowTasimProjection.bindTranslations(
                List.of(componentBodyTranslation(fixedRowTasimProjection,
                        "那个守卫没开玩笑。这个地方", " 到处都是", " 腐败的家伙。")),
                List.of());
        assertNotNull(fixedRowTasimPlan,
                "fragmented Tasim response binds to the fixed-visual paragraph renderer");
        WynnDialogueRenderPlan.Layout fixedRowTasimLayout =
                fixedRowTasimPlan.resolveLayout(dialogueFixtureFont());
        assertNull(fixedRowTasimLayout,
                "translation never extrapolates unobserved BODY rows from neighbouring source geometry");

        // Replay is an all-or-nothing preflight. A valid portrait precedes the
        // masked BODY, while a later frame font reports NaN advance. The live
        // Wynn pack also inserts visible body/effect glyphs around translated
        // prose; those keep their advances but must never be replayed over CJK.
        // A later failure still rejects the complete run list before drawing.
        FontDescription nonFiniteReplayFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("simple_translate", "fixture/non_finite_replay"));
        Component replayPreflightSource = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE500\",\"font\":\"minecraft:hud/dialogue/portrait/preflight\"},"
                + "{\"text\":\"Replay this body\\uE003\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE321\",\"font\":\"minecraft:hud/dialogue/effect/progress\"},"
                + "{\"text\":\"F\",\"font\":\"simple_translate:fixture/non_finite_replay\"}]}" );
        WynnDialogueProjection replayPreflightProjection =
                WynnDialogueProjection.project(replayPreflightSource);
        assertNotNull(replayPreflightProjection,
                "replay preflight fixture remains a recognized Wynn BODY");
        WynnDialogueRenderPlan replayPreflightPlan = replayPreflightProjection.bindTranslations(
                List.of(componentBodyTranslation(replayPreflightProjection, "预检译文")), List.of());
        assertNotNull(replayPreflightPlan,
                "replay preflight fixture binds a translated BODY plan");
        Method prepareUnmaskedSourceRuns = WynnDialogueRenderPlan.class.getDeclaredMethod(
                "prepareUnmaskedSourceRuns", Font.class, java.util.BitSet.class);
        prepareUnmaskedSourceRuns.setAccessible(true);
        Font finiteReplayFixtureFont = dialogueFixtureFont();
        WynnDialogueRenderPlan.Layout finiteReplayLayout =
                replayPreflightPlan.resolveLayout(finiteReplayFixtureFont);
        assertNotNull(finiteReplayLayout,
                "finite replay fixture resolves before source-run preflight");
        List<?> finiteReplayRuns = (List<?>) prepareUnmaskedSourceRuns.invoke(
                replayPreflightPlan, finiteReplayFixtureFont, finiteReplayLayout.acceptedMask());
        assertEquals(2, finiteReplayRuns.size(),
                "finite portrait and trailing frame are precomputed as two complete replay runs");
        List<WynnVisualGlyph> finiteReplayGlyphs = new ArrayList<>();
        for (Object replayRun : finiteReplayRuns) {
            Method replaySequence = replayRun.getClass().getDeclaredMethod("sequence");
            replaySequence.setAccessible(true);
            finiteReplayGlyphs.addAll(wynnVisualGlyphs(
                    (FormattedCharSequence) replaySequence.invoke(replayRun)));
        }
        assertEquals(finiteReplayLayout.acceptedMask().cardinality(),
                replayPreflightProjection.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .flatMap(slot -> slot.regions().stream())
                        .flatMap(region -> region.maskOrdinals().stream())
                        .mapToInt(ignored -> 1).sum(),
                "the exact semantic BODY ordinals alone form the accepted source mask");
        assertTrue(finiteReplayGlyphs.stream().anyMatch(glyph ->
                        fixtureFontId(glyph.style()).startsWith("minecraft:hud/dialogue/effect/")),
                "source-owned dialogue progress and fade glyphs remain in the replay stream");
        assertTrue(finiteReplayGlyphs.stream().anyMatch(glyph ->
                        fixtureFontId(glyph.style()).startsWith("minecraft:hud/dialogue/portrait/")),
                "dialogue portrait chrome remains on the original source replay");
        Font unsafeReplayFixtureFont = dialogueFixtureFont(nonFiniteReplayFont);
        WynnDialogueRenderPlan.Layout unsafeReplayLayout =
                replayPreflightPlan.resolveLayout(unsafeReplayFixtureFont);
        assertNotNull(unsafeReplayLayout,
                "a trailing non-finite replay event does not invalidate otherwise measurable BODY geometry");
        assertNull(prepareUnmaskedSourceRuns.invoke(
                        replayPreflightPlan, unsafeReplayFixtureFont, unsafeReplayLayout.acceptedMask()),
                "a later non-finite frame rejects all replay runs before the first GuiGraphics draw");

        // BODY_3 and BODY_4 contain only visual events, so projection correctly
        // emits no semantic LineRegion for either row. They are still replayed;
        // synthesized translation rows must therefore measure and avoid them.
        Component futureVisualRows = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"ABCDEFGHIJ\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE100\",\"font\":\"minecraft:hud/dialogue/text/layout/reset_1\"},"
                + "{\"text\":\"KLMNOPQRST\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uE101\",\"font\":\"minecraft:hud/dialogue/text/layout/reset_2\"},"
                + "{\"text\":\"UVWXYZABCD\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\"\\uE100\",\"font\":\"minecraft:hud/dialogue/text/layout/reset_1\"},"
                + "{\"text\":\"\\uE003\\uE102\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_3\"},"
                + "{\"text\":\"\\uE003\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_4\"}]}" );
        WynnDialogueProjection futureVisualProjection =
                WynnDialogueProjection.project(futureVisualRows);
        assertNotNull(futureVisualProjection,
                "visual-only future BODY rows retain the dedicated Wynn projection");
        WynnDialogueProjection.SemanticSlot futureVisualBody =
                futureVisualProjection.contentSlots().getFirst();
        assertTrue(futureVisualBody.regions().stream().noneMatch(region ->
                        region.line() == WynnDialogueProjection.DialogueLine.BODY_3
                                || region.line() == WynnDialogueProjection.DialogueLine.BODY_4),
                "visual-only BODY_3/BODY_4 events do not become semantic prose regions");
        String futureVisualTranslation = "甲".repeat(29);
        WynnDialogueRenderPlan futureVisualPlan = futureVisualProjection.bindTranslations(
                List.of(componentBodyTranslation(futureVisualProjection,
                        "甲".repeat(10), "甲".repeat(10), "甲".repeat(9))), List.of());
        assertNotNull(futureVisualPlan,
                "visual-only future rows bind one complete translated paragraph");
        WynnDialogueRenderPlan.Layout futureVisualLayout =
                futureVisualPlan.resolveLayout(dialogueFixtureFont());
        assertNull(futureVisualLayout,
                "translation cannot borrow visual-only future rows or reflow outside proven BODY capacity");

        // The real Wynn BODY fonts reference minecraft:space directly. Its PUA
        // glyphs have advances but no pixels and may sit between prose islands
        // on the same physical row. They are positioning instructions, not icon
        // obstacles, so one short translation must remain one render run.
        Component inlineSpaceTasim = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"First part\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE100\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Second part\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}" );
        WynnDialogueProjection inlineSpaceProjection =
                WynnDialogueProjection.project(inlineSpaceTasim);
        assertNotNull(inlineSpaceProjection,
                "same-font invisible Wynn positioning PUA remains projectable");
        WynnDialogueRenderPlan inlineSpacePlan = inlineSpaceProjection.bindTranslations(
                List.of(componentBodyTranslation(inlineSpaceProjection,
                        "那个守卫没开", "玩笑。")), List.of());
        assertNotNull(inlineSpacePlan,
                "same-font invisible Wynn positioning PUA translation binds");
        WynnDialogueRenderPlan.Layout inlineSpaceLayout =
                inlineSpacePlan.resolveLayout(dialogueFixtureFont());
        assertNotNull(inlineSpaceLayout,
                "invisible same-row positioning PUA remains source-owned while one measured CJK row overlays prose");
        assertEquals(1L, inlineSpaceLayout.lines().stream()
                        .filter(line -> line.kind() == WynnDialogueProjection.SemanticKind.BODY).count(),
                "invisible positioning PUA cannot create a second translated overlay on one physical row");

        // Fixed-icon regression: the merchant glyph keeps its original x range.
        // BODY rendering deliberately chooses one contiguous side of the icon;
        // it never emits two draw calls on the same physical row.
        Component fixedMerchant = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Before \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE003\",\"font\":\"minecraft:hud/dialogue/text/merchant/body_0\"},"
                + "{\"text\":\" After\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}" );
        WynnDialogueProjection fixedMerchantProjection = WynnDialogueProjection.project(fixedMerchant);
        assertNotNull(fixedMerchantProjection, "fixed merchant glyph dialogue is recognized");
        WynnDialogueRenderPlan fixedMerchantPlan = fixedMerchantProjection.bindTranslations(
                List.of(componentBodyTranslation(fixedMerchantProjection, "固定", "图标")),
                List.of());
        assertNotNull(fixedMerchantPlan, "fixed merchant glyph translation binds");
        WynnDialogueRenderPlan.Layout fixedMerchantLayout =
                fixedMerchantPlan.resolveLayout(dialogueFixtureFont());
        assertNotNull(fixedMerchantLayout, "fixed merchant glyph translation resolves");
        List<WynnDialogueRenderPlan.PositionedLine> fixedMerchantLines =
                fixedMerchantLayout.lines().stream()
                        .filter(line -> line.kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .toList();
        assertEquals(1, fixedMerchantLines.size(),
                "one literal BODY response produces at most one translated draw on a physical row");
        assertEquals("固定图标", fixedMerchantLines.stream()
                        .map(line -> formattedSequenceText(line.text()))
                        .collect(java.util.stream.Collectors.joining()),
                "the complete short translation is drawn once beside the fixed icon");
        assertTrue(fixedMerchantLines.stream().allMatch(line ->
                        line.x() + line.translatedWidth() <= 42.0F
                                || line.x() >= 54.0F),
                "the one permitted translated row stays entirely on one side of the merchant icon");
        List<WynnVisualGlyph> fixedMerchantGlyphs =
                wynnVisualGlyphs(fixedMerchantProjection.sourceSequence());
        for (int ordinal = 0; ordinal < fixedMerchantGlyphs.size(); ordinal++) {
            if (fixedMerchantGlyphs.get(ordinal).codePoint() == 0xE003) {
                assertTrue(!fixedMerchantLayout.acceptedMask().get(ordinal),
                        "merchant icon ordinal remains outside the translated prose mask");
            }
        }
        List<?> fixedMerchantReplayRuns = (List<?>) prepareUnmaskedSourceRuns.invoke(
                fixedMerchantPlan, finiteReplayFixtureFont, fixedMerchantLayout.acceptedMask());
        List<WynnVisualGlyph> fixedMerchantReplayGlyphs = new ArrayList<>();
        for (Object replayRun : fixedMerchantReplayRuns) {
            Method replaySequence = replayRun.getClass().getDeclaredMethod("sequence");
            replaySequence.setAccessible(true);
            fixedMerchantReplayGlyphs.addAll(wynnVisualGlyphs(
                    (FormattedCharSequence) replaySequence.invoke(replayRun)));
        }
        assertEquals(1L, fixedMerchantReplayGlyphs.stream()
                        .filter(glyph -> glyph.codePoint() == 0xE003).count(),
                "merchant icon is replayed exactly once beside the translated prose");

        // Real Guard regression: Wynn places the merchant service icon between
        // the two natural-language fragments. The icon is local-only; the
        // dedicated projection must not fail closed into generic PUA HUD
        // translation or send the private-use code point to the model.
        Component guardMerchantDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Guard\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"You can identify items at \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE003\",\"font\":\"minecraft:hud/dialogue/text/merchant/body_0\"},"
                + "{\"text\":\" Item Identifier.\",\"color\":\"light_purple\","
                + "\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"They are found in towns across the whole\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"world, but we have one here for recruits\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\"such as yourselves.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_3\"},"
                + "{\"text\":\"to continue\",\"font\":\"minecraft:hud/dialogue/text/control\"}]}");
        WynnDialogueProjection guardMerchantProjection =
                WynnDialogueProjection.project(guardMerchantDialogue);
        assertNotNull(guardMerchantProjection,
                "merchant icon in a Guard dialogue remains on the dedicated semantic path");
        assertEquals(3, guardMerchantProjection.contentComponents().size(),
                "merchant dialogue exposes name, colour-varied body and control slots");
        WynnDialogueProjection.SemanticSlot guardBody =
                guardMerchantProjection.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .findFirst().orElseThrow();
        assertEquals(5, guardBody.bodyAnchors().size(),
                "merchant-separated light-purple keyword keeps its own anchors beside the icon");
        assertTrue(guardMerchantProjection.contentComponents().stream().noneMatch(component ->
                        component.getString().codePoints().anyMatch(codePoint -> codePoint == 0xE003)),
                "merchant icon PUA never enters a semantic request array");
        Component guardBodyResponse = Component.empty()
                .append(Component.literal("你可以在"))
                .append(Component.literal("物品鉴定师").withStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF)).withShadowColor(1073741824)))
                .append(Component.literal("处鉴定物品。它们遍布世界各地的城镇，"
                        + "但我们这里就有一个，专门为你们这些新兵服务。"));
        WynnDialogueRenderPlan guardMerchantPlan = guardMerchantProjection.bindTranslations(
                List.of(Component.literal("守卫"), guardBodyResponse, Component.literal("继续")),
                List.of());
        assertNotNull(guardMerchantPlan,
                "merchant-separated coloured BODY binds beside name and control translations");

        List<WynnVisualGlyph> guardGlyphs = wynnVisualGlyphs(guardMerchantProjection.sourceSequence());
        int foundMerchantIconOrdinal = -1;
        for (int ordinal = 0; ordinal < guardGlyphs.size(); ordinal++) {
            if (guardGlyphs.get(ordinal).codePoint() == 0xE003) {
                foundMerchantIconOrdinal = ordinal;
                break;
            }
        }
        assertTrue(foundMerchantIconOrdinal >= 0, "captured merchant icon remains in the source glyph stream");
        final int merchantIconOrdinal = foundMerchantIconOrdinal;
        assertTrue(guardMerchantPlan.translatedSlots().stream().noneMatch(slot ->
                        slot.bodyMaskOrdinals().contains(merchantIconOrdinal)),
                "a strict BODY fallback never masks the fixed merchant icon");
        List<WynnDialogueProjection.DockedIcon> guardDocks =
                guardMerchantPlan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .findFirst().orElseThrow().dockedIcons();
        assertEquals(List.of(new WynnDialogueProjection.DockedIcon(merchantIconOrdinal, 4)),
                guardDocks,
                "merchant icon docks into the translated flow directly before its keyword span");

        // Exact live 2026-07-19 Guard helmet frame: the light-purple keyword
        // wraps across two physical rows as two same-appearance runs. The
        // merchant icon must still dock before the first keyword span instead
        // of stranding at its long-prefix source x.
        Component guardHelmetDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Guard\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Identify your helmet at the \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE003\",\"font\":\"minecraft:hud/dialogue/text/merchant/body_0\"},"
                + "{\"text\":\"Item\",\"color\":\"light_purple\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Identifier\",\"color\":\"light_purple\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\" and then \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"return to me with it\",\"color\":\"aqua\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\" equipped\",\"color\":\"aqua\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\". After you've done that I'll let you\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\"pass.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_3\"},"
                + "{\"text\":\"to continue\",\"font\":\"minecraft:hud/dialogue/text/control\"}]}");
        WynnDialogueProjection guardHelmetProjection =
                WynnDialogueProjection.project(guardHelmetDialogue);
        assertNotNull(guardHelmetProjection,
                "helmet dialogue with a row-wrapped keyword is recognized");
        Component guardHelmetResponse = Component.empty()
                .append(Component.literal("在"))
                .append(Component.literal("物品鉴定师").withStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF))))
                .append(Component.literal("处鉴定你的头盔，然后"))
                .append(Component.literal("装备好它回来找我").withStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))))
                .append(Component.literal("。完成之后我就让你过去。"));
        WynnDialogueRenderPlan guardHelmetPlan = guardHelmetProjection.bindTranslations(
                List.of(Component.literal("守卫"), guardHelmetResponse, Component.literal("继续")),
                List.of());
        assertNotNull(guardHelmetPlan, "row-wrapped keyword BODY binds");
        int helmetIconOrdinal = guardHelmetProjection.contentSlots().stream()
                .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow().regions().stream()
                .flatMap(region -> region.visualBeforeOrdinals().stream())
                .findFirst().orElseThrow();
        List<WynnDialogueProjection.DockedIcon> helmetDocks =
                guardHelmetPlan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .findFirst().orElseThrow().dockedIcons();
        assertEquals(List.of(new WynnDialogueProjection.DockedIcon(helmetIconOrdinal, 1)),
                helmetDocks,
                "a keyword wrapped across two same-colour rows still docks its merchant icon");

        Component layeredMerchantDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Visit \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uE003\\uE04A\",\"font\":\"minecraft:hud/dialogue/text/merchant/body_0\"},"
                + "{\"text\":\" Item Identifier.\",\"color\":\"light_purple\","
                + "\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}" );
        WynnDialogueProjection layeredMerchantProjection =
                WynnDialogueProjection.project(layeredMerchantDialogue);
        assertNotNull(layeredMerchantProjection,
                "colour-varied merchant BODY still projects without name/control slots");
        WynnDialogueRenderPlan layeredMerchantPlan = layeredMerchantProjection.bindTranslations(
                List.of(componentBodyTranslation(layeredMerchantProjection,
                        "去", "物品鉴定师那里。")), List.of());
        assertNotNull(layeredMerchantPlan,
                "colour-varied merchant BODY binds its translated overlay");

        float rightAligned = WynnDialogueRenderPlan.alignedDrawX(20.0F, 50.0F, 3.0F,
                30.0F, true);
        assertFloatEquals(20.0F, rightAligned + 3.0F,
                "a fixed icon never right-aligns a shorter translation into a large leading indent");
        float leftAligned = WynnDialogueRenderPlan.alignedDrawX(20.0F, 50.0F, 3.0F,
                30.0F, false);
        assertFloatEquals(20.0F, leftAligned + 3.0F,
                "ordinary and post-icon translations keep their original left edge");
        assertInlineDialogueGlyph("merchant/body_0", 0xE003, false,
                "captured merchant service icon");
        assertInlineDialogueGlyph("merchant/body_0", 0xE04A, false,
                "another BMP private-use merchant icon");
        assertInlineDialogueGlyph("merchant/body_0", 0xF0001, false,
                "supplementary private-use merchant icon");
        assertInlineDialogueGlyph("wynncraft/body_0", 0xCFFC9, false,
                "known dialogue-family supplementary positioning/icon glyph");
        assertInlineDialogueGlyph("currency/body_0", 0xE090, false,
                "protected glyph carried by a known technical body font");
        assertInlineDialogueGlyph("merchant/choice_0", 0xE091, true,
                "protected glyph carried by a known choice font");
        assertInlineDialogueGlyph("future_family/body_0", 0xE092, false,
                "a protected glyph in a future dialogue family");
        WynnDialogueProjection futureProseProjection = WynnDialogueProjection.project(
                componentFromJson("{\"text\":\"Translate future dialogue\",\"font\":"
                        + "\"minecraft:hud/dialogue/text/future_family/body_0\"}"));
        assertNotNull(futureProseProjection,
                "a future dialogue family can become the row's structural prose carrier without an allowlist");
        assertEquals("Translate future dialogue", futureProseProjection.contentSlots().getFirst().sourceText(),
                "natural-language text in a future dominant prose font reaches the semantic request");

        // Captured structure of Wynn's no-speaker quest-start notification.
        // The visible sentence deliberately switches gold/default/yellow Style
        // runs inside one body row and has no nameplate. This used to reject
        // the dedicated projection and leak every typewriter prefix into the
        // generic actionbar translator.
        Component styledQuestStarted = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uDAFF\\uDF80\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/style/reserved/box\"},"
                + "{\"text\":\"\\uDAFF\\uDFD7\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uE000 to continue\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uDAFF\\uDFD6\",\"shadow_color\":16777215,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uDAFF\\uDF8C\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"New Quest Started:\",\"color\":\"gold\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"King's Recruit\",\"color\":\"yellow\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDFF6\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}"
        );
        WynnDialogueProjection styledQuest =
                WynnDialogueProjection.project(styledQuestStarted);
        assertNotNull(styledQuest,
                "a no-name multi-style quest frame retains its independent control slot");
        assertTrue(styledQuest.contentSlots().stream().noneMatch(slot ->
                        slot.kind() == WynnDialogueProjection.SemanticKind.BODY),
                "the multi-style quest BODY itself remains source-owned");
        Component abilityTutorial = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Unlock your first \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Ability\",\"color\":\"aqua\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" by using your \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Compass \",\"color\":\"aqua\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"and opening the \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Ability Tree\",\"color\":\"aqua\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" on the left.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}");
        WynnDialogueProjection abilityTutorialProjection =
                WynnDialogueProjection.project(abilityTutorial);
        assertNotNull(abilityTutorialProjection,
                "alternating aqua and default BODY prose stays one translatable paragraph");
        WynnDialogueRenderPlan abilityTutorialPlan = abilityTutorialProjection.bindTranslations(
                List.of(componentBodyTranslation(abilityTutorialProjection,
                        "使用", "能力", "，打开你的", "指南针", "并点击左侧的", "能力树", "来解锁第一个。")),
                List.of());
        assertNotNull(abilityTutorialPlan,
                "alternating colour-only BODY binds its translated overlay");

        // Captured 26.1 Arrow Bomb tutorial grammar. Unlike the simpler Ability
        // tutorial above, the instruction crosses three physical BODY rows and
        // carries three keybind-font mouse glyphs on body_1. The first mouse is
        // leading chrome on its physical row, but semantically it belongs
        // between "clicking" on body_0 and "LEFT" on body_1. It must therefore
        // be carried by the translated LEFT anchor instead of being lost merely
        // because the preceding prose lives on another physical row.
        Component arrowBombTutorial = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uDAFF\\uDF8C\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDB00\\uDC16Cast your \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Arrow Bomb Spell\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" by clicking\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDF2D\\uDB00\\uDC23\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF000\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"LEFT - \",\"color\":\"light_purple\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF001\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"RIGHT - \",\"color\":\"light_purple\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF001\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"RIGHT\",\"color\":\"light_purple\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"while holding your \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"},"
                + "{\"text\":\"Bow\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_2\"}]}" );
        WynnDialogueProjection arrowBombProjection =
                WynnDialogueProjection.project(arrowBombTutorial);
        assertNull(arrowBombProjection,
                "multi-style Arrow Bomb BODY remains source-owned even with fixed keybind visuals");

        // Real typewriter frames frequently stop immediately after the next
        // mouse glyph. A trailing technical token plus an ordinary spacing run
        // is still a valid semantic prefix; it must not make the whole Wynn
        // structure disappear and fall through to generic actionbar caching.
        Component arrowBombTrailingIconPrefix = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uDAFF\\uDF8C\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDB00\\uDC16Cast your \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"Arrow Bomb Spell\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" by clicking\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDF2D\\uDB00\\uDC23\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF000\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"LEFT - \",\"color\":\"light_purple\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF001\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"RIGHT - \",\"color\":\"light_purple\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uF001\",\"color\":\"#00EB2C\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_1\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"}]}" );
        WynnDialogueProjection arrowBombPrefixProjection =
                WynnDialogueProjection.project(arrowBombTrailingIconPrefix);
        assertNull(arrowBombPrefixProjection,
                "a multi-style typewriter prefix remains source-owned until or unless its BODY becomes uniform");

        // Future resource packs are allowed to introduce an ordinary Unicode
        // symbol, variation selector, or a new visual font family. Classification
        // is structural: a foreign visual run between two prose regions is local
        // chrome, regardless of its exact code point or family name.
        Component futureSymbolDialogue = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Press \",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                + "{\"text\":\"\\uD83D\\uDDB1\\uFE0F\",\"color\":\"green\","
                + "\"font\":\"minecraft:hud/dialogue/text/future_input/body_0\"},"
                + "{\"text\":\" to continue\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"}]}" );
        WynnDialogueProjection futureSymbolProjection =
                WynnDialogueProjection.project(futureSymbolDialogue);
        assertNotNull(futureSymbolProjection,
                "an unknown visual font with a non-PUA symbol and FORMAT selector is classified structurally");
        assertEquals("Press to continue", futureSymbolProjection.contentSlots().getFirst().sourceText(),
                "future visual tokens stay local while adjacent prose remains translatable");
        String futureSymbolRequest = JsonPassthroughPipeline.serializeComponents(
                futureSymbolProjection.contentComponents());
        assertTrue(futureSymbolProjection.contentComponents().stream().noneMatch(component ->
                        component.getString().codePoints().anyMatch(codePoint ->
                                codePoint == 0x1F5B1 || codePoint == 0xFE0F))
                        && !futureSymbolRequest.contains("🖱") && !futureSymbolRequest.contains("\\uD83D")
                        && !futureSymbolRequest.contains("\\uFE0F")
                        && !futureSymbolRequest.contains("future_input"),
                "unknown non-PUA visuals and FORMAT selectors never enter either translation wire mode");
        WynnDialogueRenderPlan futureSymbolPlan = futureSymbolProjection.bindTranslations(
                List.of(componentBodyTranslation(futureSymbolProjection, "按下", "即可继续")), List.of());
        assertNotNull(futureSymbolPlan,
                "unknown visual-font symbols bind through the fixed-visual BODY pipeline");
        WynnDialogueRenderPlan.TranslatedSlot futureSymbolBody =
                futureSymbolPlan.translatedSlots().getFirst();
        assertEquals("按下即可继续", futureSymbolBody.component().getString(),
                "future visual binding retains all translated prose");
        List<Integer> futureVisualOrdinals = futureSymbolProjection.contentSlots().getFirst()
                .regions().stream()
                .flatMap(region -> java.util.stream.Stream.concat(
                        region.visualBeforeOrdinals().stream(), region.visualAfterOrdinals().stream()))
                .distinct().toList();
        List<WynnVisualGlyph> futureSymbolGlyphs = wynnVisualGlyphs(
                futureSymbolProjection.sourceSequence());
        assertEquals(List.of(0x1F5B1, 0xFE0F), futureVisualOrdinals.stream()
                        .map(ordinal -> futureSymbolGlyphs.get(ordinal).codePoint()).toList(),
                "the complete non-PUA symbol cluster remains fixed without a code-point allowlist");

        Component styledQuestPrefix = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uDAFF\\uDF80\",\"font\":\"minecraft:hud/dialogue/style/reserved/box\"},"
                + "{\"text\":\"\\uE000 to continue\",\"shadow_color\":1073741824,\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"\\uDAFF\\uDF8C\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"New Quest Started:\",\"color\":\"gold\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\" \",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"King\",\"color\":\"yellow\",\"bold\":true,\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"\\uDAFF\\uDFF6\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}"
        );
        WynnDialogueProjection styledPrefixProjection =
                WynnDialogueProjection.project(styledQuestPrefix);
        assertNotNull(styledPrefixProjection,
                "a multi-style quest prefix retains its independent control slot");
        assertTrue(styledPrefixProjection.contentSlots().stream().noneMatch(slot ->
                        slot.kind() == WynnDialogueProjection.SemanticKind.BODY),
                "the multi-style quest prefix BODY remains source-owned");

        Component leadingKeybind = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"KEY\",\"font\":\"minecraft:hud/dialogue/text/keybind/body_0\"},"
                + "{\"text\":\" Open the journal\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"}]}"
        );
        WynnDialogueProjection leadingKeybindProjection =
                WynnDialogueProjection.project(leadingKeybind);
        assertNotNull(leadingKeybindProjection,
                "a leading keybind token stays local without rejecting adjacent dialogue prose");
        assertEquals("Open the journal", leadingKeybindProjection.contentSlots().get(0).sourceText(),
                "the keybind token never enters either translation wire mode");

        for (String technicalFamily : List.of("keybind", "currency")) {
            Component mixedTechnicalLine = componentFromJson("{\"text\":\"\",\"extra\":["
                    + "{\"text\":\"Speaker\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                    + "{\"text\":\"Use \",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"},"
                    + "{\"text\":\"TECHNICAL_TOKEN\",\"font\":\"minecraft:hud/dialogue/text/"
                    + technicalFamily + "/body_0\"},"
                    + "{\"text\":\" now\",\"font\":\"minecraft:hud/dialogue/text/common/body_0\"}]}");
            WynnDialogueProjection technicalProjection =
                    WynnDialogueProjection.project(mixedTechnicalLine);
            assertNotNull(technicalProjection,
                    technicalFamily + " visual alphabets stay local without rejecting adjacent prose");
            assertEquals("Use now", technicalProjection.contentSlots().stream()
                            .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                            .findFirst().orElseThrow().sourceText(),
                    technicalFamily + " technical glyph text is excluded from semantic prose");
            String technicalRequest = JsonPassthroughPipeline.serializeComponents(
                    technicalProjection.contentComponents());
            assertTrue(!technicalRequest.contains("TECHNICAL_TOKEN")
                            && !technicalRequest.contains("/" + technicalFamily + "/body_0"),
                    technicalFamily + " technical runs never enter either wire mode");
            WynnDialogueRenderPlan technicalPlan = technicalProjection.bindTranslations(
                    List.of(Component.literal("Speaker"),
                            componentBodyTranslation(technicalProjection, "现在使用", "。")), List.of());
            assertNotNull(technicalPlan,
                    technicalFamily + " opaque visual cluster binds through generic local-token metadata");
        }
        Component unknownFamily = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Speaker\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Do not partially overlay me\",\"font\":"
                + "\"minecraft:hud/dialogue/text/future_family/body_0\"}]}");
        WynnDialogueProjection unknownFamilyProjection =
                WynnDialogueProjection.project(unknownFamily);
        assertNotNull(unknownFamilyProjection,
                "an unknown natural-language family becomes the row prose carrier without a family allowlist");
        assertEquals("Do not partially overlay me", unknownFamilyProjection.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                        .findFirst().orElseThrow().sourceText(),
                "future natural-language family text reaches the semantic request as a complete BODY");

        Component complete = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE500\",\"font\":\"minecraft:hud/dialogue/portrait/therck\"},"
                + "{\"text\":\"Therck\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Go on, I don't have time for you.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"},"
                + "{\"text\":\"My brother keeps sending me people all the time.\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_1\"},"
                + "{\"text\":\"\\uE001 to continue\",\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"Ask about the brother\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/choice_0\"},"
                + "{\"text\":\"Leave\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/choice_1\"},"
                + "{\"text\":\"\\uE501\",\"font\":\"minecraft:hud/dialogue/style/frame\"}]}");
        WynnDialogueProjection dialogue = WynnDialogueProjection.project(complete);
        assertNotNull(dialogue, "exact Wynn dialogue font grammar is recognized");
        assertEquals(3, dialogue.contentComponents().size(), "name, joined body and control are content slots");
        assertEquals(2, dialogue.optionComponents().size(), "all already-delivered dialogue choices are pretranslated");
        assertEquals(WynnDialogueProjection.OptionVisibility.PRELOADED_HIDDEN,
                dialogue.optionVisibility(),
                "preloaded choices stay hidden while the exact choice chrome is absent");
        assertEquals("Therck", dialogue.contentComponents().get(0).getString(), "dialogue name slot");
        assertEquals("Go on, I don't have time for you. My brother keeps sending me people all the time.",
                dialogue.contentSlots().get(1).sourceText(), "body_0..4 rows form one semantic paragraph");
        assertEquals("to continue", dialogue.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.CONTROL)
                        .findFirst().orElseThrow().sourceText(),
                "the protected continuation keycap is excluded from CONTROL prose");
        List<WynnVisualGlyph> continuationGlyphs = wynnVisualGlyphs(dialogue.sourceSequence());
        int continuationKeycapOrdinal = -1;
        for (int ordinal = 0; ordinal < continuationGlyphs.size(); ordinal++) {
            if (continuationGlyphs.get(ordinal).codePoint() == 0xE001) {
                continuationKeycapOrdinal = ordinal;
                break;
            }
        }
        assertTrue(continuationKeycapOrdinal >= 0,
                "captured U+E001 continuation keycap remains in the source stream");
        final int continuationKeycap = continuationKeycapOrdinal;
        assertTrue(dialogue.contentSlots().stream()
                        .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.CONTROL)
                        .flatMap(slot -> slot.regions().stream())
                        .flatMap(region -> region.maskOrdinals().stream())
                        .noneMatch(ordinal -> ordinal == continuationKeycap),
                "the continuation keycap never enters the CONTROL prose mask");
        assertSemanticComponentArray(dialogue.contentComponents(), "Wynn dialogue content");
        assertSemanticComponentArray(dialogue.optionComponents(), "Wynn dialogue options");
        String requests = JsonPassthroughPipeline.serializeComponents(dialogue.contentComponents())
                + JsonPassthroughPipeline.serializeComponents(dialogue.optionComponents());
        assertTrue(!requests.contains("hud/dialogue") && !requests.contains("\uE500")
                        && !requests.contains("\uE501"),
                "portrait, frame, PUA and positioning fonts never enter dialogue requests");
        assertTrue(requests.contains("minecraft:default"),
                "dialogue semantic requests force a CJK-capable default font");

        WynnDialogueRenderPlan plan = dialogue.bindTranslations(
                List.of(Component.literal("特尔克"),
                        componentBodyTranslation(dialogue, "继续走，我没时间理你。",
                                "我兄弟总是叫人来。"),
                        Component.literal("按 SHIFT 继续")),
                List.of(Component.literal("询问他的兄弟"), Component.literal("离开")));
        assertNotNull(plan, "safe complete dialogue translations bind to one render plan");
        assertEquals(5, plan.translatedSlots().size(), "content and preloaded options share one verified overlay plan");
        assertTrue(plan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.OPTION)
                        .noneMatch(WynnDialogueRenderPlan.TranslatedSlot::sourceVisible),
                "pretranslated options are cached but never receive a premature default-font overlay");
        assertTrue(plan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind() != WynnDialogueProjection.SemanticKind.OPTION)
                        .allMatch(WynnDialogueRenderPlan.TranslatedSlot::sourceVisible),
                "visible name, body and control slots remain eligible for the verified overlay");
        assertTrue(!wynnVisualGlyphs(dialogue.sourceSequence()).isEmpty(),
                "dialogue render plan retains the exact source stream for measured frame replay");
        assertNull(dialogue.bindTranslations(
                        List.of(Component.literal("特尔克\uE000"), Component.literal("正文"), Component.literal("继续")),
                        List.of(Component.literal("选项一"), Component.literal("选项二"))),
                "PUA injection rejects the complete dialogue overlay");
        assertNull(dialogue.bindTranslations(
                        List.of(Component.literal("特尔克\u200D"), Component.literal("正文"), Component.literal("继续")),
                        List.of(Component.literal("选项一"), Component.literal("选项二"))),
                "Unicode format injection rejects the complete dialogue overlay");
        WynnDialogueRenderPlan unchangedNamePlan = dialogue.bindTranslations(
                List.of(Component.literal("Therck"), componentBodyTranslation(dialogue, "正文上", "正文下"),
                        Component.literal("继续")),
                List.of(Component.literal("选项一"), Component.literal("选项二")));
        assertNotNull(unchangedNamePlan,
                "an unchanged proper name stays on the source stream without discarding translated prose");
        assertEquals(4, unchangedNamePlan.translatedSlots().size(),
                "only the unchanged name is omitted from the translated overlay plan");
        assertTrue(unchangedNamePlan.translatedSlots().stream().noneMatch(slot ->
                        slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME),
                "an unchanged NPC name keeps its original Wynn font and is never masked");

        WynnDialogueRenderPlan badNamePlan = dialogue.bindTranslations(
                List.of(Component.literal("守卫处鉴定物品"),
                        componentBodyTranslation(dialogue, "继续走，我没时间理你。",
                                "我兄弟总是叫人来。"),
                        Component.literal("继续")),
                List.of(Component.literal("询问他的兄弟"), Component.literal("离开")));
        assertNotNull(badNamePlan, "an overlong translated name still binds sibling dialogue slots");
        WynnDialogueRenderPlan.TranslatedSlot badName = badNamePlan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.NAME)
                .findFirst().orElseThrow();
        WynnDialogueRenderPlan.TranslatedSlot goodBody = badNamePlan.translatedSlots().stream()
                .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        Method commitMeasuredSlot = WynnDialogueRenderPlan.class.getDeclaredMethod(
                "commitMeasuredSlot", List.class, java.util.BitSet.class,
                WynnDialogueRenderPlan.TranslatedSlot.class, List.class);
        commitMeasuredSlot.setAccessible(true);
        List<WynnDialogueRenderPlan.PositionedLine> committedLines = new ArrayList<>();
        java.util.BitSet committedMask = new java.util.BitSet();
        assertTrue(!(Boolean) commitMeasuredSlot.invoke(badNamePlan, committedLines, committedMask,
                        badName, null),
                "a failed NAME measurement commits neither overlay pixels nor a mask");
        assertTrue(committedMask.isEmpty() && committedLines.isEmpty(),
                "a failed NAME slot leaves its exact Wynn source glyphs visible");
        FormattedCharSequence measuredBodyText = FormattedCharSequence.forward("正文", Style.EMPTY);
        WynnDialogueRenderPlan.PositionedLine measuredBody =
                new WynnDialogueRenderPlan.PositionedLine(
                        WynnDialogueProjection.SemanticKind.BODY, 0, measuredBodyText,
                        0.0F, 0.0F, 24.0F, 12.0F);
        assertTrue((Boolean) commitMeasuredSlot.invoke(badNamePlan, committedLines, committedMask,
                        goodBody, List.of(measuredBody)),
                "a completely measured BODY slot commits after a failed NAME sibling");
        assertEquals(1, committedLines.size(),
                "only the fully measured sibling slot contributes translated overlay pixels");
        assertTrue(!committedMask.isEmpty(),
                "only the fully measured sibling slot contributes source-glyph masking");
        java.util.BitSet committedMaskBeforeDuplicate =
                (java.util.BitSet) committedMask.clone();
        assertTrue(!(Boolean) commitMeasuredSlot.invoke(badNamePlan, committedLines, committedMask,
                        goodBody, List.of(measuredBody)),
                "one Wynn source glyph range cannot acquire a second translated overlay owner");
        assertEquals(1, committedLines.size(),
                "a duplicate semantic owner never appends stacked translated pixels");
        assertEquals(committedMaskBeforeDuplicate, committedMask,
                "a rejected duplicate owner cannot mutate the accepted source mask");

        List<WynnDialogueRenderPlan.PositionedLine> noAcceptedLines = new ArrayList<>();
        java.util.BitSet noAcceptedMask = new java.util.BitSet();
        for (WynnDialogueRenderPlan.TranslatedSlot slot : badNamePlan.translatedSlots()) {
            assertTrue(!(Boolean) commitMeasuredSlot.invoke(badNamePlan, noAcceptedLines, noAcceptedMask,
                            slot, List.of()),
                    "an unmeasurable semantic slot never commits a partial overlay");
        }
        assertTrue(noAcceptedMask.isEmpty() && noAcceptedLines.isEmpty(),
                "zero accepted slots retain the complete original Wynn frame");
        assertNull(dialogue.bindTranslations(
                        dialogue.contentComponents(), dialogue.optionComponents()),
                "a fully unchanged dialogue response keeps the untouched source actionbar");
        assertNull(dialogue.bindTranslations(
                        List.of(Component.literal("特尔克")),
                        List.of(Component.literal("选项一"), Component.literal("选项二"))),
                "dialogue content count mismatch fails closed");

        Component prefixSource = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE500\",\"font\":\"minecraft:hud/dialogue/portrait/therck\"},"
                + "{\"text\":\"Therck\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Go on, I don't have\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/body_0\"}]}");
        WynnDialogueProjection prefix = WynnDialogueProjection.project(prefixSource);
        assertNotNull(prefix, "typewriter prefix dialogue is recognized");
        assertTrue(prefix.isSemanticPrefixOf(dialogue), "typewriter growth stays in one semantic session");
        assertTrue(!prefix.terminalBodyPunctuation(), "unfinished typewriter prefix uses the longer stabilization delay");

        Component visibleChoices = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Therck\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Choose your answer\",\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"Ask about the brother\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/choice_0\"},"
                + "{\"text\":\"Leave\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/choice_1\"},"
                + "{\"text\":\"\\uE010\",\"font\":\"minecraft:hud/dialogue/style/default/choice\"}]}");
        WynnDialogueProjection visible = WynnDialogueProjection.project(visibleChoices);
        assertNotNull(visible, "choice-phase dialogue is recognized");
        assertEquals(WynnDialogueProjection.OptionVisibility.VISIBLE, visible.optionVisibility(),
                "the exact Wynn choice chrome is the positive option-visibility signal");
        WynnDialogueRenderPlan visiblePlan = visible.bindTranslations(
                List.of(Component.literal("特尔克"), Component.literal("选择你的回答")),
                List.of(Component.literal("询问他的兄弟"), Component.literal("Leave")));
        assertNotNull(visiblePlan, "visible choice translations bind when exact chrome is present");
        assertTrue(visiblePlan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind() == WynnDialogueProjection.SemanticKind.OPTION)
                        .allMatch(WynnDialogueRenderPlan.TranslatedSlot::sourceVisible),
                "visible choice glyphs are masked and replaced only during the real choice phase");
        assertTrue(visiblePlan.translatedSlots().stream().noneMatch(slot ->
                        slot.source().sourceText().equals("Leave")),
                "an unchanged visible option stays on Wynn's source stream while sibling choices translate");

        Component unknownChoices = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"Therck\",\"font\":\"minecraft:hud/dialogue/text/nameplate\"},"
                + "{\"text\":\"Choose your answer\",\"font\":\"minecraft:hud/dialogue/text/control\"},"
                + "{\"text\":\"Ask about the brother\",\"font\":\"minecraft:hud/dialogue/text/wynncraft/choice_0\"}]}");
        WynnDialogueProjection unknown = WynnDialogueProjection.project(unknownChoices);
        assertNotNull(unknown, "preloaded choice text remains projectable without a visibility signal");
        assertEquals(1, unknown.optionComponents().size(),
                "unknown-visibility choices still enter the independent pretranslation request");
        assertEquals(WynnDialogueProjection.OptionVisibility.UNKNOWN, unknown.optionVisibility(),
                "natural-language control text alone never claims that choices are visible");
        WynnDialogueRenderPlan unknownPlan = unknown.bindTranslations(
                        List.of(Component.literal("特尔克"), Component.literal("选择你的回答")),
                        List.of(Component.literal("询问他的兄弟")));
        assertNotNull(unknownPlan,
                "unknown option visibility no longer blocks translated visible dialogue content");
        assertTrue(unknownPlan.translatedSlots().stream()
                        .filter(slot -> slot.source().kind()
                                == WynnDialogueProjection.SemanticKind.OPTION)
                        .noneMatch(WynnDialogueRenderPlan.TranslatedSlot::sourceVisible),
                "unknown choices remain hidden/local while name and control translations render");
    }

    private static void checkDecoratedWynnServiceLabel() throws Exception {
        Component serviceLabel = componentFromJson("{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\\uE003\",\"font\":\"minecraft:merchant\"},"
                + "{\"text\":\"\\nItem Identifier\\n\",\"color\":\"light_purple\"},"
                + "{\"text\":\"\\uE060\\uF3FFFF\\uE03D\",\"font\":\"minecraft:banner/pill\"}]}");
        Method classifier = TextDisplayMixin.class.getDeclaredMethod(
                "simple_translate$isDecoratedMerchantLabel", Component.class, String.class);
        classifier.setAccessible(true);
        assertTrue((Boolean) classifier.invoke(null, serviceLabel, serviceLabel.getString()),
                "merchant plus banner/pill TextDisplay structure selects the Wynn service-title surface");

        Component ordinaryLabel = Component.literal("Item Identifier");
        assertTrue(!(Boolean) classifier.invoke(null, ordinaryLabel, ordinaryLabel.getString()),
                "plain TextDisplay text does not impersonate the decorated Wynn service-label grammar");
    }

    /** Optional developer seam for replaying a complete captured HUD cache. */
    private static void checkWynnDialoguePendingTracker() {
        WynnDialoguePendingEffect.Tracker tracker = new WynnDialoguePendingEffect.Tracker();
        long now = 10_000L;
        tracker.observe("dialogue-a", true, now);
        assertTrue(tracker.isActive("dialogue-a", now),
                "cache miss arms dialogue feedback during stabilization");

        tracker.observe("dialogue-a", false, now + 1L);
        assertTrue(!tracker.isActive("dialogue-a", now + 1L),
                "cache hit stops dialogue feedback without a rendered flash");

        tracker.observe("dialogue-b", true, now + 2L);
        tracker.fail("dialogue-b");
        tracker.observe("dialogue-b", true, now + 3L);
        assertTrue(!tracker.isActive("dialogue-b", now + 3L),
                "failed semantic frame remains latched off");

        tracker.observe("dialogue-c", true, now + 4L);
        assertTrue(tracker.isActive("dialogue-c", now + 4L),
                "dialogue/session fingerprint change starts fresh feedback");
        assertTrue(!tracker.isActive("dialogue-c",
                        now + 4L + WynnDialoguePendingEffect.TIMEOUT_MILLIS * 1_000_000L),
                "dialogue feedback stops at the safety timeout");

        tracker.clear();
        tracker.observe("dialogue-c", true, now + 5L);
        assertTrue(tracker.isActive("dialogue-c", now + 5L),
                "world/session clear discards stale failure and timeout state");
    }

    private static void checkExternalWynnDialogueFixture() throws Exception {
        String fixturePath = System.getenv("SIMPLE_TRANSLATE_WYNN_DIALOGUE_CACHE_FIXTURE");
        if (fixturePath == null || fixturePath.isBlank()) return;
        JsonObject root = JsonParser.parseString(Files.readString(Path.of(fixturePath))).getAsJsonObject();
        JsonObject entries = root.getAsJsonObject("entries");
        String sourcePayload = null;
        for (String key : entries.keySet()) {
            JsonObject entry = entries.getAsJsonObject(key);
            if (entry == null || !entry.has("sourcePayload")) continue;
            String candidate = entry.get("sourcePayload").getAsString();
            if (candidate.contains("Therck")
                    && candidate.contains("minecraft:hud/dialogue/text/wynncraft/body_1")) {
                sourcePayload = candidate;
                break;
            }
        }
        assertNotNull(sourcePayload, "external captured Therck dialogue payload exists");
        JsonArray roots = JsonParser.parseString(sourcePayload).getAsJsonArray();
        assertEquals(1, roots.size(), "external captured Therck payload has one root");
        Component source = ComponentJsonCompat.fromJson(roots.get(0));
        assertNotNull(source, "external captured Therck payload parses");
        WynnDialogueProjection projected = WynnDialogueProjection.project(source);
        assertNotNull(projected, "external captured Therck dialogue reaches its dedicated projection");
        assertEquals(3, projected.contentComponents().size(),
                "external captured Therck dialogue exposes all content slots");
        System.out.println("External captured Therck dialogue fixture passed");
    }

    private static void assertSemanticComponentArray(List<Component> semantic, String label) {
        String componentJson = JsonPassthroughPipeline.serializeComponents(semantic);
        JsonArray entries = JsonParser.parseString(componentJson).getAsJsonArray();
        assertEquals(semantic.size(), entries.size(), label + " COMPONENT_JSON top-level slot count");
        for (JsonElement entry : entries) {
            assertNotNull(ComponentJsonCompat.fromJson(entry),
                    label + " every semantic entry parses as a Component");
        }
    }

    private static Component componentBodyTranslation(WynnDialogueProjection projection,
                                                       String... translatedSegments) {
        WynnDialogueProjection.SemanticSlot body = projection.contentSlots().stream()
                .filter(slot -> slot.kind() == WynnDialogueProjection.SemanticKind.BODY)
                .findFirst().orElseThrow();
        assertEquals(body.bodyAnchors().size(), translatedSegments.length,
                "fixture supplies one translated phrase for each protected Wynn BODY anchor");
        StringBuilder paragraph = new StringBuilder();
        for (String translatedSegment : translatedSegments) {
            paragraph.append(translatedSegment == null ? "" : translatedSegment);
        }
        // A BODY response is one literal paragraph, never a model-defined
        // sibling tree. Source styles and glyph ownership remain local.
        return Component.literal(paragraph.toString());
    }

    private static Component compoundText(String... values) {
        var result = Component.empty();
        if (values != null) {
            for (String value : values) {
                result.append(Component.literal(value == null ? "" : value));
            }
        }
        return result;
    }

    private static List<Component> componentRenderLeaves(Component component) {
        if (component == null) return List.of();
        return component.getSiblings().isEmpty()
                ? List.of(component) : List.copyOf(component.getSiblings());
    }

    private static String formattedSequenceText(FormattedCharSequence sequence) {
        StringBuilder result = new StringBuilder();
        if (sequence != null) {
            sequence.accept((index, style, codePoint) -> {
                result.appendCodePoint(codePoint);
                return true;
            });
        }
        return result.toString();
    }

    private static Component componentFromJson(String json) {
        return ComponentJsonCompat.fromJson(JsonParser.parseString(json));
    }

    private static void assertInlineDialogueGlyph(String iconFontPath, int codePoint,
                                                   boolean choice, String label) {
        Component source = inlineDialogueGlyphSource(iconFontPath, codePoint, choice);
        WynnDialogueProjection projection = WynnDialogueProjection.project(source);
        assertNotNull(projection, label + " remains on the dedicated Wynn dialogue path");
        List<WynnDialogueProjection.SemanticSlot> slots = choice
                ? projection.optionSlots() : projection.contentSlots();
        int expectedSlots = choice ? 2 : 1;
        assertEquals(expectedSlots, slots.size(), label + " keeps the expected semantic grouping");
        if (choice) {
            assertEquals("Before", slots.get(0).sourceText(), label + " excludes the glyph before the boundary");
            assertEquals("After", slots.get(1).sourceText(), label + " excludes the glyph after the boundary");
        } else {
            assertEquals("Before After", slots.getFirst().sourceText(),
                    label + " keeps the complete body sentence around the omitted glyph");
        }
        assertTrue(slots.stream().flatMap(slot -> slot.regions().stream())
                        .anyMatch(WynnDialogueProjection.LineRegion::inlineIconAfter),
                label + " records the leading local avoidance region");
        assertTrue(slots.stream().flatMap(slot -> slot.regions().stream())
                        .anyMatch(WynnDialogueProjection.LineRegion::inlineIconBefore),
                label + " records the region following the local icon");
        List<Component> request = choice ? projection.optionComponents() : projection.contentComponents();
        assertTrue(request.stream().noneMatch(component -> component.getString().codePoints()
                        .anyMatch(candidate -> candidate == codePoint)),
                label + " never enters the Component JSON request array");
    }

    private static Component inlineDialogueGlyphSource(String iconFontPath, int codePoint,
                                                        boolean choice) {
        String row = choice ? "choice_0" : "body_0";
        FontDescription semanticFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("minecraft", "hud/dialogue/text/common/" + row));
        FontDescription iconFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("minecraft", "hud/dialogue/text/" + iconFontPath));
        return Component.empty()
                .append(Component.literal("Before ").withStyle(Style.EMPTY.withFont(semanticFont)))
                .append(Component.literal(new String(Character.toChars(codePoint)))
                        .withStyle(Style.EMPTY.withFont(iconFont)))
                .append(Component.literal(" After").withStyle(Style.EMPTY.withFont(semanticFont)));
    }

    private static List<Component> translateWynnGlyphSlots(List<Component> semanticSlots) {
        List<Component> translated = new ArrayList<>();
        for (Component semantic : semanticSlots) {
            String source = semantic == null ? "" : semantic.getString();
            String text = switch (source) {
                case "Left-Click to select" -> "\u5de6\u952e\u70b9\u51fb\u4ee5\u9009\u62e9\u5f53\u524d\u804c\u4e1a\u89d2\u8272";
                case "Scroll up/down to browse" -> "\u6eda\u52a8\u4ee5\u6d4f\u89c8";
                case "Right-Click to return" -> "\u53f3\u952e\u8fd4\u56de";
                case "Left-Click to play" -> "\u5de6\u952e\u70b9\u51fb\u5f00\u59cb\u6e38\u620f";
                case "Right-Click to switch" -> "\u53f3\u952e\u70b9\u51fb\u5207\u6362";
                case "Swift combos" -> "\u5feb\u901f\u8fde\u51fb";
                case "Create a Character" -> "\u521b\u5efa\u89d2\u8272";
                default -> "\u8bd1\u6587\u89c2\u4e61" + translated.size();
            };
            translated.add(Component.literal(text));
        }
        return List.copyOf(translated);
    }

    private static WynnActionbarGlyphOverlayPlan.PositionedSlot findWynnGlyphSlot(
            WynnActionbarGlyphOverlayPlan.Layout layout, String sourceText) {
        for (WynnActionbarGlyphOverlayPlan.PositionedSlot slot : layout.slots()) {
            if (sourceText.equals(slot.source().sourceText())) {
                return slot;
            }
        }
        throw new AssertionError("missing Wynn direct glyph overlay slot for " + sourceText);
    }

    private static List<WynnVisualGlyph> wynnVisualGlyphs(FormattedCharSequence sequence) {
        List<WynnVisualGlyph> glyphs = new ArrayList<>();
        assertNotNull(sequence, "visual-order sequence exists");
        sequence.accept((sourceIndex, style, codePoint) -> {
            glyphs.add(new WynnVisualGlyph(sourceIndex, style == null ? Style.EMPTY : style, codePoint));
            return true;
        });
        // Minecraft 26.1.1's default Language visual-order implementation
        // returns false after a complete visit (it returns Optional#isPresent),
        // even though every glyph was delivered to the sink. Font deliberately
        // ignores that status. Assert the captured stream itself instead of
        // treating FormattedCharSequence#accept's boolean as a completion bit.
        return List.copyOf(glyphs);
    }

    private static String fixtureFontId(Style style) {
        if (style != null && style.getFont() instanceof FontDescription.Resource resource) {
            return resource.id().toString();
        }
        return "";
    }

    private static List<WynnMaskedGlyph> wynnMaskedGlyphs(FormattedCharSequence sequence) {
        List<WynnMaskedGlyph> glyphs = new ArrayList<>();
        assertNotNull(sequence, "masked visual-order sequence exists");
        sequence.accept((sourceIndex, style, codePoint) -> {
            Style safeStyle = style == null ? Style.EMPTY : style;
            glyphs.add(new WynnMaskedGlyph(
                    new WynnVisualGlyph(sourceIndex, safeStyle, codePoint),
                    WynnActionbarGlyphOverlayPlan.isCurrentGlyphMasked()));
            return true;
        });
        return List.copyOf(glyphs);
    }

    private static String wynnGlyphText(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        for (WynnVisualGlyph glyph : wynnVisualGlyphs(sequence)) {
            text.appendCodePoint(glyph.codePoint());
        }
        return text.toString();
    }

    /** Simulates Wynn selector PUA resets using the exact visual-order stream. */
    private static float wynnGlyphOverlayWidth(FormattedCharSequence sequence) {
        List<WynnVisualGlyph> glyphs = wynnVisualGlyphs(sequence);
        float cursor = 0.0F;
        for (int index = 0; index < glyphs.size(); index++) {
            int codePoint = glyphs.get(index).codePoint();
            if (codePoint == '§' && index + 1 < glyphs.size()) {
                index++;
                continue;
            }
            if (codePoint == 0xE000) {
                cursor = 20.0F;
            } else if (codePoint == 0xE002) {
                cursor = 120.0F;
            } else if (codePoint == 0xE001) {
                cursor = 220.0F;
            } else if (codePoint < 0x20 || codePoint == 0x7F || Character.getType(codePoint) == Character.FORMAT) {
                // Wynn controls do not consume ordinary horizontal advance.
            } else if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) {
                cursor += 2.0F;
            } else {
                cursor += 1.0F;
            }
        }
        return cursor;
    }

    private static boolean isWynnPua(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    private record WynnVisualGlyph(int sourceIndex, Style style, int codePoint) {
    }

    private record WynnMaskedGlyph(WynnVisualGlyph glyph, boolean masked) {
    }

    private static void assertWynncraftActionbarLayout(ActionbarLayoutRenderer.Layout layout, String wireMode) {
        assertNotNull(layout, wireMode + " actionbar layout resolves");
        assertFloatEquals(26.0F, layout.sourceAdvance(), wireMode + " source width stays original");
        List<ActionbarLayoutRenderer.PositionedSpan> spans = layout.spans();
        assertEquals(4, spans.size(), wireMode + " layout has one span per source leaf");

        ActionbarLayoutRenderer.PositionedSpan select = spans.get(1);
        ActionbarLayoutRenderer.PositionedSpan laterAnchor = spans.get(2);
        ActionbarLayoutRenderer.PositionedSpan scroll = spans.get(3);
        assertEquals("\u70b9\u51fb\u9009\u62e9", select.rendered().getString(), wireMode + " long Chinese span rendered");
        assertEquals(new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT),
                select.rendered().getStyle().getFont(),
                wireMode + " semantic text uses the mod-owned CJK font");
        assertFloatEquals(3.0F, select.x(), wireMode + " first visible span starts after original PUA advance");
        assertFloatEquals(12.0F, select.sourceAdvance(), wireMode + " long Chinese span keeps original advance");
        assertFloatEquals(0.75F, select.scaleX(), wireMode + " long Chinese span stops at the readable scale floor");
        assertEquals("\uE002", laterAnchor.rendered().getString(), wireMode + " second PUA anchor rendered verbatim");
        assertTrue(!new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT)
                        .equals(laterAnchor.rendered().getStyle().getFont()),
                wireMode + " protected PUA anchor keeps its source resource-pack font");
        assertFloatEquals(15.0F, laterAnchor.x(), wireMode + " later PUA anchor stays at its original x");
        assertEquals("\u5411\u4e0a\u6eda\u52a8", scroll.rendered().getString(), wireMode + " short Chinese span rendered");
        assertEquals(new FontDescription.Resource(ActiveFontManager.CJK_FALLBACK_FONT),
                scroll.rendered().getStyle().getFont(),
                wireMode + " later semantic text also uses the CJK font");
        assertFloatEquals(18.0F, scroll.x(), wireMode + " later visible span starts at original anchor boundary");
        assertFloatEquals(1.0F, scroll.scaleX(), wireMode + " short Chinese span is never stretched");
    }

    private static float wynnActionbarFixtureWidth(Component component) {
        return switch (component.getString()) {
            case "\uE000", "\uE002" -> 3.0F;
            case "Click to select" -> 12.0F;
            case "\u70b9\u51fb\u9009\u62e9" -> 16.0F;
            case "Scroll up" -> 8.0F;
            case "\u5411\u4e0a\u6eda\u52a8" -> 4.0F;
            case "\uE000Click to select\uE002Scroll up" -> 26.0F;
            default -> throw new AssertionError("unexpected fixture width component: " + component.getString());
        };
    }

    private static void checkProtectedTextRuns() {
        List<ProtectedTextRuns.Run> runs =
                ProtectedTextRuns.split("\u0001\u00a7eClick\uE002");
        assertEquals(3, runs.size(), "HUD protected-run splitter keeps three ordered runs");
        assertEquals("\u0001\u00a7e", runs.get(0).text(),
                "control and legacy prefix stay together");
        assertTrue(runs.get(0).protectedRun(), "control and legacy prefix is protected");
        assertEquals("Click", runs.get(1).text(), "visible run remains separately translatable");
        assertTrue(!runs.get(1).protectedRun(), "visible run is not protected");
        assertEquals("\uE002", runs.get(2).text(), "PUA anchor stays a protected terminal run");
        assertTrue(runs.get(2).protectedRun(), "PUA anchor is protected");

        String resourceTail = Character.toString(0xCFFC4) + Character.toString(0xD0044);
        List<ProtectedTextRuns.Run> planeRuns = ProtectedTextRuns.split(
                "Class Type" + resourceTail);
        assertEquals(2, planeRuns.size(),
                "plane-12/13 resource glyphs split from visible semantic text");
        assertEquals("Class Type", planeRuns.get(0).text(),
                "visible plane-resource prefix stays available for Component projection");
        assertTrue(!planeRuns.get(0).protectedRun() && planeRuns.get(1).protectedRun(),
                "only the plane-12/13 resource tail remains client-owned");
    }

    private static void checkComponentJsonCompactRetryAndHoverIdentity() throws Exception {
        Method parseExactComponents = JsonPassthroughPipeline.class.getDeclaredMethod(
                "parseExactComponentList", String.class, int.class, String.class, int.class);
        parseExactComponents.setAccessible(true);
        List<Component> compactComponents = (List<Component>) parseExactComponents.invoke(null,
                "[\"导航员\",\"前置能力：箭矢轰炸\"]", 2,
                "tooltip.visible.item.component.v2", 0);
        assertNotNull(compactComponents,
                "compact JSON strings parse as ordinary Component entries");
        assertEquals(List.of("导航员", "前置能力：箭矢轰炸"),
                compactComponents.stream().map(Component::getString).toList(),
                "compact Component response preserves every translated slot");
        assertNull(parseExactComponents.invoke(null,
                        "[\"导航员\"]", 2,
                        "tooltip.visible.item.component.v2", 0),
                "compact Component response still obeys exact top-level count");

        FontDescription merchantFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("minecraft", "hud/dialogue/text/merchant/body_0"));
        Component decoratedNavigator = Component.empty()
                .append(Component.literal("\uE003").withStyle(Style.EMPTY.withFont(merchantFont)))
                .append(Component.literal(" Navigator").withStyle(ChatFormatting.YELLOW));
        Component requiredAbility = Component.literal("Required Ability: Arrow Bomb")
                .withStyle(ChatFormatting.GRAY);
        ComponentVisualProjection compactProjection = ComponentVisualProjection.projectComponents(
                List.of(decoratedNavigator, requiredAbility), "zh_cn");
        assertNotNull(compactProjection, "decorated compact Component projection");
        JsonArray compactRequest = JsonParser.parseString(
                compactProjection.semanticJson()).getAsJsonArray();
        assertEquals(2, compactRequest.size(),
                "decorated tooltip keeps both semantic Component slots");
        assertTrue(compactRequest.asList().stream().allMatch(element ->
                        element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()),
                "semantic request uses legal compact string Components");
        List<Component> compactRebuilt = compactProjection.rebuildComponentList(
                JsonParser.parseString("[\"导航员\",\"前置能力：箭矢轰炸\"]"));
        assertNotNull(compactRebuilt,
                "compact string Component response rebuilds through the production projection");
        assertEquals("\uE003 导航员", compactRebuilt.get(0).getString(),
                "compact rebuild restores the local visual atom at its original position");
        String rebuiltNavigatorJson = ComponentJsonCompat.toJson(compactRebuilt.get(0));
        assertTrue(rebuiltNavigatorJson.contains("hud/dialogue/text/merchant/body_0")
                        && rebuiltNavigatorJson.contains("\uE003")
                        && rebuiltNavigatorJson.contains("导航员"),
                "compact rebuild preserves the original icon/font skeleton and translated prose");
        assertEquals(ChatFormatting.GRAY.getColor(),
                compactRebuilt.get(1).getStyle().getColor().getValue(),
                "compact rebuild preserves source Component style");

        FontDescription animatedIconFont = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath("mcc", "animated_icon"));
        Component liveA = Component.empty()
                .append(Component.literal("\uE100").withStyle(Style.EMPTY.withFont(animatedIconFont)))
                .append(Component.literal(" Daily Quest Progress: 0/5")
                        .withStyle(ChatFormatting.YELLOW));
        Component liveB = Component.empty()
                .append(Component.literal("\uE101").withStyle(Style.EMPTY.withFont(animatedIconFont)))
                .append(Component.literal(" Daily Quest Progress: 3/5")
                        .withStyle(ChatFormatting.YELLOW));
        ComponentVisualProjection liveProjectionA = JsonPassthroughPipeline.projectLiveComponents(
                List.of(liveA), "zh_cn");
        ComponentVisualProjection liveProjectionB = JsonPassthroughPipeline.projectLiveComponents(
                List.of(liveB), "zh_cn");
        assertNotNull(liveProjectionA, "first live tooltip projection");
        assertNotNull(liveProjectionB, "second live tooltip projection");
        assertEquals(liveProjectionA.semanticJson(), liveProjectionB.semanticJson(),
                "animated custom-font sprite and live numbers do not change semantic identity");
        ComponentVisualProjection normalizedLiveProjection = ComponentVisualProjection.project(
                JsonPassthroughPipeline.serializeComponents(List.of(liveA)), "zh_cn");
        assertNotNull(normalizedLiveProjection, "normalized transport tooltip projection");
        Component ratioOnlyA = Component.literal("Daily Quest Progress: 0/5");
        Component ratioOnlyB = Component.literal("Daily Quest Progress: 3/5");
        assertEquals(JsonPassthroughPipeline.serializeComponents(List.of(ratioOnlyA)),
                JsonPassthroughPipeline.serializeComponents(List.of(ratioOnlyB)),
                "changing a classified live ratio reuses one normalized transport/cache source");
        assertEquals(liveProjectionA.semanticJson(), normalizedLiveProjection.semanticJson(),
                "live READY projection and shared transport derive identical semantic slots");
        assertTrue(!liveProjectionA.semanticJson().contains("\uE100")
                        && !liveProjectionB.semanticJson().contains("\uE101")
                        && !liveProjectionA.semanticJson().contains("0/5")
                        && !liveProjectionB.semanticJson().contains("3/5"),
                "custom-font sprite indices and current progress remain outside the model payload");
        List<Component> translatedSlots = liveProjectionA.semanticComponents().stream()
                .map(component -> (Component) Component.literal("每日任务进度："))
                .toList();
        List<Component> translatedLiveA = liveProjectionA.rebuildComponentList(translatedSlots);
        Method extractReadySemantic = TooltipTranslationHelper.class.getDeclaredMethod(
                "translatedSemanticComponents", ComponentVisualProjection.class, List.class);
        extractReadySemantic.setAccessible(true);
        List<Component> readySemantic = (List<Component>) extractReadySemantic.invoke(
                null, liveProjectionA, translatedLiveA);
        assertNotNull(readySemantic,
                "async full-tree result is reduced to reusable semantic READY Components");
        List<Component> reboundLiveB = liveProjectionB.rebuildComponentList(readySemantic);
        assertNotNull(reboundLiveB, "ready semantic result rebinds to the current tooltip skeleton");
        assertTrue(reboundLiveB.get(0).getString().contains("\uE101")
                        && reboundLiveB.get(0).getString().contains("3/5")
                        && reboundLiveB.get(0).getString().contains("每日任务进度"),
                "READY rebind uses current icon/progress while showing translated text");
        String reboundLiveJson = ComponentJsonCompat.toJson(reboundLiveB.get(0));
        assertTrue(reboundLiveJson.contains("mcc:animated_icon"),
                "READY rebind preserves the current custom bitmap font");
        Method readyKeyMethod = TooltipTranslationHelper.class.getDeclaredMethod(
                "semanticPendingKey", String.class, ComponentVisualProjection.class);
        readyKeyMethod.setAccessible(true);
        assertEquals(readyKeyMethod.invoke(null, "tooltip.visible.item.component.v2", liveProjectionA),
                readyKeyMethod.invoke(null, "tooltip.visible.item.component.v2", liveProjectionB),
                "pending/READY identity ignores current bitmap sprite and progress value");

        Component hoverSource = Component.literal("Open Daily Quest").withStyle(style ->
                style.withHoverEvent(new HoverEvent.ShowText(Component.literal("Hidden English details"))));
        ComponentVisualProjection hoverProjection = JsonPassthroughPipeline.projectLiveComponents(
                List.of(hoverSource), "zh_cn");
        assertNotNull(hoverProjection, "hover-sanitized live projection");
        assertTrue(!hoverProjection.semanticJson().contains("Hidden English details"),
                "ordinary tooltip translation never sends hidden hover payloads");
        List<Component> hoverRebuilt = hoverProjection.rebuildComponentList(
                List.of(Component.literal("打开每日任务")));
        List<Component> hoverRestored = JsonPassthroughPipeline
                .reattachOriginalHoverEventsForRender(hoverRebuilt, List.of(hoverSource));
        String hoverRestoredJson = ComponentJsonCompat.toJson(hoverRestored.get(0));
        assertTrue(hoverRestoredJson.contains("打开每日任务")
                        && hoverRestoredJson.contains("Hidden English details"),
                "current source hover payload is reattached after READY semantic rebinding");

        TooltipSemanticResultStore.clear();
        TooltipSemanticResultStore.put("fixture-ready", readySemantic);
        assertEquals(readySemantic, TooltipSemanticResultStore.get("fixture-ready"),
                "session READY store hands semantic Components to the next render frame");
        TooltipSemanticResultStore.clear();
        assertNull(TooltipSemanticResultStore.get("fixture-ready"),
                "READY semantic results clear across runtime/profile boundaries");

        TooltipTranslationHelper.clearPendingCache();
        List<Component> markedInstance = List.of(Component.literal("Source-equal English"));
        List<Component> equalButDifferentInstance = List.of(Component.literal("Source-equal English"));
        TooltipTranslationHelper.markTranslatedTooltip(markedInstance);
        assertTrue(TooltipTranslationHelper.isMarkedTranslatedTooltip(markedInstance),
                "translated render recursion marker recognizes the exact list instance");
        assertTrue(!TooltipTranslationHelper.isMarkedTranslatedTooltip(equalButDifferentInstance),
                "source-equal content is not globally marked translated by signature");

        Method deferRetry = TooltipTranslationHelper.class.getDeclaredMethod(
                "deferSemanticRetry", String.class, long.class);
        Method retryBlocked = TooltipTranslationHelper.class.getDeclaredMethod(
                "semanticRetryBlocked", String.class, long.class);
        deferRetry.setAccessible(true);
        retryBlocked.setAccessible(true);
        String retryKey = "fixture-tooltip-retry";
        long retryStarted = 10_000_000_000L;
        deferRetry.invoke(null, retryKey, retryStarted);
        assertTrue((boolean) retryBlocked.invoke(null, retryKey, retryStarted + 5_999_999_999L),
                "failed tooltip request remains locally suppressed during the six-second cooldown");
        assertTrue(!(boolean) retryBlocked.invoke(null, retryKey, retryStarted + 6_000_000_000L),
                "failed tooltip request becomes eligible exactly after the six-second cooldown");
        TooltipTranslationHelper.clearPendingCache();

        Method structureContext = JsonPassthroughPipeline.class.getDeclaredMethod(
                "componentStructureAttemptContext", String.class, int.class, int.class);
        structureContext.setAccessible(true);
        Field maxStructureAttempts = JsonPassthroughPipeline.class.getDeclaredField(
                "MAX_COMPONENT_STRUCTURE_ATTEMPTS");
        maxStructureAttempts.setAccessible(true);
        assertEquals(3, maxStructureAttempts.getInt(null),
                "Component structure correction has exactly attempts 0, 1 and 2");
        Method retryRemaining = JsonPassthroughPipeline.class.getDeclaredMethod(
                "hasComponentStructureRetryRemaining", int.class);
        retryRemaining.setAccessible(true);
        assertTrue((boolean) retryRemaining.invoke(null, 0),
                "invalid initial Component response permits correction attempt 1");
        assertTrue((boolean) retryRemaining.invoke(null, 1),
                "invalid correction attempt 1 permits final correction attempt 2");
        assertTrue(!(boolean) retryRemaining.invoke(null, 2),
                "correction attempt 2 is the strict retry boundary");
        String baseContext = "{\"scope\":\"server\",\"source_shape\":\"Weekly Vault <number> XP\"}";
        assertEquals(baseContext, structureContext.invoke(null, baseContext, 2, 0),
                "structure attempt zero keeps the complete original prompt context");
        for (int attempt : List.of(1, 2)) {
            JsonObject retry = JsonParser.parseString((String) structureContext.invoke(
                    null, baseContext, 2, attempt)).getAsJsonObject();
            assertTrue(retry.get("component_structure_retry").getAsBoolean(),
                    "structure retry attempt is explicitly identified: " + attempt);
            assertEquals(2, retry.get("required_top_level_count").getAsInt(),
                    "structure retry retains the exact Component count: " + attempt);
            assertEquals(attempt, retry.get("structure_retry_attempt").getAsInt(),
                    "structure retry metadata records bounded attempt: " + attempt);
            assertEquals("server", retry.get("scope").getAsString(),
                    "structure retry preserves existing context metadata: " + attempt);
            assertEquals("Weekly Vault <number> XP", retry.get("source_shape").getAsString(),
                    "structure retry preserves the complete stable source shape: " + attempt);
        }

        Method hoverSignature = TooltipTranslationTriggerState.class.getDeclaredMethod(
                "signature", List.class);
        hoverSignature.setAccessible(true);
        String animatedFirst = (String) hoverSignature.invoke(null, List.of(
                Component.literal("\uE001 Weekly Vault Progress: 0/500 XP")));
        String animatedSecond = (String) hoverSignature.invoke(null, List.of(
                Component.literal("\uE0FF Weekly Vault Progress: 42/500 XP")));
        assertEquals(animatedFirst, animatedSecond,
                "animated icons and live progress do not churn tooltip hover identity");
        String differentTooltip = (String) hoverSignature.invoke(null, List.of(
                Component.literal("\uE0FF Daily Quest Progress: 42/500 XP")));
        assertTrue(!animatedFirst.equals(differentTooltip),
                "natural-language tooltip changes still create a new hover identity");

        Method clearHoverIntent = TooltipTranslationTriggerState.class.getDeclaredMethod(
                "clearHoverIntent");
        clearHoverIntent.setAccessible(true);
        Method allowRequestAt = TooltipTranslationTriggerState.class.getDeclaredMethod(
                "allowRequestAt", TooltipTranslationController.RenderContext.class,
                String.class, long.class);
        allowRequestAt.setAccessible(true);
        boolean oldGlobalEnabled = ModConfig.GLOBAL_ENABLED.get();
        ModConfig.TooltipTriggerMode oldTriggerMode = ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get();
        try {
            ModConfig.GLOBAL_ENABLED.set(true);
            ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(ModConfig.TooltipTriggerMode.HOVER);
            clearHoverIntent.invoke(null);
            long started = 1_000_000_000L;
            assertTrue(!(boolean) allowRequestAt.invoke(null,
                            TooltipTranslationController.RenderContext.ITEM,
                            animatedFirst, started),
                    "automatic tooltip translation waits for hover intent");
            assertTrue(!(boolean) allowRequestAt.invoke(null,
                            TooltipTranslationController.RenderContext.ITEM,
                            animatedFirst, started + 200_000_000L),
                    "tooltip dwell remains pending before 350 milliseconds");
            assertTrue((boolean) allowRequestAt.invoke(null,
                            TooltipTranslationController.RenderContext.ITEM,
                            animatedSecond, started + 360_000_000L),
                    "animated icon/progress changes do not reset a continuous 350ms dwell");
            assertTrue((boolean) allowRequestAt.invoke(null,
                            TooltipTranslationController.RenderContext.ITEM,
                            animatedSecond, started + 1_200_000_000L),
                    "a resource-pack frame stall below four FPS does not reset tooltip dwell");
            assertTrue(!(boolean) allowRequestAt.invoke(null,
                            TooltipTranslationController.RenderContext.ITEM,
                            differentTooltip, started + 400_000_000L),
                    "a different natural-language tooltip starts a fresh dwell");
        } finally {
            clearHoverIntent.invoke(null);
            ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.set(oldTriggerMode);
            ModConfig.GLOBAL_ENABLED.set(oldGlobalEnabled);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkAdaptiveComponentPartitionRecovery() throws Exception {
        List<Component> abilityTooltip = List.of(
                Component.literal("Cheaper Arrow Bomb").withStyle(ChatFormatting.WHITE),
                Component.literal("Reduce the Mana cost of Arrow Bomb.").withStyle(ChatFormatting.GRAY),
                Component.literal("\uE004 Mana Cost: -10").withStyle(ChatFormatting.AQUA),
                Component.literal("Ability Points: 1").withStyle(ChatFormatting.GRAY),
                Component.literal("Required Ability: Arrow Bomb").withStyle(ChatFormatting.GRAY),
                Component.literal("You do not meet the requirements").withStyle(ChatFormatting.RED),
                Component.literal("Click to unlock this ability").withStyle(ChatFormatting.GREEN),
                Component.literal("Unlock this ability first").withStyle(ChatFormatting.YELLOW));
        ComponentVisualProjection projection = JsonPassthroughPipeline.projectLiveComponents(
                abilityTooltip, "zh_cn");
        assertNotNull(projection, "ability tooltip projection");
        assertEquals(8, projection.slotCount(),
                "ability tooltip exposes eight semantic Component slots");

        Method bisect = JsonPassthroughPipeline.class.getDeclaredMethod(
                "bisectComponentRecoveryPayload", String.class, int.class);
        bisect.setAccessible(true);
        List<String> halves = (List<String>) bisect.invoke(
                null, projection.semanticJson(), projection.slotCount());
        assertEquals(2, halves.size(), "eight-slot recovery creates two Component partitions");
        assertEquals(4, JsonParser.parseString(halves.get(0)).getAsJsonArray().size(),
                "left recovery partition keeps four source Components");
        assertEquals(4, JsonParser.parseString(halves.get(1)).getAsJsonArray().size(),
                "right recovery partition keeps four source Components");
        assertEquals(0, ((List<String>) bisect.invoke(null, "[\"one\"]", 1)).size(),
                "a singleton never falls back to a non-Component wire format");

        Method recoveryContext = JsonPassthroughPipeline.class.getDeclaredMethod(
                "componentPartitionRecoveryContext", String.class, int.class, int.class);
        recoveryContext.setAccessible(true);
        JsonObject recoveryMetadata = JsonParser.parseString((String) recoveryContext.invoke(
                null, "{}", 4, 8)).getAsJsonObject();
        assertEquals(4, recoveryMetadata.get("required_top_level_count").getAsInt(),
                "partition prompt records the actual child Component count");
        assertEquals(8, recoveryMetadata.get("source_document_top_level_count").getAsInt(),
                "partition prompt retains the complete source document count");

        Method combine = JsonPassthroughPipeline.class.getDeclaredMethod(
                "combineComponentPartitionResponses", List.class, List.class, String.class);
        combine.setAccessible(true);
        String left = "[\"更便宜的箭矢轰炸\",\"降低箭矢轰炸的法力消耗。\","
                + "\"法力消耗：-10\",\"能力点数：1\"]";
        String right = "[\"前置能力：箭矢轰炸\",\"你不满足要求\","
                + "\"点击解锁此能力\",\"请先解锁此能力\"]";
        String combined = (String) combine.invoke(null,
                List.of(left, right), List.of(4, 4),
                "tooltip.visible.item.component.v2");
        assertNotNull(combined, "valid Component partitions recombine");
        assertEquals(8, JsonParser.parseString(combined).getAsJsonArray().size(),
                "recombined response restores the exact original slot count");
        assertNull(combine.invoke(null,
                        List.of(left, "[\"a\",\"b\",\"c\",\"d\",\"extra\"]"),
                        List.of(4, 4), "tooltip.visible.item.component.v2"),
                "an invalid child count cannot be guessed, dropped, or cached");

        List<Component> rebuilt = projection.rebuildComponentList(JsonParser.parseString(combined));
        assertNotNull(rebuilt, "partition recovery rebuilds through the original projection");
        assertEquals(abilityTooltip.size(), rebuilt.size(),
                "partition recovery preserves the source Component list shape");
        assertTrue(rebuilt.get(2).getString().contains("\uE004")
                        && rebuilt.get(2).getString().contains("-10"),
                "custom icon and dynamic negative number remain client-owned after recovery");
        assertEquals(ChatFormatting.RED.getColor(), rebuilt.get(5).getStyle().getColor().getValue(),
                "source warning colour survives partition recovery");

        Component groupedFirst = Component.empty()
                .append(Component.literal("This item's power has been sealed,"))
                .append(Component.literal(" an ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\uE003").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" Item Identifier can unlock its potential."));
        Component groupedSecond = Component.empty()
                .append(Component.literal("Ability Points:"))
                .append(Component.literal(" Required Ability:"));
        ComponentVisualProjection grouped = JsonPassthroughPipeline.projectLiveComponents(
                List.of(groupedFirst, groupedSecond), "zh_cn");
        assertNotNull(grouped, "multi-style grouped recovery projection");
        assertEquals(2, grouped.atomicGroupSizes().size(),
                "two original Components create two recovery atoms");
        assertTrue(grouped.atomicGroupSizes().get(0) > 1
                        && grouped.atomicGroupSizes().get(1) > 1,
                "each recovery atom may contain multiple semantic style fragments");

        Method groupedBisect = JsonPassthroughPipeline.class.getDeclaredMethod(
                "bisectComponentRecoveryPayload", String.class, int.class, List.class);
        groupedBisect.setAccessible(true);
        assertEquals(0, ((List<String>) groupedBisect.invoke(null,
                        grouped.semanticJson(), grouped.slotCount(), List.of(grouped.slotCount()))).size(),
                "one grammatical Component group is never bisected");
        List<String> groupedHalves = (List<String>) groupedBisect.invoke(null,
                grouped.semanticJson(), grouped.slotCount(), grouped.atomicGroupSizes());
        assertEquals(grouped.atomicGroupSizes().get(0),
                JsonParser.parseString(groupedHalves.get(0)).getAsJsonArray().size(),
                "group-aware recovery splits only after the first original Component");
        assertEquals(grouped.atomicGroupSizes().get(1),
                JsonParser.parseString(groupedHalves.get(1)).getAsJsonArray().size(),
                "group-aware recovery keeps every second-Component fragment together");

        Method recoveryGroups = JsonPassthroughPipeline.class.getDeclaredMethod(
                "recoveryAtomicGroupSizes", String.class, String.class,
                ComponentVisualProjection.class);
        recoveryGroups.setAccessible(true);
        assertEquals(List.of(grouped.slotCount()),
                recoveryGroups.invoke(null, "book.page.direct", "", grouped),
                "all lines in a submitted book chunk remain one recovery document");
        assertEquals(List.of(grouped.slotCount()),
                recoveryGroups.invoke(null, "sign.manual.group.by_id.direct", "", grouped),
                "all lines in a submitted sign document remain one recovery document");
        assertEquals(List.of(grouped.slotCount()),
                recoveryGroups.invoke(null, "tooltip.visible.item.component.v2",
                        "Incremental Component request.", grouped),
                "one changed tooltip grammar group remains atomic during recovery");
        assertEquals(grouped.atomicGroupSizes(),
                recoveryGroups.invoke(null, "gui.component.visible_frame.v3",
                        "frame_context_kind=screen", grouped),
                "a GUI frame may recover only between original Components after full-document retries");
        assertEquals(grouped.atomicGroupSizes(),
                recoveryGroups.invoke(null, "hud.visible_frame.component.v2",
                        "frame_context_kind=hud", grouped),
                "a HUD overlay frame may recover only between original Components after full-document retries");
    }

    private static List<Component> lines(String... values) {
        List<Component> result = new ArrayList<>();
        for (String value : values) {
            result.add(Component.literal(value));
        }
        return List.copyOf(result);
    }

    private static Component[] signComponents(String... values) {
        Component[] components = new Component[4];
        for (int i = 0; i < 4; i++) {
            String value = i < values.length && values[i] != null ? values[i] : "";
            components[i] = Component.literal(value);
        }
        return components;
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void assertNotNull(Object value, String label) {
        if (value == null) throw new AssertionError(label + ": expected non-null");
    }

    private static void assertNull(Object value, String label) {
        if (value != null) throw new AssertionError(label + ": expected null but was " + value);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertFloatEquals(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 0.0001F) {
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
& "$env:JAVA_HOME\bin\java.exe" "-Dorg.gradle.appname=gradlew" `
  -classpath "gradle/wrapper/gradle-wrapper.jar" `
  org.gradle.wrapper.GradleWrapperMain -q --no-daemon -I $initScript simpleTranslatePrintCompileClasspath
    if ($LASTEXITCODE -ne 0) {
        throw "classpath export failed with exit code $LASTEXITCODE"
    }
    $gradleClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $minecraftVersion = (Get-Content -LiteralPath (Join-Path $ProjectDir "gradle.properties") |
        Where-Object { $_ -match "^minecraft_version=(.+)$" } |
        ForEach-Object { $Matches[1].Trim() } |
        Select-Object -First 1)
    $minecraftJar = $null
    $loomCacheRoot = Join-Path $ProjectDir ".gradle\loom-cache\minecraftMaven\net\minecraft"
    if (Test-Path -LiteralPath $loomCacheRoot) {
        $minecraftJar = Get-ChildItem -Path $loomCacheRoot -Recurse -Filter "*$minecraftVersion*.jar" |
            Where-Object { $_.Name -like "minecraft-merged*" -and $_.Name -notmatch "sources|javadoc" } |
            Select-Object -First 1
    }
    if (-not $minecraftJar) {
        # NeoForge ModDev layout: no Loom cache exists on this loader. The
        # ModDev merged jar embeds NeoForge bootstrap patches (SharedConstants
        # touches FMLEnvironment before FML is loaded), so the headless fixture
        # JVM must use the vanilla Mojmap-remapped client jar from the
        # minecraft-dev cache.
        $vanillaMojmap = Join-Path $env:APPDATA "minecraft-dev-mcp\remapped\$minecraftVersion-mojmap.jar"
        if (Test-Path -LiteralPath $vanillaMojmap) {
            $minecraftJar = Get-Item -LiteralPath $vanillaMojmap
        }
    }
    if (-not $minecraftJar) {
        # Last resort: the Mojmap Minecraft+NeoForge merged jar under
        # build\moddev\artifacts (patched; Bootstrap-based fixtures cannot run on it).
        $moddevArtifacts = Join-Path $ProjectDir "build\moddev\artifacts"
        if (Test-Path -LiteralPath $moddevArtifacts) {
            $minecraftJar = Get-ChildItem -Path $moddevArtifacts -Filter "*merged*.jar" |
                Where-Object { $_.Name -notmatch "sources|javadoc" } |
                Select-Object -First 1
        }
    }
    if (-not $minecraftJar) {
        throw "Could not find the remapped Minecraft $minecraftVersion jar"
    }
    $remappedModJars = @()
    $remappedRoot = Join-Path $ProjectDir ".gradle\loom-cache\remapped_mods\remapped"
    if (Test-Path $remappedRoot) {
        $remappedModJars = Get-ChildItem -Path $remappedRoot -Recurse -Filter "*.jar" |
            Where-Object { $_.Name -notmatch "sources|javadoc" } |
            ForEach-Object { $_.FullName }
    }
    # NeoForge's minecraft-dependencies pom for 1.21.11 pins older library
    # versions than the exact 1.21.11 runtime (e.g. datafixerupper 8.0.16 vs
    # 9.0.19, commons-lang3 3.17.0 vs 3.19.0), and vanilla 1.21.11 classes call
    # APIs that only exist in the runtime versions (MapCodec.unitCodec(Object),
    # MutableObject.get()). Override every pom-pinned library with the exact
    # runtime jar from the NeoForm runtime cache (downloaded from Mojang for
    # this Minecraft version) so this headless JVM matches the real client.
    $runtimeLibsRoot = Join-Path $env:USERPROFILE ".gradle\caches\neoformruntime\artifacts"
    if (-not (Test-Path -LiteralPath $runtimeLibsRoot)) {
        throw "NeoForm runtime artifact cache not found at $runtimeLibsRoot (run a project build first)"
    }
    $runtimeLibs = @{}
    Get-ChildItem -Path $runtimeLibsRoot -Recurse -Filter "*.jar" |
        Where-Object { $_.Name -notmatch "sources|javadoc|natives" } |
        ForEach-Object {
            $rel = $_.FullName.Substring($runtimeLibsRoot.Length + 1)
            $parts = $rel -split "[\\/]"
            if ($parts.Length -ge 4) {
                $artifact = $parts[$parts.Length - 3]
                $groupPath = $parts[0..($parts.Length - 4)] -join "."
                $runtimeLibs["${groupPath}:${artifact}"] = $_.FullName
            }
        }
    $overriddenClasspath = @()
    foreach ($entry in ($gradleClasspath -split ";")) {
        $replaced = $false
        if ($entry -match "files-2\.1[\\/]([^\\/]+)[\\/]([^\\/]+)[\\/]") {
            $key = "$($Matches[1]):$($Matches[2])"
            if ($runtimeLibs.ContainsKey($key)) {
                $overriddenClasspath += $runtimeLibs[$key]
                $replaced = $true
            }
        }
        if (-not $replaced) {
            $overriddenClasspath += $entry
        }
    }
    # NeoForge 21.11.42's compile classpath can expose authlib 6.0.54 from
    # its dependency metadata, while the exact 1.21.11 Minecraft manifest
    # requires authlib 7.0.61 (GameProfile.id(), not getId()). Put the exact
    # artifact selected by createMinecraftArtifacts first so the headless
    # fixture JVM uses the same authlib API as the target client.
    $exactRuntimeJars = @()
    $artifactManifest = Join-Path $ProjectDir "build\tmp\createMinecraftArtifacts\nfrt_artifact_manifest.properties"
    if (Test-Path -LiteralPath $artifactManifest) {
        $authlibLine = Get-Content -LiteralPath $artifactManifest |
            Where-Object { $_ -match '^com\.mojang\\:authlib\\:(?<version>[^=]+)=' } |
            Select-Object -First 1
        if ($authlibLine -and $authlibLine -match '^com\.mojang\\:authlib\\:(?<version>[^=]+)=') {
            $authlibVersion = $Matches['version']
            $authlibRoot = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.mojang\authlib\$authlibVersion"
            $authlibJar = Get-ChildItem -Path $authlibRoot -Recurse -Filter "authlib-$authlibVersion.jar" -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike '*sources*' } |
                Select-Object -First 1
            if ($authlibJar) {
                $exactRuntimeJars += $authlibJar.FullName
            }
        }
    }
    $classpath = @($classes, $resources, $minecraftJar.FullName) + $remappedModJars + $exactRuntimeJars + $overriddenClasspath -join ";"

    $javacArgs = Join-Path $tempDir "javac.args"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines($javacArgs, @(
        "-encoding", "UTF-8", "-proc:none", "-cp", ('"' + ($classpath -replace '\\', '/') + '"'),
        "-d", ('"' + ($tempDir -replace '\\', '/') + '"'), ('"' + ($sourceFile -replace '\\', '/') + '"')
    ), $utf8NoBom)
    & $JavacCommand "@$javacArgs"
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }

    $shortClasspathDir = Join-Path $tempDir "cp"
    New-Item -ItemType Directory -Path $shortClasspathDir -Force | Out-Null
    $jarIndex = 0
    foreach ($entry in $classpath.Split(";")) {
        if ([string]::IsNullOrWhiteSpace($entry) -or -not $entry.EndsWith(".jar") -or -not (Test-Path -LiteralPath $entry)) {
            continue
        }
        Copy-Item -LiteralPath $entry -Destination (Join-Path $shortClasspathDir ("lib{0:D4}.jar" -f $jarIndex)) -Force
        $jarIndex++
    }
    $shortClasspath = @($classes, $resources, (Join-Path $shortClasspathDir "*"), $tempDir) -join ";"
    & $JavaCommand -cp $shortClasspath SimpleTranslateJsonFixtureChecks $FixturePath
    if ($LASTEXITCODE -ne 0) {
        throw "translation fixture checks failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
    if (-not [string]::IsNullOrWhiteSpace($tempDir) -and (Test-Path -LiteralPath $tempDir)) {
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
        $resolvedTempDir = [System.IO.Path]::GetFullPath($tempDir)
        $leaf = [System.IO.Path]::GetFileName($resolvedTempDir)
        if ($resolvedTempDir.StartsWith($tempRoot + '\', [System.StringComparison]::OrdinalIgnoreCase) -and
                $leaf -match '^simpletranslate-json-fixtures-[0-9a-f-]+$') {
            Remove-Item -LiteralPath $resolvedTempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
