package com.yourname.simpletranslate.util;

/**
 * Fabric removed the world render event suite in the 1.21.10 API line.
 * Sign context selection still works; this version skips the optional outline
 * overlay instead of adding a brittle renderer mixin.
 */
public final class SignSelectionHighlighter {
    private SignSelectionHighlighter() {
    }

    public static void register() {
        // No stable Fabric world-render callback exists for this target version.
    }
}
