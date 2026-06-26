package com.yourname.simpletranslate.core;

import com.yourname.simpletranslate.SimpleTranslateMod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MixinRuntimeProbe {
    private static final Set<String> MATCHED = ConcurrentHashMap.newKeySet();

    private MixinRuntimeProbe() {
    }

    public static void matched(String hook) {
        if (hook != null && !hook.isBlank() && MATCHED.add(hook)) {
            SimpleTranslateMod.getLogger().debug("SimpleTranslate mixin hook active: {}", hook);
        }
    }
}
