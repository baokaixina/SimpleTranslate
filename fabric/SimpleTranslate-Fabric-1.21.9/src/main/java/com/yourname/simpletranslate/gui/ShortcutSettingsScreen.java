package com.yourname.simpletranslate.gui;

import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.KeyChord;
import com.yourname.simpletranslate.keybind.ModKeyBindings;
import com.yourname.simpletranslate.keybind.ShortcutAction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records every mod shortcut as one keyboard/mouse chord with exact modifiers. */
public final class ShortcutSettingsScreen extends BaseSimpleTranslateScreen {
    private static final int VIEWPORT_TOP = 35;
    private static final int BOTTOM_HEIGHT = 38;
    private final Screen parent;
    private final Map<BindingTarget, Button> keyButtons = new LinkedHashMap<>();
    private final List<AbstractWidget> scrolling = new ArrayList<>();
    private final List<Integer> baseYs = new ArrayList<>();
    private BindingTarget recording;
    private BindingTarget pendingConflictTarget;
    private KeyChord pendingConflictChord;
    private Component status = Component.empty();
    private long statusUntil;
    private double scrollOffset;
    private int contentHeight;

    public ShortcutSettingsScreen(Screen parent) {
        super(Component.translatable("screen.simple_translate.shortcuts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        keyButtons.clear();
        scrolling.clear();
        baseYs.clear();
        scrollOffset = 0;
        int width = Math.max(270, Math.min(360, this.width - 30));
        int x = this.width / 2 - width / 2;
        int y = 48;

        y = addSection(y, "screen.simple_translate.shortcuts.section.actions", x, width);
        for (ShortcutAction action : ShortcutAction.values()) {
            y = addBindingRow(y, x, width, BindingTarget.action(action));
        }
        y += 8;
        y = addSection(y, "screen.simple_translate.shortcuts.section.hold_original", x, width);
        CycleButton<Boolean> enabled = CycleButton.onOffBuilder(ModConfig.HOLD_ORIGINAL_ENABLED.get())
                .create(x, y, width, 20,
                        Component.translatable("screen.simple_translate.hold_original.master_enabled"),
                        (button, value) -> {
                            ModConfig.HOLD_ORIGINAL_ENABLED.set(value);
                            ModConfig.save();
                        });
        withTooltip(enabled, "screen.simple_translate.hold_original.master_enabled.tooltip");
        addScrolling(enabled, y);
        y += 26;
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            y = addBindingRow(y, x, width, BindingTarget.hold(feature));
        }
        contentHeight = y + 12;

        Button back = Button.builder(Component.translatable("screen.simple_translate.back"), button -> onClose())
                .bounds(x, this.height - 27, width, 20).build();
        withTooltip(back, "screen.simple_translate.back.tooltip");
        addRenderableWidget(back);
        reposition();
    }

    private int addSection(int y, String key, int x, int width) {
        Label label = new Label(x, y, width, 16, Component.translatable(key));
        addScrolling(label, y);
        return y + 18;
    }

    private int addBindingRow(int y, int x, int width, BindingTarget target) {
        int labelWidth = Math.min(165, width / 2);
        Label label = new Label(x, y, labelWidth, 20, target.label());
        addScrolling(label, y);
        Button key = Button.builder(target.chord().displayName(), button -> beginRecording(target))
                .bounds(x + labelWidth + 4, y, width - labelWidth - 58, 20).build();
        withTooltip(key, "screen.simple_translate.shortcuts.record.tooltip");
        keyButtons.put(target, key);
        addScrolling(key, y);
        Button clear = Button.builder(Component.translatable("screen.simple_translate.shortcuts.clear"),
                        button -> assign(target, KeyChord.NONE))
                .bounds(x + width - 50, y, 50, 20).build();
        withTooltip(clear, "screen.simple_translate.shortcuts.clear.tooltip");
        addScrolling(clear, y);
        return y + 24;
    }

    private void beginRecording(BindingTarget target) {
        cancelRecording();
        recording = target;
        Button button = keyButtons.get(target);
        if (button != null) button.setMessage(Component.translatable("screen.simple_translate.shortcuts.press_chord")
                .withStyle(ChatFormatting.YELLOW));
    }

    private void cancelRecording() {
        if (recording != null) {
            Button button = keyButtons.get(recording);
            if (button != null) button.setMessage(recording.chord().displayName());
        }
        recording = null;
        pendingConflictTarget = null;
        pendingConflictChord = null;
    }

    private void assign(BindingTarget target, KeyChord chord) {
        boolean conflict = ModKeyBindings.hasConflict(chord, target.action, target.hold);
        if (conflict && !(target.equals(pendingConflictTarget) && chord.equals(pendingConflictChord))) {
            pendingConflictTarget = target;
            pendingConflictChord = chord;
            status = Component.translatable("screen.simple_translate.shortcuts.conflict");
            statusUntil = System.currentTimeMillis() + 5000L;
            return;
        }
        if (target.action != null) ModKeyBindings.setChord(target.action, chord);
        else ModKeyBindings.setChord(target.hold, chord);
        Button button = keyButtons.get(target);
        if (button != null) button.setMessage(chord.displayName());
        recording = null;
        pendingConflictTarget = null;
        pendingConflictChord = null;
        status = Component.translatable("screen.simple_translate.shortcuts.saved");
        statusUntil = System.currentTimeMillis() + 2500L;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (recording != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                cancelRecording();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                assign(recording, KeyChord.NONE);
                return true;
            }
            if (KeyChord.isModifierKey(event.key())) return true;
            assign(recording, new KeyChord(KeyChord.InputType.KEYBOARD, event.key(),
                    KeyChord.currentModifiers(Minecraft.getInstance())));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (recording != null) {
            assign(recording, new KeyChord(KeyChord.InputType.MOUSE, event.button(),
                    KeyChord.currentModifiers(Minecraft.getInstance())));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (max > 0 && mouseY < contentBottom()) {
            scrollOffset = Math.max(0, Math.min(max, scrollOffset - scrollY * 24));
            reposition();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.renderPlain(graphics, width, height);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        if (maxScroll() > 0) drawScrollBar(graphics);
        graphics.fill(0, contentBottom(), width, height, 0xCC101010);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (System.currentTimeMillis() < statusUntil) {
            graphics.drawCenteredString(font, status, width / 2, height - 42, 0xFFFFAA00);
        }
    }

    private void addScrolling(AbstractWidget widget, int baseY) {
        scrolling.add(widget);
        baseYs.add(baseY);
        addRenderableWidget(widget);
    }

    private void reposition() {
        for (int i = 0; i < scrolling.size(); i++) {
            AbstractWidget widget = scrolling.get(i);
            int y = baseYs.get(i) - (int) scrollOffset;
            widget.setY(y);
            widget.visible = y >= VIEWPORT_TOP && y + widget.getHeight() <= contentBottom();
            widget.active = widget.visible && !(widget instanceof Label);
        }
    }

    private int contentBottom() { return height - BOTTOM_HEIGHT; }
    private int maxScroll() { return Math.max(0, contentHeight - (contentBottom() - VIEWPORT_TOP)); }

    private void drawScrollBar(GuiGraphics graphics) {
        int x = Math.min(width - 5, width / 2 + Math.min(180, (width - 30) / 2) + 4);
        int h = contentBottom() - VIEWPORT_TOP;
        graphics.fill(x, VIEWPORT_TOP, x + 3, contentBottom(), 0x33FFFFFF);
        int handle = Math.max(20, h * h / Math.max(h, contentHeight));
        int y = VIEWPORT_TOP + (int) ((h - handle) * (scrollOffset / maxScroll()));
        graphics.fill(x, y, x + 3, y + handle, 0xAAFFFFFF);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record BindingTarget(ShortcutAction action, HoldOriginalFeature hold) {
        static BindingTarget action(ShortcutAction action) { return new BindingTarget(action, null); }
        static BindingTarget hold(HoldOriginalFeature hold) { return new BindingTarget(null, hold); }
        Component label() { return Component.translatable(action != null ? action.translationKey() : hold.getTranslationKey()); }
        KeyChord chord() { return action != null ? action.chord() : hold.chord(); }
    }

    private static final class Label extends AbstractWidget {
        private Label(int x, int y, int width, int height, Component message) {
            super(x, y, width, height, message);
            active = false;
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawString(Minecraft.getInstance().font, getMessage(), getX(), getY() + 6, 0xFFCCCCCC);
        }
        @Override protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {}
    }
}
