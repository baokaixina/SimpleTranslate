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

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("simpletranslate-json-fixtures-" + [System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $sourceFile = Join-Path $tempDir "SimpleTranslateJsonFixtureChecks.java"

    $javaSource = @'
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.simpletranslate.cache.ComponentJsonCacheEditor;
import com.yourname.simpletranslate.core.DynamicTextTemplate;
import com.yourname.simpletranslate.core.JsonPassthroughPipeline;
import com.yourname.simpletranslate.core.TranslationCacheKeys;
import com.yourname.simpletranslate.feature.sign.SignJsonDocument;
import com.yourname.simpletranslate.feature.sign.SignTranslationHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SimpleTranslateJsonFixtureChecks {
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            if (args.length != 1) {
                throw new IllegalArgumentException("Usage: SimpleTranslateJsonFixtureChecks <fixtures.json>");
            }
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
            assertEquals("component-json-fixtures-v1", root.get("version").getAsString(), "fixture version");
            assertTrue(root.getAsJsonArray("fixtures").size() >= 9, "surface fixture inventory");

            checkLooseComponentJsonAcceptance();
            checkCustomFontSanitizer();
            checkHoverEventsFrozenForVisibleRequests();
            checkInvalidResponsesRejected();
            checkJsonTextNodeEditing();
            checkSignPositionMapping();
            checkSignCacheAcceptance();
            checkCacheKeyMigrationShape();
            checkDynamicNumericTemplate();
            System.out.println("SimpleTranslate component JSON fixtures passed");
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
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
        assertNotNull(JsonPassthroughPipeline.deserializeComponents(fenced, originals), "markdown fence stripped");

        String extraOnly = "[{\"extra\":[{\"text\":\"\u4e2d\u6587\",\"color\":\"gold\"}]}]";
        List<Component> normalized = JsonPassthroughPipeline.deserializeComponents(extraOnly, originals);
        assertNotNull(normalized, "extra-only component gains an empty text root");
        assertEquals("\u4e2d\u6587", normalized.get(0).getString(), "extra-only translated text retained");

        String nestedExtraOnly = "[{\"extra\":[{\"extra\":[{\"text\":\"\u5d4c\u5957\u4e2d\u6587\"}]}]}]";
        List<Component> nested = JsonPassthroughPipeline.deserializeComponents(nestedExtraOnly, originals);
        assertNotNull(nested, "nested extra-only components normalized recursively");
        assertEquals("\u5d4c\u5957\u4e2d\u6587", nested.get(0).getString(), "nested translated text retained");
    }


    private static void checkCustomFontSanitizer() {
        Component source = Component.literal("Oak Wood Wand");
        List<Component> originals = List.of(source);
        String inheritedWynnFont = "[{\"text\":\"\",\"font\":\"minecraft:language/wynncraft\","
                + "\"extra\":[{\"text\":\"\u6a61\u6728\u6cd5\u6756\",\"color\":\"white\"}]}]";
        List<Component> inheritedSanitized = JsonPassthroughPipeline.deserializeComponents(inheritedWynnFont, originals);
        assertNotNull(inheritedSanitized, "inherited custom-font CJK response accepted");
        String inheritedJson = Component.Serializer.toJson(inheritedSanitized.get(0));
        assertTrue(inheritedJson.contains("minecraft:default"),
                "CJK child overrides inherited resource-pack custom font");
        assertTrue(inheritedJson.contains("\u6a61\u6728\u6cd5\u6756"),
                "CJK child text remains visible after inherited font override");
    }
    @SuppressWarnings("unchecked")
    private static void checkHoverEventsFrozenForVisibleRequests() throws Exception {
        Component source = Component.literal("Open Menu").withStyle(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Hidden Skill Details"))));
        String requestJson = JsonPassthroughPipeline.serializeComponents(List.of(source));
        assertTrue(!requestJson.contains("hoverEvent"), "ordinary request strips hidden hover event");
        assertTrue(!requestJson.contains("Hidden Skill Details"), "ordinary request strips hidden hover text");

        Method reattach = JsonPassthroughPipeline.class.getDeclaredMethod(
                "reattachOriginalHoverEvents", List.class, List.class);
        reattach.setAccessible(true);
        List<Component> restored = (List<Component>) reattach.invoke(
                null, List.of(Component.literal("\u6253\u5f00\u83dc\u5355")), List.of(source));
        String restoredJson = Component.Serializer.toJson(restored.get(0));
        assertTrue(restoredJson.contains("hoverEvent"), "translated visible component regains hover event");
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

    private static void checkCacheKeyMigrationShape() {
        String sourceJson = "[{\"text\":\"Hello\"}]";
        String current = TranslationCacheKeys.componentJsonKey("chat.context.direct", sourceJson);
        String contextual = TranslationCacheKeys.componentJsonKey(
                "chat.context.direct", sourceJson, "Earlier line establishes the speaker.");
        String legacy = TranslationCacheKeys.legacyComponentJsonKey("chat.context.direct", sourceJson);
        assertTrue(current.startsWith("stx2:chat.context.direct:"), "current key keeps original surface lane");
        assertTrue(current.contains(":fmt=component_json_v1:"), "current key has JSON format marker");
        assertTrue(!contextual.equals(current), "contextual JSON requests use a distinct cache key");
        assertTrue(!contextual.contains(":ctx=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855:"),
                "non-empty context hash is retained");
        assertTrue(legacy.startsWith("stx2:json.chat.context.direct:"), "legacy JSON key remains discoverable");
        assertTrue(!legacy.contains(":fmt="), "legacy key shape remains byte-compatible");
    }

    private static void checkDynamicNumericTemplate() {
        DynamicTextTemplate template = DynamicTextTemplate.capture(
                Component.literal("Wave 12/20").withStyle(ChatFormatting.AQUA));
        assertTrue(template.hasValues(), "numeric template captures values");
        assertEquals("Wave \u27e61000\u27e7/\u27e61001\u27e7", template.normalizedText(), "numeric markers");
        Component restored = template.restore(Component.literal(
                "\u6ce2\u6b21 \u27e61000\u27e7/\u27e61001\u27e7").withStyle(ChatFormatting.RED));
        assertNotNull(restored, "numeric markers restore");
        assertEquals("\u6ce2\u6b21 12/20", restored.getString(), "dynamic values restored");
        assertNull(template.restore(Component.literal(
                "\u6ce2\u6b21 \u27e61000\u27e7/\u27e61000\u27e7")), "duplicate marker rejected");
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
    $gradleClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    $minecraftVersion = (Get-Content -LiteralPath (Join-Path $ProjectDir "gradle.properties") |
        Where-Object { $_ -match "^minecraft_version=(.+)$" } |
        ForEach-Object { $Matches[1].Trim() } |
        Select-Object -First 1)
    $minecraftJar = Get-ChildItem -Path (Join-Path $ProjectDir ".gradle\loom-cache\minecraftMaven\net\minecraft") -Recurse -Filter "*$minecraftVersion*.jar" |
        Where-Object { $_.Name -like "minecraft-merged*" -and $_.Name -notmatch "sources|javadoc" } |
        Select-Object -First 1
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
    $classpath = @($classes, $resources, $minecraftJar.FullName) + $remappedModJars + @($gradleClasspath) -join ";"

    $javacArgs = Join-Path $tempDir "javac.args"
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines($javacArgs, @(
        "-encoding", "UTF-8", "-proc:none", "-cp", ('"' + ($classpath -replace '\\', '/') + '"'),
        "-d", ('"' + ($tempDir -replace '\\', '/') + '"'), ('"' + ($sourceFile -replace '\\', '/') + '"')
    ), $utf8NoBom)
    & javac "@$javacArgs"
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
    & java -cp $shortClasspath SimpleTranslateJsonFixtureChecks $FixturePath
    if ($LASTEXITCODE -ne 0) {
        throw "translation fixture checks failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
