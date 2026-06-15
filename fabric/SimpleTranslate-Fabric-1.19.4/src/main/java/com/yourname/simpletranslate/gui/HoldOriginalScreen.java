package com.yourname.simpletranslate.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import com.yourname.simpletranslate.compat.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class HoldOriginalScreen extends BaseSimpleTranslateScreen {

    private final Screen parent;

    private boolean masterEnabled;
    private final Map<HoldOriginalFeature, Integer> pendingKeys = new EnumMap<>(HoldOriginalFeature.class);
    private final Map<HoldOriginalFeature, Button> keyButtons = new EnumMap<>(HoldOriginalFeature.class);

    private HoldOriginalFeature recordingFeature;

    private double scrollOffset = 0;
    private int contentHeight = 0;
    private final int viewportTop = 35;
    private final int viewportBottom = 5;

    private final List<AbstractWidget> scrollableWidgets = new ArrayList<>();
    private final List<Integer> widgetBaseY = new ArrayList<>();

    public HoldOriginalScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.hold_original"));
        this.parent = parent;
        this.masterEnabled = ModConfig.HOLD_ORIGINAL_ENABLED.get();
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            pendingKeys.put(feature, feature.getKeyConfig().get());
        }
    }

    @Override
    protected void init() {
        super.init();
        scrollOffset = 0;
        scrollableWidgets.clear();
        widgetBaseY.clear();
        keyButtons.clear();

        int centerX = this.width / 2;
        int rowWidth = 260;
        int rowHeight = 20;
        int spacing = 24;
        int startY = 50;

        CycleButton<Boolean> masterToggle = CycleButton.onOffBuilder(masterEnabled)
                .create(centerX - rowWidth / 2, startY, rowWidth, rowHeight,
                        Component.translatable("screen.simple_translate.hold_original.master_enabled"),
                        (button, value) -> masterEnabled = value);
        withTooltip(masterToggle, "screen.simple_translate.hold_original.master_enabled.tooltip");
        addScrollableWidget(masterToggle, startY);

        startY += spacing + 8;

        int labelWidth = 110;
        int clearWidth = 50;
        int keyWidth = rowWidth - labelWidth - clearWidth - 10;

        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            int rowX = centerX - rowWidth / 2;

            int featureLabelY = startY;
            int keyButtonX = rowX + labelWidth + 5;
            int clearButtonX = keyButtonX + keyWidth + 5;

            Button keyButton = UiCompat.buttonBuilder(
                    getKeyDisplayName(pendingKeys.get(feature)),
                    btn -> startRecording(feature))
                    .bounds(keyButtonX, featureLabelY, keyWidth, rowHeight)
                    .build();
            withTooltip(keyButton, Component.translatable(
                    "screen.simple_translate.hold_original.key.tooltip",
                    Component.translatable(feature.getTranslationKey())));
            keyButtons.put(feature, keyButton);
            addScrollableWidget(keyButton, featureLabelY);

            Button clearButton = UiCompat.buttonBuilder(
                    Component.translatable("screen.simple_translate.hold_original.clear"),
                    btn -> clearKey(feature))
                    .bounds(clearButtonX, featureLabelY, clearWidth, rowHeight)
                    .build();
            withTooltip(clearButton, Component.translatable(
                    "screen.simple_translate.hold_original.clear.tooltip",
                    Component.translatable(feature.getTranslationKey())));
            addScrollableWidget(clearButton, featureLabelY);

            LabelWidget label = new LabelWidget(rowX, featureLabelY, labelWidth, rowHeight,
                    Component.translatable(feature.getTranslationKey()));
            addScrollableWidget(label, featureLabelY);

            startY += spacing;
        }

        contentHeight = startY + 20;

        int halfWidth = (rowWidth - 10) / 2;
        int bottomY = Math.max(2, this.height - 28);

        Button saveButton = UiCompat.buttonBuilder(
                Component.translatable("screen.simple_translate.save"),
                btn -> saveAndClose())
                .bounds(centerX - rowWidth / 2, bottomY, halfWidth, rowHeight)
                .build();
        withTooltip(saveButton, "screen.simple_translate.save.tooltip");
        this.addRenderableWidget(saveButton);

        Button cancelButton = UiCompat.buttonBuilder(
                Component.translatable("screen.simple_translate.cancel"),
                btn -> this.onClose())
                .bounds(centerX + 5, bottomY, halfWidth, rowHeight)
                .build();
        withTooltip(cancelButton, "screen.simple_translate.cancel.tooltip");
        this.addRenderableWidget(cancelButton);

        repositionWidgets();
    }

    private void addScrollableWidget(AbstractWidget widget, int baseY) {
        scrollableWidgets.add(widget);
        widgetBaseY.add(baseY);
        this.addRenderableWidget(widget);
    }

    private void repositionWidgets() {
        int clipBottom = this.height - viewportBottom - 35;
        for (int i = 0; i < scrollableWidgets.size(); i++) {
            AbstractWidget widget = scrollableWidgets.get(i);
            int baseY = widgetBaseY.get(i);
            int newY = baseY - (int) scrollOffset;
            UiCompat.setY(widget, newY);
            boolean visible = newY >= viewportTop - 10 && newY < clipBottom;
            widget.visible = visible;
            widget.active = visible;
        }
    }

    private int getMaxScroll() {
        int viewportHeight = this.height - viewportTop - viewportBottom - 35;
        return Math.max(0, contentHeight - viewportHeight);
    }

    private void startRecording(HoldOriginalFeature feature) {
        recordingFeature = feature;
        Button btn = keyButtons.get(feature);
        if (btn != null) {
            btn.setMessage(Component.translatable("screen.simple_translate.hold_original.press_key")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private void clearKey(HoldOriginalFeature feature) {
        pendingKeys.put(feature, InputConstants.UNKNOWN.getValue());
        Button btn = keyButtons.get(feature);
        if (btn != null) {
            btn.setMessage(getKeyDisplayName(InputConstants.UNKNOWN.getValue()));
        }
        if (recordingFeature == feature) {
            recordingFeature = null;
        }
    }

    private void cancelRecording() {
        if (recordingFeature != null) {
            Button btn = keyButtons.get(recordingFeature);
            if (btn != null) {
                btn.setMessage(getKeyDisplayName(pendingKeys.get(recordingFeature)));
            }
            recordingFeature = null;
        }
    }

    private void assignKey(int keyCode) {
        if (recordingFeature == null) {
            return;
        }
        HoldOriginalFeature feature = recordingFeature;
        pendingKeys.put(feature, keyCode);
        Button btn = keyButtons.get(feature);
        if (btn != null) {
            btn.setMessage(getKeyDisplayName(keyCode));
        }
        recordingFeature = null;
    }

    private Component getKeyDisplayName(int keyCode) {
        if (keyCode <= InputConstants.UNKNOWN.getValue()) {
            return Component.translatable("screen.simple_translate.hold_original.none")
                    .withStyle(ChatFormatting.GRAY);
        }
        try {
            MutableComponent raw = InputConstants.Type.KEYSYM
                    .getOrCreate(keyCode)
                    .getDisplayName()
                    .copy();
            return raw.withStyle(ChatFormatting.WHITE);
        } catch (Throwable t) {
            return Component.literal("Key " + keyCode).withStyle(ChatFormatting.WHITE);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (recordingFeature != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelRecording();
                return true;
            }
            assignKey(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (recordingFeature != null) {
            Button recordingBtn = keyButtons.get(recordingFeature);
            if (recordingBtn != null && recordingBtn.visible
                    && mouseX >= UiCompat.getX(recordingBtn) && mouseX <= UiCompat.getX(recordingBtn) + recordingBtn.getWidth()
                    && mouseY >= UiCompat.getY(recordingBtn) && mouseY <= UiCompat.getY(recordingBtn) + 20) {
                cancelRecording();
                return true;
            }
            cancelRecording();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            scrollOffset -= delta * 25;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            repositionWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, this.width, this.height);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int clipBottom = this.height - viewportBottom - 35;
        graphics.enableScissor(0, viewportTop, this.width, clipBottom);

        int labelX = this.width / 2 - 130;
        for (int i = 0; i < scrollableWidgets.size(); i++) {
            AbstractWidget w = scrollableWidgets.get(i);
            if (w instanceof LabelWidget labelWidget && w.visible) {
                int textY = UiCompat.getY(w) + (20 - this.font.lineHeight) / 2;
                graphics.drawString(this.font, labelWidget.label, UiCompat.getX(w), textY, 0xCCCCCC);
            }
        }

        graphics.disableScissor();

        if (getMaxScroll() > 0) {
            drawScrollBar(graphics);
        }

        drawBottomActionMask(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int hintY = this.height - 44;
        if (recordingFeature != null) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.simple_translate.hold_original.recording_hint"),
                    this.width / 2, hintY, 0xFFAA00);
        }
    }

    private void drawBottomActionMask(GuiGraphics graphics) {
        int top = this.height - 35;
        int left = Math.max(0, this.width / 2 - 138);
        int right = Math.min(this.width, this.width / 2 + 138);
        graphics.fill(left, top, right, this.height - 2, 0xAA101010);
        graphics.fill(left, top, right, top + 1, 0x55FFFFFF);
    }

    private void drawScrollBar(GuiGraphics graphics) {
        int scrollBarX = this.width / 2 + 140;
        int scrollBarWidth = 4;
        int scrollBarTop = viewportTop;
        int scrollBarHeight = this.height - viewportTop - viewportBottom - 35;

        graphics.fill(scrollBarX, scrollBarTop, scrollBarX + scrollBarWidth, scrollBarTop + scrollBarHeight,
                0x33FFFFFF);

        int maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            double scrollRatio = scrollOffset / maxScroll;
            int handleHeight = Math.max(20, scrollBarHeight * scrollBarHeight / Math.max(1, contentHeight));
            int handleY = scrollBarTop + (int) ((scrollBarHeight - handleHeight) * scrollRatio);
            graphics.fill(scrollBarX, handleY, scrollBarX + scrollBarWidth, handleY + handleHeight, 0xAAFFFFFF);
        }
    }

    private void saveAndClose() {
        ModConfig.HOLD_ORIGINAL_ENABLED.set(masterEnabled);
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            feature.getKeyConfig().set(pendingKeys.get(feature));
        }
        ModConfig.save();
        this.onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class LabelWidget extends AbstractWidget {
        final Component label;

        LabelWidget(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label);
            this.label = label;
            this.active = false;
        }

        @Override
        public void renderWidget(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            // Rendering handled by parent screen (so it respects scissor/scroll)
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
        }
    }
}


