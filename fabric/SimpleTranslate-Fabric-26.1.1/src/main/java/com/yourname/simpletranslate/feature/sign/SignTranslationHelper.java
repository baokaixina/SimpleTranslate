package com.yourname.simpletranslate.feature.sign;
import com.yourname.simpletranslate.transport.TranslationLane;
import com.yourname.simpletranslate.transport.TranslationLanes;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationHelper;
import com.yourname.simpletranslate.core.SafeTranslate;
import com.yourname.simpletranslate.core.DirectSurfaceTranslator;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.transport.TranslationManager;
import com.yourname.simpletranslate.transport.TranslationRequestQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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

    private static final int MAX_SIGN_SCAN_PER_SIDE = 48;
    private static final int MAX_CONTEXTS_PER_BATCH = 4;
    private static final int MAX_BATCH_SOURCE_CHARS = 1000;
    private static final int MAX_SIGN_CACHE_SIZE = 1200;
    private static final long FAILURE_RETRY_MS = 5000L;

    public static final String TRANSLATING_MARKER = "\u00a7e\u7ffb\u8bd1\u4e2d...";

    private static final Map<SignText, SignTextIdentityData> SIGN_TEXT_DATA =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Component[]> SIGN_COMPONENT_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component[]> eldest) {
                    return size() > MAX_SIGN_CACHE_SIZE;
                }
            });
    private static final Map<String, Component[]> SHARED_SIGN_COMPONENT_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component[]> eldest) {
                    return size() > MAX_SIGN_CACHE_SIZE;
                }
            });
    private static final Set<String> PENDING_SIGN_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PENDING_BATCH_KEYS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SIGN_MODE_EPOCH = new AtomicLong();

    private SignTranslationHelper() {
    }

    public static void registerSignText(
            SignText signText,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            boolean isTranslating
    ) {
        registerSignText(signText, pos, front, translatedLines, isTranslating, 90);
    }

    public static void registerSignText(
            SignText signText,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            boolean isTranslating,
            int maxTextLineWidth
    ) {
        registerSignText(signText, pos, front, translatedLines, null, isTranslating, maxTextLineWidth);
    }

    public static void registerSignText(
            SignText signText,
            BlockPos pos,
            boolean front,
            String[] translatedLines,
            Component[] translatedComponents,
            boolean isTranslating,
            int maxTextLineWidth
    ) {
        if (signText == null) {
            return;
        }
        String[] copy = translatedLines == null ? null : Arrays.copyOf(translatedLines, translatedLines.length);
        Component[] componentCopy = copyComponents(translatedComponents);
        SignTextIdentityData existing = SIGN_TEXT_DATA.get(signText);
        if (existing != null
                && existing.isTranslating == isTranslating
                && existing.maxTextLineWidth == maxTextLineWidth
                && Arrays.equals(existing.translatedComponents, componentCopy)) {
            return;
        }
        SignLayoutEngine.Layout layout = null;
        if (!isTranslating && componentCopy != null && componentCopy.length == 4) {
            Minecraft minecraft = Minecraft.getInstance();
            layout = SignLayoutEngine.layout(componentCopy,
                    minecraft == null ? null : minecraft.font,
                    maxTextLineWidth);
        }
        SIGN_TEXT_DATA.put(signText,
                new SignTextIdentityData(pos, front, copy, componentCopy, isTranslating,
                        maxTextLineWidth, layout));
    }

    public static SignTextIdentityData getSignTextData(SignText signText) {
        return signText == null ? null : SIGN_TEXT_DATA.get(signText);
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
        requestManualContextTranslationDetailed(manualContexts, outcome ->
                completed.accept(outcome != null && outcome.fullyApplied()));
    }

    public static void requestManualContextTranslationDetailed(List<ManualSignContext> manualContexts,
                                                               Consumer<ManualTranslationOutcome> completed) {
        if (ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
            completed.accept(ManualTranslationOutcome.empty());
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
            completed.accept(ManualTranslationOutcome.failed(orderedContexts));
            return;
        }

        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            completed.accept(ManualTranslationOutcome.failed(orderedContexts));
            return;
        }

        String batchKey = "manual:" + createBatchKey(orderedContexts);
        if (!PENDING_BATCH_KEYS.add(batchKey)) {
            completed.accept(ManualTranslationOutcome.failed(orderedContexts));
            return;
        }
        for (SignContext context : orderedContexts) {
            PENDING_SIGN_KEYS.add(context.signStateKey);
        }
        requestManualGroupDirect(orderedContexts, batchKey, modeEpoch).whenComplete((success, error) -> {
            if (!isSignModeEpochCurrent(modeEpoch)
                    || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                completed.accept(ManualTranslationOutcome.failed(orderedContexts));
                return;
            }
            if (error != null) {
                SimpleTranslateMod.getLogger().error("Manual sign translation batch failed", error);
                markFailure("sign.manual.group.by_id.direct", orderedContexts);
                completed.accept(ManualTranslationOutcome.failed(orderedContexts));
                return;
            }
            completed.accept(success == null ? ManualTranslationOutcome.failed(orderedContexts) : success);
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
        SIGN_TEXT_DATA.clear();
        SIGN_COMPONENT_CACHE.clear();
        PENDING_SIGN_KEYS.clear();
        PENDING_BATCH_KEYS.clear();
    }

    public static TranslationResult getTranslatedLinesWithState(SignBlockEntity sign, boolean front, Level level) {
        return getTranslatedLinesWithState(sign, front, level, true);
    }

    public static TranslationResult getTranslatedLinesWithState(SignBlockEntity sign, boolean front, Level level,
                                                               boolean allowAutoRequest) {
        return SafeTranslate.guard(() -> {
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
                if (isStablePersistentSignCache(originalLines, byComponents)) {
                    return new TranslationResult(componentStrings(byComponents), copyComponents(byComponents), false);
                }
                SIGN_COMPONENT_CACHE.remove(signStateKey);
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
        }, new TranslationResult(null, false), "sign.getTranslatedLinesWithState");
    }

    private static CompletableFuture<Boolean> requestAutoSingleDirect(SignContext context,
                                                                      String batchKey,
                                                                      long modeEpoch) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            clearBatchState(batchKey, List.of(context), modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        SignJsonDocument.Document document = buildAutoSignDocument(context);
        if (document == null || !document.hasEnglish()) {
            clearBatchState(batchKey, List.of(context), modeEpoch);
            return CompletableFuture.completedFuture(false);
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        return DirectSurfaceTranslator.translateComponentsAsync(
                        document.components(), document.surface(), document.role(), true, document.context())
                .handle((translation, error) -> {
                    try {
                        if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                                || !isSignModeEpochCurrent(modeEpoch)
                                || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.AUTO) {
                            SimpleTranslateMod.getLogger().debug(
                                    "Auto sign direct ignored stale response sign={} front={} epoch={} currentEpoch={}",
                                    context.pos.toShortString(), context.front, modeEpoch, currentSignModeEpoch());
                            return false;
                        }
                        if (error != null || translation == null || !translation.translated
                                || translation.components == null) {
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct failed reason={} key={} source={}",
                                    error == null ? "untranslated-response" : error.getClass().getSimpleName(),
                                    document.cacheKey(), document.sourceSummary(), error);
                            return false;
                        }
                        SignJsonDocument.RestoreResult result = document.restoreComponents(translation.components);
                        if (!result.success()) {
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct rejected reason={} key={} source={}",
                                    result.failureReason(), document.cacheKey(), document.sourceSummary());
                            return false;
                        }
                        if (!canDisplaySignResult(result)) {
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct hidden reason=no-mapped-sign key={} source={}",
                                    document.cacheKey(),
                                    document.sourceSummary());
                            return false;
                        }
                        SignApplyResult applyResult = applySignDocument(List.of(context), result, "Auto", true);
                        if (!applyResult.anyApplied()) {
                            SimpleTranslateMod.getLogger().warn(
                                    "Auto sign by-id direct rejected reason=writeback-mismatch key={} source={}",
                                    document.cacheKey(), document.sourceSummary());
                            return false;
                        }
                        pruneCaches();
                        return true;
                    } finally {
                        clearBatchState(batchKey, List.of(context), modeEpoch);
                    }
                });
    }

    private static CompletableFuture<ManualTranslationOutcome> requestManualGroupDirect(List<SignContext> contexts,
                                                                                       String batchKey,
                                                                                       long modeEpoch) {
        TranslationManager manager = SimpleTranslateMod.getTranslationManager();
        if (manager == null || !manager.isReady()) {
            markFailure("sign.manual.group.by_id.direct", contexts);
            clearBatchState(batchKey, contexts, modeEpoch);
            return CompletableFuture.completedFuture(ManualTranslationOutcome.failed(contexts));
        }

        List<List<SignContext>> panels = partitionManualPanels(contexts);
        if (panels.isEmpty()) {
            clearBatchState(batchKey, contexts, modeEpoch);
            return CompletableFuture.completedFuture(ManualTranslationOutcome.failed(contexts));
        }

        long runtimeRevision = SimpleTranslateMod.getRuntimeRevision();
        List<CompletableFuture<PanelResult>> requests = new ArrayList<>(panels.size());
        for (List<SignContext> panel : panels) {
            requests.add(translateManualPanelDirect(panel, runtimeRevision, modeEpoch));
        }

        CompletableFuture<Void> allRequests = CompletableFuture.allOf(
                requests.toArray(CompletableFuture[]::new));
        return allRequests.handle((ignored, error) -> {
                    if (!isSignModeEpochCurrent(modeEpoch)
                            || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                        return ManualTranslationOutcome.failed(contexts);
                    }
                    if (error != null) {
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign panel group completed with an unexpected error signs={} panels={}",
                                contexts.size(), panels.size(), error);
                    }
                    List<PanelResult> panelResults = new ArrayList<>(requests.size());
                    for (CompletableFuture<PanelResult> request : requests) {
                        try {
                            PanelResult pr = request.getNow(null);
                            if (pr != null) {
                                panelResults.add(pr);
                            }
                        } catch (RuntimeException requestError) {
                            SimpleTranslateMod.getLogger().warn(
                                    "Manual sign panel result could not be collected", requestError);
                        }
                    }

                    boolean anyFailed = false;
                    List<SignJsonDocument.RestoreResult> allRestoreResults = new ArrayList<>();
                    List<SignContext> allContexts = new ArrayList<>();
                    for (PanelResult pr : panelResults) {
                        if (pr.result() == null) {
                            anyFailed = true;
                        } else {
                            allRestoreResults.add(pr.result());
                            allContexts.addAll(pr.contexts());
                        }
                    }

                    if (anyFailed) {
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign group atomic failure: at least one panel failed, keeping all {} signs in original text",
                                contexts.size());
                        pruneCaches();
                        return ManualTranslationOutcome.failed(contexts);
                    }

                    List<Long> appliedIndexes = new ArrayList<>();
                    for (SignContext context : contexts) {
                        if (context == null) {
                            continue;
                        }
                        for (SignJsonDocument.RestoreResult rr : allRestoreResults) {
                            if (rr.componentsBySignId() != null && rr.componentsBySignId().containsKey(context.signId)) {
                                Component[] components = rr.componentsBySignId().get(context.signId);
                                if (components != null && components.length == 4
                                        && isStablePersistentSignCache(context.sourceLines, components)) {
                                    SIGN_COMPONENT_CACHE.put(context.signStateKey, components);
                                    putSharedSignComponents(context, components);
                                    appliedIndexes.add(context.selectionIndex);
                                }
                                break;
                            }
                        }
                    }
                    pruneCaches();
                    boolean allApplied = appliedIndexes.size() == contexts.size();
                    return new ManualTranslationOutcome(
                            !appliedIndexes.isEmpty(),
                            allApplied,
                            List.copyOf(appliedIndexes),
                            List.of(),
                            !allApplied);
                })
                .whenComplete((success, error) -> clearBatchState(batchKey, contexts, modeEpoch));
    }

    private static CompletableFuture<PanelResult> translateManualPanelDirect(
            List<SignContext> panelContexts, long runtimeRevision, long modeEpoch) {
        SignJsonDocument.Document document = buildManualSignDocument(panelContexts);
        if (document == null) {
            return CompletableFuture.completedFuture(new PanelResult(panelContexts, null));
        }
        if (!document.hasEnglish()) {
            return CompletableFuture.completedFuture(new PanelResult(panelContexts, new SignJsonDocument.RestoreResult(true, java.util.Map.of(), "")));
        }

        return DirectSurfaceTranslator.translateComponentsAsync(
                        document.components(), document.surface(), document.role(), true, document.context())
                .handle((translation, error) -> {
                    if (!SimpleTranslateMod.isRuntimeRevisionCurrent(runtimeRevision)
                            || !isSignModeEpochCurrent(modeEpoch)
                            || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
                        SimpleTranslateMod.getLogger().debug(
                                "Manual sign panel ignored stale response epoch={} currentEpoch={} signs={}",
                                modeEpoch, currentSignModeEpoch(), panelContexts.size());
                        return new PanelResult(panelContexts, null);
                    }
                    if (error != null || translation == null || !translation.translated
                            || translation.components == null) {
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign panel failed signs={} reason={} key={} source={}",
                                panelContexts.size(),
                                error == null ? "untranslated-response" : error.getClass().getSimpleName(),
                                document.cacheKey(), document.sourceSummary(), error);
                        return new PanelResult(panelContexts, null);
                    }
                    SignJsonDocument.RestoreResult result = document.restoreComponents(translation.components);
                    if (!result.success() || !canDisplaySignResult(result)) {
                        SimpleTranslateMod.getLogger().warn(
                                "Manual sign panel rejected signs={} reason={} key={} source={}",
                                panelContexts.size(),
                                result == null ? "restore-null" : result.failureReason(),
                                document.cacheKey(), document.sourceSummary());
                        return new PanelResult(panelContexts, null);
                    }
                    return new PanelResult(panelContexts, result);
                });
    }

    private static SignJsonDocument.Document buildManualSignDocument(List<SignContext> panelContexts) {
        if (panelContexts == null || panelContexts.isEmpty()) {
            return null;
        }
        List<SignJsonDocument.Entry> entries = new ArrayList<>();
        for (SignContext context : panelContexts) {
            entries.add(new SignJsonDocument.Entry(context.signId, context.signStateKey, context.sourceComponents,
                    context.sourceLines, context.selectionIndex, context.front));
        }
        return SignJsonDocument.fromEntries(entries,
                "sign.manual.group.by_id.direct",
                "sign-manual-group-by-id",
                buildManualPanelContext(panelContexts));
    }

    private static SignJsonDocument.Document buildAutoSignDocument(SignContext context) {
        if (context == null) {
            return null;
        }
        SignJsonDocument.Entry entry = new SignJsonDocument.Entry(context.signId, context.signStateKey,
                context.sourceComponents, context.sourceLines, context.selectionIndex, context.front);
        return SignJsonDocument.fromEntries(List.of(entry),
                "sign.auto.single.direct",
                "sign-auto-single-by-id",
                buildAutoSignContext(context));
    }

    private static SignApplyResult applySignDocument(List<SignContext> contexts,
                                                     SignJsonDocument.RestoreResult result,
                                                     String logPrefix,
                                                     boolean persistSharedCache) {
        if (contexts == null || contexts.isEmpty() || result == null || !result.success()
                || result.componentsBySignId() == null) {
            return SignApplyResult.none(contexts);
        }
        List<SignContext> applied = new ArrayList<>();
        List<SignContext> skipped = new ArrayList<>();
        for (SignContext context : contexts) {
            Component[] restoredComponents = result.componentsBySignId().get(context.signId);
            if (restoredComponents == null || restoredComponents.length != 4) {
                skipped.add(context);
                SimpleTranslateMod.getLogger().warn(
                        "{} sign by-id writeback missing signId={} sign={} source={}",
                        logPrefix, context.signId, context.pos.toShortString(),
                        sourceTextForContext(context.sourceLines).replace('\n', ' '));
                continue;
            }
            Component[] components = copyComponents(restoredComponents);
            if (!isStablePersistentSignCache(context.sourceLines, components)) {
                skipped.add(context);
                SIGN_COMPONENT_CACHE.remove(context.signStateKey);
                SHARED_SIGN_COMPONENT_CACHE.remove(createSharedSignKey(context.pos, context.front, context.sourceLines));
                SimpleTranslateMod.getLogger().debug(
                        "{} sign by-id skipped unstable signId={} sign={} source={}",
                        logPrefix, context.signId, context.pos.toShortString(),
                        sourceTextForContext(context.sourceLines).replace('\n', ' '));
                continue;
            }
            SIGN_COMPONENT_CACHE.put(context.signStateKey, components);
            if (persistSharedCache) {
                putSharedSignComponents(context, components);
            }
            applied.add(context);
        }
        return new SignApplyResult(List.copyOf(applied), List.copyOf(skipped));
    }

    private static boolean canDisplaySignResult(SignJsonDocument.RestoreResult result) {
        return result != null && result.success()
                && result.componentsBySignId() != null
                && !result.componentsBySignId().isEmpty();
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
        context.append("Translate the complete sign message ONCE, then distribute the target-language text across the four JSON array entries so it fits a small sign. ");
        context.append("Do NOT repeat or rephrase the same sentence across multiple lines. ");
        context.append("If the translation is shorter than 4 lines, put it in the first lines and leave remaining lines empty ({}). ");
        context.append("If the translation is longer than 4 lines, compress it to fit 4 lines — signs cannot scroll. ");
        context.append("Keep symbols like arrows (↓, ↑, →, ←) on their own line if they were alone in the source. ");
        context.append("Return exactly four JSON array entries — do not add, remove, split, or merge entries. ");
        context.append("Keep commands, gamerules, coordinates, version numbers, and numbered headings anchored to this same sign.\n");
        context.append("Target sign semantic block:\n");
        context.append(buildSignContextText(List.of(target))).append('\n');
        return context.toString();
    }

    private static String buildManualPanelContext(List<SignContext> panelContexts) {
        StringBuilder context = new StringBuilder();
        int signCount = panelContexts == null ? 0 : panelContexts.size();
        context.append("The JSON array contains one complete manually selected spatial sign panel. ");
        context.append("Each sign occupies exactly four consecutive array entries (one per visible line). ");
        context.append("Return exactly ").append(signCount * 4)
                .append(" array entries (").append(signCount).append(" signs × 4 lines each), in the same order. ");
        context.append("Spatial panel reading order below is authoritative: read rows from top to bottom, and within each row from visual left to visual right. ");
        context.append("Numbered headings such as '6.' start a semantic block; following signs without a new number continue that block. ");
        context.append("Translate the panel as one continuous semantic document, while keeping each sign's wording within its four array entries. ");
        context.append("Keep the exact array order and never move wording between signs. ");
        context.append("Never duplicate earlier text into later signs. Never summarize. ");
        context.append("Keep protected anchors such as version numbers, commands, gamerules, and numbered headings in their source sign's entries. ");
        context.append("Preserve every color, style, and clickEvent on each line. ");
        context.append("CRITICAL: The four lines of each sign are one visual message split across a tiny sign face. ");
        context.append("Translate the complete message ONCE, then distribute the translation across the four lines so it fits a small sign. ");
        context.append("Do NOT repeat or rephrase the same sentence across multiple lines. ");
        context.append("If the translation is shorter than 4 lines, put it in the first lines and leave remaining lines empty ({}). ");
        context.append("If the translation is longer than 4 lines, compress it to fit 4 lines — signs cannot scroll. ");
        context.append("Keep symbols like arrows (↓, ↑, →, ←) on their own line if they were alone in the source. ");
        context.append("\nPanel source text in authoritative display order:\n");
        context.append(buildSpatialPanelContext(panelContexts));
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

    private static List<List<SignContext>> partitionManualPanels(List<SignContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }

        int totalChars = 0;
        for (SignContext context : contexts) {
            if (context != null && context.sourceLines != null) {
                for (String line : context.sourceLines) {
                    totalChars += line == null ? 0 : line.length();
                }
            }
        }

        if (contexts.size() <= MAX_CONTEXTS_PER_BATCH && totalChars <= MAX_BATCH_SOURCE_CHARS) {
            return List.of(List.copyOf(contexts));
        }

        Map<String, List<SignContext>> panels = new java.util.LinkedHashMap<>();
        for (SignContext context : contexts) {
            if (context != null) {
                panels.computeIfAbsent(context.panelKey(), ignored -> new ArrayList<>()).add(context);
            }
        }
        List<List<SignContext>> result = new ArrayList<>(panels.size());
        for (List<SignContext> panel : panels.values()) {
            result.addAll(splitPanelBySize(panel));
        }
        return List.copyOf(result);
    }

    private static List<List<SignContext>> splitPanelBySize(List<SignContext> panel) {
        if (panel == null || panel.isEmpty()) {
            return List.of();
        }
        List<List<SignContext>> chunks = new ArrayList<>();
        List<SignContext> current = new ArrayList<>();
        int currentChars = 0;
        for (SignContext context : panel) {
            int contextChars = 0;
            if (context.sourceLines != null) {
                for (String line : context.sourceLines) {
                    contextChars += line == null ? 0 : line.length();
                }
            }
            if (!current.isEmpty()
                    && (current.size() >= MAX_CONTEXTS_PER_BATCH
                    || currentChars + contextChars > MAX_BATCH_SOURCE_CHARS)) {
                chunks.add(List.copyOf(current));
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(context);
            currentChars += contextChars;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return chunks;
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
            if (isStablePersistentSignCache(sourceLines, cached)) {
                return copyComponents(cached);
            }
            SHARED_SIGN_COMPONENT_CACHE.remove(key);
        }
        return null;
    }

    private static boolean isStablePersistentSignCache(String[] sourceLines, Component[] translatedComponents) {
        if (translatedComponents == null || translatedComponents.length != 4) {
            return false;
        }
        for (Component component : translatedComponents) {
            if (component == null) {
                return false;
            }
        }
        return true;
    }

    private static void putSharedSignComponents(SignContext context, Component[] components) {
        if (context == null || components == null || components.length != 4) {
            return;
        }
        String sharedKey = createSharedSignKey(context.pos, context.front, context.sourceLines);
        Component[] copy = copyComponents(components);
        SHARED_SIGN_COMPONENT_CACHE.put(sharedKey, copy);
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

    private static String currentDimensionId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.level != null && minecraft.level.dimension() != null
                && minecraft.level.dimension().identifier() != null) {
            return minecraft.level.dimension().identifier().toString();
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

    private static ManualTranslationOutcome combineManualOutcomes(
            List<SignContext> contexts, List<ManualTranslationOutcome> outcomes) {
        Set<Long> applied = new java.util.LinkedHashSet<>();
        boolean anyFailed = false;
        boolean retryable = false;
        if (outcomes != null) {
            for (ManualTranslationOutcome outcome : outcomes) {
                if (outcome == null) {
                    anyFailed = true;
                    continue;
                }
                applied.addAll(outcome.appliedSelectionIndexes());
                retryable |= outcome.retryable();
                if (!outcome.fullyApplied()) {
                    anyFailed = true;
                }
            }
        }

        if (anyFailed) {
            for (SignContext context : contexts) {
                if (context != null) {
                    SIGN_COMPONENT_CACHE.remove(context.signStateKey);
                }
            }
            List<Long> allFailed = new ArrayList<>();
            if (contexts != null) {
                for (SignContext context : contexts) {
                    if (context != null) {
                        allFailed.add(context.selectionIndex);
                    }
                }
            }
            return new ManualTranslationOutcome(
                    false,
                    false,
                    List.of(),
                    List.copyOf(allFailed),
                    retryable);
        }

        List<Long> appliedInOrder = new ArrayList<>();
        if (contexts != null) {
            for (SignContext context : contexts) {
                if (context == null) {
                    continue;
                }
                if (applied.contains(context.selectionIndex)) {
                    appliedInOrder.add(context.selectionIndex);
                }
            }
        }
        return new ManualTranslationOutcome(
                true,
                true,
                List.copyOf(appliedInOrder),
                List.of(),
                false);
    }

    private static boolean shouldThrottle(String signStateKey) {
        String surface = "sign.auto.single.direct";
        return TranslationLanes.forSurface(surface).isThrottled(throttleKey(surface, signStateKey));
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
            lane.fail(throttleKey(surface, context.signStateKey), FAILURE_RETRY_MS);
        }
    }

    private static String throttleKey(String surface, String signStateKey) {
        return (surface == null ? "" : surface) + '\u0001' + (signStateKey == null ? "" : signStateKey);
    }

    private static void pruneCaches() {
        // LRU eviction is handled automatically by the LinkedHashMap removeEldestEntry.
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

    private record PanelResult(List<SignContext> contexts, SignJsonDocument.RestoreResult result) {
    }

    public record ManualTranslationOutcome(boolean anyApplied,
                                           boolean fullyApplied,
                                           List<Long> appliedSelectionIndexes,
                                           List<Long> failedSelectionIndexes,
                                           boolean retryable) {
        private static ManualTranslationOutcome empty() {
            return new ManualTranslationOutcome(false, false, List.of(), List.of(), false);
        }

        private static ManualTranslationOutcome failed(List<SignContext> contexts) {
            return new ManualTranslationOutcome(false, false, List.of(), selectionIndexes(contexts), false);
        }

        private static ManualTranslationOutcome completed(List<SignContext> contexts) {
            List<Long> indexes = selectionIndexes(contexts);
            return new ManualTranslationOutcome(!indexes.isEmpty(), !indexes.isEmpty(), indexes, List.of(), false);
        }

        private static ManualTranslationOutcome retryableFailure(List<SignContext> contexts) {
            return new ManualTranslationOutcome(false, false, List.of(), selectionIndexes(contexts), true);
        }

        private static ManualTranslationOutcome from(List<SignContext> contexts, SignApplyResult result) {
            if (result == null || !result.anyApplied()) {
                return failed(contexts);
            }
            return new ManualTranslationOutcome(true, result.allApplied(contexts == null ? 0 : contexts.size()),
                    selectionIndexes(result.appliedContexts()), selectionIndexes(result.skippedContexts()),
                    result.skippedContexts() != null && !result.skippedContexts().isEmpty());
        }

        private ManualTranslationOutcome withRetryable(boolean retryable) {
            return new ManualTranslationOutcome(anyApplied, fullyApplied, appliedSelectionIndexes,
                    failedSelectionIndexes, retryable);
        }

        private static List<Long> selectionIndexes(List<SignContext> contexts) {
            if (contexts == null || contexts.isEmpty()) {
                return List.of();
            }
            List<Long> indexes = new ArrayList<>(contexts.size());
            for (SignContext context : contexts) {
                if (context != null) {
                    indexes.add(context.selectionIndex);
                }
            }
            return List.copyOf(indexes);
        }
    }

    private record SignApplyResult(List<SignContext> appliedContexts, List<SignContext> skippedContexts) {
        private static SignApplyResult none(List<SignContext> contexts) {
            return new SignApplyResult(List.of(), contexts == null ? List.of() : List.copyOf(contexts));
        }

        private boolean anyApplied() {
            return appliedContexts != null && !appliedContexts.isEmpty();
        }

        private boolean allApplied(int expectedContexts) {
            return expectedContexts > 0
                    && appliedContexts != null
                    && appliedContexts.size() == expectedContexts
                    && (skippedContexts == null || skippedContexts.isEmpty());
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
        public final FormattedCharSequence[] renderLines;
        public final float renderScale;
        public final boolean reflowed;
        public final long timestamp;

        public SignTextIdentityData(BlockPos pos, boolean front, String[] translatedLines, Component[] translatedComponents,
                                    boolean isTranslating, int maxTextLineWidth, SignLayoutEngine.Layout layout) {
            this.pos = pos;
            this.front = front;
            this.translatedLines = translatedLines;
            this.translatedComponents = translatedComponents;
            this.isTranslating = isTranslating;
            this.maxTextLineWidth = maxTextLineWidth;
            this.renderLines = layout == null ? null : layout.renderLines();
            this.renderScale = layout == null ? 1.0F : layout.scale();
            this.reflowed = layout != null && layout.reflowed();
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
