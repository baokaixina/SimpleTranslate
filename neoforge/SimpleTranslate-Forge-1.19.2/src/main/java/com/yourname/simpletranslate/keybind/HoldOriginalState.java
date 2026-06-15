package com.yourname.simpletranslate.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public final class HoldOriginalState {
    private static final EnumMap<HoldOriginalFeature, Boolean> current = new EnumMap<>(HoldOriginalFeature.class);
    private static final EnumMap<HoldOriginalFeature, Boolean> previous = new EnumMap<>(HoldOriginalFeature.class);
    private static final Set<HoldOriginalFeature> STATE_SWAP_FEATURES = EnumSet.of(
            HoldOriginalFeature.CHAT,
            HoldOriginalFeature.TITLE,
            HoldOriginalFeature.ACTIONBAR);
    private static boolean registered;

    static {
        for (HoldOriginalFeature f : HoldOriginalFeature.values()) {
            current.put(f, Boolean.FALSE);
            previous.put(f, Boolean.FALSE);
        }
    }

    private HoldOriginalState() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.addListener(HoldOriginalState::onClientTick);
    }

    public static boolean isHolding(HoldOriginalFeature feature) {
        if (!ModConfig.HOLD_ORIGINAL_ENABLED.get()) {
            return false;
        }
        Boolean v = current.get(feature);
        return v != null && v;
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick(Minecraft.getInstance());
    }

    private static void tick(Minecraft mc) {
        boolean enabled = ModConfig.HOLD_ORIGINAL_ENABLED.get();
        long window = mc.getWindow() != null ? mc.getWindow().getWindow() : 0L;

        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            boolean pressed = false;
            if (enabled && window != 0L) {
                int keyCode = ModConfig.getHoldOriginalKey(feature).get();
                if (keyCode > InputConstants.UNKNOWN.getValue()) {
                    try {
                        pressed = InputConstants.isKeyDown(window, keyCode);
                    } catch (Exception ignored) {
                        pressed = false;
                    }
                }
            }
            current.put(feature, pressed);
        }

        for (HoldOriginalFeature feature : STATE_SWAP_FEATURES) {
            boolean now = current.getOrDefault(feature, Boolean.FALSE);
            boolean was = previous.getOrDefault(feature, Boolean.FALSE);
            if (now != was) {
                dispatchEdge(mc, feature, now);
            }
        }

        previous.putAll(current);
    }

    private static void dispatchEdge(Minecraft mc, HoldOriginalFeature feature, boolean holding) {
        try {
            Gui gui = mc.gui;
            if (gui == null) {
                return;
            }
            switch (feature) {
                case CHAT -> {
                    ChatComponent chat = gui.getChat();
                    if (chat instanceof HoldOriginalAware aware) {
                        aware.simple_translate$onHoldOriginalChanged(feature, holding);
                    }
                }
                case TITLE, ACTIONBAR -> {
                    if (gui instanceof HoldOriginalAware aware) {
                        aware.simple_translate$onHoldOriginalChanged(feature, holding);
                    }
                }
                default -> {}
            }
        } catch (Throwable ignored) {
        }
    }
}
