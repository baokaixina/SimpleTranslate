package com.yourname.simpletranslate.util;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.cache.TranslationCache;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.translation.TranslationManager;
import com.yourname.simpletranslate.translation.TranslationRequestQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewritten sign translation pipeline.
 */
public final class SignTranslationHelper {

    private static final Pattern INTERNAL_MARKER_PATTERN = Pattern.compile("@{3}[A-Z0-9_]+@{3}", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_LABEL_PATTERN = Pattern.compile(
            "(?is)^(?:translation|translated\\s*text|output|answer|final\\s*answer|result|\\u8bd1\\u6587|\\u7ffb\\u8bd1|\\u8f93\\u51fa|\\u7b54\\u6848)\\s*[:\\uFF1A\\-]\\s*");
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");

    private static final int MAX_SIGN_SCAN_PER_SIDE = 48;
    private static final int MAX_CONTEXTS_PER_BATCH = 24;
    private static final int MAX_BATCH_SOURCE_CHARS = 3600;
    private static final int MAX_SIGN_CACHE_SIZE = 1200;
    private static final int MAX_IDENTITY_CACHE_SIZE = 1200;
    private static final long IDENTITY_CACHE_TTL_MS = 120_000L;
    private static final long FAILURE_RETRY_MS = 5000L;

    public static final String TRANSLATING_MARKER = "\u00a7e\u7ffb\u8bd1\u4e2d...";

    private static final Map<Integer, SignTextIdentityData> SIGN_TEXT_IDENTITY_MAP = new ConcurrentHashMap<>();
    private static final Map<String, Component[]> SIGN_COMPONENT_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Component[]> SHARED_SIGN_COMPONENT_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_SIGN_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PENDING_BATCH_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SIGN_MODE_EPOCH = new AtomicLong();

    private SignTranslationHelper() {
    }

    public static void registerSignTextByIdentity(
            int identityHash,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            boolean isTranslating
    ) {
        registerSignTextByIdentity(identityHash, pos, front, translatedLines, isTranslating, 90);
    }

    public static void registerSignTextByIdentity(
            int identityHash,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            boolean isTranslating,
            int maxTextLineWidth
    ) {
        registerSignTextByIdentity(identityHash, pos, front, translatedLines, null, isTranslating, maxTextLineWidth);
    }

    public static void registerSignTextByIdentity(
            int identityHash,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            Component[] translatedComponents,
            boolean isTranslating,
            int maxTextLineWidth
    ) {
        String[] copy = translatedLines == null ? null : Arrays.copyOf(translatedLines, translatedLines.length);
        SIGN_TEXT_IDENTITY_MAP.put(identityHash,
                new SignTextIdentityData(pos, front, copy, copyComponents(translatedComponents), isTranslating, maxTextLineWidth));
        pruneIdentityMap();
    }

    public static SignTextIdentityData getSignTextDataByIdentity(int identityHash) {
        return SIGN_TEXT_IDENTITY_MAP.get(identityHash);
    }

    public static String[] getTranslatedLines(SignBlockEntity sign, boolean front, Level level) {
        return getTranslatedLinesWithState(sign, front, level).lines;
    }

    public static String[] readSignLinesForSelection(SignBlockEntity sign, boolean front) {
        return sign == null ? new String[0] : readSignLines(sign.getText(front));
    }

    public static boolean hasEnglishOnSelectionLines(String[] lines) {
        return containsEnglishOnLines(lines);
    }

    public static String createSelectionSignature(String[] lines) {
        return createSignature(lines);
    }

    public static long signModeEpochForTest() {
        return currentSignModeEpoch();
    }

    public static long bumpSignModeEpochForTest() {
        return SIGN_MODE_EPOCH.incrementAndGet();
    }

    public static void handleSignSettingsChanged(ModConfig.SignContextMode previousMode,
                                                 ModConfig.SignContextMode nextMode) {
        long epoch = SIGN_MODE_EPOCH.incrementAndGet();
        clearTransientState();

        int canceledAuto = TranslationRequestQueue.cancelSurfacePrefix("sign.auto");
        int canceledManual = TranslationRequestQueue.cancelSurfacePrefix("sign.manual");
        SimpleTranslateMod.getLogger().info(
                "Sign translation settings changed previousMode={} nextMode={} epoch={} canceledAuto={} canceledManual={}",
                previousMode, nextMode, epoch, canceledAuto, canceledManual);
    }

    public static void requestManualContextTranslation(List<ManualSignContext> manualContexts, Consumer<Boolean> completed) {
        if (ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
            completed.accept(false);
            return;
        }

        long modeEpoch = currentSignModeEpoch();
        List<SignContext> contexts = new ArrayList<>();
        if (manualContexts != null) {
            for (ManualSignContext context : manualContexts) {
                if (context != null && context.sourceLines != null) {
                    contexts.add(new SignContext(context.pos, context.front, context.sourceLines,
                            readCurrentSignComponents(context.pos, context.front, context.sourceLines),
                            context.selectionIndex));
                }
            }
        }
        List<SignContext> orderedContexts = orderManualContextsByPanel(contexts);
        if (orderedContexts.isEmpty() || orderedContexts.stream().noneMatch(context -> containsEnglishOnLines(context.sourceLines))) {
            completed.accept(false);
            return;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            completed.accept(false);
            return;
        }

        String batchKey = "manual:" + createBatchKey(orderedContexts);
        if (!PENDING_BATCH_KEYS.add(batchKey)) {
            completed.accept(false);
            return;
        }
        for (SignContext context : orderedContexts) {
            PENDING_SIGN_KEYS.add(context.signStateKey);
        }
        requestManualGroupDirect(orderedContexts, batchKey, modeEpoch).whenComplete((success, error) -> {
            if (!isSignModeEpochCurrent(modeEpoch)
                    || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                completed.accept(false);
                return;
            }
            if (error != null) {
                SimpleTranslateMod.getLogger().error("Manual sign translation batch failed", error);
                markFailure("sign.manual.group.by_id.direct", orderedContexts);
                completed.accept(false);
                return;
            }
            completed.accept(Boolean.TRUE.equals(success));
        });
    }

    public static boolean isSignTranslating(BlockPos pos, boolean front) {
        String prefix = createSignLookupPrefix(pos, front);
        for (String key : PENDING_SIGN_KEYS) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String sanitizeLineForRender(String translatedLine, String originalLine) {
        return sanitizeLineInternal(translatedLine, originalLine);
    }

    public static boolean containsEnglish(String text) {
        return text != null && !text.isEmpty() && TooltipTranslationHelper.containsEnglish(text);
    }

    public static void clearSignCache(BlockPos pos) {
        String frontPrefix = createSignLookupPrefix(pos, true);
        String backPrefix = createSignLookupPrefix(pos, false);

        SIGN_COMPONENT_CACHE.keySet().removeIf(key -> key.startsWith(frontPrefix) || key.startsWith(backPrefix));
        SHARED_SIGN_COMPONENT_CACHE.keySet().removeIf(key -> key.startsWith(frontPrefix) || key.startsWith(backPrefix));
        PENDING_SIGN_KEYS.removeIf(key -> key.startsWith(frontPrefix) || key.startsWith(backPrefix));
    }

    public static void clearAllCache() {
        clearTransientState();
        SHARED_SIGN_COMPONENT_CACHE.clear();
    }

    private static void clearTransientState() {
        SIGN_TEXT_IDENTITY_MAP.clear();
        SIGN_COMPONENT_CACHE.clear();
        PENDING_SIGN_KEYS.clear();
        PENDING_BATCH_KEYS.clear();
    }

    public static TranslationResult getTranslatedLinesWithState(SignBlockEntity sign, boolean front, Level level) {
        return getTranslatedLinesWithState(sign, front, level, true);
    }

    public static TranslationResult getTranslatedLinesWithState(SignBlockEntity sign, boolean front, Level level,
                                                               boolean allowAutoRequest) {
        if (sign == null || level == null) {
            return new TranslationResult(null, false);
        }

        String[] originalLines = readSignLines(sign.getText(front));
        if (!containsEnglishOnLines(originalLines)) {
            return new TranslationResult(null, false);
        }

        String signStateKey = createSignStateKey(currentDimensionId(), sign.getBlockPos(), front, originalLines);
        Component[] byComponents = SIGN_COMPONENT_CACHE.get(signStateKey);
        if (byComponents != null) {
            return new TranslationResult(componentStrings(byComponents), copyComponents(byComponents), false);
        }
        Component[] sharedComponents = getSharedSignComponents(sign.getBlockPos(), front, originalLines);
        if (sharedComponents != null) {
            SIGN_COMPONENT_CACHE.put(signStateKey, copyComponents(sharedComponents));
            return new TranslationResult(componentStrings(sharedComponents), copyComponents(sharedComponents), false);
        }

        if (PENDING_SIGN_KEYS.contains(signStateKey)) {
            return new TranslationResult(null, true);
        }

        if (ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() == ModConfig.SignContextMode.MANUAL) {
            return new TranslationResult(null, false);
        }

        if (!allowAutoRequest) {
            return new TranslationResult(null, false);
        }

        long modeEpoch = currentSignModeEpoch();
        SignContext currentContext = new SignContext(sign.getBlockPos(), front, originalLines,
                readSignComponents(sign.getText(front)), 0L);

        String batchKey = "auto:" + signStateKey;
        if (!PENDING_BATCH_KEYS.add(batchKey)) {
            return new TranslationResult(null, PENDING_SIGN_KEYS.contains(signStateKey));
        }

        PENDING_SIGN_KEYS.add(signStateKey);
        requestAutoSingleDirect(currentContext, batchKey, modeEpoch);

        return new TranslationResult(null, true);
    }

    private static CompletableFuture<Boolean> requestAutoSingleDirect(SignContext context,
                                                                      String batchKey,
                                                                      long modeEpoch) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            clearBatchState(batchKey, List.of(context), modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        SignDirectDocument.Document document = buildAutoSignDocument(context);
        if (document == null || !document.hasEnglish()) {
            clearBatchState(batchKey, List.of(context), modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(document.cacheKey()).orElse(null);
            if (cached != null) {
                SignDirectDocument.RestoreResult cachedResult = document.restore(cached);
                if (cachedResult.success() && cachedResult.fullyTranslated()
                        && applySignDocument(List.of(context), cachedResult, "Auto")) {
                    pruneCaches();
                    clearBatchState(batchKey, List.of(context), modeEpoch);
                    return CompletableFuture.completedFuture(true);
                }
                cache.remove(document.cacheKey());
                cache.save();
            }
        }

        TranslationTask task = TranslationTask.create(document.surface(), document.sourceText(), document.context(),
                document.layoutSignature(), document.styleSignature(), List.of());
        TranslationLane lane = TranslationLanes.forSurface(document.surface());
        if (!lane.begin(task, FAILURE_RETRY_MS)) {
            SimpleTranslateMod.getLogger().debug(
                    "Auto sign by-id direct skipped reason=pending-or-cooldown key={} source={}",
                    Integer.toHexString(document.cacheKey().hashCode()), document.sourceSummary());
            clearBatchState(batchKey, List.of(context), modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        return manager.translateFormattedDocument(document.requestPayload(), document.surface())
                .handle((translatedDocument, error) -> {
                    try {
                        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                                || !isSignModeEpochCurrent(modeEpoch)
                                || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.AUTO) {
                            SimpleTranslateMod.getLogger().debug(
                                    "Auto sign direct ignored stale response sign={} front={} epoch={} currentEpoch={}",
                                    context.pos.toShortString(), context.front, modeEpoch, currentSignModeEpoch());
                            lane.finish(task);
                            return false;
                        }
                        if (error != null || translatedDocument == null || translatedDocument.isBlank()) {
                            lane.fail(task, FAILURE_RETRY_MS);
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct failed reason={} key={} source={}",
                                    error == null ? "blank-response" : error.getClass().getSimpleName(),
                                    document.cacheKey(), document.sourceSummary(), error);
                            return false;
                        }
                        SignDirectDocument.RestoreResult result = document.restore(translatedDocument);
                        if (!result.success()) {
                            lane.fail(task, FAILURE_RETRY_MS);
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct rejected reason={} key={} source={}",
                                    result.failureReason(), document.cacheKey(), document.sourceSummary());
                            return false;
                        }
                        if (!applySignDocument(List.of(context), result, "Auto")) {
                            lane.fail(task, FAILURE_RETRY_MS);
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct rejected reason=writeback-mismatch key={} source={}",
                                    document.cacheKey(), document.sourceSummary());
                            return false;
                        }
                        if (cache != null && ModConfig.CACHE_ENABLED.get() && result.fullyTranslated()) {
                            String payload = result.canonicalPayload();
                            if (payload != null && !payload.isBlank()) {
                                cache.put(document.cacheKey(), payload, document.sourceText(),
                                        TranslationCache.displayTextFromValue(payload));
                                cache.save();
                            }
                        }
                        lane.finish(task);
                        pruneCaches();
                        return true;
                    } finally {
                        clearBatchState(batchKey, List.of(context), modeEpoch);
                    }
                });
    }

    private static CompletableFuture<Boolean> requestManualGroupDirect(List<SignContext> contexts,
                                                                       String batchKey,
                                                                       long modeEpoch) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            markFailure("sign.manual.group.by_id.direct", contexts);
            clearBatchState(batchKey, contexts, modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        SignDirectDocument.Document document = buildManualSignDocument(contexts);
        if (document == null || !document.hasEnglish()) {
            clearBatchState(batchKey, contexts, modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String cached = cache.get(document.cacheKey()).orElse(null);
            if (cached != null) {
                SignDirectDocument.RestoreResult cachedResult = document.restore(cached);
                if (cachedResult.success() && cachedResult.fullyTranslated()
                        && applyManualSignDocument(contexts, cachedResult)) {
                    pruneCaches();
                    clearBatchState(batchKey, contexts, modeEpoch);
                    return CompletableFuture.completedFuture(true);
                }
                cache.remove(document.cacheKey());
                cache.save();
            }
        }

        TranslationTask task = TranslationTask.create(document.surface(), document.sourceText(), document.context(),
                document.layoutSignature(), document.styleSignature(), List.of());
        TranslationLane lane = TranslationLanes.forSurface(document.surface());
        if (!lane.begin(task, FAILURE_RETRY_MS)) {
            SimpleTranslateMod.getLogger().debug(
                    "Manual sign by-id direct skipped reason=pending-or-cooldown key={} source={}",
                    Integer.toHexString(document.cacheKey().hashCode()), document.sourceSummary());
            clearBatchState(batchKey, contexts, modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        return manager.translateFormattedDocument(document.requestPayload(), document.surface())
                .handle((translatedDocument, error) -> {
                    if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                            || !isSignModeEpochCurrent(modeEpoch)
                            || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                        SimpleTranslateMod.getLogger().debug(
                                "Manual sign by-id direct ignored stale response epoch={} currentEpoch={} signs={}",
                                modeEpoch, currentSignModeEpoch(), contexts.size());
                        lane.finish(task);
                        return false;
                    }
                    if (error != null || translatedDocument == null || translatedDocument.isBlank()) {
                        lane.fail(task, FAILURE_RETRY_MS);
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign by-id direct failed reason={} key={} source={}",
                                error == null ? "blank-response" : error.getClass().getSimpleName(),
                                document.cacheKey(), document.sourceSummary(), error);
                        return false;
                    }
                    SignDirectDocument.RestoreResult result = document.restore(translatedDocument);
                    if (!result.success()) {
                        lane.fail(task, FAILURE_RETRY_MS);
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign by-id direct rejected reason={} key={} signs={} source={}",
                                result.failureReason(), document.cacheKey(), contexts.size(), document.sourceSummary());
                        return false;
                    }
                    if (!applyManualSignDocument(contexts, result)) {
                        lane.fail(task, FAILURE_RETRY_MS);
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign by-id direct rejected reason=writeback-mismatch key={} signs={} source={}",
                                document.cacheKey(), contexts.size(), document.sourceSummary());
                        return false;
                    }
                    if (cache != null && ModConfig.CACHE_ENABLED.get() && result.fullyTranslated()) {
                        String payload = result.canonicalPayload();
                        if (payload != null && !payload.isBlank()) {
                            cache.put(document.cacheKey(), payload, document.sourceText(),
                                    TranslationCache.displayTextFromValue(payload));
                            cache.save();
                        }
                    }
                    lane.finish(task);
                    return true;
                })
                .thenApply(success -> {
                    if (!isSignModeEpochCurrent(modeEpoch)
                            || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                        return false;
                    }
                    if (Boolean.TRUE.equals(success)) {
                        pruneCaches();
                        return true;
                    }
                    pruneCaches();
                    return false;
                })
                .whenComplete((success, error) -> clearBatchState(batchKey, contexts, modeEpoch));
    }

    private static SignDirectDocument.Document buildManualSignDocument(List<SignContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return null;
        }
        List<SignDirectDocument.Entry> entries = new ArrayList<>();
        for (SignContext context : contexts) {
            entries.add(new SignDirectDocument.Entry(context.signId, context.signStateKey, context.sourceComponents,
                    context.sourceLines, context.selectionIndex, context.front));
        }
        return SignDirectDocument.fromEntries(entries,
                "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id",
                buildManualGroupContext(contexts));
    }

    private static SignDirectDocument.Document buildAutoSignDocument(SignContext context) {
        if (context == null) {
            return null;
        }
        SignDirectDocument.Entry entry = new SignDirectDocument.Entry(context.signId, context.signStateKey,
                context.sourceComponents, context.sourceLines, context.selectionIndex, context.front);
        return SignDirectDocument.fromEntries(List.of(entry),
                "sign.auto.single.direct",
                "sign-auto-single-by-id",
                buildAutoSignContext(context));
    }

    private static boolean applyManualSignDocument(List<SignContext> contexts,
                                                   SignDirectDocument.RestoreResult result) {
        return applySignDocument(contexts, result, "Manual");
    }

    private static boolean applySignDocument(List<SignContext> contexts,
                                             SignDirectDocument.RestoreResult result,
                                             String logPrefix) {
        if (contexts == null || contexts.isEmpty() || result == null || !result.success()
                || result.componentsBySignId() == null) {
            return false;
        }
        for (SignContext context : contexts) {
            Component[] components = result.componentsBySignId().get(context.signId);
            if (components == null || components.length != 4) {
                SimpleTranslateMod.getLogger().warn(
                        "{} sign by-id writeback missing signId={} sign={} source={}",
                        logPrefix, context.signId, context.pos.toShortString(),
                        sourceTextForContext(context.sourceLines).replace('\n', ' '));
                return false;
            }
        }
        for (SignContext context : contexts) {
            Component[] components = copyComponents(result.componentsBySignId().get(context.signId));
            SIGN_COMPONENT_CACHE.put(context.signStateKey, components);
            putSharedSignComponents(context, components);
        }
        return true;
    }

    private static Component sourceComponentAt(SignContext context, int lineIndex) {
        if (context != null && context.sourceComponents != null
                && lineIndex >= 0 && lineIndex < context.sourceComponents.size()
                && context.sourceComponents.get(lineIndex) != null) {
            return context.sourceComponents.get(lineIndex);
        }
        String text = sourceTextAt(context, lineIndex);
        return text.isEmpty() ? Component.empty() : Component.literal(text);
    }

    private static String sourceTextAt(SignContext context, int lineIndex) {
        if (context == null || context.sourceLines == null || lineIndex < 0 || lineIndex >= context.sourceLines.length) {
            return "";
        }
        return stripFormattingCodes(context.sourceLines[lineIndex]).trim();
    }

    private static List<Component> signLineComponents(String[] lines) {
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String line = i < length(lines) && lines[i] != null ? stripFormattingCodes(lines[i]).trim() : "";
            components.add(Component.literal(line));
        }
        return components;
    }

    private static String buildAutoSignContext(SignContext target) {
        StringBuilder context = new StringBuilder();
        context.append("Automatic sign mode translates only this one sign. ");
        context.append("Do not infer, copy, or borrow text from nearby signs. ");
        context.append("Treat the four visible lines below as one complete sign message, not four isolated sentences. ");
        context.append("Use all four lines to understand each line's meaning, especially wrapped phrases, menu labels, lore, and instructions. ");
        context.append("Translate the complete sign message as a single semantic block, then distribute the target-language text back across the same four line indexes in display order. ");
        context.append("Keep exactly four fixed lines and preserve every returned <line i> and <run id>; never add, remove, split, or merge lines/runs. ");
        context.append("Keep commands, gamerules, coordinates, version numbers, and numbered headings anchored to this same sign.\n");
        context.append("Target sign semantic block:\n");
        context.append(buildSignContextText(List.of(target))).append('\n');
        return context.toString();
    }

    private static String buildManualGroupContext(List<SignContext> contexts) {
        StringBuilder context = new StringBuilder();
        context.append("Manual selected signs are grouped by spatial sign panels. ");
        context.append("Translate the whole selected panel text with shared context, then place the Chinese text back into the same signId/line slots. ");
        context.append("Spatial panel reading order below is authoritative: read rows from top to bottom, and within each row from visual left to visual right. ");
        context.append("Numbered headings such as '6.' start a semantic block; following signs without a new number continue that block. ");
        context.append("Keep every sign as exactly four fixed lines. ");
        context.append("Every sign has a stable signId. You may reorder <sign> blocks, but each returned sign must keep the exact original signId once. ");
        context.append("Never duplicate earlier text into later signs. Never summarize. ");
        context.append("Keep protected anchors such as version numbers, commands, gamerules, and numbered headings in their source signId/line. ");
        context.append("The panel order below is the final display order.\n");
        context.append(buildSpatialPanelContext(contexts));
        return context.toString();
    }

    private static String buildSpatialPanelContext(List<SignContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder(contexts.size() * 120);
        String currentPanel = "";
        int panelIndex = 0;
        int panelSignIndex = 0;
        String currentSemanticBlock = "";
        for (int i = 0; i < contexts.size(); i++) {
            SignContext signContext = contexts.get(i);
            if (signContext == null || signContext.sourceLines == null) {
                continue;
            }
            String panelKey = signContext.panelKey();
            if (!panelKey.equals(currentPanel)) {
                currentPanel = panelKey;
                panelIndex++;
                panelSignIndex = 0;
                if (context.length() > 0) {
                    context.append('\n');
                }
                context.append("panel ").append(panelIndex)
                        .append(" facing=").append(signContext.facing)
                        .append(" front=").append(signContext.front)
                        .append(" plane=").append(signContext.panelPlane)
                        .append(":\n");
            }
            panelSignIndex++;
            String semanticBlock = detectSemanticBlock(signContext, currentSemanticBlock);
            if (!semanticBlock.equals(currentSemanticBlock)) {
                currentSemanticBlock = semanticBlock;
            }
            context.append("  sign ").append(panelSignIndex)
                    .append(" id=").append(signContext.signId)
                    .append(" selectionIndex=").append(signContext.selectionIndex)
                    .append(" row=").append(signContext.panelRow)
                    .append(" column=").append(signContext.panelColumn)
                    .append(" semanticBlock=").append(currentSemanticBlock.isBlank() ? "none" : currentSemanticBlock)
                    .append(":\n");
            for (int line = 0; line < 4; line++) {
                context.append("    line ").append(line + 1).append(": ")
                        .append(sourceTextAt(signContext, line))
                        .append('\n');
            }
        }
        return context.toString().trim();
    }

    private static String detectSemanticBlock(SignContext context, String previousBlock) {
        if (context == null || context.sourceLines == null) {
            return previousBlock == null ? "" : previousBlock;
        }
        for (int line = 0; line < 4; line++) {
            String text = sourceTextAt(context, line);
            if (text.isBlank()) {
                continue;
            }
            Matcher matcher = Pattern.compile("^\\s*(\\d+)\\s*[.)]").matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
            break;
        }
        return previousBlock == null ? "" : previousBlock;
    }

    private static String buildSignContextText(List<SignContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder(contexts.size() * 80);
        for (int i = 0; i < contexts.size(); i++) {
            SignContext signContext = contexts.get(i);
            if (signContext == null || signContext.sourceLines == null) {
                continue;
            }
            if (context.length() > 0) {
                context.append('\n');
            }
            context.append("sign ").append(i + 1)
                    .append(" id=").append(signContext.signId)
                    .append(" selectionIndex=").append(signContext.selectionIndex)
                    .append(signContext.front ? " front" : " back").append(":\n");
            for (int line = 0; line < 4; line++) {
                context.append("  line ").append(line + 1).append(": ")
                        .append(sourceTextAt(signContext, line))
                        .append('\n');
            }
        }
        return context.toString().trim();
    }

    private static String summarizeContexts(List<SignContext> contexts) {
        String summary = buildSignContextText(contexts).replace('\n', ' ').trim();
        return summary.length() > 160 ? summary.substring(0, 157) + "..." : summary;
    }

    private static List<SignContext> orderManualContextsByPanel(List<SignContext> contexts) {
        if (contexts == null || contexts.size() <= 1) {
            return contexts == null ? List.of() : contexts;
        }
        List<SignContext> ordered = new ArrayList<>(contexts);
        ordered.sort(Comparator
                .comparing(SignContext::panelKey)
                .thenComparingInt(context -> context.panelRow)
                .thenComparingInt(context -> context.panelColumn)
                .thenComparingLong(context -> context.selectionIndex));
        return ordered;
    }

    private static String sourceTextForContext(String[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                source.append('\n');
            }
            source.append(i < lines.length && lines[i] != null ? stripFormattingCodes(lines[i]).trim() : "");
        }
        return source.toString();
    }

    private static int length(String[] lines) {
        return lines == null ? 0 : lines.length;
    }

    private static void clearBatchState(String batchKey, List<SignContext> contexts) {
        clearBatchState(batchKey, contexts, currentSignModeEpoch());
    }

    private static void clearBatchState(String batchKey, List<SignContext> contexts, long modeEpoch) {
        PENDING_BATCH_KEYS.remove(batchKey);
        if (!isSignModeEpochCurrent(modeEpoch)) {
            return;
        }
        if (contexts != null) {
            for (SignContext context : contexts) {
                PENDING_SIGN_KEYS.remove(context.signStateKey);
            }
        }
    }

    private static List<SignContext> collectContextsAround(SignBlockEntity centerSign, boolean centerFront, Level level) {
        return collectContextsAround(centerSign, centerFront, level, true);
    }

    private static List<SignContext> collectContextsAround(SignBlockEntity centerSign, boolean centerFront, Level level,
                                                           boolean pendingOnly) {
        BlockPos centerPos = centerSign.getBlockPos();
        List<SignBlockEntity> orderedSigns = collectContiguousSigns(level, centerSign, centerPos);

        List<SignContext> contexts = new ArrayList<>();
        for (SignBlockEntity sign : orderedSigns) {
            BlockPos pos = sign.getBlockPos();

            if (hasEnglishText(sign, true)) {
                SignText text = sign.getText(true);
                String[] lines = readSignLines(text);
                contexts.add(new SignContext(pos, true, lines, readSignComponents(text), contexts.size()));
            }
            if (hasEnglishText(sign, false)) {
                SignText text = sign.getText(false);
                String[] lines = readSignLines(text);
                contexts.add(new SignContext(pos, false, lines, readSignComponents(text), contexts.size()));
            }
        }

        if (contexts.isEmpty()) {
            return contexts;
        }

        List<SignContext> trimmed = limitContextsAroundCenter(contexts, centerPos, centerFront);
        List<SignContext> filtered = new ArrayList<>();
        for (SignContext context : trimmed) {
            if (pendingOnly && SIGN_COMPONENT_CACHE.containsKey(context.signStateKey)) {
                continue;
            }
            filtered.add(context);
        }
        return filtered;
    }

    private static List<SignBlockEntity> collectContiguousSigns(Level level, SignBlockEntity centerSign, BlockPos centerPos) {
        List<SignBlockEntity> ordered = new ArrayList<>();
        List<SignBlockEntity> above = new ArrayList<>();

        for (int i = 1; i <= MAX_SIGN_SCAN_PER_SIDE; i++) {
            SignBlockEntity adjacent = getSignAt(level, centerPos.above(i));
            if (adjacent == null) {
                break;
            }
            above.add(0, adjacent);
        }

        ordered.addAll(above);
        ordered.add(centerSign);

        for (int i = 1; i <= MAX_SIGN_SCAN_PER_SIDE; i++) {
            SignBlockEntity adjacent = getSignAt(level, centerPos.below(i));
            if (adjacent == null) {
                break;
            }
            ordered.add(adjacent);
        }

        return ordered;
    }

    private static List<SignContext> limitContextsAroundCenter(List<SignContext> contexts, BlockPos centerPos, boolean centerFront) {
        int centerIndex = findCenterContextIndex(contexts, centerPos, centerFront);
        if (centerIndex < 0) {
            centerIndex = findCenterContextIndex(contexts, centerPos, !centerFront);
        }
        if (centerIndex < 0) {
            centerIndex = contexts.size() / 2;
        }

        int start = 0;
        int end = contexts.size() - 1;
        int localCenter = centerIndex;

        while (start <= localCenter && localCenter <= end &&
                ((end - start + 1) > MAX_CONTEXTS_PER_BATCH
                        || estimateBatchChars(contexts, start, end) > MAX_BATCH_SOURCE_CHARS)) {
            int leftDistance = localCenter - start;
            int rightDistance = end - localCenter;
            if (rightDistance >= leftDistance) {
                end--;
            } else {
                start++;
                localCenter--;
            }
        }

        if (start == 0 && end == contexts.size() - 1) {
            return contexts;
        }
        return new ArrayList<>(contexts.subList(start, end + 1));
    }

    private static int findCenterContextIndex(List<SignContext> contexts, BlockPos centerPos, boolean front) {
        for (int i = 0; i < contexts.size(); i++) {
            SignContext context = contexts.get(i);
            if (context.front == front && context.pos.equals(centerPos)) {
                return i;
            }
        }
        return -1;
    }

    private static int estimateBatchChars(List<SignContext> contexts, int start, int end) {
        int total = 0;
        for (int i = start; i <= end; i++) {
            SignContext context = contexts.get(i);
            for (int line = 0; line < 4; line++) {
                total += 18;
                total += context.sourceLines[line].length();
                total += 1;
            }
        }
        return total;
    }

    private static String createBatchKey(List<SignContext> contexts) {
        StringBuilder key = new StringBuilder("sign_batch:");
        for (SignContext context : contexts) {
            key.append(context.signStateKey).append('|');
        }
        return key.toString();
    }

    private static long currentSignModeEpoch() {
        return SIGN_MODE_EPOCH.get();
    }

    private static boolean isSignModeEpochCurrent(long modeEpoch) {
        return modeEpoch == currentSignModeEpoch();
    }

    private static String createSignStateKey(String dimensionId, BlockPos pos, boolean front, String[] sourceLines) {
        return createSignStateKey(dimensionId, pos, front, createSignature(sourceLines));
    }

    private static String createSignStateKey(String dimensionId, BlockPos pos, boolean front, String signature) {
        return safeDimensionId(dimensionId) + "|" + (pos == null ? 0L : pos.asLong())
                + "_" + (front ? "F" : "B") + "_" + signature;
    }

    private static String createSignLookupPrefix(BlockPos pos, boolean front) {
        return safeDimensionId(currentDimensionId()) + "|" + (pos == null ? 0L : pos.asLong())
                + "_" + (front ? "F" : "B") + "_";
    }

    private static String createSharedSignKey(BlockPos pos, boolean front, String[] sourceLines) {
        return createSignStateKey(currentDimensionId(), pos, front, sourceLines);
    }

    private static Component[] getSharedSignComponents(BlockPos pos, boolean front, String[] sourceLines) {
        String key = createSharedSignKey(pos, front, sourceLines);
        Component[] cached = SHARED_SIGN_COMPONENT_CACHE.get(key);
        if (cached != null) {
            return copyComponents(cached);
        }
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache == null || !ModConfig.CACHE_ENABLED.get()) {
            return null;
        }
        String persistent = cache.get(createPersistentSignKey(pos, front, sourceLines)).orElse(null);
        Component[] parsed = deserializeSignComponents(persistent);
        if (parsed == null) {
            if (persistent != null) {
                cache.remove(createPersistentSignKey(pos, front, sourceLines));
                cache.save();
            }
            return null;
        }
        SHARED_SIGN_COMPONENT_CACHE.put(key, copyComponents(parsed));
        return parsed;
    }

    private static void putSharedSignComponents(SignContext context, Component[] components) {
        if (context == null || components == null || components.length != 4) {
            return;
        }
        String sharedKey = createSharedSignKey(context.pos, context.front, context.sourceLines);
        Component[] copy = copyComponents(components);
        SHARED_SIGN_COMPONENT_CACHE.put(sharedKey, copy);
        TranslationCache cache = SimpleTranslateMod.getTranslationCache();
        if (cache != null && ModConfig.CACHE_ENABLED.get()) {
            String serialized = serializeSignComponents(copy);
            if (serialized != null && !serialized.isBlank()) {
                cache.put(createPersistentSignKey(context.pos, context.front, context.sourceLines), serialized);
                cache.save();
            }
        }
    }

    private static String createSignId(String dimensionId, BlockPos pos, boolean front, String[] sourceLines) {
        BlockPos safePos = pos == null ? BlockPos.ZERO : pos;
        return safeDimensionId(dimensionId)
                + ":" + safePos.getX() + "," + safePos.getY() + "," + safePos.getZ()
                + ":" + (front ? "front" : "back")
                + ":" + createSignature(sourceLines);
    }

    private static Direction resolveSignFacing(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.level != null && pos != null) {
            BlockState state = minecraft.level.getBlockState(pos);
            Direction facing = state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING).orElse(null);
            if (facing != null) {
                return facing;
            }
            Integer rotation = state.getOptionalValue(BlockStateProperties.ROTATION_16).orElse(null);
            if (rotation != null) {
                return directionFromRotation(rotation);
            }
        }
        return Direction.NORTH;
    }

    private static Direction directionFromRotation(int rotation) {
        int quadrant = Math.floorMod(rotation + 2, 16) / 4;
        return switch (quadrant) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static int planeCoordinate(BlockPos pos, Direction facing) {
        if (pos == null || facing == null) {
            return 0;
        }
        return facing.getAxis() == Direction.Axis.X ? pos.getX() : pos.getZ();
    }

    private static int visualColumn(BlockPos pos, Direction facing) {
        if (pos == null || facing == null) {
            return 0;
        }
        Direction rightAxis = facing.getCounterClockWise();
        return rightAxis.getAxis() == Direction.Axis.X
                ? pos.getX() * rightAxis.getStepX()
                : pos.getZ() * rightAxis.getStepZ();
    }

    private static String createSignature(String[] lines) {
        StringBuilder signature = new StringBuilder();
        for (String line : lines) {
            signature.append(stripFormattingCodes(line)).append('\u0001');
        }
        return Integer.toHexString(signature.toString().hashCode());
    }

    private static String createPersistentSignKey(BlockPos pos, boolean front, String[] lines) {
        StringBuilder key = new StringBuilder();
        key.append(safeDimensionId(currentDimensionId())).append('|')
                .append(pos == null ? 0L : pos.asLong()).append('|').append(front ? "F" : "B").append('|');
        if (lines != null) {
            for (String line : lines) {
                key.append(stripFormattingCodes(line)).append('\u0001');
            }
        }
        return TranslationCacheKeys.key("sign.position", key.toString(),
                pos == null ? "" : pos.toShortString(), "sign-4-lines", front ? "front" : "back");
    }

    private static String currentDimensionId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.level != null && minecraft.level.dimension() != null
                && minecraft.level.dimension().location() != null) {
            return minecraft.level.dimension().location().toString();
        }
        return "unknown_dimension";
    }

    private static String safeDimensionId(String dimensionId) {
        return dimensionId == null || dimensionId.isBlank() ? "unknown_dimension" : dimensionId.trim();
    }

    private static boolean hasEnglishText(SignBlockEntity sign, boolean front) {
        return containsEnglishOnLines(readSignLines(sign.getText(front)));
    }

    private static boolean containsEnglishOnLines(String[] lines) {
        if (lines == null) {
            return false;
        }
        var blacklist = SimpleTranslateMod.getTranslationBlacklist();
        if (blacklist != null && blacklist.containsBlacklistedLine(lines)) {
            return false;
        }
        for (String line : lines) {
            if (containsEnglish(line)) {
                return true;
            }
        }
        return false;
    }

    private static SignBlockEntity getSignAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SignBlockEntity sign ? sign : null;
    }

    private static String[] readSignLines(SignText signText) {
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            Component message = signText.getMessage(i, false);
            lines[i] = message == null ? "" : message.getString();
        }
        return lines;
    }

    private static List<Component> readSignComponents(SignText signText) {
        List<Component> components = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Component message = signText == null ? null : signText.getMessage(i, false);
            components.add(message == null ? Component.empty() : message);
        }
        return components;
    }

    private static List<Component> readCurrentSignComponents(BlockPos pos, boolean front, String[] fallbackLines) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.level != null
                && pos != null && minecraft.level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            return readSignComponents(sign.getText(front));
        }
        return signLineComponents(fallbackLines);
    }

    private static Component[] copyComponents(Component[] components) {
        if (components == null) {
            return null;
        }
        return Arrays.copyOf(components, components.length);
    }

    /** Human-readable display text for position-level sign component caches. */
    public static String displayTextFromSignComponentsCache(String value) {
        Component[] components = deserializeSignComponents(value);
        if (components == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(components[i] == null ? "" : components[i].getString());
        }
        return builder.toString();
    }

    private static String serializeSignComponents(Component[] components) {
        if (components == null || components.length != 4) {
            return null;
        }
        StringBuilder builder = new StringBuilder("sign-components-v1");
        for (int i = 0; i < 4; i++) {
            builder.append('\n');
            String json = Component.Serializer.toJson(components[i] == null ? Component.empty() : components[i],
                    registryProvider());
            builder.append(Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return builder.toString();
    }

    private static Component[] deserializeSignComponents(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("\n", -1);
        if (parts.length != 5 || !"sign-components-v1".equals(parts[0])) {
            return null;
        }
        Component[] components = new Component[4];
        for (int i = 0; i < 4; i++) {
            try {
                String json = new String(Base64.getDecoder().decode(parts[i + 1]), java.nio.charset.StandardCharsets.UTF_8);
                Component component = Component.Serializer.fromJson(json, registryProvider());
                components[i] = component == null ? Component.empty() : component;
            } catch (Exception e) {
                return null;
            }
        }
        return components;
    }

    public static String serializeSharedSignComponentsForTest(Component[] components) {
        return serializeSignComponents(components);
    }

    public static Component[] deserializeSharedSignComponentsForTest(String value) {
        return deserializeSignComponents(value);
    }

    private static HolderLookup.Provider registryProvider() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.level != null) {
                return minecraft.level.registryAccess();
            }
        } catch (Exception ignored) {
        }
        return RegistryAccess.EMPTY;
    }

    private static String[] componentStrings(Component[] components) {
        String[] lines = new String[4];
        Arrays.fill(lines, "");
        if (components == null) {
            return lines;
        }
        for (int i = 0; i < lines.length && i < components.length; i++) {
            lines[i] = components[i] == null ? "" : components[i].getString();
        }
        return lines;
    }

    private static boolean shouldThrottle(String signStateKey) {
        TranslationTask task = throttleTask("sign.auto.single.direct", signStateKey);
        return TranslationLanes.forSurface("sign.auto.single.direct").isThrottled(task);
    }

    private static void markFailure(String surface, List<SignContext> contexts) {
        if (contexts == null || surface == null || surface.isBlank()) {
            return;
        }
        TranslationLane lane = TranslationLanes.forSurface(surface);
        for (SignContext context : contexts) {
            if (context == null) {
                continue;
            }
            lane.fail(throttleTask(surface, context.signStateKey), FAILURE_RETRY_MS);
        }
    }

    private static TranslationTask throttleTask(String surface, String signStateKey) {
        return TranslationTask.create(surface, signStateKey, "", signStateKey, signStateKey, List.of());
    }

    private static String sanitizeLineInternal(String translatedLine, String originalLine) {
        if (translatedLine == null) {
            return "";
        }

        String cleaned = ModelOutputSanitizer.sanitize(translatedLine);
        if (cleaned == null) {
            return "";
        }

        cleaned = THINK_BLOCK_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (cleaned.isEmpty()) {
            return "";
        }

        cleaned = cleaned.replace('\n', ' ').trim();

        cleaned = stripLeadingOutputLabels(cleaned);
        cleaned = stripInternalMarkers(cleaned);
        cleaned = trimWrapped(cleaned, '"', '"');
        cleaned = trimWrapped(cleaned, '\'', '\'');
        cleaned = trimWrapped(cleaned, '(', ')');
        cleaned = trimWrapped(cleaned, '\u201c', '\u201d');
        cleaned = trimWrapped(cleaned, '\uff08', '\uff09');
        cleaned = cleaned.replaceAll("[ \\t]{2,}", " ").trim();

        if (cleaned.isEmpty()) {
            return "";
        }
        if (looksLikeMetaLine(cleaned, originalLine)) {
            return "";
        }

        String original = stripFormattingCodes(originalLine).trim();
        if (!original.isEmpty() && cleaned.length() > Math.max(200, original.length() * 6)) {
            return "";
        }
        return cleaned;
    }

    private static String stripLeadingOutputLabels(String text) {
        String current = text;
        while (true) {
            Matcher matcher = OUTPUT_LABEL_PATTERN.matcher(current);
            if (!matcher.find()) {
                break;
            }
            String next = current.substring(matcher.end()).trim();
            if (next.isEmpty()) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String stripInternalMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = INTERNAL_MARKER_PATTERN.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("(?i)@{3}\\s*S\\d+(?:L\\d+)?\\s*@{3}", "");
        cleaned = cleaned.replaceAll("(?i)@{3}\\s*S_END\\s*@{3}", "");
        return cleaned.trim();
    }

    private static boolean looksLikeMetaLine(String text, String originalLine) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<think>") || lower.contains("</think>")) {
            return true;
        }
        if (lower.contains("as an ai") || lower.contains("here is") || lower.contains("i can")) {
            return true;
        }
        if (lower.contains("reasoning") || lower.contains("analysis")) {
            return true;
        }
        if (lower.contains("\u4ee5\u4e0b\u662f") || lower.contains("\u8bd1\u6587\u5982\u4e0b")
                || lower.startsWith("\u597d\u7684") || lower.startsWith("\u5f53\u7136")) {
            return true;
        }
        if (text.contains("@@@") || text.contains("```")) {
            return true;
        }
        return false;
    }

    private static String trimWrapped(String text, char left, char right) {
        if (text.length() >= 2 && text.charAt(0) == left && text.charAt(text.length() - 1) == right) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static void pruneIdentityMap() {
        if (SIGN_TEXT_IDENTITY_MAP.size() <= MAX_IDENTITY_CACHE_SIZE) {
            return;
        }

        long cutoff = System.currentTimeMillis() - IDENTITY_CACHE_TTL_MS;
        SIGN_TEXT_IDENTITY_MAP.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);
        if (SIGN_TEXT_IDENTITY_MAP.size() > MAX_IDENTITY_CACHE_SIZE) {
            SIGN_TEXT_IDENTITY_MAP.clear();
        }
    }

    private static void pruneCaches() {
        if (SIGN_COMPONENT_CACHE.size() > MAX_SIGN_CACHE_SIZE) {
            SIGN_COMPONENT_CACHE.clear();
        }
        if (SHARED_SIGN_COMPONENT_CACHE.size() > MAX_SIGN_CACHE_SIZE) {
            SHARED_SIGN_COMPONENT_CACHE.clear();
        }
    }

    private static String stripFormattingCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private static final class SignContext {
        private final String dimensionId;
        private final BlockPos pos;
        private final boolean front;
        private final String[] sourceLines;
        private final List<Component> sourceComponents;
        private final String signStateKey;
        private final String signId;
        private final long selectionIndex;
        private final Direction facing;
        private final int panelPlane;
        private final int panelRow;
        private final int panelColumn;

        private SignContext(BlockPos pos, boolean front, String[] sourceLines, List<Component> sourceComponents,
                            long selectionIndex) {
            this.dimensionId = currentDimensionId();
            this.pos = pos;
            this.front = front;
            this.sourceLines = normalizeSourceLines(sourceLines);
            this.sourceComponents = normalizeSignComponents(sourceComponents, this.sourceLines);
            this.signStateKey = createSignStateKey(this.dimensionId, pos, front, this.sourceLines);
            this.signId = createSignId(this.dimensionId, pos, front, this.sourceLines);
            this.selectionIndex = selectionIndex;
            this.facing = resolveSignFacing(pos);
            this.panelPlane = planeCoordinate(pos, this.facing);
            this.panelRow = pos == null ? 0 : -pos.getY();
            this.panelColumn = visualColumn(pos, this.facing);
        }

        private String panelKey() {
            return this.dimensionId + "|" + this.front + "|" + this.facing + "|" + this.panelPlane;
        }
    }

    private static List<Component> normalizeSignComponents(List<Component> components, String[] fallbackLines) {
        List<Component> normalized = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Component component = components != null && i < components.size() ? components.get(i) : null;
            if (component == null) {
                String line = i < length(fallbackLines) && fallbackLines[i] != null ? stripFormattingCodes(fallbackLines[i]).trim() : "";
                component = line.isEmpty() ? Component.empty() : Component.literal(line);
            }
            normalized.add(component);
        }
        return List.copyOf(normalized);
    }

    private static String[] normalizeSourceLines(String[] sourceLines) {
        String[] normalized = new String[4];
        for (int i = 0; i < 4; i++) {
            normalized[i] = sourceLines != null && i < sourceLines.length && sourceLines[i] != null
                    ? sourceLines[i] : "";
        }
        return normalized;
    }

    public static final class ManualSignContext {
        private final long selectionIndex;
        private final BlockPos pos;
        private final boolean front;
        private final String[] sourceLines;

        public ManualSignContext(BlockPos pos, boolean front, String[] sourceLines) {
            this(0L, pos, front, sourceLines);
        }

        public ManualSignContext(long selectionIndex, BlockPos pos, boolean front, String[] sourceLines) {
            this.selectionIndex = selectionIndex;
            this.pos = pos;
            this.front = front;
            this.sourceLines = sourceLines == null ? new String[0] : Arrays.copyOf(sourceLines, sourceLines.length);
        }
    }

    public static final class SignTextIdentityData {
        public final BlockPos pos;
        public final boolean front;
        public final String[] translatedLines;
        public final Component[] translatedComponents;
        public final boolean isTranslating;
        public final int maxTextLineWidth;
        public final long timestamp;

        public SignTextIdentityData(BlockPos pos, boolean front, String[] translatedLines, Component[] translatedComponents,
                                    boolean isTranslating, int maxTextLineWidth) {
            this.pos = pos;
            this.front = front;
            this.translatedLines = translatedLines;
            this.translatedComponents = translatedComponents;
            this.isTranslating = isTranslating;
            this.maxTextLineWidth = maxTextLineWidth;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static final class TranslationResult {
        public final String[] lines;
        public final Component[] components;
        public final boolean isTranslating;

        public TranslationResult(String[] lines, boolean isTranslating) {
            this(lines, null, isTranslating);
        }

        public TranslationResult(String[] lines, Component[] components, boolean isTranslating) {
            this.lines = lines;
            this.components = components;
            this.isTranslating = isTranslating;
        }
    }
}
