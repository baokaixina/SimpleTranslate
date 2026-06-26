package com.yourname.simpletranslate.feature.sign;

import com.yourname.simpletranslate.SimpleTranslateMod;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SignContextSelectionManager {
    private static final int MAX_SELECTED_SIGNS = 100;
    private static final Map<String, Selection> SELECTIONS = new LinkedHashMap<>();
    private static boolean dragSelectionActive;
    private static String lastDragKey = "";
    private static long nextSelectionIndex;

    private SignContextSelectionManager() {
    }

    public static void toggleDragSelectionMode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canUseManualMode(minecraft)) {
            return;
        }

        dragSelectionActive = !dragSelectionActive;
        lastDragKey = "";
        showActionbar(dragSelectionActive
                ? "message.simple_translate.sign_context.drag_started"
                : "message.simple_translate.sign_context.drag_stopped");
        if (dragSelectionActive) {
            addLookedAtSign(false);
        }
    }

    public static void tickDragSelection() {
        if (!dragSelectionActive) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!canUseManualMode(minecraft)) {
            dragSelectionActive = false;
            lastDragKey = "";
            return;
        }

        addLookedAtSign(true);
    }

    private static void addLookedAtSign(boolean quietMiss) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof BlockHitResult hitResult)
                || hitResult.getType() != HitResult.Type.BLOCK
                || minecraft.level == null
                || minecraft.player == null) {
            if (!quietMiss) {
                showActionbar("message.simple_translate.sign_context.no_target");
            }
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        if (!(minecraft.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            if (!quietMiss) {
                showActionbar("message.simple_translate.sign_context.no_target");
            }
            return;
        }

        boolean front = sign.isFacingFrontText(minecraft.player);
        String[] lines = SignTranslationHelper.readSignLinesForSelection(sign, front);

        pruneStaleSelections(minecraft.level);
        String key = createKey(minecraft.level, pos, front, lines);
        if (key.equals(lastDragKey)) {
            return;
        }
        lastDragKey = key;

        Selection existing = SELECTIONS.get(key);
        if (existing != null) {
            if (!quietMiss) {
                showActionbar(existing.state == SelectionState.TRANSLATING
                        ? "message.simple_translate.sign_context.translating"
                        : "message.simple_translate.sign_context.already_selected");
            }
            return;
        }

        if (SELECTIONS.size() >= MAX_SELECTED_SIGNS) {
            showActionbar("message.simple_translate.sign_context.full");
            return;
        }

        SELECTIONS.put(key, new Selection(nextSelectionIndex++, key, currentWorldKey(), currentDimensionKey(minecraft.level), pos,
                front, SignTranslationHelper.createSelectionSignature(lines), lines, SelectionState.SELECTED));
        showCountMessage("message.simple_translate.sign_context.added");
    }

    public static void submitSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canUseManualMode(minecraft)) {
            return;
        }
        dragSelectionActive = false;
        lastDragKey = "";
        if (minecraft.level == null) {
            return;
        }

        pruneStaleSelections(minecraft.level);
        if (SELECTIONS.isEmpty()) {
            showActionbar("message.simple_translate.sign_context.empty");
            return;
        }
        for (Selection selection : SELECTIONS.values()) {
            if (selection.state == SelectionState.TRANSLATING) {
                showActionbar("message.simple_translate.sign_context.translating");
                return;
            }
        }

        Map<Long, String> submittedKeys = new LinkedHashMap<>();
        List<SignTranslationHelper.ManualSignContext> contexts = new ArrayList<>();
        List<Selection> orderedSelections = new ArrayList<>(SELECTIONS.values());
        orderedSelections.sort(Comparator.comparingLong(selection -> selection.selectionIndex));
        for (Selection selection : orderedSelections) {
            selection.state = SelectionState.TRANSLATING;
            submittedKeys.put(selection.selectionIndex, selection.key);
            contexts.add(new SignTranslationHelper.ManualSignContext(
                    selection.selectionIndex, selection.pos, selection.front, selection.sourceLines));
        }

        showCountMessage("message.simple_translate.sign_context.submitted");
        SignTranslationHelper.requestManualContextTranslationDetailed(contexts, outcome ->
                Minecraft.getInstance().execute(() -> finishSubmittedSelection(submittedKeys, outcome)));
    }

    public static List<SelectionView> getRenderableSelections(Level level) {
        if (level == null) {
            return List.of();
        }
        pruneStaleSelections(level);
        String worldKey = currentWorldKey();
        String dimensionKey = currentDimensionKey(level);
        List<SelectionView> views = new ArrayList<>();
        for (Selection selection : SELECTIONS.values()) {
            if (selection.worldKey.equals(worldKey) && selection.dimensionKey.equals(dimensionKey)) {
                views.add(new SelectionView(selection.pos, selection.state));
            }
        }
        return views;
    }

    public static void clearAll() {
        SELECTIONS.clear();
        dragSelectionActive = false;
        lastDragKey = "";
        nextSelectionIndex = 0L;
    }

    private static boolean canUseManualMode(Minecraft minecraft) {
        if (!ModConfig.GLOBAL_ENABLED.get()
                || !ModConfig.CONTENT_SIGN_ENABLED.get()
                || ModConfig.CONTENT_SIGN_CONTEXT_MODE.get() != ModConfig.SignContextMode.MANUAL) {
            showActionbar("message.simple_translate.sign_context.manual_disabled");
            return false;
        }
        return minecraft != null && minecraft.player != null && minecraft.level != null;
    }

    private static void finishSubmittedSelection(Map<Long, String> submittedKeys,
                                                 SignTranslationHelper.ManualTranslationOutcome outcome) {
        if (submittedKeys == null || submittedKeys.isEmpty()) {
            return;
        }

        if (outcome != null) {
            for (Long index : outcome.appliedSelectionIndexes()) {
                String key = submittedKeys.get(index);
                if (key != null) {
                    SELECTIONS.remove(key);
                }
            }
            for (Long index : outcome.failedSelectionIndexes()) {
                String key = submittedKeys.get(index);
                Selection selection = key == null ? null : SELECTIONS.get(key);
                if (selection != null) {
                    selection.state = SelectionState.SELECTED;
                }
            }
        }

        for (Map.Entry<Long, String> entry : submittedKeys.entrySet()) {
            String key = entry.getValue();
            if (key == null || !SELECTIONS.containsKey(key)
                    || (outcome != null && outcome.appliedSelectionIndexes().contains(entry.getKey()))) {
                continue;
            }
            Selection selection = SELECTIONS.get(key);
            if (selection != null) {
                selection.state = SelectionState.SELECTED;
            }
        }

        if (outcome != null && outcome.fullyApplied()) {
            showActionbar("message.simple_translate.sign_context.success");
            return;
        }
        if (outcome != null && outcome.anyApplied()) {
            showActionbar("message.simple_translate.sign_context.partial");
            return;
        }

        showActionbar("message.simple_translate.sign_context.failed");
    }

    private static void pruneStaleSelections(Level level) {
        String worldKey = currentWorldKey();
        String dimensionKey = currentDimensionKey(level);
        SELECTIONS.entrySet().removeIf(entry -> {
            Selection selection = entry.getValue();
            if (!selection.worldKey.equals(worldKey) || !selection.dimensionKey.equals(dimensionKey)) {
                return true;
            }
            if (!(level.getBlockEntity(selection.pos) instanceof SignBlockEntity sign)) {
                return true;
            }
            String[] currentLines = SignTranslationHelper.readSignLinesForSelection(sign, selection.front);
            return !selection.signature.equals(SignTranslationHelper.createSelectionSignature(currentLines));
        });
    }

    private static String createKey(Level level, BlockPos pos, boolean front, String[] lines) {
        return currentWorldKey() + "|" + currentDimensionKey(level) + "|" + pos.asLong() + "|"
                + (front ? "F" : "B") + "|" + SignTranslationHelper.createSelectionSignature(lines);
    }

    private static String currentWorldKey() {
        String worldId = SimpleTranslateMod.getCurrentWorldId();
        return worldId == null || worldId.isBlank() ? "unknown" : worldId;
    }

    private static String currentDimensionKey(Level level) {
        return level.dimension().identifier().toString();
    }

    private static void showCountMessage(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.translatable(key, SELECTIONS.size()));
        }
    }

    private static void showActionbar(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.translatable(key));
        }
    }

    public enum SelectionState {
        SELECTED,
        TRANSLATING
    }

    public record SelectionView(BlockPos pos, SelectionState state) {
    }

    private static final class Selection {
        private final long selectionIndex;
        private final String key;
        private final String worldKey;
        private final String dimensionKey;
        private final BlockPos pos;
        private final boolean front;
        private final String signature;
        private final String[] sourceLines;
        private SelectionState state;

        private Selection(long selectionIndex, String key, String worldKey, String dimensionKey, BlockPos pos, boolean front,
                          String signature, String[] sourceLines, SelectionState state) {
            this.selectionIndex = selectionIndex;
            this.key = key;
            this.worldKey = worldKey;
            this.dimensionKey = dimensionKey;
            this.pos = pos;
            this.front = front;
            this.signature = signature;
            this.sourceLines = sourceLines;
            this.state = state;
        }
    }
}
