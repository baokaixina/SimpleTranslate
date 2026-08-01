package com.yourname.simpletranslate.keybind;

import com.yourname.simpletranslate.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public final class HoldOriginalState {
    private static final EnumMap<HoldOriginalFeature, Boolean> current = new EnumMap<>(HoldOriginalFeature.class);
    private static final EnumMap<HoldOriginalFeature, Boolean> previous = new EnumMap<>(HoldOriginalFeature.class);
    private static final Set<HoldOriginalFeature> STATE_SWAP_FEATURES = EnumSet.of(
            HoldOriginalFeature.CHAT,
            HoldOriginalFeature.TOOLTIP_ITEM,
            HoldOriginalFeature.TOOLTIP_HOVER,
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
        NeoForge.EVENT_BUS.addListener(HoldOriginalState::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        tick(Minecraft.getInstance());
    }

    public static boolean isHolding(HoldOriginalFeature feature) {
        if (!ModConfig.HOLD_ORIGINAL_ENABLED.get()) {
            return false;
        }
        Boolean v = current.get(feature);
        return v != null && v;
    }

    private static void tick(Minecraft mc) {
        boolean enabled = ModConfig.HOLD_ORIGINAL_ENABLED.get();
        for (HoldOriginalFeature feature : HoldOriginalFeature.values()) {
            boolean pressed = enabled && feature.chord().isDown(mc);
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
                case TOOLTIP_ITEM, TOOLTIP_HOVER -> {
                    // Holding original is a view-only override. Tooltip render/cache
                    // state must survive so releasing the key can immediately show
                    // the cached translation again.
                }
                default -> {}
            }
        } catch (Throwable ignored) {
        }
    }
}
