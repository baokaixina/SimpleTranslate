package com.yourname.simpletranslate.forge;

import net.minecraft.client.Minecraft;
import com.yourname.simpletranslate.chat.ChatTranslationController;
import com.yourname.simpletranslate.SimpleTranslateForge1122;
import com.yourname.simpletranslate.gui.GuiTranslationController;
import com.yourname.simpletranslate.gui.SimpleTranslateScreen;
import com.yourname.simpletranslate.gui.BaseSimpleTranslateScreen;
import com.yourname.simpletranslate.translation.TranslationEngine;
import com.yourname.simpletranslate.cache.SharedCacheClient;
import com.yourname.simpletranslate.config.ModConfig;
import com.yourname.simpletranslate.keybind.HoldOriginalFeature;
import com.yourname.simpletranslate.keybind.HoldOriginalState;
import com.yourname.simpletranslate.keybind.ShortcutAction;
import com.yourname.simpletranslate.keybind.ShortcutEdgeTracker;
import com.yourname.simpletranslate.feature.sign.SignContextSelectionManager;
import com.yourname.simpletranslate.feature.tooltip.TooltipTranslationGlowRenderer;
import com.yourname.simpletranslate.feature.hud.HudTranslationController;
import com.yourname.simpletranslate.feature.hud.ScoreboardTranslationController;
import com.yourname.simpletranslate.feature.book.BookTranslationSession;
import com.yourname.simpletranslate.chat.OutgoingChatTranslator;
import com.yourname.simpletranslate.compat.FtbTextInputState;
import com.yourname.simpletranslate.transport.TokenUsageMonitor;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import net.minecraft.util.text.event.HoverEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.EnumMap;


/** Forge 1.12.2 event bridge; no guessed modern Component or loader API is used. */
public final class ForgeClientEvents {
    private static final Logger LOGGER = LogManager.getLogger("SimpleTranslate/Forge-1.12.2");
    private final TranslationEngine engine;
    private final KeyBinding openSettings = new KeyBinding(
            "key.simple_translate.open_settings", Keyboard.KEY_U,
            "key.categories.simple_translate");
    private final EnumMap<ShortcutAction, ShortcutEdgeTracker> shortcutEdges =
            new EnumMap<ShortcutAction, ShortcutEdgeTracker>(ShortcutAction.class);
    private boolean keyBindingRegistered;
    private boolean firstRunHintShown;

    public ForgeClientEvents(TranslationEngine engine) {
        this.engine = engine;
        for (ShortcutAction action : ShortcutAction.values()) shortcutEdges.put(action, new ShortcutEdgeTracker());
        SignContextSelectionManager.register();
    }

    public void registerKeyBinding() {
        if (keyBindingRegistered) return;
        ClientRegistry.registerKeyBinding(openSettings);
        com.yourname.simpletranslate.keybind.KeyChord legacy = ModConfig.consumeLegacyOpenSettingsChord();
        if (legacy.isBound()) {
            if (legacy.modifiers() != 0) {
                LOGGER.warn("Legacy open-settings shortcut used modifiers unsupported by vanilla 1.12 controls; keeping U");
            } else {
                int keyCode = legacy.type() == com.yourname.simpletranslate.keybind.KeyChord.InputType.MOUSE
                        ? legacy.code() - 100 : legacy.code();
                Minecraft minecraft = Minecraft.getMinecraft();
                if (minecraft != null && minecraft.gameSettings != null) {
                    minecraft.gameSettings.setOptionKeyBinding(openSettings, keyCode);
                } else {
                    openSettings.setKeyCode(keyCode);
                }
                KeyBinding.resetKeyBindingArrayAndHash();
            }
        }
        keyBindingRegistered = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ChatTranslationController.tick();
        HudTranslationController.tick();
        SignContextSelectionManager.tickDragSelection();
        Minecraft minecraft = Minecraft.getMinecraft();
        if(!firstRunHintShown&&minecraft.player!=null){firstRunHintShown=true;if(!engine.isConfigured())minecraft.player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation("chat.simple_translate.first_run_hint"),true);}
        HoldOriginalState.tick(minecraft);
        while (openSettings.isPressed()) {
            if (minecraft.currentScreen == null) {
                minecraft.displayGuiScreen(new SimpleTranslateScreen(null, engine));
            }
        }
        GuiTranslationController.observeScreen(minecraft.currentScreen);
        boolean editingSettings = minecraft.currentScreen instanceof BaseSimpleTranslateScreen;
        boolean editingText = hasFocusedTextInput(minecraft.currentScreen);
        for (ShortcutAction action : ShortcutAction.values()) {
            com.yourname.simpletranslate.keybind.KeyChord chord = action.chord();
            boolean primaryDown = chord.isPrimaryDown(minecraft);
            boolean edge = shortcutEdges.get(action).update(primaryDown,
                    chord.modifiers() == com.yourname.simpletranslate.keybind.KeyChord.currentModifiers());
            boolean chatHoverMouse = editingText && action == ShortcutAction.TRANSLATE_TOOLTIP
                    && chord.type() == com.yourname.simpletranslate.keybind.KeyChord.InputType.MOUSE
                    && hoveredTooltipShortcutTarget(minecraft);
            if (!edge || editingSettings || (editingText && !chatHoverMouse)) continue;
            if (minecraft.currentScreen != null) {
                // Keyboard K/V are consumed by GuiScreenEvent below. Global
                // and chat-mode shortcuts remain usable in ordinary screens;
                // sign selection/submission are world-only.
                if ((action == ShortcutAction.TRANSLATE_GUI
                        || action == ShortcutAction.TRANSLATE_TOOLTIP)
                        && chord.type() == com.yourname.simpletranslate.keybind.KeyChord.InputType.KEYBOARD) continue;
                if (action == ShortcutAction.SIGN_SELECT || action == ShortcutAction.SIGN_SUBMIT) continue;
            }
            dispatchShortcut(action, minecraft);
        }
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        SimpleTranslateForge1122.onClientWorldJoined();
        SharedCacheClient.onJoinedWorld();
    }

    /**
     * Exact Forge 1.12.2 network lifecycle event.  Flush delayed persistence
     * and invalidate every world-bound handoff before a later server join.
     */
    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        engine.flushCache();
        engine.resetRuntimeState();
        ChatTranslationController.clearRuntimeState();
        GuiTranslationController.clearRuntimeState();
        TokenUsageMonitor.clear();
        SharedCacheClient.onDisconnected();
        SignContextSelectionManager.clearSelection();
        HoldOriginalState.clear();
        TooltipTranslationGlowRenderer.clear();
        HudTranslationController.clear();
        ScoreboardTranslationController.clear();
        BookTranslationSession.clear();
        OutgoingChatTranslator.clear();
        FtbTextInputState.clear();
        SimpleTranslateForge1122.onClientWorldDisconnected();
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        if (engine.isConfigured() && engine.isSurfaceEnabled("hud")) {
            GuiTranslationController.translateHudFrame(event.getLeft(), event.getRight());
        }
        GuiTranslationController.renderHudStatus();
    }

    /**
     * 1.12.2 only records KeyBinding presses while no screen is open
     * (Minecraft#runTickKeyboard calls KeyBinding.onTick solely on the
     * currentScreen == null branch), so whole-frame GUI and manual tooltip
     * keys must be handled from the screen keyboard event
     * to work inside GUIs. Verified live 2026-07-27: without this handler the
     * K/V keys are dead in every GuiScreen.
     */
    @SubscribeEvent
    public void onScreenKeyPre(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!hasFocusedTextInput(event.getGui()) || !Keyboard.getEventKeyState()
                || Keyboard.isRepeatEvent()
                || !ModConfig.GLOBAL_ENABLED.get()) return;
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE || !hoveredTooltipShortcutTarget(Minecraft.getMinecraft())) return;
        com.yourname.simpletranslate.keybind.KeyChord chord = ShortcutAction.TRANSLATE_TOOLTIP.chord();
        if (!chord.matchesKeyboardEvent(key, Minecraft.getMinecraft())) return;
        boolean edge = shortcutEdges.get(ShortcutAction.TRANSLATE_TOOLTIP).update(true,
                chord.modifiers() == com.yourname.simpletranslate.keybind.KeyChord.currentModifiers());
        if (edge) engine.requestManualTooltip();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onScreenKey(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()
                || event.getGui() instanceof BaseSimpleTranslateScreen
                || hasFocusedTextInput(event.getGui())) return;
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE) return;
        for (ShortcutAction action : new ShortcutAction[] {
                ShortcutAction.TRANSLATE_GUI, ShortcutAction.TRANSLATE_TOOLTIP }) {
            com.yourname.simpletranslate.keybind.KeyChord chord = action.chord();
            if (!chord.matchesKeyboardEvent(key, Minecraft.getMinecraft())) continue;
            boolean edge = shortcutEdges.get(action).update(true,
                    chord.modifiers() == com.yourname.simpletranslate.keybind.KeyChord.currentModifiers());
            if (edge) dispatchShortcut(action, Minecraft.getMinecraft());
            return;
        }
    }

    @SubscribeEvent
    public void onScreenPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        GuiTranslationController.beginFrame(event.getGui());
    }

    @SubscribeEvent
    public void onScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        GuiTranslationController.endFrame(event.getGui());
        GuiTranslationController.renderStatus(event.getGui());
    }

    private void dispatchShortcut(ShortcutAction action, Minecraft minecraft) {
        switch (action) {
            case TOGGLE_GLOBAL_TRANSLATION:
                boolean enabled = !ModConfig.GLOBAL_ENABLED.get();
                ModConfig.GLOBAL_ENABLED.set(enabled);
                ModConfig.save();
                SimpleTranslateForge1122.onGlobalTranslationSettingChanged(enabled);
                if (minecraft.player != null) minecraft.player.sendStatusMessage(
                        new net.minecraft.util.text.TextComponentTranslation(enabled
                                ? "screen.simple_translate.global_toggle.enabled"
                                : "screen.simple_translate.global_toggle.disabled"), true);
                break;
            case TOGGLE_CHAT_MODE:
                if (!ModConfig.GLOBAL_ENABLED.get()) break;
                boolean auto = !engine.isChatAutoMode();
                engine.setChatAutoMode(auto);
                SimpleTranslateForge1122.onTranslationSettingsChanged();
                if (minecraft.player != null) {
                    net.minecraft.util.text.ITextComponent mode = new net.minecraft.util.text.TextComponentTranslation(
                            auto ? "screen.simple_translate.mode.auto" : "screen.simple_translate.mode.button");
                    minecraft.player.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(
                            "screen.simple_translate.mode.toggle_message", mode), true);
                }
                break;
            case TRANSLATE_GUI:
                if (minecraft.currentScreen != null) {
                    GuiTranslationController.toggle(minecraft.currentScreen);
                } else {
                    GuiTranslationController.requestHudTranslation();
                }
                break;
            case TRANSLATE_TOOLTIP:
                engine.requestManualTooltip();
                break;
            case SIGN_SELECT:
                SignContextSelectionManager.toggleDragSelectionMode();
                break;
            case SIGN_SUBMIT:
                SignContextSelectionManager.submitSelection();
                break;
            default:
                break;
        }
    }

    private static boolean hasFocusedTextInput(GuiScreen screen) {
        if (FtbTextInputState.hasFocused()) return true;
        if (screen == null) return false;
        if (screen instanceof GuiChat) return true;
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try { fields = type.getDeclaredFields(); }
            catch (Throwable ignored) { continue; }
            for (java.lang.reflect.Field field : fields) {
                if (!GuiTextField.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    GuiTextField textField = (GuiTextField) field.get(screen);
                    if (textField != null && textField.isFocused()) return true;
                } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    private static boolean hoveredTooltipShortcutTarget(Minecraft minecraft) {
        if (minecraft == null || minecraft.currentScreen == null) return false;
        if (minecraft.currentScreen instanceof GuiChat && minecraft.ingameGUI != null) {
            ITextComponent component = minecraft.ingameGUI.getChatGUI().getChatComponent(Mouse.getX(), Mouse.getY());
            if (component != null && component.getStyle() != null) {
                HoverEvent hover = component.getStyle().getHoverEvent();
                if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
                    return ModConfig.TOOLTIP_CHAT_HOVER_ENABLED.get()
                            && ModConfig.TOOLTIP_CHAT_HOVER_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT;
                }
                if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_ITEM) {
                    return ModConfig.TOOLTIP_ITEM_ENABLED.get()
                            && ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT;
                }
            }
        }
        if (minecraft.currentScreen instanceof GuiContainer
                && ModConfig.TOOLTIP_ITEM_ENABLED.get()
                && ModConfig.TOOLTIP_ITEM_TRIGGER_MODE.get() == ModConfig.TooltipTriggerMode.SHORTCUT) {
            Slot slot = ((GuiContainer) minecraft.currentScreen).getSlotUnderMouse();
            return slot != null && slot.getHasStack() && slot.getStack() != null && !slot.getStack().isEmpty();
        }
        return false;
    }

}
